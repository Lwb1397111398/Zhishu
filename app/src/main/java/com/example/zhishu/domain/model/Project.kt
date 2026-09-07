package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class ProjectStatus {
    DRAFT,
    SOURCES,
    MAP,
    OUTLINE,
    GENERATING,
    READY
}

/** 知识来源模式。DOCUMENTS 以用户资料为准，MODEL 由模型自备学科知识（无资料模式） */
@Serializable
enum class SourceMode {
    DOCUMENTS,
    MODEL,
}

/** 让模型自选叙事载体时使用的风格值，UI 与 prompt 都以此为准 */
const val GENRE_AUTO = "AI 自由发挥"

@Serializable
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey val id: String,
    val title: String,
    val topic: String,
    val baseline: String,
    val depth: String,
    val dailyMinutes: Int,
    val targetDays: Int,
    val genre: String,
    val status: ProjectStatus,
    val createdAt: Long,
    val updatedAt: Long,
    /** 无资料模式下由模型自备知识，核验改为自洽性检查 + 待核实清单 */
    @ColumnInfo(defaultValue = "DOCUMENTS")
    val sourceMode: SourceMode = SourceMode.DOCUMENTS,
    /** 模型为本项目自选/沿用的叙事风格，genre 为自由发挥时由故事总纲阶段回填 */
    @ColumnInfo(defaultValue = "")
    val resolvedGenre: String = "",
    /** 为什么学习这门学科，例如工作、考试、研究或兴趣 */
    @ColumnInfo(defaultValue = "")
    val learningPurpose: String = "",
    /** 学习者身份与使用场景，例如民商法研究生、法考考生 */
    @ColumnInfo(defaultValue = "")
    val learnerIdentity: String = "",
    /** 学完希望达到的可观察结果 */
    @ColumnInfo(defaultValue = "")
    val learningGoal: String = "",
) {
    val usesModelKnowledge: Boolean get() = sourceMode == SourceMode.MODEL

    /** 展示与提示词里实际使用的风格 */
    val effectiveGenre: String
        get() = resolvedGenre.ifBlank { genre }

    /**
     * 每章目标字数。按每日阅读时长换算（中文阅读约 400 字/分钟，留出思考停顿取 300）。
     * 一章承载一整条演进链，所以下限设得比原来高——太短的章节必然写成知识点罗列。
     */
    val wordTargetPerChapter: Int
        get() = (dailyMinutes * 300).coerceIn(1200, 4000)
}
