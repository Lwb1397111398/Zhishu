package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.pipeline.NextVolumeAvailability
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
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分卷与按需续讲（第三阶段 3C）。
 *
 * 两条底线：
 * 1. 一次只规划一卷 4~8 章，不让模型一次想出整个主题的所有章节；
 * 2. 续卷只往后加，已发布章节一个字不改、一块正文不删。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VolumePlanningTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService
    private lateinit var router: VolumeRouter
    private var ids = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        router = VolumeRouter()
        ids = 0
        pipeline = PipelineService(
            router = router,
            prompts = VolumePromptProvider(),
            projectRepository = DefaultProjectRepository(db.projectDao()),
            sourceRepository = DefaultSourceRepository(db.sourceDocumentDao(), db.sourceChunkDao()),
            knowledgeRepository = DefaultKnowledgeRepository(
                dao = db.knowledgeNodeDao(),
                gapDao = db.knowledgeGapDao(),
                conflictDao = db.knowledgeConflictDao(),
            ),
            chapterRepository = DefaultChapterRepository(
                db.chapterPlanDao(),
                db.chapterBlockDao(),
                db.chapterVersionDao(),
            ),
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

    /** 要求 1、2：首次规划只创建一卷，且不超过卷内章数上限 */
    @Test
    fun firstPlanning_createsOneVolumeWithinChapterCap() = runBlocking {
        seedMap()
        pipeline.generateStoryBible("p-1").getOrThrow()

        val plans = db.chapterPlanDao().getByProject("p-1")
        assertEquals(setOf(0), plans.map { it.volumeIndex }.toSet())
        assertTrue("卷内章数不能超过 8：${plans.size}", plans.size <= 8)
        assertEquals(0, db.storyBibleDao().get("p-1")?.currentVolumeIndex)
    }

    /** 模型一口气给了 20 章也只收前 8 章，剩下的留给续卷 */
    @Test
    fun firstPlanning_trimsOversizedPlanToVolumeCap() = runBlocking {
        seedMap()
        router.bibleChapterCount = 20
        pipeline.generateStoryBible("p-1").getOrThrow()

        assertEquals(8, db.chapterPlanDao().getByProject("p-1").size)
    }

    /** 要求 7：当前卷没写完就不允许续卷，故事顺序不能被打乱 */
    @Test
    fun nextVolume_blockedWhileCurrentVolumeUnfinished() = runBlocking {
        seedPlannedVolume()
        val availability = pipeline.nextVolumeAvailability("p-1")
        assertTrue(availability is NextVolumeAvailability.CurrentVolumeUnfinished)

        val result = pipeline.generateNextVolume("p-1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("没写完"))
        // 一个字都没写进去
        assertEquals(setOf(0), db.chapterPlanDao().getByProject("p-1").map { it.volumeIndex }.toSet())
    }

    /** 要求 3、4、8：当前卷读完且还有没讲到的知识点 → 可以续卷，索引递增，旧章节一个不删 */
    @Test
    fun nextVolume_appendsWithoutTouchingPublishedChapters() = runBlocking {
        val firstVolume = publishWholeFirstVolume()
        assertTrue(pipeline.nextVolumeAvailability("p-1") is NextVolumeAvailability.CanContinue)

        assertTrue(pipeline.generateNextVolume("p-1").isSuccess)

        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        // 旧章节原样还在，状态与正文都没动
        firstVolume.forEach { old ->
            val kept = plans.first { it.id == old.id }
            assertEquals(ChapterStatus.PUBLISHED, kept.status)
            assertEquals(old.activeVersionId, kept.activeVersionId)
            assertTrue(db.chapterBlockDao().getActiveBlocks(old.id).isNotEmpty())
        }
        // 新卷索引递增，order 接在旧章节之后且不冲突
        val added = plans.filter { it.volumeIndex == 1 }
        assertTrue(added.isNotEmpty())
        assertEquals(1, db.storyBibleDao().get("p-1")?.currentVolumeIndex)
        assertTrue(added.all { new -> firstVolume.all { it.order < new.order } })
        assertEquals(plans.size, plans.map { it.order }.distinct().size)
        assertEquals(1, db.storyBibleDao().get("p-1")?.currentVolumeIndex)
    }

    /** 要求 6：已发布章节不会被再规划一遍 */
    @Test
    fun nextVolume_doesNotReplanPublishedChapters() = runBlocking {
        val firstVolume = publishWholeFirstVolume()
        assertTrue(pipeline.generateNextVolume("p-1").isSuccess)

        val plans = db.chapterPlanDao().getByProject("p-1")
        // 章节 ID 只增不改
        assertTrue(firstVolume.all { old -> plans.any { it.id == old.id } })
        assertEquals(firstVolume.size + 2, plans.size)
        // 新章节讲的是还没讲过的知识点
        val added = plans.filter { it.volumeIndex == 1 }
        val firstVolumeNodeIds = firstVolume.flatMap { it.nodeIds }.toSet()
        assertTrue(added.flatMap { it.nodeIds }.none { it in firstVolumeNodeIds })
    }

    /** 要求 5：续卷失败不写入半成品计划 */
    @Test
    fun nextVolume_failure_writesNothing() = runBlocking {
        val firstVolume = publishWholeFirstVolume()
        router.failNextVolume = true

        assertTrue(pipeline.generateNextVolume("p-1").isFailure)
        assertEquals(firstVolume.size, db.chapterPlanDao().getByProject("p-1").size)
        assertEquals(0, db.storyBibleDao().get("p-1")?.currentVolumeIndex)
    }

    /** 续卷全是讲过的知识点 → 拒绝写入，不白花钱重复讲 */
    @Test
    fun nextVolume_repeatingCoveredNodesOnly_isRejected() = runBlocking {
        val firstVolume = publishWholeFirstVolume()
        router.nextVolumeNodeTitles = listOf("继承")

        val result = pipeline.generateNextVolume("p-1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("没有推进主线"))
        assertEquals(firstVolume.size, db.chapterPlanDao().getByProject("p-1").size)
    }

    /** 要求 9：没有未覆盖节点时不显示「继续主线」，改为扩展阅读 */
    @Test
    fun allNodesCovered_showsExtendedReadingNotMainLine() = runBlocking {
        publishWholeFirstVolume()
        // 把剩下的知识点也安排掉
        assertTrue(pipeline.generateNextVolume("p-1").isSuccess)
        db.chapterPlanDao().getByProject("p-1")
            .filter { it.volumeIndex == 1 }
            .forEach { publish(it.id) }

        assertEquals(NextVolumeAvailability.MainLineDone, pipeline.nextVolumeAvailability("p-1"))
    }

    /** 要求 10：续卷上下文不含历史正文，只有有限的几段状态 */
    @Test
    fun nextVolume_contextExcludesChapterBodies() = runBlocking {
        publishWholeFirstVolume()
        assertTrue(pipeline.generateNextVolume("p-1").isSuccess)

        val prompt = router.lastNextVolumePrompt
        assertFalse("续卷提示词里出现了正文原文", prompt.contains("老人立下遗嘱，子女各怀心思"))
        assertTrue(prompt.contains("累积状态"))
        assertTrue(prompt.contains("上一章结束时的处境"))
    }

    /** 要求 11：新卷第一章接的是上一卷最后一章当前版本的结尾 */
    @Test
    fun nextVolume_contextUsesLastChapterActiveEndingState() = runBlocking {
        val firstVolume = publishWholeFirstVolume()
        val lastEnding = db.chapterVersionDao()
            .getActive(firstVolume.maxBy { it.order }.id)?.endingState
        assertTrue(lastEnding.orEmpty().isNotBlank())

        assertTrue(pipeline.generateNextVolume("p-1").isSuccess)
        assertTrue(router.lastNextVolumePrompt.contains(lastEnding.orEmpty()))
    }

    /** 有已发布章节时不允许整本重新规划——这条路会删掉读过的内容 */
    @Test
    fun replanWholeBook_isRefusedWhenSomethingIsPublished() = runBlocking {
        val firstVolume = publishWholeFirstVolume()
        val blocksBefore = db.chapterBlockDao().countByProject("p-1")

        val result = pipeline.generateStoryBible("p-1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("继续下一卷"))
        // 章节、正文、版本一个都没少
        assertEquals(firstVolume.size, db.chapterPlanDao().getByProject("p-1").size)
        assertEquals(blocksBefore, db.chapterBlockDao().countByProject("p-1"))
        assertTrue(firstVolume.all { db.chapterBlockDao().getActiveBlocks(it.id).isNotEmpty() })
    }

    /** 一个字都还没写时重新规划是安全的：清掉的只是没开始的计划 */
    @Test
    fun replanBeforeAnythingPublished_isAllowed() = runBlocking {
        seedPlannedVolume()
        val before = db.chapterPlanDao().getByProject("p-1").map { it.id }.toSet()

        assertTrue(pipeline.generateStoryBible("p-1").isSuccess)
        val after = db.chapterPlanDao().getByProject("p-1").map { it.id }.toSet()
        assertTrue(after.none { it in before })
    }

    // --- 夹具 ---

    private suspend fun seedMap() {
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
                    text = "继承、遗产范围、遗嘱继承、法定继承、遗赠扶养协议。",
                ),
            ),
        )
        pipeline.generateKnowledgeMap("p-1").getOrThrow()
    }

    private suspend fun seedPlannedVolume() {
        seedMap()
        pipeline.generateStoryBible("p-1").getOrThrow()
    }

    /** 规划第一卷并把它整卷写完 */
    private suspend fun publishWholeFirstVolume(): List<com.example.zhishu.domain.model.ChapterPlan> {
        seedPlannedVolume()
        db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }.forEach { publish(it.id) }
        return db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
    }

    private suspend fun publish(planId: String) {
        pipeline.generateChapter("p-1", planId).getOrThrow()
    }
}
