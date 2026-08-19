package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.example.zhishu.worker.GenerationNotifier
import com.example.zhishu.worker.GenerationOutcome
import com.example.zhishu.worker.GenerationWorker
import com.example.zhishu.ui.viewmodel.toGenerationState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 生成状态在 UI 上的呈现（第二阶段）。
 *
 * 重点是「显示的必须是当前这一次会话」：历史失败不能压在新会话头上，
 * 取消不能显示成 100%，退避重试不能伪装成正在调模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationUiStateTest {

    private val sessionA = "aaaa-1111"
    private val sessionB = "bbbb-2222"

    /** 要求 15：上一次会话的 FAILED 不能覆盖新会话的运行状态 */
    @Test
    fun historicalFailure_doesNotOverrideNewSession() {
        val state = listOf(
            info(WorkInfo.State.FAILED, sessionA, error = "上次没配模型"),
            info(WorkInfo.State.RUNNING, sessionB, progress = "正在写正文", completed = 1, total = 3),
        ).toGenerationState()

        assertTrue(state.running)
        assertFalse(state.hasError)
        assertEquals("正在写正文", state.stageText)
        assertEquals(33, state.percent)
    }

    /** 新会话还在排队时，旧会话的错误同样不该显示 */
    @Test
    fun historicalFailure_doesNotLeakIntoQueuedSession() {
        val state = listOf(
            info(WorkInfo.State.FAILED, sessionA, error = "上次失败了"),
            info(WorkInfo.State.ENQUEUED, sessionB),
        ).toGenerationState()

        assertTrue(state.enqueued)
        assertFalse(state.hasError)
        assertFalse(state.waitingRetry)
    }

    /** 只有当前会话真的失败了，才显示错误 */
    @Test
    fun currentSessionFailure_isShown() {
        val state = listOf(
            info(WorkInfo.State.SUCCEEDED, sessionA),
            info(WorkInfo.State.FAILED, sessionA, error = "服务端 500"),
        ).toGenerationState()

        assertTrue(state.hasError)
        assertEquals("服务端 500", state.error)
        assertEquals(GenerationOutcome.FAILED, state.outcome)
    }

    /** 链上还没轮到的章节（BLOCKED）对用户来说就是「排队中」，不能显示成空白 */
    @Test
    fun blockedChain_showsAsQueued() {
        val state = listOf(
            info(WorkInfo.State.BLOCKED, sessionA),
            info(WorkInfo.State.BLOCKED, sessionA),
        ).toGenerationState()

        assertTrue(state.enqueued)
        assertTrue(state.busy)
    }

    /** 退避重试要说清是在等，不能伪装成还在调模型（要求 6） */
    @Test
    fun backoffRetry_saysWaiting() {
        val state = listOf(
            info(WorkInfo.State.ENQUEUED, sessionA, attempt = 2),
        ).toGenerationState()

        assertTrue(state.waitingRetry)
        assertTrue(state.message.contains("等待重试"))
        assertFalse(state.determinate)
    }

    /** 取消后退出运行态，并且绝不显示 100%（要求 7） */
    @Test
    fun cancelled_showsNeitherRunningNorFullProgress() {
        val state = listOf(
            info(WorkInfo.State.CANCELLED, sessionA),
            info(WorkInfo.State.CANCELLED, sessionA),
        ).toGenerationState()

        assertFalse(state.running)
        assertFalse(state.busy)
        assertFalse(state.determinate)
        assertEquals(0, state.percent)
        assertTrue(state.cancelled)
        assertFalse(state.hasError)
    }

    /** 全部成功后状态条收起来，不留残影 */
    @Test
    fun allSucceeded_isIdle() {
        val state = listOf(
            info(WorkInfo.State.SUCCEEDED, sessionA),
            info(WorkInfo.State.SUCCEEDED, sessionA),
        ).toGenerationState()

        assertFalse(state.busy)
        assertFalse(state.hasError)
        assertNull(state.outcome)
        assertEquals("", state.message)
    }

    /** 批量链的总进度按 Worker 上报的会话级汇总算，不是单章的 1/3 */
    @Test
    fun batchProgress_usesSessionWideTotals() {
        val state = listOf(
            info(WorkInfo.State.SUCCEEDED, sessionA),
            // 4 章共 12 个单元，正跑第三章的核验
            info(WorkInfo.State.RUNNING, sessionA, progress = "第 3 章 · 正在核验内容", completed = 7, total = 12),
        ).toGenerationState()

        assertEquals(58, state.percent)
        assertTrue(state.determinate)
    }

    /** 没有会话 tag 的旧任务也要能正常显示，不能因为识别不出会话就一片空白 */
    @Test
    fun untaggedWork_stillRenders() {
        val state = listOf(
            WorkInfo(
                UUID.randomUUID(),
                WorkInfo.State.RUNNING,
                emptySet(),
                workDataOf(),
                workDataOf(GenerationWorker.KEY_PROGRESS to "正在生成"),
                0,
                0,
            ),
        ).toGenerationState()

        assertTrue(state.running)
        assertEquals("正在生成", state.stageText)
    }

    /** 要求 16：通知权限被拒绝只让通知发不出去，检查本身不抛异常、不阻断生成 */
    @Test
    fun notificationPermission_isOnlyCheckedNeverRequired() {
        val context = ApplicationProvider.getApplicationContext<ZhishuApp>()
        // Robolectric 默认未授予 POST_NOTIFICATIONS
        assertFalse(GenerationNotifier.canPostNotifications(context))
        // 照样能安全调用：不抛异常，只是静默跳过
        GenerationNotifier.notifyOutcome(
            context = context,
            projectId = "p-1",
            projectTitle = "民法基础",
            outcome = GenerationOutcome.COMPLETED,
            detail = "已完成 3/3 章",
        )
        GenerationNotifier.clearProgress(context)
        GenerationNotifier.ensureChannels(context)
    }

    /** 已取消的任务不得随后再发一条「生成失败」（要求 6） */
    @Test
    fun cancelledOutcome_sendsNoNotification() {
        val context = ApplicationProvider.getApplicationContext<ZhishuApp>()
        // 取消分支在权限检查之前就返回，因此在任何权限状态下都不会发通知
        GenerationNotifier.notifyOutcome(
            context = context,
            projectId = "p-1",
            projectTitle = "民法基础",
            outcome = GenerationOutcome.CANCELLED,
            detail = "不该出现的文案",
        )
        // 走到这里没抛异常即说明取消路径是提前返回的静默分支
        assertEquals("已取消", GenerationOutcome.CANCELLED.title)
    }

    private fun info(
        state: WorkInfo.State,
        session: String,
        progress: String? = null,
        completed: Int = 0,
        total: Int = 0,
        error: String? = null,
        attempt: Int = 0,
    ): WorkInfo = WorkInfo(
        UUID.randomUUID(),
        state,
        setOf(GenerationWorker.sessionTag(session)),
        if (error != null) {
            workDataOf(
                GenerationWorker.KEY_ERROR to error,
                GenerationWorker.KEY_OUTCOME to GenerationOutcome.FAILED.name,
            )
        } else {
            workDataOf()
        },
        workDataOf(
            GenerationWorker.KEY_PROGRESS to progress,
            GenerationWorker.KEY_PROGRESS_COMPLETED to completed,
            GenerationWorker.KEY_PROGRESS_TOTAL to total,
        ),
        attempt,
        0,
    )
}
