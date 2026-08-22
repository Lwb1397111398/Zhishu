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
import com.example.zhishu.data.local.dao.ChapterVersionDao
import com.example.zhishu.data.local.dao.CitationDao
import com.example.zhishu.data.local.dao.KnowledgeConflictDao
import com.example.zhishu.data.local.dao.KnowledgeGapDao
import com.example.zhishu.data.local.dao.KnowledgeMapBatchDao
import com.example.zhishu.data.local.dao.KnowledgeNodeDao
import com.example.zhishu.data.local.dao.ModelConfigDao
import com.example.zhishu.data.local.dao.ProjectDao
import com.example.zhishu.data.local.dao.QuizDao
import com.example.zhishu.data.local.dao.ReadingProgressDao
import com.example.zhishu.data.local.dao.ReviewItemDao
import com.example.zhishu.data.local.dao.SourceChunkDao
import com.example.zhishu.data.local.dao.SourceDocumentDao
import com.example.zhishu.data.local.dao.StoryBibleDao
import com.example.zhishu.data.local.dao.VerificationIssueDao
import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterBlockFts
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeMapBatch
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
import com.example.zhishu.domain.model.VerificationIssue
import com.example.zhishu.search.SearchTokenizer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Database(
    entities = [
        Project::class,
        SourceDocument::class,
        SourceChunk::class,
        SourceChunkFts::class,
        KnowledgeNode::class,
        KnowledgeMapBatch::class,
        KnowledgeGap::class,
        KnowledgeConflict::class,
        ChapterPlan::class,
        ChapterVersion::class,
        ChapterBlock::class,
        ChapterBlockFts::class,
        Citation::class,
        VerificationIssue::class,
        Quiz::class,
        AnswerRecord::class,
        ReviewItem::class,
        ReadingProgress::class,
        ModelConfig::class,
        StoryBible::class,
    ],
    version = 15,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun sourceDocumentDao(): SourceDocumentDao
    abstract fun sourceChunkDao(): SourceChunkDao
    abstract fun knowledgeNodeDao(): KnowledgeNodeDao
    abstract fun knowledgeMapBatchDao(): KnowledgeMapBatchDao
    abstract fun knowledgeGapDao(): KnowledgeGapDao
    abstract fun knowledgeConflictDao(): KnowledgeConflictDao
    abstract fun chapterPlanDao(): ChapterPlanDao
    abstract fun chapterVersionDao(): ChapterVersionDao
    abstract fun chapterBlockDao(): ChapterBlockDao
    abstract fun citationDao(): CitationDao
    abstract fun verificationIssueDao(): VerificationIssueDao
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

        /**
         * v5 → v6：正文版本化（第三阶段 3A）。
         *
         * 第二阶段留下的最大风险是重生成会**就地覆盖**已发布正文：清旧块、写新块，
         * 随后核验不过或用户取消，旧正文就再也回不来了。现在每次生成写一个独立版本，
         * 只有核验通过才把 chapters.activeVersionId 指过去。
         *
         * 本次迁移做四件事，一个字的正文都不删：
         *
         * 1. 建 chapter_versions 表；
         * 2. 为**每个**现有章节建一个初始版本 v1（id 用 `章节id-v1`，可追溯、不需要随机数）；
         * 3. 现有正文块、自测题全部绑定到这个初始版本；
         * 4. 正文可读的章节把 activeVersionId 指向 v1，未发布的章节留 NULL。
         *
         * 初始版本的 endingState 只在章节可读时才从 carryOver 复制：未发布章节的 carryOver
         * 装的是「开头钩子」而不是「结尾状态」（这个双重语义由 3B 彻底拆掉），
         * 现在先别把开头钩子错当成结尾状态搬进版本表。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chapter_versions` (" +
                        "`id` TEXT NOT NULL, `chapterId` TEXT NOT NULL, " +
                        "`projectId` TEXT NOT NULL DEFAULT '', `versionNumber` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
                        "`endingState` TEXT NOT NULL, `coveredTitles` TEXT NOT NULL, " +
                        "`unverifiedClaims` TEXT NOT NULL, `verificationNote` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapter_versions_chapterId` " +
                        "ON `chapter_versions` (`chapterId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapter_versions_chapterId_status` " +
                        "ON `chapter_versions` (`chapterId`, `status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapter_versions_projectId` " +
                        "ON `chapter_versions` (`projectId`)",
                )

                db.execSQL("ALTER TABLE chapters ADD COLUMN activeVersionId TEXT")
                db.execSQL("ALTER TABLE chapter_blocks ADD COLUMN versionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE quizzes ADD COLUMN versionId TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapter_blocks_versionId` " +
                        "ON `chapter_blocks` (`versionId`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_quizzes_versionId` ON `quizzes` (`versionId`)")

                // 每章一个初始版本。章节状态映射到版本状态：正文可读的算 PUBLISHED，
                // 卡在核验的算 VERIFYING，其余（含还没生成的 QUEUED）都算 DRAFT。
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT INTO chapter_versions (" +
                        "id, chapterId, projectId, versionNumber, status, title, summary, endingState, " +
                        "coveredTitles, unverifiedClaims, verificationNote, createdAt, updatedAt) " +
                        "SELECT id || '$INITIAL_VERSION_SUFFIX', id, projectId, 1, " +
                        "CASE WHEN status IN ('PUBLISHED', 'QUIZ_FAILED') THEN 'PUBLISHED' " +
                        "WHEN status = 'VERIFYING' THEN 'VERIFYING' ELSE 'DRAFT' END, " +
                        "title, summary, " +
                        "CASE WHEN status IN ('PUBLISHED', 'QUIZ_FAILED') THEN carryOver ELSE '' END, " +
                        "coveredTitles, unverifiedClaims, verificationNote, ?, ? FROM chapters",
                    arrayOf(now, now),
                )

                // 现有正文与自测题都属于这个初始版本
                db.execSQL("UPDATE chapter_blocks SET versionId = chapterId || '$INITIAL_VERSION_SUFFIX'")
                db.execSQL("UPDATE quizzes SET versionId = chapterId || '$INITIAL_VERSION_SUFFIX'")

                // 只有正文可读的章节才有 active version；未发布的留 NULL，
                // 否则阅读器会打开一段还没过核验的正文
                db.execSQL(
                    "UPDATE chapters SET activeVersionId = id || '$INITIAL_VERSION_SUFFIX' " +
                        "WHERE status IN ('PUBLISHED', 'QUIZ_FAILED')",
                )
            }
        }

        /**
         * v6 → v7：拆开 carryOver 的双重语义（第三阶段 3B）。
         *
         * 这一列原来同时装两样东西：章节还没发布时装「本章的开头钩子」，
         * 发布之后被改写成「本章结束后的故事状态」。同一列两种含义，
         * 读的时候只能靠章节状态去猜——猜错的后果是把开头钩子当成上一章的结尾喂给模型。
         *
         * 现在按用途各归各位：
         * - 开头钩子属于「计划」→ chapters.openingHook（重生成正文不会改它）
         * - 结尾状态属于「某一版正文」→ chapter_versions.endingState（3A 已建好）
         *
         * 迁移规则就是把当年那个猜法固化下来，只做一次：
         * 未发布章节的 carryOver 是开头钩子，已发布章节的 carryOver 是结尾状态。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN openingHook TEXT NOT NULL DEFAULT ''")

                // 未发布：carryOver 装的是开头钩子
                db.execSQL(
                    "UPDATE chapters SET openingHook = carryOver " +
                        "WHERE status NOT IN ('PUBLISHED', 'QUIZ_FAILED')",
                )
                // 已发布：carryOver 装的是结尾状态，落到当前版本上。
                // v5→v6 已经搬过一次，这里只补 endingState 还空着的（例如手工导入的数据），
                // 不覆盖版本自己写好的结尾
                db.execSQL(
                    "UPDATE chapter_versions SET endingState = COALESCE(" +
                        "(SELECT chapters.carryOver FROM chapters WHERE chapters.id = chapter_versions.chapterId), '') " +
                        "WHERE endingState = '' AND id IN (SELECT activeVersionId FROM chapters WHERE activeVersionId IS NOT NULL)",
                )
                // carryOver 列保留不动：不再读也不再写，但旧数据一个字都不删
            }
        }

        /**
         * v7 → v8：分卷与按需续讲（第三阶段 3C）。
         *
         * 首次只规划一卷（4~8 章），读完再按当前状态规划下一卷。
         * 所以需要记「这一章属于第几卷」和「已经规划到第几卷」。
         *
         * 旧数据全部落在第 0 卷——它们确实是「第一次规划出来的那一批」，
         * 默认值就是对的，不需要回填任何一行。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN volumeIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chapters ADD COLUMN volumeTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapters ADD COLUMN volumeGoal TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE story_bibles ADD COLUMN currentVolumeIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v8 → v9：版本系统加固（第三阶段 3D）。
         *
         * 三件事，都是把 3A~3C 留下的语义含糊处收干净：
         *
         * 1. **区分归档与放弃**。3A 的发布事务把同章其他版本一律写成 ABANDONED，
         *    于是「成功过、只是被取代的旧正文」和「核验没过的失败尝试」混成了一个值。
         *    后果很具体：UI 上「上次重新生成没通过」的提示会把每一次**成功**重生成
         *    留下的旧版本也报成失败。现在旧发布版本改成 ARCHIVED。
         *    旧数据修正规则：一个作废版本，如果它有正文块、而且**版本号比当前 active 版本小**，
         *    那它就是被取代的历史正文 → ARCHIVED；剩下的（没有正文块的，或比 active 更新的）
         *    是真的失败尝试 → 保持 ABANDONED。
         *
         * 2. **覆盖关系改用 nodeId**。chapters / chapter_versions / story_bibles 各加一列
         *    coveredNodeIds。旧数据按标题回填：能在本项目知识地图里精确匹配到标题的补上 ID，
         *    匹配不上的留空——那种情况不能猜，猜错了会把讲过的当成没讲过。
         *
         * 3. **阅读进度带上「正文换过版本」的痕迹**。只清 blockId 不够：用户明明读到一半、
         *    回来却在开头，页面还一言不发，看起来就是进度丢了。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 归档 vs 放弃
                db.execSQL(
                    "UPDATE chapter_versions SET status = 'ARCHIVED' WHERE status = 'ABANDONED' " +
                        // 有正文块 = 这一版真的写出来过
                        "AND EXISTS (SELECT 1 FROM chapter_blocks WHERE chapter_blocks.versionId = chapter_versions.id) " +
                        // 比当前 active 版本旧 = 是被取代，而不是失败
                        "AND versionNumber < COALESCE((" +
                        "SELECT active.versionNumber FROM chapter_versions AS active " +
                        "JOIN chapters ON chapters.activeVersionId = active.id " +
                        "WHERE chapters.id = chapter_versions.chapterId), 0)",
                )

                // 2. coveredNodeIds
                db.execSQL("ALTER TABLE chapters ADD COLUMN coveredNodeIds TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapter_versions ADD COLUMN coveredNodeIds TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE story_bibles ADD COLUMN coveredNodeIds TEXT NOT NULL DEFAULT ''")
                backfillCoveredNodeIds(db, table = "chapters", projectJoin = "chapters.projectId")
                backfillCoveredNodeIds(
                    db = db,
                    table = "chapter_versions",
                    projectJoin = "chapter_versions.projectId",
                )

                // 3. 进度提示
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN bodyUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "ALTER TABLE reading_progress ADD COLUMN progressNoticeShown INTEGER NOT NULL DEFAULT 0",
                )
            }

            /**
             * 按标题回填 nodeId。
             *
             * 只在标题**精确唯一匹配**到本项目某个知识点时才补：标题重复或对不上的一律留空。
             * 留空不等于「没讲过」——判断覆盖时两列都看（`coveredNodeIds` 优先，
             * 空的时候回退到标题），所以留空是安全的，硬猜才危险。
             */
            private fun backfillCoveredNodeIds(
                db: SupportSQLiteDatabase,
                table: String,
                projectJoin: String,
            ) {
                val rows = mutableListOf<Pair<String, String>>()
                db.query("SELECT id, coveredTitles, $projectJoin FROM $table WHERE coveredTitles != ''")
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            rows += Triple(
                                cursor.getString(0),
                                cursor.getString(1),
                                cursor.getString(2),
                            ).let { (id, titles, projectId) ->
                                id to resolveIds(db, titles, projectId)
                            }
                        }
                    }
                rows.filter { it.second.isNotEmpty() }.forEach { (id, ids) ->
                    db.execSQL(
                        "UPDATE $table SET coveredNodeIds = ? WHERE id = ?",
                        arrayOf(ids, id),
                    )
                }
            }

            /** Converters 把列表存成 JSON 数组，这里按同一个约定解析与拼接 */
            private fun resolveIds(
                db: SupportSQLiteDatabase,
                titles: String,
                projectId: String,
            ): String {
                if (projectId.isBlank()) return ""
                val resolved = decodeList(titles)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapNotNull { title -> lookupNodeId(db, projectId, title) }
                    .distinct()
                return if (resolved.isEmpty()) "" else LIST_JSON.encodeToString(LIST_SERIALIZER, resolved)
            }

            private fun decodeList(value: String): List<String> = runCatching {
                if (value.isBlank()) emptyList() else LIST_JSON.decodeFromString(LIST_SERIALIZER, value)
            }.getOrDefault(emptyList())

            private fun lookupNodeId(
                db: SupportSQLiteDatabase,
                projectId: String,
                title: String,
            ): String? {
                db.query(
                    "SELECT id FROM knowledge_nodes WHERE projectId = ? AND title = ? LIMIT 2",
                    arrayOf(projectId, title),
                ).use { cursor ->
                    if (!cursor.moveToNext()) return null
                    val id = cursor.getString(0)
                    // 同名两个节点：无法可靠判断指的是哪个，宁可留空
                    return if (cursor.moveToNext()) null else id
                }
            }
        }

        /** 迁移出来的初始版本 ID 后缀。测试与导入兼容逻辑共用同一个约定。 */
        const val INITIAL_VERSION_SUFFIX = "-v1"

        /**
         * v9 → v10：来源与引用可追溯（第四阶段 4A）。
         *
         * 要回答的问题是「这一段内容从哪来」。在 4A 之前只能回答一半：
         * `chapter_blocks.sourceRefs` 里躺着几个片段 ID，而片段本身不知道自己
         * 在原资料的第几页第几节，模型给的对不上的引用又在校验时被静默丢掉了。
         *
         * 四件事：
         *
         * 1. **来源元数据**。sources 增加 jurisdiction / effectiveFrom / legalVersion。
         *    法律、税务这类内容离开法域和生效时间就没有对错可言。
         * 2. **片段定位**。source_chunks 增加 pageNumber / sectionPath / paragraphIndex。
         *    旧片段留空——它们是用当年那套不记位置的分块逻辑切出来的，
         *    页码和章节无从倒推。硬填一个数字会让用户翻到错误的页码，
         *    比承认「这份资料是早先导入的，只能定位到段落」糟得多。
         * 3. **引用关系表**。按 `versionId + blockId + chunkId` 绑定，
         *    并把现有 sourceRefs 全部回填进去（含历史版本，一条不丢）。
         *    回填时对不上片段的引用记成 UNCONFIRMED 而不是丢掉：
         *    那正是这张表存在的理由。
         * 4. **本章是不是 AI 自备知识写的**。chapter_versions 增加 usedModelKnowledge，
         *    按项目当前的 sourceMode 回填——这是旧数据能给出的唯一依据。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 来源元数据（可空，旧资料就是没填）
                db.execSQL("ALTER TABLE sources ADD COLUMN jurisdiction TEXT")
                db.execSQL("ALTER TABLE sources ADD COLUMN effectiveFrom TEXT")
                db.execSQL("ALTER TABLE sources ADD COLUMN legalVersion TEXT")

                // 2. 片段定位
                db.execSQL("ALTER TABLE source_chunks ADD COLUMN pageNumber INTEGER")
                db.execSQL("ALTER TABLE source_chunks ADD COLUMN sectionPath TEXT")
                db.execSQL("ALTER TABLE source_chunks ADD COLUMN paragraphIndex INTEGER NOT NULL DEFAULT 0")

                // 3. 这一版正文写的时候有没有资料可引
                db.execSQL(
                    "ALTER TABLE chapter_versions ADD COLUMN usedModelKnowledge INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "UPDATE chapter_versions SET usedModelKnowledge = 1 WHERE projectId IN " +
                        "(SELECT id FROM projects WHERE sourceMode = 'MODEL')",
                )

                // 4. 引用关系表
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `citations` (" +
                        "`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `chapterId` TEXT NOT NULL, " +
                        "`versionId` TEXT NOT NULL, `blockId` TEXT NOT NULL, `chunkId` TEXT, " +
                        "`rawRef` TEXT NOT NULL DEFAULT '', `status` TEXT NOT NULL, " +
                        "`order` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_citations_versionId` ON `citations` (`versionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_citations_blockId` ON `citations` (`blockId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_citations_chunkId` ON `citations` (`chunkId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_citations_projectId` ON `citations` (`projectId`)")
                backfillCitations(db)
            }

            /**
             * 把 `chapter_blocks.sourceRefs` 里的引用搬进 citations 表。
             *
             * 全部版本都搬，不只是当前版本：历史版本的引用要保留（4A 的明确要求），
             * 而且 4B 的局部重写会从历史版本派生草稿，那时需要知道旧块引了什么。
             *
             * 对不上现存片段的引用记成 UNCONFIRMED。旧库里这种情况一定存在——
             * 用户删掉一份资料，引用它的正文块还留着 sourceRefs。
             * 以前这只是几个查不到的 ID；现在它是一句能说出口的话：
             * 「这一段引的资料已经不在了」。
             */
            private fun backfillCitations(db: SupportSQLiteDatabase) {
                val knownChunkIds = mutableSetOf<String>()
                db.query("SELECT id FROM source_chunks").use { cursor ->
                    while (cursor.moveToNext()) knownChunkIds += cursor.getString(0)
                }

                val rows = mutableListOf<Array<Any?>>()
                db.query(
                    "SELECT id, projectId, chapterId, versionId, sourceRefs FROM chapter_blocks " +
                        "WHERE sourceRefs != '' AND sourceRefs != '[]'",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val blockId = cursor.getString(0)
                        val projectId = cursor.getString(1)
                        val chapterId = cursor.getString(2)
                        val versionId = cursor.getString(3)
                        decodeList(cursor.getString(4))
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                            .forEachIndexed { order, ref ->
                                rows += arrayOf<Any?>(
                                    "$blockId#$order",
                                    projectId,
                                    chapterId,
                                    versionId,
                                    blockId,
                                    if (ref in knownChunkIds) ref else null,
                                    ref,
                                    if (ref in knownChunkIds) "RESOLVED" else "UNCONFIRMED",
                                    order,
                                )
                            }
                    }
                }
                rows.forEach { args ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO citations " +
                            "(id, projectId, chapterId, versionId, blockId, chunkId, rawRef, status, `order`) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        args,
                    )
                }
            }

            /** Converters 把列表存成 JSON 数组，这里按同一个约定解析 */
            private fun decodeList(value: String): List<String> = runCatching {
                if (value.isBlank()) emptyList() else LIST_JSON.decodeFromString(LIST_SERIALIZER, value)
            }.getOrDefault(emptyList())
        }
        /**
         * v10 → v11（4B）：核验问题落成一张表。
         *
         * 在此之前，一整章的核验问题被 `issues.take(5).joinToString("\n")` 拼成
         * 一个字符串塞进 `chapters.verificationNote`，第 6 条起直接丢掉，
         * `blockId` / `sourceRefs` / `suggestedFix` 全部蒸发。用户看到一段
         * 没法点、没法定位的文字，唯一的出路是整章重新生成——把写对的部分
         * 连同写错的部分一起推翻。
         *
         * 旧数据能救回多少：`verificationNote` 里那段文字是唯一线索，
         * 但它既没有 blockId 也没有原始类型，回填成一条条问题只会造出
         * 一批「定位不到块、类型是其他」的记录，而且和当时真实的问题条数
         * 未必对得上（超过 5 条的部分早就没了）。所以**不回填**：
         * 旧的说明文字继续留在 `verificationNote` 里照原样显示，
         * 这张表从下一次核验开始有数据。硬造一批半真的问题记录，
         * 会让「本章还有 3 个问题待处理」这种数字从一开始就不可信。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `verification_issues` (" +
                        "`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `chapterId` TEXT NOT NULL, " +
                        "`versionId` TEXT NOT NULL, `blockId` TEXT, `type` TEXT NOT NULL, " +
                        "`rawType` TEXT NOT NULL DEFAULT '', `message` TEXT NOT NULL, " +
                        "`sourceRefs` TEXT NOT NULL DEFAULT '', `suggestedFix` TEXT NOT NULL, " +
                        "`resolution` TEXT NOT NULL, `order` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_verification_issues_versionId` " +
                        "ON `verification_issues` (`versionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_verification_issues_blockId` " +
                        "ON `verification_issues` (`blockId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_verification_issues_chapterId` " +
                        "ON `verification_issues` (`chapterId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_verification_issues_projectId` " +
                        "ON `verification_issues` (`projectId`)",
                )
                // 草稿版本是从哪一版派生出来的（4B）。局部重写要能说出
                // 「这一版是在上一版基础上改了 3 块」，也要能在放弃时讲清放弃了什么
                db.execSQL("ALTER TABLE chapter_versions ADD COLUMN derivedFromVersionId TEXT")
                db.execSQL(
                    "ALTER TABLE chapter_versions ADD COLUMN rewrittenBlockIds TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * v11 → v12：引用增加「来源已删除」这一状态与被删资料的标题（4C）。
         *
         * 4A 起删掉一份资料不会删掉引用它的引用行，只把状态降级——正文里那段话
         * 还在，它当初确实有依据。但降级后的行和「模型给的出处一开始就没对上」
         * 长得一模一样，阅读器只能对两者说同一句「未能匹配到资料」。
         * 这两件事对读者的意思差得很远：前者是资料不在了，后者是依据从没被确认过。
         *
         * `lostSourceTitle` 必须在删除资料的同一个事务里、`sources` 那一行还在的时候
         * 抄下来（见 CitationDao.unconfirmForSource）。删完再想查标题已经查不到了，
         * 而「引用的资料已被删除」不说是哪一份，用户既没法判断这一段还站不站得住，
         * 也不知道该重新导入哪一份。
         *
         * 存量的 UNCONFIRMED 行**不回填**成 SOURCE_DELETED：一条已经降级的引用
         * 和一条从没对上的引用在库里现在没有任何区别，无从判断。全判成「资料已删除」
         * 会把模型编的出处说成「你自己删了资料」；全留在 UNCONFIRMED 是它们本来的
         * 显示方式，最多是说得保守一点。新的删除操作从这一版起分得清。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE citations ADD COLUMN lostSourceTitle TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * v12 → v13：知识地图分批生成（骨架 → 逐链展开 → 全局审计）。
         *
         * 一次性让模型输出 14-44 个节点加 3-8 条演进链，单次 2 万 token 输出、
         * 最坏几分钟不出结果，改成分批后需要两样新的持久化状态：
         *
         * 1. **节点的稳定标识与生成状态**。knowledge_nodes 增加：
         *    - `nodeKey`：骨架落库时由程序分配，单链展开时模型原样回传——
         *      标题只做显示，不能当身份（模型改一个空格/标点就匹配失败）；
         *    - `generateStatus`：PLACEHOLDER / GENERATING / COMPLETED / FAILED。
         *      旧数据一律默认 COMPLETED——它们是一次性生成出来的完整节点，
         *      不能因为缺状态被误判成「待展开」。
         * 2. **批次状态表**。knowledge_map_batches 记录当前批次的骨架、逐链、
         *    审计完成情况，供断点续跑与重新生成隔离。
         *    这是过程状态，不随备份导出。
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE knowledge_nodes ADD COLUMN nodeKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE knowledge_nodes ADD COLUMN generateStatus TEXT NOT NULL DEFAULT 'COMPLETED'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `knowledge_map_batches` (" +
                        "`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, " +
                        "`skeletonDone` INTEGER NOT NULL DEFAULT 0, " +
                        "`chainsJson` TEXT NOT NULL DEFAULT '[]', " +
                        "`chainStatuses` TEXT NOT NULL DEFAULT '[]', " +
                        "`previewGaps` TEXT NOT NULL DEFAULT '[]', " +
                        "`previewConflicts` TEXT NOT NULL DEFAULT '[]', " +
                        "`auditDone` INTEGER NOT NULL DEFAULT 0, " +
                        "`auditFailed` INTEGER NOT NULL DEFAULT 0, " +
                        "`completed` INTEGER NOT NULL DEFAULT 0, " +
                        "`failed` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_knowledge_map_batches_projectId` " +
                        "ON `knowledge_map_batches` (`projectId`)",
                )
                // 单链回填按 nodeKey 匹配，走索引而不是扫全表
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_knowledge_nodes_projectId_nodeKey` " +
                        "ON `knowledge_nodes` (`projectId`, `nodeKey`)",
                )
            }
        }

        /**
         * v13 → v14：重新生成不预先删地图，批次原子切换（收尾）。
         *
         * v13 的重新生成会在骨架阶段前 clearForProject——新批次一旦失败，
         * 用户连旧地图都看不到了。现在给节点加批次归属：
         *
         * 1. knowledge_nodes 增加 `batchId`：重新生成时新批次节点先落库但
         *    不生效（查询过滤掉），整批成功后事务切换 active 批次，
         *    旧批次节点随之删除。空串 = 迁移前旧数据，视为当前有效地图。
         * 2. knowledge_map_batches 增加 `active`：标记当前生效批次。
         * 3. 回填：有已完成批次的项目，把其节点归属到该批次并置 active；
         *    没有完成过批次的项目，节点保持空串（仍视为有效地图）。
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE knowledge_nodes ADD COLUMN batchId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE knowledge_map_batches ADD COLUMN active INTEGER NOT NULL DEFAULT 0")
                // 最新的已完成批次置为生效
                db.execSQL(
                    "UPDATE knowledge_map_batches SET active = 1 WHERE id = (" +
                        "SELECT id FROM knowledge_map_batches WHERE completed = 1 AND failed = 0 " +
                        "ORDER BY createdAt DESC LIMIT 1)",
                )
                // 该批次项目下的节点归属到它（无完成批次的项目节点保持空串）
                db.execSQL(
                    "UPDATE knowledge_nodes SET batchId = COALESCE((" +
                        "SELECT id FROM knowledge_map_batches " +
                        "WHERE completed = 1 AND failed = 0 AND knowledge_map_batches.projectId = knowledge_nodes.projectId " +
                        "ORDER BY createdAt DESC LIMIT 1), '')",
                )
            }
        }

        /**
         * v14 → v15（第二轮 P1-2）：`chapters` 增加 `chapterType` 与 `contractJson`。
         *
         * 章节因果契约是路线规划 → 正文生成 → 核验的跨 Worker 传递物，必须落库：
         * 正文模型按契约写、核验模型拿契约逐项检查。两列都是 TEXT 默认空串——
         * 旧数据（无契约）读取方明确显示「未生成因果契约」，不伪造。
         * 空串默认值保证：老备份导入时新列落默认值；新备份导出时默认值字段省略，
         * 上一版应用也能读（ignoreUnknownKeys）。formatVersion 不升位。
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN chapterType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE chapters ADD COLUMN contractJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 迁移里手工拼列表时用的 JSON 编解码，与 [Converters] 保持一致 */
        val LIST_JSON = Json
        val LIST_SERIALIZER = ListSerializer(String.serializer())

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DATABASE_NAME)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                )
                .build()
    }
}
