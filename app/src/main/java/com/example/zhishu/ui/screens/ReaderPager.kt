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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.zhishu.data.prefs.ReaderMode
import com.example.zhishu.data.prefs.ReaderPaper
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan

/**
 * 一种卡片的配色：容器底 / 文字 / 左侧色条。
 *
 * 四类旁注卡（讲解 / 事实 / 类比 / 虚构）各有一套颜色，扫一眼就能分清
 * 「这是讲解还是原话」——过去四种共用同一个米色容器，等于没有分类。
 */
data class CardTone(
    val container: Color,
    val content: Color,
    val rule: Color,
)

/**
 * 阅读器的纸色调色板。
 *
 * 阅读区不跟主题走：米白纸感 / 纯白 / 夜间三种纸面各配一套固定的前景色，
 * 保证「打开就是一本纸质书」的观感，白天夜里都不需要用户再调主题。
 *
 * [chips] / [chipContent]：阅读器叠层（知识点 chip、来源入口）用的颜色。
 * 阅读器里的组件一律吃这套色，不吃 MaterialTheme——否则「夜间纸」上
 * 会浮出白底 chip，像贴了张便利贴。
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
    val explanation: CardTone,
    val fact: CardTone,
    val analogy: CardTone,
    val fiction: CardTone,
    val chips: Color,
    val chipContent: Color,
    /** 卡片与纸面的分隔边框：纯白纸面上容器色几乎等于背景（1.03:1），全靠这条边才看得出卡 */
    val cardBorder: Color,
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
        menuScrim = Color(0xFFFBF6EC),
        explanation = CardTone(Color(0xFFF1E8D4), Color(0xFF4A4238), Color(0xFF8A7145)),
        fact = CardTone(Color(0xFFE8EDF2), Color(0xFF3D4A57), Color(0xFF5B7B99)),
        analogy = CardTone(Color(0xFFEAEFE0), Color(0xFF44503A), Color(0xFF7A8B5A)),
        fiction = CardTone(Color(0xFFF0E7F2), Color(0xFF4D3F55), Color(0xFF9070A0)),
        chips = Color(0xFFEEE4CF),
        chipContent = Color(0xFF4A4238),
        cardBorder = Color(0x1A5A4A33),
    )

    ReaderPaper.WHITE -> ReaderPalette(
        background = Color(0xFFFFFFFF),
        text = Color(0xFF1C1B1F),
        secondary = Color(0xFF6E675F),
        cardContainer = Color(0xFFEFEAE1),
        cardContent = Color(0xFF3A3630),
        accent = Color(0xFFD84315),
        attention = Color(0xFF3D5A73),
        menuScrim = Color(0xFFFFFFFF),
        explanation = CardTone(Color(0xFFF1ECDF), Color(0xFF453F33), Color(0xFF8A7145)),
        fact = CardTone(Color(0xFFE7ECF1), Color(0xFF39434D), Color(0xFF52718C)),
        analogy = CardTone(Color(0xFFE9EEE0), Color(0xFF404B37), Color(0xFF6F8050)),
        fiction = CardTone(Color(0xFFEFE6F2), Color(0xFF48394F), Color(0xFF87659A)),
        chips = Color(0xFFEFEFEF),
        chipContent = Color(0xFF3A3630),
        cardBorder = Color(0x1F000000),
    )

    ReaderPaper.NIGHT -> ReaderPalette(
        background = Color(0xFF201D19),
        text = Color(0xFFD9D2C5),
        secondary = Color(0xFF948B7D),
        cardContainer = Color(0xFF2C2823),
        cardContent = Color(0xFFCFC7B8),
        accent = Color(0xFFE5895B),
        attention = Color(0xFF8FB0CF),
        menuScrim = Color(0xFF201D19),
        explanation = CardTone(Color(0xFF2B2720), Color(0xFFCFC7B6), Color(0xFF9C8258)),
        fact = CardTone(Color(0xFF252B31), Color(0xFFC6CDD4), Color(0xFF6E90AC)),
        analogy = CardTone(Color(0xFF282D22), Color(0xFFC9D0BC), Color(0xFF8CA06A)),
        fiction = CardTone(Color(0xFF2B2531), Color(0xFFD0C4D8), Color(0xFFA583B5)),
        chips = Color(0xFF332F28),
        chipContent = Color(0xFFCFC7B8),
        cardBorder = Color(0x2EFFFFFF),
    )
}

/**
 * 分页器与渲染层共用的间距常量——**唯一事实源**。
 *
 * 切页按这些数字量、渲染按同一批数字排，谁也不许再抄一份：
 * 过去页宽/页高/间距在 ReaderScreen 与 PageMeasurer 各写一遍，
 * 改一处漏一处，量出来的页和画出来的页对不上，内容就在页底被裁。
 */
internal object ReaderLayout {
    /** 页内容左右边距 */
    val horizontalMargin = 24.dp
    /** 页顶部留白 */
    val pageTopPadding = 20.dp
    /** 页底部留给页码的区域 */
    val footerZone = 26.dp
    /** 防裁切余量：取整误差吸收，宁可页底留白 */
    val slack = 10.dp
    /** 块与块之间 */
    val blockGap = 16.dp
    /** 同一 STORY 块内相邻段落之间（≈半行高的段落呼吸） */
    val paraGap = 14.dp
    /** KEY_POINT 前后（让它站出来） */
    val keyPointGap = 20.dp
    /** 卡片内边距（上下合计进测量） */
    val cardPadding = 14.dp
    /** 卡片 label → 正文 */
    val cardLabelGap = 6.dp
    /** 章首头与正文的间距 */
    val headerToBody = 18.dp
    /** 通知条与其后内容的间距（通知条自身渲染 padding 10dp×2） */
    val noticeGap = 12.dp
    val noticePadding = 20.dp
    /** 附件行（chip / 来源入口）与块正文的间距 */
    val attachmentGap = 8.dp
    /** 单行 chip 行高估算 */
    val chipRowHeight = 34.dp
    /** 来源入口一行高估算 */
    val sourceRowHeight = 30.dp
}

/**
 * 阅读器排版：字号 → 各文本角色的 [TextStyle]——**测量与渲染共用同一批样式实例**。
 *
 * 这是整个分页系统的合同：PageMeasurer 用哪个 style 量高度，BlockContent
 * 就必须用同一个 style 画。以前两边各写各的 TextStyle，字体族/字距一漂，
 * 页就在底部裁。样式对象从这里出，两边引用，物理上不可能发散。
 *
 * [serif]：宋体（衬线，默认——「像一本书」）/ 黑体。中文正文在主流阅读 App
 * 里都是衬线，系统黑体是最不像书的一处。走 FontFamily.Serif，不打包字体文件。
 */
class ReaderTypography(
    val bodyFontSizeSp: Int,
    val serif: Boolean,
) {
    val family: FontFamily = if (serif) FontFamily.Serif else FontFamily.SansSerif
    private val bodyLs = if (serif) 0.sp else 0.2.sp

    val story = TextStyle(
        fontFamily = family,
        fontSize = bodyFontSizeSp.sp,
        lineHeight = (bodyFontSizeSp * 1.75f).sp,
        letterSpacing = bodyLs,
    )

    /** 推演落锤：比正文大 2 号、半粗 */
    val keyPoint: TextStyle = run {
        val fs = bodyFontSizeSp + 2
        TextStyle(
            fontFamily = family,
            fontSize = fs.sp,
            lineHeight = (fs * 1.6f).sp,
            letterSpacing = bodyLs,
            fontWeight = FontWeight.SemiBold,
        )
    }

    /** 卡片正文：比正文小 2 号（不低于 13） */
    val cardBody: TextStyle = run {
        val fs = (bodyFontSizeSp - 2).coerceAtLeast(13)
        TextStyle(
            fontFamily = family,
            fontSize = fs.sp,
            lineHeight = (fs * 1.55f).sp,
            letterSpacing = bodyLs,
        )
    }

    /** 卡片 label：固定 12sp 无衬线小标签 */
    val cardLabel = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    )

    /** 章首头三档：title / 「第 x 章」/ 通知条——PageMeasurer.header 与 ReaderPageHeader 都从这里取 */
    val headerTitle = TextStyle(
        fontFamily = family,
        fontSize = 26.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
    )
    val headerOrder = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 2.sp,
    )
    val notice = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )

    override fun equals(other: Any?): Boolean =
        other is ReaderTypography && other.bodyFontSizeSp == bodyFontSizeSp && other.serif == serif

    override fun hashCode(): Int = bodyFontSizeSp * 31 + serif.hashCode()

    companion object {
        /** 首行缩进：两个全角空格。中文书籍靠缩进识别段落；
         * 在文本层加，测量量到的就是画出来的，天然同构 */
        const val INDENT = "　　"

        fun indentFirst(text: String): String =
            if (text.startsWith(INDENT)) text else INDENT + text
    }
}

/** 字号档位 → 正文 sp：小 / 标准 / 大 / 特大 */
fun readerFontSizeSp(index: Int): Int = when (index) {
    0 -> 15
    1 -> 17
    2 -> 19
    else -> 21
}

/**
 * 一页里的一段内容：KEY_POINT / 讲解卡片整块一段，STORY 块按段落切成多段。
 * [text] 是这一段真正渲染的文本（已含首行缩进——渲染的就是测量的那份文本）；
 * 卡片与关键结论不拆段，恒为整块文本。
 */
data class PageSegment(
    val block: ChapterBlock,
    val text: String,
    val firstOfBlock: Boolean,
    val lastOfBlock: Boolean,
)

/**
 * 每个块在渲染层挂的「附件」高度清单（键 = block.id）。
 *
 * 知识点 chip 行和「这一段从哪来」入口是渲染在块后面的，以前**从来没人量过它们**：
 * 带附件的块实际比测量值高一行到两行，页底被裁的全是这类块。
 * 由 ReaderScreen 在切页前按当前 dense/来源状态算好传进来，
 * 只在块的最后一段（渲染层 showAttachments 的同一条件）累加一次。
 */
class AttachmentHeights(
    private val byBlock: Map<String, Int>,
) {
    fun of(blockId: String): Int = byBlock[blockId] ?: 0
}

/**
 * FlowRow 换行高度估算：把一组条目宽度按容器宽打包成若干行，返回总高。
 *
 * 切页时无法真的跑一遍 FlowRow 布局，只能按「每个条目一行放不下就换行」估算。
 * 纯函数（给宽度就出高度）——换行规则可单测，宽度估算放调用方。
 */
internal fun estimateFlowRowHeightPx(
    itemWidthsPx: List<Int>,
    rowHeightPx: Int,
    verticalGapPx: Int,
    horizontalGapPx: Int,
    containerWidthPx: Int,
): Int {
    var rows = 0
    var usedInRow = 0
    for (w in itemWidthsPx) {
        if (w <= 0) continue
        if (usedInRow == 0) {
            if (rows == 0) rows = 1
            usedInRow = w
        } else if (usedInRow + horizontalGapPx + w <= containerWidthPx) {
            usedInRow += horizontalGapPx + w
        } else {
            rows++
            usedInRow = w
        }
    }
    if (rows == 0) return 0
    return rows * rowHeightPx + (rows - 1) * verticalGapPx
}

/**
 * 算每个块的附件（知识点 chip 行 + 来源入口）占多高，喂给切页器。
 *
 * 附件只在 dense 出 chip、来源入口按渲染层同一判据（[hasSourceEntry] 由调用方用
 * sourceSummary 的 hasAnything / deservesAttention 算好传进来）——两边条件必须一致，
 * 否则要么白留一段空高、要么又裁掉。chip 宽度用「字符数 × 字宽」粗估：宁可估宽
 * （多算一行）也别估窄，估窄才会裁。
 *
 * [fontPx] 取正文像素字号（比 chip 实际用的 labelMedium 略大 → 估算偏宽，偏安全）。
 */
internal fun buildAttachmentHeights(
    blocks: List<ChapterBlock>,
    dense: Boolean,
    nodeTitles: (ChapterBlock) -> List<String>,
    hasSourceEntry: (ChapterBlock) -> Boolean,
    density: Density,
    fontPx: Int,
    pageWidthPx: Int,
): AttachmentHeights {
    fun dp(v: androidx.compose.ui.unit.Dp): Int = with(density) { v.roundToPx() }
    val chipRowH = dp(ReaderLayout.chipRowHeight)
    val chipHGap = dp(6.dp)
    val chipVGap = dp(6.dp)
    val sourceRowH = dp(ReaderLayout.sourceRowHeight)
    val gapAbove = dp(ReaderLayout.attachmentGap)
    val map = HashMap<String, Int>()
    for (block in blocks) {
        val titles = if (dense) nodeTitles(block) else emptyList()
        // 每个 chip 文本前挂了一个图标+内边距，按 4 字符 + 24dp 宽补进去
        val chipWidths = titles.map { (it.length + 4) * fontPx + dp(24.dp) }
        val chipH = estimateFlowRowHeightPx(
            chipWidths, chipRowH, chipVGap, chipHGap, pageWidthPx,
        )
        val sourceH = if (hasSourceEntry(block)) sourceRowH else 0
        val total = when {
            chipH > 0 && sourceH > 0 -> gapAbove + chipH + dp(4.dp) + sourceH
            chipH > 0 -> gapAbove + chipH
            sourceH > 0 -> gapAbove + sourceH
            else -> 0
        }
        if (total > 0) map[block.id] = total
    }
    return AttachmentHeights(map)
}

/**
 * 翻页分页测量器。
 *
 * 分页的核心是「先量高度、再切页」：用 [androidx.compose.ui.text.TextMeasurer]
 * 在固定页宽下量出每个渲染单元的真实高度，再按页高累积切页。
 *
 * 关键约定：所有样式取自 [typography]——渲染层用的是**同一批 TextStyle 实例**，
 * 字号/行高/字距/字体族不可能发散；所有间距取自 [ReaderLayout]，
 * 渲染层的 Spacer 读的是同一份数字。以前两边各抄一份常量，抄漏一处就裁切。
 */
internal class PageMeasurer(
    private val measure: (String, TextStyle) -> Int,
    private val density: Density,
    val typography: ReaderTypography,
) {
    /** dp → px：切页引擎与渲染层共用的换算入口（ReaderLayout 的 Dp 常量经它进像素域） */
    fun dpOf(v: androidx.compose.ui.unit.Dp): Int = with(density) { v.roundToPx() }

    /** STORY 正文段 */
    fun story(text: String): Int = measure(text, typography.story)

    /** 关键结论 */
    fun keyPoint(text: String): Int = measure(text, typography.keyPoint)

    /** 旁注卡片：label 一行 + labelGap + 正文 + 上下 padding + 1dp 边框余量 */
    fun card(text: String): Int {
        val label = measure("知识讲解", typography.cardLabel)
        val body = measure(text, typography.cardBody)
        return label + dpOf(ReaderLayout.cardLabelGap) + body +
            dpOf(ReaderLayout.cardPadding) * 2 + dpOf(1.dp)
    }

    /** 章首头（仅第一页）：章名 + 「第 x 章」 + 通知条 + 与正文的间距 */
    fun header(title: String, order: String, notices: List<String>): Int {
        var h = measure(title, typography.headerTitle)
        h += dpOf(6.dp) + measure(order, typography.headerOrder)
        notices.forEach { n ->
            h += dpOf(ReaderLayout.noticeGap) +
                measure(n, typography.notice) +
                dpOf(ReaderLayout.noticePadding)
        }
        return h + dpOf(ReaderLayout.headerToBody)
    }
}

/**
 * 切页结果：正文页 + 需要滚动兜底的页下标集合。
 *
 * 独占一页的单元（比一页还高的超长卡片/段落）没法靠切页解决——渲染层读到
 * [overflowPages] 后给那一页开 verticalScroll，否则它必然裁掉页底。
 * 这个信号只能由测量阶段给出：渲染层不知道单元多高。
 */
internal class ReaderPaging(
    val pages: List<List<PageSegment>>,
    val overflowPages: Set<Int>,
)

/** 单元切页时的中间表示 */
private data class PageUnit(val segment: PageSegment, val heightPx: Int, val gapPx: Int)

/**
 * 把可见块序列切成页。
 *
 * 切页规则：
 * - STORY 段落是最小单元，可以跨页；讲解卡片 / 关键结论整块不拆，
 *   放不下就整体挪到下一页（读起来像卡片被拦腰斩断是硬伤）；
 * - 单元本身比一页还高（超长卡片）就独占一页并记进 [ReaderPaging.overflowPages]——
 *   渲染层给这些页启用兜底滚动，不再像以前那样直接裁掉页底；
 * - 附件（chip 行 / 来源入口）的高度在 [attachments] 里，按块累加进该块最后一段
 *   ——渲染层同样只在最后一段画附件（showAttachments），两边条件一致；
 * - STORY 段落的首行缩进在切段时就写进 segment.text，测量量到的字符串与渲染一字不差。
 *
 * [pageHeightPx] 必须是已扣除页码区与余量的净高。
 */
internal fun buildReaderPages(
    visibleBlocks: List<ChapterBlock>,
    measurer: PageMeasurer,
    pageHeightPx: Int,
    /** 第一页顶部章首头（章名/章序/通知条）的高度，第一页的可用空间要扣掉它 */
    headerHeightPx: Int = 0,
    attachments: AttachmentHeights = AttachmentHeights(emptyMap()),
): ReaderPaging {
    if (visibleBlocks.isEmpty()) return ReaderPaging(emptyList(), emptySet())
    val units = mutableListOf<PageUnit>()
    visibleBlocks.forEach { block ->
        when (block.blockType) {
            BlockType.STORY -> {
                val rawParagraphs = block.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                val paragraphs = if (rawParagraphs.isEmpty()) listOf(block.text) else rawParagraphs
                paragraphs.forEachIndexed { i, p ->
                    val rendered = ReaderTypography.indentFirst(p)
                    val lastOfBlock = i == paragraphs.lastIndex
                    var h = measurer.story(rendered)
                    if (lastOfBlock) h += attachments.of(block.id)
                    units += PageUnit(
                        PageSegment(
                            block, rendered,
                            firstOfBlock = i == 0,
                            lastOfBlock = lastOfBlock,
                        ),
                        h,
                        if (i == 0) measurer.dpOf(ReaderLayout.blockGap) else measurer.dpOf(ReaderLayout.paraGap),
                    )
                }
            }

            // KEY_POINT 不缩进——左侧竖条已经把它和正文分开，再缩进会歪
            BlockType.KEY_POINT -> units += PageUnit(
                PageSegment(block, block.text, true, true),
                measurer.keyPoint(block.text) + attachments.of(block.id),
                measurer.dpOf(ReaderLayout.keyPointGap),
            )

            else -> units += PageUnit(
                PageSegment(block, block.text, true, true),
                measurer.card(block.text) + attachments.of(block.id),
                measurer.dpOf(ReaderLayout.blockGap),
            )
        }
    }

    val pages = mutableListOf<List<PageSegment>>()
    val overflow = mutableSetOf<Int>()
    var current = mutableListOf<PageSegment>()
    var used = headerHeightPx // 第一页的章首头先占掉一块空间
    units.forEach { unit ->
        // 页首单元不带块间间距（页面顶部不需要空隙）
        val needed = if (current.isEmpty()) unit.heightPx else unit.gapPx + unit.heightPx
        when {
            unit.heightPx > pageHeightPx -> {
                if (current.isNotEmpty()) {
                    pages += current
                    current = mutableListOf()
                }
                pages += mutableListOf(unit.segment)
                overflow += pages.lastIndex // 这一页内容比页高——渲染层启用滚动兜底
                used = 0
            }

            used + needed <= pageHeightPx -> {
                used += needed
                current += unit.segment
            }

            else -> {
                // 章首头占了整页的空间而页里还没有正文：交出一张扉页（header 渲染在
                // 第一页上，所以它不是空白页）。以前这里连同「正文一页都放不下」的
                // 情况一起吞 current，页码照算、内容却没了——只有超页分支能造成空白页，
                // 那条已经在上面标了 overflow。
                if (current.isNotEmpty() || used > 0) pages += current
                current = mutableListOf(unit.segment)
                used = unit.heightPx
            }
        }
    }
    if (current.isNotEmpty()) pages += current
    return ReaderPaging(pages, overflow)
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
            // 页码是这一页唯一的全局信息，0.7 alpha 在米白纸面上只有 ~2.2:1
            // 对比度——半透明「看起来像水印」不等于「不干扰阅读」，直接实色
            Text(
                "${pageIndex + 1} / $pageCount",
                style = MaterialTheme.typography.labelSmall,
                color = palette.secondary,
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
                // 与章节卡、首页「继续阅读」同口径：待复核但有正文的章也算「已写好」
                "共 ${plans.count { it.openableInReader }} 章已写好 / 全书 ${plans.size} 章",
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
                        // 与章节卡 canRead 同口径：待复核但有正文的章目录里也能点。
                        // 原来这里只认 isReadable，待复核章在项目页能读、目录里却是灰的
                        val readable = plan.openableInReader
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
    serif: Boolean,
    onSerif: (Boolean) -> Unit,
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

            Text("字体", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 用每种字体本身预览「宋体/黑体」，所见即所得
                listOf(true to "宋体", false to "黑体").forEach { (serifOption, label) ->
                    val selected = serifOption == serif
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSerif(serifOption) },
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontFamily = if (serifOption) FontFamily.Serif else FontFamily.SansSerif,
                                fontSize = 17.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = palette.text)
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
                    Icon(Icons.AutoMirrored.Filled.List, "全书目录", tint = palette.text)
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
