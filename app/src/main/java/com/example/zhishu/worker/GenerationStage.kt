package com.example.zhishu.worker

import com.example.zhishu.ai.ModelError
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ContractStatus
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

    /**
     * 自动闭环的驱动步（`GenerationWorker.advance()` 实现）：查库决定下一步，
     * 把后续工作 APPEND 到同一条唯一链尾部。本身不调模型。
     */
    ADVANCE,

    /**
     * 自动续卷：调一次 generateNextVolume 规划下一卷。会烧钱、会失败，
     * 所以做成独立阶段，才能拿 WorkManager 的退避重试与「失败即停链」，
     * 与其余调模型步骤一致（而不是在 ADVANCE 里同步调）。
     */
    PLAN_NEXT_VOLUME,

    /** 链尾总结：只发一条会话级结果通知，不调模型 */
    SUMMARY,
    ;

    val label: String
        get() = when (this) {
            KNOWLEDGE_MAP -> "正在梳理知识主干"
            STORY_BIBLE -> "正在规划讲述路线"
            SINGLE_CHAPTER, FIRST_CHAPTER -> "正在生成本章"
            ADVANCE -> "正在推进下一步"
            PLAN_NEXT_VOLUME -> "正在规划下一卷"
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
            // ADVANCE 只查库 + 续订，不调模型；缺配置由它 APPEND 出的 PLAN_NEXT_VOLUME 自己判
            ADVANCE -> emptyList()
            PLAN_NEXT_VOLUME -> listOf(ModelRole.STORY)
            SUMMARY -> emptyList()
        }
}

/**
 * 批量生成时应当处理哪些章节（B3/B5）。
 *
 * 已发布的跳过——包括旧库里的 QUIZ_FAILED：那些章节正文是好的，
 * 只是缺几道自测题，重排一次等于白花一遍正文的钱。
 * NEEDS_REVIEW 只在用户显式重试时纳入。
 *
 * **契约没生成的章节一律跳过**（三段式改造）：契约是正文的因果骨架，
 * 没有契约就写正文等于让模型凭空发挥，写出来的东西既无法核验、
 * 也和「讲述路线」的目标脱节。这类章节在界面上标「契约未生成」，
 * 由用户单独重试契约，而不是在这里被悄悄当成「待写正文」。
 *
 * 默认 `contractStatus = DONE`：老数据已有契约（或明确无契约），不会被误跳过。
 */
internal fun selectChaptersToGenerate(
    plans: List<ChapterPlan>,
    includeNeedsReview: Boolean,
): List<ChapterPlan> = plans
    .filter { plan -> plan.contractStatus != ContractStatus.FAILED.name }
    .filter { plan ->
        when {
            plan.status.isReadable -> false
            // 待复核分两种：带问题发布（有可读正文，等用户复核，默认不动它）和
            // 旧流程/中断留下的空壳（版本已作废、什么都没得读）——后者等于没写完，
            // 照常进批量，否则项目会卡在「继续说没有要生成的、章节又打不开」的死局
            plan.status == ChapterStatus.NEEDS_REVIEW ->
                includeNeedsReview || plan.activeVersionId == null
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
    // Router 已经在一次业务调用内部对 429 做过有限退避。若仍然是 429，说明服务商的
    // 并发槽没有释放；WorkManager 再重跑整个阶段只会形成双层重试并重做已成功步骤。
    is ModelError.RateLimited -> false
    is ModelError -> e.retryable
    // 只有明确的 I/O 临时故障值得重新排队。未知 RuntimeException 多半是数据库
    // 约束或程序缺陷，重跑不会恢复，反而可能在模型已经成功后重复收费。
    is java.io.IOException -> true
    else -> false
}

/**
 * 这个错误是否属于「再往下调模型也只是白烧钱」（第二阶段）。
 *
 * 认证失败、权限不足、配置无效属于这一类：链上后面几章必然一样失败，
 * 所以要立刻停链。单章内容校验不合要求不算——那是这一章自己的问题。
 */
/**
 * 给用户看的失败原因。
 *
 * 没有 message 的异常（超时、被系统收走、协程外中断）最容易把人带偏：
 * 界面退回「请检查网络与模型配置后重试」，用户于是去改配置，
 * 而真实原因常常是任务在后台被系统掐断——配置一点问题都没有。
 * 所以没原因时如实说「没拿到原因」，并指向能查到原因的那份诊断日志。
 */
internal fun failureReason(e: Throwable): String =
    e.message?.takeIf { it.isNotBlank() }
        ?: "${e::class.simpleName ?: "未知错误"}：这一步被中断，没有返回具体原因。" +
            "若反复出现，请到「我的 → 诊断日志」看最近一条失败记录。"

internal fun isPermanentConfigFailure(e: Throwable): Boolean = when (e) {
    is CancellationException -> false
    is ModelError.Invalid -> false
    is ModelError -> !e.retryable
    else -> false
}
