package com.example.zhishu.domain.repository

import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeMapBatch
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ModelConfig
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.QuizState
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.StoryBible
import com.example.zhishu.domain.model.VerificationIssue
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

    /**
     * 只写用户填的来源元数据：文档版次、法域、生效时间、法律版本（4A）。
     * 解析结果（哈希、状态、抓取时间）不在其中——那不是用户该改的东西。
     */
    suspend fun updateMeta(
        sourceId: String,
        versionLabel: String?,
        jurisdiction: String?,
        effectiveFrom: String?,
        legalVersion: String?,
    )

    suspend fun delete(sourceId: String)
    suspend fun deleteByProject(projectId: String)
}

/**
 * 正文块与资料片段的引用关系（第四阶段 4A）。
 *
 * 与 3D 定下的命名规矩一致：当前阅读只走 Active 系列，
 * 读历史版本必须写明 ByVersion / AllVersions。
 */
interface CitationRepository {
    /** 阅读器订阅这一条：只吐当前 activeVersion 的引用 */
    fun observeActiveByChapter(chapterId: String): Flow<List<Citation>>
    suspend fun getActiveByChapter(chapterId: String): List<Citation>

    /** 本章当前版本的引用条数：可定位的 / 需人工核对的 */
    suspend fun countActiveResolved(chapterId: String): Int
    suspend fun countActiveUnconfirmed(chapterId: String): Int

    /** 引用了某个片段的**当前版本**正文块。资料 → 正文的回跳方向 */
    suspend fun getActiveByChunk(projectId: String, chunkId: String): List<Citation>

    /** 全项目当前版本的引用。项目级统计与未核实内容筛选用 */
    suspend fun getActiveByProject(projectId: String): List<Citation>

    /** 指定版本的引用。核验与局部重写用，与章节当前指针无关 */
    suspend fun getByVersion(versionId: String): List<Citation>

    /** 全项目**全部历史版本**的引用。只用于导出备份 */
    suspend fun getAllVersionsByProject(projectId: String): List<Citation>

    /** 换掉某一版的引用，历史版本一律保留 */
    suspend fun replaceForVersion(versionId: String, citations: List<Citation>)

    suspend fun upsertAll(citations: List<Citation>)
    suspend fun deleteByChapter(chapterId: String)
    suspend fun deleteByProject(projectId: String)

    /**
     * 资料被删掉后，引用它的引用降级为「未确认」而不是被删掉（4A）。
     *
     * 正文里那段话还在，它当初确实有依据。删掉引用会让那一段看起来
     * 从来没打算给出处，读者也就不会想到去核对。
     */
    suspend fun unconfirmForSource(sourceId: String)
}

/**
 * 核验问题（第四阶段 4B）。
 *
 * Active 系列读当前正文的问题，ByVersion 系列读某一版的——局部重写发生在
 * 还没发布的草稿版本上，那时 Active 系列一条都查不到。
 */
interface VerificationIssueRepository {
    /** 当前正文的问题。大纲页与阅读器订阅这一条 */
    fun observeActiveByChapter(chapterId: String): Flow<List<VerificationIssue>>
    suspend fun getActiveByChapter(chapterId: String): List<VerificationIssue>

    /** 当前正文还没处理的问题条数 */
    suspend fun countActiveOpen(chapterId: String): Int

    fun observeActiveByProject(projectId: String): Flow<List<VerificationIssue>>

    /** 全项目当前正文的问题，一次读完。未核实内容筛选用（4C） */
    suspend fun getActiveByProject(projectId: String): List<VerificationIssue>

    /** 指定版本的问题。局部重写与重新核验用 */
    suspend fun getByVersion(versionId: String): List<VerificationIssue>
    fun observeByVersion(versionId: String): Flow<List<VerificationIssue>>

    /** 全项目**全部历史版本**的问题。只用于导出备份 */
    suspend fun getAllVersionsByProject(projectId: String): List<VerificationIssue>

    /** 换掉某一版的问题，历史版本一律保留 */
    suspend fun replaceForVersion(versionId: String, issues: List<VerificationIssue>)

    suspend fun upsertAll(issues: List<VerificationIssue>)

    /** 用户判断这条不需要改 */
    suspend fun accept(issueId: String)

    /** 这些块重写并重新核验后问题没再出现 → 标记已修 */
    suspend fun markFixedForBlocks(versionId: String, blockIds: List<String>)

    suspend fun deleteByChapter(chapterId: String)
    suspend fun deleteByProject(projectId: String)
}

interface KnowledgeRepository {
    fun observeByProject(projectId: String): Flow<List<KnowledgeNode>>
    suspend fun getByProject(projectId: String): List<KnowledgeNode>
    /** 全量查询（含未生效的新批次节点），仅供生成流水线内部使用（v14） */
    suspend fun getByProjectAll(projectId: String): List<KnowledgeNode>
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

    // --- 批次化生成状态（骨架 → 逐链 → 审计）---
    /** 取该项目最新的生成批次；没有批次 = 从未开始或已完成清理 */
    suspend fun getLatestBatch(projectId: String): KnowledgeMapBatch?
    suspend fun saveBatch(batch: KnowledgeMapBatch)
    /** 重新生成前清掉旧批次，避免旧状态被误认成当前批次（重新生成隔离） */
    suspend fun clearBatch(projectId: String)

    /**
     * 原子切换 active 批次（v14）：把 [newBatchId] 置为生效、其他批次全部失效并清理，
     * 同时删除不属于新批次的全部节点（含迁移前空批次旧数据）。
     * 旧地图在新批次完整落库并切换前一直可见，失败/取消不触发切换。
     */
    suspend fun switchActiveBatch(projectId: String, newBatchId: String)

    /** 批次化生成：只更新节点的生成状态（占位/失败），不覆盖用户字段 */
    suspend fun updateGenerateStatus(id: String, status: String)
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

    /**
     * 只在章节仍处于 [onlyIf] 之一时改状态，返回是否真的改了（第二阶段）。
     * 取消收尾用：不能覆盖已经发布的章节，也不能踩坏用户随后重新排的那次生成。
     */
    suspend fun updateStatusIfIn(
        id: String,
        status: ChapterStatus,
        note: String,
        onlyIf: List<ChapterStatus>,
    ): Boolean

    /** 只写自测题状态，不触碰正文状态（第二阶段：正文可读性与测验解耦） */
    suspend fun updateQuizState(id: String, state: QuizState, note: String = "")
    /** 只写用户可编辑字段，由 UI 使用（N13） */
    suspend fun updateEditableFields(
        id: String,
        title: String,
        plotGoal: String,
        learningGoals: List<String>,
        wordTarget: Int,
    )
    // --- 正文读取（第三阶段 3A，命名在 3D 收紧）---
    //
    // 「当前正文」与「全部历史版本」在命名上必须一眼分得开，历史版本一律带
    // AllVersions / ByVersion：业务层（阅读器、导览、搜索、跳转、出题、核验、统计、
    // 首页继续阅读）只允许调 Active 系列；AllVersions 系列只用于导出备份与按章清理。
    // 3A 之后一个含糊的 getBlocks() 在代码里看起来和「读这一章的正文」一模一样，
    // 这是最容易悄悄读错、而且错了不会报错的地方。

    /** 阅读器订阅这一条：只吐当前 activeVersion 的正文块 */
    fun observeActiveBlocks(chapterId: String): Flow<List<ChapterBlock>>

    /** 当前 activeVersion 的正文块。没有 activeVersion 时是空列表 */
    suspend fun getActiveBlocks(chapterId: String): List<ChapterBlock>

    /** 指定版本的正文块，与章节当前指针无关。核验新版本、局部重写时用 */
    suspend fun getBlocksByVersion(versionId: String): List<ChapterBlock>

    /** 全项目当前版本正文。知识点跳转锚点只能落在读者看得到的那一版上 */
    suspend fun getActiveBlocksByProject(projectId: String): List<ChapterBlock>

    /** 当前版本的块数与字数。统计「写了多少」一律用它，不能把历史版本算两遍 */
    suspend fun countActiveBlocks(projectId: String): Int
    suspend fun countActiveChars(projectId: String): Int
    suspend fun countActiveCharsForChapter(chapterId: String): Int

    /** 一章的**全部历史版本**正文。只用于导出备份 */
    suspend fun getAllVersionsBlocks(chapterId: String): List<ChapterBlock>

    /** 全项目**全部历史版本**正文。只用于导出备份 */
    suspend fun getAllVersionsBlocksByProject(projectId: String): List<ChapterBlock>

    /** 全部历史版本的块数。只用于导出核对与测试 */
    suspend fun countAllVersionsBlocks(projectId: String): Int

    suspend fun upsertBlocks(blocks: List<ChapterBlock>)

    suspend fun getActiveVersion(chapterId: String): ChapterVersion?
    suspend fun getVersion(versionId: String): ChapterVersion?
    suspend fun getVersions(chapterId: String): List<ChapterVersion>
    suspend fun getVersionsByProject(projectId: String): List<ChapterVersion>

    /**
     * 建一个新的草稿版本并返回它。versionNumber 由已有版本自动递增。
     * 只建行不改指针——新版本此刻对读者完全不可见。
     */
    suspend fun insertDraftVersion(
        chapterId: String,
        projectId: String,
        title: String,
        now: Long,
    ): ChapterVersion

    /**
     * 建一个**从某一版派生**的草稿版本（4B 局部重写）。
     *
     * 与 [insertDraftVersion] 的区别只在于记住了来源版本，并把来源版本的
     * 摘要 / 结尾状态 / 覆盖台账先继承过来——局部重写只动几块正文，
     * 这些元数据大概率不变；真变了会在重新核验通过时被覆盖。
     * 不继承的话，一次只改了第 3 段的重写会把整章摘要清空。
     *
     * 同样只建行不改指针：读者此刻读到的还是旧正文。
     */
    suspend fun insertDerivedDraftVersion(
        chapterId: String,
        projectId: String,
        source: ChapterVersion,
        now: Long,
    ): ChapterVersion

    /** 把重写过的块记在版本上，供 UI 说清「这一版改了哪几块」 */
    suspend fun setRewrittenBlocks(versionId: String, blockIds: List<String>)

    suspend fun updateVersionStatus(
        versionId: String,
        status: ChapterVersionStatus,
        note: String = "",
        now: Long,
    )

    /**
     * 放弃一个还没发布的版本（核验失败 / 用户取消收尾）。
     * 已经发布的版本一律不动——那会让整章正文从阅读器里消失。返回是否真的改了。
     */
    suspend fun abandonVersionIfPending(versionId: String, note: String, now: Long): Boolean

    /**
     * 原子切换：版本置 PUBLISHED、同章旧版本置 ABANDONED、章节 activeVersionId 指过来，
     * 并同步章节的展示元数据。一个事务，要么全成要么全不成。
     */
    suspend fun publishVersionAtomically(version: ChapterVersion, now: Long)

    /** 导入备份用：整批写入版本行 */
    suspend fun upsertVersions(versions: List<ChapterVersion>)
    suspend fun clearVersionsForProject(projectId: String)
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
        /** 覆盖关系的判据（3D）。标题会被改名，ID 不会 */
        coveredNodeIds: List<String>,
        openThreads: List<String>,
        updatedAt: Long,
    )
    suspend fun clearForProject(projectId: String)
}

interface QuizRepository {
    fun observeByChapter(chapterId: String): Flow<List<Quiz>>
    suspend fun getByChapter(chapterId: String): List<Quiz>

    /**
     * 只取当前正文版本的题（第三阶段 3A）。
     * 旧版本的题留在库里但不显示——它们问的是已经被替换掉的情节。
     */
    fun observeActiveByChapter(chapterId: String): Flow<List<Quiz>>
    suspend fun getActiveByChapter(chapterId: String): List<Quiz>

    suspend fun getByProject(projectId: String): List<Quiz>
    suspend fun upsertAll(quizzes: List<Quiz>)
    suspend fun deleteByChapter(chapterId: String)

    /** 换掉某个版本的题，别的版本一律保留（第三阶段 3A） */
    suspend fun deleteForVersion(chapterId: String, versionId: String)
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

    /**
     * 只写阅读位置（3D）。
     *
     * 不用整行 upsert：阅读器每停一秒写一次位置，整行写回会把
     * 「正文换过版本」的痕迹一起冲掉，那条「本章已更新」的提示就再也不出现了。
     */
    suspend fun saveAnchor(progress: ReadingProgress)
    suspend fun save(progress: ReadingProgress)

    /** 「本章已更新，从章首开始」这条提示已经给用户看过，不再重复出现 */
    suspend fun markNoticeShown(projectId: String)
    suspend fun clearForProject(projectId: String)
}

interface SettingsRepository {
    suspend fun getAllConfigs(): List<ModelConfig>
    suspend fun getConfig(role: ModelRole): ModelConfig?
    suspend fun saveConfig(config: ModelConfig)
    suspend fun clearConfigs()
}
