package com.example.zhishu.domain.repository

import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ModelConfig
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.StoryBible
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    fun observeAll(): Flow<List<Project>>
    suspend fun get(id: String): Project?
    suspend fun create(project: Project)
    suspend fun update(project: Project)
    suspend fun delete(id: String)
}

interface SourceRepository {
    fun observeByProject(projectId: String): Flow<List<SourceDocument>>
    suspend fun getByProject(projectId: String): List<SourceDocument>
    suspend fun addSource(source: SourceDocument, chunks: List<SourceChunk>)
    suspend fun getChunks(sourceId: String): List<SourceChunk>
    /** 按项目取全部片段（B1） */
    suspend fun getChunksByProject(projectId: String): List<SourceChunk>
    /** 按片段 ID 精确取（B1） */
    suspend fun getChunksByIds(projectId: String, ids: Collection<String>): List<SourceChunk>
    suspend fun countChunks(projectId: String): Int
    suspend fun findDuplicate(hash: String): SourceDocument?
    suspend fun delete(sourceId: String)
    suspend fun deleteByProject(projectId: String)
}

interface KnowledgeRepository {
    fun observeByProject(projectId: String): Flow<List<KnowledgeNode>>
    suspend fun getByProject(projectId: String): List<KnowledgeNode>
    suspend fun upsertAll(nodes: List<KnowledgeNode>)
    suspend fun upsert(node: KnowledgeNode)
    suspend fun updateMastery(id: String, mastery: Float)
    /** 只更新确认位，不覆盖生成流程写入的其他字段（N13） */
    suspend fun setConfirmed(id: String, confirmed: Boolean)
    suspend fun updateChapterIds(id: String, chapterIds: List<String>)
    suspend fun clearForProject(projectId: String)

    // 知识缺口与冲突（B12）
    fun observeGaps(projectId: String): Flow<List<KnowledgeGap>>
    fun observeConflicts(projectId: String): Flow<List<KnowledgeConflict>>
    suspend fun replaceInsights(
        projectId: String,
        gaps: List<KnowledgeGap>,
        conflicts: List<KnowledgeConflict>,
    )
}

interface ChapterRepository {
    fun observePlans(projectId: String): Flow<List<ChapterPlan>>
    suspend fun getPlans(projectId: String): List<ChapterPlan>
    suspend fun getPlan(id: String): ChapterPlan?
    suspend fun clearPlansForProject(projectId: String)
    suspend fun clearBlocksForProject(projectId: String)
    suspend fun clearBlocksForChapter(chapterId: String)
    suspend fun upsertPlans(plans: List<ChapterPlan>)
    suspend fun upsertPlan(plan: ChapterPlan)
    /** 只写状态与核验说明，由 Pipeline/Worker 使用（N13） */
    suspend fun updateStatus(id: String, status: ChapterStatus, note: String = "")
    suspend fun markPublished(id: String, summary: String)
    /** 发布并写入滚动记忆，供下一章续写（问题5） */
    suspend fun markPublished(
        id: String,
        summary: String,
        carryOver: String,
        coveredTitles: List<String>,
        unverifiedClaims: List<String>,
        note: String = "",
    )
    /** 只写用户可编辑字段，由 UI 使用（N13） */
    suspend fun updateEditableFields(
        id: String,
        title: String,
        plotGoal: String,
        learningGoals: List<String>,
        wordTarget: Int,
    )
    fun observeBlocks(chapterId: String): Flow<List<ChapterBlock>>
    suspend fun getBlocks(chapterId: String): List<ChapterBlock>
    /** 全项目正文，用于建立知识点到正文块的跳转锚点 */
    suspend fun getBlocksByProject(projectId: String): List<ChapterBlock>
    suspend fun countBlocks(projectId: String): Int
    suspend fun upsertBlocks(blocks: List<ChapterBlock>)
}

/** 故事总纲与滚动记忆（问题5）。一个项目一行。 */
interface StoryBibleRepository {
    suspend fun get(projectId: String): StoryBible?
    fun observe(projectId: String): Flow<StoryBible?>
    suspend fun save(bible: StoryBible)
    /** 只推进滚动记忆，保留世界观等固定设定 */
    suspend fun updateMemory(
        projectId: String,
        runningState: String,
        coveredTitles: List<String>,
        openThreads: List<String>,
        updatedAt: Long,
    )
    suspend fun clearForProject(projectId: String)
}

interface QuizRepository {
    fun observeByChapter(chapterId: String): Flow<List<Quiz>>
    suspend fun getByChapter(chapterId: String): List<Quiz>
    suspend fun getByProject(projectId: String): List<Quiz>
    suspend fun upsertAll(quizzes: List<Quiz>)
    suspend fun deleteByChapter(chapterId: String)
    suspend fun recordAnswer(record: AnswerRecord)
    suspend fun countCorrect(quizId: String): Int
    /** 已有答题记录，用于恢复页面状态并保证幂等计分（N12） */
    suspend fun getAnswers(quizIds: List<String>): List<AnswerRecord>
    suspend fun hasAnswered(quizId: String): Boolean
    suspend fun deleteByProject(projectId: String)
}

interface ReviewRepository {
    fun observeByProject(projectId: String): Flow<List<ReviewItem>>
    suspend fun getDue(now: Long): List<ReviewItem>
    suspend fun getByProject(projectId: String): List<ReviewItem>
    suspend fun upsertAll(items: List<ReviewItem>)
    suspend fun clearForProject(projectId: String)
}

interface ProgressRepository {
    suspend fun get(projectId: String): ReadingProgress?
    suspend fun save(progress: ReadingProgress)
    suspend fun clearForProject(projectId: String)
}

interface SettingsRepository {
    suspend fun getAllConfigs(): List<ModelConfig>
    suspend fun getConfig(role: ModelRole): ModelConfig?
    suspend fun saveConfig(config: ModelConfig)
    suspend fun clearConfigs()
}
