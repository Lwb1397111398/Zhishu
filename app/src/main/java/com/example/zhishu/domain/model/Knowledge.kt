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
    /**
     * 稳定标识（批次化生成）。骨架落库时由程序分配，单链展开时模型必须原样回传。
     * 标题只是显示内容，不能当身份——模型改一个空格/标点/同义词，标题就匹配失败了。
     */
    @ColumnInfo(defaultValue = "")
    val nodeKey: String = "",
    /** 生成状态（批次化生成）。旧数据默认 COMPLETED，不因状态缺失而误判为待展开 */
    @ColumnInfo(defaultValue = "COMPLETED")
    val generateStatus: String = NodeGenerateStatus.COMPLETED.name,
    /**
     * 所属生成批次（批次化生成 v14）。
     *
     * 重新生成时新批次的节点先落库但不生效（旧地图继续可见），
     * 整批成功后原子切换 active 批次——查询按此列过滤，旧批次节点随之隐藏。
     * 空串 = 迁移前的旧数据（无批次归属），视为当前有效地图。
     */
    @ColumnInfo(defaultValue = "")
    val batchId: String = "",
)

/** 节点在分批生成中的生命周期状态（持久化到 knowledge_nodes.generateStatus） */
enum class NodeGenerateStatus {
    /** 骨架已落库，还没展开内容 */
    PLACEHOLDER,
    /** 正在由某条链展开（进行中） */
    GENERATING,
    /** 内容已完整展开 */
    COMPLETED,
    /** 展开失败（重试后仍失败，UI 显示失败态，可单独重试） */
    FAILED,
}

/**
 * 知识地图的批次生成状态（批次化生成）。
 *
 * 一个项目同一时间只有一个「当前批次」；重新生成会开一个新批次。
 * 骨架、每条链、最终审计各自落库，失败批次不会混入已完成地图。
 * 这是过程状态，不随备份导出（恢复后视为无批次，重新生成即可）。
 */
@Serializable
@Entity(
    tableName = "knowledge_map_batches",
    indices = [Index("projectId")],
)
data class KnowledgeMapBatch(
    @PrimaryKey val id: String,
    val projectId: String,
    /** 骨架是否已完成（chains 已落库、占位节点已建） */
    val skeletonDone: Boolean = false,
    /** 骨架阶段的演进链结构，JSON 数组：[{theme, opening, nodeTitles:[]}] */
    val chainsJson: String = "[]",
    /** 每条链的完成状态，与链条顺序一致。枚举见 [KnowledgeMapChainStatus] */
    val chainStatuses: String = "[]",
    /** 骨架阶段的 gaps/conflicts 预览（最终审计通过后会整体替换） */
    val previewGaps: String = "[]",
    val previewConflicts: String = "[]",
    /** 全局审计（最终 gaps/conflicts）是否完成 */
    val auditDone: Boolean = false,
    /** 审计是否失败。审计失败不推翻已展开节点，但项目不得误报为全部完成 */
    val auditFailed: Boolean = false,
    /** 整个批次是否成功收尾（项目状态置 MAP） */
    val completed: Boolean = false,
    /** 整个批次是否已失败（不再继续） */
    val failed: Boolean = false,
    /** 是否当前生效批次（v14）。查询只显示 active 批次的节点；旧地图在切换前保持 active */
    val active: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 一条演进链的展开状态（持久化在 [KnowledgeMapBatch.chainStatuses] 的 JSON 数组里） */
enum class KnowledgeMapChainStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
}

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
