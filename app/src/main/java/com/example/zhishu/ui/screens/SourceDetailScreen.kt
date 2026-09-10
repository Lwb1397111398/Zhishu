package com.example.zhishu.ui.screens

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.ui.common.formatDay
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.CitingBlock
import com.example.zhishu.ui.viewmodel.SourceDetailViewModel
import kotlinx.coroutines.delay

/** 列表里片段前面的固定项数（资料信息头），片段索引 = 列表索引 - 这个数 */
private const val SOURCE_HEADER_ITEMS = 1

/**
 * 资料详情（4C）。
 *
 * 两个方向在这一页交汇：
 * - **正文 → 资料**：从阅读器点「在原资料中查看」进来，自动滚到那一段引用的片段并高亮；
 * - **资料 → 正文**：每个片段下面列出引用它的**当前**正文块，点回阅读器。
 *
 * 反向那一条只列当前版本的正文块。历史版本的引用留在库里，但列进来就会
 * 把读者送到一段他看不到的文字上——那比不给这个入口更让人困惑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDetailScreen(
    sourceId: String,
    targetChunkId: String? = null,
    onBack: () -> Unit,
    onOpenReader: (String, String?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SourceDetailViewModel = rememberZhishuViewModel(key = "source_$sourceId") {
        SourceDetailViewModel(app, sourceId)
    }
    val source by vm.source.collectAsStateWithLifecycle()
    val chunks by vm.chunks.collectAsStateWithLifecycle()
    val citingBlocks by vm.citingBlocks.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var highlightChunkId by remember { mutableStateOf(targetChunkId) }
    var scrolled by remember(sourceId) { mutableStateOf(false) }

    // 滚到目标片段。片段一装载出来就滚，不等用户自己找
    LaunchedEffect(chunks, targetChunkId) {
        if (scrolled || chunks.isEmpty()) return@LaunchedEffect
        val target = targetChunkId ?: return@LaunchedEffect
        val index = chunks.indexOfFirst { it.id == target }
        if (index < 0) {
            scrolled = true
            return@LaunchedEffect
        }
        listState.scrollToItem(index + SOURCE_HEADER_ITEMS)
        scrolled = true
    }

    // 高亮只闪一下，之后这一页就是普通的资料原文
    LaunchedEffect(highlightChunkId) {
        if (highlightChunkId != null) {
            delay(2600)
            highlightChunkId = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            source?.title ?: "资料",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${chunks.size} 个片段",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "__source_header__") {
                SourceHeader(source = source, missingTarget = targetChunkId != null && scrolled &&
                    chunks.none { it.id == targetChunkId })
            }
            items(chunks, key = { it.id }) { chunk ->
                ChunkCard(
                    chunk = chunk,
                    highlighted = chunk.id == highlightChunkId,
                    citingBlocks = citingBlocks[chunk.id].orEmpty(),
                    onOpenReader = onOpenReader,
                )
            }
            item(key = "__source_footer__") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SourceHeader(source: SourceDocument?, missingTarget: Boolean) {
    if (source == null) {
        Text(
            "这份资料已经不在库里了。引用过它的正文段落仍然保留，但会标为「来源已删除」。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column {
        val origin = when {
            !source.fileName.isNullOrBlank() -> "你的文件：${source.fileName}"
            !source.url.isNullOrBlank() -> "你抓取的网页：${source.url}"
            else -> "你粘贴的文本"
        }
        Text(origin, style = MaterialTheme.typography.bodySmall)
        Text(
            "取得于 ${formatDay(source.fetchedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 版本、法域、生效时间：法律这类内容离开这三样就没有对错可言（4A/4C）
        val meta = listOfNotNull(
            source.versionLabelForDisplay,
            source.jurisdiction?.takeIf { it.isNotBlank() },
            source.effectiveFrom?.takeIf { it.isNotBlank() }?.let { "$it 起施行" },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        // 从正文点进来但那个片段已经不在了（资料重新导入过，片段被重新切了一遍）。
        // 直接落在资料开头而什么都不说，会让人以为跳转坏了
        if (missingTarget) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "正文引用的那个片段已经不在这份资料里了（资料可能重新导入过，" +
                        "片段被重新切分）。下面是这份资料现在的全部内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun ChunkCard(
    chunk: SourceChunk,
    highlighted: Boolean,
    citingBlocks: List<CitingBlock>,
    onOpenReader: (String, String?) -> Unit,
) {
    val highlightColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "chunkHighlight",
    )
    var showCiting by remember(chunk.id) { mutableStateOf(false) }

    Surface(
        color = highlightColor,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // locator 可能是长章节路径：省略收尾，右侧引用数固宽永不被挤掉
                Text(
                    chunk.locatorLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (citingBlocks.isNotEmpty()) {
                    Text(
                        "被 ${citingBlocks.size} 处正文引用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 旧资料只有段落序号：说清「只能定位到片段」，不编页码（4C）
            if (chunk.pageNumber == null &&
                chunk.sectionPath.isNullOrBlank() &&
                chunk.locator.isNullOrBlank()
            ) {
                Text(
                    "只能定位到片段（这份资料导入时没有记录页码与章节）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(chunk.text, style = MaterialTheme.typography.bodyMedium)

            if (citingBlocks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = { showCiting = !showCiting },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (showCiting) "收起引用它的正文" else "看哪些正文引用了它",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (showCiting) {
                    citingBlocks.forEach { citing ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(
                                    "第 ${citing.chapterOrder} 章 · ${citing.chapterTitle}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(citing.excerpt, style = MaterialTheme.typography.bodySmall)
                                TextButton(
                                    onClick = { onOpenReader(citing.chapterId, citing.blockId) },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                ) {
                                    Text("去正文这一段", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
