package com.example.zhishu.ai.outputs

import com.example.zhishu.ai.ModelError
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.QuizType

/**
 * 模型输出的业务校验（N14）。
 * JSON 能反序列化不代表内容可用：标题为空、引用不存在的节点、题目缺答案都必须直接失败，
 * 不做「盲目重试」，避免把无依据内容写进数据库。
 *
 * [requireSourceRefs] 区分两种知识来源模式（问题3）：
 * 有资料时事实段落必须可追溯到资料片段；无资料（模型自备知识）时没有片段可引，
 * 改由核验环节输出「待核实清单」承担同样的责任，因此这里不能再强制引用。
 */
object OutputValidators {

    /**
     * 骨架校验（批次化生成）。
     *
     * 只做「能开跑」的硬校验：至少一条链、每条链至少两个节点标题、节点标题
     * 跨链去重后非空。节点数量低于学习深度预期只算软问题——由调用方写进 gaps
     * 提示用户补充，而不是整批失败（骨架重试便宜，但给模型「列少点也行」的
     * 退路总比反复失败强）。
     */
    fun validateSkeleton(output: KnowledgeMapSkeletonOutput): Result<KnowledgeMapSkeletonOutput> {
        val chains = output.chains
            .map { chain ->
                chain.copy(nodeTitles = chain.nodeTitles.map { it.trim() }.filter { it.isNotBlank() })
            }
            .filter { it.nodeTitles.size >= 2 }
        if (chains.isEmpty()) {
            return Result.failure(ModelError.Invalid("知识地图骨架没有任何有效演进链"))
        }
        val allTitles = chains.flatMap { it.nodeTitles }.map { it.trim() }.distinct()
        if (allTitles.isEmpty()) {
            return Result.failure(ModelError.Invalid("知识地图骨架没有列出任何知识点"))
        }
        return Result.success(output.copy(chains = chains))
    }

    /**
     * 单链展开校验（批次化生成）。
     *
     * [expectedKeys] 是骨架阶段程序分配的 nodeKey 集合——模型必须原样回传
     * 标识才能匹配到占位节点；[knownTitles] 是骨架阶段的标题集合——模型给出的
     * 标题不在骨架里的（超纲节点）按「模型改了标题」丢弃，写进 gaps 提示，
     * 而不是让整条链失败。没带 nodeKey、或带了不认识标识的节点一律丢弃。
     */
    fun validateChainNodes(
        output: ChainNodesOutput,
        expectedKeys: Set<String>,
        knownTitles: Set<String>,
    ): Result<Pair<List<NodeDto>, List<String>>> {
        val valid = output.nodes.filter { it.nodeKey.isNotBlank() && it.nodeKey in expectedKeys }
        val unmatched = output.nodes.map { it.title.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { title -> title !in knownTitles }
        return Result.success(valid to unmatched)
    }

    /** 全局审计校验（批次化生成）：gaps 非空但 conflicts 结构异常时按脏数据清洗 */
    fun validateAudit(output: KnowledgeMapAuditOutput): Result<KnowledgeMapAuditOutput> {
        val gaps = output.gaps.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val conflicts = output.conflicts
            .filter { it.topic.isNotBlank() }
            .distinctBy { it.topic.trim() }
        return Result.success(output.copy(gaps = gaps, conflicts = conflicts))
    }

    fun validateKnowledgeMap(output: KnowledgeMapOutput): Result<KnowledgeMapOutput> {
        val nodes = output.nodes.filter { it.title.isNotBlank() }
        if (nodes.isEmpty()) {
            return Result.failure(ModelError.Invalid("知识地图没有任何有效知识点"))
        }
        val duplicated = nodes.groupBy { it.title.trim() }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            return Result.failure(
                ModelError.Invalid("知识点标题重复：${duplicated.take(3).joinToString("、")}"),
            )
        }
        // 演进链只保留能映射到既有节点的部分，链本身缺失不算失败（章节规划可退回按节点顺序切分）
        val titles = nodes.map { it.title.trim() }.toSet()
        val chains = output.chains
            .map { chain -> chain.copy(nodeTitles = chain.nodeTitles.map { it.trim() }.filter { it in titles }) }
            .filter { it.nodeTitles.size >= 2 }
        return Result.success(output.copy(nodes = nodes, chains = chains))
    }

    fun validateStoryBible(
        output: StoryBibleOutput,
        knownNodeTitles: Set<String>,
    ): Result<StoryBibleOutput> {
        val chapters = output.chapters.filter { it.title.isNotBlank() }
        if (chapters.isEmpty()) {
            return Result.failure(ModelError.Invalid("讲述路线没有有效章节"))
        }
        // 至少要有一章能映射到已有知识点，否则整条路线与知识地图脱节
        val mapped = chapters.count { chapter ->
            chapter.nodeTitles.any { it.trim() in knownNodeTitles }
        }
        if (mapped == 0) {
            return Result.failure(ModelError.Invalid("章节规划没有任何一章关联到已有知识点"))
        }
        return Result.success(output.copy(chapters = chapters))
    }

    /**
     * 续卷规划的校验（第三阶段 3C）。
     *
     * 比首次规划多两条：章数不能超过卷内上限（多给的丢掉，留给再下一卷），
     * 且至少要有一章真的落在**还没讲过**的知识点上——否则这一卷就是把讲过的
     * 再讲一遍，白花钱。已讲知识点仍可作为承接引用，只是不能是全部。
     */
    fun validateNextVolume(
        output: NextVolumeOutput,
        knownNodeTitles: Set<String>,
        pendingNodeTitles: Set<String>,
        maxChapters: Int,
    ): Result<NextVolumeOutput> {
        val chapters = output.chapters.filter { it.title.isNotBlank() }.take(maxChapters)
        if (chapters.isEmpty()) {
            return Result.failure(ModelError.Invalid("续卷规划没有有效章节"))
        }
        if (chapters.none { chapter -> chapter.nodeTitles.any { it.trim() in knownNodeTitles } }) {
            return Result.failure(ModelError.Invalid("续卷规划没有任何一章关联到已有知识点"))
        }
        // 还有没讲的知识点时，新卷必须至少讲到一个；全讲完了才允许纯深化
        if (pendingNodeTitles.isNotEmpty() &&
            chapters.none { chapter -> chapter.nodeTitles.any { it.trim() in pendingNodeTitles } }
        ) {
            return Result.failure(
                ModelError.Invalid("续卷规划全是已经讲过的知识点，没有推进主线"),
            )
        }
        return Result.success(output.copy(chapters = chapters))
    }

    fun validateChapter(
        output: ChapterOutput,
        knownNodeTitles: Set<String>,
        validChunkIds: Set<String>,
        requireSourceRefs: Boolean = true,
    ): Result<ChapterOutput> {
        val blocks = output.blocks.filter { it.text.isNotBlank() }
        if (blocks.isEmpty()) {
            return Result.failure(ModelError.Invalid("章节正文为空"))
        }
        val knowledgeBlocks = blocks.filter {
            it.type == BlockType.EXPLANATION ||
                it.type == BlockType.FACT ||
                it.type == BlockType.KEY_POINT
        }
        // 讲解与事实段落必须落在既有知识点上，否则等于凭空讲知识
        val unmapped = knowledgeBlocks.filter { block ->
            block.knowledgeNodeTitles.none { it.trim() in knownNodeTitles }
        }
        if (knowledgeBlocks.isNotEmpty() && unmapped.size == knowledgeBlocks.size) {
            return Result.failure(ModelError.Invalid("章节的知识讲解段落没有关联到任何既有知识点"))
        }
        // 引用不在这里过滤（4A）。
        //
        // 原来这一步会把对不上片段的引用静默丢掉：问题随之消失，正文里那句
        // 需要出处的话变成了没人管的断言，阅读器还会显示「本段无引用」。
        // 现在原样交给 CitationResolver，由它记成 RESOLVED 或 UNCONFIRMED。
        // 校验这一层只判断「有没有可追溯的引用」这件事本身。
        if (requireSourceRefs) {
            val factBlocks = blocks.filter { it.type == BlockType.FACT }
            val traceable = factBlocks.filter { block ->
                block.sourceRefs.any { it.trim() in validChunkIds }
            }
            if (factBlocks.isNotEmpty() && traceable.isEmpty()) {
                return Result.failure(ModelError.Invalid("原始事实段落缺少可追溯的资料引用"))
            }
        }
        return Result.success(output.copy(blocks = blocks))
    }

    fun validateQuizSet(
        output: QuizSetOutput,
        knownNodeTitles: Set<String>,
        minQuestions: Int,
    ): Result<QuizSetOutput> {
        val valid = output.questions.filter { quiz ->
            quiz.question.isNotBlank() &&
                quiz.answer.isNotBlank() &&
                quiz.nodeTitles.any { it.trim() in knownNodeTitles } &&
                optionsValid(quiz)
        }
        if (valid.size < minQuestions) {
            return Result.failure(
                ModelError.Invalid("有效题目不足：需要 $minQuestions 题，实际 ${valid.size} 题"),
            )
        }
        return Result.success(output.copy(questions = valid))
    }

    private fun optionsValid(quiz: QuizDto): Boolean = when (quiz.type) {
        QuizType.RECALL -> true
        else -> quiz.options.size >= 2 &&
            quiz.options.none { it.isBlank() } &&
            quiz.options.contains(quiz.answer)
    }
}
