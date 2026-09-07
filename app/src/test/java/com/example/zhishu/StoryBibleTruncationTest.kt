package com.example.zhishu

import com.example.zhishu.ai.jsonDecoder
import com.example.zhishu.ai.outputs.OutputValidators
import com.example.zhishu.ai.outputs.StoryBibleOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 讲述路线被输出上限截断时，应保住完整章节，不让残缺尾章拖垮整卷。 */
class StoryBibleTruncationTest {

    private val decoder = jsonDecoder(StoryBibleOutput.serializer())

    private fun chapter(order: Int, title: String, node: String): String =
        """{"order":$order,"title":"$title","derivationChain":"旧办法 → 矛盾 → 新办法","plotGoal":"解决问题","learningGoals":["理解$node"],"nodeTitles":["$node"],"wordTarget":1200,"openingHook":"问题出现","closingHook":"留下新问题","chapterType":"INTRODUCE","contract":{"entryQuestion":"怎么办","priorIntuition":"沿用旧办法","obstacle":"旧办法失效","knowledgeGap":"缺少解释","introducedNodeTitles":["$node"],"appliedNodeTitles":[],"requiredBeats":["发现矛盾"],"resultingChange":"问题得到解决","bridgeToNext":"新的问题"},"prerequisiteNodeTitles":[]}"""

    private fun prefix(): String =
        """{"world":"学习世界","characters":[],"conflict":"旧办法不断失效","style":"演进推演","approach":"从问题中长出制度","throughLine":[],"chapters":[""" +
            chapter(1, "第一章", "节点一") + "," +
            chapter(2, "第二章", "节点二") + ","

    @Test
    fun `末章 contract 中截断时只保留完整章节`() {
        val truncated = prefix() +
            """{"order":3,"title":"第三章","chapterType":"INTRODUCE","contract":{"entryQuestion":"怎么办","knowledgeGap":"被切""".dropLast(1)

        val output = decoder.decode(truncated)

        assertEquals(listOf("第一章", "第二章"), output.chapters.map { it.title })
        OutputValidators.validateStoryBible(
            output,
            knownNodeTitles = setOf("节点一", "节点二"),
            requireContracts = true,
        ).getOrThrow()
    }

    @Test
    fun `章节数组闭合前截断时保留全部完整章节`() {
        val truncated = prefix().removeSuffix(",")

        val output = decoder.decode(truncated)

        assertEquals(listOf("第一章", "第二章"), output.chapters.map { it.title })
    }

    @Test
    fun `顶层必填字段尚未完整时不得伪造路线`() {
        assertThrows(Exception::class.java) {
            decoder.decode("""{"world":"学习世界","characters":[],"conflict":"被切""")
        }
    }
}
