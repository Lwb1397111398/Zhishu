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
    indices = [Index("projectId"), Index("chapterId")],
)
data class Quiz(
    @PrimaryKey val id: String,
    val projectId: String,
    val chapterId: String,
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
    val updatedAt: Long,
)
