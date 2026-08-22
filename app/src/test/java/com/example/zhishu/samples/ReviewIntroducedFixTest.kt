package com.example.zhishu.samples

import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.jsonDecoder
import com.example.zhishu.ai.outputs.ChapterContractDto
import com.example.zhishu.ai.outputs.ChapterPlanDto
import com.example.zhishu.ai.outputs.StoryBibleOutput
import com.example.zhishu.ai.pipeline.ReviewIntroducedFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REVIEW-introduced 违规自动恢复的离线测试（批次 B++ n=3 发现）。
 * 覆盖：命中检测 / 未命中不调用 / 修复成功 / 修复失败不静默降级 / 修复后仍违规拦截。
 */
class ReviewIntroducedFixTest {

    private val decoder = jsonDecoder(StoryBibleOutput.serializer())

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

    private class FixedRouter(
        private val result: Result<StoryBibleOutput>,
        var calls: Int = 0,
    ) : ModelRouter {
        override suspend fun <T> generate(
            role: ModelRole,
            request: ModelRequest,
            decoder: JsonDecoder<T>,
            onStats: (com.example.zhishu.ai.ModelCallStats) -> Unit,
            onAttempt: (com.example.zhishu.ai.CallAttempt) -> Unit,
        ): Result<T> {
            calls += 1
            @Suppress("UNCHECKED_CAST")
            return result as Result<T>
        }
    }

    // 1. 命中检测：REVIEW + introduced 非空 → true
    @Test
    fun isApplicable_reviewWithIntroduced_isTrue() {
        val b = bible(listOf(chapter(1, "INTRODUCE", introduced = listOf("A")),
            chapter(2, "REVIEW", introduced = listOf("A"), applied = listOf("A"))))
        assertTrue(ReviewIntroducedFix.isApplicable(b))
    }

    // 2. 无 REVIEW → false（不触发修复调用）
    @Test
    fun isApplicable_noReview_isFalse() {
        assertFalse(ReviewIntroducedFix.isApplicable(bible(listOf(chapter(1, "INTRODUCE", introduced = listOf("A"))))))
    }

    // 3. REVIEW 但 introduced 已空 → false
    @Test
    fun isApplicable_reviewWithoutIntroduced_isFalse() {
        assertFalse(ReviewIntroducedFix.isApplicable(
            bible(listOf(chapter(1, "REVIEW", introduced = emptyList(), applied = listOf("A")))),
        ))
    }

    // 4. 未命中 → 原样返回，不调用修复（router.calls == 0）
    @Test
    fun repairIfApplicable_notApplicable_doesNotCallRouter() {
        val router = FixedRouter(Result.success(bible(emptyList())))
        val out = bible(listOf(chapter(1, "INTRODUCE", introduced = listOf("A"))))
        val result = kotlinx.coroutines.runBlocking {
            ReviewIntroducedFix.repairIfApplicable(router, out, decoder, okValidate) { Result.failure(it) }
        }
        assertTrue(result.isSuccess)
        assertEquals(0, router.calls)
    }

    // 5. 命中且修复成功 → 返回修复后输出（修复调用 1 次）
    @Test
    fun repairIfApplicable_fixSucceeds_returnsFixed() {
        val fixed = bible(listOf(
            chapter(1, "INTRODUCE", introduced = listOf("A"), nodeTitles = listOf("A")),
            chapter(2, "REVIEW", introduced = emptyList(), applied = listOf("A"), nodeTitles = listOf("A")),
        ))
        val router = FixedRouter(Result.success(fixed))
        val bad = bible(listOf(
            chapter(1, "INTRODUCE", introduced = listOf("A"), nodeTitles = listOf("A")),
            chapter(2, "REVIEW", introduced = listOf("A"), applied = listOf("A"), nodeTitles = listOf("A")),
        ))
        val result = kotlinx.coroutines.runBlocking {
            ReviewIntroducedFix.repairIfApplicable(router, bad, decoder, okValidate) { Result.failure(it) }
        }
        assertTrue(result.isSuccess)
        assertEquals(1, router.calls)
        assertTrue(ReviewIntroducedFix.isFixed(result.getOrThrow()))
    }

    // 6. 修复调用失败 → 走 fixFailure，不静默降级为成功
    @Test
    fun repairIfApplicable_fixFails_returnsFailure() {
        val router = FixedRouter(Result.failure(ModelError.Network("timeout")))
        val bad = bible(listOf(
            chapter(1, "INTRODUCE", introduced = listOf("A")),
            chapter(2, "REVIEW", introduced = listOf("A"), applied = listOf("A")),
        ))
        var fixFailureCalled = false
        val result = kotlinx.coroutines.runBlocking {
            ReviewIntroducedFix.repairIfApplicable(router, bad, decoder, okValidate) {
                fixFailureCalled = true; Result.failure(it)
            }
        }
        assertTrue(result.isFailure)
        assertTrue(fixFailureCalled)
    }

    // 7. 修复后仍带 introduced 违规 → 拦截（fixFailure 被调）
    @Test
    fun repairIfApplicable_fixStillViolates_isRejected() {
        val stillBad = bible(listOf(
            chapter(1, "INTRODUCE", introduced = listOf("A")),
            chapter(2, "REVIEW", introduced = listOf("B"), applied = listOf("A")), // 修复失败：仍引入 B
        ))
        val router = FixedRouter(Result.success(stillBad))
        val bad = bible(listOf(
            chapter(1, "INTRODUCE", introduced = listOf("A")),
            chapter(2, "REVIEW", introduced = listOf("A"), applied = listOf("A")),
        ))
        val result = kotlinx.coroutines.runBlocking {
            ReviewIntroducedFix.repairIfApplicable(router, bad, decoder, okValidate) { Result.failure(it) }
        }
        assertTrue(result.isFailure)
        assertFalse("修复后仍违规必须失败", result.isSuccess)
    }

    // 8. 修复成功但校验失败 → 如实失败
    @Test
    fun repairIfApplicable_fixOkButValidateFails_isFailure() {
        val fixed = bible(listOf(
            chapter(1, "INTRODUCE", introduced = listOf("A")),
            chapter(2, "REVIEW", introduced = emptyList(), applied = listOf("A")),
        ))
        val router = FixedRouter(Result.success(fixed))
        val bad = bible(listOf(
            chapter(1, "INTRODUCE", introduced = listOf("A")),
            chapter(2, "REVIEW", introduced = listOf("A"), applied = listOf("A")),
        ))
        val strictValidate: (StoryBibleOutput) -> Result<StoryBibleOutput> = {
            Result.failure(ModelError.Invalid("其他校验失败"))
        }
        val result = kotlinx.coroutines.runBlocking {
            ReviewIntroducedFix.repairIfApplicable(router, bad, decoder, strictValidate) { Result.failure(it) }
        }
        assertTrue(result.isFailure)
    }
}
