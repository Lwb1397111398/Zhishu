package com.example.zhishu

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.IssueResolution
import com.example.zhishu.domain.model.IssueType
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.domain.model.SourceMode
import com.example.zhishu.domain.model.VerificationIssue
import com.example.zhishu.ui.viewmodel.BlockSourceSummary
import com.example.zhishu.ui.viewmodel.ChapterTrustSummary
import com.example.zhishu.ui.viewmodel.SourceTrustState
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

/**
 * 阅读可信度呈现（第四阶段 4C）。
 *
 * 4A 把引用存了下来，4B 把核验问题变成可处理的条目。剩下的问题是读者的：
 * **我眼前这一章、这一段，有多可信。** 4C 之前能给的回答只有一个含糊的
 * 「未确认」，它同时盖住了四种处境——模型编的出处、资料后来被删、
 * 只能定位到片段、有法域限制——而这四种对应的下一步动作完全不同。
 *
 * 这一组测试盯三件事：
 * 1. 每种处境说的是它自己那句话，不混成一句；
 * 2. 所有数字都只来自**当前** active version，历史版本一条都不掺；
 * 3. 拿不准的地方如实说拿不准，不编页码、不给点了没用的按钮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReadingTrustTest {

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
                conflictDao = db.knowledgeConflictDao(),
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

    // --- 章节级摘要：只数当前这一版 ---

    /**
     * 重生成之后，章节摘要里的数字只算新版本。
     *
     * 旧版本的引用一条都不删（历史要能查），所以「本章有几处来源」如果按全部
     * 版本数，读者每让一章重生成一次，这个数字就翻一倍——而他眼前的正文没变长。
     * 一个自己也解释不了的数字比不显示更糟。
     */
    @Test
    fun chapterTrust_countsOnlyCurrentVersionAfterRegeneration() = runBlocking {
        val planId = seedThroughBible()
        pipeline.generateChapter("p-1", planId).getOrThrow()
        router.chapterJson = router.chapterJson.replace("老人立下遗嘱，子女各怀心思。", "第二版的开头。")
        pipeline.generateChapter("p-1", planId).getOrThrow()

        // 库里确实攒下了两版的引用，摘要只认当前那一版
        assertEquals(4, db.citationDao().getAllVersionsByProject("p-1").size)
        val trust = activeChapterTrust(planId)
        assertEquals(1, trust.sourceCount)
        assertEquals(2, trust.locatableCount)
        assertEquals(0, trust.needsAttentionCount)
        assertFalse(trust.usedModelKnowledge)
    }

    /**
     * 「模型给的出处没对上」和「资料后来被删了」在摘要里分开数。
     *
     * 这两件事读者要做的事完全不同：前者得自己去查一遍权威出处，
     * 后者重新导入同一份资料就恢复了。合成一个「未确认 3 处」等于
     * 两边都说不清，而且会让本来有依据的那两处显得和编的一样可疑。
     */
    @Test
    fun chapterTrust_separatesUnmatchedRefFromDeletedSource() = runBlocking {
        val planId = seedThroughBible(secondBlockRefs = """"c-1","编的出处"""")
        pipeline.generateChapter("p-1", planId).getOrThrow()
        deleteSource("src-1")

        val trust = activeChapterTrust(planId)
        assertEquals(1, trust.unconfirmedCount)
        assertEquals(2, trust.deletedSourceCount)
        assertEquals(3, trust.needsAttentionCount)
        // 资料没了，「可定位」与「用户资料份数」跟着归零：点开只会是一片空白
        assertEquals(0, trust.locatableCount)
        assertEquals(0, trust.sourceCount)
        assertTrue(trust.scopes.isEmpty())
    }

    /**
     * 同一份资料被引十次也只算一份，法域/生效时间只列真的有限制的那些。
     *
     * 「本章基于你的 5 份资料」如果其实是同一份被引了五次，这句话就是假的。
     * 反过来，把没有时效限制的资料也列进法域清单，会让每一章都挂满
     * 一串没有信息量的提示，真正有法域限制的那一条随之被淹掉。
     */
    @Test
    fun chapterTrust_dedupesSourcesAndOnlyListsTimeSensitiveScopes() = runBlocking {
        val planId = seedThroughBible(secondBlockRefs = """"c-1","c-2"""")
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val trust = activeChapterTrust(planId)
        // c-1 被两块引用，但它来自同一份资料
        assertEquals(2, trust.sourceCount)
        assertEquals(3, trust.locatableCount)
        // 课堂笔记没填法域也没填生效时间，不进这份清单
        assertEquals(listOf("民法典释义"), trust.scopes.map { it.title })
        val scope = trust.scopes.single()
        assertTrue(scope.summary.contains("中国大陆"))
        assertTrue(scope.summary.contains("2021-01-01 起施行"))
        assertTrue(scope.summary.contains("2020 年修正"))
    }

    /**
     * 摘要那一行先说知识从哪来，再说有没有要核对的。
     *
     * 「本章主要基于你的资料」和「本章包含 AI 自备知识」是读者据以决定
     * 「这一章的内容能不能直接拿去用」的两句话，必须排在数字前面。
     */
    @Test
    fun chapterTrust_headlineNamesWhereTheKnowledgeCameFrom() {
        val fromDocuments = ChapterTrustSummary(sourceCount = 2, locatableCount = 4)
        assertEquals("本章主要基于你的 2 份资料 · 4 处可定位引用", fromDocuments.headline)

        val fromModel = ChapterTrustSummary(usedModelKnowledge = true, unverifiedClaimCount = 3)
        assertEquals("本章包含 AI 自备知识", fromModel.headline)

        val mixed = ChapterTrustSummary(
            sourceCount = 1,
            usedModelKnowledge = true,
            unconfirmedCount = 2,
        )
        assertEquals("本章基于你的 1 份资料，也包含 AI 自备知识 · 2 项需核对", mixed.headline)

        // 一份资料都没引到、也不是无资料模式：如实说没标来源，不含糊过去
        assertEquals("本章没有标注来源", ChapterTrustSummary().headline)
    }

    /**
     * v10 之前导入的片段只能定位到「第几段」，摘要里如实标出来。
     *
     * 当年的分块逻辑没记位置，无从倒推。这时候硬给一个页码是最坏的选择：
     * 用户翻到那一页发现讲的是别的内容，从此不再相信任何一个页码。
     */
    @Test
    fun chapterTrust_flagsLegacyChunksThatOnlyLocateToParagraph() = runBlocking {
        val planId = seedThroughBible()
        pipeline.generateChapter("p-1", planId).getOrThrow()
        // 只引了带页码与章节的片段
        assertFalse(activeChapterTrust(planId).hasImpreciseLocator)

        // 重生成一次，这一版引到了那份早期导入的资料
        router.chapterJson = router.chapterJson.replace(
            """"sourceRefs":["c-1"]}]}""",
            """"sourceRefs":["c-1","c-2"]}]}""",
        )
        pipeline.generateChapter("p-1", planId).getOrThrow()
        assertTrue(activeChapterTrust(planId).hasImpreciseLocator)
    }

    /**
     * 只数还没处理的问题，并说清其中几条能定位到具体段落。
     *
     * 「能定位几条」决定用户能不能只重写那几段（4B）。把定位不到的也算进
     * 「可以只改这几段」里，用户点下去会发现按钮没反应；反过来把它们藏掉，
     * 等于告诉他这一章没这些问题。
     */
    @Test
    fun chapterTrust_countsOnlyOpenIssuesAndSaysHowManyAreLocatable() = runBlocking {
        val planId = seedThroughBible()
        pipeline.generateChapter("p-1", planId).getOrThrow()
        val versionId = activeVersionId(planId)
        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        db.verificationIssueDao().upsertAll(
            listOf(
                issue(versionId, planId, 0, blocks[0].id, "开头跳了一步"),
                issue(versionId, planId, 1, blocks[1].id, "这段引的片段不支持这句话"),
                issue(versionId, planId, 2, null, "全章推演在中段断了一次"),
                // 用户看过了、判断不用改：已经有结论，不再算待处理
                issue(versionId, planId, 3, blocks[1].id, "模型误报", IssueResolution.ACCEPTED),
            ),
        )

        val trust = activeChapterTrust(planId)
        assertEquals(3, trust.openIssueCount)
        assertEquals(2, trust.locatedIssueCount)
        assertFalse(trust.isEmpty)
    }

    /**
     * 无资料模式写出来的章节，来源入口照样要出现。
     *
     * 这种章节的引用数、问题数全是 0。摘要如果因此判成「空」，阅读器就不显示
     * 来源入口——而「这一章的知识来自 AI 自己，没有你的资料作依据」恰恰是
     * 这种章节最该让读者看到的一句话，它比任何一个计数都重要。
     */
    @Test
    fun chapterTrust_isNotEmptyForModelKnowledgeChapters() = runBlocking {
        val planId = seedModelKnowledgeProject()
        pipeline.generateChapter("p-2", planId).getOrThrow()

        val trust = activeChapterTrust(planId, projectId = "p-2")
        assertTrue(trust.usedModelKnowledge)
        assertEquals(0, trust.sourceCount)
        assertEquals(0, trust.needsAttentionCount)
        assertFalse("无资料模式的章节必须还能打开来源摘要", trust.isEmpty)

        // 反过来：什么都没有、也不是无资料模式，才算真的没什么可说
        assertTrue(ChapterTrustSummary().isEmpty)
    }

    // --- 块级面板：五种处境说五句话 ---

    /**
     * 资料被删之后，那一块说得出丢的是**哪一份**资料。
     *
     * 只说「引用的资料已被删除」等于什么都没说：用户有十几份资料，
     * 不知道是哪一份就没法判断这一段还站不站得住，也不知道该重新导入哪份。
     * 资料行删掉后标题已经查不到，所以必须在删除的当时抄下来。
     */
    @Test
    fun blockSource_deletedSourceNamesTheDocumentItLost() = runBlocking {
        val planId = seedThroughBible(secondBlockRefs = """"c-1","编的出处"""")
        pipeline.generateChapter("p-1", planId).getOrThrow()
        deleteSource("src-1")

        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        val summary = activeBlockSources(planId).getValue(blocks.last().id)
        val deleted = summary.sources.single { it.trust == SourceTrustState.SOURCE_DELETED }
        assertEquals("民法典释义", deleted.title)
        // 原文找不回来了，不装作还能给一段摘录
        assertEquals("", deleted.excerpt)
        assertTrue(deleted.needsManualCheck)
        assertTrue(deleted.trust.explanation.contains("不是模型编的"))

        // 同一块上那条编出来的出处仍然是「未能匹配」，两者不混为一谈
        val unmatched = summary.sources.single { it.trust == SourceTrustState.UNCONFIRMED }
        assertEquals("未能匹配到资料", unmatched.title)
        assertEquals("编的出处", unmatched.citation.rawRef)
    }

    /** 旧片段只报「第几段」，并且明说这是定位能力不够，不是内容有问题 */
    @Test
    fun blockSource_saysWhenItCanOnlyLocateToAParagraph() = runBlocking {
        val planId = seedThroughBible(secondBlockRefs = """"c-1","c-2"""")
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        val sources = activeBlockSources(planId).getValue(blocks.last().id).sources
        val paged = sources.single { it.chunk?.id == "c-1" }
        assertEquals("第 12 页 · 第三章 合同的订立 > 第二节 要约", paged.locator)
        assertFalse(paged.locatorIsImprecise)

        val legacy = sources.single { it.chunk?.id == "c-2" }
        assertTrue(legacy.locatorIsImprecise)
        // 至少能说到第几段，而不是一句「不知道在哪」
        assertEquals("第 1 段", legacy.locator)
        // 它仍然是已确认来源：定位粗不等于依据不成立
        assertEquals(SourceTrustState.CONFIRMED, legacy.trust)
    }

    /** 有法域或生效时间限制的资料要标出来：正文那句话未必在读者的处境下成立 */
    @Test
    fun blockSource_reportsScopeLimitedSources() = runBlocking {
        val planId = seedThroughBible(secondBlockRefs = """"c-1","c-2"""")
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        val sources = activeBlockSources(planId).getValue(blocks.last().id).sources
        val legal = sources.single { it.chunk?.id == "c-1" }
        assertTrue(legal.scopeLimited)
        assertTrue(legal.versionInfo.contains("中国大陆"))
        assertTrue(legal.versionInfo.contains("2021-01-01 起施行"))
        // 课堂笔记没有适用边界，不给它挂一个空提示
        assertFalse(sources.single { it.chunk?.id == "c-2" }.scopeLimited)
    }

    /**
     * 状态说 RESOLVED 但片段查不到时按未确认显示，不显示成「已确认来源」。
     *
     * 这是数据不一致的情形（引用指向的片段没了但状态没跟着改）。显示成已确认
     * 会给出一个点开就空白的入口，比说一句「没能对上」糟得多。
     */
    @Test
    fun blockSource_treatsResolvedButMissingChunkAsUnconfirmed() = runBlocking {
        val planId = seedThroughBible()
        pipeline.generateChapter("p-1", planId).getOrThrow()
        // 只删片段，故意不动引用状态
        db.sourceChunkDao().deleteBySource("src-1")

        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        val source = activeBlockSources(planId).getValue(blocks.first().id).sources.single()
        assertEquals(CitationStatus.RESOLVED, source.citation.status)
        assertEquals(SourceTrustState.UNCONFIRMED, source.trust)
        assertTrue(source.needsManualCheck)
    }

    /**
     * 核验问题挂到它自己那一块上；定位不到段落的问题不挂给任何一块。
     *
     * 猜一个最像的块，用户点开的会是一段没问题的正文，看不出问题在哪，
     * 然后开始怀疑核验本身——4B 已经为此定过调子，块级面板同样守它。
     */
    @Test
    fun blockSummary_bindsOpenIssuesToTheirBlockAndKeepsChapterWideOnesOut() = runBlocking {
        val planId = seedThroughBible()
        pipeline.generateChapter("p-1", planId).getOrThrow()
        val versionId = activeVersionId(planId)
        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        db.verificationIssueDao().upsertAll(
            listOf(
                issue(versionId, planId, 0, blocks[1].id, "这段引的片段不支持这句话"),
                issue(versionId, planId, 1, null, "全章推演在中段断了一次"),
            ),
        )

        val summaries = activeBlockSources(planId)
        assertEquals(
            listOf("这段引的片段不支持这句话"),
            summaries.getValue(blocks[1].id).openIssues.map { it.message },
        )
        // 全章性的那条没有落到任何一块上
        assertTrue(summaries.getValue(blocks[0].id).issues.isEmpty())
        assertTrue(summaries.values.none { s -> s.issues.any { it.blockId == null } })
    }

    /**
     * 安静模式下只有「值得读者知道」的块才露出来源入口。
     *
     * 「这一段有 2 处正常来源」不该在读故事的过程中反复打断视线；
     * 「出处没对上」和「这一段有待处理的问题」必须打断。
     */
    @Test
    fun blockSummary_quietModeSpeaksUpOnlyForUnconfirmedOrOpenIssues() = runBlocking {
        val planId = seedThroughBible(secondBlockRefs = """"c-1","编的出处"""")
        pipeline.generateChapter("p-1", planId).getOrThrow()
        val versionId = activeVersionId(planId)
        val blocks = db.chapterBlockDao().getActiveBlocks(planId)

        // 干净的那一块：有来源，但不打断
        val clean = activeBlockSources(planId).getValue(blocks[0].id)
        assertTrue(clean.hasAnything)
        assertFalse(clean.deservesAttention)
        assertEquals("来源 1 处", clean.label)

        // 出处没对上的那一块：要说
        val dirty = activeBlockSources(planId).getValue(blocks[1].id)
        assertTrue(dirty.deservesAttention)
        assertEquals("来源 1 处 · 1 处待核对", dirty.label)

        // 只有一条已处理完的问题：已经有结论了，不再提醒
        db.verificationIssueDao().upsertAll(
            listOf(issue(versionId, planId, 0, blocks[0].id, "已经改好了", IssueResolution.FIXED)),
        )
        val settled = activeBlockSources(planId).getValue(blocks[0].id)
        assertEquals(1, settled.issues.size)
        assertTrue(settled.openIssues.isEmpty())
        assertFalse(settled.deservesAttention)
    }

    /**
     * 一块里的引用全都没对上时，说清「这一段的知识来自模型自己」。
     *
     * 这和「一条引用都没有」不是一回事：一条都没有可能是故事情节，
     * 而模型给了出处、我们一条都没确认，说明它在讲知识却给不出依据。
     * 一条引用都没有的块由那行小字负责说明，这里不重复。
     */
    @Test
    fun blockSummary_saysWhenEveryRefFailedToMatch() = runBlocking {
        val planId = seedThroughBible(secondBlockRefs = """"编的出处"""")
        pipeline.generateChapter("p-1", planId).getOrThrow()

        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        val summaries = activeBlockSources(planId)
        val allUnmatched = summaries.getValue(blocks[1].id)
        assertTrue(allUnmatched.allSourcesUnresolved)
        assertEquals("1 处引用未能匹配，需你核对", allUnmatched.label)
        // 有一条对上了的块不算
        assertFalse(summaries.getValue(blocks[0].id).allSourcesUnresolved)

        // 一条引用记录都没有的块：交给 label 说话，不重复一遍
        val nothing = BlockSourceSummary(blockId = "b", sources = emptyList(), modelKnowledge = true)
        assertFalse(nothing.allSourcesUnresolved)
        assertEquals("AI 自备知识", nothing.label)
    }

    // --- 局部重写：草稿上的东西一点都不能漏进当前阅读 ---

    /**
     * 局部重写没通过核验时，章节摘要仍然是发布版本那一份。
     *
     * 4B 保证了正文不变；4C 要保证**读者看到的那些数字**也不变。
     * 草稿版本的引用与问题都已经落库了，摘要如果按「最新一版」算，
     * 用户会看到一章刚被自己改坏了的统计——而正文明明还是原来那一版。
     */
    @Test
    fun failedRewrite_keepsChapterTrustOnThePublishedVersion() = runBlocking {
        val planId = seedThroughBible()
        pipeline.generateChapter("p-1", planId).getOrThrow()
        val publishedVersion = activeVersionId(planId)
        val blocks = db.chapterBlockDao().getActiveBlocks(planId)
        db.verificationIssueDao().upsertAll(
            listOf(issue(publishedVersion, planId, 0, blocks[1].id, "这段引的片段不支持这句话")),
        )
        val before = activeChapterTrust(planId)

        // 改完再核一次仍然报问题 → 草稿作废
        router.verifyJson = issuesJson(verifyIssue(null, "改完还是不对"))
        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(blocks[1].id)).isFailure)

        assertEquals(publishedVersion, activeVersionId(planId))
        val after = activeChapterTrust(planId)
        assertEquals(before.locatableCount, after.locatableCount)
        assertEquals(before.openIssueCount, after.openIssueCount)
        assertEquals(1, after.openIssueCount)
        // 草稿上那条问题没有混进当前正文
        val active = db.verificationIssueDao().getActiveByChapter(planId)
        assertTrue(active.all { it.versionId == publishedVersion })
        assertFalse(active.any { it.message == "改完还是不对" })
    }

    /**
     * 局部重写通过核验后，摘要整份换到新版本，数字不叠加。
     *
     * 旧版本的引用与问题一条都不删（历史要能查）。摘要如果把两版加起来，
     * 一次只改了一段的重写会让「本章有几处来源」翻倍，
     * 而且那条已经改好的问题会一直挂在「待核对」里。
     */
    @Test
    fun successfulRewrite_movesTrustToTheNewVersionWithoutDoubleCounting() = runBlocking {
        val planId = seedThroughBible()
        pipeline.generateChapter("p-1", planId).getOrThrow()
        val oldVersion = activeVersionId(planId)
        val oldBlocks = db.chapterBlockDao().getActiveBlocks(planId)
        db.verificationIssueDao().upsertAll(
            listOf(issue(oldVersion, planId, 0, oldBlocks[1].id, "这段引的片段不支持这句话")),
        )
        assertEquals(1, activeChapterTrust(planId).openIssueCount)

        assertTrue(pipeline.rewriteBlocks("p-1", planId, listOf(oldBlocks[1].id)).isSuccess)

        val newVersion = activeVersionId(planId)
        val trust = activeChapterTrust(planId)
        // 两块正文、两处引用——和重写前一样，没有把旧版本再算一遍
        assertEquals(1, trust.sourceCount)
        assertEquals(2, trust.locatableCount)
        // 那条问题在旧版本上被标成已修，当前正文这边是干净的
        assertEquals(0, trust.openIssueCount)
        assertEquals(
            IssueResolution.FIXED,
            db.verificationIssueDao().getByVersion(oldVersion).single().resolution,
        )
        // 当前引用全部挂在新版本上，旧版本的一条都没删
        val active = db.citationDao().getActiveByChapter(planId)
        assertTrue(active.all { it.versionId == newVersion })
        assertEquals(2, db.citationDao().getByVersion(oldVersion).size)
    }

    // --- 辅助 ---

    /**
     * 按当前 active version 拼章节摘要——走的是 ReaderViewModel 那一批 Active 查询。
     *
     * 测试里刻意重复一遍装载路径而不是调 ViewModel：4C 的全部风险都在
     * 「读的是哪一版」这一步上，用哪套查询拼出来的数字必须能被直接看见。
     */
    private suspend fun activeChapterTrust(
        planId: String,
        projectId: String = "p-1",
    ): ChapterTrustSummary {
        val citations = db.citationDao().getActiveByChapter(planId)
        return ChapterTrustSummary.build(
            citations = citations,
            chunks = db.sourceChunkDao().getByProject(projectId).associateBy { it.id },
            documents = db.sourceDocumentDao().getByProject(projectId).associateBy { it.id },
            issues = db.verificationIssueDao().getActiveByChapter(planId),
            usedModelKnowledge = db.chapterVersionDao().getActive(planId)?.usedModelKnowledge ?: false,
            unverifiedClaimCount = db.chapterPlanDao().getById(planId)?.unverifiedClaims?.size ?: 0,
        )
    }

    /** 同上，块级 */
    private suspend fun activeBlockSources(
        planId: String,
        projectId: String = "p-1",
    ): Map<String, BlockSourceSummary> = BlockSourceSummary.build(
        blockIds = db.chapterBlockDao().getActiveBlocks(planId).map { it.id },
        citations = db.citationDao().getActiveByChapter(planId),
        chunks = db.sourceChunkDao().getByProject(projectId).associateBy { it.id },
        documents = db.sourceDocumentDao().getByProject(projectId).associateBy { it.id },
        modelKnowledge = db.chapterVersionDao().getActive(planId)?.usedModelKnowledge ?: false,
        issues = db.verificationIssueDao().getActiveByChapter(planId),
    )

    /** 删资料走的是 UI 那条路径：先降级引用（此时资料行还在，标题抄得到），再删资料 */
    private suspend fun deleteSource(sourceId: String) {
        db.citationDao().unconfirmForSource(sourceId, CitationStatus.SOURCE_DELETED)
        db.sourceChunkDao().deleteBySource(sourceId)
        db.sourceDocumentDao().deleteById(sourceId)
    }

    private fun issue(
        versionId: String,
        chapterId: String,
        order: Int,
        blockId: String?,
        message: String,
        resolution: IssueResolution = IssueResolution.OPEN,
    ) = VerificationIssue(
        id = VerificationIssue.idFor(versionId, order),
        projectId = "p-1",
        chapterId = chapterId,
        versionId = versionId,
        blockId = blockId,
        type = IssueType.CITATION_ERROR,
        message = message,
        resolution = resolution,
        order = order,
        createdAt = 1L,
    )

    /** 核验阶段返回给模型桩的一条问题 */
    private fun verifyIssue(blockId: String?, message: String): String {
        val block = blockId?.let { "\"$it\"" } ?: "null"
        return """{"type":"citation_error","blockId":$block,"message":"$message",""" +
            """"sourceRefs":["c-1"],"suggestedFix":"按资料改回来"}"""
    }

    private fun issuesJson(vararg issues: String): String =
        """{"issues":[${issues.joinToString(",")}]}"""

    private suspend fun activeVersionId(planId: String): String =
        db.chapterPlanDao().getById(planId)?.activeVersionId.orEmpty()

    /**
     * 有资料模式的项目：一份带法域与生效时间的法律资料（片段有页码与章节），
     * 外加一份早期导入的笔记（片段只能定位到第几段）。
     *
     * 两份资料都需要：4C 要区分的处境里有两种只在「资料之间不一样」时才出现——
     * 有没有适用边界、定位精不精确。
     */
    private suspend fun seedThroughBible(secondBlockRefs: String = """"c-1""""): String {
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
                contentHash = "h1",
                parseStatus = ParseStatus.PARSED,
                jurisdiction = "中国大陆",
                effectiveFrom = "2021-01-01",
                legalVersion = "2020 年修正",
            ),
        )
        db.sourceDocumentDao().upsert(
            SourceDocument(
                id = "src-2",
                projectId = "p-1",
                title = "课堂笔记",
                kind = SourceKind.TEXT,
                fetchedAt = 1L,
                contentHash = "h2",
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
                    pageNumber = 12,
                    sectionPath = "第三章 合同的订立 > 第二节 要约",
                    paragraphIndex = 3,
                ),
                // v10 之前导入的片段：没有页码、没有章节路径，也没有自由格式定位串
                SourceChunk(
                    id = "c-2",
                    sourceId = "src-2",
                    projectId = "p-1",
                    ordinal = 0,
                    text = "老师说意思表示要区分内心意思与外部表达。",
                ),
            ),
        )
        pipeline.generateKnowledgeMap("p-1").getOrThrow()
        // 让知识点同时引到两份资料。正文生成时可引的片段是按知识点的 sourceRefs 装的，
        // 不补这一步，c-2 根本进不了可引范围，写进 sourceRefs 只会变成一条未确认引用
        db.knowledgeNodeDao().getByProject("p-1").forEach { node ->
            db.knowledgeNodeDao().upsert(node.copy(sourceRefs = listOf("c-1", "c-2")))
        }
        pipeline.generateStoryBible("p-1").getOrThrow()
        // 第二块正文引什么由各条测试决定：五种处境的差别全在这里
        router.chapterJson = router.chapterJson.replace(
            """"sourceRefs":["c-1"]}]}""",
            """"sourceRefs":[$secondBlockRefs]}]}""",
        )
        router.calls.clear()
        return db.chapterPlanDao().getByProject("p-1").sortedBy { it.order }.first().id
    }

    /** 无资料模式的项目：一条引用都引不到，「这一章的知识来自 AI 自己」是唯一能说的话 */
    private suspend fun seedModelKnowledgeProject(): String {
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
        router.chapterJson = router.chapterJson.replace(""""sourceRefs":["c-1"]""", """"sourceRefs":[]""")
        router.calls.clear()
        return db.chapterPlanDao().getByProject("p-2").sortedBy { it.order }.first().id
    }
}
