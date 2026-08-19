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

    /**
     * 历史状态：正文已核验通过、测验没出来。
     *
     * 第二阶段起不再写入——正文过核验就直接 PUBLISHED，测验成败改由 [ChapterPlan.quizState] 表达。
     * 但旧库里可能还留着这个值，所以枚举保留，并且在 [isReadable] 里明确算「正文可读」。
     */
    QUIZ_FAILED,
    ;

    /**
     * 正文是否已经可以读。
     *
     * 故事是主流程，测验是可选辅助：正文过了核验就该能读，
     * 不能因为出题这一步失败而把章节变成打不开的状态。
     */
    val isReadable: Boolean get() = this == PUBLISHED || this == QUIZ_FAILED
}

/**
 * 本章自测题的状态。与 [ChapterStatus] 分开，正文可读性不再受出题影响。
 *
 * 单独一列而不是塞进 ChapterStatus：出题失败和正文失败是两件事，
 * 混在一个枚举里就必然出现「正文好着呢但章节打不开」。
 */
@Serializable
enum class QuizState {
    /** 还没出过题 */
    NONE,

    /** 已有可用自测题 */
    READY,

    /** 出题失败，可单独重试出题，不会重新生成正文 */
    FAILED,
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
    /**
     * 自测题状态。正文发布与出题解耦后，出题失败只体现在这里（第二阶段）。
     * 默认 NONE：老数据升级上来时都算「还没出过题」，不影响正文可读。
     */
    @ColumnInfo(defaultValue = "NONE")
    val quizState: QuizState = QuizState.NONE,
    /** 出题失败原因，给「重新生成自测」按钮旁边显示 */
    @ColumnInfo(defaultValue = "")
    val quizNote: String = "",
) {
    /** 正文是否已可阅读。测验状态一概不参与判断。 */
    val isReadable: Boolean get() = status.isReadable
}

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
