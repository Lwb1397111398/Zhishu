package com.example.zhishu

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.transfer.TransferManager
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.IssueResolution
import com.example.zhishu.domain.model.IssueType
import com.example.zhishu.domain.model.ApiProfile
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.model.NodeGenerateStatus
import com.example.zhishu.domain.model.VerificationIssue
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
        // v13/v14 新增字段（nodeKey / generateStatus / batchId）随备份往返保留，不因恢复而丢失
        val restoredNodes = container.knowledgeRepository.getByProject(projectId)
        assertTrue(restoredNodes.all { it.generateStatus == NodeGenerateStatus.COMPLETED.name })
        assertTrue(restoredNodes.all { it.batchId == "" })
        val plans = container.chapterRepository.getPlans(projectId)
        assertEquals(3, plans.size)
        assertTrue(plans.all { it.status == ChapterStatus.PUBLISHED })
        assertEquals(10, container.chapterRepository.countAllVersionsBlocks(projectId))
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
        assertEquals(11, container.chapterRepository.countAllVersionsBlocks(projectId))

        assertTrue(transfer.import(app, uri).isSuccess)
        assertEquals(10, container.chapterRepository.countAllVersionsBlocks(projectId))
        assertTrue(container.chapterRepository.getAllVersionsBlocks("c1").none { it.id == "stale-block" })
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
        assertEquals(0, container.chapterRepository.countAllVersionsBlocks("t-p"))
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
        container.settingsRepository.saveProfile(
            ApiProfile(
                name = "含密钥方案",
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
        assertTrue("citations.json" in names)
        assertTrue("verification_issues.json" in names)
        assertTrue(names.none { it.contains("model") || it.contains("config") })
        assertFalse(text.contains("super-secret-key"))
        assertFalse(text.contains("keyRef"))
    }

    /** formatVersion 保持 8：4C 的新增字段通过序列化默认值与旧格式兼容 */
    @Test
    fun manifest_declaresCurrentFormatVersion() = runBlocking {
        DemoSeeder.seed(container)
        val file = File(workDir, "manifest.zip")
        assertTrue(transfer.export(app, Uri.fromFile(file)).isSuccess)
        assertEquals(8, TransferManager.FORMAT_VERSION)

        var manifest = ""
        ZipInputStream(file.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") manifest = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertTrue(manifest.contains("\"formatVersion\": 8"))
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

    /** 章节类型与因果契约必须随备份往返（第二轮 P1-2，新字段向后兼容） */
    @Test
    fun exportThenImport_roundTripsChapterTypeAndContract() = runBlocking {
        DemoSeeder.seed(container)
        val c1 = container.chapterRepository.getPlan("c1")!!
        container.chapterRepository.upsertPlan(
            c1.copy(
                chapterType = "INTRODUCE",
                contractJson = """{"entryQuestion":"如何判断遗嘱效力","knowledgeGap":"需要一个标准",""" +
                    """"introducedNodeTitles":["继承"],"requiredBeats":["开场","引入","收束"],"resultingChange":"有了判断"}""",
            ),
        )

        val file = File(workDir, "contract.zip")
        assertTrue(transfer.export(app, Uri.fromFile(file)).isSuccess)
        assertTrue(transfer.import(app, Uri.fromFile(file)).isSuccess)

        val restored = container.chapterRepository.getPlan("c1")
        assertEquals("INTRODUCE", restored?.chapterType)
        assertTrue(restored?.contractJson.orEmpty().contains("knowledgeGap"))
        assertTrue(restored?.contractJson.orEmpty().contains("introducedNodeTitles"))
    }

    /**
     * v5 备份（formatVersion 5）导入时，被取代的旧正文改成 ARCHIVED（3D）。
     *
     * 判据必须与 v8→v9 迁移逐字一致：有正文块、且版本号比当前指针那一版旧。
     * 不一致的话，同一份数据「升级上来」和「导入进来」会得到两种结果——
     * 一边显示「上次重新生成失败」，一边不显示。
     */
    @Test
    fun import_v5Backup_marksSupersededBodiesAsArchived() = runBlocking {
        val file = File(workDir, "v5-versions.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("manifest.json", """{"app":"zhishu","formatVersion":5,"exportedAt":0,"projects":1}""")
            put(
                "projects.json",
                """[{"id":"v5-p","title":"旧档","topic":"t","baseline":"b","depth":"d",""" +
                    """"dailyMinutes":10,"targetDays":7,"genre":"悬疑","status":"READY","createdAt":1,"updatedAt":1}]""",
            )
            put(
                "knowledge.json",
                """[{"id":"v5-n","projectId":"v5-p","title":"继承","definition":"d"}]""",
            )
            put(
                "chapters.json",
                """[{"id":"v5-c","projectId":"v5-p","order":1,"title":"第一章","status":"PUBLISHED",""" +
                    """"activeVersionId":"v5-c-v2","coveredTitles":["继承"]}]""",
            )
            // v1 有正文、被取代；v3 没有正文、是真失败。两者在 v5 备份里都是 ABANDONED
            put(
                "chapter_versions.json",
                """[{"id":"v5-c-v1","chapterId":"v5-c","projectId":"v5-p","versionNumber":1,""" +
                    """"status":"ABANDONED","title":"第一章","createdAt":1,"updatedAt":1},""" +
                    """{"id":"v5-c-v2","chapterId":"v5-c","projectId":"v5-p","versionNumber":2,""" +
                    """"status":"PUBLISHED","title":"第一章","coveredTitles":["继承"],"createdAt":1,"updatedAt":1},""" +
                    """{"id":"v5-c-v3","chapterId":"v5-c","projectId":"v5-p","versionNumber":3,""" +
                    """"status":"ABANDONED","title":"第一章","createdAt":1,"updatedAt":1}]""",
            )
            put(
                "blocks.json",
                """[{"id":"v5-b1","chapterId":"v5-c","versionId":"v5-c-v1","projectId":"v5-p",""" +
                    """"order":0,"text":"第一版正文","blockType":"STORY"},""" +
                    """{"id":"v5-b2","chapterId":"v5-c","versionId":"v5-c-v2","projectId":"v5-p",""" +
                    """"order":0,"text":"第二版正文","blockType":"STORY"}]""",
            )
        }
        assertTrue(transfer.import(app, Uri.fromFile(file)).isSuccess)

        val byId = container.chapterRepository.getVersions("v5-c").associateBy { it.id }
        // 有正文 + 比当前版本旧 → 被取代的历史正文
        assertEquals(ChapterVersionStatus.ARCHIVED, byId["v5-c-v1"]?.status)
        assertEquals(ChapterVersionStatus.PUBLISHED, byId["v5-c-v2"]?.status)
        // 没有正文 → 从来没写出来过，仍是失败尝试
        assertEquals(ChapterVersionStatus.ABANDONED, byId["v5-c-v3"]?.status)
        // 旧版本正文一块不删，也不会被当成当前正文
        assertEquals(1, container.chapterRepository.getBlocksByVersion("v5-c-v1").size)
        assertEquals("第二版正文", container.chapterRepository.getActiveBlocks("v5-c").single().text)
        // 覆盖关系按标题唯一匹配补出 ID
        assertEquals(listOf("v5-n"), container.chapterRepository.getPlan("v5-c")?.coveredNodeIds)
        assertEquals(listOf("v5-n"), byId["v5-c-v2"]?.coveredNodeIds)
    }

    /** 对不上任何知识点的覆盖标题不硬猜 ID，也不丢掉（3D） */
    @Test
    fun import_unmatchableCoveredTitle_leavesNodeIdsEmpty() = runBlocking {
        val file = File(workDir, "unmatched-coverage.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("manifest.json", """{"app":"zhishu","formatVersion":5,"exportedAt":0,"projects":1}""")
            put(
                "projects.json",
                """[{"id":"um-p","title":"旧档","topic":"t","baseline":"b","depth":"d",""" +
                    """"dailyMinutes":10,"targetDays":7,"genre":"悬疑","status":"READY","createdAt":1,"updatedAt":1}]""",
            )
            put(
                "knowledge.json",
                """[{"id":"um-n","projectId":"um-p","title":"继承","definition":"d"}]""",
            )
            put(
                "chapters.json",
                """[{"id":"um-c","projectId":"um-p","order":1,"title":"第一章","status":"QUEUED",""" +
                    """"coveredTitles":["一个已经改过名的知识点"]}]""",
            )
        }
        assertTrue(transfer.import(app, Uri.fromFile(file)).isSuccess)

        val plan = container.chapterRepository.getPlan("um-c")
        assertTrue(plan?.coveredNodeIds.orEmpty().isEmpty())
        // 标题没被丢掉：界面据此报「有内容对不上知识点」，而不是当成没讲过
        assertEquals(listOf("一个已经改过名的知识点"), plan?.coveredTitles)
    }

    /**
     * 核验问题随备份往返（4B）。
     *
     * 三件事：内容原样回来（类型、说明、建议、相关来源）、
     * 用户已经处理过的结论不被重置成「待处理」、
     * blockId 对不上那一版的问题降级成「定位不到段落」而不是被丢掉。
     *
     * 最后一条是有代价的选择：丢掉更省事，但那等于恢复出来的库
     * 悄悄告诉用户「这一章没问题」。
     */
    @Test
    fun exportThenImport_roundTripsVerificationIssues() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = firstProjectId()
        val blocks = container.chapterRepository.getActiveBlocks("c1")
        val versionId = blocks.first().versionId
        container.issueRepository.upsertAll(
            listOf(
                VerificationIssue(
                    id = VerificationIssue.idFor(versionId, 0),
                    projectId = projectId,
                    chapterId = "c1",
                    versionId = versionId,
                    blockId = blocks.first().id,
                    type = IssueType.CONTRADICTION,
                    rawType = "contradiction",
                    message = "继承顺序说错了",
                    sourceRefs = listOf("chunk-x"),
                    suggestedFix = "改成配偶、子女、父母",
                    resolution = IssueResolution.ACCEPTED,
                    order = 0,
                    createdAt = 5L,
                ),
                VerificationIssue(
                    id = VerificationIssue.idFor(versionId, 1),
                    projectId = projectId,
                    chapterId = "c1",
                    versionId = versionId,
                    // 指向一个不属于这一版的块：导入时要降级成「定位不到」
                    blockId = "别的版本的块",
                    type = IssueType.BROKEN_DERIVATION,
                    message = "推演跳了一步",
                    order = 1,
                    createdAt = 5L,
                ),
            ),
        )

        val file = File(workDir, "issues.zip")
        val uri = Uri.fromFile(file)
        assertTrue(transfer.export(app, uri).isSuccess)
        container.issueRepository.deleteByProject(projectId)
        assertTrue(container.issueRepository.getActiveByChapter("c1").isEmpty())

        assertTrue(transfer.import(app, uri).isSuccess)
        val restored = container.issueRepository.getActiveByChapter("c1").sortedBy { it.order }
        assertEquals(2, restored.size)

        assertEquals(IssueType.CONTRADICTION, restored[0].type)
        assertEquals("继承顺序说错了", restored[0].message)
        assertEquals("改成配偶、子女、父母", restored[0].suggestedFix)
        assertEquals(listOf("chunk-x"), restored[0].sourceRefs)
        assertEquals(blocks.first().id, restored[0].blockId)
        // 用户当初判断「不用改」，恢复之后不该又变成待处理
        assertEquals(IssueResolution.ACCEPTED, restored[0].resolution)

        // 对不上这一版的 blockId：问题留着，但明确说定位不到
        assertNull(restored[1].blockId)
        assertFalse(restored[1].isBlockScoped)
        assertEquals("推演跳了一步", restored[1].message)
    }

    /** v7 及更早的备份没有问题表，导入后是空的，旧的核验说明原样保留（4B） */
    @Test
    fun import_v7Backup_hasNoIssuesButKeepsOldNote() = runBlocking {
        val note = "[contradiction] 旧版本拼出来的核验说明"
        val file = File(workDir, "v7-issues.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("manifest.json", """{"app":"zhishu","formatVersion":7,"exportedAt":0,"projects":1}""")
            put(
                "projects.json",
                """[{"id":"v7-p","title":"旧档","topic":"t","baseline":"b","depth":"d",""" +
                    """"dailyMinutes":10,"targetDays":7,"genre":"悬疑","status":"READY","createdAt":1,"updatedAt":1}]""",
            )
            put(
                "chapters.json",
                """[{"id":"v7-c","projectId":"v7-p","order":1,"title":"第一章","status":"PUBLISHED",""" +
                    """"verificationNote":"$note","activeVersionId":"v7-c-v1"}]""",
            )
            put(
                "chapter_versions.json",
                """[{"id":"v7-c-v1","chapterId":"v7-c","projectId":"v7-p","versionNumber":1,""" +
                    """"status":"PUBLISHED","title":"第一章","createdAt":1,"updatedAt":1}]""",
            )
            put(
                "blocks.json",
                """[{"id":"v7-b","chapterId":"v7-c","versionId":"v7-c-v1","projectId":"v7-p",""" +
                    """"order":0,"text":"正文","blockType":"STORY","sourceRefs":[]}]""",
            )
        }
        assertTrue(transfer.import(app, Uri.fromFile(file)).isSuccess)

        // 问题表是空的，不硬造记录
        assertTrue(container.issueRepository.getAllVersionsByProject("v7-p").isEmpty())
        // 那句说明还在：它没有 blockId 也没有原始类型，回填只会造出一批假记录，
        // 让「本章还有 3 个问题待处理」从第一天起就不可信
        assertEquals(note, container.chapterRepository.getPlan("v7-c")?.verificationNote)
        // 版本行的两个新列有默认值，旧版本不会被当成局部重写的产物
        val version = container.chapterRepository.getVersion("v7-c-v1")
        assertNull(version?.derivedFromVersionId)
        assertFalse(version?.isPartialRewrite == true)
    }

    /**
     * 引用关系随备份往返（4A）。
     *
     * 三件事：当前版本的引用回来了、历史版本的引用也回来了、
     * 未确认引用仍然是未确认——把它「修好」成 RESOLVED 才是最糟的结果，
     * 那等于恢复出来的库比原库更自信，而依据一点没多。
     */
    @Test
    fun exportThenImport_roundTripsCitations() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = firstProjectId()
        val blocks = container.chapterRepository.getAllVersionsBlocks("c1")
        val chunkId = container.sourceRepository.getChunksByProject(projectId).first().id
        container.citationRepository.upsertAll(
            listOf(
                Citation(
                    id = Citation.idFor(blocks.first().id, 0),
                    projectId = projectId,
                    chapterId = "c1",
                    versionId = blocks.first().versionId,
                    blockId = blocks.first().id,
                    chunkId = chunkId,
                    rawRef = chunkId,
                    status = CitationStatus.RESOLVED,
                    order = 0,
                ),
                Citation(
                    id = Citation.idFor(blocks.first().id, 1),
                    projectId = projectId,
                    chapterId = "c1",
                    versionId = blocks.first().versionId,
                    blockId = blocks.first().id,
                    chunkId = null,
                    rawRef = "《民法典》第1123条",
                    status = CitationStatus.UNCONFIRMED,
                    order = 1,
                ),
            ),
        )

        val file = File(workDir, "citations.zip")
        val uri = Uri.fromFile(file)
        assertTrue(transfer.export(app, uri).isSuccess)
        container.citationRepository.deleteByProject(projectId)
        assertTrue(container.citationRepository.getActiveByProject(projectId).isEmpty())

        assertTrue(transfer.import(app, uri).isSuccess)
        // 只看我们造的那一块：同章其他块的 sourceRefs 也会被重建成引用（这本身是对的）
        val restored = container.citationRepository.getActiveByChapter("c1")
            .filter { it.blockId == blocks.first().id }
            .sortedBy { it.order }
        assertEquals(2, restored.size)
        assertEquals(CitationStatus.RESOLVED, restored[0].status)
        assertEquals(chunkId, restored[0].chunkId)
        // 未确认的还是未确认，原始标识一字不改
        assertEquals(CitationStatus.UNCONFIRMED, restored[1].status)
        assertNull(restored[1].chunkId)
        assertEquals("《民法典》第1123条", restored[1].rawRef)
    }

    /**
     * 「来源已删除」随备份往返，不被降级成普通「未确认」（4C）。
     *
     * 两者在库里的差别只有一个状态值：chunkId 都是空的，都对不上任何片段。
     * 导入时重算会把「这一段当初有依据、只是资料被删了」说成「模型给的出处
     * 一开始就没对上」——恢复出来的库比原库更不信任用户自己写的东西，
     * 而且被删资料的名字一起丢了，用户再也不知道该重新导入哪一份。
     */
    @Test
    fun exportThenImport_keepsDeletedSourceStateAndItsTitle() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = firstProjectId()
        val block = container.chapterRepository.getActiveBlocks("c1").first { it.sourceRefs.isEmpty() }
        container.citationRepository.upsertAll(
            listOf(
                Citation(
                    id = Citation.idFor(block.id, 0),
                    projectId = projectId,
                    chapterId = "c1",
                    versionId = block.versionId,
                    blockId = block.id,
                    chunkId = null,
                    rawRef = "讲义第三节",
                    status = CitationStatus.SOURCE_DELETED,
                    lostSourceTitle = "上学期的讲义",
                    order = 0,
                ),
            ),
        )

        val file = File(workDir, "deleted-source.zip")
        val uri = Uri.fromFile(file)
        assertTrue(transfer.export(app, uri).isSuccess)
        container.citationRepository.deleteByProject(projectId)
        assertTrue(transfer.import(app, uri).isSuccess)

        val restored = container.citationRepository.getActiveByChapter("c1")
            .single { it.blockId == block.id }
        assertEquals(CitationStatus.SOURCE_DELETED, restored.status)
        assertEquals("上学期的讲义", restored.lostSourceTitle)
        assertNull(restored.chunkId)
        assertEquals("讲义第三节", restored.rawRef)
    }

    /**
     * 备份里记着「资料已删除」，但导入时那个片段又在了：回到已确认，标题一起清掉。
     *
     * 这正是用户重新导入同一份资料之后的样子。继续说「来源已删除」是在陈述
     * 一件已经不成立的事，而那句话会让人再导一次同一份资料。
     */
    @Test
    fun import_deletedSourceThatExistsAgain_becomesResolved() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = firstProjectId()
        val chunkId = container.sourceRepository.getChunksByProject(projectId).first().id
        val block = container.chapterRepository.getActiveBlocks("c1").first { it.sourceRefs.isEmpty() }
        container.citationRepository.upsertAll(
            listOf(
                Citation(
                    id = Citation.idFor(block.id, 0),
                    projectId = projectId,
                    chapterId = "c1",
                    versionId = block.versionId,
                    blockId = block.id,
                    chunkId = null,
                    // 备份时资料已被删；导入时同名片段还在库里
                    rawRef = chunkId,
                    status = CitationStatus.SOURCE_DELETED,
                    lostSourceTitle = "被删掉又导回来的资料",
                    order = 0,
                ),
            ),
        )

        val file = File(workDir, "deleted-then-back.zip")
        val uri = Uri.fromFile(file)
        assertTrue(transfer.export(app, uri).isSuccess)
        container.citationRepository.deleteByProject(projectId)
        assertTrue(transfer.import(app, uri).isSuccess)

        val restored = container.citationRepository.getActiveByChapter("c1")
            .single { it.blockId == block.id }
        assertEquals(CitationStatus.RESOLVED, restored.status)
        assertEquals(chunkId, restored.chunkId)
        assertEquals("", restored.lostSourceTitle)
    }

    /**
     * v6 及更早的备份没有 citations.json，按块的 sourceRefs 重建（4A）。
     *
     * 规则与 v9→v10 迁移逐字一致：对得上现存片段的记 RESOLVED，
     * 对不上的记 UNCONFIRMED 而不是丢掉。两边不一致的话，同一份数据
     * 「升级上来」和「导入进来」会得到两种可信度。
     */
    @Test
    fun import_v6Backup_rebuildsCitationsFromSourceRefs() = runBlocking {
        val file = File(workDir, "v6-citations.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            put("manifest.json", """{"app":"zhishu","formatVersion":6,"exportedAt":0,"projects":1}""")
            put(
                "projects.json",
                """[{"id":"v6-p","title":"旧档","topic":"t","baseline":"b","depth":"d",""" +
                    """"dailyMinutes":10,"targetDays":7,"genre":"悬疑","status":"READY","createdAt":1,"updatedAt":1}]""",
            )
            put(
                "sources.json",
                """[{"id":"v6-src","projectId":"v6-p","title":"讲义","kind":"TEXT","fetchedAt":1,""" +
                    """"contentHash":"h","parseStatus":"PARSED"}]""",
            )
            put(
                "chunks.json",
                """[{"id":"v6-chunk","sourceId":"v6-src","projectId":"v6-p","ordinal":0,"text":"原文"}]""",
            )
            put(
                "chapters.json",
                """[{"id":"v6-c","projectId":"v6-p","order":1,"title":"第一章","status":"PUBLISHED",""" +
                    """"activeVersionId":"v6-c-v1"}]""",
            )
            put(
                "chapter_versions.json",
                """[{"id":"v6-c-v1","chapterId":"v6-c","projectId":"v6-p","versionNumber":1,""" +
                    """"status":"PUBLISHED","title":"第一章","createdAt":1,"updatedAt":1}]""",
            )
            // 一个真片段 + 一个已经不存在的片段
            put(
                "blocks.json",
                """[{"id":"v6-b","chapterId":"v6-c","versionId":"v6-c-v1","projectId":"v6-p",""" +
                    """"order":0,"text":"正文","blockType":"FACT",""" +
                    """"sourceRefs":["v6-chunk","已经删掉的片段"]}]""",
            )
        }
        assertTrue(transfer.import(app, Uri.fromFile(file)).isSuccess)

        val citations = container.citationRepository.getActiveByChapter("v6-c").sortedBy { it.order }
        assertEquals(2, citations.size)
        assertEquals(CitationStatus.RESOLVED, citations[0].status)
        assertEquals("v6-chunk", citations[0].chunkId)
        assertEquals(CitationStatus.UNCONFIRMED, citations[1].status)
        assertEquals("已经删掉的片段", citations[1].rawRef)
        // 块的 sourceRefs 只留真的对上的那个，与引用表保持一致
        assertEquals(
            listOf("v6-chunk"),
            container.chapterRepository.getActiveBlocks("v6-c").single().sourceRefs,
        )
    }

    /** 来源元数据（法域、生效时间、法律版本）随备份往返（4A） */
    @Test
    fun exportThenImport_roundTripsSourceMetadata() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = firstProjectId()
        val sourceId = container.sourceRepository.getByProject(projectId).first().id
        container.sourceRepository.updateMeta(
            sourceId = sourceId,
            versionLabel = "第 3 版",
            jurisdiction = "中国大陆",
            effectiveFrom = "2021-01-01",
            legalVersion = "2020 年修正",
        )

        val file = File(workDir, "source-meta.zip")
        val uri = Uri.fromFile(file)
        assertTrue(transfer.export(app, uri).isSuccess)
        container.sourceRepository.deleteByProject(projectId)
        assertTrue(transfer.import(app, uri).isSuccess)

        val restored = container.sourceRepository.getByProject(projectId).single { it.id == sourceId }
        assertEquals("中国大陆", restored.jurisdiction)
        assertEquals("2021-01-01", restored.effectiveFrom)
        assertEquals("2020 年修正", restored.legalVersion)
        assertTrue(restored.isTimeSensitive)
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
