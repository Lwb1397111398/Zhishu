package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "knowledge_nodes",
    indices = [Index("projectId")],
)
data class KnowledgeNode(
    @PrimaryKey val id: String,
    val projectId: String,
    val parentId: String? = null,
    val title: String,
    val definition: String = "",
    /** 它从哪个前序概念、因为什么缺口才出现——知识地图里展示「来处」（问题2） */
    @ColumnInfo(defaultValue = "")
    val arisesFrom: String = "",
    /** 它自身没解决、因而引出下一个概念的口子——展示「去处」（问题2） */
    @ColumnInfo(defaultValue = "")
    val leadsTo: String = "",
    val prerequisites: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val confusions: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    /** 保存的是 source_chunks.id（片段 ID），不是资料文件 ID（B1/B19） */
    val sourceRefs: List<String> = emptyList(),
    val chapterIds: List<String> = emptyList(),
    val mastery: Float = 0f,
    val confirmed: Boolean = false,
    val depth: Int = 1,
    val order: Int = 0,
)

/** 知识地图生成时模型报告的资料缺口（B12） */
@Serializable
@Entity(
    tableName = "knowledge_gaps",
    indices = [Index("projectId")],
)
data class KnowledgeGap(
    @PrimaryKey val id: String,
    val projectId: String,
    val description: String,
    val order: Int = 0,
)

/** 知识地图生成时模型报告的资料冲突（B12） */
@Serializable
@Entity(
    tableName = "knowledge_conflicts",
    indices = [Index("projectId")],
)
data class KnowledgeConflict(
    @PrimaryKey val id: String,
    val projectId: String,
    val topic: String,
    val sources: List<String> = emptyList(),
    val description: String = "",
    val order: Int = 0,
)
