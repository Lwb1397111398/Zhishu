package com.example.zhishu.ui.screens

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.ui.common.InfoRow
import com.example.zhishu.ui.common.UnverifiedClaimsCard
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.BlockSource
import com.example.zhishu.ui.viewmodel.BlockSourceSummary
import com.example.zhishu.ui.viewmodel.ReaderViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** LazyColumn 里正文块前面的固定项数（章节标题头），块索引 = 列表索引 - 这个数 */
private const val HEADER_ITEMS = 1

/** 「本章已更新」提示至少停留这么久再记账，避免用户还没看到就消失 */
private const val NOTICE_LINGER_MILLIS = 4000L

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(
    chapterId: String,
    targetBlockId: String? = null,
    onBack: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenQuiz: (String) -> Unit,
    onOpenNodeInMap: (String, String) -> Unit = { _, _ -> },
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ReaderViewModel = rememberZhishuViewModel(key = "reader_$chapterId") {
        ReaderViewModel(app, chapterId)
    }
    val chapter by vm.chapter.collectAsState()
    val project by vm.project.collectAsState()
    val blocks by vm.blocks.collectAsState()
    val nodes by vm.nodes.collectAsState()
    val dense by vm.denseMode.collectAsState()
    val prevChapterId by vm.prevChapterId.collectAsState()
    val nextChapterId by vm.nextChapterId.collectAsState()
    val savedAnchor by vm.savedAnchor.collectAsState()
    val quizCount by vm.quizCount.collectAsState()
    val bodyUpdatedNotice by vm.bodyUpdatedNotice.collectAsState()
    val blockSources by vm.blockSources.collectAsState()

    var selectedNode by remember { mutableStateOf<KnowledgeNode?>(null) }
    var showOutline by remember { mutableStateOf(false) }
    var highlightBlockId by remember { mutableStateOf(targetBlockId) }
    var restored by remember(chapterId) { mutableStateOf(false) }
    // 打开哪一块的来源面板（4A）。默认不展开，阅读体验优先
    var sourceBlockId by remember(chapterId) { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 安静模式只藏讲解/引文/类比这些"旁注"，但保留 KEY_POINT——
    // 那些重述句本身就是推演的骨架，藏掉会让主线断掉（问题2）
    val visibleBlocks = if (dense) {
        blocks
    } else {
        blocks.filter { it.blockType == BlockType.STORY || it.blockType == BlockType.KEY_POINT }
    }

    // 搜索命中的块若是安静模式下折叠掉的旁注，自动切到密集模式，否则跳过去看不到东西
    LaunchedEffect(targetBlockId, blocks) {
        val target = targetBlockId ?: return@LaunchedEffect
        val block = blocks.firstOrNull { it.id == target } ?: return@LaunchedEffect
        val hidden = block.blockType != BlockType.STORY && block.blockType != BlockType.KEY_POINT
        if (hidden && !vm.denseMode.value) vm.denseMode.value = true
    }

    // 恢复位置：优先跳到指定块（搜索/知识地图跳转），否则回到上次读到的块
    LaunchedEffect(visibleBlocks, savedAnchor, targetBlockId) {
        if (restored || visibleBlocks.isEmpty()) return@LaunchedEffect
        val anchor = savedAnchor ?: return@LaunchedEffect // 进度还没读出来，先别动
        val targetId = targetBlockId ?: anchor.blockId
        if (targetId == null) {
            restored = true
            return@LaunchedEffect
        }
        val index = visibleBlocks.indexOfFirst { it.id == targetId }
        if (index < 0) {
            // 块还没出现在当前可见列表里（等待切换密集模式）；确实找不到才算恢复完成
            if (blocks.none { it.id == targetId }) restored = true
            return@LaunchedEffect
        }
        listState.scrollToItem(
            index = index + HEADER_ITEMS,
            scrollOffset = if (targetBlockId != null) 0 else anchor.offset,
        )
        restored = true
    }

    // 高亮只闪一下，避免留在页面上干扰阅读
    LaunchedEffect(highlightBlockId) {
        if (highlightBlockId != null) {
            delay(2600)
            highlightBlockId = null
        }
    }

    // 「本章已更新」看到了就记账，下次进来不再提（3D）。
    // 等正文真的渲染出来再消费，否则页面还空着就已经把提示销掉了
    LaunchedEffect(bodyUpdatedNotice, blocks) {
        if (bodyUpdatedNotice && blocks.isNotEmpty()) {
            delay(NOTICE_LINGER_MILLIS)
            vm.consumeBodyUpdatedNotice()
        }
    }

    // 进度按块保存：停止滚动 1 秒后写库。恢复完成前不写，否则会把位置覆盖成开头（B10）
    LaunchedEffect(listState, visibleBlocks, restored) {
        if (!restored || visibleBlocks.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(1000)
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                val blockIndex = index - HEADER_ITEMS
                val block = visibleBlocks.getOrNull(blockIndex) ?: visibleBlocks.firstOrNull()
                vm.saveProgress(block?.id, if (blockIndex < 0) 0 else offset)
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chapter?.title ?: "阅读", style = MaterialTheme.typography.titleMedium)
                        project?.let { Text(it.title, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { showOutline = true }) {
                        Icon(Icons.Default.List, contentDescription = "本章导览")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (dense) "密集" else "安静",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Switch(
                            checked = dense,
                            onCheckedChange = { vm.denseMode.value = it },
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 主操作是「下一章」，不是「去做测验」——读下去才是这个 App 的主线
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { prevChapterId?.let(onOpenChapter) },
                    enabled = prevChapterId != null,
                ) { Text("上一章") }
                TextButton(onClick = { showOutline = true }) { Text("本章导览") }
                Button(
                    onClick = { nextChapterId?.let(onOpenChapter) },
                    enabled = nextChapterId != null,
                ) { Text(if (nextChapterId != null) "下一章" else "已是最后一章") }
            }
        },
    ) { padding ->
        if (blocks.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(80.dp))
                Text(
                    "本章正文尚未生成",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "回到项目详情，在「故事大纲」中生成该章节。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // LazyColumn + 稳定 key：长章节不再一次性组合全文，块级定位与进度也才站得住
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "__header__") {
                    Column {
                        Text(
                            chapter?.title ?: "",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "第 ${chapter?.order ?: 1} 章",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 正文换过版本：说清楚为什么回到了章首（3D）。
                        // 只说一次，中性底色，不做成警告——正文更新是好事，不是出错
                        if (bodyUpdatedNotice) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "本章正文已更新，从章首开始阅读。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                }
                items(visibleBlocks, key = { it.id }) { block ->
                    BlockContent(
                        block = block,
                        dense = dense,
                        nodes = nodes,
                        highlighted = block.id == highlightBlockId,
                        sourceSummary = blockSources[block.id],
                        onOpenNode = { selectedNode = it },
                        onOpenSources = { sourceBlockId = block.id },
                    )
                }
                item(key = "__footer__") {
                    ChapterFooter(
                        chapter = chapter,
                        quizCount = quizCount,
                        hasNext = nextChapterId != null,
                        onSelfTest = { onOpenQuiz(chapterId) },
                        onNext = { nextChapterId?.let(onOpenChapter) },
                    )
                }
            }
        }
    }

    if (showOutline) {
        ModalBottomSheet(onDismissRequest = { showOutline = false }) {
            ChapterOutlineSheet(
                chapter = chapter,
                blocks = blocks,
                visibleBlocks = visibleBlocks,
                nodes = nodes,
                onJumpBlock = { blockId ->
                    val index = visibleBlocks.indexOfFirst { it.id == blockId }
                    if (index >= 0) {
                        highlightBlockId = blockId
                        showOutline = false
                        scope.launch { listState.animateScrollToItem(index + HEADER_ITEMS) }
                    }
                },
                onOpenNode = {
                    showOutline = false
                    selectedNode = it
                },
            )
        }
    }

    selectedNode?.let { node ->
        ModalBottomSheet(onDismissRequest = { selectedNode = null }) {
            ReaderNodeSheet(
                node = node,
                onOpenInMap = {
                    val projectId = project?.id
                    selectedNode = null
                    if (projectId != null) onOpenNodeInMap(projectId, node.id)
                },
            )
        }
    }

    // 来源面板（4A）：这一段从哪来，能不能核对，要不要核对
    sourceBlockId?.let { blockId ->
        val summary = blockSources[blockId]
        ModalBottomSheet(onDismissRequest = { sourceBlockId = null }) {
            BlockSourceSheet(summary = summary)
        }
    }
}

/**
 * 一个正文块的来源明细（4A）。
 *
 * 三种情况说三种话，不混在一起：
 * - 可定位的引用：资料名、页码/章节、版本与法域、原文摘录；
 * - 未确认的引用：模型给的标识原样列出，并说明我们没能对上；
 * - 一条引用都没有：区分「AI 自备知识」和「没标来源」——前者是设计如此，后者是缺口。
 */
@Composable
private fun BlockSourceSheet(summary: BlockSourceSummary?) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text("这一段从哪来", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        if (summary == null || !summary.hasAnything) {
            Text(
                if (summary?.modelKnowledge == true) {
                    "本项目采用「AI 自备知识」模式，这一段没有用户资料作为出处。" +
                        "章末列出的待核实内容值得你自己对一遍权威出处。"
                } else {
                    "这一段没有标注来源。它可能是故事情节，也可能是没能对上资料的讲解——" +
                        "涉及具体规则时建议自己核对一次。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        summary.sources.forEach { source ->
            SourceEntry(source)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SourceEntry(source: BlockSource) {
    Surface(
        color = if (source.needsManualCheck) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (source.needsManualCheck) {
                // 说清楚发生了什么，而不是只挂一个「未确认」标签：
                // 用户需要知道这是「模型给的出处对不上」，不是「系统出错了」
                Text(
                    "未能匹配到资料，需要你自己核对",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "模型给出的出处标识：${source.citation.rawRef}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "可能是引用的资料已被删除，也可能是模型自己凑了一个标识。" +
                        "这一段的内容仍然可读，但它的出处没有得到确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                return@Column
            }

            Text(
                source.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            if (source.locator.isNotBlank()) {
                Text(
                    source.locator,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (source.versionInfo.isNotBlank()) {
                Text(
                    source.versionInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            source.document?.let { document ->
                Text(
                    "${sourceOriginLabel(document)} · 取得于 ${formatSourceDay(document.fetchedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (source.excerpt.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    source.excerpt.take(SOURCE_EXCERPT_CHARS),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** 「你的文件 / 你抓取的网页 / 你粘贴的文本」——用户要能一眼看出是不是自己的资料 */
private fun sourceOriginLabel(document: SourceDocument): String = when {
    !document.fileName.isNullOrBlank() -> "你的文件：${document.fileName}"
    !document.url.isNullOrBlank() -> "你抓取的网页：${document.url}"
    else -> "你粘贴的文本"
}

private fun formatSourceDay(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))

/** 来源面板里原文摘录的长度上限。够核对就行，不必把整个片段搬过来 */
private const val SOURCE_EXCERPT_CHARS = 600

/** 章末：只给「自测」和「下一章」两个出口，不做任务式提示 */
@Composable
private fun ChapterFooter(
    chapter: ChapterPlan?,
    quizCount: Int,
    hasNext: Boolean,
    onSelfTest: () -> Unit,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "本章完",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        // 无资料模式：章末列出需要自己核对的断言（问题3）
        chapter?.unverifiedClaims?.takeIf { it.isNotEmpty() }?.let { claims ->
            Spacer(Modifier.height(16.dp))
            UnverifiedClaimsCard(claims = claims)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (quizCount > 0) {
                OutlinedButton(onClick = onSelfTest) { Text("自测（$quizCount 题）") }
            }
            if (hasNext) {
                Button(onClick = onNext) { Text("继续读下一章") }
            }
        }
        if (quizCount > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "自测完全可选，不做也能继续往下读。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 本章导览（章节内目录）。
 *
 * 回答三个问题：这一章在解决什么、涉及哪些知识点、关键结论在哪。
 * 关键结论用 KEY_POINT 块当锚点——它在安静与密集模式下都显示，跳过去一定看得到。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterOutlineSheet(
    chapter: ChapterPlan?,
    blocks: List<ChapterBlock>,
    visibleBlocks: List<ChapterBlock>,
    nodes: List<KnowledgeNode>,
    onJumpBlock: (String) -> Unit,
    onOpenNode: (KnowledgeNode) -> Unit,
) {
    val keyPoints = remember(visibleBlocks) {
        visibleBlocks.filter { it.blockType == BlockType.KEY_POINT }
    }
    val chapterNodes = remember(blocks, chapter, nodes) {
        val ids = (blocks.flatMap { it.knowledgeNodeIds } + chapter?.nodeIds.orEmpty()).distinct()
        ids.mapNotNull { id -> nodes.firstOrNull { it.id == id } }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text("本章导览", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        val problem = chapter?.derivationChain?.ifBlank { chapter.plotGoal }.orEmpty()
        if (problem.isNotBlank()) {
            Text("这一章在解决什么", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    problem,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        chapter?.learningGoals?.takeIf { it.isNotEmpty() }?.let { goals ->
            Text("读完这章你会知道", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            goals.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(12.dp))
        }

        if (chapterNodes.isNotEmpty()) {
            Text("涉及的知识点", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chapterNodes.forEach { node ->
                    AssistChip(
                        onClick = { onOpenNode(node) },
                        label = { Text(node.title) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (keyPoints.isNotEmpty()) {
            Text("关键结论（点击跳到正文）", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            keyPoints.forEachIndexed { index, block ->
                TextButton(
                    onClick = { onJumpBlock(block.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${index + 1}. ${block.text}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 正文里点开知识点：看清来处去处，并能一步进到知识地图 */
@Composable
private fun ReaderNodeSheet(
    node: KnowledgeNode,
    onOpenInMap: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(node.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (node.arisesFrom.isNotBlank()) InfoRow("从哪来", node.arisesFrom)
        if (node.leadsTo.isNotBlank()) InfoRow("引出什么", node.leadsTo)
        InfoRow("解决什么", node.definition)
        if (node.prerequisites.isNotEmpty()) InfoRow("前置知识", node.prerequisites.joinToString("、"))
        if (node.examples.isNotEmpty()) InfoRow("案例", node.examples.joinToString("、"))
        if (node.confusions.isNotEmpty()) InfoRow("易混淆", node.confusions.joinToString("、"))
        Spacer(Modifier.height(4.dp))
        Text(
            "掌握度 ${(node.mastery * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenInMap, modifier = Modifier.fillMaxWidth()) {
            Text("在知识地图中查看它的位置")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockContent(
    block: ChapterBlock,
    dense: Boolean,
    nodes: List<KnowledgeNode>,
    highlighted: Boolean,
    sourceSummary: BlockSourceSummary?,
    onOpenNode: (KnowledgeNode) -> Unit,
    onOpenSources: () -> Unit,
) {
    // 命中的块闪一下底色，让人一眼看到"就是这里"
    val highlightColor by animateColorAsState(
        targetValue = if (highlighted) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            Color.Transparent
        },
        label = "blockHighlight",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(highlightColor, MaterialTheme.shapes.small)
            .padding(if (highlighted) 6.dp else 0.dp),
    ) {
        when (block.blockType) {
            BlockType.STORY -> {
                Text(
                    block.text,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 17.sp,
                    lineHeight = 28.sp,
                )
            }

            // 推演的落锤处：一句话钉住结论，用左侧竖线 + 大字号让它在长文里跳出来（问题2）
            BlockType.KEY_POINT -> {
                Row(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .heightIn(min = 24.dp)
                            .fillMaxHeight()
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        block.text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 30.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            BlockType.EXPLANATION -> BlockCard(
                label = "📘 知识讲解",
                text = block.text,
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            BlockType.FACT -> BlockCard(
                label = "📄 原始事实",
                text = block.text,
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
            )

            BlockType.ANALOGY -> BlockCard(
                label = "💡 类比说明",
                text = block.text,
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BlockType.FICTION -> BlockCard(
                label = "🎭 虚构演绎",
                text = block.text,
                container = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (dense && block.knowledgeNodeIds.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                block.knowledgeNodeIds.forEach { nodeId ->
                    val node = nodes.firstOrNull { it.id == nodeId }
                    if (node != null) {
                        AssistChip(
                            onClick = { onOpenNode(node) },
                            label = { Text("🔗 ${node.title}") },
                        )
                    }
                }
            }
        }

        // 来源入口（4A）。克制显示：密集模式下有来源就给入口；
        // 安静模式下只在**存在未确认引用**时出现——那件事读者应该知道，
        // 而「这一段有 3 处正常来源」不该在故事阅读中反复打断视线
        if (sourceSummary != null && sourceSummary.hasAnything &&
            (dense || sourceSummary.unconfirmedCount > 0)
        ) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onOpenSources,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(
                    sourceSummary.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (sourceSummary.unconfirmedCount > 0) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun BlockCard(
    label: String,
    text: String,
    container: Color,
    content: Color,
) {
    Surface(
        color = container,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = content)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
        }
    }
}
