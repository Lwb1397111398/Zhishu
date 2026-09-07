package com.example.zhishu.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhishu.ai.pipeline.NextVolumeAvailability
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.ai.pipeline.KnowledgeMapMode
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ContractStatus
import com.example.zhishu.domain.model.GENRE_AUTO
import com.example.zhishu.domain.model.GapStatus
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeMapChainStatus
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.NodeGenerateStatus
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.QuizState
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceMode
import com.example.zhishu.domain.model.chainStatusList
import com.example.zhishu.domain.model.StoryBible
import com.example.zhishu.domain.model.VerificationIssue
import com.example.zhishu.ui.common.ChapterStatusChip
import com.example.zhishu.ui.common.ConfirmDialog
import com.example.zhishu.ui.common.EmptyState
import com.example.zhishu.ui.common.GenerationBanner
import com.example.zhishu.ui.common.InfoRow
import com.example.zhishu.ui.common.RequestNotificationPermissionWhenGenerating
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
    /** 打开某份资料的原文与引用（4C） */
    onOpenSource: (String) -> Unit = {},
    /** 打开全书未核实内容筛选（4C） */
    onOpenTrustCenter: (String) -> Unit = {},
    /** 从正文跳回知识地图时带上的知识点：直接打开「知识主干」页并展开它 */
    focusNodeId: String? = null,
    /** 初始落地的 tab（0 资料 / 1 知识主干 / 2 讲述路线）。知识页「查看地图」直达 tab 1 */
    initialTab: Int = 0,
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

    // 带 focusNodeId 进来就是「从正文回看知识地图」，直接停在知识主干页；
    // focusNodeId 为空时按路由给的 initialTab 落地（知识页「查看地图」直达 tab 1）
    var tab by rememberSaveable {
        mutableIntStateOf(if (focusNodeId != null) 1 else initialTab)
    }
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

    // 刚开始生成时才问通知权限，此刻这个请求才讲得通（第二阶段）
    RequestNotificationPermissionWhenGenerating(generating = generation.busy)

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
                onCancel = { vm.cancelGeneration() },
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
                        onOpenSource = onOpenSource,
                        onOpenTrustCenter = { onOpenTrustCenter(projectId) },
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
    /** 打开某份资料的原文与引用（4C） */
    onOpenSource: (String) -> Unit,
    /** 打开全书未核实内容筛选（4C） */
    onOpenTrustCenter: () -> Unit,
) {
    var showText by remember { mutableStateOf(false) }
    var showUrl by remember { mutableStateOf(false) }
    // 正在编辑来源信息的那份资料（4A）
    var editingSource by remember { mutableStateOf<SourceDocument?>(null) }
    /** 待确认的知识来源模式：已有内容时先问一句再切 */
    var pendingSourceMode by remember { mutableStateOf<SourceMode?>(null) }
    val scope = rememberCoroutineScope()
    val modelKnowledge = project?.usesModelKnowledge == true
    val unconfirmed by vm.unconfirmedCitationCount.collectAsState()
    val issues by vm.issues.collectAsState()
    val openIssueCount = issues.values.sumOf { list -> list.count { it.resolution.isOpen } }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SourceModeCard(
                modelKnowledge = modelKnowledge,
                onSelect = { mode ->
                    scope.launch {
                        if (vm.needsSourceModeConfirm(mode)) {
                            pendingSourceMode = mode
                        } else {
                            vm.setSourceMode(mode)
                        }
                    }
                },
            )
        }

        // 需要核对的内容（4C）。只在真的有的时候出现——
        // 一个常驻的「0 项需核对」入口只会教用户忽略这一行
        if (unconfirmed > 0 || openIssueCount > 0) {
            item {
                TrustEntryCard(
                    unconfirmed = unconfirmed,
                    openIssues = openIssueCount,
                    onOpen = onOpenTrustCenter,
                )
            }
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
                    SourceCard(
                        source = source,
                        onEditMeta = { editingSource = source },
                        onOpenMeta = { onOpenSource(source.id) },
                        onDelete = { vm.deleteSource(source.id) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    // 切换确认：已经有内容时才问一句，说明「不会改写已有内容」，
    // 免得有人以为点一下就能重来一遍
    pendingSourceMode?.let { pending ->
        ConfirmDialog(
            title = "切换知识来源",
            message = "已经生成的内容不会被改写，也不删除；切换只影响以后怎么生成。" +
                "想按新设置重做，切换后重新梳理知识主干即可。",
            confirmLabel = "确认切换",
            onConfirm = { vm.setSourceMode(pending); pendingSourceMode = null },
            onDismiss = { pendingSourceMode = null },
        )
    }

    editingSource?.let { source ->
        SourceMetaDialog(
            source = source,
            onConfirm = { versionLabel, jurisdiction, effectiveFrom, legalVersion ->
                vm.updateSourceMeta(
                    sourceId = source.id,
                    versionLabel = versionLabel,
                    jurisdiction = jurisdiction,
                    effectiveFrom = effectiveFrom,
                    legalVersion = legalVersion,
                )
                editingSource = null
            },
            onDismiss = { editingSource = null },
        )
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

/**
 * 「需要核对的内容」入口（4C）。
 *
 * 说数字，不说结论。「有 3 处出处没对上」是事实；「本项目内容不可信」不是，
 * 而且会让用户放弃一批其实写得没问题的章节。
 */
@Composable
private fun TrustEntryCard(
    unconfirmed: Int,
    openIssues: Int,
    onOpen: () -> Unit,
) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(
                "需要你核对的内容",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildList {
                    if (unconfirmed > 0) add("$unconfirmed 处引用没能对上资料")
                    if (openIssues > 0) add("$openIssues 条核验问题待处理")
                }.joinToString("，") + "。只统计你现在读到的这一版正文。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpen) { Text("逐条查看") }
        }
    }
}

/**
 * 知识来源模式二选一（问题3）。
 *
 * [onSelect] 由调用方决定是否先确认——本卡片不假设「切换要问」还是「切换即生效」，
 * 那是「项目里有没有内容」才回答得了的问题。
 */
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
            Spacer(Modifier.height(6.dp))
            Text(
                "切换只影响以后怎么生成，已生成的内容不会被改写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun SourceCard(
    source: SourceDocument,
    onEditMeta: () -> Unit,
    /** 打开资料原文：能看到每个片段被哪些当前正文引用（4C） */
    onOpenMeta: () -> Unit,
    onDelete: () -> Unit,
) {
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
                // 版本、法域、生效时间（4A）。法律这类内容离开这三样就没有对错可言，
                // 所以填了就一定显示出来，没填就提示可以补
                val meta = listOfNotNull(
                    source.versionLabelForDisplay,
                    source.jurisdiction?.takeIf { it.isNotBlank() },
                    source.effectiveFrom?.takeIf { it.isNotBlank() }?.let { "$it 起施行" },
                ).joinToString(" · ")
                Spacer(Modifier.height(2.dp))
                Text(
                    meta.ifBlank { "未填版本 / 法域 / 生效时间" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (meta.isBlank()) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onOpenMeta,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) { Text("原文与引用", style = MaterialTheme.typography.labelMedium) }
                    TextButton(
                        onClick = onEditMeta,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) { Text("填写来源信息", style = MaterialTheme.typography.labelMedium) }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除资料", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 填写来源元数据（4A）。
 *
 * 四栏都可以留空——大多数资料不需要它们。但对法律、税务、医疗这类内容，
 * 「哪个法域、什么时候起施行、哪一次修订」决定了正文里那句话到底对不对，
 * 而这些只有用户知道：模型不该猜，我们也不该替它猜。
 * 填过之后它们会一起进提示词，也会显示在阅读器的来源面板上。
 */
@Composable
private fun SourceMetaDialog(
    source: SourceDocument,
    onConfirm: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var versionLabel by remember { mutableStateOf(source.versionLabel.orEmpty()) }
    var jurisdiction by remember { mutableStateOf(source.jurisdiction.orEmpty()) }
    var effectiveFrom by remember { mutableStateOf(source.effectiveFrom.orEmpty()) }
    var legalVersion by remember { mutableStateOf(source.legalVersion.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("来源信息") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "都可以留空。填了之后，生成和核验会知道这份资料的适用范围，" +
                        "阅读时也能看到「这一段出自哪个版本、哪个地区」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = versionLabel,
                    onValueChange = { versionLabel = it },
                    label = { Text("文档版本") },
                    placeholder = { Text("第 3 版 / 2024 修订本") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = legalVersion,
                    onValueChange = { legalVersion = it },
                    label = { Text("法律版本") },
                    placeholder = { Text("2020 年修正") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = jurisdiction,
                    onValueChange = { jurisdiction = it },
                    label = { Text("法域 / 适用地区") },
                    placeholder = { Text("中国大陆") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = effectiveFrom,
                    onValueChange = { effectiveFrom = it },
                    label = { Text("生效时间") },
                    // 原样保存用户写的形式：资料上写「2021 年 1 月 1 日施行」就是精确到日，
                    // 换算成时间戳要凭空补出时区和时分秒
                    placeholder = { Text("2021-01-01") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(versionLabel, jurisdiction, effectiveFrom, legalVersion) },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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

    // 从正文跳回来时自动展开对应知识点的详情。
    // 一次性闸门（focusHandled）：nodes 是 Room Flow，生成完成/掌握度更新都会再发射，
    // 不加闸门的话每次发射都会把这个底部弹窗强行重开，打断用户当前操作。
    var focusHandled by remember(focusNodeId) { mutableStateOf(false) }
    LaunchedEffect(focusNodeId, nodes) {
        val target = focusNodeId ?: return@LaunchedEffect
        if (focusHandled) return@LaunchedEffect
        nodes.firstOrNull { it.id == target }?.let {
            selectedNode = it
            focusHandled = true
        }
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
                // 骨架先行：先花一次小调用看清链结构，用户再决定逐条展开还是全部展开
                onAction = { vm.enqueueKnowledgeMap(KnowledgeMapMode.SKELETON) },
                modifier = Modifier.weight(1f),
            )
        } else {
            // 分步生成：批次未完成时给「继续」入口，和「重新梳理」（开新批次）分开——
            // 部分链失败后点继续只补没做完的链，不把已经花钱展开的内容作废重来
            val batch = vm.mapBatch.collectAsState().value
            val busy = vm.generation.collectAsState().value.busy
            val unfinished = batch?.takeIf { it.skeletonDone && !it.completed }
            if (unfinished != null) {
                val statuses = remember(unfinished) { unfinished.chainStatusList() }
                MapProgressCard(
                    done = statuses.count { it == KnowledgeMapChainStatus.DONE },
                    failed = statuses.count { it == KnowledgeMapChainStatus.FAILED },
                    total = statuses.size,
                    busy = busy,
                    onExpandNext = { vm.enqueueKnowledgeMap(KnowledgeMapMode.NEXT_CHAIN) },
                    onExpandAll = { vm.enqueueKnowledgeMap(KnowledgeMapMode.FULL) },
                )
                Spacer(Modifier.height(8.dp))
            }
            // 统计与操作分两行：一行说状态，一行放按钮——挤在一行时
            // 左侧文字会被两个按钮压成三四行（「页面挤压」的典型现场）
            Column(Modifier.fillMaxWidth()) {
                Text("共 ${nodes.size} 个知识点", style = MaterialTheme.typography.bodySmall)
                Text(
                    "点进节点能看到它「从哪来、又引出什么」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = { vm.enqueueKnowledgeMap(KnowledgeMapMode.FULL) },
                    enabled = !busy,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("重新梳理")
                }
                // 批次没完成时，「重新梳理」只续跑没做完的链；想换模型/改资料后彻底重来，
                // 得先作废旧批次再开新骨架（B1）。放在「重新梳理」旁边，避免用户以为
                // 点「重新梳理」就是重来一遍。
                if (unfinished != null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { vm.restartKnowledgeMap() },
                        enabled = !busy,
                    ) {
                        Text("放弃重来")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // gaps 闭环：待处理的（OPEN）按类型给按钮，已处理的折叠收起，
            // 让「还剩几条没看」一眼可读，又不抹掉处理记录
            val openGaps = remember(gaps) { gaps.filter { it.gapStatus == GapStatus.OPEN } }
            val handledGaps = remember(gaps) { gaps.filter { it.gapStatus != GapStatus.OPEN } }
            val openConflicts = remember(conflicts) { conflicts.filter { it.conflictStatus == GapStatus.OPEN } }
            val handledConflicts = remember(conflicts) { conflicts.filter { it.conflictStatus != GapStatus.OPEN } }
            val patchingGaps by vm.patchingGaps.collectAsState()
            LazyColumn {
                if (openGaps.isNotEmpty() || handledGaps.isNotEmpty() || openConflicts.isNotEmpty() || handledConflicts.isNotEmpty()) {
                    item {
                        KnowledgeInsightCard(
                            gaps = openGaps,
                            handledGaps = handledGaps,
                            conflicts = openConflicts,
                            handledConflicts = handledConflicts,
                            modelKnowledge = modelKnowledge,
                            patching = patchingGaps,
                            onPatchGap = { vm.patchKnowledgeGap(it) },
                            onDismissGap = { vm.dismissKnowledgeGap(it) },
                            onDismissConflict = { vm.dismissKnowledgeConflict(it) },
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

/**
 * 知识主干分步展开的进度卡（分步生成）。
 *
 * 显示几条链已展开、几条失败；用户自己选节奏——「展开下一条链」单步推进，
 * 「全部展开」一口气跑完剩余链与审计。链全部完成时只剩「完成审计」一步。
 * 失败链不会作废已展开的内容，重试只补失败的部分。
 */
@Composable
private fun MapProgressCard(
    done: Int,
    failed: Int,
    total: Int,
    busy: Boolean,
    onExpandNext: () -> Unit,
    onExpandAll: () -> Unit,
) {
    val allDone = total > 0 && done >= total
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            val summary = if (allDone) {
                "演进链已全部展开，还差最后一步全局审计"
            } else {
                buildString {
                    append("知识主干分步展开：$done/$total 条演进链已完成")
                    if (failed > 0) append("，$failed 条失败（继续展开会自动重试，已完成的不受影响）")
                }
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            if (!allDone) {
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            } else {
                Spacer(Modifier.height(8.dp))
            }
            Row {
                Button(onClick = onExpandNext, enabled = !busy) {
                    Text(
                        when {
                            allDone -> "完成审计"
                            failed > 0 -> "重试失败链并继续"
                            else -> "展开下一条链"
                        },
                    )
                }
                if (!allDone) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onExpandAll, enabled = !busy) {
                        Text("全部展开")
                    }
                }
            }
        }
    }
}

/**
 * 展示资料缺口与冲突（B12；gaps 闭环的分类型 UI）。
 *
 * 三类内容分开摆、给的按钮也不一样——把「漏了东西」和「要多留个心眼」混在一起，
 * 用户会以为每条都能点掉：
 *
 * - **漏掉的知识点**（[com.example.zhishu.domain.model.GapType.MISSING_NODE]）：
 *   可行动的，给「补上」——只补这一个节点、一次调用，不重跑整张地图；
 *   暂时不想补给「知道了」移出待办。
 * - **要多留个心眼的地方**（过时 / 地区差异 / 各家说法不一 / 无法核实）：
 *   这是免责提示而不是待办，**不给关闭按钮**，它会一直提醒你核对权威出处。
 * - **资料冲突**：给「知道了」——冲突没有"补上"一说，只有"我已经知道两边
 *   说法了，别反复提示"。
 *
 * 已处理（补上 / 知道了，含关闭的冲突）默认折叠：既让「还剩几条没看」一眼可读，
 * 又保留处理记录——下一次梳理会按建议标题 / topic 继承这些状态，这里能看到继承的来源。
 */
@Composable
private fun KnowledgeInsightCard(
    gaps: List<KnowledgeGap>,
    handledGaps: List<KnowledgeGap>,
    conflicts: List<KnowledgeConflict>,
    handledConflicts: List<KnowledgeConflict>,
    modelKnowledge: Boolean,
    patching: Set<String>,
    onPatchGap: (String) -> Unit,
    onDismissGap: (String) -> Unit,
    onDismissConflict: (String) -> Unit,
) {
    // actionable 即 MISSING_NODE：只有它能补、能关，其余一律当免责提示
    val actionable = remember(gaps) { gaps.filter { it.actionable } }
    val notices = remember(gaps) { gaps.filter { !it.actionable } }
    // 已处理（补上的知识点 + 关闭的冲突）合并成一条折叠，文本带上来源标签
    val handledItems = remember(handledGaps, handledConflicts) {
        buildList {
            handledGaps.forEach { gap ->
                val label = if (gap.gapStatus == GapStatus.RESOLVED) "已补上" else "知道了"
                add(gap.description to label)
            }
            handledConflicts.forEach { conflict ->
                add("${conflict.topic}：${conflict.description}" to "知道了")
            }
        }
    }
    var showHandled by remember { mutableStateOf(false) }
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
            if (actionable.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "漏掉的知识点（可以补进知识主干）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                actionable.forEach { gap ->
                    GapActionRow(
                        gap = gap,
                        patching = gap.id in patching,
                        onPatchGap = onPatchGap,
                        onDismissGap = onDismissGap,
                    )
                }
            }
            if (notices.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "这些地方要多留个心眼（建议核对权威出处）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                notices.forEach { gap ->
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
                    ConflictActionRow(conflict, onDismissConflict)
                }
            }
            if (handledItems.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showHandled = !showHandled }) {
                    Text(
                        if (showHandled) "收起已处理的 ${handledItems.size} 条" else "已处理 ${handledItems.size} 条",
                    )
                }
                if (showHandled) {
                    handledItems.forEach { (description, label) ->
                        Text(
                            "· $description（$label）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一条「漏掉的知识点」：给「补上」和「知道了」两个出口。
 *
 * 模型没给出建议标题时不给「补上」按钮——硬点只会拿到一句「没给出要补的知识点名称」，
 * 不如直接不给这个按钮。
 */
@Composable
private fun GapActionRow(
    gap: KnowledgeGap,
    patching: Boolean,
    onPatchGap: (String) -> Unit,
    onDismissGap: (String) -> Unit,
) {
    val canPatch = gap.suggestedTitle.isNotBlank()
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            "· ${gap.description}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canPatch) {
                Button(onClick = { onPatchGap(gap.id) }, enabled = !patching) {
                    Text(if (patching) "正在补上…" else "补上「${gap.suggestedTitle}」")
                }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = { onDismissGap(gap.id) }, enabled = !patching) {
                Text("知道了")
            }
        }
    }
}

/**
 * 一条资料冲突：给「知道了」。
 *
 * 冲突没有「补上」——它不是缺一个知识点，而是同一件事有两套说法，需要用户自己判断。
 * 「知道了」只是把这条提示移出待办（关闭状态按 topic 继承，不会反复冒出来）。
 */
@Composable
private fun ConflictActionRow(
    conflict: KnowledgeConflict,
    onDismissConflict: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            "· ${conflict.topic}：${conflict.description}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onDismissConflict(conflict.id) }) {
                Text("知道了")
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(node.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                node.generateStatusLabel()?.let { (labelText, labelColor) ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        labelText,
                        style = MaterialTheme.typography.labelSmall,
                        color = labelColor,
                    )
                }
            }
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

/** 批次化生成的节点状态标签：占位/展开中/失败才显示，已完成不打扰 */
@Composable
private fun KnowledgeNode.generateStatusLabel(): Pair<String, Color>? {
    val status = NodeGenerateStatus.entries.firstOrNull { it.name == generateStatus }
        ?: return null
    return when (status) {
        NodeGenerateStatus.PLACEHOLDER -> "待展开" to MaterialTheme.colorScheme.outline
        NodeGenerateStatus.GENERATING -> "展开中" to MaterialTheme.colorScheme.primary
        NodeGenerateStatus.FAILED -> "生成失败" to MaterialTheme.colorScheme.error
        NodeGenerateStatus.COMPLETED -> null
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
    // 重新规划讲述路线（换讲法/换知识主干后需要）。已有可读正文时不允许整本重规划（3C 底线）
    val hasReadable = plans.any { it.isReadable }
    var showReplanConfirm by remember { mutableStateOf(false) }
    var showReplanDenied by remember { mutableStateOf(false) }
    // 正在查看问题清单的章节（4B）
    var issuePlan by remember { mutableStateOf<ChapterPlan?>(null) }
    // 重新生成失败的说明按章节取，绑在新版本上而不是覆盖当前正文的核验说明（3A）
    val failedRegenerations by vm.failedRegenerations.collectAsState()
    // 当前正文的核验问题，按章节分组（4B）
    val issues by vm.issues.collectAsState()
    val issueBlocks by vm.issueBlocks.collectAsState()
    val rewriting by vm.rewriting.collectAsState()
    val retryingContracts by vm.retryingContracts.collectAsState()
    // 「继续下一卷」的入口状态（3C）
    val nextVolume by vm.nextVolume.collectAsState()
    // 统计只数当前版本正文（3D）
    val stats by vm.stats.collectAsState()
    val volumeCount = plans.map { it.volumeIndex }.distinct().size
    // 进度按「正文可读」算，不因为缺自测题就少算一章
    val published = plans.count { it.isReadable }
    val needsAttention = plans.count { it.status == ChapterStatus.NEEDS_REVIEW }

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
                    Text(
                        buildString {
                            if (volumeCount > 1) append("共 $volumeCount 卷 · ")
                            append("${plans.size} 章 · 已完成 $published 章")
                            // 字数只算当前版本，重生成过的章节不会被算两遍（3D）
                            if (stats.activeChars > 0) {
                                append(" · 已写 ${stats.activeChars / 1000} 千字")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
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
            NextVolumeCard(availability = nextVolume, onPlan = { vm.planNextVolume() })
            // 讲过、但对不上现存知识点的内容：如实说明，不当成「还没讲」（3D）
            if (stats.unresolvedCoverage > 0) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "有 ${stats.unresolvedCoverage} 处讲过的内容对不上现在的知识点" +
                            "（多半是知识点改过名）。它们不会被当成没讲过，也不会重复安排。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
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
                            onReplan = { if (hasReadable) showReplanDenied = true else showReplanConfirm = true },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(plans, key = { it.id }) { plan ->
                    ChapterPlanCard(
                        plan = plan,
                        failedRegeneration = failedRegenerations[plan.id],
                        onEdit = { editPlan = plan },
                        onGenerate = { vm.enqueueChapter(plan.id) },
                        onRead = { onOpenReader(plan.id, null) },
                        onQuiz = { onOpenQuiz(plan.id) },
                        onRegenerateQuiz = { vm.regenerateQuiz(plan.id) },
                        issues = issues[plan.id].orEmpty(),
                        onOpenIssues = {
                            // 正文按需装载：问题清单要显示「这条说的是哪一段」
                            vm.loadIssueBlocks(plan.id)
                            issuePlan = plan
                        },
                        rewriting = plan.id in rewriting,
                        onRetryContract = { vm.retryChapterContract(plan.id) },
                        retryingContract = plan.id in retryingContracts,
                    )
                }
            }
        }
    }

    issuePlan?.let { plan ->
        IssueSheet(
            plan = plan,
            issues = issues[plan.id].orEmpty(),
            blocks = issueBlocks,
            rewriting = plan.id in rewriting,
            onRewrite = { blockIds ->
                vm.rewriteBlocks(plan.id, blockIds)
                issuePlan = null
            },
            onAccept = { vm.acceptIssue(it) },
            onDismiss = { issuePlan = null },
        )
    }

    if (showGenre) {
        GenrePickerDialog(
            current = project?.genre.orEmpty(),
            canReplan = !hasReadable,
            onPick = { genre, replan ->
                vm.setGenre(genre, replan)
                showGenre = false
            },
            onDismiss = { showGenre = false },
        )
    }

    if (showReplanConfirm) {
        ConfirmDialog(
            title = "重新规划讲述路线",
            message = "将清空现有的 ${plans.size} 章章节安排，按当前讲法重新规划。" +
                "目前还没有已写好的正文，不会丢失可读内容。",
            confirmLabel = "重新规划",
            onConfirm = { vm.enqueueStoryBible() },
            onDismiss = { showReplanConfirm = false },
        )
    }

    if (showReplanDenied) {
        AlertDialog(
            onDismissRequest = { showReplanDenied = false },
            title = { Text("不能整本重新规划") },
            text = {
                Text(
                    "这本书已经写出了正文，整本重新规划会删掉你读过的内容，所以不被允许。\n\n" +
                        "想让新讲法生效：等当前卷读完，用「继续下一卷」接着写，新卷会按新讲法来。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showReplanDenied = false }) { Text("知道了") }
            },
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
    onReplan: () -> Unit,
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
                TextButton(onClick = onReplan) { Text("重新规划") }
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
    /** 没有已写好的正文时才能立即重新规划（3C 底线） */
    canReplan: Boolean,
    onPick: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // 换讲法的意图就是让新讲法生效，能重规划就默认勾上
    var replan by remember { mutableStateOf(true) }
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
                            .clickable { onPick(option, replan && canReplan) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = { onPick(option, replan && canReplan) })
                        Spacer(Modifier.width(8.dp))
                        Text(option, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (canReplan) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { replan = !replan }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = replan, onCheckedChange = { replan = it })
                        Spacer(Modifier.width(4.dp))
                        Column {
                            Text("立即重新规划讲述路线", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "按新讲法重新安排章节（现有章节安排会被清空，目前没有已写好的正文）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "这本书已经写出了正文，已写好的章节不会变，新讲法会从「继续下一卷」开始生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

/**
 * 「继续下一卷」入口（第三阶段 3C）。
 *
 * 三种情形三种话，不合并成一个按钮：
 * - 当前卷没读完 → 不给按钮，说清为什么（硬续会打乱故事顺序）
 * - 还有没讲到的知识点 → 「继续主线」
 * - 主线讲完了 → 「扩展阅读」，与继续主线明确区分，避免无止境地再要一卷
 */
@Composable
private fun NextVolumeCard(
    availability: NextVolumeAvailability,
    onPlan: () -> Unit,
) {
    if (availability is NextVolumeAvailability.NoPlanYet) return
    Card {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                when (availability) {
                    is NextVolumeAvailability.CurrentVolumeUnfinished -> {
                        Text("这一卷还有 ${availability.remaining} 章没写完", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "写完这一卷再规划下一卷，故事顺序才接得上",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is NextVolumeAvailability.CanContinue -> {
                        Text("还有 ${availability.pendingCount} 个知识点没讲到", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "接着当前进度规划下一卷，已写好的章节不会改动",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NextVolumeAvailability.MainLineDone -> {
                        Text("主线已经讲完了", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "知识地图上的内容都讲到了。还想读可以规划一卷扩展阅读。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NextVolumeAvailability.NoPlanYet -> Unit
                }
            }
            when (availability) {
                is NextVolumeAvailability.CanContinue ->
                    OutlinedButton(onClick = onPlan) { Text("继续主线") }
                NextVolumeAvailability.MainLineDone ->
                    TextButton(onClick = onPlan) { Text("扩展阅读") }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ChapterPlanCard(
    plan: ChapterPlan,
    /** 最近一次重新生成失败的说明。旧正文仍然可读，所以这条只是提示而不是错误状态（3A） */
    failedRegeneration: String?,
    onEdit: () -> Unit,
    onGenerate: () -> Unit,
    onRead: () -> Unit,
    onQuiz: () -> Unit,
    /** 只重新出题，不重写正文（第二阶段） */
    onRegenerateQuiz: () -> Unit,
    /** 本章当前正文的核验问题（4B）。空表示没有问题记录 */
    issues: List<VerificationIssue> = emptyList(),
    /** 打开问题列表，逐条查看并选择要重写的段落（4B） */
    onOpenIssues: () -> Unit = {},
    /** 正在局部重写 */
    rewriting: Boolean = false,
    /** 单独补这一章的因果契约（三段式）。契约没生成的章禁止写正文 */
    onRetryContract: () -> Unit = {},
    /** 契约正在补 */
    retryingContract: Boolean = false,
) {
    // 可读性只看正文状态。QUIZ_FAILED 是旧库里的值，正文其实是通过核验的
    val canRead = plan.isReadable ||
        plan.status == ChapterStatus.NEEDS_REVIEW ||
        plan.status == ChapterStatus.DRAFT
    val canQuiz = plan.isReadable && plan.quizState == QuizState.READY
    // 补题不该走「重新生成章节」，那会连正文一起重写
    val quizMissing = plan.isReadable && plan.quizState == QuizState.FAILED
    val needsRetry = plan.status == ChapterStatus.NEEDS_REVIEW ||
        plan.status == ChapterStatus.FAILED
    /**
     * 契约没生成：这一章还不能写正文。
     *
     * 三段式之后契约是逐章生成的，一章失败只影响一章。这里必须如实标出来，
     * 否则用户点「生成」会撞上一句看不懂的报错，而且不知道有单独重试这条路。
     */
    val contractFailed = plan.contractStatus == ContractStatus.FAILED.name
    // 还没处理的问题。已接受和已修的不再计数——那两种都已经有结论了（4B）
    val openIssues = issues.filter { it.resolution.isOpen }
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
            // 正文与测验是两件事：正文好着呢，只是自测没出来（第二阶段）
            if (quizMissing) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "正文已完成，自测暂未生成，不影响阅读",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 契约没生成就说清楚：这一章暂时写不了正文，而且可以只补这一章。
            // 不标出来的话，用户只会看到「生成」按钮点了报错，
            // 却不知道整卷其他章都好着呢、也不用重新规划整卷
            if (contractFailed) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "这一章的因果契约没生成成功，暂时不能写正文。" +
                            "可以只补这一章，不影响其他章节。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            // 展示核验说明，用户才知道为什么待复核（B3）
            // 核验说明只讲正文的问题。出题失败的原因单独放在 quizNote 里，
            // 免得「测验没出来」被当成「正文有问题」
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
            // 逐条的问题记录（4B）。这里只给一句概览，详情在问题列表里。
            // 关键是把「能不能只改那几段」说出来——它决定用户点哪个按钮
            if (openIssues.isNotEmpty()) {
                val located = openIssues.count { it.isBlockScoped }
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("核验待处理 ${openIssues.size} 条")
                        if (located > 0) {
                            append("，其中 $located 条能定位到具体段落，可只重写这几段")
                        } else {
                            append("，都没能定位到具体段落，只能整章重新生成")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            // 重新生成没成功时如实说一句：正文还是原来那一版（第三阶段 3A）。
            // 用中性底色而不是错误红——这一章本身好着呢，只是新写的那一版没通过
            if (!failedRegeneration.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "上次重新生成没有通过，正文仍是此前那一版（可继续阅读）。\n$failedRegeneration",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            // 按钮收敛：主操作（阅读/生成/补契约按优先级）留在卡面上，
            // 其余动作（编辑/测验/补题/逐条处理/重新生成）收进「更多」菜单——
            // 原先 7 个按钮挤一行是整个讲述路线页最乱的地方，但一个功能都没少
            val mainIsContract = contractFailed
            val mainIsRead = !contractFailed && canRead
            var menuExpanded by remember(plan.id) { mutableStateOf(false) }
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    mainIsContract -> TextButton(
                        onClick = onRetryContract,
                        enabled = !retryingContract,
                    ) { Text(if (retryingContract) "补契约中…" else "补契约") }

                    mainIsRead -> Button(onClick = onRead) { Text("阅读") }

                    else -> TextButton(
                        onClick = onGenerate,
                        // 契约没生成的章禁止写正文——点击后会在 pipeline 里被拦下，
                        // 这里直接禁用，省得用户白点一次再读一句报错
                        enabled = !rewriting && !contractFailed,
                    ) { Text(if (needsRetry) "重新生成" else "生成") }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑标题与目标") },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    if (!mainIsRead && canRead) {
                        DropdownMenuItem(
                            text = { Text("阅读") },
                            onClick = {
                                menuExpanded = false
                                onRead()
                            },
                        )
                    }
                    if (canQuiz) {
                        DropdownMenuItem(
                            text = { Text("测验") },
                            onClick = {
                                menuExpanded = false
                                onQuiz()
                            },
                        )
                    }
                    // 补题只调出题阶段，绝不重写正文
                    if (quizMissing) {
                        DropdownMenuItem(
                            text = { Text("重新生成自测") },
                            onClick = {
                                menuExpanded = false
                                onRegenerateQuiz()
                            },
                        )
                    }
                    // 有可定位到段落的问题时，「逐条处理」比整章重新生成便宜得多，
                    // 也不会把写对的部分一起换掉
                    if (openIssues.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text(if (rewriting) "修改中…" else "逐条处理") },
                            onClick = {
                                menuExpanded = false
                                onOpenIssues()
                            },
                            enabled = !rewriting,
                        )
                    }
                    if (mainIsRead) {
                        DropdownMenuItem(
                            text = { Text(if (needsRetry) "重新生成正文" else "生成正文") },
                            onClick = {
                                menuExpanded = false
                                onGenerate()
                            },
                            enabled = !rewriting && !contractFailed,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 核验问题列表（4B）。
 *
 * 这个面板存在的全部理由：让「核验没通过」从一句没法处理的话，
 * 变成一份能逐条处理的清单。所以每条问题都必须能回答三个问题——
 * 它说的是哪一段（正文原文就在下面）、属于什么类型、建议怎么改。
 *
 * 勾选框只出现在能定位到段落的问题上。定位不到的问题不给勾选框，
 * 也不给一个假的「重写」按钮：不知道改哪儿的时候，能做的只有整章重来
 * 或者由用户自己判断，把这件事说清楚比给一个点了没用的按钮诚实。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IssueSheet(
    plan: ChapterPlan,
    issues: List<VerificationIssue>,
    blocks: Map<String, ChapterBlock>,
    rewriting: Boolean,
    onRewrite: (List<String>) -> Unit,
    onAccept: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val open = issues.filter { it.resolution.isOpen }
    var selected by remember(plan.id) { mutableStateOf(setOf<String>()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                "第 ${plan.order} 章 · 核验问题",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            // 这句话是整个面板的定心丸：用户在动自己读过的内容，
            // 必须先知道「改坏了也不影响现在能读的正文」
            Text(
                "修改会写成新的一版，通过核验后才替换当前正文。" +
                    "没通过就自动作废，你现在读到的内容不会变。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (open.isEmpty()) {
                Text("没有待处理的问题。", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(open, key = { it.id }) { issue ->
                    IssueRow(
                        issue = issue,
                        block = issue.blockId?.let { blocks[it] },
                        checked = issue.blockId in selected,
                        enabled = !rewriting,
                        onToggle = {
                            val blockId = issue.blockId ?: return@IssueRow
                            selected = if (blockId in selected) {
                                selected - blockId
                            } else {
                                selected + blockId
                            }
                        },
                        onAccept = { onAccept(issue.id) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            val located = open.count { it.isBlockScoped }
            if (located == 0) {
                Text(
                    "这些问题都没能定位到具体段落，只能整章重新生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = { onRewrite(selected.toList()) },
                    enabled = selected.isNotEmpty() && !rewriting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            rewriting -> "正在修改并重新核验…"
                            selected.isEmpty() -> "勾选要重写的段落"
                            else -> "重写选中的 ${selected.size} 段并重新核验"
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "重写后会做一次全章核验：只改一段也可能让别处的「如前所述」变得不对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IssueRow(
    issue: VerificationIssue,
    block: ChapterBlock?,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onAccept: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (issue.isBlockScoped) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
                }
                Text(
                    issue.type.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAccept, enabled = enabled) { Text("不用改") }
            }
            Text(issue.message, style = MaterialTheme.typography.bodyMedium)
            if (issue.suggestedFix.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "建议：${issue.suggestedFix}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 问题指的是哪一段。没有这段原文，用户没法判断该不该改
            if (block != null) {
                Spacer(Modifier.height(6.dp))
                Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
                    Text(
                        block.text.take(ISSUE_BLOCK_EXCERPT),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "没能定位到具体段落，无法单独重写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 问题面板里正文原文显示多少字。够认出是哪一段，不必铺满屏幕 */
private const val ISSUE_BLOCK_EXCERPT = 240

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
