package com.example.zhishu.worker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务启动失败后的定时重试循环。
 *
 * Android 12+ 不允许应用在后台启动前台服务：用户点「继续」后切走/锁屏，
 * Worker 的 setForeground 会抛 ForegroundServiceStartNotAllowedException——
 * 权限没问题，是时机不允许。setForeground 失败时生成本身继续跑（不能因为
 * 保护失败就放弃生成），但会失去长任务保护：系统随时可能按后台任务的执行
 * 窗口把 Worker 收走，且进程可能被冻结，表现为模型调用挂死、无任何可见原因。
 *
 * 用户把 app 切回前台后时机豁免恢复，此时 setForeground 就能成功——
 * 这个循环每 [retryIntervalMillis] 重试一次，直到成功为止，
 * 用户任何时候回到 app，最多一个周期后保护自动补上。
 */
internal class ForegroundKeeper(
    private val attempt: suspend () -> Boolean,
    private val retryIntervalMillis: Long = 30_000,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var job: Job? = null

    /** 已在重试中则不叠加第二个循环 */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(retryIntervalMillis)
                if (attempt()) return@launch
            }
        }
    }

    /** Worker 结束或前台化已成功时调用，避免泄漏协程 */
    fun stop() {
        job?.cancel()
        job = null
    }
}
