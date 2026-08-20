package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class QuizType {
    RECALL,
    TRUE_FALSE,
    CASE
}

@Serializable
enum class ReviewStatus {
    PENDING,
    DONE
}

@Serializable
@Entity(
    tableName = "quizzes",
    indices = [Index("projectId"), Index("chapterId"), Index("versionId")],
)
data class Quiz(
    @PrimaryKey val id: String,
    val projectId: String,
    val chapterId: String,
    /**
     * 出这道题时依据的正文版本（第三阶段 3A）。
     *
     * 不绑版本的话，重生成正文之后旧题会挂在新正文上——题目问的是已经不存在的情节。
     * 空串表示历史数据（旧备份导入时会被补成当前版本），此时按章节走。
     */
    @ColumnInfo(defaultValue = "") val versionId: String = "",
    val order: Int,
    val question: String,
    val type: QuizType,
    val options: List<String> = emptyList(),
    val answer: String,
    val explanation: String = "",
    val sourceRefs: List<String> = emptyList(),
    val nodeIds: List<String> = emptyList(),
)

@Serializable
@Entity(
    tableName = "answer_records",
    // quizId 唯一：一题只保留一条答题记录，掌握度只计一次（N12）
    indices = [Index("quizId", unique = true), Index("projectId")],
)
data class AnswerRecord(
    @PrimaryKey val id: String,
    val quizId: String,
    val projectId: String,
    val isCorrect: Boolean,
    val answeredAt: Long,
)

@Serializable
@Entity(
    tableName = "review_items",
    indices = [Index("projectId"), Index("nodeId")],
)
data class ReviewItem(
    @PrimaryKey val id: String,
    val projectId: String,
    val nodeId: String,
    val dueAt: Long,
    val intervalDays: Int = 1,
    val status: ReviewStatus = ReviewStatus.PENDING,
)

@Serializable
@Entity(
    tableName = "reading_progress",
    indices = [Index("projectId")],
)
data class ReadingProgress(
    @PrimaryKey val id: String,
    val projectId: String,
    val chapterId: String,
    /** 上次停在哪个正文块。阅读器按块恢复位置，长章节与安静/密集切换都不会跑偏 */
    val blockId: String? = null,
    /**
     * 历史字段：早期保存的是整章滚动像素值。像素值换机型、换字号就失效，
     * 也和搜索用的字符位置不是一个坐标系，因此不再写入，只留着不破坏旧数据。
     */
    val charOffset: Int = 0,
    /** [blockId] 这一块顶部相对视口的偏移（像素），只在同一块内部微调位置 */
    @ColumnInfo(defaultValue = "0")
    val blockOffset: Int = 0,
    /**
     * 这一章的正文最近一次被换成新版本的时刻（3D）。0 表示从来没换过。
     *
     * 用来给一次性提示「本章已更新，从章首开始阅读」提供依据。
     * 只清掉 blockId 是不够的：用户会看到自己明明读到一半、回来却在开头，
     * 而页面对此一言不发——那看起来就是进度丢了。
     * 提示看过之后由 [progressNoticeShown] 记下来，不再重复出现。
     */
    @ColumnInfo(defaultValue = "0")
    val bodyUpdatedAt: Long = 0L,
    /** [bodyUpdatedAt] 对应的提示是否已经给用户看过。看过就不再提 */
    @ColumnInfo(defaultValue = "0")
    val progressNoticeShown: Boolean = false,
    val updatedAt: Long,
) {
    /** 该不该给一次「本章已更新」的提示 */
    val needsBodyUpdatedNotice: Boolean
        get() = bodyUpdatedAt > 0L && !progressNoticeShown
}
