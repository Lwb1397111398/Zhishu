package com.example.zhishu

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.transfer.TransferManager
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.ui.viewmodel.DemoSeeder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 备份导入导出测试（N7/B14） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferTest {

    private lateinit var app: ZhishuApp
    private lateinit var container: AppContainer
    private lateinit var transfer: TransferManager
    private lateinit var workDir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        container = app.container
        transfer = container.transfer
        workDir = File(app.cacheDir, "transfer-test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun exportThenImport_roundTripsAllData() = runBlocking {
        DemoSeeder.seed(container)
        val file = File(workDir, "backup.zip")
        val uri = Uri.fromFile(file)
        assertTrue(transfer.export(app, uri).isSuccess)
        assertTrue(file.length() > 0)

        val projectId = firstProjectId()
        container.projectRepository.delete(projectId)
        container.chapterRepository.clearBlocksForProject(projectId)
        container.chapterRepository.clearPlansForProject(projectId)
        container.knowledgeRepository.clearForProject(projectId)
        container.sourceRepository.deleteByProject(projectId)
        container.quizRepository.deleteByProject(projectId)

        assertTrue(transfer.import(app, uri).isSuccess)
        assertEquals(4, container.knowledgeRepository.getByProject(projectId).size)
        val plans = container.chapterRepository.getPlans(projectId)
        assertEquals(3, plans.size)
        assertTrue(plans.all { it.status == ChapterStatus.PUBLISHED })
        assertEquals(10, container.chapterRepository.countBlocks(projectId))
        assertEquals(4, container.quizRepository.getByProject(projectId).size)
        assertEquals(1, container.sourceRepository.countChunks(projectId))
    }

    /** 导入前先清理同项目历史子数据，不残留旧记录（B14） */
    @Test
    fun import_replacesExistingProjectChildData() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = firstProjectId()
        val file = File(workDir, "backup.zip")
        val uri = Uri.fromFile(file)
        transfer.export(app, uri)

        // 人为多加一条正文，导入后应被清掉
        container.chapterRepository.upsertBlocks(
            listOf(
                ChapterBlock(
                    id = "stale-block",
                    chapterId = "c1",
                    projectId = projectId,
                    order = 99,
                    text = "过期正文",
                ),
            ),
        )
        assertEquals(11, container.chapterRepository.countBlocks(projectId))

        assertTrue(transfer.import(app, uri).isSuccess)
        assertEquals(10, container.chapterRepository.countBlocks(projectId))
        assertTrue(container.chapterRepository.getBlocks("c1").none { it.id == "stale-block" })
    }

    /** 畸形 JSON 必须整体回滚，不留半份数据（N7） */
    @Test
    fun import_malformedPayload_writesNothing() = runBlocking {
        val file = File(workDir, "broken.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"app":"zhishu","formatVersion":1,"exportedAt":0,"projects":1}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("projects.json"))
            zip.write("""[{"id":"broken-p","title":"坏档"""".toByteArray())
            zip.closeEntry()
        }
        val result = transfer.import(app, Uri.fromFile(file))
        assertTrue(result.isFailure)
        assertEquals(null, container.projectRepository.get("broken-p"))
    }

    /** 引用不存在项目的子数据必须被过滤，不会写进库（N7） */
    @Test
    fun import_danglingReferences_areDropped() = runBlocking {
        val file = File(workDir, "dangling.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("manifest.json", """{"app":"zhishu","formatVersion":1,"exportedAt":0,"projects":1}""")
            put(
                "projects.json",
                """[{"id":"t-p","title":"t","topic":"t","baseline":"b","depth":"d","dailyMinutes":10,"targetDays":7,"genre":"悬疑","status":"DRAFT","createdAt":1,"updatedAt":1}]""",
            )
            // chapterId 不存在的正文块
            put(
                "blocks.json",
                """[{"id":"orphan","chapterId":"nope","order":0,"text":"孤儿正文","blockType":"STORY"}]""",
            )
        }
        assertTrue(transfer.import(app, Uri.fromFile(file)).isSuccess)
        assertEquals(0, container.chapterRepository.countBlocks("t-p"))
    }

    /** 条目数超限的 ZIP 直接拒绝（N7） */
    @Test
    fun import_tooManyEntries_isRejected() = runBlocking {
        val file = File(workDir, "bomb-entries.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(TransferManager.MAX_ENTRIES + 5) { index ->
                zip.putNextEntry(ZipEntry("file-$index.json"))
                zip.write("[]".toByteArray())
                zip.closeEntry()
            }
        }
        val result = transfer.import(app, Uri.fromFile(file))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("条目过多"))
    }

    /** 重复条目视为异常输入（N7）。ZipOutputStream 自身不允许重名，这里手写 ZIP 字节。 */
    @Test
    fun import_duplicateEntries_isRejected() = runBlocking {
        val file = File(workDir, "dup.zip")
        val body = """{"app":"zhishu","formatVersion":1,"exportedAt":0,"projects":0}""".toByteArray()
        writeRawZip(file, listOf("manifest.json" to body, "manifest.json" to body))
        val result = transfer.import(app, Uri.fromFile(file))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("重复条目"))
    }

    @Test
    fun import_pathTraversalEntry_isRejected() = runBlocking {
        val file = File(workDir, "traversal.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../evil.json"))
            zip.write("[]".toByteArray())
            zip.closeEntry()
        }
        val result = transfer.import(app, Uri.fromFile(file))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("非法路径"))
    }

    @Test
    fun import_notZhishuBackup_isRejected() = runBlocking {
        val file = File(workDir, "other.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"app":"other","formatVersion":1,"exportedAt":0,"projects":0}""".toByteArray())
            zip.closeEntry()
        }
        assertFalse(transfer.import(app, Uri.fromFile(file)).isSuccess)
    }

    private suspend fun firstProjectId(): String =
        container.projectRepository.observeAll().first().first().id

    /**
     * 手写一个 STORED 方式的 ZIP，用来构造 [ZipOutputStream] 拒绝生成的畸形档
     * （例如同名重复条目），验证导入端自身的防御逻辑。
     */
    private fun writeRawZip(file: File, entries: List<Pair<String, ByteArray>>) {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var offset = 0

        entries.forEach { (name, data) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(data) }.value

            val local = ByteArrayOutputStream()
            local.writeIntLe(0x04034b50)
            local.writeShortLe(20)
            local.writeShortLe(0)
            local.writeShortLe(0) // stored
            local.writeShortLe(0)
            local.writeShortLe(0)
            local.writeIntLe(crc.toInt())
            local.writeIntLe(data.size)
            local.writeIntLe(data.size)
            local.writeShortLe(nameBytes.size)
            local.writeShortLe(0)
            local.write(nameBytes)
            local.write(data)
            val localBytes = local.toByteArray()
            out.write(localBytes)

            central.writeIntLe(0x02014b50)
            central.writeShortLe(20)
            central.writeShortLe(20)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeIntLe(crc.toInt())
            central.writeIntLe(data.size)
            central.writeIntLe(data.size)
            central.writeShortLe(nameBytes.size)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeShortLe(0)
            central.writeIntLe(0)
            central.writeIntLe(offset)
            central.write(nameBytes)

            offset += localBytes.size
        }

        val centralBytes = central.toByteArray()
        out.write(centralBytes)
        out.writeIntLe(0x06054b50)
        out.writeShortLe(0)
        out.writeShortLe(0)
        out.writeShortLe(entries.size)
        out.writeShortLe(entries.size)
        out.writeIntLe(centralBytes.size)
        out.writeIntLe(offset)
        out.writeShortLe(0)

        file.writeBytes(out.toByteArray())
    }
}

private fun ByteArrayOutputStream.writeShortLe(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
}

private fun ByteArrayOutputStream.writeIntLe(value: Int) {
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}
