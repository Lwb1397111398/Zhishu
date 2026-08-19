package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 一个正文版本的状态（第三阶段 3A）。
 *
 * 与 [ChapterStatus] 的分工：章节状态说的是「这一章对读者是什么样」，
 * 版本状态说的是「这一份正文自己走到哪一步」。重生成一个已发布章节时，
 * 章节始终停在 PUBLISHED（旧正文照样能读），新版本自己在 DRAFT → VERIFYING 里走。
 */
@Serializable
enum class ChapterVersionStatus {
    /** 正文块正在写入 */
    DRAFT,

    /** 正文已写完，正在核验 */
    VERIFYING,

    /** 通过核验，可以作为 active version */
    PUBLISHED,

    /**
     * 放弃：核验没过、用户取消，或被更新的版本取代。
     * 放弃的版本不出现在阅读器和默认搜索里，但正文块一律保留，绝不删。
     */
    ABANDONED,
    ;

    /** 只有发布过的版本才可以被指为 activeVersionId */
    val canBeActive: Boolean get() = this == PUBLISHED
}

/**
 * 一次正文生成的产物（第三阶段 3A）。
 *
 * 为什么要有这张表：第二阶段留下的最大风险是重生成会就地覆盖已发布正文——
 * 清旧块、写新块，然后核验不过或用户取消，旧正文就再也回不来了。
 * 现在改成「新版本单独写、核验通过才切指针」：
 *
 * ```
 * 建新版本(DRAFT) → 写新版本的 blocks → VERIFYING → 核验通过
 *   → 事务内 PUBLISHED + chapters.activeVersionId 指向它 + 旧版本 ABANDONED
 * ```
 *
 * 中途任何一步失败或被取消，activeVersionId 一个字都不动，旧正文继续可读。
 *
 * 正文元数据（标题、摘要、结尾状态、已覆盖知识点、核验说明）跟着版本走而不是跟着章节走：
 * 重生成出来的结尾可能和旧版本完全不同，只有当前 active version 的结尾
 * 才能当作后续章节的上下文。
 */
@Serializable
@Entity(
    tableName = "chapter_versions",
    indices = [
        Index(value = ["chapterId"]),
        Index(value = ["chapterId", "status"]),
        Index(value = ["projectId"]),
    ],
)
data class ChapterVersion(
    @PrimaryKey val id: String,
    val chapterId: String,
    /** 冗余保存项目 ID，删项目时可直接清理，不依赖 chapters 表是否还在（同 [ChapterBlock]） */
    @ColumnInfo(defaultValue = "") val projectId: String = "",
    /** 同一章内自增，从 1 开始。给用户看「第 3 次生成」这种话用 */
    val versionNumber: Int,
    val status: ChapterVersionStatus,
    val title: String = "",
    val summary: String = "",
    /**
     * 本版本正文结束时留下的故事状态。
     *
     * 这是 3B 拆分 carryOver 的落点之一：结尾状态属于「具体这一份正文」，
     * 重生成后新结尾会变，所以不能存在章节行上。
     */
    val endingState: String = "",
    val coveredTitles: List<String> = emptyList(),
    val unverifiedClaims: List<String> = emptyList(),
    /** 本版本自己的核验问题。不覆盖当前发布版本的说明——那是另一份正文的事 */
    val verificationNote: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)
