package com.example.zhishu.ai

import android.content.Context
import java.io.File

/**
 * 将模型错误压缩为不含服务商响应正文的诊断提示。
 * 错误原文可能回显用户资料，普通日志只记录类别级线索。
 */
internal fun safeDiagnosticSummary(error: Throwable?): String? = when (error) {
    is ModelError.Auth -> "认证或授权失败"
    is ModelError.RateLimited -> "请求被限流"
    is ModelError.Server -> "服务商暂时不可用"
    is ModelError.Network -> "网络连接失败"
    is ModelError.Truncated -> "模型回答被输出上限截断"
    is ModelError.InvalidRequest -> safeDiagnosticSummary("InvalidRequest", error.message)
    is ModelError.Decode -> "模型输出无法解析为 JSON"
    is ModelError.Invalid -> "模型输出未通过业务校验"
    is ModelError.BudgetExhausted -> "本次调用预算已用尽"
    else -> error?.let { "本地处理失败" }
}

internal fun safeDiagnosticSummary(category: String?, message: String?): String? = when (category) {
    null -> null
    "Auth" -> "认证或授权失败"
    "RateLimited" -> "请求被限流"
    "Server" -> "服务商暂时不可用"
    "Network" -> "网络连接失败"
    "Truncated" -> "模型回答被输出上限截断"
    "InvalidRequest" -> if (looksLikeTokenLimit(message)) {
        "token 或上下文长度不兼容"
    } else {
        "请求参数不兼容"
    }
    "Decode" -> "模型输出无法解析为 JSON"
    "Invalid" -> "模型输出未通过业务校验"
    "BudgetExhausted" -> "本次调用预算已用尽"
    else -> "本地处理失败"
}

/**
 * 生成调用的诊断日志（用户反馈：「讲述路线非常卡，完全没有办法生成，不知道什么情况」）。
 *
 * 卡住时用户能看到的只有一句「已开始」，然后什么都不发生。到底是 prompt 太长、
 * 网络在重试、JSON 被截断、还是校验没过——从界面上完全看不出来，只能猜。
 * 这里给每次模型调用落一条结构化的单行记录，出问题时能直接回答：
 *
 * ```
 * 1724500000000 STORY_BIBLE ok=false in=41200t out=50000 usage=41180/0 finish=- 187300ms Network(超时)
 * ```
 *
 * 隐私边界（严格遵守，个人本地工具也不例外）：
 * - **绝不**写 API Key；
 * - **绝不**写 prompt 原文或资料原文——只写字符数与估算 token；
 * - **绝不**写完整响应体——只写错误类别与一句截断后的错误摘要。
 *
 * 只保留最近 [MAX_LINES] 行，日志不会无限增长。
 */
object GenerationLog {

    private const val DIR_NAME = "diagnostics"
    private const val FILE_NAME = "generation.log"

    /** 最多保留多少行。够覆盖一次完整的地图 + 路线生成，出问题时现场还在 */
    const val MAX_LINES = 500

    /** 错误摘要最多留多长，避免整页 HTML 或长 JSON 灌进日志 */
    const val ERROR_SUMMARY_LIMIT = 200

    /**
     * 记一次调用。
     *
     * @param stage 业务阶段，例如 `MAP_SKELETON` / `MAP_CHAIN#3` / `STORY_BIBLE`
     * @param requestedMaxTokens 本次实际发出的 `max_tokens`（TokenBudget 算出来的那个值）
     */
    fun record(
        context: Context,
        stage: String,
        success: Boolean,
        inputChars: Int,
        estimatedInputTokens: Int,
        requestedMaxTokens: Int?,
        usage: UsageInfo,
        elapsedMillis: Long,
        errorCategory: String? = null,
        errorSummary: String? = null,
    ) {
        val line = buildString {
            append(System.currentTimeMillis())
            append(' ').append(stage)
            append(" ok=").append(success)
            append(" inChars=").append(inputChars)
            append(" inTok~").append(estimatedInputTokens)
            append(" maxOut=").append(requestedMaxTokens ?: "配置值")
            append(" usage=").append(usage.promptTokens).append('/').append(usage.completionTokens)
            append(" ").append(elapsedMillis).append("ms")
            if (errorCategory != null) {
                append(' ').append(errorCategory)
                errorSummary?.takeIf { it.isNotBlank() }?.let {
                    append('(').append(sanitize(it)).append(')')
                }
            }
        }
        append(context, line)
    }

    /** 阶段耗时（prompt 构建、校验、落库这些非网络环节），定位「卡在哪一步」 */
    fun recordPhase(context: Context, stage: String, phase: String, elapsedMillis: Long) {
        append(context, "${System.currentTimeMillis()} $stage phase=$phase ${elapsedMillis}ms")
    }

    /**
     * 阶段以异常收场的原因（Worker 兜底用）。
     *
     * 能走到这里的都是模型调用之外冒出来的问题——数据库、调度、系统限制。
     * 它们不会经过 [ConfiguredModelRouter]，也就没有 HTTP 记录可查，
     * 不落一条就等于现场消失：界面上只有一句没有原因的「生成失败」。
     *
     * 只记异常类别与消息，不记堆栈（堆栈可能回显资料内容）。
     */
    fun recordFailure(context: Context, stage: String, error: Throwable) {
        append(
            context,
            "${System.currentTimeMillis()} $stage ok=false ${error::class.simpleName}" +
                "(${sanitize(error.message.orEmpty()).ifBlank { "无错误信息" }})",
        )
    }

    /** 最近的日志全文；没有日志返回 null。供「导出诊断」与排查使用 */
    fun read(context: Context): String? = logFile(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
    }

    private fun append(context: Context, line: String) {
        // 诊断失败绝不能影响生成本身：整体 runCatching 吞掉
        runCatching {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
            trim(file)
        }
    }

    /**
     * 去掉可能夹带敏感内容的部分：换行压平（保持单行可读），
     * 长度截断，并抹掉看起来像 API Key 的长串（服务商错误原文里偶尔会回显请求头）。
     */
    internal fun sanitize(text: String): String =
        text.replace(Regex("""\b(sk|ak)-[A-Za-z0-9_\-]{8,}"""), "[已隐去]")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(ERROR_SUMMARY_LIMIT)

    private fun trim(file: File) {
        val lines = file.readLines()
        if (lines.size > MAX_LINES) {
            file.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    private fun logFile(context: Context): File =
        File(File(context.applicationContext.filesDir, DIR_NAME), FILE_NAME)
}
