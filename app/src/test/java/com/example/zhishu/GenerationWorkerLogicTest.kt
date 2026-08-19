package com.example.zhishu

import com.example.zhishu.ai.ModelError
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
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

    private fun plan(id: String, order: Int, status: ChapterStatus) = ChapterPlan(
        id = id,
        projectId = "p-1",
        order = order,
        title = "第 $order 章",
        status = status,
    )

    @Test
    fun selectChapters_skipsPublishedAndNeedsReviewByDefault() {
        val plans = listOf(
            plan("c3", 3, ChapterStatus.QUEUED),
            plan("c1", 1, ChapterStatus.PUBLISHED),
            plan("c2", 2, ChapterStatus.NEEDS_REVIEW),
            plan("c4", 4, ChapterStatus.QUIZ_FAILED),
            plan("c5", 5, ChapterStatus.FAILED),
        )
        val selected = selectChaptersToGenerate(plans, includeNeedsReview = false)
        assertEquals(listOf("c3", "c5"), selected.map { it.id })
    }

    /** 显式重试时才纳入待复核与测验待补章节（B3） */
    @Test
    fun selectChapters_includesRetryableWhenRequested() {
        val plans = listOf(
            plan("c1", 1, ChapterStatus.PUBLISHED),
            plan("c2", 2, ChapterStatus.NEEDS_REVIEW),
            plan("c3", 3, ChapterStatus.QUIZ_FAILED),
        )
        val selected = selectChaptersToGenerate(plans, includeNeedsReview = true)
        assertEquals(listOf("c2", "c3"), selected.map { it.id })
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
        assertTrue(isRetryableFailure(ModelError.RateLimited("429")))
        assertTrue(isRetryableFailure(ModelError.Server("500")))
        assertTrue(isRetryableFailure(ModelError.Network("timeout")))
    }

    @Test
    fun preconditionFailures_areNotRetryable() {
        assertFalse(isRetryableFailure(IllegalStateException("项目不存在")))
        assertFalse(isRetryableFailure(IllegalArgumentException("仅支持 https 地址")))
    }

    @Test
    fun ioErrors_areRetryable() {
        assertTrue(isRetryableFailure(IOException("disk busy")))
    }
}
