package com.example.zhishu.ai.pipeline

import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.PromptProvider
import com.example.zhishu.ai.TokenBudget
import com.example.zhishu.ai.jsonDecoder
import com.example.zhishu.ai.outputs.BlockRewriteOutput
import com.example.zhishu.ai.outputs.ChainDto
import com.example.zhishu.ai.outputs.ChainNodesOutput
import com.example.zhishu.ai.outputs.ChapterContractDto
import com.example.zhishu.ai.outputs.ChapterContractOutput
import com.example.zhishu.ai.outputs.ChapterOutput
import com.example.zhishu.ai.outputs.ChapterPlanDto
import com.example.zhishu.ai.outputs.ChapterType
import com.example.zhishu.ai.outputs.ConflictDto
import com.example.zhishu.ai.outputs.GapDto
import com.example.zhishu.ai.outputs.ContractCodec
import com.example.zhishu.ai.outputs.KnowledgeMapAuditOutput
import com.example.zhishu.ai.outputs.KnowledgeMapSkeletonOutput
import com.example.zhishu.ai.outputs.NextVolumeOutput
import com.example.zhishu.ai.outputs.NodeDto
import com.example.zhishu.ai.outputs.OutputValidators
import com.example.zhishu.ai.outputs.QuizSetOutput
import com.example.zhishu.ai.outputs.RouteAudit
import com.example.zhishu.ai.outputs.RouteAuditIssue
import com.example.zhishu.ai.outputs.StoryBibleOutput
import com.example.zhishu.ai.outputs.VerificationOutput
import com.example.zhishu.ai.outputs.VolumeOutlineOutput
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.ContractStatus
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.GapStatus
import com.example.zhishu.domain.model.GapType
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
import com.example.zhishu.domain.model.RouteBatch
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.StoryBible
import com.example.zhishu.domain.model.VerificationIssue
import com.example.zhishu.domain.model.chainStatusList
import com.example.zhishu.domain.model.contractStatusList
import com.example.zhishu.domain.model.encodeContractStatuses
import com.example.zhishu.domain.model.encodeStatuses
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

/**
 * 知识主干生成的推进模式（分步生成）。
 *
 * - [FULL]：骨架（若未完成）→ 全部演进链 → 全局审计，一口气跑完。一键开讲链用这个。
 * - [SKELETON]：只做骨架就停。链怎么展开由用户看着结构自己决定——逐条或全部。
 * - [NEXT_CHAIN]：推进下一条未完成的链；链全部完成时做全局审计收尾。
 */
enum class KnowledgeMapMode { FULL, SKELETON, NEXT_CHAIN }

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
     * 原来一次性让模型输出 14-44 个节点 + 全部演进链，单次 2 万 token 输出、
     * 最坏几分钟不出结果；现在拆成三步，每步一次小调用（30-90 秒），
     * 失败只重试该步，进度逐链上报。
     *
     * 幂等与断点：骨架、每条链、审计各自落库后才进入下一步；重试/重启后
     * 从 [KnowledgeMapBatch] 里读到已完成的部分直接跳过，只补没做完的。
     * 重新生成（旧批次已完成/已失败）开新批次并清掉旧地图，旧节点不会被误认成断点。
     *
     * 单条链展开失败不再中止整批：失败链标记后继续后面的链，最后汇总报
     * 「N 条中 M 条失败」，续跑只补失败链。审计只在全部链完成后进行。
     *
     * [mode] 控制推进节奏（见 [KnowledgeMapMode]）；[onProgress] 逐阶段上报
     * 文案与单元数（默认不上报，保持既有调用方不变）。
     */
    suspend fun generateKnowledgeMap(
        projectId: String,
        mode: KnowledgeMapMode = KnowledgeMapMode.FULL,
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

        // 旧处理状态快照，必须在骨架阶段之前读——见下面两条：
        //
        // 1. 骨架分支里的 replaceInsights 是**全量 clear + 重写**，写进去的是预览行
        //    （status 空、suggestedTitle 空）；
        // 2. 而 gaps/conflicts 的重建发生在**审计阶段**，若那时再调 getGaps 读继承，
        //    读到的就是骨架刚写入的预览行，用户的 RESOLVED / DISMISSED 一条都继承不到。
        //
        // 结果就是「补过的、点掉的，重新梳理一次全冒回来」——这是这个功能最伤信任的
        // 失败模式。所以在这里（也在 isFirstGeneration 的 clearForProject 之前）先把
        // 已处理条目的状态抄成 Map，后面的重建只认这份快照。
        val inheritedGapStatus = knowledgeRepository.getGaps(projectId)
            .filter { it.gapStatus != GapStatus.OPEN && it.suggestedTitle.isNotBlank() }
            .associate { it.suggestedTitle.trim() to it.gapStatus.name }
        val inheritedConflictStatus = knowledgeRepository.getConflicts(projectId)
            .filter { it.conflictStatus != GapStatus.OPEN && it.topic.isNotBlank() }
            .associate { it.topic.trim() to it.conflictStatus.name }

        // ===== 1. 骨架（一次小调用，只列链结构与节点标题清单） =====
        if (!batch.skeletonDone) {
            onProgress(GenerationProgress("正在梳理知识主干（骨架）", 0, -1))
            val skeleton = generateSkeleton(project)
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
                conflicts = buildConflicts(projectId, skeleton.conflicts, inheritedConflictStatus),
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

        // 分步生成：骨架先行，用户看过链结构再决定逐条展开还是全部展开
        if (mode == KnowledgeMapMode.SKELETON) {
            onProgress(GenerationProgress("骨架已完成，可逐条或全部展开演进链", 1, 1))
            return Result.success(Unit)
        }

        val chains = decodeChains(batch.chainsJson)
        if (chains.isEmpty()) return Result.failure(IllegalStateException("骨架没有演进链，无法展开"))
        val total = chains.size + 2 // 骨架 + 审计
        var completed = 1

        // ===== 2. 逐链展开（每链一次小调用，写完即落库） =====
        val statuses = batch.chainStatusList().toMutableList()
        val unmatchedTitles = mutableListOf<String>()
        var failedChains = 0
        // 每条失败链的具体原因：原来只汇总「N 条中 M 条失败」，用户看不到到底是
        // 网络、限流、还是输出被截断——而这三者的下一步动作完全不同
        val chainFailures = mutableListOf<String>()
        var stepped = false // 单步模式：本轮已推进过一条链（成功或失败都算）

        chains.forEachIndexed { index, chain ->
            if (statuses.getOrElse(index) { KnowledgeMapChainStatus.PENDING } == KnowledgeMapChainStatus.DONE) {
                completed += 1
                return@forEachIndexed
            }
            if (mode == KnowledgeMapMode.NEXT_CHAIN && stepped) return@forEachIndexed
            statuses[index] = KnowledgeMapChainStatus.RUNNING
            knowledgeRepository.saveBatch(
                batch.copy(
                    chainStatuses = statuses.encodeStatuses(),
                    updatedAt = clock(),
                ),
            )
            onProgress(GenerationProgress("正在展开第 ${index + 1}/${chains.size} 条演进链", completed, total))

            // 一条链可能要多轮才能填满：单次输出被 max_tokens 截断时，本地剪裁只能
            // 救回前面的完整节点，尾部节点仍是占位。这里按「还差哪些节点」继续追问，
            // 每轮只要剩下的那些——输出随之变短，长链因此能在一次生成里走完，
            // 而不是每次都把整条链重跑一遍（既贵又同样会在同一处截断）。
            var chainFailure: Throwable? = null
            var pass = 0
            val maxPasses = chainMaxPasses(chain.nodeTitles.size)
            while (pass < maxPasses) {
                pass += 1
                // 每轮开始时从库里刷新节点：上一链/上一轮回填的结果必须在这里可见，
                // 共享节点才能「先到先得」不被覆盖，断点重试也能看到真实进度。
                // 只看本批次的节点：序号 nodeKey（nk-1、nk-2…）在批次间会重复，
                // 混入旧批次节点会匹配到错误目标；已展开摘要同理不该被旧地图污染
                val nodes = knowledgeRepository.getByProjectAll(projectId)
                    .filter { it.batchId == batch.id }
                val keyToNode = nodes.associateBy { it.nodeKey }
                val titleToNode = nodes.associateBy { it.title.trim() }
                val filledKeys = nodes
                    .filter { it.generateStatus == NodeGenerateStatus.COMPLETED.name }
                    .map { it.nodeKey }
                    .toSet()
                // 本轮还要填的节点：链上尚未 COMPLETED 的那些（跨链共享的已填节点自然跳过）。
                // 一轮最多问 CHAIN_NODES_PER_PASS 个：骨架允许长链（20+ 节点），
                // 一次问完输出必然超长，而超长的代价是整轮白费。分批问，每轮输出短、
                // 稳定落库，剩下的下一轮继续——CHAIN_MAX_PASSES 保证能问完。
                val pending = chain.nodeTitles
                    .mapNotNull { titleToNode[it.trim()] }
                    .filter { it.nodeKey !in filledKeys }
                if (pending.isEmpty()) break // 全填满了（含被别的链填完的情况）
                val remaining = pending.take(CHAIN_NODES_PER_PASS)

                // 上下文分档（见 ChainPromptContext）：首轮给这条链的全景，
                // 续轮/重试只给「还差哪些 + 接口在哪」。
                //
                // 用户的意见是对的：重试时把所有资料和所有已写节点的来处/去处/易混/案例
                // 整套重发一遍，除了让 prompt 更长、更贵、更容易撞上下文上限，没有任何收益。
                // 模型这一轮真正需要的只有两样：还差哪几个节点，以及紧挨着它们的上下游
                // 长什么样（arisesFrom/leadsTo 才咬得上）。其余节点只给标题。
                val isFirstPass = pass == 1
                val remainingTitles = remaining.map { it.title.trim() }
                // 本链已完成节点引用过的片段不再随下一轮重发：它们的内容
                // 已经通过 filledSection 给过模型，重发纯粹是重复计费
                val chainTitleSet = chain.nodeTitles.map { it.trim() }.toSet()
                val citedByChain = nodes
                    .filter {
                        it.generateStatus == NodeGenerateStatus.COMPLETED.name &&
                            it.title.trim() in chainTitleSet
                    }
                    .flatMap { it.sourceRefs }
                    .toSet()
                // 资料同理：续轮只按还差的那几个节点检索，不再整条链重新捞一遍
                val chainChunks = if (modelKnowledge) {
                    emptyList()
                } else {
                    selectChunksForChain(
                        allChunks = chunks,
                        chain = chain,
                        focusTitles = if (isFirstPass) emptyList() else remainingTitles,
                        pass = pass,
                        excludeIds = citedByChain,
                    )
                }
                val chainChunkIds = chainChunks.map { it.id }.toSet()
                val filledNodes = nodes.filter {
                    it.generateStatus == NodeGenerateStatus.COMPLETED.name && it.definition.isNotBlank()
                }
                val filledSection = if (isFirstPass) {
                    ChainPromptContext.firstPassFilledSection(
                        chainTitles = chain.nodeTitles,
                        remainingTitles = remainingTitles,
                        filledByTitle = filledNodes.associateBy { it.title.trim() },
                    )
                } else {
                    ChainPromptContext.followUpFilledSection(
                        chainTitles = chain.nodeTitles,
                        remainingTitles = remainingTitles,
                        filledByTitle = filledNodes.associateBy { it.title.trim() },
                    )
                }
                val nodeKeyTitles = remaining.joinToString("\n") { "${it.nodeKey}：${it.title}" }
                val chainPrompt = prompts.load(PROMPT_KNOWLEDGE_MAP_CHAIN)
                    .replace("{learnerProfile}", LearnerProfile.section(project))
                    .replace("{chainTheme}", chain.theme)
                    .replace("{chainOpening}", chain.opening)
                    .replace("{nodeTitles}", chain.nodeTitles.joinToString(" → "))
                    .replace("{filledNodes}", filledSection)
                    .replace("{chunks}", chainChunkSection(modelKnowledge, chainChunks))
                    .replace("{nodeKeyTitles}", nodeKeyTitles)

                val chainRaw = router.generate(
                    role = ModelRole.FAST,
                    request = ModelRequest(
                        role = ModelRole.FAST,
                        system = SYSTEM_MAP_CHAIN,
                        user = chainPrompt,
                        // 一轮最多 6 个节点，常规输出上限为 8K；输入意外变长时
                        // TokenBudget 会继续收紧，避免 prompt + max_tokens 越窗。
                        maxTokens = TokenBudget.outputLimit(
                            system = SYSTEM_MAP_CHAIN,
                            user = chainPrompt,
                            stageCap = TokenBudget.Stage.CHAIN,
                        ),
                        jsonMode = true,
                        stageLabel = "MAP_CHAIN#${index + 1}.p$pass",
                    ),
                    decoder = jsonDecoder(ChainNodesOutput.serializer()),
                ).getOrElse { error ->
                    chainFailure = error
                    null
                } ?: break // 调用失败：不再追问，交给下面的失败收尾

                val (valid, unmatched) = OutputValidators.validateChainNodes(
                    chainRaw,
                    keyToNode.keys,
                    titleToNode.keys,
                ).getOrElse { return Result.failure(it) }
                unmatchedTitles += unmatched

                // 回填：nodeKey 优先；标识抄错/没带时按骨架标题兜底，不让一个字符的
                // 抄写失误吞掉整个节点。已 COMPLETED 的共享节点跳过（先到先得）
                var filledThisPass = 0
                for (dto in valid) {
                    val node = keyToNode[dto.nodeKey]
                        ?: titleToNode[dto.title.trim()]
                        ?: continue
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
                    filledThisPass += 1
                }
                // 一个都没填上：再问一遍也是同样结果，停下别烧 token
                if (filledThisPass == 0) break
                // 还差的是「整条链」剩下的（pending），不只是本轮那一批（remaining）
                if (filledThisPass < pending.size && pass < maxPasses) {
                    onProgress(
                        GenerationProgress(
                            "第 ${index + 1} 条链还差 ${pending.size - filledThisPass} 个知识点，继续补",
                            completed,
                            total,
                        ),
                    )
                }
            }

            // 本链是否真的填满了（多轮之后仍有占位 = 没填满）
            val chainTitles = chain.nodeTitles.map { it.trim() }.toSet()
            val leftover = knowledgeRepository.getByProjectAll(projectId)
                .filter { it.batchId == batch.id }
                .filter { it.title.trim() in chainTitles }
                .filter { it.generateStatus != NodeGenerateStatus.COMPLETED.name }
            if (leftover.isNotEmpty()) {
                // 单链失败不再中止整批：后面的链照常展开，最后汇总报「N 条中 M 条失败」。
                // 原来这里直接 return，任何一条链抖一下整批就报失败，
                // 用户重试时已完成的链白跑一遍体验、失败链的资料费再交一次
                statuses[index] = KnowledgeMapChainStatus.FAILED
                knowledgeRepository.saveBatch(
                    batch.copy(
                        chainStatuses = statuses.encodeStatuses(),
                        updatedAt = clock(),
                    ),
                )
                // 本链尚未展开的节点标失败（UI 可见），重试时重新展开
                leftover.forEach {
                    knowledgeRepository.updateGenerateStatus(it.id, NodeGenerateStatus.FAILED.name)
                }
                failedChains += 1
                stepped = true
                chainFailures += chainFailureNote(index, chain, leftover.size, chainFailure)
                onProgress(GenerationProgress("第 ${index + 1} 条链展开失败，继续下一条", completed, total))
                return@forEachIndexed
            }
            statuses[index] = KnowledgeMapChainStatus.DONE
            knowledgeRepository.saveBatch(
                batch.copy(
                    chainStatuses = statuses.encodeStatuses(),
                    updatedAt = clock(),
                ),
            )
            stepped = true
            completed += 1
            onProgress(GenerationProgress("正在展开第 ${index + 1}/${chains.size} 条演进链", completed, total))
        }
        batch = batch.copy(chainStatuses = statuses.encodeStatuses(), updatedAt = clock())
        knowledgeRepository.saveBatch(batch)

        // 部分链失败：已展开的内容保留，审计不做（缺口/冲突清单等地图补齐再定稿），
        // 返回失败让 UI 明示还差哪几条；续跑只补失败链
        if (failedChains > 0) {
            onProgress(GenerationProgress("${chains.size} 条链中 $failedChains 条失败", completed, total))
            return Result.failure(
                IllegalStateException(
                    buildString {
                        append("${chains.size} 条演进链中 $failedChains 条展开失败，已完成的链不受影响；")
                        append("可点「展开下一条链」继续，失败链会自动重试。")
                        chainFailures.take(CHAIN_FAILURE_DETAIL_LIMIT).forEach { append("\n").append(it) }
                    },
                ),
            )
        }
        // 单步模式：本轮只推进一条链，审计等全部链完成后（用户再点一次）再做
        if (mode == KnowledgeMapMode.NEXT_CHAIN && statuses.any { it != KnowledgeMapChainStatus.DONE }) {
            onProgress(GenerationProgress("已展开一条演进链", completed, total))
            return Result.success(Unit)
        }

        // ===== 3. 全局审计（一次小调用，生成最终 gaps/conflicts） =====
        onProgress(GenerationProgress("正在做全局审计", completed, total))
        val allNodes = knowledgeRepository.getByProjectAll(projectId)
            .filter { it.batchId == batch.id }
        val auditResult = runCatching {
            // 审计只吃节点摘要 + 资料清单，不再带原文：原来一次塞全项目 50 条×500 字
            // （2-3 万 token）只为产出缺口/冲突清单，失败重试还按次计费
            val auditPrompt = prompts.load(PROMPT_KNOWLEDGE_MAP_AUDIT)
                .replace("{learnerProfile}", LearnerProfile.section(project))
                .replace("{topic}", project.topic)
                .replace("{goal}", LearnerProfile.goal(project))
                .replace("{nodes}", allNodes.joinToString("\n") { node ->
                    "- ${node.title}：${node.definition.take(NODE_EXCERPT)}"
                }.ifBlank { "（地图为空）" })
                .replace("{sources}", formatSourceDigest(chunks))
            router.generate(
                role = ModelRole.FAST,
                request = ModelRequest(
                    role = ModelRole.FAST,
                    system = SYSTEM_MAP_AUDIT,
                    user = auditPrompt,
                    maxTokens = TokenBudget.outputLimit(
                        system = SYSTEM_MAP_AUDIT,
                        user = auditPrompt,
                        stageCap = TokenBudget.Stage.AUDIT,
                    ),
                    jsonMode = true,
                    stageLabel = "MAP_AUDIT",
                ),
                decoder = jsonDecoder(KnowledgeMapAuditOutput.serializer()),
            )
        }
        val audit = auditResult.getOrNull()?.getOrNull()
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

        if (audit == null) {
            // 审计失败按软失败收尾：所有链已展开成功、地图内容完整，缺口/冲突清单
            // 只是锦上添花——为它报「生成失败」会让用户以为整张地图废了，重试又把
            // 2-3 万 token 的审计输入再交一遍钱。gaps 落一条说明，批次照常完成
            val reason = auditResult.exceptionOrNull()?.message ?: "模型调用失败"
            batch = batch.copy(auditFailed = true, completed = true, active = true, updatedAt = clock())
            knowledgeRepository.saveBatch(batch)
            if (!isFirstGeneration) {
                knowledgeRepository.switchActiveBatch(projectId, batch.id)
            }
            finalGaps = (finalGaps + softGaps + "全局审计未完成（$reason），以上缺口清单为骨架阶段的预览；可重新梳理补齐。").distinct()
            knowledgeRepository.replaceInsights(
                projectId = projectId,
                gaps = buildGaps(projectId, finalGaps, audit?.structuredGaps.orEmpty(), allNodes, inheritedGapStatus),
                conflicts = buildConflicts(projectId, finalConflicts, inheritedConflictStatus),
            )
            projectRepository.update(project.copy(status = ProjectStatus.MAP, updatedAt = clock()))
            onProgress(GenerationProgress("知识主干梳理完成（全局审计未完成，不影响使用）", total, total))
            return Result.success(Unit)
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
            gaps = buildGaps(projectId, finalGaps, audit?.structuredGaps.orEmpty(), allNodes, inheritedGapStatus),
            conflicts = buildConflicts(projectId, finalConflicts, inheritedConflictStatus),
        )
        projectRepository.update(project.copy(status = ProjectStatus.MAP, updatedAt = clock()))
        onProgress(GenerationProgress("知识主干梳理完成", total, total))
        return Result.success(Unit)
    }

    /**
     * 把审计产出组装成待落库的缺口清单（gaps 闭环）。
     *
     * 三件事，缺一不可：
     *
     * 1. **结构化优先**：有 `structuredGaps` 时按它带类型/建议标题落库；旧模型
     *    不输出该字段时退回纯文本，一律 `UNSPECIFIED`。两者并存而不是替换，
     *    是把 `gaps` 改成对象数组会让旧模型输出反序列化失败——整批审计会崩。
     * 2. **状态继承**：[replaceInsights] 是全量 clear + 重建，直接覆盖会把用户
     *    已经补上或已经点掉的一条打回 OPEN。「补过的又冒出来」比「没提示」更糟，
     *    所以重建时按「建议标题」继承旧的 RESOLVED / DISMISSED。
     *    **继承来源必须是调用方在骨架阶段之前拍的快照**（[inheritedByTitle]）——
     *    骨架阶段的 replaceInsights 已经把库换成了预览行，这里再读库只会读到空状态。
     * 3. **已补上的自动 RESOLVED**：建议标题能在当前地图节点标题里匹配到，
     *    说明知识点已经补进来了——审计再把它列为缺口是自相矛盾。
     */
    private fun buildGaps(
        projectId: String,
        textGaps: List<String>,
        structured: List<GapDto>,
        nodes: List<KnowledgeNode>,
        inheritedByTitle: Map<String, String>,
    ): List<KnowledgeGap> {
        val nodeTitles = nodes.map { it.title.trim() }.filter { it.isNotEmpty() }
        val nodeByKey = nodes.associate { it.nodeKey to it.id }
        val items = if (structured.isNotEmpty()) {
            structured.map { dto ->
                GapItem(
                    description = dto.description.ifBlank { dto.suggestedTitle },
                    type = GapType.parse(dto.type),
                    anchorNodeId = nodeByKey[dto.anchorNodeKey.trim()].orEmpty(),
                    suggestedTitle = dto.suggestedTitle.trim(),
                )
            }
        } else {
            textGaps.map { text -> GapItem(description = text, type = GapType.UNSPECIFIED) }
        }
        return items
            .filter { it.description.isNotBlank() }
            .distinctBy { it.description.trim() }
            .mapIndexed { index, item ->
                val status = when {
                    item.suggestedTitle.isNotBlank() && inheritedByTitle.containsKey(item.suggestedTitle) ->
                        inheritedByTitle.getValue(item.suggestedTitle)
                    item.suggestedTitle.isNotBlank() && nodeTitles.any { matchesTitle(it, item.suggestedTitle) } ->
                        GapStatus.RESOLVED.name
                    else -> GapStatus.OPEN.name
                }
                KnowledgeGap(
                    id = idFactory(),
                    projectId = projectId,
                    description = item.description,
                    order = index,
                    gapType = item.type.name,
                    status = status,
                    anchorNodeId = item.anchorNodeId,
                    suggestedTitle = item.suggestedTitle,
                )
            }
    }

    /**
     * 把冲突组装成待落库的清单（与 [buildGaps] 同一套继承逻辑）。
     *
     * 冲突没有 `suggestedTitle`，按 **topic** 继承——同一个对立观点的 topic 在两次
     * 梳理间通常是稳定的字符串，比 description 稳。继承来源同样是调用方拍的快照。
     */
    private fun buildConflicts(
        projectId: String,
        dtos: List<ConflictDto>,
        inheritedByTopic: Map<String, String>,
    ): List<KnowledgeConflict> = dtos
        .map { it.copy(topic = it.topic.trim()) }
        .filter { it.topic.isNotBlank() }
        .distinctBy { it.topic }
        .mapIndexed { index, dto ->
            KnowledgeConflict(
                id = idFactory(),
                projectId = projectId,
                topic = dto.topic,
                sources = dto.sources,
                description = dto.description,
                order = index,
                status = inheritedByTopic[dto.topic] ?: GapStatus.OPEN.name,
            )
        }

    /** 标题是否被「同一件事」匹配到：trim 后相等，或一方包含另一方（模型/用户都可能加修饰词） */
    private fun matchesTitle(existing: String, suggested: String): Boolean =
        existing == suggested || existing.contains(suggested) || suggested.contains(existing)

    private data class GapItem(
        val description: String,
        val type: GapType,
        val anchorNodeId: String = "",
        val suggestedTitle: String = "",
    )

    /**
     * 按一条缺口把漏掉的知识点补进知识主干（gaps 闭环）。
     *
     * 只补**一个**节点、只发**一次**调用：gap 说的是「这里缺一个东西」，
     * 为它重跑整张地图（几万 token、几分钟）是纯粹的浪费。
     *
     * 幂等：`status != OPEN` 直接返回成功——用户连点两下、或 Worker 被重试，
     * 都不该把同一个知识点补两遍。
     *
     * **不触发 `switchActiveBatch`**：那一步会删掉不属于新批次的节点，
     * 而这里只是往当前生效批次上追加一个节点。补完提示用户去续卷，
     * 不自动重跑审计——「补 → 又发现新缺口 → 再补」的无限递归没有任何价值。
     *
     * @return 补上的节点标题，供 UI 提示「已补上 X，点继续下一卷把它讲进去」
     */
    suspend fun generateKnowledgeMapPatch(projectId: String, gapId: String): Result<String> {
        val project = projectRepository.get(projectId)
            ?: return Result.failure(IllegalStateException("项目不存在"))
        val gap = knowledgeRepository.getGaps(projectId).firstOrNull { it.id == gapId }
            ?: return Result.failure(IllegalStateException("这条提示已经不在了，刷新一下看看"))
        if (gap.gapStatus != GapStatus.OPEN) {
            // 已处理（补过 / 用户点过「知道了」）：幂等返回，不重复烧调用
            return Result.success(gap.suggestedTitle)
        }
        if (!gap.actionable) {
            return Result.failure(
                IllegalStateException("这一条是「要多留个心眼」的提醒，不是漏掉的知识点，补不了也不该关掉"),
            )
        }
        val title = gap.suggestedTitle.trim()
        if (title.isBlank()) {
            return Result.failure(IllegalStateException("这条提示没给出要补的知识点名称，无法补充"))
        }

        val batch = knowledgeRepository.getLatestBatch(projectId)
            ?: return Result.failure(IllegalStateException("还没有知识主干，先梳理一次"))
        val nodes = knowledgeRepository.getByProjectAll(projectId).filter { it.batchId == batch.id }
        if (nodes.any { matchesTitle(it.title.trim(), title) }) {
            // 已经有同名/近名节点了（可能是用户手动加的）：直接闭环，不必再问模型
            knowledgeRepository.updateGap(gap.copy(status = GapStatus.RESOLVED.name))
            return Result.success(title)
        }

        // 新编号取当前批次最大序号 +1，避免与已有节点撞号
        val nextKey = "nk-${nodes.size + 1}"
        val modelKnowledge = project.usesModelKnowledge
        val chunks = if (modelKnowledge) {
            emptyList()
        } else {
            selectChunksForChain(
                allChunks = sourceRepository.getChunksByProject(projectId),
                chain = ChainDto(theme = title, nodeTitles = listOf(title)),
                focusTitles = listOf(title),
                pass = 1,
            )
        }
        val chunkIds = chunks.map { it.id }.toSet()
        val placeholder = KnowledgeNode(
            id = idFactory(),
            projectId = projectId,
            title = title,
            order = nodes.size,
            depth = 2,
            nodeKey = nextKey,
            batchId = batch.id,
            generateStatus = NodeGenerateStatus.PLACEHOLDER.name,
        )
        knowledgeRepository.upsertAll(listOf(placeholder))

        val prompt = prompts.load(PROMPT_KNOWLEDGE_MAP_CHAIN)
            .replace("{learnerProfile}", LearnerProfile.section(project))
            .replace("{chainTheme}", "补充：${gap.description}")
            .replace("{chainOpening}", gap.description)
            .replace("{nodeTitles}", title)
            .replace("{filledNodes}", "（这是一个补充节点，与它相邻的已有知识点见下）")
            .replace("{chunks}", chainChunkSection(modelKnowledge, chunks))
            .replace("{nodeKeyTitles}", "$nextKey：$title")
        val raw = router.generate(
            role = ModelRole.FAST,
            request = ModelRequest(
                role = ModelRole.FAST,
                system = SYSTEM_MAP_CHAIN,
                user = prompt,
                maxTokens = TokenBudget.outputLimit(
                    system = SYSTEM_MAP_CHAIN,
                    user = prompt,
                    stageCap = TokenBudget.Stage.CHAIN,
                ),
                jsonMode = true,
                stageLabel = "MAP_PATCH",
            ),
            decoder = jsonDecoder(ChainNodesOutput.serializer()),
        ).getOrElse { error ->
            // 补节点失败：占位节点留在库里（仍是 PLACEHOLDER，不会被当成已完成），
            // gap 保持 OPEN，用户可以再点一次
            return Result.failure(error)
        }

        val (valid, _) = OutputValidators.validateChainNodes(
            raw,
            setOf(nextKey),
            setOf(title),
        ).getOrElse { return Result.failure(it) }
        val dto = valid.firstOrNull { it.definition.isNotBlank() }
            ?: return Result.failure(ModelError.Invalid("模型没有给出这个知识点的内容，可以再试一次"))

        knowledgeRepository.upsert(
            placeholder.copy(
                generateStatus = NodeGenerateStatus.COMPLETED.name,
                definition = dto.definition,
                arisesFrom = dto.arisesFrom,
                leadsTo = dto.leadsTo,
                prerequisites = dto.prerequisites,
                related = dto.related,
                confusions = dto.confusions,
                examples = dto.examples,
                sourceRefs = dto.sourceRefs.filter { it in chunkIds }.distinct(),
            ),
        )
        knowledgeRepository.updateGap(gap.copy(status = GapStatus.RESOLVED.name))
        return Result.success(title)
    }

    /** 用户点「知道了」：把一条待办移出「别只信AI」 */
    suspend fun dismissGap(projectId: String, gapId: String): Result<Unit> {
        val gap = knowledgeRepository.getGaps(projectId).firstOrNull { it.id == gapId }
            ?: return Result.failure(IllegalStateException("这条提示已经不在了，刷新一下看看"))
        if (!gap.actionable) {
            return Result.failure(
                IllegalStateException("「要多留个心眼」的提醒不能关掉——它们会一直提醒你核实"),
            )
        }
        knowledgeRepository.updateGap(gap.copy(status = GapStatus.DISMISSED.name))
        return Result.success(Unit)
    }

    /**
     * 用户点「知道了」：把一条资料冲突移出「别只信AI」。
     *
     * 冲突不是缺口——没有"补上"一说，只有"我已经知道两边说法了，别反复提示"。
     * 关闭状态按 topic 在下次梳理时继承（[buildConflicts]），不会重新冒出来。
     * 幂等：重复点击只是把同一行 REPLACE 成同样的值。
     */
    suspend fun dismissConflict(projectId: String, conflictId: String): Result<Unit> {
        val conflict = knowledgeRepository.getConflicts(projectId).firstOrNull { it.id == conflictId }
            ?: return Result.failure(IllegalStateException("这条提示已经不在了，刷新一下看看"))
        knowledgeRepository.updateConflict(conflict.copy(status = GapStatus.DISMISSED.name))
        return Result.success(Unit)
    }

    /**
     * 规划第一卷讲述路线（三段式：分卷 → 大纲 → 逐章契约）。
     *
     * ## 为什么改成三段
     *
     * 旧实现是一次调用产出整卷 4-8 章、每章一份九字段契约：任何一章不合规 →
     * `ModelError.Invalid`（`retryable=false`）→ **整卷作废、不重试、已生成部分全丢**。
     * 这是「讲述路线死活生成不出来」的根因。三段式把「全或无」拆成「逐章隔离」：
     *
     * - 分卷是**纯程序**（演进链已是天然的讲述分组，再让模型输出一次映射只会打架）；
     * - 大纲每卷一次调用，只出讲什么、怎么讲，**不含契约**；
     * - 契约每章一次调用，一章失败只标这一章 FAILED，其余照常落库。
     *
     * 断点记在 [RouteBatch] 上：大纲 done 之后重试只补 `contractStatus != DONE` 的章，
     * 大纲不重建——重试的成本从「整卷」降到「一章」。
     *
     * ## 编号只在模型交互层
     *
     * 模型看到的是 `#3` 这样的编号（抄不错），落库一律换回节点 ID：
     * `nodeKey` 是批次内序号，跨批次会重复，不能当全局身份。
     */
    suspend fun generateStoryBible(
        projectId: String,
        /**
         * 逐章契约的进度上报。
         *
         * 这一步是全流程里最长的一段 Worker：大纲一次调用之后还要**逐章**生成契约，
         * 十几章就是十几轮。不上报的话，界面上只有一句「正在规划讲述路线」长时间不动，
         * 前台通知也一直停在进入时的那一条——用户既看不到进展，系统也看不到这个
         * 任务还在干活（长任务的存活靠的就是持续刷新的前台通知）。
         */
        onProgress: suspend (GenerationProgress) -> Unit = {},
    ): Result<Unit> {
        val project = projectRepository.get(projectId)
            ?: return Result.failure(IllegalStateException("项目不存在"))
        val nodes = ensureNodeKeys(knowledgeRepository.getByProject(projectId))
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
        val chains = storyChains(projectId, bible?.throughLine.orEmpty())

        // ===== 1. 分卷（纯程序） =====
        val volume = VolumePlanner.firstVolume(
            chains = chains,
            nodesByTitle = nodes.associateBy { it.title.trim() },
        )
        if (volume.nodes.isEmpty()) {
            return Result.failure(IllegalStateException("本卷没有可用知识点，无法规划讲述路线"))
        }
        if (volume.suggestedChapters <= 0) {
            return Result.failure(IllegalStateException("本卷可用知识点太少，无法规划章节"))
        }

        // ===== 2. 大纲（每卷一次调用） =====
        //
        // 断点只在「上一次契约没做完」时生效。上一轮已收尾（contractsDone）说明
        // 用户这次点的是**重新规划**，必须重来：否则复用旧大纲会连章节 ID 都不变，
        // 「重新生成」看起来什么都没发生。
        val batch = run {
            val latest = knowledgeRepository.getLatestRouteBatch(projectId)
            val resumable = latest != null &&
                latest.outlineDone &&
                !latest.failed &&
                !latest.contractsDone &&
                latest.contractStatusList().any { it == ContractStatus.FAILED }
            if (resumable) latest else null
        } ?: generateOutline(project, nodes, volume, chains).getOrElse { return Result.failure(it) }

        val outline = decodeVolumeOutline(batch.outlineJson)
            ?: return Result.failure(IllegalStateException("讲述路线大纲数据损坏，请重新规划"))

        // ===== 3. 契约（每章一次调用，逐章隔离） =====
        val plans = chapterRepository.getPlans(projectId)
            .filter { it.volumeIndex == FIRST_VOLUME }
            .sortedBy { it.order }
        if (plans.isEmpty()) {
            return Result.failure(IllegalStateException("讲述路线大纲没有落库章节，请重新规划"))
        }
        generateContracts(
            projectId = projectId,
            project = project,
            nodes = nodes,
            outline = outline,
            plans = plans,
            batch = batch,
            onProgress = onProgress,
        )

        // 路线软审计：前置顺序 / 重复引入 / 范围覆盖 / 负荷 / 类型单一化 / 链连续性（第二轮 P1-5）
        routeAuditSink(
            RouteAudit.audit(
                chapters = outline.chapters,
                nodeTitlesByPrereqs = nodes.associate { it.title.trim() to it.prerequisites },
                allNodeTitles = nodes.map { it.title.trim() }.toSet(),
                chains = chains.map { it.nodeTitles.map(String::trim) },
                dailyMinutes = project.dailyMinutes,
            ),
        )

        // 保存全局设定，同时把滚动记忆清零重新开始（问题5）
        storyBibleRepository.save(
            StoryBible(
                projectId = projectId,
                world = ContextBudget.clamp(
                    (bible?.world).orEmpty().ifBlank { "（讲述路线按知识演进编排，未设定故事舞台）" },
                    ContextBudget.WORLD_LIMIT,
                ),
                conflict = bible?.conflict.orEmpty().ifBlank { outline.volumeGoal },
                style = bible?.style.orEmpty(),
                approach = bible?.approach.orEmpty(),
                characters = bible?.characters.orEmpty(),
                throughLine = (bible?.throughLine).orEmpty().ifEmpty {
                    chains.map { "${it.theme.ifBlank { "演进链" }}：${it.nodeTitles.joinToString(" → ")}" }
                },
                runningState = "",
                coveredTitles = emptyList(),
                openThreads = emptyList(),
                currentVolumeIndex = FIRST_VOLUME,
                updatedAt = clock(),
            ),
        )
        projectRepository.update(
            project.copy(
                status = ProjectStatus.OUTLINE,
                updatedAt = clock(),
            ),
        )
        return Result.success(Unit)
    }

    /**
     * 大纲阶段：一次调用产出本卷所有章的大纲（不含契约）。
     *
     * 模型一次看到全卷，所以类型分布由它给全局答案，再由 [VolumePlanner.enforceTypes]
     * 做确定性兜底——两者都不需要为「某一章违规」重跑整卷。
     * 校验不通过时函数内重试最多 [OUTLINE_MAX_ATTEMPTS] 次，越权失败才抛给调用方。
     */
    private suspend fun generateOutline(
        project: Project,
        nodes: List<KnowledgeNode>,
        volume: VolumePlanner.DraftVolume,
        chains: List<ChainDto>,
    ): Result<RouteBatch> {
        val keyOf = { node: KnowledgeNode -> nodeKeyLabel(node) }
        val keyByTitle = NodeContext.keyByTitle(volume.nodes, keyOf)
        val suggested = volume.suggestedChapters
        val prompt = prompts.load(PROMPT_ROUTE_OUTLINE)
            .replace("{styleInstruction}", StyleInstruction.of(project.genre))
            .replace("{learnerProfile}", LearnerProfile.section(project))
            .replace("{topic}", project.topic)
            .replace("{chapterHint}", "$suggested")
            .replace("{wordHint}", "${project.wordTargetPerChapter}")
            .replace("{nodes}", NodeContext.formatL1(volume.nodes, keyOf, keyByTitle))
            .replace("{volumeGroups}", formatVolumeGroups(volume, chains, keyByTitle))

        var lastError: Throwable = IllegalStateException("讲述路线大纲生成失败")
        repeat(OUTLINE_MAX_ATTEMPTS) { attempt ->
            val raw = router.generate(
                role = ModelRole.STORY,
                request = ModelRequest(
                    role = ModelRole.STORY,
                    system = SYSTEM_ROUTE_OUTLINE,
                    user = prompt,
                    // 大纲不含契约，输出量约为旧整卷的 1/4，8K 绰绰有余
                    maxTokens = TokenBudget.outputLimit(
                        system = SYSTEM_ROUTE_OUTLINE,
                        user = prompt,
                        stageCap = TokenBudget.Stage.ROUTE_OUTLINE,
                    ),
                    jsonMode = true,
                    stageLabel = "ROUTE_OUTLINE",
                ),
                decoder = jsonDecoder(VolumeOutlineOutput.serializer()),
            ).onFailure { lastError = it }.getOrNull() ?: return@repeat

            val validKeys = volume.nodes.map { keyOf(it) }.toSet()
            val prepared = prepareOutline(raw, validKeys)
            if (prepared == null) {
                lastError = ModelError.Invalid(
                    "讲述路线大纲第 ${attempt + 1} 次尝试仍不满足要求（章节为空或引用了不存在的知识点编号）",
                )
                return@repeat
            }
            val (chapters, notes) = prepared
            val batch = persistOutline(
                projectId = project.id,
                volume = volume,
                chapters = chapters,
                keyToId = volume.nodes.associate { keyOf(it) to it.id },
                outline = raw.copy(
                    volumeTitle = raw.volumeTitle.ifBlank { "第一卷" },
                    chapters = chapters,
                ),
            )
            // 类型被程序改动过，如实告诉用户而不是假装模型原本就这么给
            notes.forEach { note ->
                routeAuditSink(
                    listOf(
                        RouteAuditIssue(
                            level = RouteAuditIssue.Level.INFO,
                            code = RouteAuditIssue.Code.TYPE_AUTO_ADJUSTED,
                            message = note,
                        ),
                    ),
                )
            }
            return Result.success(batch)
        }
        return Result.failure(lastError)
    }

    /**
     * 大纲输出的校验与类型兜底。
     *
     * 编号有效性是硬校验（引用了清单外的编号 = 模型在凭空造知识点，无从落库）；
     * 类型分布交给 [VolumePlanner.enforceTypes] 确定性修复——能程序修的绝不烧一次调用。
     * 返回 null 表示这份输出不可用。
     */
    private fun prepareOutline(
        raw: VolumeOutlineOutput,
        validKeys: Set<String>,
    ): Pair<List<ChapterPlanDto>, List<String>>? {
        val chapters = raw.chapters
            .filter { it.title.isNotBlank() }
            .mapIndexed { index, chapter ->
                if (chapter.order > 0) chapter else chapter.copy(order = index + 1)
            }
            .take(VOLUME_MAX_CHAPTERS)
        if (chapters.isEmpty()) return null
        val unknown = chapters
            .flatMap { it.nodeKeys }
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in validKeys }
            .distinct()
        if (unknown.isNotEmpty()) return null
        // 章节类型：非空非法值一律失败（与旧路径同一条硬校验）
        if (chapters.any { chapter ->
                val typeName = chapter.chapterType.trim()
                typeName.isNotEmpty() && ChapterType.entries.none { it.name == typeName.uppercase() }
            }
        ) return null
        return VolumePlanner.enforceTypes(chapters)
    }

    /**
     * 大纲落库 + 开/续 [RouteBatch]。
     *
     * 这里没有已发布章节（入口已经拦过），清理是安全的：
     * 清掉的只是上一次规划出来、还一个字都没写的章节计划。
     */
    private suspend fun persistOutline(
        projectId: String,
        volume: VolumePlanner.DraftVolume,
        chapters: List<ChapterPlanDto>,
        keyToId: Map<String, String>,
        outline: VolumeOutlineOutput,
    ): RouteBatch {
        val plans = chapters.mapIndexed { index, chapter ->
            ChapterPlan(
                id = idFactory(),
                projectId = projectId,
                order = if (chapter.order > 0) chapter.order else index + 1,
                title = chapter.title,
                plotGoal = chapter.plotGoal,
                learningGoals = chapter.learningGoals,
                nodeIds = chapter.nodeKeys.mapNotNull { keyToId[it.trim()] }.distinct(),
                wordTarget = chapter.wordTarget.coerceIn(MIN_WORDS, MAX_WORDS),
                status = ChapterStatus.QUEUED,
                derivationChain = chapter.derivationChain,
                // 开头钩子存进它自己的列。规划期与发布期不再共用一列（3B）
                openingHook = chapter.openingHook,
                coveredTitles = emptyList(),
                // 首次规划出来的都是第一卷（3C）
                volumeIndex = FIRST_VOLUME,
                volumeTitle = outline.volumeTitle.ifBlank { "第一卷" },
                volumeGoal = outline.volumeGoal,
                chapterType = chapter.chapterType,
                // 契约还没生成：留空串而不是伪造一份（ContractCodec 按 None 处理）
                contractJson = "",
                contractStatus = ContractStatus.PENDING.name,
            )
        }

        chapterRepository.clearVersionsForProject(projectId)
        // 引用跟着正文块一起清。留着的话它们会指向不存在的块，
        // 「这份资料被哪几段引用了」就会给出点不开的结果（4A）
        citationRepository.deleteByProject(projectId)
        // 核验问题同理：它们说的是某一版正文的某一块，那些块马上就不存在了（4B）
        issueRepository.deleteByProject(projectId)
        chapterRepository.clearBlocksForProject(projectId)
        chapterRepository.clearPlansForProject(projectId)
        chapterRepository.upsertPlans(plans)

        val batch = RouteBatch(
            id = idFactory(),
            projectId = projectId,
            volumeIndex = FIRST_VOLUME,
            outlineJson = json.encodeToString(VolumeOutlineOutput.serializer(), outline),
            contractStatuses = List(plans.size) { ContractStatus.PENDING }.encodeContractStatuses(),
            introducedKeysJson = json.encodeToString(
                ListSerializer(String.serializer()),
                VolumePlanner.computeIntroducedBy(chapters).map { (key, order) -> "$key:$order" },
            ),
            outlineDone = true,
            createdAt = clock(),
            updatedAt = clock(),
        )
        knowledgeRepository.saveRouteBatch(batch)
        return batch
    }

    /**
     * 逐章生成契约。
     *
     * **一章失败不连累整卷**：调用或校验失败 → 函数内重试一次 → 仍失败则该章
     * 标 FAILED、**continue 继续下一章**。失败的章由 `contractStatus` 拦住正文生成
     * （`GenerationStage.selectChaptersToGenerate`），保证「有正文必有契约」。
     *
     * 编号在这里反解回**标题**再落库：契约是给正文模型与核验模型看的，
     * 它们按标题工作；编号只是本次交互的短记号。
     */
    private suspend fun generateContracts(
        projectId: String,
        project: Project,
        nodes: List<KnowledgeNode>,
        outline: VolumeOutlineOutput,
        plans: List<ChapterPlan>,
        batch: RouteBatch,
        onProgress: suspend (GenerationProgress) -> Unit,
        /** 非空时只补这一章（单章重试入口）。其余章节连 DONE 短路都不做 */
        targetPlanId: String? = null,
    ) {
        val keyToNode = nodes.associateBy { nodeKeyLabel(it) }
        val keyByTitle = NodeContext.keyByTitle(nodes) { nodeKeyLabel(it) }
        // 「谁在哪章首次引入」由程序推导，不采信模型声明——ContractIntroducedFix 的
        // 存在恰恰证明模型声明不可信
        val introducedOrder = decodeIntroducedOrder(batch.introducedKeysJson)
        val stored = batch.contractStatusList()
        val statuses = if (stored.size == plans.size) {
            stored.toMutableList()
        } else {
            // 批次里的状态数与当前章节数对不上（大纲重规划过）：全部按待生成处理。
            // 宁可重跑一遍契约，也不能拿错位的状态去跳过某一章
            MutableList(plans.size) { ContractStatus.PENDING }
        }

        val total = 1 + plans.size
        var failedCount = 0
        plans.forEachIndexed { index, plan ->
            if (statuses[index] == ContractStatus.DONE) return@forEachIndexed
            // 单章重试：不是目标章就整个跳过（包括上面的 DONE 短路，
            // 那是因为目标章的 DONE 状态由调用方另行判断）
            if (targetPlanId != null && plan.id != targetPlanId) return@forEachIndexed
            val chapter = outline.chapters.getOrNull(index) ?: return@forEachIndexed
            val chapterKeys = chapter.nodeKeys.map { it.trim() }.filter { it.isNotEmpty() }
            onProgress(GenerationProgress("正在定第 ${plan.order} 章的因果结构", 1 + index, total))

            // 更早章节已引入过的编号：这些不能再算「本章首次引入」
            val alreadyIntroduced = introducedOrder
                .filter { (_, order) -> order < index }
                .keys
            val learnedKeys = chapterKeys.toSet() + alreadyIntroduced
            val allowed = chapterKeys.filter { it !in alreadyIntroduced }

            var contract: ChapterContractDto? = null
            var notes: List<String> = emptyList()
            // 注意：必须用带标签的 lambda。repeat 是 inline 的，裸 return 会从
            // 本函数整体返回——那样第一张契约一成功，后面所有章就永远停在 PENDING
            repeat(CONTRACT_MAX_ATTEMPTS) attempt@{
                val raw = callContract(project, chapter, chapterKeys, allowed, alreadyIntroduced, keyToNode, keyByTitle)
                    .getOrNull() ?: return@attempt
                val withKeys = raw.toContractDto()
                val fixed = ContractIntroducedFix.fix(
                    chapter = chapter,
                    contract = withKeys,
                    alreadyIntroduced = alreadyIntroduced,
                    learnedKeys = learnedKeys,
                )
                val candidate = chapter.copy(contract = fixed.contract)
                // 校验白名单 = 本章节点 ∪ 到本章为止已出现的节点。
                // 只放本章节点会误伤：APPLY / REVIEW 章按定义就要应用先前章节的知识点，
                // ContractIntroducedFix 补进 applied 的已学节点会被判成「凭空造的编号」。
                // 白名单本身仍是程序推导的（introducedOrder 来自大纲落库，不是模型声明），
                // 所以「模型凭空造知识点」照样拦得住。
                val violation = OutputValidators.contractViolation(
                    chapter = candidate,
                    knownNodeTitles = emptySet(),
                    knownNodeKeys = learnedKeys,
                )
                if (violation != null) return@attempt
                contract = fixed.contract
                notes = fixed.notes
                return@attempt
            }

            if (contract == null) {
                // 整章契约失败：只标这一章，其余章节照常
                statuses[index] = ContractStatus.FAILED
                failedCount += 1
                chapterRepository.upsertPlans(
                    listOf(plan.copy(contractStatus = ContractStatus.FAILED.name)),
                )
                knowledgeRepository.saveRouteBatch(
                    batch.copy(
                        contractStatuses = statuses.encodeContractStatuses(),
                        failedChapterCount = failedCount,
                        updatedAt = clock(),
                    ),
                )
                return@forEachIndexed
            }

            // 落库前把编号反解回标题：契约是给正文/核验模型看的，它们按标题工作
            val resolved = resolveContractKeys(contract!!, keyToNode)
            statuses[index] = ContractStatus.DONE
            chapterRepository.upsertPlans(
                listOf(
                    plan.copy(
                        chapterType = chapter.chapterType,
                        contractJson = ContractCodec.encode(resolved),
                        contractStatus = ContractStatus.DONE.name,
                    ),
                )
            )
            knowledgeRepository.saveRouteBatch(
                batch.copy(
                    contractStatuses = statuses.encodeContractStatuses(),
                    failedChapterCount = failedCount,
                    contractsDone = statuses.all { it == ContractStatus.DONE },
                    completed = statuses.all { it == ContractStatus.DONE },
                    updatedAt = clock(),
                ),
            )
            notes.forEach { note ->
                routeAuditSink(
                    listOf(
                        RouteAuditIssue(
                            level = RouteAuditIssue.Level.INFO,
                            code = RouteAuditIssue.Code.CONTRACT_AUTO_ADJUSTED,
                            chapterOrder = plan.order,
                            message = note,
                        ),
                    ),
                )
            }
        }
        onProgress(GenerationProgress("讲述路线规划完成", total, total))
    }

    /**
     * 单独重试一章的契约（三段式）。
     *
     * 存在的理由：`generateContracts` 里一章失败只标 FAILED 并继续，
     * 但那之后用户要怎么补？没有这个入口，唯一的办法是整卷重新规划——
     * 那会把已经生成好的其他章契约一起扔掉，等于把「逐章隔离」的成果又赔进去。
     *
     * 只补这一章：大纲原文从批次里取（不重新生成），
     * 成功后把 FAILED 翻成 DONE，失败则保持 FAILED 让用户能再试。
     *
     * @return 成功时返回空；章节不存在或状态不是 FAILED 时返回失败
     */
    suspend fun retryChapterContract(projectId: String, planId: String): Result<Unit> {
        val project = projectRepository.get(projectId)
            ?: return Result.failure(IllegalStateException("项目不存在"))
        val plan = chapterRepository.getPlan(planId)
            ?: return Result.failure(IllegalStateException("章节不存在"))
        if (plan.contractStatus == ContractStatus.DONE.name) {
            return Result.failure(IllegalStateException("第 ${plan.order} 章的契约已经生成好了，不用重试"))
        }

        val batch = knowledgeRepository.getLatestRouteBatch(projectId)
            ?: return Result.failure(IllegalStateException("找不到讲述路线批次，请重新规划这一卷"))
        val outline = decodeVolumeOutline(batch.outlineJson)
            ?: return Result.failure(IllegalStateException("讲述路线大纲数据损坏，请重新规划这一卷"))
        val nodes = ensureNodeKeys(knowledgeRepository.getByProject(projectId))

        // 章序按 order 反查大纲下标：plans 已按 order 排序，而 outline.chapters 同序
        val plans = chapterRepository.getPlans(projectId)
            .filter { it.volumeIndex == plan.volumeIndex }
            .sortedBy { it.order }
        val index = plans.indexOfFirst { it.id == planId }
        if (index < 0) return Result.failure(IllegalStateException("这一章不在当前卷里"))

        // 单章重试也要传整卷的 plans：契约要靠「本章在卷内的位置」推算哪些知识点
        // 是更早章节已经引入过的。只传一章，下标会变成 0，
        // 「APPLY 必须应用已学节点」这类规则就全算错了。
        generateContracts(
            projectId = projectId,
            project = project,
            nodes = nodes,
            outline = outline,
            plans = plans,
            batch = batch,
            onProgress = {},
            targetPlanId = planId,
        )

        return if (chapterRepository.getPlan(planId)?.contractStatus == ContractStatus.DONE.name) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "第 ${plan.order} 章的契约这次也没通过，可以再试一次；" +
                        "一直不通过的话重新规划这一卷会换一批章节安排。",
                ),
            )
        }
    }

    /** 单章契约的一次模型调用 */
    private suspend fun callContract(
        project: Project,
        chapter: ChapterPlanDto,
        chapterKeys: List<String>,
        allowed: List<String>,
        alreadyIntroduced: Set<String>,
        keyToNode: Map<String, KnowledgeNode>,
        keyByTitle: Map<String, String>,
    ): Result<ChapterContractOutput> {
        val chapterNodes = chapterKeys.mapNotNull { keyToNode[it] }
        val outlineText = buildString {
            appendLine("第 ${chapter.order} 章《${chapter.title}》")
            appendLine("章节类型：${chapter.chapterType}")
            if (chapter.derivationChain.isNotBlank()) {
                appendLine("推演骨架：${chapter.derivationChain}")
            }
            if (chapter.plotGoal.isNotBlank()) appendLine("本章要走完：${chapter.plotGoal}")
            if (chapter.learningGoals.isNotEmpty()) {
                appendLine("读完应想通：${chapter.learningGoals.joinToString("；")}")
            }
            if (chapter.openingHook.isNotBlank()) appendLine("开场处境：${chapter.openingHook}")
            if (chapter.closingHook.isNotBlank()) appendLine("结尾留口：${chapter.closingHook}")
            append("本章涉及的知识点：${chapterKeys.joinToString("、")}")
        }
        val prompt = prompts.load(PROMPT_CHAPTER_CONTRACT)
            .replace("{chapterOutline}", outlineText)
            .replace("{chapterType}", chapter.chapterType)
            .replace(
                "{allowedNodeKeys}",
                allowed.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "（无：本章不引入新知识点）",
            )
            .replace(
                "{alreadyIntroduced}",
                alreadyIntroduced.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "（无）",
            )
            .replace(
                "{nodeDetails}",
                NodeContext.formatL2(chapterNodes, { nodeKeyLabel(it) }, keyByTitle)
                    .ifBlank { "（本章没有关联知识点）" },
            )
        return router.generate(
            role = ModelRole.STORY,
            request = ModelRequest(
                role = ModelRole.STORY,
                system = SYSTEM_CHAPTER_CONTRACT,
                user = prompt,
                // 一份契约九个字段，输出很短；给 8K 只为不被截断
                maxTokens = TokenBudget.outputLimit(
                    system = SYSTEM_CHAPTER_CONTRACT,
                    user = prompt,
                    stageCap = TokenBudget.Stage.CHAPTER_CONTRACT,
                ),
                jsonMode = true,
                stageLabel = "CHAPTER_CONTRACT",
            ),
            decoder = jsonDecoder(ChapterContractOutput.serializer()),
        )
    }

    /** 契约里的编号换回节点标题（落库前唯一一次反解） */
    private fun resolveContractKeys(
        contract: ChapterContractDto,
        keyToNode: Map<String, KnowledgeNode>,
    ): ChapterContractDto = contract.copy(
        introducedNodeTitles = contract.introducedNodeTitles.mapNotNull { keyToNode[it.trim()]?.title }.distinct(),
        appliedNodeTitles = contract.appliedNodeTitles.mapNotNull { keyToNode[it.trim()]?.title }.distinct(),
    )

    /** 契约输出 DTO → 落库 DTO（节点字段此时还是编号） */
    private fun ChapterContractOutput.toContractDto(): ChapterContractDto = ChapterContractDto(
        entryQuestion = entryQuestion,
        priorIntuition = priorIntuition,
        obstacle = obstacle,
        knowledgeGap = knowledgeGap,
        introducedNodeTitles = introducedNodeKeys.map { it.trim() }.filter { it.isNotEmpty() },
        appliedNodeTitles = appliedNodeKeys.map { it.trim() }.filter { it.isNotEmpty() },
        requiredBeats = requiredBeats,
        resultingChange = resultingChange,
        bridgeToNext = bridgeToNext,
    )

    /**
     * 老项目补号（R2）。
     *
     * `nodeKey` 是批次内序号，迁移前的老节点为空。编号只在模型交互层使用，
     * 所以补号不碰备份格式、不碰节点 ID——只是给它们一个本次可用的短记号。
     * 已占用的序号跳过，避免同一批次内撞号。
     */
    private suspend fun ensureNodeKeys(nodes: List<KnowledgeNode>): List<KnowledgeNode> {
        val missing = nodes.filter { it.nodeKey.isBlank() }
        if (missing.isEmpty()) return nodes
        val used = nodes.map { it.nodeKey }.filter { it.isNotBlank() }.toSet()
        var next = used.size + 1
        val patched = mutableListOf<KnowledgeNode>()
        for (node in missing) {
            var key = "nk-$next"
            while (key in used) {
                next += 1
                key = "nk-$next"
            }
            val updated = node.copy(nodeKey = key)
            knowledgeRepository.upsert(updated)
            patched += updated
            next += 1
        }
        val patchedById = patched.associateBy { it.id }
        return nodes.map { patchedById[it.id] ?: it }
    }

    /** 节点在 prompt 里的编号写法。`#nk-3` 而不是裸 `nk-3`：模型更容易当成编号抄回来 */
    private fun nodeKeyLabel(node: KnowledgeNode): String = "#${node.nodeKey}"

    /** 大纲调用里「本卷分组」区块：告诉模型哪些编号属于哪条演进链 */
    private fun formatVolumeGroups(
        volume: VolumePlanner.DraftVolume,
        chains: List<ChainDto>,
        keyByTitle: Map<String, String>,
    ): String {
        if (volume.chains.isEmpty()) {
            return "（本卷 ${volume.nodes.size} 个知识点，没有链结构，请按编号顺序与前置关系自行组织）"
        }
        return volume.chains.mapIndexed { index, chain ->
            val keys = chain.nodeTitles.mapNotNull { keyByTitle[it.trim()] }
            val theme = chain.theme.ifBlank { "第 ${index + 1} 组" }
            "- $theme（${keys.joinToString("、")}）" +
                chain.opening.takeIf { it.isNotBlank() }?.let { "：${it.take(60)}" }.orEmpty()
        }.joinToString("\n")
    }

    private fun decodeVolumeOutline(jsonStr: String): VolumeOutlineOutput? = runCatching {
        json.decodeFromString(VolumeOutlineOutput.serializer(), jsonStr)
    }.getOrNull()

    /** `["nk-3:0", "nk-5:2"]` → `Map<编号, 章内序号>` */
    private fun decodeIntroducedOrder(jsonStr: String): Map<String, Int> =
        decodeStringList(jsonStr).mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val order = parts[1].toIntOrNull() ?: return@mapNotNull null
            parts[0].trim() to order
        }.toMap()

    /**
     * 路线规划要用的演进链（[ChainDto] 形态）。
     *
     * 首选批次里落库的结构（标题精确、可直接匹配节点）；批次行不在了退回解析
     * `throughLine`（骨架阶段写进去的格式是「主题：A → B → C」，按 " → " 拆回标题序列）。
     */
    private suspend fun storyChains(projectId: String, throughLine: List<String>): List<ChainDto> {
        val fromBatch = knowledgeRepository.getLatestBatch(projectId)
            ?.let { decodeChains(it.chainsJson) }
            .orEmpty()
        if (fromBatch.isNotEmpty()) return fromBatch
        return throughLine.mapNotNull { line ->
            val theme = line.substringBefore("：", "").trim()
            val titles = line.substringAfter("：", line)
                .split(" → ")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (titles.size < 2) null else ChainDto(theme = theme, nodeTitles = titles)
        }
    }

    /**
     * 一卷内的章数。一章承载一整条演进链，所以章数远少于知识点数（问题7）；
     * 上限是「一次规划的量」而不是「全书上限」——后续内容由续卷接（3C）。
     *
     * 节点数封顶交给 [VolumePlanner.chaptersFor]：这里保留 4~8 的软区间语义
     * （续卷提示词与章数提示仍在用），但**实际落库的卷**一律按节点数收过。
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
            .replace("{learnerProfile}", LearnerProfile.section(project))
            .replace("{topic}", project.topic)
            .replace("{goal}", LearnerProfile.goal(project))
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
                maxTokens = TokenBudget.outputLimit(
                    system = SYSTEM_NEXT_VOLUME,
                    user = prompt,
                    stageCap = TokenBudget.Stage.NEXT_VOLUME,
                ),
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
        // 契约没生成的章不能写正文（三段式）。正文按契约的拍子写、核验也按它逐项查，
        // 契约缺失会产出没有因果结构且无法核验的正文。
        //
        // 这里必须拦：selectChaptersToGenerate 只挡住批量生成，用户从章节列表
        // 点「重新生成」走的是这个单章入口，不拦就等于留个绕过口子。
        if (plan.contractStatus == ContractStatus.FAILED.name) {
            return Result.failure(
                IllegalStateException(
                    "第 ${plan.order} 章的因果契约没生成成功，不能写正文。" +
                        "请在讲述路线里单独重试这一章的契约。",
                ),
            )
        }
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
                maxTokens = TokenBudget.outputLimit(
                    system = SYSTEM_CHAPTER,
                    user = prompt,
                    stageCap = TokenBudget.Stage.CHAPTER,
                ),
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
        val verified = verification.getOrNull()

        // 核验「调用」失败（网络/超时/输出解析挂了）≠ 正文有问题。
        // 之前这里把整章判 NEEDS_REVIEW：正文明明已经写好，重试却从头再写一遍，
        // 新正文又可能在同一步断掉——「正文永远生成不了、一点重试就被刷掉」
        // 就是这么来的（2026-09-07 老板实测）。现在：正文照常发布可读，
        // 核验留待「更多 → 重新核验」单独补，只查不写。
        if (verified == null) {
            val reason = verification.exceptionOrNull()?.message ?: "核验失败"
            chapterRepository.publishVersionAtomically(
                version = version.copy(
                    summary = output.summary,
                    endingState = ContextBudget.clamp(
                        output.endingState.ifBlank { output.summary },
                        ContextBudget.CARRY_OVER_LIMIT,
                    ),
                    coveredTitles = output.coveredTitles.ifEmpty { nodes.map { it.title } },
                    coveredNodeIds = output.coveredTitles
                        .mapNotNull { titleToId[it.trim()] }
                        .ifEmpty { nodes.map { it.id } }
                        .distinct(),
                    unverifiedClaims = output.unverifiedClaims
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .take(MAX_UNVERIFIED_CLAIMS),
                    usedModelKnowledge = modelKnowledge,
                    verificationNote =
                        "核验没跑完（$reason）。正文先发布可读；" +
                            "「更多 → 重新核验」只补检查、不会重写正文",
                ),
                now = clock(),
            )
            attempt.published = true
            // 记忆链照常推进：下一章要靠 coveredTitles/endingState 续写，
            // 断在这里会让一次网络抖动传染成整本书写不下去
            val fallbackTitles = output.coveredTitles.ifEmpty { nodes.map { it.title } }
            val coveredIds = fallbackTitles
                .mapNotNull { titleToId[it.trim()] }
                .ifEmpty { nodes.map { it.id } }
                .distinct()
            advanceMemory(bible, output, coveredIds, allNodes.map { it.id }.toSet())
            writeBackNodeChapters(projectId, planId, blocks)
            scheduleReviews(projectId, blocks)
            refreshProjectStatus(projectId)
            // 出题跳过：还没过核验的正文先不基于它出题；补核验通过后可单独生成
            return Result.success(Unit)
        }

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

        // 正文写成了就发布（第二阶段 + 4B 的延伸）。
        //
        // 核验发现问题**不再判整章失败**：原来这里有 10 个问题就把整章扔掉——
        // 版本作废、Worker 返回失败、批量链条当场中断，一万多 token 的产出全部蒸发，
        // 而问题处理机制（定位到段、逐段重写）因为正文没了根本无从谈起。
        // 与「出题失败不影响正文」同一个道理：故事是主流程，先把能读的正文发出去，
        // 问题交给下面的自动修复和阅读器里的逐条复核。
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
                verificationNote = when {
                    recorded.issues.isEmpty() -> if (unverified.isEmpty()) {
                        ""
                    } else {
                        "有 ${unverified.size} 处内容建议你核对权威出处"
                    }
                    // 带问题发布：说明直接写核验发现了什么，章节卡与阅读器都能看到
                    else -> issueSummary(recorded)
                },
            ),
            now = clock(),
        )
        attempt.published = true

        // —— 核验问题的自动修复（一轮，带成本护栏）——
        //
        // 只修**定位得到段落**的问题（rewriteOneBlock 的能力边界）；涉及的块数超过
        // AUTO_REPAIR_MAX_BLOCKS 时放弃——那多半是核验员在挑整章的毛病，逐块修补
        // 不如留给用户复核划算。修复走既有 runRewrite：重写这几块 → 全章重新核验
        // → 干净才原子发布（旧问题标已修）。修不好时草稿作废、上面发布的版本
        // 原样保留，章节标 NEEDS_REVIEW 等用户在阅读器里逐条处理。
        var finalBlocks = blocks
        var finalVersionId = version.id
        if (recorded.issues.isNotEmpty()) {
            val repairTargets = autoRepairTargets(recorded)
            if (repairTargets != null) {
                val repaired = runRewrite(
                    projectId = projectId,
                    planId = planId,
                    plan = plan,
                    active = version,
                    oldBlocks = blocks,
                    targets = repairTargets.toSet(),
                    onPhase = onPhase,
                    onDraftCreated = {},
                    // 出题挪到函数尾部统一做：无论修复成败，题都出在最终正文上
                    regenerateQuiz = false,
                )
                if (repaired.isSuccess) {
                    // 修复发布了一个新版本，随后的写回与出题都改认新版
                    chapterRepository.getActiveVersion(planId)?.let { active ->
                        finalVersionId = active.id
                        chapterRepository.getBlocksByVersion(active.id)
                            .takeIf { it.isNotEmpty() }
                            ?.let { finalBlocks = it }
                    }
                }
            }
            if (finalVersionId == version.id) {
                // 没修（不满足条件）或没修好：正文可读、问题逐条挂在当前版本上，
                // 状态标「待复核」，由用户在阅读器里逐条处理（接受 / 只重写该段）。
                // 注意这里不能再走 failAttempt——版本已发布，作废它等于白写这一章。
                chapterRepository.updateStatus(
                    planId,
                    ChapterStatus.NEEDS_REVIEW,
                    issueSummary(recorded),
                )
            }
        }
        // 推进滚动记忆：runningState 被改写而不是追加，因此长度不随章数增长（问题5）。
        // 这三步都只依赖「正文已经发布」（带问题发布也算），与出题成败无关——
        // 否则出题失败会让下一章续写不上、知识点跳不到正文。
        advanceMemory(bible, output, coveredIds, allNodes.map { it.id }.toSet())
        writeBackNodeChapters(projectId, planId, finalBlocks)
        scheduleReviews(projectId, finalBlocks)
        refreshProjectStatus(projectId)

        // 自测题是可选产物：出题失败只记在 quizState 上，正文照样能读、下一章照样能生成
        onPhase(ChapterPhase.QUIZ)
        val quizResult = generateQuiz(projectId, plan, nodes, finalBlocks, finalVersionId)
        if (quizResult.isSuccess) {
            chapterRepository.updateQuizState(planId, QuizState.READY)
        } else {
            val message = quizResult.exceptionOrNull()?.message ?: "测验生成失败"
            chapterRepository.updateQuizState(planId, QuizState.FAILED, message)
        }
        return Result.success(Unit)
    }

    /**
     * 补核验（只查不写）。
     *
     * 配套「核验调用失败 → 正文照常发布」：核验因网络/超时/解析挂掉时正文已经
     * 发布可读，核验留待以后补，这个函数就是入口——对当前发布版本重跑核验，
     * 正文一个字不动。
     *
     * - 核验再失败：只更新章节说明并返回失败（UI 提示原因），正文不动
     * - 核验成功：问题落库 → 与 runChapter 同款的一轮自动修复；
     *   修不掉的标待复核（正文仍可读）
     */
    suspend fun reverifyChapter(
        planId: String,
        onPhase: ProgressReporter = NoOpProgressReporter,
    ): Result<Unit> {
        val plan = chapterRepository.getPlan(planId)
            ?: return Result.failure(IllegalStateException("章节不存在"))
        val projectId = plan.projectId
        val active = chapterRepository.getActiveVersion(planId)
            ?: return Result.failure(IllegalStateException("本章还没有已发布的正文"))
        val blocks = chapterRepository.getBlocksByVersion(active.id)
        if (blocks.isEmpty()) {
            return Result.failure(IllegalStateException("当前正文没有内容可核验"))
        }

        onPhase(ChapterPhase.VERIFY)
        val verification = verifyChapter(projectId, plan, blocks)
        val verified = verification.getOrNull()
        if (verified == null) {
            val reason = verification.exceptionOrNull()?.message ?: "核验失败"
            val note = "重试核验仍没跑完（$reason）。正文不受影响，可稍后再试"
            chapterRepository.updateStatus(planId, plan.status, note)
            return Result.failure(IllegalStateException(note))
        }

        val recorded = IssueRecorder.record(
            projectId = projectId,
            chapterId = planId,
            versionId = active.id,
            issues = verified.issues,
            validBlockIds = blocks.map { it.id }.toSet(),
            now = clock(),
        )
        issueRepository.replaceForVersion(active.id, recorded.issues)

        if (recorded.issues.isEmpty()) {
            // 干净了：清掉「核验没跑完」之类的说明
            chapterRepository.updateStatus(planId, ChapterStatus.PUBLISHED, "")
            return Result.success(Unit)
        }

        // 与 runChapter 相同的护栏：只修定位得到段落的问题，超过上限留给用户逐条处理
        val repairTargets = autoRepairTargets(recorded)
        if (repairTargets != null) {
            val repaired = runRewrite(
                projectId = projectId,
                planId = planId,
                plan = plan,
                active = active,
                oldBlocks = blocks,
                targets = repairTargets.toSet(),
                onPhase = onPhase,
                onDraftCreated = {},
                // 出题不在这里做：补核验只对「核验缺失/待复核」负责，题已有独立入口
                regenerateQuiz = false,
            )
            if (repaired.isSuccess) return Result.success(Unit)
        }
        // 没修/没修好：正文可读、问题挂在当前版本上，标待复核由用户在阅读器里处理
        chapterRepository.updateStatus(planId, ChapterStatus.NEEDS_REVIEW, issueSummary(recorded))
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
        /** 自动修复复用时关掉：出题由调用方（runChapter 尾部）在最终正文上统一做一次 */
        regenerateQuiz: Boolean = true,
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

        // 正文换了版本，旧版本的题问的是被改掉的文字，重新出一次。
        // 自动修复链路（runChapter 的核验问题修复）跳过这一步：它会在函数尾部
        // 对最终正文统一出一次题，这里再出就是重复扣一次题的钱。
        if (regenerateQuiz) {
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
                maxTokens = TokenBudget.outputLimit(
                    system = SYSTEM_REWRITE,
                    user = prompt,
                    stageCap = TokenBudget.Stage.REWRITE,
                ),
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
    /**
     * 自动修复的目标块（内部可见，测试直接钉决策口径）。
     *
     * 只修**定位得到段落**的问题（rewriteOneBlock 的能力边界），且涉及的块数不超过
     * [AUTO_REPAIR_MAX_BLOCKS]——超过它说明核验在挑整章的毛病，逐块修补的调用数
     * 已接近重写整章而成功率更低，交给用户复核更划算。
     * 返回 null = 不自动修（全是章级问题 / 问题面太大）。
     */
    internal fun autoRepairTargets(recorded: IssueRecorder.Recorded): List<String>? =
        recorded.affectedBlockIds
            .takeIf { it.isNotEmpty() && it.size <= AUTO_REPAIR_MAX_BLOCKS }

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
            // 精确加载：核验关心的是「正文引用的那几条片段原文说了什么」。
            // 原来按节点 sourceRefs 加载且在无引用时退化成「项目前 50 条」——
            // 发一批不相干片段既烧 token 又核不出真问题
            val chunkIds = (blocks.flatMap { it.sourceRefs } + nodes.flatMap { it.sourceRefs }).toSet()
            val chunks = if (chunkIds.isEmpty()) {
                emptyList()
            } else {
                sourceRepository.getChunksByIds(projectId, chunkIds)
            }
            // 核验也要看到片段的页码、章节、版本、法域（4A）：
            // 「这条规则只在某个法域成立」这类问题，没有元数据是核不出来的
            loadSourceMeta(projectId)
            prompts.load(PROMPT_VERIFY)
                .replace("{derivationChain}", derivation)
                .replace("{learningGoals}", plan.learningGoals.joinToString("；"))
                .replace("{chapterPlan}", planSummary)
                .replace("{chunks}", verifyChunkSection(chunks))
                .replace("{blocks}", body)
        }

        val result = router.generate(
            role = ModelRole.VERIFY,
            request = ModelRequest(
                role = ModelRole.VERIFY,
                system = SYSTEM_VERIFY,
                user = prompt,
                maxTokens = TokenBudget.outputLimit(
                    system = SYSTEM_VERIFY,
                    user = prompt,
                    stageCap = TokenBudget.Stage.VERIFY,
                ),
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
                maxTokens = TokenBudget.outputLimit(
                    system = "你是测验出题人。",
                    user = prompt,
                    stageCap = TokenBudget.Stage.QUIZ,
                ),
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
                // 短序号标识（批次内唯一）。原来是 nk-<UUID> 40 位随机串，要求模型
                // 逐字符原样回传——抄错一位节点就被静默丢弃；序号短到抄不错，
                // 就算真没带，回填还有标题兜底匹配
                nodeKey = "nk-${index + 1}",
                generateStatus = NodeGenerateStatus.PLACEHOLDER.name,
                depth = 1,
                order = index,
                batchId = batchId,
            )
        }
    }

    /**
     * 生成知识主干骨架，带一次「精简降级」重试（用户反馈：「第一步 map skeleton 总是生成失败」）。
     *
     * 骨架是整条流程的第一步，它一失败就整批中止——没有逐链展开那种「保留已完成、
     * 只补失败链」的退路。而它最常见的三种失败（输出被截断、截断后 JSON 救不回、
     * 模型把内容切成一堆单节点「链」）本质都是「一次要得太多」，减少要的量再问一次
     * 往往就成。所以这里对这三类失败降级重试一次：更小的输出上限 + 一段要求精简、
     * 强调「每条链至少 2 个节点」的追加指令。
     *
     * 配置类错误（没配模型、Key 无效、模型名写错、鉴权失败）降级也没用，直接如实报错，
     * 不白烧第二次调用。
     *
     * 返回的骨架里，被丢弃的单节点链会作为一条软提示写进 gaps（A2.4），
     * 而不是让整批失败——「骨架漏了几条链」远好过「骨架生成失败、什么都没有了」。
     */
    private suspend fun generateSkeleton(project: Project): Result<KnowledgeMapSkeletonOutput> {
        val basePrompt = prompts.load(PROMPT_KNOWLEDGE_MAP_SKELETON)
            .replace("{learnerProfile}", LearnerProfile.section(project))
            .replace("{topic}", project.topic)
            .replace("{depth}", project.depth)
            .replace("{goal}", LearnerProfile.goal(project))
            .replace("{baseline}", project.baseline)

        val firstRaw = requestSkeletonOnce(basePrompt, TokenBudget.Stage.SKELETON, "MAP_SKELETON")
        val firstValidated = firstRaw.mapCatching { OutputValidators.validateSkeleton(it).getOrThrow() }
        if (firstValidated.isSuccess) {
            return Result.success(
                appendSkeletonNote(firstValidated.getOrThrow(), skeletonDroppedChainNote(firstRaw.getOrNull())),
            )
        }
        // 只有「输出太长 / 格式不完整 / 链太碎」这类失败值得降级重试；其余如实报错
        if (!isSkeletonShrinkWorthRetry(firstValidated.exceptionOrNull())) return firstValidated

        val fallbackRaw = requestSkeletonOnce(
            skeletonFallbackPrompt(basePrompt),
            TokenBudget.Stage.SKELETON_FALLBACK,
            "MAP_SKELETON#fallback",
        )
        val fallbackValidated = fallbackRaw.mapCatching { OutputValidators.validateSkeleton(it).getOrThrow() }
        return if (fallbackValidated.isSuccess) {
            Result.success(
                appendSkeletonNote(fallbackValidated.getOrThrow(), skeletonDroppedChainNote(fallbackRaw.getOrNull())),
            )
        } else {
            // 降级仍失败：报第一次的错误更有信息量（两次多半同一个根因，第一次的文案更贴近用户操作）
            firstValidated
        }
    }

    /** 骨架的一次模型调用：只负责请求 + 解码，校验与降级决策在 [generateSkeleton] */
    private suspend fun requestSkeletonOnce(
        prompt: String,
        stageCap: Int,
        stageLabel: String,
    ): Result<KnowledgeMapSkeletonOutput> = router.generate(
        role = ModelRole.FAST,
        request = ModelRequest(
            role = ModelRole.FAST,
            system = SYSTEM_MAP_SKELETON,
            user = prompt,
            maxTokens = TokenBudget.outputLimit(
                system = SYSTEM_MAP_SKELETON,
                user = prompt,
                stageCap = stageCap,
            ),
            jsonMode = true,
            stageLabel = stageLabel,
        ),
        decoder = jsonDecoder(KnowledgeMapSkeletonOutput.serializer()),
    )

    /** 哪些骨架失败值得「少要点、再问一次」——见 [generateSkeleton] 的说明 */
    private fun isSkeletonShrinkWorthRetry(error: Throwable?): Boolean = when (error) {
        is ModelError.Truncated, is ModelError.Decode, is ModelError.Invalid -> true
        else -> false
    }

    /** 降级重试的追加指令：覆盖原 prompt 的「宁多勿漏」，改为「宁少勿断」 */
    private fun skeletonFallbackPrompt(base: String): String = base + "\n\n" +
        "## 重要：上一次输出过长或格式不完整，这次务必精简并保证 JSON 完整闭合\n" +
        "1. 每条演进链**至少 2 个节点**（只有 1 个节点的不算链，会被程序丢弃）。\n" +
        "2. 节点总量宁少勿多：入门 10-14 个、考试 16-22 个、专业 20-28 个即可，只列最核心的主干，不要追求穷尽。\n" +
        "3. 标题尽量短（每个不超过 12 字），theme 与 opening 各一句话。\n" +
        "4. 宁可少列几个节点，也绝不要输出到一半断掉。"

    /** 被 validateSkeleton 丢弃的单节点链，转成一条给用户的软提示（A2.4） */
    private fun skeletonDroppedChainNote(raw: KnowledgeMapSkeletonOutput?): String? {
        if (raw == null) return null
        val dropped = raw.chains
            .map { chain -> chain.copy(nodeTitles = chain.nodeTitles.map { it.trim() }.filter { it.isNotBlank() }) }
            .filter { it.nodeTitles.size < 2 }
        if (dropped.isEmpty()) return null
        val titles = dropped.flatMap { it.nodeTitles }.distinct().take(5)
        return "骨架里有 ${dropped.size} 条链只有 1 个节点，不构成演进链，已忽略" +
            (if (titles.isNotEmpty()) "（涉及：${titles.joinToString("、")}）" else "") +
            "。若这些是重要知识点，重新梳理时会被并入合适的链。"
    }

    private fun appendSkeletonNote(
        skeleton: KnowledgeMapSkeletonOutput,
        note: String?,
    ): KnowledgeMapSkeletonOutput = if (note.isNullOrBlank()) {
        skeleton
    } else {
        skeleton.copy(gaps = skeleton.gaps + note)
    }

    /**
     * 一条链失败时给用户看的原因（批次化生成）。
     *
     * 分两种：模型调用本身报错（网络/限流/解码），照抄它的消息；调用成功但节点
     * 没填满——这几乎总是输出被 max_tokens 截断，而截断的下一步动作（调大最大
     * Token）和网络抖动完全不同，必须说清楚，不能只报一句「失败」。
     */
    private fun chainFailureNote(
        index: Int,
        chain: ChainDto,
        leftover: Int,
        error: Throwable?,
    ): String {
        val head = "· 第 ${index + 1} 条链（${chain.theme.ifBlank { "未命名" }}）"
        val reason = error?.message?.trim()?.takeIf { it.isNotBlank() }
        return if (reason != null) {
            "$head：$reason"
        } else {
            "$head：连续 ${chainMaxPasses(chain.nodeTitles.size)} 轮仍有 $leftover 个知识点没写出来，" +
                "多半是模型这几轮都没按清单回传标识。可以再点一次继续补，" +
                "或把「快速模型」换成指令跟随更稳的普通对话模型。"
        }
    }

    /**
     * 逐链资料检索（批次化生成）。
     *
     * 链主题 + 节点标题先按整词匹配，再按中文二元组子词匹配（完整词权重 5、
     * 子词权重 1），命中总分排序取前若干条。
     *
     * 原来只用整词 contains：中文标题（如「要约的构成要件」）几乎不会逐字
     * 出现在资料原文里，命中率趋近于零，每条链都退化成同一批「前 20 条」——
     * 与链无关的片段既烧 token 又干扰输出质量。
     *
     * [focusTitles] 非空时**只按这几个节点检索**（续轮/重试用）。用户的意思是对的：
     * 重试时该发的是「还差的那几个节点需要的证据」，不是整条链的全部资料。
     *
     * [pass] 控制本轮片段上限：首轮 CHAIN_CHUNK_LIMIT 条，之后逐轮递减
     * （20/2=10、20/3≈6…，下限 [MIN_CHAIN_CHUNKS]）。一条长链要分好几轮问完，
     * 第二轮起模型已经在写后半段的节点，需要的证据面更窄——固定 20 条意味着
     * 同一批片段被反复计费 5 遍，最坏一条链白烧 5 万字符。
     *
     * [excludeIds] 是本链已完成节点已经引用过的片段：引用过的证据不必再随
     * 下一轮重发，模型在 [filledNodes] 区块里已经见过它们的内容。
     *
     * 一条都命中不到时返回空列表，由调用方明确告诉模型「没有相关证据」——
     * 原来这里静默退化成「项目前 20 个片段」，模型于是拿着一批不相干的片段
     * 硬凑引用，比没有资料更糟。
     */
    private fun selectChunksForChain(
        allChunks: List<SourceChunk>,
        chain: ChainDto,
        focusTitles: List<String> = emptyList(),
        pass: Int = 1,
        excludeIds: Set<String> = emptySet(),
    ): List<SourceChunk> {
        val keywords = buildList {
            if (focusTitles.isNotEmpty()) {
                addAll(focusTitles.map { it.trim() }.filter { it.length >= 2 })
            } else {
                addAll(chain.nodeTitles.map { it.trim() }.filter { it.length >= 2 })
            }
            chain.theme.trim().takeIf { it.length >= 2 }?.let { add(it) }
            chain.opening.trim().takeIf { it.length >= 2 }?.let { add(it) }
        }.distinct()
        if (keywords.isEmpty()) return emptyList()
        val cap = (CHAIN_CHUNK_LIMIT / pass.coerceAtLeast(1)).coerceAtLeast(MIN_CHAIN_CHUNKS)
        val subwords = keywords
            .flatMap { kw -> if (kw.length <= 2) listOf(kw) else kw.windowed(2) }
            .distinct()
        return allChunks.map { chunk ->
            var score = 0
            for (kw in keywords) if (chunk.text.contains(kw)) score += 5
            for (sw in subwords) if (chunk.text.contains(sw)) score += 1
            chunk to score
        }
            .filter { it.second > 0 && it.first.id !in excludeIds }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(cap)
    }

    /**
     * 单链 prompt 的资料区块。
     *
     * 命中不到片段时必须**明说**「这一批节点没有找到相关资料」。原来检索不到会静默
     * 退化成「项目前 20 个片段」，模型于是拿着一批不相干的片段硬凑 sourceRefs——
     * 那些引用点开来和内容对不上，比没有引用更伤可信度。
     */
    private fun chainChunkSection(modelKnowledge: Boolean, chunks: List<SourceChunk>): String = when {
        modelKnowledge -> "本项目没有用户资料（AI 自备知识模式）：所有节点的 sourceRefs 一律留空数组，不要编造片段 ID。"
        chunks.isEmpty() ->
            "没有检索到与本批节点相关的资料片段。请把 sourceRefs 全部留空数组，" +
                "**不要**编造片段 ID，也不要引用没在这里出现过的 ID。"
        else -> formatChunks(chunks)
    }

    /**
     * 审计用的资料摘要：只给清单，不给原文。
     *
     * 审计产出的是缺口/冲突「清单」，判断依据主要是节点内容本身；资料侧只需要
     * 知道有哪些资料、各覆盖多少片段。带原文的一次审计 2-3 万 token，
     * 失败重试按次计费，性价比远不如这份几百 token 的清单。
     */
    private fun formatSourceDigest(chunks: List<SourceChunk>): String {
        if (chunks.isEmpty()) return "（AI 自备知识模式，没有用户资料）"
        val bySource = chunks.groupBy { it.sourceId }
        val lines = bySource.entries.mapIndexed { index, (sourceId, list) ->
            val title = sourceMetaCache[sourceId]?.title?.takeIf { it.isNotBlank() }
                ?: "资料 ${index + 1}"
            "- $title（${list.size} 个片段）"
        }
        return "共 ${bySource.size} 份资料、${chunks.size} 个片段：\n" + lines.joinToString("\n")
    }

    /**
     * 核验 prompt 的资料区块。
     *
     * 三态必须分开说：无资料模式（没有可核对的对象）、有资料但这一章没有
     * 可追溯的引用（核验重点变成「有没有编造出处」）、有真实片段（逐条核对）。
     * 原来「有资料但空命中」会掉进 [formatChunks] 的空列表分支，被说成
     * 「AI 自备知识模式」——对模型的误导直接体现在核验结论上。
     */
    private fun verifyChunkSection(chunks: List<SourceChunk>): String = when {
        chunks.isEmpty() ->
            "本项目有资料，但本章正文没有可追溯的片段引用。请重点核对：各段落是否存在编造出处、" +
                "把常识说成条文、引用对不上内容的问题；unverifiedClaims 相应从宽给出。"
        else -> formatChunks(chunks, maxChunks = VERIFY_CHUNK_LIMIT)
    }

    private fun decodeChains(jsonStr: String): List<ChainDto> = runCatching {
        if (jsonStr.isBlank() || jsonStr == "[]") emptyList()
        else json.decodeFromString(ListSerializer(ChainDto.serializer()), jsonStr)
    }.getOrDefault(emptyList())

    private fun decodeStringList(jsonStr: String): List<String> = runCatching {
        if (jsonStr.isBlank() || jsonStr == "[]") emptyList()
        else json.decodeFromString(ListSerializer(String.serializer()), jsonStr)
    }.getOrDefault(emptyList())

    private fun decodeConflicts(jsonStr: String): List<ConflictDto> = runCatching {
        if (jsonStr.isBlank() || jsonStr == "[]") emptyList()
        else json.decodeFromString(ListSerializer(ConflictDto.serializer()), jsonStr)
    }.getOrDefault(emptyList())

    /**
     * 依据节点 sourceRefs 中保存的片段 ID 精确加载资料片段（B1）。
     *
     * 原来「没有可用引用时退回项目的前若干片段」：节点没有 sourceRefs 时
     * 发过去的是一批与内容无关的片段，模型要么硬凑引用、要么被干扰——
     * 都比「明说没有相关证据」更糟。退回分支已删：空列表由调用方
     * 明确告知模型「没有检索到相关资料，sourceRefs 留空，不要编造」。
     */
    private suspend fun loadChunksForNodes(
        projectId: String,
        nodes: List<KnowledgeNode>,
    ): List<SourceChunk> {
        val chunkIds = nodes.flatMap { it.sourceRefs }.toSet()
        if (chunkIds.isEmpty()) return emptyList()
        return sourceRepository.getChunksByIds(projectId, chunkIds)
    }

    private fun formatChunks(chunks: List<SourceChunk>, maxChunks: Int = MAX_CHUNKS): String {
        if (chunks.isEmpty()) return "（本项目采用 AI 自备知识模式，没有用户资料）"
        return chunks.take(maxChunks).joinToString("\n\n") { chunk ->
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

    /**
     * 路线软审计要用的演进链（每条链一个标题序列）。
     *
     * 首选批次里落库的 [ChainDto]——那是骨架阶段的原始结构，标题精确、可直接匹配节点。
     * 批次行不在了（老项目、或批次已被清理）时退回解析 `throughLine`：
     * 骨架阶段往那里写的格式是「主题：A → B → C」，按 " → " 拆回标题序列。
     *
     * 拿不到链就返回空列表——链连续性这一项跳过，其余审计项照常，不伪造链结构。
     */
    private suspend fun auditChains(projectId: String, throughLine: List<String>): List<List<String>> {
        val fromBatch = knowledgeRepository.getLatestBatch(projectId)
            ?.let { decodeChains(it.chainsJson) }
            ?.map { chain -> chain.nodeTitles.map { it.trim() }.filter { it.isNotEmpty() } }
            ?.filter { it.size >= 2 }
            .orEmpty()
        if (fromBatch.isNotEmpty()) return fromBatch
        return throughLine.mapNotNull { line ->
            val titles = line.substringAfter("：", line)
                .split(" → ")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            titles.takeIf { it.size >= 2 }
        }
    }

    /** 有资料时给出片段区块；无资料/无命中时明确告知，避免模型伪造引用（问题3） */
    private fun sourceSection(modelKnowledge: Boolean, chunks: List<SourceChunk>): String = when {
        modelKnowledge -> {
            """
            资料：本项目没有用户资料，知识由你自己提供。
            因此：所有 block 的 sourceRefs 一律留空数组；不要编造条文编号、判例名称、统计数字、人名年份。
            规则内容本身可以讲清楚，但不要伪造出处。
            """.trimIndent()
        }
        // 「编一个 ID 会被记成未确认」这句话必须写明（4A）。
        // 不写的话模型没有任何理由不去凑一个看起来像的 ID——它以前那么做的时候，
        // 那条引用被静默丢掉，正文里那句话就成了没有出处的断言。
        else -> buildString {
            appendLine("资料片段。引用时把**上面出现过的**片段ID原样填进对应 block 的 sourceRefs：")
            appendLine()
            appendLine(
                if (chunks.isEmpty()) {
                    "没有检索到与本批知识点相关的资料片段。请把 sourceRefs 全部留空数组，" +
                        "**不要**编造片段 ID，也不要引用没在这里出现过的 ID。"
                } else {
                    formatChunks(chunks)
                },
            )
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
        /**
         * 核验问题自动修复的成本护栏：涉及的正文块数超过这个数就不再逐块修。
         * 一章通常 20~30 块，超过 6 块有问题多半是核验员在挑整章的毛病，
         * 逐块修补的调用数（块数 + 1 次全章核验）已经接近重写整章，
         * 而成功率远不如整章重来——这种情况留给用户复核更划算。
         */
        const val AUTO_REPAIR_MAX_BLOCKS = 6

        /** 生成中的状态。取消收尾只在章节仍停在这些状态时才动它。 */
        val IN_FLIGHT_STATUSES = listOf(
            ChapterStatus.RUNNING,
            ChapterStatus.VERIFYING,
            ChapterStatus.DRAFT,
        )

        // 批次化生成的三阶段 prompt（骨架 → 逐链 → 审计）。
        // 旧的一次性地图 prompt（knowledge_map / knowledge_map_model）已随批次化改造废弃。
        const val PROMPT_KNOWLEDGE_MAP_SKELETON = "knowledge_map_skeleton"
        const val PROMPT_KNOWLEDGE_MAP_CHAIN = "knowledge_map_chain"
        const val PROMPT_KNOWLEDGE_MAP_AUDIT = "knowledge_map_audit"
        const val PROMPT_STORY_BIBLE = "story_bible"
        // 三段式讲述路线：大纲（每卷一次） / 契约（每章一次）
        const val PROMPT_ROUTE_OUTLINE = "route_outline"
        const val PROMPT_CHAPTER_CONTRACT = "chapter_contract"
        const val PROMPT_NEXT_VOLUME = "next_volume"
        const val PROMPT_CHAPTER = "chapter"
        const val PROMPT_VERIFY = "verify"
        const val PROMPT_VERIFY_MODEL = "verify_model"
        const val PROMPT_QUIZ = "quiz"
        const val PROMPT_REWRITE_BLOCK = "rewrite_block"

        const val SYSTEM_MAP_SKELETON = "你在规划一门学科知识主干的骨架：只列演进链与节点清单，不写节点内容。"
        const val SYSTEM_MAP_CHAIN = "你在展开知识主干里的一条演进链：把链上每个知识点写完整，不新增节点、不改标题、不动别的链已写好的内容。"
        const val SYSTEM_MAP_AUDIT = "你是知识地图的全局审计员：通读全部节点，给出最终的「知识缺口」与「资料冲突」清单。"
        const val SYSTEM_BIBLE = "你在设计一条讲述路线，让学科沿着它自己长出来。"
        const val SYSTEM_ROUTE_OUTLINE =
            "你在设计一卷讲述路线的大纲：定这一卷讲哪几章、每章讲哪些知识点、" +
                "每章属于什么类型。只出大纲，不出因果契约。"
        const val SYSTEM_CHAPTER_CONTRACT =
            "你在给讲述路线里的某一章定因果契约。大纲已经定好讲什么，你只负责把" +
                "这一章的因果结构写清楚，不要改动大纲里已经定下的东西。"
        const val SYSTEM_NEXT_VOLUME =
            "你在给一条已经开讲的讲述路线接下一卷。已经写出来的部分不能改，只能往后接。"
        const val SYSTEM_CHAPTER =
            "你是小说家，也是这门学科的讲述者。第一原则：一切通过具体的人、具体的场景、" +
                "可感的细节展开——推演藏在人物的遭遇里，而不是旁白里。人物要有名有姓，" +
                "场景要落地，关键处让人物开口或行动。读者是为故事往下翻的，读完却发现学会了一门学科：" +
                "制度自己出现，而不是被告知名词。"
        const val SYSTEM_VERIFY = "你是核验员。既查知识准确性，也查推演有没有跳步。"
        const val SYSTEM_REWRITE =
            "你在修一整章里的一个段落。只解决指出的问题，其余保持原样，改完要能无缝接回原位。"

        const val MIN_QUIZ_QUESTIONS = 3
        const val MAX_QUIZ_QUESTIONS = 5
        const val FIRST_REVIEW_DELAY_MILLIS = 86_400_000L

        /** 第一卷的索引。0 而不是 1，这样旧数据靠默认值就落在第一卷（3C） */
        const val FIRST_VOLUME = 0

        /**
         * 大纲 / 契约的函数内重试次数（三段式）。
         *
         * 在**函数内**重试而不是扩张 `isRetryableFailure` 语义：`ModelError.Invalid`
         * 是 `retryable=false`，那是给「输出结构不对、重发也没用」的场景准备的；
         * 而这里模型只是随机地漏填一个字段，重发一次大概率就好了。
         * 改全局重试语义会把「真的坏了」的请求也拉进来无限重发。
         */
        const val OUTLINE_MAX_ATTEMPTS = 3

        /** 契约重试比大纲少一次：单章契约很短，一次不成再试一次即可，多了只是拖慢整卷 */
        const val CONTRACT_MAX_ATTEMPTS = 2

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

        /** 续轮递减后的片段数下限：再少就不够支撑后半段节点的证据了 */
        const val MIN_CHAIN_CHUNKS = 6

        /** 核验 prompt 最多携带的片段数：核验关心的是正文实际引用的那几条 */
        const val VERIFY_CHUNK_LIMIT = 20

        /**
         * 一条链最多追问几轮（批次化生成）。
         *
         * 一轮只问 [CHAIN_NODES_PER_PASS] 个节点，所以轮数至少要够把整条链问完：
         * 节点数 ÷ 每轮个数，再加 2 轮容错（某轮模型少给几个、或被截断只救回一半）。
         * 上限 [CHAIN_MAX_PASSES_CAP] 兜住「模型怎么都填不出来」的情况，不无限烧钱。
         */
        fun chainMaxPasses(nodeCount: Int): Int =
            ((nodeCount + CHAIN_NODES_PER_PASS - 1) / CHAIN_NODES_PER_PASS + 2)
                .coerceIn(3, CHAIN_MAX_PASSES_CAP)

        /** 追问轮数的硬上限 */
        const val CHAIN_MAX_PASSES_CAP = 8

        /**
         * 一轮最多问几个节点（批次化生成）。
         *
         * 骨架对长链不设上限（20+ 节点常见），一次性要求全部写完，输出必然
         * 超过任何合理的长度上限——而超长那一轮是整轮白费。6 个节点 ×
         * 每节点各字段的字数上限 ≈ 2000 token 输出，稳稳落在安全区。
         */
        const val CHAIN_NODES_PER_PASS = 6

        /** 失败原因最多列几条，避免整屏错误信息 */
        const val CHAIN_FAILURE_DETAIL_LIMIT = 3

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
