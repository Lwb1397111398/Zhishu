package com.example.zhishu

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.IssueResolution
import com.example.zhishu.domain.model.IssueType
import com.example.zhishu.domain.model.NodeGenerateStatus
import com.example.zhishu.domain.model.VerificationIssue
import com.example.zhishu.search.DefaultSearchRepository
import com.example.zhishu.search.SearchHitKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1 → v2 迁移测试（B20）。
 * 手工建出 v1 结构并写入数据，再用带 Migration 的 Room 打开，
 * 断言：结构校验通过、旧数据保留、projectId 已回填、重复答题记录被去重。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(dbName).also { file ->
            if (file.exists()) file.delete()
        }
    }

    @Test
    fun migrate1To2_preservesDataAndBackfillsProjectId() = runBlocking {
        createV1Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                    AppDatabase.MIGRATION_9_10,
                    AppDatabase.MIGRATION_10_11,
                    AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            // 旧数据保留
            assertEquals("民法基础", db.projectDao().getById("p-1")?.title)

            // source_chunks.projectId 已按所属资料回填
            val chunks = db.sourceChunkDao().getByProject("p-1")
            assertEquals(1, chunks.size)
            assertEquals("c-1", chunks.first().id)

            // chapter_blocks.projectId 已按所属章节回填
            assertEquals(1, db.chapterBlockDao().countByProject("p-1"))

            // 重复答题记录被去重，只留最新一条
            assertEquals(1, db.answerRecordDao().countAnswers("q-1"))
            assertEquals(false, db.answerRecordDao().getByQuizId("q-1")?.isCorrect)

            // 新表可用
            assertTrue(db.knowledgeGapDao().getByProject("p-1").isEmpty())
            assertTrue(db.knowledgeConflictDao().getByProject("p-1").isEmpty())

            // v3 新增的 story_bibles 表可用，新列不影响旧数据读取
            assertTrue(db.storyBibleDao().get("p-1") == null)

            // FTS 索引已按原文回填，迁移后立刻可搜（P2）
            assertEquals(1, db.chapterBlockDao().countFtsByProject("p-1"))
            assertEquals(1, db.sourceChunkDao().countFtsByProject("p-1"))
            val repo = DefaultSearchRepository(
                knowledgeNodeDao = db.knowledgeNodeDao(),
                chapterBlockDao = db.chapterBlockDao(),
                sourceChunkDao = db.sourceChunkDao(),
            )
            assertTrue(repo.search("p-1", "意思表示").any { it.kind == SearchHitKind.SOURCE_CHUNK })
        } finally {
            db.close()
        }
    }

    /** v2 → v3：无资料模式与滚动记忆列/表。直接从完整 v2 结构升级，断言结构校验通过、数据保留。 */
    @Test
    fun migrate2To3_addsColumnsAndStoryBibleTable() = runBlocking {
        createV2Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                    AppDatabase.MIGRATION_9_10,
                    AppDatabase.MIGRATION_10_11,
                    AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)
            // 旧项目仍在，新列有默认值
            val project = db.projectDao().getById("p-1")
            assertEquals("民法基础", project?.title)
            assertEquals(com.example.zhishu.domain.model.SourceMode.DOCUMENTS, project?.sourceMode)
            // story_bibles 表建成且可写
            db.storyBibleDao().upsert(
                com.example.zhishu.domain.model.StoryBible(projectId = "p-1", world = "w", updatedAt = 1L),
            )
            assertEquals("w", db.storyBibleDao().get("p-1")?.world)
        } finally {
            db.close()
        }
    }

    /**
     * v3 → v4：阅读进度改块级定位。
     * 断言新列 blockOffset 建出来、旧的阅读进度行没丢，且新的块级位置能写能读。
     */
    @Test
    fun migrate3To4_addsBlockOffsetAndKeepsProgress() = runBlocking {
        createV3Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                    AppDatabase.MIGRATION_9_10,
                    AppDatabase.MIGRATION_10_11,
                    AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)
            // 旧进度行保留，新列取默认值 0
            val old = db.readingProgressDao().getForProject("p-1")
            assertEquals("ch-1", old?.chapterId)
            assertEquals(0, old?.blockOffset)

            // 块级位置可写可读
            db.readingProgressDao().upsert(
                com.example.zhishu.domain.model.ReadingProgress(
                    id = "p-1",
                    projectId = "p-1",
                    chapterId = "ch-1",
                    blockId = "b-1",
                    blockOffset = 24,
                    updatedAt = 2L,
                ),
            )
            val saved = db.readingProgressDao().getForProject("p-1")
            assertEquals("b-1", saved?.blockId)
            assertEquals(24, saved?.blockOffset)
        } finally {
            db.close()
        }
    }

    /**
     * v4 → v5：正文发布与测验解耦。
     *
     * 断言三件事：新列建出来了、有题的章节标成 READY、
     * 历史 QUIZ_FAILED 被扶正成「正文可读 + 测验失败」而不是丢掉。
     */
    @Test
    fun migrate4To5_decouplesQuizStateFromChapterStatus() = runBlocking {
        createV4Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                    AppDatabase.MIGRATION_9_10,
                    AppDatabase.MIGRATION_10_11,
                    AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            // ch-1 有题：标 READY，状态不动
            val withQuiz = db.chapterPlanDao().getById("ch-1")
            assertEquals(com.example.zhishu.domain.model.ChapterStatus.PUBLISHED, withQuiz?.status)
            assertEquals(com.example.zhishu.domain.model.QuizState.READY, withQuiz?.quizState)

            // ch-2 是历史 QUIZ_FAILED：正文其实通过了核验，现在明确算可读
            val quizFailed = db.chapterPlanDao().getById("ch-2")
            assertEquals(com.example.zhishu.domain.model.ChapterStatus.PUBLISHED, quizFailed?.status)
            assertEquals(com.example.zhishu.domain.model.QuizState.FAILED, quizFailed?.quizState)
            assertTrue(quizFailed?.isReadable == true)
            // 原来的失败说明搬进 quizNote，没被丢掉
            assertEquals("正文已通过核验，但测验未生成：题目不足", quizFailed?.quizNote)
            assertEquals("", quizFailed?.verificationNote)

            // ch-3 还没生成：默认 NONE，不受影响
            val queued = db.chapterPlanDao().getById("ch-3")
            assertEquals(com.example.zhishu.domain.model.ChapterStatus.QUEUED, queued?.status)
            assertEquals(com.example.zhishu.domain.model.QuizState.NONE, queued?.quizState)

            // v4 的阅读进度与正文数据都还在，备份导入导出读的就是这些表
            assertEquals("b-1", db.readingProgressDao().getForProject("p-1")?.blockId)
            assertEquals(1, db.chapterBlockDao().countByProject("p-1"))
            assertEquals(1, db.quizDao().getByProject("p-1").size)
        } finally {
            db.close()
        }
    }

    /**
     * v5 → v6：正文版本化。
     *
     * 这条测试盯的是「升级不能丢正文、也不能让已经能读的章节变得读不了」：
     * 每章补出初始版本、现有正文与自测题绑到它上面、可读章节的指针指过去、
     * 阅读进度与测验数据一条不少。
     */
    @Test
    fun migrate5To6_bindsExistingBodyToInitialVersion() = runBlocking {
        createV5Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                    AppDatabase.MIGRATION_9_10,
                    AppDatabase.MIGRATION_10_11,
                    AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            // 每个现有章节都有一个初始版本，一章不落
            assertEquals(1, db.chapterVersionDao().countByChapter("ch-1"))
            assertEquals(1, db.chapterVersionDao().countByChapter("ch-2"))
            assertEquals(1, db.chapterVersionDao().countByChapter("ch-3"))

            // 正文可读的章节：版本 PUBLISHED，指针指过去，阅读器立刻能读
            val published = db.chapterPlanDao().getById("ch-1")
            assertEquals("ch-1-v1", published?.activeVersionId)
            val v1 = db.chapterVersionDao().getById("ch-1-v1")
            assertEquals(ChapterVersionStatus.PUBLISHED, v1?.status)
            assertEquals(1, v1?.versionNumber)
            // 已发布章节的 carryOver 装的是「结尾状态」，可以搬进版本
            assertEquals("房子归属仍未决", v1?.endingState)
            assertEquals(1, db.chapterBlockDao().getActiveBlocks("ch-1").size)
            assertEquals("b-1", db.chapterBlockDao().getActiveBlocks("ch-1").first().id)

            // 历史 QUIZ_FAILED（v4→v5 已扶正成 PUBLISHED）同样算可读，指针照样指上
            val quizFailed = db.chapterPlanDao().getById("ch-2")
            assertTrue(quizFailed?.isReadable == true)
            assertEquals("ch-2-v1", quizFailed?.activeVersionId)
            assertEquals(
                ChapterVersionStatus.PUBLISHED,
                db.chapterVersionDao().getById("ch-2-v1")?.status,
            )

            // 还没生成的章节：版本是草稿，指针留空——不能让阅读器打开一段没过核验的正文
            val queued = db.chapterPlanDao().getById("ch-3")
            assertNull(queued?.activeVersionId)
            val draft = db.chapterVersionDao().getById("ch-3-v1")
            assertEquals(ChapterVersionStatus.DRAFT, draft?.status)
            // 未发布章节的 carryOver 是「开头钩子」，不能错当成结尾状态
            assertEquals("", draft?.endingState)

            // 正文一个块都没删，FTS 索引也还在
            assertEquals(1, db.chapterBlockDao().countByProject("p-1"))
            assertEquals(1, db.chapterBlockDao().countFtsByProject("p-1"))
            val repo = DefaultSearchRepository(
                knowledgeNodeDao = db.knowledgeNodeDao(),
                chapterBlockDao = db.chapterBlockDao(),
                sourceChunkDao = db.sourceChunkDao(),
            )
            assertTrue(repo.search("p-1", "正文").any { it.kind == SearchHitKind.CHAPTER_BLOCK })

            // 阅读进度与测验数据保留，自测题绑到初始版本上
            assertEquals("b-1", db.readingProgressDao().getForProject("p-1")?.blockId)
            assertEquals(1, db.quizDao().getByProject("p-1").size)
            assertEquals("ch-1-v1", db.quizDao().getByProject("p-1").first().versionId)
            assertEquals(1, db.quizDao().getActiveByChapter("ch-1").size)
        } finally {
            db.close()
        }
    }

    /**
     * v6 → v7：拆开 carryOver 的双重语义。
     *
     * 一次性把当年那个「靠章节状态猜含义」的规则固化下来：
     * 未发布章节的 carryOver 是开头钩子，已发布章节的 carryOver 是结尾状态。
     */
    @Test
    fun migrate6To7_splitsCarryOverSemantics() = runBlocking {
        createV6Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                    AppDatabase.MIGRATION_9_10,
                    AppDatabase.MIGRATION_10_11,
                    AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            // 已发布章节：carryOver 是结尾状态，落在当前版本上；openingHook 留空
            val published = db.chapterPlanDao().getById("ch-1")
            assertEquals("", published?.openingHook)
            assertEquals("房子归属仍未决", db.chapterVersionDao().getActive("ch-1")?.endingState)

            // 未发布章节：carryOver 是开头钩子，搬进 openingHook；不当成结尾状态
            val queued = db.chapterPlanDao().getById("ch-3")
            assertEquals("开场：法庭外的雨", queued?.openingHook)
            assertEquals("", db.chapterVersionDao().getById("ch-3-v1")?.endingState)

            // 历史列保留，数据没被删——只是不再读也不再写
            assertEquals("房子归属仍未决", published?.carryOver)
            assertEquals("开场：法庭外的雨", queued?.carryOver)

            // 正文、进度、测验一条不少
            assertEquals(1, db.chapterBlockDao().countByProject("p-1"))
            assertEquals(1, db.chapterBlockDao().getActiveBlocks("ch-1").size)
            assertEquals("b-1", db.readingProgressDao().getForProject("p-1")?.blockId)
            assertEquals(1, db.quizDao().getActiveByChapter("ch-1").size)
        } finally {
            db.close()
        }
    }

    /**
     * v7 → v8：分卷字段。
     *
     * 旧数据落在第 0 卷——它们确实就是「第一次规划出来的那一批」，
     * 默认值即正确，不需要回填。这条测试盯的是「加列没把任何东西弄丢」。
     */
    @Test
    fun migrate7To8_addsVolumeColumnsWithoutLosingData() = runBlocking {
        createV7Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            // 旧章节全部落在第一卷
            val plans = db.chapterPlanDao().getByProject("p-1")
            assertEquals(3, plans.size)
            assertTrue(plans.all { it.volumeIndex == 0 })
            assertTrue(plans.all { it.volumeTitle.isEmpty() })

            // 3B 拆出来的开头钩子、3A 的版本指针、正文与进度全都还在
            assertEquals("开场：法庭外的雨", db.chapterPlanDao().getById("ch-3")?.openingHook)
            assertEquals("ch-1-v1", db.chapterPlanDao().getById("ch-1")?.activeVersionId)
            assertEquals(1, db.chapterBlockDao().getActiveBlocks("ch-1").size)
            assertEquals("b-1", db.readingProgressDao().getForProject("p-1")?.blockId)
            assertEquals(1, db.quizDao().getActiveByChapter("ch-1").size)

            // story_bibles 的新列可写可读
            db.storyBibleDao().upsert(
                com.example.zhishu.domain.model.StoryBible(
                    projectId = "p-1",
                    world = "w",
                    currentVolumeIndex = 2,
                    updatedAt = 1L,
                ),
            )
            assertEquals(2, db.storyBibleDao().get("p-1")?.currentVolumeIndex)
        } finally {
            db.close()
        }
    }

    /**
     * v8 → v9：版本系统加固（3D）。三条修正规则各验一遍。
     *
     * 造出来的 v8 库里，ch-1 有三个版本：v1 有正文、v2 有正文、v3 是当前发布版本，
     * 而 v1/v2 在 3D 之前都被写成了 ABANDONED。另外还有一个没有正文的失败尝试。
     */
    @Test
    fun migrate8To9_separatesArchivedFromAbandonedAndBackfillsNodeIds() = runBlocking {
        createV8Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            val byId = db.chapterVersionDao().getByChapter("ch-1").associateBy { it.id }
            // 有正文、比当前版本旧 → 被取代的历史正文
            assertEquals(ChapterVersionStatus.ARCHIVED, byId["ch-1-v1"]?.status)
            assertEquals(ChapterVersionStatus.ARCHIVED, byId["ch-1-v2"]?.status)
            // 当前发布版本一动不动
            assertEquals(ChapterVersionStatus.PUBLISHED, byId["ch-1-v3"]?.status)
            // 没有正文 → 从来没写出来过，仍然是失败尝试
            assertEquals(ChapterVersionStatus.ABANDONED, byId["ch-1-v4"]?.status)

            // 覆盖关系按标题唯一匹配补出 ID
            val plan = db.chapterPlanDao().getById("ch-1")
            assertEquals(listOf("n-1"), plan?.coveredNodeIds)
            assertEquals(listOf("继承"), plan?.coveredTitles)
            assertEquals(listOf("n-1"), byId["ch-1-v3"]?.coveredNodeIds)
            // 对不上任何节点的标题不硬猜：留空，由界面报「待确认」
            assertEquals(emptyList<String>(), db.chapterPlanDao().getById("ch-2")?.coveredNodeIds)
            assertEquals(listOf("一个已经没有的知识点"), db.chapterPlanDao().getById("ch-2")?.coveredTitles)

            // 阅读进度新列可用，且旧位置一个字没动
            val progress = db.readingProgressDao().getForProject("p-1")
            assertEquals("b-1", progress?.blockId)
            assertEquals(24, progress?.blockOffset)
            assertEquals(0L, progress?.bodyUpdatedAt)
            assertFalse(progress?.needsBodyUpdatedNotice == true)

            // 正文、测验一条不少
            assertEquals(3, db.chapterBlockDao().countByProject("p-1"))
            assertEquals(1, db.chapterBlockDao().getActiveBlocks("ch-1").size)
            assertEquals(1, db.quizDao().getActiveByChapter("ch-1").size)
        } finally {
            db.close()
        }
    }

    /**
     * v9 → v10：来源与引用可追溯（4A）。
     *
     * 盯三件事：
     * 1. `chapter_blocks.sourceRefs` 里的引用全部搬进 citations 表，**历史版本也搬**；
     * 2. 对不上现存片段的引用记成 UNCONFIRMED 而不是被丢掉——这正是这张表存在的理由；
     * 3. 旧片段的页码/章节留空，不硬填。硬填会让用户翻到错误的页码。
     */
    @Test
    fun migrate9To10_backfillsCitationsAndKeepsUnmatchedRefs() = runBlocking {
        createV9Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            // 当前版本（ch-1-v3）的引用：一条对上了片段，一条对不上
            val active = db.citationDao().getActiveByChapter("ch-1").sortedBy { it.order }
            assertEquals(2, active.size)
            assertEquals(CitationStatus.RESOLVED, active[0].status)
            assertEquals("c-1", active[0].chunkId)
            // 对不上的那一条留着模型给的原始标识，而不是被删掉
            assertEquals(CitationStatus.UNCONFIRMED, active[1].status)
            assertNull(active[1].chunkId)
            assertEquals("c-已经删掉的资料", active[1].rawRef)

            // 历史版本的引用同样搬了过来（4A 明确要求保留）
            val archived = db.citationDao().getByVersion("ch-1-v2")
            assertEquals(1, archived.size)
            assertEquals("c-1", archived.single().chunkId)
            // 但它们不出现在当前阅读里：上面 getActiveByChapter 只有 2 条
            assertTrue(archived.single().versionId == "ch-1-v2")

            // 反向查询：这个片段被当前正文哪几块引用了
            val backRefs = db.citationDao().getActiveByChunk("p-1", "c-1")
            assertEquals(listOf("b-v3"), backRefs.map { it.blockId })

            // 旧片段的定位留空，不硬猜页码
            val chunk = db.sourceChunkDao().getBySource("src-1").single()
            assertNull(chunk.pageNumber)
            assertNull(chunk.sectionPath)
            assertEquals(0, chunk.paragraphIndex)
            // 没有页码也不至于什么都说不出来：至少能说到第几段
            assertEquals("第 1 段", chunk.locatorLabel)

            // 来源元数据新列可写可读
            db.sourceDocumentDao().updateMeta(
                id = "src-1",
                versionLabel = "第 3 版",
                jurisdiction = "中国大陆",
                effectiveFrom = "2021-01-01",
                legalVersion = "2020 年修正",
            )
            val source = db.sourceDocumentDao().getById("src-1")
            assertEquals("中国大陆", source?.jurisdiction)
            assertEquals("2020 年修正", source?.versionLabelForDisplay)
            assertTrue(source?.isTimeSensitive == true)

            // 正文一块不少，进度一个字没动
            assertEquals(3, db.chapterBlockDao().countByProject("p-1"))
            assertEquals("b-1", db.readingProgressDao().getForProject("p-1")?.blockId)
        } finally {
            db.close()
        }
    }

    /** DOCUMENTS 模式的项目升级上来，版本不会被标成「AI 自备知识」 */
    @Test
    fun migrate9To10_marksModelKnowledgeByProjectMode() = runBlocking {
        createV9Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        try {
            // 种子项目是 DOCUMENTS 模式（v2→v3 的默认值）
            assertFalse(db.chapterVersionDao().getById("ch-1-v3")?.usedModelKnowledge == true)
        } finally {
            db.close()
        }
    }

    /**
     * v10 → v11：核验问题落成一张表（4B）。
     *
     * 盯三件事：
     * 1. 新表建起来且真能写读，`blockId` 可空（定位不到具体段落的问题也要能存）；
     * 2. 旧的 `chapters.verificationNote` **原样保留**，不被拆成一批假的问题记录。
     *    那段文字没有 blockId、没有原始类型，还很可能已经被 `take(5)` 截过，
     *    回填出来的「本章还有 3 个问题」从第一天起就是不可信的数字；
     * 3. 正文、引用、版本、进度一个字都没动。
     */
    @Test
    fun migrate10To11_addsIssueTableWithoutFakingOldIssues() = runBlocking {
        createV10Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            // 旧说明原样留着，而且没有被回填成问题记录
            assertEquals(
                "[contradiction] 继承顺序说错了",
                db.chapterPlanDao().getById("ch-1")?.verificationNote,
            )
            assertTrue(db.verificationIssueDao().getAllVersionsByProject("p-1").isEmpty())

            // 新表可写可读：一条能定位到段落，一条定位不到
            db.verificationIssueDao().upsertAll(
                listOf(
                    VerificationIssue(
                        id = VerificationIssue.idFor("ch-1-v3", 0),
                        projectId = "p-1",
                        chapterId = "ch-1",
                        versionId = "ch-1-v3",
                        blockId = "b-v3",
                        type = IssueType.CONTRADICTION,
                        rawType = "contradiction",
                        message = "这里的顺序与资料不符",
                        suggestedFix = "改成配偶、子女、父母",
                        createdAt = 1L,
                    ),
                    VerificationIssue(
                        id = VerificationIssue.idFor("ch-1-v3", 1),
                        projectId = "p-1",
                        chapterId = "ch-1",
                        versionId = "ch-1-v3",
                        blockId = null,
                        type = IssueType.BROKEN_DERIVATION,
                        message = "全章的推演在中段断了一次",
                        createdAt = 1L,
                    ),
                ),
            )
            val active = db.verificationIssueDao().getActiveByChapter("ch-1")
            assertEquals(2, active.size)
            assertEquals("b-v3", active[0].blockId)
            assertTrue(active[0].isBlockScoped)
            // 定位不到段落的问题存得下，而且明确不能单块重写
            assertNull(active[1].blockId)
            assertFalse(active[1].isBlockScoped)
            assertEquals(2, db.verificationIssueDao().countActiveOpen("ch-1", IssueResolution.OPEN))

            // 版本行的两个新列：旧版本都是整章生成的，没有派生来源
            val version = db.chapterVersionDao().getById("ch-1-v3")
            assertNull(version?.derivedFromVersionId)
            assertEquals(emptyList<String>(), version?.rewrittenBlockIds)
            assertFalse(version?.isPartialRewrite == true)

            // 4A 建立的引用一条不少，正文一块不少，进度一个字没动
            assertEquals(2, db.citationDao().getActiveByChapter("ch-1").size)
            assertEquals(3, db.chapterBlockDao().countByProject("p-1"))
            assertEquals("b-1", db.readingProgressDao().getForProject("p-1")?.blockId)
        } finally {
            db.close()
        }
    }

    /**
     * v11 → v12：引用增加「来源已删除」状态与被删资料标题（4C）。
     *
     * 盯三件事：
     * 1. 新列建起来、有默认值，旧引用一条不少也一条没被改状态；
     * 2. **存量 UNCONFIRMED 不回填成 SOURCE_DELETED**。库里那条降级过的引用和
     *    一条从没对上的引用现在长得一模一样，无从判断；全判成「你自己删了资料」
     *    会把模型编的出处说成用户的操作，这比说得保守一点糟得多；
     * 3. 新的删除操作从这一版起分得清，而且标题抄得下来。
     */
    @Test
    fun migrate11To12_addsDeletedSourceStateWithoutRelabelingOldRows() = runBlocking {
        createV11Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            // 旧引用一条不少，状态一个没动，新列取默认空串
            val active = db.citationDao().getActiveByChapter("ch-1").sortedBy { it.order }
            assertEquals(2, active.size)
            assertEquals(CitationStatus.RESOLVED, active[0].status)
            assertEquals("", active[0].lostSourceTitle)
            // 存量「未能匹配」保持原状，没有被说成「资料已删除」
            assertEquals(CitationStatus.UNCONFIRMED, active[1].status)
            assertEquals("", active[1].lostSourceTitle)

            // 从这一版起，删资料能分得清，而且标题在删除前抄了下来
            db.citationDao().unconfirmForSource("src-1", CitationStatus.SOURCE_DELETED)
            db.sourceChunkDao().deleteBySource("src-1")
            db.sourceDocumentDao().deleteById("src-1")

            val afterDelete = db.citationDao().getActiveByChapter("ch-1").sortedBy { it.order }
            assertEquals(CitationStatus.SOURCE_DELETED, afterDelete[0].status)
            assertEquals("笔记", afterDelete[0].lostSourceTitle)
            assertNull(afterDelete[0].chunkId)
            // 那条从没对上的引用不受影响：它跟这份资料没有关系
            assertEquals(CitationStatus.UNCONFIRMED, afterDelete[1].status)
            assertEquals("", afterDelete[1].lostSourceTitle)

            // 正文一块不少，进度一个字没动
            assertEquals(3, db.chapterBlockDao().countByProject("p-1"))
            assertEquals("b-1", db.readingProgressDao().getForProject("p-1")?.blockId)
        } finally {
            db.close()
        }
    }

    /**
     * v12 → v13（→ v14）：节点获得 nodeKey 与生成状态。
     *
     * v12 还没有批次表、节点也没有 v13 字段。迁移后：
     * 1. 旧节点默认 generateStatus=COMPLETED（旧数据是一次性生成的完整节点，
     *    不能因为缺状态被误判成「待展开」）、nodeKey 空；
     * 2. 无完成批次 → 节点 batchId 保持空串（legacy），通过 active 过滤查询仍可见；
     * 3. 旧数据读取不受影响：引用、正文、进度照常。
     */
    @Test
    fun migrate12To13_addsNodeKeyAndBatchState() = runBlocking {
        createV12Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_12_13, AppDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            // 旧节点：状态默认 COMPLETED、nodeKey 空、无完成批次时 batchId 保持空串
            val node = db.knowledgeNodeDao().getById("n-1")
            assertEquals("继承", node?.title)
            assertEquals(NodeGenerateStatus.COMPLETED.name, node?.generateStatus)
            assertEquals("", node?.nodeKey)
            assertEquals("", node?.batchId)

            // legacy 节点（batchId=''）通过 active 过滤查询仍然可见
            assertEquals(1, db.knowledgeNodeDao().getByProject("p-1").size)

            // 旧数据读取不受影响
            assertEquals(2, db.citationDao().getActiveByChapter("ch-1").size)
            assertEquals(3, db.chapterBlockDao().countByProject("p-1"))
            assertEquals("b-1", db.readingProgressDao().getForProject("p-1")?.blockId)
        } finally {
            db.close()
        }
    }

    /**
     * v13 → v14：已完成批次置 active、其节点归属回填（旧地图在切换模型下仍可见）。
     */
    @Test
    fun migrate13To14_activatesCompletedBatchAndBackfillsNodeOwnership() = runBlocking {
        createV13Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(14, db.openHelper.readableDatabase.version)

            // 已完成批次被置 active：旧地图通过 active 过滤查询可见
            val batch = db.knowledgeMapBatchDao().getById("batch-1")
            assertEquals(true, batch?.active)
            assertEquals(1, db.knowledgeNodeDao().getByProject("p-1").size)

            // 节点归属回填到该批次，nodeKey/generateStatus 原样保留
            val node = db.knowledgeNodeDao().getById("n-1")
            assertEquals("batch-1", node?.batchId)
            assertEquals("nk-1", node?.nodeKey)
            assertEquals(NodeGenerateStatus.COMPLETED.name, node?.generateStatus)
        } finally {
            db.close()
        }
    }

    private fun createV1Database() {        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V1_SCHEMA.forEach(db::execSQL)
                V1_SEED.forEach(db::execSQL)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 1 }
        helper.close()
    }

    /**
     * 造出一个真正的 v2 库：先建 v1 结构，再手工执行 MIGRATION_1_2 并把版本号置为 2。
     * 不经过 Room.databaseBuilder，避免它因为目标版本是 3、只给了 1→2 而尝试一步 1→3。
     */
    private fun createV2Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V1_SCHEMA.forEach(db::execSQL)
                V1_SEED.forEach(db::execSQL)
                AppDatabase.MIGRATION_1_2.migrate(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 2 }
        helper.close()
    }

    /** 造出一个真正的 v3 库：v1 结构 + 依次执行 1→2、2→3，版本号置为 3。 */
    private fun createV3Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V1_SCHEMA.forEach(db::execSQL)
                V1_SEED.forEach(db::execSQL)
                db.execSQL(
                    "INSERT INTO reading_progress VALUES ('p-1','p-1','ch-1',NULL,120,1)",
                )
                AppDatabase.MIGRATION_1_2.migrate(db)
                AppDatabase.MIGRATION_2_3.migrate(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 3 }
        helper.close()
    }

    /**
     * 造出一个真正的 v4 库：v1 结构 + 依次执行 1→2、2→3、3→4，版本号置为 4。
     * 额外塞进三种章节：有题的、历史 QUIZ_FAILED 的、还没生成的。
     */
    private fun createV4Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V1_SCHEMA.forEach(db::execSQL)
                V1_SEED.forEach(db::execSQL)
                db.execSQL(
                    "INSERT INTO chapters VALUES ('ch-2','p-1',2,'第二章','','[]','[]',1200," +
                        "'QUIZ_FAILED','摘要','正文已通过核验，但测验未生成：题目不足')",
                )
                db.execSQL(
                    "INSERT INTO chapters VALUES ('ch-3','p-1',3,'第三章','','[]','[]',1200,'QUEUED','','')",
                )
                AppDatabase.MIGRATION_1_2.migrate(db)
                AppDatabase.MIGRATION_2_3.migrate(db)
                AppDatabase.MIGRATION_3_4.migrate(db)
                // v4 的块级阅读进度
                db.execSQL(
                    "INSERT INTO reading_progress (id, projectId, chapterId, blockId, charOffset, " +
                        "updatedAt, blockOffset) VALUES ('p-1','p-1','ch-1','b-1',0,1,24)",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 4 }
        helper.close()
    }

    /**
     * 造出一个真正的 v5 库：v1 结构 + 依次执行 1→2、2→3、3→4、4→5，版本号置为 5。
     * 三种章节都在：已发布有题的、历史 QUIZ_FAILED 的（4→5 已扶正）、还没生成的。
     */
    private fun createV5Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedV5(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 5 }
        helper.close()
    }

    /** v5 库的内容：v1 结构 + 依次执行 1→2、2→3、3→4、4→5，外加三种章节与块级阅读进度 */
    private fun seedV5(db: SupportSQLiteDatabase) {
        V1_SCHEMA.forEach(db::execSQL)
        V1_SEED.forEach(db::execSQL)
        db.execSQL(
            "INSERT INTO chapters VALUES ('ch-2','p-1',2,'第二章','','[]','[]',1200," +
                "'QUIZ_FAILED','摘要','正文已通过核验，但测验未生成：题目不足')",
        )
        db.execSQL(
            "INSERT INTO chapters VALUES ('ch-3','p-1',3,'第三章','','[]','[]',1200,'QUEUED','','')",
        )
        AppDatabase.MIGRATION_1_2.migrate(db)
        AppDatabase.MIGRATION_2_3.migrate(db)
        AppDatabase.MIGRATION_3_4.migrate(db)
        AppDatabase.MIGRATION_4_5.migrate(db)
        // carryOver 的双重语义：已发布章节里它是结尾状态，未发布章节里它是开头钩子
        db.execSQL("UPDATE chapters SET carryOver = '房子归属仍未决' WHERE id = 'ch-1'")
        db.execSQL("UPDATE chapters SET carryOver = '开场：法庭外的雨' WHERE id = 'ch-3'")
        db.execSQL(
            "INSERT INTO reading_progress (id, projectId, chapterId, blockId, charOffset, " +
                "updatedAt, blockOffset) VALUES ('p-1','p-1','ch-1','b-1',0,1,24)",
        )
    }

    /** 造出一个真正的 v6 库：v5 库 + 执行 5→6，版本号置为 6。 */
    private fun createV6Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedV5(db)
                AppDatabase.MIGRATION_5_6.migrate(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 6 }
        helper.close()
    }

    /** 造出一个真正的 v7 库：v5 库 + 依次执行 5→6、6→7，版本号置为 7。 */
    private fun createV7Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(7) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedV5(db)
                AppDatabase.MIGRATION_5_6.migrate(db)
                AppDatabase.MIGRATION_6_7.migrate(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 7 }
        helper.close()
    }

    /**
     * 造出一个真正的 v8 库：v5 库 + 依次执行 5→6、6→7、7→8，版本号置为 8。
     *
     * 额外造出「多版本正文」这个 3D 要修正的局面：v5→v6 只会给每章建一个 v1，
     * 这里手工再加 v2/v3/v4，并且按 3D 之前的写法把被取代的旧版本记成 ABANDONED。
     */
    private fun createV8Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedV5(db)
                AppDatabase.MIGRATION_5_6.migrate(db)
                AppDatabase.MIGRATION_6_7.migrate(db)
                AppDatabase.MIGRATION_7_8.migrate(db)
                seedV8MultiVersionChapter(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 8 }
        helper.close()
    }

    /**
     * 造出一个真正的 v9 库：v8 库 + 执行 8→9，版本号置为 9。
     *
     * 额外给正文块塞上 4A 之前那种引用形态：一个指向真实片段，
     * 一个指向已经不存在的片段——后者在旧库里就是几个查不到的 ID，
     * 迁移之后要变成一条能说出口的「未确认引用」。
     */
    private fun createV9Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(9) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedV5(db)
                AppDatabase.MIGRATION_5_6.migrate(db)
                AppDatabase.MIGRATION_6_7.migrate(db)
                AppDatabase.MIGRATION_7_8.migrate(db)
                seedV8MultiVersionChapter(db)
                AppDatabase.MIGRATION_8_9.migrate(db)
                // 当前版本引了一个真片段和一个已经删掉的资料；历史版本只引真片段
                db.execSQL(
                    "UPDATE chapter_blocks SET sourceRefs = '[\"c-1\",\"c-已经删掉的资料\"]' " +
                        "WHERE id = 'b-v3'",
                )
                db.execSQL("UPDATE chapter_blocks SET sourceRefs = '[\"c-1\"]' WHERE id = 'b-v2'")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 9 }
        helper.close()
    }

    /**
     * 造出一个真正的 v10 库：v9 库 + 执行 9→10，版本号置为 10。
     *
     * 顺带塞一条 4B 之前那种核验说明：一整章的问题被拼成一个字符串存在
     * `chapters.verificationNote` 上。迁移之后它必须原样还在。
     */
    /** 造一个真正的 v11 库：v10 结构 + 执行 10→11，版本号置为 11。 */
    private fun createV11Database() {        val callback = object : SupportSQLiteOpenHelper.Callback(11) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedV5(db)
                AppDatabase.MIGRATION_5_6.migrate(db)
                AppDatabase.MIGRATION_6_7.migrate(db)
                AppDatabase.MIGRATION_7_8.migrate(db)
                seedV8MultiVersionChapter(db)
                AppDatabase.MIGRATION_8_9.migrate(db)
                db.execSQL(
                    "UPDATE chapter_blocks SET sourceRefs = '[\"c-1\",\"c-已经删掉的资料\"]' " +
                        "WHERE id = 'b-v3'",
                )
                db.execSQL("UPDATE chapter_blocks SET sourceRefs = '[\"c-1\"]' WHERE id = 'b-v2'")
                AppDatabase.MIGRATION_9_10.migrate(db)
                AppDatabase.MIGRATION_10_11.migrate(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 11 }
        helper.close()
    }

    /**
     * v12 的真实数据形态：v11 基础上经过 11→12（citations 增加 lostSourceTitle）。
     * 注意 v12 还没有 knowledge_map_batches 表（那是 v13 才建的）、
     * knowledge_nodes 也还没有 nodeKey/generateStatus——这正是迁移要补的。
     */
    private fun createV12Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(12) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedV5(db)
                AppDatabase.MIGRATION_5_6.migrate(db)
                AppDatabase.MIGRATION_6_7.migrate(db)
                AppDatabase.MIGRATION_7_8.migrate(db)
                seedV8MultiVersionChapter(db)
                AppDatabase.MIGRATION_8_9.migrate(db)
                db.execSQL(
                    "UPDATE chapter_blocks SET sourceRefs = '[\"c-1\",\"c-已经删掉的资料\"]' " +
                        "WHERE id = 'b-v3'",
                )
                db.execSQL("UPDATE chapter_blocks SET sourceRefs = '[\"c-1\"]' WHERE id = 'b-v2'")
                AppDatabase.MIGRATION_9_10.migrate(db)
                AppDatabase.MIGRATION_10_11.migrate(db)
                AppDatabase.MIGRATION_11_12.migrate(db)
                // 一条引用已被降级为「资料已删除」（4C 之后的新数据形态）
                db.execSQL(
                    "UPDATE citations SET status = 'SOURCE_DELETED', lostSourceTitle = '笔记' " +
                        "WHERE chunkId = 'c-已经删掉的资料'",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 12 }
        helper.close()
    }

    /**
     * v13 的真实数据形态：v12 + 12→13（节点带 nodeKey/generateStatus、建批次表），
     * 并带一个「已完成的知识地图批次」——v14 迁移应把节点归属回填、批次置 active。
     */
    private fun createV13Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(13) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedV5(db)
                AppDatabase.MIGRATION_5_6.migrate(db)
                AppDatabase.MIGRATION_6_7.migrate(db)
                AppDatabase.MIGRATION_7_8.migrate(db)
                seedV8MultiVersionChapter(db)
                AppDatabase.MIGRATION_8_9.migrate(db)
                db.execSQL(
                    "UPDATE chapter_blocks SET sourceRefs = '[\"c-1\",\"c-已经删掉的资料\"]' " +
                        "WHERE id = 'b-v3'",
                )
                db.execSQL("UPDATE chapter_blocks SET sourceRefs = '[\"c-1\"]' WHERE id = 'b-v2'")
                AppDatabase.MIGRATION_9_10.migrate(db)
                AppDatabase.MIGRATION_10_11.migrate(db)
                AppDatabase.MIGRATION_11_12.migrate(db)
                AppDatabase.MIGRATION_12_13.migrate(db)
                // v13 形态：批次表已存在，一个已完成批次；节点带 v13 字段
                db.execSQL(
                    "INSERT INTO knowledge_map_batches (id, projectId, skeletonDone, chainsJson, " +
                        "chainStatuses, previewGaps, previewConflicts, auditDone, auditFailed, " +
                        "completed, failed, createdAt, updatedAt) VALUES " +
                        "('batch-1','p-1',1,'[]','[]','[]','[]',1,0,1,0,1,1)",
                )
                db.execSQL(
                    "UPDATE knowledge_nodes SET nodeKey = 'nk-1', generateStatus = 'COMPLETED' WHERE id = 'n-1'",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 13 }
        helper.close()
    }

    private fun createV10Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(10) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedV5(db)
                AppDatabase.MIGRATION_5_6.migrate(db)
                AppDatabase.MIGRATION_6_7.migrate(db)
                AppDatabase.MIGRATION_7_8.migrate(db)
                seedV8MultiVersionChapter(db)
                AppDatabase.MIGRATION_8_9.migrate(db)
                db.execSQL(
                    "UPDATE chapter_blocks SET sourceRefs = '[\"c-1\",\"c-已经删掉的资料\"]' " +
                        "WHERE id = 'b-v3'",
                )
                db.execSQL("UPDATE chapter_blocks SET sourceRefs = '[\"c-1\"]' WHERE id = 'b-v2'")
                AppDatabase.MIGRATION_9_10.migrate(db)
                db.execSQL(
                    "UPDATE chapters SET verificationNote = '[contradiction] 继承顺序说错了' WHERE id = 'ch-1'",
                )
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build(),
        )
        helper.writableDatabase.use { it.version = 10 }
        helper.close()
    }

    /**
     * 3D 之前的真实数据形态：一章被重生成过两次，
     * 旧版本（成功过、被取代）和失败尝试都记成 ABANDONED，从数据上分不出来。
     */
    private fun seedV8MultiVersionChapter(db: SupportSQLiteDatabase) {
        fun version(number: Int, status: String) = db.execSQL(
            "INSERT INTO chapter_versions (id, chapterId, projectId, versionNumber, status, title, " +
                "summary, endingState, coveredTitles, unverifiedClaims, verificationNote, " +
                "createdAt, updatedAt) VALUES " +
                "('ch-1-v$number','ch-1','p-1',$number,'$status','第一章','摘要','结尾'," +
                "'[\"继承\"]','[]','',1,1)",
        )
        // v1 已由 5→6 建好，这里补 v2/v3/v4
        db.execSQL("UPDATE chapter_versions SET status = 'ABANDONED' WHERE id = 'ch-1-v1'")
        version(2, "ABANDONED")
        version(3, "PUBLISHED")
        // v4：没有任何正文块，是一次真正没写出来的失败尝试
        version(4, "ABANDONED")
        db.execSQL("UPDATE chapters SET activeVersionId = 'ch-1-v3' WHERE id = 'ch-1'")
        // v1 的正文块由 5→6 绑好了；给 v2 与 v3 各补一块
        db.execSQL(
            "INSERT INTO chapter_blocks (id, chapterId, versionId, projectId, [order], text, " +
                "blockType, knowledgeNodeIds, sourceRefs) VALUES " +
                "('b-v2','ch-1','ch-1-v2','p-1',0,'第二版正文','STORY','[]','[]')",
        )
        db.execSQL(
            "INSERT INTO chapter_blocks (id, chapterId, versionId, projectId, [order], text, " +
                "blockType, knowledgeNodeIds, sourceRefs) VALUES " +
                "('b-v3','ch-1','ch-1-v3','p-1',0,'第三版正文','STORY','[]','[]')",
        )
        db.execSQL("UPDATE quizzes SET versionId = 'ch-1-v3' WHERE chapterId = 'ch-1'")
        // 知识地图里只有「继承」一个节点
        db.execSQL(
            "INSERT INTO knowledge_nodes (id, projectId, parentId, title, definition, prerequisites, " +
                "related, confusions, examples, sourceRefs, chapterIds, mastery, confirmed, depth, " +
                "[order], arisesFrom, leadsTo) VALUES " +
                "('n-1','p-1',NULL,'继承','财产转移制度','[]','[]','[]','[]','[]','[]',0,1,1,0,'','')",
        )
        // 覆盖关系：ch-1 的标题能对上知识点，ch-2 的对不上（模拟改过名）
        db.execSQL("UPDATE chapters SET coveredTitles = '[\"继承\"]' WHERE id = 'ch-1'")
        db.execSQL("UPDATE chapters SET coveredTitles = '[\"一个已经没有的知识点\"]' WHERE id = 'ch-2'")
    }

    private companion object {
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `projects` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`topic` TEXT NOT NULL, `baseline` TEXT NOT NULL, `depth` TEXT NOT NULL, " +
                "`dailyMinutes` INTEGER NOT NULL, `targetDays` INTEGER NOT NULL, `genre` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `sources` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `kind` TEXT NOT NULL, `fileName` TEXT, `url` TEXT, " +
                "`fetchedAt` INTEGER NOT NULL, `versionLabel` TEXT, `contentHash` TEXT NOT NULL, " +
                "`parseStatus` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `source_chunks` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                "`ordinal` INTEGER NOT NULL, `text` TEXT NOT NULL, `locator` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `knowledge_nodes` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
                "`parentId` TEXT, `title` TEXT NOT NULL, `definition` TEXT NOT NULL, " +
                "`prerequisites` TEXT NOT NULL, `related` TEXT NOT NULL, `confusions` TEXT NOT NULL, " +
                "`examples` TEXT NOT NULL, `sourceRefs` TEXT NOT NULL, `chapterIds` TEXT NOT NULL, " +
                "`mastery` REAL NOT NULL, `confirmed` INTEGER NOT NULL, `depth` INTEGER NOT NULL, " +
                "`order` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `chapters` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
                "`order` INTEGER NOT NULL, `title` TEXT NOT NULL, `plotGoal` TEXT NOT NULL, " +
                "`learningGoals` TEXT NOT NULL, `nodeIds` TEXT NOT NULL, `wordTarget` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, `summary` TEXT NOT NULL, `verificationNote` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `chapter_blocks` (`id` TEXT NOT NULL, `chapterId` TEXT NOT NULL, " +
                "`order` INTEGER NOT NULL, `text` TEXT NOT NULL, `blockType` TEXT NOT NULL, " +
                "`knowledgeNodeIds` TEXT NOT NULL, `sourceRefs` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `quizzes` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
                "`chapterId` TEXT NOT NULL, `order` INTEGER NOT NULL, `question` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, `options` TEXT NOT NULL, `answer` TEXT NOT NULL, " +
                "`explanation` TEXT NOT NULL, `sourceRefs` TEXT NOT NULL, `nodeIds` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `answer_records` (`id` TEXT NOT NULL, `quizId` TEXT NOT NULL, " +
                "`projectId` TEXT NOT NULL, `isCorrect` INTEGER NOT NULL, `answeredAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `review_items` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
                "`nodeId` TEXT NOT NULL, `dueAt` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `reading_progress` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
                "`chapterId` TEXT NOT NULL, `blockId` TEXT, `charOffset` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `model_configs` (`role` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, " +
                "`modelName` TEXT NOT NULL, `maxTokens` INTEGER NOT NULL, `temperature` REAL NOT NULL, " +
                "`supportsJson` INTEGER NOT NULL, `keyRef` TEXT, PRIMARY KEY(`role`))",
        )

        val V1_SEED = listOf(
            "INSERT INTO projects VALUES ('p-1','民法基础','民法','入门','入门',20,30,'悬疑','DRAFT',1,1)",
            "INSERT INTO sources VALUES ('src-1','p-1','笔记','TEXT',NULL,NULL,1,NULL,'h','PARSED')",
            "INSERT INTO source_chunks VALUES ('c-1','src-1',0,'意思表示是核心要素。',NULL)",
            "INSERT INTO chapters VALUES ('ch-1','p-1',1,'章','','[]','[]',1200,'PUBLISHED','','')",
            "INSERT INTO chapter_blocks VALUES ('b-1','ch-1',0,'正文','STORY','[]','[]')",
            "INSERT INTO quizzes VALUES ('q-1','p-1','ch-1',0,'问？','TRUE_FALSE','[\"对\",\"错\"]','对','','[]','[]')",
            "INSERT INTO answer_records VALUES ('a-1','q-1','p-1',1,1)",
            "INSERT INTO answer_records VALUES ('a-2','q-1','p-1',0,2)",
        )
    }
}
