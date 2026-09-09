package com.example.zhishu

import androidx.compose.ui.unit.Density
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.ui.screens.AttachmentHeights
import com.example.zhishu.ui.screens.PageMeasurer
import com.example.zhishu.ui.screens.ReaderTypography
import com.example.zhishu.ui.screens.estimateFlowRowHeightPx
import com.example.zhishu.ui.screens.buildReaderPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页分页引擎的纯逻辑测试。
 *
 * 假测量器：高度 = 字符数 × 10px；Density(1f, 1f) 让 1dp = 1px，
 * 于是 ReaderLayout 的块间距/段间距直接以 dp 数值进像素域
 * （blockGap=16、paraGap=14、keyPointGap=20）。测的是切页规则本身：
 * 段落可跨页、卡片整块不拆、超页高独占且标记 overflow、附件高度计入、章首头占第一页。
 *
 * 注意 STORY 段落在切页时已加首行缩进（两个全角空格），所以一段 N 字的实际测量
 * 长度是 N+2——下面每个用例的高度都按这个口径算。
 */
class ReaderPaginationTest {

    private val density = Density(1f, 1f)

    /** 认样式但按字符数估高的假测量器（1 字 = 10px），样式由 typography 提供但不参与高度 */
    private fun fakeMeasurer() = PageMeasurer(
        measure = { text, _ -> text.length * 10 },
        density = density,
        typography = ReaderTypography(bodyFontSizeSp = 17, serif = true),
    )

    private fun block(id: String, text: String, type: BlockType) =
        ChapterBlock(id = id, chapterId = "c", order = 0, text = text, blockType = type)

    private fun story(id: String, text: String) = block(id, text, BlockType.STORY)
    private fun card(id: String, text: String) = block(id, text, BlockType.EXPLANATION)
    private fun keyPoint(id: String, text: String) = block(id, text, BlockType.KEY_POINT)

    /** 卡片高度（假测量器口径）：label(40) + labelGap(6) + 正文 + cardPadding(14×2) + 边框(1) */
    private fun cardHeight(text: String) = 40 + 6 + text.length * 10 + 28 + 1

    @Test
    fun smallChapter_staysOnOnePage() {
        val paging = buildReaderPages(
            visibleBlocks = listOf(story("a", "字".repeat(50))),
            measurer = fakeMeasurer(),
            pageHeightPx = 1000,
        )
        assertEquals(1, paging.pages.size)
        assertEquals(1, paging.pages[0].size)
        assertTrue(paging.pages[0][0].firstOfBlock && paging.pages[0][0].lastOfBlock)
    }

    @Test
    fun storyParagraph_splitAcrossPages() {
        // 每段缩进后 42 字 = 420px，页高 500：第一段放下，第二段段间 gap=14 也放不下 → 新页
        val text = "一".repeat(40) + "\n" + "二".repeat(40)
        val paging = buildReaderPages(
            visibleBlocks = listOf(story("a", text)),
            measurer = fakeMeasurer(),
            pageHeightPx = 500,
        )
        val pages = paging.pages
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].size)
        assertEquals(1, pages[1].size)
        assertTrue(pages[0][0].firstOfBlock)
        assertFalse(pages[1][0].firstOfBlock) // 第二段是同块的延续段
        assertTrue(pages[1][0].lastOfBlock)
    }

    @Test
    fun storySegment_carriesIndentIntoMeasuredText() {
        // 渲染与测量读同一份 segment.text：它必须已含首行缩进，否则量到 ≠ 画到
        val paging = buildReaderPages(
            visibleBlocks = listOf(story("a", "正文")),
            measurer = fakeMeasurer(),
            pageHeightPx = 1000,
        )
        val seg = paging.pages.single().single()
        assertEquals("　　正文", seg.text)
    }

    @Test
    fun card_movesWholeToNextPage_neverSplit() {
        val body = "讲".repeat(30)
        val paging = buildReaderPages(
            visibleBlocks = listOf(story("a", "正".repeat(45)), card("b", body)),
            measurer = fakeMeasurer(),
            pageHeightPx = 800,
        )
        val pages = paging.pages
        // story 470px；card 375px → 470+16+375=861 > 800，卡片整体挪到下一页
        assertEquals(2, pages.size)
        assertEquals("a", pages[0][0].block.id)
        assertEquals("b", pages[1][0].block.id)
        assertEquals(body, pages[1][0].text) // 卡片文本保持整块
    }

    @Test
    fun oversizedCard_getsOwnPage_andMarkedOverflow() {
        val big = "长".repeat(200)
        val paging = buildReaderPages(
            visibleBlocks = listOf(card("big", big), story("after", "后".repeat(30))),
            measurer = fakeMeasurer(),
            pageHeightPx = 1000,
        )
        assertTrue(paging.pages.size >= 2)
        assertEquals("big", paging.pages[0][0].block.id) // 超页高卡片独占第一页
        assertEquals("after", paging.pages[1][0].block.id)
        assertTrue("overflow 页必须标出来，渲染层据此开兜底滚动", 0 in paging.overflowPages)
    }

    @Test
    fun attachmentHeight_pushesBlockToOwnPage() {
        // 一个「刚好一页」的块 + 附件高度：以前附件不测量 → 页底裁切。
        // 现在把附件高度累加到该块最后一段，块整体被顶到下一页。
        val withAttach = AttachmentHeights(mapOf("b" to 200))
        val withoutAttach = AttachmentHeights(emptyMap())
        val blocks = listOf(story("a", "甲".repeat(48)), story("b", "乙".repeat(48)))
        // 每段缩进 50 字 = 500px；页高 1100：
        // 不带附件 500+16+500=1016 ≤ 1100 同页；带 200px 附件 1216 > 1100 → b 换页。
        // 对照证明附件高度真的进了分页预算（以前它从不被测量，b 的附件就被裁掉）。
        val roomy = 1100
        val packedPages = buildReaderPages(blocks, fakeMeasurer(), roomy, attachments = withoutAttach).pages
        val attachPages = buildReaderPages(blocks, fakeMeasurer(), roomy, attachments = withAttach).pages
        assertEquals("无附件：两块同页", 1, packedPages.size)
        assertEquals("带 200px 附件：b 块被顶到第二页", 2, attachPages.size)
    }

    @Test
    fun headerHeight_shrinksFirstPage() {
        val paging = buildReaderPages(
            visibleBlocks = listOf(story("a", "章".repeat(80))), // 缩进 820px > 1000-300
            measurer = fakeMeasurer(),
            pageHeightPx = 1000,
            headerHeightPx = 300,
        )
        assertEquals(2, paging.pages.size)
        assertTrue(paging.pages[0].isEmpty()) // 第一页只剩章首头（标题页），header 渲染在其上
        assertEquals("a", paging.pages[1][0].block.id)
    }

    @Test
    fun keyPoint_and_story_gapsBetweenBlocks() {
        // a=320, b(keypoint)=300, c=320；a→b 间距 keyPointGap=20，b→c 间距 blockGap=16
        // 320+20+300=640 ≤ 700 同页；再放 c：640+16+320=976 > 700 → c 换页
        val paging = buildReaderPages(
            visibleBlocks = listOf(
                story("a", "一".repeat(30)),
                keyPoint("b", "结".repeat(30)),
                story("c", "二".repeat(30)),
            ),
            measurer = fakeMeasurer(),
            pageHeightPx = 700,
        )
        val pages = paging.pages
        assertEquals(2, pages.size)
        assertEquals(2, pages[0].size)
        assertEquals(1, pages[1].size)
    }

    @Test
    fun keyPoint_notIndented() {
        // KEY_POINT 有左色条区分，不再首行缩进——测量与渲染都用原文
        val paging = buildReaderPages(
            visibleBlocks = listOf(keyPoint("b", "结论")),
            measurer = fakeMeasurer(),
            pageHeightPx = 1000,
        )
        assertEquals("结论", paging.pages.single().single().text)
    }

    @Test
    fun emptyInput_returnsNoPages() {
        val paging = buildReaderPages(
            visibleBlocks = emptyList(),
            measurer = fakeMeasurer(),
            pageHeightPx = 1000,
        )
        assertTrue(paging.pages.isEmpty())
        assertTrue(paging.overflowPages.isEmpty())
    }

    @Test
    fun estimateFlowRowHeight_wrapsIntoRows() {
        // 容器 100，条目 60/60/10，行高 34，横向 gap 6，纵向 gap 6：
        // 行1=60；60+6+60=126>100 → 行2=60；60+6+10=76≤100 → 行2=70
        // 共 2 行 = 34*2 + 6 = 74
        val h = estimateFlowRowHeightPx(
            itemWidthsPx = listOf(60, 60, 10),
            rowHeightPx = 34,
            verticalGapPx = 6,
            horizontalGapPx = 6,
            containerWidthPx = 100,
        )
        assertEquals(74, h)
        assertEquals(0, estimateFlowRowHeightPx(emptyList(), 34, 6, 6, 100))
    }

    @Test
    fun cardHeight_matchesMeasurer() {
        // 钉死卡片高度构成：改 ReaderLayout.cardPadding/cardLabelGap 时这里同步提醒
        val m = fakeMeasurer()
        val body = "讲".repeat(30)
        assertEquals(cardHeight(body), m.card(body))
        assertFalse(m.story("x") == 0)
    }
}
