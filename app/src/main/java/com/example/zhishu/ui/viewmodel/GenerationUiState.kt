package com.example.zhishu.ui.viewmodel

import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.zhishu.worker.GenerationOutcome
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
    /** 已经自动重试过的次数（退避等待期间显示给用户，说明任务没死） */
    val retryCount: Int = 0,
    /** 已完成的工作单元数（问题2） */
    val completed: Int = 0,
    /** 本次任务的工作单元总数。为 0 表示总量未知，UI 退回不确定进度条（问题2） */
    val total: Int = 0,
    /** 会话结局。取消与部分完成都要如实显示，不能显示成 100%（第二阶段） */
    val outcome: GenerationOutcome? = null,
) {
    val busy: Boolean get() = running || enqueued
    val hasError: Boolean get() = !error.isNullOrBlank()

    /** 取消后不显示进度条，也不显示百分比——那会让人以为还在跑 */
    val cancelled: Boolean get() = outcome == GenerationOutcome.CANCELLED

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
            // 退避重试期间必须说清是在等，不能伪装成还在调模型
            waitingRetry -> if (retryCount > 0) {
                "服务繁忙或网络不可用，正在等待第 $retryCount 次重试…"
            } else {
                "服务繁忙或网络不可用，正在等待重试…"
            }
            enqueued -> "已排队，等待开始…"
            cancelled -> "已取消生成。已经写好的章节不受影响。"
            else -> ""
        }
}

/** 把某个项目的唯一生成任务链映射成 UI 状态 */
internal fun WorkManager.observeGeneration(projectId: String): Flow<GenerationUiState> =
    getWorkInfosForUniqueWorkFlow(GenerationWorker.uniqueWorkName(projectId))
        .map { infos -> infos.toGenerationState() }

/**
 * 一条唯一工作链上有多个 WorkInfo：批量生成时一章一个，还可能混着上一次会话的残留。
 *
 * 所以先按会话 tag 收窄到「当前那一次」——否则上一次的 FAILED 会一直压在
 * 新会话头上，用户点了重新生成，界面却还挂着上次的错误。
 * 判断顺序：运行中 > 排队/阻塞 > 失败 > 取消。
 */
internal fun List<WorkInfo>.toGenerationState(): GenerationUiState {
    if (isEmpty()) return GenerationUiState()
    val current = currentSession()

    val running = current.firstOrNull { it.state == WorkInfo.State.RUNNING }
    if (running != null) {
        return GenerationUiState(
            running = true,
            stageText = running.progress.getString(GenerationWorker.KEY_PROGRESS).orEmpty(),
            completed = running.progress.getInt(GenerationWorker.KEY_PROGRESS_COMPLETED, 0),
            total = running.progress.getInt(GenerationWorker.KEY_PROGRESS_TOTAL, 0),
        )
    }
    // BLOCKED 是链上还没轮到的章节，对用户来说与排队没区别
    val pending = current.firstOrNull {
        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
    }
    if (pending != null) {
        return GenerationUiState(
            enqueued = true,
            // runAttemptCount > 0 说明已经跑过并退避重试中，和「刚排队」要区分开
            waitingRetry = pending.runAttemptCount > 0,
            retryCount = pending.runAttemptCount,
        )
    }
    val failed = current.lastOrNull { it.state == WorkInfo.State.FAILED }
    if (failed != null) {
        return GenerationUiState(
            error = failed.outputData.getString(GenerationWorker.KEY_ERROR)
                ?: "生成失败，请检查网络与模型配置后重试",
            outcome = failed.outputData.getString(GenerationWorker.KEY_OUTCOME)
                ?.let { name -> GenerationOutcome.entries.firstOrNull { it.name == name } }
                ?: GenerationOutcome.FAILED,
        )
    }
    // 取消不算错误，但要让状态条退出运行态并说明一句
    if (current.any { it.state == WorkInfo.State.CANCELLED }) {
        return GenerationUiState(outcome = GenerationOutcome.CANCELLED)
    }
    return GenerationUiState()
}

/**
 * 收窄到当前会话：有未结束的任务就取它所属的会话，否则取最后一批。
 *
 * 会话靠 `gen-session:<uuid>` tag 识别。拿不到 tag（例如旧版本排下的任务）时
 * 退回全部，行为与从前一致，不会因为识别不出会话就什么都不显示。
 */
private fun List<WorkInfo>.currentSession(): List<WorkInfo> {
    val activeSession = firstOrNull { !it.state.isFinished }?.sessionTag()
    if (activeSession != null) return filter { it.sessionTag() == activeSession }
    val lastSession = lastOrNull { it.sessionTag() != null }?.sessionTag()
        ?: return this
    return filter { it.sessionTag() == lastSession }
}

private fun WorkInfo.sessionTag(): String? = tags.firstOrNull { it.startsWith(SESSION_TAG_PREFIX) }

private const val SESSION_TAG_PREFIX = "gen-session:"
