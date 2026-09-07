package com.example.zhishu

import androidx.test.core.app.ApplicationProvider
import com.example.zhishu.ai.GenerationLog
import com.example.zhishu.ai.UsageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 生成诊断日志（用户反馈：「讲述路线非常卡，完全没有办法生成，不知道什么情况」）。
 *
 * 两类断言：
 * 1. 卡住时该有的线索真的记下来了（阶段、输入长度、请求的 max_tokens、耗时、错误类别）；
 * 2. **隐私边界不许破**：不写 API Key、不写 prompt/资料原文、不写完整响应。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GenerationLogTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() = GenerationLog.clear(context)

    @Test
    fun record_writesActionableFields() {
        GenerationLog.record(
            context = context,
            stage = "STORY#1",
            success = false,
            inputChars = 12_345,
            estimatedInputTokens = 9_000,
            requestedMaxTokens = 50_000,
            usage = UsageInfo(promptTokens = 8_900, completionTokens = 0, totalTokens = 8_900),
            elapsedMillis = 187_300,
            errorCategory = "Network",
            errorSummary = "连接超时",
        )
        val log = GenerationLog.read(context)!!
        assertTrue(log.contains("STORY#1"))
        assertTrue("必须能看出成败", log.contains("ok=false"))
        assertTrue("必须能看出输入多长", log.contains("inChars=12345"))
        assertTrue("必须能看出请求了多大的输出上限", log.contains("maxOut=50000"))
        assertTrue("必须能看出耗时", log.contains("187300ms"))
        assertTrue("必须能看出错误类别", log.contains("Network"))
    }

    @Test
    fun record_includesSanitizedErrorSummary() {
        GenerationLog.record(
            context = context,
            stage = "STORY_BIBLE#FINAL",
            success = false,
            inputChars = 120,
            estimatedInputTokens = 50,
            requestedMaxTokens = 8_000,
            usage = UsageInfo.NONE,
            elapsedMillis = 25,
            errorCategory = "Decode",
            errorSummary = "JSON 失败 sk-abcd1234EFGH5678ijkl\n" + "错".repeat(300),
        )

        val log = GenerationLog.read(context)!!
        assertTrue(log.contains("STORY_BIBLE#FINAL"))
        assertTrue(log.contains("Decode(JSON 失败 [已隐去]"))
        assertFalse(log.contains("sk-abcd1234EFGH5678ijkl"))
        assertFalse(log.contains("\n错"))
    }

    @Test
    fun safeDiagnosticSummary_neverCopiesProviderBody() {
        val secret = "用户资料原文：这段不能进入日志"
        val summary = com.example.zhishu.ai.safeDiagnosticSummary(
            com.example.zhishu.ai.ModelError.InvalidRequest(
                "max_tokens is too large; echoed=$secret",
            ),
        )

        assertTrue(summary!!.contains("token"))
        assertFalse(summary.contains(secret))
    }

    /**
     * C2 统一口径：日志的 token 超限判定与降档通道共用同一张关键词表。
     * 原来日志用宽松口径（含「长度」泛词）、路由用严格关键词表——同一条错误
     * 日志说是 token 问题、降档却不认。现在国内服务商的「输出上限」类文案
     * 两边都认。
     */
    @Test
    fun safeDiagnosticSummary_tokenLimitMatchesRouterKeywords() {
        val chinese = com.example.zhishu.ai.safeDiagnosticSummary(
            "InvalidRequest", "超出最大输出上限：请调小 max_tokens",
        )
        assertTrue("中文长度类 400 应判为 token 问题", chinese!!.contains("token"))
        // 与路由降档同一口径：looksLikeTokenLimit 认的，日志也认
        assertTrue(com.example.zhishu.ai.looksLikeTokenLimit("超出最大输出上限"))

        val unrelated = com.example.zhishu.ai.safeDiagnosticSummary(
            "InvalidRequest", "model not found: gpt-99",
        )
        assertEquals("请求参数不兼容", unrelated)
    }

    /** Truncated 是独立类别：日志里要如实说「被输出上限截断」，不再混进 InvalidRequest */
    @Test
    fun safeDiagnosticSummary_truncatedHasOwnLine() {
        val summary = com.example.zhishu.ai.safeDiagnosticSummary(
            com.example.zhishu.ai.ModelError.Truncated("finish_reason=length"),
        )
        assertTrue(summary!!.contains("截断"))
    }

    /** 关键：错误原文里回显的 API Key 必须被抹掉 */
    @Test
    fun sanitize_removesApiKeyLikeStrings() {
        val cleaned = GenerationLog.sanitize("Invalid key: sk-abcd1234EFGH5678ijkl provided")
        assertFalse("API Key 绝不能进日志", cleaned.contains("sk-abcd1234EFGH5678ijkl"))
        assertTrue(cleaned.contains("[已隐去]"))
    }

    /** 单行记录：错误原文里的换行要压平，否则一条记录被拆成多行、trim 逻辑也会错 */
    @Test
    fun sanitize_flattensNewlinesAndTruncates() {
        val cleaned = GenerationLog.sanitize("第一行\n第二行\r第三行")
        assertFalse(cleaned.contains("\n"))
        assertFalse(cleaned.contains("\r"))
        assertTrue(GenerationLog.sanitize("啊".repeat(500)).length <= GenerationLog.ERROR_SUMMARY_LIMIT)
    }

    /** 日志不会无限增长 */
    @Test
    fun log_keepsOnlyRecentLines() {
        repeat(GenerationLog.MAX_LINES + 50) { i ->
            GenerationLog.recordPhase(context, "MAP_CHAIN#$i", "validate", i.toLong())
        }
        val lines = GenerationLog.read(context)!!.trim().lines()
        assertEquals(GenerationLog.MAX_LINES, lines.size)
        // 保留的是最近的，不是最早的
        assertTrue(lines.last().contains("MAP_CHAIN#${GenerationLog.MAX_LINES + 49}"))
    }

    @Test
    fun read_withoutAnyLog_returnsNull() {
        assertNull(GenerationLog.read(context))
    }
}
