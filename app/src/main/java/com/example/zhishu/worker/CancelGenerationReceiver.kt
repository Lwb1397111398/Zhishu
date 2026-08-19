package com.example.zhishu.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/**
 * 前台通知上「取消」按钮的落点（第二阶段）。
 *
 * 取消的是项目级唯一链，而不是当前那一个 WorkRequest——
 * 只取消当前 Worker 的话，链上后面几章会立刻接着跑，用户按了取消却还在扣费。
 */
class CancelGenerationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val projectId = intent.getStringExtra(GenerationNotifier.EXTRA_PROJECT_ID) ?: return
        GenerationScheduler.cancel(context, projectId)
        GenerationNotifier.clearProgress(context)
    }
}

/** 供 UI 与通知共用的调度入口薄封装，避免两边各写一遍取消逻辑 */
internal object GenerationScheduler {
    fun cancel(context: Context, projectId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(GenerationWorker.uniqueWorkName(projectId))
    }
}
