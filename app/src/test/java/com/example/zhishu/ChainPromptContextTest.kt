package com.example.zhishu

import com.example.zhishu.ai.pipeline.ChainPromptContext
import com.example.zhishu.domain.model.KnowledgeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链重试的最小上下文（用户反馈：「重试的时候真的有必要把所有资料都发给 AI 吗」）。
 *
 * 用户的意见：重试时消息越简明越好——大部分知识点只要标题，不需要来处、去处、
 * 前置、易混、案例；只有上下联系最紧密的那几个才发全文。这些测试就是把这条规则钉住。
 */
class ChainPromptContextTest {

    private fun node(
        title: String,
        definition: String = "$title 的定义",
        arisesFrom: String = "$title 的来处",
        leadsTo: String = "$title 的去处",
    ) = KnowledgeNode(
        id = "id-$title",
        projectId = "p-1",
        title = title,
        definition = definition,
        arisesFrom = arisesFrom,
        leadsTo = leadsTo,
        confusions = listOf("$title 容易和别的混"),
        examples = listOf("$title 的例子"),
        prerequisites = listOf("$title 的前置"),
    )

    private val chain = listOf("A", "B", "C", "D", "E", "F")

    /** 紧邻 = 直接前驱 + 直接后继，只取一格 */
    @Test
    fun neighbourTitles_takesOnlyDirectNeighbours() {
        assertEquals(listOf("B", "D"), ChainPromptContext.neighbourTitles(chain, setOf("C")))
        // 连续两个待写节点：中间那格不算邻居（它自己就是待写的）
        assertEquals(listOf("B", "E"), ChainPromptContext.neighbourTitles(chain, setOf("C", "D")))
        // 链首没有前驱
        assertEquals(listOf("B"), ChainPromptContext.neighbourTitles(chain, setOf("A")))
    }

    /**
     * 核心断言：续轮只有紧邻节点给全文，其余已写节点只出现标题。
     *
     * 「只出现标题」的判据是——那些节点的定义/来处/易混/案例一个字都不该出现在 prompt 里。
     */
    @Test
    fun followUp_onlyNeighboursGetFullText_othersTitleOnly() {
        val filled = listOf("A", "B", "D", "E", "F").map { node(it) }
        val section = ChainPromptContext.followUpFilledSection(
            chainTitles = chain,
            remainingTitles = listOf("C"),
            filledByTitle = filled.associateBy { it.title },
        )
        // 紧邻的 B、D 给全文：定义 + 来处 + 去处
        assertTrue(section.contains("B 的定义"))
        assertTrue(section.contains("B 的来处"))
        assertTrue(section.contains("D 的去处"))
        // 非紧邻的 A、E、F 只出现标题，正文字段一律不发
        listOf("A", "E", "F").forEach { t ->
            assertTrue("非紧邻节点 $t 的标题应该出现", section.contains(t))
            assertFalse("非紧邻节点 $t 不该发定义", section.contains("$t 的定义"))
            assertFalse("非紧邻节点 $t 不该发来处", section.contains("$t 的来处"))
        }
        // 用户明确点名不必发的字段：易混、案例、前置——任何节点都不发
        assertFalse(section.contains("容易和别的混"))
        assertFalse(section.contains("的例子"))
        assertFalse(section.contains("的前置"))
    }

    /** 续轮上下文不随「这条链已写了多少」增长：写完 1 个和写完 20 个，长度基本一样 */
    @Test
    fun followUp_lengthDoesNotGrowWithFilledCount() {
        val longChain = (1..40).map { "节点$it" }
        fun sectionAfter(filledCount: Int): String {
            val filled = longChain.take(filledCount).map { node(it) }
            return ChainPromptContext.followUpFilledSection(
                chainTitles = longChain,
                remainingTitles = listOf(longChain[filledCount]),
                filledByTitle = filled.associateBy { it.title },
            )
        }
        val early = sectionAfter(2).length
        val late = sectionAfter(30).length
        // 只增长「标题清单」那一截，不会把 30 个节点的全文都带上
        assertTrue("续轮上下文随已写数量膨胀了（$early → $late）", late <= early * 3)
    }

    /** 一个都没写过时明确说清楚，不留空字符串让模型自己猜 */
    @Test
    fun followUp_nothingFilled_saysSoExplicitly() {
        val section = ChainPromptContext.followUpFilledSection(
            chainTitles = chain,
            remainingTitles = chain,
            filledByTitle = emptyMap(),
        )
        assertTrue(section.contains("暂无"))
        assertTrue(section.contains("第一批"))
    }

    /**
     * 首轮也只给本轮节点的直接邻居详情；本链其余已完成节点只列标题。
     * 共享节点可能来自更早的链，不能因为这是当前链的首轮就把它们的定义全部重发。
     */
    @Test
    fun firstPass_onlyNeighboursGetDetails_othersTitleOnly() {
        val filled = listOf("A", "B", "D", "E", "F", "链外节点").map { node(it) }
        val section = ChainPromptContext.firstPassFilledSection(
            chainTitles = chain,
            remainingTitles = listOf("C"),
            filledByTitle = filled.associateBy { it.title },
        )

        assertTrue(section.contains("B 的定义"))
        assertTrue(section.contains("D 的定义"))
        listOf("A", "E", "F").forEach { title ->
            assertTrue(section.contains(title))
            assertFalse("非紧邻节点 $title 不该发定义", section.contains("$title 的定义"))
        }
        assertFalse(section.contains("链外节点"))
    }

    @Test
    fun firstPass_titleOnlyListIsCapped() {
        val titles = (1..40).map { "节点$it" }
        val filled = titles.filterNot { it == "节点20" }.map { node(it) }
        val section = ChainPromptContext.firstPassFilledSection(
            chainTitles = titles,
            remainingTitles = listOf("节点20"),
            filledByTitle = filled.associateBy { it.title },
        )

        val titleOnly = section.substringAfter("本链其他已写好的节点").substringAfter("：")
        assertTrue(titleOnly.split("、").size <= ChainPromptContext.TITLE_ONLY_LIMIT)
    }
}
