package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.prefs.LearningPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 自动生成总开关的持久化与默认值（自动化闭环 Task 5）。
 *
 * 两条断言各守一件事：默认值对不对（用户要的「默认允许」）、改了会不会落盘
 * （Robolectric 每个 @Test 新建 Application，所以换一个实例读 = 验的是磁盘而非内存）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LearningPrefsAutoAdvanceTest {

    private fun prefs() = LearningPrefs(ApplicationProvider.getApplicationContext())

    /** 默认开启（用户明确要「默认让其加入自动允许」，与 spacedReview 默认关相反） */
    @Test
    fun defaultsToEnabled() {
        assertTrue(prefs().generationAutoAdvance.value)
    }

    @Test
    fun setPersistedAndLive() {
        val p = prefs()
        p.setGenerationAutoAdvance(false)
        assertFalse(p.generationAutoAdvance.value)
        // 换一个实例读同一 SharedPreferences：确认落盘而非只改内存
        assertFalse(prefs().generationAutoAdvance.value)
    }
}
