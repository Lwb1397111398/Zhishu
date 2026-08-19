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
        // 引用的资料片段必须属于本项目
        val cleaned = blocks.map { block ->
            block.copy(sourceRefs = block.sourceRefs.filter { it in validChunkIds }.distinct())
        }
        if (requireSourceRefs) {
            val factBlocks = cleaned.filter { it.type == BlockType.FACT }
            if (factBlocks.isNotEmpty() && factBlocks.all { it.sourceRefs.isEmpty() }) {
                return Result.failure(ModelError.Invalid("原始事实段落缺少可追溯的资料引用"))
            }
        }
        return Result.success(output.copy(blocks = cleaned))
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
