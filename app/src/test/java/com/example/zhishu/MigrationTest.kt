package com.example.zhishu

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.search.DefaultSearchRepository
import com.example.zhishu.search.SearchHitKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(5, db.openHelper.readableDatabase.version)

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
            )
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(5, db.openHelper.readableDatabase.version)
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
            .addMigrations(AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(5, db.openHelper.readableDatabase.version)
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
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(5, db.openHelper.readableDatabase.version)

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

    private fun createV1Database() {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
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
