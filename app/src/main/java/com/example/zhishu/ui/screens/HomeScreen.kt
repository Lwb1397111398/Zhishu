package com.example.zhishu.ui.screens

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.ui.common.ConfirmDialog
import com.example.zhishu.ui.common.EmptyState
import com.example.zhishu.ui.common.SectionTitle
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.HomeViewModel
import com.example.zhishu.ui.viewmodel.HomeViewModel.BookStat
import com.example.zhishu.ui.viewmodel.HomeViewModel.ContinueEntry
import com.example.zhishu.ui.viewmodel.ReviewViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

/** 封面状态行的小字映射（书架上不再放大号 chip，信息不丢只是降层） */
private fun projectStatusLine(status: ProjectStatus): String = when (status) {
    ProjectStatus.DRAFT -> "刚开的书 · 还没开始"
    ProjectStatus.SOURCES -> "资料已就绪"
    ProjectStatus.MAP -> "知识地图已生成"
    ProjectStatus.OUTLINE -> "讲述路线已就绪"
    ProjectStatus.GENERATING -> "正在生成…"
    ProjectStatus.READY -> "可阅读"
}

/** 空书架上的一本简化小书：渐变封面 + 右侧圆角书脊，tilted 时斜靠 */
@Composable
private fun SimpleBook(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, top: Color, bottom: Color, tilted: Boolean = false) {
    Box(
        Modifier
            .width(width)
            .height(height)
            .rotate(if (tilted) 10f else 0f)
            .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp, topStart = 1.dp, bottomStart = 1.dp))
            .background(Brush.verticalGradient(listOf(top, bottom))),
    )
}

/** 程序化封面配色：书名哈希稳定选一组，同一本书重开不变样 */
private data class CoverPalette(val top: Color, val bottom: Color)

private val COVER_PALETTES = listOf(
    CoverPalette(Color(0xFFD84315), Color(0xFF7E2A0B)), // 朱橙
    CoverPalette(Color(0xFF44617B), Color(0xFF24384A)), // 墨蓝
    CoverPalette(Color(0xFF5B6E4E), Color(0xFF33402A)), // 苔绿
    CoverPalette(Color(0xFF6B4E71), Color(0xFF3D2A42)), // 紫藤
    CoverPalette(Color(0xFF8A6D3B), Color(0xFF54401F)), // 赭金
    CoverPalette(Color(0xFF4E6E6A), Color(0xFF29403D)), // 黛青
)

private fun coverPalette(projectId: String): CoverPalette =
    COVER_PALETTES[abs(projectId.hashCode()) % COVER_PALETTES.size]

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
    val bookStats by vm.bookStats.collectAsState()
    val weekReadCount by vm.weekReadCount.collectAsState()
    var deleteTarget by remember { mutableStateOf<Project?>(null) }

    // 每次回到书架都刷新阅读统计：点书直读靠它找「上次读到的章」，
    // 不刷新的话读完一章返回再点同一本书，会被带回上一章而不是刚读到的位置
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            // 空书架：一本本立着的书 + 引导文案，替代干巴巴的图标空状态
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    SimpleBook(30.dp, 46.dp, Color(0xFF44617B), Color(0xFF24384A))
                    Spacer(Modifier.width(6.dp))
                    SimpleBook(34.dp, 54.dp, Color(0xFFD84315), Color(0xFF7E2A0B))
                    Spacer(Modifier.width(6.dp))
                    SimpleBook(30.dp, 48.dp, Color(0xFF8A6D3B), Color(0xFF54401F), tilted = true)
                }
                Spacer(Modifier.height(28.dp))
                Text(
                    "书架还是空的",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "告诉知述你想学什么——比如民法、宏观经济学——" +
                        "它会把这门学科讲成一本能一路读下去的书。不用准备资料。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onCreateProject) {
                    Text("开始学一门新东西")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.seedDemo() }) {
                    Text("先看个示例（民法继承案）")
                }
            }
        } else {
            // 书架：继续阅读/复习横幅各占整行，书卡自适应列宽（手机 2 列、平板 3-4 列），
            // 点封面直读、长按管理
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                continueEntry?.let { entry ->
                    item(key = "__continue__", span = { GridItemSpan(2) }) {
                        ContinueReadingCard(
                            entry = entry,
                            stat = bookStats[entry.project.id],
                            onOpenReader = onOpenReader,
                        )
                    }
                }
                // 「今日复习」只在用户主动开启间隔复习后出现，
                // 默认不给阅读加一层待办感（复习页仍可从「我的」进入）
                if (reviewEnabled) {
                    item(key = "__review__", span = { GridItemSpan(2) }) {
                        Card(
                            onClick = onGoReview,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
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
                item(key = "__shelf_title__", span = { GridItemSpan(2) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("我的书架", modifier = Modifier.weight(1f))
                        if (weekReadCount > 0) {
                            Text(
                                "本周读过 $weekReadCount 本",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(projects, key = { it.id }) { project ->
                    BookCard(
                        project = project,
                        stat = bookStats[project.id],
                        onRead = { chapterId ->
                            // 不传 blockId：阅读器自己按保存的块恢复，位置更准
                            onOpenReader(chapterId, null)
                        },
                        onOpenProject = { onOpenProject(project.id) },
                        onDelete = { deleteTarget = project },
                    )
                }
                item(key = "__bottom__", span = { GridItemSpan(2) }) {
                    Spacer(Modifier.height(80.dp))
                }
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

/** 顶部「继续阅读」大卡：打开 APP 后两击进正文的最短路径 */
@Composable
private fun ContinueReadingCard(
    entry: ContinueEntry,
    stat: BookStat?,
    onOpenReader: (String, String?) -> Unit,
) {
    Card(
        onClick = { onOpenReader(entry.progress.chapterId, null) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 小书封：与书架封面同一套配色，一眼认出是哪本书
                val palette = coverPalette(entry.project.id)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Brush.verticalGradient(listOf(palette.top, palette.bottom))),
                ) {
                    Text(
                        entry.project.title.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "继续阅读",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        entry.project.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "上次读到：第 ${entry.chapterOrder} 章 · ${entry.chapterTitle}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stat != null && stat.totalChapters > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (entry.chapterOrder.toFloat() / stat.totalChapters).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "全书 ${stat.totalChapters} 章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * 书架上的书卡：程序化封面（渐变底 + 书名 + 讲法）。
 *
 * 点封面**直接进阅读**（有可读正文时，接着上次读到的那页；这正是老板说的
 * 「点进去就仿佛小说软件一样」）；没有可读内容才进项目页。
 * 长按弹出管理菜单：直接阅读 / 项目详情 / 删除——原来的常驻按钮收进来，
 * 减少视觉噪音但不减少功能。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    project: Project,
    stat: BookStat?,
    onRead: (String) -> Unit,
    onOpenProject: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val palette = coverPalette(project.id)
    val directChapterId = stat?.directReadChapterId

    Column {
        Box {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .shadow(3.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brush.verticalGradient(listOf(palette.top, palette.bottom)))
                    .combinedClickable(
                        onClick = {
                            if (directChapterId != null) onRead(directChapterId) else onOpenProject()
                        },
                        onLongClick = { menuExpanded = true },
                    ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "知述",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        project.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (project.topic.isNotBlank() && project.topic != project.title) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            project.topic,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        project.effectiveGenre,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                if (directChapterId != null) {
                    DropdownMenuItem(
                        text = { Text("直接阅读") },
                        onClick = {
                            menuExpanded = false
                            onRead(directChapterId)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("项目详情") },
                    onClick = {
                        menuExpanded = false
                        onOpenProject()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // 进度/状态行：有章节讲进度，没章节讲状态；更新时间收敛在这行小字里
        Text(
            buildString {
                if (stat != null && stat.totalChapters > 0) {
                    append("已写 ${stat.publishedChapters}/${stat.totalChapters} 章")
                } else {
                    append(projectStatusLine(project.status))
                }
                append(" · ")
                append(formatTime(project.updatedAt))
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 「知识」页 = 知识资产页：不和学习页（书架）重复——
 * 这里放全书检索入口、知识总量统计，以及每本书的知识地图入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeHubScreen(
    /** 点某本书 = 看它的知识地图（导航层直达项目详情的「知识主干」tab） */
    onOpenMap: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: HomeViewModel = rememberZhishuViewModel(key = "knowledge_hub") { HomeViewModel(app) }
    val projects by vm.projects.collectAsState()
    val knowledgeStats by vm.knowledgeStats.collectAsState()
    val dueCount by vm.dueCount.collectAsState()
    val reviewEnabled by vm.spacedReviewEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("知识", fontWeight = FontWeight.Bold) })
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.padding(padding)) {
                EmptyState(
                    icon = Icons.Default.School,
                    title = "暂无知识地图",
                    subtitle = "先在书架上打开一本书并生成知识地图，所有知识点会以树形结构呈现在这里。",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
            ) {
                item(key = "__search__") {
                    // 大搜索入口：全书内容、知识点、章节一站直达
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSearch() },
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "搜索全书内容、知识点与章节…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item(key = "__stats__") {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        KnowledgeStatCard("书", projects.size.toString(), Modifier.weight(1f))
                        KnowledgeStatCard(
                            "知识点",
                            knowledgeStats.values.sum().toString(),
                            Modifier.weight(1f),
                        )
                        if (reviewEnabled) {
                            KnowledgeStatCard("待复习", dueCount.toString(), Modifier.weight(1f))
                        }
                    }
                }
                item(key = "__list_title__") {
                    SectionTitle("按书查看")
                }
                items(projects, key = { it.id }) { project ->
                    KnowledgeProjectCard(
                        project = project,
                        nodeCount = knowledgeStats[project.id] ?: 0,
                        onOpen = { onOpenMap(project.id) },
                    )
                }
                item(key = "__bottom__") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** 知识页顶部的统计小卡：数字 + 单位 */
@Composable
private fun KnowledgeStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KnowledgeProjectCard(
    project: Project,
    nodeCount: Int,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (project.topic.isNotBlank() && project.topic != project.title) {
                    Text(
                        project.topic,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (nodeCount > 0) "$nodeCount 个知识点" else "还没有知识地图",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (nodeCount > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    "查看地图 →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
