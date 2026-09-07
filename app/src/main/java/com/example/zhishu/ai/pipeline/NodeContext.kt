package com.example.zhishu.ai.pipeline

import com.example.zhishu.domain.model.KnowledgeNode

/**
 * 节点上下文分档（三段式改造）。
 *
 * 讲述路线拆成大纲（L1）与契约（L2）两段后，各段对节点信息的需求完全不同：
 * 规划只需要知道「有哪些点、谁在谁前面」，契约才需要「这个概念怎么来、解决什么」。
 * 原来的 `formatNodesWithDerivation` 把全部字段一次性发给大纲调用，
 * 30 节点约 12k 字符，其中大纲根本用不上的定义占了 8 成。
 *
 * 编号只在模型交互层使用（L0-L2 的输出与模型回传），落库一律仍是节点 ID——
 * `nodeKey` 是批次内序号，跨批次会重复，因此每次三段式开始时按当前 active
 * 批次重新分配，不跨批次传递。
 *
 * 正文与核验继续用标题制（`formatNodesWithDerivation`）：正文块要落
 * `knowledgeNodeTitles`，且按章过滤后节点只剩三四个，分档收益为零。
 *
 * 这里只放纯函数：不碰数据库、不碰模型，方便对「每个档位到底发了什么」写断言。
 */
internal object NodeContext {

    /** 编号上限：地图超过这个节点数时 L1 也只编前 40 个，防止极端大图撑爆 prompt */
    const val LIMIT = 40

    /**
     * L0 —— 纯编号清单（备用：极省场景）。
     * 每行 `- #3 民事行为能力`，单节点约 10 字符。
     */
    fun formatL0(
        nodes: List<KnowledgeNode>,
        keyOf: (KnowledgeNode) -> String,
    ): String = nodes.take(LIMIT).joinToString("\n") { "- ${keyOf(it)} ${it.title}" }

    /**
     * L1 —— 规划档：编号 + 标题 + 前置/去处编号。
     * 每行 `- #3 民事行为能力｜前置 #1,#2｜去处 #7`，单节点约 40 字符。
     * 40 节点约 2k 字符（现状全量约 12k）。
     *
     * [keyByTitle] 把前置标题解析成编号；解析不到的保留原标题——
     * 前置可能指向本批之外的节点（骨架去重后），硬转编号会丢信息。
     */
    fun formatL1(
        nodes: List<KnowledgeNode>,
        keyOf: (KnowledgeNode) -> String,
        keyByTitle: Map<String, String>,
    ): String = nodes.take(LIMIT).joinToString("\n") { node ->
        buildString {
            append("- ").append(keyOf(node)).append(" ").append(node.title)
            val prereqKeys = node.prerequisites.mapNotNull { keyByTitle[it.trim()] }
            if (prereqKeys.isNotEmpty()) {
                append("｜前置 ").append(prereqKeys.joinToString(","))
            } else if (node.prerequisites.any { it.isNotBlank() }) {
                append("｜前置 ").append(node.prerequisites.take(4).joinToString("；"))
            }
            if (node.leadsTo.isNotBlank()) {
                append("｜去处：").append(node.leadsTo.take(60))
            }
        }
    }

    /**
     * L2 —— 契约档：L1 + 定义（截断）+ 来处。
     * 契约要回答「这章引入什么、为什么此刻引入」，来处与定义是最低必需，
     * 易混/举例留给正文。单节点约 250 字符。
     */
    fun formatL2(
        nodes: List<KnowledgeNode>,
        keyOf: (KnowledgeNode) -> String,
        keyByTitle: Map<String, String>,
    ): String = nodes.take(LIMIT).joinToString("\n\n") { node ->
        buildString {
            append(formatL1(listOf(node), keyOf, keyByTitle).removePrefix("- "))
            if (node.definition.isNotBlank()) {
                append("\n  定义：").append(node.definition.take(120))
            }
            if (node.arisesFrom.isNotBlank()) {
                append("\n  来处：").append(node.arisesFrom.take(80))
            }
        }
    }

    /**
     * 当前批次内所有节点的「标题 → 编号」映射。
     * 前置字段存的是标题，转编号全靠它；标题不在表里就保留原标题。
     */
    fun keyByTitle(
        nodes: List<KnowledgeNode>,
        keyOf: (KnowledgeNode) -> String,
    ): Map<String, String> = nodes.associate { it.title.trim() to keyOf(it) }
}
