package com.example.zhishu

import com.example.zhishu.ai.pipeline.LearnerProfile
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习者画像（用户反馈：「想学什么这个太笼统了」）。
 *
 * 钉住三件事：
 * 1. 用户填的身份/目的/目标真的进了 prompt——这是主干怎么切的依据；
 * 2. 没填的字段整行省略，不留空白让模型对着空行猜；
 * 3. `{goal}` 不再拿 `project.title` 顶替（title 默认就是学科名，
 *    当学习目标喂过去等于「我的目标是民法」，一句有效信息都没有）。
 */
class LearnerProfileTest {

    private fun project(
        title: String = "民法",
        identity: String = "",
        purpose: String = "",
        goal: String = "",
        baseline: String = "完全不了解",
        depth: String = "入门",
    ) = Project(
        id = "p-1",
        title = title,
        topic = "民法",
        baseline = baseline,
        depth = depth,
        dailyMinutes = 30,
        targetDays = 14,
        genre = "悬疑",
        status = ProjectStatus.DRAFT,
        createdAt = 1L,
        updatedAt = 1L,
        learnerIdentity = identity,
        learningPurpose = purpose,
        learningGoal = goal,
    )

    /** 用户举的第一个例子：民商法研究生、基础不牢、想深化 */
    @Test
    fun section_includesEverythingUserFilled() {
        val section = LearnerProfile.section(
            project(
                identity = "民商法研究生，本科民法基础不牢",
                purpose = "写论文需要深化理论",
                goal = "能比较各家学说并给出自己的论证",
                baseline = "学过但不牢",
                depth = "专业",
            ),
        )
        assertTrue(section.contains("民商法研究生，本科民法基础不牢"))
        assertTrue(section.contains("写论文需要深化理论"))
        assertTrue(section.contains("能比较各家学说并给出自己的论证"))
        assertTrue(section.contains("学过但不牢"))
        assertTrue(section.contains("专业"))
    }

    /** 用户举的第三个例子：法考考生。目的必须真的传下去，主干才会按考试框架切 */
    @Test
    fun section_examCandidate_purposeReachesPrompt() {
        val section = LearnerProfile.section(project(purpose = "准备法考", goal = "通过法考民法部分"))
        assertTrue(section.contains("准备法考"))
        assertTrue(section.contains("通过法考民法部分"))
    }

    /** 没填的字段整行省略，不留「学习者身份：」这种空标签 */
    @Test
    fun section_omitsBlankFieldsEntirely() {
        val section = LearnerProfile.section(project(identity = "", purpose = ""))
        assertFalse("身份没填就不该出现这一行", section.contains("学习者身份"))
        assertFalse("目的没填就不该出现这一行", section.contains("学习目的"))
        // 基础和深度有默认值，仍然给
        assertTrue(section.contains("已有基础"))
    }

    /** 身份和目的都没填时给一句明确兜底，别让模型自己编一个学习者出来 */
    @Test
    fun section_noIdentityOrPurpose_returnsExplicitFallback() {
        val section = LearnerProfile.section(project(identity = "", purpose = ""))
        assertTrue(section.contains("没有提供更多背景信息"))
        assertTrue("必须明说别假设专业训练", section.contains("不要假设"))
        // 反之，填了身份就不该再出现这句兜底
        val filled = LearnerProfile.section(project(identity = "法考考生"))
        assertFalse(filled.contains("没有提供更多背景信息"))
    }

    /** 目标一定会出现（用户没填就是默认目标），模型才知道该讲到什么程度 */
    @Test
    fun section_alwaysStatesGoal() {
        assertTrue(LearnerProfile.section(project(goal = "")).contains(LearnerProfile.DEFAULT_GOAL))
    }

    /** 关键回归：goal 绝不再用 title 顶替 */
    @Test
    fun goal_neverFallsBackToProjectTitle() {
        // 用户没填目标 → 用默认目标，而不是把项目名「民法」当成学习目标
        assertEquals(LearnerProfile.DEFAULT_GOAL, LearnerProfile.goal(project(title = "民法", goal = "")))
        // 用户填了 → 以用户填的为准
        assertEquals("能独立分析简单合同纠纷", LearnerProfile.goal(project(goal = "能独立分析简单合同纠纷")))
        // 只有空白也算没填
        assertEquals(LearnerProfile.DEFAULT_GOAL, LearnerProfile.goal(project(goal = "   ")))
    }
}
