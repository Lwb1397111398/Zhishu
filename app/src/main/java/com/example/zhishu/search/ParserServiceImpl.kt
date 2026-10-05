package com.example.zhishu.search

import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 资料解析。PDF 抽取、文本解码和分块都是 CPU/IO 密集操作，
 * 统一在 [ioDispatcher] 上执行，避免从 viewModelScope 调用时冻结 UI（N4）。
 */
class ParserServiceImpl(
    private val webFetcher: WebFetcher = JsoupWebFetcher(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SourceParser {

    override suspend fun parse(
        projectId: String,
        input: SourceInput,
        fetchedAt: Long,
    ): ParseResult {
        // URL 抓取自身已切 IO，这里再包一层保证文件解析与分块也不在主线程
        val (source, pages) = when (input) {
            is SourceInput.Url -> {
                val page = webFetcher.fetch(input.url)
                SourceDocument(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    title = input.title ?: page.title,
                    kind = SourceKind.URL,
                    url = input.url,
                    fetchedAt = fetchedAt,
                    versionLabel = null,
                    contentHash = HashUtils.sha256(page.text),
                    parseStatus = if (page.text.isBlank()) ParseStatus.FAILED else ParseStatus.PARSED,
                ) to onePage(page.text)
            }

            is SourceInput.Text -> withContext(ioDispatcher) {
                SourceDocument(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    title = input.title,
                    kind = input.kind,
                    fetchedAt = fetchedAt,
                    contentHash = HashUtils.sha256(input.text),
                    parseStatus = ParseStatus.PARSED,
                ) to onePage(input.text)
            }

            is SourceInput.FileBytes -> withContext(ioDispatcher) {
                parseFile(projectId, input, fetchedAt)
            }
        }

        if (pages.all { it.text.isBlank() }) {
            val status = if (source.kind == SourceKind.PDF) ParseStatus.SCAN_ONLY else ParseStatus.FAILED
            return ParseResult(source.copy(parseStatus = status), emptyList())
        }
        val chunks = withContext(ioDispatcher) {
            Chunker.chunkPages(sourceId = source.id, pages = pages, projectId = projectId)
        }
        return ParseResult(source, chunks)
    }

    /** 没有页码概念的资料（纯文本、Markdown、网页）：整份算一「页」，页码留空 */
    private fun onePage(text: String): List<SourcePage> = listOf(SourcePage(pageNumber = 0, text = text))

    private fun parseFile(
        projectId: String,
        input: SourceInput.FileBytes,
        fetchedAt: Long,
    ): Pair<SourceDocument, List<SourcePage>> {
        return when (input.kind) {
            SourceKind.PDF -> {
                val pages = extractPdfPages(input.bytes)
                val totalLength = pages.sumOf { it.text.length }
                val status = if (totalLength < MIN_PDF_TEXT_LENGTH) {
                    ParseStatus.SCAN_ONLY
                } else {
                    ParseStatus.PARSED
                }
                SourceDocument(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    title = input.title,
                    kind = SourceKind.PDF,
                    fileName = input.fileName,
                    fetchedAt = fetchedAt,
                    contentHash = HashUtils.sha256(input.bytes),
                    parseStatus = status,
                ) to pages
            }

            else -> {
                val text = decodeText(input.bytes)
                SourceDocument(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    title = input.title,
                    kind = input.kind,
                    fileName = input.fileName,
                    fetchedAt = fetchedAt,
                    contentHash = HashUtils.sha256(input.bytes),
                    parseStatus = if (text.isBlank()) ParseStatus.FAILED else ParseStatus.PARSED,
                ) to onePage(text)
            }
        }
    }

    /**
     * 文本解码：先按 UTF-8 严格解码（坏字节直接报错而不是吞成替换符），
     * 失败再回退 GB18030。网上下载的中文资料很多是 GBK/GB18030 编码，
     * `String(bytes, UTF_8)` 的默认替换解码会把整篇文字变成 U+FFFD 乱码，
     * 用户只会看到「导入成功了但全是问号」——宁可明确走兜底路径。
     */
    private fun decodeText(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }

    /**
     * 逐页抽取 PDF 文本（4A）。
     *
     * 原来是一次 `getText(document)` 拿到整本的文本，页码信息在那一步就被抹掉了——
     * 于是引用只能说「出自这份 PDF」，用户拿着一份三百页的资料无从核对。
     * 现在按页抽，页码直接跟着片段走。
     */
    private fun extractPdfPages(bytes: ByteArray): List<SourcePage> {
        return try {
            PDDocument.load(bytes).use { document ->
                val stripper = PDFTextStripper()
                (1..document.numberOfPages).map { page ->
                    stripper.startPage = page
                    stripper.endPage = page
                    SourcePage(pageNumber = page, text = stripper.getText(document))
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        /** 抽出来的文字少于这个数就当扫描件——排版空白比正文还多的 PDF 引用不了 */
        const val MIN_PDF_TEXT_LENGTH = 20
    }
}
