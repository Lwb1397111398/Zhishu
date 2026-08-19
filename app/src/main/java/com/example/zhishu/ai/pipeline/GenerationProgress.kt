package com.example.zhishu.ai.pipeline

import kotlin.math.roundToInt

/**
 * 生成进度（问题2）。
 *
 * 原来只有一句「正在生成章节」，用户完全不知道走到哪一步、还剩多少。
 * 这里把整次任务拆成若干等价的「工作单元」，完成一个就上报一次，
 * UI 据此显示确定进度条与百分比。
 *
 * 之所以按单元数而不是按时间估算：单元边界是真实发生的事件（正文写完、核验通过、
 * 测验出好），不会出现「卡在 99% 假装还在动」这种骗人的进度条。
 */
data class GenerationProgress(
    /** 当前在做什么，直接给用户看 */
    val label: String,
    /** 已完成的工作单元数 */
    val completed: Int,
    /** 本次任务的工作单元总数 */
    val total: Int,
) {
    /** 0..100。总数未知（<=0）时返回 0，由 UI 退回不确定进度条 */
    val percent: Int
        get() = if (total <= 0) 0 else ((completed.toFloat() / total) * 100f).roundToInt().coerceIn(0, 100)

    /** 是否可以显示确定进度条 */
    val determinate: Boolean get() = total > 0
}

/**
 * 单章生成内部的子步骤。一章要写正文、过核验、再出测验，
 * 三步耗时都不短，合成一个单元的话进度条会长时间不动。
 */
enum class ChapterPhase {
    BODY,
    VERIFY,
    QUIZ,
    ;

    val label: String
        get() = when (this) {
            BODY -> "正在写正文"
            VERIFY -> "正在核验内容"
            QUIZ -> "正在出测验题"
        }

    companion object {
        /** 一章包含的工作单元数，供调用方计算总量 */
        val UNITS_PER_CHAPTER = entries.size
    }
}

/** 进度上报回调。挂起是因为 WorkManager 的 setProgress 是挂起函数。 */
typealias ProgressReporter = suspend (ChapterPhase) -> Unit

/** 默认不上报，保证既有调用方与测试无需改动 */
val NoOpProgressReporter: ProgressReporter = {}
