package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.worker.EnqueueResult
import com.example.zhishu.worker.GenerationEnqueuer
import com.example.zhishu.worker.GenerationWorker
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
 * 真实 WorkManager 的调度行为（第二阶段）。
 *
 * 这一轮的关键风险恰恰在调度本身，所以不测抽出来的纯函数，而是用
 * [WorkManagerTestInitHelper] 起一个真的 WorkManager：唯一工作名、KEEP 策略、
 * 链式结构、取消传播，全都由 WorkManager 自己判定。
 *
 * 测试驱动器不自动执行任务（约束未满足时保持 ENQUEUED/BLOCKED），
 * 所以这里验证的是「排了什么」，不会真去调模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationSchedulingTest {

    private lateinit var container: AppContainer
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<ZhishuApp>()
        container = context.container
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setMinimumLoggingLevel(android.util.Log.ERROR)
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    // --- 1/2/3：防重复入队 ---

    /** 同一项目连续入队两次，只有一个有效生成链——连点不会排两个收费任务 */
    @Test
    fun sameProject_secondEnqueue_isRejected() = runBlocking {
        seedProject("p-1")
        val first = GenerationEnqueuer.knowledgeMap(app(), "p-1")
        val second = GenerationEnqueuer.knowledgeMap(app(), "p-1")

        assertTrue(first is EnqueueResult.Started)
        assertEquals(EnqueueResult.AlreadyRunning, second)
        // 唯一链上仍然只有第一次那一个 WorkRequest
        assertEquals(1, infos("p-1").size)
    }

    /**
     * KEEP 不会追加重复收费任务：即使绕过 isActive 的预检直接再入队一条链，
     * 唯一名 + KEEP 也会把它挡掉。
     */
    @Test
    fun keepPolicy_doesNotAppendDuplicateWork() = runBlocking {
        seedProject("p-1")
        seedChapters("p-1", 3)
        val plans = container.chapterRepository.getPlans("p-1")

        GenerationEnqueuer.chapters(app(), "p-1", plans)
        val afterFirst = infos("p-1").map { it.id }.toSet()

        // 直接再调一次入队（模拟预检与入队之间那个时间窗里的第二次点击）
        GenerationEnqueuer.chapters(app(), "p-1", plans)
        val afterSecond = infos("p-1").map { it.id }.toSet()

        assertEquals(afterFirst, afterSecond)
    }

    /** 不同项目可以同时各有一个任务，不能用全局锁 */
    @Test
    fun differentProjects_runIndependently() = runBlocking {
        seedProject("p-1")
        seedProject("p-2")
        assertTrue(GenerationEnqueuer.knowledgeMap(app(), "p-1") is EnqueueResult.Started)
        assertTrue(GenerationEnqueuer.knowledgeMap(app(), "p-2") is EnqueueResult.Started)

        assertEquals(1, infos("p-1").size)
        assertEquals(1, infos("p-2").size)
        assertTrue(GenerationEnqueuer.isActive(app(), "p-1"))
        assertTrue(GenerationEnqueuer.isActive(app(), "p-2"))
    }

    // --- 7/8/9：批量拆成链 ---

    /** 批量生成一章对应一个 WorkRequest，外加一个不调模型的链尾收尾 */
    @Test
    fun batch_createsOneRequestPerChapter() = runBlocking {
        seedProject("p-1")
        seedChapters("p-1", 4)
        val plans = container.chapterRepository.getPlans("p-1")

        val result = GenerationEnqueuer.chapters(app(), "p-1", plans)
        assertEquals(EnqueueResult.Started(4), result)
        // 4 章 + 1 收尾
        assertEquals(5, infos("p-1").size)
    }

    /**
     * 章节按顺序串行：只有第一章处于可运行状态，其余都被链阻塞。
     * runningState / carryOver 有顺序依赖，并行推进会写乱滚动记忆。
     */
    @Test
    fun batch_runsChaptersSequentially() = runBlocking {
        seedProject("p-1")
        seedChapters("p-1", 3)
        val plans = container.chapterRepository.getPlans("p-1")
        GenerationEnqueuer.chapters(app(), "p-1", plans)

        val states = infos("p-1").map { it.state }
        // 链首等约束（网络），其余等前一环完成
        assertEquals(1, states.count { it == WorkInfo.State.ENQUEUED })
        assertEquals(3, states.count { it == WorkInfo.State.BLOCKED })
    }

    /** 已发布章节不会重复入队——包括旧库里正文可读的 QUIZ_FAILED */
    @Test
    fun publishedChapters_areNotEnqueued() = runBlocking {
        seedProject("p-1")
        container.chapterRepository.upsertPlans(
            listOf(
                plan("p-1", "c1", 1, ChapterStatus.PUBLISHED),
                plan("p-1", "c2", 2, ChapterStatus.QUIZ_FAILED),
                plan("p-1", "c3", 3, ChapterStatus.QUEUED),
            ),
        )
        val plans = container.chapterRepository.getPlans("p-1")
        val result = GenerationEnqueuer.chapters(app(), "p-1", plans)

        // 只有 c3 要生成，所以 1 章 + 1 收尾
        assertEquals(EnqueueResult.Started(1), result)
        assertEquals(2, infos("p-1").size)
    }

    /** 全部已发布时不排任何任务，也不假装开始了 */
    @Test
    fun allPublished_enqueuesNothing() = runBlocking {
        seedProject("p-1")
        container.chapterRepository.upsertPlans(
            listOf(plan("p-1", "c1", 1, ChapterStatus.PUBLISHED)),
        )
        val result = GenerationEnqueuer.chapters(
            app(),
            "p-1",
            container.chapterRepository.getPlans("p-1"),
        )
        assertTrue(result is EnqueueResult.Nothing)
        assertTrue(infos("p-1").isEmpty())
    }

    /** 一键开讲串成三步一链：知识梳理 → 路线规划 → 首章，再加收尾 */
    @Test
    fun oneTap_chainsThreeStages() = runBlocking {
        seedProject("p-1")
        val result = GenerationEnqueuer.oneTap(app(), "p-1", needsMap = true, needsBible = true)
        assertEquals(EnqueueResult.Started(3), result)
        assertEquals(4, infos("p-1").size)
    }

    /** 已有知识地图时跳过那一步，不会无条件要求 FAST 配置 */
    @Test
    fun oneTap_skipsCompletedStages() = runBlocking {
        seedProject("p-1")
        val result = GenerationEnqueuer.oneTap(app(), "p-1", needsMap = false, needsBible = false)
        // 只剩首章 + 收尾
        assertEquals(EnqueueResult.Started(1), result)
        assertEquals(2, infos("p-1").size)
    }

    // --- 4/5：取消 ---

    /** 取消 unique work 会取消整条章节链，包括还没轮到的那几章 */
    @Test
    fun cancel_cancelsWholeChain() = runBlocking {
        seedProject("p-1")
        seedChapters("p-1", 3)
        GenerationEnqueuer.chapters(app(), "p-1", container.chapterRepository.getPlans("p-1"))
        assertTrue(GenerationEnqueuer.isActive(app(), "p-1"))

        GenerationEnqueuer.cancel(app(), "p-1")

        val states = infos("p-1").map { it.state }
        assertTrue(states.isNotEmpty())
        assertTrue(states.all { it == WorkInfo.State.CANCELLED })
        assertFalse(GenerationEnqueuer.isActive(app(), "p-1"))
    }

    /** 取消一个项目不影响另一个项目正在排的任务 */
    @Test
    fun cancel_doesNotTouchOtherProjects() = runBlocking {
        seedProject("p-1")
        seedProject("p-2")
        GenerationEnqueuer.knowledgeMap(app(), "p-1")
        GenerationEnqueuer.knowledgeMap(app(), "p-2")

        GenerationEnqueuer.cancel(app(), "p-1")

        assertTrue(infos("p-1").all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(GenerationEnqueuer.isActive(app(), "p-2"))
    }

    /** 取消之后按钮要恢复可用：能立刻排下一次生成 */
    @Test
    fun afterCancel_canEnqueueAgain() = runBlocking {
        seedProject("p-1")
        GenerationEnqueuer.knowledgeMap(app(), "p-1")
        GenerationEnqueuer.cancel(app(), "p-1")

        val again = GenerationEnqueuer.knowledgeMap(app(), "p-1")
        assertTrue(again is EnqueueResult.Started)
    }

    // --- 15：会话隔离 ---

    /**
     * 同一次入队的所有 WorkRequest 共用一个会话 tag。
     * UI 靠它把当前会话与上一次失败的 WorkInfo 分开，否则旧错误会一直压在新会话头上。
     */
    @Test
    fun oneEnqueue_sharesASingleSessionTag() = runBlocking {
        seedProject("p-1")
        seedChapters("p-1", 3)
        GenerationEnqueuer.chapters(app(), "p-1", container.chapterRepository.getPlans("p-1"))

        val sessions = infos("p-1")
            .map { info -> info.tags.filter { it.startsWith("gen-session:") } }
        assertTrue(sessions.all { it.size == 1 })
        assertEquals(1, sessions.flatten().distinct().size)
    }

    /**
     * 新会话不会被上一次的 WorkInfo 污染（要求 15）。
     *
     * 上一条链已经结束时，KEEP 会用新链整体替换它——旧的 CANCELLED / FAILED 记录
     * 一并被清掉，唯一链上只剩当前会话。所以 UI 拿到的状态天然就是这一次的，
     * 不会挂着上次的错误不放。
     */
    @Test
    fun newSession_replacesFinishedOne() = runBlocking {
        seedProject("p-1")
        GenerationEnqueuer.knowledgeMap(app(), "p-1")
        val firstSession = sessionTagOf("p-1")
        GenerationEnqueuer.cancel(app(), "p-1")
        // 取消后旧链确实是 CANCELLED
        assertTrue(infos("p-1").all { it.state == WorkInfo.State.CANCELLED })

        GenerationEnqueuer.storyBible(app(), "p-1")
        val sessions = infos("p-1").flatMap { info ->
            info.tags.filter { it.startsWith("gen-session:") }
        }.distinct()

        // 只剩新会话，旧会话的记录已经不在链上了
        assertEquals(1, sessions.size)
        assertFalse(firstSession in sessions)
        // 而且新链是可运行的，没有继承旧的取消状态
        assertTrue(infos("p-1").none { it.state == WorkInfo.State.CANCELLED })
    }

    // --- 辅助 ---

    private fun app(): ZhishuApp = ApplicationProvider.getApplicationContext()

    private fun infos(projectId: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(GenerationWorker.uniqueWorkName(projectId)).get()

    private fun sessionTagOf(projectId: String): String =
        infos(projectId).first().tags.first { it.startsWith("gen-session:") }

    private suspend fun seedProject(id: String) {
        container.projectRepository.create(
            Project(
                id = id,
                title = "项目 $id",
                topic = "民法",
                baseline = "入门",
                depth = "入门",
                dailyMinutes = 20,
                targetDays = 30,
                genre = "悬疑",
                status = ProjectStatus.OUTLINE,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun seedChapters(projectId: String, count: Int) {
        container.chapterRepository.upsertPlans(
            (1..count).map { plan(projectId, "$projectId-c$it", it, ChapterStatus.QUEUED) },
        )
    }

    private fun plan(
        projectId: String,
        id: String,
        order: Int,
        status: ChapterStatus,
    ) = ChapterPlan(
        id = id,
        projectId = projectId,
        order = order,
        title = "第 $order 章",
        status = status,
    )
}
