package com.example.zhishu

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 未捕获异常写进应用私有目录，作为最后防线。
 *
 * 个人本地工具没有线上崩溃收集，闪退只能靠复现猜；落盘之后，
 * 下次启动把最近一次堆栈带出来（或用户手动导出），定位不再靠猜。
 * 只保留最近 [MAX_KEPT] 份，避免日志无限增长。
 */
object CrashLog {

    private const val TAG = "ZhishuCrash"
    private const val DIR_NAME = "crash"
    private const val MAX_KEPT = 5

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeLatest(appContext, thread.name, throwable)
            // 交回系统默认处理（结束进程并走系统的崩溃记录），
            // 我们的处理器只负责留现场，不吞崩溃
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 最近一次崩溃的堆栈；从未崩过返回 null。 */
    fun latest(context: Context): String? =
        crashFiles(context).firstOrNull()?.readText()

    private fun writeLatest(context: Context, threadName: String, throwable: Throwable) {
        runCatching {
            val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
            val file = File(dir, "crash-${System.currentTimeMillis()}.txt")
            file.writeText(
                buildString {
                    appendLine("thread: $threadName")
                    appendLine("time: ${System.currentTimeMillis()}")
                    appendLine(Log.getStackTraceString(throwable))
                },
            )
            trimOld(dir)
        }
    }

    /** 供测试直接清理超额日志 */
    internal fun trimOldForTest(dir: File) = trimOld(dir)

    private fun trimOld(dir: File) {
        val files = dir.listFiles().orEmpty()
        if (files.size > MAX_KEPT) {
            files.sortedBy { it.name }.take(files.size - MAX_KEPT).forEach { it.delete() }
        }
    }

    private fun crashFiles(context: Context): List<File> =
        File(context.filesDir, DIR_NAME).listFiles().orEmpty()
            .filter { it.name.startsWith("crash-") }
            .sortedByDescending { it.name }
}
