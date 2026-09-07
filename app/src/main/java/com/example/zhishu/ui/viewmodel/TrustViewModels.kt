package com.example.zhishu.ui.viewmodel

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 引用了某个资料片段的一个当前正文块（4C 资料 → 正文方向） */
data class CitingBlock(
    val chapterId: String,
    val chapterOrder: Int,
    val chapterTitle: String,
    val blockId: String,
    val excerpt: String,
)

/**
 * 资料详情（4C）：正文 → 资料这条回跳的落点，也是反向回跳的起点。
 *
 * 只读当前版本的引用（`getActiveByProject`）。历史版本的引用留在库里，
 * 但如果混进来，「这一段被正文哪几处引用」会列出读者已经看不到的段落，
 * 点过去还会落在一个不存在的块上。
 */
class SourceDetailViewModel(
    app: Application,
    val sourceId: String,
) : ZhishuViewModel(app) {

    private val _source = MutableStateFlow<SourceDocument?>(null)
    val source: StateFlow<SourceDocument?> = _source

    private val _chunks = MutableStateFlow<List<SourceChunk>>(emptyList())
    val chunks: StateFlow<List<SourceChunk>> = _chunks

    /** chunkId → 引用它的当前正文块 */
    private val _citingBlocks = MutableStateFlow<Map<String, List<CitingBlock>>>(emptyMap())
    val citingBlocks: StateFlow<Map<String, List<CitingBlock>>> = _citingBlocks

    init { load() }

    private fun load() {
        viewModelScope.launch {
            // 先按 ID 直查资料本身：原来靠「片段的 projectId」反推资料，
            // 一份解析失败或内容为空的资料（0 片段）会走不到赋值那一步，
            // 页面于是把「还在库里的资料」误报成「这份资料已经不在库里了」（D4）。
            val document = container.sourceRepository.getById(sourceId)
            _source.value = document
            val chunkList = container.sourceRepository.getChunks(sourceId).sortedBy { it.ordinal }
            _chunks.value = chunkList
            val projectId = document?.projectId ?: chunkList.firstOrNull()?.projectId
                ?: return@launch

            // 三次整表查询换掉「每个片段查一次」：一份 PDF 切出上百个片段，
            // 逐个查会在打开页面时打出上百条 SQL
            val citations = container.citationRepository.getActiveByProject(projectId)
                .filter { it.status == CitationStatus.RESOLVED && it.chunkId != null }
            if (citations.isEmpty()) return@launch
            val blocks = container.chapterRepository.getActiveBlocksByProject(projectId)
                .associateBy { it.id }
            val plans = container.chapterRepository.getPlans(projectId).associateBy { it.id }
            _citingBlocks.value = citations
                .groupBy { it.chunkId!! }
                .mapValues { (_, list) ->
                    list.mapNotNull { citation ->
                        val block = blocks[citation.blockId] ?: return@mapNotNull null
                        val plan = plans[citation.chapterId]
                        CitingBlock(
                            chapterId = citation.chapterId,
                            chapterOrder = plan?.order ?: 0,
                            chapterTitle = plan?.title.orEmpty(),
                            blockId = block.id,
                            excerpt = block.text.take(CITING_EXCERPT),
                        )
                    }.distinctBy { it.blockId }.sortedBy { it.chapterOrder }
                }
        }
    }

    private companion object {
        /** 回跳列表里正文摘录多长。够认出是哪一段就行 */
        const val CITING_EXCERPT = 120
    }
}

/** 未核实内容的一条（4C） */
data class TrustEntry(
    val kind: Kind,
    val chapterId: String,
    val chapterOrder: Int,
    val chapterTitle: String,
    /** 能定位到具体正文块时非空。为空表示只能整章处理 */
    val blockId: String?,
    val title: String,
    val detail: String,
    val excerpt: String,
    /** 未确认引用带的资料标识 / 已删资料标题；问题条目为空 */
    val rawRef: String = "",
) {
    enum class Kind {
        UNCONFIRMED_CITATION,
        DELETED_SOURCE,
        OPEN_ISSUE,
        ;

        val label: String
            get() = when (this) {
                UNCONFIRMED_CITATION -> "未确认来源"
                DELETED_SOURCE -> "来源已删除"
                OPEN_ISSUE -> "待核对问题"
            }
    }

    val canLocate: Boolean get() = blockId != null
}

/**
 * 未核实内容筛选（4C）。
 *
 * 只列当前 active version 的东西：这一页的用途是「我该去核对什么」，
 * 把历史版本混进来就变成了「我曾经该核对什么」，其中大半段落读者
 * 已经看不到了，点过去也落不到实处。
 *
 * 定位不到具体正文块的核验问题照样列出来，但明说只能整章重新生成，
 * 不给一个点了没用的重写按钮——4B 已经定过这个调子。
 */
class TrustCenterViewModel(
    app: Application,
    val projectId: String,
) : ZhishuViewModel(app) {

    private val _entries = MutableStateFlow<List<TrustEntry>>(emptyList())
    val entries: StateFlow<List<TrustEntry>> = _entries

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    /** null 表示不筛选。UI 同时读 [entries] 与这一条，筛选在展示层做 */
    val filter = MutableStateFlow<TrustEntry.Kind?>(null)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            val plans = container.chapterRepository.getPlans(projectId).associateBy { it.id }
            val blocks = container.chapterRepository.getActiveBlocksByProject(projectId)
                .associateBy { it.id }
            val citations = container.citationRepository.getActiveByProject(projectId)
                .filter { it.status != CitationStatus.RESOLVED }
            val issues = container.issueRepository.getActiveByProject(projectId)
                .filter { it.resolution.isOpen }

            val fromCitations = citations.map { citation ->
                val plan = plans[citation.chapterId]
                val block = blocks[citation.blockId]
                val deleted = citation.status == CitationStatus.SOURCE_DELETED
                TrustEntry(
                    kind = if (deleted) {
                        TrustEntry.Kind.DELETED_SOURCE
                    } else {
                        TrustEntry.Kind.UNCONFIRMED_CITATION
                    },
                    chapterId = citation.chapterId,
                    chapterOrder = plan?.order ?: 0,
                    chapterTitle = plan?.title.orEmpty(),
                    // 引用一定挂在某个块上，但那个块可能已经不属于当前版本
                    // （查询已经过滤过，这里为 null 只会是数据异常）
                    blockId = block?.id,
                    title = if (deleted) {
                        val name = citation.lostSourceTitle.ifBlank { "一份已删除的资料" }
                        "引用的资料「$name」已被删除"
                    } else {
                        "模型给的出处没能对上资料"
                    },
                    detail = if (deleted) {
                        SourceTrustState.SOURCE_DELETED.explanation
                    } else {
                        SourceTrustState.UNCONFIRMED.explanation
                    },
                    excerpt = block?.text?.take(TRUST_EXCERPT).orEmpty(),
                    rawRef = citation.rawRef,
                )
            }

            val fromIssues = issues.map { issue ->
                val plan = plans[issue.chapterId]
                TrustEntry(
                    kind = TrustEntry.Kind.OPEN_ISSUE,
                    chapterId = issue.chapterId,
                    chapterOrder = plan?.order ?: 0,
                    chapterTitle = plan?.title.orEmpty(),
                    blockId = issue.blockId?.takeIf { blocks.containsKey(it) },
                    title = issue.type.label,
                    detail = issue.message,
                    excerpt = issue.blockId?.let { blocks[it]?.text?.take(TRUST_EXCERPT) }.orEmpty(),
                )
            }

            _entries.value = (fromCitations + fromIssues)
                .sortedWith(compareBy({ it.chapterOrder }, { it.kind.ordinal }))
            _loading.value = false
        }
    }

    private companion object {
        const val TRUST_EXCERPT = 160
    }
}
