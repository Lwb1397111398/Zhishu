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
