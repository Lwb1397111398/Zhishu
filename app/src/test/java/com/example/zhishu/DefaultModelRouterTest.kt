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

    // ---- max_tokens 降档：把「窗口装不下」这类硬失败救回来 ----

    /**
     * 输出上限已按用户意愿放到模型允许的最大（5 万）。窗口更小的模型会返回 400，
     * 而 400 是 InvalidRequest（retryable=false）——原来这会让该步**永久**失败。
     * 现在路由认出这是「嫌 max_tokens 太大」，减半重发。
     */
    @Test
    fun maxTokensTooLarge_downgradesAndSucceeds() = runBlocking {
        val provider = RecordingProvider(
            listOf(
                Result.failure(ModelError.InvalidRequest("max_tokens is too large: 50000（HTTP 400）")),
                Result.success("{\"ok\":true}"),
            ),
        )
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L))
        val result = router.generate(
            ModelRole.FAST,
            request().copy(maxTokens = 50_000),
            decoder,
        )
        assertTrue("降档重发应该成功，实际：${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(listOf(50_000, 25_000), provider.requestedMaxTokens)
    }

    /** 一直嫌大就一路降到底，然后如实报错——不无限降、不无限烧钱 */
    @Test
    fun maxTokensTooLarge_stopsDowngradingAtFloor() = runBlocking {
        val provider = RecordingProvider(
            listOf(Result.failure(ModelError.InvalidRequest("maximum context length exceeded"))),
        )
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L))
        val result = router.generate(
            ModelRole.FAST,
            request().copy(maxTokens = 50_000),
            decoder,
        )
        assertTrue(result.exceptionOrNull() is ModelError.InvalidRequest)
        // 首次 + 最多 3 次降档（MAX_TOKEN_DOWNGRADES），到 6250 就不再降
        assertEquals(listOf(50_000, 25_000, 12_500, 6_250), provider.requestedMaxTokens)
    }

    /**
     * 降档不许吃掉业务调用预算。
     *
     * maxProviderCalls=4 是给「1 次业务 + 3 次退避重试」留的，不是给降档阶梯用的。
     * 降档正好也是 4 次（50000→25000→12500→6250），一旦占预算，降档成功后
     * **一次网络抖动都扛不住、也没配额做 JSON 修复**，整步直接失败——用户看到的
     * 就是「有一堆调用记录，结果全没用上」。所以降档要退还配额。
     */
    @Test
    fun downgrade_doesNotConsumeRetryBudget() = runBlocking {
        val tooLarge = Result.failure<String>(ModelError.InvalidRequest("max_tokens is too large"))
        val provider = RecordingProvider(
            listOf(
                tooLarge,
                tooLarge,
                tooLarge,
                // 降到底后才轮到真正的一次业务调用，这次撞上网络抖动
                Result.failure(ModelError.Network("timeout")),
                // 退避重试仍必须有配额——降档吃掉预算的话这一步根本发不出去
                Result.success("{\"ok\":true}"),
            ),
        )
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L, 1L))
        val result = router.generate(
            ModelRole.FAST,
            request().copy(maxTokens = 50_000),
            decoder,
        )
        assertTrue(
            "降档 3 次后仍应保有重试配额，实际：${result.exceptionOrNull()?.message}",
            result.isSuccess,
        )
        assertEquals(
            listOf(50_000, 25_000, 12_500, 6_250, 6_250),
            provider.requestedMaxTokens,
        )
    }

    /**
     * 不是 token 长度问题的 400 不许降档重试。
     * 「模型名写错」需要用户改配置，偷偷重发只会多花钱、还把真正的原因藏起来。
     */
    @Test
    fun otherInvalidRequest_doesNotDowngrade() = runBlocking {
        val provider = RecordingProvider(
            listOf(Result.failure(ModelError.InvalidRequest("The model `gpt-nonexistent` does not exist"))),
        )
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L))
        val result = router.generate(
            ModelRole.FAST,
            request().copy(maxTokens = 50_000),
            decoder,
        )
        assertTrue(result.exceptionOrNull() is ModelError.InvalidRequest)
        assertEquals(listOf(50_000), provider.requestedMaxTokens)
    }

    /**
     * 中文服务商的「输出上限」类 400 也要能认出并降档。
     *
     * 早先关键词表只有英文和「上下文/最大长度/长度超」，国内服务商常见的
     * 「超出最大输出上限」这类文案匹配不上，真·长度错误反而不降档。
     */
    @Test
    fun chineseTokenLimitMessage_downgrades() = runBlocking {
        val provider = RecordingProvider(
            listOf(
                Result.failure(ModelError.InvalidRequest("请求失败：超出模型最大输出上限（HTTP 400）")),
                Result.success("{\"ok\":true}"),
            ),
        )
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L))
        val result = router.generate(
            ModelRole.FAST,
            request().copy(maxTokens = 8_000),
            decoder,
        )
        assertTrue("中文长度类 400 应降档重发，实际：${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(listOf(8_000, 4_000), provider.requestedMaxTokens)
    }

    /**
     * 截断（Truncated）不降档、不重试，直接如实失败。
     *
     * 降 max_tokens 对截断只会让输出更短、更截，是帮倒忙；正确自救在调用方
     * （骨架降级重试会「少要点内容」）。路由层这里必须一次都不重发。
     */
    @Test
    fun truncated_doesNotDowngradeOrRetry() = runBlocking {
        val provider = RecordingProvider(
            listOf(Result.failure(ModelError.Truncated("输出被截断（finish_reason=length）"))),
        )
        val router = DefaultModelRouter(provider, retryDelaysMillis = listOf(1L, 1L))
        val result = router.generate(
            ModelRole.FAST,
            request().copy(maxTokens = 8_000),
            decoder,
        )
        assertTrue(result.exceptionOrNull() is ModelError.Truncated)
        assertEquals(listOf(8_000), provider.requestedMaxTokens)
    }

    /** 记录每次请求实际发出的 maxTokens */
    private class RecordingProvider(
        private val results: List<Result<String>>,
    ) : ModelProvider {
        var calls = 0
        val requestedMaxTokens = mutableListOf<Int?>()

        override suspend fun listModels(): Result<List<String>> = Result.success(emptyList())

        override suspend fun complete(
            request: ModelRequest,
            onUsage: (UsageInfo) -> Unit,
            onRawResponse: (String) -> Unit,
        ): Result<String> {
            requestedMaxTokens += request.maxTokens
            val index = calls.coerceAtMost(results.size - 1)
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
