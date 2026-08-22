package com.example.zhishu.ai.pipeline

import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.outputs.StoryBibleOutput
import com.example.zhishu.ai.outputs.ChapterPlanDto
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * REVIEW-introduced 违规的针对性修复（批次 B++ n=3 发现：路线强校验拦截的最大失败模式）。
 *
 * 规则：REVIEW 章节只能引用已学节点，`introducedNodeTitles` 必须为空数组。
 * 模型偶发把新节点填进 REVIEW 的 introducedNodeTitles → 整条路线被强校验拒绝。
 * 本修复器只针对该模式做一次**针对性**修复重试（不是把整个路线重新生成一遍）：
 * - 清空 REVIEW 章的 introducedNodeTitles；
 * - 检查这些节点是否在本章之前出现过（此前出现 → 进 appliedNodeTitles 作为回顾；从未出现 → 也移入 appliedNodeTitles，
 *   因为 REVIEW 不允许首次引入，宁可让核验/审计去提示遗漏，也不能让 REVIEW 伪装首次介绍）。
 *
 * **修复失败不静默降级**：修复调用失败或修复后仍校验失败 → 保持原失败返回，由调用方如实拦截。
 */
internal object ReviewIntroducedFix {

    /** 是否命中「REVIEW + introducedNodeTitles 非空」这一可修复违规 */
    fun isApplicable(output: StoryBibleOutput): Boolean =
        output.chapters.any { it.chapterType.equals("REVIEW", ignoreCase = true) &&
            (it.contract?.introducedNodeTitles ?: emptyList()).any { it.isNotBlank() } }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val SYSTEM =
        "你是章节计划修复助手。只修复一处违规，不改变任何其他内容，只输出修复后的完整 JSON。"

    private val USER_TEMPLATE =
        """下面是一份章节计划 JSON。其中 REVIEW 类型的章节违反了硬性规则：
        |REVIEW 只能回顾已学节点，introducedNodeTitles 必须为空数组（[]）。
        |修复要求：
        |1. 把所有 REVIEW 章的 introducedNodeTitles 改为空数组 []；
        |2. 这些节点如果在本章之前的章节（order 更小）里出现过（在任一章的 nodeTitles 里），移入本章的 appliedNodeTitles；
        |3. 如果之前从未出现过，也移入 appliedNodeTitles（REVIEW 不允许首次引入，宁可在 applied 里被审计提示遗漏，也不允许伪装首次介绍）；
        |4. 其余章节、其余字段一个字都不要改。
        |只输出修复后的完整 JSON，不要任何解释、代码块围栏或多余文字。
        |
        |原始章节计划：
        |{json}""".trimMargin()

    /**
     * 执行针对性修复。返回修复后的路线；调用失败（网络/解码/模型拒绝）返回失败。
     */
    suspend fun repair(
        router: ModelRouter,
        original: StoryBibleOutput,
        decoder: JsonDecoder<StoryBibleOutput>,
    ): Result<StoryBibleOutput> {
        val prompt = USER_TEMPLATE.replace("{json}", json.encodeToString(original))
        return router.generate(
            role = ModelRole.FAST,
            request = ModelRequest(
                role = ModelRole.FAST,
                system = SYSTEM,
                user = prompt,
                jsonMode = true,
            ),
            decoder = decoder,
        )
    }

    /** 修复后的结构检查：所有 REVIEW 章的 introduced 必须为空 */
    fun isFixed(output: StoryBibleOutput): Boolean =
        output.chapters.none { it.chapterType.equals("REVIEW", ignoreCase = true) &&
            (it.contract?.introducedNodeTitles ?: emptyList()).any { it.isNotBlank() } }

    /**
     * 校验-修复-再校验流程：
     * - 命中 REVIEW-introduced 违规 → 修复一次 → 校验；修复仍失败返回 [fixFailure]（不静默降级）；
     * - 未命中 → 返回原输出（不触发修复调用）。
     */
    suspend fun repairIfApplicable(
        router: ModelRouter,
        output: StoryBibleOutput,
        decoder: JsonDecoder<StoryBibleOutput>,
        validate: (StoryBibleOutput) -> Result<StoryBibleOutput>,
        fixFailure: (Throwable) -> Result<StoryBibleOutput>,
    ): Result<StoryBibleOutput> {
        if (!isApplicable(output)) return validate(output)
        val repaired = repair(router, output, decoder)
            .getOrElse { return@repairIfApplicable fixFailure(it) }
        // 修复后仍带 REVIEW-introduced 违规 → 视为修复失败，如实拦截
        if (!isFixed(repaired)) {
            return@repairIfApplicable fixFailure(
                IllegalStateException("REVIEW 违规修复后仍未清空 introducedNodeTitles"),
            )
        }
        // 修复成功后校验：失败如实返回（Result.failure），不再走 fixFailure
        return validate(repaired)
    }
}
