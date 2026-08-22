package com.example.zhishu.ai

import com.example.zhishu.domain.model.ModelConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.json.Json

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Float,
    @SerialName("response_format") val responseFormat: ResponseFormatDto? = null,
    /**
     * 推理模型的思考开关（火山 Ark / DeepSeek 等 OpenAI 兼容服务商支持）：
     * `thinking: {"type":"disabled"}` 把推理模型当普通对话模型用——
     * 批次 B 样本评估的是契约/路线/核验，不需要长思考；生产默认 null（服务商默认行为）。
     */
    val thinking: ThinkingDto? = null,
)

@Serializable
private data class ThinkingDto(
    @SerialName("type") val type: String,
)

@Serializable
private data class ChatMessageDto(
    val role: String,
    val content: String,
)

@Serializable
private data class ResponseFormatDto(
    @EncodeDefault
    val type: String = "json_object",
)

@Serializable
private data class ChatCompletionResponse(
    val choices: List<ChoiceDto> = emptyList(),
    /** token 用量。兼容服务商不返回该字段的情况（第二轮 P1-1） */
    val usage: UsageDto? = null,
)

@Serializable
private data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
)

@Serializable
private data class ModelsResponse(
    val data: List<ModelDto> = emptyList(),
)

@Serializable
private data class ChoiceDto(
    val message: MessageDto? = null,
    // 截断时为 "length"，正常结束为 "stop"。用来区分「真空返回」和「被截断」（避免盲目重试扣费）
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class MessageDto(
    val content: String? = null,
    // 推理模型（DeepSeek-R1/QwQ/o1 等）把思考过程放这里，正式答案仍应在 content
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
private data class ErrorResponse(
    val error: ErrorDetailDto? = null,
)

@Serializable
private data class ErrorDetailDto(
    val message: String? = null,
)

class OpenAiModelProvider(
    private val config: ModelConfig,
    private val apiKey: String,
    clientOverride: HttpClient? = null,
    /** 仅测试用：允许 http 明文地址。生产路径固定为 true，强制 HTTPS（B13） */
    private val requireHttps: Boolean = true,
    /**
     * 可配置超时（批次 B 样本用）。生产默认 180s/120s；
     * 推理模型（doubao-seed/deepseek-r1 等）思考阶段可能长时间无数据，
     * 会击穿 socket 空闲超时——样本运行器传更长超时，不改变生产默认值。
     */
    private val socketTimeoutMillis: Long = 120_000,
    private val requestTimeoutMillis: Long = 180_000,
    /**
     * 关闭推理模型的思考（Ark thinking:disabled）。样本运行器用：
     * 把 doubao-seed 等推理模型当普通对话模型，避免长思考导致超时与高成本。
     * 生产默认 false（服务商默认行为）。
     */
    private val thinkingDisabled: Boolean = false,
) : ModelProvider {
    init {
        require(socketTimeoutMillis > 0 && requestTimeoutMillis > 0) { "超时必须是正数" }
    }

    private val client: HttpClient = clientOverride ?: defaultClient(socketTimeoutMillis, requestTimeoutMillis)

    override suspend fun listModels(): Result<List<String>> {
        return try {
            val base = requireSecureUrl(config.baseUrl)
            val url = chatBaseUrl(base).replace("/chat/completions", "/models")
            val response = client.get(url) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            if (!response.status.isSuccess()) {
                // 走同一套错误解释，测试连接时也能看到可行动的中文提示而不是干巴巴的状态码
                return Result.failure(
                    classify(response.status.value, errorMessage(response.bodyAsText(), response.status.value), response),
                )
            }
            val models = lenientJson
                .decodeFromString<ModelsResponse>(response.bodyAsText())
            Result.success(models.data.map { it.id })
        } catch (e: ModelError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ModelError.Network(e.message ?: "模型列表请求失败"))
        }
    }

    override suspend fun complete(
        request: ModelRequest,
        onUsage: (UsageInfo) -> Unit,
        onRawResponse: (String) -> Unit,
    ): Result<String> {
        return try {
            val base = requireSecureUrl(config.baseUrl)
            val body = ChatCompletionRequest(
                model = config.modelName,
                messages = request.messages().map { ChatMessageDto(it.role, it.content) },
                // 用户在设置页配置的值生效，调用方只在需要时覆盖（B6）
                maxTokens = request.effectiveMaxTokens(config.maxTokens),
                temperature = request.effectiveTemperature(config.temperature),
                responseFormat = if (request.jsonMode && config.supportsJson) ResponseFormatDto() else null,
                thinking = if (thinkingDisabled) ThinkingDto(type = "disabled") else null,
            )
            val startedAt = System.currentTimeMillis()
            val response = client.post(chatBaseUrl(base)) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val latencyMillis = System.currentTimeMillis() - startedAt
            handleResponse(response, latencyMillis, onUsage, onRawResponse)
        } catch (e: ModelError) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(ModelError.Network(e.message ?: "请求失败"))
        }
    }

    private suspend fun handleResponse(
        response: HttpResponse,
        latencyMillis: Long,
        onUsage: (UsageInfo) -> Unit,
        onRawResponse: (String) -> Unit,
    ): Result<String> {
        val text = response.bodyAsText()
        // 原始响应体先交给调用方（样本运行器保存原始响应；不写入普通日志）
        onRawResponse(text)
        if (!response.status.isSuccess()) {
            val raw = errorMessage(text, response.status.value)
            return Result.failure(classify(response.status.value, raw, response))
        }
        val parsed = try {
            lenientJson.decodeFromString<ChatCompletionResponse>(text)
        } catch (e: Exception) {
            null
        }
        // 服务商返回了 usage 才上报；解析失败或缺失一律不上报（不伪造用量）
        parsed?.usage?.let { usage ->
            onUsage(
                UsageInfo(
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    totalTokens = usage.totalTokens,
                    model = config.modelName,
                    latencyMillis = latencyMillis,
                ),
            )
        }
        val choice = parsed?.choices?.firstOrNull()
        val content = choice?.message?.content
        if (!content.isNullOrBlank()) return Result.success(content.trim())

        // 到这里说明 content 为空。区分三种情况，避免把「注定重试也没用」的场景反复重发扣费：
        val finishReason = choice?.finishReason
        val reasoning = choice?.message?.reasoningContent
        return when {
            // 被截断：max_tokens 太小。重发同样会再被截断，判为不可重试并给出可操作提示
            finishReason == "length" -> Result.failure(
                ModelError.InvalidRequest(
                    "模型输出被截断（max_tokens 太小）。请到「我的」页把该模型的「最大 Token」调大（建议 8000 以上）后重试。",
                ),
            )
            // 推理模型只吐了思考过程、没给正式答案：换普通模型才是正解，重试无意义
            !reasoning.isNullOrBlank() -> Result.failure(
                ModelError.InvalidRequest(
                    "该模型只返回了思考过程、没有正式内容，通常是推理模型（如 R1/QwQ/o1）在此步不适用或输出被截断。" +
                        "建议把「快速模型」换成同服务商的普通对话模型（如 deepseek-chat、qwen-plus），或调大最大 Token。",
                ),
            )
            // 真正的空返回：可重试一次
            else -> Result.failure(ModelError.Empty("模型返回为空"))
        }
    }

    private fun errorMessage(body: String, status: Int): String {
        if (body.isBlank()) return "HTTP $status"
        // 优先取标准 OpenAI 错误结构；取不到就保留原始返回体，
        // 否则像 WPS 这类服务商的自定义错误会被压成一句无信息的「HTTP 403」。
        val parsed = try {
            lenientJson.decodeFromString<ErrorResponse>(body).error?.message
        } catch (e: Exception) {
            null
        }
        return parsed?.takeIf { it.isNotBlank() }
            ?: body.trim().takeIf { it.isNotBlank() }?.take(ERROR_BODY_LIMIT)
            ?: "HTTP $status"
    }

    /**
     * HTTP 状态分类（N8）：
     * 401/403 → Auth；429 → RateLimited（读取 Retry-After）；其余 4xx → InvalidRequest（不重试）；
     * 5xx → Server（可重试）。
     */
    private fun classify(status: Int, message: String, response: HttpResponse?): ModelError = when {
        status == 401 || status == 403 -> ModelError.Auth(explainAuthFailure(status, message))
        status == 429 -> ModelError.RateLimited(message, parseRetryAfterMillis(response))
        status in 400..499 -> ModelError.InvalidRequest("$message（HTTP $status，请检查模型名、上下文长度或请求参数）")
        status in 500..599 -> ModelError.Server(message)
        else -> ModelError.Network(message)
    }

    /**
     * 401/403 的原文往往是服务商自己的英文提示（例如「You do not have a valid license of this
     * product…(#3501)」这类企业授权错误），直接透传给用户既看不懂、也不知道下一步该做什么。
     * 这里翻译成可行动的中文，并保留原文便于排查。
     *
     * 注意：这类错误出在「你配的这个接口地址 + Key」这一侧，不是本应用能绕过的，
     * 所以提示的重点是「换一个真正提供 OpenAI 兼容接口的服务商」。
     */
    private fun explainAuthFailure(status: Int, raw: String): String {
        val lower = raw.lowercase()
        val hint = when {
            // 服务商说「你没有这个产品的授权/许可」：多为企业版接口或该账号未开通此模型
            lower.contains("license") || lower.contains("not authorized") ||
                lower.contains("no permission") || lower.contains("permission denied") ->
                "这个接口拒绝了你的账号：它提示当前账号没有调用该服务的授权（不是本应用的问题）。" +
                    "请确认三件事：①「接口地址」填的是服务商公开的 OpenAI 兼容地址（通常以 /v1 结尾）；" +
                    "②这个 API Key 属于该服务商、且已开通对应模型；③该账号不是需要企业授权才能用的内部接口。" +
                    "建议换用 DeepSeek、阿里云百炼、火山方舟、月之暗面等提供 OpenAI 兼容接口的服务商重试。"

            lower.contains("invalid") && lower.contains("key") ->
                "API Key 不被这个接口接受，请检查是否复制完整、是否和「接口地址」属于同一个服务商。"

            lower.contains("expired") ->
                "API Key 或账号授权已过期，请到服务商后台重新获取。"

            lower.contains("quota") || lower.contains("balance") || lower.contains("insufficient") ->
                "账号额度或余额不足，请到服务商后台充值后重试。"

            status == 401 ->
                "API Key 无效或未被接受（HTTP 401）。请到「我的」页重新填写，注意 Key 要和「接口地址」属于同一个服务商。"

            else ->
                "接口拒绝了这次调用（HTTP 403）。通常是 API Key 与接口地址不匹配、账号未开通该模型，" +
                    "或这个地址不是面向个人的 OpenAI 兼容接口。请到「我的」页检查模型配置。"
        }
        // 原文附在后面：用户看不懂但可以截图给客服，也方便你排查
        return "$hint\n\n服务商原始返回：$raw"
    }

    private fun parseRetryAfterMillis(response: HttpResponse?): Long? {
        val header = response?.headers?.get(HttpHeaders.RetryAfter) ?: return null
        header.trim().toLongOrNull()?.let { return it * 1000L }
        return null
    }

    private fun chatBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    /** 只允许 HTTPS 接口，避免 API Key 走明文（B13） */
    private fun requireSecureUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (requireHttps && !trimmed.startsWith("https://", ignoreCase = true)) {
            throw ModelError.InvalidRequest("接口地址必须以 https:// 开头，避免 API Key 明文传输")
        }
        return trimmed
    }

    companion object {
        private val lenientJson = Json { ignoreUnknownKeys = true }

        /** 原始错误体最多保留这么长，避免整页 HTML 灌进提示里 */
        private const val ERROR_BODY_LIMIT = 300

        fun defaultClient(socketTimeout: Long = 120_000, requestTimeout: Long = 180_000): HttpClient =
            HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                // 总超时 180 秒：单次 AI 生成本来就可能跑 1-3 分钟（长 JSON 输出），
                // 收紧到 60 秒会主动杀死正常请求。socketTimeout 是「连续 120 秒无数据」的
                // 空闲超时——服务端一直在吐就不算超时，彻底挂死才会触发。
                // 推理模型思考阶段可能长时间无数据，样本运行器可传更长超时。
                requestTimeoutMillis = requestTimeout
                connectTimeoutMillis = 20_000
                socketTimeoutMillis = socketTimeout
            }
        }
    }
}

@Serializable
private data class ModelDto(
    val id: String,
)
