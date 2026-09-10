package com.example.zhishu.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhishu.ui.common.EmptyState
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.TrustCenterViewModel
import com.example.zhishu.ui.viewmodel.TrustEntry

/**
 * 未核实内容筛选（4C）。
 *
 * 这一页回答的是「我该去核对什么」，所以只列**当前正文**里的东西：
 * 未确认的出处、已删除的资料、还没处理的核验问题。历史版本的同类记录都留在库里，
 * 但列进来就变成了「我曾经该核对什么」——其中大半段落读者已经看不到了。
 *
 * 定位不到具体段落的问题照样列出来，并明说只能整章重新生成。
 * 给它一个「去正文这一段」按钮才是不诚实的：点了不知道该跳到哪。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrustCenterScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenReader: (String, String?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: TrustCenterViewModel = rememberZhishuViewModel(key = "trust_$projectId") {
        TrustCenterViewModel(app, projectId)
    }
    val entries by vm.entries.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    val visible = if (filter == null) entries else entries.filter { it.kind == filter }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("需要核对的内容", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "只统计当前正文",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrustFilterChip(
                    label = "全部（${entries.size}）",
                    selected = filter == null,
                    onClick = { vm.filter.value = null },
                )
                TrustEntry.Kind.entries.forEach { kind ->
                    val count = entries.count { it.kind == kind }
                    if (count > 0) {
                        TrustFilterChip(
                            label = "${kind.label}（$count）",
                            selected = filter == kind,
                            onClick = { vm.filter.value = if (filter == kind) null else kind },
                        )
                    }
                }
            }

            if (!loading && entries.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Check,
                    title = "没有需要核对的内容",
                    subtitle = "当前正文的每条引用都对上了资料，也没有待处理的核验问题。",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                )
                return@Column
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // key 只能用 sourceId：kind/title 是展示文本（同章多条引用 title 相同），
                // blockId 在源块换版后为 null——拼出来的 key 会撞车，滚到那条就闪退
                items(visible, key = { "${it.kind}-${it.sourceId}" }) { entry ->
                    TrustEntryCard(entry = entry, onOpenReader = onOpenReader)
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun TrustFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun TrustEntryCard(
    entry: TrustEntry,
    onOpenReader: (String, String?) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.kind.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(8.dp))
                // 章节标题长短不可控：吃掉剩余宽度并省略，kind 标签（固宽）留在前面
                Text(
                    "第 ${entry.chapterOrder} 章 · ${entry.chapterTitle}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                entry.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.rawRef.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "模型给出的出处标识：${entry.rawRef}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.excerpt.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                    Text(
                        entry.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            if (entry.canLocate) {
                TextButton(
                    onClick = { onOpenReader(entry.chapterId, entry.blockId) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("去正文这一段", style = MaterialTheme.typography.labelMedium) }
            } else {
                Spacer(Modifier.height(4.dp))
                // 说清「为什么没有按钮」。4B 定的调子：不给点了没用的入口
                Text(
                    "这一条没能定位到具体段落，不能只改一段——只能整章重新生成，" +
                        "或者你自己判断它是否要紧。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                TextButton(
                    onClick = { onOpenReader(entry.chapterId, null) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("打开这一章", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
