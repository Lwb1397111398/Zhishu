package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class SourceKind {
    TEXT,
    MARKDOWN,
    PDF,
    URL
}

@Serializable
enum class ParseStatus {
    PENDING,
    PARSED,
    SCAN_ONLY,
    FAILED
}

@Serializable
@Entity(
    tableName = "sources",
    indices = [Index("projectId"), Index("contentHash")],
)
data class SourceDocument(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val kind: SourceKind,
    val fileName: String? = null,
    val url: String? = null,
    val fetchedAt: Long,
    val versionLabel: String? = null,
    val contentHash: String,
    val parseStatus: ParseStatus,
)

@Serializable
@Entity(
    tableName = "source_chunks",
    indices = [Index("sourceId"), Index("projectId")],
)
data class SourceChunk(
    @PrimaryKey val id: String,
    val sourceId: String,
    /** 冗余保存项目 ID，便于按项目直接查询与清理（B1/N6） */
    @ColumnInfo(defaultValue = "") val projectId: String = "",
    val ordinal: Int,
    val text: String,
    val locator: String? = null,
)
