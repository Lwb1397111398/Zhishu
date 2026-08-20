package com.example.zhishu.ai.pipeline

import com.example.zhishu.ai.outputs.VerificationIssueDto
import com.example.zhishu.domain.model.IssueType
import com.example.zhishu.domain.model.VerificationIssue

/**
 * 把模型给出的核验问题落成可操作的记录（4B）。
 *
 * 和 [CitationResolver] 是同一个思路：模型给的定位标识**不一定对得上**，
 * 但对不上这件事本身必须如实记下来，而不是让问题消失。
 *
 * 三条规则：
 *
 * 1. **blockId 必须能对上这一版真实的块。** 模型经常给出一个不存在的 blockId
 *    （截断过的、别的章节的、干脆编的）。对不上就记成「定位不到具体段落」，
 *    绝不猜一个最像的块——猜错的话用户点开一段没问题的正文，
 *    看不出问题在哪，然后开始怀疑核验本身。
 * 2. **一条都不丢。** 4B 之前是 `issues.take(5)`，第 6 条起直接消失。
 *    上限改成一个高得多的数字，而且是为了防模型抽风刷出几百条，
 *    不是为了省 UI 空间。
 * 3. **认不出的类型不丢。** 映射不上的落到 OTHER，原始字符串留在 rawType 里。
 */
internal object IssueRecorder {

    /**
     * 一次核验的结果。
     *
     * [blockScoped] 与 [chapterScoped] 分开，因为它们能做的事不一样：
     * 前者可以「只重写这一块」，后者只能整章重写或由用户判断。
     */
    class Recorded(val issues: List<VerificationIssue>) {
        val blockScoped: List<VerificationIssue> get() = issues.filter { it.isBlockScoped }
        val chapterScoped: List<VerificationIssue> get() = issues.filterNot { it.isBlockScoped }

        /** 有问题的块，按正文顺序去重 */
        val affectedBlockIds: List<String>
            get() = blockScoped.mapNotNull { it.blockId }.distinct()
    }

    fun record(
        projectId: String,
        chapterId: String,
        versionId: String,
        issues: List<VerificationIssueDto>,
        /** 这一版真实存在的块 ID。blockId 对不上就当定位不到 */
        validBlockIds: Set<String>,
        now: Long,
    ): Recorded {
        val recorded = issues
            .filter { it.message.isNotBlank() }
            .take(MAX_ISSUES)
            .mapIndexed { order, dto ->
                val blockId = dto.blockId?.trim()?.takeIf { it in validBlockIds }
                VerificationIssue(
                    id = VerificationIssue.idFor(versionId, order),
                    projectId = projectId,
                    chapterId = chapterId,
                    versionId = versionId,
                    blockId = blockId,
                    type = IssueType.parse(dto.type),
                    rawType = dto.type.trim(),
                    message = dto.message.trim(),
                    // 相关来源原样留着：用户要核对时得能点开那几段资料原文。
                    // 这里不校验片段是否存在——「引用的资料已经不在了」本身
                    // 就是一条有用的信息，4A 的 UNCONFIRMED 是同一个道理
                    sourceRefs = dto.sourceRefs.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                    suggestedFix = dto.suggestedFix.trim(),
                    order = order,
                    createdAt = now,
                )
            }
        return Recorded(recorded)
    }

    /**
     * 一次核验最多记多少条问题。
     *
     * 这个上限是为了防模型抽风刷出几百条，不是为了省界面空间——
     * 那正是 4B 要修的毛病（原来是 5 条）。
     */
    private const val MAX_ISSUES = 60
}
