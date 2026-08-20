package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 一条引用的可信程度（4A）。
 *
 * 只有两个值，而且必须分开：模型给出的引用标识**经常**对不上任何真实片段
 * （编一个像样的 ID 对它来说毫无成本）。这种引用既不能悄悄丢掉——那样正文里
 * 那句需要出处的话就变成了没人管的断言；也不能当成有效引用——那是拿一个
 * 不存在的出处给读者壮胆。所以如实记成「未确认」，让阅读器能说出
 * 「这一段模型说有出处，但我们没能对上」。
 */
@Serializable
enum class CitationStatus {
    /** 解析到了真实的 [SourceChunk]，可以点开看原文 */
    RESOLVED,

    /** 模型给的标识对不上任何片段。原样留着 [Citation.rawRef]，不伪装成已引用 */
    UNCONFIRMED,
    ;

    val isResolved: Boolean get() = this == RESOLVED
}

/**
 * 正文块与资料片段之间的引用关系（第四阶段 4A）。
 *
 * 为什么要单独一张表，而不是继续只用 [ChapterBlock.sourceRefs] 那个列表列：
 *
 * 1. **未确认的引用需要有地方存。** `sourceRefs` 只能装「解析成功的片段 ID」，
 *    对不上的引用以前是被 `filter` 静默丢掉的——问题就此消失，读者也就永远
 *    不知道这一段的出处其实没对上。
 * 2. **反向查询要能走索引。** 「这份资料的这一段被正文哪几块引用了」是 4A 要求的
 *    回跳方向。把引用埋在 JSON 列表列里，这个方向只能全表扫描 + 字符串匹配。
 * 3. **引用属于某一版正文。** 绑定键是 `versionId + blockId + chunkId`：
 *    重新生成会产出全新的 blockId，旧版本的引用一条都不删（历史版本要能查），
 *    但当前阅读只看 active version 的那些。
 */
@Serializable
@Entity(
    tableName = "citations",
    indices = [
        Index(value = ["versionId"]),
        Index(value = ["blockId"]),
        Index(value = ["chunkId"]),
        Index(value = ["projectId"]),
    ],
)
data class Citation(
    /**
     * `blockId#order`。
     *
     * 用确定性 ID 而不是随机 UUID：迁移回填、备份导入、重新生成三条路径都要能算出
     * 同一条引用的同一个 ID，否则同一份数据从不同入口进来会留下两份重复引用。
     */
    @PrimaryKey val id: String,
    val projectId: String,
    val chapterId: String,
    /** 这条引用属于哪一版正文。历史版本的引用保留，但不参与当前阅读与统计 */
    val versionId: String,
    val blockId: String,
    /**
     * 解析到的真实片段 ID。[CitationStatus.UNCONFIRMED] 时为 null。
     *
     * 绝不在这里填一个「看起来最接近」的片段——那正是「不允许模型凭空生成来源」
     * 想要防住的事，只不过换成由我们自己来编。
     */
    val chunkId: String? = null,
    /** 模型原样给出的引用标识。未确认时它是唯一线索，必须留着给用户看 */
    @ColumnInfo(defaultValue = "")
    val rawRef: String = "",
    val status: CitationStatus = CitationStatus.RESOLVED,
    /** 块内顺序，同时参与 [id] 的构造 */
    @ColumnInfo(defaultValue = "0")
    val order: Int = 0,
) {
    companion object {
        /** 与 [id] 的构造规则一一对应。迁移与导入也用它，保证三条路径算出同一个 ID */
        fun idFor(blockId: String, order: Int): String = "$blockId#$order"
    }
}
