package com.example.zhishu.ai.pipeline

import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.StoryBible

/**
 * 「这个知识点讲过了吗」的唯一判据（第三阶段 3D）。
 *
 * 3C 时这件事是按**标题字符串**判断的：章节的 `coveredTitles` 里有没有这个标题。
 * 问题在于标题是用户可以改的。改一次知识点名字，讲过的内容就全部变回「还没讲」，
 * 续卷会把它们重新排一遍——用户为同一段知识付两次钱，故事里也会重复讲。
 *
 * 现在改成 ID 优先：
 *
 * | 判据 | 用途 |
 * |---|---|
 * | `coveredNodeIds` | 唯一可靠依据，新数据一律写它 |
 * | `coveredTitles` | 只用于展示，以及旧数据（v9 之前）的兼容回退 |
 *
 * 还有第三种情况必须如实表达：`coveredTitles` 里有一条，但在当前知识地图里
 * **找不到对应节点**（改过名、或知识地图被重新生成过）。这条内容既不能算覆盖了某个节点，
 * 也不该被悄悄丢掉——它落进 [CoverageReport.unresolvedTitles]，界面上说明
 * 「有 N 条讲过的内容对不上知识点」，由用户判断，而不是系统猜。
 */
internal object NodeCoverage {

    data class CoverageReport(
        /** 确认讲过的知识点 ID */
        val coveredNodeIds: Set<String>,
        /** 已经安排进某一章（不管写没写）的知识点 ID */
        val plannedNodeIds: Set<String>,
        /** 既没安排也没讲过的知识点，续卷优先讲它们 */
        val pendingNodes: List<KnowledgeNode>,
        /** 讲过、但对不上任何现存知识点的标题。待用户确认，不当成「没讲过」 */
        val unresolvedTitles: List<String>,
    ) {
        val pendingTitles: Set<String> get() = pendingNodes.map { it.title }.toSet()
    }

    fun report(
        nodes: List<KnowledgeNode>,
        plans: List<ChapterPlan>,
        bible: StoryBible?,
    ): CoverageReport {
        val byId = nodes.associateBy { it.id }
        // 标题 → ID 只在标题唯一时可用：同名两个节点无法判断指的是哪个，宁可留给用户确认
        val titleToId = nodes.groupBy { it.title.trim() }
            .filterValues { it.size == 1 }
            .mapValues { (_, group) -> group.first().id }

        val idsFromData = (
            plans.flatMap { it.coveredNodeIds } +
                bible?.coveredNodeIds.orEmpty()
            ).filter { it in byId }

        val titles = (
            plans.flatMap { it.coveredTitles } +
                bible?.coveredTitles.orEmpty()
            ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val idsFromTitles = titles.mapNotNull { titleToId[it] }
        val covered = (idsFromData + idsFromTitles).toSet()
        // 对不上的标题：既不在标题表里，也不是某个已确认覆盖节点的旧名字（后者无从判断）
        val unresolved = titles.filter { it !in titleToId }

        val planned = plans.flatMap { it.nodeIds }.filter { it in byId }.toSet()
        val pending = nodes.filter { it.id !in planned && it.id !in covered }

        return CoverageReport(
            coveredNodeIds = covered,
            plannedNodeIds = planned,
            pendingNodes = pending,
            unresolvedTitles = unresolved,
        )
    }

    /**
     * 把本章讲到的知识点并入台账。
     *
     * 只接受**真实存在**的节点 ID：模型给的标题先映射成 ID，映射不上的丢掉——
     * 台账里塞一个不存在的 ID 等于永久少讲一个知识点，而且看不出是哪里出的错。
     */
    fun mergeCoveredIds(existing: List<String>, added: List<String>, knownIds: Set<String>): List<String> {
        val merged = LinkedHashSet(existing.filter { it in knownIds })
        added.filter { it in knownIds }.forEach { merged += it }
        return merged.toList()
    }
}
