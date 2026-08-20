package com.example.zhishu.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 故事总纲 + 滚动记忆（问题5）。
 *
 * 一个项目只有一行。生成第 N 章时喂给模型的上下文固定为：
 * 本行（压缩后的全局设定 + 累积状态）+ 上一章**当前版本**的
 * [ChapterVersion.endingState] + 本章知识群，**不携带任何章节正文**。
 * 因此单章 token 成本与已生成章数无关，生成到几百章也不会膨胀。
 */
@Serializable
@Entity(tableName = "story_bibles")
data class StoryBible(
    @PrimaryKey val projectId: String,
    /** 世界观设定，压缩到 [WORLD_BUDGET] 字以内 */
    val world: String = "",
    /** 核心矛盾 / 全书要回答的根问题 */
    val conflict: String = "",
    /** 模型最终采用的叙事风格（自由发挥模式下由模型自选） */
    val style: String = "",
    /** 叙事载体说明：为什么这个学科适合这样讲 */
    val approach: String = "",
    /** 人物档案，「姓名｜身份｜一句话状态」逐行保存 */
    val characters: List<String> = emptyList(),
    /** 全书的制度演进主线，逐条列出「阶段 → 解决的问题」 */
    val throughLine: List<String> = emptyList(),
    /**
     * 累积状态摘要：随章节推进被模型改写而不是追加，长度受 [RUNNING_BUDGET] 限制。
     * 这是「后面的故事顺着前面发展、又不断开」与「上下文不膨胀」能同时成立的关键。
     */
    val runningState: String = "",
    /** 已讲完的知识点标题台账。只用于喂给模型「别重复讲」，判断覆盖看 [coveredNodeIds] */
    val coveredTitles: List<String> = emptyList(),
    /**
     * 已讲完的知识点 ID 台账（3D）。
     *
     * 覆盖关系的判据。标题会被用户改名，ID 不会——
     * 只按标题记账的话，改一次知识点名字，讲过的内容就全变回「还没讲」。
     */
    @androidx.room.ColumnInfo(defaultValue = "")
    val coveredNodeIds: List<String> = emptyList(),
    /** 尚未展开的悬念钩子，下一章优先回收 */
    val openThreads: List<String> = emptyList(),
    /**
     * 已规划到第几卷（第三阶段 3C）。从 0 开始，0 表示只规划了第一卷。
     * 续卷时递增，新卷的章节 order 一律接在现有章节之后，绝不与旧章节冲突。
     */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val currentVolumeIndex: Int = 0,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val WORLD_BUDGET = 400
        const val RUNNING_BUDGET = 700
        const val MAX_CHARACTERS = 8
        const val MAX_OPEN_THREADS = 6
    }
}
