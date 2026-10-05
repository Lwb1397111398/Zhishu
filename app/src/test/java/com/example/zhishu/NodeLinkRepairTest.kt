package com.example.zhishu

import com.example.zhishu.ai.outputs.BlockDto
import com.example.zhishu.ai.outputs.ChapterOutput
import com.example.zhishu.ai.outputs.OutputValidators
import com.example.zhishu.domain.model.BlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `validateChapter` 知识点标题对齐修复的回归测试（2026-09 实测 bug）。
 *
 * 实测失败模式：正文写得没问题，但模型的 `knowledgeNodeTitles` 抄成了标题变体
 * （标点/空格差异）或整段留空，精确匹配全部落空——落库时正文块全都不带知识点 ID。
 * 修复是校验前的确定性对齐：变体归位 + 讲解段落文内取证。
 *
 * 注意：对齐后**全部**讲解段落仍没有关联时，校验不再拒收整章（2026-09-14 起改由
 * 流水线降级为带问题发布、标待复核），所以这里断言的是「原样放行、不硬造关联」。
 */
class NodeLinkRepairTest {

    private val knownTitles = setOf("光作为电磁波与可见光谱", "波长与颜色的对应关系")

    private fun validate(blocks: List<BlockDto>): Result<ChapterOutput> =
        OutputValidators.validateChapter(
            output = ChapterOutput(title = "第一章", blocks = blocks),
            knownNodeTitles = knownTitles,
            validChunkIds = emptySet(),
            requireSourceRefs = false,
        )

    @Test
    fun `变体标题归位后通过校验并流回真实标题`() {
        // 模型抄写差异：全角引号 + 首尾空格 + 中间标点变化
        val blocks = listOf(
            BlockDto(type = BlockType.EXPLANATION, text = "停下来分辨这段。"),
            BlockDto(
                type = BlockType.FACT,
                text = "事实段落。",
                knowledgeNodeTitles = listOf(" 「光作为电磁波与可见光谱」 ", "波长与颜色的对应关系！"),
            ),
        )
        val result = validate(blocks)
        val output = result.getOrThrow()
        // 归位后的标题必须是真实标题（runChapter 按它反解成节点 ID）
        assertEquals(
            listOf("光作为电磁波与可见光谱", "波长与颜色的对应关系"),
            output.blocks[1].knowledgeNodeTitles,
        )
    }

    @Test
    fun `讲解段落没填标题但正文里出现了真实标题时文内取证补上`() {
        // 批次 B 样本行为：部分模型从不填 knowledgeNodeTitles
        val blocks = listOf(
            BlockDto(
                type = BlockType.EXPLANATION,
                text = "这里要分辨清楚：波长与颜色的对应关系并不是任意指定的。",
            ),
        )
        val output = validate(blocks).getOrThrow()
        assertEquals(listOf("波长与颜色的对应关系"), output.blocks[0].knowledgeNodeTitles)
    }

    @Test
    fun `文内取证优先归位标题_整体不硬造`() {
        val blocks = listOf(
            BlockDto(
                type = BlockType.KEY_POINT,
                text = "光作为电磁波与可见光谱，是这一章钉住的结论。",
                knowledgeNodeTitles = listOf("凭空编的标题"),
            ),
        )
        val output = validate(blocks).getOrThrow()
        // 「凭空编的标题」保留（不静默丢弃），取证补上的真实标题排在后面
        assertEquals(
            listOf("凭空编的标题", "光作为电磁波与可见光谱"),
            output.blocks[0].knowledgeNodeTitles,
        )
    }

    @Test
    fun `完全对不上时原样放行不硬造`() {
        // 校验层不再拒收整章（拒收 = 白扔上万 token）；对不上的标题原样保留，
        // 「全未关联」的降级（带问题发布、标待复核）由流水线负责
        val blocks = listOf(
            BlockDto(
                type = BlockType.EXPLANATION,
                text = "一段与本章知识点无关的讲解。",
                knowledgeNodeTitles = listOf("不存在的知识点"),
            ),
        )
        val output = validate(blocks).getOrThrow()
        assertEquals(listOf("不存在的知识点"), output.blocks[0].knowledgeNodeTitles)
    }

    @Test
    fun `STORY 块不参与文内取证也不拦整章`() {
        // STORY 不承担讲解责任：没有有效关联也不触发兜底，更不触发拒收
        val blocks = listOf(
            BlockDto(type = BlockType.STORY, text = "波长与颜色的对应关系出现在故事里。"),
        )
        val result = validate(blocks)
        assertTrue(result.isSuccess)
        // STORY 块原样保留，没有被贴上取证标题
        assertEquals(0, result.getOrThrow().blocks[0].knowledgeNodeTitles.size)
    }

    @Test
    fun `部分讲解段落有关联时不影响其余环节`() {
        val blocks = listOf(
            BlockDto(
                type = BlockType.EXPLANATION,
                text = "无关讲解。",
                knowledgeNodeTitles = listOf("不存在的知识点"),
            ),
            BlockDto(
                type = BlockType.FACT,
                text = "有关讲解。",
                knowledgeNodeTitles = listOf("光作为电磁波与可见光谱"),
            ),
        )
        assertTrue(validate(blocks).isSuccess)
    }
}
