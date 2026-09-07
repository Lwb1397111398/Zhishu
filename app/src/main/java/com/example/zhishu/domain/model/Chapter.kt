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
    val wordTarget: Int = 1800,
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
     * 本章开始时如何承接前文（第三阶段 3B）。
     *
     * 属于「计划」：规划讲述路线时定下，重生成正文不会改它。
     * 与之相对，「本章结束后的故事状态」属于**具体那一版正文**，存在
     * [ChapterVersion.endingState] 上——重生成出来的结尾会变，
     * 只有当前 active version 的结尾才能当作后续章节的上下文。
     */
    @ColumnInfo(defaultValue = "")
    val openingHook: String = "",
    /**
     * 历史字段：曾经同时表示两件事——规划期存「下一章的开头钩子」，
     * 发布后存「本章结束后的故事状态」。同一列两种含义，读的时候只能靠章节状态去猜。
     *
     * 第三阶段 3B 起彻底拆开：开头钩子 → [openingHook]，结尾状态 → [ChapterVersion.endingState]。
     * 这一列只为旧数据库升级与旧备份导入而保留，业务代码一律不读不写
     * （`CarryOverSplitTest` 有一条架构断言在守这件事，3D）。
     */
    @Deprecated(
        message = "语义已拆分：开头钩子用 openingHook，结尾状态用 ChapterVersion.endingState。" +
            "这一列只用于旧数据库/旧备份兼容，业务代码不要读写。",
        replaceWith = ReplaceWith("openingHook"),
    )
    @ColumnInfo(defaultValue = "")
    val carryOver: String = "",
    /** 本章实际讲到的知识点标题。只用于展示与旧数据兼容，判断「讲过没讲过」看 [coveredNodeIds] */
    @ColumnInfo(defaultValue = "")
    val coveredTitles: List<String> = emptyList(),
    /**
     * 本章实际讲到的知识点 ID（3D）。
     *
     * 覆盖关系的唯一判据。标题会被用户改名，ID 不会——
     * 按标题判断的后果是改一次名字，讲过的知识点全变回「还没讲」，续卷把它们再排一遍。
     */
    @ColumnInfo(defaultValue = "")
    val coveredNodeIds: List<String> = emptyList(),
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
    /**
     * 本章属于第几卷（第三阶段 3C）。从 0 开始，0 就是第一卷。
     *
     * 分卷不是为了排版，是为了让「一次规划的量」有上限：首次只规划一卷 4~8 章，
     * 读完再按当前状态规划下一卷。这样既不需要让模型一次想出整个主题的所有章节，
     * 也不必把 MAX_CHAPTERS 直接改成很大。
     */
    @ColumnInfo(defaultValue = "0")
    val volumeIndex: Int = 0,
    @ColumnInfo(defaultValue = "")
    val volumeTitle: String = "",
    /** 这一卷要走完的目标，续卷规划时用来判断「上一卷讲到哪了」 */
    @ColumnInfo(defaultValue = "")
    val volumeGoal: String = "",
    /**
     * 当前对读者生效的正文版本（第三阶段 3A）。
     *
     * 阅读、搜索、知识点跳转一律走这个指针，而不是按 chapterId 直接捞全部正文块——
     * 否则重生成过的章节会把新旧两版正文混在一起显示。
     * 为 null 表示这一章还没有任何通过核验的正文。
     */
    val activeVersionId: String? = null,
    /**
     * 本章类型（第二轮 P1-2）：INTRODUCE / APPLY / CONTRAST / REVIEW / ASSESS。
     * 空串 = 未指定（旧数据/旧 Prompt 生成的章节），阅读器据此显示「未生成因果契约」。
     */
    @ColumnInfo(defaultValue = "")
    val chapterType: String = "",
    /**
     * 本章因果契约的 JSON（第二轮 P1-2）。
     *
     * 路线规划时生成、正文模型按它写、核验模型拿它逐项检查，所以必须落库才能跨
     * Worker 传递。空串 = 未生成契约（旧数据），读取方明确显示「未生成因果契约」，
     * 不得伪造一份。内容为 [com.example.zhishu.ai.outputs.ChapterContractDto] 的序列化。
     */
    @ColumnInfo(defaultValue = "")
    val contractJson: String = "",
    /**
     * 契约生成状态（三段式改造）。
     *
     * 大纲与契约拆开后，一章失败不再连累整卷：失败的章标 FAILED，
     * 重试只补它；DONE 之前禁止写正文——契约是正文的因果骨架，
     * 「有正文必有契约」这条底线靠这一列把守。
     * 默认 DONE：老数据已有 contractJson（或明确无契约），不能被误判成待生成。
     */
    @ColumnInfo(defaultValue = "DONE")
    val contractStatus: String = ContractStatus.DONE.name,
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
    indices = [Index("chapterId"), Index("projectId"), Index("versionId")],
)
data class ChapterBlock(
    @PrimaryKey val id: String,
    val chapterId: String,
    /**
     * 这一块属于哪个正文版本（第三阶段 3A）。
     *
     * chapterId 保留是为了「删章节连带删正文」这类按章操作仍然直接可用，
     * 但**读取正文一律按 versionId**：一章可能有好几版正文，
     * 按 chapterId 捞出来的是全部历史，不是当前正文。
     */
    @ColumnInfo(defaultValue = "") val versionId: String = "",
    /** 冗余保存项目 ID，使删除项目时可直接清理正文（N6） */
    @ColumnInfo(defaultValue = "") val projectId: String = "",
    val order: Int,
    val text: String,
    val blockType: BlockType = BlockType.STORY,
    val knowledgeNodeIds: List<String> = emptyList(),
    val sourceRefs: List<String> = emptyList(),
)
