package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ModelConfig
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.QuizType
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.ReviewStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseTest {

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

    @Test
    fun databaseVersion_isEight() {
        assertEquals(8, db.openHelper.readableDatabase.version)
    }

    @Test
    fun projectCrud_roundTrip() = runBlocking {
        val project = project(now = 1000L)
        db.projectDao().upsert(project)
        val loaded = db.projectDao().getById(project.id)
        assertNotNull(loaded)
        assertEquals(project.title, loaded?.title)
        assertEquals(ProjectStatus.DRAFT, loaded?.status)

        val updated = project.copy(status = ProjectStatus.READY, updatedAt = 2000L)
        db.projectDao().upsert(updated)
        assertEquals(ProjectStatus.READY, db.projectDao().getById(project.id)?.status)

        db.projectDao().deleteById(project.id)
        assertNull(db.projectDao().getById(project.id))
    }

    @Test
    fun sourceAndChunks_persist() = runBlocking {
        val source = SourceDocument(
            id = "src-1",
            projectId = "p-1",
            title = "民法笔记",
            kind = SourceKind.TEXT,
            fileName = "note.txt",
            fetchedAt = 1000L,
            contentHash = "abc",
            parseStatus = ParseStatus.PARSED,
        )
        val chunks = listOf(
            SourceChunk(id = "c-1", sourceId = source.id, projectId = "p-1", ordinal = 0, text = "第一章"),
            SourceChunk(id = "c-2", sourceId = source.id, projectId = "p-1", ordinal = 1, text = "第二章"),
        )
        db.sourceDocumentDao().upsert(source)
        db.sourceChunkDao().upsertAll(chunks)

        assertEquals(source.id, db.sourceDocumentDao().findByHash("abc")?.id)
        val loadedChunks = db.sourceChunkDao().getBySource(source.id)
        assertEquals(2, loadedChunks.size)
        assertEquals("第二章", loadedChunks[1].text)
        // 按项目与按 ID 两条加载路径都可用（B1）
        assertEquals(2, db.sourceChunkDao().getByProject("p-1").size)
        assertEquals(1, db.sourceChunkDao().getByIds("p-1", listOf("c-2")).size)
    }

    @Test
    fun knowledgeNode_listFieldsRoundTrip() = runBlocking {
        val node = KnowledgeNode(
            id = "n-1",
            projectId = "p-1",
            title = "意思表示",
            prerequisites = listOf("民事法律行为"),
            related = listOf("要约", "承诺"),
            confusions = listOf("效力"),
            examples = listOf("买咖啡"),
            sourceRefs = listOf("c-1"),
            mastery = 0.3f,
            depth = 2,
        )
        db.knowledgeNodeDao().upsert(node)
        val loaded = db.knowledgeNodeDao().getById(node.id)
        assertNotNull(loaded)
        assertEquals(listOf("要约", "承诺"), loaded?.related)
        assertEquals(0.3f, loaded?.mastery ?: 0f, 0.001f)
    }

    /** 字段级更新不得覆盖其他字段（N13） */
    @Test
    fun knowledgeNode_updateConfirmed_keepsMastery() = runBlocking {
        db.knowledgeNodeDao().upsert(
            KnowledgeNode(id = "n-1", projectId = "p-1", title = "意思表示", mastery = 0.6f),
        )
        db.knowledgeNodeDao().updateConfirmed("n-1", true)
        val loaded = db.knowledgeNodeDao().getById("n-1")
        assertEquals(true, loaded?.confirmed)
        assertEquals(0.6f, loaded?.mastery ?: 0f, 0.001f)

        db.knowledgeNodeDao().updateChapterIds("n-1", listOf("ch-1", "ch-2"))
        assertEquals(listOf("ch-1", "ch-2"), db.knowledgeNodeDao().getById("n-1")?.chapterIds)
        assertEquals(true, db.knowledgeNodeDao().getById("n-1")?.confirmed)
    }

    /** 用户编辑标题不应回退 Worker 写入的状态与核验说明（N13） */
    @Test
    fun chapterPlan_editableUpdate_keepsStatusAndNote() = runBlocking {
        db.chapterPlanDao().upsert(
            ChapterPlan(id = "ch-1", projectId = "p-1", order = 1, title = "旧标题"),
        )
        db.chapterPlanDao().updateStatus("ch-1", ChapterStatus.RUNNING, "生成中")
        db.chapterPlanDao().updateEditableFields(
            id = "ch-1",
            title = "新标题",
            plotGoal = "新目标",
            learningGoals = listOf("A", "B"),
            wordTarget = 1500,
        )
        val loaded = db.chapterPlanDao().getById("ch-1")
        assertEquals("新标题", loaded?.title)
        assertEquals(listOf("A", "B"), loaded?.learningGoals)
        assertEquals(ChapterStatus.RUNNING, loaded?.status)
        assertEquals("生成中", loaded?.verificationNote)
    }

    @Test
    fun chapterPlanAndBlocks_persist() = runBlocking {
        val plan = ChapterPlan(
            id = "ch-1",
            projectId = "p-1",
            order = 1,
            title = "遗嘱风波",
            learningGoals = listOf("遗嘱形式"),
            nodeIds = listOf("n-1"),
            status = ChapterStatus.PUBLISHED,
        )
        val blocks = listOf(
            ChapterBlock(
                id = "b-1",
                chapterId = plan.id,
                projectId = "p-1",
                order = 0,
                text = "老人立下遗嘱",
                blockType = BlockType.STORY,
                knowledgeNodeIds = listOf("n-1"),
            ),
        )
        db.chapterPlanDao().upsert(plan)
        db.chapterBlockDao().upsertAll(blocks)

        val loadedPlans = db.chapterPlanDao().getByProject("p-1")
        assertEquals(1, loadedPlans.size)
        assertEquals(ChapterStatus.PUBLISHED, loadedPlans[0].status)
        val loadedBlocks = db.chapterBlockDao().getByChapter("ch-1")
        assertEquals(BlockType.STORY, loadedBlocks[0].blockType)
    }

    /**
     * 删除项目后正文必须为零：即使先删了 chapters 行，
     * chapter_blocks 也能按 projectId 直接清掉（N6）。
     */
    @Test
    fun deleteProject_removesChapterBlocks() = runBlocking {
        db.chapterPlanDao().upsert(ChapterPlan(id = "ch-1", projectId = "p-1", order = 1, title = "章"))
        db.chapterBlockDao().upsertAll(
            listOf(
                ChapterBlock(id = "b-1", chapterId = "ch-1", projectId = "p-1", order = 0, text = "正文"),
            ),
        )
        // 模拟旧顺序：先删 chapters
        db.chapterPlanDao().clearByProject("p-1")
        db.chapterBlockDao().deleteByProject("p-1")
        assertEquals(0, db.chapterBlockDao().countByProject("p-1"))
        assertTrue(db.chapterBlockDao().getByChapter("ch-1").isEmpty())
    }

    @Test
    fun deleteProject_removesSourceChunks() = runBlocking {
        val source = SourceDocument(
            id = "src-1",
            projectId = "p-1",
            title = "笔记",
            kind = SourceKind.TEXT,
            fetchedAt = 1L,
            contentHash = "h",
            parseStatus = ParseStatus.PARSED,
        )
        db.sourceDocumentDao().upsert(source)
        db.sourceChunkDao().upsertAll(
            listOf(SourceChunk(id = "c-1", sourceId = "src-1", projectId = "p-1", ordinal = 0, text = "x")),
        )
        db.sourceDocumentDao().deleteByProject("p-1")
        db.sourceChunkDao().deleteByProject("p-1")
        assertEquals(0, db.sourceChunkDao().countByProject("p-1"))
    }

    @Test
    fun quizAndAnswerRecord_persist() = runBlocking {
        val quiz = Quiz(
            id = "q-1",
            projectId = "p-1",
            chapterId = "ch-1",
            order = 1,
            question = "遗嘱是否需要两个见证人？",
            type = QuizType.TRUE_FALSE,
            options = listOf("是", "否"),
            answer = "是",
            nodeIds = listOf("n-1"),
        )
        db.quizDao().upsertAll(listOf(quiz))
        db.answerRecordDao().upsert(
            AnswerRecord(id = "a-1", quizId = quiz.id, projectId = "p-1", isCorrect = true, answeredAt = 1L),
        )

        assertEquals(1, db.quizDao().getByProject("p-1").size)
        assertEquals(1, db.answerRecordDao().countCorrect(quiz.id))
        assertEquals(1, db.answerRecordDao().getByQuizIds(listOf("q-1")).size)
    }

    /** quizId 唯一：同一题重复写入只保留一条，掌握度不会被重复计入（N12） */
    @Test
    fun answerRecord_quizIdIsUnique() = runBlocking {
        db.answerRecordDao().upsert(
            AnswerRecord(id = "a-1", quizId = "q-1", projectId = "p-1", isCorrect = true, answeredAt = 1L),
        )
        db.answerRecordDao().upsert(
            AnswerRecord(id = "a-2", quizId = "q-1", projectId = "p-1", isCorrect = false, answeredAt = 2L),
        )
        assertEquals(1, db.answerRecordDao().countAnswers("q-1"))
        assertEquals(false, db.answerRecordDao().getByQuizId("q-1")?.isCorrect)
    }

    @Test
    fun reviewDue_queryReturnsOnlyPending() = runBlocking {
        db.reviewItemDao().upsertAll(
            listOf(
                ReviewItem(id = "r-1", projectId = "p-1", nodeId = "n-1", dueAt = 100L),
                ReviewItem(id = "r-2", projectId = "p-1", nodeId = "n-2", dueAt = 500L),
                ReviewItem(id = "r-3", projectId = "p-1", nodeId = "n-3", dueAt = 50L, status = ReviewStatus.DONE),
            ),
        )
        val due = db.reviewItemDao().getDue(now = 200L, status = ReviewStatus.PENDING)
        assertEquals(1, due.size)
        assertEquals("r-1", due[0].id)
    }

    @Test
    fun readingProgress_upsert() = runBlocking {
        db.readingProgressDao().upsert(
            ReadingProgress(
                id = "rp-1",
                projectId = "p-1",
                chapterId = "ch-1",
                blockId = "b-1",
                blockOffset = 42,
                updatedAt = 1L,
            ),
        )
        val loaded = db.readingProgressDao().getForProject("p-1")
        assertNotNull(loaded)
        // 进度按「哪一块 + 块内偏移」保存，不再是整章像素值
        assertEquals("b-1", loaded?.blockId)
        assertEquals(42, loaded?.blockOffset)
    }

    @Test
    fun modelConfig_upsertAndClear() = runBlocking {
        db.modelConfigDao().upsert(ModelConfig(role = "STORY", modelName = "gpt-4o", baseUrl = "https://x"))
        assertEquals("gpt-4o", db.modelConfigDao().getByRole("STORY")?.modelName)
        db.modelConfigDao().clearAll()
        assertTrue(db.modelConfigDao().getAll().isEmpty())
    }

    private fun project(now: Long): Project = Project(
        id = "p-1",
        title = "民法基础",
        topic = "民法",
        baseline = "完全不了解",
        depth = "入门",
        dailyMinutes = 20,
        targetDays = 30,
        genre = "悬疑",
        status = ProjectStatus.DRAFT,
        createdAt = now,
        updatedAt = now,
    )
}
