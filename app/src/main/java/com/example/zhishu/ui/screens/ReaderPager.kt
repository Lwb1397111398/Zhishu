package com.example.zhishu.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zhishu.data.prefs.ReaderMode
import com.example.zhishu.data.prefs.ReaderPaper
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus

/**
 * 阅读器的纸色调色板。
 *
 * 阅读区不跟主题走：米白纸感 / 纯白 / 夜间三种纸面各配一套固定的前景色，
 * 保证「打开就是一本纸质书」的观感，白天夜里都不需要用户再调主题。
 */
data class ReaderPalette(
    val background: Color,
    val text: Color,
    val secondary: Color,
    val cardContainer: Color,
    val cardContent: Color,
    val accent: Color,
    val attention: Color,
    val menuScrim: Color,
)

fun readerPalette(paper: ReaderPaper): ReaderPalette = when (paper) {
    ReaderPaper.CREAM -> ReaderPalette(
        background = Color(0xFFFBF6EC),
        text = Color(0xFF3A3532),
        secondary = Color(0xFF8A7F70),
        cardContainer = Color(0xFFF1E8D6),
        cardContent = Color(0xFF4A4238),
        accent = Color(0xFFD84315),
        attention = Color(0xFF44617B),
        menuScrim = Color(0xF2FBF6EC),
    )

    ReaderPaper.WHITE -> ReaderPalette(
        background = Color(0xFFFFFFFF),
        text = Color(0xFF1C1B1F),
        secondary = Color(0xFF79726A),
        cardContainer = Color(0xFFF3F0EA),
        cardContent = Color(0xFF3A3630),
        accent = Color(0xFFD84315),
        attention = Color(0xFF44617B),
        menuScrim = Color(0xF2FFFFFF),
    )

    ReaderPaper.NIGHT -> ReaderPalette(
        background = Color(0xFF201D19),
        text = Color(0xFFD9D2C5),
        secondary = Color(0xFF948B7D),
        cardContainer = Color(0xFF2C2823),
        cardContent = Color(0xFFCFC7B8),
        accent = Color(0xFFE5895B),
        attention = Color(0xFF8FB0CF),
        menuScrim = Color(0xF2201D19),
    )
}

/** 阅读器字号档位对应的正文字号（sp）：小 / 标准 / 大 / 特大 */
fun readerFontSizeSp(index: Int): Int = when (index) {
    0 -> 15
    1 -> 17
    2 -> 19
    else -> 21
}

/**
 * 一页里的一段内容：KEY_POINT / 讲解卡片整块一段，STORY 块按段落切成多段。
 * [text] 是这一段真正渲染的文本；卡片与关键结论不拆段，恒为整块文本。
 */
data class PageSegment(
    val block: ChapterBlock,
    val text: String,
    val firstOfBlock: Boolean,
    val lastOfBlock: Boolean,
)

/**
 * 翻页分页测量器。
 *
 * 分页的核心是「先量高度、再切页」：用 [androidx.compose.ui.text.TextMeasurer]
 * 在固定页宽下量出每个渲染单元的真实高度，再按页高累积切页。估算口径与
 * ReaderScreen 里 [BlockContent] 的实际渲染样式保持一致（同一字号体系），
 * 另留出余量吸收取整误差——宁可页底留一点白，也不能裁掉最后一行。
 */
internal class PageMeasurer(
    private val measure: (String, TextStyle) -> Int,
    private val density: Density,
) {
    private fun dp(v: Int): Int = with(density) { v.dp.roundToPx() }

    /** STORY 正文段：fontSize / 1.7 倍行高，与渲染处完全一致 */
    fun story(text: String, fontSp: Int): Int =
        measure(
            text,
            TextStyle(fontSize = fontSp.sp, lineHeight = (fontSp * 1.7f).sp),
        )

    /** KEY_POINT：比正文大 2sp、1.55 倍行高、半粗 */
    fun keyPoint(text: String, fontSp: Int): Int {
        val fs = fontSp + 2
        return measure(
            text,
            TextStyle(fontSize = fs.sp, lineHeight = (fs * 1.55f).sp, fontWeight = FontWeight.SemiBold),
        )
    }

    /** 讲解卡片：label 一行 + 4dp + 正文(小 2 号) + 上下 padding 24dp */
    fun card(text: String, fontSp: Int): Int {
        val fs = (fontSp - 2).coerceAtLeast(13)
        val label = measure("知识讲解", TextStyle(fontSize = 12.sp, lineHeight = 16.sp))
        val body = measure(text, TextStyle(fontSize = fs.sp, lineHeight = (fs * 1.55f).sp))
        return label + dp(4) + body + dp(24)
    }

    /** 章首头（仅第一页）：章名 + 「第 x 章」 + 可选的通知条 */
    fun header(title: String, order: String, notices: List<String>): Int {
        var h = measure(title, TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold))
        h += dp(4) + measure(order, TextStyle(fontSize = 12.sp, lineHeight = 16.sp))
        notices.forEach { n ->
            h += dp(12) + measure(n, TextStyle(fontSize = 12.sp, lineHeight = 17.sp)) + dp(20)
        }
        return h + dp(20)
    }
}

/** 单元切页时的中间表示 */
private data class PageUnit(val segment: PageSegment, val heightPx: Int, val gapPx: Int)

/**
 * 把可见块序列切成页。
 *
 * 切页规则：
 * - STORY 段落是最小单元，可以跨页；讲解卡片 / 关键结论整块不拆，
 *   放不下就整体挪到下一页（读起来像卡片被拦腰斩断是硬伤）；
 * - 单元本身比一页还高（超长卡片）就独占一页，页内由渲染层兜底滚动；
 * - 同一 STORY 块的相邻段落之间不加额外间距，块与块之间留一段呼吸空隙。
 *
 * [pageHeightPx] 必须是已扣除页码区与余量的净高。
 */
internal fun buildReaderPages(
    visibleBlocks: List<ChapterBlock>,
    measurer: PageMeasurer,
    fontSp: Int,
    pageHeightPx: Int,
    gapPx: Int,
    /** 第一页顶部章首头（章名/章序/通知条）的高度，第一页的可用空间要扣掉它 */
    headerHeightPx: Int = 0,
): List<List<PageSegment>> {
    if (visibleBlocks.isEmpty()) return emptyList()
    val units = mutableListOf<PageUnit>()
    visibleBlocks.forEach { block ->
        when (block.blockType) {
            BlockType.STORY -> {
                val paragraphs = block.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (paragraphs.isEmpty()) {
                    units += PageUnit(
                        PageSegment(block, block.text, firstOfBlock = true, lastOfBlock = true),
                        measurer.story(block.text, fontSp),
                        gapPx,
                    )
                } else {
                    paragraphs.forEachIndexed { i, p ->
                        units += PageUnit(
                            PageSegment(
                                block, p,
                                firstOfBlock = i == 0,
                                lastOfBlock = i == paragraphs.lastIndex,
                            ),
                            measurer.story(p, fontSp),
                            if (i == 0) gapPx else 0,
                        )
                    }
                }
            }

            BlockType.KEY_POINT -> units += PageUnit(
                PageSegment(block, block.text, true, true),
                measurer.keyPoint(block.text, fontSp),
                gapPx,
            )

            else -> units += PageUnit(
                PageSegment(block, block.text, true, true),
                measurer.card(block.text, fontSp),
                gapPx,
            )
        }
    }

    val pages = mutableListOf<List<PageSegment>>()
    var current = mutableListOf<PageSegment>()
    var used = headerHeightPx // 第一页的章首头先占掉一块空间
    units.forEach { unit ->
        val overflow = unit.heightPx > pageHeightPx // 超页高的单元（超长卡片）只能独占一页
        // 页首单元不带块间间距（页面顶部不需要空隙）
        val needed = if (current.isEmpty()) unit.heightPx else unit.gapPx + unit.heightPx
        when {
            overflow -> {
                if (current.isNotEmpty()) {
                    pages += current
                    current = mutableListOf()
                }
                pages += mutableListOf(unit.segment)
                used = 0
            }

            used + needed <= pageHeightPx -> {
                used += needed
                current += unit.segment
            }

            else -> {
                // 扉页：章首头占了空间、正文从下一页开始（used>0 且页里还没有正文段）
                if (current.isNotEmpty() || used > 0) pages += current
                current = mutableListOf(unit.segment)
                used = unit.heightPx
            }
        }
    }
    if (current.isNotEmpty()) pages += current
    return pages
}

/**
 * 翻页正文：左右滑动的 HorizontalPager，每页一个非懒 Column
 * （页内容已在切页时算好，不会超页高），底部中央小字页码。
 * [pageCount] 含章末页；页内文本边距与高亮由 [pageContent] 自行处理。
 */
@Composable
internal fun PagedReaderBody(
    pageCount: Int,
    pagerState: PagerState,
    palette: ReaderPalette,
    pageContent: @Composable (Int) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        key = { it },
    ) { pageIndex ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                pageContent(pageIndex)
            }
            Text(
                "${pageIndex + 1} / $pageCount",
                style = MaterialTheme.typography.labelSmall,
                color = palette.secondary.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
            )
        }
    }
}

/**
 * 全书目录：按卷分组，标出 已写好 / 未生成 / 上次读到。
 * 数据直接来自讲述路线的章节安排，不必回项目页找下一章。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookTocSheet(
    plans: List<ChapterPlan>,
    currentChapterId: String,
    onOpen: (ChapterPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    val byVolume = remember(plans) {
        plans.sortedBy { it.order }.groupBy { it.volumeIndex }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("全书目录", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "共 ${plans.count { it.isReadable }} 章已写好 / 全书 ${plans.size} 章",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                byVolume.forEach { (volumeIndex, chapters) ->
                    item(key = "vol_$volumeIndex") {
                        Text(
                            chapters.firstOrNull()?.volumeTitle?.takeIf { it.isNotBlank() }
                                ?: "第 $volumeIndex 卷",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                        )
                    }
                    items(chapters, key = { it.id }) { plan ->
                        val readable = plan.isReadable
                        val isCurrent = plan.id == currentChapterId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = readable) { onOpen(plan) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "第 ${plan.order} 章",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (readable) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                plan.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (readable) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                            )
                            when {
                                isCurrent -> Text(
                                    "在读",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )

                                !readable -> Text(
                                    "未生成",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 阅读设置：字号 / 纸色 / 翻页方式，改了立即生效并记住 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsSheet(
    fontIndex: Int,
    onFont: (Int) -> Unit,
    paper: ReaderPaper,
    onPaper: (ReaderPaper) -> Unit,
    mode: ReaderMode,
    onMode: (ReaderMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            Text("字号", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("小", "标准", "大", "特大").forEachIndexed { index, label ->
                    val selected = index == fontIndex
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onFont(index) },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = (13 + index * 2).sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("纸色", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple(ReaderPaper.CREAM, "米白", Color(0xFFFBF6EC)),
                    Triple(ReaderPaper.WHITE, "纯白", Color(0xFFFFFFFF)),
                    Triple(ReaderPaper.NIGHT, "夜间", Color(0xFF201D19)),
                ).forEach { (paperOption, label, color) ->
                    val selected = paperOption == paper
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPaper(paperOption) },
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .border(1.dp, Color(0x22000000), CircleShape)
                                    .background(color, CircleShape),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("翻页方式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Pair(ReaderMode.PAGER, "左右翻页"),
                    Pair(ReaderMode.SCROLL, "上下滚动"),
                ).forEach { (modeOption, label) ->
                    val selected = modeOption == mode
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onMode(modeOption) },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 浅色圆点在浅色底上需要一圈细边才看得清（辅助 import 处） */

/** 阅读时呼出的顶部菜单 */
@Composable
internal fun ReaderTopMenu(
    bookTitle: String,
    chapterTitle: String,
    dense: Boolean,
    onToggleDense: (Boolean) -> Unit,
    showTrust: Boolean,
    trustNeedsAttention: Boolean,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onOutline: () -> Unit,
    onTrust: () -> Unit,
) {
    Surface(color = palette.menuScrim) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "返回", tint = palette.text)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        bookTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.secondary,
                        maxLines = 1,
                    )
                    Text(
                        chapterTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.text,
                        maxLines = 1,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (dense) "带讲解" else "只看故事",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.secondary,
                    )
                    Switch(checked = dense, onCheckedChange = onToggleDense)
                }
                if (showTrust) {
                    IconButton(onClick = onTrust) {
                        Icon(
                            Icons.Default.Info,
                            "本章来源",
                            tint = if (trustNeedsAttention) palette.attention else palette.secondary,
                        )
                    }
                }
                IconButton(onClick = onOutline) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, "本章导览", tint = palette.text)
                }
            }
        }
    }
}

/** 阅读时呼出的底部菜单：目录 / 翻章 / 进度 / 设置 */
@Composable
internal fun ReaderBottomMenu(
    mode: ReaderMode,
    page: Int,
    pageCount: Int,
    chapterLabel: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    palette: ReaderPalette,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToc: () -> Unit,
    onSettings: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    Surface(color = palette.menuScrim) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            if (mode == ReaderMode.PAGER && pageCount > 1) {
                Slider(
                    value = page.toFloat(),
                    onValueChange = { onSeek(it.toInt()) },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToc) {
                    Icon(Icons.Default.List, "全书目录", tint = palette.text)
                }
                TextButton(onClick = onPrev, enabled = hasPrev) {
                    Text(if (hasPrev) "上一章" else "第一章", color = if (hasPrev) palette.text else palette.secondary)
                }
                Text(
                    buildString {
                        append(chapterLabel)
                        if (mode == ReaderMode.PAGER && pageCount > 0) {
                            append(" · 第 ${page + 1}/$pageCount 页")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.secondary,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 1,
                )
                TextButton(onClick = onNext, enabled = hasNext) {
                    Text(if (hasNext) "下一章" else "最后一章", color = if (hasNext) palette.text else palette.secondary)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, "阅读设置", tint = palette.text)
                }
            }
        }
    }
}
