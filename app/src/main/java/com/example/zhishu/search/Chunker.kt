package com.example.zhishu.search

import com.example.zhishu.domain.model.SourceChunk
import java.util.UUID

object Chunker {
    private const val MAX_CHUNK_LENGTH = 2000

    fun chunk(sourceId: String, text: String, projectId: String = ""): List<SourceChunk> {
        val paragraphs = text.split(Regex("\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()

        val chunks = mutableListOf<SourceChunk>()
        var current = StringBuilder()
        var ordinal = 0

        fun add(body: String) {
            chunks.add(
                SourceChunk(
                    id = UUID.randomUUID().toString(),
                    sourceId = sourceId,
                    projectId = projectId,
                    ordinal = ordinal++,
                    text = body,
                    locator = null,
                ),
            )
        }

        fun flush() {
            if (current.isNotEmpty()) {
                add(current.toString())
                current = StringBuilder()
            }
        }

        for (paragraph in paragraphs) {
            if (paragraph.length > MAX_CHUNK_LENGTH) {
                flush()
                paragraph.chunked(MAX_CHUNK_LENGTH).forEach { part -> add(part) }
            } else {
                if (current.length + paragraph.length + 1 > MAX_CHUNK_LENGTH) flush()
                if (current.isNotEmpty()) current.append('\n')
                current.append(paragraph)
            }
        }
        flush()
        return chunks
    }
}
