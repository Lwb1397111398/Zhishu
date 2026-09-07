package com.example.zhishu.worker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.example.zhishu.MainActivity
import com.example.zhishu.R

/**
 * 生成任务的通知（第二阶段）。
 *
 * 两类通知，职责分开：
 * - **前台通知**：只在真正跑模型的时候存在，用来向系统申明这是长任务，
 *   顺便让用户随时能取消。不会每写一个正文块就刷一次——那既没意义又费电。
 * - **结果通知**：一次生成会话结束时发一条总结，而不是每章一条。
 *
 * 通知权限被拒绝时这里的每个方法都安静降级：生成照跑，APP 内的状态条照显示。
 * 通知从来不是生成的前置条件。
 */
internal object GenerationNotifier {

    /** 正在生成的前台通知渠道。低优先级，不出声不弹横幅。 */
    private const val CHANNEL_PROGRESS = "generation_progress"

    /** 结果通知渠道。默认优先级，用户会看到一条可点开的总结。 */
    private const val CHANNEL_RESULT = "generation_result"

    /** 前台通知固定用这个 ID，多章串行时复用同一条，不会堆一屏 */
    private const val NOTIFICATION_ID_PROGRESS = 4101

    /** 结果通知按项目分配 ID，不同项目的结果不互相覆盖 */
    private const val NOTIFICATION_ID_RESULT_BASE = 4200

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "生成进度",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "显示正在进行的章节生成，可从这里取消" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                "生成结果",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "生成完成、部分完成或失败时的一条总结" },
        )
    }

    /**
     * 正在生成的前台通知。
     *
     * 文案里只放项目名、阶段和进度——绝不放 API Key、完整错误响应或资料内容。
     */
    fun progressForegroundInfo(
        context: Context,
        projectId: String,
        projectTitle: String,
        stageText: String,
        completed: Int,
        total: Int,
    ): ForegroundInfo {
        ensureChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(projectTitle.ifBlank { "正在生成" })
            .setContentText(stageText.ifBlank { "正在生成…" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openProjectIntent(context, projectId))
            .addAction(
                0,
                "取消",
                cancelUniqueWorkIntent(context, projectId),
            )
        if (total > 0) {
            builder.setProgress(total, completed.coerceIn(0, total), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return foregroundInfo(NOTIFICATION_ID_PROGRESS, builder.build())
    }

    /** targetSdk 35 上前台服务必须声明类型，dataSync 对应「代替用户完成一段网络工作」 */
    private fun foregroundInfo(id: Int, notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                id,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(id, notification)
        }

    /**
     * 会话结束时的一条总结通知。
     *
     * [GenerationOutcome.CANCELLED] 不发通知：用户自己按的取消，不需要再被告知一次，
     * 更不能在取消之后弹一条「生成失败」。
     */
    // 权限在下面第一行就用 canPostNotifications 检查过了；lint 不做跨函数分析所以看不见。
    // 即便如此 notify 仍然包在 runCatching 里，被系统拒掉也不会把生成拖崩。
    @SuppressLint("MissingPermission")
    fun notifyOutcome(
        context: Context,
        projectId: String,
        projectTitle: String,
        outcome: GenerationOutcome,
        detail: String,
    ) {
        if (outcome == GenerationOutcome.CANCELLED) return
        if (!canPostNotifications(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${projectTitle.ifBlank { "学习项目" }}·${outcome.title}")
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setContentIntent(openProjectIntent(context, projectId))
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(resultNotificationId(projectId), notification)
        }
    }

    /** 前台通知随任务结束一起消失，不要留一条「正在生成」在栏里 */
    fun clearProgress(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_PROGRESS) }
    }

    /**
     * Android 13 起没有 POST_NOTIFICATIONS 就发不出通知。
     * 这里只做检查并安静跳过——权限请求属于 UI 的时机判断，Worker 里弹不出来。
     */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 点通知回到对应项目。第一章已完成时由 UI 决定是否直接进阅读器——
     * 这里只负责把项目 ID 带过去，不在通知层判断读到哪。
     */
    private fun openProjectIntent(context: Context, projectId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_PROJECT_ID, projectId)
        }
        return PendingIntent.getActivity(
            context,
            projectId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 通知里的取消按钮：取消整条项目级唯一链，而不是单个 WorkRequest */
    private fun cancelUniqueWorkIntent(context: Context, projectId: String): PendingIntent {
        val intent = Intent(context, CancelGenerationReceiver::class.java).apply {
            putExtra(EXTRA_PROJECT_ID, projectId)
        }
        return PendingIntent.getBroadcast(
            context,
            projectId.hashCode() + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun resultNotificationId(projectId: String): Int =
        NOTIFICATION_ID_RESULT_BASE + (projectId.hashCode() and 0xFF)

    const val EXTRA_PROJECT_ID = "com.example.zhishu.PROJECT_ID"
}

/** 一次生成会话的结局。UI 状态条与结果通知共用这套语义。 */
enum class GenerationOutcome {
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED,
    ;

    val title: String
        get() = when (this) {
            COMPLETED -> "生成完成"
            PARTIAL -> "部分完成"
            FAILED -> "生成失败"
            CANCELLED -> "已取消"
        }
}
