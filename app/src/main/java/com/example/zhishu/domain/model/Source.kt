package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class SourceKind {
    TEXT,
    MARKDOWN,
    PDF,
    URL
}

@Serializable
enum class ParseStatus {
    PENDING,
    PARSED,
    SCAN_ONLY,
    FAILED
}

/**
 * 一份用户资料。
 *
 * 第四阶段 4A 起这张表要能回答「这一段内容从哪来」的**前半句**：是哪份资料、
 * 哪个版本、什么时候拿到的、适用于哪个法域。后半句（哪一页、哪一节）在 [SourceChunk] 上。
 */
@Serializable
@Entity(
    tableName = "sources",
    indices = [Index("projectId"), Index("contentHash")],
)
data class SourceDocument(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val kind: SourceKind,
    val fileName: String? = null,
    val url: String? = null,
    /** 抓取/导入时间。网页资料尤其重要——同一个 URL 昨天和今天的内容可能不一样 */
    val fetchedAt: Long,
    /** 文档版本，例如「第 3 版」「2024 修订本」。由用户填写，模型不猜 */
    val versionLabel: String? = null,
    val contentHash: String,
    val parseStatus: ParseStatus,
    /**
     * 法域（4A）。例如「中国大陆」「美国加州」。
     *
     * 法律、税务、医疗这类内容离开法域就没有对错可言，
     * 而模型最容易犯的错正是把一个地区的规则讲成普适规则。
     */
    val jurisdiction: String? = null,
    /**
     * 生效时间（4A）。存字符串而不是时间戳。
     *
     * 资料上写的是「2021 年 1 月 1 日施行」，精确到日；换算成毫秒要补上时区和时分秒，
     * 那是凭空造出来的精度。这一栏是给人看的，原样保留用户写的形式最诚实。
     */
    val effectiveFrom: String? = null,
    /** 法律版本，例如「2020 年修正」「2023 年第二次修订」。与 [versionLabel] 分开：一本教材的版次和一部法律的修正次数不是一回事 */
    val legalVersion: String? = null,
) {
    /** 时效敏感资料：填了法域或生效时间，说明这份资料的适用范围有边界（4C 阅读器据此提示） */
    val isTimeSensitive: Boolean
        get() = !jurisdiction.isNullOrBlank() ||
            !effectiveFrom.isNullOrBlank() ||
            !legalVersion.isNullOrBlank()

    /** 「版本」一栏给用户看的合并展示：法律版本优先，其次文档版次 */
    val versionLabelForDisplay: String?
        get() = legalVersion?.takeIf { it.isNotBlank() } ?: versionLabel?.takeIf { it.isNotBlank() }
}

/**
 * 资料的一个片段，也是引用的最小单位。
 *
 * 4A 给它补上了「在原资料里的哪个位置」：PDF 页码、文档章节路径、段落序号。
 * 三者都是**稳定锚点**——重新解析同一份资料会得到同样的值，
 * 而字符偏移会因为一次空格清理就整体错位，所以这里一个字符偏移都不存。
 */
@Serializable
@Entity(
    tableName = "source_chunks",
    indices = [Index("sourceId"), Index("projectId")],
)
data class SourceChunk(
    @PrimaryKey val id: String,
    val sourceId: String,
    /** 冗余保存项目 ID，便于按项目直接查询与清理（B1/N6） */
    @ColumnInfo(defaultValue = "") val projectId: String = "",
    val ordinal: Int,
    val text: String,
    /**
     * 自由格式定位串。旧数据与外部导入用，新解析出来的片段一律填下面三个结构化字段。
     * 保留它是因为「导进来的备份只告诉你一句『第二册 附录 B』」这种情况确实存在。
     */
    val locator: String? = null,
    /** PDF 页码，从 1 开始。非 PDF 资料为 null（4A） */
    val pageNumber: Int? = null,
    /** 文档章节路径，例如「第三章 合同的订立 > 第二节 要约」。解析时按标题层级拼出（4A） */
    val sectionPath: String? = null,
    /** 片段起始处在原资料里是第几段，从 0 开始。回跳定位用（4A） */
    @ColumnInfo(defaultValue = "0")
    val paragraphIndex: Int = 0,
) {
    /**
     * 给用户看的定位说明，例如「第 12 页 · 第三章 > 第二节」。
     *
     * 结构化字段优先；都没有时退回自由格式 [locator]；再没有就报段落序号——
     * 「第 8 段」也比什么都不说好，至少能在原文里数下去。
     */
    val locatorLabel: String
        get() = buildList {
            pageNumber?.let { add("第 $it 页") }
            sectionPath?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (isEmpty()) {
                locator?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            if (isEmpty()) add("第 ${paragraphIndex + 1} 段")
        }.joinToString(" · ")
}
