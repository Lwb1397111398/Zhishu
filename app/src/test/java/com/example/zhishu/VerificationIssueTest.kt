package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.pipeline.PipelineService
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.data.local.repo.DefaultChapterRepository
import com.example.zhishu.data.local.repo.DefaultCitationRepository
import com.example.zhishu.data.local.repo.DefaultKnowledgeRepository
import com.example.zhishu.data.local.repo.DefaultProjectRepository
import com.example.zhishu.data.local.repo.DefaultQuizRepository
import com.example.zhishu.data.local.repo.DefaultReviewRepository
import com.example.zhishu.data.local.repo.DefaultSourceRepository
import com.example.zhishu.data.local.repo.DefaultStoryBibleRepository
import com.example.zhishu.data.local.repo.DefaultVerificationIssueRepository
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.IssueResolution
import com.example.zhishu.domain.model.IssueType
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 核验问题可操作化与局部重写（第四阶段 4B）。
 *
 * 4B 之前，「核验没通过」是一句没法处理的话：一整章的问题被拼成一个字符串，
 * 第 6 条起丢掉，blockId 全部蒸发，用户唯一的出路是整章重新生成——
 * 把写对的部分连同写错的部分一起推翻，再扣一次故事模型的钱。
 *
 * 这一组测试盯两句承诺：
 * 1. 每条问题都能定位、能查看、能处理；
 * 2. 局部重写全程不碰 active version，失败/取消/仍有问题时旧正文一个字不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VerificationIssueTest {

    private lateinit var db: AppDatabase
    private lateinit var pipeline: PipelineService
    private lateinit var router: ScriptedRouter
    private var ids = 0

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
            prompts = TaggedPromptProvider(),
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

    // --- 问题记录本身 ---

    /**
     * 核验问题逐条落库，一条都不丢，而且定位到具体的正文块。
     *
     * 原来是 `take(5)` + 拼字符串。第 6 条起消失这件事最要命的地方在于：
     * 用户看不出「还有问题没告诉我」，他以为改完这 5 条就干净了。
     */
    @Test
    fun verificationIssues_areRecordedPerBlockAndNoneDropped() = runBlocking {
        val planId = seedThroughBible()
        verifyWithIssues(planId) { blocks ->
            issuesJson(
                issue("contradiction", blocks[0], "继承顺序说错了"),
                issue("citation_error", blocks[1], "这段引的片段不支持这句话"),
                issue("broken_derivation", null, "全章推演在中段断了一次"),
                issue("scope_error", blocks[1], "这条规则只在某个法域成立"),
                issue("我自己编的类型", blocks[0], "说不清是什么问题"),
                issue("fabrication", blocks[1], "编了一个不存在的条文号"),
            )
        }
        // 问题不再是整章失败：正文发布（可读），自动修复（定位到 2 个真实块）重写一轮
        // 后重新核验仍报问题 → 草稿作废，章节标待复核
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        // 六条一条不少（原来第 6 条会被 take(5) 吃掉），都挂在发布出去的版本上
        val version = db.chapterVersionDao().getActive(planId)!!
        val issues = db.verificationIssueDao().getByVersion(version.id)
        assertEquals(6, issues.size)

        // 类型映射：认得出的归类，认不出的落 OTHER 但原文留着
        assertEquals(IssueType.CONTRADICTION, issues[0].type)
        assertEquals(IssueType.CITATION_ERROR, issues[1].type)
        assertEquals(IssueType.BROKEN_DERIVATION, issues[2].type)
        assertEquals(IssueType.SCOPE_ERROR, issues[3].type)
        assertEquals(IssueType.OTHER, issues[4].type)
        assertEquals("我自己编的类型", issues[4].rawType)
        assertEquals(IssueType.FABRICATION, issues[5].type)

        // 定位：五条挂在块上，一条明确定位不到
        assertEquals(5, issues.count { it.isBlockScoped })
        assertNull(issues[2].blockId)
        assertTrue(issues.all { it.resolution == IssueResolution.OPEN })
        // 建议改法与相关来源都留着——用户要判断该不该改，这两样是依据
        assertEquals("改成配偶、子女、父母", issues[0].suggestedFix)
        assertEquals(listOf("c-1"), issues[0].sourceRefs)
    }

    /**
     * 模型给了一个不存在的 blockId 时，记成「定位不到具体段落」，而不是猜一个最像的块。
     *
     * 猜错的后果是用户点开一段没有问题的正文，看不出问题在哪，
     * 然后开始怀疑核验本身——那比说一句「这条没能定位」糟得多。
     */
    @Test
    fun issueWithUnknownBlockId_isRecordedAsUnlocatedNotGuessed() = runBlocking {
        val planId = seedThroughBible()
        router.verifyJson = issuesJson(
            issue("contradiction", "根本不存在的块", "说错了"),
        )
        // 定位不到块 → 章级问题，自动修复无从下手；正文照常发布，章节待复核
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val version = db.chapterVersionDao().getByChapter(planId).single()
        val issue = db.verificationIssueDao().getByVersion(version.id).single()
        assertNull(issue.blockId)
        assertFalse(issue.isBlockScoped)
        // 问题本身没被丢掉：核验确实指出了它
        assertEquals("说错了", issue.message)
    }

    /**
     * 章节行上的说明改成「几个问题 + 能不能只改那几段」的概览。
     *
     * 前几条原文仍然带着，而且必须带：一次失败的**重生成**留下的问题挂在
     * 作废的草稿版本上，问题面板只读当前正文的问题（它必须如此），
     * 那种情况下这句说明是唯一能告诉用户哪里不对的地方。
     */
    @Test
    fun chapterNote_summarizesAndStillNamesTheFirstIssues() = runBlocking {
        val planId = seedThroughBible()
        verifyWithIssues(planId) { blocks ->
            issuesJson(
                issue("contradiction", blocks[0], "继承顺序说错了"),
                issue("broken_derivation", null, "全章推演在中段断了一次"),
            )
        }
        // 定位到 1 个真实块 → 自动修复跑一轮；重新核验仍报同样的问题 → 草稿作废，
        // 章节标待复核。正文发布（可读），说明写在章节行的核验说明里
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val note = db.chapterPlanDao().getById(planId)?.verificationNote.orEmpty()
        // 概览：几条、涉及几段、能不能只改那几段
        assertTrue(note.contains("2 个问题"))
        assertTrue(note.contains("可只重写这几段"))
        assertTrue(note.contains("1 条没能定位"))
        // 原文也在，而且带着可读的类型名（不是 raw 的 broken_derivation）
        assertTrue(note.contains("继承顺序说错了"))
        assertTrue(note.contains("[推演跳步]"))
        assertFalse(note.contains("broken_derivation"))
    }

    /** 问题太多时说明里不铺满，但明确告诉用户「还有几条」，不假装只有这些 */
    @Test
    fun chapterNote_saysHowManyIssuesItDidNotList() = runBlocking {
        val planId = seedThroughBible()
        verifyWithIssues(planId) { blocks ->
            issuesJson(
                *Array(7) { index -> issue("contradiction", blocks[0], "问题$index") },
            )
        }
        // 七条都在同一个真实块上 → 自动修复跑一轮、修不动 → 发布 + 待复核
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val note = db.chapterPlanDao().getById(planId)?.verificationNote.orEmpty()
        assertTrue(note.contains("7 个问题"))
        assertTrue(note.contains("还有 4 条"))
        // 七条全在表里，一条不丢——这才是 4B 的重点（都挂在发布的版本上）
        val version = db.chapterVersionDao().getActive(planId)!!
        assertEquals(7, db.verificationIssueDao().getByVersion(version.id).size)
    }

    /**
     * 自动修复的**成功**路径：问题定位到真实块 → 发布后自动重写这几块 →
     * 重新核验干净 → 修复版原子发布，旧问题标已修，章节回到「已发布」。
     *
     * 这条是「核验发现问题 ≠ 整章失败」的正面样本：用户什么都不用做，
     * 第二次核验干净的那一版就是最终正文。
     */
    @Test
    fun autoRepair_rewritesAffectedBlocks_andPublishesWhenClean() = runBlocking {
        val planId = seedThroughBible()
        var verifyCall = 0
        router.onPhase = { phase ->
            if (phase == ScriptedRouter.Phase.VERIFY) {
                val versionId = db.chapterVersionDao().getByChapter(planId).last().id
                val blockIds = db.chapterBlockDao().getByVersion(versionId).map { it.id }
                verifyCall += 1
                router.verifyJson = if (verifyCall == 1) {
                    // 第一次核验：两个真实块各有一条问题 → 触发自动修复
                    issuesJson(
                        issue("contradiction", blockIds[0], "继承顺序说错了"),
                        issue("citation_error", blockIds[1], "这段引的片段不支持这句话"),
                    )
                } else {
                    // 修复后的重新核验：干净
                    """{"issues":[]}"""
                }
            }
        }
        assertTrue(pipeline.generateChapter("p-1", planId).isSuccess)

        val plan = db.chapterPlanDao().getById(planId)
        // 修复版发布，章节状态回到已发布——不需要用户介入
        assertEquals(ChapterStatus.PUBLISHED, plan?.status)
        val versions = db.chapterVersionDao().getByChapter(planId)
        assertEquals(2, versions.size)
        val published = versions.single { it.status == ChapterVersionStatus.PUBLISHED }
        assertEquals(published.id, plan?.activeVersionId)
        // 旧版本（带问题的那一版）归档而不是作废，它的问题全部标已修
        val superseded = versions.single { it.status == ChapterVersionStatus.ARCHIVED }
        val oldIssues = db.verificationIssueDao().getByVersion(superseded.id)
        assertEquals(2, oldIssues.size)
        assertTrue(oldIssues.all { it.resolution == IssueResolution.FIXED })
        // 修复版上没有问题记录
        assertTrue(db.verificationIssueDao().getByVersion(published.id).isEmpty())
    }

    // --- 局部重写 ---

    /**
     * 局部重写的主路径：只重写有问题的块，其余整块搬过去，通过核验后原子切换指针。
     */
    @Test
    fun rewrite_replacesOnlySelectedBlocksAndSwitchesPointer() = runBlocking {
        val planId = publishedChapterWithIssues()
        val oldVersionId = activeVersionId(planId)
        val oldBlocks = db.chapterBlockDao().getActiveBlocks(planId)
        val target = oldBlocks[1]
        val untouchedText = oldBlocks[0].text

        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(target.id)).isSuccess)

        // 指针搬到了新版本，旧版本归档（它成功过，不是失败）
        val newVersionId = activeVersionId(planId)
        assertNotEquals(oldVersionId, newVersionId)
        assertEquals(
            ChapterVersionStatus.ARCHIVED,
            db.chapterVersionDao().getById(oldVersionId)?.status,
        )
        val newVersion = db.chapterVersionDao().getById(newVersionId)
        assertEquals(oldVersionId, newVersion?.derivedFromVersionId)
        assertTrue(newVersion?.isPartialRewrite == true)

        // 新正文：块数不变，没选中的那一块一个字没改，选中的那一块换了内容
        val newBlocks = db.chapterBlockDao().getActiveBlocks(planId)
        assertEquals(oldBlocks.size, newBlocks.size)
        assertEquals(untouchedText, newBlocks[0].text)
        assertEquals("改写后的这一段。", newBlocks[1].text)
        // 顺序保持原样，改写不会把段落搬到别处
        assertEquals(listOf(0, 1), newBlocks.map { it.order })

        // 每一块都换了新 ID，包括没改动的：块是版本的一部分，不在版本之间共享
        assertTrue(newBlocks.none { it.id in oldBlocks.map { old -> old.id } })
        assertEquals(listOf(newBlocks[1].id), newVersion?.rewrittenBlockIds)

        // 旧版本的正文一块都没删，而且内容还是原来那个
        val archivedBlocks = db.chapterBlockDao().getByVersion(oldVersionId)
        assertEquals(oldBlocks.map { it.text }, archivedBlocks.map { it.text })
    }

    /**
     * 没改动的块，引用关系照搬过来，不重新解析。
     *
     * 重新解析等于让模型重新给一遍引用，而那一段正文一个字都没变——
     * 结果可能和读者当初看到的来源不一样。
     */
    @Test
    fun rewrite_carriesCitationsOfUntouchedBlocksVerbatim() = runBlocking {
        val planId = publishedChapterWithIssues()
        val oldVersionId = activeVersionId(planId)
        val oldBlocks = db.chapterBlockDao().getActiveBlocks(planId)
        // 第一块引了 c-1，另外再塞一条对不上的引用，看它会不会被「修好」
        db.citationDao().upsertAll(
            listOf(
                com.example.zhishu.domain.model.Citation(
                    id = com.example.zhishu.domain.model.Citation.idFor(oldBlocks[0].id, 1),
                    projectId = "p-1",
                    chapterId = planId,
                    versionId = oldVersionId,
                    blockId = oldBlocks[0].id,
                    chunkId = null,
                    rawRef = "c-模型编的",
                    status = CitationStatus.UNCONFIRMED,
                    order = 1,
                ),
            ),
        )

        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(oldBlocks[1].id)).isSuccess)

        val newBlocks = db.chapterBlockDao().getActiveBlocks(planId)
        val carried = db.citationDao().getByVersion(activeVersionId(planId))
            .filter { it.blockId == newBlocks[0].id }
            .sortedBy { it.order }
        assertEquals(2, carried.size)
        assertEquals("c-1", carried[0].chunkId)
        // 未确认的照搬，不会被这次重写悄悄改成已确认——没有任何新信息让它对上
        assertEquals(CitationStatus.UNCONFIRMED, carried[1].status)
        assertEquals("c-模型编的", carried[1].rawRef)
        // 引用挂在新块与新版本上，ID 按新块重算
        assertTrue(carried.all { it.versionId == activeVersionId(planId) })
        assertEquals(
            com.example.zhishu.domain.model.Citation.idFor(newBlocks[0].id, 0),
            carried[0].id,
        )

        // 旧版本的引用一条都没动
        val old = db.citationDao().getByVersion(oldVersionId)
        assertEquals(2, old.count { it.blockId == oldBlocks[0].id })
    }

    /** 重写过的块，引用重新解析：编出来的片段 ID 仍然记成未确认，不伪装成来源 */
    @Test
    fun rewrite_reresolvesCitationsOfRewrittenBlockAndKeepsUnconfirmed() = runBlocking {
        val planId = publishedChapterWithIssues()
        val target = db.chapterBlockDao().getActiveBlocks(planId)[1]
        router.rewriteJson =
            """{"text":"改写后的这一段。","knowledgeNodeTitles":["意思表示"],""" +
                """"sourceRefs":["c-1","c-这个片段不存在"],"changeNote":"改了"}"""

        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(target.id)).isSuccess)

        val newBlocks = db.chapterBlockDao().getActiveBlocks(planId)
        val fresh = db.citationDao().getByVersion(activeVersionId(planId))
            .filter { it.blockId == newBlocks[1].id }
            .sortedBy { it.order }
        assertEquals(2, fresh.size)
        assertEquals(CitationStatus.RESOLVED, fresh[0].status)
        assertEquals(CitationStatus.UNCONFIRMED, fresh[1].status)
        assertEquals("c-这个片段不存在", fresh[1].rawRef)
        // 块自己的 sourceRefs 只留真的解析到的，与引用表一致
        assertEquals(listOf("c-1"), newBlocks[1].sourceRefs)
    }

    /** 相邻块的原文一起喂给模型，改出来的段落才接得回原位 */
    @Test
    fun rewrite_feedsNeighbouringBlocksToTheModel() = runBlocking {
        val planId = publishedChapterWithIssues()
        val blocks = db.chapterBlockDao().getActiveBlocks(planId)

        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(blocks[1].id)).isSuccess)

        val prompt = router.lastPrompts.getValue(ScriptedRouter.Phase.REWRITE)
        assertTrue(prompt.contains("上一段=<${blocks[0].text}>"))
        assertTrue(prompt.contains("本段=<${blocks[1].text}>"))
        // 最后一段没有下一段，明说而不是留空白
        assertTrue(prompt.contains("下一段=<（这是本章最后一段）>"))
        // 问题清单也进了提示词，模型知道要解决什么
        assertTrue(prompt.contains("继承顺序说错了"))
    }

    /** 发布前做的是**全章**核验，不是只核改动块 */
    @Test
    fun rewrite_verifiesWholeChapterBeforePublishing() = runBlocking {
        val planId = publishedChapterWithIssues()
        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        router.calls.clear()

        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(blocks[1].id)).isSuccess)

        // 只重写了一块，但核验看到的是两块正文
        assertEquals(1, router.calls.count { it == ScriptedRouter.Phase.REWRITE })
        assertEquals(1, router.calls.count { it == ScriptedRouter.Phase.VERIFY })
        val verifyPrompt = router.lastPrompts.getValue(ScriptedRouter.Phase.VERIFY)
        assertTrue(verifyPrompt.contains("改写后的这一段。"))
        assertTrue(verifyPrompt.contains(blocks[0].text))
    }

    /** 改过的块上那几条问题，重写并通过核验后标为已修；用户没法手动标 */
    @Test
    fun rewrite_marksIssuesOfRewrittenBlocksAsFixed() = runBlocking {
        val planId = publishedChapterWithIssues()
        val oldVersionId = activeVersionId(planId)
        val blocks = db.chapterBlockDao().getActiveBlocks(planId)

        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(blocks[1].id)).isSuccess)

        val old = db.verificationIssueDao().getByVersion(oldVersionId)
        assertEquals(IssueResolution.FIXED, old.single { it.blockId == blocks[1].id }.resolution)
        // 别的块上的问题不受影响——它们没被重写
        assertTrue(old.filter { it.blockId != blocks[1].id }.all { it.resolution.isOpen })
        // 新版本通过了核验，所以它自己没有问题记录
        assertTrue(db.verificationIssueDao().getByVersion(activeVersionId(planId)).isEmpty())
        // 大纲页看到的是当前正文的问题：干净的
        assertEquals(0, db.verificationIssueDao().countActiveOpen(planId, IssueResolution.OPEN))
    }

    /** 用户判断某条不需要改：只动 resolution，问题内容一个字不变 */
    @Test
    fun acceptedIssue_staysRecordedWithOriginalMessage() = runBlocking {
        val planId = publishedChapterWithIssues()
        val issue = db.verificationIssueDao().getActiveByChapter(planId).first()

        db.verificationIssueDao().updateResolution(issue.id, IssueResolution.ACCEPTED)

        val after = db.verificationIssueDao().getActiveByChapter(planId).first { it.id == issue.id }
        assertEquals(IssueResolution.ACCEPTED, after.resolution)
        assertEquals(issue.message, after.message)
        assertEquals(issue.blockId, after.blockId)
        // 已接受的不再算待处理
        assertEquals(
            db.verificationIssueDao().getActiveByChapter(planId).count { it.resolution.isOpen },
            db.verificationIssueDao().countActiveOpen(planId, IssueResolution.OPEN),
        )
    }

    // --- 失败、取消、仍有问题：旧正文一个字不变 ---

    /** 重写的模型调用失败：草稿作废，旧正文继续可读 */
    @Test
    fun rewriteFailure_abandonsDraftAndKeepsOldBodyReadable() = runBlocking {
        val planId = publishedChapterWithIssues()
        val oldVersionId = activeVersionId(planId)
        val oldText = activeText(planId)
        val target = db.chapterBlockDao().getActiveBlocks(planId)[1]
        router.failAt = ScriptedRouter.Phase.REWRITE
        router.failWith = ModelError.Server("500")

        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(target.id)).isFailure)

        assertEquals(oldVersionId, activeVersionId(planId))
        assertEquals(oldText, activeText(planId))
        assertTrue(db.chapterPlanDao().getById(planId)?.isReadable == true)
        val draft = db.chapterVersionDao().getByChapter(planId).single { it.id != oldVersionId }
        assertEquals(ChapterVersionStatus.ABANDONED, draft.status)
        assertTrue(draft.verificationNote.contains("正文仍是此前那一版"))
    }

    /** 重写后仍有问题：不发布，旧正文继续可读，新问题记在草稿版本上 */
    @Test
    fun rewriteStillFailingVerification_doesNotPublishAndKeepsOldBody() = runBlocking {
        val planId = publishedChapterWithIssues()
        val oldVersionId = activeVersionId(planId)
        val oldText = activeText(planId)
        val target = db.chapterBlockDao().getActiveBlocks(planId)[1]
        // 重写之后的那次核验仍然报问题
        router.verifyJson = issuesJson(issue("contradiction", null, "改完还是不对"))

        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(target.id)).isFailure)

        assertEquals(oldVersionId, activeVersionId(planId))
        assertEquals(oldText, activeText(planId))
        val draft = db.chapterVersionDao().getByChapter(planId).single { it.id != oldVersionId }
        assertEquals(ChapterVersionStatus.ABANDONED, draft.status)
        // 新问题挂在草稿上，旧版本的问题一条不动
        assertEquals(1, db.verificationIssueDao().getByVersion(draft.id).size)
        assertTrue(db.verificationIssueDao().getByVersion(oldVersionId).all { it.resolution.isOpen })
        // 大纲页看到的还是当前正文的那些问题，不掺进这次没通过的尝试
        val active = db.verificationIssueDao().getActiveByChapter(planId)
        assertTrue(active.all { it.versionId == oldVersionId })
        assertFalse(active.any { it.message == "改完还是不对" })
    }

    /** 中途取消：草稿作废，旧正文一个字不变 */
    @Test
    fun cancelledRewrite_abandonsDraftAndKeepsOldBody() = runBlocking {
        val planId = publishedChapterWithIssues()
        val oldVersionId = activeVersionId(planId)
        val oldText = activeText(planId)
        val target = db.chapterBlockDao().getActiveBlocks(planId)[1]
        router.cancelAt = ScriptedRouter.Phase.VERIFY

        val error = runCatching {
            pipeline.rewriteBlocks("p-1", planId, listOf(target.id))
        }.exceptionOrNull()
        assertTrue(error is CancellationException)

        assertEquals(oldVersionId, activeVersionId(planId))
        assertEquals(oldText, activeText(planId))
        assertTrue(db.chapterPlanDao().getById(planId)?.isReadable == true)
        val draft = db.chapterVersionDao().getByChapter(planId).single { it.id != oldVersionId }
        assertEquals(ChapterVersionStatus.ABANDONED, draft.status)
    }

    /** 没有可读正文时不给局部重写：没有 active version 可派生 */
    @Test
    fun rewriteWithoutPublishedBody_isRejected() = runBlocking {
        val planId = seedThroughBible()
        val error = pipeline.rewriteBlocks("p-1", planId, listOf("随便一个块"))
            .exceptionOrNull()
        assertTrue(error?.message?.contains("还没有通过核验的正文") == true)
        // 一个版本都不该被建出来
        assertTrue(db.chapterVersionDao().getByChapter(planId).isEmpty())
    }

    /** 选中的块不属于当前正文（正文已经更新过）：拒绝，而不是重写一段看不见的文字 */
    @Test
    fun rewritingStaleBlockId_isRejected() = runBlocking {
        val planId = publishedChapterWithIssues()
        val before = db.chapterVersionDao().getByChapter(planId).size
        val error = pipeline.rewriteBlocks("p-1", planId, listOf("上一版的块"))
            .exceptionOrNull()
        assertTrue(error?.message?.contains("不属于当前正文") == true)
        assertEquals(before, db.chapterVersionDao().getByChapter(planId).size)
    }

    // --- 版本切换的既有规则同样适用 ---

    /** 发布新版本后，旧版本的自测题不再显示，阅读锚点回到章首（3A/3D 的规则） */
    @Test
    fun rewritePublish_isolatesOldQuizzesAndResetsProgress() = runBlocking {
        val planId = publishedChapterWithIssues()
        val oldVersionId = activeVersionId(planId)
        val oldQuizIds = db.quizDao().getActiveByChapter(planId).map { it.id }
        assertTrue(oldQuizIds.isNotEmpty())
        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        db.readingProgressDao().upsert(
            ReadingProgress(
                id = "rp-1",
                projectId = "p-1",
                chapterId = planId,
                blockId = blocks[1].id,
                blockOffset = 30,
                updatedAt = 1L,
            ),
        )

        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(blocks[1].id)).isSuccess)

        // 新版本的题是新出的，旧题留在库里但不显示
        val nowQuizIds = db.quizDao().getActiveByChapter(planId).map { it.id }
        assertTrue(nowQuizIds.isNotEmpty())
        assertTrue(nowQuizIds.none { it in oldQuizIds })
        assertTrue(db.quizDao().getByChapter(planId).any { it.versionId == oldVersionId })

        // 锚点回到章首并留下「本章已更新」的痕迹：旧 blockId 在新正文里不存在
        val progress = db.readingProgressDao().getForProject("p-1")
        assertNull(progress?.blockId)
        assertEquals(0, progress?.blockOffset)
        assertFalse(progress?.progressNoticeShown == true)
    }

    // --- 提示词与枚举必须说同一套词 ---

    /**
     * 提示词里定义的每一个 `type` 值，[IssueType.parse] 都要认得（4B）。
     *
     * 这条测试是有来历的：4B 第一版的枚举是凭印象写的（`FACT_ERROR` / `LOGIC_GAP`），
     * 而 verify.txt 让模型输出的是 `unsupported` / `contradiction` /
     * `broken_derivation` / `missing` / `overreach`。两边一个都对不上，
     * 于是**每一条**核验问题都会落进「其他问题」——分类当场失去意义，
     * 而且不会有任何报错，只是界面上所有问题都长得一样。
     *
     * 词表写在提示词文件里，改提示词的人不一定会想到改枚举，所以让测试直接
     * 从文件里把词捞出来对。
     */
    @Test
    fun everyIssueTypeInPrompts_isRecognizedByTheEnum() {
        val declared = listOf("verify.txt", "verify_model.txt").flatMap { name ->
            val text = promptFile(name).readText()
            // 提示词里的词表写成 `- \`type_name\`：说明`
            Regex("^- `([a-z_]+)`", RegexOption.MULTILINE)
                .findAll(text)
                .map { name to it.groupValues[1] }
                .toList()
        }
        // 先确认真的捞到了词，否则这条测试会在提示词改格式后静默通过
        assertTrue("没有从提示词里解析出任何 type，正则该更新了", declared.size >= 8)

        val unrecognized = declared.filter { (_, type) -> IssueType.parse(type) == IssueType.OTHER }
        assertEquals(emptyList<Pair<String, String>>(), unrecognized)
    }

    /** 反过来：枚举里除 OTHER 之外的每个值，都真的有提示词会产出它 */
    @Test
    fun everyIssueType_isProducibleByAPrompt() {
        val declared = listOf("verify.txt", "verify_model.txt").flatMap { name ->
            Regex("^- `([a-z_]+)`", RegexOption.MULTILINE)
                .findAll(promptFile(name).readText())
                .map { it.groupValues[1] }
                .toList()
        }
        val produced = declared.map { IssueType.parse(it) }.toSet()
        val orphans = IssueType.entries.filter { it != IssueType.OTHER && it !in produced }
        // 有值但没人产出它，说明它是凭想象加的——要么补进提示词，要么删掉
        assertEquals(emptyList<IssueType>(), orphans)
    }

    // --- 辅助 ---

    private fun promptFile(name: String): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir").orEmpty())
        return listOfNotNull(cwd, cwd.resolve("app"), cwd.parentFile?.resolve("app"))
            .map { it.resolve("src/main/assets/prompts/$name") }
            .firstOrNull { it.isFile }
            ?: error("找不到提示词 $name，工作目录是 $cwd")
    }

    private fun issue(type: String, blockId: String?, message: String): String {
        val block = blockId?.let { "\"$it\"" } ?: "null"
        return """{"type":"$type","blockId":$block,"message":"$message",""" +
            """"sourceRefs":["c-1"],"suggestedFix":"改成配偶、子女、父母"}"""
    }

    private fun issuesJson(vararg issues: String): String =
        """{"issues":[${issues.joinToString(",")}]}"""

    /**
     * 在核验发生的那一刻，按**这一版真实的** blockId 拼出核验结果。
     *
     * 不预测 ID：问题必须引用真实存在的块，而这正是 IssueRecorder 要校验的事，
     * 测试里预测一串 `id-3` / `id-4` 等于绕过被测逻辑，
     * 而且块数一变测试就以一个看不懂的方式失败。
     */
    private fun verifyWithIssues(planId: String, build: (List<String>) -> String) {
        router.onPhase = { phase ->
            if (phase == ScriptedRouter.Phase.VERIFY) {
                val versionId = db.chapterVersionDao().getByChapter(planId).last().id
                val blockIds = db.chapterBlockDao().getByVersion(versionId).map { it.id }
                router.verifyJson = build(blockIds)
            }
        }
    }

    /** 建到「第一章已发布，而且当前版本上挂着两条核验问题」 */
    private suspend fun publishedChapterWithIssues(): String {
        val planId = seedThroughBible()
        pipeline.generateChapter("p-1", planId).getOrThrow()
        val versionId = activeVersionId(planId)
        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        db.verificationIssueDao().upsertAll(
            listOf(
                com.example.zhishu.domain.model.VerificationIssue(
                    id = com.example.zhishu.domain.model.VerificationIssue.idFor(versionId, 0),
                    projectId = "p-1",
                    chapterId = planId,
                    versionId = versionId,
                    blockId = blocks[1].id,
                    type = IssueType.CONTRADICTION,
                    rawType = "contradiction",
                    message = "继承顺序说错了",
                    suggestedFix = "改成配偶、子女、父母",
                    order = 0,
                    createdAt = 1L,
                ),
                com.example.zhishu.domain.model.VerificationIssue(
                    id = com.example.zhishu.domain.model.VerificationIssue.idFor(versionId, 1),
                    projectId = "p-1",
                    chapterId = planId,
                    versionId = versionId,
                    blockId = blocks[0].id,
                    type = IssueType.BROKEN_DERIVATION,
                    message = "开头跳了一步",
                    order = 1,
                    createdAt = 1L,
                ),
            ),
        )
        router.calls.clear()
        return planId
    }

    private suspend fun seedThroughBible(): String {
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
            ),
        )
        db.sourceDocumentDao().upsert(
            SourceDocument(
                id = "src-1",
                projectId = "p-1",
                title = "笔记",
                kind = SourceKind.TEXT,
                fetchedAt = 1L,
                contentHash = "h",
                parseStatus = ParseStatus.PARSED,
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
                ),
            ),
        )
        pipeline.generateKnowledgeMap("p-1").getOrThrow()
        pipeline.generateStoryBible("p-1").getOrThrow()
        router.calls.clear()
        return db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }.first().id
    }

    private suspend fun activeVersionId(planId: String): String =
        db.chapterPlanDao().getById(planId)?.activeVersionId.orEmpty()

    private suspend fun activeText(planId: String): String =
        db.chapterBlockDao().getActiveBlocks(planId).joinToString("\n") { it.text }
}
