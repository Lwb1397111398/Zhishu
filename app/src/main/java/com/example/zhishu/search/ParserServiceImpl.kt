package com.example.zhishu.search

import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
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
        val (source, rawText) = when (input) {
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
                ) to page.text
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
                ) to input.text
            }

            is SourceInput.FileBytes -> withContext(ioDispatcher) {
                parseFile(projectId, input, fetchedAt)
            }
        }

        if (rawText.isBlank()) {
            val status = if (source.kind == SourceKind.PDF) ParseStatus.SCAN_ONLY else ParseStatus.FAILED
            return ParseResult(source.copy(parseStatus = status), emptyList())
        }
        val chunks = withContext(ioDispatcher) {
            Chunker.chunk(sourceId = source.id, text = rawText, projectId = projectId)
        }
        return ParseResult(source, chunks)
    }

    private fun parseFile(
        projectId: String,
        input: SourceInput.FileBytes,
        fetchedAt: Long,
    ): Pair<SourceDocument, String> {
        return when (input.kind) {
            SourceKind.PDF -> {
                val text = extractPdfText(input.bytes)
                val status = if (text.length < 20) ParseStatus.SCAN_ONLY else ParseStatus.PARSED
                SourceDocument(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    title = input.title,
                    kind = SourceKind.PDF,
                    fileName = input.fileName,
                    fetchedAt = fetchedAt,
                    contentHash = HashUtils.sha256(input.bytes),
                    parseStatus = status,
                ) to text
            }

            else -> {
                val text = String(input.bytes, Charsets.UTF_8)
                SourceDocument(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    title = input.title,
                    kind = input.kind,
                    fileName = input.fileName,
                    fetchedAt = fetchedAt,
                    contentHash = HashUtils.sha256(input.bytes),
                    parseStatus = if (text.isBlank()) ParseStatus.FAILED else ParseStatus.PARSED,
                ) to text
            }
        }
    }

    private fun extractPdfText(bytes: ByteArray): String {
        return try {
            PDDocument.load(bytes).use { document ->
                PDFTextStripper().getText(document)
            }
        } catch (e: Exception) {
            ""
        }
    }
}
