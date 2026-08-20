package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.ui.viewmodel.DemoSeeder
import com.example.zhishu.ui.viewmodel.SearchViewModel
import com.example.zhishu.ui.viewmodel.deleteProjectCompletely
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 删除项目彻底清理（N6）与单项目搜索可用（N10） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class ProjectLifecycleTest {

    private lateinit var app: ZhishuApp
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        container = app.container
    }

    /** 删除项目后章节正文必须为零，不留隐私数据（N6） */
    @Test
    fun deleteProject_removesEverythingIncludingBlocks() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = container.projectRepository.observeAll().first().first().id
        assertEquals(10, container.chapterRepository.countAllVersionsBlocks(projectId))

        deleteProjectCompletely(container, projectId)

        assertNull(container.projectRepository.get(projectId))
        assertEquals(0, container.chapterRepository.countAllVersionsBlocks(projectId))
        assertEquals(0, container.sourceRepository.countChunks(projectId))
        assertTrue(container.chapterRepository.getPlans(projectId).isEmpty())
        assertTrue(container.knowledgeRepository.getByProject(projectId).isEmpty())
        assertTrue(container.quizRepository.getByProject(projectId).isEmpty())
        assertTrue(container.reviewRepository.getByProject(projectId).isEmpty())
        assertNull(container.progressRepository.get(projectId))
        // 按章节 ID 直查也必须为空
        assertTrue(container.chapterRepository.getAllVersionsBlocks("c1").isEmpty())
    }

    /** 从知识页进入搜索没有带 projectId 时，应自动选中唯一项目（N10） */
    @Test
    fun searchViewModel_autoSelectsSingleProject() = runBlocking {
        DemoSeeder.seed(container)
        val projectId = container.projectRepository.observeAll().first().first().id

        val vm = SearchViewModel(app, initialProjectId = null)
        val selected = withTimeout(10_000) { vm.selectedProjectId.first { it.isNotEmpty() } }
        assertEquals(projectId, selected)

        vm.query.value = "继承"
        vm.search()
        val results = withTimeout(10_000) { vm.results.first { it.isNotEmpty() } }
        assertTrue("单项目路径下搜索不应为空", results.isNotEmpty())
    }

    /** 显式传入的 projectId 优先，不被自动选择覆盖（N10） */
    @Test
    fun searchViewModel_keepsExplicitProjectId() = runBlocking {
        DemoSeeder.seed(container)
        val now = System.currentTimeMillis()
        container.projectRepository.create(
            Project(
                id = "other-project",
                title = "另一个项目",
                topic = "别的主题",
                baseline = "入门",
                depth = "入门",
                dailyMinutes = 10,
                targetDays = 7,
                genre = "悬疑",
                status = ProjectStatus.DRAFT,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val vm = SearchViewModel(app, initialProjectId = "other-project")
        // 等一小会儿，确认初始化逻辑没有把显式值改掉
        delay(200)
        assertEquals("other-project", vm.selectedProjectId.value)
    }
}
