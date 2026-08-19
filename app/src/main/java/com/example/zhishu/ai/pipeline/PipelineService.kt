package com.example.zhishu.ai.pipeline

import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.PromptProvider
import com.example.zhishu.ai.jsonDecoder
import com.example.zhishu.ai.outputs.ChapterOutput
import com.example.zhishu.ai.outputs.KnowledgeMapOutput
import com.example.zhishu.ai.outputs.OutputValidators
import com.example.zhishu.ai.outputs.QuizSetOutput
import com.example.zhishu.ai.outputs.StoryBibleOutput
import com.example.zhishu.ai.outputs.VerificationOutput
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.ReviewStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.StoryBible
import com.example.zhishu.domain.repository.ChapterRepository
import com.example.zhishu.domain.repository.KnowledgeRepository
import com.example.zhishu.domain.repository.ProjectRepository
import com.example.zhishu.domain.repository.QuizRepository
import com.example.zhishu.domain.repository.ReviewRepository
import com.example.zhishu.domain.repository.SourceRepository
import com.example.zhishu.domain.repository.StoryBibleRepository
import java.util.UUID

class PipelineService(
    private val router: ModelRouter,
    private val prompts: PromptProvider,
    private val projectRepository: ProjectRepository,
    private val sourceRepository: SourceRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val chapterRepository: ChapterRepository,
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
) {

    suspend fun generateKnowledgeMap(projectId: String): Result<Unit> {
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

        val template = prompts.load(
            if (modelKnowledge) PROMPT_KNOWLEDGE_MAP_MODEL else PROMPT_KNOWLEDGE_MAP,
        )
        val prompt = template
            .replace("{topic}", project.topic)
            .replace("{depth}", project.depth)
            .replace("{goal}", project.title)
            .replace("{baseline}", project.baseline)
            .replace("{chunks}", formatChunks(chunks))

        val raw = router.generate(
            role = ModelRole.FAST,
            request = ModelRequest(
                role = ModelRole.FAST,
                system = if (modelKnowledge) SYSTEM_MAP_MODEL else SYSTEM_MAP,
                user = prompt,
                jsonMode = true,
            ),
            decoder = jsonDecoder(KnowledgeMapOutput.serializer()),
        ).getOrElse { return Result.failure(it) }

        val output = OutputValidators.validateKnowledgeMap(raw)
            .getOrElse { return Result.failure(it) }

        val validChunkIds = chunks.map { it.id }.toSet()
        val nodes = buildKnowledgeNodes(projectId, output, validChunkIds)
        knowledgeRepository.clearForProject(projectId)
        knowledgeRepository.upsertAll(nodes)
        // 资料缺口与冲突落库，供 UI 展示（B12）。无资料模式下这里装的是
        // 「有地区/版本差异、需要你自己核对」的提醒，作用同样重要。
        knowledgeRepository.replaceInsights(
            projectId = projectId,
            gaps = output.gaps.filter { it.isNotBlank() }.mapIndexed { index, text ->
                KnowledgeGap(id = idFactory(), projectId = projectId, description = text, order = index)
            },
            conflicts = output.conflicts.mapIndexed { index, dto ->
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
        // 演进链暂存进故事总纲行，章节规划阶段直接拿来当章节骨架（问题7）
        storyBibleRepository.save(
            (storyBibleRepository.get(projectId) ?: StoryBible(projectId = projectId)).copy(
                throughLine = output.chains.map { chain ->
                    "${chain.theme.ifBlank { "演进链" }}：${chain.nodeTitles.joinToString(" → ")}"
                },
                updatedAt = clock(),
            ),
        )
        projectRepository.update(project.copy(status = ProjectStatus.MAP, updatedAt = clock()))
        return Result.success(Unit)
    }

    suspend fun generateStoryBible(projectId: String): Result<Unit> {
        val project = projectRepository.get(projectId)
            ?: return Result.failure(IllegalStateException("项目不存在"))
        val nodes = knowledgeRepository.getByProject(projectId)
        if (nodes.isEmpty()) return Result.failure(IllegalStateException("知识地图为空"))

        val existing = storyBibleRepository.get(projectId)
        // 章数按知识点规模推荐：一章承载一整条演进链，宁少而厚（问题7）
        val suggested = suggestChapterCount(nodes.size)

        val prompt = prompts.load(PROMPT_STORY_BIBLE)
            .replace("{styleInstruction}", StyleInstruction.of(project.genre))
            .replace("{topic}", project.topic)
            .replace("{goal}", project.title)
            .replace("{baseline}", project.baseline)
            .replace("{chapterHint}", "$suggested 章左右（可在 ${suggested - 1}-${suggested + 2} 之间调整）")
            .replace("{wordHint}", "${project.wordTargetPerChapter}")
            .replace("{nodes}", formatNodesWithDerivation(nodes))
            .replace("{chains}", formatChains(existing?.throughLine.orEmpty()))

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
        val output = OutputValidators.validateStoryBible(raw, titleToId.keys)
            .getOrElse { return Result.failure(it) }

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
                // 开头/结尾钩子存进 carryOver 的规划位，正文阶段读取后再被实际处境覆盖
                carryOver = chapter.openingHook,
                coveredTitles = emptyList(),
            )
        }

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

    /** 一章承载一整条演进链，所以章数远少于知识点数（问题7） */
    internal fun suggestChapterCount(nodeCount: Int): Int =
        (nodeCount / NODES_PER_CHAPTER).coerceIn(MIN_CHAPTERS, MAX_CHAPTERS)

    suspend fun generateChapter(
        projectId: String,
        planId: String,
        /** 子步骤进度上报（问题2）。默认不上报，保持既有调用方不变。 */
        onPhase: ProgressReporter = NoOpProgressReporter,
    ): Result<Unit> {
        val plan = chapterRepository.getPlan(planId)
            ?: return Result.failure(IllegalStateException("章节不存在"))
        val project = projectRepository.get(projectId)
            ?: return Result.failure(IllegalStateException("项目不存在"))

        // 进入生成态：项目 GENERATING、章节 RUNNING（B17）
        if (project.status != ProjectStatus.GENERATING) {
            projectRepository.update(project.copy(status = ProjectStatus.GENERATING, updatedAt = clock()))
        }
        chapterRepository.updateStatus(planId, ChapterStatus.RUNNING)
        onPhase(ChapterPhase.BODY)

        val modelKnowledge = project.usesModelKnowledge
        val allNodes = knowledgeRepository.getByProject(projectId)
        val nodes = plan.nodeIds.mapNotNull { id -> allNodes.firstOrNull { it.id == id } }
        val chunks = if (modelKnowledge) emptyList() else loadChunksForNodes(projectId, nodes)

        val bible = storyBibleRepository.get(projectId) ?: StoryBible(projectId = projectId)
        val plans = chapterRepository.getPlans(projectId)
        val previous = plans.firstOrNull { it.order == plan.order - 1 }
        val next = plans.firstOrNull { it.order == plan.order + 1 }

        // 上下文只由固定预算的几段构成，与已生成章数无关（问题5）
        val prompt = prompts.load(PROMPT_CHAPTER)
            .replace("{derivationChain}", ContextBudget.orNone(plan.derivationChain.ifBlank { plan.plotGoal }))
            .replace("{closingHook}", ContextBudget.orNone(next?.carryOver.orEmpty()))
            .replace("{world}", ContextBudget.orNone(bible.world))
            .replace("{style}", ContextBudget.orNone(bible.style.ifBlank { project.effectiveGenre }))
            .replace("{characters}", ContextBudget.formatCharacters(bible.characters))
            .replace("{throughLine}", ContextBudget.formatThroughLine(bible.throughLine))
            .replace("{runningState}", ContextBudget.orNone(bible.runningState))
            .replace("{carryOver}", ContextBudget.carryOverOf(previous))
            .replace("{coveredTitles}", ContextBudget.formatCovered(bible.coveredTitles))
            .replace("{openThreads}", ContextBudget.formatList(bible.openThreads, ContextBudget.OPEN_THREAD_LIMIT))
            .replace("{chapterTitle}", plan.title)
            .replace("{plotGoal}", plan.plotGoal)
            .replace("{learningGoals}", plan.learningGoals.joinToString("；"))
            .replace("{openingHook}", ContextBudget.orNone(plan.carryOver))
            .replace("{nodes}", formatNodesWithDerivation(nodes))
            .replace("{sourceSection}", sourceSection(modelKnowledge, chunks))
            .replace("{wordTarget}", plan.wordTarget.toString())
            .replace("{runningBudget}", ContextBudget.RUNNING_STATE_LIMIT.toString())
            .replace("{unverifiedSection}", unverifiedSection(modelKnowledge))

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
            chapterRepository.updateStatus(planId, ChapterStatus.FAILED, it.message ?: "生成失败")
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
        ).getOrElse {
            chapterRepository.updateStatus(planId, ChapterStatus.FAILED, it.message ?: "章节内容不合要求")
            return Result.failure(it)
        }

        val blocks = output.blocks.mapIndexed { index, block ->
            ChapterBlock(
                id = idFactory(),
                chapterId = planId,
                projectId = projectId,
                order = index,
                text = block.text,
                blockType = block.type,
                knowledgeNodeIds = block.knowledgeNodeTitles.mapNotNull { titleToId[it.trim()] }.distinct(),
                sourceRefs = block.sourceRefs.filter { it in validChunkIds }.distinct(),
            )
        }
        // 重生成时先清掉旧正文，避免同一章正文叠加
        chapterRepository.clearBlocksForChapter(planId)
        chapterRepository.upsertBlocks(blocks)
        chapterRepository.updateStatus(planId, ChapterStatus.VERIFYING)
        onPhase(ChapterPhase.VERIFY)

        val verification = verifyChapter(projectId, plan, blocks)
        if (verification.isFailure) {
            val message = verification.exceptionOrNull()?.message ?: "核验失败"
            chapterRepository.updateStatus(planId, ChapterStatus.NEEDS_REVIEW, "核验未完成：$message")
            return Result.failure(verification.exceptionOrNull() ?: IllegalStateException("核验失败"))
        }

        val verified = verification.getOrThrow()
        if (verified.issues.isNotEmpty()) {
            val note = verified.issues.take(5).joinToString("\n") { "[${it.type}] ${it.message}" }
            chapterRepository.updateStatus(planId, ChapterStatus.NEEDS_REVIEW, note)
            return Result.failure(IllegalStateException("章节存在 ${verified.issues.size} 个待处理问题"))
        }

        // 正文通过核验，先写草稿态，再确认测验（N2）
        chapterRepository.updateStatus(planId, ChapterStatus.DRAFT)
        onPhase(ChapterPhase.QUIZ)

        val quizResult = generateQuiz(projectId, plan, nodes, blocks)
        if (quizResult.isFailure) {
            val message = quizResult.exceptionOrNull()?.message ?: "测验生成失败"
            chapterRepository.updateStatus(planId, ChapterStatus.QUIZ_FAILED, "正文已通过核验，但测验未生成：$message")
            return Result.failure(quizResult.exceptionOrNull() ?: IllegalStateException(message))
        }

        // 待核实清单合并模型自报与核验发现，展示给用户去核对权威出处（问题3）
        val unverified = (output.unverifiedClaims + verified.unverifiedClaims)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_UNVERIFIED_CLAIMS)

        chapterRepository.markPublished(
            id = planId,
            summary = output.summary,
            carryOver = ContextBudget.clamp(output.carryOver.ifBlank { output.summary }, ContextBudget.CARRY_OVER_LIMIT),
            coveredTitles = output.coveredTitles.ifEmpty { nodes.map { it.title } },
            unverifiedClaims = unverified,
            note = if (unverified.isEmpty()) "" else "有 ${unverified.size} 处内容建议你核对权威出处",
        )
        // 推进滚动记忆：runningState 被改写而不是追加，因此长度不随章数增长（问题5）
        advanceMemory(bible, output)
        // 章节发布后回写节点出现章节，并建立首次复习计划（B16/B2）
        writeBackNodeChapters(projectId, planId, blocks)
        scheduleReviews(projectId, blocks)
        refreshProjectStatus(projectId)
        return Result.success(Unit)
    }

    /**
     * 把本章产出并入滚动记忆（问题5）。
     * runningState 取模型改写后的新版本并强制截断到预算内，避免它逐章变长。
     */
    private suspend fun advanceMemory(bible: StoryBible, output: ChapterOutput) {
        val nextState = output.runningStateUpdate.ifBlank {
            // 模型没给改写版本时退回「旧状态 + 本章摘要」再截断，仍然受预算约束
            listOf(bible.runningState, output.summary).filter { it.isNotBlank() }.joinToString(" ")
        }
        storyBibleRepository.updateMemory(
            projectId = bible.projectId,
            runningState = ContextBudget.clamp(nextState, ContextBudget.RUNNING_STATE_LIMIT),
            coveredTitles = ContextBudget.mergeCovered(bible.coveredTitles, output.coveredTitles),
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

        // 无资料模式核验的是「推演是否走通 + 有没有编造出处」，而不是「与资料是否一致」（问题3）
        val prompt = if (modelKnowledge) {
            prompts.load(PROMPT_VERIFY_MODEL)
                .replace("{topic}", project?.topic.orEmpty())
                .replace("{derivationChain}", derivation)
                .replace("{learningGoals}", plan.learningGoals.joinToString("；"))
                .replace("{nodes}", formatNodesWithDerivation(nodes))
                .replace("{blocks}", body)
        } else {
            val chunks = loadChunksForNodes(projectId, nodes)
            prompts.load(PROMPT_VERIFY)
                .replace("{derivationChain}", derivation)
                .replace("{learningGoals}", plan.learningGoals.joinToString("；"))
                .replace("{chunks}", formatChunks(chunks))
                .replace("{blocks}", body)
        }

        return router.generate(
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
    }

    /** 出题使用核验模型，与设置页说明一致（N3） */
    suspend fun generateQuiz(
        projectId: String,
        plan: ChapterPlan,
        nodes: List<KnowledgeNode>,
        blocks: List<ChapterBlock>,
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
                order = index,
                question = quiz.question,
                type = quiz.type,
                options = quiz.options,
                answer = quiz.answer,
                explanation = quiz.explanation,
                nodeIds = quiz.nodeTitles.mapNotNull { titleToId[it.trim()] }.distinct(),
            )
        }
        quizRepository.deleteByChapter(plan.id)
        quizRepository.upsertAll(quizzes)
        return Result.success(Unit)
    }

    suspend fun refreshProjectStatus(projectId: String) {
        val project = projectRepository.get(projectId) ?: return
        val plans = chapterRepository.getPlans(projectId)
        if (plans.isEmpty()) return
        val allPublished = plans.all { it.status == ChapterStatus.PUBLISHED }
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
        return chunks.take(MAX_CHUNKS).joinToString("\n\n") {
            "[片段ID: ${it.id}]\n${it.text.take(CHUNK_EXCERPT)}"
        }
    }

    /** 节点连带来处与去处一起给模型，推演才接得上（问题2） */
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
            "资料片段（引用时把片段ID填进对应 block 的 sourceRefs）：\n" + formatChunks(chunks)
        }

    private fun unverifiedSection(modelKnowledge: Boolean): String =
        if (modelKnowledge) {
            "- `unverifiedClaims`：本章里读者**不该只信你一家之言**的断言（具体数字门槛、期限、地区差异、近年修改）。宁可多列不要漏列。"
        } else {
            "- `unverifiedClaims`：留空数组。"
        }

    private companion object {
        const val PROMPT_KNOWLEDGE_MAP = "knowledge_map"
        const val PROMPT_KNOWLEDGE_MAP_MODEL = "knowledge_map_model"
        const val PROMPT_STORY_BIBLE = "story_bible"
        const val PROMPT_CHAPTER = "chapter"
        const val PROMPT_VERIFY = "verify"
        const val PROMPT_VERIFY_MODEL = "verify_model"
        const val PROMPT_QUIZ = "quiz"

        const val SYSTEM_MAP = "你在整理一门学科的演进主干，不是在做目录。"
        const val SYSTEM_MAP_MODEL =
            "你在整理一门学科的演进主干，知识由你自己提供。宁可承认不确定，也不要编造具体出处。"
        const val SYSTEM_BIBLE = "你在设计一条讲述路线，让学科沿着它自己长出来。"
        const val SYSTEM_CHAPTER = "你在写一段推演：让读者跟着走，制度自己出现，而不是被告知名词。"
        const val SYSTEM_VERIFY = "你是核验员。既查知识准确性，也查推演有没有跳步。"

        const val MIN_QUIZ_QUESTIONS = 3
        const val MAX_QUIZ_QUESTIONS = 5
        const val FALLBACK_CHUNK_LIMIT = 20
        const val FIRST_REVIEW_DELAY_MILLIS = 86_400_000L

        // 一章承载一整条演进链，所以章数远少于知识点数（问题7）
        const val NODES_PER_CHAPTER = 4
        const val MIN_CHAPTERS = 4
        const val MAX_CHAPTERS = 12
        const val MIN_WORDS = 1200
        const val MAX_WORDS = 4000

        const val MAX_UNVERIFIED_CLAIMS = 12
        const val MAX_CHUNKS = 50
        const val CHUNK_EXCERPT = 500
        const val NODE_EXCERPT = 160
        const val BLOCK_EXCERPT = 1200
        const val QUIZ_EXCERPT = 800
        const val CONTEXT_CHAIN_LIMIT = 10
    }
}
