package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.ui.viewmodel.CreateProjectViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 创建项目必须是「全部资料解析成功 → 原子写入」，
 * 任何一份资料失败都不得留下半成品项目（N5）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CreateProjectViewModelTest {

    private lateinit var app: ZhishuApp
    private lateinit var container: AppContainer
    private lateinit var vm: CreateProjectViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        container = app.container
        vm = CreateProjectViewModel(app)
        vm.title.value = "民法基础"
        vm.topic.value = "继承编"
        // 这些用例测的是"有资料"路径，显式选文档模式（默认已改为 AI 自备知识）
        vm.sourceMode.value = com.example.zhishu.domain.model.SourceMode.DOCUMENTS
    }

    @Test
    fun createProject_allSourcesValid_writesProjectAndChunks() = runBlocking {
        vm.addTextSource("第一份", "第一段内容。\n第二段内容。")
        vm.addTextSource("第二份", "另一份资料内容。")

        val result = vm.createProject()
        assertTrue(result.isSuccess)
        val id = result.getOrThrow()

        assertEquals("民法基础", container.projectRepository.get(id)?.title)
        assertEquals(2, container.sourceRepository.getByProject(id).size)
        assertTrue(container.sourceRepository.countChunks(id) >= 2)
    }

    /** 第二份资料解析失败：项目、资料、片段都不应存在（N5） */
    @Test
    fun createProject_secondSourceFails_writesNothing() = runBlocking {
        val projectsBefore = container.projectRepository.observeAll().first().size
        vm.addTextSource("第一份", "第一段内容。")
        // http 明文地址在解析阶段即被拒绝，模拟第二份资料失败
        vm.addUrlSource("http://insecure.example.com/page")

        val result = vm.createProject()
        assertTrue(result.isFailure)

        val projectsAfter = container.projectRepository.observeAll().first()
        assertEquals(projectsBefore, projectsAfter.size)
        assertTrue(projectsAfter.none { it.title == "民法基础" })
    }

    /** 内容重复的资料只写一份，不会因唯一约束中断创建 */
    @Test
    fun createProject_duplicateContent_keepsOneSource() = runBlocking {
        vm.addTextSource("A", "完全相同的内容")
        vm.addTextSource("B", "完全相同的内容")

        val id = vm.createProject().getOrThrow()
        assertEquals(1, container.sourceRepository.getByProject(id).size)
    }

    @Test
    fun createProject_withoutTitle_fails() = runBlocking {
        val empty = CreateProjectViewModel(app)
        assertTrue(empty.createProject().isFailure)
    }

    /**
     * 学习画像三项真的落库（用户反馈：「想学什么这个太笼统了」）。
     *
     * 这是主干怎么切的依据——同样填「民法」，研究生深化 / 非专业自学 / 法考备考
     * 是三种完全不同的切法，所以从创建页到数据库这一段不能断。
     */
    @Test
    fun createProject_persistsLearnerProfile() = runBlocking {
        vm.learnerIdentity.value = "民商法研究生，本科民法基础不牢"
        vm.learningPurpose.value = "写论文需要深化理论"
        vm.learningGoal.value = "能比较各家学说并给出自己的论证"
        vm.addTextSource("第一份", "第一段内容。")

        val id = vm.createProject().getOrThrow()
        val project = container.projectRepository.get(id)
        assertEquals("民商法研究生，本科民法基础不牢", project?.learnerIdentity)
        assertEquals("写论文需要深化理论", project?.learningPurpose)
        assertEquals("能比较各家学说并给出自己的论证", project?.learningGoal)
    }

    /** 三项都不填仍能建项目：老流程不被新字段卡住，读出来是空串而不是 null */
    @Test
    fun createProject_withoutLearnerProfile_stillWorks() = runBlocking {
        vm.addTextSource("第一份", "第一段内容。")
        val id = vm.createProject().getOrThrow()
        val project = container.projectRepository.get(id)
        assertEquals("", project?.learnerIdentity)
        assertEquals("", project?.learningPurpose)
        assertEquals("", project?.learningGoal)
    }

    @Test
    fun removeSource_dropsPendingEntry() {
        vm.addTextSource("A", "内容 A")
        vm.addFileSource("B", "b.txt", SourceKind.TEXT, "内容 B".toByteArray())
        assertEquals(2, vm.sources.value.size)
        vm.removeSource(0)
        assertEquals(listOf("B"), vm.sources.value.map { it.title })
    }
}
