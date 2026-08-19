package com.example.zhishu.ui.common

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.zhishu.worker.GenerationNotifier

/**
 * 在合适的时机请求通知权限（第二阶段）。
 *
 * 时机 = 用户刚刚主动开始了一次生成。这时候「想在生成完成时通知你」是句能听懂的话；
 * 冷启动就弹权限框只会被顺手拒掉。
 *
 * 拒绝也无所谓：生成照跑，状态条照显示，只是收不到通知。所以这里既不重复骚扰
 * （一次会话只问一次），也不因为被拒就拦住任何功能。
 *
 * 权限请求放在 UI 层是必须的——Worker 里弹不出权限框。
 */
@Composable
fun RequestNotificationPermissionWhenGenerating(generating: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 授权与否都不影响生成，什么都不用做 */ }

    LaunchedEffect(generating) {
        if (generating && !asked && !GenerationNotifier.canPostNotifications(context)) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
