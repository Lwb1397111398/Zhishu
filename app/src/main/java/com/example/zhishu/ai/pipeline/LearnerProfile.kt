package com.example.zhishu.ai.pipeline

import com.example.zhishu.domain.model.Project

/**
 * 学习者画像（用户反馈：「想学什么这个太笼统了」）。
 *
 * 用户举的三个例子说明了问题：同样填「民法」，
 *
 * - 民商法研究生、本科基础不牢、想深化 → 主干要细、要深，允许学理争议与比较；
 * - 非法学专业自学 → 先建立整体框架，别一上来就掉进条文细节；
 * - 准备法考 → 按考试的框架切分，重点跟着考点走。
 *
 * 这三种切法完全不同，而程序原来只知道 topic 和一个 depth 选项，
 * 于是模型只能按「一般学习者」猜。现在把身份、目的、目标显式传下去。
 *
 * 三个字段都可能为空（老项目、或用户没填）：为空时该行整体省略，
 * 空行不会留在 prompt 里让模型对着空白猜。全为空时返回统一的兜底说明，
 * 而不是一段空文本——模型看到空白最容易自己编一个学习者出来。
 */
internal object LearnerProfile {

    /**
     * prompt 里的「学习者画像」小节。四个阶段（骨架/单链/审计/讲述路线）共用同一段文本，
     * 保证同一个项目在各阶段被理解成同一个学习者。
     */
    fun section(project: Project): String {
        val lines = buildList {
            project.learnerIdentity.trim().takeIf { it.isNotEmpty() }?.let { add("学习者身份：$it") }
            project.baseline.trim().takeIf { it.isNotEmpty() }?.let { add("已有基础：$it") }
            project.learningPurpose.trim().takeIf { it.isNotEmpty() }?.let { add("学习目的：$it") }
            project.depth.trim().takeIf { it.isNotEmpty() }?.let { add("学习深度：$it") }
            add("希望达到的目标：${goal(project)}")
            // 身份和目的都没填 → 必须明说「按完全陌生处理」。
            // 少了这句，模型会自己给学习者编一个身份（常见的是默认按专业学生讲），
            // 于是非专业自学者一上来就被灌条文细节。
            if (project.learnerIdentity.isBlank() && project.learningPurpose.isBlank()) {
                add(
                    "学习者没有提供更多背景信息。请按「对这门学科完全陌生、想建立整体理解」来安排，" +
                        "不要假设他有专业训练，也不要假设他在准备某种考试。",
                )
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * 学习目标。用户填了就用用户填的。
     *
     * 原来 `{goal}` 一律用 `project.title` 顶替——那是项目**展示名称**（默认就是学科名，
     * 比如「民法」），把它当学习目标喂给模型，等于告诉它「我的目标是民法」，
     * 一句有效信息都没有。现在 title 只用于展示，目标有专门的字段；
     * 用户没填时给一句明确的默认目标，而不是回头再拿 title 充数。
     */
    fun goal(project: Project): String =
        project.learningGoal.trim().ifBlank { DEFAULT_GOAL }

    /** 用户没填目标时的默认值。写成可观察的结果，模型才知道该讲到什么程度 */
    const val DEFAULT_GOAL = "建立整体理解，并能解释核心知识点之间的关系"
}
