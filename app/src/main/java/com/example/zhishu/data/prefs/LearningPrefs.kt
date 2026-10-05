package com.example.zhishu.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 学习方式偏好。
 *
 * 两项影响行为的开关：
 * - 间隔复习（[spacedReviewEnabled]）**默认关**——主线是「像读小说一样一路读下去」，
 *   默认开会把每章结尾变成一个待办任务，打断节奏；
 * - 自动生成（[generationAutoAdvance]）**默认开**——用户要的「点一次继续就写到主线讲完」。
 *
 * 其余是阅读器外观偏好（翻页/字号/纸色/衬线），改了立即生效且记住。
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

    /**
     * 自动生成总开关（默认**开**）。
     *
     * 开启后点一次「继续」会自动跑完整条主线：逐章生成、本卷写完自动规划下一卷、
     * 梳理完自动补上 actionable 的漏知识点、待复核不阻断续卷，直到知识主干
     * 全部讲到才停。关闭则回到每步手动确认（一次一键开讲只出一章）。
     *
     * 与 [spacedReviewEnabled] 默认关相反，这里默认开——用户明确要「默认允许」。
     * 随时可在设置页关掉；关掉后已在链上的当前章写完为止，下一个 ADVANCE 起不再追加。
     */
    private val _generationAutoAdvance =
        MutableStateFlow(prefs.getBoolean(KEY_AUTO_ADVANCE, DEFAULT_AUTO_ADVANCE))

    val generationAutoAdvance: StateFlow<Boolean> = _generationAutoAdvance.asStateFlow()

    fun setGenerationAutoAdvance(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ADVANCE, enabled).apply()
        _generationAutoAdvance.value = enabled
    }

    /**
     * 核验问题自动处理开关（默认**开**）。
     *
     * 开启后核验发现的问题不再停在「等人逐条确认」：自动局部重写最多
     * 两轮（每轮仍受块数护栏约束），修不掉的「文风类」问题自动标记不用改——
     * 它们是程序自检出来的表达问题，不伤知识准确性，留在待复核清单里只会
     * 把真正要人看的事实问题淹没。事实类问题（资料不支持/矛盾/编造……）
     * 无论开关如何都保留，绝不替用户做「不用改」的决定。
     *
     * 关闭后回到旧行为：只自动重写一轮，修不掉就全部留给用户逐条处理。
     */
    private val _autoHandleIssues =
        MutableStateFlow(prefs.getBoolean(KEY_AUTO_HANDLE_ISSUES, DEFAULT_AUTO_HANDLE_ISSUES))

    val autoHandleIssues: StateFlow<Boolean> = _autoHandleIssues.asStateFlow()

    fun setAutoHandleIssues(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_HANDLE_ISSUES, enabled).apply()
        _autoHandleIssues.value = enabled
    }

    // --- 备份提醒（3.3）---

    /**
     * 上次成功导出备份的时间戳；0 表示从未备份过。
     *
     * 导出成功时由设置页写入。书架据此判断「写了东西却很久没备份」，
     * 温和地提醒一次（可关，本次运行不再弹）。
     */
    private val _lastBackupAt = MutableStateFlow(prefs.getLong(KEY_LAST_BACKUP_AT, 0L))

    val lastBackupAt: StateFlow<Long> = _lastBackupAt.asStateFlow()

    fun setLastBackupAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_BACKUP_AT, timestamp).apply()
        _lastBackupAt.value = timestamp
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

    // --- 应用自更新 ---

    /**
     * 启动时自动检查更新（默认**开**）。
     *
     * 打开后 App 启动会静默查一次 GitHub Releases（24 小时节流），
     * 有新版本才弹窗；出错一律安静，不打扰阅读。
     */
    private val _updateAutoCheck =
        MutableStateFlow(prefs.getBoolean(KEY_UPDATE_AUTO_CHECK, DEFAULT_UPDATE_AUTO_CHECK))

    val updateAutoCheck: StateFlow<Boolean> = _updateAutoCheck.asStateFlow()

    fun setUpdateAutoCheck(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_AUTO_CHECK, enabled).apply()
        _updateAutoCheck.value = enabled
    }

    /** 上一次真正联网检查更新的时间戳；0 表示从未检查过 */
    fun updateLastCheckedMs(): Long = prefs.getLong(KEY_UPDATE_LAST_CHECKED, 0L)

    fun markUpdateChecked() {
        prefs.edit().putLong(KEY_UPDATE_LAST_CHECKED, System.currentTimeMillis()).apply()
    }

    companion object {
        const val DEFAULT_SPACED_REVIEW = false
        const val DEFAULT_AUTO_ADVANCE = true
        const val DEFAULT_AUTO_HANDLE_ISSUES = true
        const val DEFAULT_UPDATE_AUTO_CHECK = true
        val DEFAULT_READER_MODE = ReaderMode.PAGER
        const val DEFAULT_FONT_INDEX = 1
        val DEFAULT_READER_PAPER = ReaderPaper.CREAM
        const val DEFAULT_READER_SERIF = true
        private const val PREFS_NAME = "zhishu_learning_prefs"
        private const val KEY_SPACED_REVIEW = "spaced_review_enabled"
        private const val KEY_AUTO_ADVANCE = "generation_auto_advance"
        private const val KEY_AUTO_HANDLE_ISSUES = "auto_handle_issues"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_READER_MODE = "reader_mode"
        private const val KEY_READER_FONT = "reader_font_size"
        private const val KEY_READER_PAPER = "reader_paper"
        private const val KEY_READER_SERIF = "reader_serif"
        private const val KEY_UPDATE_AUTO_CHECK = "update_auto_check"
        private const val KEY_UPDATE_LAST_CHECKED = "update_last_checked_ms"
    }
}

/** 阅读方式：左右翻页（默认，像小说软件）或上下滚动 */
enum class ReaderMode { PAGER, SCROLL }

/** 阅读纸色：米黄纸感（默认）/ 纯白 / 夜间 */
enum class ReaderPaper { CREAM, WHITE, NIGHT }
