package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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

/**
 * 知识缺口的类型（gaps 闭环）。
 *
 * 原来所有 gap 混成一个纯文本列表，「地区差异提醒」和「骨架漏了知识点」
 * 语义完全不同却同样式展示——前者是免责提示（不该驱动改地图），
 * 后者才该出现「补上这个知识点」按钮。
 */
enum class GapType {
    /** 骨架漏掉 / 单链展开被丢弃的知识点——可行动，驱动 PATCH 补节点 */
    MISSING_NODE,

    /** 内容可能过时——免责提示 */
    OUTDATED,

    /** 存在地区/版本差异——免责提示 */
    REGIONAL,

    /** 各家说法不一——免责提示 */
    CONTESTED,

    /** 无法核实——免责提示 */
    UNVERIFIED,

    /** 旧数据 / 模型未声明类型 */
    UNSPECIFIED,
    ;
    companion object {
        /** 宽松映射：未知/空值一律 UNSPECIFIED，防模型输出变体炸掉解析 */
        fun parse(raw: String?): GapType = entries.firstOrNull { it.name == raw?.trim()?.uppercase() } ?: UNSPECIFIED
    }
}

/** gap 的处理状态。UI 默认只显示 OPEN——已补上的、用户已知晓的不再出现，避免「看起来没处理」的混淆 */
enum class GapStatus {
    /** 未处理：显示在「别只信AI」区域，按类型给按钮 */
    OPEN,

    /** 已通过 PATCH 补上知识点，不再显示 */
    RESOLVED,

    /** 用户点「知道了」关闭（免责提示型 / 暂不处理），不再显示 */
    DISMISSED,
}

/** 知识地图生成时模型报告的资料缺口（B12；gaps 闭环加结构化字段） */
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
    /** 缺口类型。空串 = 旧数据（读取时按 [GapType] 宽松映射为 UNSPECIFIED） */
    @ColumnInfo(defaultValue = "")
    val gapType: String = "",
    /** 处理状态。空串 = 旧数据，读取时视为 OPEN（保持既有可见性，不凭空消失） */
    @ColumnInfo(defaultValue = "")
    val status: String = "",
    /** 缺口挂靠的节点 ID（可为空：模型可能只给出主题级缺口） */
    @ColumnInfo(defaultValue = "")
    val anchorNodeId: String = "",
    /** 建议补充的知识点标题（MISSING_NODE 时模型给出） */
    @ColumnInfo(defaultValue = "")
    val suggestedTitle: String = "",
) {
    /** 解析后的类型。空串/未知值 → UNSPECIFIED */
    val type: GapType get() = GapType.parse(gapType)

    /** 解析后的状态。空串（旧数据）→ OPEN，与改造前行为一致 */
    val gapStatus: GapStatus get() = if (status.isBlank()) GapStatus.OPEN else GapStatus.entries.firstOrNull { it.name == status } ?: GapStatus.OPEN

    /** 是否是「可以驱动补节点」的可行动缺口 */
    val actionable: Boolean get() = type == GapType.MISSING_NODE
}

/** 知识地图生成时模型报告的资料冲突（B12）。与 gap 同样支持「知道了」关闭 */
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
    /** 处理状态。空串 = 旧数据，读取时视为 OPEN */
    @ColumnInfo(defaultValue = "")
    val status: String = "",
) {
    /** 解析后的状态。空串（旧数据）→ OPEN */
    val conflictStatus: GapStatus get() = if (status.isBlank()) GapStatus.OPEN else GapStatus.entries.firstOrNull { it.name == status } ?: GapStatus.OPEN
}

/**
 * 解码批次里逐链展开状态（与链条顺序一致）。
 *
 * 管线与 UI 共用：管线靠它跳过已完成的链，UI 靠它显示「几条链已展开 / 几条失败」。
 * 存的是 JSON 字符串数组；解析失败按「无状态」处理（全部视为待展开，宁可重跑也不误跳）。
 */
fun KnowledgeMapBatch.chainStatusList(): List<KnowledgeMapChainStatus> = runCatching {
    if (chainStatuses.isBlank() || chainStatuses == "[]") {
        emptyList()
    } else {
        Json.decodeFromString(ListSerializer(String.serializer()), chainStatuses)
            .mapNotNull { name -> KnowledgeMapChainStatus.entries.firstOrNull { it.name == name } }
    }
}.getOrDefault(emptyList())

/** 把链状态编码回批次存储的 JSON 字符串（与 [chainStatusList] 互逆） */
fun List<KnowledgeMapChainStatus>.encodeStatuses(): String =
    Json.encodeToString(ListSerializer(String.serializer()), map { it.name })
