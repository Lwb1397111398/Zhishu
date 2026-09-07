package com.example.zhishu

import android.app.Application
import android.util.Log
import com.example.zhishu.di.AppContainer

class ZhishuApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 尽早安装：从这里之后的任何未捕获异常都会留现场
        CrashLog.install(this)
        container = AppContainer(this)
        // 后台预热数据库：迁移与首帧并行；文件损坏时在这里自愈，
        // 而不是让首个查询的协程把整个进程带崩（「一打开就闪退」的来源之一）
        Thread {
            runCatching { container.warmUpDatabase() }
                .onFailure { Log.e(TAG, "数据库预热失败", it) }
        }.start()
    }

    private companion object {
        const val TAG = "ZhishuApp"
    }
}
