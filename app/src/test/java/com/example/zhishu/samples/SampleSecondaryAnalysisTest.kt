package com.example.zhishu.samples

import com.example.zhishu.samples.SampleSecondaryAnalysis.ApplicationEvidence
import com.example.zhishu.samples.SampleSecondaryAnalysis.ResultEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 B B.0-4：二次分析离线测试（不调模型、不花费用）。
 *
 * 覆盖执行指令要求的判定场景：声明应用+证据存在 / 声明应用+证据不存在 /
 * 未声明应用+正文出现应用（不自动判定，待人工）/ 应用存在但无结果 /
 * 前置倒挂 / 重复首次引入 / 范围缺口 / 章节负荷超标 / 链跨章连续性问题 / 证据不确定态。
 */
class SampleSecondaryAnalysisTest {

    private fun chapter(
        subject: String = "sky",
        variant: String = "new",
        no: Int = 1,
        applied: Set<String> = emptySet(),
        introduced: Set<String> = emptySet(),
        issues: List<String> = emptyList(),
        verifyAvailable: Boolean = true,
        checks: List<SampleSecondaryAnalysis.CheckInput> = emptyList(),
    ) = SampleSecondaryAnalysis.ChapterInput(
        subjectId = subject,
        variant = variant,
        chapterNo = no,
        declaredApplied = applied,
        declaredIntroduced = introduced,
        verifyIssueTypes = issues,
        verifyAvailable = verifyAvailable,
        checks = checks,
    )

    private fun check(
        node: String,
        evidence: String = "PRESENT",
        claim: String = "APPLIED",
    ) = SampleSecondaryAnalysis.CheckInput(nodeTitle = node, claim = claim, evidence = evidence)

    // 1. 声明应用，正文证据存在（核验未报 no_application → 假定证据）
    @Test
    fun declaredApplied_withoutNoApplication_isApplied() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("进程"))),
        )
        assertEquals(1, result.appliedWithEvidence)
        assertEquals(0, result.declaredButNotApplied)
        assertEquals(1.0, result.appliedRatio, 1e-9)
    }

    // 2. 声明应用，正文证据不存在（核验报 no_application → 确证缺失）
    @Test
    fun declaredApplied_withNoApplication_isDeclaredButNotApplied() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("进程"), issues = listOf("no_application"))),
        )
        assertEquals(0, result.appliedWithEvidence)
        assertEquals(1, result.declaredButNotApplied)
    }

    // 3. 未声明应用 + 正文出现应用：不自动判定，绝不猜测
    @Test
    fun undeclaredApplication_isNeverAutoDetected() {
        // 正文其实有应用行为，但契约没声明——没有正文证据自动判据，必须留待人工
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = emptySet(), issues = emptyList())),
        )
        assertEquals("UNMEASURED", result.appliedButNotDeclared)
        assertEquals(ApplicationEvidence.NO_DECLARATION.name, "NO_DECLARATION")
    }

    // 4. 应用存在但行动没有结果（核验报 missing_result）
    @Test
    fun declaredApplied_withMissingResult_hasActionWithoutResult() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("进程"), issues = listOf("missing_result"))),
        )
        assertEquals(1, result.actionWithoutResult)
        assertEquals(ResultEvidence.ACTION_NO_RESULT.name, "ACTION_NO_RESULT")
    }

    // 5. 应用后结果证据（未报 missing_result → 假定有结果）
    @Test
    fun declaredApplied_withoutMissingResult_hasResult() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("进程"))),
        )
        assertEquals(1.0, result.resultRatio, 1e-9)
    }

    // 6. 核验不可用时不得误判为「已应用」——证据不确定
    @Test
    fun declaredApplied_withUnavailableVerify_isUnknown() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("进程"), verifyAvailable = false)),
        )
        assertTrue(result.evidenceUnknownCount >= 1)
        assertEquals(ApplicationEvidence.UNKNOWN.name, "UNKNOWN")
    }

    // 7. 前置倒挂（PREREQUISITE_ORDER / SELF_REF 计数）
    @Test
    fun routeIssues_prerequisiteViolations_counted() {
        val result = SampleSecondaryAnalysis.analyze(
            chapters = listOf(chapter()),
            routeIssues = listOf(
                SampleSecondaryAnalysis.RouteIssueInput("PREREQUISITE_ORDER", 2),
                SampleSecondaryAnalysis.RouteIssueInput("PREREQUISITE_SELF_REF", 1),
                SampleSecondaryAnalysis.RouteIssueInput("PREREQUISITE_UNRESOLVED", 1),
            ),
        )
        assertEquals(2, result.routePrerequisiteViolations)
        assertEquals(1, result.routeUnresolvedPrerequisite)
    }

    // 8. 重复首次引入
    @Test
    fun routeIssues_duplicateIntroduce_counted() {
        val result = SampleSecondaryAnalysis.analyze(
            chapters = listOf(chapter()),
            routeIssues = listOf(
                SampleSecondaryAnalysis.RouteIssueInput("DUPLICATE_INTRODUCE", 1),
                SampleSecondaryAnalysis.RouteIssueInput("DUPLICATE_INTRODUCE", 3),
            ),
        )
        assertEquals(2, result.routeDuplicateIntroduce)
    }

    // 9. 范围缺口（RANGE_GAP）
    @Test
    fun routeIssues_rangeGap_counted() {
        val result = SampleSecondaryAnalysis.analyze(
            chapters = listOf(chapter()),
            routeIssues = listOf(SampleSecondaryAnalysis.RouteIssueInput("RANGE_GAP", null)),
        )
        assertEquals(1, result.routeRangeGap)
    }

    // 10. 章节负荷超标（LOAD_EXCEEDS_TARGET）
    @Test
    fun routeIssues_overload_counted() {
        val result = SampleSecondaryAnalysis.analyze(
            chapters = listOf(chapter()),
            routeIssues = listOf(SampleSecondaryAnalysis.RouteIssueInput("LOAD_EXCEEDS_TARGET", 1)),
        )
        assertEquals(1, result.routeOverload)
    }

    // 11. 链跨章连续性问题（CHAIN_DISCONTINUITY）
    @Test
    fun routeIssues_chainDiscontinuity_counted() {
        val result = SampleSecondaryAnalysis.analyze(
            chapters = listOf(chapter()),
            routeIssues = listOf(SampleSecondaryAnalysis.RouteIssueInput("CHAIN_DISCONTINUITY", 3)),
        )
        assertEquals(1, result.routeChainDiscontinuity)
    }

    // 12. 混合：声明但未应用 + 无结果 + 部分通过 → 比例计算正确
    @Test
    fun mixedChapters_ratiosAreConsistent() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(
                chapter(no = 1, applied = setOf("A")),                                          // APPLIED + HAS_RESULT
                chapter(no = 2, applied = setOf("A"), issues = listOf("no_application")),        // 声明未应用
                chapter(no = 3, applied = setOf("B"), issues = listOf("missing_result")),        // 应用但无结果
                chapter(no = 4, applied = emptySet(), introduced = setOf("C")),                  // 未声明
            ),
        )
        assertEquals(4, result.totalChapters)
        assertEquals(3, result.declaredAppliedChapters)
        // 第 1 章（通过）与第 3 章（应用了但无结果）都有应用证据；第 2 章确证缺失
        assertEquals(2, result.appliedWithEvidence)
        assertEquals(1, result.declaredButNotApplied)
        assertEquals(1, result.actionWithoutResult)
        assertEquals(0.75, result.appliedRatio, 1e-9)
        // 有结果 = 应用且未缺失结果：第 1 章（1 章），占比 1/3
        assertEquals(1.0 / 3.0, result.resultRatio, 1e-9)
    }

    // ==================== contractChecks 升级测试（批次 B 后续） ====================

    // 13. checks 全 PRESENT → APPLIED_WITH_EVIDENCE（真证据）
    @Test
    fun checksAllPresent_isAppliedWithEvidence() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("A", "B"), checks = listOf(check("A"), check("B")))),
        )
        assertEquals(1, result.appliedWithEvidence)
        assertEquals(0, result.declaredButNotApplied)
        assertEquals(ApplicationEvidence.APPLIED_WITH_EVIDENCE.name, "APPLIED_WITH_EVIDENCE")
    }

    // 14. checks 任一 ABSENT → DECLARED_NOT_APPLIED（确证缺失）
    @Test
    fun checksWithAbsent_isDeclaredNotApplied() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("A", "B"),
                checks = listOf(check("A"), check("B", evidence = "ABSENT")))),
        )
        assertEquals(1, result.declaredButNotApplied)
        assertEquals(0, result.appliedWithEvidence)
    }

    // 15. checks 任一 PARTIAL（无 ABSENT）→ DECLARED_PARTIALLY_APPLIED
    @Test
    fun checksWithPartial_isPartiallyApplied() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("A", "B"),
                checks = listOf(check("A"), check("B", evidence = "PARTIAL")))),
        )
        assertEquals(1, result.partiallyApplied)
        assertEquals(0, result.appliedWithEvidence)
    }

    // 16. checks 任一 UNKNOWN（无 ABSENT/PARTIAL）→ UNKNOWN，不误报
    @Test
    fun checksWithUnknown_isUnknown() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("A", "B"),
                checks = listOf(check("A"), check("B", evidence = "UNKNOWN")))),
        )
        assertTrue(result.evidenceUnknownCount >= 1)
        assertEquals(0, result.appliedWithEvidence)
    }

    // 17. 无 checks（旧模型）→ 退回 issues 推断 DECLARED_AND_NOT_DISPROVED
    @Test
    fun legacyWithoutChecks_fallsBackToIssues() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("A"))),
        )
        assertEquals(1, result.appliedWithEvidence) // 回退口径计入「未发现否定证据」
        assertEquals(0, result.declaredButNotApplied)
    }

    // 18. 宽松 evidence 映射：小写/未知 → UNKNOWN（防炸 + 防误报）
    @Test
    fun evidenceMapping_isLenient() {
        assertEquals(com.example.zhishu.ai.outputs.Evidence.PRESENT,
            com.example.zhishu.ai.outputs.Evidence.parse("present"))
        assertEquals(com.example.zhishu.ai.outputs.Evidence.PARTIAL,
            com.example.zhishu.ai.outputs.Evidence.parse(" Partial "))
        assertEquals(com.example.zhishu.ai.outputs.Evidence.UNKNOWN,
            com.example.zhishu.ai.outputs.Evidence.parse("maybe"))
        assertEquals(com.example.zhishu.ai.outputs.Evidence.UNKNOWN,
            com.example.zhishu.ai.outputs.Evidence.parse(""))
        assertEquals(com.example.zhishu.ai.outputs.Evidence.UNKNOWN,
            com.example.zhishu.ai.outputs.Evidence.parse(null))
    }

    // 19. claim=RESULT 的 checks：PRESENT→HAS_RESULT、ABSENT→ACTION_NO_RESULT
    @Test
    fun resultChecks_driveResultEvidence() {
        val has = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("A"),
                checks = listOf(check("A"), check("A", claim = "RESULT", evidence = "PRESENT")))),
        )
        assertEquals(1.0, has.resultRatio, 1e-9)

        val none = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("A"),
                checks = listOf(check("A"), check("A", claim = "RESULT", evidence = "ABSENT")))),
        )
        assertEquals(1, none.actionWithoutResult)
        assertEquals(0.0, none.resultRatio, 1e-9)
    }

    // 20. 声明的节点与 checks 无关（checks 覆盖别的节点）→ 视为无 checks，退回 issues
    @Test
    fun checksNotCoveringDeclaredNodes_fallsBack() {
        val result = SampleSecondaryAnalysis.analyze(
            listOf(chapter(applied = setOf("A"), checks = listOf(check("别的节点", evidence = "ABSENT")))),
        )
        // A 没有对应 check → 退回 issues 推断（未发现否定证据）
        assertEquals(1, result.appliedWithEvidence)
    }
}
