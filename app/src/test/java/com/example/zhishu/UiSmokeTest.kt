package com.example.zhishu

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.ui.viewmodel.DemoSeeder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSmokeTest {

    @Test
    fun activityLaunchesWithoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertNotNull(activity) }
        }
    }

    @Test
    fun demoSeed_populatesAllData() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ZhishuApp>()
        DemoSeeder.seed(app.container)

        val projects = app.container.projectRepository.observeAll().first()
        assertEquals(1, projects.size)
        val project = projects.first()
        assertEquals(ProjectStatus.READY, project.status)

        val sources = app.container.sourceRepository.getByProject(project.id)
        assertEquals(1, sources.size)
        assertEquals(1, app.container.sourceRepository.getChunks(sources.first().id).size)

        val nodes = app.container.knowledgeRepository.getByProject(project.id)
        assertEquals(4, nodes.size)
        assertTrue(nodes.any { it.title == "法定继承" })

        val plans = app.container.chapterRepository.getPlans(project.id)
        assertEquals(3, plans.size)
        assertTrue(plans.all { it.status == ChapterStatus.PUBLISHED })

        val blocks = plans.flatMap { app.container.chapterRepository.getActiveBlocks(it.id) }
        assertEquals(10, blocks.size)

        val quizzes = app.container.quizRepository.getByProject(project.id)
        assertEquals(4, quizzes.size)

        val progress = app.container.progressRepository.get(project.id)
        assertNotNull(progress)

        val due = app.container.reviewRepository.getDue(System.currentTimeMillis())
        assertEquals(2, due.size)
    }
}
