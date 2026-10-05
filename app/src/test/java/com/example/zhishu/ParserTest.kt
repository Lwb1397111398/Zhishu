package com.example.zhishu

import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.search.Chunker
import com.example.zhishu.search.FetchedPage
import com.example.zhishu.search.HashUtils
import com.example.zhishu.search.JsoupWebFetcher
import com.example.zhishu.search.ParserServiceImpl
import com.example.zhishu.search.SourceInput
import com.example.zhishu.search.WebFetcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.nio.charset.Charset
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParserTest {

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

    @Test
    fun textParse_createsSourceAndChunks() = runBlocking {
        val parser = ParserServiceImpl(webFetcher = NoopFetcher())
        val text = buildString {
            repeat(50) { append("这是第${it}段知识内容，用于测试分块。\n") }
        }
        val result = parser.parse(projectId = "p-1", input = SourceInput.Text("笔记", text))

        assertEquals(ParseStatus.PARSED, result.source.parseStatus)
        assertEquals(SourceKind.TEXT, result.source.kind)
        assertEquals(HashUtils.sha256(text), result.source.contentHash)
        assertTrue(result.chunks.isNotEmpty())
        assertTrue(result.chunks.all { it.sourceId == result.source.id })
        // 片段必须带上 projectId（B1）
        assertTrue(result.chunks.all { it.projectId == "p-1" })
        assertEquals(result.chunks.size, result.chunks.map { it.ordinal }.distinct().size)
    }

    @Test
    fun duplicateContent_sameHash() {
        assertEquals(HashUtils.sha256("相同内容"), HashUtils.sha256("相同内容"))
        assertTrue(HashUtils.sha256("A") != HashUtils.sha256("B"))
    }

    @Test
    fun chunker_splitsLongText() {
        val chunks = Chunker.chunk("s-1", "第一段内容。\n\n" + "长".repeat(3000) + "\n\n结尾段。")
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.text.length <= 2000 })
        assertTrue(chunks.last().text.contains("结尾段"))
    }

    /** 分块内部用真实换行拼接段落，而非字面 "\n"（B11） */
    @Test
    fun chunker_joinsParagraphsWithRealNewline() {
        val chunks = Chunker.chunk("s-1", "第一段\n第二段")
        assertEquals(1, chunks.size)
        assertTrue(chunks.first().text.contains('\n'))
        assertFalse(chunks.first().text.contains("\\n"))
    }

    @Test
    fun invalidPdf_marksScanOnly() = runBlocking {
        val parser = ParserServiceImpl(webFetcher = NoopFetcher())
        val fakePdf = ByteArray(200) { it.toByte() }
        val result = parser.parse(
            projectId = "p-1",
            input = SourceInput.FileBytes("扫描件.pdf", "scan.pdf", fakePdf, SourceKind.PDF),
        )
        assertEquals(ParseStatus.SCAN_ONLY, result.source.parseStatus)
        assertTrue(result.chunks.isEmpty())
    }

    @Test
    fun urlParse_fetchesHtml() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><head><title>民法百科</title></head><body><p>意思表示是民事法律行为的核心要素。</p></body></html>"),
        )
        val parser = ParserServiceImpl(webFetcher = plainHttpFetcher())
        val result = parser.parse(
            projectId = "p-1",
            input = SourceInput.Url(server.url("/page").toString()),
        )
        assertEquals(ParseStatus.PARSED, result.source.parseStatus)
        assertEquals("民法百科", result.source.title)
        assertTrue(result.source.url!!.contains("/page"))
        assertTrue(result.chunks.any { it.text.contains("意思表示") })
    }

    /** 生产配置下必须拒绝 http 明文抓取（B13） */
    @Test
    fun urlParse_httpRejectedByDefault() = runBlocking {
        val parser = ParserServiceImpl(webFetcher = JsoupWebFetcher())
        val error = runCatching {
            parser.parse(projectId = "p-1", input = SourceInput.Url("http://insecure.example.com/x"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    /**
     * 解析必须把阻塞工作切到注入的 IO dispatcher，而不是留在调用线程上（N4）。
     * 用计数 dispatcher 断言：确实发生了切换，且执行线程是 IO 线程。
     */
    @Test
    fun parse_runsBlockingWorkOnIoDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-io") }
        val counting = CountingDispatcher(executor.asCoroutineDispatcher())
        val callerThread = Thread.currentThread().name
        try {
            val parser = ParserServiceImpl(webFetcher = NoopFetcher(), ioDispatcher = counting)
            val result = parser.parse(
                projectId = "p-1",
                input = SourceInput.FileBytes(
                    title = "笔记",
                    fileName = "note.txt",
                    bytes = "第一段\n第二段".toByteArray(),
                    kind = SourceKind.TEXT,
                ),
            )
            assertTrue(result.chunks.isNotEmpty())
            // 文件解析 + 分块，至少两次切换
            assertTrue("解析没有切到 IO dispatcher", counting.dispatches.get() >= 2)
            assertEquals(setOf("test-io"), counting.threadNames.toSet())
            assertFalse(counting.threadNames.contains(callerThread))
        } finally {
            executor.shutdown()
        }
    }

    /**
     * Markdown 资料解析出来的片段带上章节路径（4A）。
     *
     * 没有它，引用只能说「出自这份讲义」——一份两百页的讲义，
     * 用户拿着这句话没法核对任何东西。
     */
    @Test
    fun markdownParse_recordsSectionPath() = runBlocking {
        val parser = ParserServiceImpl(webFetcher = NoopFetcher())
        val text = "# 第三章 合同的订立\n## 第二节 要约\n要约是希望与他人订立合同的意思表示。"
        val result = parser.parse(
            projectId = "p-1",
            input = SourceInput.FileBytes("讲义", "note.md", text.toByteArray(), SourceKind.MARKDOWN),
        )
        assertEquals(
            "第三章 合同的订立 > 第二节 要约",
            result.chunks.single().sectionPath,
        )
        // 非 PDF 资料没有页码，不假装有
        assertEquals(null, result.chunks.single().pageNumber)
    }

    /**
     * GBK/GB18030 编码的文本文件不再乱码（网上下载的中文资料很常见）。
     *
     * 原来 `String(bytes, UTF_8)` 的默认替换解码会把整篇变成 U+FFFD 乱码，
     * 导入「成功」了但正文全是问号。现在 UTF-8 严格解码失败时回退 GB18030，
     * 正文原样可读。
     */
    @Test
    fun txtFile_gb18030Encoding_fallsBackToReadableText() = runBlocking {
        val parser = ParserServiceImpl(webFetcher = NoopFetcher())
        val text = "《民法典》继承编：遗嘱的效力要看立遗嘱的人当时真正的想法。".repeat(20)
        val gbBytes = text.toByteArray(Charset.forName("GB18030"))
        val result = parser.parse(
            projectId = "p-1",
            input = SourceInput.FileBytes("讲义", "讲义.txt", gbBytes, SourceKind.TEXT),
        )
        assertEquals(ParseStatus.PARSED, result.source.parseStatus)
        assertTrue(result.chunks.first().text.contains("遗嘱的效力"))
        // 替换符一个都不能有——有就说明走了替换解码而不是兜底
        assertFalse(result.chunks.any { it.text.contains('\uFFFD') })
    }

    /** UTF-8 文件不受影响：严格解码路径原样通过，一个字不改 */
    @Test
    fun txtFile_utf8_stillDecodesAsBefore() = runBlocking {
        val parser = ParserServiceImpl(webFetcher = NoopFetcher())
        val text = "要约是希望与他人订立合同的意思表示。\n".repeat(30)
        val result = parser.parse(
            projectId = "p-1",
            input = SourceInput.FileBytes("讲义", "讲义.txt", text.toByteArray(Charsets.UTF_8), SourceKind.TEXT),
        )
        assertEquals(ParseStatus.PARSED, result.source.parseStatus)
        assertTrue(result.chunks.first().text.contains("要约是希望与他人订立合同"))
    }

    /**
     * 网页抓取保留标题结构（4A）。
     *
     * 原来是 `body().text()`，整页被压成一行，分块只能按字数硬切，
     * 引用回去只能说「出自这个网页」。保留 `## 小标题` 之后，
     * 分块才认得出层级，来源才能说到「这一节」。
     */
    @Test
    fun urlParse_keepsHeadingStructure() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    "<html><head><title>民法百科</title></head><body>" +
                        "<nav>导航栏不是资料</nav>" +
                        "<h2>要约</h2><p>要约是希望与他人订立合同的意思表示。</p>" +
                        "</body></html>",
                ),
        )
        val parser = ParserServiceImpl(webFetcher = plainHttpFetcher())
        val result = parser.parse(
            projectId = "p-1",
            input = SourceInput.Url(server.url("/page").toString()),
        )
        assertEquals("要约", result.chunks.single().sectionPath)
        assertTrue(result.chunks.single().text.contains("意思表示"))
        // 导航栏被剔掉，不会被当成资料内容引用
        assertFalse(result.chunks.single().text.contains("导航栏"))
    }

    private fun plainHttpFetcher(): WebFetcher = JsoupWebFetcher(requireHttps = false)

    private class NoopFetcher : WebFetcher {
        override suspend fun fetch(url: String): FetchedPage = throw UnsupportedOperationException()
    }

    private class CountingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        val dispatches = AtomicInteger(0)
        val threadNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context) {
                threadNames.add(Thread.currentThread().name)
                block.run()
            }
        }
    }
}
