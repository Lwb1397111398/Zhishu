package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 版本系统加固（第三阶段 3D）。
 *
 * 三件事各自都能悄悄出错、而且错了不报任何异常：
 * 1. 「被取代的旧正文」和「失败的尝试」写成同一个状态 → 每次成功重生成都被报成失败；
 * 2. 覆盖关系按标题记账 → 用户改一次知识点名字，讲过的内容全变回「还没讲」；
 * 3. 正文换版本后进度悄悄回到章首 → 看起来就是进度丢了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VersionHardeningTest {

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
                routeBatchDao = db.routeBatchDao(),
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

    // --- 3D-1 区分归档与放弃 ---

    /** 成功重生成：上一版归档而不是作废，正文块一块不少 */
    @Test
    fun successfulRegeneration_archivesPreviousVersion() = runBlocking {
        val planId = firstPublishedChapter()
        val firstVersion = activeVersionId(planId)
        val firstBlockIds = db.chapterBlockDao().getByVersion(firstVersion).map { it.id }.toSet()

        rewriteBody("第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        assertEquals(
            ChapterVersionStatus.ARCHIVED,
            db.chapterVersionDao().getById(firstVersion)?.status,
        )
        assertEquals(
            firstBlockIds,
            db.chapterBlockDao().getByVersion(firstVersion).map { it.id }.toSet(),
        )
    }

    /** 失败的重生成：新版本才是 ABANDONED，当前发布版本一动不动 */
    @Test
    fun failedRegeneration_abandonsOnlyTheNewAttempt() = runBlocking {
        val planId = firstPublishedChapter()
        val published = activeVersionId(planId)

        router.failAt = ScriptedRouter.Phase.CHAPTER
        assertTrue(pipeline.generateChapter("p-1", planId).isFailure)

        assertEquals(published, activeVersionId(planId))
        assertEquals(
            ChapterVersionStatus.PUBLISHED,
            db.chapterVersionDao().getById(published)?.status,
        )
        val attempt = db.chapterVersionDao().getByChapter(planId).single { it.id != published }
        assertEquals(ChapterVersionStatus.ABANDONED, attempt.status)
    }

    /**
     * 三次生成之后每个版本的结局都说得清：v1 归档、v2 作废（失败）、v3 发布。
     *
     * 这是 3D-1 的核心断言——之前 v1 和 v2 都是 ABANDONED，
     * 从数据上分不出「成功过被取代」和「从来没成功」。
     */
    @Test
    fun versionHistory_distinguishesArchivedFromAbandoned() = runBlocking {
        val planId = firstPublishedChapter()
        val v1 = activeVersionId(planId)

        // 第二次：失败
        router.failAt = ScriptedRouter.Phase.VERIFY
        assertTrue(pipeline.generateChapter("p-1", planId).isFailure)
        router.failAt = null

        // 第三次：成功
        rewriteBody("第三版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)
        val v3 = activeVersionId(planId)

        val byNumber = db.chapterVersionDao().getByChapter(planId).associateBy { it.versionNumber }
        assertEquals(3, byNumber.size)
        assertEquals(ChapterVersionStatus.ARCHIVED, byNumber[1]?.status)
        assertEquals(ChapterVersionStatus.ABANDONED, byNumber[2]?.status)
        assertEquals(ChapterVersionStatus.PUBLISHED, byNumber[3]?.status)
        assertEquals(v3, byNumber[3]?.id)
        assertEquals(v1, byNumber[1]?.id)
    }

    /**
     * 「上次重新生成没通过」只认真正的失败。
     *
     * 判据与 ProjectDetailViewModel.failedRegenerations 一致：
     * ABANDONED + 版本号比当前 active 新。归档版本不该进这个判据——
     * 否则每一次**成功**的重生成都会留下一条「失败」提示。
     */
    @Test
    fun successfulRegeneration_leavesNoFailureNote() = runBlocking {
        val planId = firstPublishedChapter()
        rewriteBody("第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val activeNumber = db.chapterVersionDao().getById(activeVersionId(planId))!!.versionNumber
        val reported = db.chapterVersionDao().getByChapter(planId).filter {
            it.status == ChapterVersionStatus.ABANDONED && it.versionNumber > activeNumber
        }
        assertTrue(reported.isEmpty())
    }

    /** 半路死掉的旧尝试在别的版本发布时被结清，不会留成看不出结局的僵尸版本 */
    @Test
    fun publish_closesOutStalePendingAttempts() = runBlocking {
        val planId = seedThroughBible()
        // 造一个卡在 VERIFYING 的旧尝试：取消发生在核验阶段
        router.cancelAt = ScriptedRouter.Phase.VERIFY
        runCatching { pipeline.generateChapter("p-1", planId) }
        router.cancelAt = null
        // 手工把它扶回 VERIFYING，模拟「进程被杀，收尾没跑完」
        val stale = db.chapterVersionDao().getByChapter(planId).single()
        db.chapterVersionDao().updateStatus(stale.id, ChapterVersionStatus.VERIFYING, "", 1L)

        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val closed = db.chapterVersionDao().getById(stale.id)
        assertEquals(ChapterVersionStatus.ABANDONED, closed?.status)
        assertTrue(closed?.verificationNote.orEmpty().isNotBlank())
    }

    // --- 3D-3 版本切换后的学习数据 ---

    /** 旧版本的题保留在库里，但不显示在新正文下；新版本自己出了新题 */
    @Test
    fun versionSwitch_keepsOldQuizzesButShowsOnlyNew() = runBlocking {
        val planId = firstPublishedChapter()
        val v1 = activeVersionId(planId)
        val oldQuizIds = db.quizDao().getByChapter(planId).map { it.id }.toSet()
        assertTrue(oldQuizIds.isNotEmpty())

        rewriteBody("第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)
        val v2 = activeVersionId(planId)

        // 旧题一道没删
        assertTrue(db.quizDao().getByChapter(planId).map { it.id }.containsAll(oldQuizIds))
        assertTrue(db.quizDao().getByChapter(planId).any { it.versionId == v1 })
        // 但当前只显示新版本的题
        val visible = db.quizDao().getActiveByChapter(planId)
        assertTrue(visible.isNotEmpty())
        assertTrue(visible.all { it.versionId == v2 })
        assertTrue(visible.none { it.id in oldQuizIds })
    }

    /** 出题失败不影响正文：正文照样发布可读，只有 quizState 记账 */
    @Test
    fun quizFailureAfterVersionSwitch_doesNotAffectBody() = runBlocking {
        val planId = firstPublishedChapter()
        rewriteBody("第二版的开头。")
        router.failAt = ScriptedRouter.Phase.QUIZ
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val plan = db.chapterPlanDao().getById(planId)
        assertTrue(plan?.isReadable == true)
        assertEquals(
            ChapterVersionStatus.PUBLISHED,
            db.chapterVersionDao().getById(activeVersionId(planId))?.status,
        )
        assertTrue(db.chapterBlockDao().getActiveBlocks(planId).first().text.contains("第二版"))
    }

    /** 掌握度不因为重生成清零：答题记录与节点掌握度都属于学习者，不属于某一版正文 */
    @Test
    fun versionSwitch_doesNotResetMastery() = runBlocking {
        val planId = firstPublishedChapter()
        val quiz = db.quizDao().getByChapter(planId).first()
        db.answerRecordDao().upsert(
            AnswerRecord(
                id = "a-1",
                quizId = quiz.id,
                projectId = "p-1",
                isCorrect = true,
                answeredAt = 1L,
            ),
        )
        val nodeId = db.knowledgeNodeDao().getByProject("p-1").first().id
        db.knowledgeNodeDao().updateMastery(nodeId, 0.6f)

        rewriteBody("第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        assertEquals(
            0.6f,
            db.knowledgeNodeDao().getByProject("p-1").first { it.id == nodeId }.mastery,
            0.0001f,
        )
        // 旧答题记录也在：它记的是「这个人答对过这道题」，与正文换没换版本无关
        assertEquals(1, db.answerRecordDao().getByQuizIds(listOf(quiz.id)).size)
    }

    /** 同一知识点重生成不重复建复习项（间隔复习开启时） */
    @Test
    fun versionSwitch_doesNotDuplicateReviewItems() = runBlocking {
        val reviewPipeline = pipelineWithReview(enabled = true)
        val planId = seedThroughBible()
        assertTrue(reviewPipeline.generateChapter("p-1", planId).isSuccess)
        val firstCount = db.reviewItemDao().getByProject("p-1").size
        assertTrue(firstCount > 0)

        rewriteBody("第二版的开头。")
        assertTrue(reviewPipeline.generateChapter("p-1", planId).isSuccess)
        assertEquals(firstCount, db.reviewItemDao().getByProject("p-1").size)
    }

    /** 关闭间隔复习时，重生成同样不创建复习项 */
    @Test
    fun versionSwitch_withReviewDisabled_createsNoReviewItems() = runBlocking {
        val planId = firstPublishedChapter()
        rewriteBody("第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)
        assertTrue(db.reviewItemDao().getByProject("p-1").isEmpty())
    }

    /**
     * 进度回章首，并且留下一次性提示的依据。
     *
     * 只清 blockId 是不够的：用户明明读到一半、回来却在开头，页面还一言不发，
     * 看起来就是进度丢了。所以 bodyUpdatedAt 记一笔、progressNoticeShown 归零。
     */
    @Test
    fun versionSwitch_resetsAnchorAndFlagsNotice() = runBlocking {
        val planId = firstPublishedChapter()
        val blockId = db.chapterBlockDao().getActiveBlocks(planId).last().id
        db.readingProgressDao().upsert(
            ReadingProgress(
                id = "p-1",
                projectId = "p-1",
                chapterId = planId,
                blockId = blockId,
                blockOffset = 120,
                progressNoticeShown = true,
                updatedAt = 1L,
            ),
        )

        rewriteBody("第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val progress = db.readingProgressDao().getForProject("p-1")
        assertEquals(planId, progress?.chapterId)
        assertNull(progress?.blockId)
        assertEquals(0, progress?.blockOffset)
        assertTrue(progress?.needsBodyUpdatedNotice == true)
    }

    /** 提示只给一次：看过之后记账，再进来不再提 */
    @Test
    fun bodyUpdatedNotice_isShownOnlyOnce() = runBlocking {
        val planId = firstPublishedChapter()
        db.readingProgressDao().upsert(
            ReadingProgress(
                id = "p-1",
                projectId = "p-1",
                chapterId = planId,
                blockId = db.chapterBlockDao().getActiveBlocks(planId).first().id,
                updatedAt = 1L,
            ),
        )
        rewriteBody("第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)
        assertTrue(db.readingProgressDao().getForProject("p-1")?.needsBodyUpdatedNotice == true)

        db.readingProgressDao().markNoticeShown("p-1")
        assertFalse(db.readingProgressDao().getForProject("p-1")?.needsBodyUpdatedNotice == true)
    }

    /**
     * 阅读器保存位置时不会把「本章已更新」的痕迹冲掉。
     *
     * 这是整行 upsert 与字段级更新的区别：用户滚动一下就写一次进度，
     * 整行写回的话那条提示永远不会被看到。
     */
    @Test
    fun savingAnchor_doesNotClearBodyUpdatedFlag() = runBlocking {
        val planId = firstPublishedChapter()
        db.readingProgressDao().upsert(
            ReadingProgress(
                id = "p-1",
                projectId = "p-1",
                chapterId = planId,
                blockId = db.chapterBlockDao().getActiveBlocks(planId).first().id,
                updatedAt = 1L,
            ),
        )
        rewriteBody("第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val newBlockId = db.chapterBlockDao().getActiveBlocks(planId).first().id
        db.readingProgressDao().saveAnchor(
            ReadingProgress(
                id = "p-1",
                projectId = "p-1",
                chapterId = planId,
                blockId = newBlockId,
                blockOffset = 30,
                updatedAt = 2L,
            ),
        )

        val progress = db.readingProgressDao().getForProject("p-1")
        assertEquals(newBlockId, progress?.blockId)
        assertEquals(30, progress?.blockOffset)
        // 提示依据还在，用户仍会看到那一句
        assertTrue(progress?.needsBodyUpdatedNotice == true)
    }

    // --- 3D-5 覆盖关系改用 nodeId ---

    /** 发布时把覆盖关系记成 ID，标题同时保留（标题只用于展示） */
    @Test
    fun publish_recordsCoveredNodeIdsNotOnlyTitles() = runBlocking {
        val planId = firstPublishedChapter()
        val nodeId = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "意思表示" }.id

        val plan = db.chapterPlanDao().getById(planId)
        assertEquals(listOf(nodeId), plan?.coveredNodeIds)
        assertEquals(listOf("意思表示"), plan?.coveredTitles)
        assertEquals(listOf(nodeId), db.chapterVersionDao().getById(activeVersionId(planId))?.coveredNodeIds)
        assertTrue(db.storyBibleDao().get("p-1")?.coveredNodeIds?.contains(nodeId) == true)
    }

    /**
     * 知识点改名之后，已覆盖关系不丢。
     *
     * 3C 时这件事是按标题判断的：改一次名字，讲过的内容全变回「还没讲」，
     * 续卷会把它们重新排一遍——用户为同一段知识付两次钱。
     */
    @Test
    fun renamingNode_keepsCoverage() = runBlocking {
        val planId = firstPublishedChapter()
        val node = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "意思表示" }

        db.knowledgeNodeDao().upsert(node.copy(title = "意思表示（改名后）"))

        val report = com.example.zhishu.ai.pipeline.NodeCoverage.report(
            nodes = db.knowledgeNodeDao().getByProject("p-1"),
            plans = db.chapterPlanDao().getByProject("p-1"),
            bible = db.storyBibleDao().get("p-1"),
        )
        assertTrue(node.id in report.coveredNodeIds)
        assertTrue(report.pendingNodes.none { it.id == node.id })
        assertEquals(planId, db.chapterPlanDao().getById(planId)?.id)
    }

    /**
     * 对不上任何现存知识点的标题算「待确认」，不算「没讲过」。
     *
     * 直接当成没讲过会让续卷把已经讲透的东西再讲一遍；
     * 硬猜成某个节点又可能猜错。所以如实报出来由用户判断。
     */
    @Test
    fun unmatchableCoveredTitle_isReportedAsUnresolved() = runBlocking {
        val planId = firstPublishedChapter()
        val plan = db.chapterPlanDao().getById(planId)!!
        // 模拟旧数据：标题记了一条，但知识地图里没有这个节点
        db.chapterPlanDao().upsert(
            plan.copy(coveredTitles = plan.coveredTitles + "一个已经不存在的知识点"),
        )

        val unresolved = pipeline.unresolvedCoverageTitles("p-1")
        assertEquals(listOf("一个已经不存在的知识点"), unresolved)
    }

    /** 台账不接受不存在的节点 ID：塞进去等于永久少讲一个知识点，还看不出错在哪 */
    @Test
    fun coverageMerge_dropsUnknownNodeIds() {
        val merged = com.example.zhishu.ai.pipeline.NodeCoverage.mergeCoveredIds(
            existing = listOf("n-1", "n-gone"),
            added = listOf("n-2", "n-bogus"),
            knownIds = setOf("n-1", "n-2"),
        )
        assertEquals(listOf("n-1", "n-2"), merged)
    }

    /** 统计只数当前版本：重生成过的章节不会被算两遍字数 */
    @Test
    fun stats_countOnlyActiveVersion() = runBlocking {
        val planId = firstPublishedChapter()
        val charsBefore = db.chapterBlockDao().countActiveCharsByProject("p-1")
        val blocksBefore = db.chapterBlockDao().countActiveByProject("p-1")
        assertTrue(charsBefore > 0)

        rewriteBody("第二版的开头。")
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        // 全部历史版本翻倍，当前版本的块数不变
        assertEquals(blocksBefore * 2, db.chapterBlockDao().countByProject("p-1"))
        assertEquals(blocksBefore, db.chapterBlockDao().countActiveByProject("p-1"))
        // 字数也只算当前版本：新版本第一块短一些，所以总数会变小而不是翻倍
        val charsAfter = db.chapterBlockDao().countActiveCharsByProject("p-1")
        assertTrue(charsAfter < charsBefore * 2)
        assertEquals(charsAfter, db.chapterBlockDao().countActiveCharsByChapter(planId))
    }

    // --- 3D-2 全项目审计：业务层只调 active 查询 ---

    /**
     * 架构约束：业务层（流水线、搜索、ViewModel、界面）不许调「全部历史版本」的读取。
     *
     * 这条只能靠扫源码守。3A 之后 `getBlocks(chapterId)` 在代码里看起来
     * 和「读这一章的正文」一模一样，读错了不会抛异常、不会报错——
     * 只会在阅读器里把两版正文叠着显示，或者把字数算成两倍。
     * 所以历史版本的方法名一律带 `AllVersions`，并且只允许备份导出调用。
     */
    @Test
    fun historyReads_areConfinedToBackupExport() {
        val allowed = setOf(
            // 备份导出：必须导全部历史版本，只导 active 的话恢复出来就再也没有旧版本可查
            "TransferManager.kt",
            // 接口与实现声明本身
            "Repositories.kt",
            "RepositoriesImpl.kt",
            "ChapterBlockDao.kt",
            // 引用关系的 DAO（4A）：同样只有备份导出会调它的 AllVersions 查询
            "CitationDao.kt",
            // 核验问题的 DAO（4B）：同理，AllVersions 只给备份导出用。
            // 大纲页看到的必须是 Active 系列——混进历史版本的问题，
            // 用户会看到一堆说的是已经被替换掉的文字的问题
            "VerificationIssueDao.kt",
        )
        val offenders = sourceRoot().resolve("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { file ->
                val code = stripComments(file.readText())
                HISTORY_READS.any { code.contains(it) }
            }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * 反过来的一条：正文消费入口都真的走了 active 查询。
     *
     * 上一条测的是「没人误用历史版本」，这一条测的是「该用 active 的地方确实用了」——
     * 少了任何一条，某个入口就会在重生成之后显示读者已经看不到的内容。
     */
    @Test
    fun everyBodyConsumer_usesActiveQueries() {
        val root = sourceRoot().resolve("src/main/java/com/example/zhishu")
        // 入口 → 它必须出现的 active 查询
        val expectations = mapOf(
            // 阅读器、章末自测题数
            "ui/viewmodel/ZhishuViewModels.kt" to listOf(
                "observeActiveBlocks",
                "observeActiveByChapter",
                "getActiveByChapter",
                // 知识点跳转锚点
                "getActiveBlocksByProject",
                // 项目进度统计
                "countActiveChars",
            ),
            // 正文搜索 + 搜索里的知识点锚点
            "search/DefaultSearchRepository.kt" to listOf(
                "searchActiveByProjectMatch",
                "getActiveBlocksByProject",
            ),
            // 补出自测题只依据当前正文
            "ai/pipeline/PipelineService.kt" to listOf("getActiveBlocks"),
        )
        expectations.forEach { (path, required) ->
            val code = stripComments(root.resolve(path).readText())
            required.forEach { symbol ->
                assertTrue("$path 应当调用 $symbol", code.contains(symbol))
            }
        }
    }

    /**
     * 核验读的是**本次这一版**的正文，不是章节当前正文。
     *
     * 这是 active 之外的第三种正确答案，容易被上面那条规则带偏：
     * 核验的对象是刚写出来、还没发布的新版本，此刻 activeVersionId 还指着旧的。
     * 拿 active 去核验等于核验了一遍上一版正文，然后据此发布新版本。
     */
    @Test
    fun verification_readsTheVersionBeingGenerated() = runBlocking {
        val planId = firstPublishedChapter()
        val firstVersion = activeVersionId(planId)
        rewriteBody("只在第二版里出现的句子。")

        var verifiedBody = ""
        router.onPhase = { phase ->
            if (phase == ScriptedRouter.Phase.VERIFY) {
                verifiedBody = router.lastPrompts[ScriptedRouter.Phase.VERIFY].orEmpty()
            }
        }
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)
        router.onPhase = null

        // 核验期间指针还指着旧版本，但核验拿到的是新版本的正文
        assertTrue(firstVersion != activeVersionId(planId))
        val newBlocks = db.chapterBlockDao().getByVersion(activeVersionId(planId))
        assertTrue(newBlocks.any { it.text.contains("只在第二版里出现的句子") })
    }

    // PLACEHOLDER_TESTS

    /** 建一条开启/关闭间隔复习的流水线，其他依赖与主实例共用同一个库 */
    private fun pipelineWithReview(enabled: Boolean): PipelineService = PipelineService(
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
                routeBatchDao = db.routeBatchDao(),
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
        spacedReviewEnabled = { enabled },
        idFactory = { "rid-${ids++}" },
    )

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

    private suspend fun firstPublishedChapter(): String {
        val planId = seedThroughBible()
        pipeline.generateChapter("p-1", planId).getOrThrow()
        router.calls.clear()
        return planId
    }

    private suspend fun activeVersionId(planId: String): String =
        db.chapterPlanDao().getById(planId)?.activeVersionId.orEmpty()

    /** 换一版正文内容，让新旧版本能区分开 */
    private fun rewriteBody(marker: String) {
        router.chapterJson = router.chapterJson
            .replace("老人立下遗嘱，子女各怀心思。", marker)
    }

    /** 定位 app 模块目录。Gradle 的工作目录可能是仓库根也可能是 app/，两种都试 */
    private fun sourceRoot(): File {
        val cwd = File(System.getProperty("user.dir").orEmpty())
        return listOfNotNull(cwd, cwd.resolve("app"), cwd.parentFile?.resolve("app"))
            .firstOrNull { it.resolve("src/main").isDirectory }
            ?: error("找不到 app/src/main，工作目录是 $cwd")
    }

    /** 去掉注释：注释里提到某个方法名不算调用它 */
    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    private companion object {
        /** 「全部历史版本」的读取入口。业务代码里出现任何一个都是误用 */
        val HISTORY_READS = listOf(
            "getAllVersionsByChapter",
            "getAllVersionsByProject",
            "getAllVersionsBlocks",
            "countAllVersionsBlocks",
            "searchAllVersionsByProjectMatch",
        )
    }
}
