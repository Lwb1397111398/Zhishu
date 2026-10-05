package com.example.zhishu.ai

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 一次生成任务内累计的 token 用量（3.2）。
 *
 * 用量数据本来就有（服务商返回 usage，诊断日志也记了），只是没接到界面——
 * 用户不知道一章写下来到底花了多少。这里按任务累计，挂到协程上下文里，
 * 让 Router 在每次调用结算时顺手记一笔。
 */
class UsageAccumulator {

    private val tokens = AtomicLong()

    /** 忽略 0 与负数：服务商没返回用量时不算进账，避免把「没数据」当「花了 0」 */
    fun add(totalTokens: Long) {
        if (totalTokens > 0) tokens.addAndGet(totalTokens)
    }

    val totalTokens: Long get() = tokens.get()
}

/**
 * 挂在协程上下文里的用量累加器。
 *
 * 用 CoroutineContext 而不是逐层传参：PipelineService 内部十几处 router.generate
 * 分布在多层私有函数里，逐层穿透参数会把整条调用链的签名全部污染；
 * 协程上下文随挂起调用自动传播（PipelineService 内部没有另起根协程的地方），
 * Router 结算时读一下即可，调用方零改动。
 */
class UsageTrackerElement(
    val accumulator: UsageAccumulator,
) : AbstractCoroutineContextElement(UsageTrackerElement) {
    companion object Key : CoroutineContext.Key<UsageTrackerElement>
}
