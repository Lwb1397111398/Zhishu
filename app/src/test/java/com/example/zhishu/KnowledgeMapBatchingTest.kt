package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.ModelCallStats
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.PromptProvider
import com.example.zhishu.ai.TokenBudget
import com.example.zhishu.ai.pipeline.KnowledgeMapMode
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
import com.example.zhishu.domain.model.KnowledgeMapChainStatus
import com.example.zhishu.domain.model.NodeGenerateStatus
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.domain.model.SourceMode
import com.example.zhishu.domain.model.chainStatusList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 知识地图分批生成（批次化）的专项测试：
 * 骨架 → 逐链展开 → 全局审计，断点续跑 / 重新生成隔离 / 部分返回 /
 * 标识被改 / 审计失败 / 无资料模式 / 资料检索为空 / 共享节点只填充一次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KnowledgeMapBatchingTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService
    private lateinit var router: BatchingRouter
    private var idCounter = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        router = BatchingRouter()
        idCounter = 0
        pipeline = PipelineService(
            router = router,
            prompts = BatchingPromptProvider(),
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
            spacedReviewEnabled = { false },
            idFactory = { "id-${idCounter++}" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 断点续跑：链展开失败后，第二次调用跳过骨架与已完成的链，只补失败链 */
    @Test
    fun chainFailure_thenRetry_resumesFromBreakpoint() = runBlocking {
        router.skeletonJson = skeleton(2) // 两条链
        seedProject()
        // 第一次：第 1 条链失败。失败不再中止整批——链 2 照常展开成功
        router.failNextChain = true
        val first = pipeline.generateKnowledgeMap("p-1")
        assertTrue(first.isFailure)
        assertTrue(first.exceptionOrNull()?.message.orEmpty().contains("1 条展开失败"))
        assertEquals(1, router.skeletonCalls)
        // 链 1 失败 1 次 + 链 2 成功 1 次：没有让一条链的失败挡住整批
        assertEquals(2, router.chainCalls)
        assertEquals(0, router.auditCalls) // 有失败链时不做审计
        // 骨架已落库，失败的链节点标 FAILED；链 2 节点已完成
        val batch = db.knowledgeMapBatchDao().getLatest("p-1")
        assertEquals(true, batch?.skeletonDone)
        val failedNode = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "A" }
        assertEquals(NodeGenerateStatus.FAILED.name, failedNode.generateStatus)
        val chain2Node = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "C" }
        assertEquals(NodeGenerateStatus.COMPLETED.name, chain2Node.generateStatus)

        // 第二次：不再调骨架、不再调已完成的链 2，只重试失败链 + 审计
        router.failNextChain = false
        val second = pipeline.generateKnowledgeMap("p-1")
        assertTrue(second.isSuccess)
        assertEquals(1, router.skeletonCalls) // 骨架没有重复调用
        // 链调用总数 = 链 1 失败 1 次 + 链 2 一次 + 链 1 重试 1 次
        assertEquals(3, router.chainCalls)
        assertEquals(1, router.auditCalls)
        val all = db.knowledgeNodeDao().getByProject("p-1")
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.generateStatus == NodeGenerateStatus.COMPLETED.name })
        assertEquals(ProjectStatus.MAP, db.projectDao().getById("p-1")?.status)
    }

    /** 重新生成隔离：已完成批次再调用 = 新批次，旧节点全部清掉，不误认断点 */
    @Test
    fun repeatCall_afterCompleted_opensNewBatchAndClearsOldNodes() = runBlocking {
        router.skeletonJson = skeleton(1)
        seedProject()
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        assertEquals(2, db.knowledgeNodeDao().getByProject("p-1").size)
        val firstBatchId = db.knowledgeMapBatchDao().getLatest("p-1")?.id

        // 重新生成：骨架换了新内容，旧节点必须消失
        router.skeletonJson = skeleton(1, prefix = "新")
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val nodes = db.knowledgeNodeDao().getByProject("p-1")
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.title.startsWith("新") })
        val latest = db.knowledgeMapBatchDao().getLatest("p-1")
        assertNotEquals(firstBatchId, latest?.id)
        assertEquals(true, latest?.completed)
    }

    /**
     * 部分节点返回 → 自动追问补齐（截断自愈）。
     *
     * 单次输出被 max_tokens 截断时只能救回前面的节点，尾部仍是占位。
     * 这时不能就这么算完（原来会「成功」但留一张缺内容的地图），
     * 而是按「还差哪些节点」再问一轮——输出更短，因此能补上。
     */
    @Test
    fun partialChainReturn_autoContinuesUntilFilled() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        seedProject()
        // 第一轮只回传 A（模拟输出在 B 处被截断）；第二轮走默认生成器补 B
        router.chainCustom[0] = {
            """{"nodes":[{"nodeKey":"nk-1","title":"A","definition":"A 定义。","depth":1,"sourceRefs":["c-1"]}]}"""
        }
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val nodes = db.knowledgeNodeDao().getByProject("p-1")
        assertTrue(nodes.all { it.generateStatus == NodeGenerateStatus.COMPLETED.name })
        // 第二轮的提示词里只该出现还没填的 B，不该把已填的 A 再要一遍
        val secondPrompt = router.chainPrompts[1]
        assertTrue(secondPrompt.contains("nk-2：B"))
        assertFalse(secondPrompt.contains("nk-1：A"))
    }

    /**
     * 追问到上限仍填不满 → 如实报失败，并说清是「没写出来」而不是网络问题。
     * 静默成功会让用户拿到一张缺内容的地图，且 COMPLETED 永远不再重试。
     */
    @Test
    fun neverFilledNode_reportsFailureWithReason() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        seedProject()
        val onlyA = { _: List<Pair<String, String>> ->
            """{"nodes":[{"nodeKey":"nk-1","title":"A","definition":"A 定义。","depth":1,"sourceRefs":["c-1"]}]}"""
        }
        // 每一轮都只回传 A：B 永远填不上。
        // 轮数由 PipelineService.chainMaxPasses(节点数) 推导，2 节点链取下限 3
        repeat(3) { router.chainCustom[it] = onlyA }
        val result = pipeline.generateKnowledgeMap("p-1")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("1 条展开失败"))
        // 说清是「模型没按清单回传」而不是网络问题。
        // 不再提「调大最大 Token」——单链输出上限已由程序固定为 CHAIN_MAX_TOKENS，
        // 用户改设置页的值对这一步没有影响，那条建议只会白折腾
        assertTrue("失败原因该说清是没写出来：$message", message.contains("没写出来"))
        assertFalse("单链上限已由程序固定，不该再让用户调最大 Token", message.contains("最大 Token"))
        // A 保留（已写好的不白跑），B 标失败让 UI 可见并允许重试
        val nodes = db.knowledgeNodeDao().getByProject("p-1")
        assertEquals(NodeGenerateStatus.COMPLETED.name, nodes.first { it.title == "A" }.generateStatus)
        assertEquals(NodeGenerateStatus.FAILED.name, nodes.first { it.title == "B" }.generateStatus)
        assertEquals(0, router.auditCalls) // 有失败链时不做审计
    }

    /** 模型修改标题/标识：nodeKey 不认识的节点被丢弃，并写进 gaps 提示 */
    @Test
    fun unknownNodeKey_droppedAndNotedInGaps() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        seedProject()
        router.chainCustom[0] = { entries ->
            val (aKey, aTitle) = entries.first()
            // 模型"发明"了一个不存在的 nodeKey 和超纲标题
            """{"nodes":[{"nodeKey":"$aKey","title":"$aTitle","definition":"正确定义。","depth":1,"sourceRefs":["c-1"]},{"nodeKey":"nk-bogus","title":"幽灵概念","definition":"不该存在。","depth":1}]}"""
        }
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        // 幽灵概念没有被创建
        assertTrue(db.knowledgeNodeDao().getByProject("p-1").none { it.title == "幽灵概念" })
        // gaps 里记录了被丢弃的标题
        val gaps = db.knowledgeGapDao().getByProject("p-1")
        assertTrue(gaps.any { it.description.contains("幽灵概念") })
    }

    /**
     * 最终审计失败按软失败收尾：地图内容完整、批次照常完成，gaps 落一条说明。
     * 原来这里返回失败、批次不置 completed——用户看到「又失败了」，
     * 重试还要把 2-3 万 token 的审计输入再交一遍钱；审计输入瘦身后失败概率本就大降
     */
    @Test
    fun auditFailure_keepsNodesAndMarksAuditFailed() = runBlocking {
        router.skeletonJson = skeleton(1)
        seedProject()
        router.failAudit = true
        val result = pipeline.generateKnowledgeMap("p-1")
        // 软失败：不算失败——所有链已展开、地图可用，只剩缺口/冲突清单没定稿
        assertTrue(result.isSuccess)
        // 节点全部保留且完成
        val nodes = db.knowledgeNodeDao().getByProject("p-1")
        assertTrue(nodes.isNotEmpty())
        assertTrue(nodes.all { it.generateStatus == NodeGenerateStatus.COMPLETED.name })
        // 批次标记审计失败但也标记完成（重新梳理才是补审计的路）
        val batch = db.knowledgeMapBatchDao().getLatest("p-1")
        assertEquals(true, batch?.auditFailed)
        assertEquals(true, batch?.completed)
        // 地图可用：项目已置 MAP，gaps 里有审计未完成的说明
        assertEquals(ProjectStatus.MAP, db.projectDao().getById("p-1")?.status)
        assertTrue(db.knowledgeGapDao().getByProject("p-1").any { it.description.contains("审计未完成") })
    }

    // ==================== gaps / 冲突的状态继承（gaps 闭环） ====================

    /**
     * 点「知道了」的 gap 在重新梳理后不复活。
     *
     * 这是修复回归测试：骨架阶段的 replaceInsights 是**全量 clear + 重写预览**，
     * 若等审计阶段再读库继承，读到的就是刚写入的预览行（status 全空）——
     * 用户点掉的条目重新梳理一次就全部冒回来。修复是进骨架前先把旧状态拍成快照。
     */
    @Test
    fun dismissedGap_doesNotResurfaceAfterRegeneration() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        router.auditJson = auditWithInsights(
            structuredGaps = listOf(
                """{"type":"MISSING_NODE","description":"缺物权法定","suggestedTitle":"物权法定"}""",
            ),
        )
        seedProject()
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val gap = db.knowledgeGapDao().getByProject("p-1").first { it.suggestedTitle == "物权法定" }
        assertEquals(GapStatus.OPEN.name, gap.status)
        assertTrue(pipeline.dismissGap("p-1", gap.id).isSuccess)
        assertEquals(
            GapStatus.DISMISSED.name,
            db.knowledgeGapDao().getByProject("p-1").first { it.id == gap.id }.status,
        )

        // 重新梳理：骨架内容整体换掉（新批次），审计仍报同一条缺口 → 状态必须继承下来
        router.skeletonJson = skeleton(1, titles = listOf("C", "D"))
        router.auditJson = auditWithInsights(
            structuredGaps = listOf(
                """{"type":"MISSING_NODE","description":"缺物权法定","suggestedTitle":"物权法定"}""",
            ),
        )
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val after = db.knowledgeGapDao().getByProject("p-1").first { it.suggestedTitle == "物权法定" }
        assertEquals("点掉的缺口不该在重新梳理后复活", GapStatus.DISMISSED.name, after.status)
    }

    /**
     * 关闭的资料冲突在重新梳理后不复活（按 topic 继承，与 gap 同一套快照机制）。
     * 冲突没有「补上」一说，只有「我知道了别反复提示」。
     */
    @Test
    fun dismissedConflict_doesNotResurfaceAfterRegeneration() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        router.auditJson = auditWithInsights(
            conflicts = listOf(
                """{"topic":"诉讼时效","sources":["c-1"],"description":"三年还是两年"}""",
            ),
        )
        seedProject()
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val conflict = db.knowledgeConflictDao().getByProject("p-1").first { it.topic == "诉讼时效" }
        assertEquals(GapStatus.OPEN.name, conflict.status)
        assertTrue(pipeline.dismissConflict("p-1", conflict.id).isSuccess)
        assertEquals(
            GapStatus.DISMISSED.name,
            db.knowledgeConflictDao().getByProject("p-1").first { it.id == conflict.id }.status,
        )

        // 重新梳理：骨架换成新内容，审计仍报同一对立观点 → 状态必须继承下来
        router.skeletonJson = skeleton(1, titles = listOf("C", "D"))
        router.auditJson = auditWithInsights(
            conflicts = listOf(
                """{"topic":"诉讼时效","sources":["c-1"],"description":"三年还是两年"}""",
            ),
        )
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val after = db.knowledgeConflictDao().getByProject("p-1").first { it.topic == "诉讼时效" }
        assertEquals("关闭的冲突不该在重新梳理后复活", GapStatus.DISMISSED.name, after.status)
    }

    /** 重新生成失败：新批次失败时旧地图原样保留，不预先删除（v14 原子切换） */
    @Test
    fun regenerateFailure_keepsOldMapIntact() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        seedProject()
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        assertEquals(2, db.knowledgeNodeDao().getByProject("p-1").size)

        // 重新生成：新批次第 1 条链失败 → 旧地图必须还在
        router.skeletonJson = skeleton(1, titles = listOf("C", "D"))
        router.failNextChain = true
        assertTrue(pipeline.generateKnowledgeMap("p-1").isFailure)

        val nodes = db.knowledgeNodeDao().getByProject("p-1")
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.title == "A" || it.title == "B" })

        // 续跑同一失败批次（断点），成功后原子切换为新地图
        router.failNextChain = false
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val switched = db.knowledgeNodeDao().getByProject("p-1")
        assertEquals(2, switched.size)
        assertTrue(switched.all { it.title == "C" || it.title == "D" })
        // 旧批次行已被切换事务清理，只剩当前批次
        assertEquals(1, db.knowledgeMapBatchDao().getLatest("p-1")?.let { 1 } ?: 0)
    }

    /** 无资料模式（AI 自备知识）：引用被清空，流程照常完成 */
    @Test
    fun modelKnowledgeMode_stripsSourceRefs() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        seedProject(modelKnowledge = true)
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val nodes = db.knowledgeNodeDao().getByProject("p-1")
        assertEquals(2, nodes.size)
        // 模型回传的 sourceRefs 在无资料模式下被过滤为空
        assertTrue(nodes.all { it.sourceRefs.isEmpty() })
    }

    /**
     * 资料检索为空：明确告诉模型「没有相关证据」，引用留空，流程不中断。
     *
     * 原来这里降级为「项目前若干片段」，于是模型拿着一批和内容完全不沾边的片段
     * 硬凑 sourceRefs——读者点开来发现引用和正文对不上，比没有引用更伤可信度。
     * 现在检索不到就如实说没有，宁可不引。
     */
    @Test
    fun emptyChunkMatch_tellsModelNoEvidence_andKeepsRefsEmpty() = runBlocking {
        // 片段内容与链主题/节点标题完全不沾边
        seedProject(chunks = listOf("c-1" to "与主题毫无关系的一段随机文本。"))
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        // prompt 里明说没有相关资料，而不是塞一批不相干片段
        val prompt = router.chainPrompts.first()
        assertTrue("应明确告知没有检索到相关资料", prompt.contains("没有检索到与本批节点相关的资料片段"))
        assertFalse("不相干的片段不该被塞进 prompt", prompt.contains("与主题毫无关系"))
        // 模型仍回传了 c-1，但它不在本次提供的片段内 → 被过滤掉，不留虚假引用
        val a = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "A" }
        assertEquals(emptyList<String>(), a.sourceRefs)
    }

    /**
     * 续轮/重试的 prompt 必须比首轮更简明（用户反馈：「重试消息越简明越好」）。
     *
     * 一条 10 个节点的链要分两轮问完（一轮封顶 6 个）。第二轮时第一批已经写好，
     * 旧行为是把这些节点的定义摘要整套重发；现在只发紧邻上下游的全文，
     * 其余已写节点只列标题——于是第二轮的「已写节点」区块不该包含远处节点的定义。
     */
    @Test
    fun followUpPass_sendsMinimalContext() = runBlocking {
        val titles = (1..10).map { "节点$it" }
        router.skeletonJson = skeleton(1, titles = titles)
        seedProject()
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        assertTrue("10 个节点应该分两轮问完，实际 ${router.chainPrompts.size} 轮", router.chainPrompts.size >= 2)

        val followUp = router.chainPrompts[1]
        // 第二轮问的是 节点7..10，紧邻的是 节点6 → 只有它带定义
        assertTrue("紧邻的上一个节点应给全文", followUp.contains("节点6 定义v0"))
        // 远处的 节点1/节点2 只该出现标题，不该带定义
        assertFalse("远处节点不该重发定义", followUp.contains("节点1 定义v0"))
        assertFalse("远处节点不该重发定义", followUp.contains("节点2 定义v0"))
        assertTrue("远处节点仍应以标题出现（告知已写过、别重写）", followUp.contains("节点1"))
    }
    @Test
    fun sharedNode_filledOnce_byFirstChain() = runBlocking {
        // 链 1：[A, B]；链 2：[A, C]
        router.skeletonJson = skeleton(2, titles = listOf("A", "B"), secondChainTitles = listOf("A", "C"))
        seedProject()
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val a = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "A" }
        // 链 1（序号 0）先填充，链 2 跳过 → 定义是链 1 的写法
        assertEquals("A 定义v0", a.definition)
        val c = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "C" }
        assertEquals(NodeGenerateStatus.COMPLETED.name, c.generateStatus)
    }

    /**
     * 单链调用必须显式给 max_tokens，而且要和 prompt 长度一起装得进上下文窗口。
     *
     * 服务商普遍按「prompt_tokens + max_tokens ≤ 上下文窗口」校验请求，越界返回 400 →
     * ModelError.InvalidRequest 的 retryable=false → 该链**永久**失败。
     *
     * 但要防的不只是「越窗」，也包括「值大得没必要」：曾经把上限设成 5 万，
     * 结果低单次输出上限的服务商逐次 400、推理模型把预算当思考额度花掉几分钟，
     * 知识主干整体慢了一个数量级。现在阶段上限按实际需要给（见 TokenBudget.Stage），
     * 实际值再由 TokenBudget 按输入反算收紧。
     * 所以这里钉两条不变量：显式给值不沿用设置页，且 输入 + 输出 装得下窗口。
     */
    @Test
    fun chainCall_capsMaxTokens_notUserConfigured() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        seedProject()
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val chainMaxTokens = router.chainMaxTokens.first()
        assertNotNull("单链调用必须显式给 maxTokens，不能沿用设置页的 5 万", chainMaxTokens)
        assertTrue(
            "单链 maxTokens 不该超过阶段上限，实际 $chainMaxTokens",
            chainMaxTokens!! <= TokenBudget.Stage.CHAIN,
        )
        // 真正的不变量：估算输入 + 请求输出必须装进上下文窗口，否则注定 400
        val inputTokens = TokenBudget.estimateTokens(router.chainPrompts.first())
        assertTrue(
            "输入($inputTokens) + 输出($chainMaxTokens) 越过了上下文窗口" +
                "(${TokenBudget.DEFAULT_CONTEXT_WINDOW})，这类请求会被服务商 400 且不可重试",
            inputTokens + chainMaxTokens + TokenBudget.PROTOCOL_RESERVE <=
                TokenBudget.DEFAULT_CONTEXT_WINDOW,
        )
    }

    // ==================== 分步生成 / 序号标识 / 子词检索（本轮） ====================

    /** SKELETON 模式：只出骨架就停，链全待展开；nodeKey 是批次内短序号 */
    @Test
    fun skeletonMode_stopsAfterSkeleton() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        seedProject()
        val result = pipeline.generateKnowledgeMap("p-1", KnowledgeMapMode.SKELETON)
        assertTrue(result.isSuccess)
        assertEquals(1, router.skeletonCalls)
        assertEquals(0, router.chainCalls)
        assertEquals(0, router.auditCalls)
        val batch = db.knowledgeMapBatchDao().getLatest("p-1")!!
        assertTrue(batch.skeletonDone)
        assertTrue(!batch.completed)
        assertTrue(batch.chainStatusList().all { it == KnowledgeMapChainStatus.PENDING })
        // 占位节点已建，标识是短序号 nk-1/nk-2（批次内稳定，好抄好回传）
        val nodes = db.knowledgeNodeDao().getByProject("p-1").sortedBy { it.order }
        assertEquals(listOf("nk-1", "nk-2"), nodes.map { it.nodeKey })
        assertTrue(nodes.all { it.generateStatus == NodeGenerateStatus.PLACEHOLDER.name })
    }

    /**
     * 骨架降级重试（用户反馈：「第一步 map skeleton 总是生成失败」）。
     *
     * 骨架第一次失败（截断/JSON 不完整）后，应「少要点、再问一次」：
     * 更小的输出上限 + 追加精简指令，而不是整批中止。第二次成功即继续。
     */
    @Test
    fun skeletonFailsOnce_downgradesAndRetries() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        router.failSkeletonOnce = true
        seedProject()
        val result = pipeline.generateKnowledgeMap("p-1", KnowledgeMapMode.SKELETON)
        assertTrue("降级重试后应成功，实际：${result.exceptionOrNull()?.message}", result.isSuccess)
        // 骨架被问了两次：第一次失败、第二次降级成功
        assertEquals(2, router.skeletonCalls)
        // 第二次的输出上限被压低（SKELETON_FALLBACK < SKELETON）
        assertTrue(
            "降级重试应压低 maxTokens，实际：${router.skeletonMaxTokens}",
            (router.skeletonMaxTokens[1] ?: 0) < (router.skeletonMaxTokens[0] ?: 0),
        )
        // 第二次追加了精简指令（要求每条链至少 2 个节点）
        assertTrue(
            "降级 prompt 应含精简指令",
            router.skeletonPrompts[1].contains("至少 2 个节点"),
        )
        // 骨架最终落库、占位节点建好
        assertTrue(db.knowledgeMapBatchDao().getLatest("p-1")!!.skeletonDone)
        assertEquals(2, db.knowledgeNodeDao().getByProject("p-1").size)
    }

    /**
     * 配置类错误不降级重试。
     * 没配模型 / Key 无效 / 鉴权失败，降级也没用，直接如实报错，不白烧第二次调用。
     */
    @Test
    fun skeletonAuthFailure_doesNotRetry() = runBlocking {
        router.skeletonJson = skeleton(1)
        router.failSkeletonOnceWithAuth = true
        seedProject()
        val result = pipeline.generateKnowledgeMap("p-1", KnowledgeMapMode.SKELETON)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ModelError.Auth)
        // 鉴权失败只问一次，不降级重试
        assertEquals(1, router.skeletonCalls)
    }

    /** NEXT_CHAIN 模式：一次只推进一条链；最后一条展开完的同一轮里做审计收尾 */
    @Test
    fun nextChainMode_advancesOneChainPerCall() = runBlocking {
        router.skeletonJson = skeleton(2)
        seedProject()
        // 第一步：只展开链 1
        assertTrue(pipeline.generateKnowledgeMap("p-1", KnowledgeMapMode.NEXT_CHAIN).isSuccess)
        assertEquals(1, router.chainCalls)
        assertEquals(0, router.auditCalls)
        val mid = db.knowledgeMapBatchDao().getLatest("p-1")!!
        assertTrue(!mid.completed)
        val midStatuses = mid.chainStatusList()
        assertTrue(midStatuses[0] == KnowledgeMapChainStatus.DONE)
        assertTrue(midStatuses[1] == KnowledgeMapChainStatus.PENDING)

        // 第二步：展开链 2；链全完成 → 审计同轮收尾，批次完成
        assertTrue(pipeline.generateKnowledgeMap("p-1", KnowledgeMapMode.NEXT_CHAIN).isSuccess)
        assertEquals(2, router.chainCalls)
        assertEquals(1, router.auditCalls)
        assertTrue(db.knowledgeMapBatchDao().getLatest("p-1")!!.completed)
        assertEquals(ProjectStatus.MAP, db.projectDao().getById("p-1")?.status)
    }

    /**
     * B1「放弃重来」：已标失败（failed=true）的未完成批次不再被续跑，下一次调用开新批次。
     *
     * 原缺陷：failed 全项目从未被置 true，resume 的 `!it.failed` 恒真——换模型后
     * 想重跑骨架，拿到的仍是旧骨架。「放弃重来」按钮把未完成批次标 failed，
     * 管线必须真的认这个标志。
     */
    @Test
    fun failedBatch_opensFreshSkeletonInsteadOfResuming() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        seedProject()
        // 先只出骨架，留下一个未完成批次
        assertTrue(pipeline.generateKnowledgeMap("p-1", KnowledgeMapMode.SKELETON).isSuccess)
        val firstBatchId = db.knowledgeMapBatchDao().getLatest("p-1")!!.id
        assertEquals(1, router.skeletonCalls)

        // 对照：未标失败的批次会被续跑（骨架不重问）
        assertTrue(pipeline.generateKnowledgeMap("p-1", KnowledgeMapMode.SKELETON).isSuccess)
        assertEquals(1, router.skeletonCalls)
        assertEquals(firstBatchId, db.knowledgeMapBatchDao().getLatest("p-1")!!.id)

        // 模拟「放弃重来」：把未完成批次标 failed
        db.knowledgeMapBatchDao().upsert(
            db.knowledgeMapBatchDao().getLatest("p-1")!!.copy(failed = true),
        )

        // 再调用：开新批次、骨架重问，旧节点整批成功后被替换
        router.skeletonJson = skeleton(1, titles = listOf("X", "Y"))
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        assertEquals(2, router.skeletonCalls)
        val latest = db.knowledgeMapBatchDao().getLatest("p-1")!!
        assertNotEquals(firstBatchId, latest.id)
        assertTrue(latest.completed)
        assertEquals(listOf("X", "Y"), db.knowledgeNodeDao().getByProject("p-1").map { it.title }.sorted())
    }

    /**
     * B3 细分进度：onProgress 按地图自己的单元上报（骨架=1、每条链=1、审计=1），
     * Worker 据此把「正在展开第 2/3 条链」并进 UI 百分比，而不是整张地图卡在 0/1。
     */
    @Test
    fun progressCallback_reportsPerChainUnits() = runBlocking {
        router.skeletonJson = skeleton(2)
        seedProject()
        val events = mutableListOf<com.example.zhishu.ai.pipeline.GenerationProgress>()
        assertTrue(pipeline.generateKnowledgeMap("p-1") { events += it }.isSuccess)
        // 总单元 = 2 条链 + 骨架 + 审计 = 4，所有确定进度的上报都用同一个总数
        assertEquals(
            listOf(4),
            events.filter { it.total > 0 }.map { it.total }.distinct(),
        )
        // 链级细分可见：骨架+链1 完成后 completed=2，且文案点明「演进链」
        assertTrue(events.any { it.completed == 2 && it.label.contains("演进链") })
        // 审计阶段单独可见
        assertTrue(events.any { it.label.contains("全局审计") })
        // 收尾报满
        assertEquals(4, events.last().completed)
        assertEquals(4, events.last().total)
    }

    /** 标识兜底：模型没回传/抄错 nodeKey 但标题与骨架一致时仍回填，不再静默丢节点 */
    @Test
    fun wrongNodeKey_titleFallback_fillsNode() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        seedProject()
        // nodeKey 抄错，标题对
        router.chainCustom[0] = { entries ->
            val aTitle = entries.first { it.second == "A" }.second
            """{"nodes":[{"nodeKey":"nk-typo","title":"$aTitle","definition":"兜底定义","depth":1}]}"""
        }
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val a = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "A" }
        assertEquals(NodeGenerateStatus.COMPLETED.name, a.generateStatus)
        assertEquals("兜底定义", a.definition)
    }

    /** 子词检索：中文长标题逐字不命中原文时，按二元组子词仍能选对相关片段 */
    @Test
    fun subwordMatching_picksRelevantChunk() = runBlocking {
        seedProject(
            chunks = listOf(
                "c-rel" to "要约是一方以订立合同为目的的意思表示，应当内容具体确定。",
                "c-irr" to "与主题毫无关系的一段随机文本。",
            ),
        )
        // 标题「要约的构成要件」整词不会逐字出现在资料原文里，但子词「要约」「意思表示」能命中
        router.skeletonJson = skeleton(1, titles = listOf("要约的构成要件", "意思表示"))
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val prompt = router.chainPrompts.first()
        assertTrue(prompt.contains("c-rel"))
        assertTrue(!prompt.contains("c-irr"))
    }

    // ==================== helpers ====================

    /** 审计 JSON，可带结构化缺口与冲突（gaps 闭环测试用） */
    private fun auditWithInsights(
        gaps: List<String> = emptyList(),
        structuredGaps: List<String> = emptyList(),
        conflicts: List<String> = emptyList(),
    ): String {
        val gapTexts = gaps.joinToString(",") { "\"$it\"" }
        return """{"gaps":[$gapTexts],"structuredGaps":[${structuredGaps.joinToString(",")}],""" +
            """"conflicts":[${conflicts.joinToString(",")}]}"""
    }

    /** 一条/两条链的骨架 JSON。链 1 标题默认 [A, B]；两链时第二条默认 [C, D] */
    private fun skeleton(
        chainCount: Int,
        titles: List<String> = listOf("A", "B"),
        secondChainTitles: List<String> = listOf("C", "D"),
        prefix: String = "",
    ): String {
        val chain1 = """{"theme":"${prefix}链一","opening":"从${prefix}A开始","nodeTitles":${jsonList(titles.map { prefix + it })}}"""
        val chains = buildList {
            add(chain1)
            if (chainCount > 1) {
                add("""{"theme":"${prefix}链二","opening":"从${prefix}C开始","nodeTitles":${jsonList(secondChainTitles.map { prefix + it })}}""")
            }
        }
        return """{"chains":[${chains.joinToString(",")}],"gaps":[],"conflicts":[]}"""
    }

    private fun jsonList(items: List<String>): String =
        "[" + items.joinToString(",") { "\"$it\"" } + "]"

    private suspend fun seedProject(
        chunks: List<Pair<String, String>> = listOf("c-1" to "意思表示是民事法律行为的核心要素。"),
        modelKnowledge: Boolean = false,
    ) {
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
                sourceMode = if (modelKnowledge) SourceMode.MODEL else SourceMode.DOCUMENTS,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        if (modelKnowledge) return
        chunks.forEachIndexed { index, (chunkId, text) ->
            val source = SourceDocument(
                id = "src-$index",
                projectId = "p-1",
                title = "资料 $index",
                kind = SourceKind.TEXT,
                fetchedAt = 1L,
                contentHash = "h$index",
                parseStatus = ParseStatus.PARSED,
            )
            db.sourceDocumentDao().upsert(source)
            db.sourceChunkDao().upsertAll(
                listOf(
                    SourceChunk(
                        id = chunkId,
                        sourceId = source.id,
                        projectId = "p-1",
                        ordinal = index,
                        text = text,
                    ),
                ),
            )
        }
    }

    /** 阶段标记 prompt，与 BatchingRouter 的分发约定一致 */
    private class BatchingPromptProvider : PromptProvider {
        private val map = mapOf(
            "knowledge_map_skeleton" to "[[MAP_SKELETON]]",
            // {chunks} / {filledNodes} 保留真实占位符：子词检索与「重试最小上下文」
            // 两组测试都靠它们断言「到底喂给了模型什么」。
            // {nodeKeyTitles} 必须放在最末——它是多行的「nk-x：标题」清单，
            // 后面再接内容会把最后一行的标题拼进去（真实 prompt 也是放末尾）。
            "knowledge_map_chain" to
                "[[MAP_CHAIN]] 已写节点：{filledNodes} 资料区：{chunks} 节点标识清单：\n{nodeKeyTitles}",
            "knowledge_map_audit" to "[[MAP_AUDIT]]",
        )

        override fun load(name: String): String = map[name] ?: name
    }

    /**
     * 可编程路由器：支持「第几条链失败」「第几条链返回自定义 JSON」「审计失败」。
     * 单链默认按 prompt 里的节点标识清单生成全部节点（带链序号便于断言）。
     */
    private class BatchingRouter : ModelRouter {
        var skeletonJson: String = ""
        var auditJson: String = """{"gaps":[],"conflicts":[]}"""
        /** 让下一次链调用失败（一次性标志，避免受全局调用计数影响） */
        var failNextChain: Boolean = false
        var failAudit: Boolean = false
        /**
         * 让骨架第一次调用失败（一次性），第二次正常返回 [skeletonJson]。
         * 用来验证「精简降级重试」：骨架失败后应再问一次而不是整批中止。
         */
        var failSkeletonOnce: Boolean = false
        /** 让骨架第一次调用返回鉴权错误（一次性）：验证配置类错误不降级重试 */
        var failSkeletonOnceWithAuth: Boolean = false
        /** 第 i 条链的自定义 JSON 生成器，入参为解析出的 (nodeKey, title) 列表 */
        val chainCustom = mutableMapOf<Int, (List<Pair<String, String>>) -> String>()

        var skeletonCalls = 0
        var chainCalls = 0
        var auditCalls = 0
        /** 每次骨架调用请求的 maxTokens，用来断言降级重试压低了输出上限 */
        val skeletonMaxTokens = mutableListOf<Int?>()
        /** 每次骨架调用收到的用户提示词，用来断言降级重试追加了精简指令 */
        val skeletonPrompts = mutableListOf<String>()

        /** 每次链调用收到的用户提示词，用来断言「喂给模型的资料片段选对了没有」 */
        val chainPrompts = mutableListOf<String>()

        /** 每次链调用请求的 maxTokens。null = 沿用设置页配置（那正是要防的） */
        val chainMaxTokens = mutableListOf<Int?>()

        override suspend fun <T> generate(
            role: com.example.zhishu.domain.model.ModelRole,
            request: ModelRequest,
            decoder: JsonDecoder<T>,
            onStats: (ModelCallStats) -> Unit,
            onAttempt: (com.example.zhishu.ai.CallAttempt) -> Unit,
        ): Result<T> {
            val text = when {
                request.user.contains("[[MAP_SKELETON]]") -> {
                    skeletonCalls++
                    skeletonMaxTokens += request.maxTokens
                    skeletonPrompts += request.user
                    if (failSkeletonOnceWithAuth) {
                        failSkeletonOnceWithAuth = false
                        return Result.failure(ModelError.Auth("还没有配置模型"))
                    }
                    if (failSkeletonOnce) {
                        failSkeletonOnce = false
                        return Result.failure(ModelError.Decode("骨架输出被截断，JSON 不完整"))
                    }
                    skeletonJson
                }
                request.user.contains("[[MAP_CHAIN]]") -> {
                    chainPrompts += request.user
                    chainMaxTokens += request.maxTokens
                    val index = chainCalls++
                    if (failNextChain) {
                        failNextChain = false
                        return Result.failure(ModelError.Server("500"))
                    }
                    val entries = parseEntries(request)
                    chainCustom[index]?.invoke(entries) ?: defaultChainJson(entries, index)
                }
                request.user.contains("[[MAP_AUDIT]]") -> {
                    auditCalls++
                    if (failAudit) return Result.failure(ModelError.Server("审计服务不可用"))
                    auditJson
                }
                else -> error("没有匹配的测试数据: ${request.user.take(40)}")
            }
            return try {
                Result.success(decoder.decode(text))
            } catch (e: Exception) {
                Result.failure(ModelError.Decode(e.message ?: "decode failed"))
            }
        }

        /** 从单链 prompt 提取 (nodeKey, title) 对，格式为「nk-xxx：标题」每行一条 */
        private fun parseEntries(request: ModelRequest): List<Pair<String, String>> =
            Regex("""(nk-[^\s：]+)：([^\n]+)""")
                .findAll(request.user)
                .map { it.groupValues[1] to it.groupValues[2].trim() }
                .toList()

        private fun defaultChainJson(entries: List<Pair<String, String>>, chainIndex: Int): String {
            val nodeJson = entries.map { (key, title) ->
                """{"nodeKey":"$key","title":"$title","definition":"$title 定义v$chainIndex",""" +
                    """"arisesFrom":"起点","leadsTo":"下一步","depth":1,"sourceRefs":["c-1"]}"""
            }.joinToString(",")
            return """{"nodes":[$nodeJson]}"""
        }
    }
}
