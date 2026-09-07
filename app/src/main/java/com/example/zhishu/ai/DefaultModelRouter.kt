package com.example.zhishu.ai

import com.example.zhishu.domain.model.ModelRole
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * 错误文案是否在抱怨 token/上下文长度。
 *
 * 只做关键词匹配：OpenAI 兼容服务商的 400 文案各不相同
 * （`max_tokens is too large`、`maximum context length`、`超出模型最大上下文`…），
 * 但都离不开这几个词。匹配不上就不降档——宁可如实报错，也不要把
 * 「模型名写错」这类真正需要用户改配置的问题偷偷重试掉。
 *
 * 全应用唯一的 token 超限判定口径：降档（DefaultModelRouter）与诊断日志
 * （GenerationLog.safeDiagnosticSummary）都走这里，别在别处另写宽松版。
 */
internal fun looksLikeTokenLimit(message: String?): Boolean {
    if (message.isNullOrBlank()) return false
    val lower = message.lowercase()
    return TOKEN_LIMIT_KEYWORDS.any { lower.contains(it) }
}

/** token 超限关键词表，[looksLikeTokenLimit] 专用（包级，与路由/日志共用） */
internal val TOKEN_LIMIT_KEYWORDS = listOf(
    "max_tokens",
    "maxtokens",
    "max tokens",
    "context length",
    "context_length",
    "context window",
    "too many tokens",
    "token limit",
    "上下文",
    "最大长度",
    "长度超",
    // 国内服务商常见的长度抱怨文案（此前缺这些，真·长度 400 反而不降档；
    // 而误降档是 classify() 包装文案造成的，那边已修）。
    // 只补「长度/上限」语义的词，别把「invalid」「model」这类泛词放进来。
    "输出上限",
    "长度上限",
    "超出最大",
    "超过最大",
    "too large",
    "exceed",
    "maximum output",
    "output length",
    "length limit",
)

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
        // 本次业务生成实际发出的请求。被服务商嫌 max_tokens 太大时会降档重建
        var current = request
        var downgrades = 0

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
                current,
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
                    errorSummary = result.exceptionOrNull()?.message,
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
                        val repaired = repair(text, current, stats, businessCallId, httpAttempt, onAttempt)
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
            // 服务商嫌 max_tokens / 上下文太大：这类 400 本身不可重试，但**换一个更小的
            // max_tokens 就能成**。逐档减半兜底，避免「一律永久失败」。
            val downgraded = downgradedRequest(current, error, downgrades)
            if (downgraded != null) {
                current = downgraded
                downgrades += 1
                // 降档**不占业务调用预算**，也不算一次重试。
                //
                // 原来占：maxProviderCalls=4，而降档阶梯正好是 4 次调用
                // （50000→25000→12500→6250），于是真正能成的那一次永远撞在
                // 预算门上，整步报「调用预算已用尽」——用户看到的是「有调用记录
                // 但结果没用上」。降档次数本身已由 MAX_TOKEN_DOWNGRADES 与
                // MIN_DOWNGRADED_TOKENS 双重封顶，不会失控。
                calls -= 1
                // 这不是「上一次失败后重试同一个请求」，而是「换一个合法参数重发」，
                // 因此清掉 lastError，避免把它记成 RETRY 类调用
                lastError = null
                continue
            }
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

    /**
     * 服务商拒绝了当前的 `max_tokens`（或抱怨上下文超长）时，返回一个输出上限减半的请求；
     * 不属于这类错误、已降档到底、或本来就没显式给 max_tokens 时返回 null。
     *
     * 为什么需要这一层：各阶段已经按实际需要限制输出（知识链通常 8K，路线 16K），
     * 但部分兼容服务的单次输出上限更低，或把 prompt 与 max_tokens 合并校验，仍可能返回 400。
     * 降档只作为兼容兜底；匹配不到明确 token 限制文案时如实失败，不盲目重试。
     */
    private fun downgradedRequest(
        request: ModelRequest,
        error: ModelError,
        downgrades: Int,
    ): ModelRequest? {
        if (downgrades >= MAX_TOKEN_DOWNGRADES) return null
        if (error !is ModelError.InvalidRequest) return null
        val currentLimit = request.maxTokens ?: return null
        if (currentLimit <= MIN_DOWNGRADED_TOKENS) return null
        if (!looksLikeTokenLimit(error.message)) return null
        return request.copy(maxTokens = (currentLimit / 2).coerceAtLeast(MIN_DOWNGRADED_TOKENS))
    }

    /**
     * 错误文案是否在抱怨 token/上下文长度。
     *
     * 只做关键词匹配：OpenAI 兼容服务商的 400 文案各不相同
     * （`max_tokens is too large`、`maximum context length`、`超出模型最大上下文`…），
     * 但都离不开这几个词。匹配不上就不降档——宁可如实报错，也不要把
     * 「模型名写错」这类真正需要用户改配置的问题偷偷重试掉。
     *
     * 判定口径统一走包级 [looksLikeTokenLimit]，与诊断日志共用。
     */
    private fun looksLikeTokenLimit(message: String?) =
        com.example.zhishu.ai.looksLikeTokenLimit(message)

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

        /** 最多降几档（50000 → 25000 → 12500 → 6250），够覆盖到 8K 窗口的老模型 */
        const val MAX_TOKEN_DOWNGRADES = 3

        /** 降到这个值就不再降了：再小连一轮节点都写不完，不如如实报错 */
        const val MIN_DOWNGRADED_TOKENS = 2_000
    }
}
