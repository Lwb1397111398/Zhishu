package com.example.zhishu

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.transfer.TransferManager
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.ModelConfig
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.ui.viewmodel.DemoSeeder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * 版本化数据的往返（第三阶段 3A）：
     * 版本行、每块的 versionId、章节的 activeVersionId 三者关系恢复后必须自洽。
     */
    @Test
    fun exportThenImport_roundTripsChapterVersions() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = firstProjectId()
        // 造一个「有历史版本」的章节：c1 多一版作废正文
        container.chapterRepository.upsertVersions(
            listOf(
                ChapterVersion(
                    id = "c1-v0",
                    chapterId = "c1",
                    projectId = projectId,
                    versionNumber = 0,
                    status = ChapterVersionStatus.ABANDONED,
                    title = "旧版",
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            ),
        )
        container.chapterRepository.upsertBlocks(
            listOf(
                ChapterBlock(
                    id = "c1-v0-b1",
                    chapterId = "c1",
                    versionId = "c1-v0",
                    projectId = projectId,
                    order = 0,
                    text = "被替换掉的第一版正文",
                ),
            ),
        )
        val activeBefore = container.chapterRepository.getPlan("c1")?.activeVersionId

        val file = File(workDir, "versions.zip")
        val uri = Uri.fromFile(file)
        assertTrue(transfer.export(app, uri).isSuccess)
        assertTrue(transfer.import(app, uri).isSuccess)

        // 两个版本都回来了，指针还是原来那个
        val versions = container.chapterRepository.getVersions("c1")
        assertEquals(2, versions.size)
        assertEquals(activeBefore, container.chapterRepository.getPlan("c1")?.activeVersionId)
        // 导入不会把旧版本正文当成当前正文
        val active = container.chapterRepository.getActiveBlocks("c1")
        assertTrue(active.none { it.id == "c1-v0-b1" })
        assertTrue(active.all { it.versionId == activeBefore })
        // 旧版本的正文块也没丢
        assertEquals(1, container.chapterRepository.getBlocksByVersion("c1-v0").size)
        // 自测题绑定的是当前版本
        assertTrue(container.quizRepository.getActiveByChapter("c1").isNotEmpty())
    }

    /**
     * 旧备份（formatVersion 3，完全没有版本概念）仍然可以导入：
     * 每章补一个初始版本，正文可读的章节指针补上，正文一块不丢。
     */
    @Test
    fun import_legacyBackupWithoutVersions_buildsInitialVersions() = runBlocking {
        val file = File(workDir, "legacy.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("manifest.json", """{"app":"zhishu","formatVersion":3,"exportedAt":0,"projects":1}""")
            put(
                "projects.json",
                """[{"id":"legacy-p","title":"旧档","topic":"t","baseline":"b","depth":"d",""" +
                    """"dailyMinutes":10,"targetDays":7,"genre":"悬疑","status":"READY","createdAt":1,"updatedAt":1}]""",
            )
            put(
                "chapters.json",
                """[{"id":"lc-1","projectId":"legacy-p","order":1,"title":"第一章","status":"PUBLISHED",""" +
                    """"summary":"摘要","carryOver":"结尾留了个悬念"},""" +
                    """{"id":"lc-2","projectId":"legacy-p","order":2,"title":"第二章","status":"QUEUED",""" +
                    """"carryOver":"开场钩子"}]""",
            )
            put(
                "blocks.json",
                """[{"id":"lb-1","chapterId":"lc-1","order":0,"text":"旧备份里的正文","blockType":"STORY"}]""",
            )
            put(
                "quizzes.json",
                """[{"id":"lq-1","projectId":"legacy-p","chapterId":"lc-1","order":0,"question":"问？",""" +
                    """"type":"TRUE_FALSE","options":["对","错"],"answer":"对"}]""",
            )
        }
        assertTrue(transfer.import(app, Uri.fromFile(file)).isSuccess)

        // 已发布章节：补出的初始版本是 PUBLISHED，指针指上，正文立刻能读
        val published = container.chapterRepository.getPlan("lc-1")
        assertEquals("lc-1-v1", published?.activeVersionId)
        assertEquals(
            ChapterVersionStatus.PUBLISHED,
            container.chapterRepository.getVersion("lc-1-v1")?.status,
        )
        // 已发布章节的 carryOver 是结尾状态，可以搬进版本
        assertEquals("结尾留了个悬念", container.chapterRepository.getVersion("lc-1-v1")?.endingState)
        val active = container.chapterRepository.getActiveBlocks("lc-1")
        assertEquals(1, active.size)
        assertEquals("lb-1", active.first().id)
        assertEquals("lc-1-v1", active.first().versionId)
        // 旧题也绑到了初始版本，不会跟着以后的新正文显示
        assertEquals(1, container.quizRepository.getActiveByChapter("lc-1").size)
        assertEquals("lc-1-v1", container.quizRepository.getByChapter("lc-1").first().versionId)

        // 没发布的章节：版本是草稿、指针留空，carryOver 不当成结尾状态
        val queued = container.chapterRepository.getPlan("lc-2")
        assertNull(queued?.activeVersionId)
        val draft = container.chapterRepository.getVersion("lc-2-v1")
        assertEquals(ChapterVersionStatus.DRAFT, draft?.status)
        assertEquals("", draft?.endingState)
    }

    /** 备份里从来不含 API Key，版本化之后也一样 */
    @Test
    fun export_neverContainsApiKey() = runBlocking {
        DemoSeeder.seed(container)
        container.settingsRepository.saveConfig(
            ModelConfig(
                role = ModelRole.STORY.name,
                baseUrl = "https://example.com/v1",
                modelName = "test-model",
                keyRef = "enc:super-secret-key",
            ),
        )
        val file = File(workDir, "nokey.zip")
        assertTrue(transfer.export(app, Uri.fromFile(file)).isSuccess)

        val names = mutableListOf<String>()
        val text = StringBuilder()
        ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names += entry.name
                text.append(zip.readBytes().toString(Charsets.UTF_8))
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertTrue("chapter_versions.json" in names)
        assertTrue(names.none { it.contains("model") || it.contains("config") })
        assertFalse(text.contains("super-secret-key"))
        assertFalse(text.contains("keyRef"))
    }

    /** formatVersion 必须递增，且旧版本仍然读得进来 */
    @Test
    fun manifest_declaresCurrentFormatVersion() = runBlocking {
        DemoSeeder.seed(container)
        val file = File(workDir, "manifest.zip")
        assertTrue(transfer.export(app, Uri.fromFile(file)).isSuccess)
        assertEquals(5, TransferManager.FORMAT_VERSION)

        var manifest = ""
        ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") manifest = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertTrue(manifest.contains("\"formatVersion\": 5"))
    }

    /** 分卷字段能导出也能恢复（第三阶段 3C） */
    @Test
    fun exportThenImport_roundTripsVolumeFields() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = firstProjectId()
        // 把 c3 挪到第二卷，并记下卷信息
        val c3 = container.chapterRepository.getPlan("c3")!!
        container.chapterRepository.upsertPlan(
            c3.copy(volumeIndex = 1, volumeTitle = "第二卷", volumeGoal = "讲清法定继承"),
        )
        container.storyBibleRepository.save(
            container.storyBibleRepository.get(projectId)!!.copy(currentVolumeIndex = 1),
        )

        val file = File(workDir, "volumes.zip")
        assertTrue(transfer.export(app, Uri.fromFile(file)).isSuccess)
        assertTrue(transfer.import(app, Uri.fromFile(file)).isSuccess)

        val restored = container.chapterRepository.getPlan("c3")
        assertEquals(1, restored?.volumeIndex)
        assertEquals("第二卷", restored?.volumeTitle)
        assertEquals("讲清法定继承", restored?.volumeGoal)
        assertEquals(0, container.chapterRepository.getPlan("c1")?.volumeIndex)
        assertEquals(1, container.storyBibleRepository.get(projectId)?.currentVolumeIndex)
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
