package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.IssueResolution
import com.example.zhishu.domain.model.IssueType
import com.example.zhishu.domain.model.VerificationIssue
import com.example.zhishu.ui.viewmodel.DemoSeeder
import com.example.zhishu.ui.viewmodel.SourceDetailViewModel
import com.example.zhishu.ui.viewmodel.TrustCenterViewModel
import com.example.zhishu.ui.viewmodel.TrustEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 正文 ⇄ 资料双向回跳与未核实内容筛选（第四阶段 4C）。
 *
 * 回跳只有一个要求，但它很容易被违反：**落点必须是读者现在真的看得到的东西。**
 * 引用、正文块、核验问题在库里都按版本堆着，历史版本一条都不删（3A/4A/4B 的承诺）。
 * 只要有一处忘了限定当前版本，用户点过去就会落在一段已经不存在的正文上，
 * 或者被告成「这一章还有 5 处没核对」——其中四处属于他早就替换掉的那一版。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class TrustNavigationTest {

    private lateinit var app: ZhishuApp
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        container = app.container
    }

    // --- 资料 → 正文 ---

    /**
     * 一个片段被哪几段正文引用，全都列出来，并说清是第几章的哪一段。
     *
     * 只列一条是不够的：一份法条片段在整本书里会被反复引用，
     * 用户在资料页问的是「我改了这一段，会影响哪些正文」。
     */
    @Test
    fun sourceDetail_listsEveryCurrentBlockCitingOneChunk() = runBlocking {
        DemoSeeder.seed(container)

        val vm = SourceDetailViewModel(app, SOURCE_ID)
        val citing = withTimeout(10_000) { vm.citingBlocks.first { it.isNotEmpty() } }

        val blocks = citing.getValue(CHUNK_ID)
        assertEquals(listOf("c1b2", "c2b2", "c3b2"), blocks.map { it.blockId })
        // 按章节顺序排，页面上从前往后读得下去
        assertEquals(listOf(1, 2, 3), blocks.map { it.chapterOrder })
        assertTrue(blocks.all { it.chapterTitle.isNotBlank() })
        // 带正文摘录：用户要认出这是哪一段，光有 ID 认不出来
        assertTrue(blocks.first().excerpt.contains("继承从被继承人死亡时开始"))

        // 片段自己的定位信息也读出来了，回跳时页面能说出「在原资料的哪里」
        assertEquals(
            "第六编 继承 > 第一章 一般规定",
            vm.chunks.value.single { it.id == CHUNK_ID }.locatorLabel,
        )
        assertEquals("民法典继承编（节选）", vm.source.value?.title)
    }

    /**
     * 历史版本的正文块不出现在回跳列表里。
     *
     * 旧版本的引用一条都不删（3A/4A 的承诺），但读者已经看不到那段正文了。
     * 列出来的后果是点过去落在一个不存在的块上——阅读器只能回到章首，
     * 用户完全不知道刚才发生了什么。
     */
    @Test
    fun sourceDetail_excludesBlocksFromHistoryVersions() = runBlocking {
        DemoSeeder.seed(container)
        seedAbandonedVersionCiting(CHUNK_ID)

        val vm = SourceDetailViewModel(app, SOURCE_ID)
        val citing = withTimeout(10_000) { vm.citingBlocks.first { it.isNotEmpty() } }

        val ids = citing.getValue(CHUNK_ID).map { it.blockId }
        assertEquals(listOf("c1b2", "c2b2", "c3b2"), ids)
        assertFalse("上一版的正文块不该出现在回跳列表里", ids.contains(STALE_BLOCK_ID))
        // 那条历史引用本身还在库里，只是不参与回跳
        assertTrue(
            container.citationRepository.getAllVersionsByProject(PROJECT_ID)
                .any { it.blockId == STALE_BLOCK_ID },
        )
    }

    // --- 未核实内容筛选 ---

    /**
     * 可信度中心只列当前正文的待核对项，而且分得清是哪一种。
     *
     * 「模型给的出处没对上」「引用的资料被删了」「核验指出的问题」三件事的
     * 下一步动作完全不同，混成一张没有类型的清单等于让用户自己去猜。
     */
    @Test
    fun trustCenter_listsCurrentVersionEntriesAndNamesTheirKind() = runBlocking {
        DemoSeeder.seed(container)
        val activeVersion = "c1-v1"
        container.citationRepository.upsertAll(
            listOf(
                citation("c1b3", activeVersion, order = 0, chunkId = null,
                    rawRef = "《民法典》第1122条", status = CitationStatus.UNCONFIRMED),
                citation("c1b4", activeVersion, order = 0, chunkId = null,
                    rawRef = CHUNK_ID, status = CitationStatus.SOURCE_DELETED,
                    lostSourceTitle = "上学期的讲义"),
            ),
        )
        container.issueRepository.upsertAll(
            listOf(issue(activeVersion, 0, "c1b1", "这里的顺序与资料不符")),
        )

        val entries = loadTrustEntries()
        assertEquals(
            listOf(
                TrustEntry.Kind.UNCONFIRMED_CITATION,
                TrustEntry.Kind.DELETED_SOURCE,
                TrustEntry.Kind.OPEN_ISSUE,
            ),
            entries.map { it.kind },
        )
        // 已删除的那条说得出丢的是哪一份资料
        val deleted = entries.single { it.kind == TrustEntry.Kind.DELETED_SOURCE }
        assertTrue(deleted.title.contains("上学期的讲义"))
        assertTrue(deleted.detail.contains("不是模型编的"))
        // 未确认的那条留着模型给的原始标识——它是用户自己去核对时唯一的线索
        val unconfirmed = entries.single { it.kind == TrustEntry.Kind.UNCONFIRMED_CITATION }
        assertEquals("《民法典》第1122条", unconfirmed.rawRef)
        // 三条都能定位到具体段落，也都带着正文摘录
        assertTrue(entries.all { it.canLocate })
        assertTrue(entries.all { it.excerpt.isNotBlank() })
        assertTrue(entries.all { it.chapterTitle.isNotBlank() })
    }

    /** 已经能对上的引用、已修与「不用改」的问题都不再列出来：它们已经有结论了 */
    @Test
    fun trustCenter_ignoresResolvedCitationsAndSettledIssues() = runBlocking {
        DemoSeeder.seed(container)
        container.issueRepository.upsertAll(
            listOf(
                issue("c1-v1", 0, "c1b1", "已经改好了", IssueResolution.FIXED),
                issue("c1-v1", 1, "c1b2", "我看过了，不用改", IssueResolution.ACCEPTED),
            ),
        )

        // 示例项目的引用全是 RESOLVED，加上两条已有结论的问题：清单应当是空的
        assertTrue(container.citationRepository.getActiveByProject(PROJECT_ID).isNotEmpty())
        assertTrue(loadTrustEntries().isEmpty())
    }

    /**
     * 历史版本的待核对项不混进来。
     *
     * 这一页的用途是「我现在该去核对什么」。掺进上一版的条目就变成了
     * 「我曾经该核对什么」，其中大半段落读者已经看不到，点过去也落不到实处，
     * 而那个虚高的数字会让人以为自己的书比实际更不可信。
     */
    @Test
    fun trustCenter_excludesEntriesFromHistoryVersions() = runBlocking {
        DemoSeeder.seed(container)
        seedAbandonedVersionCiting(CHUNK_ID)
        // 旧版本上挂一条未确认引用和一条待处理问题
        container.citationRepository.upsertAll(
            listOf(
                citation(STALE_BLOCK_ID, STALE_VERSION_ID, order = 1, chunkId = null,
                    rawRef = "上一版编的出处", status = CitationStatus.UNCONFIRMED),
            ),
        )
        container.issueRepository.upsertAll(
            listOf(issue(STALE_VERSION_ID, 0, STALE_BLOCK_ID, "上一版的问题")),
        )
        // 当前版本上挂一条，用来确认查询本身是通的
        container.issueRepository.upsertAll(
            listOf(issue("c2-v1", 0, "c2b1", "当前正文的问题")),
        )

        val entries = loadTrustEntries()
        assertEquals(listOf("当前正文的问题"), entries.map { it.detail })
        assertTrue(entries.none { it.excerpt.contains("上一版") })
    }

    /**
     * 定位不到具体段落的问题照样列出来，但不假装能定位。
     *
     * 两种情况都归到这里：模型本来就没给出 blockId（「全章语气不一致」），
     * 以及给的 blockId 属于上一版正文。后者尤其要小心——那个 ID 看起来完全正常，
     * 猜一个最像的块给用户点，他会打开一段没有问题的正文然后开始怀疑核验本身。
     */
    @Test
    fun trustCenter_keepsUnlocatableIssuesWithoutFakingALocation() = runBlocking {
        DemoSeeder.seed(container)
        container.issueRepository.upsertAll(
            listOf(
                issue("c1-v1", 0, null, "全章推演在中段断了一次"),
                issue("c1-v1", 1, "上一版的块", "这条问题的定位已经失效"),
            ),
        )

        val entries = loadTrustEntries()
        assertEquals(2, entries.size)
        assertTrue("定位不到就是定位不到，不给一个点了没用的按钮", entries.none { it.canLocate })
        assertTrue(entries.all { it.blockId == null })
        assertTrue(entries.all { it.excerpt.isEmpty() })
        // 但问题本身没被丢掉：丢掉等于悄悄告诉用户这一章没问题
        assertTrue(entries.any { it.detail == "全章推演在中段断了一次" })
        assertTrue(entries.any { it.detail == "这条问题的定位已经失效" })
        // 章节信息还在，用户至少知道该打开哪一章
        assertTrue(entries.all { it.chapterId == "c1" && it.chapterTitle.isNotBlank() })
    }

    // --- 辅助 ---

    private suspend fun loadTrustEntries(): List<TrustEntry> {
        val vm = TrustCenterViewModel(app, PROJECT_ID)
        withTimeout(10_000) { vm.loading.first { !it } }
        return vm.entries.value
    }

    /**
     * 造一个「上一版正文」：作废的版本 + 属于它的正文块 + 指向同一片段的引用。
     *
     * 这是重生成过一次的章节在库里的真实样子（3A）。回跳与筛选都必须把它当成
     * 不存在——但它确实在库里，所以只能靠查询限定当前版本来做到。
     */
    private suspend fun seedAbandonedVersionCiting(chunkId: String) {
        container.chapterRepository.upsertVersions(
            listOf(
                ChapterVersion(
                    id = STALE_VERSION_ID,
                    chapterId = "c1",
                    projectId = PROJECT_ID,
                    versionNumber = 0,
                    status = ChapterVersionStatus.ARCHIVED,
                    title = "上一版",
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            ),
        )
        container.chapterRepository.upsertBlocks(
            listOf(
                ChapterBlock(
                    id = STALE_BLOCK_ID,
                    chapterId = "c1",
                    versionId = STALE_VERSION_ID,
                    projectId = PROJECT_ID,
                    order = 0,
                    text = "上一版的这一段正文，读者已经看不到了。",
                    sourceRefs = listOf(chunkId),
                ),
            ),
        )
        container.citationRepository.upsertAll(
            listOf(citation(STALE_BLOCK_ID, STALE_VERSION_ID, order = 0, chunkId = chunkId)),
        )
    }

    private fun citation(
        blockId: String,
        versionId: String,
        order: Int,
        chunkId: String?,
        rawRef: String = chunkId.orEmpty(),
        status: CitationStatus = CitationStatus.RESOLVED,
        lostSourceTitle: String = "",
    ) = Citation(
        id = Citation.idFor(blockId, order),
        projectId = PROJECT_ID,
        chapterId = versionId.substringBefore("-v"),
        versionId = versionId,
        blockId = blockId,
        chunkId = chunkId,
        rawRef = rawRef,
        status = status,
        lostSourceTitle = lostSourceTitle,
        order = order,
    )

    private fun issue(
        versionId: String,
        order: Int,
        blockId: String?,
        message: String,
        resolution: IssueResolution = IssueResolution.OPEN,
    ) = VerificationIssue(
        id = VerificationIssue.idFor(versionId, order),
        projectId = PROJECT_ID,
        chapterId = versionId.substringBefore("-v"),
        versionId = versionId,
        blockId = blockId,
        type = IssueType.CITATION_ERROR,
        message = message,
        resolution = resolution,
        order = order,
        createdAt = 1L,
    )

    private companion object {
        const val PROJECT_ID = "demo-civil-law"
        const val SOURCE_ID = "demo-source-civil-code"
        const val CHUNK_ID = "demo-chunk-1"

        /** 重生成之前那一版：版本作废、正文块还在库里，但读者看不到 */
        const val STALE_VERSION_ID = "c1-v0"
        const val STALE_BLOCK_ID = "c1b2-old"
    }
}
