package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ui.viewmodel.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 模型选择弹窗的可见性状态（用户反馈：「滑动设置页会自动跳到模型选择弹窗」）。
 *
 * 修复核心：弹窗开关从组合局部状态提升到 ViewModel 的 [SettingsViewModel.pickerProfiles]。
 * 原来 `LaunchedEffect(loaded)` 在卡片滑出屏幕被回收、滑回重新组合时又触发一次——
 * 因为 Loaded 状态存活在 ViewModel 里永不消失。提升到 ViewModel 后，
 * 「弹窗开没开」只有一个事实来源，重组不影响它。本测试守住这个来源的语义。
 *
 * key 是方案 id（新增草稿用 [SettingsViewModel.NEW_PROFILE_KEY]），不再是角色——
 * 配置方案库改造后，搜索模型发生在「某一套方案」上，而不是「某一档分工」上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsPickerStateTest {

    @Test
    fun pickerProfiles_startsEmpty_loadedStateAloneDoesNotOpenPicker() {
        val app = ApplicationProvider.getApplicationContext<ZhishuApp>()
        val vm = SettingsViewModel(app)
        // 初始不弹任何窗：界面重组（模拟滚动回收）读到的就是这个空集
        assertTrue(vm.pickerProfiles.value.isEmpty())
        // 搜索结果（Loaded）留在 ViewModel 里也不会让弹窗复活——可见性只认 pickerProfiles
        vm.closePicker(1L)
        assertTrue(vm.pickerProfiles.value.isEmpty())
    }

    @Test
    fun openAndClosePicker_tracksProfilesIndependently() {
        val app = ApplicationProvider.getApplicationContext<ZhishuApp>()
        val vm = SettingsViewModel(app)
        vm.openPicker(1L)
        vm.openPicker(2L)
        assertEquals(setOf(1L, 2L), vm.pickerProfiles.value)
        // 关掉一个不影响另一个：每张方案卡各自受控
        vm.closePicker(1L)
        assertEquals(setOf(2L), vm.pickerProfiles.value)
    }

    /** 新增草稿（没有真实 id）也要能独立开合选择弹窗 */
    @Test
    fun draftProfile_usesSentinelKey() {
        val app = ApplicationProvider.getApplicationContext<ZhishuApp>()
        val vm = SettingsViewModel(app)
        vm.openPicker(SettingsViewModel.NEW_PROFILE_KEY)
        assertEquals(setOf(SettingsViewModel.NEW_PROFILE_KEY), vm.pickerProfiles.value)
        vm.closePicker(SettingsViewModel.NEW_PROFILE_KEY)
        assertTrue(vm.pickerProfiles.value.isEmpty())
    }
}
