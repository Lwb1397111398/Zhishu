package com.example.zhishu

import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.OpenAiModelProvider
import com.example.zhishu.domain.model.ApiProfile
import com.example.zhishu.domain.model.ModelRole
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 流式可靠性降级的回归测试。
 *
 * 单独成类、刻意不挂 Robolectric：本用例只依赖 Provider + MockWebServer，
 * 不碰任何 Android API。原类整体跑 Robolectric 会加载整套框架（本机内存紧张时
 * 直接把测试 JVM 挤崩），把纯网络用例隔离出来才能稳定验证首字超时/降级逻辑。
 */
class OpenAiModelProviderStreamTest {

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
        firstTokenTimeoutMillis: Long = 90_000,
        bodyStallHardTimeoutMillis: Long = 10 * 60_000,
    ): OpenAiModelProvider {
        val profile = ApiProfile(
            name = "test",
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            modelName = "test-model",
            maxTokens = 4096,
            temperature = 0.7f,
        )
        return OpenAiModelProvider(
            profile = profile,
            apiKey = "test-key",
            requireHttps = false,
            firstTokenTimeoutMillis = firstTokenTimeoutMillis,
            bodyStallHardTimeoutMillis = bodyStallHardTimeoutMillis,
        )
    }

    private fun request(): ModelRequest = ModelRequest(
        role = ModelRole.STORY,
        system = "你是助手",
        user = "生成一个测试结果",
    )

    // ---- 流式可靠性降级：卡死「正在写正文」的根因修复 ----

    /**
     * 服务商收到请求后一直 stall、连状态行都不发（限流排队的极端表现）：
     * 必须在「首字超时」放弃这次流式，并降级为非流式重发一次。非流式若成功，整次调用即成功。
     *
     * 用 NO_RESPONSE 而不是 throttleBody：后者会同时节流服务端【读请求体】
     * （provider 发的 JSON 有几百字节，1 字节/3 秒要把测试拖到 15 分钟），且与真实故障
     * 「200 后不吐字节」不完全等价。NO_RESPONSE 是「读完请求后一个字节也不回」——恰好
     * 触发 OkHttp readTimeout，等价复现「服务端挂起、首字节迟迟不到」，且请求体被完整记录可断言。
     */
    @Test
    fun streamFirstByteStall_fallsBackToNonStreamAndSucceeds() = runBlocking {
        // 第一次（流式）：服务端读完请求后不写回任何响应（排队挂死）
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        // 第二次（降级为非流式）：正常返回
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"ok\":true}","finish_reason":"stop"}}]}""",
            ),
        )
        val startedAt = System.currentTimeMillis()
        val result = provider(firstTokenTimeoutMillis = 500).complete(request())
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue(
            "流式卡首字应在首字超时后快速降级非流式并成功，实际：${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
        assertTrue(
            "降级应在秒级完成、而不是拖满 requestTimeout（实际 ${elapsed}ms）",
            elapsed < 5_000,
        )
        assertEquals("{\"ok\":true}", result.getOrThrow())

        // 两次请求：第一次带 stream=true，第二次必须已降级为 stream=false
        val first = server.takeRequest(2, TimeUnit.SECONDS)?.body?.readUtf8()
        val second = server.takeRequest(2, TimeUnit.SECONDS)?.body?.readUtf8()
        assertTrue("第一次应请求流式", first?.contains("\"stream\":true") == true)
        assertTrue("降级后第二次应为非流式", second?.contains("\"stream\":false") == true)
    }

    /**
     * 流式卡首字后已降级非流式：这一次若仍失败（如服务商抖动返回 5xx），错误必须是
     * 可重试的 Server 类，而不是把「流式抖动」的细节泄漏给用户。同时确认整个过程
     * 只用了一次降级、没有无限重发。
     */
    @Test
    fun streamStall_thenNonStreamFails_reportsRetryableServer(): Unit = runBlocking {
        // 流式 stall → 非流式返回 500（服务商抖动）
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":{\"message\":\"overloaded\"}}"))
        val error = provider(firstTokenTimeoutMillis = 500).complete(request()).exceptionOrNull()
        assertTrue(
            "应为可重试的 Server 错误，实际：${error?.let { it::class.simpleName }}（${error?.message}）",
            error is ModelError.Server,
        )
        assertEquals(true, (error as ModelError).retryable)
        // 两次 HTTP：流式 + 非流式，不多不少
        server.takeRequest(2, TimeUnit.SECONDS)
        server.takeRequest(2, TimeUnit.SECONDS)
    }

    /**
     * 心跳保活绕过首字超时（2026-09 用户实测「正在写正文」无限卡死的根因）：
     * 中转站排队时按固定间隔吐 SSE 注释行（`: keep-alive`）——字节一直在来，
     * OkHttp readTimeout 永不触发；但没有一条是正文。必须在「正文硬超时」
     * （距上一条正文数据的时长，注释行不算）按期放弃，并降级非流式重发成功。
     *
     * 节流节奏 120 字节/100ms ≈ 一行心跳每 100ms：比首字超时（5 秒）密得多，
     * readTimeout 不会触发；比正文硬超时（500ms）密，注释行持续流入——
     * 唯一能让这条流退场的就是正文硬超时本身。
     */
    @Test
    fun heartbeatOnlyStream_bodyStallHardTimeout_fallsBackToNonStream(): Unit = runBlocking {
        // 第一次（流式）：200 后持续吐注释行心跳、永远不吐正文
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(": keep-alive\n".repeat(60))
                .throttleBody(120, 100, TimeUnit.MILLISECONDS),
        )
        // 第二次（降级为非流式）：正常返回
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"ok\":true}","finish_reason":"stop"}}]}""",
            ),
        )
        val startedAt = System.currentTimeMillis()
        val result = provider(
            firstTokenTimeoutMillis = 5_000,
            bodyStallHardTimeoutMillis = 500,
        ).complete(request())
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue(
            "只吐心跳的流式应在正文硬超时后降级非流式并成功，实际：${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
        assertTrue(
            "降级应在秒级完成、而不是等心跳把连接拖满（实际 ${elapsed}ms）",
            elapsed < 5_000,
        )
        assertEquals("{\"ok\":true}", result.getOrThrow())

        val first = server.takeRequest(2, TimeUnit.SECONDS)?.body?.readUtf8()
        val second = server.takeRequest(2, TimeUnit.SECONDS)?.body?.readUtf8()
        assertTrue("第一次应请求流式", first?.contains("\"stream\":true") == true)
        assertTrue("降级后第二次应为非流式", second?.contains("\"stream\":false") == true)
    }

    /**
     * 正文数据持续在吐的慢流**不能**被正文硬超时误杀：计时随每条正文数据清零，
     * 只要 delta 一直在来，流就能跑到自然结束。节流 120 字节/100ms，
     * 整个响应约 2 秒——远超 500ms 的硬超时，但因为每行都是正文，必须成功。
     */
    @Test
    fun slowButFlowingStream_survivesBodyStallHardTimeout(): Unit = runBlocking {
        val chunks = sequenceOf("Hello", " ", "world").joinToString("") { piece ->
            "data: {\"choices\":[{\"delta\":{\"content\":\"$piece\"}}]}\n\n"
        } +
            // 结束标记要带 finish_reason，否则 readStreamResponse 会按
            // 「连接断掉且没收到完成标记」判 StreamFallback（那测的就不是本逻辑了）
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(chunks)
                .throttleBody(120, 100, TimeUnit.MILLISECONDS),
        )
        val result = provider(
            firstTokenTimeoutMillis = 5_000,
            bodyStallHardTimeoutMillis = 500,
        ).complete(request())
        assertTrue(
            "正文持续在吐的慢流不应被硬超时误杀，实际：${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
        assertEquals("Hello world", result.getOrThrow())
    }
}
