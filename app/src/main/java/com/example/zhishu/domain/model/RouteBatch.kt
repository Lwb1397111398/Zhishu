package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 讲述路线的批次生成状态（三段式改造）。
 *
 * 讲述路线从「一次调用出整卷」拆成分卷（纯程序）→ 大纲（每卷 1 次调用）→
 * 契约（每章 1 次调用）三段。一次成型时任何一章不合规就整卷作废；
 * 拆开后一章失败只影响一章，重试只补没做完的——断点就记在这一行上。
 *
 * 与 [KnowledgeMapBatch] 同构：一个项目同一时间只有一个进行中的路线批次，
 * 重新规划开新批次。这是过程状态，不随备份导出。
 */
@Serializable
@Entity(
    tableName = "route_batches",
    indices = [Index("projectId")],
)
data class RouteBatch(
    @PrimaryKey val id: String,
    val projectId: String,
    /** 本批次规划的是第几卷（从 0 开始） */
    @ColumnInfo(defaultValue = "0")
    val volumeIndex: Int = 0,
    /**
     * 大纲阶段的原始输出（volumeTitle / volumeGoal / 各章大纲 JSON）。
     *
     * 断点续跑时要靠它重算 introducedBy、重建契约调用的输入——
     * 只靠 chapters 表反推会丢 volumeTitle 这类只落进 StoryBible 的字段。
     */
    @ColumnInfo(defaultValue = "")
    val outlineJson: String = "",
    /** 每章契约的完成状态，与章节顺序一致。枚举见 [ContractStatus] */
    @ColumnInfo(defaultValue = "[]")
    val contractStatuses: String = "[]",
    /**
     * 大纲阶段程序推导的「知识点首次出现在第几章」缓存（nodeId → order）。
     * 可从已落库的 chapters.nodeIds 重算，这里只是加速，不作为唯一来源。
     */
    @ColumnInfo(defaultValue = "[]")
    val introducedKeysJson: String = "[]",
    /** 大纲是否已完成（章节计划已落库，契约还没开始或没做完） */
    @ColumnInfo(defaultValue = "0")
    val outlineDone: Boolean = false,
    /** 全部契约是否完成（失败章清零后置真） */
    @ColumnInfo(defaultValue = "0")
    val contractsDone: Boolean = false,
    /** 契约失败的章数。重试只补这些章 */
    @ColumnInfo(defaultValue = "0")
    val failedChapterCount: Int = 0,
    /** 整个批次成功收尾 */
    @ColumnInfo(defaultValue = "0")
    val completed: Boolean = false,
    /** 整个批次已失败（不再继续） */
    @ColumnInfo(defaultValue = "0")
    val failed: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 一章契约在三段式生成中的状态（持久化在 [RouteBatch.contractStatuses] 与 chapters.contractStatus） */
enum class ContractStatus {
    /** 大纲已定，契约还没生成 */
    PENDING,

    /** 契约已生成并通过校验 */
    DONE,

    /** 契约生成失败（重试后仍失败）。该章禁止写正文，单独重试 */
    FAILED,
}

/**
 * 解码批次里每章契约状态（与章节顺序一致）。
 * 解析失败按「无状态」处理（全部视为待生成，宁可重跑也不误跳）。
 */
fun RouteBatch.contractStatusList(): List<ContractStatus> = runCatching {
    if (contractStatuses.isBlank() || contractStatuses == "[]") {
        emptyList()
    } else {
        Json.decodeFromString(ListSerializer(String.serializer()), contractStatuses)
            .mapNotNull { name -> ContractStatus.entries.firstOrNull { it.name == name } }
    }
}.getOrDefault(emptyList())

/**
 * 把契约状态编码回批次存储的 JSON 字符串（与 [contractStatusList] 互逆）。
 *
 * 名字不与 `Knowledge.kt` 里的 `encodeStatuses` 撞车——两个扩展函数都挂在
 * `List<*>` 上，同名会让调用方（PipelineService 同时用到两者）出现重载歧义。
 */
fun List<ContractStatus>.encodeContractStatuses(): String =
    Json.encodeToString(ListSerializer(String.serializer()), map { it.name })
