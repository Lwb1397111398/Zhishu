package com.example.zhishu.search

import com.example.zhishu.data.local.dao.ChapterBlockDao
import com.example.zhishu.data.local.dao.KnowledgeNodeDao
import com.example.zhishu.data.local.dao.SourceChunkDao
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.KnowledgeNode

class DefaultSearchRepository(
    private val knowledgeNodeDao: KnowledgeNodeDao,
    private val chapterBlockDao: ChapterBlockDao,
    private val sourceChunkDao: SourceChunkDao,
) : SearchRepository {

    override suspend fun search(projectId: String, query: String): List<SearchHit> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptyList()

        val hits = mutableListOf<SearchHit>()
        // 一次取出全项目正文，供知识点命中定位到「讲这个知识点的那一块」
        val blocksByChapter = chapterBlockDao.getByProject(projectId).groupBy { it.chapterId }

        knowledgeNodeDao.searchExactTitle(projectId, normalized).forEach { node ->
            val anchor = anchorFor(node, blocksByChapter)
            hits += SearchHit(
                id = node.id,
                kind = SearchHitKind.NODE_EXACT,
                title = node.title,
                snippet = node.definition.ifBlank { node.title },
                projectId = projectId,
                // 节点命中也带上可跳转章节，否则点击无反应（B16）
                chapterId = anchor?.first,
                // 再带上具体正文块，点进去直接落在讲它的那一段
                blockId = anchor?.second,
                nodeId = node.id,
                score = 1000,
            )
        }

        knowledgeNodeDao.searchFuzzy(projectId, normalized).forEach { node ->
            if (hits.none { it.id == node.id && it.kind == SearchHitKind.NODE_EXACT }) {
                val anchor = anchorFor(node, blocksByChapter)
                hits += SearchHit(
                    id = node.id,
                    kind = SearchHitKind.NODE_FUZZY,
                    title = node.title,
                    snippet = node.definition.ifBlank { node.title },
                    projectId = projectId,
                    chapterId = anchor?.first,
                    blockId = anchor?.second,
                    nodeId = node.id,
                    score = if (node.title.contains(normalized)) 800 else 600,
                )
            }
        }

        val match = SearchTokenizer.matchQuery(normalized)
        if (match != null) {
            chapterBlockDao.searchByProjectMatch(projectId, match).forEach { block ->
                hits += SearchHit(
                    id = block.id,
                    kind = SearchHitKind.CHAPTER_BLOCK,
                    title = "章节段落",
                    snippet = block.text,
                    projectId = projectId,
                    chapterId = block.chapterId,
                    blockId = block.id,
                    score = 400,
                )
            }

            sourceChunkDao.searchByProjectMatch(projectId, match).forEach { chunk ->
                hits += SearchHit(
                    id = chunk.id,
                    kind = SearchHitKind.SOURCE_CHUNK,
                    title = "来源片段",
                    snippet = chunk.text,
                    projectId = projectId,
                    score = 200,
                )
            }
        }

        return hits.sortedByDescending { it.score }.take(50)
    }

    /**
     * 知识点落在正文哪里：优先取它 chapterIds 里第一个真的有正文的章节，
     * 再在该章里找第一个标注了这个知识点的块。
     */
    private fun anchorFor(
        node: KnowledgeNode,
        blocksByChapter: Map<String, List<ChapterBlock>>,
    ): Pair<String, String?>? {
        val chapterId = node.chapterIds.firstOrNull { blocksByChapter.containsKey(it) }
            ?: node.chapterIds.firstOrNull()
            ?: return null
        val blockId = blocksByChapter[chapterId]
            ?.sortedBy { it.order }
            ?.firstOrNull { node.id in it.knowledgeNodeIds }
            ?.id
        return chapterId to blockId
    }
}
