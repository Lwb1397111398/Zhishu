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
import com.example.zhishu.ui.common.InfoRow
import com.example.zhishu.ui.common.UnverifiedClaimsCard
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.ReaderViewModel
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

    var selectedNode by remember { mutableStateOf<KnowledgeNode?>(null) }
    var showOutline by remember { mutableStateOf(false) }
    var highlightBlockId by remember { mutableStateOf(targetBlockId) }
    var restored by remember(chapterId) { mutableStateOf(false) }

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
                        onOpenNode = { selectedNode = it },
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
}

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
    onOpenNode: (KnowledgeNode) -> Unit,
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
