package com.example.zhishu.ui.viewmodel

import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument

/**
 * 一条引用在界面上需要说清的全部信息（第四阶段 4A）。
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
    val needsManualCheck: Boolean get() = citation.status != CitationStatus.RESOLVED

    /** 资料名称。未确认引用连资料都不知道，如实说 */
    val title: String get() = document?.title ?: "未能匹配到资料"

    /** 「第 12 页 · 第三章 > 第二节」。没有片段时为空 */
    val locator: String get() = chunk?.locatorLabel.orEmpty()

    /** 版本与时效信息，例如「2020 年修正 · 中国大陆 · 2021-01-01 起施行」 */
    val versionInfo: String
        get() = buildList {
            document?.versionLabelForDisplay?.let { add(it) }
            document?.jurisdiction?.takeIf { it.isNotBlank() }?.let { add(it) }
            document?.effectiveFrom?.takeIf { it.isNotBlank() }?.let { add("$it 起施行") }
        }.joinToString(" · ")

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
 */
data class BlockSourceSummary(
    val blockId: String,
    val sources: List<BlockSource>,
    val modelKnowledge: Boolean,
) {
    val resolvedCount: Int get() = sources.count { !it.needsManualCheck }
    val unconfirmedCount: Int get() = sources.count { it.needsManualCheck }
    val hasAnything: Boolean get() = sources.isNotEmpty()

    /**
     * 正文块旁边那一行小字。四种情况分开说，因为它们对读者的意思完全不同：
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

    companion object {
        /** 把三张表拼成按块分组的汇总。没有引用的块也会有一条（空的），UI 才能说「未标注来源」 */
        fun build(
            blockIds: List<String>,
            citations: List<Citation>,
            chunks: Map<String, SourceChunk>,
            documents: Map<String, SourceDocument>,
            modelKnowledge: Boolean,
        ): Map<String, BlockSourceSummary> {
            val byBlock = citations.groupBy { it.blockId }
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
                )
            }
        }
    }
}
