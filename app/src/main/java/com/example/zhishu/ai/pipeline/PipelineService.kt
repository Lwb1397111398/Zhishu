package com.example.zhishu.ai.pipeline

import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.PromptProvider
import com.example.zhishu.ai.jsonDecoder
import com.example.zhishu.ai.outputs.BlockRewriteOutput
import com.example.zhishu.ai.outputs.ChainDto
import com.example.zhishu.ai.outputs.ChainNodesOutput
import com.example.zhishu.ai.outputs.ChapterContractDto
import com.example.zhishu.ai.outputs.ChapterOutput
import com.example.zhishu.ai.outputs.ChapterPlanDto
import com.example.zhishu.ai.outputs.ConflictDto
import com.example.zhishu.ai.outputs.ContractCodec
import com.example.zhishu.ai.outputs.KnowledgeMapAuditOutput
import com.example.zhishu.ai.outputs.KnowledgeMapOutput
import com.example.zhishu.ai.outputs.KnowledgeMapSkeletonOutput
import com.example.zhishu.ai.outputs.NextVolumeOutput
import com.example.zhishu.ai.outputs.NodeDto
import com.example.zhishu.ai.outputs.OutputValidators
import com.example.zhishu.ai.outputs.QuizSetOutput
import com.example.zhishu.ai.outputs.RouteAudit
import com.example.zhishu.ai.outputs.RouteAuditIssue
import com.example.zhishu.ai.outputs.StoryBibleOutput
import com.example.zhishu.ai.outputs.VerificationOutput
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeMapBatch
import com.example.zhishu.domain.model.KnowledgeMapChainStatus
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.model.NodeGenerateStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.QuizState
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.ReviewStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.StoryBible
import com.example.zhishu.domain.model.VerificationIssue
import com.example.zhishu.domain.repository.ChapterRepository
import com.example.zhishu.domain.repository.CitationRepository
import com.example.zhishu.domain.repository.KnowledgeRepository
import com.example.zhishu.domain.repository.ProjectRepository
import com.example.zhishu.domain.repository.QuizRepository
import com.example.zhishu.domain.repository.ReviewRepository
import com.example.zhishu.domain.repository.SourceRepository
import com.example.zhishu.domain.repository.StoryBibleRepository
import com.example.zhishu.domain.repository.VerificationIssueRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PipelineService(
    private val router: ModelRouter,
    private val prompts: PromptProvider,
    private val projectRepository: ProjectRepository,
    private val sourceRepository: SourceRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val chapterRepository: ChapterRepository,
    /** 正文块 ↔ 资料片段的引用关系（4A）。发布哪一版正文，就写哪一版的引用 */
    private val citationRepository: CitationRepository,
    /** 核验问题（4B）。问题绑在版本上，局部重写据此知道该重写哪几块 */
    private val issueRepository: VerificationIssueRepository,
    private val storyBibleRepository: StoryBibleRepository,
    private val quizRepository: QuizRepository,
    private val reviewRepository: ReviewRepository,
    /**
     * 间隔复习是否开启（默认关）。关闭时章节发布不再自动排复习，
     * 阅读不会变成待办任务；已有复习数据不受影响。
     */
    private val spacedReviewEnabled: () -> Boolean = { false },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * 路线规划是否强制章节因果契约（批次 B B.0-3）。
     *
     * 新版 Prompt 输出契约 → true（生产默认）；旧版 Prompt 不输出契约 → false，
     * 由样本运行器传入，走兼容解析（chapterType=未指定、contract=未生成），
     * 事实/推导/引用核验照常，不因契约缺失失败、不补写虚假契约。
     */
    private val requireChapterContracts: () -> Boolean = { true },
    /**
     * 正文是否强制讲解段落关联既有知识点（批次 B 样本）。生产默认 true；
     * 样本运行器传 false 以容忍部分模型（如 doubao-seed）正文块不填
     * knowledgeNodeTitles——该质量问题作为样本发现记录，不改变生产默认。
     */
    private val requireBlockNodeLinks: () -> Boolean = { true },
    /**
     * 路线软审计结果出口（第二轮 P1-5）。
     *
     * 硬校验失败会让路线生成直接失败；这里是「能继续但有风险」的提示。
     * 批次 A 先通过此回调把审计问题交出去（测试断言、后续版本接 UI/落库），
     * 不落库、不影响生成结果。
     */
    private val routeAuditSink: (List<RouteAuditIssue>) -> Unit = {},
) {

    /**
     * 知识地图分批生成：骨架 → 逐链展开 → 全局审计（批次化）。
     *
     * 原来一次性让模型输出 14-44 个节点 + 3-8 条演进链，单次 2 万 token 输出、
     * 最坏几分钟不出结果；现在拆成三步，每步一次小调用（30-90 秒），
     * 失败只重试该步，进度逐链上报。
     *
     * 幂等与断点：骨架、每条链、审计各自落库后才进入下一步；重试/重启后
     * 从 [KnowledgeMapBatch] 里读到已完成的部分直接跳过，只补没做完的。
     * 重新生成（旧批次已完成/已失败）开新批次并清掉旧地图，旧节点不会被误认成断点。
     *
     * [onProgress] 逐阶段上报文案与单元数（默认不上报，保持既有调用方不变）。
     */
    suspend fun generateKnowledgeMap(
        projectId: String,
        onProgress: suspend (GenerationProgress) -> Unit = {},
    ): Result<Unit> {
        val project = projectRepository.get(projectId)
            ?: return Result.failure(IllegalStateException("项目不存在"))
        // 无资料模式（问题3）：知识全部由模型自备，不查片段也不要求引用
        val modelKnowledge = project.usesModelKnowledge
        val chunks = if (modelKnowledge) {
            emptyList()
        } else {
            sourceRepository.getChunksByProject(projectId).also {
                if (it.isEmpty()) {
                    return Result.failure(
                        IllegalStateException("没有可用资料片段。若想让 AI 自备知识，请在项目设置里切换到「AI 自备知识」模式"),
                    )
                }
            }
        }
        // 片段的来源与定位信息一起喂给模型（4A）
        loadSourceMeta(projectId)

        // 批次判定：未完成（骨架/链/审计缺一步）→ 续跑；已完成/已失败 → 开新批次。
        // 重新生成不预先删除旧地图：旧批次保持 active，新批次完整落库并切换后才替换；
        // 失败或取消时旧地图原样保留（v14）。
        val latest = knowledgeRepository.getLatestBatch(projectId)
        val resume = latest?.takeIf { !it.completed && !it.failed }
        val isFirstGeneration = latest == null
        var batch = resume ?: KnowledgeMapBatch(
            id = idFactory(),
            projectId = projectId,
            createdAt = clock(),
            updatedAt = clock(),
        )

        // ===== 1. 骨架（一次小调用，只列链结构与节点标题清单） =====
        if (!batch.skeletonDone) {
            onProgress(GenerationProgress("正在梳理知识主干（骨架）", 0, -1))
            val skeletonPrompt = prompts.load(PROMPT_KNOWLEDGE_MAP_SKELETON)
                .replace("{topic}", project.topic)
                .replace("{depth}", project.depth)
                .replace("{goal}", project.title)
                .replace("{baseline}", project.baseline)
            val skeletonRaw = router.generate(
                role = ModelRole.FAST,
                request = ModelRequest(
                    role = ModelRole.FAST,
                    system = SYSTEM_MAP_SKELETON,
                    user = skeletonPrompt,
                    jsonMode = true,
                ),
                decoder = jsonDecoder(KnowledgeMapSkeletonOutput.serializer()),
            ).getOrElse { return Result.failure(it) }
            val skeleton = OutputValidators.validateSkeleton(skeletonRaw)
                .getOrElse { return Result.failure(it) }

            // 占位节点：nodeKey 由程序分配（稳定标识），标题只做显示；节点归属本批次
            if (isFirstGeneration) {
                // 首次生成：清掉迁移前的旧节点/insights（没有旧地图要保护）
                knowledgeRepository.clearForProject(projectId)
            }
            knowledgeRepository.upsertAll(buildSkeletonNodes(projectId, skeleton, batch.id))
            // 骨架预览的 gaps/conflicts 先落库，全局审计通过后整体替换
            knowledgeRepository.replaceInsights(
                projectId = projectId,
                gaps = skeleton.gaps.filter { it.isNotBlank() }.mapIndexed { index, text ->
                    KnowledgeGap(id = idFactory(), projectId = projectId, description = text, order = index)
                },
                conflicts = skeleton.conflicts.mapIndexed { index, dto ->
                    KnowledgeConflict(
                        id = idFactory(),
                        projectId = projectId,
                        topic = dto.topic,
                        sources = dto.sources,
                        description = dto.description,
                        order = index,
                    )
                },
            )
            batch = batch.copy(
                skeletonDone = true,
                // 首次生成没有旧地图要保护，骨架完成即生效；重新生成保持 inactive，整批成功再切换
                active = isFirstGeneration,
                chainsJson = json.encodeToString(ListSerializer(ChainDto.serializer()), skeleton.chains),
                chainStatuses = json.encodeToString(
                    ListSerializer(String.serializer()),
                    List(skeleton.chains.size) { KnowledgeMapChainStatus.PENDING.name },
                ),
                previewGaps = json.encodeToString(
                    ListSerializer(String.serializer()),
                    skeleton.gaps,
                ),
                previewConflicts = json.encodeToString(
                    ListSerializer(ConflictDto.serializer()),
                    skeleton.conflicts,
                ),
                updatedAt = clock(),
            )
            knowledgeRepository.saveBatch(batch)
            // 演进链暂存进故事总纲行，章节规划阶段直接拿来当章节骨架（问题7）
            storyBibleRepository.save(
                (storyBibleRepository.get(projectId) ?: StoryBible(projectId = projectId)).copy(
                    throughLine = skeleton.chains.map { chain ->
                        "${chain.theme.ifBlank { "演进链" }}：${chain.nodeTitles.joinToString(" → ")}"
                    },
                    updatedAt = clock(),
                ),
            )
        }

        val chains = decodeChains(batch.chainsJson)
        if (chains.isEmpty()) return Result.failure(IllegalStateException("骨架没有演进链，无法展开"))
        val total = chains.size + 2 // 骨架 + 审计
        var completed = 1

        // ===== 2. 逐链展开（每链一次小调用，写完即落库） =====
        val statuses = decodeChainStatuses(batch.chainStatuses).toMutableList()
        val unmatchedTitles = mutableListOf<String>()

        chains.forEachIndexed { index, chain ->
            if (statuses.getOrElse(index) { KnowledgeMapChainStatus.PENDING } == KnowledgeMapChainStatus.DONE) {
                completed += 1
                return@forEachIndexed
            }
            statuses[index] = KnowledgeMapChainStatus.RUNNING
            knowledgeRepository.saveBatch(
                batch.copy(
                    chainStatuses = encodeStatuses(statuses),
                    updatedAt = clock(),
                ),
            )
            onProgress(GenerationProgress("正在展开第 ${index + 1}/${chains.size} 条演进链", completed, total))

            // 每条链开始时从库里刷新节点：上一链回填的结果必须在这里可见，
            // 共享节点才能「先到先得」不被覆盖，断点重试也能看到真实进度
            val nodes = knowledgeRepository.getByProjectAll(projectId)
            val keyToNode = nodes.associateBy { it.nodeKey }
            val titleToNode = nodes.associateBy { it.title.trim() }
            // 本链资料检索（有资料模式）：按链主题 + 节点标题匹配片段，引用只能落在本次提供的片段上
            val chainChunks = if (modelKnowledge) emptyList() else selectChunksForChain(chunks, chain)
            val chainChunkIds = chainChunks.map { it.id }.toSet()
            // 已填充节点摘要（共享节点防打架：后写链只能引用，不能重写）
            val filledSummary = nodes
                .filter { it.generateStatus == NodeGenerateStatus.COMPLETED.name && it.definition.isNotBlank() }
                .joinToString("\n") { "- ${it.title}：${it.definition.take(NODE_EXCERPT)}" }
            val nodeKeyTitles = chain.nodeTitles.joinToString("\n") { title ->
                val node = titleToNode[title.trim()]
                if (node != null) "${node.nodeKey}：${node.title}" else title
            }
            val chainPrompt = prompts.load(PROMPT_KNOWLEDGE_MAP_CHAIN)
                .replace("{chainTheme}", chain.theme)
                .replace("{chainOpening}", chain.opening)
                .replace("{nodeTitles}", chain.nodeTitles.joinToString(" → "))
                .replace("{filledNodes}", filledSummary.ifBlank { "暂无" })
                .replace("{chunks}", formatChunks(chainChunks))
                .replace("{nodeKeyTitles}", nodeKeyTitles)

            val chainRaw = router.generate(
                role = ModelRole.FAST,
                request = ModelRequest(
                    role = ModelRole.FAST,
                    system = SYSTEM_MAP_CHAIN,
                    user = chainPrompt,
                    jsonMode = true,
                ),
                decoder = jsonDecoder(ChainNodesOutput.serializer()),
            ).getOrElse {
                statuses[index] = KnowledgeMapChainStatus.FAILED
                knowledgeRepository.saveBatch(
                    batch.copy(
                        chainStatuses = encodeStatuses(statuses),
                        updatedAt = clock(),
                    ),
                )
                // 本链尚未展开的节点标失败（UI 可见），重试时重新展开
                chain.nodeTitles.mapNotNull { titleToNode[it.trim()] }
                    .filter { it.generateStatus != NodeGenerateStatus.COMPLETED.name }
                    .forEach { knowledgeRepository.updateGenerateStatus(it.id, NodeGenerateStatus.FAILED.name) }
                return Result.failure(it)
            }
            val (valid, unmatched) = OutputValidators.validateChainNodes(
                chainRaw,
                keyToNode.keys,
                titleToNode.keys,
            ).getOrElse { return Result.failure(it) }
            unmatchedTitles += unmatched

            // 回填：按 nodeKey 匹配；已 COMPLETED 的共享节点跳过（先到先得）
            for (dto in valid) {
                val node = keyToNode[dto.nodeKey] ?: continue
                if (node.generateStatus == NodeGenerateStatus.COMPLETED.name) continue
                val parentId = dto.parentTitle?.trim()?.let { titleToNode[it]?.id }
                knowledgeRepository.upsert(
                    node.copy(
                        generateStatus = NodeGenerateStatus.COMPLETED.name,
                        parentId = parentId ?: node.parentId,
                        depth = dto.depth.coerceIn(1, 3),
                        definition = dto.definition,
                        arisesFrom = dto.arisesFrom,
                        leadsTo = dto.leadsTo,
                        prerequisites = dto.prerequisites,
                        related = dto.related,
                        confusions = dto.confusions,
                        examples = dto.examples,
                        // 引用只能落在本次提供的片段上；骨架后新增的片段 ID 一律丢弃
                        sourceRefs = dto.sourceRefs.filter { it in chainChunkIds }.distinct(),
                    ),
                )
            }
            statuses[index] = KnowledgeMapChainStatus.DONE
            knowledgeRepository.saveBatch(
                batch.copy(
                    chainStatuses = encodeStatuses(statuses),
                    updatedAt = clock(),
                ),
            )
            completed += 1
            onProgress(GenerationProgress("正在展开第 ${index + 1}/${chains.size} 条演进链", completed, total))
        }
        batch = batch.copy(chainStatuses = encodeStatuses(statuses), updatedAt = clock())
        knowledgeRepository.saveBatch(batch)

        // ===== 3. 全局审计（一次小调用，生成最终 gaps/conflicts） =====
        onProgress(GenerationProgress("正在做全局审计", completed, total))
        val allNodes = knowledgeRepository.getByProjectAll(projectId)
        val auditResult = runCatching {
            val auditPrompt = prompts.load(PROMPT_KNOWLEDGE_MAP_AUDIT)
                .replace("{topic}", project.topic)
                .replace("{goal}", project.title)
                .replace("{nodes}", allNodes.joinToString("\n") { node ->
                    "- ${node.title}：${node.definition.take(NODE_EXCERPT)}"
                }.ifBlank { "（地图为空）" })
                .replace("{chunks}", formatChunks(chunks))
            router.generate(
                role = ModelRole.FAST,
                request = ModelRequest(
                    role = ModelRole.FAST,
                    system = SYSTEM_MAP_AUDIT,
                    user = auditPrompt,
                    jsonMode = true,
                ),
                decoder = jsonDecoder(KnowledgeMapAuditOutput.serializer()),
            )
        }
        val audit = auditResult.getOrNull()?.getOrNull()
        val auditSucceeded = audit != null
        var finalGaps = audit?.gaps ?: decodeStringList(batch.previewGaps)
        var finalConflicts = audit?.conflicts ?: decodeConflicts(batch.previewConflicts)

        // 无论审计成败都要带的软提示：节点数偏少 / 单链给出的超纲标题
        val softGaps = mutableListOf<String>()
        val expectedMin = when (project.depth) {
            "考试" -> 24
            "专业" -> 32
            else -> 14
        }
        if (allNodes.size < expectedMin) {
            softGaps += "知识地图只有 ${allNodes.size} 个知识点，低于「${project.depth}」深度的预期（$expectedMin 个），可能漏掉了部分内容，建议补充资料后重新梳理。"
        }
        if (unmatchedTitles.isNotEmpty()) {
            softGaps += unmatchedTitles.take(5).map { "「$it」未纳入知识地图（单链展开给出的标题不在骨架内）" }
        }

        if (!auditSucceeded) {
            // 审计失败：不推翻已展开节点，地图内容可用；但批次不置 completed，
            // 下次续跑只补审计，UI 通过错误横幅明确标「全局审计待重试」，不误报为全部完成。
            val reason = auditResult.exceptionOrNull()?.message ?: "模型调用失败"
            batch = batch.copy(auditFailed = true, updatedAt = clock())
            knowledgeRepository.saveBatch(batch)
            // 首次生成：地图已在用（骨架即 active），落一份带说明的保底 insights
            if (batch.active) {
                val gaps = (finalGaps + softGaps +
                    "全局审计未完成（$reason），可稍后重试。以上缺口清单为骨架阶段的预览。").distinct()
                knowledgeRepository.replaceInsights(
                    projectId = projectId,
                    gaps = gaps.mapIndexed { index, text ->
                        KnowledgeGap(id = idFactory(), projectId = projectId, description = text, order = index)
                    },
                    conflicts = finalConflicts.distinctBy { it.topic }.mapIndexed { index, dto ->
                        KnowledgeConflict(
                            id = idFactory(),
                            projectId = projectId,
                            topic = dto.topic,
                            sources = dto.sources,
                            description = dto.description,
                            order = index,
                        )
                    },
                )
                projectRepository.update(project.copy(status = ProjectStatus.MAP, updatedAt = clock()))
            }
            onProgress(GenerationProgress("全局审计未完成，可重试", completed, total))
            return Result.failure(
                IllegalStateException("全局审计未完成（$reason）。知识地图内容已可用，可重试补齐审计。"),
            )
        }

        // 审计成功：整批完成。重新生成场景下旧地图此刻才下线（原子切换），
        // 失败或取消时旧地图与旧 insights 全程保留。
        // 顺序关键：先 saveBatch 落 active=true，再 switchActiveBatch——
        // 切换事务会把全部批次置 0 后把本批次置 1；若反过来，saveBatch 的
        // 整行 REPLACE 会把 active 又覆盖回 false。
        batch = batch.copy(auditDone = true, completed = true, active = true, updatedAt = clock())
        knowledgeRepository.saveBatch(batch)
        knowledgeRepository.switchActiveBatch(projectId, batch.id)
        finalGaps = (finalGaps + softGaps).distinct()
        knowledgeRepository.replaceInsights(
            projectId = projectId,
            gaps = finalGaps.mapIndexed { index, text ->
                KnowledgeGap(id = idFactory(), projectId = projectId, description = text, order = index)
            },
            conflicts = finalConflicts.distinctBy { it.topic }.mapIndexed { index, dto ->
                KnowledgeConflict(
                    id = idFactory(),
                    projectId = projectId,
                    topic = dto.topic,
                    sources = dto.sources,
                    description = dto.description,
                    order = index,
                )
            },
        )
        projectRepository.update(project.copy(status = ProjectStatus.MAP, updatedAt = clock()))
        onProgress(GenerationProgress("知识主干梳理完成", total, total))
        return Result.success(Unit)
    }

    suspend fun generateStoryBible(projectId: String): Result<Unit> {
        val project = projectRepository.get(projectId)
            ?: return Result.failure(IllegalStateException("项目不存在"))
        val nodes = knowledgeRepository.getByProject(projectId)
        if (nodes.isEmpty()) return Result.failure(IllegalStateException("知识地图为空"))

        // 已经有可读正文时不允许整本重新规划（第三阶段 3C）。
        //
        // 原来这里直接 clearBlocksForProject + clearPlansForProject，
        // 用户在知识地图页点一下「重新生成」就把读过的章节全删了。
        // 已发布内容不会因为重新规划而丢失——这是第三阶段的底线，
        // 所以这条路直接堵死，改由「继续下一卷」往后接。
        val existing = chapterRepository.getPlans(projectId)
        if (existing.any { it.isReadable }) {
            return Result.failure(
                IllegalStateException(
                    "这个项目已经有写好的章节了，不能整本重新规划（那会删掉你读过的内容）。" +
                        "想往后讲就用「继续下一卷」。",
                ),
            )
        }

        val bible = storyBibleRepository.get(projectId)
        // 首次只规划一卷。让模型一次想出整个主题的所有章节，越往后越糊，
        // 而且用户根本还没读到那儿（3C）
        val suggested = suggestChapterCount(nodes.size)

        val prompt = prompts.load(PROMPT_STORY_BIBLE)
            .replace("{styleInstruction}", StyleInstruction.of(project.genre))
            .replace("{topic}", project.topic)
            .replace("{goal}", project.title)
            .replace("{baseline}", project.baseline)
            .replace("{chapterHint}", "$suggested 章左右（不要超过 $VOLUME_MAX_CHAPTERS 章）")
            .replace("{wordHint}", "${project.wordTargetPerChapter}")
            .replace("{nodes}", formatNodesWithDerivation(nodes))
            .replace("{chains}", formatChains(bible?.throughLine.orEmpty()))

        val raw = router.generate(
            role = ModelRole.STORY,
            request = ModelRequest(
                role = ModelRole.STORY,
                system = SYSTEM_BIBLE,
                user = prompt,
                jsonMode = true,
            ),
            decoder = jsonDecoder(StoryBibleOutput.serializer()),
        ).getOrElse { return Result.failure(it) }

        val titleToId = nodes.associate { it.title to it.id }
        // 新 Prompt 版本要求每章带因果契约与章节类型（第二轮 P1-2）：
        // 老模型输出（无契约字段）解析成默认空契约，这里会明确失败而不是伪造通过
        //
        // 批次 B++ n=3：路线被强校验拦截的最大失败模式是「REVIEW 章把新节点填进
        // introducedNodeTitles」。先校验；命中该模式时做一次**针对性修复重试**（只清空
        // REVIEW 的 introduced 并检查此前引入），修复失败仍如实拦截，不静默降级。
        val output = ReviewIntroducedFix.repairIfApplicable(
            router = router,
            output = raw,
            decoder = jsonDecoder(StoryBibleOutput.serializer()),
            validate = { candidate ->
                OutputValidators.validateStoryBible(
                    candidate, titleToId.keys, requireContracts = requireChapterContracts(),
                )
            },
            fixFailure = { err -> Result.failure(err) },
        ).getOrElse { return Result.failure(it) }

        // 路线软审计：前置顺序 / 重复引入 / 范围覆盖 / 负荷 / 类型单一化 / 链连续性（第二轮 P1-5）
        // 演进链（chains）在批次 A 不落库，链连续性检查暂以空链运行，其余审计项完整生效
        routeAuditSink(
            RouteAudit.audit(
                chapters = output.chapters,
                nodeTitlesByPrereqs = nodes.associate { it.title to it.prerequisites },
                allNodeTitles = titleToId.keys,
                dailyMinutes = project.dailyMinutes,
            ),
        )

        val plans = output.chapters.mapIndexed { index, chapter ->
            ChapterPlan(
                id = idFactory(),
                projectId = projectId,
                order = if (chapter.order > 0) chapter.order else index + 1,
                title = chapter.title,
                plotGoal = chapter.plotGoal,
                learningGoals = chapter.learningGoals,
                nodeIds = chapter.nodeTitles.mapNotNull { titleToId[it.trim()] }.distinct(),
                wordTarget = chapter.wordTarget.coerceIn(MIN_WORDS, MAX_WORDS),
                status = ChapterStatus.QUEUED,
                derivationChain = chapter.derivationChain,
                // 开头钩子存进它自己的列。规划期与发布期不再共用一列（3B）
                openingHook = chapter.openingHook,
                coveredTitles = emptyList(),
                // 首次规划出来的都是第一卷（3C）
                volumeIndex = FIRST_VOLUME,
                volumeTitle = "第一卷",
                volumeGoal = output.conflict,
                // 章节类型与因果契约落库（第二轮 P1-2）：正文与核验阶段要用
                chapterType = chapter.chapterType,
                contractJson = ContractCodec.encode(chapter.contract),
            )
            // 卷内章数硬上限：模型多给了也只收前 VOLUME_MAX_CHAPTERS 章，
            // 剩下的知识点留给续卷，而不是一次摊平
        }.take(VOLUME_MAX_CHAPTERS)

        // 这里没有已发布章节（上面已经拦过），清理是安全的：
        // 清掉的只是上一次规划出来、还一个字都没写的章节计划
        chapterRepository.clearVersionsForProject(projectId)
        // 引用跟着正文块一起清。留着的话它们会指向不存在的块，
        // 「这份资料被哪几段引用了」就会给出点不开的结果（4A）
        citationRepository.deleteByProject(projectId)
        // 核验问题同理：它们说的是某一版正文的某一块，那些块马上就不存在了（4B）
        issueRepository.deleteByProject(projectId)
        chapterRepository.clearBlocksForProject(projectId)
        chapterRepository.clearPlansForProject(projectId)
        chapterRepository.upsertPlans(plans)

        // 保存全局设定，同时把滚动记忆清零重新开始（问题5）
        storyBibleRepository.save(
            StoryBible(
                projectId = projectId,
                world = ContextBudget.clamp(output.world, ContextBudget.WORLD_LIMIT),
                conflict = output.conflict,
                style = output.style,
                approach = output.approach,
                characters = output.characters.map { "${it.name}｜${it.role}｜${it.description}" },
                throughLine = output.throughLine.ifEmpty {
                    plans.map { "第${it.order}章：${it.derivationChain.ifBlank { it.plotGoal }}" }
                },
                runningState = "",
                coveredTitles = emptyList(),
                openThreads = emptyList(),
                currentVolumeIndex = FIRST_VOLUME,
                updatedAt = clock(),
            ),
        )
        // 自由发挥模式下把模型自选的风格回填，UI 与后续章节都以它为准（问题6）
        projectRepository.update(
            project.copy(
                status = ProjectStatus.OUTLINE,
                resolvedGenre = output.style.take(60),
                updatedAt = clock(),
            ),
        )
        return Result.success(Unit)
    }

    /**
     * 一卷内的章数。一章承载一整条演进链，所以章数远少于知识点数（问题7）；
     * 上限是「一次规划的量」而不是「全书上限」——后续内容由续卷接（3C）。
     */
    internal fun suggestChapterCount(nodeCount: Int): Int =
        (nodeCount / NODES_PER_CHAPTER).coerceIn(VOLUME_MIN_CHAPTERS, VOLUME_MAX_CHAPTERS)

    /**
     * 能不能规划下一卷（第三阶段 3C）。
     *
     * 两个条件：当前卷全部可读了，而且知识地图里还有没讲到的节点。
     * 当前卷没写完就续卷会打乱故事顺序——读者还没读到那儿，
     * 而新卷的开场必须接上「上一章的结尾」，那个结尾此刻还不存在。
     */
    suspend fun nextVolumeAvailability(projectId: String): NextVolumeAvailability {
        val plans = chapterRepository.getPlans(projectId)
        if (plans.isEmpty()) return NextVolumeAvailability.NoPlanYet
        val currentVolume = plans.maxOf { it.volumeIndex }
        val currentVolumeChapters = plans.filter { it.volumeIndex == currentVolume }
        if (currentVolumeChapters.any { !it.isReadable }) {
            return NextVolumeAvailability.CurrentVolumeUnfinished(
                remaining = currentVolumeChapters.count { !it.isReadable },
            )
        }
        val pending = coverageReport(projectId, plans).pendingNodes
        return if (pending.isEmpty()) {
            // 主线讲完了。仍然可以继续，但要和「继续主线」明确区分开，
            // 否则模型会被无止境地要求「再来一卷」
            NextVolumeAvailability.MainLineDone
        } else {
            NextVolumeAvailability.CanContinue(pendingCount = pending.size)
        }
    }

    /**
     * 规划下一卷（第三阶段 3C）。
     *
     * 只往后加章节：已发布章节一个字不改、正文一块不删、order 一律接在现有章节之后。
     * 上下文同样是有限的几段——绝不把历史正文重新塞进模型。
     * 规划失败时什么都不写，不留半成品章节计划。
     */
    suspend fun generateNextVolume(projectId: String): Result<Unit> {
        val project = projectRepository.get(projectId)
            ?: return Result.failure(IllegalStateException("项目不存在"))
        val plans = chapterRepository.getPlans(projectId)
        if (plans.isEmpty()) {
            return Result.failure(IllegalStateException("还没有讲述路线，先规划第一卷"))
        }
        val currentVolume = plans.maxOf { it.volumeIndex }
        val unfinished = plans.filter { it.volumeIndex == currentVolume && !it.isReadable }
        if (unfinished.isNotEmpty()) {
            return Result.failure(
                IllegalStateException("当前这一卷还有 ${unfinished.size} 章没写完，先把它读完再往下讲"),
            )
        }

        val allNodes = knowledgeRepository.getByProject(projectId)
        if (allNodes.isEmpty()) return Result.failure(IllegalStateException("知识地图为空"))
        val coverage = coverageReport(projectId, plans)
        val pendingNodes = coverage.pendingNodes
        val pendingTitles = coverage.pendingTitles
        val bible = storyBibleRepository.get(projectId) ?: StoryBible(projectId = projectId)
        // 上一章的结尾只取它当前版本的（3B）；没有就明说，不伪造
        val lastChapter = plans.maxByOrNull { it.order }
        val lastEnding = lastChapter?.let { chapterRepository.getActiveVersion(it.id) }

        val suggested = suggestChapterCount(pendingNodes.size.coerceAtLeast(1))
        val prompt = prompts.load(PROMPT_NEXT_VOLUME)
            .replace("{topic}", project.topic)
            .replace("{goal}", project.title)
            .replace("{baseline}", project.baseline)
            .replace("{currentVolume}", "${currentVolume + 1}")
            .replace("{chapterHint}", "$suggested 章（不要超过 $VOLUME_MAX_CHAPTERS 章）")
            .replace("{wordHint}", "${project.wordTargetPerChapter}")
            .replace("{runningState}", ContextBudget.orNone(bible.runningState))
            .replace("{lastEndingState}", ContextBudget.previousEndingState(lastChapter, lastEnding))
            .replace("{openThreads}", ContextBudget.formatList(bible.openThreads, ContextBudget.OPEN_THREAD_LIMIT))
            .replace("{coveredTitles}", ContextBudget.formatCovered(bible.coveredTitles))
            .replace("{pendingNodes}", formatNodesWithDerivation(pendingNodes))

        val raw = router.generate(
            role = ModelRole.STORY,
            request = ModelRequest(
                role = ModelRole.STORY,
                system = SYSTEM_NEXT_VOLUME,
                user = prompt,
                jsonMode = true,
            ),
            decoder = jsonDecoder(NextVolumeOutput.serializer()),
        ).getOrElse { return Result.failure(it) }

        val titleToId = allNodes.associate { it.title to it.id }
        val output = OutputValidators.validateNextVolume(
            output = raw,
            knownNodeTitles = titleToId.keys,
            pendingNodeTitles = pendingTitles,
            maxChapters = VOLUME_MAX_CHAPTERS,
            requireContracts = requireChapterContracts(),
        ).getOrElse { return Result.failure(it) }

        // 续卷同样过一遍路线软审计（第二轮 P1-5）
        routeAuditSink(
            RouteAudit.audit(
                chapters = output.chapters,
                nodeTitlesByPrereqs = allNodes.associate { it.title to it.prerequisites },
                allNodeTitles = titleToId.keys,
                dailyMinutes = project.dailyMinutes,
            ),
        )

        // order 从现有最大值往后排，绝不与已有章节冲突
        val nextOrder = plans.maxOf { it.order } + 1
        val newVolume = currentVolume + 1
        val added = output.chapters.mapIndexed { index, chapter ->
            ChapterPlan(
                id = idFactory(),
                projectId = projectId,
                order = nextOrder + index,
                title = chapter.title,
                plotGoal = chapter.plotGoal,
                learningGoals = chapter.learningGoals,
                nodeIds = chapter.nodeTitles.mapNotNull { titleToId[it.trim()] }.distinct(),
                wordTarget = chapter.wordTarget.coerceIn(MIN_WORDS, MAX_WORDS),
                status = ChapterStatus.QUEUED,
                derivationChain = chapter.derivationChain,
                openingHook = chapter.openingHook,
                volumeIndex = newVolume,
                volumeTitle = chapter.volumeTitleOr(output.volumeTitle, newVolume),
                volumeGoal = output.volumeGoal,
                // 续卷章节同样带类型与契约（第二轮 P1-2）
                chapterType = chapter.chapterType,
                contractJson = ContractCodec.encode(chapter.contract),
            )
        }
        // 只 upsert 新章节。不 clear 任何东西——这是「往后接」，不是「重新规划」
        chapterRepository.upsertPlans(added)
        storyBibleRepository.save(
            bible.copy(
                currentVolumeIndex = newVolume,
                // 新卷的主线追加进全书主线，续写时模型才知道后面还有这一段
                throughLine = (bible.throughLine + "第${newVolume + 1}卷：${output.volumeGoal}")
                    .takeLast(ContextBudget.THROUGH_LINE_LIMIT * 2),
                updatedAt = clock(),
            ),
        )
        refreshProjectStatus(projectId)
        return Result.success(Unit)
    }

    private fun ChapterPlanDto.volumeTitleOr(fallback: String, volumeIndex: Int): String =
        fallback.ifBlank { "第${volumeIndex + 1}卷" }

    /**
     * 因果契约的落库/读库编解码（批次 A.1）。
     *
     * 落库用 [ContractCodec.encode]（空契约 → 空串）；读库用 [ContractCodec.decode]——
     * 它区分四种状态：Valid / None（老数据无契约）/ MissingAfterType（声明类型但契约缺失）/
     * Corrupt（数据损坏）。损坏绝不回退成看似正常的空契约，正文 prompt 与核验计划摘要
     * 都会显示「契约不可用」，而不是静默按无契约处理。
     */
    private fun chapterContractState(plan: ChapterPlan): ContractCodec.State =
        ContractCodec.decode(plan.contractJson, plan.chapterType)

    /**
     * 还没被任何章节安排到、也没有讲过的知识点（3D 起按 nodeId 判断）。
     *
     * 「安排过但还没写」也算已安排，否则续卷会把当前卷正要讲的东西再排一遍。
     * 判据从标题换成了 ID：标题是用户能改的，改一次名字就会让讲过的内容
     * 全部变回「还没讲」，续卷把它们重新排一遍（详见 [NodeCoverage]）。
     */
    private suspend fun coverageReport(
        projectId: String,
        plans: List<ChapterPlan>,
    ): NodeCoverage.CoverageReport = NodeCoverage.report(
        nodes = knowledgeRepository.getByProject(projectId),
        plans = plans,
        bible = storyBibleRepository.get(projectId),
    )

    /**
     * 「讲过、但对不上任何现存知识点」的标题（3D）。
     *
     * 一般是知识点改过名，或知识地图被重新生成过。这类内容不能当成「没讲过」——
     * 那会让续卷把已经讲透的东西再排一遍；也不能当成覆盖了某个节点——
     * 不知道是哪个。所以如实列出来，由用户确认。
     */
    suspend fun unresolvedCoverageTitles(projectId: String): List<String> =
        coverageReport(projectId, chapterRepository.getPlans(projectId)).unresolvedTitles

    suspend fun generateChapter(
        projectId: String,
        planId: String,
        /** 子步骤进度上报（问题2）。默认不上报，保持既有调用方不变。 */
        onPhase: ProgressReporter = NoOpProgressReporter,
    ): Result<Unit> {
        val plan = chapterRepository.getPlan(planId)
            ?: return Result.failure(IllegalStateException("章节不存在"))
        // 取消与失败收尾要知道「进来之前这一章有没有可读正文、这次写的是哪个版本」（第三阶段 3A）
        val attempt = ChapterAttempt(
            originalStatus = plan.status,
            originalActiveVersionId = plan.activeVersionId,
        )
        return try {
            runChapter(projectId, planId, plan, attempt, onPhase)
        } catch (e: CancellationException) {
            // 取消的收尾必须在不可取消的上下文里做，否则这几条写库自己也会被取消，
            // 章节就永久卡在 RUNNING / VERIFYING
            withContext(NonCancellable) { finalizeCancelledChapter(projectId, planId, attempt) }
            throw e
        }
    }

    /**
     * 单章生成走到哪一步了。取消与失败收尾都靠它判断该恢复成什么状态。
     *
     * [hadReadableBody] 是 3A 的关键：进来之前就有一版通过核验的正文时，
     * 这一次生成无论怎么失败都不该影响读者——旧版本还在，章节状态一个字都不用改。
     */
    private class ChapterAttempt(
        val originalStatus: ChapterStatus,
        val originalActiveVersionId: String?,
    ) {
        /** 本次生成写入的新版本。为 null 说明还没建版本行 */
        var version: ChapterVersion? = null

        /** 新版本的正文块是否已落库。落库也不影响旧正文——它们挂在不同 versionId 上 */
        var bodyWritten: Boolean = false

        /** 新版本是否已经发布并成为 activeVersion */
        var published: Boolean = false

        /** 进来之前就有可读正文（重新生成场景） */
        val hadReadableBody: Boolean
            get() = originalActiveVersionId != null && originalStatus.isReadable
    }

    private suspend fun runChapter(
        projectId: String,
        planId: String,
        plan: ChapterPlan,
        attempt: ChapterAttempt,
        onPhase: ProgressReporter,
    ): Result<Unit> {
        val project = projectRepository.get(projectId)
            ?: return Result.failure(IllegalStateException("项目不存在"))

        // 进入生成态：项目 GENERATING（B17）。
        // 章节状态只在「还没有可读正文」时才推到 RUNNING —— 重生成一个已发布章节时
        // 把它改成 RUNNING 会让它在整个生成期间变得不可读，而旧正文明明好好地躺在库里（3A）
        if (project.status != ProjectStatus.GENERATING) {
            projectRepository.update(project.copy(status = ProjectStatus.GENERATING, updatedAt = clock()))
        }
        if (!attempt.hadReadableBody) {
            chapterRepository.updateStatus(planId, ChapterStatus.RUNNING)
        }
        onPhase(ChapterPhase.BODY)

        // 新正文写进一个独立版本。此刻它对读者完全不可见，
        // 直到核验通过后 activeVersionId 才指过来
        val version = chapterRepository.insertDraftVersion(
            chapterId = planId,
            projectId = projectId,
            title = plan.title,
            now = clock(),
        )
        attempt.version = version

        val modelKnowledge = project.usesModelKnowledge
        val allNodes = knowledgeRepository.getByProject(projectId)
        val nodes = plan.nodeIds.mapNotNull { id -> allNodes.firstOrNull { it.id == id } }
        val chunks = if (modelKnowledge) emptyList() else loadChunksForNodes(projectId, nodes)
        // 片段的来源、页码、版本、法域要一起喂给模型（4A）
        loadSourceMeta(projectId)

        val bible = storyBibleRepository.get(projectId) ?: StoryBible(projectId = projectId)
        val plans = chapterRepository.getPlans(projectId)
        val previous = plans.firstOrNull { it.order == plan.order - 1 }
        val next = plans.firstOrNull { it.order == plan.order + 1 }
        // 上一章的前情只认它**当前版本**的结尾状态（3B）。
        // 上一章还没写出来时这里是 null，提示词会明说「没有上一章」而不是让模型自己编一个。
        val previousEnding = previous?.let { chapterRepository.getActiveVersion(it.id) }

        // 上下文只由固定预算的几段构成，与已生成章数无关（问题5）
        val prompt = prompts.load(PROMPT_CHAPTER)
            .replace("{derivationChain}", ContextBudget.orNone(plan.derivationChain.ifBlank { plan.plotGoal }))
            // 本章要留的口子 = 下一章**计划里**的开头钩子。
            // 绝不能用下一章已经写出来的结尾——那是后面发生的事，
            // 重生成本章时拿它当收尾提示等于让本章去衔接一个还不该存在的未来（3B）
            .replace("{closingHook}", ContextBudget.orNone(next?.openingHook.orEmpty()))
            .replace("{world}", ContextBudget.orNone(bible.world))
            .replace("{style}", ContextBudget.orNone(bible.style.ifBlank { project.effectiveGenre }))
            .replace("{characters}", ContextBudget.formatCharacters(bible.characters))
            .replace("{throughLine}", ContextBudget.formatThroughLine(bible.throughLine))
            .replace("{runningState}", ContextBudget.orNone(bible.runningState))
            .replace("{previousEndingState}", ContextBudget.previousEndingState(previous, previousEnding))
            .replace("{coveredTitles}", ContextBudget.formatCovered(bible.coveredTitles))
            .replace("{openThreads}", ContextBudget.formatList(bible.openThreads, ContextBudget.OPEN_THREAD_LIMIT))
            .replace("{chapterTitle}", plan.title)
            .replace("{plotGoal}", plan.plotGoal)
            .replace("{learningGoals}", plan.learningGoals.joinToString("；"))
            .replace("{openingHook}", ContextBudget.orNone(plan.openingHook))
            .replace("{nodes}", formatNodesWithDerivation(nodes))
            .replace("{sourceSection}", sourceSection(modelKnowledge, chunks))
            .replace("{wordTarget}", plan.wordTarget.toString())
            .replace("{runningBudget}", ContextBudget.RUNNING_STATE_LIMIT.toString())
            .replace("{unverifiedSection}", unverifiedSection(modelKnowledge))
            // 章节类型与因果契约（第二轮 P1-2）：正文必须按契约走拍子
            .replace("{chapterType}", plan.chapterType.ifBlank { "未指定（按通用顺序写作）" })
            .replace("{contract}", ContractCodec.describe(chapterContractState(plan)))

        val rawOutput = router.generate(
            role = ModelRole.STORY,
            request = ModelRequest(
                role = ModelRole.STORY,
                system = SYSTEM_CHAPTER,
                user = prompt,
                jsonMode = true,
                temperature = 0.85f,
            ),
            decoder = jsonDecoder(ChapterOutput.serializer()),
        ).getOrElse {
            failAttempt(planId, attempt, it.message ?: "生成失败", ChapterStatus.FAILED)
            return Result.failure(it)
        }

        val titleToId = nodes.associate { it.title to it.id }
        val validChunkIds = chunks.map { it.id }.toSet()
        val output = OutputValidators.validateChapter(
            output = rawOutput,
            knownNodeTitles = titleToId.keys,
            validChunkIds = validChunkIds,
            // 无资料模式没有片段可引，改由核验的待核实清单承担追溯责任（问题3）
            requireSourceRefs = !modelKnowledge,
            requireNodeLinks = requireBlockNodeLinks(),
        ).getOrElse {
            failAttempt(planId, attempt, it.message ?: "章节内容不合要求", ChapterStatus.FAILED)
            return Result.failure(it)
        }

        val rawBlocks = output.blocks.mapIndexed { index, block ->
            ChapterBlock(
                id = idFactory(),
                chapterId = planId,
                versionId = version.id,
                projectId = projectId,
                order = index,
                text = block.text,
                blockType = block.type,
                knowledgeNodeIds = block.knowledgeNodeTitles.mapNotNull { titleToId[it.trim()] }.distinct(),
                // sourceRefs 先留空，下面按解析结果回填——能不能解析到真实片段
                // 由 CitationResolver 一处说了算，这里不重复一遍那套判断（4A）
            )
        }
        // 引用关系落成独立的行（4A）：解析到片段的记 RESOLVED，
        // 对不上任何片段的记 UNCONFIRMED，而不是像以前那样静默丢掉。
        // 丢掉的后果是正文里那句需要出处的话变成了没人管的断言，
        // 而阅读器还会显示「本段无引用」——看起来跟模型本来就没打算给出处一样。
        val citations = CitationResolver.resolve(
            projectId = projectId,
            chapterId = planId,
            versionId = version.id,
            blocks = rawBlocks.mapIndexed { index, block ->
                CitationResolver.BlockRefs(
                    blockId = block.id,
                    rawRefs = output.blocks[index].sourceRefs,
                )
            },
            validChunkIds = validChunkIds,
        )
        val blocks = rawBlocks.map { block ->
            block.copy(sourceRefs = citations.resolvedByBlock[block.id].orEmpty())
        }
        // 新正文写进新版本，旧版本的块一个都不删（第三阶段 3A）。
        // 这就是「重生成会覆盖已发布正文」这个风险的根治办法：两版正文挂在不同
        // versionId 上共存，阅读器只认 chapters.activeVersionId 指的那一版。
        chapterRepository.upsertBlocks(blocks)
        citationRepository.replaceForVersion(version.id, citations.citations)
        attempt.bodyWritten = true
        chapterRepository.updateVersionStatus(version.id, ChapterVersionStatus.VERIFYING, now = clock())
        if (!attempt.hadReadableBody) {
            chapterRepository.updateStatus(planId, ChapterStatus.VERIFYING)
        }
        onPhase(ChapterPhase.VERIFY)

        val verification = verifyChapter(projectId, plan, blocks)
        if (verification.isFailure) {
            val message = verification.exceptionOrNull()?.message ?: "核验失败"
            failAttempt(planId, attempt, "核验未完成：$message", ChapterStatus.NEEDS_REVIEW)
            return Result.failure(verification.exceptionOrNull() ?: IllegalStateException("核验失败"))
        }

        val verified = verification.getOrThrow()
        // 核验问题落成一条条记录（4B）。
        //
        // 原来这里是 `issues.take(5).joinToString("\n")`：一整章的问题被拼成
        // 一个字符串，第 6 条起丢掉，blockId / sourceRefs / suggestedFix 全部蒸发。
        // 用户看到一段没法点、没法定位的文字，唯一的出路是整章重新生成——
        // 把写对的部分连同写错的部分一起推翻，还要再扣一次故事模型的钱。
        val recorded = IssueRecorder.record(
            projectId = projectId,
            chapterId = planId,
            versionId = version.id,
            issues = verified.issues,
            validBlockIds = blocks.map { it.id }.toSet(),
            now = clock(),
        )
        issueRepository.replaceForVersion(version.id, recorded.issues)
        if (recorded.issues.isNotEmpty()) {
            failAttempt(planId, attempt, issueSummary(recorded), ChapterStatus.NEEDS_REVIEW)
            return Result.failure(IllegalStateException("章节存在 ${recorded.issues.size} 个待处理问题"))
        }

        // 正文过了核验 —— 到这里这一章就算写成了，先发布再说（第二阶段）。
        //
        // 原来的顺序是「核验通过 → 出题 → 出题成功才发布」，出题一失败，
        // 正文明明写好、核验也过了，章节却停在 QUIZ_FAILED 打不开。
        // 故事是主流程，测验是可选辅助，两件事不该绑在一个状态上。
        val unverified = (output.unverifiedClaims + verified.unverifiedClaims)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_UNVERIFIED_CLAIMS)
        val endingState = ContextBudget.clamp(
            output.endingState.ifBlank { output.summary },
            ContextBudget.CARRY_OVER_LIMIT,
        )
        // 本章讲到的知识点：ID 是判据，标题只用于展示（3D）。
        // 模型只会给标题，这里映射成 ID，映射不上的丢掉——
        // 台账里塞一个不存在的 ID 等于永久少讲一个知识点，而且看不出错在哪
        val coveredTitles = output.coveredTitles.ifEmpty { nodes.map { it.title } }
        val coveredIds = coveredTitles.mapNotNull { titleToId[it.trim()] }
            .ifEmpty { nodes.map { it.id } }
            .distinct()

        // 原子切换：新版本置 PUBLISHED、上一版发布正文置 ARCHIVED（不是 ABANDONED——
        // 那一版成功过，只是被取代了）、章节指针改指向新版本、章节展示元数据同步换掉。
        // 中途断电不会出现「章节指向一个没发布的版本」（3A/3D）
        chapterRepository.publishVersionAtomically(
            version = version.copy(
                summary = output.summary,
                endingState = endingState,
                coveredTitles = coveredTitles,
                coveredNodeIds = coveredIds,
                unverifiedClaims = unverified,
                // 记住这一版是在什么知识模式下写出来的（4A）。用户以后补了资料，
                // 也不能让这一版看起来像是有出处的——它当时一条引用都引不到
                usedModelKnowledge = modelKnowledge,
                verificationNote = if (unverified.isEmpty()) {
                    ""
                } else {
                    "有 ${unverified.size} 处内容建议你核对权威出处"
                },
            ),
            now = clock(),
        )
        attempt.published = true
        // 推进滚动记忆：runningState 被改写而不是追加，因此长度不随章数增长（问题5）。
        // 这三步都只依赖「正文通过核验」，与出题成败无关——否则出题失败会让
        // 下一章续写不上、知识点跳不到正文。
        advanceMemory(bible, output, coveredIds, allNodes.map { it.id }.toSet())
        writeBackNodeChapters(projectId, planId, blocks)
        scheduleReviews(projectId, blocks)
        refreshProjectStatus(projectId)

        // 自测题是可选产物：出题失败只记在 quizState 上，正文照样能读、下一章照样能生成
        onPhase(ChapterPhase.QUIZ)
        val quizResult = generateQuiz(projectId, plan, nodes, blocks, version.id)
        if (quizResult.isSuccess) {
            chapterRepository.updateQuizState(planId, QuizState.READY)
        } else {
            val message = quizResult.exceptionOrNull()?.message ?: "测验生成失败"
            chapterRepository.updateQuizState(planId, QuizState.FAILED, message)
        }
        return Result.success(Unit)
    }

    /**
     * 只重写有问题的正文块（第四阶段 4B）。
     *
     * ```
     * 保留 active version
     *   → 从 active version 派生草稿版本
     *   → 复制没问题的块（换新 ID）
     *   → 只重写选中的那几块
     *   → 全章重新核验
     *   → 通过后原子切换 activeVersionId
     * ```
     *
     * 全程不碰 active version 一个字段。中途失败、取消、或者重新核验还有问题，
     * 草稿版本置 ABANDONED，读者手里的正文完好无损——这是 3A 定下的底线，
     * 局部重写没有理由例外。
     *
     * ## 为什么发布前一定要全章重新核验
     *
     * 只核验改过的那几块，成本确实低得多，但局部修改**恰恰**是最容易
     * 制造全章不一致的操作：改掉第 3 段里的一个数字，第 7 段那句
     * 「如前所述，这个期限是三年」就成了错的，而第 7 段自己一个字都没改，
     * 只核验改动块永远发现不了它。所以宁可多花一次核验的钱。
     */
    suspend fun rewriteBlocks(
        projectId: String,
        planId: String,
        blockIds: List<String>,
        onPhase: ProgressReporter = NoOpProgressReporter,
    ): Result<Unit> {
        if (blockIds.isEmpty()) {
            return Result.failure(IllegalStateException("没有选中要修改的段落"))
        }
        val plan = chapterRepository.getPlan(planId)
            ?: return Result.failure(IllegalStateException("章节不存在"))
        val active = chapterRepository.getActiveVersion(planId)
            ?: return Result.failure(
                IllegalStateException("本章还没有通过核验的正文，请先整章生成"),
            )
        val oldBlocks = chapterRepository.getBlocksByVersion(active.id)
        if (oldBlocks.isEmpty()) {
            return Result.failure(IllegalStateException("当前正文没有内容可修改"))
        }
        val targets = blockIds.toSet()
        if (oldBlocks.none { it.id in targets }) {
            return Result.failure(
                IllegalStateException("选中的段落不属于当前正文（可能正文已经更新过）"),
            )
        }

        var draft: ChapterVersion? = null
        return try {
            runRewrite(projectId, planId, plan, active, oldBlocks, targets, onPhase) { draft = it }
        } catch (e: CancellationException) {
            // 收尾必须在不可取消的上下文里做，否则这几条写库自己也会被取消，
            // 草稿版本会永久卡在 DRAFT / VERIFYING
            withContext(NonCancellable) {
                draft?.let {
                    chapterRepository.abandonVersionIfPending(
                        it.id,
                        "局部修改已取消，正文仍是此前那一版。",
                        clock(),
                    )
                }
                refreshProjectStatus(projectId)
            }
            throw e
        }
    }

    private suspend fun runRewrite(
        projectId: String,
        planId: String,
        plan: ChapterPlan,
        active: ChapterVersion,
        oldBlocks: List<ChapterBlock>,
        targets: Set<String>,
        onPhase: ProgressReporter,
        onDraftCreated: (ChapterVersion) -> Unit,
    ): Result<Unit> {
        val project = projectRepository.get(projectId)
            ?: return Result.failure(IllegalStateException("项目不存在"))
        onPhase(ChapterPhase.BODY)

        // 草稿版本。指针不动，读者读到的还是 active version
        val draft = chapterRepository.insertDerivedDraftVersion(
            chapterId = planId,
            projectId = projectId,
            source = active,
            now = clock(),
        )
        onDraftCreated(draft)

        // 旧块 → 新块 ID。每一块都换新 ID，包括没改动的（4B）。
        //
        // 复用旧 ID 会让两个版本共享同一批块行，「改第 3 块」当场也改掉了
        // 旧版本——旧正文变成一份从未通过核验的混合体，3A 的前提就没了。
        // 而且核验问题是按 blockId 记的：ID 复用的话，挂在旧版本上的问题
        // 会同时挂到新版本上，用户修完了问题还在。
        val idMapping = oldBlocks.associate { it.id to idFactory() }
        val oldCitations = citationRepository.getByVersion(active.id).groupBy { it.blockId }
        val issuesByBlock = issueRepository.getByVersion(active.id)
            .filter { it.resolution.isOpen }
            .groupBy { it.blockId }

        val modelKnowledge = active.usedModelKnowledge
        val allNodes = knowledgeRepository.getByProject(projectId)
        val nodes = plan.nodeIds.mapNotNull { id -> allNodes.firstOrNull { it.id == id } }
        val chunks = if (modelKnowledge) emptyList() else loadChunksForNodes(projectId, nodes)
        loadSourceMeta(projectId)
        val validChunkIds = chunks.map { it.id }.toSet()
        val titleToId = allNodes.associate { it.title to it.id }

        val newBlocks = mutableListOf<ChapterBlock>()
        val rewrittenRefs = mutableMapOf<String, List<String>>()
        val rewrittenIds = mutableListOf<String>()
        val changeNotes = mutableListOf<String>()

        for ((index, old) in oldBlocks.withIndex()) {
            val newId = idMapping.getValue(old.id)
            if (old.id !in targets) {
                // 没问题的块整块搬过去，一个字不改
                newBlocks += old.copy(id = newId, versionId = draft.id)
                continue
            }
            val rewritten = rewriteOneBlock(
                plan = plan,
                project = project,
                target = old,
                previous = oldBlocks.getOrNull(index - 1),
                next = oldBlocks.getOrNull(index + 1),
                issues = issuesByBlock[old.id].orEmpty(),
                nodes = nodes,
                chunks = chunks,
                modelKnowledge = modelKnowledge,
            ).getOrElse { error ->
                chapterRepository.abandonVersionIfPending(
                    draft.id,
                    "局部修改失败：${error.message ?: "生成失败"}。正文仍是此前那一版。",
                    clock(),
                )
                refreshProjectStatus(projectId)
                return Result.failure(error)
            }
            newBlocks += old.copy(
                id = newId,
                versionId = draft.id,
                text = rewritten.text,
                // 段落类型跟着原块，不让模型顺手改掉（见 BlockRewriteOutput）
                knowledgeNodeIds = rewritten.knowledgeNodeTitles
                    .mapNotNull { titleToId[it.trim()] }
                    .distinct()
                    .ifEmpty { old.knowledgeNodeIds },
                sourceRefs = emptyList(),
            )
            rewrittenRefs[newId] = rewritten.sourceRefs
            rewrittenIds += newId
            rewritten.changeNote.takeIf { it.isNotBlank() }?.let { changeNotes += it }
        }

        // 引用关系：没改过的块**照搬**旧引用（只换 blockId 与 versionId），
        // 改过的块重新解析。
        //
        // 照搬而不是重新解析没改过的块，是因为那些块的文字一个字都没变——
        // 重新解析等于让模型重新给一遍引用，结果可能和读者当初看到的不一样，
        // 而那一段正文根本没动。UNCONFIRMED 也照搬：它当初就没对上，
        // 这次也没有任何新信息能让它对上。
        val carried = newBlocks.filterNot { it.id in rewrittenIds }.flatMap { block ->
            val oldId = idMapping.entries.first { it.value == block.id }.key
            oldCitations[oldId].orEmpty().mapIndexed { order, citation ->
                citation.copy(
                    id = Citation.idFor(block.id, order),
                    versionId = draft.id,
                    blockId = block.id,
                    order = order,
                )
            }
        }
        val resolved = CitationResolver.resolve(
            projectId = projectId,
            chapterId = planId,
            versionId = draft.id,
            blocks = rewrittenIds.map { id ->
                CitationResolver.BlockRefs(blockId = id, rawRefs = rewrittenRefs[id].orEmpty())
            },
            validChunkIds = validChunkIds,
        )
        val blocks = newBlocks.map { block ->
            when {
                block.id in rewrittenIds ->
                    block.copy(sourceRefs = resolved.resolvedByBlock[block.id].orEmpty())
                else -> block
            }
        }
        chapterRepository.upsertBlocks(blocks)
        citationRepository.replaceForVersion(draft.id, carried + resolved.citations)
        chapterRepository.setRewrittenBlocks(draft.id, rewrittenIds)
        chapterRepository.updateVersionStatus(draft.id, ChapterVersionStatus.VERIFYING, now = clock())
        onPhase(ChapterPhase.VERIFY)

        // 全章重新核验，不是只核改过的那几块（理由见 rewriteBlocks 的注释）
        val verification = verifyChapter(projectId, plan, blocks)
        if (verification.isFailure) {
            val message = verification.exceptionOrNull()?.message ?: "核验失败"
            chapterRepository.abandonVersionIfPending(
                draft.id,
                "局部修改后的核验没能完成：$message。正文仍是此前那一版。",
                clock(),
            )
            refreshProjectStatus(projectId)
            return Result.failure(verification.exceptionOrNull() ?: IllegalStateException(message))
        }

        val verified = verification.getOrThrow()
        val recorded = IssueRecorder.record(
            projectId = projectId,
            chapterId = planId,
            versionId = draft.id,
            issues = verified.issues,
            validBlockIds = blocks.map { it.id }.toSet(),
            now = clock(),
        )
        // 新问题记在草稿版本上，旧版本的问题一条不动——它们说的是那一版的文字
        issueRepository.replaceForVersion(draft.id, recorded.issues)
        if (recorded.issues.isNotEmpty()) {
            chapterRepository.abandonVersionIfPending(
                draft.id,
                "局部修改后仍有 ${recorded.issues.size} 个问题，这一版没有发布。" +
                    "正文仍是此前那一版（可继续阅读）。",
                clock(),
            )
            refreshProjectStatus(projectId)
            return Result.failure(
                IllegalStateException("修改后的正文仍有 ${recorded.issues.size} 个问题，未发布"),
            )
        }

        // 过了 —— 原子切换。旧版本置 ARCHIVED（它成功过），指针指向草稿。
        //
        // rewrittenBlockIds 必须在这里再写一遍：发布走的是整行 upsert，
        // 上面那次 setRewrittenBlocks 写的列会被这一行盖掉。
        // （上面那次仍然要写——草稿没能发布时，那份名单是「这次试着改了哪几块」的唯一记录。）
        chapterRepository.publishVersionAtomically(
            version = draft.copy(
                rewrittenBlockIds = rewrittenIds,
                verificationNote = changeNotes.take(MAX_CHANGE_NOTES)
                    .joinToString("；")
                    .ifBlank { "已修改 ${rewrittenIds.size} 个段落并通过核验。" },
            ),
            now = clock(),
        )
        // 旧版本上那几块的问题：重写并重新核验后没再出现 → 标记已修。
        // 「已修」不是用户手点的状态，它由这一串事实推出来（见 IssueResolution）
        issueRepository.markFixedForBlocks(active.id, targets.toList())
        writeBackNodeChapters(projectId, planId, blocks)
        refreshProjectStatus(projectId)

        // 正文换了版本，旧版本的题问的是被改掉的文字，重新出一次
        onPhase(ChapterPhase.QUIZ)
        val quizResult = generateQuiz(projectId, plan, nodes, blocks, draft.id)
        if (quizResult.isSuccess) {
            chapterRepository.updateQuizState(planId, QuizState.READY)
        } else {
            chapterRepository.updateQuizState(
                planId,
                QuizState.FAILED,
                quizResult.exceptionOrNull()?.message ?: "测验生成失败",
            )
        }
        return Result.success(Unit)
    }

    /** 重写一个块。上下段原文一起给模型，改出来的段落才接得回原位（4B） */
    private suspend fun rewriteOneBlock(
        plan: ChapterPlan,
        project: Project,
        target: ChapterBlock,
        previous: ChapterBlock?,
        next: ChapterBlock?,
        issues: List<VerificationIssue>,
        nodes: List<KnowledgeNode>,
        chunks: List<SourceChunk>,
        modelKnowledge: Boolean,
    ): Result<BlockRewriteOutput> {
        val prompt = prompts.load(PROMPT_REWRITE_BLOCK)
            .replace("{derivationChain}", ContextBudget.orNone(plan.derivationChain.ifBlank { plan.plotGoal }))
            .replace("{learningGoals}", plan.learningGoals.joinToString("；"))
            .replace("{previousBlock}", previous?.text?.take(NEIGHBOUR_EXCERPT) ?: "（这是本章第一段）")
            .replace("{blockType}", target.blockType.name)
            .replace("{targetBlock}", target.text)
            .replace("{nextBlock}", next?.text?.take(NEIGHBOUR_EXCERPT) ?: "（这是本章最后一段）")
            .replace("{issues}", formatIssues(issues))
            .replace("{sourceSection}", sourceSection(modelKnowledge, chunks))

        val raw = router.generate(
            role = ModelRole.STORY,
            request = ModelRequest(
                role = ModelRole.STORY,
                system = SYSTEM_REWRITE,
                user = prompt,
                jsonMode = true,
                // 比整章生成低：这是修一个具体问题，不是重新创作
                temperature = 0.5f,
            ),
            decoder = jsonDecoder(BlockRewriteOutput.serializer()),
        ).getOrElse { return Result.failure(it) }

        if (raw.text.isBlank()) {
            return Result.failure(ModelError.Invalid("改写结果是空的"))
        }
        return Result.success(raw)
    }

    /**
     * 把这一块的问题喂给模型。
     *
     * 问题为空时明说「没有指出具体问题」而不是留一段空白：模型看到空白
     * 会自己找点什么改，而用户点「重写这一块」时想要的是解决已知问题。
     */
    private fun formatIssues(issues: List<VerificationIssue>): String {
        if (issues.isEmpty()) {
            return "（核验没有指出这一段的具体问题，用户是自己选择重写它的。" +
                "请只做必要的澄清，不要重新创作。）"
        }
        return issues.joinToString("\n\n") { issue ->
            buildString {
                append("- 【").append(issue.type.label).append("】").append(issue.message)
                if (issue.suggestedFix.isNotBlank()) {
                    append("\n  建议改法：").append(issue.suggestedFix)
                }
                if (issue.sourceRefs.isNotEmpty()) {
                    append("\n  相关片段ID：").append(issue.sourceRefs.joinToString("、"))
                }
            }
        }
    }

    /**
     * 给章节行留的一句话说明（4B）。
     *
     * 问题的正文已经在 verification_issues 表里逐条存着了，所以这里主要说清
     * 「有几个问题、能不能只修那几段」——那是决定用户点哪个按钮的信息。
     *
     * 但前几条问题的原文仍然要带上，而且不能省：一次失败的**重生成**留下的
     * 问题挂在作废的草稿版本上，而问题清单只读当前正文的问题（它必须如此，
     * 否则用户会看到一堆说的是已经被替换掉的文字的问题）。也就是说，
     * 那种情况下这句说明是唯一能告诉用户「哪里不对」的地方。
     */
    private fun issueSummary(recorded: IssueRecorder.Recorded): String {
        val blockCount = recorded.affectedBlockIds.size
        val unlocated = recorded.chapterScoped.size
        return buildString {
            append("核验发现 ${recorded.issues.size} 个问题")
            if (blockCount > 0) append("，涉及 $blockCount 个正文段落（可只重写这几段）")
            if (unlocated > 0) append("，其中 $unlocated 条没能定位到具体段落")
            append("。")
            recorded.issues.take(NOTE_ISSUE_PREVIEW).forEach { issue ->
                append("\n[").append(issue.type.label).append("] ").append(issue.message)
            }
            if (recorded.issues.size > NOTE_ISSUE_PREVIEW) {
                append("\n……还有 ${recorded.issues.size - NOTE_ISSUE_PREVIEW} 条，展开逐条处理可以全部查看。")
            }
        }
    }

    /**
     * 本次生成失败的收尾（第三阶段 3A）。
     *
     * 问题绑在**新版本**上，不去覆盖当前发布版本的说明——那是另一份正文的事。
     * 进来之前就有可读正文时，章节状态一个字都不改：读者手里的正文没有任何变化，
     * 把它改成 FAILED / NEEDS_REVIEW 等于因为一次失败的重写把读过的内容收回去。
     */
    private suspend fun failAttempt(
        planId: String,
        attempt: ChapterAttempt,
        note: String,
        fallbackStatus: ChapterStatus,
    ) {
        attempt.version?.let { chapterRepository.abandonVersionIfPending(it.id, note, clock()) }
        if (attempt.hadReadableBody) return
        chapterRepository.updateStatus(planId, fallbackStatus, note)
    }

    /**
     * 取消发生后把这一章收拾干净（第二阶段 + 第三阶段 3A）。
     *
     * 3A 之后这里简单了很多：新正文写在独立版本上，取消时把那个版本置为 ABANDONED 就行，
     * 旧正文根本没被动过。第二阶段那句「原来的正文已被新生成的正文替换」也就不再需要——
     * 因为它不会再发生了。
     */
    private suspend fun finalizeCancelledChapter(
        projectId: String,
        planId: String,
        attempt: ChapterAttempt,
    ) {
        // 已经发布的不动。正文可读就是可读，取消后面的步骤不该把它收回去。
        if (attempt.published) return

        val note = if (attempt.bodyWritten) {
            "生成已取消，本次新正文尚未完成核验，已作废。"
        } else {
            "生成已取消，可以重新生成。"
        }
        attempt.version?.let { chapterRepository.abandonVersionIfPending(it.id, note, clock()) }

        // 进来之前就有可读正文：activeVersionId 一个字都没改，读者手里的正文完好无损，
        // 章节状态也不需要动
        if (!attempt.hadReadableBody) {
            val target = if (attempt.bodyWritten) {
                // 写出来了但没过核验。正文留在作废版本里可查，但没有可读内容，
                // 所以章节不能算可读
                ChapterStatus.NEEDS_REVIEW
            } else {
                ChapterStatus.QUEUED
            }
            chapterRepository.updateStatusIfIn(
                id = planId,
                status = target,
                note = note,
                // 只收拾还停在生成态的章节
                onlyIf = IN_FLIGHT_STATUSES,
            )
        }
        // 项目状态也别卡在 GENERATING
        refreshProjectStatus(projectId)
    }

    /**
     * 把本章产出并入滚动记忆（问题5）。
     * runningState 取模型改写后的新版本并强制截断到预算内，避免它逐章变长。
     * 覆盖台账同时记 ID 与标题：ID 是判据，标题只喂给模型看（3D）。
     */
    private suspend fun advanceMemory(
        bible: StoryBible,
        output: ChapterOutput,
        coveredIds: List<String>,
        knownNodeIds: Set<String>,
    ) {
        val nextState = output.runningStateUpdate.ifBlank {
            // 模型没给改写版本时退回「旧状态 + 本章摘要」再截断，仍然受预算约束
            listOf(bible.runningState, output.summary).filter { it.isNotBlank() }.joinToString(" ")
        }
        storyBibleRepository.updateMemory(
            projectId = bible.projectId,
            runningState = ContextBudget.clamp(nextState, ContextBudget.RUNNING_STATE_LIMIT),
            coveredTitles = ContextBudget.mergeCovered(bible.coveredTitles, output.coveredTitles),
            coveredNodeIds = NodeCoverage.mergeCoveredIds(
                existing = bible.coveredNodeIds,
                added = coveredIds,
                knownIds = knownNodeIds,
            ),
            openThreads = ContextBudget.mergeThreads(
                existing = bible.openThreads,
                added = output.openThreads,
                // 本章讲透的内容视为已回收，避免悬念列表只增不减
                resolved = output.coveredTitles,
            ),
            updatedAt = clock(),
        )
    }

    suspend fun verifyChapter(
        projectId: String,
        plan: ChapterPlan,
        blocks: List<ChapterBlock>,
    ): Result<VerificationOutput> {
        val project = projectRepository.get(projectId)
        val modelKnowledge = project?.usesModelKnowledge == true
        val nodeIds = blocks.flatMap { it.knowledgeNodeIds }.distinct().toSet()
        val nodes = knowledgeRepository.getByProject(projectId).filter { it.id in nodeIds }
        val derivation = ContextBudget.orNone(plan.derivationChain.ifBlank { plan.plotGoal })
        val body = blocks.joinToString("\n\n") {
            "[blockId: ${it.id}] [type: ${it.blockType.name}]\n${it.text.take(BLOCK_EXCERPT)}"
        }
        // 计划摘要（第二轮 P1-3）：核验拿着本章「打算讲什么」逐项对正文。
        // 包含章节类型与因果契约（v15 起落库），核验据此检查契约是否走完、按类型豁免回顾/解释章节
        val planSummary = buildString {
            append("本章类型：").appendLine(plan.chapterType.ifBlank { "未指定" })
            append("本章要走的推演：").appendLine(derivation)
            if (plan.plotGoal.isNotBlank()) append("本章目标：").appendLine(plan.plotGoal)
            if (plan.learningGoals.isNotEmpty()) {
                append("应当想通的问题：").appendLine(plan.learningGoals.joinToString("；"))
            }
            if (plan.openingHook.isNotBlank()) append("开头处境：").appendLine(plan.openingHook)
            append("本章因果契约：").appendLine(ContractCodec.describe(chapterContractState(plan)))
        }.trim()

        // 无资料模式核验的是「推演是否走通 + 有没有编造出处」，而不是「与资料是否一致」（问题3）
        val prompt = if (modelKnowledge) {
            prompts.load(PROMPT_VERIFY_MODEL)
                .replace("{topic}", project?.topic.orEmpty())
                .replace("{derivationChain}", derivation)
                .replace("{learningGoals}", plan.learningGoals.joinToString("；"))
                .replace("{chapterPlan}", planSummary)
                .replace("{nodes}", formatNodesWithDerivation(nodes))
                .replace("{blocks}", body)
        } else {
            val chunks = loadChunksForNodes(projectId, nodes)
            // 核验也要看到片段的页码、章节、版本、法域（4A）：
            // 「这条规则只在某个法域成立」这类问题，没有元数据是核不出来的
            loadSourceMeta(projectId)
            prompts.load(PROMPT_VERIFY)
                .replace("{derivationChain}", derivation)
                .replace("{learningGoals}", plan.learningGoals.joinToString("；"))
                .replace("{chapterPlan}", planSummary)
                .replace("{chunks}", formatChunks(chunks))
                .replace("{blocks}", body)
        }

        val result = router.generate(
            role = ModelRole.VERIFY,
            request = ModelRequest(
                role = ModelRole.VERIFY,
                system = SYSTEM_VERIFY,
                user = prompt,
                jsonMode = true,
                temperature = 0f,
            ),
            decoder = jsonDecoder(VerificationOutput.serializer()),
        )
        // contractChecks 轻校验（批次 B 后续）：nodeTitle 为空的条目丢弃；
        // 不因 checks 结构问题让整个核验失败（旧模型无 checks 完全兼容）
        if (result.isSuccess) {
            val output = result.getOrThrow()
            return Result.success(output.copy(checks = output.checks.filter { it.nodeTitle.isNotBlank() }))
        }
        return result
    }

    /** 出题使用核验模型，与设置页说明一致（N3） */
    suspend fun generateQuiz(
        projectId: String,
        plan: ChapterPlan,
        nodes: List<KnowledgeNode>,
        blocks: List<ChapterBlock>,
        /** 出题依据的正文版本。旧版本的题留在库里但不会显示在新正文上（3A） */
        versionId: String,
    ): Result<Unit> {
        if (nodes.isEmpty()) {
            return Result.failure(ModelError.Invalid("本章没有关联知识点，无法出题"))
        }
        val prompt = prompts.load(PROMPT_QUIZ)
            .replace("{derivationChain}", ContextBudget.orNone(plan.derivationChain.ifBlank { plan.plotGoal }))
            .replace("{learningGoals}", plan.learningGoals.joinToString("；"))
            .replace("{nodes}", formatNodesWithDerivation(nodes))
            .replace(
                "{blocks}",
                blocks.joinToString("\n\n") { "[${it.blockType.name}] ${it.text.take(QUIZ_EXCERPT)}" },
            )

        val raw = router.generate(
            role = ModelRole.VERIFY,
            request = ModelRequest(
                role = ModelRole.VERIFY,
                system = "你是测验出题人。",
                user = prompt,
                jsonMode = true,
                temperature = 0.2f,
            ),
            decoder = jsonDecoder(QuizSetOutput.serializer()),
        ).getOrElse { return Result.failure(it) }

        val titleToId = nodes.associate { it.title to it.id }
        val output = OutputValidators.validateQuizSet(raw, titleToId.keys, MIN_QUIZ_QUESTIONS)
            .getOrElse { return Result.failure(it) }

        val quizzes = output.questions.take(MAX_QUIZ_QUESTIONS).mapIndexed { index, quiz ->
            Quiz(
                id = idFactory(),
                projectId = projectId,
                chapterId = plan.id,
                versionId = versionId,
                order = index,
                question = quiz.question,
                type = quiz.type,
                options = quiz.options,
                answer = quiz.answer,
                explanation = quiz.explanation,
                nodeIds = quiz.nodeTitles.mapNotNull { titleToId[it.trim()] }.distinct(),
            )
        }
        // 只换掉这一版的题，别的版本的题一律保留（3A）
        quizRepository.deleteForVersion(plan.id, versionId)
        quizRepository.upsertAll(quizzes)
        return Result.success(Unit)
    }

    /**
     * 只重新出题，不碰正文（第二阶段）。
     *
     * 「重新生成自测」按钮走这条路：正文已经发布，没有任何理由为了补几道题
     * 再调一次故事模型重写一遍正文——那既费钱又可能把读过的内容换掉。
     */
    suspend fun regenerateQuiz(projectId: String, planId: String): Result<Unit> {
        val plan = chapterRepository.getPlan(planId)
            ?: return Result.failure(IllegalStateException("章节不存在"))
        // 只按当前版本出题。拿历史版本的正文出题，题目问的会是读者看不到的情节（3A）
        val blocks = chapterRepository.getActiveBlocks(planId)
        if (blocks.isEmpty()) {
            return Result.failure(IllegalStateException("本章还没有正文，先生成正文再出题"))
        }
        val allNodes = knowledgeRepository.getByProject(projectId)
        val nodeIds = (blocks.flatMap { it.knowledgeNodeIds } + plan.nodeIds).distinct()
        val nodes = nodeIds.mapNotNull { id -> allNodes.firstOrNull { it.id == id } }

        val result = generateQuiz(projectId, plan, nodes, blocks, plan.activeVersionId.orEmpty())
        if (result.isSuccess) {
            chapterRepository.updateQuizState(planId, QuizState.READY)
        } else {
            chapterRepository.updateQuizState(
                planId,
                QuizState.FAILED,
                result.exceptionOrNull()?.message ?: "测验生成失败",
            )
        }
        return result
    }

    suspend fun refreshProjectStatus(projectId: String) {
        val project = projectRepository.get(projectId) ?: return
        val plans = chapterRepository.getPlans(projectId)
        if (plans.isEmpty()) return
        // 用 isReadable 而不是 == PUBLISHED：旧库里的 QUIZ_FAILED 正文其实是可读的，
        // 不该因为缺几道自测题就让整个项目永远显示「生成中」
        val allPublished = plans.all { it.status.isReadable }
        val target = if (allPublished) ProjectStatus.READY else ProjectStatus.GENERATING
        if (project.status != target) {
            projectRepository.update(project.copy(status = target, updatedAt = clock()))
        }
    }

    /** 把「该节点出现在哪些章节」写回节点，知识页才能跳转阅读（B16） */
    private suspend fun writeBackNodeChapters(
        projectId: String,
        chapterId: String,
        blocks: List<ChapterBlock>,
    ) {
        val touched = blocks.flatMap { it.knowledgeNodeIds }.distinct()
        if (touched.isEmpty()) return
        val nodes = knowledgeRepository.getByProject(projectId).associateBy { it.id }
        touched.forEach { nodeId ->
            val node = nodes[nodeId] ?: return@forEach
            if (chapterId in node.chapterIds) return@forEach
            knowledgeRepository.updateChapterIds(nodeId, node.chapterIds + chapterId)
        }
    }

    /**
     * 章节发布后为涉及的知识点建立首次复习计划（B2）。
     * 用户没在设置里打开间隔复习就不排——默认只管往下读。
     */
    private suspend fun scheduleReviews(projectId: String, blocks: List<ChapterBlock>) {
        if (!spacedReviewEnabled()) return
        val nodeIds = blocks.flatMap { it.knowledgeNodeIds }.distinct()
        if (nodeIds.isEmpty()) return
        val existing = reviewRepository.getByProject(projectId).map { it.nodeId }.toSet()
        val now = clock()
        val newItems = nodeIds.filter { it !in existing }.map { nodeId ->
            ReviewItem(
                id = idFactory(),
                projectId = projectId,
                nodeId = nodeId,
                dueAt = now + FIRST_REVIEW_DELAY_MILLIS,
                intervalDays = 1,
                status = ReviewStatus.PENDING,
            )
        }
        if (newItems.isNotEmpty()) reviewRepository.upsertAll(newItems)
    }

    /**
     * 骨架落库：为每个唯一标题建占位节点（批次化生成）。
     *
     * nodeKey 由程序分配、作为节点身份；标题只做显示。占位节点内容留空，
     * generateStatus = PLACEHOLDER，由逐链展开阶段回填。
     * [batchId] 标记节点所属批次：重新生成时新批次节点先落库但不生效，
     * 整批成功后原子切换 active 批次才可见（v14）。
     */
    private fun buildSkeletonNodes(
        projectId: String,
        skeleton: KnowledgeMapSkeletonOutput,
        batchId: String,
    ): List<KnowledgeNode> {
        val titles = skeleton.chains.flatMap { it.nodeTitles }.map { it.trim() }.distinct()
        return titles.mapIndexed { index, title ->
            KnowledgeNode(
                id = idFactory(),
                projectId = projectId,
                title = title,
                nodeKey = "nk-${idFactory()}",
                generateStatus = NodeGenerateStatus.PLACEHOLDER.name,
                depth = 1,
                order = index,
                batchId = batchId,
            )
        }
    }

    /**
     * 逐链资料检索（批次化生成）。
     *
     * 用链主题 + 节点标题作为关键词匹配片段文本；命中数多者优先，
     * 最多 [CHAIN_CHUNK_LIMIT] 条。没有匹配时降级为项目前若干片段
     * （引用仍然只能落在本次提供的片段上——模型拿不到列表之外的 ID）。
     */
    private fun selectChunksForChain(
        allChunks: List<SourceChunk>,
        chain: ChainDto,
    ): List<SourceChunk> {
        val keywords = buildList {
            addAll(chain.nodeTitles.map { it.trim() }.filter { it.length >= 2 })
            chain.theme.trim().takeIf { it.length >= 2 }?.let { add(it) }
            chain.opening.trim().takeIf { it.length >= 2 }?.let { add(it) }
        }.distinct()
        if (keywords.isEmpty()) return allChunks.take(CHAIN_CHUNK_LIMIT)
        val scored = allChunks.map { chunk ->
            chunk to keywords.count { kw -> chunk.text.contains(kw) }
        }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(CHAIN_CHUNK_LIMIT)
        return if (scored.isEmpty()) allChunks.take(CHAIN_CHUNK_LIMIT) else scored
    }

    private fun decodeChains(jsonStr: String): List<ChainDto> = runCatching {
        if (jsonStr.isBlank() || jsonStr == "[]") emptyList()
        else json.decodeFromString(ListSerializer(ChainDto.serializer()), jsonStr)
    }.getOrDefault(emptyList())

    private fun decodeChainStatuses(jsonStr: String): List<KnowledgeMapChainStatus> = runCatching {
        if (jsonStr.isBlank() || jsonStr == "[]") emptyList()
        else json.decodeFromString(ListSerializer(String.serializer()), jsonStr).mapNotNull { name ->
            KnowledgeMapChainStatus.entries.firstOrNull { it.name == name }
        }
    }.getOrDefault(emptyList())

    private fun encodeStatuses(statuses: List<KnowledgeMapChainStatus>): String =
        json.encodeToString(ListSerializer(String.serializer()), statuses.map { it.name })

    private fun decodeStringList(jsonStr: String): List<String> = runCatching {
        if (jsonStr.isBlank() || jsonStr == "[]") emptyList()
        else json.decodeFromString(ListSerializer(String.serializer()), jsonStr)
    }.getOrDefault(emptyList())

    private fun decodeConflicts(jsonStr: String): List<ConflictDto> = runCatching {
        if (jsonStr.isBlank() || jsonStr == "[]") emptyList()
        else json.decodeFromString(ListSerializer(ConflictDto.serializer()), jsonStr)
    }.getOrDefault(emptyList())

    private fun buildKnowledgeNodes(
        projectId: String,
        output: KnowledgeMapOutput,
        validChunkIds: Set<String>,
    ): List<KnowledgeNode> {
        val created = mutableListOf<KnowledgeNode>()
        val titleToId = mutableMapOf<String, String>()
        for (dto in output.nodes) {
            val parentId = dto.parentTitle?.trim()?.let { titleToId[it] }
            val node = KnowledgeNode(
                id = idFactory(),
                projectId = projectId,
                parentId = parentId,
                title = dto.title.trim(),
                definition = dto.definition,
                arisesFrom = dto.arisesFrom,
                leadsTo = dto.leadsTo,
                prerequisites = dto.prerequisites,
                related = dto.related,
                confusions = dto.confusions,
                examples = dto.examples,
                // sourceRefs 保存的是片段 ID，与后续加载口径一致（B1）
                sourceRefs = dto.sourceRefs.filter { it in validChunkIds }.distinct(),
                depth = dto.depth.coerceIn(1, 3),
                order = created.size,
            )
            titleToId[node.title] = node.id
            created += node
        }
        return created
    }

    /**
     * 依据节点 sourceRefs 中保存的片段 ID 精确加载资料片段；
     * 没有可用引用时退回项目的前若干片段（B1）。
     */
    private suspend fun loadChunksForNodes(
        projectId: String,
        nodes: List<KnowledgeNode>,
    ): List<SourceChunk> {
        val chunkIds = nodes.flatMap { it.sourceRefs }.toSet()
        if (chunkIds.isNotEmpty()) {
            val resolved = sourceRepository.getChunksByIds(projectId, chunkIds)
            if (resolved.isNotEmpty()) return resolved
        }
        return sourceRepository.getChunksByProject(projectId).take(FALLBACK_CHUNK_LIMIT)
    }

    private fun formatChunks(chunks: List<SourceChunk>): String {
        if (chunks.isEmpty()) return "（本项目采用 AI 自备知识模式，没有用户资料）"
        return chunks.take(MAX_CHUNKS).joinToString("\n\n") { chunk ->
            "[片段ID: ${chunk.id}]${sourceIdentity(chunk)}\n${chunk.text.take(CHUNK_EXCERPT)}"
        }
    }

    /**
     * 片段的结构化来源标识（4A）。
     *
     * 模型看到的不再只是一串 UUID，而是「出自哪份资料的哪一页哪一节、什么版本、
     * 哪个法域、什么时候拿到的」。两个用处：引用能落到具体位置；模型自己知道
     * 「这份资料只适用于某个法域和某个时间之后」，不会把它讲成普适规则。
     *
     * 元数据来自 [sourceMetaCache]，它在每次生成开始时按项目装一次。
     */
    private fun sourceIdentity(chunk: SourceChunk): String {
        val document = sourceMetaCache[chunk.sourceId]
        val parts = buildList {
            document?.title?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(chunk.locatorLabel)
            document?.versionLabelForDisplay?.let { add("版本：$it") }
            document?.jurisdiction?.takeIf { it.isNotBlank() }?.let { add("法域：$it") }
            document?.effectiveFrom?.takeIf { it.isNotBlank() }?.let { add("生效：$it") }
        }
        return if (parts.isEmpty()) "" else " 来源：" + parts.joinToString(" · ")
    }

    /**
     * 本次生成用到的资料元数据，sourceId → 资料行。
     *
     * 之所以是一个字段而不是参数：`formatChunks` 被章节、核验、知识地图三处调用，
     * 每处都多穿一个参数只是把同一件事说三遍。生成是单线程顺序执行的，
     * 每次进入前 [loadSourceMeta] 会整体换掉它。
     */
    private var sourceMetaCache: Map<String, SourceDocument> = emptyMap()

    private suspend fun loadSourceMeta(projectId: String) {
        sourceMetaCache = sourceRepository.getByProject(projectId).associateBy { it.id }
    }

    /** 节点连带来处、去处与前置一起给模型，推演才接得上（问题2 / 第二轮 P1-4） */
    private fun formatNodesWithDerivation(nodes: List<KnowledgeNode>): String =
        nodes.joinToString("\n") { node ->
            buildString {
                append("- ").append(node.title).append("：").append(node.definition.take(NODE_EXCERPT))
                if (node.arisesFrom.isNotBlank()) {
                    append("\n    来处：").append(node.arisesFrom.take(NODE_EXCERPT))
                }
                if (node.leadsTo.isNotBlank()) {
                    append("\n    去处：").append(node.leadsTo.take(NODE_EXCERPT))
                }
                if (node.prerequisites.isNotEmpty()) {
                    append("\n    前置：").append(node.prerequisites.take(4).joinToString("；"))
                }
                if (node.confusions.isNotEmpty()) {
                    append("\n    易混：").append(node.confusions.take(3).joinToString("；"))
                }
            }
        }

    private fun formatChains(chains: List<String>): String =
        chains.take(CONTEXT_CHAIN_LIMIT).mapIndexed { i, s -> "${i + 1}. $s" }
            .joinToString("\n").ifBlank { "（未识别到明确的演进链，请自行按节点的来处/去处组织）" }

    /** 有资料时给出片段区块；无资料时明确告知，避免模型伪造引用（问题3） */
    private fun sourceSection(modelKnowledge: Boolean, chunks: List<SourceChunk>): String =
        if (modelKnowledge) {
            """
            资料：本项目没有用户资料，知识由你自己提供。
            因此：所有 block 的 sourceRefs 一律留空数组；不要编造条文编号、判例名称、统计数字、人名年份。
            规则内容本身可以讲清楚，但不要伪造出处。
            """.trimIndent()
        } else {
            // 「编一个 ID 会被记成未确认」这句话必须写明（4A）。
            // 不写的话模型没有任何理由不去凑一个看起来像的 ID——它以前那么做的时候，
            // 那条引用被静默丢掉，正文里那句话就成了没有出处的断言。
            buildString {
                appendLine("资料片段。引用时把**上面出现过的**片段ID原样填进对应 block 的 sourceRefs：")
                appendLine()
                appendLine(formatChunks(chunks))
                appendLine()
                appendLine("关于引用（4A，必须遵守）：")
                appendLine("- 只能填上面列出的片段ID，原样复制，不要改写、拼接或缩短。")
                appendLine("- 不要编造片段ID。编出来的 ID 会被记成「未确认引用」直接展示给读者，不会变成来源。")
                appendLine("- 每个 FACT 段落至少引一个真实片段；讲解性段落引用它实际依据的片段。")
                append("- 引用不到资料、只能靠常识说的内容，宁可不引，也不要引一个不相干的片段充数。")
            }
        }

    private fun unverifiedSection(modelKnowledge: Boolean): String =
        if (modelKnowledge) {
            "- `unverifiedClaims`：本章里读者**不该只信你一家之言**的断言（具体数字门槛、期限、地区差异、近年修改）。宁可多列不要漏列。"
        } else {
            "- `unverifiedClaims`：留空数组。"
        }

    private companion object {
        /** 生成中的状态。取消收尾只在章节仍停在这些状态时才动它。 */
        val IN_FLIGHT_STATUSES = listOf(
            ChapterStatus.RUNNING,
            ChapterStatus.VERIFYING,
            ChapterStatus.DRAFT,
        )

        const val PROMPT_KNOWLEDGE_MAP = "knowledge_map"
        const val PROMPT_KNOWLEDGE_MAP_MODEL = "knowledge_map_model"
        // 批次化生成的三阶段 prompt（骨架 → 逐链 → 审计）
        const val PROMPT_KNOWLEDGE_MAP_SKELETON = "knowledge_map_skeleton"
        const val PROMPT_KNOWLEDGE_MAP_CHAIN = "knowledge_map_chain"
        const val PROMPT_KNOWLEDGE_MAP_AUDIT = "knowledge_map_audit"
        const val PROMPT_STORY_BIBLE = "story_bible"
        const val PROMPT_NEXT_VOLUME = "next_volume"
        const val PROMPT_CHAPTER = "chapter"
        const val PROMPT_VERIFY = "verify"
        const val PROMPT_VERIFY_MODEL = "verify_model"
        const val PROMPT_QUIZ = "quiz"
        const val PROMPT_REWRITE_BLOCK = "rewrite_block"

        const val SYSTEM_MAP = "你在整理一门学科的演进主干，不是在做目录。"
        const val SYSTEM_MAP_MODEL =
            "你在整理一门学科的演进主干，知识由你自己提供。宁可承认不确定，也不要编造具体出处。"
        const val SYSTEM_MAP_SKELETON = "你在规划一门学科知识主干的骨架：只列演进链与节点清单，不写节点内容。"
        const val SYSTEM_MAP_CHAIN = "你在展开知识主干里的一条演进链：把链上每个知识点写完整，不新增节点、不改标题、不动别的链已写好的内容。"
        const val SYSTEM_MAP_AUDIT = "你是知识地图的全局审计员：通读全部节点，给出最终的「知识缺口」与「资料冲突」清单。"
        const val SYSTEM_BIBLE = "你在设计一条讲述路线，让学科沿着它自己长出来。"
        const val SYSTEM_NEXT_VOLUME =
            "你在给一条已经开讲的讲述路线接下一卷。已经写出来的部分不能改，只能往后接。"
        const val SYSTEM_CHAPTER = "你在写一段推演：让读者跟着走，制度自己出现，而不是被告知名词。"
        const val SYSTEM_VERIFY = "你是核验员。既查知识准确性，也查推演有没有跳步。"
        const val SYSTEM_REWRITE =
            "你在修一整章里的一个段落。只解决指出的问题，其余保持原样，改完要能无缝接回原位。"

        const val MIN_QUIZ_QUESTIONS = 3
        const val MAX_QUIZ_QUESTIONS = 5
        const val FALLBACK_CHUNK_LIMIT = 20
        const val FIRST_REVIEW_DELAY_MILLIS = 86_400_000L

        // 一章承载一整条演进链，所以章数远少于知识点数（问题7）
        const val NODES_PER_CHAPTER = 4

        /** 第一卷的索引。0 而不是 1，这样旧数据靠默认值就落在第一卷（3C） */
        const val FIRST_VOLUME = 0

        /**
         * 一卷 4~8 章（3C）。
         *
         * 这不是全书上限：读完一卷可以继续规划下一卷，总章数没有硬顶。
         * 之所以不把上限直接改成 100，是因为「一次规划多少」和「总共能讲多少」
         * 是两件事——让模型一次想出 100 章，越往后越糊，而用户根本还没读到那儿。
         */
        const val VOLUME_MIN_CHAPTERS = 4
        const val VOLUME_MAX_CHAPTERS = 8
        const val MIN_WORDS = 1200
        const val MAX_WORDS = 4000

        const val MAX_UNVERIFIED_CLAIMS = 12
        const val MAX_CHUNKS = 50
        const val CHUNK_EXCERPT = 500
        const val NODE_EXCERPT = 160
        const val BLOCK_EXCERPT = 1200
        const val QUIZ_EXCERPT = 800
        const val CONTEXT_CHAIN_LIMIT = 10

        /** 逐链展开时每条链最多携带的资料片段数（批次化生成） */
        const val CHAIN_CHUNK_LIMIT = 20

        /** 批次化生成的 JSON 编解码 */
        private val json = Json { ignoreUnknownKeys = true }

        /** 局部重写时上下相邻段落给多少字。够判断衔接，不必给全文 */
        const val NEIGHBOUR_EXCERPT = 600

        /** 版本说明里最多留几条改动自述 */
        const val MAX_CHANGE_NOTES = 5

        /**
         * 章节说明里带几条问题原文（4B）。
         *
         * 不是「界面放不下更多」——这句说明只是入口，完整清单在问题面板里。
         * 带上前几条是为了失败的重生成：那种情况下问题挂在作废版本上，
         * 面板看不到，这句话是唯一的线索。
         */
        const val NOTE_ISSUE_PREVIEW = 3
    }
}
