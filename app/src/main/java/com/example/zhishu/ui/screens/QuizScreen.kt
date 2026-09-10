package com.example.zhishu.ui.screens

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.QuizType
import com.example.zhishu.ui.common.StatusChip
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.QuizViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    chapterId: String,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: QuizViewModel = rememberZhishuViewModel(key = "quiz_$chapterId") {
        QuizViewModel(app, chapterId)
    }
    val chapter by vm.chapter.collectAsStateWithLifecycle()
    val quizzes by vm.quizzes.collectAsStateWithLifecycle()
    val selections by vm.selections.collectAsStateWithLifecycle()
    // 订阅答题状态，提交后结果与按钮可靠重组（N1）
    val answers by vm.answers.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("章节测验") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (quizzes.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(80.dp))
                Text("本章还没有测验题", style = MaterialTheme.typography.titleMedium)
                Text(
                    "生成章节时会自动附带测验；如果章节状态是「测验待补」，可回到项目详情重试生成。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        chapter?.let { "《${it.title}》章节测验 · 共 ${quizzes.size} 题" } ?: "共 ${quizzes.size} 题",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(quizzes, key = { it.id }) { quiz ->
                    QuizCard(
                        quiz = quiz,
                        selected = selections[quiz.id],
                        answered = answers.containsKey(quiz.id),
                        correct = answers[quiz.id],
                        onSelect = { vm.selectOption(quiz.id, it) },
                        onSubmit = { vm.submit(quiz) },
                        onSelfAssess = { recalled -> vm.submit(quiz, recalledCorrectly = recalled) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuizCard(
    quiz: Quiz,
    selected: String?,
    answered: Boolean,
    correct: Boolean?,
    onSelect: (String) -> Unit,
    onSubmit: () -> Unit,
    onSelfAssess: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 ${quiz.order + 1} 题",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                QuizTypeChip(quiz.type)
                if (answered) {
                    Spacer(Modifier.width(8.dp))
                    if (correct == true) {
                        StatusChip("答对了", Color(0xFF2E7D32), Color(0xFFE8F5E9))
                    } else {
                        StatusChip("答错了", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(quiz.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            when (quiz.type) {
                QuizType.RECALL -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "先在脑中回想答案，再对照正确答案自评（B9：由你判断是否答对）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!answered) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onSelfAssess(true) }) { Text("我想对了") }
                            OutlinedButton(onClick = { onSelfAssess(false) }) { Text("没想起来") }
                        }
                    }
                }

                else -> {
                    Spacer(Modifier.height(8.dp))
                    quiz.options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !answered) { onSelect(option) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected == option,
                                enabled = !answered,
                                onClick = { if (!answered) onSelect(option) },
                            )
                            Text(option, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (!answered) {
                        Button(
                            onClick = onSubmit,
                            enabled = selected != null,
                            modifier = Modifier.semantics { testTag = "submit_${quiz.id}" },
                        ) { Text("提交答案") }
                    }
                }
            }

            if (answered) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "本题已作答，答案锁定不能修改（同一题只计分一次）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "正确答案：${quiz.answer}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (quiz.explanation.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                quiz.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizTypeChip(type: QuizType) {
    val label = when (type) {
        QuizType.RECALL -> "回忆题"
        QuizType.TRUE_FALSE -> "判断题"
        QuizType.CASE -> "案例题"
    }
    StatusChip(label, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
}
