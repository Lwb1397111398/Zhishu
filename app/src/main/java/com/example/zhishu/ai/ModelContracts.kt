package com.example.zhishu.ai

import com.example.zhishu.domain.model.ModelRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class ChatMessage(
    val role: String,
    val content: String,
)

/**
 * 模型请求。[maxTokens] 与 [temperature] 为 null 时表示「沿用用户在设置页配置的值」，
 * 只有调用方显式给出时才覆盖配置（B6：不使用 sentinel 值判断是否覆盖）。
 *
 * [stageLabel] 只用于诊断日志：同一个角色（FAST）会跑骨架、逐链、审计三种步骤，
 * 只按角色记日志时三者都叫 `FAST#1`，卡住时根本分不清是哪一步。给个短标签
 * （`MAP_SKELETON` / `MAP_CHAIN` / `STORY_BIBLE`…）就能一眼看出停在哪。为空时
 * 退回角色名，兼容不关心诊断的调用方。
 */
data class ModelRequest(
    val role: ModelRole,
    val system: String,
    val user: String,
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val jsonMode: Boolean = true,
    val stageLabel: String? = null,
) {
    fun messages(): List<ChatMessage> = listOf(
        ChatMessage(role = "system", content = system),
        ChatMessage(role = "user", content = user),
    )

    fun effectiveMaxTokens(configured: Int): Int = maxTokens ?: configured

    fun effectiveTemperature(configured: Float): Float = temperature ?: configured
}

/**
 * 一次模型调用的 token 用量与元数据（第二轮 P1-1）。
 *
 * 服务商返回 `usage` 时才有真实值；不返回（或解析失败）时全为 0，
 * 调用方据此区分「没有用量数据」与「用量确实是 0」。
 */
@Serializable
data class UsageInfo(
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
    /** 实际响应本次调用的模型名（有 usage 的服务商才拿得到） */
    val model: String = "",
    /** 本次 HTTP 调用耗时（毫秒），由客户端测量 */
    val latencyMillis: Long = 0,
) {
    companion object {
        val NONE = UsageInfo()
    }
}

/**
 * 一次「业务生成」按类别累计的模型调用统计（第二轮 P1-1）。
 *
 * 统计口径必须分层，否则无法解释成本来源：
 * - [business]：第一次业务调用
 * - [retry]：可重试错误（网络/限流/5xx）退避后重试的调用
 * - [repair]：JSON 修复调用（不算在业务调用里，也不归入重试）
 *
 * [total] 是三者之和。取消或异常路径不会调用 [add]，因此不会伪造用量。
 */
class ModelCallStats {

    enum class Kind { BUSINESS, RETRY, REPAIR }

    var business: UsageInfo = UsageInfo.NONE
        private set
    var retry: UsageInfo = UsageInfo.NONE
        private set
    var repair: UsageInfo = UsageInfo.NONE
        private set

    val total: UsageInfo
        get() = UsageInfo(
            promptTokens = business.promptTokens + retry.promptTokens + repair.promptTokens,
            completionTokens = business.completionTokens + retry.completionTokens + repair.completionTokens,
            totalTokens = business.totalTokens + retry.totalTokens + repair.totalTokens,
            model = business.model.ifBlank { retry.model }.ifBlank { repair.model },
            latencyMillis = business.latencyMillis + retry.latencyMillis + repair.latencyMillis,
        )

    fun add(kind: Kind, usage: UsageInfo) {
        if (usage.totalTokens <= 0) return
        when (kind) {
            Kind.BUSINESS -> business = merge(business, usage)
            Kind.RETRY -> retry = merge(retry, usage)
            Kind.REPAIR -> repair = merge(repair, usage)
        }
    }

    private fun merge(a: UsageInfo, b: UsageInfo): UsageInfo = UsageInfo(
        promptTokens = a.promptTokens + b.promptTokens,
        completionTokens = a.completionTokens + b.completionTokens,
        totalTokens = a.totalTokens + b.totalTokens,
        model = a.model.ifBlank { b.model },
        latencyMillis = a.latencyMillis + b.latencyMillis,
    )
}

interface ModelProvider {
    suspend fun listModels(): Result<List<String>>

    /**
     * 完成一次模型调用（一次 HTTP 尝试）。
     *
     * [onUsage] 在成功响应且服务商返回用量信息时回调（缺失时不回调）；
     * [onRawResponse] 在拿到 HTTP 响应体后回调**原始响应全文**（批次 B 样本需要保存原始响应，
     * 用来核对 usage/finish_reason 与重放）。响应体可能含资料原文，调用方自行决定是否落盘。
     */
    suspend fun complete(
        request: ModelRequest,
        onUsage: (UsageInfo) -> Unit = {},
        onRawResponse: (String) -> Unit = {},
    ): Result<String>
}

/**
 * 一次 HTTP 尝试（批次 B B.0-5）。
 *
 * 与 [ModelCallStats] 的差别：stats 是**按类别汇总**的业务成本口径；
 * 这里是一条记录 = 一次实际发出的请求（含失败的尝试），用于样本审计。
 * 口径（执行指令）：
 * - 首次请求失败且无 usage → [UsageStatus.MISSING]，不当作 0；
 * - 重试成功后首次失败记录不消失（每条 attempt 独立成行）；
 * - JSON 修复用 [AttemptKind.JSON_REPAIR] 并通过 [businessCallId] 关联原业务调用；
 * - HTTP 失败可能已产生服务商费用，无法确认时 [UsageStatus.MISSING] + 保留 errorCategory；
 * - 汇总时 usage 缺失次数单独统计，不能用零填充。
 */
data class CallAttempt(
    /** 一次业务生成（一次 ModelRouter.generate）的标识，JSON 修复用它关联原调用 */
    val businessCallId: String,
    /** 本条记录是这次业务生成的第几次 HTTP 尝试（1 起） */
    val attempt: Int,
    /** BUSINESS=首次业务调用；RETRY=可重试错误后的重试；JSON_REPAIR=修复调用 */
    val kind: AttemptKind,
    /** 服务商实际报告的用量；缺失时全 0 且 [usageStatus] = MISSING */
    val usage: UsageInfo = UsageInfo.NONE,
    /** REPORTED=服务商报告了 usage；MISSING=请求发出但服务商未报告；NOT_APPLICABLE=请求未发出 */
    val usageStatus: UsageStatus,
    /** 本条 HTTP 尝试开始时间（epoch 毫秒） */
    val startedAt: Long,
    val latencyMillis: Long,
    val outcome: AttemptOutcome,
    /** ModelError 类名（Auth/RateLimited/Server/Network/Decode/...），成功为 null */
    val errorCategory: String? = null,
    /** 安全错误摘要的原始来源；普通诊断写入前还会统一脱敏、压平与截断 */
    val errorSummary: String? = null,
    /** 原始响应体（含 usage/finish_reason）。样本运行器写入 artifacts，不写进普通日志 */
    val rawResponse: String? = null,
)

enum class AttemptKind { BUSINESS, RETRY, JSON_REPAIR }

enum class UsageStatus { REPORTED, MISSING, NOT_APPLICABLE }

enum class AttemptOutcome { SUCCESS, FAILED }

interface JsonDecoder<T> {
    fun decode(text: String): T
}

interface ModelRouter {
    suspend fun <T> generate(
        role: ModelRole,
        request: ModelRequest,
        decoder: JsonDecoder<T>,
        /** 本次业务生成结束后回调按类别累计的调用统计（第二轮 P1-1），不传则不统计 */
        onStats: (ModelCallStats) -> Unit = {},
        /** 每次 HTTP 尝试回调一条记录（批次 B B.0-5），不传则不记录 */
        onAttempt: (CallAttempt) -> Unit = {},
    ): Result<T>
}

/**
 * 模型错误分类。是否可重试由 [retryable] 决定，避免把 400 之类不可恢复错误反复重发（N8）。
 */
sealed class ModelError(
    override val message: String,
    val retryable: Boolean,
) : Exception(message) {

    /** 认证或授权失败：401/403，或本地缺少配置。不可重试。 */
    class Auth(message: String) : ModelError(message, retryable = false)

    /** 请求本身不合法：400/404/413/422 等。不可重试。 */
    class InvalidRequest(message: String) : ModelError(message, retryable = false)

    /** 限流：429。可重试，[retryAfterMillis] 为服务端建议的等待时间。 */
    class RateLimited(
        message: String,
        val retryAfterMillis: Long? = null,
    ) : ModelError(message, retryable = true)

    /** 服务端错误：5xx。可重试。 */
    class Server(message: String) : ModelError(message, retryable = true)

    /** 网络错误：超时、连接失败。可重试。 */
    class Network(message: String) : ModelError(message, retryable = true)

    /** JSON 无法解析。不可重试（重复生成同样格式无意义，由修复流程单独处理）。 */
    class Decode(message: String) : ModelError(message, retryable = false)

    /** 模型返回空内容。可重试一次。 */
    class Empty(message: String) : ModelError(message, retryable = true)

    /**
     * 输出被长度上限切断（finish_reason=length）。
     *
     * 单列一类而不是塞进 [InvalidRequest]，是为了让上层能分清「换个更小的请求没用、
     * 得少要点内容」和「请求参数本身不合法」。降 max_tokens 对截断只会更糟，
     * 所以这里 [retryable] 为 false——不在路由层退避重发同一请求，
     * 而是交给调用方（知识主干骨架）用「减少本轮要生成的量」的方式自救。
     */
    class Truncated(message: String) : ModelError(message, retryable = false)

    /** 本次业务请求的调用预算已用尽。不可重试。 */
    class BudgetExhausted(message: String) : ModelError(message, retryable = false)

    /** 模型输出通过了 JSON 解析，但不满足业务约束（N14）。不可重试。 */
    class Invalid(message: String) : ModelError(message, retryable = false)
}
