package com.example.zhishu.ai.outputs

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 章节因果契约的落库/读库编解码（第二轮批次 A.1）。
 *
 * 空串承载三种语义，运行时必须区分，不能混成一件事：
 * - [None]：老数据，从未生成契约（chapterType 也为空）——显示「未生成因果契约」
 * - [MissingAfterType]：声明了章节类型但没有契约——生成异常或数据被改坏，
 *   理论上会被规划校验拦截，但读库时仍要如实显示「契约不可用」
 * - [Corrupt]：非空但无法反序列化——数据损坏，**绝不能回退成看似正常的空契约**
 *
 * [Valid] 是唯一正常状态。
 */
object ContractCodec {

    sealed interface State {
        /** 契约完整可用 */
        data class Valid(val contract: ChapterContractDto) : State

        /** 老数据/从未生成：空串且无章节类型 */
        data object None : State

        /** 声明了章节类型但契约缺失（生成异常） */
        data object MissingAfterType : State

        /** 契约数据损坏，无法反序列化 */
        data object Corrupt : State
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(contract: ChapterContractDto): String =
        if (contract.isEmpty) "" else json.encodeToString(ChapterContractDto.serializer(), contract)

    /**
     * 从库里的 [contractJson] 与章节类型还原契约状态。
     * [jsonStr] 非空但解析失败 → [Corrupt]，不回退为空契约。
     */
    fun decode(jsonStr: String, chapterType: String): State {
        if (jsonStr.isBlank()) {
            return if (chapterType.isBlank()) State.None else State.MissingAfterType
        }
        return runCatching {
            json.decodeFromString(ChapterContractDto.serializer(), jsonStr)
        }.fold(
            onSuccess = { State.Valid(it) },
            onFailure = { State.Corrupt },
        )
    }

    /** 面向用户/模型的契约描述（正文 prompt 与核验计划摘要共用） */
    fun describe(state: State): String = when (state) {
        is State.Valid -> formatContract(state.contract)
        State.None -> "（本章未生成因果契约，按章节类型的最低要求写作即可）"
        State.MissingAfterType -> "（契约不可用：本章声明了类型但契约缺失，请按通用因果顺序写作）"
        State.Corrupt -> "（契约不可用：契约数据损坏，请重新规划本章后重试）"
    }

    private fun formatContract(contract: ChapterContractDto): String {
        if (contract.isEmpty) return "（本章未生成因果契约，按章节类型的最低要求写作即可）"
        return buildString {
            contract.entryQuestion.takeIf { it.isNotBlank() }?.let { appendLine("- 开头问题：$it") }
            contract.priorIntuition.takeIf { it.isNotBlank() }?.let { appendLine("- 原有直觉/旧方法：$it") }
            contract.obstacle.takeIf { it.isNotBlank() }?.let { appendLine("- 遇到的阻碍：$it") }
            contract.knowledgeGap.takeIf { it.isNotBlank() }?.let { appendLine("- 需要的新解释：$it") }
            if (contract.introducedNodeTitles.isNotEmpty()) {
                appendLine("- 新引入：${contract.introducedNodeTitles.joinToString("、")}")
            }
            if (contract.appliedNodeTitles.isNotEmpty()) {
                appendLine("- 应用：${contract.appliedNodeTitles.joinToString("、")}")
            }
            if (contract.requiredBeats.isNotEmpty()) {
                appendLine("- 必须走到的拍子：${contract.requiredBeats.joinToString(" → ")}")
            }
            contract.resultingChange.takeIf { it.isNotBlank() }?.let { appendLine("- 可观察变化：$it") }
            contract.bridgeToNext.takeIf { it.isNotBlank() }?.let { appendLine("- 留给下一章的问题：$it") }
        }.trim()
    }
}
