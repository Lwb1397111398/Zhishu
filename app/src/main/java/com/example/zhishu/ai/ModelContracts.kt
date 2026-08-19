package com.example.zhishu.ai

import com.example.zhishu.domain.model.ModelRole

data class ChatMessage(
    val role: String,
    val content: String,
)

/**
 * 模型请求。[maxTokens] 与 [temperature] 为 null 时表示「沿用用户在设置页配置的值」，
 * 只有调用方显式给出时才覆盖配置（B6：不使用 sentinel 值判断是否覆盖）。
 */
data class ModelRequest(
    val role: ModelRole,
    val system: String,
    val user: String,
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val jsonMode: Boolean = true,
) {
    fun messages(): List<ChatMessage> = listOf(
        ChatMessage(role = "system", content = system),
        ChatMessage(role = "user", content = user),
    )

    fun effectiveMaxTokens(configured: Int): Int = maxTokens ?: configured

    fun effectiveTemperature(configured: Float): Float = temperature ?: configured
}

interface ModelProvider {
    suspend fun listModels(): Result<List<String>>
    suspend fun complete(request: ModelRequest): Result<String>
}

interface JsonDecoder<T> {
    fun decode(text: String): T
}

interface ModelRouter {
    suspend fun <T> generate(
        role: ModelRole,
        request: ModelRequest,
        decoder: JsonDecoder<T>,
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

    /** 本次业务请求的调用预算已用尽。不可重试。 */
    class BudgetExhausted(message: String) : ModelError(message, retryable = false)

    /** 模型输出通过了 JSON 解析，但不满足业务约束（N14）。不可重试。 */
    class Invalid(message: String) : ModelError(message, retryable = false)
}
