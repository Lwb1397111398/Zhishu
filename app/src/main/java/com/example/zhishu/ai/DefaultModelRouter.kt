package com.example.zhishu.ai

import com.example.zhishu.domain.model.ModelRole
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * 默认路由：把「一次业务生成」限制在总调用预算内（N9）。
 *
 * - [maxProviderCalls] 是本次业务请求允许的模型调用总数，包含生成与 JSON 修复。
 * - 原始响应无法解码时最多修复一次；修复仍失败直接返回 Decode，不再重新生成（N9）。
 * - 只有 [ModelError.retryable] 为真的错误才重试；429 优先采用服务端 Retry-After（N8）。
 * - 退避加入抖动，避免多章节并发时同时重试。
 *
 * 记录口径（批次 B B.0-5）：
 * - [onAttempt] 每发出一次 HTTP 请求回调一条 [CallAttempt]，**失败的尝试也保留**（不消失）；
 * - 首次业务调用 [AttemptKind.BUSINESS]，重试 [AttemptKind.RETRY]，JSON 修复 [AttemptKind.JSON_REPAIR]；
 * - 服务商没报 usage → [UsageStatus.MISSING]，不当作 0；请求未发出 → NOT_APPLICABLE；
 * - [businessCallId] 贯穿一次业务生成，JSON 修复用它关联原业务调用。
 */
class DefaultModelRouter(
    private val provider: ModelProvider,
    private val retryDelaysMillis: List<Long> = listOf(2_000L, 5_000L, 15_000L),
    private val maxProviderCalls: Int = 4,
    private val repairAttempts: Int = 1,
    private val random: Random = Random.Default,
) : ModelRouter {

    override suspend fun <T> generate(
        role: ModelRole,
        request: ModelRequest,
        decoder: JsonDecoder<T>,
        onStats: (ModelCallStats) -> Unit,
        onAttempt: (CallAttempt) -> Unit,
    ): Result<T> {
        val stats = ModelCallStats()
        val businessCallId = UUID.randomUUID().toString()
        var calls = 0
        var lastError: ModelError? = null
        var attempt = 0
        var httpAttempt = 0

        while (true) {
            if (calls >= maxProviderCalls) {
                onStats(stats)
                return Result.failure(
                    lastError ?: ModelError.BudgetExhausted("本次生成的模型调用预算已用尽"),
                )
            }
            calls += 1
            // 上一次是失败重试，则本次调用计入 retry 类（第二轮 P1-1）
            val retrying = lastError != null
            val startedAt = System.currentTimeMillis()
            var reportedUsage: UsageInfo? = null
            var rawResponse: String? = null
            val result = provider.complete(
                request,
                onUsage = { usage ->
                    reportedUsage = usage
                    stats.add(
                        if (retrying) ModelCallStats.Kind.RETRY else ModelCallStats.Kind.BUSINESS,
                        usage,
                    )
                },
                onRawResponse = { raw -> rawResponse = raw },
            )
            onAttempt(
                CallAttempt(
                    businessCallId = businessCallId,
                    attempt = ++httpAttempt,
                    kind = if (retrying) AttemptKind.RETRY else AttemptKind.BUSINESS,
                    usage = reportedUsage ?: UsageInfo.NONE,
                    usageStatus = if (reportedUsage != null) UsageStatus.REPORTED else UsageStatus.MISSING,
                    startedAt = startedAt,
                    latencyMillis = System.currentTimeMillis() - startedAt,
                    outcome = if (result.isSuccess) AttemptOutcome.SUCCESS else AttemptOutcome.FAILED,
                    errorCategory = result.exceptionOrNull()?.let { it::class.simpleName },
                    rawResponse = rawResponse,
                ),
            )

            if (result.isSuccess) {
                val text = result.getOrThrow()
                try {
                    onStats(stats)
                    return Result.success(decoder.decode(text))
                } catch (e: Exception) {
                    // 原始响应不可解码：只允许一次修复，且修复也计入预算
                    if (repairAttempts > 0 && calls < maxProviderCalls) {
                        calls += 1
                        val repaired = repair(text, request, stats, businessCallId, httpAttempt, onAttempt)
                        if (repaired != null) {
                            try {
                                onStats(stats)
                                return Result.success(decoder.decode(repaired))
                            } catch (repairError: Exception) {
                                onStats(stats)
                                return Result.failure(
                                    ModelError.Decode(repairError.message ?: "JSON 修复后仍无法解析"),
                                )
                            }
                        }
                    }
                    onStats(stats)
                    return Result.failure(ModelError.Decode(e.message ?: "JSON 解析失败"))
                }
            }

            val error = result.exceptionOrNull() as? ModelError
                ?: ModelError.Network(result.exceptionOrNull()?.message ?: "未知错误")
            lastError = error
            if (!error.retryable) {
                onStats(stats)
                return Result.failure(error)
            }
            if (calls >= maxProviderCalls || attempt >= retryDelaysMillis.size) {
                onStats(stats)
                return Result.failure(error)
            }
            delay(backoffMillis(attempt, error))
            attempt += 1
        }
    }

    private fun backoffMillis(attempt: Int, error: ModelError): Long {
        val base = retryDelaysMillis[attempt]
        val suggested = (error as? ModelError.RateLimited)?.retryAfterMillis
        val target = if (suggested != null) min(suggested, MAX_BACKOFF_MILLIS) else base
        val jitter = if (target > 0) random.nextLong(0, (target / 4).coerceAtLeast(1)) else 0
        return target + jitter
    }

    private suspend fun repair(
        broken: String,
        request: ModelRequest,
        stats: ModelCallStats,
        businessCallId: String,
        httpAttemptBeforeRepair: Int,
        onAttempt: (CallAttempt) -> Unit,
    ): String? {
        val repairRequest = ModelRequest(
            role = request.role,
            system = "你是 JSON 修复助手。只修复 JSON 语法错误，不要改变任何语义和内容。只输出修复后的 JSON。",
            user = "以下是损坏的 JSON：\n$broken\n\n请输出修复后的完整 JSON。",
            maxTokens = request.maxTokens,
            temperature = 0f,
            jsonMode = true,
        )
        // JSON 修复是独立的一类调用，不归入业务调用，也不归入重试（第二轮 P1-1）
        val startedAt = System.currentTimeMillis()
        var reportedUsage: UsageInfo? = null
        var rawResponse: String? = null
        val result = provider.complete(
            repairRequest,
            onUsage = { usage ->
                reportedUsage = usage
                stats.add(ModelCallStats.Kind.REPAIR, usage)
            },
            onRawResponse = { raw -> rawResponse = raw },
        )
        onAttempt(
            CallAttempt(
                businessCallId = businessCallId,
                attempt = httpAttemptBeforeRepair + 1,
                kind = AttemptKind.JSON_REPAIR,
                usage = reportedUsage ?: UsageInfo.NONE,
                usageStatus = if (reportedUsage != null) UsageStatus.REPORTED else UsageStatus.MISSING,
                startedAt = startedAt,
                latencyMillis = System.currentTimeMillis() - startedAt,
                outcome = if (result.isSuccess) AttemptOutcome.SUCCESS else AttemptOutcome.FAILED,
                errorCategory = result.exceptionOrNull()?.let { it::class.simpleName },
                rawResponse = rawResponse,
            ),
        )
        return result.getOrNull()
    }

    private companion object {
        const val MAX_BACKOFF_MILLIS = 60_000L
    }
}
