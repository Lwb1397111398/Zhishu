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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.zhishu.ZhishuApp
import com.example.zhishu.ai.GenerationLog
import com.example.zhishu.domain.model.ApiProfile
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
    val profiles by vm.profiles.collectAsState()
    val assignments by vm.assignments.collectAsState()
    val editingProfileId by vm.editingProfileId.collectAsState()
    val busy by vm.busy.collectAsState()
    val testResults by vm.testResults.collectAsState()
    val modelLists by vm.modelLists.collectAsState()
    val spacedReview by vm.spacedReviewEnabled.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val container = (app as ZhishuApp).container
    // 弹窗可见性跟着 ViewModel 走：它不能随卡片滑出屏幕被回收，
    // 否则滑回来时会用「搜索结果还在」这件事再弹一次（用户反馈的滑动自动弹窗）
    val pickerProfiles by vm.pickerProfiles.collectAsState()
    // 删除确认 / 三档分配选择这两个弹窗是页面级一次性事件，页面局部状态即可
    var deleteTarget by remember { mutableStateOf<ApiProfile?>(null) }
    var assignmentTarget by remember { mutableStateOf<AssignmentTarget?>(null) }

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
            item { SectionTitle("API 配置方案") }
            item {
                Text(
                    "把常用的接口配置（服务商地址 + 模型 + API Key）各存成一套方案，" +
                        "再到下方「各环节使用的方案」里为三个环节挑选。想试不同的 LLM，" +
                        "新增一套方案、换一下分配即可，不用重填地址和 Key。" +
                        "API Key 仅保存在本机加密存储中，不会随备份导出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editingProfileId == SettingsViewModel.NEW_PROFILE_KEY) {
                item(key = "draft-edit") {
                    ProfileEditCard(
                        profile = null,
                        busy = busy,
                        testResult = testResults[SettingsViewModel.NEW_PROFILE_KEY],
                        modelListState = modelLists[SettingsViewModel.NEW_PROFILE_KEY]
                            ?: SettingsViewModel.ModelListState.Idle,
                        showPicker = SettingsViewModel.NEW_PROFILE_KEY in pickerProfiles,
                        onSearchModels = { url, key ->
                            vm.fetchModels(SettingsViewModel.NEW_PROFILE_KEY, url, key)
                        },
                        onOpenPicker = { vm.openPicker(SettingsViewModel.NEW_PROFILE_KEY) },
                        onDismissPicker = { vm.closePicker(SettingsViewModel.NEW_PROFILE_KEY) },
                        onSave = { updated, key ->
                            vm.saveProfile(updated, key) { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                        onTest = { vm.testProfile(SettingsViewModel.NEW_PROFILE_KEY) },
                        onCancel = { vm.cancelEdit() },
                    )
                }
            }
            items(profiles, key = { it.id }) { profile ->
                if (editingProfileId == profile.id) {
                    ProfileEditCard(
                        profile = profile,
                        busy = busy,
                        testResult = testResults[profile.id],
                        modelListState = modelLists[profile.id] ?: SettingsViewModel.ModelListState.Idle,
                        showPicker = profile.id in pickerProfiles,
                        onSearchModels = { url, key -> vm.fetchModels(profile.id, url, key) },
                        onOpenPicker = { vm.openPicker(profile.id) },
                        onDismissPicker = { vm.closePicker(profile.id) },
                        onSave = { updated, key ->
                            vm.saveProfile(updated, key) { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                        onTest = { vm.testProfile(profile.id) },
                        onCancel = { vm.cancelEdit() },
                    )
                } else {
                    ProfileSummaryCard(
                        profile = profile,
                        usedBy = ModelRole.entries.filter { assignments[it] == profile.id },
                        busy = busy,
                        testResult = testResults[profile.id],
                        onTest = { vm.testProfile(profile.id) },
                        onEdit = { vm.startEdit(profile.id) },
                        onDuplicate = { vm.duplicateProfile(profile) },
                        onDelete = { deleteTarget = profile },
                    )
                }
            }
            if (editingProfileId == null) {
                item {
                    OutlinedButton(
                        onClick = { vm.startEdit(null) },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("新增配置方案")
                    }
                }
            }

            item { SectionTitle("各环节使用的方案") }
            item {
                Text(
                    "知识地图用「知识梳理」，故事大纲和正文用「正文写作」，核验和测验用「核验出题」。三个环节可以各用一套方案，也可以一键全用同一套。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(ModelRole.entries, key = { "role-${it.name}" }) { role ->
                val assigned = profiles.firstOrNull { it.id == assignments[role] }
                RoleAssignmentRow(
                    role = role,
                    assignedName = assigned?.name,
                    onPick = { assignmentTarget = AssignmentTarget.Single(role) },
                )
            }
            item {
                OutlinedButton(
                    onClick = { assignmentTarget = AssignmentTarget.All },
                    enabled = profiles.isNotEmpty(),
                ) {
                    Text("一键全部使用同一方案…")
                }
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
            item { SectionTitle("生成诊断") }
            item { GenerationDiagnosticsCard() }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    deleteTarget?.let { target ->
        val usedBy = ModelRole.entries.filter { assignments[it] == target.id }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除方案「${target.name.ifBlank { "未命名方案" }}」？") },
            text = {
                Text(
                    if (usedBy.isEmpty()) {
                        "删除后无法恢复。"
                    } else {
                        "该方案正被「${usedBy.joinToString("、") { it.displayName }}」使用，" +
                            "删除后这些环节会变为未配置，需要重新选择方案。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteProfile(target) { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                        deleteTarget = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    assignmentTarget?.let { target ->
        ProfilePickerDialog(
            title = when (target) {
                is AssignmentTarget.Single -> "为「${target.role.displayName}」选择方案"
                AssignmentTarget.All -> "选择方案，应用到全部三个环节"
            },
            profiles = profiles,
            currentId = when (target) {
                is AssignmentTarget.Single -> assignments[target.role]
                AssignmentTarget.All -> null
            },
            onPick = { profileId ->
                when (target) {
                    is AssignmentTarget.Single -> vm.assignRole(target.role, profileId)
                    AssignmentTarget.All -> vm.assignAll(profileId)
                }
                assignmentTarget = null
            },
            onDismiss = { assignmentTarget = null },
        )
    }
}

/**
 * 生成诊断（用户反馈：「讲述路线非常卡，完全没有办法生成，不知道什么情况」）。
 *
 * 生成卡住时界面只有一句「已开始」，用户唯一能做的事就是等和猜。这里把每次模型
 * 调用的阶段、输入长度、请求的输出上限、耗时和错误类别摊开——一眼能分清
 * 「在重试网络」「输入太长被拒」「输出被截断」还是「校验没过」，这三种下一步动作完全不同。
 *
 * 日志里没有 API Key、没有资料原文、没有完整响应（见 GenerationLog）。
 */
@Composable
private fun GenerationDiagnosticsCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    // reloadKey 变化时重读文件：日志是生成过程中追加的，进页面时的快照会过期。
    // 读文件放到 IO 线程：原来写在 remember { GenerationLog.read(...) } 里，
    // 等于每次组合/刷新都在主线程做磁盘 I/O，日志几百行时会掉帧（同族 Compose bug #9）。
    var reloadKey by remember { mutableStateOf(0) }
    var log by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reloadKey) {
        log = withContext(Dispatchers.IO) { GenerationLog.read(context) }
    }

    Card {
        Column(Modifier.padding(14.dp)) {
            Text(
                "生成卡住或失败时看这里：每一行是一次模型调用（阶段、输入长度、请求的输出上限、" +
                    "耗时、错误类别）。不含 API Key、资料原文和完整响应。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            if (log.isNullOrBlank()) {
                Text(
                    "还没有生成记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 只显示最近若干行：整份日志可能几百行，界面上翻不完，要全文就用「复制」
                val recent = log!!.trim().lines().takeLast(DIAGNOSTICS_PREVIEW_LINES)
                Text(
                    recent.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // 人话翻译层（B2）：把「Network(超时)」「Decode」这类类别码翻成下一步能做什么，
                // 小白用户看不懂原始单行。原文保留在上面的代码块里，这里只给结论。
                Text(
                    humanReadableDiagnostics(recent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(log.orEmpty())) },
                    enabled = !log.isNullOrBlank(),
                ) { Text("复制全部") }
                OutlinedButton(onClick = { reloadKey++ }) { Text("刷新") }
                OutlinedButton(
                    onClick = {
                        // 清空也是磁盘操作，同样不在主线程做
                        scope.launch {
                            withContext(Dispatchers.IO) { GenerationLog.clear(context) }
                            reloadKey++
                        }
                    },
                    enabled = !log.isNullOrBlank(),
                ) { Text("清空") }
            }
        }
    }
}

/** 诊断卡片里直接展示多少行。要全文用「复制全部」 */
private const val DIAGNOSTICS_PREVIEW_LINES = 12

/**
 * 诊断日志人话层（B2）：把原始单行里的错误类别翻译成下一步能做什么。
 *
 * 原始日志形如 `MAP_SKELETON#1 ok=false inTok~1234 maxOut=8000 187300ms Network(超时)`——
 * 记的是类别与耗时，但小白用户看不出「Network」和「超时」意味着什么。这里只做
 * 最后一条失败事件的解读，原文仍保留在代码块里供复制。
 */
private fun humanReadableDiagnostics(lines: List<String>): String {
    val failed = lines.lastOrNull { it.contains("ok=false") } ?: return "全部成功。如果还是卡住，可能是正在生成中。"
    val category = Regex("""\b(Auth|RateLimited|Server|Network|InvalidRequest|Decode|Invalid|Truncated|BudgetExhausted)\b""")
        .find(failed)?.groupValues?.get(1)
    val advice = when (category) {
        "Network" -> {
            val timedOut = failed.contains("超时")
            if (timedOut) {
                "服务器等太久没响应（不是你手机断网）。免费通道高峰期很常见：稍等重试；" +
                    "反复出现就换更快的模型档位，或换服务商。"
            } else {
                "网络没连上服务商。检查手机网络，或确认接口地址填的是 https:// 开头的完整地址。"
            }
        }
        "Auth" -> "接口地址 / 模型名 / API Key 对不上。到「我的」页重新核对这三样。"
        "RateLimited" -> "请求被限流，通常是额度或并发超了。稍等一会儿再试，或换小一点的模型档位。"
        "Server" -> "服务商暂时不可用。稍等几分钟再试。"
        "InvalidRequest" -> "请求参数不被接受：多半是模型名写错、或这个模型不支持某些参数。到「我的」页检查模型名。"
        "Truncated" -> "模型回答被输出上限截断，内容不完整。已自动用更低的节点要求重试，还不行就换输出上限更大的模型。"
        "Decode" -> "模型返回的内容格式不对（不是期望的结构）。已自动重试，多次失败建议换一个模型试试。"
        "Invalid" -> "模型生成的内容没通过业务校验（例如缺知识点、引用不存在）。已自动重试。"
        "BudgetExhausted" -> "一次生成的调用次数用完了。多半是模型经常失败重试，检查前面的错误类别更实在。"
        else -> "看上面最近的失败行，可以从「复制全部」把日志发给懂行的人。"
    }
    return "说明：$advice"
}

/**
 * 模型选择（问题1）。
 *
 * 原来只有一个「模型名称」输入框，用户得自己知道并手打准确的模型 ID——
 * 打错一个字就是一次 404，而且报错里看不出是名字错了。
 * 现在填好地址与 Key 后点「搜索模型」，从服务商真实返回的列表里选。
 *
 * 仍保留手动填写：部分自建或中转接口不提供 /models。
 *
 * **弹窗开关由调用方持有**（[showPicker] / [onDismissPicker]），本组件自己不记。
 * 早先这里用 `remember { mutableStateOf(false) }` + `LaunchedEffect(loaded)` 自动弹窗，
 * 结果是：搜索结果 [state] 存活在 ViewModel 里永不消失，而局部开关随卡片滑出屏幕被
 * 回收——滑回来重新组合时又自动弹一次。可见性必须有一个不随重组生灭的持有者。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    modelName: String,
    state: SettingsViewModel.ModelListState,
    manualModel: Boolean,
    canSearch: Boolean,
    showPicker: Boolean,
    onSearch: () -> Unit,
    onPick: (String) -> Unit,
    onModelNameChange: (String) -> Unit,
    onToggleManual: (Boolean) -> Unit,
    onOpenPicker: () -> Unit,
    onDismissPicker: () -> Unit,
) {
    val loaded = state as? SettingsViewModel.ModelListState.Loaded

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
                    TextButton(onClick = onOpenPicker) { Text("重新选择") }
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
                onDismissPicker()
            },
            onDismiss = onDismissPicker,
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

/** 三档分配弹窗的目标：给某一档选，或全部三档一起选 */
private sealed interface AssignmentTarget {
    data class Single(val role: ModelRole) : AssignmentTarget
    data object All : AssignmentTarget
}

/**
 * 方案摘要卡：方案库里的常态展示。
 * 只显示名称、地址、模型与「哪些环节在用」，编辑进入独立的编辑卡，避免一页全是输入框。
 */
@Composable
private fun ProfileSummaryCard(
    profile: ApiProfile,
    usedBy: List<ModelRole>,
    busy: Boolean,
    testResult: String?,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 方案名用户可自填，「使用中」列出所有角色：两段都可变长，
                // 都得带 weight——无 weight 的那个会先量走全部宽度，把另一个挤到 0
                Text(
                    profile.name.ifBlank { "未命名方案" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.4f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                if (usedBy.isNotEmpty()) {
                    Text(
                        "使用中：${usedBy.joinToString("、") { it.displayName }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                profile.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "模型：${profile.modelName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(onClick = onTest, enabled = !busy) { Text("测试连接") }
                TextButton(onClick = onEdit, enabled = !busy) { Text("编辑") }
                TextButton(onClick = onDuplicate, enabled = !busy) { Text("复制") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 方案编辑卡：新增（[profile] 为 null）或编辑一套配置方案。
 * 字段与旧的角色卡片一致（地址 / Key / 模型搜索 / 参数），只多一个「方案名称」。
 * 纯 UI 开关（显示明文、手动填模型名）不随 profile 重置；业务字段随 profile 重置，
 * 保存成功后退出编辑态、失败时输入保留——语义与旧卡片一致。
 */
@Composable
private fun ProfileEditCard(
    profile: ApiProfile?,
    busy: Boolean,
    testResult: String?,
    modelListState: SettingsViewModel.ModelListState,
    showPicker: Boolean,
    onSearchModels: (String, String) -> Unit,
    onOpenPicker: () -> Unit,
    onDismissPicker: () -> Unit,
    onSave: (ApiProfile, String) -> Unit,
    onTest: () -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(profile) { mutableStateOf(profile?.name ?: "") }
    var baseUrl by remember(profile) { mutableStateOf(profile?.baseUrl ?: "https://api.openai.com/v1") }
    var modelName by remember(profile) { mutableStateOf(profile?.modelName ?: "gpt-4o-mini") }
    // 保存成功后 profile 变化、这里清空是符合预期的（label 已提示「输入新值则替换」）；
    // 保存失败时 ViewModel 不退出编辑态，输入得以保留。
    var apiKey by remember(profile) { mutableStateOf("") }
    // 纯 UI 开关与 profile 无关，不能跟着 profile 重置——否则保存一次，
    // 「显示明文」被强制关掉、手动填写模式被踢回搜索选择
    var showKey by remember { mutableStateOf(false) }
    var maxTokens by remember(profile) {
        mutableStateOf((profile?.maxTokens ?: ApiProfile.DEFAULT_MAX_TOKENS).toString())
    }
    var temperature by remember(profile) { mutableStateOf((profile?.temperature ?: 0.7f).toString()) }
    var supportsJson by remember(profile) { mutableStateOf(profile?.supportsJson ?: true) }
    // 手动填模型名的退路：有些自建/中转接口不提供 /models。同样是纯 UI 状态
    var manualModel by remember { mutableStateOf(false) }
    val httpsOk = baseUrl.trim().startsWith("https://", ignoreCase = true)

    Card(modifier = Modifier.padding(vertical = 8.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (profile == null) "新增配置方案" else "编辑「${profile.name.ifBlank { "未命名方案" }}」",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                if (profile?.keyRef != null) {
                    Text(
                        "API Key 已保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("方案名称（如：DeepSeek 官方）") },
                supportingText = { Text("留空保存时会自动编号") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
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
                label = { Text(if (profile?.keyRef != null) "API Key（已保存，输入新值则替换）" else "API Key") },
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
                showPicker = showPicker,
                onSearch = { onSearchModels(baseUrl, apiKey) },
                onPick = { modelName = it },
                onModelNameChange = { modelName = it },
                onToggleManual = { manualModel = it },
                onOpenPicker = onOpenPicker,
                onDismissPicker = onDismissPicker,
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    label = { Text("最大 Token") },
                    supportingText = { Text("默认 ${ApiProfile.DEFAULT_MAX_TOKENS}，太小会导致正文被截断") },
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
                OutlinedButton(onClick = onTest, enabled = !busy && profile != null) {
                    Text("测试连接")
                }
                if (profile == null) {
                    Text(
                        "保存后才能测试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
                Button(
                    onClick = {
                        val parsedTokens = maxTokens.toIntOrNull()
                            ?.coerceIn(ApiProfile.MIN_MAX_TOKENS, ApiProfile.MAX_MAX_TOKENS)
                            ?: ApiProfile.DEFAULT_MAX_TOKENS
                        val parsedTemperature = temperature.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f
                        onSave(
                            (profile ?: ApiProfile()).copy(
                                name = name.trim(),
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

/** 三档分配行：环节名 + 当前使用的方案 + 「选择」按钮（弹 [ProfilePickerDialog]） */
@Composable
private fun RoleAssignmentRow(
    role: ModelRole,
    assignedName: String?,
    onPick: () -> Unit,
) {
    val description = when (role) {
        ModelRole.FAST -> "知识地图 / 资料整理"
        ModelRole.STORY -> "故事大纲 / 章节正文"
        ModelRole.VERIFY -> "事实核验 / 测验生成"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(role.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                assignedName ?: "未配置",
                style = MaterialTheme.typography.bodyMedium,
                color = if (assignedName != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.2f),
            )
            TextButton(onClick = onPick) { Text("选择") }
        }
    }
}

/** 三档分配的选择弹窗：列出全部方案 + 「未配置」 */
@Composable
private fun ProfilePickerDialog(
    title: String,
    profiles: List<ApiProfile>,
    currentId: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (profiles.isEmpty()) {
                Text("还没有任何配置方案，先在上方新增一套。")
            } else {
                LazyColumn(Modifier.height(320.dp)) {
                    item(key = "none") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(null) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = currentId == null, onClick = { onPick(null) })
                            Spacer(Modifier.width(8.dp))
                            Text("未配置", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(profiles, key = { it.id }) { profile ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(profile.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = currentId == profile.id, onClick = { onPick(profile.id) })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    profile.name.ifBlank { "未命名方案" },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    profile.modelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
