package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.PromptProvider
import com.example.zhishu.ai.pipeline.PipelineService
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.data.local.repo.DefaultChapterRepository
import com.example.zhishu.data.local.repo.DefaultKnowledgeRepository
import com.example.zhishu.data.local.repo.DefaultProjectRepository
import com.example.zhishu.data.local.repo.DefaultQuizRepository
import com.example.zhishu.data.local.repo.DefaultReviewRepository
import com.example.zhishu.data.local.repo.DefaultSourceRepository
import com.example.zhishu.data.local.repo.DefaultStoryBibleRepository
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.QuizState
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService
    private lateinit var fakeRouter: FakeRouter
    private var idCounter = 0

    /** 间隔复习开关（默认关，和产品默认一致），个别测试里改成 true 验证排复习 */
    private var spacedReview = false

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        fakeRouter = FakeRouter()
        idCounter = 0
        spacedReview = false
        pipeline = PipelineService(
            router = fakeRouter,
            prompts = MapPromptProvider(),
            projectRepository = DefaultProjectRepository(db.projectDao()),
            sourceRepository = DefaultSourceRepository(db.sourceDocumentDao(), db.sourceChunkDao()),
            knowledgeRepository = DefaultKnowledgeRepository(
                dao = db.knowledgeNodeDao(),
                gapDao = db.knowledgeGapDao(),
                conflictDao = db.knowledgeConflictDao(),
            ),
            chapterRepository = DefaultChapterRepository(db.chapterPlanDao(), db.chapterBlockDao()),
            storyBibleRepository = DefaultStoryBibleRepository(db.storyBibleDao()),
            quizRepository = DefaultQuizRepository(db.quizDao(), db.answerRecordDao()),
            reviewRepository = DefaultReviewRepository(db.reviewItemDao()),
            spacedReviewEnabled = { spacedReview },
            idFactory = { "id-${idCounter++}" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun generateKnowledgeMap_createsNodesAndUpdatesStatus() = runBlocking {
        seedProjectWithChunk()
        val result = pipeline.generateKnowledgeMap("p-1")
        assertTrue(result.isSuccess)
        val nodes = db.knowledgeNodeDao().getByProject("p-1")
        assertEquals(2, nodes.size)
        assertEquals(
            "意思表示",
            nodes.first { it.title == "民事法律行为" }.parentId?.let { parentId ->
                db.knowledgeNodeDao().getById(parentId)?.title
            },
        )
        assertEquals(ProjectStatus.MAP, db.projectDao().getById("p-1")?.status)
    }

    /** sourceRefs 必须保存片段 ID，且能据此加载回资料（B1/B19） */
    @Test
    fun generateKnowledgeMap_storesChunkIdsInSourceRefs() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        val node = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "意思表示" }
        assertEquals(listOf("c-1"), node.sourceRefs)
        val chunks = DefaultSourceRepository(db.sourceDocumentDao(), db.sourceChunkDao())
            .getChunksByIds("p-1", node.sourceRefs)
        assertEquals(1, chunks.size)
        assertTrue(chunks.first().text.contains("意思表示"))
    }

    /** 资料缺口与冲突必须持久化（B12） */
    @Test
    fun generateKnowledgeMap_persistsGapsAndConflicts() = runBlocking {
        seedProjectWithChunk()
        fakeRouter.knowledgeMapJson = """
            {"nodes":[{"title":"意思表示","definition":"核心要素。","depth":1,"sourceRefs":["c-1"]}],
             "gaps":["缺少合同解除的资料"],
             "conflicts":[{"topic":"撤销权期限","sources":["A","B"],"description":"两份资料期限不一致"}]}
        """.trimIndent()
        assertTrue(pipeline.generateKnowledgeMap("p-1").isSuccess)
        assertEquals(1, db.knowledgeGapDao().getByProject("p-1").size)
        assertEquals("撤销权期限", db.knowledgeConflictDao().getByProject("p-1").first().topic)
    }

    /** 空知识点必须直接失败，不写库（N14） */
    @Test
    fun generateKnowledgeMap_emptyNodes_failsValidation() = runBlocking {
        seedProjectWithChunk()
        fakeRouter.knowledgeMapJson = """{"nodes":[],"gaps":[],"conflicts":[]}"""
        val result = pipeline.generateKnowledgeMap("p-1")
        assertTrue(result.exceptionOrNull() is ModelError.Invalid)
        assertTrue(db.knowledgeNodeDao().getByProject("p-1").isEmpty())
    }

    @Test
    fun generateStoryBible_createsPlans() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        val result = pipeline.generateStoryBible("p-1")
        assertTrue(result.isSuccess)
        val plans = db.chapterPlanDao().getByProject("p-1")
        assertEquals(1, plans.size)
        assertEquals("第一章 遗嘱风波", plans[0].title)
        assertEquals(1, plans[0].nodeIds.size)
        assertEquals("意思表示", db.knowledgeNodeDao().getById(plans[0].nodeIds.first())?.title)
        assertEquals(ProjectStatus.OUTLINE, db.projectDao().getById("p-1")?.status)
    }

    /** 章节规划完全不关联已有知识点时必须失败（N14） */
    @Test
    fun generateStoryBible_unmappedNodes_fails() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        fakeRouter.storyBibleJson =
            """{"world":"x","characters":[],"conflict":"c","style":"s","chapters":[{"order":1,"title":"章","nodeTitles":["不存在的知识点"],"wordTarget":1000}]}"""
        val result = pipeline.generateStoryBible("p-1")
        assertTrue(result.exceptionOrNull() is ModelError.Invalid)
    }

    @Test
    fun generateChapter_publishesBlocksAndQuiz() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        val result = pipeline.generateChapter("p-1", plan.id)
        assertTrue(result.isSuccess)
        val loaded = db.chapterPlanDao().getById(plan.id)
        assertEquals(ChapterStatus.PUBLISHED, loaded?.status)
        val blocks = db.chapterBlockDao().getByChapter(plan.id)
        assertTrue(blocks.isNotEmpty())
        // 正文块必须带 projectId，删除项目时才能清理（N6）
        assertTrue(blocks.all { it.projectId == "p-1" })
        assertEquals(3, db.quizDao().getByProject("p-1").size)
        // 发布后回写节点出现章节（B16）
        val node = db.knowledgeNodeDao().getByProject("p-1").first { it.title == "意思表示" }
        assertTrue(node.chapterIds.contains(plan.id))
        // 复习默认关闭：读完一章不会自动生出复习任务（第一阶段：故事优先）
        assertTrue(db.reviewItemDao().getByProject("p-1").isEmpty())
        assertEquals(ProjectStatus.READY, db.projectDao().getById("p-1")?.status)
    }

    /** 用户在设置里打开间隔复习后，发布章节才排首次复习（B2） */
    @Test
    fun generateChapter_withSpacedReviewEnabled_schedulesReviews() = runBlocking {
        spacedReview = true
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        assertTrue(pipeline.generateChapter("p-1", plan.id).isSuccess)
        assertTrue(db.reviewItemDao().getByProject("p-1").isNotEmpty())
    }

    /** 出题必须走 VERIFY 角色，与设置页说明一致（N3） */
    @Test
    fun generateQuiz_usesVerifyRole() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        pipeline.generateChapter("p-1", plan.id)
        assertEquals(ModelRole.VERIFY, fakeRouter.quizRole)
        assertEquals(ModelRole.VERIFY, fakeRouter.verifyRole)
        assertEquals(ModelRole.FAST, fakeRouter.knowledgeMapRole)
        assertEquals(ModelRole.STORY, fakeRouter.chapterRole)
    }

    /** 测验生成失败时不得发布章节（N2） */
    @Test
    fun generateChapter_quizFails_stillPublishesBody() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        fakeRouter.quizJson = """{"questions":[]}"""
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        // 出题失败不算这一章失败：正文写好了、核验也过了
        val result = pipeline.generateChapter("p-1", plan.id)
        assertTrue(result.isSuccess)
        val loaded = db.chapterPlanDao().getById(plan.id)
        assertEquals(ChapterStatus.PUBLISHED, loaded?.status)
        assertTrue(loaded?.isReadable == true)
        // 失败只落在测验状态上，正文的核验说明不受污染
        assertEquals(QuizState.FAILED, loaded?.quizState)
        assertTrue(loaded?.quizNote.orEmpty().isNotBlank())
        assertTrue(db.quizDao().getByProject("p-1").isEmpty())
        // 正文块在库里，能读
        assertTrue(db.chapterBlockDao().getByChapter(plan.id).isNotEmpty())
    }

    /** 题目数量不足最低要求：仍然只影响测验状态（第二阶段） */
    @Test
    fun generateChapter_tooFewQuizzes_marksQuizFailedOnly() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        fakeRouter.quizJson =
            """{"questions":[{"question":"Q1","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["意思表示"]}]}"""
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        assertTrue(pipeline.generateChapter("p-1", plan.id).isSuccess)
        val loaded = db.chapterPlanDao().getById(plan.id)
        assertEquals(ChapterStatus.PUBLISHED, loaded?.status)
        assertEquals(QuizState.FAILED, loaded?.quizState)
    }

    /** 题目引用不存在的知识点时被过滤掉，同样不拖累正文（第二阶段） */
    @Test
    fun generateChapter_quizWithUnknownNodes_keepsBodyReadable() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        fakeRouter.quizJson = """
            {"questions":[
              {"question":"Q1","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["幽灵知识点"]},
              {"question":"Q2","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["幽灵知识点"]},
              {"question":"Q3","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["幽灵知识点"]}
            ]}
        """.trimIndent()
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        assertTrue(pipeline.generateChapter("p-1", plan.id).isSuccess)
        val loaded = db.chapterPlanDao().getById(plan.id)
        assertEquals(ChapterStatus.PUBLISHED, loaded?.status)
        assertEquals(QuizState.FAILED, loaded?.quizState)
    }

    @Test
    fun generateChapter_withIssues_marksNeedsReview() = runBlocking {
        seedProjectWithChunk()
        fakeRouter.verifyJson =
            """{"issues":[{"type":"contradiction","blockId":"b-1","message":"与资料矛盾","sourceRefs":["c-1"],"suggestedFix":"修改"}]}"""
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        val result = pipeline.generateChapter("p-1", plan.id)
        assertTrue(result.isFailure)
        val loaded = db.chapterPlanDao().getById(plan.id)
        assertEquals(ChapterStatus.NEEDS_REVIEW, loaded?.status)
        assertTrue(loaded?.verificationNote.orEmpty().contains("contradiction"))
    }

    /** 章节生成期间项目状态进入 GENERATING（B17） */
    @Test
    fun generateChapter_setsGeneratingStatusOnFailure() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        fakeRouter.chapterJson = """{"title":"t","summary":"s","blocks":[]}"""
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        assertTrue(pipeline.generateChapter("p-1", plan.id).isFailure)
        assertEquals(ProjectStatus.GENERATING, db.projectDao().getById("p-1")?.status)
        assertEquals(ChapterStatus.FAILED, db.chapterPlanDao().getById(plan.id)?.status)
    }

    /** 重复生成同一章不会叠加正文 */
    @Test
    fun regenerateChapter_replacesBlocks() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        pipeline.generateChapter("p-1", plan.id)
        val firstCount = db.chapterBlockDao().getByChapter(plan.id).size
        pipeline.generateChapter("p-1", plan.id)
        assertEquals(firstCount, db.chapterBlockDao().getByChapter(plan.id).size)
    }

    @Test
    fun generateKnowledgeMap_withoutChunks_fails() = runBlocking {
        db.projectDao().upsert(project())
        val result = pipeline.generateKnowledgeMap("p-1")
        assertTrue(result.isFailure)
    }

    @Test
    fun refreshProjectStatus_readyOnlyWhenAllPublished() = runBlocking {
        seedProjectWithChunk()
        pipeline.generateKnowledgeMap("p-1")
        pipeline.generateStoryBible("p-1")
        pipeline.refreshProjectStatus("p-1")
        assertEquals(ProjectStatus.GENERATING, db.projectDao().getById("p-1")?.status)
        assertFalse(db.chapterPlanDao().getByProject("p-1").isEmpty())
        val plan = db.chapterPlanDao().getByProject("p-1").first()
        pipeline.generateChapter("p-1", plan.id)
        assertEquals(ProjectStatus.READY, db.projectDao().getById("p-1")?.status)
        assertNotNull(db.chapterPlanDao().getById(plan.id))
    }

    private fun project(status: ProjectStatus = ProjectStatus.DRAFT) = Project(
        id = "p-1",
        title = "民法基础",
        topic = "民法",
        baseline = "入门",
        depth = "入门",
        dailyMinutes = 20,
        targetDays = 30,
        genre = "悬疑",
        status = status,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private suspend fun seedProjectWithChunk() {
        db.projectDao().upsert(project())
        val source = SourceDocument(
            id = "src-1",
            projectId = "p-1",
            title = "民法笔记",
            kind = SourceKind.TEXT,
            fetchedAt = 1L,
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
                    text = "意思表示是民事法律行为的核心要素。",
                ),
            ),
        )
    }

    private class MapPromptProvider : PromptProvider {
        // 返回值里带一个稳定的阶段标记，FakeRouter 据此分辨阶段，
        // 不再依赖会随产品文案变化的 system 串。
        private val map = mapOf(
            "knowledge_map" to "[[MAP]]",
            "knowledge_map_model" to "[[MAP]]",
            "story_bible" to "[[BIBLE]]",
            "chapter" to "[[CHAPTER]]",
            "verify" to "[[VERIFY]]",
            "verify_model" to "[[VERIFY]]",
            "quiz" to "[[QUIZ]]",
        )

        override fun load(name: String): String = map[name] ?: name
    }

    private class FakeRouter : ModelRouter {
        var knowledgeMapJson: String =
            """{"nodes":[{"title":"意思表示","definition":"民事法律行为的核心要素。","depth":1,"sourceRefs":["c-1"]},{"title":"民事法律行为","definition":"以意思表示为核心的行为。","parentTitle":"意思表示","depth":2,"sourceRefs":["c-1"]}],"gaps":[],"conflicts":[]}"""
        var storyBibleJson: String =
            """{"world":"滨海市","characters":[{"name":"林默","role":"主角","description":"年轻律师"}],"conflict":"遗产继承纠纷","style":"悬疑","chapters":[{"order":1,"title":"第一章 遗嘱风波","plotGoal":"引出遗嘱问题","learningGoals":["意思表示"],"nodeTitles":["意思表示"],"wordTarget":1000}]}"""
        var chapterJson: String =
            """{"title":"第一章 遗嘱风波","summary":"老人立下遗嘱后引发纠纷","blocks":[{"type":"STORY","text":"老人立下遗嘱，子女们各怀心思。","knowledgeNodeTitles":["意思表示"],"sourceRefs":["c-1"]},{"type":"EXPLANATION","text":"意思表示是民事法律行为的核心要素。","knowledgeNodeTitles":["意思表示"],"sourceRefs":["c-1"]}]}"""
        var verifyJson: String = """{"issues":[]}"""
        var quizJson: String = """
            {"questions":[
              {"question":"意思表示是否为民法概念？","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"依据资料。","nodeTitles":["意思表示"]},
              {"question":"意思表示是民事法律行为的核心要素吗？","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"依据资料。","nodeTitles":["意思表示"]},
              {"question":"请回忆意思表示的定义。","type":"RECALL","options":[],"answer":"行为人将内心意思表达于外","explanation":"依据资料。","nodeTitles":["意思表示"]}
            ]}
        """.trimIndent()

        var knowledgeMapRole: ModelRole? = null
        var chapterRole: ModelRole? = null
        var verifyRole: ModelRole? = null
        var quizRole: ModelRole? = null

        override suspend fun <T> generate(
            role: ModelRole,
            request: ModelRequest,
            decoder: JsonDecoder<T>,
        ): Result<T> {
            val text = when {
                request.user.contains("[[MAP]]") -> {
                    knowledgeMapRole = role; knowledgeMapJson
                }
                request.user.contains("[[BIBLE]]") -> storyBibleJson
                request.user.contains("[[CHAPTER]]") -> {
                    chapterRole = role; chapterJson
                }
                request.user.contains("[[VERIFY]]") -> {
                    verifyRole = role; verifyJson
                }
                request.user.contains("[[QUIZ]]") -> {
                    quizRole = role; quizJson
                }
                else -> error("没有匹配的测试数据: ${request.user.take(40)}")
            }
            return try {
                Result.success(decoder.decode(text))
            } catch (e: Exception) {
                Result.failure(ModelError.Decode(e.message ?: "decode failed"))
            }
        }
    }
}
