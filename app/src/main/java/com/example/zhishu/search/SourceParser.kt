package com.example.zhishu.search

import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind

sealed class SourceInput {
    data class Text(
        val title: String,
        val text: String,
        val kind: SourceKind = SourceKind.TEXT,
    ) : SourceInput()

    data class FileBytes(
        val title: String,
        val fileName: String,
        val bytes: ByteArray,
        val kind: SourceKind,
    ) : SourceInput()

    data class Url(
        val url: String,
        val title: String? = null,
    ) : SourceInput()
}

data class ParseResult(
    val source: SourceDocument,
    val chunks: List<SourceChunk>,
)

interface SourceParser {
    suspend fun parse(
        projectId: String,
        input: SourceInput,
        fetchedAt: Long = System.currentTimeMillis(),
    ): ParseResult
}
