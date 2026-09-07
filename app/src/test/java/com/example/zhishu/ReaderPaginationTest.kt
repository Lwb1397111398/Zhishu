package com.example.zhishu

import androidx.compose.ui.unit.Density
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.ui.screens.PageMeasurer
import com.example.zhishu.ui.screens.buildReaderPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页分页引擎的纯逻辑测试。
 *
 * 假测量器：高度 = 字数 × 10px；Density(1f, 1f) 让 1dp = 1px，
 * 所有页高/间距直接按"像素"算，测的是切页规则本身：
 * 正文段落可跨页、卡片整块不拆、超页高独占、章首头占用第一页空间。
 */
class ReaderPaginationTest {

    private val density = Density(1f, 1f)

    private fun fakeMeasurer() = PageMeasurer(
        measure = { text, _ -> text.length * 10 },
        density = density,
    )

    private fun block(id: String, text: String, type: BlockType) =
        ChapterBlock(id = id, chapterId = "c", order = 0, text = text, blockType = type)

    private fun story(id: String, text: String) = block(id, text, BlockType.STORY)
    private fun card(id: String, text: String) = block(id, text, BlockType.EXPLANATION)
    private fun keyPoint(id: String, text: String) = block(id, text, BlockType.KEY_POINT)

    /** 卡片高度（按假测量器口径）：label("知识讲解"40px) + 4dp + 正文 + 24dp padding */
    private fun cardHeight(text: String) = 40 + 4 + text.length * 10 + 24

    @Test
    fun smallChapter_staysOnOnePage() {
        val pages = buildReaderPages(
            visibleBlocks = listOf(story("a", "字".repeat(50))),
            measurer = fakeMeasurer(),
            fontSp = 17,
            pageHeightPx = 1000,
            gapPx = 16,
        )
        assertEquals(1, pages.size)
        assertEquals(1, pages[0].size)
        assertTrue(pages[0][0].firstOfBlock && pages[0][0].lastOfBlock)
    }

    @Test
    fun storyParagraphs_splitAcrossPages() {
        // 每段 400px，页高 500：第一段放下，第二段同块 gap=0 也放不下 → 新页
        val text = "一".repeat(40) + "\n" + "二".repeat(40)
        val pages = buildReaderPages(
            visibleBlocks = listOf(story("a", text)),
            measurer = fakeMeasurer(),
            fontSp = 17,
            pageHeightPx = 500,
            gapPx = 16,
        )
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].size)
        assertEquals(1, pages[1].size)
        assertTrue(pages[0][0].firstOfBlock)
        assertFalse(pages[1][0].firstOfBlock) // 第二段是同块的延续段
        assertTrue(pages[1][0].lastOfBlock)
    }

    @Test
    fun card_movesWholeToNextPage_neverSplit() {
        val body = "讲".repeat(30)
        val pages = buildReaderPages(
            visibleBlocks = listOf(story("a", "正".repeat(45)), card("b", body)),
            measurer = fakeMeasurer(),
            fontSp = 17,
            pageHeightPx = 800,
            gapPx = 16,
        )
        // story 450px；card 368px → 450+16+368=834 > 800，卡片整体挪到下一页
        assertEquals(2, pages.size)
        assertEquals("a", pages[0][0].block.id)
        assertEquals("b", pages[1][0].block.id)
        assertEquals(body, pages[1][0].text) // 卡片文本保持整块
    }

    @Test
    fun oversizedCard_getsOwnPage() {
        val pages = buildReaderPages(
            visibleBlocks = listOf(card("big", "长".repeat(200)), story("after", "后".repeat(30))),
            measurer = fakeMeasurer(),
            fontSp = 17,
            pageHeightPx = 1000,
            gapPx = 16,
        )
        assertTrue(pages.size >= 2)
        assertEquals("big", pages[0][0].block.id) // 超页高卡片独占第一页
        assertEquals("after", pages[1][0].block.id)
    }

    @Test
    fun headerHeight_shrinksFirstPage() {
        val pages = buildReaderPages(
            visibleBlocks = listOf(story("a", "章".repeat(80))), // 800px > 1000-300
            measurer = fakeMeasurer(),
            fontSp = 17,
            pageHeightPx = 1000,
            gapPx = 16,
            headerHeightPx = 300,
        )
        assertEquals(2, pages.size)
        assertTrue(pages[0].isEmpty()) // 第一页只剩章首头（标题页）
        assertEquals("a", pages[1][0].block.id)
    }

    @Test
    fun keyPoint_and_story_gapsBetweenBlocks() {
        // 两块各自 300px，块间 gap 16 → 300+16+300=616 ≤ 700，同页；再放第三块就该换页
        val pages = buildReaderPages(
            visibleBlocks = listOf(
                story("a", "一".repeat(30)),
                keyPoint("b", "结".repeat(30)),
                story("c", "二".repeat(30)),
            ),
            measurer = fakeMeasurer(),
            fontSp = 17,
            pageHeightPx = 700,
            gapPx = 16,
        )
        assertEquals(2, pages.size)
        assertEquals(2, pages[0].size)
        assertEquals(1, pages[1].size)
    }

    @Test
    fun emptyInput_returnsNoPages() {
        val pages = buildReaderPages(
            visibleBlocks = emptyList(),
            measurer = fakeMeasurer(),
            fontSp = 17,
            pageHeightPx = 1000,
            gapPx = 16,
        )
        assertTrue(pages.isEmpty())
    }
}
