package com.example.zhishu.samples

import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.outputs.ChapterContractDto
import com.example.zhishu.ai.outputs.ChapterPlanDto
import com.example.zhishu.ai.outputs.StoryBibleOutput
import com.example.zhishu.ai.pipeline.ContractIntroducedFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约「引入/应用」归属违规的程序化修复测试（批次 B++ n=3 发现）。
 *
 * 本轮改为**纯程序化**修复：不再把整卷 JSON 发回模型。所以测试的重点从
 * 「修复调用怎么走」变成「修复结果对不对、其余内容有没有被动」，并新增一条
 * 关键回归：修复过程一次模型调用都不许发生——原来那条路会隐式要求 FAST 模型，
 * 而讲述路线阶段只要求 STORY，用户没配 FAST 时路线就在这一步整体失败。
 *
 * 类名保留 ReviewIntroducedFixTest：这批用例验证的规则（REVIEW 章不能标
 * 首次引入）源自那个类，改名会让历史记录对不上。实现已迁到
 * [ContractIntroducedFix]——三段式之后规则面更宽（不只 REVIEW，还有跨章重复
 * 引入、APPLY 无已学节点），两处维护同一套规则必然走偏。
 */
class ReviewIntroducedFixTest {

    private fun chapter(
        order: Int = 1,
        type: String = "INTRODUCE",
        introduced: List<String> = emptyList(),
        applied: List<String> = emptyList(),
        nodeTitles: List<String> = emptyList(),
    ) = ChapterPlanDto(
        order = order,
        title = "第 $order 章",
        nodeTitles = nodeTitles,
        chapterType = type,
        contract = ChapterContractDto(
            entryQuestion = "q",
            priorIntuition = "p",
            obstacle = "o",
            knowledgeGap = "g",
            introducedNodeTitles = introduced,
            appliedNodeTitles = applied,
            requiredBeats = listOf("b1", "b2", "b3"),
            resultingChange = "r",
            bridgeToNext = "n",
        ),
    )

    private fun bible(chapters: List<ChapterPlanDto>) = StoryBibleOutput(
        world = "w", conflict = "c", style = "s", chapters = chapters,
    )

    private val okValidate: (StoryBibleOutput) -> Result<StoryBibleOutput> = { Result.success(it) }

    // 1. 命中检测：REVIEW + introduced 非空 → true
    @Test
    fun isApplicable_reviewWithIntroduced_isTrue() {
        val b = bible(
            listOf(
                chapter(1, "INTRODUCE", introduced = listOf("A")),
                chapter(2, "REVIEW", introduced = listOf("A"), applied = listOf("A")),
            ),
        )
        assertTrue(ContractIntroducedFix.isApplicable(b))
    }

    // 2. 无 REVIEW → false（不触发修复）
    @Test
    fun isApplicable_noReview_isFalse() {
        assertFalse(
            ContractIntroducedFix.isApplicable(
                bible(listOf(chapter(1, "INTRODUCE", introduced = listOf("A")))),
            ),
        )
    }

    // 3. REVIEW 但 introduced 已空 → false
    @Test
    fun isApplicable_reviewWithoutIntroduced_isFalse() {
        assertFalse(
            ContractIntroducedFix.isApplicable(
                bible(listOf(chapter(1, "REVIEW", introduced = emptyList(), applied = listOf("A")))),
            ),
        )
    }

    // 4. 未命中 → 原样返回
    @Test
    fun repairIfApplicable_notApplicable_returnsOriginal() {
        val out = bible(listOf(chapter(1, "INTRODUCE", introduced = listOf("A"))))
        val result = ContractIntroducedFix.repairIfApplicable(out, okValidate) { Result.failure(it) }
        assertTrue(result.isSuccess)
        assertEquals(out, result.getOrThrow())
    }

    /**
     * 5. 命中 → REVIEW 的 introduced 清空并并入 applied，且**其余章节一个字都不改**。
     *
     * 这是从模型修复换成程序修复后真正要钉住的东西：原来模型「只改一处」并不可靠，
     * 它会顺手重写别的章节，让修复自己引入新违规。
     */
    @Test
    fun fix_clearsReviewIntroduced_andKeepsEverythingElse() {
        val intro = chapter(1, "INTRODUCE", introduced = listOf("A"), nodeTitles = listOf("A"))
        val bad = bible(
            listOf(
                intro,
                chapter(2, "REVIEW", introduced = listOf("B"), applied = listOf("A"), nodeTitles = listOf("A", "B")),
            ),
        )
        val result = ContractIntroducedFix.repairIfApplicable(bad, okValidate) { Result.failure(it) }
        assertTrue(result.isSuccess)
        val fixed = result.getOrThrow()
        assertTrue(ContractIntroducedFix.isFixed(fixed))
        // INTRODUCE 章完全没动
        assertEquals(intro, fixed.chapters[0])
        val review = fixed.chapters[1].contract
        assertEquals(emptyList<String>(), review.introducedNodeTitles)
        // B 被挪进 applied，原有的 A 保留且不重复
        assertEquals(listOf("A", "B"), review.appliedNodeTitles)
        // 契约其余字段原样
        assertEquals("g", review.knowledgeGap)
        assertEquals("n", review.bridgeToNext)
    }

    /** 6. 已在 applied 里的节点不会被搬成重复项 */
    @Test
    fun fix_doesNotDuplicateAlreadyAppliedTitles() {
        val bad = chapter(1, "REVIEW", introduced = listOf("A"), applied = listOf("A", "B"))
        val contract = bad.contract
        val fixed = ContractIntroducedFix.fix(
            chapter = bad,
            contract = contract,
            alreadyIntroduced = emptySet(),
        ).contract
        assertEquals(listOf("A", "B"), fixed.appliedNodeTitles)
        assertEquals(emptyList<String>(), fixed.introducedNodeTitles)
    }

    /**
     * 7. 修复过程绝不发起模型调用。
     *
     * 回归的是「讲述路线完全生成不出来」的一条真实原因：原实现硬编码 FAST 角色，
     * 而本阶段只要求 STORY。现在整个修复是本地字符串搬移，函数签名里连 router 都没有
     * ——这条测试用「不接受 router 参数」这一事实把它钉死（编译期即保证）。
     */
    @Test
    fun repairIfApplicable_neverCallsAnyModel() {
        val bad = bible(
            listOf(
                chapter(1, "INTRODUCE", introduced = listOf("A")),
                chapter(2, "REVIEW", introduced = listOf("A"), applied = listOf("A")),
            ),
        )
        // 不是 suspend、不吃 router：拿不到模型就修不了的情况已经从根上不存在
        val result: Result<StoryBibleOutput> =
            ContractIntroducedFix.repairIfApplicable(bad, okValidate) { Result.failure(it) }
        assertTrue(result.isSuccess)
    }

    // 8. 修复成功但其他校验失败 → 如实失败，不静默降级
    @Test
    fun repairIfApplicable_fixOkButValidateFails_isFailure() {
        val bad = bible(
            listOf(
                chapter(1, "INTRODUCE", introduced = listOf("A")),
                chapter(2, "REVIEW", introduced = listOf("A"), applied = listOf("A")),
            ),
        )
        val strictValidate: (StoryBibleOutput) -> Result<StoryBibleOutput> = {
            Result.failure(ModelError.Invalid("其他校验失败"))
        }
        val result = ContractIntroducedFix.repairIfApplicable(bad, strictValidate) { Result.failure(it) }
        assertTrue(result.isFailure)
    }
}
