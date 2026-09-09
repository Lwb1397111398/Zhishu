package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.pipeline.PipelineService
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.data.local.repo.DefaultChapterRepository
import com.example.zhishu.data.local.repo.DefaultCitationRepository
import com.example.zhishu.data.local.repo.DefaultKnowledgeRepository
import com.example.zhishu.data.local.repo.DefaultProjectRepository
import com.example.zhishu.data.local.repo.DefaultQuizRepository
import com.example.zhishu.data.local.repo.DefaultReviewRepository
import com.example.zhishu.data.local.repo.DefaultSourceRepository
import com.example.zhishu.data.local.repo.DefaultStoryBibleRepository
import com.example.zhishu.data.local.repo.DefaultVerificationIssueRepository
import com.example.zhishu.domain.model.GapStatus
import com.example.zhishu.domain.model.GapType
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeMapBatch
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 审计完成后自动补「漏掉的知识点」（自动化闭环 Task 3）。
 *
 * 三条性质，各自守一种翻车：
 * 1. 只补 actionable（MISSING_NODE）——免责提示型（地区差异/过时）绝不能被自动关掉；
 * 2. [limit] 生效——补一条 = 一次模型调用，成本护栏；
 * 3. 补上的走既有 generateKnowledgeMapPatch（幂等、同名节点不重烧调用）。
 *
 * router 用测试包里现成的 internal VolumeRouter：它的 [[MAP_CHAIN]] 分支会从补节点
 * prompt 里解析 (nodeKey, 标题) 并回传带 definition 的节点，补节点调用因此能成功。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoResolveGapsTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService
    private val knowledge by lazy {
        DefaultKnowledgeRepository(
            db = db,
            dao = db.knowledgeNodeDao(),
            gapDao = db.knowledgeGapDao(),
            conflictDao = db.knowledgeConflictDao(),
            batchDao = db.knowledgeMapBatchDao(),
            routeBatchDao = db.routeBatchDao(),
        )
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        pipeline = PipelineService(
            router = VolumeRouter(),
            prompts = VolumePromptProvider(),
            projectRepository = DefaultProjectRepository(db.projectDao()),
            sourceRepository = DefaultSourceRepository(db.sourceDocumentDao(), db.sourceChunkDao()),
            knowledgeRepository = knowledge,
            chapterRepository = DefaultChapterRepository(db.chapterPlanDao(), db.chapterBlockDao(), db.chapterVersionDao()),
            citationRepository = DefaultCitationRepository(db.citationDao()),
            issueRepository = DefaultVerificationIssueRepository(db.verificationIssueDao()),
            storyBibleRepository = DefaultStoryBibleRepository(db.storyBibleDao()),
            quizRepository = DefaultQuizRepository(db.quizDao(), db.answerRecordDao()),
            reviewRepository = DefaultReviewRepository(db.reviewItemDao()),
        )
        runBlocking {
            DefaultProjectRepository(db.projectDao()).create(
                Project(
                    id = "p-1", title = "项目", topic = "民法", baseline = "入门", depth = "入门",
                    dailyMinutes = 20, targetDays = 30, genre = "悬疑",
                    sourceMode = SourceMode.MODEL,
                    status = ProjectStatus.MAP, createdAt = 1L, updatedAt = 1L,
                ),
            )
            // generateKnowledgeMapPatch 要求有已完成批次（取 latest batch 挂节点）
            knowledge.saveBatch(
                KnowledgeMapBatch(
                    id = "b-1", projectId = "p-1", createdAt = 1L, updatedAt = 1L,
                    completed = true, active = true, skeletonDone = true,
                ),
            )
        }
    }

    @After
    fun tearDown() { db.close() }

    private fun gap(id: String, type: GapType, title: String) = KnowledgeGap(
        id = id, projectId = "p-1", description = "缺：$title", suggestedTitle = title,
        gapType = type.name, status = GapStatus.OPEN.name,
    )

    @Test
    fun resolvesActionableGaps_upToLimit_andNeverTouchesNonActionable() = runBlocking {
        knowledge.replaceInsights(
            "p-1",
            gaps = listOf(
                gap("g1", GapType.MISSING_NODE, "善意取得"),
                gap("g2", GapType.MISSING_NODE, "不当得利"),
                gap("g3", GapType.REGIONAL, "某条已废止，地区有差异"),   // 非 actionable，永不自动补
            ),
            conflicts = emptyList(),
        )

        val resolved = pipeline.autoResolveGaps("p-1", limit = 1)   // 上限 1

        assertEquals(1, resolved)
        val after = knowledge.getGaps("p-1").associate { it.id to it.gapStatus }
        // 有且仅有一条 MISSING_NODE 被补上（不假设哪条——getGaps 顺序不由本测试约定）
        val actionable = after.filterKeys { it != "g3" }
        assertEquals(1, actionable.values.count { it == GapStatus.RESOLVED })
        assertEquals(1, actionable.values.count { it == GapStatus.OPEN })
        // 免责提示型绝不被自动关，仍是 OPEN
        assertEquals(GapStatus.OPEN, after["g3"])
    }

    @Test
    fun noActionableGaps_resolvesNothing() = runBlocking {
        knowledge.replaceInsights(
            "p-1",
            gaps = listOf(gap("g3", GapType.OUTDATED, "内容可能过时")),
            conflicts = emptyList(),
        )
        assertEquals(0, pipeline.autoResolveGaps("p-1"))
        assertEquals(GapStatus.OPEN, knowledge.getGaps("p-1").first().gapStatus)
    }

    @Test
    fun limitBelowTargets_leavesRestOpenForNextRun() = runBlocking {
        knowledge.replaceInsights(
            "p-1",
            gaps = listOf(
                gap("g1", GapType.MISSING_NODE, "善意取得"),
                gap("g2", GapType.MISSING_NODE, "不当得利"),
                gap("g4", GapType.MISSING_NODE, "无因管理"),
            ),
            conflicts = emptyList(),
        )
        assertEquals(2, pipeline.autoResolveGaps("p-1", limit = 2))
        val open = knowledge.getGaps("p-1").count { it.gapStatus == GapStatus.OPEN }
        // 三条 actionable，补 2 条 → 剩 1 条 OPEN，下次点继续再补
        assertEquals(1, open)
    }
}
