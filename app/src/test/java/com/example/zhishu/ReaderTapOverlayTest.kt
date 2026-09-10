package com.example.zhishu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.data.prefs.ReaderMode
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.ui.screens.ReaderScreen
import com.example.zhishu.ui.theme.ZhishuTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 阅读器「点击手势层」与正文交互的回归测试。
 *
 * 覆盖层（detectTapGestures 点按呼出菜单/翻页）声明在正文之后，即位于 z 序上层。
 * 如果它把点按全部消费掉，正文里的知识点 chip、来源入口、章末按钮就全都点不动——
 * 用户表现为「点了没反应」。这条测试钉住：chip 必须能打开节点详情。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderTapOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val projectId = "tap-project"
    private val chapterId = "tap-chapter"
    private val versionId = "tap-version"
    private val storyBlockId = "tap-block-story"
    private val explainBlockId = "tap-block-explain"

    @Before
    fun seed() = runBlocking {
        val container = ApplicationProvider.getApplicationContext<ZhishuApp>().container
        container.projectRepository.create(
            Project(
                id = projectId,
                title = "测试书",
                topic = "合同法",
                baseline = "零基础",
                depth = "入门",
                dailyMinutes = 20,
                targetDays = 30,
                genre = "现实主义",
                status = ProjectStatus.READY,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        container.chapterRepository.upsertPlan(
            ChapterPlan(
                id = chapterId,
                projectId = projectId,
                order = 1,
                title = "要约与承诺",
                status = ChapterStatus.PUBLISHED,
                activeVersionId = versionId,
            ),
        )
        container.chapterRepository.upsertVersions(
            listOf(
                ChapterVersion(
                    id = versionId,
                    chapterId = chapterId,
                    projectId = projectId,
                    versionNumber = 1,
                    status = ChapterVersionStatus.PUBLISHED,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            ),
        )
        container.chapterRepository.upsertBlocks(
            listOf(
                ChapterBlock(
                    id = storyBlockId,
                    chapterId = chapterId,
                    versionId = versionId,
                    projectId = projectId,
                    order = 0,
                    text = "林越把合同推到桌子中间。",
                    blockType = BlockType.STORY,
                ),
                ChapterBlock(
                    id = explainBlockId,
                    chapterId = chapterId,
                    versionId = versionId,
                    projectId = projectId,
                    order = 1,
                    text = "要约是希望与他人订立合同的意思表示。",
                    blockType = BlockType.EXPLANATION,
                    knowledgeNodeIds = listOf("tap-node"),
                ),
            ),
        )
        container.knowledgeRepository.upsert(
            KnowledgeNode(
                id = "tap-node",
                projectId = projectId,
                title = "要约的构成",
                definition = "内容具体确定，表明经受要约人承诺即受该意思表示约束。",
            ),
        )
        // 滚动模式：不走翻页测量（Robolectric 下分页测量不可靠），
        // targetBlockId 指向旁注块会触发「自动切到带讲解」，chip 才会渲染
        container.learningPrefs.setReaderMode(ReaderMode.SCROLL)
    }

    @Test
    fun tappingKnowledgeChip_opensNodeSheet() {
        composeRule.setContent {
            ZhishuTheme {
                ReaderScreen(
                    chapterId = chapterId,
                    targetBlockId = explainBlockId,
                    onBack = {},
                    onOpenChapter = {},
                    onOpenQuiz = {},
                )
            }
        }

        composeRule.onNodeWithText("要约的构成").performClick()
        composeRule.waitForIdle()

        // 节点详情弹层的固定文案：只有 selectedNode 被设置才会出现
        composeRule.onNodeWithText("掌握度 0%").assertIsDisplayed()
    }
}
