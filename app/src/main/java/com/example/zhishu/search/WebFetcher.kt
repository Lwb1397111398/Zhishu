package com.example.zhishu.search

import org.jsoup.Jsoup
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
                val text = document.body()?.text()?.take(maxChars).orEmpty()
                FetchedPage(
                    title = document.title().ifBlank { normalized },
                    text = text,
                )
            } catch (e: IOException) {
                throw IllegalArgumentException("抓取失败: ${e.message}")
            }
        }
    }
}
