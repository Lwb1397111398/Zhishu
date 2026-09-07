package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.pipeline.PipelineService
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.data.local.repo.DefaultChapterRepository
import com.example.zhishu.data.local.repo.DefaultCitationRepository
import com.example.zhishu.data.local.repo.DefaultVerificationIssueRepository
import com.example.zhishu.data.local.repo.DefaultKnowledgeRepository
import com.example.zhishu.data.local.repo.DefaultProjectRepository
import com.example.zhishu.data.local.repo.DefaultQuizRepository
import com.example.zhishu.data.local.repo.DefaultReviewRepository
import com.example.zhishu.data.local.repo.DefaultSourceRepository
import com.example.zhishu.data.local.repo.DefaultStoryBibleRepository
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.domain.model.SourceMode
import com.example.zhishu.search.Chunker
import com.example.zhishu.search.SourcePage
import com.example.zhishu.ui.viewmodel.BlockSourceSummary
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

/**
 * 来源与引用可追溯（第四阶段 4A）。
 *
 * 要回答的只有一个问题：**这一段内容从哪来。** 在 4A 之前只能回答一半——
 * 正文块里躺着几个片段 ID，片段自己不知道在原资料的第几页第几节，
 * 而模型给出的对不上的引用在校验时被静默丢掉了：问题消失了，
 * 那句需要出处的话变成了没人管的断言，阅读器还会说「本段无引用」。
 *
 * 所以这一组测试盯的是三件事：定位信息真的存下来了、引用绑在正确的版本上、
 * 对不上的引用被如实记成「未确认」而不是被抹掉或被硬猜成某个片段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceTraceTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService
    private lateinit var router: ScriptedRouter
    private var ids = 0

    /** 带 {sourceSection} 的章节模板：喂给模型的来源标识长什么样，只能从提示词里验 */
    private val chapterTemplate = "[[CHAPTER]] 资料区=<{sourceSection}>"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        router = ScriptedRouter()
        ids = 0
        pipeline = PipelineService(
            router = router,
            prompts = TaggedPromptProvider(chapterTemplate),
            projectRepository = DefaultProjectRepository(db.projectDao()),
            sourceRepository = DefaultSourceRepository(db.sourceDocumentDao(), db.sourceChunkDao()),
            knowledgeRepository = DefaultKnowledgeRepository(
                dao = db.knowledgeNodeDao(),
                gapDao = db.knowledgeGapDao(),
                db = db,
                conflictDao = db.knowledgeConflictDao(),
                batchDao = db.knowledgeMapBatchDao(),
                routeBatchDao = db.routeBatchDao(),
            ),
            chapterRepository = DefaultChapterRepository(
                db.chapterPlanDao(),
                db.chapterBlockDao(),
                db.chapterVersionDao(),
            ),
            citationRepository = DefaultCitationRepository(db.citationDao()),
            issueRepository = DefaultVerificationIssueRepository(db.verificationIssueDao()),
            storyBibleRepository = DefaultStoryBibleRepository(db.storyBibleDao()),
            quizRepository = DefaultQuizRepository(db.quizDao(), db.answerRecordDao()),
            reviewRepository = DefaultReviewRepository(db.reviewItemDao()),
            idFactory = { "id-${ids++}" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- 片段定位：稳定锚点，不用字符偏移 ---

    /** PDF 按页切分：每个片段记住自己在第几页，页码从 1 开始，与阅读软件显示的一致 */
    @Test
    fun chunker_recordsPageNumbersForPagedSources() {
        val chunks = Chunker.chunkPages(
            sourceId = "src-1",
            pages = listOf(
                SourcePage(1, "第一页的内容。"),
                SourcePage(2, "第二页的内容。"),
            ),
            projectId = "p-1",
        )
        assertEquals(listOf(1, 2), chunks.map { it.pageNumber })
        assertEquals("第 1 页", chunks.first().locatorLabel)
    }

    /** 没有页码概念的资料（文本、网页）不假装自己在第 0 页 */
    @Test
    fun chunker_leavesPageNumberNullForUnpagedSources() {
        val chunks = Chunker.chunk("src-1", "一段内容。")
        assertNull(chunks.single().pageNumber)
        // 也不至于什么都说不出来：至少能说到第几段
        assertEquals("第 1 段", chunks.single().locatorLabel)
    }

    /** 章节路径按标题层级拼出来，跨页延续——一节内容横跨三页是 PDF 的常态 */
    @Test
    fun chunker_buildsSectionPathAcrossPages() {
        val chunks = Chunker.chunkPages(
            sourceId = "src-1",
            pages = listOf(
                SourcePage(1, "# 第三章 合同的订立\n## 第二节 要约\n要约是希望与他人订立合同的意思表示。"),
                SourcePage(2, "要约邀请不同于要约。"),
            ),
            projectId = "p-1",
        )
        assertEquals("第三章 合同的订立 > 第二节 要约", chunks.first().sectionPath)
        // 第 2 页没有新标题，仍然属于同一节
        assertEquals("第三章 合同的订立 > 第二节 要约", chunks.last().sectionPath)
        assertEquals(2, chunks.last().pageNumber)
    }

    /** 中文标题也认，但只认到「编/章/节」——把每一条法条都当标题会拼出整部法律的目录 */
    @Test
    fun chunker_recognizesChineseHeadingsButNotArticles() {
        val chunks = Chunker.chunk(
            "src-1",
            "第三章 合同的订立\n第二节 要约\n第四百七十二条 要约是希望与他人订立合同的意思表示。",
        )
        assertEquals("第三章 合同的订立 > 第二节 要约", chunks.single().sectionPath)
        assertFalse(chunks.single().sectionPath.orEmpty().contains("第四百七十二条"))
    }

    /** 进一级标题时清掉更深的层级，否则上一节的小标题会挂在新节下面 */
    @Test
    fun chunker_resetsDeeperSectionsOnNewChapter() {
        val chunks = Chunker.chunk(
            "src-1",
            "# 第一章 总则\n## 第一节 基本规定\n" + "甲".repeat(2100) + "\n# 第二章 物权\n这一章讲的是物权。",
        )
        assertEquals("第二章 物权", chunks.last().sectionPath)
    }

    /**
     * 「第二章的正文接着说」不是标题，是一句正常的话。
     *
     * 序号后面必须跟空白才算标题。少了这一条，正文里任何以「第三章……」开头的句子
     * 都会变成一级标题，整段内容随之挂到一个不存在的章节下面——
     * 用户点开来源，看到的定位是假的。
     */
    @Test
    fun chunker_doesNotTreatSentencesAsHeadings() {
        val chunks = Chunker.chunk("src-1", "# 第一章 总则\n第二章的内容我们放到后面讲。")
        assertEquals("第一章 总则", chunks.single().sectionPath)
    }

    /** 段落序号是全局递增的，因为回跳要能在整份资料里数下去 */
    @Test
    fun chunker_recordsParagraphIndexGlobally() {
        val chunks = Chunker.chunkPages(
            sourceId = "src-1",
            pages = listOf(
                SourcePage(1, "甲".repeat(1900) + "\n" + "乙".repeat(1900)),
                SourcePage(2, "丙".repeat(1900)),
            ),
            projectId = "p-1",
        )
        assertEquals(listOf(0, 1, 2), chunks.map { it.paragraphIndex })
    }

    // --- 喂给模型的来源标识 ---

    /**
     * 提示词里的片段带上出处、页码、章节、版本、法域。
     *
     * 没有这些，模型看到的只是一串 UUID：引用回不去具体位置，
     * 而且它不知道某条规则只在某个法域、某个时间之后成立，会讲成普适规则。
     */
    @Test
    fun chapterPrompt_carriesStructuredSourceIdentity() = runBlocking {
        seedThroughBible()
        val planId = db.chapterPlanDao().getByProject("p-1").minBy { it.order }.id
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val prompt = router.lastPrompts[ScriptedRouter.Phase.CHAPTER].orEmpty()
        assertTrue(prompt.contains("[片段ID: c-1]"))
        assertTrue(prompt.contains("民法典释义"))
        assertTrue(prompt.contains("第 12 页 · 第三章 合同的订立 > 第二节 要约"))
        assertTrue(prompt.contains("版本：2020 年修正"))
        assertTrue(prompt.contains("法域：中国大陆"))
        assertTrue(prompt.contains("生效：2021-01-01"))
    }

    /** 提示词必须说明「编 ID 会被记成未确认」——不说，模型没有理由不去凑一个 */
    @Test
    fun chapterPrompt_warnsThatFabricatedIdsBecomeUnconfirmed() = runBlocking {
        seedThroughBible()
        val planId = db.chapterPlanDao().getByProject("p-1").minBy { it.order }.id
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val prompt = router.lastPrompts[ScriptedRouter.Phase.CHAPTER].orEmpty()
        assertTrue(prompt.contains("不要编造片段ID"))
        assertTrue(prompt.contains("未确认引用"))
    }

    // --- 引用解析：能对上的落地，对不上的如实记账 ---

    /** 正常引用落成 RESOLVED，并写进块的 sourceRefs */
    @Test
    fun resolvedCitation_bindsBlockToChunk() = runBlocking {
        seedThroughBible()
        val planId = db.chapterPlanDao().getByProject("p-1").minBy { it.order }.id
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val citations = db.citationDao().getActiveByChapter(planId)
        assertTrue(citations.isNotEmpty())
        assertTrue(citations.all { it.status == CitationStatus.RESOLVED })
        assertTrue(citations.all { it.chunkId == "c-1" })
        // 版本绑定：引用挂在这一版正文上，不是挂在章节上
        val activeVersion = db.chapterPlanDao().getById(planId)?.activeVersionId
        assertTrue(citations.all { it.versionId == activeVersion })
        // 块的 sourceRefs 与引用一致
        assertTrue(db.chapterBlockDao().getActiveBlocks(planId).all { it.sourceRefs == listOf("c-1") })
    }

    /**
     * 模型编出来的片段 ID 记成未确认，**不丢掉也不硬猜**。
     *
     * 丢掉的后果：那句需要出处的话变成没人管的断言，界面还会显示「本段无引用」，
     * 看起来跟模型本来就没打算给出处一样。硬猜一个最像的片段更糟——
     * 那是我们自己编了一个出处，还带着「系统确认过」的外观。
     */
    @Test
    fun fabricatedRef_isRecordedAsUnconfirmed() = runBlocking {
        seedThroughBible()
        router.chapterJson = router.chapterJson.replace(
            """"sourceRefs":["c-1"]}]}""",
            """"sourceRefs":["c-1","《民法典》第472条"]}]}""",
        )
        val planId = db.chapterPlanDao().getByProject("p-1").minBy { it.order }.id
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val citations = db.citationDao().getActiveByChapter(planId)
        val unconfirmed = citations.filter { it.status == CitationStatus.UNCONFIRMED }
        assertEquals(1, unconfirmed.size)
        // 原样留着模型给的标识——未确认时它是唯一线索
        assertEquals("《民法典》第472条", unconfirmed.single().rawRef)
        assertNull(unconfirmed.single().chunkId)
        // 但它没有混进块的 sourceRefs：那一列只装真的解析到的片段
        val block = db.chapterBlockDao().getActiveBlocks(planId)
            .single { it.id == unconfirmed.single().blockId }
        assertEquals(listOf("c-1"), block.sourceRefs)
    }

    /** 同一块里重复给同一个片段只记一条，否则「本章有几处来源」会虚高 */
    @Test
    fun duplicateRefsInOneBlock_areRecordedOnce() = runBlocking {
        seedThroughBible()
        router.chapterJson = router.chapterJson.replace(
            """"sourceRefs":["c-1"]}]}""",
            """"sourceRefs":["c-1","c-1"," c-1 "]}]}""",
        )
        val planId = db.chapterPlanDao().getByProject("p-1").minBy { it.order }.id
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val lastBlock = db.chapterBlockDao().getActiveBlocks(planId).last()
        assertEquals(1, db.citationDao().getActiveByChapter(planId).count { it.blockId == lastBlock.id })
    }

    /** 无资料模式下不该有任何引用，而且这个事实要记在版本上 */
    @Test
    fun modelKnowledgeMode_recordsNoCitationsAndMarksVersion() = runBlocking {
        seedModelKnowledgeProject()
        val planId = db.chapterPlanDao().getByProject("p-2").minBy { it.order }.id
        pipeline.generateChapter("p-2", planId).getOrThrow()

        assertTrue(db.citationDao().getActiveByChapter(planId).isEmpty())
        val version = db.chapterVersionDao().getActive(planId)
        assertTrue(version?.usedModelKnowledge == true)
    }

    /**
     * 「这一版是 AI 自备知识写的」记在版本上，不能现查项目设置。
     *
     * 用户完全可能先用无资料模式写了几章、之后才导入资料。那时候按项目设置看，
     * 那几章会显示成「有出处」——它们一条引用都没有，因为当时没有资料可引。
     */
    @Test
    fun switchingToDocumentsMode_doesNotRelabelOldVersions() = runBlocking {
        seedModelKnowledgeProject()
        val planId = db.chapterPlanDao().getByProject("p-2").minBy { it.order }.id
        pipeline.generateChapter("p-2", planId).getOrThrow()
        val versionId = db.chapterPlanDao().getById(planId)?.activeVersionId

        val project = db.projectDao().getById("p-2")!!
        db.projectDao().upsert(project.copy(sourceMode = SourceMode.DOCUMENTS))

        assertTrue(db.chapterVersionDao().getById(versionId!!)?.usedModelKnowledge == true)
    }

    // --- 版本隔离：当前阅读只看 active version 的引用 ---

    /**
     * 重生成之后，当前阅读只看到新版本的引用；旧版本的引用一条不删。
     *
     * 两件事都要成立：混进来会让「本章有几处来源」变成一个自己也解释不了的数字，
     * 而删掉会让恢复出来的历史版本变成一段没有出处的正文。
     */
    @Test
    fun regeneration_showsOnlyNewCitationsButKeepsOldOnes() = runBlocking {
        seedThroughBible()
        val planId = db.chapterPlanDao().getByProject("p-1").minBy { it.order }.id
        pipeline.generateChapter("p-1", planId).getOrThrow()
        val firstVersion = db.chapterPlanDao().getById(planId)?.activeVersionId!!
        val firstCount = db.citationDao().getByVersion(firstVersion).size
        assertTrue(firstCount > 0)

        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "第二版的开头。")
        pipeline.generateChapter("p-1", planId).getOrThrow()
        val secondVersion = db.chapterPlanDao().getById(planId)?.activeVersionId!!
        assertTrue(secondVersion != firstVersion)

        // 当前阅读只看新版本
        val active = db.citationDao().getActiveByChapter(planId)
        assertTrue(active.isNotEmpty())
        assertTrue(active.all { it.versionId == secondVersion })
        // 旧版本的引用还在库里
        assertEquals(firstCount, db.citationDao().getByVersion(firstVersion).size)
    }

    /** 反向定位：从资料片段找回引用它的**当前**正文块，不能返回读者看不到的历史块 */
    @Test
    fun reverseLookup_returnsOnlyActiveVersionBlocks() = runBlocking {
        seedThroughBible()
        val planId = db.chapterPlanDao().getByProject("p-1").minBy { it.order }.id
        pipeline.generateChapter("p-1", planId).getOrThrow()
        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "第二版的开头。")
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val activeVersion = db.chapterPlanDao().getById(planId)?.activeVersionId
        val back = db.citationDao().getActiveByChunk("p-1", "c-1")
        assertTrue(back.isNotEmpty())
        assertTrue(back.all { it.versionId == activeVersion })
        val activeBlockIds = db.chapterBlockDao().getActiveBlocks(planId).map { it.id }.toSet()
        assertTrue(back.all { it.blockId in activeBlockIds })
    }

    /** 章节级计数只数当前版本，可定位与待核对分开数（4C 的章节摘要靠它） */
    @Test
    fun chapterCounts_separateResolvedFromUnconfirmed() = runBlocking {
        seedThroughBible()
        router.chapterJson = router.chapterJson.replace(
            """"sourceRefs":["c-1"]}]}""",
            """"sourceRefs":["c-1","编的出处"]}]}""",
        )
        val planId = db.chapterPlanDao().getByProject("p-1").minBy { it.order }.id
        pipeline.generateChapter("p-1", planId).getOrThrow()

        assertEquals(
            2,
            db.citationDao().countActiveByChapterAndStatus(planId, CitationStatus.RESOLVED),
        )
        assertEquals(
            1,
            db.citationDao().countActiveByChapterAndStatus(planId, CitationStatus.UNCONFIRMED),
        )
    }

    // --- 资料被删：引用降级，不删 ---

    /**
     * 删掉资料后引用变成「来源已删除」，正文一个字不动，并保留原资料标题。
     *
     * 删掉引用是错的：正文里那段话还在，它当初确实有依据。
     * 标成普通「未确认」也不够准确：用户需要知道这是资料后来被删，而不是模型一开始就没对上。
     */
    @Test
    fun deletingSource_marksCitationsAsDeletedInsteadOfRemovingThem() = runBlocking {
        seedThroughBible()
        val planId = db.chapterPlanDao().getByProject("p-1").minBy { it.order }.id
        pipeline.generateChapter("p-1", planId).getOrThrow()
        val before = db.citationDao().getActiveByChapter(planId).size
        val blocksBefore = db.chapterBlockDao().getActiveBlocks(planId).size

        // 必须在删除资料行之前调用，DAO 才能抄下原资料标题。
        db.citationDao().unconfirmForSource("src-1", CitationStatus.SOURCE_DELETED)
        db.sourceChunkDao().deleteBySource("src-1")
        db.sourceDocumentDao().deleteById("src-1")

        val after = db.citationDao().getActiveByChapter(planId)
        assertEquals(before, after.size)
        assertTrue(after.all { it.status == CitationStatus.SOURCE_DELETED })
        assertTrue(after.all { it.lostSourceTitle == "民法典释义" })
        // 原始标识留着，用户还能看出当初引的是什么
        assertTrue(after.all { it.rawRef == "c-1" })
        assertEquals(blocksBefore, db.chapterBlockDao().getActiveBlocks(planId).size)
    }

    // --- 界面拼装 ---

    /** 界面上的那一行小字：四种情况分开说，因为它们对读者的意思完全不同 */
    @Test
    fun blockSourceSummary_saysWhichOfFourSituationsItIs() = runBlocking {
        seedThroughBible()
        router.chapterJson = router.chapterJson.replace(
            """"sourceRefs":["c-1"]}]}""",
            """"sourceRefs":["c-1","编的出处"]}]}""",
        )
        val planId = db.chapterPlanDao().getByProject("p-1").minBy { it.order }.id
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        val citations = db.citationDao().getActiveByChapter(planId)
        val summaries = BlockSourceSummary.build(
            blockIds = blocks.map { it.id },
            citations = citations,
            chunks = db.sourceChunkDao().getByProject("p-1").associateBy { it.id },
            documents = db.sourceDocumentDao().getByProject("p-1").associateBy { it.id },
            modelKnowledge = false,
        )
        val withUnconfirmed = summaries.getValue(blocks.last().id)
        assertEquals("来源 1 处 · 1 处待核对", withUnconfirmed.label)
        val clean = summaries.getValue(blocks.first().id)
        assertEquals("来源 1 处", clean.label)
        // 未确认的那一条能说出资料名对不上，也不装作有原文
        val bad = withUnconfirmed.sources.single { it.needsManualCheck }
        assertEquals("未能匹配到资料", bad.title)
        assertEquals("", bad.excerpt)
        // 正常那一条能说出页码、版本、法域
        val good = withUnconfirmed.sources.single { !it.needsManualCheck }
        assertEquals("第 12 页 · 第三章 合同的订立 > 第二节 要约", good.locator)
        assertTrue(good.versionInfo.contains("中国大陆"))
        assertTrue(good.versionInfo.contains("2020 年修正"))
    }

    /** 一条引用都没有时，「AI 自备知识」和「没标来源」不是同一句话 */
    @Test
    fun blockWithoutCitations_distinguishesModelKnowledgeFromMissingSource() {
        val asModel = BlockSourceSummary(blockId = "b", sources = emptyList(), modelKnowledge = true)
        assertEquals("AI 自备知识", asModel.label)
        val asMissing = BlockSourceSummary(blockId = "b", sources = emptyList(), modelKnowledge = false)
        assertEquals("未标注来源", asMissing.label)
    }

    // --- 种子 ---

    /** 有资料模式的项目：一份带法域与生效时间的资料，一个带页码与章节的片段 */
    private suspend fun seedThroughBible() {
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
                createdAt = 1L,
                updatedAt = 1L,
                sourceMode = SourceMode.DOCUMENTS,
            ),
        )
        db.sourceDocumentDao().upsert(
            SourceDocument(
                id = "src-1",
                projectId = "p-1",
                title = "民法典释义",
                kind = SourceKind.PDF,
                fileName = "minfadian.pdf",
                fetchedAt = 1L,
                contentHash = "h",
                parseStatus = ParseStatus.PARSED,
                jurisdiction = "中国大陆",
                effectiveFrom = "2021-01-01",
                legalVersion = "2020 年修正",
            ),
        )
        db.sourceChunkDao().upsertAll(
            listOf(
                SourceChunk(
                    id = "c-1",
                    sourceId = "src-1",
                    projectId = "p-1",
                    ordinal = 0,
                    text = "意思表示是民事法律行为的核心要素。",
                    pageNumber = 12,
                    sectionPath = "第三章 合同的订立 > 第二节 要约",
                    paragraphIndex = 3,
                ),
            ),
        )
        pipeline.generateKnowledgeMap("p-1").getOrThrow()
        pipeline.generateStoryBible("p-1").getOrThrow()
        router.calls.clear()
    }

    /** 无资料模式的项目：没有任何资料，正文一条引用都引不到 */
    private suspend fun seedModelKnowledgeProject() {
        db.projectDao().upsert(
            Project(
                id = "p-2",
                title = "刑法基础",
                topic = "刑法",
                baseline = "入门",
                depth = "入门",
                dailyMinutes = 20,
                targetDays = 30,
                genre = "悬疑",
                status = ProjectStatus.DRAFT,
                createdAt = 1L,
                updatedAt = 1L,
                sourceMode = SourceMode.MODEL,
            ),
        )
        pipeline.generateKnowledgeMap("p-2").getOrThrow()
        pipeline.generateStoryBible("p-2").getOrThrow()
        // 无资料模式下不能引任何片段
        router.chapterJson = router.chapterJson.replace(""""sourceRefs":["c-1"]""", """"sourceRefs":[]""")
        router.calls.clear()
    }
}
