package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.di.AppContainer
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据库损坏自愈（防「一打开就闪退」）。
 *
 * 真机上库文件损坏（断电、进程被杀在写盘中途）时，Room 打不开库的异常
 * 会顺着首个查询的协程冒成未捕获异常，APP 每次启动都闪退。
 * warmUpDatabase 必须做到：损坏现场改名保留、重建空库、查询恢复可用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseRecoveryTest {

    @Test
    fun warmUp_onHealthyDatabase_leavesNoBackup() = runBlocking {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        // 先正常打开一次（建库建表）再关掉，模拟一个完好的库
        container.warmUpDatabase()
        container.projectRepository.observeAll().first()
        container.database.close()

        container.warmUpDatabase()
        assertEquals(emptyList<File>(), corruptBackups())
    }

    @Test
    fun warmUp_onCorruptDatabase_recoversAndKeepsEvidence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 用「数据库路径被目录占住」模拟打不开：真机上的库文件损坏（file is not a
        // database / disk image is malformed）同样在打开时抛 SQLiteException，
        // 但 Robolectric 的 SQLite 会静默把垃圾文件重建为新库，模拟不出那个失败，
        // 而目录占位在任何实现下都必然 SQLiteCantOpenDatabaseException
        val dbFile = context.getDatabasePath("zhishu.db")
        dbFile.parentFile?.mkdirs()
        assertTrue(dbFile.mkdirs())

        val container = AppContainer(context)
        container.warmUpDatabase()

        // 自愈后查询可用：能读项目列表
        assertTrue(container.projectRepository.observeAll().first().isEmpty())
        // 原位置让给了新库
        assertTrue(dbFile.isFile)

        // 打不开的现场被改名保留（不删数据，只挪开）
        val backups = corruptBackups()
        assertEquals(1, backups.size)
        assertTrue(backups.single().exists())
    }

    private fun corruptBackups(): List<File> =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getDatabasePath("zhishu.db").parentFile!!
            .listFiles().orEmpty()
            .filter { it.name.startsWith("zhishu.db.corrupt-") }

    @Test
    fun crashLog_keepsOnlyRecentFiles() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "crash").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        // 模拟 7 次崩溃留下的日志
        repeat(7) { index ->
            File(dir, "crash-${1000 + index}.txt").writeText("crash #$index")
        }

        CrashLog.trimOldForTest(dir)

        val kept = dir.listFiles().orEmpty().sortedBy { it.name }
        // 只留最近 5 份，且是最新的那 5 份
        assertEquals(5, kept.size)
        assertFalse(kept.any { it.name == "crash-1000.txt" })
        assertFalse(kept.any { it.name == "crash-1001.txt" })
        assertTrue(kept.any { it.name == "crash-1006.txt" })
    }
}
