package com.example.zhishu.ui.viewmodel

import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.VerificationIssue

/**
 * 一条引用在界面上的可信状态（4C）。
 *
 * 4A 只分「对上了」和「没对上」两种。读者面对的其实是五种不同处境，
 * 混成一句「未确认」会让人无从下手：
 * 「资料删了」该去重新导入，「模型编的出处」该自己查一遍权威来源，
 * 「有法域限制」该确认自己在不在这个范围内，「只能定位到片段」是我们的
 * 定位能力不够、不是内容有问题。说清是哪一种，用户才知道下一步做什么。
 */
enum class SourceTrustState {
    /** 对上了真实片段，能点开原文 */
    CONFIRMED,

    /** 模型给的出处对不上任何片段 */
    UNCONFIRMED,

    /** 当初对上过，那份资料后来被删了 */
    SOURCE_DELETED,
    ;

    val label: String
        get() = when (this) {
            CONFIRMED -> "已确认来源"
            UNCONFIRMED -> "未确认来源"
            SOURCE_DELETED -> "来源已删除"
        }

    /** 一句话说清「发生了什么、你可以做什么」，不只挂一个标签 */
    val explanation: String
        get() = when (this) {
            CONFIRMED -> "这一段能对上你资料里的具体片段，可以点开原文核对。"
            UNCONFIRMED ->
                "模型给出了出处，但它对不上你资料里的任何片段——可能是模型自己凑了一个标识。" +
                    "这一段内容仍然可读，但它的依据没有得到确认，涉及具体规则时建议自己查一次。"
            SOURCE_DELETED ->
                "这一段当初确实有依据，但引用的那份资料已经被删除，原文找不回来了。" +
                    "它不是模型编的；重新导入同一份资料就能恢复定位。"
        }
}

/**
 * 一条引用在界面上需要说清的全部信息（第四阶段 4A，状态与文案在 4C 细分）。
 *
 * 界面要能回答「这一段从哪来」，具体是：哪份资料、哪一页哪一节、什么版本、
 * 什么时候拿到的、是不是用户自己的资料、要不要人工核对。
 * 这些散在三张表里（citations / source_chunks / sources），
 * 在这里拼成一条，UI 不必自己做三次查找。
 */
data class BlockSource(
    val citation: Citation,
    /** 未确认引用时为 null——它本来就没对上任何片段 */
    val chunk: SourceChunk?,
    val document: SourceDocument?,
) {
    val trust: SourceTrustState
        get() = when {
            citation.status == CitationStatus.SOURCE_DELETED -> SourceTrustState.SOURCE_DELETED
            // 状态是 RESOLVED 但片段或资料已经查不到：数据对不上，按未确认处理。
            // 显示成「已确认」会给出一个点开就空白的入口
            citation.status == CitationStatus.RESOLVED && (chunk == null || document == null) ->
                SourceTrustState.UNCONFIRMED
            citation.status.isResolved -> SourceTrustState.CONFIRMED
            else -> SourceTrustState.UNCONFIRMED
        }

    val needsManualCheck: Boolean get() = trust != SourceTrustState.CONFIRMED

    /** 资料名称。资料已删除时用当时抄下来的标题，否则如实说没对上 */
    val title: String
        get() = document?.title
            ?: citation.lostSourceTitle.takeIf { it.isNotBlank() }
            ?: "未能匹配到资料"

    /** 「第 12 页 · 第三章 > 第二节」。没有片段时为空 */
    val locator: String get() = chunk?.locatorLabel.orEmpty()

    /**
     * 定位只到片段级别（4C）。
     *
     * v10 之前导入的片段没有页码也没有章节路径——当年的分块逻辑没记位置，
     * 无从倒推。这时候要明说「只能定位到片段」，不能凭空给一个页码：
     * 用户翻到那一页发现讲的是别的内容，比没有页码更糟。
     */
    val locatorIsImprecise: Boolean
        get() = chunk != null &&
            chunk.pageNumber == null &&
            chunk.sectionPath.isNullOrBlank() &&
            chunk.locator.isNullOrBlank()

    /** 版本与时效信息，例如「2020 年修正 · 中国大陆 · 2021-01-01 起施行」 */
    val versionInfo: String
        get() = buildList {
            document?.versionLabelForDisplay?.let { add(it) }
            document?.jurisdiction?.takeIf { it.isNotBlank() }?.let { add(it) }
            document?.effectiveFrom?.takeIf { it.isNotBlank() }?.let { add("$it 起施行") }
        }.joinToString(" · ")

    /** 这份资料有法域或生效时间限制：正文里那句话未必在读者的处境下成立 */
    val scopeLimited: Boolean get() = document?.isTimeSensitive == true

    /** 原文摘录，点开来核对用 */
    val excerpt: String get() = chunk?.text.orEmpty()
}

/**
 * 一个正文块的来源汇总。
 *
 * [modelKnowledge] 说的是**这一版正文写作时**的知识模式，取自
 * `ChapterVersion.usedModelKnowledge`，而不是现查项目设置：用户完全可能
 * 先用无资料模式写了前五章，之后才导入资料。那时候按项目设置看，
 * 前五章会显示成「有出处」——它们一条引用都没有，因为当时没有资料可引。
 *
 * [issues] 是**当前版本**挂在这一块上的核验问题（4C）。读者点开一段的来源，
 * 最想知道的两件事是「这段的依据在哪」和「这段有没有已知的问题」，
 * 分在两个页面里就等于没说。
 */
data class BlockSourceSummary(
    val blockId: String,
    val sources: List<BlockSource>,
    val modelKnowledge: Boolean,
    val issues: List<VerificationIssue> = emptyList(),
) {
    val resolvedCount: Int get() = sources.count { !it.needsManualCheck }
    val unconfirmedCount: Int get() = sources.count { it.needsManualCheck }
    val hasAnything: Boolean get() = sources.isNotEmpty()

    /** 还没处理的问题。已修和「不用改」都已经有结论了，不该再提醒 */
    val openIssues: List<VerificationIssue> get() = issues.filter { it.resolution.isOpen }

    /**
     * 这一块有引用记录，但没有一条对上（4C）。
     *
     * 这种块里的知识实际上来自模型自己：它给了出处，我们一条都没能确认。
     * 和 [modelKnowledge] 分开——那说的是**整版正文**的写作模式，
     * 而这一条说的是这一块的实际处境，有资料模式下同样会出现。
     * 一条引用记录都没有的块由 [label] 负责说明（「未标注来源」/「AI 自备知识」），
     * 所以这里要求 [hasAnything]，否则两句话会同时出现、重复一遍。
     */
    val allSourcesUnresolved: Boolean get() = hasAnything && resolvedCount == 0

    /**
     * 正文块旁边那一行小字。几种情况分开说，因为它们对读者的意思完全不同：
     * 无资料模式是「这是 AI 自己的知识」；有引用是「可以点开核对」；
     * 有未确认引用是「模型说有出处但没对上」；一条都没有是「这一段没有给出处」。
     */
    val label: String
        get() = when {
            unconfirmedCount > 0 && resolvedCount > 0 ->
                "来源 $resolvedCount 处 · $unconfirmedCount 处待核对"
            unconfirmedCount > 0 -> "$unconfirmedCount 处引用未能匹配，需你核对"
            resolvedCount > 0 -> "来源 $resolvedCount 处"
            modelKnowledge -> "AI 自备知识"
            else -> "未标注来源"
        }

    /**
     * 安静模式下是否值得为这一块打断阅读（4C）。
     *
     * 默认保持故事阅读体验，只有「读者应该知道」的两件事才主动露出来：
     * 出处没对上、这一段有待处理的核验问题。「这一段有 3 处正常来源」
     * 不该在读小说的过程中反复出现。
     */
    val deservesAttention: Boolean get() = unconfirmedCount > 0 || openIssues.isNotEmpty()

    companion object {
        /** 把三张表拼成按块分组的汇总。没有引用的块也会有一条（空的），UI 才能说「未标注来源」 */
        fun build(
            blockIds: List<String>,
            citations: List<Citation>,
            chunks: Map<String, SourceChunk>,
            documents: Map<String, SourceDocument>,
            modelKnowledge: Boolean,
            issues: List<VerificationIssue> = emptyList(),
        ): Map<String, BlockSourceSummary> {
            val byBlock = citations.groupBy { it.blockId }
            val issuesByBlock = issues.filter { it.isBlockScoped }.groupBy { it.blockId }
            return blockIds.associateWith { blockId ->
                BlockSourceSummary(
                    blockId = blockId,
                    sources = byBlock[blockId].orEmpty().sortedBy { it.order }.map { citation ->
                        val chunk = citation.chunkId?.let { chunks[it] }
                        BlockSource(
                            citation = citation,
                            chunk = chunk,
                            document = chunk?.let { documents[it.sourceId] },
                        )
                    },
                    modelKnowledge = modelKnowledge,
                    issues = issuesByBlock[blockId].orEmpty(),
                )
            }
        }
    }
}
