package com.example.zhishu

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ui.ZhishuRoot
import com.example.zhishu.ui.theme.ZhishuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 启动冒烟测试：复现「点开图标到首页出现」的完整路径。
 *
 * 覆盖 Application 构造（AppContainer / Room builder）、根导航组合、
 * HomeScreen 订阅真实数据库 Flow（触发建库建表）以及 refresh() 的全部查询。
 * 这条路径上任何未捕获异常在真机上都是「一打开就闪退」——
 * 单元测试各查一角时它可能漏网，在这里必须原样炸出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppStartupSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startup_composesHomeAndOpensDatabase() {
        // Robolectric 已按 manifest 实例化 ZhishuApp，container 是真实实现
        val app = ApplicationProvider.getApplicationContext<ZhishuApp>()
        composeRule.setContent {
            ZhishuTheme {
                ZhishuRoot(initialProjectId = null)
            }
        }

        composeRule.waitForIdle()

        // 首页顶栏与空态文案出现 = 组合完成、数据库已打开且查询未抛异常
        composeRule.onNodeWithText("知述").assertExists()
        composeRule.onNodeWithText("把想学的东西，读成一个故事").assertExists()

        // Room 真的打开了：版本号是当前 schema 版本
        assertEquals(18, app.container.database.openHelper.readableDatabase.version)
    }
}
