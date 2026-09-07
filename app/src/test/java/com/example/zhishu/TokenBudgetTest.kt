package com.example.zhishu

import com.example.zhishu.ai.TokenBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输出上限预算的不变量。
 *
 * 定位过一次真实故障后口径改了：曾经各阶段一律给 5 万（「max_tokens 只是上限，
 * 没写那么长就不收费」），结果整个知识主干慢了一个数量级——上下文窗口和
 * 「单次回复最多吐多少」是两个限制，主流服务商后者普遍只有 8192，给 5 万要么
 * 被 400 拒、要么被推理模型当成思考预算花掉几分钟。
 *
 * 所以不变量是「按阶段实际需要给足余量，且绝不越窗」：
 * - 输入正常 → 拿到阶段上限；
 * - 输入变长 → 自动让位给输入，不发出注定 400 的请求。
 */
class TokenBudgetTest {

    @Test
    fun `输入很短时拿到阶段上限`() {
        val limit = TokenBudget.outputLimit(
            system = "你在展开一条演进链",
            user = "很短的提示词",
            stageCap = TokenBudget.Stage.CHAIN,
        )
        assertEquals(
            "输入只有几十 token、窗口 20 万，应该直接给到阶段上限",
            TokenBudget.Stage.CHAIN,
            limit,
        )
    }

    /**
     * 阶段上限必须留在主流服务商的单次输出上限之内。
     *
     * 这是「知识主干特别慢、有调用记录但结果没用上」的根因防回归：CHAIN 一旦
     * 又被调到 5 万，低上限服务商就会逐次 400、推理模型则会把预算花在思考上。
     * 讲述路线确实需要更长，单独放行并由降档重发兜底。
     */
    @Test
    fun `逐链等高频阶段的上限不超过主流服务商单次输出上限`() {
        val cap = TokenBudget.COMMON_PROVIDER_OUTPUT_CAP
        assertTrue("单链展开 ${TokenBudget.Stage.CHAIN} 超过了 $cap", TokenBudget.Stage.CHAIN <= cap)
        assertTrue("骨架 ${TokenBudget.Stage.SKELETON} 超过了 $cap", TokenBudget.Stage.SKELETON <= cap)
        assertTrue("审计 ${TokenBudget.Stage.AUDIT} 超过了 $cap", TokenBudget.Stage.AUDIT <= cap)
        assertTrue("出题 ${TokenBudget.Stage.QUIZ} 超过了 $cap", TokenBudget.Stage.QUIZ <= cap)
        assertTrue("局部重写 ${TokenBudget.Stage.REWRITE} 超过了 $cap", TokenBudget.Stage.REWRITE <= cap)
    }

    /** 一轮 6 个节点按 prompt 里的字数上限折算约 3000 token，上限必须真的够用 */
    @Test
    fun `单链上限足够容纳一轮六个节点`() {
        assertTrue(
            "单链上限 ${TokenBudget.Stage.CHAIN} 不足以覆盖一轮 6 节点（约 3000 token）",
            TokenBudget.Stage.CHAIN >= 6_000,
        )
    }

    @Test
    fun `输入变长时输出上限自动下降`() {
        val small = TokenBudget.outputLimit(inputTokens = 1_000, stageCap = 50_000, contextWindow = 32_000)
        val large = TokenBudget.outputLimit(inputTokens = 20_000, stageCap = 50_000, contextWindow = 32_000)
        assertTrue("输入变大时输出上限必须下降，实际 $small → $large", large < small)
    }

    @Test
    fun `绝不发出输入加输出越过窗口的请求`() {
        val window = 32_000
        // 从很短到几乎占满窗口，逐档检查不变量
        for (inputTokens in listOf(0, 100, 1_000, 8_000, 16_000, 24_000)) {
            val limit = TokenBudget.outputLimit(
                inputTokens = inputTokens,
                stageCap = TokenBudget.Stage.CHAIN,
                contextWindow = window,
            )
            assertTrue(
                "输入 $inputTokens + 输出 $limit 越过了窗口 $window（这类请求会被 400 且不可重试）",
                inputTokens + limit + TokenBudget.PROTOCOL_RESERVE <= window,
            )
        }
    }

    @Test
    fun `输入把窗口占满时明确报越窗而不是悄悄发一个小请求`() {
        assertFalse(TokenBudget.exceedsWindow(inputTokens = 1_000, contextWindow = 32_000))
        assertTrue(TokenBudget.exceedsWindow(inputTokens = 31_000, contextWindow = 32_000))
    }

    /** 估算必须**估高**不估低：估低会让请求越窗，那是不可重试的硬失败 */
    @Test
    fun `中文按一字一 token 估算，不低估`() {
        // 20 个汉字：真实分词器约 12-20 token，估算不得低于 20
        assertTrue(TokenBudget.estimateTokens("民法总则意思表示法律行为效力瑕疵撤销权除斥期间") >= 20)
        // 纯 ASCII 按 3 字符/token（真实约 3.5-4），同样偏保守
        assertEquals(4, TokenBudget.estimateTokens("abcdefghijkl"))
        assertEquals(0, TokenBudget.estimateTokens(""))
    }

    @Test
    fun `安全余量随窗口放大但有下限`() {
        assertEquals(512, TokenBudget.safetyMargin(4_000))
        assertEquals(10_000, TokenBudget.safetyMargin(200_000))
    }
}
