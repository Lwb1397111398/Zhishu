package com.example.zhishu.ui.common

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.SourceKind

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 不再 fillMaxSize：调用点给的是固定 height()，大字号下副标题会被裁掉。
    // 现在内容多高就多高，调用点用 heightIn(min=…) 保底一个像样的空态区
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun StatusChip(text: String, container: Color, content: Color) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun ProjectStatusChip(status: ProjectStatus) {
    val (label, container, content) = when (status) {
        ProjectStatus.DRAFT -> Triple("草稿", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        ProjectStatus.SOURCES -> Triple("资料已就绪", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ProjectStatus.MAP -> Triple("知识地图已生成", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ProjectStatus.OUTLINE -> Triple("大纲已就绪", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ProjectStatus.GENERATING -> Triple("生成中", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ProjectStatus.READY -> Triple("可阅读", Color(0xFF2E7D32), Color(0xFFE8F5E9))
    }
    StatusChip(label, container, content)
}

@Composable
fun ChapterStatusChip(status: ChapterStatus) {
    val (label, container, content) = when (status) {
        ChapterStatus.QUEUED -> Triple("排队中", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        ChapterStatus.RUNNING -> Triple("生成中", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ChapterStatus.DRAFT -> Triple("草稿", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ChapterStatus.VERIFYING -> Triple("核验中", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ChapterStatus.PUBLISHED -> Triple("已发布", Color(0xFF2E7D32), Color(0xFFE8F5E9))
        ChapterStatus.FAILED -> Triple("失败", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        ChapterStatus.NEEDS_REVIEW -> Triple("待复核", Color(0xFF9A6200), Color(0xFFFFF3D6))
        // 旧库里的状态：正文其实已通过核验，只是缺自测题，所以按「已发布」显示
        ChapterStatus.QUIZ_FAILED -> Triple("已发布", Color(0xFF2E7D32), Color(0xFFE8F5E9))
    }
    StatusChip(label, container, content)
}

@Composable
fun SourceKindChip(kind: SourceKind) {
    val label = when (kind) {
        SourceKind.TEXT -> "文本"
        SourceKind.MARKDOWN -> "Markdown"
        SourceKind.PDF -> "PDF"
        SourceKind.URL -> "网页"
    }
    StatusChip(label, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 固定宽在大字号下会把值挤到下一列错位；给个上限并允许标签自身换行
            modifier = Modifier.widthIn(max = 96.dp),
        )
        Spacer(Modifier.width(8.dp))
        // 值是用户/模型给的长文本：吃剩余宽度、允许换行，绝不撑爆行
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
