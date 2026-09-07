package com.example.zhishu.ai.pipeline

import com.example.zhishu.domain.model.KnowledgeNode

/**
 * 单链展开的 prompt 上下文分档（用户反馈：「重试的时候真的有必要把所有资料都发给 AI 吗」）。
 *
 * 用户的判断是对的。一条链要分几轮问完，第二轮起模型其实已经知道自己在写什么，
 * 这时候把所有已写节点的定义、来处、去处、易混、案例整套重发一遍，除了让 prompt
 * 变长、变贵、更容易撞上下文上限，没有任何收益。它真正需要的只有两样：
 *
 * 1. **还差哪些节点**——一份 `nodeKey：标题` 清单；
 * 2. **接口在哪**——紧挨着这些节点的上下游节点长什么样，`arisesFrom`/`leadsTo`
 *    才咬得上。这几个要给全文，其余一律只给标题。
 *
 * 于是分两档：
 *
 * | | 首轮 | 续轮/重试 |
 * |---|---|---|
 * | 本链已写好的节点 | 摘要（定义截断） | **紧邻上下游给全文，其余只给标题** |
 * | 资料片段 | 按整条链检索 | **只按还差的那几个节点检索** |
 *
 * 这里只放纯函数：不碰数据库、不碰模型，[PipelineService] 把数据传进来即可，
 * 因此可以直接对「重试时到底发了什么」写断言。
 */
internal object ChainPromptContext {

    /** 续轮最多带几个紧邻节点的全文。再多就退化成「把整条链重发一遍」 */
    const val NEIGHBOUR_LIMIT = 4

    /** 紧邻节点全文里定义的字数上限。给足以看清接口，但不必是全文照搬 */
    const val NEIGHBOUR_DEFINITION_LIMIT = 200

    /** 只给标题的那部分最多列几个，避免长链把清单本身撑长 */
    const val TITLE_ONLY_LIMIT = 30

    /**
     * 当前链首次展开时的「已写好节点」区块。
     *
     * 首轮不等于没有历史：跨链共享节点可能已经由前面的链写好。它们仍按关联度分档，
     * 只有本轮节点的直接前后邻居给详情，本链其他已完成节点只列标题。
     */
    fun firstPassFilledSection(
        chainTitles: List<String>,
        remainingTitles: Collection<String>,
        filledByTitle: Map<String, KnowledgeNode>,
    ): String = relatedFilledSection(chainTitles, remainingTitles, filledByTitle)

    /**
     * 续轮/重试用的「已写好节点」区块。
     *
     * [remainingTitles] 是本轮还要写的节点标题。规则：
     * - 这些节点在链上的**直接前驱与直接后继**（已写好的那些）→ 给全文（定义 + 来处 + 去处）；
     * - 本链其他已写好的节点 → 只给一行标题；
     * - 一个都没有 → 明确说「这条链还没有任何节点被写过」，不留空字符串让模型自己猜。
     */
    fun followUpFilledSection(
        chainTitles: List<String>,
        remainingTitles: Collection<String>,
        filledByTitle: Map<String, KnowledgeNode>,
    ): String = relatedFilledSection(chainTitles, remainingTitles, filledByTitle)

    private fun relatedFilledSection(
        chainTitles: List<String>,
        remainingTitles: Collection<String>,
        filledByTitle: Map<String, KnowledgeNode>,
    ): String {
        val ordered = chainTitles.map { it.trim() }.filter { it.isNotEmpty() }
        val remaining = remainingTitles.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val neighbourTitles = neighbourTitles(ordered, remaining)

        val neighbours = neighbourTitles.mapNotNull { filledByTitle[it] }.take(NEIGHBOUR_LIMIT)
        val neighbourSet = neighbours.map { it.title.trim() }.toSet()
        val others = ordered
            .filter { it !in remaining && it !in neighbourSet }
            .filter { filledByTitle.containsKey(it) }
            .take(TITLE_ONLY_LIMIT)

        if (neighbours.isEmpty() && others.isEmpty()) {
            return "暂无（这条链还没有任何节点被写过，你写的是第一批）"
        }
        return buildString {
            if (neighbours.isNotEmpty()) {
                appendLine("紧挨着本轮节点的上下游（`arisesFrom`/`leadsTo` 必须和它们咬合）：")
                neighbours.forEach { node ->
                    append("- ").append(node.title).append("：")
                    append(node.definition.take(NEIGHBOUR_DEFINITION_LIMIT))
                    if (node.arisesFrom.isNotBlank()) {
                        append("\n    来处：").append(node.arisesFrom)
                    }
                    if (node.leadsTo.isNotBlank()) {
                        append("\n    去处：").append(node.leadsTo)
                    }
                    appendLine()
                }
            }
            if (others.isNotEmpty()) {
                if (neighbours.isNotEmpty()) appendLine()
                appendLine("本链其他已写好的节点（只列标题，别再写一遍，也不要重复它们的内容）：")
                append(others.joinToString("、"))
            }
        }.trim()
    }

    /**
     * 本轮节点在链上的直接前驱与直接后继标题（去重，保持链上顺序）。
     *
     * 只取直接相邻的一格：链是一条推理，`arisesFrom` 咬的是前一个、`leadsTo` 咬的是后一个，
     * 隔两格以上的节点对写好本轮内容没有帮助，带上只是加长 prompt。
     */
    fun neighbourTitles(
        chainTitles: List<String>,
        remainingTitles: Set<String>,
    ): List<String> {
        val result = LinkedHashSet<String>()
        chainTitles.forEachIndexed { index, title ->
            if (title !in remainingTitles) return@forEachIndexed
            chainTitles.getOrNull(index - 1)?.takeIf { it !in remainingTitles }?.let { result += it }
            chainTitles.getOrNull(index + 1)?.takeIf { it !in remainingTitles }?.let { result += it }
        }
        return result.toList()
    }
}
