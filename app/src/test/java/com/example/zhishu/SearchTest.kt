package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.search.DefaultSearchRepository
import com.example.zhishu.search.SearchHitKind
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchTest {

    private lateinit var db: AppDatabase

    private companion object {
        const val VERSION_ID = "ch-1-v1"
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun search_prioritizesExactNodeTitle() = runBlocking {
        seed()
        val repo = DefaultSearchRepository(
            knowledgeNodeDao = db.knowledgeNodeDao(),
            chapterBlockDao = db.chapterBlockDao(),
            sourceChunkDao = db.sourceChunkDao(),
        )
        val hits = repo.search("p-1", "意思表示")

        assertTrue(hits.isNotEmpty())
        assertEquals(SearchHitKind.NODE_EXACT, hits.first().kind)
        assertEquals("意思表示", hits.first().title)
        // 节点命中带上章节，点击才能跳转（B16）
        assertEquals("ch-1", hits.first().chapterId)
    }

    @Test
    fun search_findsChapterBlockWithLocation() = runBlocking {
        seed()
        val repo = DefaultSearchRepository(
            knowledgeNodeDao = db.knowledgeNodeDao(),
            chapterBlockDao = db.chapterBlockDao(),
            sourceChunkDao = db.sourceChunkDao(),
        )
        val hits = repo.search("p-1", "遗嘱")

        assertTrue(hits.any { it.kind == SearchHitKind.CHAPTER_BLOCK })
        val blockHit = hits.first { it.kind == SearchHitKind.CHAPTER_BLOCK }
        assertEquals("ch-1", blockHit.chapterId)
        assertEquals("b-1", blockHit.blockId)
        assertTrue(blockHit.charOffset >= 0)
    }

    @Test
    fun search_findsSourceChunk() = runBlocking {
        seed()
        val repo = DefaultSearchRepository(
            knowledgeNodeDao = db.knowledgeNodeDao(),
            chapterBlockDao = db.chapterBlockDao(),
            sourceChunkDao = db.sourceChunkDao(),
        )
        val hits = repo.search("p-1", "要约人")

        assertTrue(hits.any { it.kind == SearchHitKind.SOURCE_CHUNK })
    }

    @Test
    fun search_emptyQuery_returnsNothing() = runBlocking {
        seed()
        val repo = DefaultSearchRepository(
            knowledgeNodeDao = db.knowledgeNodeDao(),
            chapterBlockDao = db.chapterBlockDao(),
            sourceChunkDao = db.sourceChunkDao(),
        )
        assertTrue(repo.search("p-1", "  ").isEmpty())
    }

    @Test
    fun search_otherProject_returnsNothing() = runBlocking {
        seed()
        val repo = DefaultSearchRepository(
            knowledgeNodeDao = db.knowledgeNodeDao(),
            chapterBlockDao = db.chapterBlockDao(),
            sourceChunkDao = db.sourceChunkDao(),
        )
        assertTrue(repo.search("p-other", "意思表示").isEmpty())
    }

    /** 大样本下 FTS 查询仍返回正确结果，且查询计划使用 FTS 表（P2） */
    @Test
    fun search_largeCorpus_usesFtsAndStaysCorrect() = runBlocking {
        seed()
        val blocks = (0 until 800).map { index ->
            ChapterBlock(
                id = "bulk-$index",
                chapterId = "ch-1",
                versionId = VERSION_ID,
                projectId = "p-1",
                order = index + 1,
                text = "第${index}段：合同订立与履行的一般规则说明。",
                blockType = BlockType.STORY,
            )
        } + ChapterBlock(
            id = "needle",
            chapterId = "ch-1",
            versionId = VERSION_ID,
            projectId = "p-1",
            order = 9999,
            text = "本段唯一提到表见代理的效力认定。",
            blockType = BlockType.STORY,
        )
        db.chapterBlockDao().upsertAll(blocks)
        assertEquals(801 + 1, db.chapterBlockDao().countByProject("p-1"))
        // FTS 索引与正文条数一致
        assertEquals(
            db.chapterBlockDao().countByProject("p-1"),
            db.chapterBlockDao().countFtsByProject("p-1"),
        )

        val repo = DefaultSearchRepository(
            knowledgeNodeDao = db.knowledgeNodeDao(),
            chapterBlockDao = db.chapterBlockDao(),
            sourceChunkDao = db.sourceChunkDao(),
        )
        val hits = repo.search("p-1", "表见代理")
        assertEquals(1, hits.count { it.kind == SearchHitKind.CHAPTER_BLOCK })
        assertEquals("needle", hits.first { it.kind == SearchHitKind.CHAPTER_BLOCK }.id)

        // 查询计划应命中 FTS 虚拟表，而不是扫描 chapter_blocks。
        // 3A 之后多了一层 chapters 连接（只搜当前版本），仍然不能退化成全表扫描
        val plan = queryPlan(
            "SELECT chapter_blocks.* FROM chapter_block_fts " +
                "JOIN chapter_blocks ON chapter_blocks.id = chapter_block_fts.blockId " +
                "JOIN chapters ON chapters.id = chapter_blocks.chapterId " +
                "WHERE chapter_block_fts.projectId = 'p-1' AND chapter_block_fts.body MATCH '\"表见 见代 代理\"' " +
                "AND chapter_blocks.versionId = chapters.activeVersionId " +
                "ORDER BY chapter_blocks.[order] LIMIT 50",
        )
        assertTrue("查询计划未使用 FTS：$plan", plan.contains("chapter_block_fts"))
        assertFalse("查询计划仍在扫描 chapter_blocks：$plan", plan.contains("SCAN chapter_blocks"))
    }

    /** 删除正文时 FTS 索引必须同步清理，避免搜索出已删内容 */
    @Test
    fun deleteBlocks_clearsFtsIndex() = runBlocking {
        seed()
        assertEquals(1, db.chapterBlockDao().countFtsByProject("p-1"))
        db.chapterBlockDao().deleteByProject("p-1")
        assertEquals(0, db.chapterBlockDao().countFtsByProject("p-1"))

        val repo = DefaultSearchRepository(
            knowledgeNodeDao = db.knowledgeNodeDao(),
            chapterBlockDao = db.chapterBlockDao(),
            sourceChunkDao = db.sourceChunkDao(),
        )
        assertTrue(repo.search("p-1", "遗嘱").none { it.kind == SearchHitKind.CHAPTER_BLOCK })
    }

    @Test
    fun deleteChunks_clearsFtsIndex() = runBlocking {
        seed()
        assertEquals(1, db.sourceChunkDao().countFtsByProject("p-1"))
        db.sourceChunkDao().deleteByProject("p-1")
        assertEquals(0, db.sourceChunkDao().countFtsByProject("p-1"))
    }

    private fun queryPlan(sql: String): String = buildString {
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            while (cursor.moveToNext()) {
                append(cursor.getString(cursor.columnCount - 1))
                append('\n')
            }
        }
    }

    private suspend fun seed() {
        val now = 1_000L
        db.projectDao().upsert(
            Project(
                id = "p-1",
                title = "民法基础",
                topic = "民法",
                baseline = "入门",
                depth = "入门",
                dailyMinutes = 20,
                targetDays = 30,
                genre = "悬疑",
                status = ProjectStatus.DRAFT,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.knowledgeNodeDao().upsert(
            KnowledgeNode(
                id = "n-1",
                projectId = "p-1",
                title = "意思表示",
                definition = "意思表示是民事法律行为的核心要素，指行为人将内心意思表达于外。",
                chapterIds = listOf("ch-1"),
            ),
        )
        db.chapterPlanDao().upsert(
            ChapterPlan(
                id = "ch-1",
                projectId = "p-1",
                order = 1,
                title = "遗嘱风波",
                // 搜索只命中 active version 的正文，所以测试数据也得有版本指针（3A）
                activeVersionId = VERSION_ID,
            ),
        )
        db.chapterVersionDao().upsert(
            ChapterVersion(
                id = VERSION_ID,
                chapterId = "ch-1",
                projectId = "p-1",
                versionNumber = 1,
                status = ChapterVersionStatus.PUBLISHED,
                title = "遗嘱风波",
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.chapterBlockDao().upsertAll(
            listOf(
                ChapterBlock(
                    id = "b-1",
                    chapterId = "ch-1",
                    versionId = VERSION_ID,
                    projectId = "p-1",
                    order = 0,
                    text = "老人立下遗嘱后，子女们为遗产分配争执不休。",
                    blockType = BlockType.STORY,
                ),
            ),
        )
        val source = SourceDocument(
            id = "src-1",
            projectId = "p-1",
            title = "合同法笔记",
            kind = SourceKind.TEXT,
            fetchedAt = now,
            contentHash = "h1",
            parseStatus = ParseStatus.PARSED,
        )
        db.sourceDocumentDao().upsert(source)
        db.sourceChunkDao().upsertAll(
            listOf(
                SourceChunk(
                    id = "c-1",
                    sourceId = source.id,
                    projectId = "p-1",
                    ordinal = 0,
                    text = "要约人发出要约后，在承诺期限内不得随意撤销。",
                ),
            ),
        )
    }
}
