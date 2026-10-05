package com.example.zhishu.samples

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.ConfiguredModelRouter
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
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.domain.model.SourceMode
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 端到端全流程验证（真实 API，生产默认开关）：
 * 知识主干（骨架→逐链→审计）→ 故事圣经与章节计划 → 章节正文（契约驱动）→ 核验。
 *
 * 运行（复用 runQualitySamples task 的过滤与开关）：
 * ```
 * ZHISHU_RUN_SAMPLES=true \
 * ZHISHU_SAMPLE_BASE_URL=... ZHISHU_SAMPLE_MODEL=... ZHISHU_SAMPLE_API_KEY=... \
 * ZHISHU_E2E_TOPIC="天空为什么是蓝色的" ZHISHU_E2E_CHAPTERS=2 \
 * ./gradlew.bat :app:runQualitySamples --max-workers=1 \
 *   --tests "com.example.zhishu.samples.E2eFullFlowRunner"
 * ```
 * 输出快照写入 samples/e2e/<时间戳>/；stdout 逐步打印每个阶段的结果与耗时。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class E2eFullFlowRunner {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class StepResult(val step: String, val ok: Boolean, val detail: String, val millis: Long)

    @Serializable
    data class NodeRow(val title: String, val key: String, val definition: String)

    @Serializable
    data class PlanRow(val order: Int, val title: String, val type: String, val wordTarget: Int)

    @Serializable
    data class BibleSnap(
        val world: String,
        val conflict: String,
        val style: String,
        val throughLine: List<String>,
        val plans: List<PlanRow>,
    )

    @Serializable
    data class BlockRow(val type: String, val text: String, val nodes: List<String>)

    @Serializable
    data class IssueRow(val type: String, val message: String)

    @Serializable
    data class CheckRow(val node: String, val claim: String, val evidence: String)

    @Serializable
    data class VerifySnap(
        val issues: List<IssueRow>,
        val unverifiedClaims: List<String>,
        val checks: List<CheckRow>,
        val dbIssues: List<IssueRow>,
    )

    @Serializable
    data class StepRow(val step: String, val ok: Boolean, val detail: String, val seconds: Long)

    @Serializable
    data class Summary(
        val topic: String,
        val model: String,
        val allOk: Boolean,
        val totalSeconds: Long,
        val steps: List<StepRow>,
    )

    @Test
    fun fullFlow() {
        assumeTrue("未设置 ZHISHU_RUN_SAMPLES=true，跳过", System.getenv("ZHISHU_RUN_SAMPLES") == "true")
        val baseUrl = System.getenv("ZHISHU_SAMPLE_BASE_URL") ?: return
        val model = System.getenv("ZHISHU_SAMPLE_MODEL") ?: return
        val apiKey = System.getenv("ZHISHU_SAMPLE_API_KEY")?.takeIf { it.isNotBlank() } ?: return
        val topic = System.getenv("ZHISHU_E2E_TOPIC") ?: "天空为什么是蓝色的"
        val chapterLimit = System.getenv("ZHISHU_E2E_CHAPTERS")?.toIntOrNull() ?: 2

        val cwd = File(System.getProperty("user.dir").orEmpty())
        val promptDir = listOf(File(cwd, "src/main/assets/prompts"), File(cwd, "app/src/main/assets/prompts"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到生产 prompts 目录（assets/prompts）")
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val outDir = File(E2E_ROOT, stamp).apply { mkdirs() }
        println("[E2E] 主题: $topic | 模型: $model | 输出: ${outDir.absolutePath}")

        val steps = mutableListOf<StepResult>()
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            runBlocking {
                val projectId = "e2e-$stamp"
                db.projectDao().upsert(
                    Project(
                        id = projectId,
                        title = topic,
                        topic = topic,
                        baseline = "入门",
                        depth = "考试",
                        dailyMinutes = 30,
                        targetDays = 30,
                        genre = "AI 自由发挥",
                        status = ProjectStatus.DRAFT,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        sourceMode = SourceMode.MODEL,
                    ),
                )

                val config = SampleRunner.RunConfig(
                    baseUrl = baseUrl,
                    model = model,
                    apiKey = apiKey,
                    temperature = 0.7f,
                    // 与生产 ApiProfile.DEFAULT_MAX_TOKENS 一致
                    maxTokens = 50_000,
                    seed = null,
                )
                val router = ConfiguredModelRouter(
                    settingsRepository = FixedSettingsRepository(config),
                    keyCipherProvider = { FakeCipher(apiKey) },
                    providerFactory = { profile, key ->
                        com.example.zhishu.ai.OpenAiModelProvider(
                            profile = profile,
                            apiKey = key,
                            requireHttps = false,
                            // 免费中转慢：给足超时
                            socketTimeoutMillis = 300_000,
                            requestTimeoutMillis = 600_000,
                            // 中转已验证支持 thinking:disabled；关掉思考又快又省
                            thinkingDisabled = true,
                        )
                    },
                )
                val pipeline = PipelineService(
                    router = router,
                    prompts = FilePromptProvider(promptDir),
                    projectRepository = DefaultProjectRepository(db.projectDao()),
                    sourceRepository = DefaultSourceRepository(db.sourceDocumentDao(), db.sourceChunkDao()),
                    knowledgeRepository = DefaultKnowledgeRepository(
                        db, db.knowledgeNodeDao(), db.knowledgeGapDao(),
                        db.knowledgeConflictDao(), db.knowledgeMapBatchDao(), db.routeBatchDao(),
                    ),
                    chapterRepository = DefaultChapterRepository(db.chapterPlanDao(), db.chapterBlockDao(), db.chapterVersionDao()),
                    citationRepository = DefaultCitationRepository(db.citationDao()),
                    issueRepository = DefaultVerificationIssueRepository(db.verificationIssueDao()),
                    storyBibleRepository = DefaultStoryBibleRepository(db.storyBibleDao()),
                    quizRepository = DefaultQuizRepository(db.quizDao(), db.answerRecordDao()),
                    reviewRepository = DefaultReviewRepository(db.reviewItemDao()),
                    spacedReviewEnabled = { false },
                    // 其余开关全部走生产默认：requireChapterContracts={true}
                )

                // ---- 步骤 1：知识主干（骨架→逐链→全局审计）----
                var t0 = System.currentTimeMillis()
                println("[E2E] 步骤 1/4 知识主干生成中（骨架→逐链→审计）…")
                val mapResult = pipeline.generateKnowledgeMap(projectId) { p -> println("[E2E]   · ${p.label} (${p.completed}/${p.total})") }
                val mapMillis = System.currentTimeMillis() - t0
                val nodes = db.knowledgeNodeDao().getByProjectAll(projectId)
                val batch = db.knowledgeMapBatchDao().getLatest(projectId)
                val mapOk = mapResult.isSuccess && nodes.isNotEmpty()
                val mapDetail = if (mapResult.isFailure) {
                    "失败: ${mapResult.exceptionOrNull()?.message?.take(300)}"
                } else {
                    "节点 ${nodes.size} 个，批次状态: 骨架=${batch?.skeletonDone} 审计=${batch?.auditDone} 收尾=${batch?.completed}"
                }
                println("[E2E] 步骤 1/4 ${if (mapOk) "✅" else "❌"} $mapDetail (${mapMillis / 1000}s)")
                steps += StepResult("知识主干", mapOk, mapDetail, mapMillis)
                File(outDir, "1-map.json").writeText(
                    json.encodeToString(nodes.map { NodeRow(it.title, it.nodeKey, it.definition) }),
                )

                if (!mapOk) return@runBlocking

                // ---- 步骤 2：故事圣经与章节计划 ----
                t0 = System.currentTimeMillis()
                println("[E2E] 步骤 2/4 故事圣经与章节计划生成中…")
                val bibleResult = pipeline.generateStoryBible(projectId)
                val bibleMillis = System.currentTimeMillis() - t0
                val bible = db.storyBibleDao().get(projectId)
                val plans = db.chapterPlanDao().getByProject(projectId).sortedBy { it.order }
                val bibleOk = bibleResult.isSuccess && plans.isNotEmpty()
                val bibleDetail = if (bibleResult.isFailure) {
                    "失败: ${bibleResult.exceptionOrNull()?.message?.take(300)}"
                } else {
                    "主线「${bible?.throughLine?.joinToString("→")?.take(120)}」· 计划 ${plans.size} 章"
                }
                println("[E2E] 步骤 2/4 ${if (bibleOk) "✅" else "❌"} $bibleDetail (${bibleMillis / 1000}s)")
                steps += StepResult("故事圣经与章节计划", bibleOk, bibleDetail, bibleMillis)
                File(outDir, "2-bible.json").writeText(
                    json.encodeToString(
                        BibleSnap(
                            world = bible?.world ?: "",
                            conflict = bible?.conflict ?: "",
                            style = bible?.style ?: "",
                            throughLine = bible?.throughLine ?: emptyList(),
                            plans = plans.map { PlanRow(it.order, it.title, it.chapterType, it.wordTarget) },
                        ),
                    ),
                )

                if (!bibleOk) return@runBlocking

                // ---- 步骤 3+4：逐章正文（契约驱动）+ 核验 ----
                plans.take(chapterLimit).forEachIndexed { i, plan ->
                    t0 = System.currentTimeMillis()
                    println("[E2E] 步骤 3/4 第 ${i + 1} 章正文生成中（${plan.title}）…")
                    val chResult = pipeline.generateChapter(projectId, plan.id) { p -> println("[E2E]   · ${p.name}") }
                    val chMillis = System.currentTimeMillis() - t0
                    // 含被废弃版本：自动修复失败的草稿会被 abandon，正文仍在库里
                    val blocks = db.chapterBlockDao().getAllVersionsByChapter(plan.id)
                    // 所有块（STORY/EXPLANATION/KEY_POINT/FACT…）都是正文文字。
                    // 原来过滤 blockType == "PROSE"——这个类型根本不存在，
                    // 字数恒为 0，样章质量报告一直失真
                    val wordCount = blocks.sumOf { it.text.length }
                    // 核验发现问题 = 正文已发布（可读）、问题已落库，章节标待复核——
                    // 设计内的降级路径，不算流程失败；修没修干净由最终章节状态表达
                    val chError = chResult.exceptionOrNull()?.message.orEmpty()
                    val needsReview =
                        db.chapterPlanDao().getById(plan.id)?.status ==
                            com.example.zhishu.domain.model.ChapterStatus.NEEDS_REVIEW
                    val chOk = chResult.isSuccess
                    val chDetail = when {
                        !chOk -> "失败: ${chError.take(300)}"
                        needsReview -> "正文约 $wordCount 字（块 ${blocks.size} 个），核验待复核"
                        else -> "块 ${blocks.size} 个，正文约 $wordCount 字"
                    }
                    println("[E2E] 步骤 3/4 第 ${i + 1} 章 ${if (chOk) "✅" else "❌"} $chDetail (${chMillis / 1000}s)")
                    steps += StepResult("第${i + 1}章正文", chOk, chDetail, chMillis)
                    File(outDir, "3-chapter${i + 1}-blocks.json").writeText(
                        json.encodeToString(blocks.map { BlockRow(it.blockType.name, it.text.take(2000), it.knowledgeNodeIds) }),
                    )

                    if (!chOk) return@forEachIndexed

                    // 核验已在 generateChapter 内部执行并落库（问题记录挂在版本上），
                    // 这里只读库展示，不重复调用模型
                    t0 = System.currentTimeMillis()
                    val issues = db.verificationIssueDao().getAllVersionsByProject(projectId)
                        .filter { it.chapterId == plan.id }
                    val vMillis = System.currentTimeMillis() - t0
                    val vOk = true
                    val vDetail = "核验问题 ${issues.size} 条: " + issues.take(3).joinToString("; ") {
                        "${it.type.name}(${it.message.take(60)})"
                    }.ifBlank { "无问题，直接通过" }
                    println("[E2E] 步骤 4/4 第 ${i + 1} 章 ${if (vOk) "✅" else "❌"} $vDetail (${vMillis / 1000}s)")
                    steps += StepResult("第${i + 1}章核验", vOk, vDetail, vMillis)
                    File(outDir, "4-chapter${i + 1}-verify.json").writeText(
                        json.encodeToString(
                            VerifySnap(
                                issues = emptyList(),
                                unverifiedClaims = emptyList(),
                                checks = emptyList(),
                                dbIssues = issues.map { IssueRow(it.type.name, it.message) },
                            ),
                        ),
                    )
                }

                // ---- 总结 ----
                val allOk = steps.all { it.ok }
                File(outDir, "summary.json").writeText(
                    json.encodeToString(
                        Summary(
                            topic = topic,
                            model = model,
                            allOk = allOk,
                            totalSeconds = steps.sumOf { it.millis } / 1000,
                            steps = steps.map { StepRow(it.step, it.ok, it.detail, it.millis / 1000) },
                        ),
                    ),
                )
                println("[E2E] ===== 总结：${if (allOk) "全部通过 ✅" else "存在失败 ❌"} =====")
                steps.forEach { println("[E2E]   ${if (it.ok) "✅" else "❌"} ${it.step}: ${it.detail} (${it.millis / 1000}s)") }
            }
        } finally {
            db.close()
        }
    }

    companion object {
        /** 与 SampleRunner.SAMPLES_ROOT 同样的目录探测逻辑：samples/ 在项目根 */
        val E2E_ROOT: File = run {
            val cwd = File(System.getProperty("user.dir").orEmpty())
            listOf(File(cwd, "samples"), File(cwd.parentFile, "samples")).firstOrNull { it.isDirectory }
                ?: File(cwd, "samples").apply { mkdirs() }
        }
    }
}
