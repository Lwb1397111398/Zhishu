package com.example.zhishu.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhishu.ui.viewmodel.GenerationUiState

/**
 * 生成状态条（问题1）。
 *
 * 之前入队后只弹一次 Snackbar 就再无消息，用户完全无法判断是在跑、失败了、还是压根没启动。
 * 这个条常驻在页面顶部：排队中、正在做什么、失败原因、以及失败后的下一步动作，全部可见。
 */
@Composable
fun GenerationBanner(
    state: GenerationUiState,
    onRetry: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    /** 取消整条生成链。排队中、运行中、等待重试都能点（第二阶段） */
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 错误横幅可手动收起（会话级）：记住已读的那条错误，同一条不再弹出；
    // 新原因的错误来了重新显示。没有这个出口，用户不想立刻重试时就只能
    // 让横幅一直挂着，或靠一次付费运行把它冲掉。
    var dismissedError by remember { mutableStateOf<String?>(null) }
    val showError = state.hasError && dismissedError != state.error
    AnimatedVisibility(visible = state.busy || showError) {
        if (state.hasError && showError) {
            ErrorBanner(
                message = state.error.orEmpty(),
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
                onDismiss = { dismissedError = state.error },
                modifier = modifier,
            )
        } else {
            RunningBanner(state = state, onCancel = onCancel, modifier = modifier)
        }
    }
}

@Composable
private fun RunningBanner(
    state: GenerationUiState,
    onCancel: (() -> Unit)?,
    modifier: Modifier,
) {
    // 百分比变化时平滑过渡，避免数字与条子突然跳一大段
    val animatedProgress by animateFloatAsState(
        targetValue = state.percent / 100f,
        label = "generationProgress",
    )
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                // 百分比只在总量已知时显示，不编造数字（问题2）
                if (state.determinate) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${state.percent}%",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (state.determinate) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "已完成 ${state.completed}/${state.total} 步",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                // 总量未知（刚排队、或阶段本身不可细分）时才用不确定进度条
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "生成在后台进行，可以离开这个页面。完成后章节状态会自动更新。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (onCancel != null) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onRetry: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    // 缺配置是最高频的失败原因，命中时把"去配置"按钮顶到前面。
    // 除了本应用自己的文案，也要覆盖服务商返回的鉴权类报错（403 license / 401 invalid key 等），
    // 否则用户看到一句读不懂的英文却连入口都找不到。
    val needsConfig = CONFIG_ERROR_HINTS.any { message.contains(it, ignoreCase = true) }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "生成没有完成",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (needsConfig && onOpenSettings != null) {
                    TextButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("去配置模型")
                    }
                }
                if (onRetry != null) {
                    // 不叫「重试」：它从第一个没完成的步骤接着跑（可能是重跑失败那步，
                    // 也可能是跳过它继续往下写），按实际行为称呼
                    TextButton(onClick = onRetry) { Text("继续生成") }
                }
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

/**
 * 命中任一关键词就认为「用户需要去改模型配置」，从而在失败横幅里露出「去配置模型」入口。
 * 既含本应用文案，也含服务商常见的鉴权类英文报错。
 */
private val CONFIG_ERROR_HINTS = listOf(
    "配置", "API Key", "接口地址", "模型名", "额度", "余额",
    "license", "unauthorized", "not authorized", "permission",
    "invalid", "expired", "quota",
)

/**
 * 待核实提示（问题3）。
 *
 * 无资料模式下知识由模型提供，模型可能记错、也可能过时。
 * 与其假装可靠，不如把「这几处你该自己去核对」明确摆出来——
 * 这是无资料模式能被放心使用的前提。
 */
/**
 * [container] / [content] 非空时用这组色而不是主题色——阅读器章末的这张卡落在
 * 纸面（含夜间）上，跟着主题走会在夜间纸上浮成一块白。阅读器传 palette，
 * 其它地方不传、维持原样。
 */
@Composable
fun UnverifiedClaimsCard(
    claims: List<String>,
    modifier: Modifier = Modifier,
    container: Color? = null,
    content: Color? = null,
) {
    if (claims.isEmpty()) return
    val bg = container ?: MaterialTheme.colorScheme.tertiaryContainer
    val fg = content ?: MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        color = bg,
        contentColor = fg,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "这 ${claims.size} 处建议你核对权威出处",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "本章知识由 AI 提供，以下内容可能因地区、年份或版本而不同。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            claims.forEach { claim ->
                Text("· $claim", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
