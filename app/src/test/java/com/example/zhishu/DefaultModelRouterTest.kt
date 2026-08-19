package com.example.zhishu

import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelProvider
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.DefaultModelRouter
import com.example.zhishu.domain.model.ModelRole
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private data class TestPayload(
    val ok: Boolean,
)

class DefaultModelRouterTest {

    private val decoder = object : JsonDecoder<TestPayload> {
        override fun decode(text: String): TestPayload =
            Json { ignoreUnknownKeys = true }.decodeFromString(TestPayload.serializer(), text)
    }

    private fun request(): ModelRequest = ModelRequest(
        role = ModelRole.FAST,
        system = "s",
        user = "u",
    )

    @Test
    fun success_decodesPayload() = runBlocking {
        val provider = SequenceProvider(listOf(Result.success("{\"ok\":true}")))
        val router = DefaultModelRouter(provider)
        val result = router.generate(ModelRole.FAST, request(), decoder)
        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrThrow().ok)
    }

    @Test
    fun invalidJson_repairOnce_thenDecodes() = runBlocking {
        val provider = SequenceProvider(
            listOf(
                Result.success("{\"ok\": tru"),
                Result.success("{\"ok\":true}"),
            ),
        )
        val router = DefaultModelRouter(provider)
        val result = router.generate(ModelRole.FAST, request(), decoder)
        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrThrow().ok)
        assertEquals(2, provider.calls)
    }

    /** 修复失败后直接返回 Decode，不再重新生成（N9） */
    @Test
    fun repairFails_returnsDecodeWithoutRegenerating() = runBlocking {
        val provider = SequenceProvider(listOf(Result.success("{\"ok\": tru")))
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L, 1L))
        val result = router.generate(ModelRole.FAST, request(), decoder)
        assertTrue(result.exceptionOrNull() is ModelError.Decode)
        // 一次生成 + 一次修复，绝不放大成四次生成四次修复
        assertEquals(2, provider.calls)
    }

    @Test
    fun networkErrors_retryThenSucceeds() = runBlocking {
        val provider = SequenceProvider(
            listOf(
                Result.failure(ModelError.Network("timeout")),
                Result.failure(ModelError.Network("timeout")),
                Result.success("{\"ok\":true}"),
            ),
        )
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L))
        val result = router.generate(ModelRole.FAST, request(), decoder)
        assertTrue(result.isSuccess)
        assertEquals(3, provider.calls)
    }

    @Test
    fun authError_doesNotRetry() = runBlocking {
        val provider = SequenceProvider(listOf(Result.failure(ModelError.Auth("bad key"))))
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L, 1L))
        val result = router.generate(ModelRole.FAST, request(), decoder)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ModelError.Auth)
        assertEquals(1, provider.calls)
    }

    /** 400 类错误只调用一次，不会重复扣费（N8） */
    @Test
    fun invalidRequest_doesNotRetry() = runBlocking {
        val provider = SequenceProvider(listOf(Result.failure(ModelError.InvalidRequest("bad model"))))
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L, 1L))
        val result = router.generate(ModelRole.FAST, request(), decoder)
        assertTrue(result.exceptionOrNull() is ModelError.InvalidRequest)
        assertEquals(1, provider.calls)
    }

    @Test
    fun rateLimited_retriesWithinBudget() = runBlocking {
        val provider = SequenceProvider(
            listOf(
                Result.failure(ModelError.RateLimited("429", retryAfterMillis = 1L)),
                Result.success("{\"ok\":true}"),
            ),
        )
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L))
        assertTrue(router.generate(ModelRole.FAST, request(), decoder).isSuccess)
        assertEquals(2, provider.calls)
    }

    @Test
    fun serverErrors_exhaustBudget() = runBlocking {
        val provider = SequenceProvider(listOf(Result.failure(ModelError.Server("500"))))
        val router = DefaultModelRouter(
            provider,
            retryDelaysMillis = listOf(1L, 1L, 1L),
            maxProviderCalls = 4,
        )
        val result = router.generate(ModelRole.FAST, request(), decoder)
        assertTrue(result.isFailure)
        assertEquals(4, provider.calls)
    }

    /** 总调用预算是硬上限（N9） */
    @Test
    fun callBudget_isRespected() = runBlocking {
        val provider = SequenceProvider(listOf(Result.failure(ModelError.Network("x"))))
        val router = DefaultModelRouter(
            provider,
            retryDelaysMillis = listOf(1L, 1L, 1L, 1L, 1L),
            maxProviderCalls = 2,
        )
        assertTrue(router.generate(ModelRole.FAST, request(), decoder).isFailure)
        assertEquals(2, provider.calls)
    }

    private class SequenceProvider(
        private val results: List<Result<String>>,
    ) : ModelProvider {
        var calls = 0

        override suspend fun listModels(): Result<List<String>> = Result.success(emptyList())

        override suspend fun complete(request: ModelRequest): Result<String> {
            val index = calls.coerceAtMost(results.size - 1)
            calls += 1
            return results[index]
        }
    }
}
