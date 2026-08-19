package com.example.zhishu.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.GENRE_AUTO
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceMode
import com.example.zhishu.domain.model.StoryBible
import com.example.zhishu.ui.common.ChapterStatusChip
import com.example.zhishu.ui.common.ConfirmDialog
import com.example.zhishu.ui.common.EmptyState
import com.example.zhishu.ui.common.GenerationBanner
import com.example.zhishu.ui.common.InfoRow
import com.example.zhishu.ui.common.SOURCE_MIME_TYPES
import com.example.zhishu.ui.common.SourceKindChip
import com.example.zhishu.ui.common.SourceTextDialog
import com.example.zhishu.ui.common.SourceUrlDialog
import com.example.zhishu.ui.common.StatusChip
import com.example.zhishu.ui.common.readSourceFile
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.ProjectDetailViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenReader: (String, String?) -> Unit,
    onOpenQuiz: (String) -> Unit,
    onOpenSearch: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    /** 从正文跳回知识地图时带上的知识点：直接打开「知识主干」页并展开它 */
    focusNodeId: String? = null,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ProjectDetailViewModel = rememberZhishuViewModel(key = "detail_$projectId") {
        ProjectDetailViewModel(app, projectId)
    }
    val project by vm.project.collectAsState()
    val sources by vm.sources.collectAsState()
    val nodes by vm.nodes.collectAsState()
    val gaps by vm.gaps.collectAsState()
    val conflicts by vm.conflicts.collectAsState()
    val plans by vm.plans.collectAsState()
    val bible by vm.bible.collectAsState()
    val generation by vm.generation.collectAsState()
    val message by vm.message.collectAsState()
    val nodeAnchors by vm.nodeAnchors.collectAsState()

    // 带 focusNodeId 进来就是「从正文回看知识地图」，直接停在知识主干页
    var tab by rememberSaveable { mutableIntStateOf(if (focusNodeId != null) 1 else 0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) { readSourceFile(app, uri) }
                    .onSuccess { picked ->
                        vm.addFileSource(picked.title, picked.fileName, picked.kind, picked.bytes)
                    }
                    .onFailure { snackbarHostState.showSnackbar("读取文件失败：${it.message}") }
            }
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project?.title ?: "项目详情", style = MaterialTheme.typography.titleLarge)
                        project?.let { Text(it.topic, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { onOpenSearch(projectId) }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除项目",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
        bottomBar = {
            TabRow(selectedTabIndex = tab) {
                listOf("资料", "知识主干", "讲述路线").forEachIndexed { index, label ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // 生成状态常驻在页面顶部，三个 Tab 都能看到（问题1）
            GenerationBanner(
                state = generation,
                onRetry = { vm.enqueueOneTap() },
                onOpenSettings = onOpenSettings,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> SourcesTab(
                        project = project,
                        sources = sources,
                        vm = vm,
                        hasNodes = nodes.isNotEmpty(),
                        onAddFile = { filePicker.launch(SOURCE_MIME_TYPES) },
                        onGoMap = { tab = 1 },
                    )
                    1 -> MapTab(
                        vm = vm,
                        project = project,
                        nodes = nodes,
                        gaps = gaps,
                        conflicts = conflicts,
                        sources = sources,
                        nodeAnchors = nodeAnchors,
                        focusNodeId = focusNodeId,
                        onOpenReader = onOpenReader,
                    )
                    else -> OutlineTab(
                        vm = vm,
                        project = project,
                        plans = plans,
                        bible = bible,
                        onOpenReader = onOpenReader,
                        onOpenQuiz = onOpenQuiz,
                    )
                }
            }
        }
    }

    confirmDelete.let {
        if (it) {
            ConfirmDialog(
                title = "删除项目",
                message = "确定删除「${project?.title ?: ""}」吗？所有数据都会被删除且无法恢复。",
                confirmLabel = "删除",
                onConfirm = {
                    vm.deleteProject()
                    onBack()
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesTab(
    project: com.example.zhishu.domain.model.Project?,
    sources: List<SourceDocument>,
    vm: ProjectDetailViewModel,
    hasNodes: Boolean,
    onAddFile: () -> Unit,
    onGoMap: () -> Unit,
) {
    var showText by remember { mutableStateOf(false) }
    var showUrl by remember { mutableStateOf(false) }
    val modelKnowledge = project?.usesModelKnowledge == true

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SourceModeCard(
                modelKnowledge = modelKnowledge,
                onSelect = { vm.setSourceMode(it) },
            )
        }

        // 一键开讲：把「知识地图 → 讲述路线 → 第一章」收成一个按钮（问题4）
        item {
            OneTapCard(
                project = project,
                ready = modelKnowledge || sources.isNotEmpty(),
                hasNodes = hasNodes,
                onStart = { vm.enqueueOneTap() },
                onGoMap = onGoMap,
            )
        }

        if (!modelKnowledge) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showText = true }) {
                        Icon(Icons.Default.NoteAdd, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("粘贴文本")
                    }
                    OutlinedButton(onClick = { showUrl = true }) {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("网页")
                    }
                    OutlinedButton(onClick = onAddFile) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("文件")
                    }
                }
            }
            if (sources.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Default.NoteAdd,
                        title = "还没有资料",
                        subtitle = "添加教材、讲义、法条或网页内容；或者切换到上面的「AI 自备知识」，让 AI 自己组织这门学科。",
                        modifier = Modifier.height(220.dp),
                    )
                }
            } else {
                items(sources, key = { it.id }) { source ->
                    SourceCard(source = source, onDelete = { vm.deleteSource(source.id) })
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (showText) {
        SourceTextDialog(
            onConfirm = { title, text ->
                vm.addTextSource(title, text)
                showText = false
            },
            onDismiss = { showText = false },
        )
    }
    if (showUrl) {
        SourceUrlDialog(
            onConfirm = { url ->
                vm.addUrlSource(url)
                showUrl = false
            },
            onDismiss = { showUrl = false },
        )
    }
}

/** 知识来源模式二选一（问题3） */
@Composable
private fun SourceModeCard(
    modelKnowledge: Boolean,
    onSelect: (SourceMode) -> Unit,
) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text("这门学科的知识从哪来", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            ModeOption(
                selected = modelKnowledge,
                title = "AI 自备知识（推荐）",
                subtitle = "不需要准备任何资料。AI 会站在整门学科之上自己组织讲法，容易讲出连贯的推演；" +
                    "有地区差异或可能过时的地方会标出来让你核对。",
                onClick = { onSelect(SourceMode.MODEL) },
            )
            Spacer(Modifier.height(8.dp))
            ModeOption(
                selected = !modelKnowledge,
                title = "以我的资料为准",
                subtitle = "适合有指定教材、内部讲义或必须严格对齐的考试范围。每句知识都能追溯到你给的资料。",
                onClick = { onSelect(SourceMode.DOCUMENTS) },
            )
        }
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 一键开讲（问题4）：原来要在三个 Tab 之间来回点四五次，现在一次点完 */
@Composable
private fun OneTapCard(
    project: com.example.zhishu.domain.model.Project?,
    ready: Boolean,
    hasNodes: Boolean,
    onStart: () -> Unit,
    onGoMap: () -> Unit,
) {
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (hasNodes) "继续生成" else "一键开讲",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (hasNodes) {
                    "会接着把还没做完的步骤补齐，然后生成下一章。"
                } else {
                    "依次完成三步：梳理知识主干 → 规划讲述路线 → 写出第一章。全程后台进行。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            project?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "讲法：${it.effectiveGenre}　每章约 ${it.wordTargetPerChapter} 字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = ready) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (hasNodes) "继续" else "开始")
                }
                if (hasNodes) {
                    OutlinedButton(onClick = onGoMap) { Text("看知识主干") }
                }
            }
            if (!ready) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "请先添加至少一份资料，或改用上面的「AI 自备知识」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SourceCard(source: SourceDocument, onDelete: () -> Unit) {
    Card(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(source.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceKindChip(source.kind)
                    ParseStatusChip(source.parseStatus)
                }
                Text(
                    "${source.fileName ?: source.url ?: "文本"} · ${formatDay(source.fetchedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除资料", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ParseStatusChip(status: ParseStatus) {
    val (label, container, content) = when (status) {
        ParseStatus.PENDING -> Triple("等待解析", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        ParseStatus.PARSED -> Triple("已解析", androidx.compose.ui.graphics.Color(0xFF2E7D32), androidx.compose.ui.graphics.Color(0xFFE8F5E9))
        ParseStatus.SCAN_ONLY -> Triple("扫描版（需文字版）", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ParseStatus.FAILED -> Triple("解析失败", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
    }
    StatusChip(label, container, content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapTab(
    vm: ProjectDetailViewModel,
    project: com.example.zhishu.domain.model.Project?,
    nodes: List<KnowledgeNode>,
    gaps: List<KnowledgeGap>,
    conflicts: List<KnowledgeConflict>,
    sources: List<SourceDocument>,
    nodeAnchors: Map<String, List<ProjectDetailViewModel.ChapterAnchor>> = emptyMap(),
    focusNodeId: String? = null,
    onOpenReader: (String, String?) -> Unit = { _, _ -> },
) {
    var selectedNode by remember { mutableStateOf<KnowledgeNode?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val modelKnowledge = project?.usesModelKnowledge == true

    // 从正文跳回来时自动展开对应知识点的详情
    LaunchedEffect(focusNodeId, nodes) {
        val target = focusNodeId ?: return@LaunchedEffect
        nodes.firstOrNull { it.id == target }?.let { selectedNode = it }
    }
    // 只渲染根节点，子节点由 TreeNodeRow 递归展开，避免同一节点重复出现（B8）
    val nodeIds = remember(nodes) { nodes.map { it.id }.toSet() }
    val rootNodes = remember(nodes, nodeIds) {
        nodes.filter { it.parentId == null || it.parentId !in nodeIds }
    }
    val childrenByParent = remember(nodes) {
        nodes.filter { it.parentId != null }.groupBy { it.parentId!! }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (nodes.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Map,
                title = "知识主干尚未梳理",
                subtitle = when {
                    modelKnowledge -> "将由 AI 自己组织这门学科的演进主干：一个概念从哪来、又引出什么。需要联网并已配置模型。"
                    sources.isEmpty() -> "请先在「资料」页添加学习资料，或改用「AI 自备知识」模式。"
                    else -> "将从资料中提取知识点，整理成一条能走通的演进主干。需要联网并已配置模型。"
                },
                actionLabel = if (!modelKnowledge && sources.isEmpty()) null else "开始梳理知识主干",
                onAction = { vm.enqueueKnowledgeMap() },
                modifier = Modifier.weight(1f),
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("共 ${nodes.size} 个知识点", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "点进节点能看到它「从哪来、又引出什么」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { vm.enqueueKnowledgeMap() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("重新梳理")
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                if (gaps.isNotEmpty() || conflicts.isNotEmpty()) {
                    item {
                        KnowledgeInsightCard(
                            gaps = gaps,
                            conflicts = conflicts,
                            modelKnowledge = modelKnowledge,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(rootNodes, key = { it.id }) { node ->
                    TreeNodeRow(
                        node = node,
                        childrenByParent = childrenByParent,
                        expanded = expanded,
                        onClick = { selectedNode = it },
                    )
                }
            }
        }
    }

    selectedNode?.let { node ->
        ModalBottomSheet(onDismissRequest = { selectedNode = null }) {
            NodeDetailSheet(
                node = node,
                anchors = nodeAnchors[node.id].orEmpty(),
                onToggleConfirm = { confirmed -> vm.setNodeConfirmed(node, confirmed) },
                onOpenReader = { chapterId, blockId ->
                    selectedNode = null
                    onOpenReader(chapterId, blockId)
                },
            )
        }
    }
}

/** 展示资料缺口与冲突（B12）。无资料模式下它们变成"需要你自己核对"的提醒（问题3） */
@Composable
private fun KnowledgeInsightCard(
    gaps: List<KnowledgeGap>,
    conflicts: List<KnowledgeConflict>,
    modelKnowledge: Boolean,
) {
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (modelKnowledge) "这些地方别只信 AI" else "资料体检",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (gaps.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (modelKnowledge) {
                        "有地区/版本差异或可能已过时，建议核对权威出处"
                    } else {
                        "资料缺口（建议补充相关资料）"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                gaps.forEach { gap ->
                    Text(
                        "· ${gap.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            if (conflicts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (modelKnowledge) "存在对立观点（需要你判断采用哪一方）" else "资料冲突（需要你判断采用哪一方）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                conflicts.forEach { conflict ->
                    Text(
                        "· ${conflict.topic}：${conflict.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun TreeNodeRow(
    node: KnowledgeNode,
    childrenByParent: Map<String, List<KnowledgeNode>>,
    expanded: MutableMap<String, Boolean>,
    onClick: (KnowledgeNode) -> Unit,
) {
    val children = childrenByParent[node.id].orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick(node) }
            .padding(start = ((node.depth - 1) * 20).dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (children.isNotEmpty()) {
            IconButton(onClick = { expanded[node.id] = !(expanded[node.id] ?: false) }) {
                Text(if (expanded[node.id] == true) "▾" else "▸")
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Icon(
            Icons.Default.School,
            contentDescription = null,
            tint = if (node.confirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(node.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            LinearProgressIndicator(
                progress = { node.mastery },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
        if (node.confirmed) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = "已确认",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    if (expanded[node.id] == true) {
        children.forEach { child ->
            TreeNodeRow(
                node = child,
                childrenByParent = childrenByParent,
                expanded = expanded,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun NodeDetailSheet(
    node: KnowledgeNode,
    anchors: List<ProjectDetailViewModel.ChapterAnchor>,
    onToggleConfirm: (Boolean) -> Unit,
    onOpenReader: (String, String?) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(node.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "掌握度 ${(node.mastery * 100).toInt()}% · ${if (node.confirmed) "已确认" else "未确认"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        // 来处/去处放在最前面：它们才是"为什么会有这个概念"的答案（问题2）
        if (node.arisesFrom.isNotBlank()) InfoRow("从哪来", node.arisesFrom)
        if (node.leadsTo.isNotBlank()) InfoRow("引出什么", node.leadsTo)
        InfoRow("解决什么", node.definition)
        if (node.prerequisites.isNotEmpty()) InfoRow("前置知识", node.prerequisites.joinToString("、"))
        if (node.related.isNotEmpty()) InfoRow("相关知识", node.related.joinToString("、"))
        if (node.confusions.isNotEmpty()) InfoRow("易混淆", node.confusions.joinToString("、"))
        if (node.examples.isNotEmpty()) InfoRow("案例", node.examples.joinToString("、"))
        if (node.sourceRefs.isNotEmpty()) InfoRow("资料来源", node.sourceRefs.size.toString() + " 份")

        // 知识地图 → 正文：点章节直接落到讲这个知识点的那一段（第一阶段核心闭环）
        Spacer(Modifier.height(8.dp))
        if (anchors.isEmpty()) {
            Text(
                "这个知识点还没在正文里讲到。生成对应章节后，这里会出现跳转入口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("在故事里读它", style = MaterialTheme.typography.labelLarge)
            anchors.forEach { anchor ->
                TextButton(
                    onClick = { onOpenReader(anchor.chapterId, anchor.blockId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "第 ${anchor.order} 章 ${anchor.title}" +
                            if (anchor.blockId != null) " · 跳到正文" else " · 打开章节",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已确认（可用于后续故事规划）", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(checked = node.confirmed, onCheckedChange = onToggleConfirm)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "确认过、来源清晰的知识点会被优先编织进故事；不确认的节点只做补充。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OutlineTab(
    vm: ProjectDetailViewModel,
    project: com.example.zhishu.domain.model.Project?,
    plans: List<ChapterPlan>,
    bible: StoryBible?,
    onOpenReader: (String, String?) -> Unit,
    onOpenQuiz: (String) -> Unit,
) {
    var editPlan by remember { mutableStateOf<ChapterPlan?>(null) }
    var showGenre by remember { mutableStateOf(false) }
    val published = plans.count { it.status == ChapterStatus.PUBLISHED }
    val needsAttention = plans.count {
        it.status == ChapterStatus.NEEDS_REVIEW || it.status == ChapterStatus.QUIZ_FAILED
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (plans.isEmpty()) {
            EmptyState(
                icon = Icons.Default.AutoAwesome,
                title = "讲述路线尚未规划",
                subtitle = "会把知识主干切成若干章，每章走完一条完整的演进链——" +
                    "从一个已经成立的东西出发，撞上它解决不了的问题，于是长出下一个制度。",
                actionLabel = "规划讲述路线",
                onAction = { vm.enqueueStoryBible() },
                modifier = Modifier.weight(1f),
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("共 ${plans.size} 章 · 已完成 $published 章", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "一章走完一条演进链，不是一两个零散考点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { vm.enqueueChapters() }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("生成未完成")
                }
            }
            if (needsAttention > 0) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "有 $needsAttention 章需要复核或补测验",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.retryNeedsReview() }) { Text("全部重试") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                if (bible != null) {
                    item {
                        StoryBibleCard(
                            bible = bible,
                            project = project,
                            chapterCount = plans.size,
                            onChangeGenre = { showGenre = true },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(plans, key = { it.id }) { plan ->
                    ChapterPlanCard(
                        plan = plan,
                        onEdit = { editPlan = plan },
                        onGenerate = { vm.enqueueChapter(plan.id) },
                        onRead = { onOpenReader(plan.id, null) },
                        onQuiz = { onOpenQuiz(plan.id) },
                    )
                }
            }
        }
    }

    if (showGenre) {
        GenrePickerDialog(
            current = project?.genre.orEmpty(),
            onPick = {
                vm.setGenre(it)
                showGenre = false
            },
            onDismiss = { showGenre = false },
        )
    }

    editPlan?.let { plan ->
        EditChapterDialog(
            plan = plan,
            onSave = { title, plotGoal, goals, wordTarget ->
                // 只提交用户可编辑字段，不回写整行，避免覆盖生成状态（N13）
                vm.updatePlanFields(plan.id, title, plotGoal, goals, wordTarget)
                editPlan = null
            },
            onDismiss = { editPlan = null },
        )
    }
}

/** AI 选了什么讲法、故事走到哪了、上下文成本（问题5、问题6） */
@Composable
private fun StoryBibleCard(
    bible: StoryBible,
    project: com.example.zhishu.domain.model.Project?,
    chapterCount: Int,
    onChangeGenre: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AI 选的讲法",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onChangeGenre) { Text("换讲法") }
            }
            if (bible.style.isNotBlank()) {
                Text(bible.style, style = MaterialTheme.typography.bodyMedium)
            }
            if (bible.approach.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    bible.approach,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            // 明确告诉用户成本不随章数增长——这是用户第五个顾虑的直接回答
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "上下文用量固定",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "每写一章只带上设定、主线、一段会被不断改写的进度摘要和上一章结尾，" +
                            "不带任何前面的正文。所以第 $chapterCount 章和第 1 章的花费几乎一样，" +
                            "写到几百章也不会越来越贵。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (bible.runningState.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "故事走到哪了",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    bible.runningState,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
            if (bible.throughLine.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "全书主线",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                bible.throughLine.take(if (expanded) Int.MAX_VALUE else 3).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (bible.coveredTitles.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "已讲透 ${bible.coveredTitles.size} 个知识点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开全部")
            }
        }
    }
}

/** 风格选择，第一项就是「AI 自由发挥」（问题6） */
@Composable
private fun GenrePickerDialog(
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("换一种讲法") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "不确定选哪个就用第一项——像民法这类学科，AI 自选往往比硬套一个类型更好。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                GENRE_OPTIONS.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = { onPick(option) })
                        Spacer(Modifier.width(8.dp))
                        Text(option, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

internal val GENRE_OPTIONS = listOf(
    GENRE_AUTO,
    "跟着一个人的一生走",
    "沿着思想史走",
    "从最小的能跑起来的东西搭起",
    "围绕一个棘手案子展开",
    "师徒对话",
    "悬疑",
)

@Composable
private fun ChapterPlanCard(
    plan: ChapterPlan,
    onEdit: () -> Unit,
    onGenerate: () -> Unit,
    onRead: () -> Unit,
    onQuiz: () -> Unit,
) {
    val canRead = plan.status == ChapterStatus.PUBLISHED ||
        plan.status == ChapterStatus.NEEDS_REVIEW ||
        plan.status == ChapterStatus.QUIZ_FAILED ||
        plan.status == ChapterStatus.DRAFT
    val canQuiz = plan.status == ChapterStatus.PUBLISHED
    val needsRetry = plan.status == ChapterStatus.NEEDS_REVIEW ||
        plan.status == ChapterStatus.QUIZ_FAILED ||
        plan.status == ChapterStatus.FAILED
    Card(modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 ${plan.order} 章",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    plan.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                ChapterStatusChip(plan.status)
            }
            // 推演骨架是这一章的脊椎，比剧情目标更值得显示在前面（问题7）
            if (plan.derivationChain.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        plan.derivationChain,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            } else if (plan.plotGoal.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    plan.plotGoal,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (plan.learningGoals.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                plan.learningGoals.take(3).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (plan.unverifiedClaims.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "有 ${plan.unverifiedClaims.size} 处建议核对权威出处",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            // 展示核验说明，用户才知道为什么待复核（B3）
            if (plan.verificationNote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        plan.verificationNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            Row(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onEdit) { Text("编辑") }
                if (canRead) TextButton(onClick = onRead) { Text("阅读") }
                if (canQuiz) TextButton(onClick = onQuiz) { Text("测验") }
                TextButton(onClick = onGenerate) {
                    Text(if (needsRetry) "重新生成" else "生成")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditChapterDialog(
    plan: ChapterPlan,
    onSave: (String, String, List<String>, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(plan.title) }
    var plotGoal by remember { mutableStateOf(plan.plotGoal) }
    var learningGoals by remember { mutableStateOf(plan.learningGoals.joinToString("、")) }
    var wordTarget by remember { mutableStateOf(plan.wordTarget.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑第 ${plan.order} 章") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("章节标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = plotGoal,
                    onValueChange = { plotGoal = it },
                    label = { Text("剧情目标") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = learningGoals,
                    onValueChange = { learningGoals = it },
                    label = { Text("本章知识点（用顿号或逗号分隔）") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = wordTarget,
                    onValueChange = { wordTarget = it },
                    label = { Text("目标字数") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        title.trim(),
                        plotGoal.trim(),
                        learningGoals.split(Regex("[、，,；;]")).map { it.trim() }.filter { it.isNotEmpty() },
                        wordTarget.toIntOrNull() ?: plan.wordTarget,
                    )
                },
                enabled = title.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatDay(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
