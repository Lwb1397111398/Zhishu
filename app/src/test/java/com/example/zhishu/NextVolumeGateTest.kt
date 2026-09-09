package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.pipeline.NextVolumeAvailability
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
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 续卷入口在「有章带问题发布 / 有章失败」时不再被永久堵死（自动化闭环 Task 2）。
 *
 * 这两条正是用户「老是在第一卷」的直接复现：只要有一章停在待复核，
 * 改造前 nextVolumeAvailability 恒返回 CurrentVolumeUnfinished，按钮再也不出现。
 *
 * 判据是 isVolumeBlocking（Task 1）：只有「还在生成流程里」的章才阻断续卷。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NextVolumeGateTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        // 本任务只测 nextVolumeAvailability（纯查库），
        // router 用测试包里现成的 internal VolumeRouter / VolumePromptProvider（见 VolumeRouter.kt）
        pipeline = PipelineService(
            router = VolumeRouter(),
            prompts = VolumePromptProvider(),
            projectRepository = DefaultProjectRepository(db.projectDao()),
            sourceRepository = DefaultSourceRepository(db.sourceDocumentDao(), db.sourceChunkDao()),
            knowledgeRepository = DefaultKnowledgeRepository(
                db = db,
                dao = db.knowledgeNodeDao(),
                gapDao = db.knowledgeGapDao(),
                conflictDao = db.knowledgeConflictDao(),
                batchDao = db.knowledgeMapBatchDao(),
                routeBatchDao = db.routeBatchDao(),
            ),
            chapterRepository = DefaultChapterRepository(db.chapterPlanDao(), db.chapterBlockDao(), db.chapterVersionDao()),
            citationRepository = DefaultCitationRepository(db.citationDao()),
            issueRepository = DefaultVerificationIssueRepository(db.verificationIssueDao()),
            storyBibleRepository = DefaultStoryBibleRepository(db.storyBibleDao()),
            quizRepository = DefaultQuizRepository(db.quizDao(), db.answerRecordDao()),
            reviewRepository = DefaultReviewRepository(db.reviewItemDao()),
        )
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun seedPlans(vararg pairs: Pair<String, ChapterStatus>) {
        DefaultProjectRepository(db.projectDao()).create(
            Project(
                id = "p-1", title = "项目", topic = "民法", baseline = "入门", depth = "入门",
                dailyMinutes = 20, targetDays = 30, genre = "悬疑",
                status = ProjectStatus.OUTLINE, createdAt = 1L, updatedAt = 1L,
            ),
        )
        DefaultChapterRepository(db.chapterPlanDao(), db.chapterBlockDao(), db.chapterVersionDao())
            .upsertPlans(pairs.mapIndexed { index, (id, status) ->
                ChapterPlan(
                    id = id, projectId = "p-1", order = index + 1,
                    title = "第 ${index + 1} 章", status = status, volumeIndex = 0,
                )
            })
    }

    @Test
    fun needsReviewChapter_noLongerBlocksNextVolume() = runBlocking {
        // 没有知识节点 → pending 为空 → 放行后落点应是 MainLineDone（不是 CurrentVolumeUnfinished）
        seedPlans("c1" to ChapterStatus.PUBLISHED, "c2" to ChapterStatus.NEEDS_REVIEW)
        assertEquals(
            NextVolumeAvailability.MainLineDone,
            pipeline.nextVolumeAvailability("p-1"),
        )
    }

    @Test
    fun failedChapter_noLongerBlocksNextVolume() = runBlocking {
        seedPlans("c1" to ChapterStatus.PUBLISHED, "c2" to ChapterStatus.FAILED)
        assertEquals(
            NextVolumeAvailability.MainLineDone,
            pipeline.nextVolumeAvailability("p-1"),
        )
    }

    @Test
    fun stillGeneratingChapter_doesBlockNextVolume() = runBlocking {
        seedPlans("c1" to ChapterStatus.PUBLISHED, "c2" to ChapterStatus.QUEUED)
        assertEquals(
            NextVolumeAvailability.CurrentVolumeUnfinished(remaining = 1),
            pipeline.nextVolumeAvailability("p-1"),
        )
    }
}
