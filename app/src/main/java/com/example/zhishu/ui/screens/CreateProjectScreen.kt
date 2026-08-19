package com.example.zhishu.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhishu.domain.model.SourceMode
import com.example.zhishu.ui.common.SOURCE_MIME_TYPES
import com.example.zhishu.ui.common.SourceKindChip
import com.example.zhishu.ui.common.SourceTextDialog
import com.example.zhishu.ui.common.SourceUrlDialog
import com.example.zhishu.ui.common.readSourceFile
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.CreateProjectViewModel
import com.example.zhishu.ui.viewmodel.PendingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val baselineOptions = listOf("完全不了解", "略有了解", "正在复习")
private val depthOptions = listOf("入门", "考试", "专业")
private val minuteOptions = listOf(15, 30, 60, 90)

/**
 * 创建项目（问题4：操作简化）。
 *
 * 原来是三步向导、必填项目名 + 主题、还要选故事类型。对小白负担太重，
 * 而且"给民法选什么小说风格"本身就是用户第六个问题吐槽的点。
 *
 * 现在压成一屏：只需要填「想学什么」，默认 AI 自备知识 + AI 自选讲法，
 * 直接就能创建并开讲。想细调的人展开「更多设置」即可。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateProjectScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: CreateProjectViewModel = rememberZhishuViewModel { CreateProjectViewModel(app) }
    val sources by vm.sources.collectAsState()
    val creating by vm.creating.collectAsState()
    val topic by vm.topic.collectAsState()
    val sourceMode by vm.sourceMode.collectAsState()

    var showMore by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) { readSourceFile(app, uri) }
                    .onSuccess { vm.addFileSource(it.title, it.fileName, it.kind, it.bytes) }
                    .onFailure { snackbarHostState.showSnackbar("读取文件失败：${it.message}") }
            }
        }
    }
    val canSubmit = topic.isNotBlank() && (sourceMode == SourceMode.MODEL || sources.isNotEmpty())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学点什么") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Button(
                onClick = {
                    scope.launch {
                        vm.createProject()
                            .onSuccess { onCreated(it) }
                            .onFailure { error = it.message }
                    }
                },
                enabled = canSubmit && !creating,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                if (creating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("创建并开始")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // 唯一的必填项
            OutlinedTextField(
                value = topic,
                onValueChange = { vm.topic.value = it },
                label = { Text("想学什么？") },
                placeholder = { Text("例如：民法、宏观经济学、机器学习入门") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // 知识来源模式（问题3）
            Text("知识从哪来", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            ModeRow(
                selected = sourceMode == SourceMode.MODEL,
                title = "AI 自备知识（推荐）",
                subtitle = "不用准备资料。适合民法这类自己也不好找资料的学科。",
                onClick = { vm.sourceMode.value = SourceMode.MODEL },
            )
            Spacer(Modifier.height(8.dp))
            ModeRow(
                selected = sourceMode == SourceMode.DOCUMENTS,
                title = "用我自己的资料",
                subtitle = "有指定教材或考试范围时选它，每句知识都能追溯到你给的资料。",
                onClick = { vm.sourceMode.value = SourceMode.DOCUMENTS },
            )

            // 只有选了"用我的资料"才需要添加资料区
            AnimatedVisibility(visible = sourceMode == SourceMode.DOCUMENTS) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showTextDialog = true }) {
                            Icon(Icons.Default.NoteAdd, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("文本")
                        }
                        OutlinedButton(onClick = { showUrlDialog = true }) {
                            Icon(Icons.Default.Link, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("网页")
                        }
                        OutlinedButton(onClick = { filePicker.launch(SOURCE_MIME_TYPES) }) {
                            Icon(Icons.Default.FileUpload, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("文件")
                        }
                    }
                    sources.forEachIndexed { index, source ->
                        SourceRow(source = source, onRemove = { vm.removeSource(index) })
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 更多设置默认折叠：小白不用管，进阶用户能调（问题4）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showMore = !showMore }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "更多设置（讲法、基础、时长）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(visible = showMore) {
                MoreSettings(vm)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTextDialog) {
        SourceTextDialog(
            onConfirm = { title, text -> vm.addTextSource(title, text); showTextDialog = false },
            onDismiss = { showTextDialog = false },
        )
    }
    if (showUrlDialog) {
        SourceUrlDialog(
            onConfirm = { url -> vm.addUrlSource(url); showUrlDialog = false },
            onDismiss = { showUrlDialog = false },
        )
    }
    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("创建失败") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun ModeRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
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
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun SourceRow(source: PendingSource, onRemove: () -> Unit) {
    Card(modifier = Modifier.padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(source.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                SourceKindChip(source.kind)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "移除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreSettings(vm: CreateProjectViewModel) {
    val baseline by vm.baseline.collectAsState()
    val depth by vm.depth.collectAsState()
    val genre by vm.genre.collectAsState()
    val dailyMinutes by vm.dailyMinutes.collectAsState()

    Column(Modifier.padding(top = 8.dp)) {
        // 讲法：第一项就是 AI 自由发挥（问题6）
        ChipGroup(
            label = "讲法",
            options = GENRE_OPTIONS,
            selected = genre,
            onSelect = { vm.genre.value = it },
        )
        Spacer(Modifier.height(12.dp))
        ChipGroup(
            label = "我的基础",
            options = baselineOptions,
            selected = baseline,
            onSelect = { vm.baseline.value = it },
        )
        Spacer(Modifier.height(12.dp))
        ChipGroup(
            label = "深度",
            options = depthOptions,
            selected = depth,
            onSelect = { vm.depth.value = it },
        )
        Spacer(Modifier.height(12.dp))
        ChipGroup(
            label = "每天读多久（分钟，也决定每章篇幅）",
            options = minuteOptions.map { it.toString() },
            selected = dailyMinutes.toString(),
            onSelect = { vm.dailyMinutes.value = it.toInt() },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option) },
            )
        }
    }
}
