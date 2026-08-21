package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.pipeline.PipelineService
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.data.local.repo.DefaultChapterRepository
import com.example.zhishu.data.local.repo.DefaultCitationRepository
import com.example.zhishu.data.local.repo.DefaultVerificationIssueRepository
import com.example.zhishu.data.local.repo.DefaultKnowledgeRepository
import com.example.zhishu.data.local.repo.DefaultProgressRepository
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
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.search.DefaultSearchRepository
import com.example.zhishu.search.SearchHitKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 正文版本化（第三阶段 3A）。
 *
 * 这一组测试盯的全是同一句承诺：**已发布的正文不会因为重生成、取消或核验失败而丢失**。
 * 第二阶段的做法是「清旧块 → 写新块」，一旦后面失败旧正文就没了；
 * 现在每次生成写独立版本，只有核验通过才把 activeVersionId 切过去。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChapterVersioningTest {

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

    /** 首次生成就建出一个版本，指针指向它 */
    @Test
    fun firstGeneration_createsPublishedVersionAndPointer() = runBlocking {
        val planId = seedThroughBible()
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val versions = db.chapterVersionDao().getByChapter(planId)
        assertEquals(1, versions.size)
        assertEquals(1, versions.first().versionNumber)
        assertEquals(ChapterVersionStatus.PUBLISHED, versions.first().status)
        assertEquals(versions.first().id, db.chapterPlanDao().getById(planId)?.activeVersionId)
        // 正文元数据跟着版本走
        assertEquals("老人立下遗嘱", versions.first().summary)
        assertEquals("遗嘱真伪待查", versions.first().endingState)
    }

    /** 要求 1：重生成期间新版本是草稿，旧正文照样可读 */
    @Test
    fun regeneration_keepsOldBodyReadableWhileNewVersionIsDraft() = runBlocking {
        val planId = firstPublishedChapter()
        val oldVersionId = activeVersionId(planId)
        val oldText = activeText(planId)

        // 在核验阶段观察一次：此刻新正文已落库，指针还没切
        var readableDuringRun = false
        var textDuringRun: String? = null
        var draftSeen: ChapterVersionStatus? = null
        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "改写后的开头。")
        router.onPhase = { phase ->
            if (phase == ScriptedRouter.Phase.VERIFY) {
                readableDuringRun = db.chapterPlanDao().getById(planId)?.isReadable == true
                textDuringRun = activeText(planId)
                draftSeen = db.chapterVersionDao().getByChapter(planId)
                    .single { it.id != oldVersionId }.status
            }
        }
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        // 要求 9：整个重生成过程中章节一刻都没有变得不可读
        assertTrue(readableDuringRun)
        assertEquals(oldText, textDuringRun)
        assertEquals(ChapterVersionStatus.VERIFYING, draftSeen)
    }

    /** 要求 2：新版本正文生成失败，activeVersionId 不变，旧正文照样能读 */
    @Test
    fun bodyFailure_keepsActiveVersion() = runBlocking {
        val planId = firstPublishedChapter()
        val oldVersionId = activeVersionId(planId)
        val oldText = activeText(planId)

        router.failAt = ScriptedRouter.Phase.CHAPTER
        router.failWith = ModelError.Server("502")
        assertTrue(pipeline.generateChapter("p-1", planId).isFailure)

        val plan = db.chapterPlanDao().getById(planId)
        assertEquals(oldVersionId, plan?.activeVersionId)
        assertEquals(ChapterStatus.PUBLISHED, plan?.status)
        assertTrue(plan?.isReadable == true)
        assertEquals(oldText, activeText(planId))
        // 失败的那一版被标成作废，问题记在版本上而不是覆盖章节的核验说明
        val abandoned = db.chapterVersionDao().getByChapter(planId).single { it.id != oldVersionId }
        assertEquals(ChapterVersionStatus.ABANDONED, abandoned.status)
        assertTrue(abandoned.verificationNote.isNotBlank())
        assertEquals("", plan?.verificationNote)
    }

    /** 要求 3：新版本核验失败，activeVersionId 不变，章节不被改成 NEEDS_REVIEW */
    @Test
    fun verificationFailure_keepsActiveVersionAndReadableChapter() = runBlocking {
        val planId = firstPublishedChapter()
        val oldVersionId = activeVersionId(planId)
        val oldText = activeText(planId)

        router.verifyJson =
            """{"issues":[{"type":"contradiction","blockId":"id-9","message":"与资料矛盾","sourceRefs":["c-1"],"suggestedFix":"改"}]}"""
        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "有问题的新开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isFailure)

        val plan = db.chapterPlanDao().getById(planId)
        assertEquals(oldVersionId, plan?.activeVersionId)
        assertEquals(ChapterStatus.PUBLISHED, plan?.status)
        assertEquals(oldText, activeText(planId))
        val abandoned = db.chapterVersionDao().getByChapter(planId).single { it.id != oldVersionId }
        assertEquals(ChapterVersionStatus.ABANDONED, abandoned.status)
        assertTrue(abandoned.verificationNote.contains("与资料矛盾"))
    }

    /** 没有旧版本可读时，核验失败仍然要拦住发布并标 NEEDS_REVIEW（解耦没放过真正的正文问题） */
    @Test
    fun verificationFailure_withoutOldVersion_marksNeedsReview() = runBlocking {
        val planId = seedThroughBible()
        router.verifyJson =
            """{"issues":[{"type":"contradiction","blockId":"id-9","message":"与资料矛盾","sourceRefs":["c-1"],"suggestedFix":"改"}]}"""
        assertTrue(pipeline.generateChapter("p-1", planId).isFailure)

        val plan = db.chapterPlanDao().getById(planId)
        assertEquals(ChapterStatus.NEEDS_REVIEW, plan?.status)
        assertFalse(plan?.isReadable == true)
        assertNull(plan?.activeVersionId)
    }

    /** 要求 5 + 8 + 10：核验通过后原子切换；旧版本 blocks 保留；两版 blockId 不同 */
    @Test
    fun successfulRegeneration_switchesPointerAndKeepsOldBlocks() = runBlocking {
        val planId = firstPublishedChapter()
        val oldVersionId = activeVersionId(planId)
        val oldBlockIds = db.chapterBlockDao().getByVersion(oldVersionId).map { it.id }

        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val newVersionId = activeVersionId(planId)
        assertNotEquals(oldVersionId, newVersionId)
        assertEquals(2, db.chapterVersionDao().getById(newVersionId)?.versionNumber)
        assertEquals(ChapterVersionStatus.PUBLISHED, db.chapterVersionDao().getById(newVersionId)?.status)
        // 旧版本被归档而不是作废，正文块一个不少（3D）：
        // 它成功过、读者真的读过它，只是现在有了更新的一版。
        // 记成 ABANDONED 的话，「上次重新生成没通过」的提示会把这次**成功**也报成失败
        assertEquals(
            ChapterVersionStatus.ARCHIVED,
            db.chapterVersionDao().getById(oldVersionId)?.status,
        )
        val keptOldBlocks = db.chapterBlockDao().getByVersion(oldVersionId)
        assertEquals(oldBlockIds.size, keptOldBlocks.size)
        assertEquals(oldBlockIds.toSet(), keptOldBlocks.map { it.id }.toSet())
        // 新旧版本用不同 blockId，阅读进度锚点不会串版本
        val newBlockIds = db.chapterBlockDao().getByVersion(newVersionId).map { it.id }
        assertTrue(newBlockIds.none { it in oldBlockIds })
        // 要求 7：阅读器只显示新版本
        val visible = db.chapterBlockDao().observeActiveBlocks(planId).first()
        assertEquals(newBlockIds, visible.map { it.id })
        assertTrue(visible.first().text.contains("第二版"))
    }

    /** 要求 6：切换之后搜索只命中新版本，旧版本的文字搜不到 */
    @Test
    fun afterSwitch_searchOnlyHitsNewVersion() = runBlocking {
        val planId = firstPublishedChapter()
        val searchRepo = DefaultSearchRepository(
            knowledgeNodeDao = db.knowledgeNodeDao(),
            chapterBlockDao = db.chapterBlockDao(),
            sourceChunkDao = db.sourceChunkDao(),
        )
        assertTrue(
            searchRepo.search("p-1", "各怀心思").any { it.kind == SearchHitKind.CHAPTER_BLOCK },
        )

        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "众人屏息等待宣读。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        // 旧正文的原句搜不出来了——它已经不在读者看到的那一版里
        assertTrue(searchRepo.search("p-1", "各怀心思").none { it.kind == SearchHitKind.CHAPTER_BLOCK })
        val hit = searchRepo.search("p-1", "屏息").first { it.kind == SearchHitKind.CHAPTER_BLOCK }
        assertEquals(activeVersionId(planId), db.chapterBlockDao().getActiveBlocks(planId).first().versionId)
        assertTrue(db.chapterBlockDao().getActiveBlocks(planId).any { it.id == hit.blockId })
    }

    /** 要求 11：出题失败不回滚正文版本——正文已经发布了，测验只是可选产物 */
    @Test
    fun quizFailure_doesNotRollBackVersion() = runBlocking {
        val planId = seedThroughBible()
        router.failAt = ScriptedRouter.Phase.QUIZ
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val plan = db.chapterPlanDao().getById(planId)
        assertEquals(ChapterStatus.PUBLISHED, plan?.status)
        assertTrue(plan?.isReadable == true)
        val versionId = plan?.activeVersionId
        assertEquals(
            ChapterVersionStatus.PUBLISHED,
            db.chapterVersionDao().getById(versionId.orEmpty())?.status,
        )
        assertEquals(QuizState.FAILED, plan?.quizState)
        assertTrue(db.chapterBlockDao().getActiveBlocks(planId).isNotEmpty())
    }

    /** 自测题绑定版本：重生成正文后旧题不会挂在新正文上 */
    @Test
    fun regeneration_dropsOldVersionQuizzesFromView() = runBlocking {
        val planId = firstPublishedChapter()
        val oldVersionId = activeVersionId(planId)
        assertEquals(3, db.quizDao().getActiveByChapter(planId).size)
        val oldQuizIds = db.quizDao().getByChapter(planId).map { it.id }.toSet()

        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        // 新版本有自己的题；旧题还在库里（答题记录不至于变成孤儿），但不再显示
        val active = db.quizDao().getActiveByChapter(planId)
        assertEquals(3, active.size)
        assertTrue(active.none { it.id in oldQuizIds })
        assertEquals(activeVersionId(planId), active.first().versionId)
        assertTrue(db.quizDao().getByChapter(planId).any { it.versionId == oldVersionId })
    }

    /** 补题只用当前版本正文出题，且不重新生成正文 */
    @Test
    fun regenerateQuiz_usesActiveVersionOnly() = runBlocking {
        val planId = firstPublishedChapter()
        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)
        val newVersionId = activeVersionId(planId)

        router.calls.clear()
        assertTrue(pipeline.regenerateQuiz("p-1", planId).isSuccess)
        assertEquals(listOf(ScriptedRouter.Phase.QUIZ), router.calls)
        // 版本指针没被补题动过
        assertEquals(newVersionId, activeVersionId(planId))
        assertTrue(db.quizDao().getActiveByChapter(planId).all { it.versionId == newVersionId })
    }

    /**
     * 要求 12：版本切换后阅读进度的行为是明确的——章节保留、块锚点清空。
     *
     * 旧 blockId 属于上一版正文，在新正文里不存在。不清掉的话阅读器会「恢复失败、
     * 停在顶部」，而进度里却记着一个查不到的块，谁也说不清发生了什么。
     * 现在就是一句话：还是这一章，从头开始读。
     */
    @Test
    fun versionSwitch_resetsProgressAnchorButKeepsChapter() = runBlocking {
        val planId = firstPublishedChapter()
        val progress = DefaultProgressRepository(db.readingProgressDao())
        val oldBlockId = db.chapterBlockDao().getActiveBlocks(planId).last().id
        progress.save(
            ReadingProgress(
                id = "p-1",
                projectId = "p-1",
                chapterId = planId,
                blockId = oldBlockId,
                blockOffset = 42,
                updatedAt = 5L,
            ),
        )

        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val saved = progress.get("p-1")
        assertEquals(planId, saved?.chapterId)
        assertNull(saved?.blockId)
        assertEquals(0, saved?.blockOffset)
        // 旧块本身没被删，只是不再作为阅读锚点
        assertTrue(db.chapterBlockDao().getAllVersionsByChapter(planId).any { it.id == oldBlockId })
    }

    /** 别的章节被重生成时，本章的阅读进度不受影响 */
    @Test
    fun versionSwitch_doesNotTouchOtherChapterProgress() = runBlocking {
        val first = firstPublishedChapter()
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        val second = plans[1].id
        val progress = DefaultProgressRepository(db.readingProgressDao())
        val anchor = db.chapterBlockDao().getActiveBlocks(first).last().id
        progress.save(
            ReadingProgress(
                id = "p-1",
                projectId = "p-1",
                chapterId = first,
                blockId = anchor,
                blockOffset = 17,
                updatedAt = 5L,
            ),
        )

        // 生成的是第二章，第一章的进度不该被清
        assertTrue(pipeline.generateChapter("p-1", second).isSuccess)
        val saved = progress.get("p-1")
        assertEquals(first, saved?.chapterId)
        assertEquals(anchor, saved?.blockId)
        assertEquals(17, saved?.blockOffset)
    }

    /**
     * 失败的重生成不能悄无声息（第三阶段 3A）。
     *
     * UI 靠「有没有比当前版本更新的作废版本 + 它的说明」来提示「上次重新生成没通过」。
     * 这条测试盯住那个判据本身：失败后存在这样的版本，重生成成功后就不再存在。
     */
    @Test
    fun failedRegeneration_leavesNewerAbandonedVersionWithReason() = runBlocking {
        val planId = firstPublishedChapter()
        router.failAt = ScriptedRouter.Phase.CHAPTER
        assertTrue(pipeline.generateChapter("p-1", planId).isFailure)

        val activeNumber = db.chapterVersionDao()
            .getById(activeVersionId(planId))?.versionNumber ?: 0
        val newerFailures = db.chapterVersionDao().getByChapter(planId).filter {
            it.status == ChapterVersionStatus.ABANDONED &&
                it.verificationNote.isNotBlank() &&
                it.versionNumber > activeNumber
        }
        assertEquals(1, newerFailures.size)

        // 再生成一次并成功：指针前移，不再有「比当前更新的失败版本」，提示自然消失
        router.failAt = null
        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "第三版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)
        val newActiveNumber = db.chapterVersionDao()
            .getById(activeVersionId(planId))?.versionNumber ?: 0
        assertTrue(
            db.chapterVersionDao().getByChapter(planId).none {
                it.status == ChapterVersionStatus.ABANDONED && it.versionNumber > newActiveNumber
            },
        )
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

    /** 建到「第一章已发布」为止 */
    private suspend fun firstPublishedChapter(): String {
        val planId = seedThroughBible()
        pipeline.generateChapter("p-1", planId).getOrThrow()
        router.calls.clear()
        return planId
    }

    private suspend fun activeVersionId(planId: String): String =
        db.chapterPlanDao().getById(planId)?.activeVersionId.orEmpty()

    private suspend fun activeText(planId: String): String =
        db.chapterBlockDao().getActiveBlocks(planId).joinToString("\n") { it.text }
}
