package com.example.zhishu.worker

import com.example.zhishu.ai.ModelError
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ModelRole
import kotlinx.coroutines.CancellationException

/**
 * 一个 WorkRequest 只做一件事（第二阶段）。
 *
 * 原来 CHAPTERS 一个 Worker 串行跑完 12 章：一次模型调用慢一点，整批就一起撞上
 * 普通 Worker 的执行窗口；进程被杀之后也没法从「第几章」接着来。
 * 现在批量在入队时就展开成一章一个 WorkRequest，串成一条链，
 * 每一章成败单独落库，重启后 WorkManager 自己接着跑剩下的。
 */
enum class GenerationStage {
    KNOWLEDGE_MAP,
    STORY_BIBLE,

    /** 指定章节的正文 + 核验 + 出题 */
    SINGLE_CHAPTER,

    /**
     * 一键开讲链的收尾一章：入队时还不知道章节 ID（讲述路线都还没生成），
     * 所以运行时取「第一个还没写的章节」。这不是内存状态，是每次现查数据库。
     */
    FIRST_CHAPTER,

    /** 链尾总结：只发一条会话级结果通知，不调模型 */
    SUMMARY,
    ;

    val label: String
        get() = when (this) {
            KNOWLEDGE_MAP -> "正在梳理知识主干"
            STORY_BIBLE -> "正在规划讲述路线"
            SINGLE_CHAPTER, FIRST_CHAPTER -> "正在生成本章"
            SUMMARY -> "正在收尾"
        }

    /**
     * 这一阶段真正要用到的模型角色（第二阶段修正）。
     *
     * 原来除知识地图和讲述路线之外一律要求 FAST + STORY + VERIFY 三样齐全，
     * 于是只想生成一章正文的人被一个用不到的 FAST 配置卡住。
     */
    val requiredRoles: List<ModelRole>
        get() = when (this) {
            KNOWLEDGE_MAP -> listOf(ModelRole.FAST)
            STORY_BIBLE -> listOf(ModelRole.STORY)
            // 正文用 STORY 写、VERIFY 核验并出题，与 FAST 无关
            SINGLE_CHAPTER, FIRST_CHAPTER -> listOf(ModelRole.STORY, ModelRole.VERIFY)
            SUMMARY -> emptyList()
        }
}

/**
 * 批量生成时应当处理哪些章节（B3/B5）。
 *
 * 已发布的跳过——包括旧库里的 QUIZ_FAILED：那些章节正文是好的，
 * 只是缺几道自测题，重排一次等于白花一遍正文的钱。
 * NEEDS_REVIEW 只在用户显式重试时纳入。
 */
internal fun selectChaptersToGenerate(
    plans: List<ChapterPlan>,
    includeNeedsReview: Boolean,
): List<ChapterPlan> = plans
    .filter { plan ->
        when {
            plan.status.isReadable -> false
            plan.status == ChapterStatus.NEEDS_REVIEW -> includeNeedsReview
            else -> true
        }
    }
    .sortedBy { it.order }

/**
 * 该异常是否值得让 WorkManager 重试（B4）。
 * 协程取消不走这里（由调用方直接重抛）；配置类错误与业务前置条件失败不重试。
 */
internal fun isRetryableFailure(e: Throwable): Boolean = when (e) {
    is CancellationException -> false
    is ModelError -> e.retryable
    is IllegalStateException, is IllegalArgumentException -> false
    else -> true
}

/**
 * 这个错误是否属于「再往下调模型也只是白烧钱」（第二阶段）。
 *
 * 认证失败、权限不足、配置无效属于这一类：链上后面几章必然一样失败，
 * 所以要立刻停链。单章内容校验不合要求不算——那是这一章自己的问题。
 */
internal fun isPermanentConfigFailure(e: Throwable): Boolean = when (e) {
    is CancellationException -> false
    is ModelError.Invalid -> false
    is ModelError -> !e.retryable
    else -> false
}
