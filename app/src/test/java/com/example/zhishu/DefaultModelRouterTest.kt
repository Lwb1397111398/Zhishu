package com.example.zhishu

import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.AttemptKind
import com.example.zhishu.ai.AttemptOutcome
import com.example.zhishu.ai.CallAttempt
import com.example.zhishu.ai.ModelCallStats
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelProvider
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.DefaultModelRouter
import com.example.zhishu.ai.UsageInfo
import com.example.zhishu.ai.UsageStatus
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
        private val usages: List<UsageInfo> = emptyList(),
    ) : ModelProvider {
        var calls = 0

        override suspend fun listModels(): Result<List<String>> = Result.success(emptyList())

        override suspend fun complete(
            request: ModelRequest,
            onUsage: (UsageInfo) -> Unit,
            onRawResponse: (String) -> Unit,
        ): Result<String> {
            val index = calls.coerceAtMost(results.size - 1)
            // 每次调用上报对应的 usage（没有就不报，模拟服务商缺失 usage）
            usages.getOrNull(calls)?.let(onUsage)
            calls += 1
            return results[index]
        }
    }

    // ---- 第二轮 P1-1：成本统计口径（业务 / 重试 / JSON 修复 / 总） ----

    @Test
    fun success_reportsBusinessUsageOnly() = runBlocking {
        val provider = SequenceProvider(
            listOf(Result.success("{\"ok\":true}")),
            usages = listOf(UsageInfo(promptTokens = 10, completionTokens = 20, totalTokens = 30)),
        )
        var stats: ModelCallStats? = null
        val router = DefaultModelRouter(provider)
        assertTrue(router.generate(ModelRole.FAST, request(), decoder, onStats = { stats = it }).isSuccess)
        assertEquals(30L, stats!!.business.totalTokens)
        assertEquals(0L, stats!!.retry.totalTokens)
        assertEquals(0L, stats!!.repair.totalTokens)
        assertEquals(30L, stats!!.total.totalTokens)
    }

    /** 网络错误退避重试后成功：第一次计入 business，重试计入 retry（口径分开才能解释成本） */
    @Test
    fun retryUsage_classifiedAsRetryNotBusiness() = runBlocking {
        val provider = SequenceProvider(
            listOf(
                Result.failure(ModelError.Network("timeout")),
                Result.success("{\"ok\":true}"),
            ),
            usages = listOf(
                UsageInfo(promptTokens = 100, completionTokens = 0, totalTokens = 100),
                UsageInfo(promptTokens = 100, completionTokens = 200, totalTokens = 300),
            ),
        )
        var stats: ModelCallStats? = null
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L))
        assertTrue(router.generate(ModelRole.FAST, request(), decoder, onStats = { stats = it }).isSuccess)
        assertEquals(100L, stats!!.business.totalTokens)
        assertEquals(300L, stats!!.retry.totalTokens)
        assertEquals(0L, stats!!.repair.totalTokens)
        assertEquals(400L, stats!!.total.totalTokens)
    }

    /** JSON 修复调用独立计类，不混入业务调用（修复不是原业务） */
    @Test
    fun repairUsage_classifiedAsRepair() = runBlocking {
        val provider = SequenceProvider(
            listOf(
                Result.success("{\"ok\": tru"),
                Result.success("{\"ok\":true}"),
            ),
            usages = listOf(
                UsageInfo(totalTokens = 500),
                UsageInfo(totalTokens = 80),
            ),
        )
        var stats: ModelCallStats? = null
        val router = DefaultModelRouter(provider)
        assertTrue(router.generate(ModelRole.FAST, request(), decoder, onStats = { stats = it }).isSuccess)
        assertEquals(500L, stats!!.business.totalTokens)
        assertEquals(0L, stats!!.retry.totalTokens)
        assertEquals(80L, stats!!.repair.totalTokens)
        assertEquals(580L, stats!!.total.totalTokens)
    }

    /** 不可重试的鉴权失败：没有 usage 就不伪造用量 */
    @Test
    fun authFailure_withoutUsage_doesNotFabricate() = runBlocking {
        val provider = SequenceProvider(listOf(Result.failure(ModelError.Auth("bad key"))))
        var stats: ModelCallStats? = null
        val router = DefaultModelRouter(provider)
        assertTrue(router.generate(ModelRole.FAST, request(), decoder, onStats = { stats = it }).isFailure)
        assertEquals(0L, stats!!.business.totalTokens)
        assertEquals(0L, stats!!.retry.totalTokens)
        assertEquals(0L, stats!!.repair.totalTokens)
        assertEquals(0L, stats!!.total.totalTokens)
    }

    // ---- 批次 B B.0-5：attempt 级记录（一次 HTTP 尝试一条，失败不消失） ----

    /** 失败尝试也必须出现在 attempt 记录里，不能因为最终成功而消失 */
    @Test
    fun retryAfterFailure_keepsBothAttempts() = runBlocking {
        val provider = SequenceProvider(
            listOf(
                Result.failure(ModelError.Network("timeout")),
                Result.success("{\"ok\":true}"),
            ),
            usages = listOf(UsageInfo(totalTokens = 0), UsageInfo(totalTokens = 300)),
        )
        val attempts = mutableListOf<CallAttempt>()
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L))
        assertTrue(
            router.generate(ModelRole.FAST, request(), decoder, onAttempt = { attempts += it }).isSuccess,
        )
        assertEquals("失败尝试与成功尝试都必须保留", 2, attempts.size)
        assertEquals(AttemptKind.BUSINESS, attempts[0].kind)
        assertEquals(AttemptOutcome.FAILED, attempts[0].outcome)
        assertEquals(AttemptKind.RETRY, attempts[1].kind)
        assertEquals(AttemptOutcome.SUCCESS, attempts[1].outcome)
        assertEquals("重试必须属于同一业务调用", attempts[0].businessCallId, attempts[1].businessCallId)
        assertEquals(1, attempts[0].attempt)
        assertEquals(2, attempts[1].attempt)
    }

    /** 服务商没报 usage → usageStatus=MISSING，不当作 0（0 是 REPORTED 的零值） */
    @Test
    fun missingUsage_markedMissingNotZero() = runBlocking {
        val provider = SequenceProvider(listOf(Result.success("{\"ok\":true}")))
        val attempts = mutableListOf<CallAttempt>()
        val router = DefaultModelRouter(provider)
        assertTrue(router.generate(ModelRole.FAST, request(), decoder, onAttempt = { attempts += it }).isSuccess)
        assertEquals(1, attempts.size)
        assertEquals(UsageStatus.MISSING, attempts[0].usageStatus)
        assertEquals(0L, attempts[0].usage.totalTokens)
    }

    /** JSON 修复尝试独立成条，并关联到原业务调用 */
    @Test
    fun repairAttempt_linkedToBusinessCall() = runBlocking {
        val provider = SequenceProvider(
            listOf(
                Result.success("{\"ok\": tru"),
                Result.success("{\"ok\":true}"),
            ),
            usages = listOf(UsageInfo(totalTokens = 500), UsageInfo(totalTokens = 80)),
        )
        val attempts = mutableListOf<CallAttempt>()
        val router = DefaultModelRouter(provider)
        assertTrue(router.generate(ModelRole.FAST, request(), decoder, onAttempt = { attempts += it }).isSuccess)
        assertEquals(2, attempts.size)
        assertEquals(AttemptKind.BUSINESS, attempts[0].kind)
        assertEquals(AttemptKind.JSON_REPAIR, attempts[1].kind)
        assertEquals("修复必须关联原业务调用", attempts[0].businessCallId, attempts[1].businessCallId)
        assertEquals(2, attempts[1].attempt)
    }

    /** 请求未发出的场景不应伪造 attempt（当前路由无此路径，语义为 NOT_APPLICABLE 保留） */
    @Test
    fun attemptKindEnum_hasAllThreeKinds() {
        assertEquals(3, AttemptKind.entries.size)
        assertTrue(AttemptKind.entries.containsAll(listOf(AttemptKind.BUSINESS, AttemptKind.RETRY, AttemptKind.JSON_REPAIR)))
    }
}
