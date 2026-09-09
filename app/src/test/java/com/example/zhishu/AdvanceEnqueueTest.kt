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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自动闭环的入队形状（自动化闭环 Task 6）。
 *
 * 测的是「排了什么」而不是「Worker 跑起来会怎样」（后者是 Task 7 + 纯函数测试）：
 * 1. 开自动时链尾多一个 ADVANCE，关自动与改造前逐字节一致——手动模式零风险承诺的凭据；
 * 2. 续订 API 往活跃链尾追加工作，唯一名不变——一次 cancelUniqueWork 仍能清掉整条，
 *    自动延长不会把「取消」变成只取消一半。
 *
 * 同 GenerationSchedulingTest：WorkManager 测试驱动器不自动执行任务，不会真调模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdvanceEnqueueTest {

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

    /** 一键开讲开自动时链尾多一个 ADVANCE；关自动与今天一模一样 */
    @Test
    fun oneTap_autoAppendsAdvance() = runBlocking {
        seedProject("p-1")
        val result = GenerationEnqueuer.oneTap(
            app(), "p-1", needsMap = true, needsBible = true, autoAdvance = true,
        )
        // 主干 + 路线 + 首章 = 3 个烧模型的步（ADVANCE 不烧模型，不加计）
        assertEquals(EnqueueResult.Started(3), result)
        // map + bible + 首章 + 收尾 + ADVANCE = 5 个 WorkRequest
        assertEquals(5, infos("p-1").size)
        // 同一次入队共用一个会话 tag（ADVANCE 也在其中 → 一次取消全清）
        assertEquals(
            1,
            infos("p-1")
                .flatMap { it.tags.filter { t -> t.startsWith("gen-session:") } }
                .distinct().size,
        )
    }

    @Test
    fun oneTap_manualKeepsLegacyChainShape() = runBlocking {
        seedProject("p-1")
        val result = GenerationEnqueuer.oneTap(
            app(), "p-1", needsMap = true, needsBible = true, autoAdvance = false,
        )
        assertEquals(EnqueueResult.Started(3), result)
        assertEquals(4, infos("p-1").size)   // 改造前：主干+路线+首章+收尾
    }

    /** 续订 API：往已有链尾追加 2 章 + 收尾 + ADVANCE，链长 +4，唯一名与会话隔离不变 */
    @Test
    fun appendAutoChunk_extendsChainUnderSameUniqueName() = runBlocking {
        seedProject("p-1")
        GenerationEnqueuer.storyBible(app(), "p-1")
        val before = infos("p-1").size

        GenerationEnqueuer.appendAutoChunk(
            context = app(), projectId = "p-1", session = "s-test",
            chapterIds = listOf("p-1-c1", "p-1-c2"),
            chapterLabels = listOf("第 1 章", "第 2 章"),
            unitsTotal = 6,
        )
        assertEquals(before + 4, infos("p-1").size)

        // 取消一次清全链——续订进来的工作也在同一条唯一名下
        GenerationEnqueuer.cancel(app(), "p-1")
        assertEquals(
            true,
            infos("p-1").all { it.state == WorkInfo.State.CANCELLED },
        )
    }

    /** 续订「规划下一卷」：挂 PLAN_NEXT_VOLUME + ADVANCE 两步，同样可被一次取消清掉 */
    @Test
    fun appendVolumePlan_appendsTwoSteps() = runBlocking {
        seedProject("p-1")
        GenerationEnqueuer.storyBible(app(), "p-1")
        val before = infos("p-1").size

        GenerationEnqueuer.appendVolumePlan(app(), "p-1", session = "s-test")
        assertEquals(before + 2, infos("p-1").size)
    }

    // --- 辅助（复制自 GenerationSchedulingTest，同包各自私有） ---

    private fun app(): ZhishuApp = ApplicationProvider.getApplicationContext()

    private fun infos(projectId: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(GenerationWorker.uniqueWorkName(projectId)).get()

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

    @Suppress("unused")
    private suspend fun seedChapters(projectId: String, count: Int) {
        container.chapterRepository.upsertPlans(
            (1..count).map {
                ChapterPlan(
                    id = "$projectId-c$it",
                    projectId = projectId,
                    order = it,
                    title = "第 $it 章",
                    status = ChapterStatus.QUEUED,
                )
            },
        )
    }
}
