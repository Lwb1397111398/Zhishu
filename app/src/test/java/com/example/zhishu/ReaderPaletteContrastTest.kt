package com.example.zhishu

import androidx.compose.ui.graphics.Color
import com.example.zhishu.data.prefs.ReaderPaper
import com.example.zhishu.ui.screens.readerPalette
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.pow

/**
 * 阅读器纸面配色的可读性守卫。
 *
 * 「排版丑」里最隐形的一类是低对比度：纯白纸面上卡片容器 0xFFF3F0EA 贴着
 * 0xFFFFFFFF 背景（1.03:1）几乎看不出有卡，页码用 0.7 alpha 的次要色在米白上
 * 只有 ~2.2:1——用户说不出哪不对，只觉得「糊」。这类问题肉眼看截图会疲劳、
 * 会自适应放过，用 WCAG 相对亮度比一算就藏不住。
 *
 * 正文对背景要求 4.5:1（AA 常规文本）；卡片文字同理。分隔性的次要色放宽到 3:1
 * （WCAG 对大号/装饰文本的下限），但页码不该再半透明。
 */
class ReaderPaletteContrastTest {

    private fun luminance(c: Color): Double {
        fun ch(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun allPapers() = ReaderPaper.entries.map { it to readerPalette(it) }

    @Test
    fun bodyText_clearsAA_onEveryPaper() {
        for ((paper, p) in allPapers()) {
            assertTrue("$paper 正文对比度不足", ratio(p.text, p.background) >= 4.5)
            assertTrue("$paper 卡片正文对比度不足", ratio(p.cardContent, p.cardContainer) >= 4.5)
        }
    }

    @Test
    fun cardToneBody_clearsAA_onEveryPaper() {
        for ((paper, p) in allPapers()) {
            assertTrue("$paper 讲解卡", ratio(p.explanation.content, p.explanation.container) >= 4.5)
            assertTrue("$paper 事实卡", ratio(p.fact.content, p.fact.container) >= 4.5)
            assertTrue("$paper 类比卡", ratio(p.analogy.content, p.analogy.container) >= 4.5)
            assertTrue("$paper 虚构卡", ratio(p.fiction.content, p.fiction.container) >= 4.5)
        }
    }

    @Test
    fun secondaryText_clearsLargeTextThreshold_onEveryPaper() {
        // 页码 / 章序这类次要信息：3:1 是底线（WCAG 大文本下限），以前页码 0.7 alpha 掉到 ~2.2
        for ((paper, p) in allPapers()) {
            assertTrue("$paper 次要色(页码)对比度不足", ratio(p.secondary, p.background) >= 3.0)
        }
    }

    @Test
    fun chipText_clearsAA_onEveryPaper() {
        for ((paper, p) in allPapers()) {
            assertTrue("$paper chip 文字", ratio(p.chipContent, p.chips) >= 4.5)
        }
    }

    /**
     * 卡片要和纸面区分开。历史上四种卡共用一个 ≈背景色的容器（WHITE 纸 1.03:1）、
     * 又没有边框，于是「卡」在页面上根本不成其为卡。
     *
     * 现在的分隔是三层，各测各的机制，不逼淡色纸卡去凑容器亮度：
     * ① 边框不透明（[ReaderPalette.cardBorder] 有实际 alpha）；
     * ② 左侧色条对自己卡片容器可见（≥3:1）——四类卡片靠这条 rule 一眼分类；
     * ③ 夜间纸面容器比背景更亮/更暗一档（≥1.12:1），保证暗底上的卡不糊进底。
     */
    @Test
    fun cards_areDelimitedOnEveryPaper() {
        for ((paper, p) in allPapers()) {
            assertTrue("$paper 卡片边框不可见（alpha 过低）", p.cardBorder.alpha > 0.05f)
            val tones = listOf(p.explanation, p.fact, p.analogy, p.fiction)
            tones.forEachIndexed { i, t ->
                assertTrue(
                    "$paper 第 $i 类卡片左色条对自身容器不可见",
                    ratio(t.rule, t.container) >= 3.0,
                )
            }
        }
        // 夜间：卡片容器须与背景拉开一档，暗底上尤其要看得见卡的存在
        val night = readerPalette(ReaderPaper.NIGHT)
        assertTrue(
            "夜间卡片容器几乎贴背景色",
            ratio(night.cardContainer, night.background) >= 1.12,
        )
    }
}
