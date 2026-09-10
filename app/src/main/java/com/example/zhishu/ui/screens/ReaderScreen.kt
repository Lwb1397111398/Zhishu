package com.example.zhishu.ui.screens

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.School
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zhishu.data.prefs.ReaderMode
import com.example.zhishu.data.prefs.ReaderPaper
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.IssueResolution
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.ui.common.InfoRow
import com.example.zhishu.ui.common.UnverifiedClaimsCard
import com.example.zhishu.ui.common.formatDay
import com.example.zhishu.ui.common.rememberZhishuViewModel
import com.example.zhishu.ui.viewmodel.BlockSource
import com.example.zhishu.ui.viewmodel.BlockSourceSummary
import com.example.zhishu.ui.viewmodel.ChapterTrustSummary
import com.example.zhishu.ui.viewmodel.ReaderViewModel
import com.example.zhishu.ui.viewmodel.SourceTrustState
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
    /** 正文 → 资料：打开某份资料并滚到指定片段（4C） */
    onOpenSource: (String, String?) -> Unit = { _, _ -> },
    /** 打开全书未核实内容筛选（4C） */
    onOpenTrustCenter: (String) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ReaderViewModel = rememberZhishuViewModel(key = "reader_$chapterId") {
        ReaderViewModel(app, chapterId)
    }
    val chapter by vm.chapter.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val dense by vm.denseMode.collectAsStateWithLifecycle()
    val prevChapterId by vm.prevChapterId.collectAsStateWithLifecycle()
    val nextChapterId by vm.nextChapterId.collectAsStateWithLifecycle()
    val savedAnchor by vm.savedAnchor.collectAsStateWithLifecycle()
    val quizCount by vm.quizCount.collectAsStateWithLifecycle()
    val bodyUpdatedNotice by vm.bodyUpdatedNotice.collectAsStateWithLifecycle()
    val blockSources by vm.blockSources.collectAsStateWithLifecycle()
    val chapterTrust by vm.chapterTrust.collectAsStateWithLifecycle()
    val staleTargetNotice by vm.staleTargetNotice.collectAsStateWithLifecycle()
    // 阅读偏好（小说模式）：翻页/滚动、字号、纸色——都持久化，改了立即生效
    val readerMode by vm.readerMode.collectAsStateWithLifecycle()
    val fontIndex by vm.readerFontSizeIndex.collectAsStateWithLifecycle()
    val paper by vm.readerPaper.collectAsStateWithLifecycle()
    val serifBody by vm.readerSerif.collectAsStateWithLifecycle()
    val allPlans by vm.allPlans.collectAsStateWithLifecycle()

    var selectedNode by remember { mutableStateOf<KnowledgeNode?>(null) }
    var showOutline by remember { mutableStateOf(false) }
    var showTrust by remember { mutableStateOf(false) }
    // 沉浸式阅读的呼出菜单与两个面板（全书目录 / 阅读设置）
    var menuShown by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showReaderSettings by remember { mutableStateOf(false) }
    var highlightBlockId by remember { mutableStateOf(targetBlockId) }
    var restored by remember(chapterId) { mutableStateOf(false) }
    // 打开哪一块的来源面板（4A）。默认不展开，阅读体验优先
    var sourceBlockId by remember(chapterId) { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val palette = readerPalette(paper)
    val fontSp = readerFontSizeSp(fontIndex)
    // 测量与渲染共用的样式表：翻页与滚动两种模式画的是同一批 TextStyle
    val typography = remember(fontSp, serifBody) { ReaderTypography(fontSp, serifBody) }

    // 安静模式（现在叫「只看故事」）只藏讲解/引文/类比这些"旁注"，但保留 KEY_POINT——
    // 那些重述句本身就是推演的骨架，藏掉会让主线断掉（问题2）
    val visibleBlocks = if (dense) {
        blocks
    } else {
        blocks.filter { it.blockType == BlockType.STORY || it.blockType == BlockType.KEY_POINT }
    }

    // 搜索命中的块若是「只看故事」模式下折叠掉的旁注，自动切到「带讲解」，否则跳过去看不到东西。
    // 一次性闸门（按 targetBlockId）：blocks 是 Flow，重生成/进度更新都会再发射，
    // 不加闸门的话用户手动关掉讲解后、下一次 blocks 变化又会被强行打开。
    var denseAutoApplied by remember(targetBlockId) { mutableStateOf(false) }
    LaunchedEffect(targetBlockId, blocks) {
        val target = targetBlockId ?: return@LaunchedEffect
        if (denseAutoApplied) return@LaunchedEffect
        val block = blocks.firstOrNull { it.id == target } ?: return@LaunchedEffect
        val hidden = block.blockType != BlockType.STORY && block.blockType != BlockType.KEY_POINT
        if (hidden && !vm.denseMode.value) {
            vm.denseMode.value = true
            denseAutoApplied = true
        }
    }

    // 切换翻页/滚动时内容结构完全变了，恢复闸门重走一遍（锚点会把位置带回来）
    LaunchedEffect(readerMode) { restored = false }

    // 翻页模式的分页结果（正文页），由翻页分支计算后写入
    var pages by remember { mutableStateOf<List<List<PageSegment>>>(emptyList()) }
    // 切页器标出的「内容比一页还高」的页：这些页开纵向滚动兜底而不是裁掉页底
    var overflowPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    // 章末「本章完 / 自测 / 下一章」在翻页模式下独立成最后一页
    val footerPageCount = if (readerMode == ReaderMode.PAGER) 1 else 0

    // 翻页状态：页数 = 正文页数 + 章末页
    val pagerState = rememberPagerState { pages.size + footerPageCount }

    // —— 滚动模式：恢复位置。优先跳到指定块（搜索/知识地图跳转），否则回到上次读到的块
    LaunchedEffect(readerMode, visibleBlocks, savedAnchor, targetBlockId) {
        if (readerMode != ReaderMode.SCROLL) return@LaunchedEffect
        if (restored || visibleBlocks.isEmpty()) return@LaunchedEffect
        val anchor = savedAnchor ?: return@LaunchedEffect // 进度还没读出来，先别动
        val targetId = targetBlockId ?: anchor.blockId
        if (targetId == null) {
            restored = true
            return@LaunchedEffect
        }
        val index = visibleBlocks.indexOfFirst { it.id == targetId }
        if (index < 0) {
            // 块还没出现在当前可见列表里（等待切换带讲解）；确实找不到才算恢复完成
            if (blocks.none { it.id == targetId }) {
                restored = true
                // 从搜索 / 资料 / 知识地图点进来，而目标段落不属于当前版本：
                // 本章更新过，那一段已经不存在了。不说的话用户会以为点了没反应（4C）
                if (targetBlockId != null) vm.reportStaleTarget()
            }
            return@LaunchedEffect
        }
        listState.scrollToItem(
            index = index + HEADER_ITEMS,
            scrollOffset = if (targetBlockId != null) 0 else anchor.offset,
        )
        restored = true
    }

    // —— 翻页模式：恢复位置。pages 切好后找包含锚点块的页，直接跳过去
    LaunchedEffect(readerMode, pages, savedAnchor, targetBlockId) {
        if (readerMode != ReaderMode.PAGER) return@LaunchedEffect
        if (restored || pages.isEmpty()) return@LaunchedEffect
        val anchor = savedAnchor ?: return@LaunchedEffect
        val targetId = targetBlockId ?: anchor.blockId
        if (targetId == null) {
            restored = true
            return@LaunchedEffect
        }
        val pageIndex = pages.indexOfFirst { page -> page.any { it.block.id == targetId } }
        if (pageIndex < 0) {
            if (blocks.none { it.id == targetId }) {
                restored = true
                if (targetBlockId != null) vm.reportStaleTarget()
            }
            return@LaunchedEffect
        }
        pagerState.scrollToPage(pageIndex)
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

    // —— 滚动模式：进度按块保存，停止滚动 1 秒后写库。恢复完成前不写（B10）
    LaunchedEffect(readerMode, listState, visibleBlocks, restored) {
        if (readerMode != ReaderMode.SCROLL) return@LaunchedEffect
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

    // —— 翻页模式：翻到稳定页后写库（记该页第一段的块；章末页保持最后一页正文的位置）
    LaunchedEffect(readerMode, pages, restored) {
        if (readerMode != ReaderMode.PAGER) return@LaunchedEffect
        if (!restored || pages.isEmpty()) return@LaunchedEffect
        snapshotFlow { pagerState.settledPage }
            .debounce(600)
            .distinctUntilChanged()
            .collectLatest { pageIdx ->
                val segs = pages.getOrNull(pageIdx) ?: return@collectLatest
                vm.saveProgress(segs.firstOrNull()?.block?.id, 0)
            }
    }

    val headerNotices = buildList {
        if (bodyUpdatedNotice) add("本章正文已更新，从章首开始阅读。")
        if (staleTargetNotice) {
            add(
                "你点的那一段属于本章的旧版本，正文后来更新过，" +
                    "所以只能从章首开始。可以用本章导览或搜索重新定位。",
            )
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        if (blocks.isEmpty()) {
            // 空状态：保留返回手段，其他信息照旧，只是换成纸面配色
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(32.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = palette.text)
                }
                Spacer(Modifier.height(80.dp))
                Text(
                    "本章正文尚未生成",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.text,
                )
                Text(
                    "回到项目详情，在「故事大纲」中生成该章节。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.secondary,
                )
            }
        } else if (readerMode == ReaderMode.PAGER) {
            // ================= 翻页模式（默认）：像小说软件一样左右翻页 =================
            // 安全区在 BoxWithConstraints 上就扣掉：页宽/页高量的是真正可写的区域，
            // 正文不会再钻到状态栏底下（以前 padding 加在内侧、测量按全屏算，两头对不上）
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                val density = LocalDensity.current
                // 页宽页高的扣除口径全在 ReaderLayout——测量与渲染读同一份数字
                val margin = ReaderLayout.horizontalMargin
                val pageWidthPx = with(density) {
                    (maxWidth - margin * 2).roundToPx().coerceAtLeast(200)
                }
                val pageHeightPx = with(density) {
                    (maxHeight - ReaderLayout.pageTopPadding - ReaderLayout.footerZone - ReaderLayout.slack)
                        .roundToPx().coerceAtLeast(200)
                }
                val textMeasurer = rememberTextMeasurer(cacheSize = 512)
                val headerTitle = chapter?.title ?: "阅读"
                val headerOrder = "第 ${chapter?.order ?: 1} 章"
                val measurer = remember(density, textMeasurer, pageWidthPx, typography) {
                    PageMeasurer(
                        measure = { text, style ->
                            textMeasurer.measure(
                                text,
                                style,
                                maxLines = Int.MAX_VALUE,
                                softWrap = true,
                                constraints = Constraints(maxWidth = pageWidthPx),
                            ).size.height
                        },
                        density = density,
                        typography = typography,
                    )
                }
                val headerHeightPx = measurer.header(headerTitle, headerOrder, headerNotices)

                // 附件高度：chip 行 + 来源入口以前没人量过，带附件的块页页底被裁
                val attachmentHeights = remember(
                    visibleBlocks, dense, blockSources, nodes, fontSp, pageWidthPx,
                ) {
                    buildAttachmentHeights(
                        blocks = visibleBlocks,
                        dense = dense,
                        nodeTitles = { b ->
                            b.knowledgeNodeIds.mapNotNull { id -> nodes.firstOrNull { it.id == id }?.title }
                        },
                        hasSourceEntry = { b ->
                            val s = blockSources[b.id]
                            s != null && s.hasAnything && (dense || s.deservesAttention)
                        },
                        density = density,
                        fontPx = with(density) { fontSp.sp.roundToPx() },
                        pageWidthPx = pageWidthPx,
                    )
                }

                // 切页：正文块流 → 页。字号/模式/正文变化都会重算，重算后停回当前阅读位置
                val newPaging = remember(
                    visibleBlocks, pageWidthPx, pageHeightPx, headerHeightPx,
                    measurer, attachmentHeights,
                ) {
                    buildReaderPages(
                        visibleBlocks = visibleBlocks,
                        measurer = measurer,
                        pageHeightPx = pageHeightPx,
                        headerHeightPx = headerHeightPx,
                        attachments = attachmentHeights,
                    )
                }
                LaunchedEffect(newPaging) {
                    // 保持位置：记录当前页（章末页则按最后一页正文算）页首块，
                    // 重算后跳回包含该块的新页，而不是被甩回章首
                    val currentPageIdx = pagerState.currentPage
                        .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    val keepBlockId = pages.getOrNull(currentPageIdx)
                        ?.firstOrNull()?.block?.id
                    pages = newPaging.pages
                    overflowPages = newPaging.overflowPages
                    if (restored) {
                        val target = keepBlockId?.let { id ->
                            newPaging.pages.indexOfFirst { page -> page.any { it.block.id == id } }
                        }
                        pagerState.scrollToPage(if (target != null && target >= 0) target else 0)
                    }
                }

                PagedReaderBody(
                    pageCount = pages.size + footerPageCount,
                    pagerState = pagerState,
                    palette = palette,
                ) { pageIndex ->
                    if (pageIndex < pages.size) {
                        // 超页内容的兜底滚动：切页器标出的 overflow 页开 verticalScroll，
                        // 以前这类页直接被裁掉页底（注释里承诺了滚动却没人实现）
                        val canScroll = pageIndex in overflowPages || pageIndex == 0
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState(), enabled = canScroll)
                                .padding(horizontal = margin)
                                .padding(top = ReaderLayout.pageTopPadding),
                        ) {
                            if (pageIndex == 0) {
                                ReaderPageHeader(
                                    title = headerTitle,
                                    order = headerOrder,
                                    notices = headerNotices,
                                    typography = typography,
                                    palette = palette,
                                )
                                Spacer(Modifier.height(ReaderLayout.headerToBody))
                            }
                            pages.getOrElse(pageIndex) { emptyList() }.forEachIndexed { i, seg ->
                                if (i > 0) {
                                    // 块间留呼吸空隙；关键结论前后再多一点；同块段落中等间距。
                                    // 数字全取 ReaderLayout——切页测量用的就是这几个
                                    val gap = when {
                                        !seg.firstOfBlock -> ReaderLayout.paraGap
                                        seg.block.blockType == BlockType.KEY_POINT -> ReaderLayout.keyPointGap
                                        else -> ReaderLayout.blockGap
                                    }
                                    Spacer(Modifier.height(gap))
                                }
                                BlockContent(
                                    block = seg.block,
                                    dense = dense,
                                    nodes = nodes,
                                    highlighted = seg.block.id == highlightBlockId,
                                    sourceSummary = blockSources[seg.block.id],
                                    onOpenNode = { selectedNode = it },
                                    onOpenSources = { sourceBlockId = seg.block.id },
                                    palette = palette,
                                    typography = typography,
                                    textOverride = if (seg.block.blockType == BlockType.STORY) seg.text else null,
                                    showAttachments = seg.lastOfBlock,
                                )
                            }
                        }
                    } else {
                        // 章末页：本章完 / 待核实断言 / 自测 / 下一章（内容超页时可滚动兜底）
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp)
                                .padding(top = 24.dp, bottom = 40.dp),
                        ) {
                            ChapterFooter(
                                chapter = chapter,
                                quizCount = quizCount,
                                hasNext = nextChapterId != null,
                                onSelfTest = { onOpenQuiz(chapterId) },
                                onNext = { nextChapterId?.let(onOpenChapter) },
                                palette = palette,
                            )
                        }
                    }
                }
            }
        } else {
            // ================= 滚动模式：保留块级定位与逐段进度，纸面化 =================
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "__header__") {
                    Column {
                        Text(
                            chapter?.title ?: "",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.text,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "第 ${chapter?.order ?: 1} 章",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.secondary,
                        )
                        // 正文换过版本：说清楚为什么回到了章首（3D）。
                        // 只说一次，中性底色，不做成警告——正文更新是好事，不是出错
                        if (bodyUpdatedNotice) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                color = palette.cardContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "本章正文已更新，从章首开始阅读。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.cardContent,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                        // 点进来的那一段属于旧版本（4C）。同样中性——本章更新过而已，
                        // 但必须说，否则「跳转没反应」看起来就是个 bug
                        if (staleTargetNotice) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                color = palette.cardContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "你点的那一段属于本章的旧版本，正文后来更新过，" +
                                        "所以只能从章首开始。可以用本章导览或搜索重新定位。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.cardContent,
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
                        palette = palette,
                        typography = typography,
                    )
                }
                item(key = "__footer__") {
                    ChapterFooter(
                        chapter = chapter,
                        quizCount = quizCount,
                        hasNext = nextChapterId != null,
                        onSelfTest = { onOpenQuiz(chapterId) },
                        onNext = { nextChapterId?.let(onOpenChapter) },
                        palette = palette,
                    )
                }
            }
        }

        // 点击手势层：翻页模式点左/右 1/3 翻页，中间呼出菜单；滚动模式任意点按呼出菜单。
        // detectTapGestures 只消费点按，不干扰翻页滑动与滚动
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(readerMode) {
                    detectTapGestures { offset ->
                        when {
                            readerMode == ReaderMode.PAGER && offset.x < size.width / 3f ->
                                scope.launch {
                                    if (pagerState.currentPage > 0) {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }

                            readerMode == ReaderMode.PAGER &&
                                offset.x > size.width * 2f / 3f ->
                                scope.launch {
                                    val last = pages.size + footerPageCount - 1
                                    if (pagerState.currentPage < last) {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                                }

                            else -> menuShown = !menuShown
                        }
                    }
                },
        )

        // 呼出菜单：顶部（返回/书名章节/带讲解/来源/导览）与底部（目录/翻章/进度/设置）。
        // 菜单是覆盖层，点菜单外的空白处会再触发一次 toggle 收起
        AnimatedVisibility(
            visible = menuShown,
            modifier = Modifier.align(Alignment.TopStart),
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        ) {
            ReaderTopMenu(
                bookTitle = project?.title.orEmpty(),
                chapterTitle = chapter?.title.orEmpty(),
                dense = dense,
                onToggleDense = { vm.denseMode.value = it },
                showTrust = !chapterTrust.isEmpty,
                trustNeedsAttention = chapterTrust.needsAttentionCount > 0,
                palette = palette,
                onBack = onBack,
                onOutline = { showOutline = true },
                onTrust = { showTrust = true },
            )
        }
        AnimatedVisibility(
            visible = menuShown,
            modifier = Modifier.align(Alignment.BottomStart),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            ReaderBottomMenu(
                mode = readerMode,
                page = pagerState.currentPage,
                pageCount = pages.size + footerPageCount,
                chapterLabel = "第 ${chapter?.order ?: 1} 章",
                hasPrev = prevChapterId != null,
                hasNext = nextChapterId != null,
                palette = palette,
                onPrev = { prevChapterId?.let(onOpenChapter) },
                onNext = { nextChapterId?.let(onOpenChapter) },
                onToc = { showToc = true },
                onSettings = { showReaderSettings = true },
                onSeek = { pageIdx ->
                    // 页码来自渲染时读到的 pageCount，但改字号/重生成正文会先让页数变小，
                    // 再触发这里的滚动——不夹一下就落到不存在的页上（IllegalArgumentException）
                    scope.launch {
                        val last = (pagerState.pageCount - 1).coerceAtLeast(0)
                        pagerState.scrollToPage(pageIdx.coerceIn(0, last))
                    }
                },
            )
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
                    highlightBlockId = blockId
                    showOutline = false
                    when (readerMode) {
                        ReaderMode.SCROLL -> {
                            val index = visibleBlocks.indexOfFirst { it.id == blockId }
                            if (index >= 0) {
                                scope.launch { listState.animateScrollToItem(index + HEADER_ITEMS) }
                            }
                        }

                        ReaderMode.PAGER -> {
                            val pageIndex = pages.indexOfFirst { page ->
                                page.any { it.block.id == blockId }
                            }
                            if (pageIndex >= 0) {
                                scope.launch { pagerState.animateScrollToPage(pageIndex) }
                            }
                        }
                    }
                },
                onOpenNode = {
                    showOutline = false
                    selectedNode = it
                },
            )
        }
    }

    if (showToc) {
        BookTocSheet(
            plans = allPlans,
            currentChapterId = chapterId,
            onOpen = { plan ->
                showToc = false
                if (plan.id != chapterId) onOpenChapter(plan.id)
            },
            onDismiss = { showToc = false },
        )
    }

    if (showReaderSettings) {
        ReaderSettingsSheet(
            fontIndex = fontIndex,
            onFont = { vm.setFontSize(it) },
            serif = serifBody,
            onSerif = { vm.setSerif(it) },
            paper = paper,
            onPaper = { vm.setPaper(it) },
            mode = readerMode,
            onMode = { vm.setReaderMode(it) },
            onDismiss = { showReaderSettings = false },
        )
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
            BlockSourceSheet(
                summary = summary,
                onOpenSource = { sourceId, chunkId ->
                    sourceBlockId = null
                    onOpenSource(sourceId, chunkId)
                },
            )
        }
    }

    // 章节级来源摘要（4C）
    if (showTrust) {
        ModalBottomSheet(onDismissRequest = { showTrust = false }) {
            ChapterTrustSheet(
                trust = chapterTrust,
                onOpenTrustCenter = {
                    val projectId = project?.id
                    showTrust = false
                    if (projectId != null) onOpenTrustCenter(projectId)
                },
            )
        }
    }
}

/**
 * 翻页模式第一页的章首头：章名 + 章序 + 必要的一次性提示。
 *
 * 样式与间距必须逐条对上 PageMeasurer.header（headerTitle/6dp/headerOrder/
 * 每条 notice 的 noticeGap+notice+10dp×2），量多少画多少才不会把第一条正文顶出页。
 */
@Composable
private fun ReaderPageHeader(
    title: String,
    order: String,
    notices: List<String>,
    typography: ReaderTypography,
    palette: ReaderPalette,
) {
    Column {
        Text(title, style = typography.headerTitle, color = palette.text)
        Spacer(Modifier.height(6.dp))
        Text(order, style = typography.headerOrder, color = palette.secondary)
        notices.forEach { notice ->
            Spacer(Modifier.height(ReaderLayout.noticeGap))
            Surface(
                color = palette.cardContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    notice,
                    style = typography.notice,
                    color = palette.cardContent,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

/**
 * 一个正文块的来源明细（4A，4C 补上可信度）。
 *
 * 几种情况说几种话，不混在一起：
 * - 可定位的引用：资料名、页码/章节、版本与法域、原文摘录，可以点开原资料；
 * - 未确认的引用：模型给的标识原样列出，并说明我们没能对上；
 * - 资料已删除：说出是哪一份，并说明这一段当初确实有依据；
 * - 一条引用都没有：区分「AI 自备知识」和「没标来源」——前者是设计如此，后者是缺口。
 *
 * 最后再把这一块的核验问题列出来。「这段的依据在哪」和「这段有没有已知问题」
 * 是读者同时会问的两件事，分在两个页面里等于没说。
 */
@Composable
private fun BlockSourceSheet(
    summary: BlockSourceSummary?,
    onOpenSource: (String, String?) -> Unit,
) {
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
            BlockIssues(summary)
            return@Column
        }

        summary.sources.forEach { source ->
            SourceEntry(source = source, onOpenSource = onOpenSource)
            Spacer(Modifier.height(12.dp))
        }

        // 有资料模式下也会出现「这一块有引用记录，但一条都没对上」：那部分内容
        // 同样是模型自己的知识，读者该知道。一条引用都没有的块在上面就已经
        // 说过话了（「未标注来源」/「AI 自备知识」），不在这里重复一遍
        if (summary.allSourcesUnresolved) {
            Text(
                "这一段没有任何已确认的引用，其中的知识来自模型自己，建议自行核对。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }

        BlockIssues(summary)
    }
}

/** 这一块上的核验问题及处理状态（4C）。已修与「不用改」也显示，但说明它们已有结论 */
@Composable
private fun BlockIssues(summary: BlockSourceSummary?) {
    val issues = summary?.issues.orEmpty()
    if (issues.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Text("这一段的核验问题", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    issues.forEach { issue ->
        Surface(
            color = if (issue.resolution.isOpen) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "${issue.type.label} · ${issueResolutionLabel(issue.resolution)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(issue.message, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    // 处理入口在大纲页，不在阅读器里：读者正在读故事，
    // 「重写这一段」是编辑动作，摆在这里会把阅读变成校对
    Text(
        "要修改的话，回到项目的「讲述路线」页，在这一章上点「逐条处理」。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun issueResolutionLabel(resolution: IssueResolution): String = when (resolution) {
    IssueResolution.OPEN -> "待处理"
    IssueResolution.FIXED -> "已修"
    IssueResolution.ACCEPTED -> "你已判断不用改"
}

@Composable
private fun SourceEntry(
    source: BlockSource,
    onOpenSource: (String, String?) -> Unit,
) {
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
                // 用户需要知道这是「模型给的出处对不上」还是「资料被删了」，
                // 两者该做的事不一样，而「系统出错了」哪一种都不是
                Text(
                    source.trust.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                if (source.trust == SourceTrustState.SOURCE_DELETED) {
                    Text(
                        "原资料：${source.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                } else if (source.citation.rawRef.isNotBlank()) {
                    Text(
                        "模型给出的出处标识：${source.citation.rawRef}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    source.trust.explanation,
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
            // 旧资料没有页码与章节：说明我们只能定位到片段，不编一个页码出来（4C）
            if (source.locatorIsImprecise) {
                Text(
                    "这份资料是早期导入的，只能定位到片段，没有页码与章节；重新导入一次即可获得完整定位。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (source.versionInfo.isNotBlank()) {
                Text(
                    source.versionInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 有法域或生效时间限制：这句话未必在读者的处境下成立（4C）
            if (source.scopeLimited) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "这份资料有适用范围限制，请确认它覆盖你所在的地区与时间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            source.document?.let { document ->
                Text(
                    "${sourceOriginLabel(document)} · 取得于 ${formatDay(document.fetchedAt)}",
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
            // 回到原资料，并滚到这一段引用的片段（4C）
            source.document?.let { document ->
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { onOpenSource(document.id, source.chunk?.id) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text("在原资料中查看", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/**
 * 章节级来源摘要（4C）。
 *
 * 阅读器上只有一行入口，点开才看到这些。默认不展开是刻意的：
 * 把可信度做成常驻警告条，读者会先学会无视它，然后连真正要紧的那条也一起无视。
 *
 * 统计范围只含当前版本——这句话在数据层已经保证（ChapterTrustSummary.build
 * 拿到的是 Active 系列查询的结果），这里只负责把它说明白。
 */
@Composable
private fun ChapterTrustSheet(
    trust: ChapterTrustSummary,
    onOpenTrustCenter: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text("本章的来源", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "以下只统计你现在读到的这一版正文。旧版本的引用仍然保留在库里，但不计入这里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))

        Text(trust.headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        TrustStatRow("用户资料", if (trust.sourceCount > 0) "${trust.sourceCount} 份" else "未使用")
        TrustStatRow(
            "可定位引用",
            if (trust.locatableCount > 0) "${trust.locatableCount} 处（可点开原文）" else "无",
        )
        if (trust.unconfirmedCount > 0) {
            TrustStatRow("未确认来源", "${trust.unconfirmedCount} 处", attention = true)
        }
        if (trust.deletedSourceCount > 0) {
            TrustStatRow("来源已删除", "${trust.deletedSourceCount} 处", attention = true)
        }
        TrustStatRow("AI 自备知识", if (trust.usedModelKnowledge) "本章包含" else "未使用")
        if (trust.openIssueCount > 0) {
            TrustStatRow(
                "待核对的核验问题",
                buildString {
                    append("${trust.openIssueCount} 条")
                    if (trust.locatedIssueCount > 0) {
                        append("（${trust.locatedIssueCount} 条能定位到具体段落）")
                    }
                },
                attention = true,
            )
        }
        if (trust.unverifiedClaimCount > 0) {
            TrustStatRow("建议自己查证的断言", "${trust.unverifiedClaimCount} 条", attention = true)
        }

        // 法域、生效时间、版本：时效敏感内容离开这三样就没有对错可言（4C）
        if (trust.scopes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("适用范围", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            trust.scopes.forEach { scope ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(scope.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            scope.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "本章内容以上面这些资料为准，请确认它们覆盖你所在的地区与时间。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (trust.hasImpreciseLocator) {
            Spacer(Modifier.height(10.dp))
            Text(
                "本章有引用只能定位到片段，没有页码与章节——那是早期导入的资料没有记位置。" +
                    "重新导入一次同一份资料即可获得完整定位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (trust.needsAttentionCount > 0) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onOpenTrustCenter, modifier = Modifier.fillMaxWidth()) {
                Text("查看全书需要核对的内容")
            }
        }
    }
}

@Composable
private fun TrustStatRow(label: String, value: String, attention: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (attention) FontWeight.SemiBold else FontWeight.Normal,
            color = if (attention) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/** 「你的文件 / 你抓取的网页 / 你粘贴的文本」——用户要能一眼看出是不是自己的资料 */
private fun sourceOriginLabel(document: SourceDocument): String = when {
    !document.fileName.isNullOrBlank() -> "你的文件：${document.fileName}"
    !document.url.isNullOrBlank() -> "你抓取的网页：${document.url}"
    else -> "你粘贴的文本"
}

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
    palette: ReaderPalette,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "本章完",
            style = MaterialTheme.typography.titleSmall,
            color = palette.secondary,
        )
        // 无资料模式：章末列出需要自己核对的断言（问题3）。
        // 传纸面色——夜间纸面上跟主题走的卡片会浮成一块刺眼的白
        chapter?.unverifiedClaims?.takeIf { it.isNotEmpty() }?.let { claims ->
            Spacer(Modifier.height(16.dp))
            UnverifiedClaimsCard(
                claims = claims,
                container = palette.cardContainer,
                content = palette.cardContent,
            )
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
                color = palette.secondary,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 本章导览（章节内目录）。
 *
 * 回答三个问题：这一章在解决什么、涉及哪些知识点、关键结论在哪。
 * 关键结论用 KEY_POINT 块当锚点——它在只看故事与带讲解两种模式下都显示，跳过去一定看得到。
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
    palette: ReaderPalette,
    /** 测量与渲染共用的样式表——字号/行高/字距/字体族与分页测量一字不差 */
    typography: ReaderTypography,
    /** 翻页模式下本段实际渲染的文本（STORY 段落级切分）；null 表示整块 */
    textOverride: String? = null,
    /** 翻页模式下只有块的最后一段才挂知识点 chip 与来源入口 */
    showAttachments: Boolean = true,
) {
    // 命中的块闪一下底色，让人一眼看到"就是这里"
    val highlightColor by animateColorAsState(
        targetValue = if (highlighted) {
            palette.cardContainer
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
                    textOverride ?: ReaderTypography.indentFirst(block.text),
                    style = typography.story,
                    color = palette.text,
                )
            }

            // 推演的落锤处：一句话钉住结论，用左侧竖线 + 大字号让它在长文里跳出来（问题2）
            BlockType.KEY_POINT -> Row(
                Modifier
                    .fillMaxWidth()
                    // IntrinsicSize.Min 让左杠以文字高度为准。
                    // 没有它，fillMaxHeight 的杠会以整页高度为上限拉满——
                    // 一个两行的结论，红杠却从文字一直贯到页尾（2026-09-07 老板截图）
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(palette.accent, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    block.text,
                    style = typography.keyPoint,
                    color = palette.text,
                    modifier = Modifier.weight(1f),
                )
            }

            // 四类旁注卡各有一套颜色 + 左侧色条，不再共用一个米色容器；
            // emoji 标签（📘💡…）跨机型渲染漂移、也配不上纸质书，换成色条 + 文字标签
            BlockType.EXPLANATION -> BlockCard(
                label = "知识讲解",
                text = block.text,
                tone = palette.explanation,
                border = palette.cardBorder,
                typography = typography,
            )

            BlockType.FACT -> BlockCard(
                label = "原始事实",
                text = block.text,
                tone = palette.fact,
                border = palette.cardBorder,
                typography = typography,
            )

            BlockType.ANALOGY -> BlockCard(
                label = "类比说明",
                text = block.text,
                tone = palette.analogy,
                border = palette.cardBorder,
                typography = typography,
            )

            BlockType.FICTION -> BlockCard(
                label = "虚构演绎",
                text = block.text,
                tone = palette.fiction,
                border = palette.cardBorder,
                typography = typography,
            )
        }

        if (showAttachments && dense && block.knowledgeNodeIds.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                block.knowledgeNodeIds.forEach { nodeId ->
                    val node = nodes.firstOrNull { it.id == nodeId }
                    if (node != null) {
                        ReaderChip(text = node.title, palette = palette, onClick = { onOpenNode(node) })
                    }
                }
            }
        }

        // 来源入口（4A，判据在 4C 收紧）。克制显示：带讲解时有来源就给入口；
        // 只看故事时只在**读者应该知道**的时候出现——出处没对上，或者这一段
        // 有待处理的核验问题。「这一段有 3 处正常来源」不该在读故事时反复打断视线
        if (showAttachments && sourceSummary != null && sourceSummary.hasAnything &&
            (dense || sourceSummary.deservesAttention)
        ) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onOpenSources,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Text(
                    // 有待处理问题时优先说这件事：它比「几处来源」更需要读者知道
                    if (sourceSummary.openIssues.isNotEmpty()) {
                        "${sourceSummary.label} · ${sourceSummary.openIssues.size} 项待核对"
                    } else {
                        sourceSummary.label
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (sourceSummary.deservesAttention) {
                        palette.attention
                    } else {
                        palette.secondary
                    },
                )
            }
        }
    }
}

/**
 * 一类旁注卡：左侧色条 + 类型标签 + 正文。
 *
 * 高度构成必须与 PageMeasurer.card 一字不差——label(cardLabel) + cardLabelGap +
 * 正文(cardBody) + 14dp×2 内边距，所以这里 padding=ReaderLayout.cardPadding、
 * label 间距=cardLabelGap、样式取 typography。色条在行内不占高度。
 */
@Composable
private fun BlockCard(
    label: String,
    text: String,
    tone: CardTone,
    border: Color,
    typography: ReaderTypography,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(tone.rule, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Surface(
            color = tone.container,
            contentColor = tone.content,
            shape = MaterialTheme.shapes.small,
            border = androidx.compose.foundation.BorderStroke(1.dp, border),
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(ReaderLayout.cardPadding)) {
                Text(label, style = typography.cardLabel, color = tone.rule)
                Spacer(Modifier.height(ReaderLayout.cardLabelGap))
                Text(text, style = typography.cardBody, color = tone.content)
            }
        }
    }
}

/**
 * 阅读器里的知识点 chip：吃纸面调色板而不是 MaterialTheme。
 *
 * AssistChip 的底色跟着主题走——切到夜间纸面时它会浮成一团白，像贴了张便利贴。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderChip(
    text: String,
    palette: ReaderPalette,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = palette.chips,
        contentColor = palette.chipContent,
        shape = CircleShape,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.School,
                contentDescription = null,
                tint = palette.chipContent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
    }
}
