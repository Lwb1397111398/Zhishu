package com.example.zhishu.samples

import java.io.File
import kotlinx.serialization.Serializable

/**
 * 批次 B 调用级硬指标（B.0-5 口径）。
 *
 * 输入是 attempt 级 [CallRecord]（一次 HTTP 尝试一条）。口径：
 * - 首次业务调用计入 business；重试计入 retry；JSON 修复计入 repair；三者之和为总；
 * - 服务商未报 usage 记 usageStatus=MISSING（汇总单列 usageMissingCalls，不用零填充）；
 * - 失败的尝试保留（mapFailures/chapterFailures/verifyFailures 按 outcome=FAILED 数）；
 * - HTTP 失败可能已收费：MISSING + errorCategory 保留，不伪装成 0 成本。
 *
 * 二次分析（契约声明 vs 正文证据双轨、RouteAudit 软指标、应用/结果比例）在
 * [SampleSecondaryAnalysis] 中基于 artifacts 离线计算，并有对应的离线测试。
 */
internal object SampleMetrics {

    @Serializable
    data class Metrics(
        val perSubject: List<SubjectMetrics>,
        val aggregate: Aggregate,
    )

    @Serializable
    data class SubjectMetrics(
        val subjectId: String,
        val variant: String,
        val businessTokens: Long,
        val retryTokens: Long,
        val repairTokens: Long,
        val totalTokens: Long,
        val usageMissingCalls: Int,
        val latencyTotalMs: Long,
        val failedAttempts: Int,
        val mapFailures: Int,
        val chapterFailures: Int,
        val verifyFailures: Int,
    )

    @Serializable
    data class Aggregate(
        val subjects: Int,
        val totalBusinessTokens: Long,
        val totalRetryTokens: Long,
        val totalRepairTokens: Long,
        val totalTokens: Long,
        val usageMissingCalls: Int,
        val mapFailures: Int,
        val chapterFailures: Int,
        val verifyFailures: Int,
        val note: String,
    )

    fun compute(calls: List<CallRecord>, artifactsDir: File): Metrics {
        val perSubject = calls.groupBy { it.subjectId + "|" + it.variant }
            .map { (key, recs) ->
                val parts = key.split("|")
                SubjectMetrics(
                    subjectId = parts[0],
                    variant = parts[1],
                    businessTokens = recs.filter { it.kind == "BUSINESS" }.sumOf { it.totalTokens },
                    retryTokens = recs.filter { it.kind == "RETRY" }.sumOf { it.totalTokens },
                    repairTokens = recs.filter { it.kind == "JSON_REPAIR" }.sumOf { it.totalTokens },
                    totalTokens = recs.sumOf { it.totalTokens },
                    usageMissingCalls = recs.count { it.usageStatus == "MISSING" },
                    latencyTotalMs = recs.sumOf { it.latencyMillis },
                    failedAttempts = recs.count { it.outcome == "FAILED" },
                    mapFailures = recs.count { it.stage == "KNOWLEDGE_MAP" && it.outcome == "FAILED" },
                    chapterFailures = recs.count { it.stage.startsWith("CHAPTER") && it.outcome == "FAILED" },
                    verifyFailures = recs.count { it.stage == "VERIFY" && it.outcome == "FAILED" },
                )
            }.sortedWith(compareBy({ it.subjectId }, { it.variant }))

        val aggregate = Aggregate(
            subjects = perSubject.size,
            totalBusinessTokens = calls.filter { it.kind == "BUSINESS" }.sumOf { it.totalTokens },
            totalRetryTokens = calls.filter { it.kind == "RETRY" }.sumOf { it.totalTokens },
            totalRepairTokens = calls.filter { it.kind == "JSON_REPAIR" }.sumOf { it.totalTokens },
            totalTokens = calls.sumOf { it.totalTokens },
            usageMissingCalls = calls.count { it.usageStatus == "MISSING" },
            mapFailures = calls.count { it.stage == "KNOWLEDGE_MAP" && it.outcome == "FAILED" },
            chapterFailures = calls.count { it.stage.startsWith("CHAPTER") && it.outcome == "FAILED" },
            verifyFailures = calls.count { it.stage == "VERIFY" && it.outcome == "FAILED" },
            note = "调用级硬指标：一次 HTTP 尝试一条；usage 缺失单列不计 0；" +
                "二次分析（契约双轨/RouteAudit 软指标/应用与结果比例）见 SampleSecondaryAnalysis。" +
                "首轮每主题每 variant 各 1 次，单次差异不足以证明统计显著性，仅作探索性结果。",
        )
        return Metrics(perSubject = perSubject, aggregate = aggregate)
    }
}

/** 评分表模板：两名评分人盲评（隐藏 legacy/new 标签，评分完成后揭盲） */
internal object RatingTemplate {
    fun render(manifest: RunManifest): String = buildString {
        appendLine("# 批次 B 人工评分表（盲评：A/B 随机编号，完成后揭盲）")
        appendLine()
        appendLine("运行：${manifest.runId}　模型：${manifest.model}（${manifest.baseUrlHost}）")
        appendLine("说明：本表不标注 legacy/new。两张对照表分别标为 方案A / 方案B，评分完成后揭盲。")
        appendLine()
        appendLine("评分人：____　日期：____")
        appendLine()
        appendLine("## 评分规则")
        appendLine("- 每项 1-5 分；**无法仅凭模型输出判断的填「不可判断」**；")
        appendLine("- 无资料模式下的事实项必须写「评分依据」：用户资料 / 专业人员 / 指定参考资料 / 常识判断；")
        appendLine("- 「需要外部来源」表示该内容可信但必须有外部资料佐证才能给分。")
        appendLine()
        manifest.themes.forEach { theme ->
            appendLine("### 主题 ${theme.id}（${theme.mode}）")
            appendLine()
            appendLine("| 方案 | 范围正确 | 节点完整 | 关系正确 | 概念非凭空出现 | 故事因果成立 | 人物行为合理 | 知识用于解决问题 | 章节不重复 | 事实可信 | 信息密度 | 依据 |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            appendLine("| A | | | | | | | | | | | |")
            appendLine("| B | | | | | | | | | | | |")
            appendLine()
        }
    }
}
