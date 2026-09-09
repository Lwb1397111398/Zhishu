package com.example.zhishu.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 学习方式偏好。
 *
 * 目前只有一项：间隔复习是否开启，**默认关闭**。
 * 这个 App 的主线是「像读小说一样一路读下去」，复习和测验是可选辅助——
 * 默认开启会让每章结束都变成一个待办任务，把阅读节奏打断。
 *
 * 关闭时：章节发布后不再自动建 [com.example.zhishu.domain.model.ReviewItem]，
 * 首页也不显示「今日复习」。已有的复习数据一律保留，随时打开就能接着用。
 */
class LearningPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _spacedReviewEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_SPACED_REVIEW, DEFAULT_SPACED_REVIEW))

    val spacedReviewEnabled: StateFlow<Boolean> = _spacedReviewEnabled.asStateFlow()

    fun setSpacedReviewEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPACED_REVIEW, enabled).apply()
        _spacedReviewEnabled.value = enabled
    }

    // --- 阅读器偏好（小说模式）。三项都记住，下次打开还是用户自己的习惯 ---

    private val _readerMode = MutableStateFlow(
        ReaderMode.entries.firstOrNull {
            it.name == prefs.getString(KEY_READER_MODE, null)
        } ?: DEFAULT_READER_MODE,
    )
    val readerMode: StateFlow<ReaderMode> = _readerMode.asStateFlow()

    fun setReaderMode(mode: ReaderMode) {
        prefs.edit().putString(KEY_READER_MODE, mode.name).apply()
        _readerMode.value = mode
    }

    /** 字号档位：0 小 / 1 标准 / 2 大 / 3 特大 */
    private val _readerFontSize = MutableStateFlow(
        prefs.getInt(KEY_READER_FONT, DEFAULT_FONT_INDEX).coerceIn(0, 3),
    )
    val readerFontSize: StateFlow<Int> = _readerFontSize.asStateFlow()

    fun setReaderFontSize(index: Int) {
        val v = index.coerceIn(0, 3)
        prefs.edit().putInt(KEY_READER_FONT, v).apply()
        _readerFontSize.value = v
    }

    private val _readerPaper = MutableStateFlow(
        ReaderPaper.entries.firstOrNull {
            it.name == prefs.getString(KEY_READER_PAPER, null)
        } ?: DEFAULT_READER_PAPER,
    )
    val readerPaper: StateFlow<ReaderPaper> = _readerPaper.asStateFlow()

    fun setReaderPaper(paper: ReaderPaper) {
        prefs.edit().putString(KEY_READER_PAPER, paper.name).apply()
        _readerPaper.value = paper
    }

    /**
     * 正文字体族：衬线（宋体，默认——「像一本书」）或黑体。
     *
     * 中文阅读 App 正文默认用宋体/衬线，系统黑体是最不像书的一处。
     * 这里不打包字体文件（体积/授权/OEM 回退都复杂），走系统 FontFamily.Serif。
     */
    private val _readerSerif = MutableStateFlow(
        prefs.getBoolean(KEY_READER_SERIF, DEFAULT_READER_SERIF),
    )
    val readerSerif: StateFlow<Boolean> = _readerSerif.asStateFlow()

    fun setReaderSerif(serif: Boolean) {
        prefs.edit().putBoolean(KEY_READER_SERIF, serif).apply()
        _readerSerif.value = serif
    }

    companion object {
        const val DEFAULT_SPACED_REVIEW = false
        val DEFAULT_READER_MODE = ReaderMode.PAGER
        const val DEFAULT_FONT_INDEX = 1
        val DEFAULT_READER_PAPER = ReaderPaper.CREAM
        const val DEFAULT_READER_SERIF = true
        private const val PREFS_NAME = "zhishu_learning_prefs"
        private const val KEY_SPACED_REVIEW = "spaced_review_enabled"
        private const val KEY_READER_MODE = "reader_mode"
        private const val KEY_READER_FONT = "reader_font_size"
        private const val KEY_READER_PAPER = "reader_paper"
        private const val KEY_READER_SERIF = "reader_serif"
    }
}

/** 阅读方式：左右翻页（默认，像小说软件）或上下滚动 */
enum class ReaderMode { PAGER, SCROLL }

/** 阅读纸色：米黄纸感（默认）/ 纯白 / 夜间 */
enum class ReaderPaper { CREAM, WHITE, NIGHT }
