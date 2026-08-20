package com.example.zhishu.search

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FetchedPage(
    val title: String,
    val text: String,
)

interface WebFetcher {
    suspend fun fetch(url: String): FetchedPage
}

/**
 * Jsoup 抓取。强制 HTTPS，避免资料与 Cookie 走明文（B13）；
 * 阻塞 IO 一律切到 [ioDispatcher]，防止在主线程抛 NetworkOnMainThreadException（N4）。
 */
class JsoupWebFetcher(
    private val timeoutMillis: Long = 15000L,
    private val maxChars: Int = 200_000,
    private val maxBodyBytes: Int = 8 * 1024 * 1024,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 仅测试用：允许 http。生产固定 true（B13） */
    private val requireHttps: Boolean = true,
) : WebFetcher {
    override suspend fun fetch(url: String): FetchedPage {
        val normalized = url.trim()
        val schemeOk = if (requireHttps) {
            normalized.startsWith("https://", ignoreCase = true)
        } else {
            normalized.startsWith("https://", ignoreCase = true) ||
                normalized.startsWith("http://", ignoreCase = true)
        }
        if (!schemeOk) {
            throw IllegalArgumentException("仅支持 https 地址，http 明文抓取已禁用")
        }
        return withContext(ioDispatcher) {
            try {
                val document = Jsoup.connect(normalized)
                    .timeout(timeoutMillis.toInt())
                    .maxBodySize(maxBodyBytes)
                    .userAgent("Mozilla/5.0 (Zhishu Learner)")
                    .get()
                val text = extractStructuredText(document).take(maxChars)
                FetchedPage(
                    title = document.title().ifBlank { normalized },
                    text = text,
                )
            } catch (e: IOException) {
                throw IllegalArgumentException("抓取失败: ${e.message}")
            }
        }
    }

    /**
     * 按块级元素抽取正文，标题保留成 Markdown 形式（4A）。
     *
     * 原来是 `body().text()`，整页被压成一行——分块只能按 2000 字硬切，
     * 引用回去只能说「出自这个网页」。保留 `## 小标题` 这种行之后，
     * 分块阶段就能认出章节层级，引用才能说到「这一节」。
     */
    private fun extractStructuredText(document: Document): String {
        val body = document.body() ?: return ""
        // 脚本、样式、导航栏不是资料内容
        body.select("script, style, noscript, nav, footer, header, aside").remove()
        val lines = body.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, td")
            .mapNotNull { element ->
                val text = element.text().trim()
                if (text.isEmpty()) {
                    null
                } else {
                    val level = HEADING_TAGS.indexOf(element.tagName().lowercase())
                    if (level >= 0) "${"#".repeat(level + 1)} $text" else text
                }
            }
            // 同一段文字可能被嵌套元素重复选中（例如 li 里又有 p）
            .distinct()
        // 一个块级元素都没匹配上（整页都是 div）时退回平铺文本，宁可少定位也不能丢内容
        return if (lines.isEmpty()) body.text() else lines.joinToString("\n")
    }

    private companion object {
        val HEADING_TAGS = listOf("h1", "h2", "h3", "h4", "h5", "h6")
    }
}
