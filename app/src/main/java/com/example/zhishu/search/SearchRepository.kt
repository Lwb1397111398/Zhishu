package com.example.zhishu.search

enum class SearchHitKind {
    NODE_EXACT,
    NODE_FUZZY,
    CHAPTER_BLOCK,
    SOURCE_CHUNK
}

data class SearchHit(
    val id: String,
    val kind: SearchHitKind,
    val title: String,
    val snippet: String,
    val projectId: String,
    val chapterId: String? = null,
    /** 命中所在的正文块。阅读器按它定位并高亮，比字符/像素偏移稳定 */
    val blockId: String? = null,
    /** 知识点命中时带上节点 ID，供「在知识地图中查看」使用 */
    val nodeId: String? = null,
    /**
     * 历史字段：命中词在文本中的字符位置。
     * 它和阅读器的滚动坐标不是同一个体系，定位一律走 [blockId]，这里不再写入。
     */
    val charOffset: Int = 0,
    val score: Int,
)

interface SearchRepository {
    suspend fun search(projectId: String, query: String): List<SearchHit>
}
