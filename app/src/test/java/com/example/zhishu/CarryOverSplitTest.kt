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
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import java.io.File
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
 * carryOver 双重语义的拆分（第三阶段 3B）。
 *
 * 原来一列装两样东西：章节没发布时是「本章开头钩子」，发布后被改写成「本章结尾状态」。
 * 读的时候只能靠章节状态去猜，猜错的后果是把开头钩子当成上一章的结尾喂给模型。
 *
 * 现在各归各位：开头钩子 → chapters.openingHook（属于计划），
 * 结尾状态 → chapter_versions.endingState（属于某一版正文）。
 * 这一组测试全部盯在「喂进模型的到底是哪一段」上——那是拆分的唯一意义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarryOverSplitTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService
    private lateinit var router: ScriptedRouter
    private var ids = 0

    /** 带占位符的章节模板：只有这样才能从提示词里读出各段前情的来源 */
    private val chapterTemplate =
        "[[CHAPTER]] 上一章结尾=<{previousEndingState}> 开头钩子=<{openingHook}> 口子=<{closingHook}>"

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
            prompts = TaggedPromptProvider(chapterTemplate),
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

    /** 规划阶段写进 openingHook 而不是 carryOver——两种含义不再共用一列 */
    @Test
    @Suppress("DEPRECATION")
    fun planning_writesOpeningHookNotCarryOver() = runBlocking {
        seedThroughBible()
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        assertEquals("从一封律师函开始", plans[0].openingHook)
        assertEquals("庭审当天的清晨", plans[1].openingHook)
        // 历史列一个字都不写
        assertTrue(plans.all { it.carryOver.isEmpty() })
    }

    /** 要求 3：下一章生成时读的是上一章 active version 的 endingState */
    @Test
    fun nextChapter_readsPreviousActiveVersionEndingState() = runBlocking {
        seedThroughBible()
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        pipeline.generateChapter("p-1", plans[0].id).getOrThrow()
        val firstEnding = db.chapterVersionDao().getActive(plans[0].id)?.endingState
        assertEquals("遗嘱真伪待查", firstEnding)

        pipeline.generateChapter("p-1", plans[1].id).getOrThrow()
        val prompt = router.lastPrompts[ScriptedRouter.Phase.CHAPTER].orEmpty()
        assertTrue(prompt.contains("上一章结尾=<遗嘱真伪待查>"))
        // 第二章自己的开头钩子来自计划，不是上一章的结尾
        assertTrue(prompt.contains("开头钩子=<庭审当天的清晨>"))
    }

    /** 重生成上一章后，下一章拿到的是**新**版本的结尾，不是被作废那一版的 */
    @Test
    fun nextChapter_readsEndingStateOfNewVersionAfterRegeneration() = runBlocking {
        seedThroughBible()
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        pipeline.generateChapter("p-1", plans[0].id).getOrThrow()

        router.chapterJson = router.chapterJson.replace("遗嘱真伪待查", "遗嘱已被认定无效")
        pipeline.generateChapter("p-1", plans[0].id).getOrThrow()

        pipeline.generateChapter("p-1", plans[1].id).getOrThrow()
        val prompt = router.lastPrompts[ScriptedRouter.Phase.CHAPTER].orEmpty()
        assertTrue(prompt.contains("上一章结尾=<遗嘱已被认定无效>"))
        assertFalse(prompt.contains("遗嘱真伪待查"))
    }

    /** 要求 4：重生成当前章不改变下一章的 openingHook */
    @Test
    fun regeneratingChapter_doesNotTouchNextOpeningHook() = runBlocking {
        seedThroughBible()
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        pipeline.generateChapter("p-1", plans[0].id).getOrThrow()
        assertEquals("庭审当天的清晨", db.chapterPlanDao().getById(plans[1].id)?.openingHook)

        router.chapterJson = router.chapterJson.replace("遗嘱真伪待查", "另一个结尾")
        pipeline.generateChapter("p-1", plans[0].id).getOrThrow()
        // 下一章的计划一个字没动
        assertEquals("庭审当天的清晨", db.chapterPlanDao().getById(plans[1].id)?.openingHook)
    }

    /**
     * 本章要留的口子取自**下一章计划里的开头钩子**，而不是下一章已经写出来的结尾。
     *
     * 后者是后面才发生的事：重生成第一章时拿第二章的结尾当收尾提示，
     * 等于让第一章去衔接一个此刻还不该存在的未来。
     */
    @Test
    fun closingHook_comesFromNextPlanNotNextPublishedEnding() = runBlocking {
        seedThroughBible()
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        // 先把第二章写出来，让它有一个已发布的结尾
        pipeline.generateChapter("p-1", plans[1].id).getOrThrow()
        val secondEnding = db.chapterVersionDao().getActive(plans[1].id)?.endingState
        assertEquals("遗嘱真伪待查", secondEnding)

        // 再生成第一章
        pipeline.generateChapter("p-1", plans[0].id).getOrThrow()
        val prompt = router.lastPrompts[ScriptedRouter.Phase.CHAPTER].orEmpty()
        assertTrue(prompt.contains("口子=<庭审当天的清晨>"))
        assertFalse(prompt.contains("口子=<遗嘱真伪待查>"))
    }

    /** 要求 5：跳章生成时上一章还没有正文，不能伪造一个上一章结尾 */
    @Test
    fun skippingAhead_doesNotFabricatePreviousEnding() = runBlocking {
        seedThroughBible()
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        // 直接生成第二章，第一章一个字都没写
        pipeline.generateChapter("p-1", plans[1].id).getOrThrow()

        val prompt = router.lastPrompts[ScriptedRouter.Phase.CHAPTER].orEmpty()
        assertTrue(prompt.contains("上一章还没有写出正文"))
        assertTrue(prompt.contains("不要虚构上一章发生过什么"))
        // 尤其不能把第一章计划里的开头钩子当成它的结尾塞进来
        assertFalse(prompt.contains("上一章结尾=<从一封律师函开始>"))
    }

    /** 第一章没有上一章，说明里要明确写「这是第一章」 */
    @Test
    fun firstChapter_saysThereIsNoPreviousChapter() = runBlocking {
        seedThroughBible()
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        pipeline.generateChapter("p-1", plans[0].id).getOrThrow()

        val prompt = router.lastPrompts[ScriptedRouter.Phase.CHAPTER].orEmpty()
        assertTrue(prompt.contains("这是第一章，还没有前情"))
    }

    /** 发布后只更新当前版本的 endingState，历史 carryOver 列不再被写 */
    @Test
    @Suppress("DEPRECATION")
    fun publish_writesEndingStateOnVersionOnly() = runBlocking {
        seedThroughBible()
        val planId = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }.first().id
        pipeline.generateChapter("p-1", planId).getOrThrow()

        assertEquals("遗嘱真伪待查", db.chapterVersionDao().getActive(planId)?.endingState)
        assertEquals("", db.chapterPlanDao().getById(planId)?.carryOver)
        // 开头钩子还是规划时那一句，没被结尾覆盖——这正是旧实现的病根
        assertEquals("从一封律师函开始", db.chapterPlanDao().getById(planId)?.openingHook)
    }

    /**
     * 架构约束（3D）：业务代码不许读写 `carryOver`。
     *
     * 这一列现在只为旧数据库升级与旧备份导入而存在。它已经标了 `@Deprecated`，
     * 但注释和 warning 挡不住所有人——尤其它的名字看起来正好像是「上一章的前情」。
     * 所以这里直接扫源码：只有下面这份白名单里的文件可以出现它。
     *
     * 白名单的每一项都有明确理由：
     * - `Chapter.kt`：字段声明本身；
     * - `AppDatabase.kt`：v2→v3 建列、v5→v6 与 v6→v7 一次性搬运；
     * - `TransferManager.kt`：旧备份（v4 及更早）导入，规则与上面两条迁移逐字一致。
     *
     * 提示词模板一律不许出现——那是最容易把两个含义接回去的地方。
     */
    @Test
    fun carryOver_isNotReferencedByBusinessCode() {
        val mainSources = sourceRoot().resolve("src/main")
        val offenders = mainSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in CARRY_OVER_ALLOWLIST }
            // 只看真正的代码：注释里提到它是好事（说明为什么不能用），不该算违规
            .filter { stripComments(it.readText()).contains("carryOver") }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)

        val prompts = mainSources.resolve("assets/prompts").walkTopDown()
            .filter { it.isFile && it.extension == "txt" }
            .filter { it.readText().contains("carryOver") }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), prompts)
    }

    /**
     * 去掉行注释与块注释（含 KDoc）。
     *
     * 够用就行：这里只是为了把「注释里解释为什么不能用 carryOver」和
     * 「代码里真的读写了它」分开，不需要一个完整的 Kotlin 词法分析器。
     */
    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    /**
     * 定位 app 模块目录。
     *
     * Gradle 跑单元测试时工作目录可能是仓库根也可能是 `app/`，
     * 所以两种都试一下，而不是写死一个相对路径。
     */
    private fun sourceRoot(): File {
        val cwd = File(System.getProperty("user.dir").orEmpty())
        val candidates = listOf(cwd, cwd.resolve("app"), cwd.parentFile?.resolve("app"))
        return candidates.filterNotNull().firstOrNull { it.resolve("src/main").isDirectory }
            ?: error("找不到 app/src/main，工作目录是 $cwd")
    }

    private companion object {
        val CARRY_OVER_ALLOWLIST = setOf(
            // 字段声明本身
            "Chapter.kt",
            // 建列 + 两次一次性搬运（v2→v3 / v5→v6 / v6→v7）
            "AppDatabase.kt",
            // 旧备份导入，规则与上面的迁移逐字一致
            "TransferManager.kt",
        )
    }

    private suspend fun seedThroughBible() {
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
    }
}
