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

    companion object {
        const val DEFAULT_SPACED_REVIEW = false
        private const val PREFS_NAME = "zhishu_learning_prefs"
        private const val KEY_SPACED_REVIEW = "spaced_review_enabled"
    }
}
