package com.example.zhishu

import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.OpenAiModelProvider
import com.example.zhishu.ai.UsageInfo
import com.example.zhishu.domain.model.ApiProfile
import com.example.zhishu.domain.model.ModelRole
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenAiModelProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(
        maxTokens: Int = 4096,
        temperature: Float = 0.7f,
        firstTokenTimeoutMillis: Long = 90_000,
    ): OpenAiModelProvider {
        val profile = ApiProfile(
            name = "test",
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            modelName = "test-model",
            maxTokens = maxTokens,
            temperature = temperature,
        )
        // MockWebServer 是 http，测试关掉 HTTPS 强制
        return OpenAiModelProvider(
            profile = profile,
            apiKey = "test-key",
            requireHttps = false,
            firstTokenTimeoutMillis = firstTokenTimeoutMillis,
        )
    }

    private fun request(): ModelRequest = ModelRequest(
        role = ModelRole.STORY,
        system = "你是助手",
        user = "生成一个测试结果",
    )

    @Test
    fun complete_success_returnsContent() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"ok\":true}"}}]}""",
            ),
        )
        val result = provider().complete(request())
        assertTrue(result.isSuccess)
        assertEquals("{\"ok\":true}", result.getOrThrow())
    }

    @Test
    fun complete_auth401_failsWithAuth() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"invalid key\"}}"),
        )
        val result = provider().complete(request())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ModelError.Auth)
    }

    @Test
    fun complete_rateLimited429_failsWithRateLimited() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "7")
                .setBody("{\"error\":{\"message\":\"slow down\"}}"),
        )
        val result = provider().complete(request())
        val error = result.exceptionOrNull()
        assertTrue(error is ModelError.RateLimited)
        assertTrue((error as ModelError.RateLimited).retryable)
        assertEquals(7_000L, error.retryAfterMillis)
    }

    /** 400 属于不可恢复错误，必须归类为 InvalidRequest 且不可重试（N8） */
    @Test
    fun complete_badRequest400_failsWithInvalidRequestAndNotRetryable() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"context too long\"}}"),
        )
        val error = provider().complete(request()).exceptionOrNull()
        assertTrue(error is ModelError.InvalidRequest)
        assertEquals(false, (error as ModelError).retryable)
    }

    /** 5xx 归类为 Server 且可重试（N8） */
    @Test
    fun complete_serverError500_failsWithServerAndRetryable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"oops\"}}"))
        val error = provider().complete(request()).exceptionOrNull()
        assertTrue(error is ModelError.Server)
        assertEquals(true, (error as ModelError).retryable)
    }

    /**
     * content 为空且 finish_reason=length → 归类为 Truncated（此前是 InvalidRequest）。
     *
     * 单列一类是为了让上层分清「换个更小请求没用、得少要点内容」和「参数本身不合法」。
     * 这是骨架失败的高频路径之一，此前零覆盖（生产读 finish_reason，测试从不写）。
     */
    @Test
    fun complete_finishReasonLength_failsWithTruncated() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""",
            ),
        )
        val error = provider().complete(request()).exceptionOrNull()
        assertTrue("应为 Truncated，实际：${error?.let { it::class.simpleName }}", error is ModelError.Truncated)
        assertEquals(false, (error as ModelError).retryable)
    }

    /**
     * 推理模型只吐 reasoning_content、正式 content 为空 → InvalidRequest（不可重试）。
     * 此前零覆盖。这是「快速模型配成推理模型 → 骨架总是失败」的直接来源。
     */
    @Test
    fun complete_reasoningOnly_failsWithInvalidRequest() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"","reasoning_content":"让我想想……"}}]}""",
            ),
        )
        val error = provider().complete(request()).exceptionOrNull()
        assertTrue(error is ModelError.InvalidRequest)
        assertEquals(false, (error as ModelError).retryable)
    }

    /**
     * 400 的包装文案不得含「上下文/长度/token」——否则降档判定会把模型名写错
     * 这类错误误判成 token 超限、白白降档重发。锁死这条不变量。
     */
    @Test
    fun complete_badRequest400_messageDoesNotTriggerDowngrade() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"The model does not exist\"}}"),
        )
        val message = provider().complete(request()).exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "400 包装文案不应含降档触发词，实际：$message",
            listOf("上下文", "最大长度", "长度超", "max_tokens", "context length", "token limit")
                .none { message.contains(it, ignoreCase = true) },
        )
    }

    @Test
    fun complete_emptyContent_failsWithEmpty() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"choices\":[]}"))
        val result = provider().complete(request())
        assertTrue(result.exceptionOrNull() is ModelError.Empty)
    }

    @Test
    fun httpBaseUrl_rejectedWhenHttpsRequired() = runBlocking {
        val profile = ApiProfile(
            name = "test",
            baseUrl = "http://insecure.example.com/v1",
            modelName = "test-model",
        )
        val error = OpenAiModelProvider(profile = profile, apiKey = "k")
            .complete(request()).exceptionOrNull()
        assertTrue(error is ModelError.InvalidRequest)
    }

    @Test
    fun listModels_parsesData() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":[{"id":"model-a"},{"id":"model-b"}]}""",
            ),
        )
        val result = provider().listModels()
        assertTrue(result.isSuccess)
        assertEquals(listOf("model-a", "model-b"), result.getOrThrow())
    }

    /**
     * 服务商返回自己的 403 授权错误（如 WPS 的 #3501 license 报错）时，
     * 不能把英文原文直接甩给用户：要翻译成可行动的中文，同时保留原文便于排查。
     */
    @Test
    fun complete_vendorLicense403_isTranslatedToActionableChinese() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                "status_code=403, You do not have a valid license of this product. " +
                    "Please contact your administrator to request a license. (#3501)",
            ),
        )
        val error = provider().complete(request()).exceptionOrNull()
        assertTrue(error is ModelError.Auth)
        val message = error!!.message.orEmpty()
        // 给出可行动的中文说明
        assertTrue(message.contains("没有调用该服务的授权"))
        assertTrue(message.contains("接口地址"))
        // 并保留服务商原文
        assertTrue(message.contains("#3501"))
        // 鉴权失败不可重试，避免反复扣费
        assertEquals(false, (error as ModelError).retryable)
    }

    /** 非标准错误体不能被压成一句无信息的「HTTP 403」，原文必须保留 */
    @Test
    fun complete_nonJsonErrorBody_keepsRawText() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("Bad model name: foo-bar"))
        val message = provider().complete(request()).exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("Bad model name: foo-bar"))
    }

    /** 401 也要给出「Key 和地址要属于同一服务商」这类可操作提示 */
    @Test
    fun complete_401_mentionsApiKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unexpected"))
        val message = provider().complete(request()).exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("API Key"))
    }

    /** 余额不足要直说充值，而不是笼统的「鉴权失败」 */
    @Test
    fun complete_403InsufficientBalance_mentionsTopUp() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"message":"Insufficient balance"}}"""),
        )
        val message = provider().complete(request()).exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("余额"))
    }

    /** 测试连接（listModels）失败时也要走同一套翻译，而不是只报状态码 */
    @Test
    fun listModels_403_isTranslatedToo() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                "You do not have a valid license of this product. (#3501)",
            ),
        )
        val error = provider().listModels().exceptionOrNull()
        assertTrue(error is ModelError.Auth)
        assertTrue(error!!.message.orEmpty().contains("没有调用该服务的授权"))
    }

    @Test
    fun jsonMode_includesResponseFormat() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{}"}}]}""",
            ),
        )
        provider().complete(request())
        val recorded = server.takeRequest().body.readUtf8()
        assertTrue(recorded.contains("response_format"))
        assertTrue(recorded.contains("json_object"))
        assertTrue(recorded.contains("test-model"))
    }

    /** 用户在设置页配置的 maxTokens / temperature 必须真正生效（B6） */
    @Test
    fun configuredLimits_appliedWhenRequestDoesNotOverride() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"{}"}}]}"""),
        )
        provider(maxTokens = 1234, temperature = 0.25f).complete(request())
        val recorded = server.takeRequest().body.readUtf8()
        assertTrue(recorded.contains("\"max_tokens\":1234"))
        assertTrue(recorded.contains("\"temperature\":0.25"))
    }

    /** 调用方显式给值时覆盖配置 */
    @Test
    fun requestOverride_takesPrecedenceOverConfig() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"{}"}}]}"""),
        )
        provider(maxTokens = 1234, temperature = 0.25f).complete(
            ModelRequest(
                role = ModelRole.STORY,
                system = "s",
                user = "u",
                maxTokens = 500,
                temperature = 0f,
            ),
        )
        val recorded = server.takeRequest().body.readUtf8()
        assertTrue(recorded.contains("\"max_tokens\":500"))
        assertTrue(recorded.contains("\"temperature\":0.0"))
    }

    // ---- 第二轮 P1-1：usage 接收 ----

    /** 服务商返回 usage 时必须解析并上报（业务成本统计的第一步） */
    @Test
    fun complete_withUsage_reportsParsedUsage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"ok\":true}"}}],""" +
                    """"usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165}}""",
            ),
        )
        var captured: UsageInfo? = null
        val result = provider().complete(request(), onUsage = { usage -> captured = usage })
        assertTrue(result.isSuccess)
        assertTrue("usage 必须被回调", captured != null)
        assertEquals(120L, captured!!.promptTokens)
        assertEquals(45L, captured!!.completionTokens)
        assertEquals(165L, captured!!.totalTokens)
        assertEquals("test-model", captured!!.model)
        assertTrue("必须测量到耗时", captured!!.latencyMillis >= 0)
    }

    /** 服务商不返回 usage 时不能失败，也不能伪造用量 */
    @Test
    fun complete_withoutUsage_doesNotFailAndDoesNotFabricate() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"choices":[{"message":{"content":"ok"}}]}"""),
        )
        var calls = 0
        var captured: UsageInfo? = null
        val result = provider().complete(request(), onUsage = { usage ->
            calls++
            captured = usage
        })
        assertTrue(result.isSuccess)
        assertEquals("没有 usage 时不得回调", 0, calls)
        assertEquals(null, captured)
    }

    // 流式可靠性降级的回归测试已移至 OpenAiModelProviderStreamTest（不挂 Robolectric，
    // 本机内存紧张时可稳定运行）。
}
