package com.example.zhishu.data.local.repo

import com.example.zhishu.data.local.dao.AnswerRecordDao
import com.example.zhishu.data.local.dao.ChapterBlockDao
import com.example.zhishu.data.local.dao.ChapterPlanDao
import com.example.zhishu.data.local.dao.ChapterVersionDao
import com.example.zhishu.data.local.dao.CitationDao
import com.example.zhishu.data.local.dao.KnowledgeConflictDao
import com.example.zhishu.data.local.dao.KnowledgeGapDao
import com.example.zhishu.data.local.dao.KnowledgeNodeDao
import com.example.zhishu.data.local.dao.ModelConfigDao
import com.example.zhishu.data.local.dao.ProjectDao
import com.example.zhishu.data.local.dao.QuizDao
import com.example.zhishu.data.local.dao.ReadingProgressDao
import com.example.zhishu.data.local.dao.ReviewItemDao
import com.example.zhishu.data.local.dao.SourceChunkDao
import com.example.zhishu.data.local.dao.SourceDocumentDao
import com.example.zhishu.data.local.dao.StoryBibleDao
import com.example.zhishu.data.local.dao.VerificationIssueDao
import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.IssueResolution
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ModelConfig
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.QuizState
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.ReviewStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.StoryBible
import com.example.zhishu.domain.model.VerificationIssue
import com.example.zhishu.domain.repository.ChapterRepository
import com.example.zhishu.domain.repository.CitationRepository
import com.example.zhishu.domain.repository.KnowledgeRepository
import com.example.zhishu.domain.repository.ProgressRepository
import com.example.zhishu.domain.repository.ProjectRepository
import com.example.zhishu.domain.repository.QuizRepository
import com.example.zhishu.domain.repository.ReviewRepository
import com.example.zhishu.domain.repository.SettingsRepository
import com.example.zhishu.domain.repository.SourceRepository
import com.example.zhishu.domain.repository.StoryBibleRepository
import com.example.zhishu.domain.repository.VerificationIssueRepository
import kotlinx.coroutines.flow.Flow

class DefaultProjectRepository(private val dao: ProjectDao) : ProjectRepository {
    override fun observeAll(): Flow<List<Project>> = dao.observeAll()
    override suspend fun get(id: String): Project? = dao.getById(id)
    override suspend fun create(project: Project) = dao.upsert(project)
    override suspend fun update(project: Project) = dao.upsert(project)
    override suspend fun delete(id: String) = dao.deleteById(id)
}

class DefaultSourceRepository(
    private val documentDao: SourceDocumentDao,
    private val chunkDao: SourceChunkDao,
) : SourceRepository {
    override fun observeByProject(projectId: String): Flow<List<SourceDocument>> =
        documentDao.observeByProject(projectId)

    override suspend fun getByProject(projectId: String): List<SourceDocument> =
        documentDao.getByProject(projectId)

    override suspend fun addSource(source: SourceDocument, chunks: List<SourceChunk>) {
        documentDao.upsert(source)
        // 片段一律带上 projectId，保证按项目查询可用（B1）
        chunkDao.upsertAll(chunks.map { it.copy(projectId = source.projectId) })
    }

    override suspend fun getChunks(sourceId: String): List<SourceChunk> =
        chunkDao.getBySource(sourceId)

    override suspend fun getChunksByProject(projectId: String): List<SourceChunk> =
        chunkDao.getByProject(projectId)

    override suspend fun getChunksByIds(projectId: String, ids: Collection<String>): List<SourceChunk> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(SQLITE_VARIABLE_LIMIT).flatMap { batch ->
            chunkDao.getByIds(projectId, batch)
        }
    }

    override suspend fun countChunks(projectId: String): Int = chunkDao.countByProject(projectId)

    override suspend fun findDuplicate(hash: String): SourceDocument? =
        documentDao.findByHash(hash)

    override suspend fun updateMeta(
        sourceId: String,
        versionLabel: String?,
        jurisdiction: String?,
        effectiveFrom: String?,
        legalVersion: String?,
    ) = documentDao.updateMeta(
        id = sourceId,
        // 空白一律存 null：数据库里躺着一个空字符串和「没填」是同一件事，
        // 但判断「填了没填」时空字符串会算成填了
        versionLabel = versionLabel?.trim()?.takeIf { it.isNotEmpty() },
        jurisdiction = jurisdiction?.trim()?.takeIf { it.isNotEmpty() },
        effectiveFrom = effectiveFrom?.trim()?.takeIf { it.isNotEmpty() },
        legalVersion = legalVersion?.trim()?.takeIf { it.isNotEmpty() },
    )

    override suspend fun delete(sourceId: String) {
        chunkDao.deleteBySource(sourceId)
        documentDao.deleteById(sourceId)
    }

    override suspend fun deleteByProject(projectId: String) {
        chunkDao.deleteByProject(projectId)
        documentDao.deleteByProject(projectId)
    }

    private companion object {
        // SQLite 默认最多 999 个绑定参数
        const val SQLITE_VARIABLE_LIMIT = 900
    }
}

class DefaultKnowledgeRepository(
    private val dao: KnowledgeNodeDao,
    private val gapDao: KnowledgeGapDao,
    private val conflictDao: KnowledgeConflictDao,
) : KnowledgeRepository {
    override fun observeByProject(projectId: String): Flow<List<KnowledgeNode>> =
        dao.observeByProject(projectId)

    override suspend fun getByProject(projectId: String): List<KnowledgeNode> =
        dao.getByProject(projectId)

    override suspend fun upsertAll(nodes: List<KnowledgeNode>) = dao.upsertAll(nodes)
    override suspend fun upsert(node: KnowledgeNode) = dao.upsert(node)
    override suspend fun updateMastery(id: String, mastery: Float) = dao.updateMastery(id, mastery)
    override suspend fun setConfirmed(id: String, confirmed: Boolean) = dao.updateConfirmed(id, confirmed)
    override suspend fun updateChapterIds(id: String, chapterIds: List<String>) =
        dao.updateChapterIds(id, chapterIds)

    override suspend fun clearForProject(projectId: String) {
        dao.clearByProject(projectId)
        gapDao.clearByProject(projectId)
        conflictDao.clearByProject(projectId)
    }

    override fun observeGaps(projectId: String): Flow<List<KnowledgeGap>> =
        gapDao.observeByProject(projectId)

    override fun observeConflicts(projectId: String): Flow<List<KnowledgeConflict>> =
        conflictDao.observeByProject(projectId)

    override suspend fun replaceInsights(
        projectId: String,
        gaps: List<KnowledgeGap>,
        conflicts: List<KnowledgeConflict>,
    ) {
        gapDao.clearByProject(projectId)
        conflictDao.clearByProject(projectId)
        if (gaps.isNotEmpty()) gapDao.upsertAll(gaps)
        if (conflicts.isNotEmpty()) conflictDao.upsertAll(conflicts)
    }
}

class DefaultChapterRepository(
    private val planDao: ChapterPlanDao,
    private val blockDao: ChapterBlockDao,
    private val versionDao: ChapterVersionDao,
) : ChapterRepository {
    override fun observePlans(projectId: String): Flow<List<ChapterPlan>> =
        planDao.observeByProject(projectId)

    override suspend fun getPlans(projectId: String): List<ChapterPlan> =
        planDao.getByProject(projectId)

    override suspend fun getPlan(id: String): ChapterPlan? = planDao.getById(id)
    override suspend fun clearPlansForProject(projectId: String) = planDao.clearByProject(projectId)
    override suspend fun clearBlocksForProject(projectId: String) = blockDao.deleteByProject(projectId)
    override suspend fun clearBlocksForChapter(chapterId: String) = blockDao.deleteByChapter(chapterId)
    override suspend fun upsertPlans(plans: List<ChapterPlan>) = planDao.upsertAll(plans)
    override suspend fun upsertPlan(plan: ChapterPlan) = planDao.upsert(plan)

    override suspend fun updateStatus(id: String, status: ChapterStatus, note: String) =
        planDao.updateStatus(id, status, note)

    override suspend fun updateStatusIfIn(
        id: String,
        status: ChapterStatus,
        note: String,
        onlyIf: List<ChapterStatus>,
    ): Boolean = planDao.updateStatusIfIn(id, status, note, onlyIf) > 0

    override suspend fun updateQuizState(id: String, state: QuizState, note: String) =
        planDao.updateQuizState(id, state, note)

    override suspend fun updateEditableFields(
        id: String,
        title: String,
        plotGoal: String,
        learningGoals: List<String>,
        wordTarget: Int,
    ) = planDao.updateEditableFields(id, title, plotGoal, learningGoals, wordTarget)

    // --- 正文读取（第三阶段 3A，命名在 3D 收紧）---

    override fun observeActiveBlocks(chapterId: String): Flow<List<ChapterBlock>> =
        blockDao.observeActiveBlocks(chapterId)

    override suspend fun getActiveBlocks(chapterId: String): List<ChapterBlock> =
        blockDao.getActiveBlocks(chapterId)

    override suspend fun getBlocksByVersion(versionId: String): List<ChapterBlock> =
        blockDao.getByVersion(versionId)

    override suspend fun getActiveBlocksByProject(projectId: String): List<ChapterBlock> =
        blockDao.getActiveBlocksByProject(projectId)

    override suspend fun countActiveBlocks(projectId: String): Int =
        blockDao.countActiveByProject(projectId)

    override suspend fun countActiveChars(projectId: String): Int =
        blockDao.countActiveCharsByProject(projectId)

    override suspend fun countActiveCharsForChapter(chapterId: String): Int =
        blockDao.countActiveCharsByChapter(chapterId)

    override suspend fun getAllVersionsBlocks(chapterId: String): List<ChapterBlock> =
        blockDao.getAllVersionsByChapter(chapterId)

    override suspend fun getAllVersionsBlocksByProject(projectId: String): List<ChapterBlock> =
        blockDao.getAllVersionsByProject(projectId)

    override suspend fun countAllVersionsBlocks(projectId: String): Int =
        blockDao.countByProject(projectId)

    override suspend fun upsertBlocks(blocks: List<ChapterBlock>) = blockDao.upsertAll(blocks)

    override suspend fun getActiveVersion(chapterId: String): ChapterVersion? =
        versionDao.getActive(chapterId)

    override suspend fun getVersion(versionId: String): ChapterVersion? = versionDao.getById(versionId)

    override suspend fun getVersions(chapterId: String): List<ChapterVersion> =
        versionDao.getByChapter(chapterId)

    override suspend fun getVersionsByProject(projectId: String): List<ChapterVersion> =
        versionDao.getByProject(projectId)

    override suspend fun insertDraftVersion(
        chapterId: String,
        projectId: String,
        title: String,
        now: Long,
    ): ChapterVersion {
        val next = versionDao.maxVersionNumber(chapterId) + 1
        val version = ChapterVersion(
            id = "$chapterId-v$next",
            chapterId = chapterId,
            projectId = projectId,
            versionNumber = next,
            status = ChapterVersionStatus.DRAFT,
            title = title,
            createdAt = now,
            updatedAt = now,
        )
        versionDao.upsert(version)
        return version
    }

    override suspend fun insertDerivedDraftVersion(
        chapterId: String,
        projectId: String,
        source: ChapterVersion,
        now: Long,
    ): ChapterVersion {
        val next = versionDao.maxVersionNumber(chapterId) + 1
        val version = ChapterVersion(
            id = "$chapterId-v$next",
            chapterId = chapterId,
            projectId = projectId,
            versionNumber = next,
            status = ChapterVersionStatus.DRAFT,
            title = source.title,
            // 元数据先继承。局部重写只动几块正文，整章摘要与结尾状态大概率不变；
            // 真变了会在重新核验通过、发布时被覆盖。不继承的后果很具体：
            // 只改了第 3 段的重写会把整章摘要和覆盖台账清空
            summary = source.summary,
            endingState = source.endingState,
            coveredTitles = source.coveredTitles,
            coveredNodeIds = source.coveredNodeIds,
            unverifiedClaims = source.unverifiedClaims,
            usedModelKnowledge = source.usedModelKnowledge,
            derivedFromVersionId = source.id,
            createdAt = now,
            updatedAt = now,
        )
        versionDao.upsert(version)
        return version
    }

    override suspend fun setRewrittenBlocks(versionId: String, blockIds: List<String>) =
        versionDao.updateRewrittenBlocks(versionId, blockIds)

    override suspend fun updateVersionStatus(
        versionId: String,
        status: ChapterVersionStatus,
        note: String,
        now: Long,
    ) = versionDao.updateStatus(versionId, status, note, now)

    override suspend fun abandonVersionIfPending(
        versionId: String,
        note: String,
        now: Long,
    ): Boolean = versionDao.updateStatusIfIn(
        id = versionId,
        status = ChapterVersionStatus.ABANDONED,
        note = note,
        updatedAt = now,
        onlyIf = PENDING_VERSION_STATUSES,
    ) > 0

    override suspend fun publishVersionAtomically(
        version: ChapterVersion,
        now: Long,
    ) = versionDao.publishVersion(version, now)

    override suspend fun upsertVersions(versions: List<ChapterVersion>) =
        versionDao.upsertAll(versions)

    override suspend fun clearVersionsForProject(projectId: String) =
        versionDao.deleteByProject(projectId)

    private companion object {
        /** 还没发布的版本状态。取消与核验失败收尾只动这些。 */
        val PENDING_VERSION_STATUSES = listOf(
            ChapterVersionStatus.DRAFT,
            ChapterVersionStatus.VERIFYING,
        )
    }
}

/**
 * 引用关系（4A）。转发为主，唯一有逻辑的是 [replaceForVersion]：
 * 先清这一版再写，历史版本的引用一条都不碰。
 */
class DefaultCitationRepository(private val dao: CitationDao) : CitationRepository {
    override fun observeActiveByChapter(chapterId: String): Flow<List<Citation>> =
        dao.observeActiveByChapter(chapterId)

    override suspend fun getActiveByChapter(chapterId: String): List<Citation> =
        dao.getActiveByChapter(chapterId)

    override suspend fun countActiveResolved(chapterId: String): Int =
        dao.countActiveByChapterAndStatus(chapterId, CitationStatus.RESOLVED)

    override suspend fun countActiveUnconfirmed(chapterId: String): Int =
        dao.countActiveByChapterAndStatus(chapterId, CitationStatus.UNCONFIRMED) +
            dao.countActiveByChapterAndStatus(chapterId, CitationStatus.SOURCE_DELETED)

    override suspend fun getActiveByChunk(projectId: String, chunkId: String): List<Citation> =
        dao.getActiveByChunk(projectId, chunkId)

    override suspend fun getActiveByProject(projectId: String): List<Citation> =
        dao.getActiveByProject(projectId)

    override suspend fun getByVersion(versionId: String): List<Citation> = dao.getByVersion(versionId)

    override suspend fun getAllVersionsByProject(projectId: String): List<Citation> =
        dao.getAllVersionsByProject(projectId)

    override suspend fun replaceForVersion(versionId: String, citations: List<Citation>) {
        dao.deleteByVersion(versionId)
        if (citations.isNotEmpty()) dao.upsertAll(citations)
    }

    override suspend fun upsertAll(citations: List<Citation>) = dao.upsertAll(citations)
    override suspend fun deleteByChapter(chapterId: String) = dao.deleteByChapter(chapterId)
    override suspend fun deleteByProject(projectId: String) = dao.deleteByProject(projectId)

    override suspend fun unconfirmForSource(sourceId: String) =
        dao.unconfirmForSource(sourceId, CitationStatus.SOURCE_DELETED)
}

/**
 * 核验问题（4B）。转发为主，两处有逻辑：
 * [replaceForVersion] 先清这一版再写（重新核验同一版不留上一次的残留）；
 * [markFixedForBlocks] 空名单直接返回——`blockId IN ()` 在 SQLite 里是语法错误。
 */
class DefaultVerificationIssueRepository(
    private val dao: VerificationIssueDao,
) : VerificationIssueRepository {
    override fun observeActiveByChapter(chapterId: String): Flow<List<VerificationIssue>> =
        dao.observeActiveByChapter(chapterId)

    override suspend fun getActiveByChapter(chapterId: String): List<VerificationIssue> =
        dao.getActiveByChapter(chapterId)

    override suspend fun countActiveOpen(chapterId: String): Int =
        dao.countActiveOpen(chapterId, IssueResolution.OPEN)

    override fun observeActiveByProject(projectId: String): Flow<List<VerificationIssue>> =
        dao.observeActiveByProject(projectId)

    override suspend fun getActiveByProject(projectId: String): List<VerificationIssue> =
        dao.getActiveByProject(projectId)

    override suspend fun getByVersion(versionId: String): List<VerificationIssue> =
        dao.getByVersion(versionId)

    override fun observeByVersion(versionId: String): Flow<List<VerificationIssue>> =
        dao.observeByVersion(versionId)

    override suspend fun getAllVersionsByProject(projectId: String): List<VerificationIssue> =
        dao.getAllVersionsByProject(projectId)

    override suspend fun replaceForVersion(versionId: String, issues: List<VerificationIssue>) {
        dao.deleteByVersion(versionId)
        if (issues.isNotEmpty()) dao.upsertAll(issues)
    }

    override suspend fun upsertAll(issues: List<VerificationIssue>) = dao.upsertAll(issues)

    override suspend fun accept(issueId: String) =
        dao.updateResolution(issueId, IssueResolution.ACCEPTED)

    override suspend fun markFixedForBlocks(versionId: String, blockIds: List<String>) {
        if (blockIds.isEmpty()) return
        dao.markFixedForBlocks(versionId, blockIds, IssueResolution.FIXED)
    }

    override suspend fun deleteByChapter(chapterId: String) = dao.deleteByChapter(chapterId)
    override suspend fun deleteByProject(projectId: String) = dao.deleteByProject(projectId)
}

class DefaultStoryBibleRepository(private val dao: StoryBibleDao) : StoryBibleRepository {    override suspend fun get(projectId: String): StoryBible? = dao.get(projectId)
    override fun observe(projectId: String): Flow<StoryBible?> = dao.observe(projectId)
    override suspend fun save(bible: StoryBible) = dao.upsert(bible)

    override suspend fun updateMemory(
        projectId: String,
        runningState: String,
        coveredTitles: List<String>,
        coveredNodeIds: List<String>,
        openThreads: List<String>,
        updatedAt: Long,
    ) = dao.updateMemory(
        projectId = projectId,
        runningState = runningState,
        coveredTitles = coveredTitles,
        coveredNodeIds = coveredNodeIds,
        openThreads = openThreads,
        updatedAt = updatedAt,
    )

    override suspend fun clearForProject(projectId: String) = dao.clearByProject(projectId)
}

class DefaultQuizRepository(
    private val quizDao: QuizDao,
    private val answerDao: AnswerRecordDao,
) : QuizRepository {
    override fun observeByChapter(chapterId: String): Flow<List<Quiz>> =
        quizDao.observeByChapter(chapterId)

    override suspend fun getByChapter(chapterId: String): List<Quiz> = quizDao.getByChapter(chapterId)

    override fun observeActiveByChapter(chapterId: String): Flow<List<Quiz>> =
        quizDao.observeActiveByChapter(chapterId)

    override suspend fun getActiveByChapter(chapterId: String): List<Quiz> =
        quizDao.getActiveByChapter(chapterId)

    override suspend fun getByProject(projectId: String): List<Quiz> =
        quizDao.getByProject(projectId)

    override suspend fun upsertAll(quizzes: List<Quiz>) = quizDao.upsertAll(quizzes)
    override suspend fun deleteByChapter(chapterId: String) = quizDao.deleteByChapter(chapterId)

    override suspend fun deleteForVersion(chapterId: String, versionId: String) =
        quizDao.deleteForVersion(chapterId, versionId)
    override suspend fun recordAnswer(record: AnswerRecord) = answerDao.upsert(record)
    override suspend fun countCorrect(quizId: String): Int = answerDao.countCorrect(quizId)

    override suspend fun getAnswers(quizIds: List<String>): List<AnswerRecord> {
        if (quizIds.isEmpty()) return emptyList()
        return quizIds.chunked(900).flatMap { answerDao.getByQuizIds(it) }
    }

    override suspend fun hasAnswered(quizId: String): Boolean = answerDao.countAnswers(quizId) > 0

    override suspend fun deleteByProject(projectId: String) {
        answerDao.deleteByProject(projectId)
        quizDao.deleteByProject(projectId)
    }
}

class DefaultReviewRepository(private val dao: ReviewItemDao) : ReviewRepository {
    override fun observeByProject(projectId: String): Flow<List<ReviewItem>> =
        dao.observeByProject(projectId)

    override suspend fun getDue(now: Long): List<ReviewItem> =
        dao.getDue(now, ReviewStatus.PENDING)

    override suspend fun getByProject(projectId: String): List<ReviewItem> = dao.getByProject(projectId)

    override suspend fun upsertAll(items: List<ReviewItem>) = dao.upsertAll(items)
    override suspend fun clearForProject(projectId: String) = dao.clearByProject(projectId)
}

class DefaultProgressRepository(private val dao: ReadingProgressDao) : ProgressRepository {
    override suspend fun get(projectId: String): ReadingProgress? = dao.getForProject(projectId)
    override suspend fun saveAnchor(progress: ReadingProgress) = dao.saveAnchor(progress)
    override suspend fun save(progress: ReadingProgress) = dao.upsert(progress)
    override suspend fun markNoticeShown(projectId: String) = dao.markNoticeShown(projectId)
    override suspend fun clearForProject(projectId: String) = dao.deleteByProject(projectId)
}

class DefaultSettingsRepository(private val dao: ModelConfigDao) : SettingsRepository {
    override suspend fun getAllConfigs(): List<ModelConfig> = dao.getAll()
    override suspend fun getConfig(role: ModelRole): ModelConfig? = dao.getByRole(role.name)
    override suspend fun saveConfig(config: ModelConfig) = dao.upsert(config)
    override suspend fun clearConfigs() = dao.clearAll()
}
