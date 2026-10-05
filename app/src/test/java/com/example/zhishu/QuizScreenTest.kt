package com.example.zhishu

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.QuizType
import com.example.zhishu.ui.screens.QuizScreen
import com.example.zhishu.ui.theme.ZhishuTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 答题结果必须在提交后立即可见，提交按钮消失（N1）。
 * 这条测试专门覆盖「UI 直接读 StateFlow.value 导致不重组」的回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuizScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val chapterId = "ui-chapter"
    private val versionId = "ui-chapter-v1"

    @Before
    fun seedQuiz() = runBlocking {
        val container = ApplicationProvider.getApplicationContext<ZhishuApp>().container
        // 自测题按「章节 + 正文版本」取，所以章节行和版本行都要有（第三阶段 3A）
        container.chapterRepository.upsertPlan(
            ChapterPlan(
                id = chapterId,
                projectId = "ui-project",
                order = 1,
                title = "遗产的开始",
                status = ChapterStatus.PUBLISHED,
                activeVersionId = versionId,
            ),
        )
        container.chapterRepository.upsertVersions(
            listOf(
                ChapterVersion(
                    id = versionId,
                    chapterId = chapterId,
                    projectId = "ui-project",
                    versionNumber = 1,
                    status = ChapterVersionStatus.PUBLISHED,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            ),
        )
        container.quizRepository.upsertAll(
            listOf(
                Quiz(
                    id = "ui-q1",
                    projectId = "ui-project",
                    chapterId = chapterId,
                    versionId = versionId,
                    order = 0,
                    question = "继承从什么时候开始？",
                    type = QuizType.CASE,
                    options = listOf("立遗嘱时", "被继承人死亡时"),
                    answer = "被继承人死亡时",
                    explanation = "民法典规定继承从被继承人死亡时开始。",
                    nodeIds = emptyList(),
                ),
            ),
        )
    }

    @Test
    fun submitCorrectAnswer_showsResultAndHidesSubmitButton() {
        composeRule.setContent {
            ZhishuTheme { QuizScreen(chapterId = chapterId, onBack = {}) }
        }

        // 题目来自 Room Flow 的异步首发射，Robolectric 上偶发晚于断言——显式等待出现
        waitForQuizLoaded()
        composeRule.onNodeWithText("继承从什么时候开始？").assertIsDisplayed()
        composeRule.onNodeWithText("被继承人死亡时").performClick()
        composeRule.onNodeWithText("提交答案").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("答对了").assertIsDisplayed()
        composeRule.onNodeWithText("正确答案：被继承人死亡时").assertIsDisplayed()
        // 已作答后提交按钮必须消失，说明 UI 真正订阅到了状态变化
        composeRule.onAllNodesWithText("提交答案").assertCountEquals(0)
    }

    @Test
    fun submitWrongAnswer_showsWrongResult() {
        composeRule.setContent {
            ZhishuTheme { QuizScreen(chapterId = chapterId, onBack = {}) }
        }

        waitForQuizLoaded()
        composeRule.onNodeWithText("立遗嘱时").performClick()
        composeRule.onNodeWithText("提交答案").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("答错了").assertIsDisplayed()
    }

    /** 等题目真正渲染出来（Room Flow 首发射是异步的，直接断言会撞时序） */
    private fun waitForQuizLoaded() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("继承从什么时候开始？")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}
