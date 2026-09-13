package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.ModelCallStats
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.PromptProvider
import com.example.zhishu.ai.pipeline.IssueRecorder
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
import com.example.zhishu.domain.model.IssueType
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.QuizState
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.domain.model.VerificationIssue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService
    private lateinit var fakeRouter: FakeRouter
    private var idCounter = 0

    /** 间隔复习开关（默认关，和产品默认一致），个别测试里改成 true 验证排复习 */
    private var spacedReview = false

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        RouteStubs.reset()
        fakeRouter = FakeRouter()
        idCounter = 0
        spacedReview = false
        pipeline = PipelineService(
            router = fakeRouter,
            prompts = MapPromptProvider(),
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
            chapterRepository = DefaultChapterRepository(db.chapterPlanDao(), db.chapterBlockDao(), db.chapterVersionDao()),
            citationRepository = DefaultCitationRepository(db.citationDao()),
            issueRepository = DefaultVerificationIssueRepository(db.verificationIssueDao()),
            storyBibleRepository = DefaultStoryBibleRepository(db.storyBibleDao()),
            quizRepository = DefaultQuizRepository(db.quizDao(), db.answerRecordDao()),
            reviewRepository = DefaultReviewRepository(db.reviewItemDao()),
            spacedReviewEnabled = { spacedReview },
            idFactory = { "id-${idCounter++}" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun generateKnowledgeMap_createsNodesAndUpdatesStatus() = runBlocking {
        seedProjectWithChunk()
        val result = pipeline.generateKnowledgeMap("p-1")
        assertTrue(result.isSuccess)
        val nodes = db.knowledgeNodeDao().getByProject("p-1")
        assertEquals(2, nodes.size)
        assertEquals(
            "意思表示",
            nodes.first { it.title == "民事法律行为" }.parentId?.let { parentId ->
                db.knowledgeNodeDao().getById(parentId)?.title
            },
        )
        assertEquals(ProjectStatus.MAP, db.projectDao().getById("p-1")?.status)
    }

    /** sourceRefs 必须保存片段 ID，且能据此加载回资料（B1/B19） */
    @Test
    fun generateKnowledgeMap_storesChunkIdsInSourceRefs() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        val node = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "意思表示" }
        assertEquals(listOf("c-1"), node.sourceRefs)
        val chunks = DefaultSourceRepository(db.sourceDocumentDao(), db.sourceChunkDao())
            .getChunksByIds("p-1", node.sourceRefs)
        assertEquals(1, chunks.size)
        assertTrue(chunks.first().text.contains("意思表示"))
    }

    /** 资料缺口与冲突必须持久化（B12） */
    @Test
    fun generateKnowledgeMap_persistsGapsAndConflicts() = runBlocking {
        seedProjectWithChunk()
        fakeRouter.auditJson = """
            {"gaps":["缺少合同解除的资料"],
             "conflicts":[{"topic":"撤销权期限","sources":["A","B"],"description":"两份资料期限不一致"}]}
        """.trimIndent()
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        // 审计的 gaps + 节点数偏少的软提示都可能存在，断言关键内容命中即可
        val gaps = db.knowledgeGapDao().getByProject("p-1")
        assertTrue(gaps.any { it.description.contains("缺少合同解除的资料") })
        assertEquals("撤销权期限", db.knowledgeConflictDao().getByProject("p-1").first().topic)
    }

    /** 空知识点必须直接失败，不写库（N14） */
    @Test
    fun generateKnowledgeMap_emptyNodes_failsValidation() = runBlocking {
        seedProjectWithChunk()
        fakeRouter.skeletonJson = """{"chains":[],"gaps":[],"conflicts":[]}"""
        val result = pipeline.generateKnowledgeMap("p-1")
        assertTrue(result.exceptionOrNull() is ModelError.Invalid)
        assertTrue(db.knowledgeNodeDao().getByProject("p-1").isEmpty())
    }

    @Test
    fun generateStoryBible_createsPlans() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        val result = pipeline.generateStoryBible("p-1")
        assertTrue(result.isSuccess)
        val plans = db.chapterPlanDao().getByProject("p-1")
        assertTrue(plans.isNotEmpty())
        // 知识点只有 2 个，卷内章数必须被节点数封顶——不能摊成 4 章每章半个知识点
        assertTrue("章数 ${plans.size} 超过了 2 个知识点能撑起的上限", plans.size <= 2)
        // 每一章都要真的挂到一个知识点上
        assertTrue(plans.all { it.nodeIds.isNotEmpty() })
        val nodeTitles = db.knowledgeNodeDao().getByProject("p-1").map { it.title }.toSet()
        plans.flatMap { it.nodeIds }.forEach { id ->
            assertTrue(db.knowledgeNodeDao().getById(id)?.title in nodeTitles)
        }
        assertEquals(ProjectStatus.OUTLINE, db.projectDao().getById("p-1")?.status)
    }

    /**
     * 三段式下每一章都要有通过校验的契约（改前断言的是「一次成型 + REVIEW 修复」）。
     *
     * REVIEW 章节的 introduced 必须为空这条规则没有消失，只是挪到了
     * [com.example.zhishu.ai.pipeline.ContractIntroducedFix]——那里按章节类型逐条处理，
     * 这条集成测试改为验证「全卷每章契约都落库且合规」这个更强的性质。
     */
    @Test
    fun generateStoryBible_everyChapter_hasValidContract() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        val result = pipeline.generateStoryBible("p-1")
        assertTrue(result.isSuccess)
        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        assertTrue(plans.isNotEmpty())
        plans.forEach { plan ->
            // 契约没生成 = 不允许写正文，这条底线由 contractStatus 把守
            assertEquals(
                com.example.zhishu.domain.model.ContractStatus.DONE.name,
                plan.contractStatus,
            )
            assertTrue(plan.chapterType.isNotBlank())
            val state = com.example.zhishu.ai.outputs.ContractCodec.decode(
                plan.contractJson,
                plan.chapterType,
            )
            assertTrue("第 ${plan.order} 章契约不可用：$state", state is com.example.zhishu.ai.outputs.ContractCodec.State.Valid)
            val contract = (state as com.example.zhishu.ai.outputs.ContractCodec.State.Valid).contract
            // REVIEW 章不能把任何节点算作「首次引入」——原测试的意图保留在这里
            if (plan.chapterType == "REVIEW") {
                assertTrue(contract.introducedNodeTitles.isEmpty())
            }
            // 契约引用的节点必须真的存在（编号反解后是标题）
            val known = db.knowledgeNodeDao().getByProject("p-1").map { it.title }.toSet()
            assertTrue(
                (contract.introducedNodeTitles + contract.appliedNodeTitles).all { it in known },
            )
        }
    }

    /**
     * 大纲引用了清单外的编号 → 整卷失败（改前断言的是标题匹配失败）。
     *
     * 三段式把「引用不存在的东西」从标题匹配换成编号匹配，性质不变：
     * 引用一个不存在于本卷的知识点编号，等于凭空讲知识，必须拦下而不是静默丢弃。
     */
    @Test
    fun generateStoryBible_unknownNodeKey_fails() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        // 让大纲桩返回一个不在清单里的编号
        com.example.zhishu.RouteStubs.outlineForcesUnknownKey = true
        val result = pipeline.generateStoryBible("p-1")
        assertFalse(result.isSuccess)
        // 失败不留半成品：章节计划一个都不落库
        assertTrue(db.chapterPlanDao().getByProject("p-1").isEmpty())
    }

    /**
     * 一章契约失败只影响这一章（计划验收项 10）。
     *
     * 这是三段式改造的全部意义所在：改之前一章不合规 → `ModelError.Invalid`
     * （不可重试）→ 整卷作废、已生成的部分全丢。改之后失败的章单独标
     * [ContractStatus.FAILED]，其余章照常落库可用。
     */
    @Test
    fun generateStoryBible_oneChapterContractFails_othersStillDone() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        RouteStubs.outlineChapterCount = 4
        // 只让第 3 章的契约违规
        RouteStubs.contractViolationAtChapter = 3

        val result = pipeline.generateStoryBible("p-1")
        assertTrue(result.isSuccess)

        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        assertEquals(4, plans.size)
        val statuses = plans.map { it.contractStatus }
        assertEquals(
            listOf("DONE", "DONE", "FAILED", "DONE"),
            statuses,
        )
        // 失败章没有契约，成功章契约完整
        assertTrue(plans[2].contractJson.isBlank())
        assertTrue(plans[0].contractJson.isNotBlank())
        // 路线整体算成功（不再因一章而整卷作废），项目进入 OUTLINE
        assertEquals(ProjectStatus.OUTLINE, db.projectDao().getById("p-1")?.status)
    }

    /**
     * 契约失败的章禁止写正文（计划验收项 11 的下半段）。
     *
     * 「有正文必有契约」是一条硬约束：正文按契约的拍子写，核验也按它逐项查。
     * 契约缺失的章若允许生成，会产出没有因果结构的正文，而且无法核验。
     */
    @Test
    fun chapterWithFailedContract_isExcludedFromGeneration() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        RouteStubs.outlineChapterCount = 3
        RouteStubs.contractViolationAtChapter = 2
        pipeline.generateStoryBible("p-1").getOrThrow()

        val plans = db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }
        val failed = plans.first { it.contractStatus == "FAILED" }

        // 直接点生成也要被拦：不能绕过 selectChaptersToGenerate 从单章入口溜进去
        val result = pipeline.generateChapter("p-1", failed.id)
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("契约"),
        )
        assertTrue(db.chapterBlockDao().getAllVersionsByChapter(failed.id).isEmpty())
    }

    @Test
    fun generateChapter_publishesBlocksAndQuiz() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        // 同上：末尾断言项目 READY，本卷必须只有这一章
        RouteStubs.outlineChapterCount = 1
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        val result = pipeline.generateChapter("p-1", plan.id)
        assertTrue(result.isSuccess)
        val loaded = db.chapterPlanDao().getById(plan.id)
        assertEquals(ChapterStatus.PUBLISHED, loaded?.status)
        val blocks = db.chapterBlockDao().getAllVersionsByChapter(plan.id)
        assertTrue(blocks.isNotEmpty())
        // 正文块必须带 projectId，删除项目时才能清理（N6）
        assertTrue(blocks.all { it.projectId == "p-1" })
        assertEquals(3, db.quizDao().getByProject("p-1").size)
        // 发布后回写节点出现章节（B16）
        val node = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "意思表示" }
        assertTrue(node.chapterIds.contains(plan.id))
        // 复习默认关闭：读完一章不会自动生出复习任务（第一阶段：故事优先）
        assertTrue(db.reviewItemDao().getByProject("p-1").isEmpty())
        assertEquals(ProjectStatus.READY, db.projectDao().getById("p-1")?.status)
    }

    /** 用户在设置里打开间隔复习后，发布章节才排首次复习（B2） */
    @Test
    fun generateChapter_withSpacedReviewEnabled_schedulesReviews() = runBlocking {
        spacedReview = true
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        assertTrue(pipeline.generateChapter("p-1", plan.id).isSuccess)
        assertTrue(db.reviewItemDao().getByProject("p-1").isNotEmpty())
    }

    /** 出题必须走 VERIFY 角色，与设置页说明一致（N3） */
    @Test
    fun generateQuiz_usesVerifyRole() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        pipeline.generateChapter("p-1", plan.id)
        assertEquals(ModelRole.VERIFY, fakeRouter.quizRole)
        assertEquals(ModelRole.VERIFY, fakeRouter.verifyRole)
        assertEquals(ModelRole.FAST, fakeRouter.knowledgeMapRole)
        assertEquals(ModelRole.STORY, fakeRouter.chapterRole)
    }

    /** 测验生成失败时不得发布章节（N2） */
    @Test
    fun generateChapter_quizFails_stillPublishesBody() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        fakeRouter.quizJson = """{"questions":[]}"""
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        // 出题失败不算这一章失败：正文写好了、核验也过了
        val result = pipeline.generateChapter("p-1", plan.id)
        assertTrue(result.isSuccess)
        val loaded = db.chapterPlanDao().getById(plan.id)
        assertEquals(ChapterStatus.PUBLISHED, loaded?.status)
        assertTrue(loaded?.isReadable == true)
        // 失败只落在测验状态上，正文的核验说明不受污染
        assertEquals(QuizState.FAILED, loaded?.quizState)
        assertTrue(loaded?.quizNote.orEmpty().isNotBlank())
        assertTrue(db.quizDao().getByProject("p-1").isEmpty())
        // 正文块在库里，能读
        assertTrue(db.chapterBlockDao().getAllVersionsByChapter(plan.id).isNotEmpty())
    }

    /** 题目数量不足最低要求：仍然只影响测验状态（第二阶段） */
    @Test
    fun generateChapter_tooFewQuizzes_marksQuizFailedOnly() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        fakeRouter.quizJson =
            """{"questions":[{"question":"Q1","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["意思表示"]}]}"""
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        assertTrue(pipeline.generateChapter("p-1", plan.id).isSuccess)
        val loaded = db.chapterPlanDao().getById(plan.id)
        assertEquals(ChapterStatus.PUBLISHED, loaded?.status)
        assertEquals(QuizState.FAILED, loaded?.quizState)
    }

    /** 题目引用不存在的知识点时被过滤掉，同样不拖累正文（第二阶段） */
    @Test
    fun generateChapter_quizWithUnknownNodes_keepsBodyReadable() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        fakeRouter.quizJson = """
            {"questions":[
              {"question":"Q1","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["幽灵知识点"]},
              {"question":"Q2","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["幽灵知识点"]},
              {"question":"Q3","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["幽灵知识点"]}
            ]}
        """.trimIndent()
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        assertTrue(pipeline.generateChapter("p-1", plan.id).isSuccess)
        val loaded = db.chapterPlanDao().getById(plan.id)
        assertEquals(ChapterStatus.PUBLISHED, loaded?.status)
        assertEquals(QuizState.FAILED, loaded?.quizState)
    }

    @Test
    fun generateChapter_withIssues_publishesBodyAndMarksNeedsReview() = runBlocking {
        seedProjectWithChunk()
        fakeRouter.verifyJson =
            """{"issues":[{"type":"contradiction","blockId":"b-1","message":"与资料矛盾","sourceRefs":["c-1"],"suggestedFix":"修改"}]}"""
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        val result = pipeline.generateChapter("p-1", plan.id)
        // 核验发现问题不再判整章失败：正文照常发布（可读），问题留给复核。
        // blockId 对不上任何真实块 → 章级问题，自动修复无从下手，直接标待复核
        assertTrue(result.isSuccess)
        val loaded = db.chapterPlanDao().getById(plan.id)
        assertEquals(ChapterStatus.NEEDS_REVIEW, loaded?.status)
        // 正文真的在库里、已发布成 active 版本——「待复核」复核的是读得到的文字
        val active = db.chapterVersionDao().getActive(plan.id)
        assertTrue(active != null)
        assertTrue(db.chapterBlockDao().getAllVersionsByChapter(plan.id).isNotEmpty())
        // 4B 起说明里给的是问题条数与可读的类型名，而不是模型的原始 type 串。
        // 「contradiction」是给代码看的，用户看的是「与资料矛盾」
        val note = loaded?.verificationNote.orEmpty()
        assertTrue(note.contains("1 个问题"))
        assertTrue(note.contains("与资料矛盾"))
        assertFalse(note.contains("contradiction"))
    }

    /** 自动修复：问题定位到真实块时，发布后立刻按既有逐段重写管线修一轮（4B 延伸） */
    @Test
    fun generateChapter_autoRepairTargets_followGuardrails() {
        fun recordedOf(vararg blockIds: String?) = IssueRecorder.Recorded(
            blockIds.mapIndexed { index, blockId ->
                VerificationIssue(
                    id = "i-$index",
                    projectId = "p-1",
                    chapterId = "c-1",
                    versionId = "v-1",
                    blockId = blockId,
                    type = IssueType.CONTRADICTION,
                    rawType = "contradiction",
                    message = "m",
                    sourceRefs = emptyList(),
                    suggestedFix = "",
                    order = index,
                    createdAt = 0L,
                )
            },
        )

        // 定位得到、块数在护栏内 → 修
        assertEquals(listOf("b-1"), pipeline.autoRepairTargets(recordedOf("b-1")))
        // 全是章级问题（blockId 空）→ 不修，直接待复核
        assertNull(pipeline.autoRepairTargets(recordedOf(null, null)))
        // 块数超过护栏 → 不修
        val many = (1..7).map { "b-$it" }.toTypedArray()
        assertNull(pipeline.autoRepairTargets(recordedOf(*many)))
        // 恰好在护栏边界 → 修
        val edge = (1..6).map { "b-$it" }.toTypedArray()
        assertEquals(6, pipeline.autoRepairTargets(recordedOf(*edge))?.size)
    }

    /** 章节生成期间项目状态进入 GENERATING（B17） */
    @Test
    fun generateChapter_setsGeneratingStatusOnFailure() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        fakeRouter.chapterJson = """{"title":"t","summary":"s","blocks":[]}"""
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        assertTrue(pipeline.generateChapter("p-1", plan.id).isFailure)
        assertEquals(ProjectStatus.GENERATING, db.projectDao().getById("p-1")?.status)
        assertEquals(ChapterStatus.FAILED, db.chapterPlanDao().getById(plan.id)?.status)
    }

    /**
     * 重复生成同一章不会叠加正文——阅读器里始终只有一版。
     *
     * 第三阶段 3A 起旧版本的块保留在库里（可追溯、可回查），
     * 所以「不叠加」要按 active version 断言，而不是按 chapterId 数总数。
     */
    @Test
    fun regenerateChapter_replacesActiveBlocksAndKeepsOldVersion() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        pipeline.generateChapter("p-1", plan.id)
        val firstVersion = db.chapterPlanDao().getById(plan.id)?.activeVersionId
        val firstCount = db.chapterBlockDao().getActiveBlocks(plan.id).size

        pipeline.generateChapter("p-1", plan.id)
        // 读者看到的仍然只有一版正文
        assertEquals(firstCount, db.chapterBlockDao().getActiveBlocks(plan.id).size)
        // 指针换到了新版本，旧版本的正文块一个都没被删
        val secondVersion = db.chapterPlanDao().getById(plan.id)?.activeVersionId
        assertNotEquals(firstVersion, secondVersion)
        assertEquals(firstCount * 2, db.chapterBlockDao().getAllVersionsByChapter(plan.id).size)
        assertEquals(firstCount, db.chapterBlockDao().getByVersion(firstVersion.orEmpty()).size)
    }

    @Test
    fun generateKnowledgeMap_withoutChunks_fails() = runBlocking {
        db.projectDao().upsert(project())
        val result = pipeline.generateKnowledgeMap("p-1")
        assertTrue(result.isFailure)
    }

    @Test
    fun refreshProjectStatus_readyOnlyWhenAllPublished() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        // 这条断言「一章发布完项目就 READY」，所以本卷只能有这一章（测试前置条件）
        RouteStubs.outlineChapterCount = 1
        pipeline.generateStoryBible("p-1")
        pipeline.refreshProjectStatus("p-1")
        assertEquals(ProjectStatus.GENERATING, db.projectDao().getById("p-1")?.status)
        assertFalse(db.chapterPlanDao().getByProject("p-1").isEmpty())
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        pipeline.generateChapter("p-1", plan.id)
        assertEquals(ProjectStatus.READY, db.projectDao().getById("p-1")?.status)
        assertNotNull(db.chapterPlanDao().getById(plan.id))
    }

    /**
     * 待复核章（正文已发布、只等用户确认）重写失败：状态必须保持待复核、
     * activeVersionId 原样——旧判据（isReadable）把它当「没有正文」，
     * 失败后翻成 FAILED，阅读入口全部关闭，正文明明还在库里。
     */
    @Test
    fun regenerate_needsReviewChapter_failedAttemptKeepsReadableBody() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        RouteStubs.outlineChapterCount = 1
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        pipeline.generateChapter("p-1", plan.id)
        // 模拟「带问题发布」：核验挑出问题、自动修复没修干净时正文照常发布但停在待复核
        db.chapterPlanDao().updateStatus(plan.id, ChapterStatus.NEEDS_REVIEW, "核验发现问题")
        val bodyBefore = db.chapterPlanDao().getById(plan.id)?.activeVersionId
        assertNotNull(bodyBefore)

        fakeRouter.failChapterBody = true
        val result = pipeline.generateChapter("p-1", plan.id)
        assertTrue(result.isFailure)
        val after = db.chapterPlanDao().getById(plan.id)!!
        assertEquals(ChapterStatus.NEEDS_REVIEW, after.status)
        assertEquals(bodyBefore, after.activeVersionId)
        assertFalse(db.chapterBlockDao().getActiveBlocks(plan.id).isEmpty())
    }

    /**
     * 整本书都停在待复核（正文可读）时，整本重规划必须被闸门拦下——
     * 旧判据（isReadable）会把这种书判成「没有正文」，重规划直接清掉全书可读内容。
     */
    @Test
    fun replan_gate_blocksWhenOnlyNeedsReviewWithBody() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        RouteStubs.outlineChapterCount = 1
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        pipeline.generateChapter("p-1", plan.id)
        db.chapterPlanDao().updateStatus(plan.id, ChapterStatus.NEEDS_REVIEW, "核验发现问题")

        val result = pipeline.generateStoryBible("p-1")
        assertTrue(result.isFailure)
        // 拦下来的同时正文一个块都不能少
        assertFalse(db.chapterBlockDao().getActiveBlocks(plan.id).isEmpty())
    }

    /** 待复核不是「在生成」：项目徽章不该永远挂在「正在生成…」 */
    @Test
    fun refreshProjectStatus_needsReviewIsNotGenerating() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        RouteStubs.outlineChapterCount = 1
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        pipeline.generateChapter("p-1", plan.id)
        assertEquals(ProjectStatus.READY, db.projectDao().getById("p-1")?.status)
        db.chapterPlanDao().updateStatus(plan.id, ChapterStatus.NEEDS_REVIEW, "核验发现问题")
        pipeline.refreshProjectStatus("p-1")
        assertEquals(ProjectStatus.READY, db.projectDao().getById("p-1")?.status)
    }

    private fun project(status: ProjectStatus = ProjectStatus.DRAFT) = Project(
        id = "p-1",
        title = "民法基础",
        topic = "民法",
        baseline = "入门",
        depth = "入门",
        dailyMinutes = 20,
        targetDays = 30,
        genre = "悬疑",
        status = status,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private suspend fun seedProjectWithChunk() {
        db.projectDao().upsert(project())
        val source = SourceDocument(
            id = "src-1",
            projectId = "p-1",
            title = "民法笔记",
            kind = SourceKind.TEXT,
            fetchedAt = 1L,
            contentHash = "h1",
            parseStatus = ParseStatus.PARSED,
        )
        db.sourceDocumentDao().upsert(source)
        db.sourceChunkDao().upsertAll(
            listOf(
                SourceChunk(
                    id = "c-1",
                    sourceId = source.id,
                    projectId = "p-1",
                    ordinal = 0,
                    text = "意思表示是民事法律行为的核心要素。",
                ),
            ),
        )
    }

    private class MapPromptProvider : PromptProvider {
        // 返回值里带一个稳定的阶段标记，FakeRouter 据此分辨阶段，
        // 不再依赖会随产品文案变化的 system 串。
        private val map = mapOf(
            "knowledge_map_skeleton" to "[[MAP_SKELETON]]",
            "knowledge_map_chain" to "[[MAP_CHAIN]] 节点标识清单：{nodeKeyTitles}",
            "knowledge_map_audit" to "[[MAP_AUDIT]]",
            "knowledge_map" to "[[MAP]]",
            "knowledge_map_model" to "[[MAP]]",
            "story_bible" to "[[BIBLE]]",
            "route_outline" to RouteStubs.OUTLINE_TEMPLATE,
            "chapter_contract" to RouteStubs.CONTRACT_TEMPLATE,
            "chapter" to "[[CHAPTER]]",
            "verify" to "[[VERIFY]]",
            "verify_model" to "[[VERIFY]]",
            "quiz" to "[[QUIZ]]",
        )

        override fun load(name: String): String = map[name] ?: name
    }

    private class FakeRouter : ModelRouter {
        // 批次化：骨架输出（一条链、两个节点），单链展开 JSON 由 generate 里按 prompt 动态构造
        var skeletonJson: String =
            """{"chains":[{"theme":"民法演进","opening":"从合同纠纷开始","nodeTitles":["意思表示","民事法律行为"]}],"gaps":[],"conflicts":[]}"""
        var auditJson: String = """{"gaps":[],"conflicts":[]}"""
        var storyBibleJson: String =
            """{"world":"滨海市","characters":[{"name":"林默","role":"主角","description":"年轻律师"}],"conflict":"遗产继承纠纷","style":"悬疑","chapters":[{"order":1,"title":"第一章 遗嘱风波","plotGoal":"引出遗嘱问题","learningGoals":["意思表示"],"nodeTitles":["意思表示"],"wordTarget":1000,"chapterType":"INTRODUCE","contract":{"entryQuestion":"老人立下遗嘱后子女各执一词","priorIntuition":"谁拿到遗嘱谁说了算","obstacle":"遗嘱真伪存疑","knowledgeGap":"需要判断遗嘱效力的标准","introducedNodeTitles":["意思表示"],"appliedNodeTitles":[],"requiredBeats":["收到律师函","发现遗嘱","引入意思表示","判断效力"],"resultingChange":"子女开始按意思表示规则重新审视遗嘱","bridgeToNext":"遗嘱是否有效"}}]}"""
        var chapterJson: String =
            """{"title":"第一章 遗嘱风波","summary":"老人立下遗嘱后引发纠纷","blocks":[{"type":"STORY","text":"老人立下遗嘱，子女们各怀心思。","knowledgeNodeTitles":["意思表示"],"sourceRefs":["c-1"]},{"type":"EXPLANATION","text":"意思表示是民事法律行为的核心要素。","knowledgeNodeTitles":["意思表示"],"sourceRefs":["c-1"]}]}"""
        var verifyJson: String = """{"issues":[]}"""
        var quizJson: String = """
            {"questions":[
              {"question":"意思表示是否为民法概念？","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"依据资料。","nodeTitles":["意思表示"]},
              {"question":"意思表示是民事法律行为的核心要素吗？","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"依据资料。","nodeTitles":["意思表示"]},
              {"question":"请回忆意思表示的定义。","type":"RECALL","options":[],"answer":"行为人将内心意思表达于外","explanation":"依据资料。","nodeTitles":["意思表示"]}
            ]}
        """.trimIndent()

        var knowledgeMapRole: ModelRole? = null
        var chapterRole: ModelRole? = null
        var verifyRole: ModelRole? = null
        var quizRole: ModelRole? = null
        var storyBibleCalls: Int = 0

        /** 置 true 时正文生成返回失败，用于验证重写失败的收尾不动旧正文 */
        var failChapterBody: Boolean = false

        override suspend fun <T> generate(
            role: ModelRole,
            request: ModelRequest,
            decoder: JsonDecoder<T>,
            onStats: (ModelCallStats) -> Unit,
            onAttempt: (com.example.zhishu.ai.CallAttempt) -> Unit,
        ): Result<T> {
            val text = when {
                request.user.contains("[[MAP_SKELETON]]") -> {
                    knowledgeMapRole = role; skeletonJson
                }
                // 单链展开：从 prompt 的「节点标识清单」解析 nodeKey 与标题，回传带 nodeKey 的节点。
                // 第一个节点是根（depth 1），其余挂到第一个节点下（depth 2），与旧测试的父子断言兼容。
                request.user.contains("[[MAP_CHAIN]]") -> chainNodesJson(request)
                request.user.contains("[[MAP_AUDIT]]") -> auditJson
                request.user.contains("[[MAP]]") -> {
                    knowledgeMapRole = role; knowledgeMapJson()
                }
                request.user.contains("[[OUTLINE]]") -> RouteStubs.outlineJson(request.user)
                request.user.contains("[[CONTRACT]]") -> RouteStubs.contractJson(request.user)
                request.user.contains("[[BIBLE]]") -> {
                    storyBibleCalls += 1
                    storyBibleJson
                }
                request.user.contains("[[CHAPTER]]") -> {
                    chapterRole = role; chapterJson
                }
                request.user.contains("[[VERIFY]]") -> {
                    verifyRole = role; verifyJson
                }
                request.user.contains("[[QUIZ]]") -> {
                    quizRole = role; quizJson
                }
                else -> error("没有匹配的测试数据: ${request.user.take(40)}")
            }
            if (failChapterBody && request.user.contains("[[CHAPTER]]")) {
                return Result.failure(ModelError.Server("注入的正文生成失败"))
            }
            return try {
                Result.success(decoder.decode(text))
            } catch (e: Exception) {
                Result.failure(ModelError.Decode(e.message ?: "decode failed"))
            }
        }

        /** 旧式一次性地图 JSON（兼容只设旧字段的用例） */
        private fun knowledgeMapJson(): String {
            val nodes = skeletonJson.let { raw ->
                runCatching {
                    com.example.zhishu.ai.outputs.OutputValidators.validateSkeleton(
                        com.example.zhishu.ai.jsonDecoder(
                            com.example.zhishu.ai.outputs.KnowledgeMapSkeletonOutput.serializer(),
                        ).decode(raw),
                    ).getOrThrow()
                }.getOrNull()?.chains?.flatMap { it.nodeTitles }.orEmpty()
            }
            val nodeJson = nodes.mapIndexed { index, title ->
                """{"title":"$title","definition":"$title 的定义。","parentTitle":${if (index == 0) "null" else "\"${nodes[0]}\""},"depth":${if (index == 0) 1 else 2},"sourceRefs":["c-1"]}"""
            }.joinToString(",")
            return """{"nodes":[$nodeJson],"gaps":[],"conflicts":[]}"""
        }

        /** 从单链 prompt 提取 (nodeKey, title) 对，格式为「nk-xxx：标题」每行一条 */
        private fun chainNodesJson(request: ModelRequest): String {
            val entries = Regex("""(nk-[^\s：]+)：([^\n]+)""")
                .findAll(request.user)
                .map { it.groupValues[1] to it.groupValues[2].trim() }
                .toList()
            val nodeJson = entries.mapIndexed { index, (key, title) ->
                """{"nodeKey":"$key","title":"$title","definition":"$title 的核心定义。",""" +
                    """"arisesFrom":"从起点而来","leadsTo":"引出下一步","depth":${if (index == 0) 1 else 2},""" +
                    """"parentTitle":${if (index == 0) "null" else "\"${entries[0].second}\""},"sourceRefs":["c-1"]}"""
            }.joinToString(",")
            return """{"nodes":[$nodeJson]}"""
        }
    }
}
