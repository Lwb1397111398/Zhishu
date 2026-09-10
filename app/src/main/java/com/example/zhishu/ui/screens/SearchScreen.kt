package com.example.zhishu.ui.screens

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.zhishu.search.SearchHit
import com.example.zhishu.search.SearchHitKind
import com.example.zhishu.ui.common.StatusChip
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialProjectId: String?,
    onBack: () -> Unit,
    onOpenReader: (String, String?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    // key 固定为 "search"：原来带 initialProjectId，从「知识」页进入是 "search_"、
    // 从项目页进入带 ID，两个实例并存、各自缓存上一次的 query/results，
    // 来回切换会看到不相关的旧结果。固定 key 后复用同一个 VM，入口项目靠
    // applyInitialProject 同步。
    val vm: SearchViewModel = rememberZhishuViewModel(key = "search") {
        SearchViewModel(app, initialProjectId)
    }
    LaunchedEffect(initialProjectId) {
        vm.applyInitialProject(initialProjectId)
    }
    val projects by vm.projects.collectAsStateWithLifecycle()
    val selectedProjectId by vm.selectedProjectId.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()

    val projectTitle = projects.firstOrNull { it.id == selectedProjectId }?.title ?: "选择项目"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("知识搜索") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (projects.size > 1) {
                ProjectDropdown(
                    label = projectTitle,
                    projects = projects,
                    selectedId = selectedProjectId,
                    onSelect = {
                        vm.selectedProjectId.value = it
                        vm.search()
                    },
                )
                Spacer(Modifier.height(8.dp))
            } else if (projects.size == 1) {
                Text(
                    "在《${projectTitle}》中搜索",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                label = { Text("搜索知识点、章节或资料") },
                trailingIcon = {
                    IconButton(onClick = { vm.search() }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))

            when {
                searching -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
                results.isEmpty() -> Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.width(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (query.isBlank()) "输入关键词开始搜索" else "没有找到相关内容",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    Text(
                        "找到 ${results.size} 条结果",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn {
                        items(results, key = { "${it.kind}-${it.id}" }) { hit ->
                            SearchResultCard(
                                hit = hit,
                                projectTitle = projectTitle,
                                // 带上 blockId：直接落到命中的那一段正文，不是章节开头
                                onClick = {
                                    hit.chapterId?.let { chapterId ->
                                        onOpenReader(chapterId, hit.blockId)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDropdown(
    label: String,
    projects: List<com.example.zhishu.domain.model.Project>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("搜索范围") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text(project.title) },
                    onClick = {
                        onSelect(project.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    hit: SearchHit,
    projectTitle: String,
    onClick: () -> Unit,
) {
    val (icon, kindLabel, tint) = when (hit.kind) {
        SearchHitKind.NODE_EXACT -> Triple(Icons.Default.School, "知识点（精确）", MaterialTheme.colorScheme.primary)
        SearchHitKind.NODE_FUZZY -> Triple(Icons.Default.School, "知识点", MaterialTheme.colorScheme.primary)
        SearchHitKind.CHAPTER_BLOCK -> Triple(Icons.Default.MenuBook, "章节", MaterialTheme.colorScheme.tertiary)
        SearchHitKind.SOURCE_CHUNK -> Triple(Icons.Default.Book, "资料", MaterialTheme.colorScheme.outline)
    }
    Card(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = hit.chapterId != null, onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.width(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hit.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(kindLabel, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    hit.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    projectTitle + when {
                        // 资料片段没有对应的正文位置，点不动是设计如此——但得说清楚，
                        // 否则用户点了没反应会以为卡住（D2）
                        hit.chapterId == null -> " · 资料原文（在资料页查看，不能跳到正文）"
                        hit.blockId != null -> " · 点击跳到正文这一段"
                        else -> " · 点击跳转阅读"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
            }
        }
    }
}
