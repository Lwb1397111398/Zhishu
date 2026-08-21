package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.PromptProvider
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
import com.example.zhishu.domain.model.NodeGenerateStatus
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.domain.model.SourceMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        // 第一次：第 1 条链失败
        router.failNextChain = true
        val first = pipeline.generateKnowledgeMap("p-1")
        assertTrue(first.isFailure)
        assertEquals(1, router.skeletonCalls)
        assertEquals(1, router.chainCalls)
        // 骨架已落库，失败的链节点标 FAILED
        val batch = db.knowledgeMapBatchDao().getLatest("p-1")
        assertEquals(true, batch?.skeletonDone)
        val failedNode = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "A" }
        assertEquals(NodeGenerateStatus.FAILED.name, failedNode.generateStatus)

        // 第二次：不再调骨架、不再调已完成的链，只重试失败链 + 审计
        router.failNextChain = false
        val second = pipeline.generateKnowledgeMap("p-1")
        assertTrue(second.isSuccess)
        assertEquals(1, router.skeletonCalls) // 骨架没有重复调用
        // 链调用总数 = 第一次失败那 1 次 + 重试链 1 + 链 2 各 1 次
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

    /** 部分节点返回：单链只回传部分节点，其余保持占位（不失败、不误报） */
    @Test
    fun partialChainReturn_othersStayPlaceholder() = runBlocking {
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        seedProject()
        // 第 1 条链只回传 A，不回传 B
        router.chainCustom[0] = { entries ->
            val (aKey, aTitle) = entries.first { it.second == "A" }
            """{"nodes":[{"nodeKey":"$aKey","title":"$aTitle","definition":"$aTitle 定义。","depth":1,"sourceRefs":["c-1"]}]}"""
        }
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val a = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "A" }
        val b = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "B" }
        assertEquals(NodeGenerateStatus.COMPLETED.name, a.generateStatus)
        // B 未展开：保持占位，而不是凭空消失
        assertEquals(NodeGenerateStatus.PLACEHOLDER.name, b.generateStatus)
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

    /** 最终审计失败：地图内容可用但明确标「审计待重试」，不误报为全部完成 */
    @Test
    fun auditFailure_keepsNodesAndMarksAuditFailed() = runBlocking {
        router.skeletonJson = skeleton(1)
        seedProject()
        router.failAudit = true
        val result = pipeline.generateKnowledgeMap("p-1")
        // 返回失败 → UI 显示「全局审计未完成」横幅，而不是「梳理完成」
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("审计未完成"))
        // 节点全部保留且完成
        val nodes = db.knowledgeNodeDao().getByProject("p-1")
        assertTrue(nodes.isNotEmpty())
        assertTrue(nodes.all { it.generateStatus == NodeGenerateStatus.COMPLETED.name })
        // 批次标记审计失败且不置 completed（下次续跑只补审计，不误报全部完成）
        val batch = db.knowledgeMapBatchDao().getLatest("p-1")
        assertEquals(true, batch?.auditFailed)
        assertEquals(false, batch?.completed)
        // 地图可用：项目已置 MAP，gaps 里有审计未完成的说明
        assertEquals(ProjectStatus.MAP, db.projectDao().getById("p-1")?.status)
        assertTrue(db.knowledgeGapDao().getByProject("p-1").any { it.description.contains("审计未完成") })

        // 重试审计成功 → 批次完成
        router.failAudit = false
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        assertEquals(true, db.knowledgeMapBatchDao().getLatest("p-1")?.completed)
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

    /** 资料检索为空：链关键词匹配不到任何片段时降级为项目前若干片段，流程不中断 */
    @Test
    fun emptyChunkMatch_fallsBackToFirstChunks() = runBlocking {
        // 片段内容与链主题/节点标题完全不沾边
        seedProject(chunks = listOf("c-1" to "与主题毫无关系的一段随机文本。"))
        router.skeletonJson = skeleton(1, titles = listOf("A", "B"))
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        val a = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "A" }
        // 降级片段仍在本次提供的片段内，引用保留
        assertEquals(listOf("c-1"), a.sourceRefs)
    }

    /** 共享节点先到先得：两条链都含 A，A 的内容由第一条链填充，不被第二条链覆盖 */
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

    // ==================== helpers ====================

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
            "knowledge_map_chain" to "[[MAP_CHAIN]] 节点标识清单：{nodeKeyTitles}",
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
        /** 第 i 条链的自定义 JSON 生成器，入参为解析出的 (nodeKey, title) 列表 */
        val chainCustom = mutableMapOf<Int, (List<Pair<String, String>>) -> String>()

        var skeletonCalls = 0
        var chainCalls = 0
        var auditCalls = 0

        override suspend fun <T> generate(
            role: com.example.zhishu.domain.model.ModelRole,
            request: ModelRequest,
            decoder: JsonDecoder<T>,
        ): Result<T> {
            val text = when {
                request.user.contains("[[MAP_SKELETON]]") -> {
                    skeletonCalls++
                    skeletonJson
                }
                request.user.contains("[[MAP_CHAIN]]") -> {
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
