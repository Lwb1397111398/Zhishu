package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.ModelCallStats
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
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceMode
import java.io.File
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
 * 单链 prompt 不得随「已完成链数」增长的回归测试。
 *
 * 根因（「后面的链有的成功有的失败，失败的永远失败」）：单链 prompt 里的
 * `filledNodes` 原本带**全项目**所有已完成节点，于是 prompt 随已完成链数线性膨胀
 * ——实测 12 条链从 1738 涨到 8288 字符，放大 4.8 倍。叠加设置页默认 5 万的
 * max_tokens，服务商按「prompt_tokens + max_tokens ≤ 上下文窗口」校验，越界返回
 * 400 → ModelError.InvalidRequest 的 retryable=false → 该链**永久**失败；
 * 而重试时别的链只填得更多，prompt 更长，于是越重试越死。
 *
 * 这个测试用真实 assets prompt 跑一个大学科，钉住两件事：
 * 末条 prompt 不比首条长多少，且整体不随链数漂移。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChainPromptGrowthProbe {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService
    private lateinit var router: ProbeRouter
    private var idCounter = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        router = ProbeRouter()
        pipeline = PipelineService(
            router = router,
            prompts = RealAssetPrompts(),
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
            chapterRepository = DefaultChapterRepository(
                db.chapterPlanDao(), db.chapterBlockDao(), db.chapterVersionDao(),
            ),
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
    fun tearDown() = db.close()

    @Test
    fun `单链 prompt 不随已完成链数增长`() = runBlocking {
        db.projectDao().upsert(
            Project(
                id = "p-1",
                title = "系统掌握民法",
                topic = "民法",
                baseline = "入门",
                depth = "专业",
                dailyMinutes = 20,
                targetDays = 30,
                genre = "悬疑",
                status = ProjectStatus.DRAFT,
                // 用户的默认模式：AI 自备知识
                sourceMode = SourceMode.MODEL,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        router.skeletonJson = bigSkeleton(chains = 12, nodesPerChain = 8)

        pipeline.generateKnowledgeMap("p-1")

        val lengths = router.chainPrompts.map { it.length }
        println("=== 单链 prompt 长度（字符） ===")
        lengths.forEachIndexed { i, len -> println("第 ${i + 1} 次调用：$len") }

        val first = lengths.first()
        val longest = lengths.max()
        println("首次 $first → 最长 $longest，放大 ${"%.2f".format(longest.toDouble() / first)} 倍")

        // 修复前：12 条链 1738 → 8288（4.8 倍）。留到 1.5 倍的余量，
        // 只要有人把 filledNodes 改回「全项目累计」，这条线就会被顶穿。
        assertTrue(
            "单链 prompt 随链数膨胀了（首次 $first，最长 $longest）——" +
                "filledNodes 又带上了本链用不到的节点？",
            longest <= first * 3 / 2,
        )
        // 后半程不得比前半程系统性地长：真正要防的是「随链数漂移」
        val half = lengths.size / 2
        val frontMax = lengths.take(half).max()
        val backMax = lengths.drop(half).max()
        assertTrue(
            "后半程 prompt（$backMax）明显长于前半程（$frontMax），说明仍在累积",
            backMax <= frontMax + 200,
        )
    }

    /** 长链必须分批问：一次要求写完 20 个节点，输出注定超长，整轮白费 */
    @Test
    fun `长链一轮不会问超过六个节点`() = runBlocking {
        db.projectDao().upsert(
            Project(
                id = "p-1",
                title = "系统掌握民法",
                topic = "民法",
                baseline = "入门",
                depth = "专业",
                dailyMinutes = 20,
                targetDays = 30,
                genre = "悬疑",
                status = ProjectStatus.DRAFT,
                sourceMode = SourceMode.MODEL,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        // 一条 20 节点的长链（骨架对链长不设上限，真实大学科常见）
        router.skeletonJson = bigSkeleton(chains = 1, nodesPerChain = 20)

        val result = pipeline.generateKnowledgeMap("p-1")

        val perPass = router.chainPrompts.map { prompt ->
            prompt.substringAfter("节点标识清单（nodeKey：标题，原样使用）：")
                .lines().count { it.trim().startsWith("nk-") }
        }
        println("每轮问的节点数：$perPass（共 ${perPass.size} 轮）")
        assertTrue("一轮问了 ${perPass.max()} 个节点，超过封顶 6 个", perPass.max() <= 6)
        // 分批之后必须仍能把 20 个节点全部填满，不能因为轮数不够而失败
        assertTrue("长链分批后应当能填满，实际失败：${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(
            20,
            db.knowledgeNodeDao().getByProjectAll("p-1")
                .count { it.generateStatus == NodeGenerateStatus.COMPLETED.name },
        )
    }

    /** chains 条链、每条 nodesPerChain 个节点，标题为真实长度的中文 */
    private fun bigSkeleton(chains: Int, nodesPerChain: Int): String {
        val list = (0 until chains).map { c ->
            val titles = (0 until nodesPerChain).map { n ->
                "\"第${c + 1}链的知识点${n + 1}：意思表示与法律行为效力\""
            }
            """{"theme":"第${c + 1}条链在回答什么问题","opening":"从学习者能感知的处境开始","nodeTitles":[${titles.joinToString(",")}]}"""
        }
        return """{"chains":[${list.joinToString(",")}],"gaps":[],"conflicts":[]}"""
    }

    /** 直接读 assets 里的真实 prompt 文件 */
    private class RealAssetPrompts : PromptProvider {
        override fun load(name: String): String =
            File("src/main/assets/prompts/$name.txt").readText()
    }

    private class ProbeRouter : ModelRouter {
        var skeletonJson: String = ""
        val chainPrompts = mutableListOf<String>()

        override suspend fun <T> generate(
            role: com.example.zhishu.domain.model.ModelRole,
            request: ModelRequest,
            decoder: JsonDecoder<T>,
            onStats: (ModelCallStats) -> Unit,
            onAttempt: (com.example.zhishu.ai.CallAttempt) -> Unit,
        ): Result<T> {
            val text = when {
                request.user.contains("骨架规划") -> skeletonJson
                request.user.contains("节点标识清单") -> {
                    chainPrompts += request.user
                    fullChainJson(request.user)
                }
                else -> """{"gaps":[],"conflicts":[]}"""
            }
            return Result.success(decoder.decode(text))
        }

        /** 按清单把每个节点都写满（模拟一切顺利），让 filledNodes 自然累积 */
        private fun fullChainJson(prompt: String): String {
            val entries = prompt.substringAfter("节点标识清单（nodeKey：标题，原样使用）：")
                .lines().map { it.trim() }.filter { it.startsWith("nk-") }
                .mapNotNull { line ->
                    val key = line.substringBefore("：").trim()
                    val title = line.substringAfter("：").trim()
                    if (key.isEmpty() || title.isEmpty()) null else key to title
                }
            val nodes = entries.map { (key, title) ->
                """{"nodeKey":"$key","title":"$title",""" +
                    """"definition":"这个概念是为了回答交易安全与意思自治之间的张力才出现的，它解决的是表示与内心真意不一致时的归责问题。",""" +
                    """"arisesFrom":"上一个制度留下的归责空白","leadsTo":"仍未解决第三人信赖的保护边界",""" +
                    """"prerequisites":["前置概念"],"related":["相关概念"],"confusions":["容易和通谋虚伪表示混"],""" +
                    """"examples":["买卖合同中写错价格"],"sourceRefs":[],"parentTitle":null,"depth":1}"""
            }
            return """{"nodes":[${nodes.joinToString(",")}]}"""
        }
    }
}
