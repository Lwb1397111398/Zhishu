package com.example.zhishu.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.zhishu.ZhishuApp
import com.example.zhishu.domain.model.ModelConfig
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.ui.common.SectionTitle
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenReview: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as Application
    val vm: SettingsViewModel = rememberZhishuViewModel(key = "settings") { SettingsViewModel(app) }
    val configs by vm.configs.collectAsState()
    val busy by vm.busy.collectAsState()
    val testResults by vm.testResults.collectAsState()
    val modelLists by vm.modelLists.collectAsState()
    val spacedReview by vm.spacedReviewEnabled.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val container = (app as ZhishuApp).container

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                vm.setBusy(true)
                val result = withContext(Dispatchers.IO) { container.transfer.export(app, uri) }
                vm.setBusy(false)
                snackbarHostState.showSnackbar(result.getOrElse { "导出失败：${it.message}" })
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                vm.setBusy(true)
                val result = withContext(Dispatchers.IO) { container.transfer.import(app, uri) }
                vm.setBusy(false)
                snackbarHostState.showSnackbar(result.getOrElse { "导入失败：${it.message}" })
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("我的") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("关于知述", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "把一门学科讲成一条能一路读下去的故事：主题 → 知识主干 → 讲述路线 → 分章生成 → 核验 → 阅读与检索。测验与复习是可选辅助。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
            item { SectionTitle("学习方式") }
            item {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("间隔复习", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "默认关闭。开启后，读过的知识点会按 1/3/7/14/30 天排进复习中心，首页也会显示「今日复习」。关闭时只管往下读。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = spacedReview,
                                onCheckedChange = { vm.setSpacedReviewEnabled(it) },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已有的复习记录和答题记录不会被删除，随时开关都能接着用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenReview) { Text("打开复习中心") }
                    }
                }
            }
            item { SectionTitle("模型配置") }
            item {
                Text(
                    "知识地图使用快速模型，故事大纲和正文使用故事模型，核验和测验使用核验模型。可分别配置不同服务商（需兼容 OpenAI 接口）。API Key 仅保存在本机加密存储中，不会随备份导出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ModelRole.entries, key = { it.name }) { role ->
                val config = configs.firstOrNull { it.role == role.name }
                ModelConfigCard(
                    role = role,
                    config = config,
                    busy = busy,
                    testResult = testResults[role],
                    modelListState = modelLists[role] ?: SettingsViewModel.ModelListState.Idle,
                    onSearchModels = { url, key -> vm.fetchModels(role, url, key) },
                    onSave = { updated, key -> vm.saveConfig(updated, key) { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } } },
                    onTest = { vm.testConnection(role) },
                )
            }
            item { SectionTitle("数据备份") }
            item {
                Card {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "备份包含项目、资料、知识地图、章节、测验与复习进度；不包含任何 API Key。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val name = "zhishu-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".zip"
                                    exportLauncher.launch(name)
                                },
                                enabled = !busy,
                            ) { Text("导出备份") }
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                                enabled = !busy,
                            ) {
                                Icon(Icons.Default.SettingsBackupRestore, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("导入备份")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/**
 * 模型选择（问题1）。
 *
 * 原来只有一个「模型名称」输入框，用户得自己知道并手打准确的模型 ID——
 * 打错一个字就是一次 404，而且报错里看不出是名字错了。
 * 现在填好地址与 Key 后点「搜索模型」，从服务商真实返回的列表里选。
 *
 * 仍保留手动填写：部分自建或中转接口不提供 /models。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    modelName: String,
    state: SettingsViewModel.ModelListState,
    manualModel: Boolean,
    canSearch: Boolean,
    onSearch: () -> Unit,
    onPick: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onToggleManual: (Boolean) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    // 搜到列表后自动弹出选择框，省掉一次点击
    val loaded = state as? SettingsViewModel.ModelListState.Loaded
    LaunchedEffect(loaded) {
        if (loaded != null) showPicker = true
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模型", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onToggleManual(!manualModel) }) {
                Text(if (manualModel) "改为搜索选择" else "手动填写")
            }
        }

        if (manualModel) {
            OutlinedTextField(
                value = modelName,
                onValueChange = onModelNameChange,
                label = { Text("模型名称") },
                supportingText = { Text("需与服务商文档中的模型 ID 完全一致") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }

        // 已选模型 + 搜索按钮
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        modelName.ifBlank { "尚未选择模型" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (modelName.isBlank()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (loaded != null) {
                        Text(
                            "搜到 ${loaded.models.size} 个可用模型，点右侧可重新选择",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (loaded != null) {
                    TextButton(onClick = { showPicker = true }) { Text("重新选择") }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onSearch,
                enabled = canSearch && state !is SettingsViewModel.ModelListState.Loading,
            ) {
                if (state is SettingsViewModel.ModelListState.Loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("搜索中…")
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("搜索可用模型")
                }
            }
        }
        if (!canSearch) {
            Spacer(Modifier.height(4.dp))
            Text(
                "请先填好 https 接口地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        (state as? SettingsViewModel.ModelListState.Failed)?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                it.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (showPicker && loaded != null) {
        ModelPickerDialog(
            models = loaded.models,
            current = modelName,
            onPick = {
                onPick(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** 模型列表可能有上百个（中转站尤其多），带一个关键字过滤框 */
@Composable
private fun ModelPickerDialog(
    models: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var filter by remember { mutableStateOf("") }
    val filtered = remember(filter, models) {
        if (filter.isBlank()) models else models.filter { it.contains(filter.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择模型") },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("筛选，如 deepseek、qwen") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (filtered.isEmpty()) {
                    Text("没有匹配的模型", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.height(320.dp)) {
                        items(filtered, key = { it }) { model ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(model) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = model == current, onClick = { onPick(model) })
                                Spacer(Modifier.width(8.dp))
                                Text(model, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ModelConfigCard(
    role: ModelRole,
    config: ModelConfig?,
    busy: Boolean,
    testResult: String?,
    modelListState: SettingsViewModel.ModelListState,
    onSearchModels: (String, String) -> Unit,
    onSave: (ModelConfig, String) -> Unit,
    onTest: () -> Unit,
) {
    val (title, description, defaultModel) = when (role) {
        ModelRole.FAST -> Triple("快速模型", "知识地图 / 资料整理", "gpt-4o-mini")
        ModelRole.STORY -> Triple("故事模型", "故事大纲 / 章节正文", "gpt-4o")
        ModelRole.VERIFY -> Triple("核验模型", "事实核验 / 测验生成", "gpt-4o-mini")
    }
    var baseUrl by remember(config) { mutableStateOf(config?.baseUrl ?: "https://api.openai.com/v1") }
    var modelName by remember(config) { mutableStateOf(config?.modelName ?: defaultModel) }
    var apiKey by remember(config) { mutableStateOf("") }
    var showKey by remember(config) { mutableStateOf(false) }
    var maxTokens by remember(config) {
        mutableStateOf((config?.maxTokens ?: ModelConfig.DEFAULT_MAX_TOKENS).toString())
    }
    var temperature by remember(config) { mutableStateOf((config?.temperature ?: 0.7f).toString()) }
    var supportsJson by remember(config) { mutableStateOf(config?.supportsJson ?: true) }
    // 手动填模型名的退路：有些自建/中转接口不提供 /models
    var manualModel by remember(config) { mutableStateOf(false) }
    val httpsOk = baseUrl.trim().startsWith("https://", ignoreCase = true)

    Card(modifier = Modifier.padding(vertical = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (config != null) "已配置" else "未配置",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (config != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("接口地址（Base URL，需 https）") },
                isError = baseUrl.isNotBlank() && !httpsOk,
                supportingText = {
                    if (baseUrl.isNotBlank() && !httpsOk) Text("为保护 API Key，只支持 https 接口")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            // API Key 默认以密码方式显示，可手动切换（B18）
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(if (config?.keyRef != null) "API Key（已保存，输入新值则替换）" else "API Key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showKey) "隐藏 API Key" else "显示 API Key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))

            // 填好地址与 Key 后直接搜模型，从列表里挑，不用自己打模型名（问题1）
            ModelPicker(
                modelName = modelName,
                state = modelListState,
                manualModel = manualModel,
                canSearch = !busy && httpsOk,
                onSearch = { onSearchModels(baseUrl, apiKey) },
                onPick = { modelName = it },
                onModelNameChange = { modelName = it },
                onToggleManual = { manualModel = it },
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    label = { Text("最大 Token") },
                    supportingText = { Text("默认 ${ModelConfig.DEFAULT_MAX_TOKENS}，太小会导致正文被截断") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text("温度") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("支持 JSON 输出", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(8.dp))
                Switch(checked = supportsJson, onCheckedChange = { supportsJson = it })
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onTest, enabled = !busy && config != null) {
                    Text("测试连接")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val parsedTokens = maxTokens.toIntOrNull()
                            ?.coerceIn(ModelConfig.MIN_MAX_TOKENS, ModelConfig.MAX_MAX_TOKENS)
                            ?: ModelConfig.DEFAULT_MAX_TOKENS
                        val parsedTemperature = temperature.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f
                        onSave(
                            config?.copy(
                                baseUrl = baseUrl.trim(),
                                modelName = modelName.trim(),
                                maxTokens = parsedTokens,
                                temperature = parsedTemperature,
                                supportsJson = supportsJson,
                            ) ?: ModelConfig(
                                role = role.name,
                                baseUrl = baseUrl.trim(),
                                modelName = modelName.trim(),
                                maxTokens = parsedTokens,
                                temperature = parsedTemperature,
                                supportsJson = supportsJson,
                            ),
                            apiKey,
                        )
                    },
                    enabled = !busy && httpsOk && modelName.isNotBlank(),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("保存")
                }
            }
            testResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("连接成功")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}
