package com.example.zhishu

import com.example.zhishu.ai.pipeline.StoryQualityChecks
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.IssueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 程序化文风预检（2026-09 文风修复）。
 *
 * 只验机器能可靠判定的硬信号与保守阈值：短章不报、误报源不报、
 * 报出来的必须是 type=style 且定位字段正确。
 */
class StoryQualityChecksTest {

    private var idSeq = 0
    private var proseSeq = 0

    private fun block(
        text: String,
        type: BlockType = BlockType.STORY,
    ): ChapterBlock = ChapterBlock(
        id = "b-${idSeq++}",
        chapterId = "ch-1",
        order = idSeq,
        text = text,
        blockType = type,
    )

    /** 生成指定长度的填充正文；每句都带全局序号，保证块间、块内都不重复 */
    private fun prose(length: Int): String {
        val tag = "S${proseSeq++}"
        return buildString {
            var i = 0
            while (this.length < length) {
                i++
                append("他沿着河岸走了一段又一段，心里反复掂量着白天那件事$tag-$i。")
                append("风把水面吹皱了，他停下来看了很久才转身离开$tag-$i。")
            }
        }
    }

    @Test
    fun shortChapter_isSkipped() {
        val blocks = listOf(block("短文，没有对话。", BlockType.EXPLANATION))
        assertTrue(StoryQualityChecks.check(blocks).isEmpty())
    }

    @Test
    fun templateEcho_isReportedOnceWithSecondBlock() {
        val phrase = "这里要修正一个容易混的地方"
        val filler = "光走过一段路之后才被眼睛接住，这件事本身值得停下来想一想，不能急着下结论。"
        val blocks = listOf(
            block(prose(500) + phrase + "，先把前面那个简化撤回来。"),
            block(prose(400) + phrase + "，这次轮到折射这一层。", BlockType.EXPLANATION),
        )
        val issues = StoryQualityChecks.check(blocks)
        val echo = issues.filter { it.message.contains(phrase) }
        assertEquals(1, echo.size)
        assertEquals("style", echo.single().type)
        assertEquals(blocks[1].id, echo.single().blockId)
    }

    @Test
    fun sentenceEcho_isReportedOnSecondOccurrenceBlock() {
        val repeated = "太阳光根本不是单一的白，而是很多颜色混在一起才显得亮。"
        val unique = "他把三棱镜支在窗台上，光斑在墙上拉开一排颜色，像有人把白天拆开了。"
        val blocks = listOf(
            block(prose(500) + repeated),
            block(prose(400) + unique + repeated, BlockType.EXPLANATION),
        )
        val issues = StoryQualityChecks.check(blocks).filter { it.message.contains("同一句话") }
        assertEquals(1, issues.size)
        assertEquals(blocks[1].id, issues.single().blockId)
    }

    @Test
    fun noDialogue_isReportedAsChapterScopedIssue() {
        val blocks = listOf(
            block(prose(500)),
            block(prose(400), BlockType.EXPLANATION),
        )
        val issues = StoryQualityChecks.check(blocks).filter { it.message.contains("对话") }
        assertEquals(1, issues.size)
        assertEquals(null, issues.single().blockId)
    }

    @Test
    fun dialoguePresent_isNotReported() {
        val blocks = listOf(
            block(prose(500) + "「你真觉得光是直着撞进眼睛的？」她问。"),
            block(prose(400), BlockType.EXPLANATION),
        )
        val issues = StoryQualityChecks.check(blocks).filter { it.message.contains("对话") }
        assertTrue(issues.isEmpty())
    }

    @Test
    fun lowStoryRatio_isReported() {
        // STORY 只占 1/3，剩下全是说理段
        val blocks = listOf(
            block(prose(400)),
            block(prose(400), BlockType.EXPLANATION),
            block(prose(400), BlockType.EXPLANATION),
        )
        val issues = StoryQualityChecks.check(blocks).filter { it.message.contains("STORY") }
        assertEquals(1, issues.size)
    }

    @Test
    fun healthyChapter_isClean() {
        // STORY 占 2/3、有对话、无复读
        val blocks = listOf(
            block(prose(500) + "「水缸里的筷子看着是折的。」他说。"),
            block(prose(200), BlockType.EXPLANATION),
            block(prose(300)),
        )
        assertTrue(StoryQualityChecks.check(blocks).isEmpty())
    }

    @Test
    fun styleTypeParsesFromModelWording() {
        assertEquals(IssueType.STYLE, IssueType.parse("style"))
        assertEquals(IssueType.STYLE, IssueType.parse("文风"))
        assertEquals(IssueType.STYLE, IssueType.parse("写法生硬，像散文"))
        // 别的类型不被新关键词误吞
        assertEquals(IssueType.MISSING, IssueType.parse("missing"))
        assertEquals(IssueType.OVERREACH, IssueType.parse("说得太绝对"))
    }
}
