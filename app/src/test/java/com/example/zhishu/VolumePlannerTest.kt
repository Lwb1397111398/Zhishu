package com.example.zhishu

import com.example.zhishu.ai.pipeline.VolumePlanner
import com.example.zhishu.ai.outputs.ChainDto
import com.example.zhishu.domain.model.KnowledgeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VolumePlanner.chaptersFor 的区间约束回归（2026-09-07 线上故障）。
 *
 * 用户真实项目约 292 个知识点：lower=ceil(292/4)=73 > upper=8，
 * `target.coerceIn(lower, upper)` 抛 IllegalArgumentException
 * 「Cannot coerce value to an empty range: maximum 8 is less than minimum 73」，
 * 讲述路线（STORY_BIBLE）在调任何模型之前就整体失败。
 * 此前所有测试都用小地图（<32 个节点），所以从未触发。
 *
 * 语义：节点数超过一卷承载量（NODES_PER_CHAPTER × VOLUME_MAX_CHAPTERS = 32）时，
 * 本卷按上限 8 章规划，剩余节点由续卷接手——firstVolume 的贪心装链正是按这个假设设计的。
 */
class VolumePlannerTest {

    private fun node(id: String) = KnowledgeNode(id = id, projectId = "p-1", title = id)

    private fun chain(prefix: String, nodeCount: Int) =
        ChainDto(theme = prefix, nodeTitles = (1..nodeCount).map { "$prefix-n$it" })

    @Test
    fun chaptersFor_hugeNodeCount_doesNotThrow() {
        // 修复前：4.coerceIn(73, 8) 直接抛 IllegalArgumentException
        assertEquals(8, VolumePlanner.chaptersFor(292))
        assertEquals(8, VolumePlanner.chaptersFor(290))
        // 33 个节点：lower=9 首次超过 upper=8，是最小复现
        assertEquals(8, VolumePlanner.chaptersFor(33))
    }

    @Test
    fun chaptersFor_boundaryAndSmallProjects_unchanged() {
        assertEquals(0, VolumePlanner.chaptersFor(0))
        assertEquals(1, VolumePlanner.chaptersFor(1))
        assertEquals(2, VolumePlanner.chaptersFor(2))
        assertEquals(4, VolumePlanner.chaptersFor(5))
        assertEquals(4, VolumePlanner.chaptersFor(16))
        // 32 个节点正好装满一卷，与修复前行为一致
        assertEquals(8, VolumePlanner.chaptersFor(32))
    }

    @Test
    fun firstVolume_hugeMap_singleOversizedChain_doesNotThrow() {
        // 单条链 292 个节点（链不可切，整条给入）：suggestedChapters 也要算得出来
        val titles = (1..292).map { "n$it" }
        val nodesByTitle = titles.associateWith { node(it) }
        val volume = VolumePlanner.firstVolume(
            chains = listOf(ChainDto(theme = "大链", nodeTitles = titles)),
            nodesByTitle = nodesByTitle,
        )
        assertEquals(292, volume.nodes.size)
        assertTrue("章数预算必须在 1~8 内：${volume.suggestedChapters}", volume.suggestedChapters in 1..8)
    }

    @Test
    fun firstVolume_hugeMap_manyChains_picksFirstVolumeOnly() {
        // 8 条链 × 36~37 节点 ≈ 用户真实项目规模：修复前在 chaptersFor(292) 炸
        val chains = (1..4).map { chain("a$it", 37) } + (1..4).map { chain("b$it", 36) }
        val nodesByTitle = chains.flatMap { it.nodeTitles }.associateWith { node(it) }
        val volume = VolumePlanner.firstVolume(chains, nodesByTitle)
        assertTrue("第一卷章数预算不能超过 8：${volume.suggestedChapters}", volume.suggestedChapters in 1..8)
        assertTrue(volume.nodes.isNotEmpty())
    }

    @Test
    fun firstVolume_noChainStructure_hugeNodes_doesNotThrow() {
        // 老批次无链结构：退化分支同样调 chaptersFor(292)，一样不能炸
        val nodesByTitle = (1..292).map { "n$it" }.associateWith { node(it) }
        val volume = VolumePlanner.firstVolume(chains = emptyList(), nodesByTitle = nodesByTitle)
        assertTrue(volume.suggestedChapters in 1..8)
    }
}
