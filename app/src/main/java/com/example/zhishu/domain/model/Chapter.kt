package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class ChapterStatus {
    QUEUED,
    RUNNING,
    DRAFT,
    VERIFYING,
    PUBLISHED,
    FAILED,
    NEEDS_REVIEW,

    /** 正文已核验通过，但测验生成失败，需要重试（N2） */
    QUIZ_FAILED,
}

@Serializable
@Entity(
    tableName = "chapters",
    indices = [Index("projectId")],
)
data class ChapterPlan(
    @PrimaryKey val id: String,
    val projectId: String,
    val order: Int,
    val title: String,
    val plotGoal: String = "",
    val learningGoals: List<String> = emptyList(),
    val nodeIds: List<String> = emptyList(),
    val wordTarget: Int = 1200,
    val status: ChapterStatus = ChapterStatus.QUEUED,
    val summary: String = "",
    val verificationNote: String = "",
    /**
     * 本章要走完的推演链：一句话说明「从哪个已有制度出发 → 遇到什么新问题 → 长出哪个新制度」。
     * 这是每章承载一整个知识群而不是一两个零散考点的关键（问题7）。
     */
    @ColumnInfo(defaultValue = "")
    val derivationChain: String = "",
    /**
     * 上一章结束时的世界状态：人物处境、已埋伏笔、留给本章的悬念。
     * 生成时只喂这一段而不是全部前文，让每章上下文恒定（问题5）。
     */
    @ColumnInfo(defaultValue = "")
    val carryOver: String = "",
    /** 本章实际讲到的知识点标题，供后续章节避免重复讲解 */
    @ColumnInfo(defaultValue = "")
    val coveredTitles: List<String> = emptyList(),
    /** 无资料模式下模型自报的「需要你去核对权威出处」的断言 */
    @ColumnInfo(defaultValue = "")
    val unverifiedClaims: List<String> = emptyList(),
)

@Serializable
enum class BlockType {
    STORY,
    EXPLANATION,
    FACT,
    ANALOGY,
    FICTION,

    /** 钉住一个结论的独立重述句，阅读器里以引用块样式突出显示（问题2） */
    KEY_POINT,
}

@Serializable
@Entity(
    tableName = "chapter_blocks",
    indices = [Index("chapterId"), Index("projectId")],
)
data class ChapterBlock(
    @PrimaryKey val id: String,
    val chapterId: String,
    /** 冗余保存项目 ID，使删除项目时可直接清理正文（N6） */
    @ColumnInfo(defaultValue = "") val projectId: String = "",
    val order: Int,
    val text: String,
    val blockType: BlockType = BlockType.STORY,
    val knowledgeNodeIds: List<String> = emptyList(),
    val sourceRefs: List<String> = emptyList(),
)
