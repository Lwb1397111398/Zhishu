package com.example.zhishu

import com.example.zhishu.ai.pipeline.ChapterPhase
import com.example.zhishu.domain.model.ApiProfile
import com.example.zhishu.ui.viewmodel.GenerationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三个用户反馈的回归测试：
 * 1. 最大 Token 默认值要足够大
 * 2. 进度要能算出准确百分比
 * 3. 403 等鉴权错误要翻译成可行动的中文，且失败横幅能识别为「需要去配置」
 */
class UserFeedbackFixesTest {

    // ---------- 问题1：最大 Token 默认值 ----------

    /** 4096 会让两三千字的正文必然被截断，默认必须给足 */
    @Test
    fun maxTokens_defaultsToFiftyThousand() {
        assertEquals(50_000, ApiProfile.DEFAULT_MAX_TOKENS)
        assertEquals(50_000, ApiProfile().maxTokens)
    }

    /** 上限必须容纳默认值，否则保存时会被 coerce 砍掉（原来上限是 32768） */
    @Test
    fun maxTokens_rangeContainsDefault() {
        assertTrue(ApiProfile.MAX_MAX_TOKENS >= ApiProfile.DEFAULT_MAX_TOKENS)
        assertEquals(
            ApiProfile.DEFAULT_MAX_TOKENS,
            ApiProfile.DEFAULT_MAX_TOKENS
                .coerceIn(ApiProfile.MIN_MAX_TOKENS, ApiProfile.MAX_MAX_TOKENS),
        )
    }

    // ---------- 问题2：百分比进度 ----------

    @Test
    fun progress_percentReflectsCompletedUnits() {
        val state = GenerationUiState(running = true, stageText = "正在写正文", completed = 3, total = 12)
        assertEquals(25, state.percent)
        assertTrue(state.determinate)
    }

    /** 总量未知时不能编造百分比，UI 要退回不确定进度条 */
    @Test
    fun progress_unknownTotal_isIndeterminate() {
        val state = GenerationUiState(running = true, completed = 0, total = 0)
        assertFalse(state.determinate)
        assertEquals(0, state.percent)
    }

    /** 排队中还没开始跑，也不能显示确定进度 */
    @Test
    fun progress_enqueued_isNotDeterminate() {
        assertFalse(GenerationUiState(enqueued = true, total = 12).determinate)
    }

    @Test
    fun progress_percentIsClampedAndComplete() {
        assertEquals(100, GenerationUiState(running = true, completed = 12, total = 12).percent)
        // 上报错乱也不能超过 100
        assertEquals(100, GenerationUiState(running = true, completed = 99, total = 12).percent)
    }

    /** 一章拆成正文/核验/出题三步，批量总量按此计算 */
    @Test
    fun chapterPhase_hasThreeUnits() {
        assertEquals(3, ChapterPhase.UNITS_PER_CHAPTER)
        assertEquals(
            listOf("正在写正文", "正在核验内容", "正在出测验题"),
            ChapterPhase.entries.map { it.label },
        )
    }

    /** 5 章批量生成 = 15 个工作单元，走完第 2 章正文时是 4/15 */
    @Test
    fun progress_batchChaptersPercent() {
        val total = 5 * ChapterPhase.UNITS_PER_CHAPTER
        val state = GenerationUiState(running = true, completed = 4, total = total)
        assertEquals(15, state.total)
        assertEquals(26, state.percent)
    }
}
