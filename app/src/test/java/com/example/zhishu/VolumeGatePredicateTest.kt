package com.example.zhishu

import com.example.zhishu.domain.model.ChapterStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卷门判定（自动化闭环 Task 1）。
 *
 * isVolumeBlocking = 「这一章还在生成流程里，下一卷的开场还接不上」。
 * 判据从 isReadable 换成它，是为了让待复核/失败不再永久堵死续卷入口：
 * 带问题发布的章正文可读，下一卷要接的是它已发布的结尾，不该等一个用户可能永远不去点的复核。
 */
class VolumeGatePredicateTest {

    @Test
    fun generating_states_blockTheVolume() {
        assertTrue(ChapterStatus.QUEUED.isVolumeBlocking)
        assertTrue(ChapterStatus.RUNNING.isVolumeBlocking)
        assertTrue(ChapterStatus.DRAFT.isVolumeBlocking)
        assertTrue(ChapterStatus.VERIFYING.isVolumeBlocking)
    }

    @Test
    fun settled_states_doNotBlockTheVolume() {
        assertFalse(ChapterStatus.PUBLISHED.isVolumeBlocking)
        assertFalse(ChapterStatus.QUIZ_FAILED.isVolumeBlocking)
        // 这两个正是今天把「继续下一卷」永久堵死的值
        assertFalse(ChapterStatus.NEEDS_REVIEW.isVolumeBlocking)
        assertFalse(ChapterStatus.FAILED.isVolumeBlocking)
    }
}
