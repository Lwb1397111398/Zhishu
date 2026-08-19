package com.example.zhishu.ui.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.zhishu.domain.model.SourceKind

/**
 * 资料录入相关的共享 UI 与文件读取逻辑。
 * 创建项目页与项目详情页共用同一份实现，避免行为分叉（B21）。
 */

/** 可导入的 MIME 类型，两个入口保持一致 */
val SOURCE_MIME_TYPES = arrayOf("text/*", "application/pdf", "text/markdown", "text/plain")

/** 单个资料文件的读取上限，防止超大文件直接占满内存 */
const val MAX_SOURCE_FILE_BYTES = 32 * 1024 * 1024

data class SourceFileInfo(
    val title: String,
    val fileName: String,
    val kind: SourceKind,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourceFileInfo) return false
        return title == other.title &&
            fileName == other.fileName &&
            kind == other.kind &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/** 在 IO 线程调用：读取选中文件的名称、类型与内容 */
fun readSourceFile(context: Context, uri: Uri): Result<SourceFileInfo> = runCatching {
    val resolver = context.contentResolver
    var displayName = uri.lastPathSegment ?: "资料"
    var declaredSize = -1L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) cursor.getString(nameIndex)?.let { displayName = it }
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
        }
    }
    if (declaredSize > MAX_SOURCE_FILE_BYTES) {
        throw IllegalArgumentException("文件超过 ${MAX_SOURCE_FILE_BYTES / 1024 / 1024} MB，请拆分后再导入")
    }
    val bytes = resolver.openInputStream(uri)?.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_SOURCE_FILE_BYTES) {
                throw IllegalArgumentException("文件超过 ${MAX_SOURCE_FILE_BYTES / 1024 / 1024} MB，请拆分后再导入")
            }
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    } ?: throw IllegalStateException("无法读取所选文件")

    val mime = resolver.getType(uri).orEmpty()
    val kind = when {
        mime.contains("pdf") || displayName.endsWith(".pdf", ignoreCase = true) -> SourceKind.PDF
        mime.contains("markdown") || displayName.endsWith(".md", ignoreCase = true) -> SourceKind.MARKDOWN
        else -> SourceKind.TEXT
    }
    SourceFileInfo(
        title = displayName.substringBeforeLast('.').ifBlank { displayName },
        fileName = displayName,
        kind = kind,
        bytes = bytes,
    )
}

@Composable
fun SourceTextDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴文本资料") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("资料标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("正文内容") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, text) },
                enabled = title.isNotBlank() && text.isNotBlank(),
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun SourceUrlDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf("https://") }
    val valid = url.startsWith("https://", ignoreCase = true) && url.length > "https://".length
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加网页资料") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网页地址") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    isError = url.isNotBlank() && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text("为保护隐私只支持 https 地址。")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url.trim()) }, enabled = valid) { Text("抓取") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
