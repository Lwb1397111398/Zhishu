package com.example.zhishu

import android.app.Application
import com.example.zhishu.di.AppContainer

class ZhishuApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
