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

    /*
     * 通知 ID 按项目分配：三类各占一段互不重叠的区间，段内用项目 ID 的哈希
     * 取 8192 个槽。固定 ID 在单项目时代是「多章串行只刷一条」的手段，但
     * 两本书同时生成时会互相覆盖——更糟的是前台服务结束撤通知是按 ID 撤的，
     * 会把另一个项目的前台通知一起撤掉，令那个前台服务「无通知可显示」。
     * 槽位碰撞的概率约 0.01%（1/8192 每对），碰撞后果也只是两条通知互盖，无害。
     */
    private const val ID_SPAN = 0x1FFF
    private const val PROGRESS_ID_BASE = 410_100
    private const val KEEPER_ID_BASE = 420_100
    private const val RESULT_ID_BASE = 430_100

    /** 正在生成的前台通知 ID：同项目多章串行复用同一条，不同项目互不干扰 */
    fun progressNotificationId(projectId: String): Int =
        PROGRESS_ID_BASE + (projectId.hashCode() and ID_SPAN)

    /**
     * 保活服务（[GenerationKeeperService]）的前台通知 ID，按项目分配。
     *
     * 与进度通知分开：两者可能同时存在（Worker 前台化成功 + 保活服务还在），
     * 共用 ID 会互相覆盖——Worker 结束时 WorkManager 撤通知会把保活服务的
     * 通知一起撤掉，前台服务却没有通知可显示。
     */
    fun keeperNotificationId(projectId: String): Int =
        KEEPER_ID_BASE + (projectId.hashCode() and ID_SPAN)

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
        return foregroundInfo(
            progressNotificationId(projectId),
            progressBuilder(context, projectId, projectTitle, stageText, completed, total).build(),
        )
    }

    /** 进度通知与保活通知共用的骨架：同样的渠道、同样的取消按钮、同样的进度条 */
    private fun progressBuilder(
        context: Context,
        projectId: String,
        projectTitle: String,
        stageText: String,
        completed: Int,
        total: Int,
    ): NotificationCompat.Builder {
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
        return builder
    }

    /**
     * 保活服务的常驻通知（入队即挂，整条会话期间存在）。
     *
     * [stageText] 为空（还没有 Worker 上报进度）时给一句泛化文案。
     */
    fun keeperNotification(
        context: Context,
        projectId: String,
        stageText: String?,
        completed: Int,
        total: Int,
    ): Notification {
        ensureChannels(context)
        return progressBuilder(
            context = context,
            projectId = projectId,
            projectTitle = "正在生成",
            stageText = stageText.orEmpty(),
            completed = completed,
            total = total,
        ).build()
    }

    /**
     * 刷新保活通知的内容（服务存活期间 Worker 每到阶段边界上报一次）。
     * 只是内容刷新，不改变通知的前台身份；通知权限被拒时安静跳过。
     */
    // 权限在下面第一行就用 canPostNotifications 检查过了；lint 不做跨函数分析所以看不见。
    // 即便如此 notify 仍然包在 runCatching 里，被系统拒掉也不会把生成拖崩。
    @SuppressLint("MissingPermission")
    fun notifyKeeper(
        context: Context,
        projectId: String,
        stageText: String?,
        completed: Int,
        total: Int,
    ) {
        if (!canPostNotifications(context)) return
        runCatching {
            NotificationManagerCompat.from(context).notify(
                keeperNotificationId(projectId),
                keeperNotification(context, projectId, stageText, completed, total),
            )
        }
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
    fun clearProgress(context: Context, projectId: String) {
        runCatching {
            NotificationManagerCompat.from(context)
                .cancel(progressNotificationId(projectId))
        }
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
        RESULT_ID_BASE + (projectId.hashCode() and ID_SPAN)

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
