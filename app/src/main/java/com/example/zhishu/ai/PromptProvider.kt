package com.example.zhishu.ai

import android.content.Context

interface PromptProvider {
    fun load(name: String): String
}

class AssetsPromptProvider(context: Context) : PromptProvider {
    private val assets = context.applicationContext.assets

    override fun load(name: String): String =
        assets.open("prompts/$name.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
}
