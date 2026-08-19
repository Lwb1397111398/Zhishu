package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.QuizType
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.ReviewStatus
import com.example.zhishu.di.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 学习闭环相关行为：答题幂等（N12）与复习循环（B2） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LearningLoopTest {

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = ApplicationProvider.getApplicationContext<ZhishuApp>().container
    }

    /** 同一题重复提交只计一次，掌握度不会反复增长（N12） */
    @Test
    fun repeatedAnswer_doesNotAccumulateMastery() = runBlocking {
        val quiz = Quiz(
            id = "q-idem",
            projectId = "p-idem",
            chapterId = "c-idem",
            order = 0,
            question = "问题",
            type = QuizType.TRUE_FALSE,
            options = listOf("对", "错"),
            answer = "对",
            nodeIds = listOf("n-idem"),
        )
        container.quizRepository.upsertAll(listOf(quiz))
        container.knowledgeRepository.upsertAll(
            listOf(
                com.example.zhishu.domain.model.KnowledgeNode(
                    id = "n-idem",
                    projectId = "p-idem",
                    title = "幂等知识点",
                ),
            ),
        )

        // 模拟 ViewModel 的计分逻辑：先查是否已答过
        suspend fun answerOnce() {
            if (container.quizRepository.hasAnswered(quiz.id)) return
            container.quizRepository.recordAnswer(
                AnswerRecord(
                    id = "a-${System.nanoTime()}",
                    quizId = quiz.id,
                    projectId = quiz.projectId,
                    isCorrect = true,
                    answeredAt = 1L,
                ),
            )
            val node = container.knowledgeRepository.getByProject("p-idem").first { it.id == "n-idem" }
            container.knowledgeRepository.updateMastery("n-idem", (node.mastery + 0.15f).coerceIn(0f, 1f))
        }

        answerOnce()
        answerOnce()
        answerOnce()

        val node = container.knowledgeRepository.getByProject("p-idem").first { it.id == "n-idem" }
        assertEquals(0.15f, node.mastery, 0.001f)
        assertEquals(1, container.quizRepository.getAnswers(listOf(quiz.id)).size)
    }

    /** 完成一次复习后仍保持 PENDING，只推后到期时间，才能继续进入复习循环（B2） */
    @Test
    fun completeReview_keepsItemPendingWithLongerInterval() = runBlocking {
        val now = 10_000L
        val item = ReviewItem(
            id = "r-loop",
            projectId = "p-loop",
            nodeId = "n-loop",
            dueAt = now - 1,
            intervalDays = 1,
            status = ReviewStatus.PENDING,
        )
        container.reviewRepository.upsertAll(listOf(item))
        assertTrue(container.reviewRepository.getDue(now).any { it.id == "r-loop" })

        val nextInterval = 3
        container.reviewRepository.upsertAll(
            listOf(
                item.copy(
                    status = ReviewStatus.PENDING,
                    intervalDays = nextInterval,
                    dueAt = now + nextInterval * 86_400_000L,
                ),
            ),
        )
        // 立刻不再到期，但未来仍会到期
        assertTrue(container.reviewRepository.getDue(now).none { it.id == "r-loop" })
        val future = now + nextInterval * 86_400_000L + 1
        val due = container.reviewRepository.getDue(future).first { it.id == "r-loop" }
        assertEquals(ReviewStatus.PENDING, due.status)
        assertEquals(3, due.intervalDays)
    }
}
