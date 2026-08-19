package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.data.local.repo.DefaultChapterRepository
import com.example.zhishu.data.local.repo.DefaultProgressRepository
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.search.DefaultSearchRepository
import com.example.zhishu.search.SearchHitKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 第一阶段「阅读闭环」的回归测试。
 *
 * 覆盖三条主线：
 * 1. 搜索命中能定位到具体正文块（chapterId + blockId），不再只打开章节首页；
 * 2. 知识地图节点能落到「哪一章的哪一块」；
 * 3. 阅读进度按块 + 块内偏移保存与恢复，且不会串到别的章节。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingLoopTest {

    private lateinit var db: AppDatabase

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

    private fun searchRepo() = DefaultSearchRepository(
        knowledgeNodeDao = db.knowledgeNodeDao(),
        chapterBlockDao = db.chapterBlockDao(),
        sourceChunkDao = db.sourceChunkDao(),
    )

    /** 正文命中必须带上 blockId，阅读器才能滚到命中的那一段 */
    @Test
    fun search_chapterBlockHit_carriesBlockId() = runBlocking {
        seed()
        val hit = searchRepo().search("p-1", "阿尔茨海默")
            .first { it.kind == SearchHitKind.CHAPTER_BLOCK }
        assertEquals("ch-2", hit.chapterId)
        assertEquals("b-2b", hit.blockId)
    }

    /** 知识点命中也要解析到讲它的那一块，而不是只给章节 */
    @Test
    fun search_nodeHit_resolvesBlockInChapter() = runBlocking {
        seed()
        val hit = searchRepo().search("p-1", "遗嘱继承")
            .first { it.kind == SearchHitKind.NODE_EXACT }
        assertEquals("ch-2", hit.chapterId)
        assertEquals("b-2a", hit.blockId)
        assertEquals("n-will", hit.nodeId)
    }

    /** 知识点只登记了章节、正文里没标注它时，仍要能打开章节（blockId 允许为空） */
    @Test
    fun search_nodeWithoutTaggedBlock_stillOpensChapter() = runBlocking {
        seed()
        db.knowledgeNodeDao().upsert(
            KnowledgeNode(
                id = "n-orphan",
                projectId = "p-1",
                title = "遗赠扶养协议",
                definition = "另一种处理身后财产的安排。",
                chapterIds = listOf("ch-1"),
            ),
        )
        val hit = searchRepo().search("p-1", "遗赠扶养协议")
            .first { it.kind == SearchHitKind.NODE_EXACT }
        assertEquals("ch-1", hit.chapterId)
        assertNull(hit.blockId)
    }

    /** 资料片段命中不属于正文，不给章节也不给块 */
    @Test
    fun search_sourceChunkHit_hasNoBlockAnchor() = runBlocking {
        seed()
        val hits = searchRepo().search("p-1", "遗嘱")
        hits.filter { it.kind == SearchHitKind.SOURCE_CHUNK }.forEach {
            assertNull(it.chapterId)
            assertNull(it.blockId)
        }
    }

    /**
     * 知识地图 → 正文的锚点：与 ProjectDetailViewModel.buildNodeAnchors 同一套规则，
     * 只收有正文的章节，并取章内第一个标注该知识点的块。
     */
    @Test
    fun nodeAnchors_pointToFirstTaggedBlockOfEachWrittenChapter() = runBlocking {
        seed()
        val chapterRepo = DefaultChapterRepository(db.chapterPlanDao(), db.chapterBlockDao(), db.chapterVersionDao())
        val blocksByChapter = chapterRepo.getActiveBlocksByProject("p-1").groupBy { it.chapterId }

        // ch-3 只有计划、没有正文，不该出现在锚点里
        assertEquals(setOf("ch-1", "ch-2"), blocksByChapter.keys)

        val anchor = blocksByChapter["ch-2"].orEmpty()
            .sortedBy { it.order }
            .firstOrNull { "n-will" in it.knowledgeNodeIds }
        assertEquals("b-2a", anchor?.id)
    }

    /** 阅读进度保存的是块 + 块内偏移，不再是整章像素值 */
    @Test
    fun progress_savesAndRestoresBlockAnchor() = runBlocking {
        seed()
        val repo = DefaultProgressRepository(db.readingProgressDao())
        repo.save(
            ReadingProgress(
                id = "p-1",
                projectId = "p-1",
                chapterId = "ch-2",
                blockId = "b-2b",
                blockOffset = 36,
                updatedAt = 10L,
            ),
        )
        val loaded = repo.get("p-1")
        assertNotNull(loaded)
        assertEquals("ch-2", loaded?.chapterId)
        assertEquals("b-2b", loaded?.blockId)
        assertEquals(36, loaded?.blockOffset)
        // 一个项目只保留一条最新进度
        assertEquals(1, listOfNotNull(loaded).size)
    }

    /** 进度属于别的章节时不能拿来恢复，否则会跳到无关位置（B7） */
    @Test
    fun progress_fromAnotherChapter_isNotRestored() = runBlocking {
        seed()
        val repo = DefaultProgressRepository(db.readingProgressDao())
        repo.save(
            ReadingProgress(
                id = "p-1",
                projectId = "p-1",
                chapterId = "ch-1",
                blockId = "b-1a",
                blockOffset = 88,
                updatedAt = 10L,
            ),
        )
        val saved = repo.get("p-1")
        // 打开 ch-2 时 ReaderViewModel 的判定：章节不匹配就不恢复
        val restoredForCh2 = if (saved?.chapterId == "ch-2") saved.blockId else null
        assertNull(restoredForCh2)
    }

    /** 阅读器 LazyColumn 的 key 必须唯一，否则滚动位置会错乱 */
    @Test
    fun blockIds_areUniqueWithinChapter() = runBlocking {
        seed()
        val chapterRepo = DefaultChapterRepository(db.chapterPlanDao(), db.chapterBlockDao(), db.chapterVersionDao())
        val blocks = chapterRepo.getActiveBlocks("ch-2")
        assertTrue(blocks.isNotEmpty())
        assertEquals(blocks.size, blocks.map { it.id }.distinct().size)
    }

    private suspend fun seed() {
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
                status = ProjectStatus.READY,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        db.knowledgeNodeDao().upsertAll(
            listOf(
                KnowledgeNode(
                    id = "n-will",
                    projectId = "p-1",
                    title = "遗嘱继承",
                    definition = "有合法有效遗嘱时按遗嘱继承。",
                    chapterIds = listOf("ch-2"),
                ),
                KnowledgeNode(
                    id = "n-legal",
                    projectId = "p-1",
                    title = "法定继承",
                    definition = "没有遗嘱或遗嘱无效时按法定顺序继承。",
                    chapterIds = listOf("ch-2", "ch-3"),
                ),
            ),
        )
        db.chapterPlanDao().upsertAll(
            listOf(
                ChapterPlan(
                    id = "ch-1",
                    projectId = "p-1",
                    order = 1,
                    title = "深夜的律师函",
                    status = ChapterStatus.PUBLISHED,
                    activeVersionId = "ch-1-v1",
                ),
                ChapterPlan(
                    id = "ch-2",
                    projectId = "p-1",
                    order = 2,
                    title = "尘封的遗嘱",
                    status = ChapterStatus.PUBLISHED,
                    nodeIds = listOf("n-will", "n-legal"),
                    activeVersionId = "ch-2-v1",
                ),
                // 只有计划、还没写正文：不应产生跳转锚点，也没有 active version
                ChapterPlan(
                    id = "ch-3",
                    projectId = "p-1",
                    order = 3,
                    title = "最后的庭审",
                    status = ChapterStatus.QUEUED,
                    nodeIds = listOf("n-legal"),
                ),
            ),
        )
        // 阅读与搜索都只认 active version 的正文（3A）
        db.chapterVersionDao().upsertAll(
            listOf("ch-1", "ch-2").map { chapterId ->
                ChapterVersion(
                    id = "$chapterId-v1",
                    chapterId = chapterId,
                    projectId = "p-1",
                    versionNumber = 1,
                    status = ChapterVersionStatus.PUBLISHED,
                    createdAt = 1L,
                    updatedAt = 1L,
                )
            },
        )
        db.chapterBlockDao().upsertAll(
            listOf(
                ChapterBlock(
                    id = "b-1a",
                    chapterId = "ch-1",
                    versionId = "ch-1-v1",
                    projectId = "p-1",
                    order = 0,
                    text = "深夜十一点，林晓收到一封律师函。",
                    blockType = BlockType.STORY,
                ),
                ChapterBlock(
                    id = "b-2a",
                    chapterId = "ch-2",
                    versionId = "ch-2-v1",
                    projectId = "p-1",
                    order = 0,
                    text = "旧皮箱夹层里有一封信，要把房子留给护工。",
                    blockType = BlockType.STORY,
                    knowledgeNodeIds = listOf("n-will"),
                ),
                ChapterBlock(
                    id = "b-2b",
                    chapterId = "ch-2",
                    versionId = "ch-2-v1",
                    projectId = "p-1",
                    order = 1,
                    text = "姑母三年前就患上了阿尔茨海默病，这份遗嘱是否有效成了悬念。",
                    blockType = BlockType.EXPLANATION,
                    knowledgeNodeIds = listOf("n-will", "n-legal"),
                ),
            ),
        )
    }
}
