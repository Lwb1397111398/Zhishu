package com.example.zhishu.ui.viewmodel

import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.zhishu.worker.GenerationWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 生成任务在 UI 上的状态（问题1）。
 *
 * 原来入队后只弹一句「已开始生成」，之后无论成功、失败还是在排队，界面都没有任何反馈——
 * 用户看到的就是「点了没反应」。最常见的真实原因是没配模型：
 * 模型返回 Auth 错误 → Worker 判定不可重试 → 静默结束 → UI 一无所知。
 *
 * 现在 UI 直接订阅 WorkManager 的 WorkInfo，把排队、运行中、失败原因、重试等待都显示出来。
 */
data class GenerationUiState(
    val running: Boolean = false,
    val enqueued: Boolean = false,
    /** Worker 上报的当前阶段，如「正在生成第 3 章」 */
    val stageText: String = "",
    /** 失败原因。非空时 UI 要显著提示并给出重试入口 */
    val error: String? = null,
    /** 正在等待网络/退避重试 */
    val waitingRetry: Boolean = false,
    /** 已完成的工作单元数（问题2） */
    val completed: Int = 0,
    /** 本次任务的工作单元总数。为 0 表示总量未知，UI 退回不确定进度条（问题2） */
    val total: Int = 0,
) {
    val busy: Boolean get() = running || enqueued
    val hasError: Boolean get() = !error.isNullOrBlank()

    /** 能否显示确定进度条与百分比 */
    val determinate: Boolean get() = running && total > 0

    /** 0..100 的真实完成度。按已完成的工作单元算，不做时间估算（问题2） */
    val percent: Int
        get() = if (total <= 0) {
            0
        } else {
            ((completed.toFloat() / total) * 100f).toInt().coerceIn(0, 100)
        }

    /** 给用户看的一句话状态 */
    val message: String
        get() = when {
            hasError -> error.orEmpty()
            running -> stageText.ifBlank { "正在生成…" }
            waitingRetry -> "网络不可用或服务繁忙，正在等待重试…"
            enqueued -> "已排队，等待开始…"
            else -> ""
        }
}

/** 把某个项目的唯一生成任务链映射成 UI 状态 */
internal fun WorkManager.observeGeneration(projectId: String): Flow<GenerationUiState> =
    getWorkInfosForUniqueWorkFlow(GenerationWorker.uniqueWorkName(projectId))
        .map { infos -> infos.toGenerationState() }

/**
 * 一条唯一工作链里可能有多个 WorkInfo（连续点了几次）。
 * 取「最需要被用户看见」的那一个：运行中 > 排队 > 失败 > 其他。
 */
internal fun List<WorkInfo>.toGenerationState(): GenerationUiState {
    if (isEmpty()) return GenerationUiState()
    val running = firstOrNull { it.state == WorkInfo.State.RUNNING }
    if (running != null) {
        return GenerationUiState(
            running = true,
            stageText = running.progress.getString(GenerationWorker.KEY_PROGRESS).orEmpty(),
            completed = running.progress.getInt(GenerationWorker.KEY_PROGRESS_COMPLETED, 0),
            total = running.progress.getInt(GenerationWorker.KEY_PROGRESS_TOTAL, 0),
        )
    }
    val enqueued = firstOrNull { it.state == WorkInfo.State.ENQUEUED }
    if (enqueued != null) {
        // runAttemptCount > 0 说明已经跑过并退避重试中，和「刚排队」要区分开
        return GenerationUiState(
            enqueued = true,
            waitingRetry = enqueued.runAttemptCount > 0,
        )
    }
    val failed = lastOrNull { it.state == WorkInfo.State.FAILED }
    if (failed != null) {
        return GenerationUiState(
            error = failed.outputData.getString(GenerationWorker.KEY_ERROR)
                ?: "生成失败，请检查网络与模型配置后重试",
        )
    }
    return GenerationUiState()
}
