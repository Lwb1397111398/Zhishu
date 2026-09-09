package com.example.zhishu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.example.zhishu.domain.model.GapType
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.ui.screens.GapActionRow
import com.example.zhishu.ui.theme.ZhishuTheme
import com.example.zhishu.ui.viewmodel.TrustEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「逐条查看往下一滑就闪退」（用户报的 bug）的回归守卫——纯逻辑，不用渲染。
 *
 * 病因：TrustCenterScreen 的 LazyColumn 用 `${kind}-${chapterId}-${blockId}-${title}`
 * 当 key。两个字段天生会重名：OPEN_ISSUE 的 title 是 issue.type.label（同一块上
 * 同类问题重复），UNCONFIRMED_CITATION 的 title 是一句固定文案；而 blockId 在源块
 * 换版后为 null。于是同一章里多条记录拼出同一个 key，Compose 抛
 * "Key was already used"——偏偏只在坏 key 滚进屏幕那一帧触发，看起来就是「一滑就崩」。
 *
 * 修法：列表加 sourceId（citation.id / issue.id，各自唯一），key 只认它。
 * 这条测试复刻崩溃数据的形状，证明新 key 不撞车。
 */
class TrustCenterKeyUniquenessTest {

    private fun entry(sourceId: String, kind: TrustEntry.Kind, title: String, blockId: String?) =
        TrustEntry(
            sourceId = sourceId,
            kind = kind,
            chapterId = "ch1",
            chapterOrder = 1,
            chapterTitle = "第一章",
            blockId = blockId,
            title = title,
            detail = "d",
            excerpt = "e",
        )

    @Test
    fun oldStyleKey_collides_newSourceIdKey_doesNot() {
        // 同章、同类、同标题、blockId 都为 null 的多条——正是旧 key 崩的那一组
        val entries = listOf(
            entry("cite-1", TrustEntry.Kind.UNCONFIRMED_CITATION, "模型给的出处没能对上资料", null),
            entry("cite-2", TrustEntry.Kind.UNCONFIRMED_CITATION, "模型给的出处没能对上资料", null),
            entry("issue-1", TrustEntry.Kind.OPEN_ISSUE, "出处存疑", null),
            entry("issue-2", TrustEntry.Kind.OPEN_ISSUE, "出处存疑", null),
        )

        // 旧 key：确实会撞（证明病因判断成立）
        val oldKeys = entries.map { "${it.kind}-${it.chapterId}-${it.blockId}-${it.title}" }
        assertTrue("旧 key 未复现撞车，说明没测到点上", oldKeys.size != oldKeys.distinct().size)

        // 新 key（渲染层用的就是这个表达式）：不撞
        val newKeys = entries.map { "${it.kind}-${it.sourceId}" }
        assertEquals("sourceId key 仍撞车", entries.size, newKeys.distinct().size)
    }
}

/**
 * 「知道了」按钮显示不出那三个字（用户报的 bug）的回归守卫。
 *
 * 病因：一行里「补上「<模型给的标题>」」按钮的文本长度完全不可控，旧布局里
 * 它没有 weight、「知道了」也没有布局保护——长标题会把「知道了」整颗挤出屏幕，
 * 那三个字就"有时候显示不出来"（取决于模型这次给多长的标题）。
 *
 * 修法：「补上」按钮吃剩余宽度并省略号收尾，「知道了」不带 weight、先按固宽布局。
 * 这条测试用一条 40 字的夸张标题渲染，断言「知道了」真的存在、可见、且有非零宽度。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SqueezedButtonGuardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longSuggestedTitle_doesNotEvictDismissButton() {
        val longTitle = "关于合同违约责任中可预见性规则在司法实践中的适用边界与例外情形"
        val gap = KnowledgeGap(
            id = "gap-1",
            projectId = "p",
            description = "缺少关于缔约过失责任的讲解",
            gapType = GapType.MISSING_NODE.name,
            suggestedTitle = longTitle,
        )
        // 手机宽度：给一个足够窄的容器，逼出旧布局会崩的那种挤压
        composeRule.setContent {
            ZhishuTheme {
                Box(Modifier.width(360.dp)) {
                    GapActionRow(
                        gap = gap,
                        patching = false,
                        onPatchGap = {},
                        onDismissGap = {},
                    )
                }
            }
        }
        // 补上按钮存在（长标题被省略号收尾，而不是撑破行）
        val patch = composeRule.onNodeWithText("补上「$longTitle」", substring = true)
        patch.assertIsDisplayed()
        // 关键：「知道了」没被挤没——存在、可见、宽度非零
        val dismiss = composeRule.onNodeWithText("知道了")
        dismiss.assertIsDisplayed()
        val bounds = dismiss.fetchSemanticsNode().boundsInRoot
        assertTrue("「知道了」被挤到零宽就是回归", bounds.width > 1f)
    }
}
