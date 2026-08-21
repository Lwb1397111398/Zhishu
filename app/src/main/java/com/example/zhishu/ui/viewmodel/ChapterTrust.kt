package com.example.zhishu.ui.viewmodel

import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.VerificationIssue

/**
 * 一份资料在本章里的时效信息（4C）。
 *
 * 法律、税务、医疗这类内容离开法域和生效时间就没有对错可言。
 * 读者要能一眼看到「本章讲的是哪个地区、哪个时间点之后的规则」，
 * 而不是读完一整章才发现它讲的是另一个法域的做法。
 */
data class ChapterSourceScope(
    val sourceId: String,
    val title: String,
    val jurisdiction: String?,
    val effectiveFrom: String?,
    val version: String?,
) {
    /** 「中国大陆 · 2021-01-01 起施行 · 2020 年修正」 */
    val summary: String
        get() = buildList {
            jurisdiction?.takeIf { it.isNotBlank() }?.let { add(it) }
            effectiveFrom?.takeIf { it.isNotBlank() }?.let { add("$it 起施行") }
            version?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(" · ")
}

/**
 * 章节级来源摘要（4C）。
 *
 * 回答的是读者在读之前/读完之后想问的那句「这一章有多可信」。
 * 统计范围**严格限定为当前 active version**：历史版本的引用与问题都留在库里，
 * 但混进来会让每个数字都失去意义——重生成过一次的章节会把两版的来源加起来，
 * 显示出「本章有 14 处来源」，而读者眼前这一版其实只有 7 处。
 *
 * 这些数字不用来吓唬人。默认折叠，展开才看得到；阅读器上只留一行入口。
 */
data class ChapterTrustSummary(
    /** 本章当前版本引用到的用户资料份数（去重后） */
    val sourceCount: Int = 0,
    /** 可定位引用：能点开原文的那些 */
    val locatableCount: Int = 0,
    /** 未确认引用：模型给了出处但没对上 */
    val unconfirmedCount: Int = 0,
    /** 引用的资料已被删除 */
    val deletedSourceCount: Int = 0,
    /** 这一版正文写作时用的是 AI 自备知识 */
    val usedModelKnowledge: Boolean = false,
    /** 当前版本还没处理的核验问题 */
    val openIssueCount: Int = 0,
    /** 其中能定位到具体正文块的条数——决定用户能不能只重写那几段 */
    val locatedIssueCount: Int = 0,
    /** 需要自己核对的断言（无资料模式下模型自己列的） */
    val unverifiedClaimCount: Int = 0,
    /** 本章涉及的、带法域或生效时间限制的资料 */
    val scopes: List<ChapterSourceScope> = emptyList(),
    /** 有引用只能定位到片段级别（v10 之前导入的旧资料） */
    val hasImpreciseLocator: Boolean = false,
) {
    /**
     * 没什么可展开的：没有来源、没有待核对项，而且这一章也不是 AI 自备知识写的。
     *
     * `usedModelKnowledge` 必须算进来。无资料模式写出来的章节引用数、问题数
     * 全是 0，漏掉这一项的话它会被判成「空」，阅读器连来源入口都不显示——
     * 而「这一章的知识来自 AI 自己」恰恰是这种章节最该让读者看到的一句话。
     */
    val isEmpty: Boolean
        get() = sourceCount == 0 && unconfirmedCount == 0 &&
            deletedSourceCount == 0 && openIssueCount == 0 &&
            unverifiedClaimCount == 0 && !usedModelKnowledge

    val needsAttentionCount: Int get() = unconfirmedCount + deletedSourceCount + openIssueCount

    /**
     * 阅读器顶部那一行。一句话，克制。
     *
     * 先说这一章的知识从哪来（用户资料 / AI 自备），再说有没有要核对的。
     * 「本章主要基于你的资料」和「本章包含 AI 自备知识」是两句完全不同的话，
     * 读者据此决定要不要把这一章的内容当成可以直接引用的东西。
     */
    val headline: String
        get() {
            val origin = when {
                sourceCount > 0 && usedModelKnowledge -> "本章基于你的 $sourceCount 份资料，也包含 AI 自备知识"
                sourceCount > 0 -> "本章主要基于你的 $sourceCount 份资料"
                usedModelKnowledge -> "本章包含 AI 自备知识"
                else -> "本章没有标注来源"
            }
            val tail = buildList {
                if (locatableCount > 0) add("$locatableCount 处可定位引用")
                if (needsAttentionCount > 0) add("$needsAttentionCount 项需核对")
            }
            return if (tail.isEmpty()) origin else "$origin · ${tail.joinToString(" · ")}"
        }

    companion object {
        /**
         * 从当前版本的引用、片段、资料与问题拼出摘要。
         *
         * 传进来的 [citations] 与 [issues] 必须已经限定在 active version 上——
         * 这个函数不做过滤，因为它拿不到章节指针；调用方（ReaderViewModel）
         * 用的是 Active 系列查询，那里才是唯一该做这件事的地方。
         */
        fun build(
            citations: List<Citation>,
            chunks: Map<String, SourceChunk>,
            documents: Map<String, SourceDocument>,
            issues: List<VerificationIssue>,
            usedModelKnowledge: Boolean,
            unverifiedClaimCount: Int,
        ): ChapterTrustSummary {
            val resolved = citations.filter { it.status == CitationStatus.RESOLVED }
            val usedDocuments = resolved
                .mapNotNull { citation -> citation.chunkId?.let { chunks[it] } }
                .mapNotNull { documents[it.sourceId] }
                .distinctBy { it.id }
            val open = issues.filter { it.resolution.isOpen }
            return ChapterTrustSummary(
                sourceCount = usedDocuments.size,
                // 「可定位」要求片段与资料都还在：状态是 RESOLVED 但资料查不到时
                // 点开只会得到一片空白，那不算可定位
                locatableCount = resolved.count { citation ->
                    val chunk = citation.chunkId?.let { chunks[it] }
                    chunk != null && documents[chunk.sourceId] != null
                },
                unconfirmedCount = citations.count { it.status == CitationStatus.UNCONFIRMED },
                deletedSourceCount = citations.count {
                    it.status == CitationStatus.SOURCE_DELETED
                },
                usedModelKnowledge = usedModelKnowledge,
                openIssueCount = open.size,
                locatedIssueCount = open.count { it.isBlockScoped },
                unverifiedClaimCount = unverifiedClaimCount,
                scopes = usedDocuments.filter { it.isTimeSensitive }.map { document ->
                    ChapterSourceScope(
                        sourceId = document.id,
                        title = document.title,
                        jurisdiction = document.jurisdiction,
                        effectiveFrom = document.effectiveFrom,
                        version = document.versionLabelForDisplay,
                    )
                },
                hasImpreciseLocator = resolved.any { citation ->
                    val chunk = citation.chunkId?.let { chunks[it] } ?: return@any false
                    chunk.pageNumber == null &&
                        chunk.sectionPath.isNullOrBlank() &&
                        chunk.locator.isNullOrBlank()
                },
            )
        }
    }
}
