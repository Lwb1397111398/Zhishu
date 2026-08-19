package com.example.zhishu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.example.zhishu.ui.ZhishuRoot
import com.example.zhishu.ui.theme.ZhishuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZhishuTheme {
                ZhishuAppContent()
            }
        }
    }
}

@Composable
private fun ZhishuAppContent() {
    ZhishuRoot()
}
