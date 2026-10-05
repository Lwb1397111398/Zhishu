package com.example.zhishu.worker

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.zhishu.ai.GenerationLog

/**
 * 生成会话的保活前台服务（一次入队一条，整条会话期间存在）。
 *
 * ## 为什么要它：Worker 的保护有覆盖不到的洞
 *
 * 长任务靠 `setForeground` 免受后台执行窗口限制（不设的话单次运行约 10 分钟就被
 * JobScheduler 掐断重来，进程还可能被冻结——表现为模型调用挂死、进度条永远不动）。
 * 但 Android 12+ 不允许**后台**应用启动前台服务，而这恰恰是链式生成的常态：
 *
 * - 用户点「生成」时人还在前台，第一环来得及把自己前台化；
 * - 之后用户切走，链上后续 Worker（知识梳理 → 路线 → 逐章）启动时应用已在后台，
 *   它们的 `setForeground` 会被 `ForegroundServiceStartNotAllowedException` 拒掉；
 * - [ForegroundKeeper] 每 30 秒重试一次，但时机限制在用户回到前台前不解除——
 *   重试在后台永远不成功（2026-09 实测「调入后台就没工作，一直卡着」）。
 *
 * ## 做法：把保护从「Worker 自取」改成「入队时一次性建立」
 *
 * 入队永远发生在用户点按钮的那一刻（前台，时机允许），本服务立刻 startForeground。
 * 此后整个会话期间进程都持有前台服务：系统不再把它当可冻结的后台进程，链上每个
 * Worker 都在受保护的状态下跑，链与链之间也不会出现「无保护间隙」。
 *
 * ## 退场
 *
 * 盯着项目唯一链的 WorkInfo：链上**全部**工作都进入终态（成功 / 失败 / 取消）才
 * stopSelf。自动续订（appendAutoChunk / appendVolumePlan）会先挂上新工作再结束当前
 * Worker，所以观察器看到的链不会在续订间隙「暂时全完成」。这样成功收尾、失败、
 * 用户取消每条路都会退场，不需要各出口记得叫停；再加一条与 dataSync 前台服务
 * 时长上限对齐的看门狗兜底，防止观察器失效导致服务滞留。
 */
class GenerationKeeperService : Service() {

    private var projectId: String = ""
    private var workObserver: Observer<List<WorkInfo>>? = null
    private val watchdog = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        projectId = intent?.getStringExtra(GenerationNotifier.EXTRA_PROJECT_ID).orEmpty()
        if (projectId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            val notification = GenerationNotifier.keeperNotification(this, projectId, null, 0, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    GenerationNotifier.NOTIFICATION_ID_KEEPER,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(GenerationNotifier.NOTIFICATION_ID_KEEPER, notification)
            }
        }
        watchUniqueWork()
        watchdog.postDelayed({ stopSelf() }, WATCHDOG_MILLIS)
        return START_NOT_STICKY
    }

    /** 链上全部工作终态即退场；顺带把运行中 Worker 上报的进度映到通知上 */
    private fun watchUniqueWork() {
        val name = GenerationWorker.uniqueWorkName(projectId)
        val observer = Observer<List<WorkInfo>> { infos ->
            if (infos.isEmpty()) return@Observer
            if (infos.all { it.state.isFinished }) {
                stopSelf()
                return@Observer
            }
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            val progress = running?.progress
            GenerationNotifier.notifyKeeper(
                context = this,
                projectId = projectId,
                stageText = progress?.getString(GenerationWorker.KEY_PROGRESS),
                completed = progress?.getInt(GenerationWorker.KEY_PROGRESS_COMPLETED, 0) ?: 0,
                total = progress?.getInt(GenerationWorker.KEY_PROGRESS_TOTAL, 0) ?: 0,
            )
        }
        workObserver = observer
        runCatching {
            WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData(name)
                .observeForever(observer)
        }.onFailure {
            // 观察器挂不上（WorkManager 未初始化等）：只失去「自动退场」，
            // 保活本身仍有效，交给看门狗收尾
            GenerationLog.recordFailure(this, "KEEPER", it)
        }
    }

    override fun onDestroy() {
        watchdog.removeCallbacksAndMessages(null)
        workObserver?.let { observer ->
            runCatching {
                WorkManager.getInstance(this)
                    .getWorkInfosForUniqueWorkLiveData(GenerationWorker.uniqueWorkName(projectId))
                    .removeObserver(observer)
            }
        }
        workObserver = null
        super.onDestroy()
    }

    companion object {
        /**
         * 看门狗：观察器失效时的退场保险丝。取 6 小时，与 dataSync 前台服务在
         * Android 15 上的单次时长上限一致——系统反正也会在这一刻收走它。
         */
        private const val WATCHDOG_MILLIS = 6 * 60 * 60 * 1000L

        /**
         * 启动保活服务。只允许在**前台时机**调用（入队路径满足）；
         * Android 12+ 后台调用会抛 ForegroundServiceStartNotAllowedException，
         * 这里按失败留痕、不阻断生成——退回「Worker 自己 setForeground」的原路。
         */
        fun start(context: Context, projectId: String) {
            val intent = Intent(context, GenerationKeeperService::class.java)
                .putExtra(GenerationNotifier.EXTRA_PROJECT_ID, projectId)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    GenerationLog.recordFailure(context, "KEEPER", it)
                }
        }
    }
}
