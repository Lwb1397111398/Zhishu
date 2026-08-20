package com.example.zhishu.search

import com.example.zhishu.domain.model.SourceChunk
import java.util.UUID

/** 一页 PDF 的文本。页码从 1 开始，与阅读器/PDF 软件显示的页码一致 */
data class SourcePage(val pageNumber: Int, val text: String)

/**
 * 把资料切成可引用的片段。
 *
 * 4A 起每个片段都带上「在原资料里的哪个位置」：章节路径、段落序号，PDF 还带页码。
 * 这三样都是重新解析同一份资料能得到同样结果的稳定锚点。
 * 这里一个字符偏移都不产出——那种坐标只要资料被清理过一次空白就整体错位，
 * 而资料是会被重新导入的。
 */
object Chunker {
    private const val MAX_CHUNK_LENGTH = 2000

    /** 标题最多认到六层，再深就不像目录结构了 */
    private const val MAX_SECTION_DEPTH = 6

    /** Markdown 标题：`## 第二节 要约` */
    private val MARKDOWN_HEADING = Regex("^(#{1,6})\\s+(.+)$")

    /**
     * 中文文档标题：「第三章 合同的订立」「第二节 要约」。
     *
     * 只认「章/节/编/部分」这几级，不认「第十二条」——法律条文是内容本身，
     * 把每一条都当成一级标题的话，章节路径会变成整部法律的目录。
     *
     * 序号后面必须是空白或行尾。少了这一条，「第二章的正文接着说……」
     * 这种正常句子会被当成一个标题，整段内容随之挂到一个不存在的章节下面。
     */
    private val CHINESE_HEADING =
        Regex("^(第[一二三四五六七八九十百千零〇\\d]+\\s*[编章节]|第[一二三四五六七八九十百千零〇\\d]+\\s*部分)(?:\\s+(.*))?$")

    /** 纯文本/网页资料：整份当一「页」，只按标题与段落定位 */
    fun chunk(sourceId: String, text: String, projectId: String = ""): List<SourceChunk> =
        chunkPages(
            sourceId = sourceId,
            pages = listOf(SourcePage(pageNumber = 0, text = text)),
            projectId = projectId,
        )

    /**
     * 按页切分。[SourcePage.pageNumber] 为 0 表示「这份资料没有页码概念」，
     * 产出的片段 pageNumber 为 null，而不是假装它在第 0 页。
     *
     * 章节栈跨页延续：PDF 里一节内容横跨三页是常态，
     * 每页从零开始认标题会让第 2、3 页的片段丢掉所属章节。
     */
    fun chunkPages(
        sourceId: String,
        pages: List<SourcePage>,
        projectId: String = "",
    ): List<SourceChunk> {
        val chunks = mutableListOf<SourceChunk>()
        // 章节栈：下标即层级，跨页保留
        val sections = arrayOfNulls<String>(MAX_SECTION_DEPTH)
        var ordinal = 0
        var paragraphCounter = 0

        for (page in pages) {
            val paragraphs = page.text.split(Regex("\\r?\\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (paragraphs.isEmpty()) continue

            val body = StringBuilder()
            // 缓冲区里第一段的全局段落序号与它所属的章节路径——
            // 片段的定位说的是它**开头**在哪，不是结尾在哪
            var bufferStart = paragraphCounter
            var bufferSection: String? = sectionPath(sections)
            // 缓冲区里除了标题还有没有正文。连续几行标题不该被切成一个只有标题的片段
            var bodyHasContent = false

            fun add(text: String, startParagraph: Int, section: String?) {
                chunks += SourceChunk(
                    id = UUID.randomUUID().toString(),
                    sourceId = sourceId,
                    projectId = projectId,
                    ordinal = ordinal++,
                    text = text,
                    pageNumber = page.pageNumber.takeIf { it > 0 },
                    sectionPath = section,
                    paragraphIndex = startParagraph,
                )
            }

            fun flush() {
                if (body.isNotEmpty()) {
                    add(body.toString(), bufferStart, bufferSection)
                    body.clear()
                }
                bodyHasContent = false
            }

            for (paragraph in paragraphs) {
                val heading = parseHeading(paragraph)
                if (heading != null) {
                    // 标题之前积累的正文属于上一节，先落地；
                    // 但缓冲区里只有标题时不落地——那会切出一个只有标题的片段
                    if (bodyHasContent) flush()
                    pushSection(sections, heading.level, heading.title)
                    // 标题连着标题：整个缓冲区归入最新的那条路径
                    if (!bodyHasContent) bufferSection = sectionPath(sections)
                }
                if (body.isEmpty()) {
                    bufferStart = paragraphCounter
                    bufferSection = sectionPath(sections)
                }
                if (paragraph.length > MAX_CHUNK_LENGTH) {
                    // 超长段落单独切开，每一片都记同一个段落序号：它们确实都在这一段里
                    flush()
                    val start = paragraphCounter
                    val section = sectionPath(sections)
                    paragraph.chunked(MAX_CHUNK_LENGTH).forEach { part -> add(part, start, section) }
                } else {
                    if (body.length + paragraph.length + 1 > MAX_CHUNK_LENGTH) {
                        flush()
                        bufferStart = paragraphCounter
                        bufferSection = sectionPath(sections)
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(paragraph)
                    if (heading == null) bodyHasContent = true
                }
                paragraphCounter += 1
            }
            flush()
        }
        return chunks
    }

    private class Heading(val level: Int, val title: String)

    private fun parseHeading(paragraph: String): Heading? {
        MARKDOWN_HEADING.matchEntire(paragraph)?.let { match ->
            val level = match.groupValues[1].length
            val title = match.groupValues[2].trim()
            return if (title.isEmpty()) null else Heading(level, title)
        }
        CHINESE_HEADING.matchEntire(paragraph)?.let { match ->
            val marker = match.groupValues[1].replace(" ", "")
            val rest = match.groupValues[2].trim()
            // 「第三章」「第二节」「第一编」各占一级，编 < 章 < 节
            val level = when {
                marker.endsWith("编") || marker.endsWith("部分") -> 1
                marker.endsWith("章") -> 2
                else -> 3
            }
            val title = if (rest.isEmpty()) marker else "$marker $rest"
            // 太长的一行不是标题，是正文里正好以「第三章」开头的句子
            return if (title.length > MAX_HEADING_LENGTH) null else Heading(level, title)
        }
        return null
    }

    /** 进一级标题就清掉它下面所有更深的层级，否则上一节的小标题会挂在新节下面 */
    private fun pushSection(sections: Array<String?>, level: Int, title: String) {
        val index = (level - 1).coerceIn(0, MAX_SECTION_DEPTH - 1)
        sections[index] = title
        for (deeper in index + 1 until MAX_SECTION_DEPTH) sections[deeper] = null
    }

    private fun sectionPath(sections: Array<String?>): String? =
        sections.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" > ")

    /** 一行超过这个长度就当正文看，不当标题 */
    private const val MAX_HEADING_LENGTH = 60
}
