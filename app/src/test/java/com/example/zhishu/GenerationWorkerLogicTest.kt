package com.example.zhishu

import com.example.zhishu.ai.ModelError
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.worker.GenerationStage
import com.example.zhishu.worker.isPermanentConfigFailure
import com.example.zhishu.worker.failureReason
import com.example.zhishu.worker.isRetryableFailure
import com.example.zhishu.worker.selectChaptersToGenerate
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Worker 的阶段筛选与异常分类（B3/B4/B5） */
class GenerationWorkerLogicTest {

    private fun plan(id: String, order: Int, status: ChapterStatus, activeVersionId: String? = null) =
        ChapterPlan(
            id = id,
            projectId = "p-1",
            order = order,
            title = "第 $order 章",
            status = status,
            activeVersionId = activeVersionId,
        )

    @Test
    fun selectChapters_skipsPublishedAndReviewedWithBodyByDefault() {
        val plans = listOf(
            plan("c3", 3, ChapterStatus.QUEUED),
            plan("c1", 1, ChapterStatus.PUBLISHED),
            // 带问题发布的待复核（有可读正文）：默认不动，等用户复核
            plan("c2", 2, ChapterStatus.NEEDS_REVIEW, activeVersionId = "v-2"),
            plan("c4", 4, ChapterStatus.QUIZ_FAILED),
            plan("c5", 5, ChapterStatus.FAILED),
        )
        val selected = selectChaptersToGenerate(plans, includeNeedsReview = false)
        assertEquals(listOf("c3", "c5"), selected.map { it.id })
    }

    /** 待复核但没有任何可读正文（版本已作废的空壳）＝ 没写完，照常进批量 */
    @Test
    fun selectChapters_includesNeedsReviewWithoutReadableBody() {
        val plans = listOf(
            plan("c1", 1, ChapterStatus.NEEDS_REVIEW, activeVersionId = null),
            plan("c2", 2, ChapterStatus.NEEDS_REVIEW, activeVersionId = "v-2"),
        )
        val selected = selectChaptersToGenerate(plans, includeNeedsReview = false)
        assertEquals(listOf("c1"), selected.map { it.id })
    }

    /**
     * 显式重试时纳入待复核章节。
     *
     * QUIZ_FAILED 不纳入：那是旧库里的状态，正文已经通过核验、能读，
     * 只是缺几道自测题——为几道题重跑一遍正文纯属浪费额度（第二阶段）。
     * 补题走「重新生成自测」，只调出题阶段。
     */
    @Test
    fun selectChapters_includesNeedsReviewButNotQuizFailed() {
        val plans = listOf(
            plan("c1", 1, ChapterStatus.PUBLISHED),
            plan("c2", 2, ChapterStatus.NEEDS_REVIEW),
            plan("c3", 3, ChapterStatus.QUIZ_FAILED),
        )
        val selected = selectChaptersToGenerate(plans, includeNeedsReview = true)
        assertEquals(listOf("c2"), selected.map { it.id })
    }

    /** 正文可读性只看正文状态：旧的 QUIZ_FAILED 也算可读（第二阶段） */
    @Test
    fun quizFailed_countsAsReadable() {
        assertTrue(ChapterStatus.PUBLISHED.isReadable)
        assertTrue(ChapterStatus.QUIZ_FAILED.isReadable)
        assertFalse(ChapterStatus.NEEDS_REVIEW.isReadable)
        assertFalse(ChapterStatus.DRAFT.isReadable)
        assertFalse(ChapterStatus.FAILED.isReadable)
    }

    /**
     * 永久性配置错误要立刻停链，别把后面几章也白调一遍（第二阶段）。
     * 单章内容不合要求不算——那只是这一章的问题。
     */
    @Test
    fun permanentConfigFailures_stopTheChain() {
        assertTrue(isPermanentConfigFailure(ModelError.Auth("401")))
        assertTrue(isPermanentConfigFailure(ModelError.InvalidRequest("no such model")))
        assertTrue(isPermanentConfigFailure(ModelError.BudgetExhausted("额度用尽")))
        assertFalse(isPermanentConfigFailure(ModelError.Invalid("本章缺少知识点")))
        assertFalse(isPermanentConfigFailure(ModelError.RateLimited("429")))
        assertFalse(isPermanentConfigFailure(CancellationException("cancelled")))
    }

    /** 各阶段只检查自己真正会用到的模型角色（第二阶段修正） */
    @Test
    fun stages_requireOnlyTheRolesTheyUse() {
        assertEquals(listOf(ModelRole.FAST), GenerationStage.KNOWLEDGE_MAP.requiredRoles)
        assertEquals(listOf(ModelRole.STORY), GenerationStage.STORY_BIBLE.requiredRoles)
        // 单章正文用 STORY 写、VERIFY 核验与出题，与 FAST 无关
        assertEquals(
            listOf(ModelRole.STORY, ModelRole.VERIFY),
            GenerationStage.SINGLE_CHAPTER.requiredRoles,
        )
        assertFalse(ModelRole.FAST in GenerationStage.SINGLE_CHAPTER.requiredRoles)
        assertFalse(ModelRole.FAST in GenerationStage.FIRST_CHAPTER.requiredRoles)
        // 链尾收尾不调模型
        assertTrue(GenerationStage.SUMMARY.requiredRoles.isEmpty())
    }

    @Test
    fun selectChapters_sortsByOrder() {
        val plans = listOf(
            plan("c9", 9, ChapterStatus.QUEUED),
            plan("c2", 2, ChapterStatus.QUEUED),
            plan("c5", 5, ChapterStatus.QUEUED),
        )
        assertEquals(listOf(2, 5, 9), selectChaptersToGenerate(plans, false).map { it.order })
    }

    /** 协程取消绝不能被当成可重试的业务失败（B4） */
    @Test
    fun cancellation_isNotRetryable() {
        assertFalse(isRetryableFailure(CancellationException("cancelled")))
    }

    @Test
    fun modelErrors_followRetryableFlag() {
        assertFalse(isRetryableFailure(ModelError.Auth("no key")))
        assertFalse(isRetryableFailure(ModelError.InvalidRequest("bad model")))
        assertFalse(isRetryableFailure(ModelError.Decode("bad json")))
        assertFalse(isRetryableFailure(ModelError.Invalid("no nodes")))
        // Router 已在单次业务调用内处理过 429；Worker 再重跑整阶段会形成双层重试，
        // 对服务商的并发限流尤其无效，还会重做已成功的骨架/路线。
        assertFalse(isRetryableFailure(ModelError.RateLimited("429")))
        assertTrue(isRetryableFailure(ModelError.Server("500")))
        assertTrue(isRetryableFailure(ModelError.Network("timeout")))
    }

    @Test
    fun preconditionFailures_areNotRetryable() {
        assertFalse(isRetryableFailure(IllegalStateException("项目不存在")))
        assertFalse(isRetryableFailure(IllegalArgumentException("仅支持 https 地址")))
    }

    @Test
    fun deterministicRuntimeFailures_areNotRetryable() {
        assertFalse(isRetryableFailure(IndexOutOfBoundsException("bug")))
        assertFalse(isRetryableFailure(NullPointerException("bug")))
    }

    @Test
    fun ioErrors_areRetryable() {
        assertTrue(isRetryableFailure(IOException("disk busy")))
    }

    @Test
    fun failureReason_keepsTheOriginalMessage() {
        assertEquals("接口地址没有填", failureReason(IllegalStateException("接口地址没有填")))
    }

    /**
     * 没有 message 的异常（超时、被系统收走）不能退化成「请检查网络与模型配置后重试」。
     *
     * 那句话会让人去改配置，而真实原因多半是长任务在后台被系统掐断——
     * 配置一点问题都没有，越改越远。没原因就如实说没原因，并指到能查到原因的地方。
     */
    @Test
    fun failureReason_withoutMessageExplainsTheInterruption() {
        val reason = failureReason(Throwable())
        assertTrue(reason.contains("被中断"))
        assertTrue(reason.contains("诊断日志"))
        assertFalse(reason.contains("请检查网络与模型配置"))
        // Error 也要能给出一句话，不能因为不是 Exception 就留下空白
        assertTrue(failureReason(StackOverflowError()).contains("被中断"))
    }
}
