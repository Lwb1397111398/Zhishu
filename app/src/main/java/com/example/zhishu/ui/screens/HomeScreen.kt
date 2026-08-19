package com.example.zhishu.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.ui.common.ConfirmDialog
import com.example.zhishu.ui.common.EmptyState
import com.example.zhishu.ui.common.ProjectStatusChip
import com.example.zhishu.ui.common.SectionTitle
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.HomeViewModel
import com.example.zhishu.ui.viewmodel.ReviewViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenProject: (String) -> Unit,
    onCreateProject: () -> Unit,
    onOpenReader: (String, String?) -> Unit,
    onGoReview: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: HomeViewModel = rememberZhishuViewModel { HomeViewModel(app) }
    val projects by vm.projects.collectAsState()
    val dueCount by vm.dueCount.collectAsState()
    val continueEntry by vm.continueEntry.collectAsState()
    val reviewEnabled by vm.spacedReviewEnabled.collectAsState()
    var deleteTarget by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("知述", fontWeight = FontWeight.Bold) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateProject) {
                Icon(Icons.Default.Add, contentDescription = "新建学习项目")
            }
        },
    ) { padding ->
        if (projects.isEmpty() && continueEntry == null) {
            Box(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.Default.Book,
                    title = "把想学的东西，读成一个故事",
                    subtitle = "告诉它你想学什么——比如民法、宏观经济学——它会把这门学科讲成一条能一路读下去的故事。不用准备资料。",
                    actionLabel = "开始学一门新东西",
                    onAction = onCreateProject,
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OutlinedButton(onClick = { vm.seedDemo() }) {
                        Text("先看个示例（民法继承案）")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                continueEntry?.let { entry ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "继续阅读",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    entry.project.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    "上次读到：${entry.chapterTitle}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.height(10.dp))
                                // 不传 blockId：阅读器自己按保存的块 + 块内偏移恢复，位置更准
                                TextButton(onClick = { onOpenReader(entry.progress.chapterId, null) }) {
                                    Text("继续阅读 →", color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
                // 「今日复习」只在用户主动开启间隔复习后出现，
                // 默认不给阅读加一层待办感（复习页仍可从「我的」进入）
                if (reviewEnabled) {
                    item {
                        Card(
                            onClick = onGoReview,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        "今日复习",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Text(
                                        if (dueCount > 0) "有 $dueCount 个知识点待复习" else "今天没有到期的复习",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                }
                item { SectionTitle("学习项目") }
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onDelete = { deleteTarget = project },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "删除项目",
            message = "确定删除「${target.title}」吗？该项目的资料、知识地图、章节与复习记录都会一并删除。",
            confirmLabel = "删除",
            onConfirm = { vm.deleteProject(target.id) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        project.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        project.topic,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ProjectStatusChip(project.status)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${project.effectiveGenre} · 每天 ${project.dailyMinutes} 分钟 · 更新于 ${formatTime(project.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = onOpen) { Text("打开项目") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeHubScreen(
    onOpenProject: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: HomeViewModel = rememberZhishuViewModel(key = "knowledge_hub") { HomeViewModel(app) }
    val projects by vm.projects.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("知识主干") },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, contentDescription = "搜索知识点")
                    }
                },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.Default.School,
                    title = "暂无知识地图",
                    subtitle = "先创建学习项目并生成知识地图，所有知识点会以树形结构呈现在这里。",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                item {
                    Text(
                        "选择一个项目查看它的知识地图：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(projects, key = { it.id }) { project ->
                    Card(
                        onClick = { onOpenProject(project.id) },
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.School,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    project.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    project.topic,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            ProjectStatusChip(project.status)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit = {},
    onOpenChapter: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ReviewViewModel = rememberZhishuViewModel { ReviewViewModel(app) }
    val items by vm.items.collectAsState()
    val projects by vm.projects.collectAsState()
    val nodes by vm.nodes.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("复习中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.refresh() }) { Text("刷新") }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.Default.Star,
                    title = "暂无到期复习",
                    subtitle = "间隔复习是可选功能。在「我的」页打开后，读过的知识点会按 1/3/7 天的节奏排进这里。",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                item {
                    Text(
                        "有 ${items.size} 个知识点等待复习",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(items, key = { it.id }) { item ->
                    ReviewCard(
                        item = item,
                        projectTitle = projects.firstOrNull { it.id == item.projectId }?.title ?: "",
                        nodeTitle = nodes[item.nodeId]?.title ?: "未知知识点",
                        chapterId = nodes[item.nodeId]?.chapterIds?.firstOrNull(),
                        onComplete = { vm.complete(item) },
                        onArchive = { vm.archive(item) },
                        onOpenChapter = onOpenChapter,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(
    item: ReviewItem,
    projectTitle: String,
    nodeTitle: String,
    chapterId: String?,
    onComplete: () -> Unit,
    onArchive: () -> Unit,
    onOpenChapter: (String) -> Unit,
) {
    Card(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    nodeTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "$projectTitle · 间隔 ${item.intervalDays} 天",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onComplete) { Text("完成复习") }
                TextButton(onClick = onArchive) { Text("已完全掌握") }
                if (chapterId != null) {
                    TextButton(onClick = { onOpenChapter(chapterId) }) { Text("去阅读相关章节") }
                }
            }
        }
    }
}
