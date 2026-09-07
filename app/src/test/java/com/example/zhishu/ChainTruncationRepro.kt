package com.example.zhishu

import com.example.zhishu.ai.jsonDecoder
import com.example.zhishu.ai.outputs.ChainNodesOutput
import com.example.zhishu.ai.outputs.OutputValidators
import com.example.zhishu.ai.repairTruncatedJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「演进链总是生成失败」的回归测试。
 *
 * 根因：截断修复的扫描状态机没有处理空容器（`[]` / `{}`）。真实单链输出里空数组
 * 几乎必然出现——无资料模式 `sourceRefs` 恒为 `[]`，`examples`/`confusions` 也常空——
 * 于是任何一次截断都在第一个 `[]` 处被判成「坏 JSON」返回 null，本地剪裁形同不存在，
 * 全部落到按 token 计费的模型修复上；而修复请求要求重新输出完整 JSON，
 * 同样撞上同一个上限被截断，整条链必然失败。
 */
class ChainTruncationRepro {

    /** 一个完整节点，字段顺序与 prompt 一致；无资料模式 sourceRefs 为空数组 */
    private fun node(key: String, def: String) = """{"nodeKey":"$key","title":"标题$key",""" +
        """"definition":"$def","arisesFrom":"来处","leadsTo":"去处",""" +
        """"prerequisites":["前置一"],"related":["相关一"],"confusions":["易混一"],""" +
        """"examples":["例子一"],"sourceRefs":[],"parentTitle":null,"depth":1}"""

    @Test
    fun `空数组不再让截断修复失效`() {
        val truncated = """{"nodes":[""" + node("nk-1", "完整定义一") + "," +
            """{"nodeKey":"nk-2","title":"标题nk-2","definition":"被切断的定"""
        assertNotNull("空数组不该让本地修复失效", repairTruncatedJson(truncated))
    }

    @Test
    fun `最小复现_单个空数组`() {
        assertEquals(
            """{"nodes":[{"sourceRefs":[]}]}""",
            repairTruncatedJson("""{"nodes":[{"sourceRefs":[],"title":"A"""),
        )
    }

    @Test
    fun `空对象同样能修复`() {
        assertEquals(
            """{"a":{},"b":[{}]}""",
            repairTruncatedJson("""{"a":{},"b":[{}],"c":"被切"""),
        )
    }

    @Test
    fun `端到端_截断的真实链输出救回已完成节点`() {
        val decoder = jsonDecoder(ChainNodesOutput.serializer())
        val truncated = """{"nodes":[""" + node("nk-1", "完整定义一") + "," +
            node("nk-2", "完整定义二") + "," +
            """{"nodeKey":"nk-3","title":"标题nk-3","definition":"被切"""
        val output = decoder.decode(truncated)
        // 前两个节点完整救回；第三个只剩到 title 的残壳（definition 被剪掉）
        assertEquals(3, output.nodes.size)
        assertEquals("完整定义一", output.nodes[0].definition)
        assertEquals(listOf("前置一"), output.nodes[1].prerequisites)
        assertEquals("", output.nodes[2].definition)
        assertTrue(output.nodes.take(2).all { it.sourceRefs.isEmpty() })

        // 校验层把残壳丢掉：留着它会被标成 COMPLETED，
        // 用户得到一个永远不再重试的空节点
        val (valid, _) = OutputValidators.validateChainNodes(
            output,
            setOf("nk-1", "nk-2", "nk-3"),
            setOf("标题nk-1", "标题nk-2", "标题nk-3"),
        ).getOrThrow()
        assertEquals(listOf("nk-1", "nk-2"), valid.map { it.nodeKey })
    }

    @Test
    fun `title 无默认值时不再拖垮整个 payload`() {
        val decoder = jsonDecoder(ChainNodesOutput.serializer())
        // 截断落在 title 键名中间：修复后该节点只剩 nodeKey。
        // title 曾是必填字段，会让整个 payload 抛 MissingFieldException，
        // 连前面完整的节点一起陪葬
        val truncated = """{"nodes":[{"nodeKey":"nk-1","title":"A","depth":1},{"nodeKey":"nk-2","ti"""
        val output = decoder.decode(truncated)
        assertEquals(2, output.nodes.size)
        assertEquals("A", output.nodes[0].title)
        assertEquals("", output.nodes[1].title)
    }

    @Test
    fun `完整 JSON 仍然返回 null 不做无谓修复`() {
        assertEquals(null, repairTruncatedJson("""{"nodes":[{"sourceRefs":[],"title":"A"}]}"""))
    }
}
