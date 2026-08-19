package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * 章节正文的全文索引（P2）。
 *
 * 独立 FTS 表而非 contentEntity：索引里存的是 [com.example.zhishu.search.SearchTokenizer]
 * 生成的字符二元组串，与原文不同，由 DAO 在写入正文时同步维护。
 */
@Fts4
@Entity(tableName = "chapter_block_fts")
data class ChapterBlockFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val blockId: String,
    val projectId: String,
    val body: String,
)

/** 资料片段的全文索引（P2） */
@Fts4
@Entity(tableName = "source_chunk_fts")
data class SourceChunkFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val chunkId: String,
    val projectId: String,
    val body: String,
)
