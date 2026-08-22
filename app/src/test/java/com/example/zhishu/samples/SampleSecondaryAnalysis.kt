package com.example.zhishu.samples

import com.example.zhishu.ai.outputs.Evidence
import kotlinx.serialization.Serializable

/**
 * 批次 B 二次分析（B.0-4 + contractChecks 升级）：契约声明 vs 正文证据的双轨比对 + RouteAudit 软指标。
 *
 * ## 判定规则（不搜索节点标题，以核验结果为正文证据）
 *
 * 应用证据 [ApplicationEvidence]，**优先使用结构化 checks**（核验对契约声明的节点逐项给出证据）：
 * - 有 checks 覆盖本章声明节点时：
 *   - 任一声明节点 [Evidence.ABSENT] → [ApplicationEvidence.DECLARED_NOT_APPLIED]（确证缺失）
 *   - 否则任一 [Evidence.PARTIAL] → [ApplicationEvidence.DECLARED_PARTIALLY_APPLIED]（有涉及没真正用起来）
 *   - 否则任一 [Evidence.UNKNOWN] → [ApplicationEvidence.UNKNOWN]（核验无法判断，不误报）
 *   - 否则（全 PRESENT）→ [ApplicationEvidence.APPLIED_WITH_EVIDENCE]（**真证据**，仅 PRESENT 可判）
 * - checks 缺失（旧模型/旧 Prompt）→ **退回 issues 推断**：
 *   - 声明 + 未报 no_application → [ApplicationEvidence.DECLARED_AND_NOT_DISPROVED]（未发现否定证据，不是确认）
 *   - 声明 + 报 no_application → [ApplicationEvidence.DECLARED_NOT_APPLIED]
 *   - 核验不可用 → [ApplicationEvidence.UNKNOWN]
 * - 契约未声明 → [ApplicationEvidence.NO_DECLARATION]；
 *   「正文出现应用但契约未声明」无法自动判定，[AggregateOutcome.appliedButNotDeclared] 恒为
 *   **UNMEASURED**（不搜索节点标题，绝不猜测）。
 *
 * 结果证据 [ResultEvidence]：有 claim=RESULT 的 checks 时用其证据（PRESENT→HAS_RESULT、ABSENT→ACTION_NO_RESULT、
 * PARTIAL/UNKNOWN→UNKNOWN）；否则退回 issues 推断（missing_result）。
 */
internal object SampleSecondaryAnalysis {

    @Serializable
    data class ChapterInput(
        val subjectId: String,
        val variant: String,
        val chapterNo: Int,
        val declaredApplied: Set<String> = emptySet(),
        val declaredIntroduced: Set<String> = emptySet(),
        val verifyIssueTypes: List<String> = emptyList(),
        val verifyAvailable: Boolean = true,
        /** 结构化 contractChecks（新模型输出；空 = 旧模型，退回 issues 推断） */
        val checks: List<CheckInput> = emptyList(),
    )

    @Serializable
    data class CheckInput(
        val nodeTitle: String,
        val claim: String = "APPLIED",
        val evidence: String = "UNKNOWN",
        val evidenceBlockIds: List<String> = emptyList(),
    )

    @Serializable
    data class RouteIssueInput(
        val code: String,
        val chapterOrder: Int? = null,
    )

    @Serializable
    enum class ApplicationEvidence {
        /** 仅当 checks 全 PRESENT 时判定——正文应用有结构化证据（批次 B 后续升级） */
        APPLIED_WITH_EVIDENCE,
        /** 声明应用但核验未发现否定证据（issues 推断回退）——不是「已确认完成应用」 */
        DECLARED_AND_NOT_DISPROVED,
        /** checks 显示有涉及但没真正用起来 */
        DECLARED_PARTIALLY_APPLIED,
        DECLARED_NOT_APPLIED,
        NO_DECLARATION,
        UNKNOWN,
    }

    @Serializable
    enum class ResultEvidence { HAS_RESULT, ACTION_NO_RESULT, UNKNOWN }

    @Serializable
    data class ChapterOutcome(
        val subjectId: String,
        val variant: String,
        val chapterNo: Int,
        val application: ApplicationEvidence,
        val result: ResultEvidence,
        val derivationBroken: Boolean,
    )

    @Serializable
    data class AggregateOutcome(
        val totalChapters: Int,
        val declaredAppliedChapters: Int,
        /** 声明应用且核验未发现缺失（issues 推断：假定证据） */
        val appliedWithEvidence: Int,
        /** 声明应用但核验报 no_application 或 checks 全 ABSENT（确证缺失） */
        val declaredButNotApplied: Int,
        /** checks 显示部分应用（PARTIAL）的章节数 */
        val partiallyApplied: Int,
        /** 正文出现应用但契约未声明：无法可靠判定（不搜索节点标题），恒为 UNMEASURED，待人工核对 */
        val appliedButNotDeclared: String = "UNMEASURED",
        val actionWithoutResult: Int,
        val evidenceUnknownCount: Int,
        val routePrerequisiteViolations: Int,
        val routeUnresolvedPrerequisite: Int,
        val routeDuplicateIntroduce: Int,
        val routeRangeGap: Int,
        val routeOverload: Int,
        val routeChainDiscontinuity: Int,
        /** 存在应用行为章节占比（声明口径：declaredAppliedChapters / totalChapters） */
        val appliedRatio: Double,
        /** 应用后有可观察结果占比（结果证据明确且有结果的章节 / 声明应用章节） */
        val resultRatio: Double,
    )

    fun analyze(
        chapters: List<ChapterInput>,
        routeIssues: List<RouteIssueInput> = emptyList(),
    ): AggregateOutcome {
        val outcomes = chapters.map { c ->
            ChapterOutcome(
                subjectId = c.subjectId,
                variant = c.variant,
                chapterNo = c.chapterNo,
                application = resolveApplication(c),
                result = resolveResult(c),
                derivationBroken = "broken_derivation" in c.verifyIssueTypes,
            )
        }

        val declaredApplied = outcomes.count { it.application != ApplicationEvidence.NO_DECLARATION }
        val appliedWithEvidence = outcomes.count {
            it.application == ApplicationEvidence.APPLIED_WITH_EVIDENCE ||
                it.application == ApplicationEvidence.DECLARED_AND_NOT_DISPROVED
        }
        val declaredButNotApplied = outcomes.count { it.application == ApplicationEvidence.DECLARED_NOT_APPLIED }
        val partiallyApplied = outcomes.count { it.application == ApplicationEvidence.DECLARED_PARTIALLY_APPLIED }
        val actionWithoutResult = outcomes.count { it.result == ResultEvidence.ACTION_NO_RESULT }
        val evidenceUnknown = outcomes.count {
            it.application == ApplicationEvidence.UNKNOWN || it.result == ResultEvidence.UNKNOWN
        }

        return AggregateOutcome(
            totalChapters = outcomes.size,
            declaredAppliedChapters = declaredApplied,
            appliedWithEvidence = appliedWithEvidence,
            declaredButNotApplied = declaredButNotApplied,
            partiallyApplied = partiallyApplied,
            appliedButNotDeclared = "UNMEASURED", // 不自动判定：正文应用但契约未声明需要人工核对
            actionWithoutResult = actionWithoutResult,
            evidenceUnknownCount = evidenceUnknown,
            routePrerequisiteViolations = routeIssues.count {
                it.code == "PREREQUISITE_ORDER" || it.code == "PREREQUISITE_SELF_REF"
            },
            routeUnresolvedPrerequisite = routeIssues.count { it.code == "PREREQUISITE_UNRESOLVED" },
            routeDuplicateIntroduce = routeIssues.count { it.code == "DUPLICATE_INTRODUCE" },
            routeRangeGap = routeIssues.count { it.code == "RANGE_GAP" },
            routeOverload = routeIssues.count { it.code == "LOAD_EXCEEDS_TARGET" },
            routeChainDiscontinuity = routeIssues.count { it.code == "CHAIN_DISCONTINUITY" },
            appliedRatio = if (outcomes.isEmpty()) 0.0 else declaredApplied.toDouble() / outcomes.size,
            resultRatio = if (declaredApplied == 0) 0.0
            else (appliedWithEvidence - actionWithoutResult).coerceAtLeast(0).toDouble() / declaredApplied,
        )
    }

    // ---- 判定 ----

    /**
     * 应用证据。优先结构化 checks（仅 PRESENT 可判真证据）；checks 缺失退回 issues 推断。
     */
    private fun resolveApplication(c: ChapterInput): ApplicationEvidence {
        if (c.declaredApplied.isEmpty()) return ApplicationEvidence.NO_DECLARATION
        if (!c.verifyAvailable) return ApplicationEvidence.UNKNOWN
        val checks = checksFor(c.checks, c.declaredApplied, claim = "APPLIED")
        if (checks.isNotEmpty()) {
            return when {
                checks.any { it == Evidence.ABSENT } -> ApplicationEvidence.DECLARED_NOT_APPLIED
                checks.any { it == Evidence.PARTIAL } -> ApplicationEvidence.DECLARED_PARTIALLY_APPLIED
                checks.any { it == Evidence.UNKNOWN } -> ApplicationEvidence.UNKNOWN
                else -> ApplicationEvidence.APPLIED_WITH_EVIDENCE
            }
        }
        // 兼容回退：issues 推断
        return if ("no_application" in c.verifyIssueTypes) {
            ApplicationEvidence.DECLARED_NOT_APPLIED
        } else {
            ApplicationEvidence.DECLARED_AND_NOT_DISPROVED
        }
    }

    /**
     * 结果证据。优先 claim=RESULT 的 checks；缺失退回 issues 推断（missing_result）。
     */
    private fun resolveResult(c: ChapterInput): ResultEvidence {
        if (c.declaredApplied.isEmpty() || !c.verifyAvailable) return ResultEvidence.UNKNOWN
        val resultChecks = checksFor(c.checks, c.declaredApplied, claim = "RESULT")
        if (resultChecks.isNotEmpty()) {
            return when {
                resultChecks.any { it == Evidence.ABSENT } -> ResultEvidence.ACTION_NO_RESULT
                resultChecks.any { it == Evidence.UNKNOWN || it == Evidence.PARTIAL } -> ResultEvidence.UNKNOWN
                else -> ResultEvidence.HAS_RESULT
            }
        }
        return if ("missing_result" in c.verifyIssueTypes) {
            ResultEvidence.ACTION_NO_RESULT
        } else {
            ResultEvidence.HAS_RESULT
        }
    }

    /** 取与声明节点相关的 checks 的 evidence 映射（宽松映射，未知归 UNKNOWN） */
    private fun checksFor(
        checks: List<CheckInput>,
        declared: Set<String>,
        claim: String,
    ): List<Evidence> = checks
        .filter { it.nodeTitle.trim() in declared && it.claim.uppercase() == claim }
        .map { Evidence.parse(it.evidence) }
}
