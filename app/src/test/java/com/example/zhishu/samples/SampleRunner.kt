package com.example.zhishu.samples

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.CallAttempt
import com.example.zhishu.ai.ConfiguredModelRouter
import com.example.zhishu.ai.ModelCallStats
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.PromptProvider
import com.example.zhishu.ai.UsageInfo
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
import com.example.zhishu.domain.model.KnowledgeMapBatch
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.domain.model.SourceMode
import com.example.zhishu.domain.repository.SettingsRepository
import com.example.zhishu.security.KeyCipher
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 批次 B：真实模型质量样本运行器（B.0 加固版）。
 *
 * ## 运行方式（显式、双保险）
 *
 * ```
 * ZHISHU_RUN_SAMPLES=true \
 * ZHISHU_SAMPLE_BASE_URL=... ZHISHU_SAMPLE_MODEL=... ZHISHU_SAMPLE_API_KEY=... \
 * ./gradlew.bat :app:runQualitySamples --max-workers=1
 * ```
 *
 * - 只通过专门 Gradle task `runQualitySamples` 运行（build.gradle.kts 里常规
 *   testDebugUnitTest 已排除 `samples` 包，不会计入常规测试统计）。
 * - 必须同时满足「显式开关 ZHISHU_RUN_SAMPLES=true」与「API Key 已配置」才运行；
 *   有 Key 但没开关、或有开关但没 Key 都跳过，避免开发机长期配置 Key 时误扣费。
 * - 输出目录按 runId 区分，不覆盖旧运行；单主题失败保留已完成快照。
 *
 * ## 控制变量（B.0-2 / 批次 B 运行策略）
 *
 * 地图输入两组共用：每个主题先生成一次**固定地图**（fixed-maps/<theme>.json），
 * legacy 与 new 组都注入同一份地图再各自规划路线与正文——最终差异只归因于
 * 路线/正文/核验 Prompt 的差异，而不是地图的随机性。
 *
 * ## 记录（B.0-5 / B.0-6）
 *
 * calls.jsonl 一行 = 一次 HTTP 尝试（含失败的尝试，不消失）；完整有效请求存
 * artifacts/requests/、原始响应存 artifacts/responses/（默认不提交）。
 * API Key 只进环境变量；Authorization 头、Key、Keystore 绝不落盘。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SampleRunner {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** calls.jsonl 用紧凑单行（一行一条 HTTP 尝试），便于逐行解析 */
    private val compactJson = Json { ignoreUnknownKeys = true }

    internal data class RunConfig(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val temperature: Float,
        val maxTokens: Int,
        val seed: String?,
    )

    @Test
    fun runQualitySamples() {
        // 双保险：显式开关 + API Key 缺一不可（B.0-7）
        assumeTrue("未设置 ZHISHU_RUN_SAMPLES=true，跳过真实样本", System.getenv("ZHISHU_RUN_SAMPLES") == "true")
        val config = configFromEnv()
        assumeTrue("ZHISHU_SAMPLE_API_KEY 未配置，跳过真实样本（不消耗额度）", config != null)
        runBlocking { runAll(config!!) }
    }

    /**
     * 批次 B 第一步：连通性探针（最小请求，不计入质量样本，单独存档 samples/probes/）。
     *
     * 确认：endpoint 路径 / 模型支持 JSON object / seed 是否生效 / usage 是否返回 /
     * finish_reason 是否可用 / max tokens 参数名兼容 / 请求与响应脱敏正确。
     * 探针响应可能含资料/业务内容，但与正式样本一样：samples/ 已在 .gitignore 排除。
     */
    @Test
    fun connectivityProbe() {
        assumeTrue("未设置 ZHISHU_RUN_SAMPLES=true，跳过探针", System.getenv("ZHISHU_RUN_SAMPLES") == "true")
        val config = configFromEnv() ?: return
        val probeDir = File(SAMPLES_ROOT, "probes").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())

        val provider = com.example.zhishu.ai.OpenAiModelProvider(
            config = com.example.zhishu.domain.model.ModelConfig(
                role = ModelRole.STORY.name,
                baseUrl = config.baseUrl,
                modelName = config.model,
                maxTokens = config.maxTokens,
                temperature = 0.2f,
                supportsJson = true,
                keyRef = "stub",
            ),
            apiKey = config.apiKey,
            requireHttps = false,
            // 推理模型思考阶段长时间无数据，样本用更长超时（生产默认 180s/120s 不变）
            socketTimeoutMillis = 300_000,
            requestTimeoutMillis = 600_000,
            // 样本评估契约/路线/核验，不需要长思考：把推理模型当普通模型用
            thinkingDisabled = true,
        )
        val request = ModelRequest(
            role = ModelRole.STORY,
            system = "你是连通性探针。只输出 JSON 对象。",
            user = "请输出 {\"ok\":true}",
            jsonMode = true,
            temperature = 0.2f,
            maxTokens = 128,
        )
        val startedAt = System.currentTimeMillis()
        var usage: UsageInfo? = null
        var raw: String? = null
        val result = runBlocking {
            provider.complete(request, onUsage = { usage = it }, onRawResponse = { raw = it })
        }
        val latency = System.currentTimeMillis() - startedAt
        val finishReason = raw?.let { Regex("\"finish_reason\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

        val probe = ProbeSnapshot(
            probedAt = System.currentTimeMillis(),
            endpoint = config.baseUrl.trimEnd('/') + "/chat/completions",
            model = config.model,
            maxTokens = config.maxTokens,
            temperature = 0.2f,
            jsonMode = true,
            supportsSeed = false,
            seedNote = "服务商未声明 seed 支持，视为不具备完全可复现性（记录，不作断言）",
            success = result.isSuccess,
            error = result.exceptionOrNull()?.message?.take(200),
            latencyMillis = latency,
            usageReported = usage != null,
            promptTokens = usage?.promptTokens ?: 0,
            completionTokens = usage?.completionTokens ?: 0,
            totalTokens = usage?.totalTokens ?: 0,
            finishReason = finishReason,
            responseHasAuthLeak = raw?.contains("Authorization") == true || raw?.contains("sk-") == true,
            responsePreview = raw?.take(300),
        )
        File(probeDir, "probe-$stamp.json").writeText(json.encodeToString(probe))
    }

    private suspend fun runAll(config: RunConfig) {
        val runId = "run-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val outDir = File(SAMPLES_ROOT, "runs/$runId").apply { mkdirs() }
        File(outDir, "requests").mkdirs()
        File(outDir, "responses").mkdirs()
        File(outDir, "fixed-maps").mkdirs()

        val legacyHash = promptDirHash(File(SAMPLES_ROOT, "prompts-legacy"))
        val newHash = promptDirHash(File(SAMPLES_ROOT, "prompts-new"))

        val manifest = RunManifest(
            runId = runId,
            createdAt = System.currentTimeMillis(),
            dbVersion = AppDatabaseVersion,
            model = config.model,
            baseUrlHost = runCatching { java.net.URI(config.baseUrl).host }.getOrNull().orEmpty(),
            supportsSeed = config.seed != null,
            seed = config.seed,
            promptVersionLegacy = legacyHash,
            promptVersionNew = newHash,
            themes = listOf(
                ThemeSpec(id = "os", mode = "MODEL", topic = "操作系统中的进程、虚拟内存与文件系统", docText = null),
                ThemeSpec(id = "sky", mode = "MODEL", topic = "天空为什么是蓝色的", docText = null),
                ThemeSpec(id = "inheritance", mode = "DOCUMENTS", topic = "民法继承：遗嘱与法定继承",
                    docText = INHERITANCE_DOC),
            ),
        )
        File(outDir, "run-manifest.json").writeText(json.encodeToString(manifest))

        val callRecords = mutableListOf<CallRecord>()
        // calls.jsonl 即时追加写入：单主题/单阶段崩溃也不丢已完成的调用记录（B.0-7 断点续跑）
        val callsFile = File(outDir, "calls.jsonl")
        fun appendRecord(record: CallRecord) {
            callRecords += record
            callsFile.appendText(compactJson.encodeToString(record) + "\n")
        }

        // 单主题/单变体试运行支持：ZHISHU_SAMPLE_SUBJECTS=sky、ZHISHU_SAMPLE_VARIANTS=new
        val subjects = System.getenv("ZHISHU_SAMPLE_SUBJECTS")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val variants = System.getenv("ZHISHU_SAMPLE_VARIANTS")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        // 每组重复次数（批次 B 后续：n=2）：ZHISHU_SAMPLE_REPEATS=2
        val repeats = System.getenv("ZHISHU_SAMPLE_REPEATS")?.toIntOrNull() ?: 1

        manifest.themes.filter { subjects == null || it.id in subjects!! }.forEach { theme ->
            // 固定地图：legacy 与 new 共用同一份（B.0-2 控制变量）
            val fixedMap = ensureFixedMap(config, theme, outDir, ::appendRecord)
            repeat(repeats) { rep ->
                val suffix = if (repeats > 1) "-r${rep + 1}" else ""
                if (variants == null || "legacy" in variants!!) {
                    runOnce(config, theme, "legacy$suffix", File(SAMPLES_ROOT, "prompts-legacy"), legacyHash,
                        runId, outDir, fixedMap, ::appendRecord, requireContracts = false)
                }
                if (variants == null || "new" in variants!!) {
                    runOnce(config, theme, "new$suffix", File(SAMPLES_ROOT, "prompts-new"), newHash,
                        runId, outDir, fixedMap, ::appendRecord, requireContracts = true)
                }
            }
        }

        val metrics = SampleMetrics.compute(callRecords, File(outDir, "artifacts"))
        File(outDir, "metrics.json").writeText(json.encodeToString(metrics))
        File(outDir, "rating-template.md").writeText(RatingTemplate.render(manifest))
    }

    /**
     * 固定地图：每主题生成一次（用 new 目录的地图 Prompt——地图 Prompt 两组相同），
     * 序列化到 fixed-maps/<theme>.json；已存在则直接复用（不重复调用、不覆盖）。
     */
    private suspend fun ensureFixedMap(
        config: RunConfig,
        theme: ThemeSpec,
        outDir: File,
        appendRecord: (CallRecord) -> Unit,
    ): FixedMapSnapshot {
        val file = File(SAMPLES_ROOT, "fixed-maps/${theme.id}.json")
        if (file.isFile) {
            return json.decodeFromString<FixedMapSnapshot>(file.readText())
        }
        val db = newDb()
        try {
            db.projectDao().upsert(project(theme))
            if (theme.mode == "DOCUMENTS" && theme.docText != null) seedDocument(db, theme.docText!!)

            val stage = StageHolder()
            val recorder = RecordingRouter(outDir, { stage.current }, { theme.id }, { "map" }, { "new" },
                config.model, appendRecord)
            val pipeline = buildPipeline(db, recorder, File(SAMPLES_ROOT, "prompts-new"), config, theme,
                requireContracts = true)
            val mapResult = stage.around("KNOWLEDGE_MAP") { pipeline.generateKnowledgeMap(theme.id) {} }

            // 地图生成失败不写固定地图快照（文件保持不存在，下次运行会重试生成），
            // 失败原因落盘便于诊断
            if (mapResult.isFailure) {
                File(SAMPLES_ROOT, "fixed-maps/${theme.id}-error.txt").writeText(
                    mapResult.exceptionOrNull()?.stackTraceToString() ?: "unknown failure",
                )
                return@ensureFixedMap FixedMapSnapshot(themeId = theme.id, nodes = emptyList(), createdAt = 0L)
            }
            val nodes = db.knowledgeNodeDao().getByProjectAll(theme.id)
            if (nodes.isEmpty()) {
                return@ensureFixedMap FixedMapSnapshot(themeId = theme.id, nodes = emptyList(), createdAt = 0L)
            }
            // 生效批次一并快照：路线阶段 getByProject 按 active 批次过滤，批次缺失会让地图读空
            val batch = db.knowledgeMapBatchDao().getLatest(theme.id)
            val snapshot = FixedMapSnapshot(
                themeId = theme.id,
                nodes = nodes,
                batch = batch?.copy(active = true, completed = true, failed = false),
                createdAt = System.currentTimeMillis(),
            )
            file.writeText(json.encodeToString(snapshot))
            return snapshot
        } finally {
            db.close()
        }
    }

    private suspend fun runOnce(
        config: RunConfig,
        theme: ThemeSpec,
        variant: String,
        promptDir: File,
        promptHash: String,
        runId: String,
        outDir: File,
        fixedMap: FixedMapSnapshot,
        appendRecord: (CallRecord) -> Unit,
        requireContracts: Boolean,
    ) {
        val db = newDb()
        try {
            // 固定地图生成失败：跳过本变体（保留已完成快照，可断点续跑）
            if (fixedMap.nodes.isEmpty()) {
                File(outDir, "artifacts/${theme.id}-$variant-status.json").apply { parentFile.mkdirs() }
                    .writeText(json.encodeToString(RunStatus(ok = false, reason = "固定地图生成失败，本变体跳过")))
                return
            }
            // 注入固定地图（legacy/new 从同一份地图出发）。
            // 先注入生效批次（active=1）再注入节点——getByProject 按批次过滤，
            // 批次缺失会让路线阶段读到空地图
            fixedMap.batch?.let { db.knowledgeMapBatchDao().upsert(it) }
            db.projectDao().upsert(project(theme).copy(status = ProjectStatus.MAP))
            if (fixedMap.nodes.isNotEmpty()) db.knowledgeNodeDao().upsertAll(fixedMap.nodes)
            if (theme.mode == "DOCUMENTS" && theme.docText != null) seedDocument(db, theme.docText!!)

            val stage = StageHolder()
            val recorder = RecordingRouter(outDir, { stage.current }, { theme.id }, { variant }, { promptHash },
                config.model, appendRecord)
            val pipeline = buildPipeline(db, recorder, promptDir, config, theme, requireContracts)

            val bibleResult = stage.around("STORY_BIBLE") { pipeline.generateStoryBible(theme.id) }
            if (bibleResult.isSuccess) {
                val plans = db.chapterPlanDao().getByProject(theme.id).sortedBy { it.order }.take(2)
                plans.forEachIndexed { i, plan ->
                    val chResult = pipeline.generateChapter(theme.id, plan.id) { phase -> stage.current = phase.name }
                    val blocks = db.chapterBlockDao().getActiveBlocks(plan.id)
                    val issues = db.verificationIssueDao().getAllVersionsByProject(theme.id)
                    File(outDir, "artifacts/${theme.id}-$variant-chapter${i + 1}-blocks.json").apply { parentFile.mkdirs() }
                        .writeText(json.encodeToString(blocks.map { BlockSnapshot(it.order, it.blockType.name, it.text, it.knowledgeNodeIds, it.sourceRefs, it.id) }))
                    File(outDir, "artifacts/${theme.id}-$variant-chapter${i + 1}-issues.json")
                        .writeText(json.encodeToString(issues.map { IssueSnapshot(it.type.name, it.rawType, it.blockId, it.message) }))
                    File(outDir, "artifacts/${theme.id}-$variant-chapter${i + 1}-plan.json")
                        .writeText(json.encodeToString(PlanSnapshot(
                            plan.title, plan.derivationChain, plan.chapterType,
                            plan.contractJson, plan.plotGoal,
                            // nodeIds → 标题（范围缺口/负荷指标采集用）
                            plan.nodeIds.mapNotNull { id -> fixedMap.nodes.firstOrNull { it.id == id }?.title },
                            plan.wordTarget,
                        )))
                    File(outDir, "artifacts/${theme.id}-$variant-chapter${i + 1}-status.json")
                        .writeText(json.encodeToString(
                            RunStatus(chResult.isSuccess, chResult.exceptionOrNull()?.message?.take(200)),
                        ))
                }
            }
            File(outDir, "artifacts/${theme.id}-$variant-bible.json").apply { parentFile.mkdirs() }
                .writeText(json.encodeToString(
                    db.storyBibleDao().get(theme.id)?.let {
                        BibleSnapshot(it.world, it.conflict, it.style, it.throughLine, it.runningState)
                    } ?: BibleSnapshot("", "", "", emptyList(), ""),
                ))
            File(outDir, "artifacts/${theme.id}-$variant-status.json")
                .writeText(json.encodeToString(RunStatus(bibleResult.isSuccess)))
        } finally {
            db.close()
        }
    }

    private fun newDb(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<android.content.Context>(),
        AppDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun buildPipeline(
        db: AppDatabase,
        recorder: RecordingRouter,
        promptDir: File,
        config: RunConfig,
        theme: ThemeSpec,
        requireContracts: Boolean,
    ): PipelineService {
        val router = ConfiguredModelRouter(
            settingsRepository = FixedSettingsRepository(config),
            keyCipherProvider = { FakeCipher(config.apiKey) },
            providerFactory = { cfg, key ->
                com.example.zhishu.ai.OpenAiModelProvider(
                    config = cfg,
                    apiKey = key,
                    // 测试环境允许 http（本地网关）；真实服务商都是 https，由 requireSecureUrl 兜底
                    requireHttps = false,
                    // 推理模型思考阶段长时间无数据，样本用更长超时（生产默认 180s/120s 不变）
                    socketTimeoutMillis = 300_000,
                    requestTimeoutMillis = 600_000,
                    // 样本评估契约/路线/核验，不需要长思考：把推理模型当普通模型用
                    thinkingDisabled = true,
                )
            },
        )
        return PipelineService(
            router = recorder.wrap(router),
            prompts = FilePromptProvider(promptDir),
            projectRepository = DefaultProjectRepository(db.projectDao()),
            sourceRepository = DefaultSourceRepository(db.sourceDocumentDao(), db.sourceChunkDao()),
            knowledgeRepository = DefaultKnowledgeRepository(db, db.knowledgeNodeDao(), db.knowledgeGapDao(), db.knowledgeConflictDao(), db.knowledgeMapBatchDao()),
            chapterRepository = DefaultChapterRepository(db.chapterPlanDao(), db.chapterBlockDao(), db.chapterVersionDao()),
            citationRepository = DefaultCitationRepository(db.citationDao()),
            issueRepository = DefaultVerificationIssueRepository(db.verificationIssueDao()),
            storyBibleRepository = DefaultStoryBibleRepository(db.storyBibleDao()),
            quizRepository = DefaultQuizRepository(db.quizDao(), db.answerRecordDao()),
            reviewRepository = DefaultReviewRepository(db.reviewItemDao()),
            spacedReviewEnabled = { false },
            requireChapterContracts = { requireContracts },
            // 样本发现 doubao 正文块不填 knowledgeNodeTitles，放宽让链路跑通（生产默认严格）
            requireBlockNodeLinks = { false },
            // RouteAudit 软指标落盘（批次 B 报告需要）
            routeAuditSink = { issues ->
                File(SAMPLES_ROOT, "runs").listFiles()?.maxByOrNull { it.name }?.let { latest ->
                    File(latest, "artifacts/${theme.id}-${if (requireContracts) "new" else "legacy"}-route-audit.json").apply { parentFile.mkdirs() }
                        .writeText(compactJson.encodeToString(issues.map {
                            RouteAuditIssueSnapshot(it.code.name, it.level.name, it.chapterOrder ?: 0, it.message)
                        }))
                }
            },
        )
    }

    private fun project(theme: ThemeSpec) = Project(
        id = theme.id,
        title = theme.topic,
        topic = theme.topic,
        baseline = "入门",
        depth = "考试",
        dailyMinutes = 30,
        targetDays = 30,
        genre = "AI 自由发挥",
        status = ProjectStatus.DRAFT,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        sourceMode = if (theme.mode == "DOCUMENTS") SourceMode.DOCUMENTS else SourceMode.MODEL,
    )

    private suspend fun seedDocument(db: AppDatabase, text: String) {
        val source = com.example.zhishu.domain.model.SourceDocument(
            id = "doc-1", projectId = "inheritance", title = "继承法讲义（受控资料）",
            kind = SourceKind.TEXT, fetchedAt = System.currentTimeMillis(),
            contentHash = text.hashCode().toString(), parseStatus = com.example.zhishu.domain.model.ParseStatus.PARSED,
        )
        db.sourceDocumentDao().upsert(source)
        db.sourceChunkDao().upsertAll(listOf(
            com.example.zhishu.domain.model.SourceChunk(id = "chunk-1", sourceId = source.id, projectId = "inheritance", ordinal = 0, text = text),
        ))
    }

    private fun configFromEnv(): RunConfig? {
        val baseUrl = System.getenv("ZHISHU_SAMPLE_BASE_URL") ?: return null
        val model = System.getenv("ZHISHU_SAMPLE_MODEL") ?: return null
        val apiKey = System.getenv("ZHISHU_SAMPLE_API_KEY") ?: return null
        if (apiKey.isBlank()) return null
        return RunConfig(
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
            temperature = System.getenv("ZHISHU_SAMPLE_TEMPERATURE")?.toFloatOrNull() ?: 0.7f,
            // 推理模型（如 deepseek-v4-flash）会把大量 completion token 花在 reasoning_content 上，
            // 8192 容易被思考过程吃满导致 content 为空。默认给足余量，env 可覆盖。
            maxTokens = System.getenv("ZHISHU_SAMPLE_MAX_TOKENS")?.toIntOrNull() ?: 32_000,
            seed = System.getenv("ZHISHU_SAMPLE_SEED"),
        )
    }

    companion object {
        const val AppDatabaseVersion = 15

        /**
         * 样本根目录。Robolectric 测试里 System.getProperty("user.dir") 可能是 app 模块目录，
         * 因此向上找包含 prompts-legacy 的 samples 目录（项目根下的 samples/）。
         */
        val SAMPLES_ROOT: File = run {
            val cwd = File(System.getProperty("user.dir").orEmpty())
            val candidates = listOf(cwd, cwd.resolve(".."), cwd.parentFile ?: cwd)
            candidates.mapNotNull { dir ->
                listOf(dir.resolve("samples"), dir.resolve("app").resolve("samples"))
                    .firstOrNull { File(it, "prompts-legacy").isDirectory }
            }.firstOrNull() ?: cwd.resolve("samples")
        }

        fun promptDirHash(dir: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                digest.update(f.name.toByteArray())
                digest.update(f.readBytes())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        }

        /** 受控资料：内容稳定、结构明确，无冲突。冲突样本由后续用例再喂两份差异文档 */
        val INHERITANCE_DOC = """
            继承编讲义（节选）
            一、继承的开始。继承从被继承人死亡时开始。继承开始后，遗产归全体继承人共同共有。
            二、遗产范围。遗产是自然人死亡时遗留的个人合法财产，包括收入、房屋、储蓄、股权与知识产权中的财产权利。专属于死者的人身权利不属于遗产。
            三、法定继承。没有遗嘱或遗嘱无效时，按法定顺序继承：第一顺序为配偶、子女、父母；第二顺序为兄弟姐妹、祖父母、外祖父母。第一顺序存在时，第二顺序不继承。同一顺序继承人均分。
            四、遗嘱继承。有合法有效遗嘱的，按遗嘱继承。下列遗嘱无效：无民事行为能力人所立；受欺诈、胁迫所立；伪造的遗嘱；被篡改的内容。
            五、夫妻财产。夫妻共同财产中，只有属于死者的一半份额计入遗产。
            六、遗嘱形式。自书遗嘱由遗嘱人亲笔书写、签名并注明年月日。
        """.trimIndent()
    }
}

// ---- 阶段识别 ----

internal class StageHolder {
    var current: String = "UNKNOWN"
    suspend fun <T> around(stage: String, block: suspend () -> Result<T>): Result<T> {
        val prev = current
        current = stage
        try {
            return block()
        } finally {
            current = prev
        }
    }
}

// ---- 记录：一次 HTTP 尝试一条（B.0-5 / B.0-6） ----

internal class RecordingRouter(
    private val outDir: File,
    private val currentStage: () -> String,
    private val currentTheme: () -> String,
    private val currentVariant: () -> String,
    private val currentPromptHash: () -> String,
    private val model: String,
    private val sink: (CallRecord) -> Unit,
) {
    fun wrap(inner: ModelRouter): ModelRouter = object : ModelRouter {
        override suspend fun <T> generate(
            role: ModelRole,
            request: ModelRequest,
            decoder: com.example.zhishu.ai.JsonDecoder<T>,
            onStats: (ModelCallStats) -> Unit,
            onAttempt: (CallAttempt) -> Unit,
        ): Result<T> {
            val requestId = UUID.randomUUID().toString().take(8)
            // 完整有效请求（system + 占位符替换后的 user + 请求参数）落 artifacts/requests
            val reqFile = writeRequest(requestId, request)
            return inner.generate(role, request, decoder, onStats) { attempt ->
                val respFile = attempt.rawResponse?.let { raw ->
                    writeFile("responses/$requestId-attempt${attempt.attempt}.json", raw)
                }
                val finishReason = attempt.rawResponse
                    ?.let { Regex("\"finish_reason\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                sink(
                    CallRecord(
                        runId = "",
                        subjectId = currentTheme(),
                        variant = currentVariant(),
                        stage = currentStage(),
                        businessCallId = attempt.businessCallId,
                        attempt = attempt.attempt,
                        kind = attempt.kind.name,
                        model = model,
                        requestHash = sha256(request.system + "\n" + request.user),
                        promptHash = currentPromptHash(),
                        startedAt = attempt.startedAt,
                        latencyMillis = attempt.latencyMillis,
                        finishReason = finishReason,
                        promptTokens = attempt.usage.promptTokens,
                        completionTokens = attempt.usage.completionTokens,
                        totalTokens = attempt.usage.totalTokens,
                        usageStatus = attempt.usageStatus.name,
                        outcome = attempt.outcome.name,
                        errorCategory = attempt.errorCategory,
                        requestFile = reqFile,
                        responseFile = respFile,
                    ),
                )
                onAttempt(attempt)
            }
        }
    }

    private fun writeRequest(requestId: String, request: ModelRequest): String {
        // 只存 system + user + 参数；绝不写 Authorization/API Key
        val body = buildString {
            appendLine("SYSTEM:")
            appendLine(request.system)
            appendLine("USER:")
            appendLine(request.user)
            appendLine("PARAMS: maxTokens=${request.maxTokens} temperature=${request.temperature} jsonMode=${request.jsonMode}")
        }
        return writeFile("requests/$requestId.json", body)
    }

    private fun writeFile(relPath: String, content: String): String {
        val file = File(outDir, relPath).apply { parentFile.mkdirs() }
        file.writeText(content)
        return relPath
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}

// ---- 快照 DTO ----

@Serializable
internal data class RunManifest(
    val runId: String,
    val createdAt: Long,
    val dbVersion: Int,
    val model: String,
    val baseUrlHost: String,
    val supportsSeed: Boolean,
    val seed: String?,
    val promptVersionLegacy: String,
    val promptVersionNew: String,
    val themes: List<ThemeSpec>,
)

@Serializable
internal data class ThemeSpec(
    val id: String,
    val mode: String,
    val topic: String,
    val docText: String? = null,
)

/** 固定地图快照：legacy/new 共同消费（B.0-2 控制变量）。含生效批次信息——
 * 节点按 active 批次过滤（getByProject），注入时必须同时注入 active 批次，
 * 否则路线阶段读到的地图是空的。 */
@Serializable
internal data class FixedMapSnapshot(
    val themeId: String,
    val nodes: List<KnowledgeNode>,
    /** 生效批次（active=1, completed=1），保证路线阶段 getByProject 能读到节点 */
    val batch: KnowledgeMapBatch? = null,
    val createdAt: Long,
)

/** 连通性探针快照（批次 B 第一步，不计入质量样本） */
@Serializable
internal data class ProbeSnapshot(
    val probedAt: Long,
    val endpoint: String,
    val model: String,
    val maxTokens: Int,
    val temperature: Float,
    val jsonMode: Boolean,
    val supportsSeed: Boolean,
    val seedNote: String,
    val success: Boolean,
    val error: String? = null,
    val latencyMillis: Long,
    val usageReported: Boolean,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val finishReason: String? = null,
    val responseHasAuthLeak: Boolean,
    val responsePreview: String? = null,
)

/** calls.jsonl 的一行 = 一次 HTTP 尝试（B.0-5 字段清单） */
@Serializable
internal data class CallRecord(
    val runId: String,
    val subjectId: String,
    val variant: String,
    val stage: String,
    val businessCallId: String,
    val attempt: Int,
    val kind: String,
    val model: String,
    val requestHash: String,
    val promptHash: String,
    val startedAt: Long,
    val latencyMillis: Long,
    val finishReason: String? = null,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val usageStatus: String,
    val outcome: String,
    val errorCategory: String? = null,
    val requestFile: String? = null,
    val responseFile: String? = null,
)

@Serializable
internal data class BlockSnapshot(
    val order: Int,
    val type: String,
    val text: String,
    val nodeIds: List<String>,
    val sourceRefs: List<String>,
    /** 段落 id（与核验 checks 的 evidenceBlockIds 对应，可精确对照） */
    val blockId: String = "",
)
@Serializable
internal data class IssueSnapshot(val type: String, val rawType: String, val blockId: String?, val message: String)
@Serializable
internal data class PlanSnapshot(
    val title: String,
    val derivationChain: String,
    val chapterType: String,
    val contractJson: String,
    val plotGoal: String,
    val nodeTitles: List<String> = emptyList(),
    val wordTarget: Int = 0,
)
@Serializable
internal data class BibleSnapshot(val world: String, val conflict: String, val style: String, val throughLine: List<String>, val runningState: String)
@Serializable
internal data class RunStatus(val ok: Boolean, val reason: String? = null)

/** RouteAudit 软指标快照（批次 B 报告需要） */
@Serializable
internal data class RouteAuditIssueSnapshot(
    val code: String,
    val level: String,
    val chapterOrder: Int,
    val message: String,
)

// ---- 配置与依赖桩 ----

internal class FilePromptProvider(private val dir: File) : PromptProvider {
    override fun load(name: String): String {
        val f = File(dir, "$name.txt")
        return if (f.isFile) f.readText() else name
    }
}

internal class FixedSettingsRepository(private val config: SampleRunner.RunConfig) :
    SettingsRepository {
    override suspend fun getAllConfigs(): List<com.example.zhishu.domain.model.ModelConfig> =
        com.example.zhishu.domain.model.ModelRole.entries.map { role ->
            com.example.zhishu.domain.model.ModelConfig(
                role = role.name,
                baseUrl = config.baseUrl,
                modelName = config.model,
                maxTokens = config.maxTokens,
                temperature = config.temperature,
                supportsJson = true,
                keyRef = "stub",
            )
        }
    override suspend fun getConfig(role: ModelRole): com.example.zhishu.domain.model.ModelConfig? =
        com.example.zhishu.domain.model.ModelConfig(
            role = role.name,
            baseUrl = config.baseUrl,
            modelName = config.model,
            maxTokens = config.maxTokens,
            temperature = config.temperature,
            supportsJson = true,
            keyRef = "stub",
        )
    override suspend fun saveConfig(config: com.example.zhishu.domain.model.ModelConfig) {}
    override suspend fun clearConfigs() {}
}

internal class FakeCipher(private val apiKey: String) : KeyCipher {
    override fun encrypt(alias: String, plaintext: String): String = plaintext
    override fun decrypt(alias: String, ciphertext: String): String = apiKey
}
