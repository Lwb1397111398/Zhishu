package com.example.zhishu.ai.pipeline

import com.example.zhishu.ai.outputs.ChapterContractDto
import com.example.zhishu.ai.outputs.ChapterPlanDto
import com.example.zhishu.ai.outputs.ChapterType
import com.example.zhishu.ai.outputs.StoryBibleOutput

/**
 * 契约「引入/应用」归属违规的**程序化**修复（三段式改造，泛化自原 ReviewIntroducedFix（已合并进来））。
 *
 * ## 为什么是程序修而不是回模型
 *
 * 前身 ReviewIntroducedFix 已经证明过一次：把整卷 JSON 发回模型让它「只改一处」
 * 有三个真实代价——硬编码 FAST 角色导致只配了 STORY 的用户整条路线失败（这正是
 * 「讲述路线完全生成不出来」最直接的一条路）、几万 token 往返慢且易截断、模型
 * 会顺手重写别的章节引入新违规。而这类修复完全是**确定性**的字符串搬移，
 * 不需要任何判断力。
 *
 * 三段式之后违规面更宽了（不只 REVIEW，还有 INTRODUCE 重复引入、APPLY 无已学节点），
 * 所以这里把它们统一成一套规则：
 *
 * - R1 REVIEW / ASSESS 章：`introducedNodeKeys` 必须为空 → 全部挪进 applied
 * - R2 任何章：`introducedNodeKeys` 里已经在更早章节引入过的（[alreadyIntroduced]）→ 挪进 applied
 * - R3 APPLY 章：applied 全是本章新引入的 → 把[已学候选]补进 applied（无候选时不硬造，交给校验报失败）
 *
 * 挪动而不是丢弃：被挪的节点仍然留在本章的应用清单里，内容和覆盖统计都不丢；
 * 挪进 applied 后若它其实从未出现过，会被路线软审计（RouteAudit）提示出来，
 * 这比让它伪装成「本章首次引入」要好。
 *
 * **修复失败不静默降级**：修复后仍校验失败 → 如实返回失败，由调用方拦截。
 */
internal object ContractIntroducedFix {

    /**
     * 一次修复的完整结果。
     *
     * [notes] 是给软审计/进度文案看的人话说明——「为什么这章的类型或节点归属被改了」
     * 必须能追溯，否则用户看到契约和模型输出不一致时无从判断是不是 bug。
     */
    data class FixResult(
        val contract: ChapterContractDto,
        val notes: List<String> = emptyList(),
    )

    /** 是否命中任一可修复的引入/应用归属违规 */
    fun isApplicable(chapter: ChapterPlanDto, alreadyIntroduced: Set<String>): Boolean =
        findViolations(chapter, alreadyIntroduced).isNotEmpty()

    /**
     * 修复单章契约的节点归属。
     *
     * @param chapter 本章大纲（含 [ChapterPlanDto.chapterType]）
     * @param contract 模型本次输出的契约（节点用编号引用）
     * @param alreadyIntroduced **更早章节**已引入过的节点编号
     * @param learnedKeys 本章之前已经出现过的全部节点编号（R3 的候选池）
     */
    fun fix(
        chapter: ChapterPlanDto,
        contract: ChapterContractDto,
        alreadyIntroduced: Set<String>,
        learnedKeys: Set<String> = alreadyIntroduced,
    ): FixResult {
        val type = ChapterType.entries.firstOrNull { it.name == chapter.chapterType.trim().uppercase() }
        val notes = mutableListOf<String>()

        val introducedRaw = contract.introducedNodeTitles.map { it.trim() }.filter { it.isNotEmpty() }
        val appliedRaw = contract.appliedNodeTitles.map { it.trim() }.filter { it.isNotEmpty() }

        // R1：回顾/检验章不允许任何「首次引入」——回顾的对象当然只能是学过的东西
        val forbidIntroduce = type == ChapterType.REVIEW || type == ChapterType.ASSESS
        val movedOut = if (forbidIntroduce) {
            if (introducedRaw.isNotEmpty()) {
                notes += "第 ${chapter.order} 章是 ${type!!.name}，回顾/检验对象不能标成首次引入，" +
                    introducedRaw.joinToString("、") + " 已挪进应用清单"
            }
            introducedRaw
        } else {
            // R2：别的章已引入过 → 本章只能算应用，不能重复算「首次」
            val duplicated = introducedRaw.filter { it in alreadyIntroduced }
            if (duplicated.isNotEmpty()) {
                notes += duplicated.joinToString("、") +
                    " 已在更早的章节引入，第 ${chapter.order} 章改为应用"
            }
            duplicated
        }

        val introduced = introducedRaw.filter { it !in movedOut }.distinct()
        val applied = LinkedHashSet(appliedRaw)
        applied += movedOut

        // R3：APPLY 的「应用已有概念」必须成立——应用清单不能全是本章新引入的节点
        if (type == ChapterType.APPLY && applied.isNotEmpty() && applied.all { it in introduced }) {
            val candidate = learnedKeys.firstOrNull { it !in introduced }
            if (candidate != null) {
                applied += candidate
                notes += "第 ${chapter.order} 章是 APPLY，应用清单原本全是本章新引入的节点，" +
                    "已补入已学节点「$candidate」"
            }
        }
        // introduced 与 applied 同时出现同一节点时 introduced 优先，避免同章自相矛盾
        val appliedFinal = applied.filter { it !in introduced }

        return FixResult(
            contract = contract.copy(
                introducedNodeTitles = introduced,
                appliedNodeTitles = appliedFinal,
            ),
            notes = notes,
        )
    }

    /**
     * 旧路径（一次成型的 [StoryBibleOutput]）兼容入口：只做 R1（REVIEW 清空 introduced）。
     *
     * 保留它是因为 `generateNextVolume` 与样本运行器仍走一次成型；三段式新路径
     * 一律用上面的单章版本。两个入口共享同一套「挪进 applied」语义，
     * 免得两条路对同一条规则给出不同结果。
     */
    fun fixStoryBible(output: StoryBibleOutput): StoryBibleOutput =
        output.copy(
            chapters = output.chapters.map { chapter ->
                if (!chapter.isReviewWithIntroduced) return@map chapter
                val moved = chapter.contract.introducedNodeTitles
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val applied = LinkedHashSet(chapter.contract.appliedNodeTitles.map { it.trim() })
                applied += moved
                chapter.copy(
                    contract = chapter.contract.copy(
                        introducedNodeTitles = emptyList(),
                        appliedNodeTitles = applied.filter { it.isNotEmpty() },
                    ),
                )
            },
        )

    /** 旧路径：是否命中「REVIEW + introduced 非空」 */
    fun isApplicable(output: StoryBibleOutput): Boolean =
        output.chapters.any { it.isReviewWithIntroduced }

    /** 旧路径：修复后所有 REVIEW 章的 introduced 必须为空 */
    fun isFixed(output: StoryBibleOutput): Boolean =
        output.chapters.none { it.isReviewWithIntroduced }

    /** 旧路径的校验-修复-再校验；语义与原 ReviewIntroducedFix.repairIfApplicable 一致 */
    fun repairIfApplicable(
        output: StoryBibleOutput,
        validate: (StoryBibleOutput) -> Result<StoryBibleOutput>,
        fixFailure: (Throwable) -> Result<StoryBibleOutput>,
    ): Result<StoryBibleOutput> {
        if (!isApplicable(output)) return validate(output)
        val repaired = fixStoryBible(output)
        if (!isFixed(repaired)) {
            return fixFailure(
                IllegalStateException("REVIEW 违规修复后仍未清空 introducedNodeTitles"),
            )
        }
        return validate(repaired)
    }

    private fun findViolations(chapter: ChapterPlanDto, alreadyIntroduced: Set<String>): List<String> {
        val type = ChapterType.entries.firstOrNull { it.name == chapter.chapterType.trim().uppercase() }
        val introduced = chapter.contract.introducedNodeTitles.map { it.trim() }.filter { it.isNotEmpty() }
        val violations = mutableListOf<String>()
        if (type == ChapterType.REVIEW || type == ChapterType.ASSESS) {
            if (introduced.isNotEmpty()) violations += "REVIEW_OR_ASSESS_WITH_INTRODUCED"
        }
        if (introduced.any { it in alreadyIntroduced }) violations += "DUPLICATE_INTRODUCE"
        return violations
    }

    private val ChapterPlanDto.isReviewWithIntroduced: Boolean
        get() = chapterType.equals("REVIEW", ignoreCase = true) &&
            contract.introducedNodeTitles.any { it.isNotBlank() }
}
