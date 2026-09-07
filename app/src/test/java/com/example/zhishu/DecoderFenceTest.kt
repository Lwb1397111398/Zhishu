package com.example.zhishu

import com.example.zhishu.ai.extractJsonPayload
import com.example.zhishu.ai.jsonDecoder
import com.example.zhishu.ai.outputs.ChainDto
import com.example.zhishu.ai.outputs.ChainNodesOutput
import com.example.zhishu.ai.outputs.ChapterOutput
import com.example.zhishu.ai.outputs.KnowledgeMapSkeletonOutput
import com.example.zhishu.ai.outputs.QuizSetOutput
import com.example.zhishu.ai.repairTruncatedJson
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.QuizType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型输出常把 JSON 包在 ```json 围栏里或前后夹解释文字，
 * 解析层必须先剥掉再解码（否则 offset 0 处直接失败，触发不必要的 JSON 修复调用）。
 */
class DecoderFenceTest {

    private val decoder = jsonDecoder(KnowledgeMapSkeletonOutput.serializer())

    private val skeletonJson = """
        {"chains":[{"theme":"货币形态","opening":"以物易物","nodeTitles":["贝壳","金属铸币","纸币"]}]}
    """.trimIndent()

    @Test
    fun `纯 JSON 直接解析`() {
        val output = decoder.decode(skeletonJson)
        assertEquals(1, output.chains.size)
        assertEquals(listOf("贝壳", "金属铸币", "纸币"), output.chains[0].nodeTitles)
    }

    @Test
    fun `json 围栏包裹时剥离后解析`() {
        val fenced = "```json\n$skeletonJson\n```"
        val output = decoder.decode(fenced)
        assertEquals(1, output.chains.size)
    }

    @Test
    fun `无语言标记围栏包裹时剥离后解析`() {
        val fenced = "```\n$skeletonJson\n```"
        val output = decoder.decode(fenced)
        assertEquals(listOf("贝壳", "金属铸币", "纸币"), output.chains[0].nodeTitles)
    }

    @Test
    fun `前后夹解释文字时截取大括号本体`() {
        val noisy = "好的，以下是骨架：\n$skeletonJson\n希望对你有帮助。"
        val output = decoder.decode(noisy)
        assertEquals(1, output.chains.size)
    }

    @Test
    fun `损坏输入仍然报错而不是静默通过`() {
        val broken = "```json\n{\"chains\": [不完全的 JSON"
        try {
            decoder.decode(broken)
            throw AssertionError("损坏输入应当抛出解析异常")
        } catch (e: Exception) {
            assertTrue(e.message != null)
        }
    }

    @Test
    fun `extractJsonPayload 无大括号时原样返回`() {
        assertEquals("普通文本", extractJsonPayload("  普通文本  "))
    }

    @Test
    fun `链 DTO 也走同一剥离逻辑`() {
        val chainDecoder = jsonDecoder(ChainDto.serializer())
        val output = chainDecoder.decode("```json\n{\"theme\":\"t\",\"nodeTitles\":[\"a\"]}\n```")
        assertEquals(listOf("a"), output.nodeTitles)
    }

    // ==================== 截断 JSON 本地补全 ====================
    // 大 JSON 在输出长度上限处被切断是解码失败的主要来源；
    // 本地剪裁补全必须救回前面所有完整成员，救不了才走模型修复（按 token 计费）

    @Test
    fun `字符串值中间截断_剪掉最后一个成员并补括号`() {
        // 截断发生在第三个键名（"confus…"）中间：前两个成员完整，definition 完整保留
        val truncated = """{"nodes":[{"nodeKey":"nk-1","definition":"要约是","confus"""
        val repaired = repairTruncatedJson(truncated)!!
        assertEquals("""{"nodes":[{"nodeKey":"nk-1","definition":"要约是"}]}""", repaired)
    }

    @Test
    fun `值字符串中间截断_整个成员剪掉`() {
        // 截断发生在 definition 的值中间：该成员整体剪掉，nodeKey 靠前序成员保留
        val truncated = """{"nodes":[{"nodeKey":"nk-1","definition":"要约是"""
        val repaired = repairTruncatedJson(truncated)!!
        assertEquals("""{"nodes":[{"nodeKey":"nk-1"}]}""", repaired)
    }

    @Test
    fun `嵌套数组截断_补齐两层括号`() {
        val truncated = """{"gaps":["a","b" """.trimEnd()
        val repaired = repairTruncatedJson(truncated)!!
        assertEquals("""{"gaps":["a","b"]}""", repaired)
    }

    @Test
    fun `对象后截断_只补外层`() {
        val truncated = """{"nodes":[{"nodeKey":"nk-1"} """.trimEnd()
        val repaired = repairTruncatedJson(truncated)!!
        assertEquals("""{"nodes":[{"nodeKey":"nk-1"}]}""", repaired)
    }

    @Test
    fun `完整 JSON 返回 null 无需修复`() {
        assertEquals(null, repairTruncatedJson("""{"a":1}"""))
    }

    @Test
    fun `第一个成员就断了_返回 null 交给上层`() {
        assertEquals(null, repairTruncatedJson("""{"a" """.trimEnd()))
        assertEquals(null, repairTruncatedJson("""{"a": """.trimEnd()))
    }

    @Test
    fun `字符串内转义引号不误判字符串结束`() {
        // "nk-1\"x" 是完整的转义字符串值；其后在键名处截断
        val truncated = """{"nodes":[{"nodeKey":"nk-1\"x","depth":1,"def"""
        val repaired = repairTruncatedJson(truncated)!!
        assertEquals("""{"nodes":[{"nodeKey":"nk-1\"x","depth":1}]}""", repaired)
    }

    @Test
    fun `解码器端到端_截断的链输出能救回完整节点`() {
        val nodeDecoder = jsonDecoder(ChainNodesOutput.serializer())
        // 第三个节点在 definition 值中间被切断：剪掉该成员后节点本体保住（definition 落默认值），
        // 前两个节点完整——比整链解码失败重跑（整条链的资料费再交一遍）好得多
        val truncated = """{"nodes":[""" +
            """{"nodeKey":"nk-1","title":"A","definition":"完整定义一"},""" +
            """{"nodeKey":"nk-2","title":"B","definition":"完整定义二"},""" +
            """{"nodeKey":"nk-3","title":"C","definition":"被切断"""
        val output = nodeDecoder.decode(truncated)
        assertEquals(3, output.nodes.size)
        assertEquals("A", output.nodes[0].title)
        assertEquals("完整定义二", output.nodes[1].definition)
        assertEquals("C", output.nodes[2].title)
        assertEquals("", output.nodes[2].definition)
    }

    // ==================== 未知枚举值兜底 ====================
    // 模型会把章节类型（CONTRAST 等）串进块 type、或写出未定义的题型。
    // coerceInputValues 只对声明了默认值的字段生效（未知值 → 默认值），
    // 没有默认值时一个未知值就炸掉上万 token 的整章正文——2026-09-07 线上故障。

    @Test
    fun `未知块类型降级为 STORY 而不是报废整章`() {
        val chapterDecoder = jsonDecoder(ChapterOutput.serializer())
        val json = """
            {"title":"继承法第一章","blocks":[
              {"type":"STORY","text":"一个孩子出生了","knowledgeNodeTitles":["自然人的权利能力"]},
              {"type":"CONTRAST","text":" owning 一套房和卖掉它不一样","knowledgeNodeTitles":["所有权与处分权"]}
            ]}
        """.trimIndent()
        val output = chapterDecoder.decode(json)
        assertEquals(2, output.blocks.size)
        assertEquals(BlockType.STORY, output.blocks[0].type)
        // 线上报错现场：blocks[20].type = "CONTRAST"——修复后降级为普通正文块
        assertEquals(BlockType.STORY, output.blocks[1].type)
        assertEquals(" owning 一套房和卖掉它不一样", output.blocks[1].text)
    }

    @Test
    fun `未知题型降级为 RECALL 而不是报废整组自测题`() {
        val quizDecoder = jsonDecoder(QuizSetOutput.serializer())
        val json = """
            {"questions":[
              {"question":" q1","type":"MULTI_CHOICE","answer":"a","explanation":"e"},
              {"question":" q2","type":"RECALL","answer":"a2","explanation":"e2"}
            ]}
        """.trimIndent()
        val output = quizDecoder.decode(json)
        assertEquals(2, output.questions.size)
        assertEquals(QuizType.RECALL, output.questions[0].type)
        assertEquals(QuizType.RECALL, output.questions[1].type)
    }
}
