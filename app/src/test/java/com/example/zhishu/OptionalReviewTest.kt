package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.prefs.LearningPrefs
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.ui.viewmodel.DemoSeeder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 复习降级为可选功能后的行为（第一阶段：故事优先）。
 *
 * 关键约束：数据一个都不删，只是默认不进主线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OptionalReviewTest {

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = ApplicationProvider.getApplicationContext<ZhishuApp>().container
        // 每个用例都从「关闭」开始，避免共享 SharedPreferences 互相影响
        container.learningPrefs.setSpacedReviewEnabled(false)
    }

    /** 间隔复习默认关闭：不主动开就不会有复习任务冒出来 */
    @Test
    fun spacedReview_defaultsToDisabled() {
        assertFalse(LearningPrefs.DEFAULT_SPACED_REVIEW)
        assertFalse(container.learningPrefs.spacedReviewEnabled.value)
    }

    /** 开关能来回切换并被记住 */
    @Test
    fun spacedReview_togglePersists() {
        container.learningPrefs.setSpacedReviewEnabled(true)
        assertTrue(container.learningPrefs.spacedReviewEnabled.value)
        assertTrue(
            LearningPrefs(ApplicationProvider.getApplicationContext())
                .spacedReviewEnabled.value,
        )

        container.learningPrefs.setSpacedReviewEnabled(false)
        assertFalse(container.learningPrefs.spacedReviewEnabled.value)
    }

    /** 关掉复习不会动到已有的复习数据与答题记录（示例项目里就带着两条） */
    @Test
    fun disablingReview_keepsExistingData() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = container.projectRepository.observeAll().first().first().id
        container.learningPrefs.setSpacedReviewEnabled(false)
        assertEquals(2, container.reviewRepository.getByProject(projectId).size)
    }

    /** 没做测验也能一路读下去：章节可读性只看正文状态，与答题记录无关 */
    @Test
    fun readingNextChapter_doesNotRequireQuiz() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = container.projectRepository.observeAll().first().first().id
        val plans = container.chapterRepository.getPlans(projectId).sortedBy { it.order }
        assertTrue(plans.size >= 2)

        // 一道题都没答
        val quizIds = container.quizRepository.getByProject(projectId).map { it.id }
        assertTrue(container.quizRepository.getAnswers(quizIds).isEmpty())

        // 后续章节依然是已发布、可读状态
        assertTrue(plans.drop(1).all { it.status == ChapterStatus.PUBLISHED })
        assertTrue(container.chapterRepository.getBlocks(plans[1].id).isNotEmpty())
    }
}
