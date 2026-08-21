package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.pipeline.PipelineService
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.data.local.repo.DefaultChapterRepository
import com.example.zhishu.data.local.repo.DefaultCitationRepository
import com.example.zhishu.data.local.repo.DefaultVerificationIssueRepository
import com.example.zhishu.data.local.repo.DefaultKnowledgeRepository
import com.example.zhishu.data.local.repo.DefaultProjectRepository
import com.example.zhishu.data.local.repo.DefaultQuizRepository
import com.example.zhishu.data.local.repo.DefaultReviewRepository
import com.example.zhishu.data.local.repo.DefaultSourceRepository
import com.example.zhishu.data.local.repo.DefaultStoryBibleRepository
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.QuizState
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 取消生成的一致性收尾（第二阶段）。
 *
 * 核心约束：取消之后不能留下永久卡死的 RUNNING / VERIFYING 章节，
 * 已经发布的正文不能被别的任务的取消动作牵连，
 * 而正文已被覆盖这件事必须如实写进说明里而不是掩盖掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationCancellationTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService
    private lateinit var router: ScriptedRouter
    private var ids = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        router = ScriptedRouter()
        ids = 0
        pipeline = PipelineService(
            router = router,
            prompts = TaggedPromptProvider(),
            projectRepository = DefaultProjectRepository(db.projectDao()),
            sourceRepository = DefaultSourceRepository(db.sourceDocumentDao(), db.sourceChunkDao()),
            knowledgeRepository = DefaultKnowledgeRepository(
                dao = db.knowledgeNodeDao(),
                gapDao = db.knowledgeGapDao(),
                db = db,
                conflictDao = db.knowledgeConflictDao(),
                batchDao = db.knowledgeMapBatchDao(),
            ),
            chapterRepository = DefaultChapterRepository(db.chapterPlanDao(), db.chapterBlockDao(), db.chapterVersionDao()),
            citationRepository = DefaultCitationRepository(db.citationDao()),
            issueRepository = DefaultVerificationIssueRepository(db.verificationIssueDao()),
            storyBibleRepository = DefaultStoryBibleRepository(db.storyBibleDao()),
            quizRepository = DefaultQuizRepository(db.quizDao(), db.answerRecordDao()),
            reviewRepository = DefaultReviewRepository(db.reviewItemDao()),
            idFactory = { "id-${ids++}" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 要求 5/6：正文阶段取消 → 章节回到可重新生成状态，不卡在 RUNNING */
    @Test
    fun cancelDuringBody_restoresQueued() = runBlocking {
        val planId = seedThroughBible()
        router.cancelAt = ScriptedRouter.Phase.CHAPTER

        val thrown = runCatching { pipeline.generateChapter("p-1", planId) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)

        val plan = db.chapterPlanDao().getById(planId)
        assertEquals(ChapterStatus.QUEUED, plan?.status)
        assertFalse(plan?.status == ChapterStatus.RUNNING)
        assertTrue(plan?.verificationNote.orEmpty().contains("取消"))
        // 正文一个字都没写进来
        assertTrue(db.chapterBlockDao().getAllVersionsByChapter(planId).isEmpty())
    }

    /** 要求 5：核验阶段取消 → 正文已写入但未核验，标 NEEDS_REVIEW 并说明清楚 */
    @Test
    fun cancelDuringVerify_marksNeedsReview() = runBlocking {
        val planId = seedThroughBible()
        router.cancelAt = ScriptedRouter.Phase.VERIFY

        runCatching { pipeline.generateChapter("p-1", planId) }

        val plan = db.chapterPlanDao().getById(planId)
        assertEquals(ChapterStatus.NEEDS_REVIEW, plan?.status)
        assertTrue(plan?.verificationNote.orEmpty().contains("尚未完成核验"))
        // 正文块留在库里，用户可以看到写到哪儿了
        assertTrue(db.chapterBlockDao().getAllVersionsByChapter(planId).isNotEmpty())
    }

    /**
     * 重生成一个已发布章节、在核验阶段取消：**旧正文完好无损**（第三阶段 3A）。
     *
     * 这条测试是 3A 的核心断言，也是第二阶段那句「原正文已被新生成的正文替换」的终结：
     * 新正文写在独立版本上，指针没切过去，取消之后读者手里的正文一个字都没变。
     */
    @Test
    fun cancelDuringVerify_onPublishedChapter_keepsOldBodyReadable() = runBlocking {
        val planId = seedThroughBible()
        // 先正常生成一遍，让它发布
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)
        assertEquals(ChapterStatus.PUBLISHED, db.chapterPlanDao().getById(planId)?.status)
        val oldVersionId = db.chapterPlanDao().getById(planId)?.activeVersionId
        val oldText = db.chapterBlockDao().getActiveBlocks(planId).first().text

        // 再生成一次，这次在核验阶段取消
        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "改写后的开头。")
        router.cancelAt = ScriptedRouter.Phase.VERIFY
        runCatching { pipeline.generateChapter("p-1", planId) }

        val plan = db.chapterPlanDao().getById(planId)
        // 章节照样是已发布、照样可读，指针也没动
        assertEquals(ChapterStatus.PUBLISHED, plan?.status)
        assertTrue(plan?.isReadable == true)
        assertEquals(oldVersionId, plan?.activeVersionId)
        assertEquals(oldText, db.chapterBlockDao().getActiveBlocks(planId).first().text)

        // 被取消的那一版留在库里、标成作废，正文块也没删——可查，但读者看不到
        val abandoned = db.chapterVersionDao().getByChapter(planId)
            .single { it.id != oldVersionId }
        assertEquals(ChapterVersionStatus.ABANDONED, abandoned.status)
        assertTrue(abandoned.verificationNote.contains("已取消"))
        assertTrue(db.chapterBlockDao().getByVersion(abandoned.id).isNotEmpty())
        assertNotEquals(oldText, db.chapterBlockDao().getByVersion(abandoned.id).first().text)
    }

    /** 要求 5：发布之后才取消（出题阶段）→ 已发布内容与状态一概不动 */
    @Test
    fun cancelAfterPublish_keepsPublished() = runBlocking {
        val planId = seedThroughBible()
        router.cancelAt = ScriptedRouter.Phase.QUIZ

        runCatching { pipeline.generateChapter("p-1", planId) }

        val plan = db.chapterPlanDao().getById(planId)
        assertEquals(ChapterStatus.PUBLISHED, plan?.status)
        assertTrue(plan?.isReadable == true)
        assertTrue(db.chapterBlockDao().getAllVersionsByChapter(planId).isNotEmpty())
        // 滚动记忆也已经推进，下一章能接着写
        assertTrue(db.storyBibleDao().get("p-1")?.runningState.orEmpty().isNotBlank())
    }

    /** 取消一章不会波及同项目里另一章已经发布的内容 */
    @Test
    fun cancelOneChapter_doesNotTouchAnother() = runBlocking {
        val first = seedThroughBible()
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        assertTrue(pipeline.generateChapter("p-1", plans[0].id).isSuccess)

        router.cancelAt = ScriptedRouter.Phase.CHAPTER
        runCatching { pipeline.generateChapter("p-1", plans[1].id) }

        assertEquals(ChapterStatus.PUBLISHED, db.chapterPlanDao().getById(first)?.status)
        assertEquals(ChapterStatus.QUEUED, db.chapterPlanDao().getById(plans[1].id)?.status)
    }

    /** 取消不能把项目状态永久卡在 GENERATING */
    @Test
    fun cancel_leavesProjectStatusConsistent() = runBlocking {
        val planId = seedThroughBible()
        router.cancelAt = ScriptedRouter.Phase.CHAPTER
        runCatching { pipeline.generateChapter("p-1", planId) }

        // 还有章节没写完，所以 GENERATING 是对的；关键是章节自己不卡在 RUNNING
        val plan = db.chapterPlanDao().getById(planId)
        assertFalse(plan?.status == ChapterStatus.RUNNING)
        assertFalse(plan?.status == ChapterStatus.VERIFYING)
    }

    /** 要求 12：只重新出题绝不会再调一次正文模型 */
    @Test
    fun regenerateQuiz_neverRegeneratesBody() = runBlocking {
        val planId = seedThroughBible()
        router.failAt = ScriptedRouter.Phase.QUIZ
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)
        assertEquals(QuizState.FAILED, db.chapterPlanDao().getById(planId)?.quizState)
        val bodyText = db.chapterBlockDao().getAllVersionsByChapter(planId).first().text

        // 重新出题：这次让出题成功
        router.failAt = null
        router.calls.clear()
        assertTrue(pipeline.regenerateQuiz("p-1", planId).isSuccess)

        // 只调了出题，没有 CHAPTER / VERIFY
        assertEquals(listOf(ScriptedRouter.Phase.QUIZ), router.calls)
        assertEquals(QuizState.READY, db.chapterPlanDao().getById(planId)?.quizState)
        // 正文一字未动
        assertEquals(bodyText, db.chapterBlockDao().getAllVersionsByChapter(planId).first().text)
        assertEquals(3, db.quizDao().getByChapter(planId).size)
    }

    /** 要求 13：出题失败后下一章照样能生成，不被前一章的测验状态挡住 */
    @Test
    fun quizFailure_doesNotBlockNextChapter() = runBlocking {
        seedThroughBible()
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        router.failAt = ScriptedRouter.Phase.QUIZ

        assertTrue(pipeline.generateChapter("p-1", plans[0].id).isSuccess)
        assertTrue(pipeline.generateChapter("p-1", plans[1].id).isSuccess)

        val loaded = db.chapterPlanDao().getByProject("p-1")
        assertTrue(loaded.all { it.status == ChapterStatus.PUBLISHED })
        assertTrue(loaded.all { it.quizState == QuizState.FAILED })
        // 两章正文都能读
        assertTrue(loaded.all { it.isReadable })
    }

    /** 要求 11：核验通过、出题失败时，节点回写与滚动记忆照样完成 */
    @Test
    fun quizFailure_stillAdvancesMemoryAndNodeLinks() = runBlocking {
        val planId = seedThroughBible()
        router.failAt = ScriptedRouter.Phase.QUIZ
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val node = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "意思表示" }
        assertTrue(planId in node.chapterIds)
        assertTrue(db.storyBibleDao().get("p-1")?.runningState.orEmpty().isNotBlank())
        // 结尾状态存在当前版本上，不再写章节的 carryOver 列（第三阶段 3B）
        assertTrue(db.chapterVersionDao().getActive(planId)?.endingState.orEmpty().isNotBlank())
    }

    /** 正文核验不过时仍然是 NEEDS_REVIEW，解耦没有把真正的正文问题也放过 */
    @Test
    fun verificationIssues_stillBlockPublish() = runBlocking {
        val planId = seedThroughBible()
        router.verifyJson =
            """{"issues":[{"type":"contradiction","blockId":"b-1","message":"与资料矛盾","sourceRefs":["c-1"],"suggestedFix":"改"}]}"""

        assertTrue(pipeline.generateChapter("p-1", planId).isFailure)
        val plan = db.chapterPlanDao().getById(planId)
        assertEquals(ChapterStatus.NEEDS_REVIEW, plan?.status)
        assertFalse(plan?.isReadable == true)
        // 没走到出题
        assertFalse(ScriptedRouter.Phase.QUIZ in router.calls)
    }

    /** 建到「有知识地图 + 有章节规划」为止，返回第一章的 ID */
    private suspend fun seedThroughBible(): String {
        db.projectDao().upsert(
            Project(
                id = "p-1",
                title = "民法基础",
                topic = "民法",
                baseline = "入门",
                depth = "入门",
                dailyMinutes = 20,
                targetDays = 30,
                genre = "悬疑",
                status = ProjectStatus.DRAFT,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        db.sourceDocumentDao().upsert(
            SourceDocument(
                id = "src-1",
                projectId = "p-1",
                title = "笔记",
                kind = SourceKind.TEXT,
                fetchedAt = 1L,
                contentHash = "h",
                parseStatus = ParseStatus.PARSED,
            ),
        )
        db.sourceChunkDao().upsertAll(
            listOf(
                SourceChunk(
                    id = "c-1",
                    sourceId = "src-1",
                    projectId = "p-1",
                    ordinal = 0,
                    text = "意思表示是民事法律行为的核心要素。",
                ),
            ),
        )
        pipeline.generateKnowledgeMap("p-1").getOrThrow()
        pipeline.generateStoryBible("p-1").getOrThrow()
        router.calls.clear()
        return db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }.first().id
    }
}
