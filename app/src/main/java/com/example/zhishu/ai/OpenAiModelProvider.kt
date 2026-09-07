package com.example.zhishu.ai

import com.example.zhishu.domain.model.ApiProfile
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
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
    /** 流式接收：生成期间连接上持续有数据，中转站网关不会再按「N 秒无响应」掐断（详见 complete 注释） */
    @EncodeDefault
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptionsDto? = null,
)

@Serializable
private data class StreamOptionsDto(
    /** 流式最后一个 chunk 附带完整 usage；不支持该参数的服务商由 complete 自动降级重发 */
    @SerialName("include_usage")
    @EncodeDefault
    val includeUsage: Boolean = true,
)

/** 流式 SSE 单个 chunk（data: {...} 行）的最小解析结构 */
@Serializable
private data class StreamChunk(
    val choices: List<StreamChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
)

@Serializable
private data class StreamChoiceDto(
    val delta: StreamDeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class StreamDeltaDto(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
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
    private val profile: ApiProfile,
    private val apiKey: String,
    clientOverride: HttpClient? = null,
    /** 仅测试用：允许 http 明文地址。生产路径固定为 true，强制 HTTPS（B13） */
    private val requireHttps: Boolean = true,
    /**
     * 可配置超时（批次 B 样本用）。生产默认 180s/120s；
     * 推理模型（doubao-seed/deepseek-r1 等）思考阶段可能长时间无数据，
     * 会击穿 socket 空闲超时——样本运行器传更长超时，不改变生产默认值。
     */
    /**
     * 可配置超时。生产默认 300s/600s：推理模型思考阶段长时间无数据、免费通道
     * 排队都在余量内（原来 120s/180s 会把高峰期的正常生成掐断，
     * 用户看到的是「网络没连上服务器」的误导提示）。
     */
    private val socketTimeoutMillis: Long = 300_000,
    private val requestTimeoutMillis: Long = 600_000,
    /**
     * 关闭推理模型的思考（Ark thinking:disabled）。样本运行器用：
     * 把 doubao-seed 等推理模型当普通对话模型，避免长思考导致超时与高成本。
     */
    private val thinkingDisabled: Boolean = false,
    /**
     * 生产默认关闭思考（与 [thinkingDisabled] 任一生效即发送 thinking:disabled）。
     * 推理模型的思考阶段服务端不吐数据，免费通道排队+思考轻松超过生产 socket
     * 空闲超时，用户只会看到「网络没连上服务器」。严格校验参数的服务商由
     * complete 内部自动剥掉该字段重发，不影响兼容。
     */
    private val thinkingOffByDefault: Boolean = true,
) : ModelProvider {
    init {
        require(socketTimeoutMillis > 0 && requestTimeoutMillis > 0) { "超时必须是正数" }
    }

    private val client: HttpClient = clientOverride ?: defaultClient(socketTimeoutMillis, requestTimeoutMillis)

    override suspend fun listModels(): Result<List<String>> {
        return try {
            val base = requireSecureUrl(profile.baseUrl)
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

    /** 服务商不认识 thinking / stream 参数的信号：外层据此剥掉对应字段降级重发 */
    private class ParamRejected(
        val dropThinking: Boolean = false,
        val dropStreamOptions: Boolean = false,
        val dropStream: Boolean = false,
    ) : Exception("unsupported parameter")

    override suspend fun complete(
        request: ModelRequest,
        onUsage: (UsageInfo) -> Unit,
        onRawResponse: (String) -> Unit,
    ): Result<String> {
        return try {
            val base = requireSecureUrl(profile.baseUrl)
            val url = chatBaseUrl(base)

            /**
             * 默认走流式（stream=true）：生成需要 1-3 分钟，中转站网关普遍按
             * 「N 秒无响应」掐断非流式连接——上游模型其实跑完并计了费（用户在
             * 服务商后台看得到调用记录），结果却在回程被掐，APP 侧报 5xx/断连。
             * 流式下连接持续有 chunk 流动，网关不再按无响应超时处理。
             * 个别服务商不支持流式或 stream_options 时自动降级重发。
             */
            suspend fun send(useStream: Boolean, useStreamOptions: Boolean, useThinking: Boolean): Result<String> {
                val body = ChatCompletionRequest(
                    model = profile.modelName,
                    messages = request.messages().map { ChatMessageDto(it.role, it.content) },
                    // 用户在设置页配置的值生效，调用方只在需要时覆盖（B6）
                    maxTokens = request.effectiveMaxTokens(profile.maxTokens),
                    temperature = request.effectiveTemperature(profile.temperature),
                    responseFormat = if (request.jsonMode && profile.supportsJson) ResponseFormatDto() else null,
                    // 生产默认关闭推理模型的思考：思考阶段服务端不吐任何数据，几十秒到几分钟的
                    // 空闲会击穿 socket 空闲超时（用户看到的是「网络没连上服务器」——其实是
                    // 本应用等不及掐断了）。本应用的步骤都是契约/路线/核验类 JSON 输出，不需要
                    // 长思考；关掉后首字节快很多，还省下按 token 计费的思考开销。
                    thinking = if (useThinking) ThinkingDto(type = "disabled") else null,
                    stream = useStream,
                    streamOptions = if (useStream && useStreamOptions) StreamOptionsDto() else null,
                )
                val startedAt = System.currentTimeMillis()
                return client.preparePost(url) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.execute { response ->
                    val latencyMillis = System.currentTimeMillis() - startedAt
                    if (!response.status.isSuccess()) {
                        val text = response.bodyAsText()
                        onRawResponse(text)
                        val rejected = rejectsParam(text)
                        if (rejected != null) throw rejected
                        return@execute Result.failure(
                            classify(response.status.value, errorMessage(text, response.status.value), response),
                        )
                    }
                    if (useStream) {
                        readStreamResponse(response, latencyMillis, onUsage, onRawResponse)
                    } else {
                        handleResponse(response, latencyMillis, onUsage, onRawResponse)
                    }
                }
            }

            // 降级顺序：流式+usage → 去 stream_options → 整个转非流式；thinking 参数单独降级。
            // 每一级只在服务商明确抱怨对应参数时才触发，真正的业务错误原样上抛。
            var dropThinking = false
            var dropStreamOptions = false
            var dropStream = false
            var fallbackError: Throwable? = null
            repeat(4) {
                try {
                    return send(
                        useStream = !dropStream,
                        useStreamOptions = !dropStreamOptions,
                        useThinking = !dropThinking,
                    )
                } catch (e: ParamRejected) {
                    dropThinking = dropThinking || e.dropThinking
                    dropStreamOptions = dropStreamOptions || e.dropStreamOptions
                    dropStream = dropStream || e.dropStream
                    fallbackError = e
                }
            }
            Result.failure(
                ModelError.InvalidRequest(
                    "该服务商不接受本应用的请求参数（思考开关/流式），已尝试全部降级方式仍失败。建议换一个 OpenAI 兼容服务商。",
                ),
            )
        } catch (e: ModelError) {
            Result.failure(e)
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(ModelError.Network(timeoutMessage))
        } catch (e: java.io.InterruptedIOException) {
            // OkHttp 的读空闲超时走 InterruptedIOException（socket timeout），同样按超时解释
            Result.failure(ModelError.Network(timeoutMessage))
        } catch (e: Exception) {
            Result.failure(ModelError.Network(e.message ?: "请求失败"))
        }
    }

    /** 超时的人话解释：区分「服务器慢」和「你断网」，避免误导用户去查手机网络 */
    private val timeoutMessage: String
        get() = "连接超时：服务器超过 ${requestTimeoutMillis / 1000} 秒没有完成响应，或长时间没有返回任何数据。" +
            "免费通道高峰期很常见。请重试；反复出现就换更快的模型档位。"

    /** 服务商明确抱怨 thinking / stream 参数（而不是别的错）时才降级，避免把真错误吞掉重发 */
    private suspend fun rejectsParam(text: String): ParamRejected? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        val complaint =
            lower.contains("unrecognized") || lower.contains("unknown") || lower.contains("not supported") ||
                lower.contains("unsupported") || lower.contains("unexpected") || lower.contains("invalid")
        if (!complaint) return null
        return when {
            // 只抱怨 usage 附带参数：保留流式，去掉 stream_options
            lower.contains("stream_options") -> ParamRejected(dropStreamOptions = true)
            // 抱怨流式本身：整个转非流式
            lower.contains("stream") -> ParamRejected(dropStream = true)
            // 抱怨 thinking 字段：剥掉该参数原样重发
            lower.contains("thinking") -> ParamRejected(dropThinking = true)
            else -> null
        }
    }

    /**
     * 流式 SSE 解析：聚合 `data: {...}` 行的 delta.content / delta.reasoning_content，
     * usage 取自最后一个 chunk（stream_options.include_usage）。
     * 兼容两类「不按规矩来的服务商」：① 返回完整 JSON 而非 SSE——按非流式解析；
     * ② 中途断流但 finish_reason 已到——内容完整，照常成功。
     */
    private suspend fun readStreamResponse(
        response: HttpResponse,
        latencyMillis: Long,
        onUsage: (UsageInfo) -> Unit,
        onRawResponse: (String) -> Unit,
    ): Result<String> {
        val channel = response.bodyAsChannel()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var finishReason: String? = null
        var usage: UsageDto? = null
        val raw = StringBuilder()
        val dataLines = mutableListOf<String>()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            raw.appendLine(line)
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            dataLines.add(payload)
            val chunk = runCatching { lenientJson.decodeFromString<StreamChunk>(payload) }.getOrNull() ?: continue
            chunk.usage?.let { usage = it }
            val choice = chunk.choices.firstOrNull() ?: continue
            choice.finishReason?.let { finishReason = it }
            choice.delta?.content?.let { content.append(it) }
            choice.delta?.reasoningContent?.let { reasoning.append(it) }
        }
        onRawResponse(raw.toString())
        usage?.let {
            onUsage(
                UsageInfo(
                    promptTokens = it.promptTokens,
                    completionTokens = it.completionTokens,
                    totalTokens = it.totalTokens,
                    model = profile.modelName,
                    latencyMillis = latencyMillis,
                ),
            )
        }
        // 服务商没真给 SSE（直接回了完整 JSON）：按非流式语义解析
        if (dataLines.isEmpty()) {
            return parseCompletionBody(raw.toString(), latencyMillis, onUsage)
        }
        // 连接中途断掉且服务端没给结束标记：内容不完整，按网络错误让上层重试
        if (finishReason == null && content.isNotEmpty()) {
            return Result.failure(
                ModelError.Network("生成中途连接中断（服务端未发送完成标记），已收到 ${content.length} 字。请重试。"),
            )
        }
        return judgeOutput(contentText = content.toString(), finishReason = finishReason, reasoningText = reasoning.toString())
    }

    /** 内容判定（与非流式 handleResponse 同一套语义）：非空成功 / 截断 / 只思考 / 空返回 */
    private fun judgeOutput(contentText: String, finishReason: String?, reasoningText: String): Result<String> {
        if (contentText.isNotBlank()) return Result.success(contentText.trim())
        return when {
            finishReason == "length" -> Result.failure(
                ModelError.Truncated(
                    "模型这一步的输出被截断了（finish_reason=length）。通常是这一步要生成的内容偏长，" +
                        "可稍后重试——知识主干会自动只补没写完的部分。若反复如此，多半是所选模型单次" +
                        "输出上限偏低，建议换一个单次回复更长的模型。",
                ),
            )
            reasoningText.isNotBlank() -> Result.failure(
                ModelError.InvalidRequest(
                    "该模型只返回了思考过程、没有正式内容，通常是推理模型（如 R1/QwQ/o1）在这一步" +
                        "把额度全花在思考上、或此步不适用。建议把对应角色换成同服务商的普通对话模型" +
                        "（如 deepseek-chat、qwen-plus、doubao 非 thinking 版）。",
                ),
            )
            else -> Result.failure(ModelError.Empty("模型返回为空"))
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
        return parseCompletionBody(text, latencyMillis, onUsage)
    }

    /** 解析非流式的 chat.completion 响应体：usage 上报 + content 三分支判定 */
    private fun parseCompletionBody(text: String, latencyMillis: Long, onUsage: (UsageInfo) -> Unit): Result<String> {
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
                    model = profile.modelName,
                    latencyMillis = latencyMillis,
                ),
            )
        }
        val choice = parsed?.choices?.firstOrNull()
        val content = choice?.message?.content
        return judgeOutput(
            contentText = content.orEmpty(),
            finishReason = choice?.finishReason,
            reasoningText = choice?.message?.reasoningContent.orEmpty(),
        )
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
        // 包装文案刻意不含「上下文」「长度」「token」这些词：降档判定（DefaultModelRouter
        // .looksLikeTokenLimit）靠这些词识别「服务商嫌请求太长」。早先这里写着
        // 「请检查模型名、上下文长度或请求参数」，等于给**每一条** 4xx 都塞进了关键词——
        // 于是模型名写错、账号没开通这类真需要用户改配置的错误，也被当成 token 超限
        // 白白降档重发三次。降档只应认服务商原文里的长度抱怨。
        status in 400..499 -> ModelError.InvalidRequest("$message（HTTP $status，请检查模型名或请求参数）")
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

        fun defaultClient(socketTimeout: Long = 300_000, requestTimeout: Long = 600_000): HttpClient =
            HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                // 总超时 600 秒：单次 AI 生成本来就可能跑 1-3 分钟（长 JSON 输出），
                // 收紧到 60 秒会主动杀死正常请求。socketTimeout 是「连续 300 秒无数据」的
                // 空闲超时——服务端一直在吐就不算超时，彻底挂死才会触发。
                // 推理模型思考阶段长时间无数据、免费通道高峰期排队，都在这个余量内。
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
