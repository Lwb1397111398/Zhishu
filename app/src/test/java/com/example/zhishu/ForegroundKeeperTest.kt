package com.example.zhishu

import com.example.zhishu.worker.ForegroundKeeper
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 前台服务启动失败后的定时重试（2026-09-07）。
 *
 * Android 12+ 后台启动 FGS 被拒（ForegroundServiceStartNotAllowedException）时，
 * 重试循环要按周期反复尝试，用户切回 app 后自动补上保护；成功即停、stop 不再试、
 * 重复 start 不叠加循环。
 */
class ForegroundKeeperTest {

    @Test
    fun `按周期重试直到成功为止`() = runTest {
        var calls = 0
        val keeper = ForegroundKeeper(
            attempt = { calls++; calls >= 3 },
            retryIntervalMillis = 10,
            scope = backgroundScope,
        )
        keeper.start()
        advanceTimeBy(100)
        // 前两次失败继续试，第三次成功即停——不会多试
        assertEquals(3, calls)
        advanceTimeBy(100)
        assertEquals(3, calls)
    }

    @Test
    fun `stop 之后不再重试`() = runTest {
        var calls = 0
        val keeper = ForegroundKeeper(
            attempt = { calls++; false },
            retryIntervalMillis = 10,
            scope = backgroundScope,
        )
        keeper.start()
        advanceTimeBy(50)
        keeper.stop()
        val at = calls
        advanceTimeBy(200)
        assertEquals(at, calls)
    }

    @Test
    fun `重复 start 不叠加循环`() = runTest {
        var calls = 0
        val keeper = ForegroundKeeper(
            attempt = { calls++; false },
            retryIntervalMillis = 10,
            scope = backgroundScope,
        )
        keeper.start()
        keeper.start()
        keeper.start()
        advanceTimeBy(100)
        runCurrent() // advanceTimeBy 不含恰好在该时刻到期的任务，补执行
        // 10 个周期、单循环 → 10 次；若叠加成 3 个循环会是 30 次
        assertEquals(10, calls)
    }
}
