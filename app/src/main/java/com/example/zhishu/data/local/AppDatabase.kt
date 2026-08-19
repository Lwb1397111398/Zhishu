package com.example.zhishu.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.zhishu.data.local.dao.AnswerRecordDao
import com.example.zhishu.data.local.dao.ChapterBlockDao
import com.example.zhishu.data.local.dao.ChapterPlanDao
import com.example.zhishu.data.local.dao.KnowledgeConflictDao
import com.example.zhishu.data.local.dao.KnowledgeGapDao
import com.example.zhishu.data.local.dao.KnowledgeNodeDao
import com.example.zhishu.data.local.dao.ModelConfigDao
import com.example.zhishu.data.local.dao.ProjectDao
import com.example.zhishu.data.local.dao.QuizDao
import com.example.zhishu.data.local.dao.ReadingProgressDao
import com.example.zhishu.data.local.dao.ReviewItemDao
import com.example.zhishu.data.local.dao.SourceChunkDao
import com.example.zhishu.data.local.dao.SourceDocumentDao
import com.example.zhishu.data.local.dao.StoryBibleDao
import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterBlockFts
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ModelConfig
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceChunkFts
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.StoryBible
import com.example.zhishu.search.SearchTokenizer

@Database(
    entities = [
        Project::class,
        SourceDocument::class,
        SourceChunk::class,
        SourceChunkFts::class,
        KnowledgeNode::class,
        KnowledgeGap::class,
        KnowledgeConflict::class,
        ChapterPlan::class,
        ChapterBlock::class,
        ChapterBlockFts::class,
        Quiz::class,
        AnswerRecord::class,
        ReviewItem::class,
        ReadingProgress::class,
        ModelConfig::class,
        StoryBible::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun sourceDocumentDao(): SourceDocumentDao
    abstract fun sourceChunkDao(): SourceChunkDao
    abstract fun knowledgeNodeDao(): KnowledgeNodeDao
    abstract fun knowledgeGapDao(): KnowledgeGapDao
    abstract fun knowledgeConflictDao(): KnowledgeConflictDao
    abstract fun chapterPlanDao(): ChapterPlanDao
    abstract fun chapterBlockDao(): ChapterBlockDao
    abstract fun quizDao(): QuizDao
    abstract fun answerRecordDao(): AnswerRecordDao
    abstract fun reviewItemDao(): ReviewItemDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun modelConfigDao(): ModelConfigDao
    abstract fun storyBibleDao(): StoryBibleDao

    companion object {
        private const val DATABASE_NAME = "zhishu.db"

        /**
         * v1 → v2：为 source_chunks / chapter_blocks 增加 projectId 冗余列并回填，
         * 补齐索引，新增知识缺口与冲突表、正文与资料的 FTS 索引，
         * 并去掉 answer_records 的重复答题记录（N6/N12/B12/B20/P2）。
         * 不使用 fallbackToDestructiveMigration，避免用户数据被清空。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // source_chunks.projectId
                db.execSQL("ALTER TABLE source_chunks ADD COLUMN projectId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE source_chunks SET projectId = COALESCE(" +
                        "(SELECT sources.projectId FROM sources WHERE sources.id = source_chunks.sourceId), '')",
                )

                // chapter_blocks.projectId
                db.execSQL("ALTER TABLE chapter_blocks ADD COLUMN projectId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE chapter_blocks SET projectId = COALESCE(" +
                        "(SELECT chapters.projectId FROM chapters WHERE chapters.id = chapter_blocks.chapterId), '')",
                )

                // 索引
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sources_projectId ON sources (projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sources_contentHash ON sources (contentHash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_source_chunks_sourceId ON source_chunks (sourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_source_chunks_projectId ON source_chunks (projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_nodes_projectId ON knowledge_nodes (projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapters_projectId ON chapters (projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_blocks_chapterId ON chapter_blocks (chapterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_blocks_projectId ON chapter_blocks (projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quizzes_projectId ON quizzes (projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_quizzes_chapterId ON quizzes (chapterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_items_projectId ON review_items (projectId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_review_items_nodeId ON review_items (nodeId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_progress_projectId ON reading_progress (projectId)")

                // answer_records：先去重再建唯一索引，保证一题一条记录
                db.execSQL(
                    "DELETE FROM answer_records WHERE rowid NOT IN " +
                        "(SELECT MAX(rowid) FROM answer_records GROUP BY quizId)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_answer_records_quizId ON answer_records (quizId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_answer_records_projectId ON answer_records (projectId)")

                // 知识缺口与冲突
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS knowledge_gaps (" +
                        "id TEXT NOT NULL, projectId TEXT NOT NULL, description TEXT NOT NULL, " +
                        "`order` INTEGER NOT NULL, PRIMARY KEY(id))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_knowledge_gaps_projectId ON knowledge_gaps (projectId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS knowledge_conflicts (" +
                        "id TEXT NOT NULL, projectId TEXT NOT NULL, topic TEXT NOT NULL, " +
                        "sources TEXT NOT NULL, description TEXT NOT NULL, `order` INTEGER NOT NULL, " +
                        "PRIMARY KEY(id))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_knowledge_conflicts_projectId ON knowledge_conflicts (projectId)",
                )

                // 全文索引表并回填现有内容（P2）
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `chapter_block_fts` USING FTS4(" +
                        "`blockId` TEXT NOT NULL, `projectId` TEXT NOT NULL, `body` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `source_chunk_fts` USING FTS4(" +
                        "`chunkId` TEXT NOT NULL, `projectId` TEXT NOT NULL, `body` TEXT NOT NULL)",
                )
                backfillFts(
                    db = db,
                    sourceTable = "chapter_blocks",
                    ftsTable = "chapter_block_fts",
                    idColumn = "blockId",
                )
                backfillFts(
                    db = db,
                    sourceTable = "source_chunks",
                    ftsTable = "source_chunk_fts",
                    idColumn = "chunkId",
                )
            }

            /** 逐行读出原文，按分词规则写入 FTS 表 */
            private fun backfillFts(
                db: SupportSQLiteDatabase,
                sourceTable: String,
                ftsTable: String,
                idColumn: String,
            ) {
                db.query("SELECT id, projectId, text FROM $sourceTable").use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val projectId = cursor.getString(1)
                        val body = SearchTokenizer.indexBody(cursor.getString(2))
                        db.execSQL(
                            "INSERT INTO $ftsTable ($idColumn, projectId, body) VALUES (?, ?, ?)",
                            arrayOf(id, projectId, body),
                        )
                    }
                }
            }
        }

        /**
         * v2 → v3：无资料模式与滚动记忆。
         *
         * - projects 增加 sourceMode / resolvedGenre（问题3、问题6）
         * - chapters 增加 derivationChain / carryOver / coveredTitles / unverifiedClaims（问题5、问题7）
         * - 新增 story_bibles 表保存全局设定与滚动记忆，让单章上下文与已生成章数无关（问题5）
         *
         * 列类型与默认值必须与实体声明完全一致，否则 Room 的 schema 校验会失败。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE projects ADD COLUMN sourceMode TEXT NOT NULL DEFAULT 'DOCUMENTS'")
                db.execSQL("ALTER TABLE projects ADD COLUMN resolvedGenre TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE chapters ADD COLUMN derivationChain TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapters ADD COLUMN carryOver TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapters ADD COLUMN coveredTitles TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapters ADD COLUMN unverifiedClaims TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE knowledge_nodes ADD COLUMN arisesFrom TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE knowledge_nodes ADD COLUMN leadsTo TEXT NOT NULL DEFAULT ''")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS story_bibles (" +
                        "projectId TEXT NOT NULL, world TEXT NOT NULL, conflict TEXT NOT NULL, " +
                        "style TEXT NOT NULL, approach TEXT NOT NULL, characters TEXT NOT NULL, " +
                        "throughLine TEXT NOT NULL, runningState TEXT NOT NULL, " +
                        "coveredTitles TEXT NOT NULL, openThreads TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, PRIMARY KEY(projectId))",
                )
            }
        }

        /**
         * v3 → v4：阅读进度改为块级定位（第一阶段：阅读闭环）。
         *
         * reading_progress 增加 blockOffset。原来的 charOffset 保存的是整章滚动像素值，
         * 换机型或字号后就对不上，也和搜索用的字符位置不是同一坐标系；
         * 现在改成 blockId（停在哪块）+ blockOffset（块内偏移），charOffset 保留不再写入。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN blockOffset INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 → v5：正文发布与测验解耦（第二阶段：生成任务可靠性）。
         *
         * chapters 增加 quizState / quizNote 两列。原来「出题成功才 PUBLISHED」，
         * 出题一失败章节就停在 QUIZ_FAILED，正文明明写好核验也过了却不算可读——
         * 这与「故事是主流程、测验是可选辅助」相反。现在正文过核验即 PUBLISHED，
         * 出题成败只落在 quizState 上。
         *
         * 已有的 QUIZ_FAILED 章节顺手扶正：状态改成 PUBLISHED（正文本来就通过了核验），
         * quizState 记 FAILED，原来的核验说明搬到 quizNote。数据不丢，只是各归各位。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN quizState TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE chapters ADD COLUMN quizNote TEXT NOT NULL DEFAULT ''")
                // 有题的章节标 READY，让「重新生成自测」按钮不会对已有题的章节乱提示
                db.execSQL(
                    "UPDATE chapters SET quizState = 'READY' " +
                        "WHERE id IN (SELECT DISTINCT chapterId FROM quizzes)",
                )
                // 历史 QUIZ_FAILED：正文可读，失败信息移到测验列
                db.execSQL(
                    "UPDATE chapters SET quizNote = verificationNote, verificationNote = '', " +
                        "quizState = 'FAILED', status = 'PUBLISHED' WHERE status = 'QUIZ_FAILED'",
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
