package com.example.zhishu

import com.example.zhishu.worker.AdvanceDecision
import com.example.zhishu.worker.decideAdvance
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动闭环的决策内核（纯函数，不碰数据库不碰 WorkManager）。
 *
 * 输入都是「从库里数出来的东西」，所以判定表能用最便宜的普通测试穷举。
 * 把决策从 Worker 里剥出来，是因为 Worker 测起来最贵也最容易假绿；
 * 真正会出错的边界（待复核算不算阻断、pending 为空算不算讲完）全在这里。
 */
class AdvanceDecisionTest {

    private fun d(auto: Boolean, blocking: Int, targets: Int, pending: Int) =
        decideAdvance(
            autoOn = auto,
            volumeBlockingCount = blocking,
            generatableCount = targets,
            pendingKnowledgeCount = pending,
        )

    @Test
    fun switchOff_stops() =
        assertEquals(AdvanceDecision.StoppedBySwitch, d(auto = false, blocking = 3, targets = 3, pending = 5))

    @Test
    fun stillGenerating_hasWork_continuesVolume() =
        assertEquals(AdvanceDecision.ContinueCurrentVolume, d(auto = true, blocking = 2, targets = 2, pending = 5))

    /** 卷没完但可生成数为 0 = 剩下的都卡在「契约未生成」：停住交给用户，不死循环 */
    @Test
    fun stillGenerating_nothingGeneratable_pausesForUser() =
        assertEquals(AdvanceDecision.PausedForUser, d(auto = true, blocking = 1, targets = 0, pending = 5))

    @Test
    fun volumeDone_pendingKnowledge_plansNextVolume() =
        assertEquals(AdvanceDecision.PlanNextVolume, d(auto = true, blocking = 0, targets = 0, pending = 4))

    /** 刹车点：主线讲完才停，扩展阅读留给人手动 */
    @Test
    fun volumeDone_noPending_mainLineDone() =
        assertEquals(AdvanceDecision.MainLineDone, d(auto = true, blocking = 0, targets = 0, pending = 0))

    /** 本卷收尾、还有 pending：即使当前 targets 为 0 也续卷（pending 优先于卷内空白） */
    @Test
    fun volumeDone_zeroTargetsButPending_plansNextVolume() =
        assertEquals(AdvanceDecision.PlanNextVolume, d(auto = true, blocking = 0, targets = 0, pending = 1))
}
