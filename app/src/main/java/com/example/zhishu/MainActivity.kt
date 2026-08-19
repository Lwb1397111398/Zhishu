package com.example.zhishu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.zhishu.ui.ZhishuRoot
import com.example.zhishu.ui.theme.ZhishuTheme
import com.example.zhishu.worker.GenerationNotifier

class MainActivity : ComponentActivity() {

    /**
     * 点生成结果通知进来时带的项目 ID。
     * 用可变状态而不是只读 intent，是因为 APP 已在前台时会走 onNewIntent。
     */
    private var pendingProjectId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 渠道尽早建好：Worker 里再建也行，但那时通知已经要发了
        GenerationNotifier.ensureChannels(this)
        pendingProjectId = intent?.projectIdExtra()
        setContent {
            ZhishuTheme {
                ZhishuAppContent(initialProjectId = pendingProjectId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingProjectId = intent.projectIdExtra()
    }

    private fun Intent.projectIdExtra(): String? =
        getStringExtra(GenerationNotifier.EXTRA_PROJECT_ID)?.takeIf { it.isNotBlank() }
}

@Composable
private fun ZhishuAppContent(initialProjectId: String?) {
    ZhishuRoot(initialProjectId = initialProjectId)
}
