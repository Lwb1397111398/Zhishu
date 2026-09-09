package com.example.zhishu.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.example.zhishu.ZhishuApp
import com.example.zhishu.ai.pipeline.KnowledgeMapMode
import com.example.zhishu.ai.pipeline.NextVolumeAvailability
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.ApiProfile
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.GENRE_AUTO
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeMapBatch
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ModelRole
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.QuizType
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.ReviewStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.domain.model.SourceMode
import com.example.zhishu.domain.model.StoryBible
import com.example.zhishu.domain.model.VerificationIssue
import com.example.zhishu.search.ParseResult
import com.example.zhishu.search.SearchHit
import com.example.zhishu.search.SourceInput
import com.example.zhishu.worker.EnqueueResult
import com.example.zhishu.worker.GenerationEnqueuer
import com.example.zhishu.worker.GenerationWorker
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class ZhishuViewModel(app: Application) : AndroidViewModel(app) {
    protected val container: AppContainer
        get() = (getApplication<Application>() as ZhishuApp).container

    /**
     * 带兜底的后台任务：失败先等一小会儿重试一次（覆盖数据库预热尚未完成的窗口），
     * 仍失败只记日志。没有这层时，Room 抛出的任何异常都会顺着 viewModelScope
     * 冒成未捕获异常，直接杀死进程——用户看到的就是「一打开就闪退」。
     * CancellationException 必须原样抛出，否则页面销毁后协程取消会被吞掉。
     */
    protected fun launchSafely(tag: String, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            runWithRetry(tag, block)
        }

    private suspend fun runWithRetry(tag: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "后台任务失败，${RETRY_DELAY_MS}ms 后重试一次", e)
            delay(RETRY_DELAY_MS)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "重试仍失败，放弃本次操作", e)
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_MS = 1500L
    }
}

private fun now(): Long = System.currentTimeMillis()

/**
 * 删除项目全部数据。顺序上先删子数据再删项目，且 chapter_blocks 按 projectId 直接删，
 * 不依赖 chapters 表是否还在（N6）。整体放在一个事务里，避免中途失败留下半份数据。
 */
internal suspend fun deleteProjectCompletely(container: AppContainer, projectId: String) {
    container.inTransaction {
        // 引用先删：它同时指向正文块和资料片段，两头都要没了才没有意义（4A）
        container.citationRepository.deleteByProject(projectId)
        // 核验问题绑在版本与块上，同样跟着一起删（4B）
        container.issueRepository.deleteByProject(projectId)
        container.chapterRepository.clearBlocksForProject(projectId)
        container.chapterRepository.clearVersionsForProject(projectId)
        container.chapterRepository.clearPlansForProject(projectId)
        container.storyBibleRepository.clearForProject(projectId)
        container.quizRepository.deleteByProject(projectId)
        container.reviewRepository.clearForProject(projectId)
        container.progressRepository.clearForProject(projectId)
        container.knowledgeRepository.clearForProject(projectId)
        container.sourceRepository.deleteByProject(projectId)
        container.projectRepository.delete(projectId)
    }
}

class HomeViewModel(app: Application) : ZhishuViewModel(app) {

    data class ContinueEntry(
        val project: Project,
        val progress: ReadingProgress,
        val chapterTitle: String,
        val chapterOrder: Int,
        val totalChapters: Int,
    )

    /** 书架上一本书的轻量统计（封面下的进度行），随 refresh 一起算 */
    data class BookStat(
        val totalChapters: Int,
        val publishedChapters: Int,
        /** 最近一次有阅读动作的时间（0 = 从没读过），用于「本周读过」统计 */
        val lastReadAt: Long,
        /** 上次读到的章：点书直读的第一优先 */
        val readingChapterId: String? = null,
        /** 还没读过时从第一章可读章开始 */
        val firstReadableChapterId: String? = null,
    ) {
        /** 点书直读的目标章：上次读到的 > 第一章可读的。null = 这本书还没有可读内容 */
        val directReadChapterId: String?
            get() = readingChapterId ?: firstReadableChapterId
    }

    val projects: StateFlow<List<Project>> =
        container.projectRepository.observeAll()
            .retryWhen { cause, attempt ->
                // 数据库可能还在预热或自愈：稍等重订一次，别让异常炸掉 Compose 组合
                if (cause is CancellationException) {
                    false
                } else if (attempt < SUBSCRIBE_RETRIES) {
                    delay(SUBSCRIBE_RETRY_DELAY_MS)
                    true
                } else {
                    false
                }
            }
            .catch { cause ->
                // 取消要照常传播，否则页面退出后 Flow 还挂着
                if (cause is CancellationException) throw cause
                // 持续失败（理论上只在故障时发生）：先给空列表，页面保持可用
                emit(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _dueCount = MutableStateFlow(0)
    val dueCount: StateFlow<Int> = _dueCount

    /** 关闭时首页不出现「今日复习」，主线保持「继续阅读」 */
    val spacedReviewEnabled: StateFlow<Boolean> = container.learningPrefs.spacedReviewEnabled

    private val _continueEntry = MutableStateFlow<ContinueEntry?>(null)
    val continueEntry: StateFlow<ContinueEntry?> = _continueEntry

    /** 每本书的章节数/阅读时间，书架封面下的进度行用 */
    private val _bookStats = MutableStateFlow<Map<String, BookStat>>(emptyMap())
    val bookStats: StateFlow<Map<String, BookStat>> = _bookStats

    /** 最近 7 天里有阅读动作的书数（0 不显示） */
    private val _weekReadCount = MutableStateFlow(0)
    val weekReadCount: StateFlow<Int> = _weekReadCount

    /** 每本书的知识点数（知识页统计条与列表用） */
    private val _knowledgeStats = MutableStateFlow<Map<String, Int>>(emptyMap())
    val knowledgeStats: StateFlow<Map<String, Int>> = _knowledgeStats

    init {
        refresh()
    }

    fun refresh() {
        launchSafely("HomeViewModel") {
            // 知识页的统计条要显示待复习数，所以这里无条件算；
            // 首页「今日复习」卡片是否出现仍由 reviewEnabled 决定，逻辑不变
            _dueCount.value = container.reviewRepository.getDue(now()).size
            val all = container.projectRepository.observeAll().first()
            val stats = mutableMapOf<String, BookStat>()
            val nodeCounts = mutableMapOf<String, Int>()
            val entries = mutableListOf<ContinueEntry>()
            val weekAgo = now() - 7 * 24 * 60 * 60 * 1000L
            all.forEach { project ->
                val plans = container.chapterRepository.getPlans(project.id).sortedBy { it.order }
                val progress = container.progressRepository.get(project.id)
                stats[project.id] = BookStat(
                    totalChapters = plans.size,
                    publishedChapters = plans.count { it.isReadable },
                    lastReadAt = progress?.updatedAt ?: 0L,
                    readingChapterId = progress?.chapterId?.takeIf { id ->
                        // 与项目页章节卡 canRead 同口径：待复核但有正文的章也能读
                        plans.any {
                            it.id == id && (it.isReadable || it.status == ChapterStatus.NEEDS_REVIEW)
                        }
                    },
                    firstReadableChapterId = plans
                        .firstOrNull { it.isReadable || it.status == ChapterStatus.NEEDS_REVIEW }
                        ?.id,
                )
                nodeCounts[project.id] = container.knowledgeRepository.getByProject(project.id).size
                if (progress != null) {
                    val chapter = plans.firstOrNull { it.id == progress.chapterId }
                    if (chapter != null) {
                        entries += ContinueEntry(
                            project, progress, chapter.title, chapter.order, plans.size,
                        )
                    }
                }
            }
            _bookStats.value = stats
            _knowledgeStats.value = nodeCounts
            _continueEntry.value = entries.maxByOrNull { it.progress.updatedAt }
            _weekReadCount.value = stats.values.count { it.lastReadAt >= weekAgo }
        }
    }

    fun deleteProject(projectId: String) {
        launchSafely("HomeViewModel") {
            deleteProjectCompletely(container, projectId)
            refresh()
        }
    }

    fun seedDemo() {
        launchSafely("HomeViewModel") {
            DemoSeeder.seed(container)
            refresh()
        }
    }

    private companion object {
        /** Room Flow 订阅失败的重订次数与间隔；给数据库预热/自愈留出时间窗口 */
        const val SUBSCRIBE_RETRIES = 3
        const val SUBSCRIBE_RETRY_DELAY_MS = 800L
    }
}

data class PendingSource(
    val title: String,
    val kind: SourceKind,
    val text: String? = null,
    val url: String? = null,
    val fileName: String? = null,
    val bytes: ByteArray? = null,
)

class CreateProjectViewModel(app: Application) : ZhishuViewModel(app) {

    val title = MutableStateFlow("")
    val topic = MutableStateFlow("")
    val baseline = MutableStateFlow("完全不了解")
    val depth = MutableStateFlow("入门")
    /**
     * 学习画像三项。「想学什么」太笼统——同样填「民法」，民商法研究生、
     * 非法学自学者、法考考生要的知识主干切法完全不同（见 LearnerProfile）。
     * 三项都不是必填：不填就按「完全陌生、想建立整体理解」处理，行为与改造前一致。
     */
    val learningGoal = MutableStateFlow("")
    val learningPurpose = MutableStateFlow("")
    val learnerIdentity = MutableStateFlow("")
    // 默认让 AI 自选讲法：用户往往不知道一门学科适合什么风格（问题6）
    val genre = MutableStateFlow(GENRE_AUTO)
    // 默认无需资料：像民法这类学科用户很难找到合适资料（问题3）
    val sourceMode = MutableStateFlow(SourceMode.MODEL)
    val dailyMinutes = MutableStateFlow(30)
    val targetDays = MutableStateFlow(14)

    private val _sources = MutableStateFlow<List<PendingSource>>(emptyList())
    val sources: StateFlow<List<PendingSource>> = _sources

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating

    /** 无资料模式下只要填了主题就能建项目并直接开讲 */
    val canSubmit: Boolean
        get() = topic.value.isNotBlank() &&
            (sourceMode.value == SourceMode.MODEL || _sources.value.isNotEmpty())

    /** 标题留空时用主题兜底，少一个必填项（问题4） */
    private fun resolvedTitle(): String =
        title.value.trim().ifBlank { topic.value.trim() }

    fun addTextSource(textTitle: String, text: String) {
        if (textTitle.isBlank() || text.isBlank()) return
        _sources.update { it + PendingSource(textTitle.trim(), SourceKind.TEXT, text = text.trim()) }
    }

    fun addUrlSource(url: String) {
        if (url.isBlank()) return
        val trimmed = url.trim()
        _sources.update { it + PendingSource(trimmed, SourceKind.URL, url = trimmed) }
    }

    fun addFileSource(sourceTitle: String, fileName: String, kind: SourceKind, bytes: ByteArray) {
        _sources.update {
            it + PendingSource(
                title = sourceTitle.ifBlank { fileName },
                kind = kind,
                fileName = fileName,
                bytes = bytes,
            )
        }
    }

    fun removeSource(index: Int) {
        _sources.update { it.filterIndexed { i, _ -> i != index } }
    }

    /**
     * 先在内存中解析全部资料，全部成功后再在一个事务里原子写入项目、资料与片段（N5）。
     * 任何一份资料解析失败都不会留下半成品项目。
     */
    suspend fun createProject(): Result<String> {
        if (!canSubmit) {
            return Result.failure(
                IllegalStateException(
                    if (topic.value.isBlank()) "请先填写想学什么" else "请添加至少一份资料，或切换到 AI 自备知识模式",
                ),
            )
        }
        _creating.value = true
        return try {
            val id = UUID.randomUUID().toString()
            val modelKnowledge = sourceMode.value == SourceMode.MODEL
            val project = Project(
                id = id,
                title = resolvedTitle(),
                topic = topic.value.trim(),
                baseline = baseline.value,
                depth = depth.value,
                dailyMinutes = dailyMinutes.value,
                targetDays = targetDays.value,
                genre = genre.value,
                status = ProjectStatus.SOURCES,
                createdAt = now(),
                updatedAt = now(),
                sourceMode = sourceMode.value,
                learningGoal = learningGoal.value.trim(),
                learningPurpose = learningPurpose.value.trim(),
                learnerIdentity = learnerIdentity.value.trim(),
            )

            // 阶段一：只解析，不落库
            val parsed = mutableListOf<ParseResult>()
            val seenHashes = mutableSetOf<String>()
            if (!modelKnowledge) {
                for (source in _sources.value) {
                    val input = source.toInput()
                    val result = container.parser.parse(id, input)
                    if (!seenHashes.add(result.source.contentHash)) continue
                    parsed += result
                }
            }

            // 阶段二：原子写入
            container.inTransaction {
                container.projectRepository.create(project)
                parsed.forEach { container.sourceRepository.addSource(it.source, it.chunks) }
            }
            Result.success(id)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _creating.value = false
        }
    }
}

private fun PendingSource.toInput(): SourceInput = when {
    bytes != null -> SourceInput.FileBytes(
        title = title,
        fileName = fileName ?: title,
        bytes = bytes,
        kind = kind,
    )
    url != null -> SourceInput.Url(url, title)
    else -> SourceInput.Text(title, text ?: "")
}

class ProjectDetailViewModel(
    app: Application,
    val projectId: String,
) : ZhishuViewModel(app) {

    val project: StateFlow<Project?> =
        container.projectRepository.observeAll()
            .map { list -> list.firstOrNull { it.id == projectId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val sources: StateFlow<List<SourceDocument>> =
        container.sourceRepository.observeByProject(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val nodes: StateFlow<List<KnowledgeNode>> =
        container.knowledgeRepository.observeByProject(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 知识主干生成批次（分步生成）：知识地图页靠它显示「几条链已展开 / 几条失败」，
     * 并把「继续展开」与「重新梳理」区分开——批次没完成时点继续只补没做完的部分。
     */
    val mapBatch: StateFlow<KnowledgeMapBatch?> =
        container.knowledgeRepository.observeLatestBatch(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val gaps: StateFlow<List<KnowledgeGap>> =
        container.knowledgeRepository.observeGaps(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conflicts: StateFlow<List<KnowledgeConflict>> =
        container.knowledgeRepository.observeConflicts(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val plans: StateFlow<List<ChapterPlan>> =
        container.chapterRepository.observePlans(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 故事总纲与滚动记忆，UI 展示「AI 选了什么讲法、故事走到哪了」（问题5、问题6） */
    val bible: StateFlow<StoryBible?> =
        container.storyBibleRepository.observe(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 当前正文的核验问题，按章节分组（4B）。
     *
     * 只订阅 active version 的问题：草稿版本的问题属于一次还没发布的修改尝试，
     * 混进来会让用户看到「本章有 5 个问题」，而其中 3 个说的是他刚才那次
     * 没通过的修改。历史版本同理——那些文字读者已经看不到了。
     */
    val issues: StateFlow<Map<String, List<VerificationIssue>>> =
        container.issueRepository.observeActiveByProject(projectId)
            .map { list -> list.groupBy { it.chapterId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** 正在局部重写的章节 ID。防连点，也让卡片能显示「正在修改」 */
    private val _rewriting = MutableStateFlow<Set<String>>(emptySet())
    val rewriting: StateFlow<Set<String>> = _rewriting

    /** 正在补契约的章节 ID。同样是防连点（契约是一次模型调用，重复点会重复扣额度） */
    private val _retryingContracts = MutableStateFlow<Set<String>>(emptySet())
    val retryingContracts: StateFlow<Set<String>> = _retryingContracts

    /** 正在补核验的章节（只查不写，防重复点击） */
    private val _reverifying = MutableStateFlow<Set<String>>(emptySet())
    val reverifying: StateFlow<Set<String>> = _reverifying

    /** 正在补知识点的缺口 ID。防连点：补一个节点也是一次模型调用，重复点会重复扣额度 */
    private val _patchingGaps = MutableStateFlow<Set<String>>(emptySet())
    val patchingGaps: StateFlow<Set<String>> = _patchingGaps

    /**
     * 当前正文里出处没对上的引用条数（4C）：未确认 + 来源已删除。
     *
     * 只数 active version。历史版本的未确认引用留在库里，但它们指的是
     * 读者已经看不到的段落，数进来会让「有 12 项要核对」这个数字
     * 既无法定位也无法消掉。
     *
     * 跟着 plans 重算：发布新版本、局部重写、删除资料都会改变它。
     */
    val unconfirmedCitationCount: StateFlow<Int> =
        plans.map {
            container.citationRepository.getActiveByProject(projectId)
                .count { citation -> !citation.status.isResolved }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * 问题所在段落的正文，blockId → 文字（4B）。
     *
     * 用户要能看到「问题指的是哪一段」才谈得上判断。只给一句问题说明
     * 就让人选「要不要重写」，等于让他闭着眼决定改动自己读过的内容。
     * 打开某一章的问题列表时按需装载，不常驻。
     */
    private val _issueBlocks = MutableStateFlow<Map<String, ChapterBlock>>(emptyMap())
    val issueBlocks: StateFlow<Map<String, ChapterBlock>> = _issueBlocks

    fun loadIssueBlocks(chapterId: String) {
        viewModelScope.launch {
            _issueBlocks.value = container.chapterRepository.getActiveBlocks(chapterId)
                .associateBy { it.id }
        }
    }

    /**
     * 只重写选中的问题段落（4B）。
     *
     * 同步调用而不是排 Worker，和「重新出题」同一个理由：用户是在问题列表上
     * 主动点的，而且这条路**不会**触发整章重生成。它自己带着「旧正文继续可读」
     * 的保证——中途失败只是草稿作废。
     */
    fun rewriteBlocks(planId: String, blockIds: List<String>) {
        if (planId in _rewriting.value) {
            _message.value = "这一章正在修改中，没有重复提交。"
            return
        }
        if (blockIds.isEmpty()) {
            _message.value = "请先选择要修改的段落。"
            return
        }
        viewModelScope.launch {
            _rewriting.value = _rewriting.value + planId
            _message.value = "正在重写 ${blockIds.size} 个段落并重新核验（需要网络）…"
            val result = runCatching {
                container.pipeline.rewriteBlocks(projectId, planId, blockIds)
            }.getOrElse { Result.failure(it) }
            _rewriting.value = _rewriting.value - planId
            _message.value = if (result.isSuccess) {
                "已修改并通过核验，正文已更新为新版本。"
            } else {
                // 这句话必须说全：用户最担心的是「我点了修改，是不是把能读的正文弄坏了」
                "修改没有发布：${result.exceptionOrNull()?.message ?: "未知原因"}。" +
                    "正文仍是此前那一版，可以继续阅读。"
            }
        }
    }

    /**
     * 用户判断某条问题不需要改（4B）。
     *
     * 只有「我看过了，不改」这一个选项，没有「标记为已修」——那得由
     * 「重写 → 重新核验 → 问题没再出现」推出来（见 IssueResolution）。
     */
    fun acceptIssue(issueId: String) {
        viewModelScope.launch {
            container.issueRepository.accept(issueId)
            _message.value = "已标记为「不需要修改」，正文没有改动。"
        }
    }

    /** 知识点在正文里的落点：第几章、章里的哪一块。知识地图靠它跳进正文 */
    data class ChapterAnchor(
        val chapterId: String,
        val order: Int,
        val title: String,
        val blockId: String?,
    )

    /**
     * nodeId → 该知识点出现过的章节锚点。
     * 只收已经有正文的章节——跳到还没写的章节没有意义。
     * plans 变化（新章节发布）时自动重算。
     */
    val nodeAnchors: StateFlow<Map<String, List<ChapterAnchor>>> =
        plans.map { buildNodeAnchors(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private suspend fun buildNodeAnchors(
        plans: List<ChapterPlan>,
    ): Map<String, List<ChapterAnchor>> {
        if (plans.isEmpty()) return emptyMap()
        // 只取当前版本正文：重生成过的章节还留着旧版本的块，
        // 拿它们当锚点会把用户送到一段已经看不到的文字上（第三阶段 3A）
        val blocksByChapter = container.chapterRepository.getActiveBlocksByProject(projectId)
            .groupBy { it.chapterId }
        val result = mutableMapOf<String, MutableList<ChapterAnchor>>()
        plans.sortedBy { it.order }.forEach { plan ->
            val blocks = blocksByChapter[plan.id].orEmpty().sortedBy { it.order }
            if (blocks.isEmpty()) return@forEach
            val touched = (blocks.flatMap { it.knowledgeNodeIds } + plan.nodeIds).distinct()
            touched.forEach { nodeId ->
                val anchor = ChapterAnchor(
                    chapterId = plan.id,
                    order = plan.order,
                    title = plan.title,
                    // 找不到明确的块就只给章节，仍然能跳进阅读器
                    blockId = blocks.firstOrNull { nodeId in it.knowledgeNodeIds }?.id,
                )
                result.getOrPut(nodeId) { mutableListOf() } += anchor
            }
        }
        return result
    }

    /** 生成任务状态。入队后 UI 一直有反馈，失败原因直接上屏（问题1） */
    val generation: StateFlow<GenerationUiState> =
        WorkManager.getInstance(app).observeGeneration(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GenerationUiState())

    /**
     * 章节 ID → 最近一次「重新生成没成功」的说明（第三阶段 3A，判据在 3D 收紧）。
     *
     * 3A 之后重生成失败不再改动章节状态——旧正文照样可读，这是对的。
     * 但如果什么都不说，用户点了重新生成、等了半天、页面一动不动，会以为是自己没点上。
     * 所以问题绑在新版本上，由这里单独读出来展示，而不是去覆盖当前发布版本的核验说明。
     *
     * 只认真正的 [ChapterVersionStatus.ABANDONED]：3D 之前发布事务把被取代的旧正文
     * 也写成 ABANDONED，于是每一次**成功**的重生成都会在这里留下一条「失败」提示。
     * 被取代的旧版本现在是 ARCHIVED，不进这个判据。
     */
    val failedRegenerations: StateFlow<Map<String, String>> =
        plans.map { buildFailedRegenerationNotes(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private suspend fun buildFailedRegenerationNotes(
        plans: List<ChapterPlan>,
    ): Map<String, String> {
        // 只对「有可读正文」的章节报。没有可读正文时问题已经写在章节的核验说明里了，
        // 再报一遍等于同一件事说两次
        val readable = plans.filter { it.isReadable && it.activeVersionId != null }
        if (readable.isEmpty()) return emptyMap()
        val versions = container.chapterRepository.getVersionsByProject(projectId)
        return readable.mapNotNull { plan ->
            val active = versions.firstOrNull { it.id == plan.activeVersionId }
                ?: return@mapNotNull null
            val latestFailure = versions
                .filter {
                    it.chapterId == plan.id &&
                        it.status == ChapterVersionStatus.ABANDONED &&
                        it.verificationNote.isNotBlank() &&
                        // 只报比当前版本更新的尝试。比它旧的作废版本是正常历史，
                        // 那是「后来重生成成功了」，不该再报失败
                        it.versionNumber > active.versionNumber
                }
                .maxByOrNull { it.versionNumber }
                ?: return@mapNotNull null
            plan.id to latestFailure.verificationNote
        }.toMap()
    }

    /**
     * 项目进度统计（3D）。
     *
     * 字数只数**当前版本**：把历史版本一起算进去，重生成过一章就凭空多出一整章的量，
     * 显示出来的就是一个自己也解释不了的数字。
     *
     * [unresolvedCoverage] 是「讲过、但对不上任何现存知识点」的条数——
     * 通常是知识点改过名或知识地图重新生成过。这类内容既不能算覆盖了某个节点，
     * 也不该被当成「没讲过」（否则续卷会把它们重讲一遍），所以如实报出来让用户判断。
     */
    data class ProjectStats(
        val activeChars: Int = 0,
        val unresolvedCoverage: Int = 0,
    )

    val stats: StateFlow<ProjectStats> =
        plans.map { current ->
            if (current.isEmpty()) {
                ProjectStats()
            } else {
                ProjectStats(
                    activeChars = container.chapterRepository.countActiveChars(projectId),
                    unresolvedCoverage = container.pipeline
                        .unresolvedCoverageTitles(projectId)
                        .size,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProjectStats())

    /**
     * 「继续下一卷」入口的状态（第三阶段 3C）。
     *
     * 当前卷没读完就不显示——续卷的开场必须接上「上一章的结尾」，
     * 那个结尾在当前卷写完之前根本不存在，硬续会打乱故事顺序。
     */
    val nextVolume: StateFlow<NextVolumeAvailability> =
        plans.map { container.pipeline.nextVolumeAvailability(projectId) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                NextVolumeAvailability.NoPlanYet,
            )

    /**
     * 规划下一卷。只往后加章节，已发布的一个字不改。
     *
     * 走同步调用而不是 Worker：这是一次模型调用，用户主动点的，
     * 而且它绝不会触发任何正文生成。
     */
    fun planNextVolume() {
        viewModelScope.launch {
            _message.value = "正在规划下一卷…"
            val result = container.pipeline.generateNextVolume(projectId)
            _message.value = if (result.isSuccess) {
                "下一卷已规划好，已发布的章节没有任何改动。可以开始生成新章节了。"
            } else {
                result.exceptionOrNull()?.message ?: "规划失败，请重试"
            }
        }
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() {
        _message.value = null
    }
    // PLACEHOLDER_PROJECT_DETAIL

    private suspend fun addParsedSource(input: SourceInput) {
        val parsed = container.parser.parse(projectId, input)
        val existing = container.sourceRepository.findDuplicate(parsed.source.contentHash)
        if (existing != null && existing.projectId == projectId) {
            _message.value = "该资料已存在：${existing.title}"
        } else {
            container.sourceRepository.addSource(parsed.source, parsed.chunks)
            _message.value = "已添加资料：${parsed.source.title}"
        }
    }

    fun addTextSource(textTitle: String, text: String) {
        if (textTitle.isBlank() || text.isBlank()) {
            _message.value = "标题和内容不能为空"
            return
        }
        viewModelScope.launch {
            runCatching { addParsedSource(SourceInput.Text(textTitle.trim(), text.trim())) }
                .onFailure { _message.value = "添加失败：${it.message}" }
        }
    }

    fun addUrlSource(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            runCatching { addParsedSource(SourceInput.Url(url.trim())) }
                .onFailure { _message.value = "抓取失败：${it.message}" }
        }
    }

    fun addFileSource(sourceTitle: String, fileName: String, kind: SourceKind, bytes: ByteArray) {
        viewModelScope.launch {
            runCatching {
                addParsedSource(
                    SourceInput.FileBytes(
                        title = sourceTitle.ifBlank { fileName },
                        fileName = fileName,
                        bytes = bytes,
                        kind = kind,
                    ),
                )
            }.onFailure { _message.value = "解析失败：${it.message}" }
        }
    }

    /**
     * 保存用户填的来源元数据（4A）。
     *
     * 只写这四栏。解析结果（哈希、状态、抓取时间）不给改——那不是用户该动的东西，
     * 一次手误就能让一份好资料显示成解析失败。
     */
    fun updateSourceMeta(
        sourceId: String,
        versionLabel: String,
        jurisdiction: String,
        effectiveFrom: String,
        legalVersion: String,
    ) {
        viewModelScope.launch {
            container.sourceRepository.updateMeta(
                sourceId = sourceId,
                versionLabel = versionLabel,
                jurisdiction = jurisdiction,
                effectiveFrom = effectiveFrom,
                legalVersion = legalVersion,
            )
            _message.value = "已保存来源信息。生成与核验都会用到它。"
        }
    }

    fun deleteSource(sourceId: String) {
        viewModelScope.launch {
            // 引用这份资料的正文块不动，只把引用降级成「未确认」（4A）。
            // 正文里那段话还在，它当初确实有依据；删掉引用会让它看起来
            // 从来没打算给出处，读者也就不会想到去核对
            container.inTransaction {
                val removedChunkIds = container.sourceRepository.getChunks(sourceId)
                    .map { it.id }
                    .toSet()
                container.citationRepository.unconfirmForSource(sourceId)
                if (removedChunkIds.isNotEmpty()) {
                    dropChunkRefsFromNodes(projectId, removedChunkIds)
                    dropChunkRefsFromIssues(projectId, removedChunkIds)
                }
                container.sourceRepository.delete(sourceId)
            }
            _message.value = "已删除资料。引用过它的正文段落会标为「需要核对」，正文不受影响。"
        }
    }

    /**
     * 把已删片段的 ID 从知识节点的引用里摘掉。
     *
     * 只改 sourceRefs 一个字段，其余照抄——节点上还有掌握度、确认位、章节回写
     * 等用户与流程各自维护的状态，用整行覆盖会把它们一起冲掉（N13 定过的口径）。
     */
    private suspend fun dropChunkRefsFromNodes(projectId: String, removedChunkIds: Set<String>) {
        val affected = container.knowledgeRepository.getByProjectAll(projectId)
            .filter { node -> node.sourceRefs.any { it in removedChunkIds } }
        if (affected.isEmpty()) return
        container.knowledgeRepository.upsertAll(
            affected.map { node ->
                node.copy(sourceRefs = node.sourceRefs.filter { it !in removedChunkIds })
            },
        )
    }

    /**
     * 同 [dropChunkRefsFromNodes]，作用在核验问题上（4B）。
     *
     * **只清当前版本**：历史版本的问题说的是已经被替换掉的那版文字，
     * 界面上一律不展示（`VersionHardeningTest` 的 `historyReads_areConfinedToBackupExport`
     * 就是拦这件事的——业务层读 AllVersions 会让用户看到一堆说的是不存在文字的问题）。
     * 这里要修的是「点开是空白」的可见坏链接，当前版本清干净就达到目的；
     * 历史版本那条记录连同它的引用一起留在库里，本来就是刻意保留的现场。
     */
    private suspend fun dropChunkRefsFromIssues(projectId: String, removedChunkIds: Set<String>) {
        val affected = container.issueRepository.getActiveByProject(projectId)
            .filter { issue -> issue.sourceRefs.any { it in removedChunkIds } }
        if (affected.isEmpty()) return
        container.issueRepository.upsertAll(
            affected.map { issue ->
                issue.copy(sourceRefs = issue.sourceRefs.filter { it !in removedChunkIds })
            },
        )
    }

    /** 只写确认位，避免整行 upsert 覆盖掌握度或章节回写结果（N13） */
    fun setNodeConfirmed(node: KnowledgeNode, confirmed: Boolean) {
        viewModelScope.launch {
            container.knowledgeRepository.setConfirmed(node.id, confirmed)
        }
    }

    /** 只写用户可编辑字段，不触碰 status / verificationNote（N13） */
    fun updatePlanFields(
        planId: String,
        title: String,
        plotGoal: String,
        learningGoals: List<String>,
        wordTarget: Int,
    ) {
        viewModelScope.launch {
            container.chapterRepository.updateEditableFields(
                id = planId,
                title = title,
                plotGoal = plotGoal,
                learningGoals = learningGoals,
                wordTarget = wordTarget,
            )
            _message.value = "已保存章节设置"
        }
    }

    /**
     * 梳理知识主干。[mode] 决定节奏：默认 FULL 一口气跑完（重新梳理走这个）；
     * SKELETON 只出骨架（首次生成先看结构再决定）；NEXT_CHAIN 单步推一条链。
     */
    fun enqueueKnowledgeMap(mode: KnowledgeMapMode = KnowledgeMapMode.FULL) {
        val message = when (mode) {
            KnowledgeMapMode.FULL -> "已开始梳理知识主干（需要网络）"
            KnowledgeMapMode.SKELETON -> "已开始梳理骨架，完成后可逐条或全部展开（需要网络）"
            KnowledgeMapMode.NEXT_CHAIN -> "已开始展开下一条演进链（需要网络）"
        }
        launchEnqueue(message) {
            GenerationEnqueuer.knowledgeMap(getApplication(), projectId, mode)
        }
    }

    /**
     * 放弃当前批次，从头重新梳理知识主干（B1）。
     *
     * 批次没完成时「重新梳理」走的是续跑（`enqueueKnowledgeMap(FULL)`）：只补没做完的
     * 链/审计，节点数和结构都是旧骨架的。换模型、改资料后想彻底重来，旧批次必须
     * 显式作废，否则生成流程永远把旧骨架当断点续接。
     */
    fun restartKnowledgeMap() {
        viewModelScope.launch {
            val latest = container.knowledgeRepository.getLatestBatch(projectId)
            if (latest != null && !latest.completed && !latest.failed) {
                // 未完成批次先作废，保证下一步生成开新批次、重新出骨架
                val ok = runCatching {
                    container.knowledgeRepository.saveBatch(
                        latest.copy(failed = true, updatedAt = System.currentTimeMillis()),
                    )
                }.isSuccess
                if (!ok) {
                    _message.value = "丢弃失败，请重试。旧批次仍在，重试不会重复扣费。"
                    return@launch
                }
                _message.value = "已丢弃当前进度，将从新骨架重头开始。"
            }
            launchEnqueue("已开始从头梳理知识主干（需要网络）") {
                GenerationEnqueuer.knowledgeMap(getApplication(), projectId, KnowledgeMapMode.SKELETON)
            }
        }
    }

    fun enqueueStoryBible() {
        launchEnqueue("已开始规划讲述路线（需要网络）") {
            GenerationEnqueuer.storyBible(getApplication(), projectId)
        }
    }

    fun enqueueChapters() {
        launchEnqueue("已开始生成未完成的章节（需要网络）") {
            GenerationEnqueuer.chapters(
                context = getApplication(),
                projectId = projectId,
                plans = container.chapterRepository.getPlans(projectId),
            )
        }
    }

    /** 一键开讲：知识地图 → 讲述路线 → 首章，一次点完（问题4）。知识地图走 FULL 全自动展开 */
    fun enqueueOneTap() {
        launchEnqueue("已开始，将依次完成知识梳理、路线规划和第一章") {
            // needsMap 不只看节点是否为空：分步生成可能停在「骨架完成、链未展开」，
            // 那些占位节点没有内容，讲述路线等不了——批次没完成就得把地图跑完
            val mapIncomplete = container.knowledgeRepository.getLatestBatch(projectId)
                ?.let { !it.completed } == true
            GenerationEnqueuer.oneTap(
                context = getApplication(),
                projectId = projectId,
                needsMap = mapIncomplete || container.knowledgeRepository.getByProject(projectId).isEmpty(),
                needsBible = container.chapterRepository.getPlans(projectId).isEmpty(),
            )
        }
    }

    /** 取消本项目的整条生成链，包括还没轮到的章节（第二阶段） */
    fun cancelGeneration() {
        GenerationEnqueuer.cancel(getApplication(), projectId)
        _message.value = "已取消生成。已经写好的章节不受影响。"
    }

    /**
     * 切换知识来源模式（问题3）。
     *
     * 只改变**以后**怎么生成：已生成的知识主干与已发布的章节一个字不动，
     * 引用关系也原样保留。（早先这里写着「既有知识地图与章节都失效」，与实现
     * 不符——实现从没删过任何东西，那条注释把用户吓退了又没兑现。）
     * 想让新设置真的生效，重新梳理知识主干即可。
     */
    fun setSourceMode(mode: SourceMode) {
        viewModelScope.launch {
            val current = container.projectRepository.get(projectId) ?: return@launch
            if (current.sourceMode == mode) return@launch
            container.projectRepository.update(current.copy(sourceMode = mode, updatedAt = now()))
            _message.value = when (mode) {
                SourceMode.MODEL -> "已切换到 AI 自备知识模式。已生成的内容不受影响；重新梳理知识主干后按新设置生成。"
                SourceMode.DOCUMENTS -> "已切换到以资料为准模式。已生成的内容不受影响；重新梳理知识主干后按新设置生成。"
            }
        }
    }

    /**
     * 切换知识来源模式前，是否需要先让用户确认。
     *
     * 已经生成过内容时才问：那说明用户可能没意识到「切换不会改写已有内容」——
     * 有人是冲着「重来一遍」点它的，也有人只是想看看。空项目切换没有任何代价，
     * 多一次弹窗反而把最顺的那条路堵住。
     */
    suspend fun needsSourceModeConfirm(mode: SourceMode): Boolean {
        val current = container.projectRepository.get(projectId) ?: return false
        if (current.sourceMode == mode) return false
        return container.knowledgeRepository.getByProject(projectId).isNotEmpty() ||
            container.chapterRepository.getPlans(projectId).isNotEmpty()
    }

    /**
     * 修改风格（含「AI 自由发挥」）。改完需重新规划讲述路线才生效（问题6）。
     *
     * [replan] 为 true 时顺手入队重新规划——前提是项目还没有可读正文
     * （已有正文时整本重规划被 PipelineService 硬性禁止，这里直接给出
     * 「从下一卷生效」的说明，而不是让用户在生成横幅里看到失败）。
     */
    fun setGenre(genre: String, replan: Boolean = false) {
        viewModelScope.launch {
            val current = container.projectRepository.get(projectId) ?: return@launch
            container.projectRepository.update(
                // resolvedGenre 清空，让模型在下次规划时重新自选
                current.copy(genre = genre, resolvedGenre = "", updatedAt = now()),
            )
            if (!replan) {
                _message.value = "已改为「$genre」。到「讲述路线」页点「重新规划」后生效"
                return@launch
            }
            val hasReadable = container.chapterRepository.getPlans(projectId).any { it.isReadable }
            if (hasReadable) {
                _message.value = "已改为「$genre」。这本书已有正文，新讲法从「继续下一卷」开始生效"
                return@launch
            }
            launchEnqueue("已改为「$genre」，正在按新讲法重新规划讲述路线") {
                GenerationEnqueuer.storyBible(getApplication(), projectId)
            }
        }
    }

    /** 单章生成 / 重试，明确指定阶段与章节（B5/B3） */
    fun enqueueChapter(planId: String) {
        launchEnqueue("已开始生成该章节（需要网络）") {
            GenerationEnqueuer.singleChapter(
                context = getApplication(),
                projectId = projectId,
                chapterId = planId,
                chapterLabel = container.chapterRepository.getPlan(planId)
                    ?.let { "第 ${it.order} 章" }
                    .orEmpty(),
            )
        }
    }

    /** 重新核验并重生成待复核章节（B3） */
    fun retryNeedsReview() {
        launchEnqueue("已开始重新生成待复核章节") {
            GenerationEnqueuer.chapters(
                context = getApplication(),
                projectId = projectId,
                plans = container.chapterRepository.getPlans(projectId),
                includeNeedsReview = true,
            )
        }
    }

    /**
     * 只重新出题，不重写正文（第二阶段）。
     *
     * 走同步调用而不是 Worker：出题是一次模型调用，用户是在章节卡片上主动点的，
     * 没必要为它排一条链——更重要的是它绝不会触发正文重生成。
     */
    fun regenerateQuiz(planId: String) {
        viewModelScope.launch {
            _message.value = "正在重新生成自测题…"
            val result = container.pipeline.regenerateQuiz(projectId, planId)
            _message.value = if (result.isSuccess) {
                "自测题已生成"
            } else {
                "自测题仍未生成：${result.exceptionOrNull()?.message ?: "未知原因"}。正文不受影响。"
            }
        }
    }

    /**
     * 单独重试一章的因果契约（三段式）。
     *
     * 走同步调用而不是 Worker：契约是一次短调用，用户是在章节卡片上主动点的。
     * 更重要的是它绝不碰正文——重试契约只会让这一章从「不能写」变成「能写」，
     * 已经写好的正文一个字都不会动。
     */
    fun retryChapterContract(planId: String) {
        viewModelScope.launch {
            if (planId in _retryingContracts.value) return@launch
            _retryingContracts.value = _retryingContracts.value + planId
            _message.value = "正在补这一章的因果契约…"
            val result = runCatching { container.pipeline.retryChapterContract(projectId, planId) }
                .getOrElse { Result.failure(it) }
            _message.value = if (result.isSuccess) {
                "契约已生成，这一章可以写正文了"
            } else {
                "契约仍未生成：${result.exceptionOrNull()?.message ?: "未知原因"}"
            }
            _retryingContracts.value = _retryingContracts.value - planId
        }
    }

    /**
     * 补核验（只查不写）。
     *
     * 核验调用失败不再作废正文——正文照常发布，核验用这个入口单独补：
     * 对当前发布版本重跑一次核验，正文一个字都不会动。
     */
    fun reverifyChapter(planId: String) {
        viewModelScope.launch {
            if (planId in _reverifying.value) return@launch
            _reverifying.value = _reverifying.value + planId
            _message.value = "正在重新核验这一章…"
            val result = runCatching { container.pipeline.reverifyChapter(planId) }
                .getOrElse { Result.failure(it) }
            _message.value = if (result.isSuccess) {
                "核验完成，正文没有变动"
            } else {
                result.exceptionOrNull()?.message ?: "核验没跑完，正文不受影响"
            }
            _reverifying.value = _reverifying.value - planId
        }
    }

    /**
     * 按一条「漏掉的知识点」把它补进知识主干（gaps 闭环）。
     *
     * 只补**一个**节点、只发**一次**调用——为一条缺口重跑整张地图（几万 token、
     * 几分钟）是纯粹的浪费。补完这条提示自动变成「已补上」，移出待办区。
     */
    fun patchKnowledgeGap(gapId: String) {
        viewModelScope.launch {
            if (gapId in _patchingGaps.value) return@launch
            _patchingGaps.value = _patchingGaps.value + gapId
            _message.value = "正在补上这个知识点…"
            val result = runCatching { container.pipeline.generateKnowledgeMapPatch(projectId, gapId) }
                .getOrElse { Result.failure(it) }
            val title = result.getOrNull().orEmpty()
            _message.value = if (result.isSuccess) {
                "已补上「$title」，写下一卷时它会讲进去"
            } else {
                "没补上：${result.exceptionOrNull()?.message ?: "未知原因"}"
            }
            _patchingGaps.value = _patchingGaps.value - gapId
        }
    }

    /**
     * 点「知道了」：把一条可补的缺口移出待办。
     *
     * 只有「漏掉的知识点」能关。过时 / 地区差异 / 各家说法不一这类是免责提示，
     * 不是待办，后端也会拒绝关闭——它们要一直提醒用户核对权威出处。
     */
    fun dismissKnowledgeGap(gapId: String) {
        viewModelScope.launch {
            val result = runCatching { container.pipeline.dismissGap(projectId, gapId) }
                .getOrElse { Result.failure(it) }
            _message.value = if (result.isSuccess) {
                "已移出待办"
            } else {
                "没能移出：${result.exceptionOrNull()?.message ?: "未知原因"}"
            }
        }
    }

    /**
     * 点「知道了」：把一条资料冲突移出「别只信AI」。
     *
     * 冲突没有「补上」一说，只有「我知道了，别再提示」。关闭状态按 topic 继承，
     * 重新梳理后不会复活。
     */
    fun dismissKnowledgeConflict(conflictId: String) {
        viewModelScope.launch {
            val result = runCatching { container.pipeline.dismissConflict(projectId, conflictId) }
                .getOrElse { Result.failure(it) }
            _message.value = if (result.isSuccess) {
                "已移出待办"
            } else {
                "没能移出：${result.exceptionOrNull()?.message ?: "未知原因"}"
            }
        }
    }

    /**
     * 入队并把结果翻译成一句话。
     *
     * 「已经在生成了」必须如实说出来——用户连点两下时若还提示「已开始生成」，
     * 他会以为排了两个任务、正在双倍扣费。
     */
    private fun launchEnqueue(startedMessage: String, block: suspend () -> EnqueueResult) {
        viewModelScope.launch {
            _message.value = when (val result = runCatching { block() }.getOrNull()) {
                is EnqueueResult.Started -> startedMessage
                EnqueueResult.AlreadyRunning ->
                    "这个项目已经在生成了，没有重复排队，也不会重复消耗额度。"
                is EnqueueResult.Nothing -> result.reason
                null -> "排队失败，请重试"
            }
        }
    }

    fun deleteProject() {
        viewModelScope.launch {
            deleteProjectCompletely(container, projectId)
        }
    }
}

class ReaderViewModel(
    app: Application,
    val chapterId: String,
) : ZhishuViewModel(app) {

    /** 阅读位置：停在哪一块 + 块内偏移。不再用整章像素值（换机型/字号就失效） */
    data class ReadAnchor(val blockId: String?, val offset: Int)

    val chapter = MutableStateFlow<ChapterPlan?>(null)
    val project = MutableStateFlow<Project?>(null)
    val blocks: StateFlow<List<ChapterBlock>> =
        container.chapterRepository.observeActiveBlocks(chapterId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _nodes = MutableStateFlow<List<KnowledgeNode>>(emptyList())
    val nodes: StateFlow<List<KnowledgeNode>> = _nodes

    val denseMode = MutableStateFlow(false)
    val prevChapterId = MutableStateFlow<String?>(null)
    val nextChapterId = MutableStateFlow<String?>(null)

    /** 全书章节（按 order 排好）：阅读器内的「全书目录」直接用，不用回项目页 */
    val allPlans = MutableStateFlow<List<ChapterPlan>>(emptyList())

    // 阅读偏好（翻页/滚动、字号、纸色）——持久化在 LearningPrefs，改了立即生效且记住
    val readerMode: StateFlow<com.example.zhishu.data.prefs.ReaderMode> =
        container.learningPrefs.readerMode
    val readerFontSizeIndex: StateFlow<Int> = container.learningPrefs.readerFontSize
    val readerPaper: StateFlow<com.example.zhishu.data.prefs.ReaderPaper> =
        container.learningPrefs.readerPaper
    /** 正文衬线（宋体）/黑体切换 */
    val readerSerif: StateFlow<Boolean> = container.learningPrefs.readerSerif

    fun setReaderMode(mode: com.example.zhishu.data.prefs.ReaderMode) {
        container.learningPrefs.setReaderMode(mode)
    }

    fun setFontSize(index: Int) {
        container.learningPrefs.setReaderFontSize(index)
    }

    fun setPaper(paper: com.example.zhishu.data.prefs.ReaderPaper) {
        container.learningPrefs.setReaderPaper(paper)
    }

    fun setSerif(serif: Boolean) {
        container.learningPrefs.setReaderSerif(serif)
    }

    /** 本章自测题数：0 时不显示「自测」按钮，但绝不阻塞继续读下一章。只数当前版本的题（3A） */
    val quizCount: StateFlow<Int> =
        container.quizRepository.observeActiveByChapter(chapterId)
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** null 表示进度还没读出来，阅读器在此之前不要写库，否则会把位置覆盖成开头 */
    private val _savedAnchor = MutableStateFlow<ReadAnchor?>(null)
    val savedAnchor: StateFlow<ReadAnchor?> = _savedAnchor

    /**
     * 「本章已更新，从章首开始阅读」——一次性提示（3D）。
     *
     * 正文换版本时进度锚点被清回章首（旧 blockId 属于上一版正文，在新正文里不存在）。
     * 光清不说的话，用户明明读到一半、回来却在开头，页面还一言不发，
     * 看起来就是进度丢了。看过一次就不再提，靠 `progressNoticeShown` 记账。
     */
    private val _bodyUpdatedNotice = MutableStateFlow(false)
    val bodyUpdatedNotice: StateFlow<Boolean> = _bodyUpdatedNotice

    /**
     * 每个正文块的来源（4A）。blockId → 汇总。
     *
     * 只含当前 activeVersion 的引用：历史版本的引用留在库里，
     * 但混进来会让「本章有几处来源」这个数字变得没有意义，
     * 点过去还会打开读者看不到的正文。
     */
    private val _blockSources = MutableStateFlow<Map<String, BlockSourceSummary>>(emptyMap())
    val blockSources: StateFlow<Map<String, BlockSourceSummary>> = _blockSources

    /**
     * 章节级来源摘要（4C）。统计范围只含当前 active version。
     *
     * 和 [blockSources] 同一次装载算出来：两者读的是同一批引用，
     * 分两次查会出现「摘要说 7 处来源、逐块数只有 5 处」这种自己打自己脸的情况。
     */
    private val _chapterTrust = MutableStateFlow(ChapterTrustSummary())
    val chapterTrust: StateFlow<ChapterTrustSummary> = _chapterTrust

    /**
     * 跳转过来的 blockId 在当前版本里已经不存在（4C）。
     *
     * 搜索结果、资料回跳、知识地图锚点都可能是在上一版正文上算出来的。
     * 正文换版本后每个块都是新 ID（4B），旧链接必然落空。
     * 什么都不说会让用户以为「点了没反应」，所以如实说一句：
     * 那一段属于旧版本，本章已经更新过。
     *
     * 和 [bodyUpdatedNotice] 不同，这条不落库、也没有「消费」这一步：
     * 它说的是**这一次跳转**的结果，生命周期就是这一次进入阅读器。
     * 换一章或重新跳一次都会拿到新的 ViewModel，提示自然消失；
     * 记进库反而会让它在一次跟本次跳转无关的阅读里冒出来。
     */
    private val _staleTargetNotice = MutableStateFlow(false)
    val staleTargetNotice: StateFlow<Boolean> = _staleTargetNotice

    fun reportStaleTarget() {
        _staleTargetNotice.value = true
    }

    private var lastSaved: ReadAnchor? = null

    init {
        load()
        observeSources()
    }

    /**
     * 引用、片段、资料三张表拼成按块分组的来源信息。
     *
     * 跟着正文块流重算：重生成发布后 blocks 会换一批新 ID，
     * 旧的来源汇总必须一起换掉，否则阅读器会拿上一版的来源去配新正文。
     */
    private fun observeSources() {
        viewModelScope.launch {
            blocks.collectLatest { currentBlocks ->
                if (currentBlocks.isEmpty()) {
                    _blockSources.value = emptyMap()
                    _chapterTrust.value = ChapterTrustSummary()
                    return@collectLatest
                }
                val projectId = currentBlocks.first().projectId
                val citations = container.citationRepository.getActiveByChapter(chapterId)
                val chunkIds = citations.mapNotNull { it.chunkId }.distinct()
                val chunks = container.sourceRepository
                    .getChunksByIds(projectId, chunkIds)
                    .associateBy { it.id }
                val documents = container.sourceRepository.getByProject(projectId)
                    .associateBy { it.id }
                // 「这一版是不是 AI 自备知识写的」记在版本上而不是项目设置上（4A）
                val version = container.chapterRepository.getActiveVersion(chapterId)
                // 当前版本的核验问题（4B/4C）：块级面板要能说出「这一段有已知问题」
                val issues = container.issueRepository.getActiveByChapter(chapterId)
                _blockSources.value = BlockSourceSummary.build(
                    blockIds = currentBlocks.map { it.id },
                    citations = citations,
                    chunks = chunks,
                    documents = documents,
                    modelKnowledge = version?.usedModelKnowledge ?: false,
                    issues = issues,
                )
                _chapterTrust.value = ChapterTrustSummary.build(
                    citations = citations,
                    chunks = chunks,
                    documents = documents,
                    issues = issues,
                    usedModelKnowledge = version?.usedModelKnowledge ?: false,
                    unverifiedClaimCount = container.chapterRepository.getPlan(chapterId)
                        ?.unverifiedClaims?.size ?: 0,
                )
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val plan = container.chapterRepository.getPlan(chapterId) ?: return@launch
            chapter.value = plan
            project.value = container.projectRepository.get(plan.projectId)
            _nodes.value = container.knowledgeRepository.getByProject(plan.projectId)
            val plans = container.chapterRepository.getPlans(plan.projectId).sortedBy { it.order }
            allPlans.value = plans
            val index = plans.indexOfFirst { it.id == chapterId }
            prevChapterId.value = plans.getOrNull(index - 1)?.id
            nextChapterId.value = plans.getOrNull(index + 1)?.id
            // 只有保存的进度确实属于当前章节时才恢复（B7）
            val saved = container.progressRepository.get(plan.projectId)
            _savedAnchor.value = if (saved?.chapterId == chapterId) {
                ReadAnchor(saved.blockId, saved.blockOffset)
            } else {
                ReadAnchor(null, 0)
            }
            // 提示只对「进度记的就是这一章」有意义：换到别的章本来就从头读
            _bodyUpdatedNotice.value = saved?.chapterId == chapterId && saved.needsBodyUpdatedNotice
        }
    }

    /** 提示已展示，落库记账，下次进来不再提 */
    fun consumeBodyUpdatedNotice() {
        if (!_bodyUpdatedNotice.value) return
        _bodyUpdatedNotice.value = false
        viewModelScope.launch {
            val projectId = chapter.value?.projectId ?: return@launch
            container.progressRepository.markNoticeShown(projectId)
        }
    }

    fun saveProgress(blockId: String?, offset: Int) {
        val anchor = ReadAnchor(blockId, offset)
        if (anchor == lastSaved) return
        lastSaved = anchor
        viewModelScope.launch {
            val plan = chapter.value ?: return@launch
            // 只写位置。整行 upsert 会把 bodyUpdatedAt / progressNoticeShown 冲掉（3D）
            container.progressRepository.saveAnchor(
                ReadingProgress(
                    id = plan.projectId,
                    projectId = plan.projectId,
                    chapterId = chapterId,
                    blockId = blockId,
                    blockOffset = offset,
                    updatedAt = now(),
                ),
            )
        }
    }
}

class QuizViewModel(
    app: Application,
    val chapterId: String,
) : ZhishuViewModel(app) {

    val chapter = MutableStateFlow<ChapterPlan?>(null)
    val quizzes: StateFlow<List<Quiz>> =
        container.quizRepository.observeActiveByChapter(chapterId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selections = MutableStateFlow<Map<String, String>>(emptyMap())
    val selections: StateFlow<Map<String, String>> = _selections

    /** quizId → 是否答对。UI 直接订阅这个 StateFlow（N1） */
    private val _answers = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val answers: StateFlow<Map<String, Boolean>> = _answers

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() {
        _message.value = null
    }

    init {
        viewModelScope.launch {
            chapter.value = container.chapterRepository.getPlan(chapterId)
            restoreAnswers()
        }
    }

    /** 从数据库恢复答题历史，重新进入页面不会重复计分（N12） */
    private suspend fun restoreAnswers() {
        val existing = container.quizRepository.getActiveByChapter(chapterId)
        val records = container.quizRepository.getAnswers(existing.map { it.id })
        if (records.isEmpty()) return
        _answers.value = records.associate { it.quizId to it.isCorrect }
    }

    fun selectOption(quizId: String, option: String) {
        if (_answers.value.containsKey(quizId)) return
        _selections.update { it + (quizId to option) }
    }

    /**
     * 提交答案。回忆题按用户自评（[recalledCorrectly]）判定，不再一律记正确（B9）。
     * 同一题只计分一次：已有答题记录时直接返回（N12）。
     */
    fun submit(quiz: Quiz, recalledCorrectly: Boolean? = null) {
        if (_answers.value.containsKey(quiz.id)) return
        val correct = when (quiz.type) {
            QuizType.RECALL -> recalledCorrectly ?: return
            else -> {
                val selected = _selections.value[quiz.id] ?: return
                selected == quiz.answer
            }
        }
        _answers.update { it + (quiz.id to correct) }
        viewModelScope.launch {
            if (container.quizRepository.hasAnswered(quiz.id)) {
                _message.value = "这道题此前已作答，本次不再重复计入掌握度"
                return@launch
            }
            container.quizRepository.recordAnswer(
                AnswerRecord(
                    id = UUID.randomUUID().toString(),
                    quizId = quiz.id,
                    projectId = quiz.projectId,
                    isCorrect = correct,
                    answeredAt = now(),
                ),
            )
            val nodes = container.knowledgeRepository.getByProject(quiz.projectId)
            quiz.nodeIds.forEach { nodeId ->
                val node = nodes.firstOrNull { it.id == nodeId } ?: return@forEach
                val delta = if (correct) 0.15f else -0.1f
                container.knowledgeRepository.updateMastery(nodeId, (node.mastery + delta).coerceIn(0f, 1f))
            }
        }
    }
}

class ReviewViewModel(app: Application) : ZhishuViewModel(app) {

    private val _items = MutableStateFlow<List<ReviewItem>>(emptyList())
    val items: StateFlow<List<ReviewItem>> = _items

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects

    private val _nodes = MutableStateFlow<Map<String, KnowledgeNode>>(emptyMap())
    val nodes: StateFlow<Map<String, KnowledgeNode>> = _nodes

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _projects.value = container.projectRepository.observeAll().first()
            _items.value = container.reviewRepository.getDue(now())
            val nodeMap = mutableMapOf<String, KnowledgeNode>()
            _items.value.map { it.projectId }.distinct().forEach { pid ->
                container.knowledgeRepository.getByProject(pid).forEach { node -> nodeMap[node.id] = node }
            }
            _nodes.value = nodeMap
        }
    }

    /**
     * 完成一次复习：保持 PENDING 并推进到下一次到期时间，
     * 否则写成 DONE 后该节点永远不会再出现在待复习列表里（B2）。
     */
    fun complete(item: ReviewItem) {
        viewModelScope.launch {
            val nextInterval = when (item.intervalDays) {
                1 -> 3
                3 -> 7
                7 -> 14
                else -> 30
            }
            container.reviewRepository.upsertAll(
                listOf(
                    item.copy(
                        status = ReviewStatus.PENDING,
                        intervalDays = nextInterval,
                        dueAt = now() + nextInterval * 86_400_000L,
                    ),
                ),
            )
            refresh()
        }
    }

    /** 用户认为已经完全掌握，退出复习循环 */
    fun archive(item: ReviewItem) {
        viewModelScope.launch {
            container.reviewRepository.upsertAll(listOf(item.copy(status = ReviewStatus.DONE)))
            refresh()
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class SearchViewModel(
    app: Application,
    initialProjectId: String?,
) : ZhishuViewModel(app) {

    val projects: StateFlow<List<Project>> =
        container.projectRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedProjectId = MutableStateFlow(initialProjectId.orEmpty())
    val query = MutableStateFlow("")

    private val _results = MutableStateFlow<List<SearchHit>>(emptyList())
    val results: StateFlow<List<SearchHit>> = _results

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    init {
        // 未显式指定项目时，自动选中唯一项目或最近更新的项目，避免搜索恒为空（N10）
        viewModelScope.launch {
            if (selectedProjectId.value.isNotEmpty()) return@launch
            val all = container.projectRepository.observeAll().first()
            selectedProjectId.value = when {
                all.size == 1 -> all.first().id
                all.isNotEmpty() -> all.first().id
                else -> ""
            }
        }
        // 输入停顿后自动搜索（用户反馈：改了词没点搜索，页面还挂着上一次的结果，
        // 看起来「内容与输入不匹配」）。手动 search()（键盘搜索键/放大镜）保留，
        // 两者并存：debounce 只是把「等用户点」变成「等用户停」。
        // query 是 StateFlow，天然只在值变化时发射，无需再 distinctUntilChanged
        viewModelScope.launch {
            query.debounce(SEARCH_DEBOUNCE_MILLIS)
                .collectLatest { search() }
        }
    }

    /**
     * 页面复用同一个 ViewModel 时（key 固定为 "search"），把新入口带来的项目
     * 应用到已有实例上——否则从项目页进入搜索时选中的还是上一个项目。
     */
    fun applyInitialProject(projectId: String?) {
        if (projectId.isNullOrBlank()) return
        if (selectedProjectId.value == projectId) return
        selectedProjectId.value = projectId
        // selectedProjectId 不在 debounce 流里，切项目后立刻按当前词重搜
        search()
    }

    fun search() {
        viewModelScope.launch {
            val q = query.value.trim()
            val pid = selectedProjectId.value
            if (q.isEmpty() || pid.isEmpty()) {
                _results.value = emptyList()
                return@launch
            }
            _searching.value = true
            _results.value = container.searchRepository.search(pid, q)
            _searching.value = false
        }
    }

    private companion object {
        /** 输入停顿多久后自动搜索。太短会边打字边打库，太长显得迟钝 */
        const val SEARCH_DEBOUNCE_MILLIS = 400L
    }
}

class SettingsViewModel(app: Application) : ZhishuViewModel(app) {

    /** 某个配置方案的模型列表搜索状态（问题1）。key 为方案 id，新增草稿用 [NEW_PROFILE_KEY] */
    sealed interface ModelListState {
        data object Idle : ModelListState
        data object Loading : ModelListState
        data class Loaded(val models: List<String>) : ModelListState
        data class Failed(val reason: String) : ModelListState
    }

    /** 方案库：所有已保存的 API 配置方案 */
    private val _profiles = MutableStateFlow<List<ApiProfile>>(emptyList())
    val profiles: StateFlow<List<ApiProfile>> = _profiles

    /** 三档分工各自指向的方案 id（null = 未配置） */
    private val _assignments = MutableStateFlow<Map<ModelRole, Long?>>(emptyMap())
    val assignments: StateFlow<Map<ModelRole, Long?>> = _assignments

    /**
     * 正在编辑的方案：null = 没有在编辑；[NEW_PROFILE_KEY] = 新增草稿；其余 = 方案 id。
     * 方案库一次只允许编辑一张卡，避免多张卡同时处于半保存状态。
     */
    private val _editingProfileId = MutableStateFlow<Long?>(null)
    val editingProfileId: StateFlow<Long?> = _editingProfileId

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** 各方案的连通性测试结果（B18）。key 同 [ModelListState] */
    private val _testResults = MutableStateFlow<Map<Long, String>>(emptyMap())
    val testResults: StateFlow<Map<Long, String>> = _testResults

    /** 各方案搜索到的可用模型（问题1）。用户从列表里挑，不用手打模型名。 */
    private val _modelLists = MutableStateFlow<Map<Long, ModelListState>>(emptyMap())
    val modelLists: StateFlow<Map<Long, ModelListState>> = _modelLists

    /**
     * 当前打开着「选择模型」弹窗的方案 key。
     *
     * **必须放在 ViewModel 里**，不能是界面的局部状态。原来弹窗开关是
     * 组合里的 `remember { mutableStateOf(false) }`，而触发它的
     * [modelLists] 却存活在 ViewModel 中——设置页卡片都很高，滑出屏幕后
     * 局部状态被回收、滑回来又重新组合，于是「搜到列表就自动弹窗」被反复重演。
     * 放在这里之后，弹窗开没开是唯一的事实来源，滚动与重组都不影响它。
     */
    private val _pickerProfiles = MutableStateFlow<Set<Long>>(emptySet())
    val pickerProfiles: StateFlow<Set<Long>> = _pickerProfiles

    fun openPicker(profileKey: Long) = _pickerProfiles.update { it + profileKey }

    fun closePicker(profileKey: Long) = _pickerProfiles.update { it - profileKey }

    /** 间隔复习开关（默认关）。关掉时读完章节不会自动生出复习任务 */
    val spacedReviewEnabled: StateFlow<Boolean> = container.learningPrefs.spacedReviewEnabled

    fun setSpacedReviewEnabled(enabled: Boolean) {
        container.learningPrefs.setSpacedReviewEnabled(enabled)
    }

    init { load() }

    fun load() {
        viewModelScope.launch {
            val repo = container.settingsRepository
            _profiles.value = repo.getProfiles()
            _assignments.value = repo.getAssignments()
        }
    }

    /** 进入编辑态：传 null 表示新增（草稿用 [NEW_PROFILE_KEY] 占位） */
    fun startEdit(profileId: Long?) {
        _editingProfileId.value = profileId ?: NEW_PROFILE_KEY
    }

    fun cancelEdit() {
        _editingProfileId.value = null
    }

    /**
     * 用页面上当前填写的地址与 Key 搜索可用模型（问题1）。
     * 不要求先保存——否则用户得先盲填模型名才能保存，顺序正好是反的。
     *
     * 搜到列表时顺手打开选择弹窗（省一次点击）。注意这是**事件驱动**的：
     * 只在搜索真正完成的那一刻开一次。
     */
    fun fetchModels(profileKey: Long, baseUrl: String, apiKey: String) {
        viewModelScope.launch {
            _modelLists.update { it + (profileKey to ModelListState.Loading) }
            // 编辑已有方案且没重输 Key 时，沿用该方案已保存的 Key 密文
            val savedKeyRef = if (profileKey == NEW_PROFILE_KEY) {
                null
            } else {
                _profiles.value.firstOrNull { it.id == profileKey }?.keyRef
            }
            val result = container.router.listModelsWith(baseUrl, apiKey, savedKeyRef)
            val loaded = result.getOrNull()?.let { models ->
                models.filter { it.isNotBlank() }.distinct().sorted()
            }
            _modelLists.update { current ->
                current + (
                    profileKey to if (loaded == null) {
                        ModelListState.Failed(result.exceptionOrNull()?.message ?: "搜索失败")
                    } else if (loaded.isEmpty()) {
                        ModelListState.Failed("这个接口没有返回任何模型，可手动填写模型名")
                    } else {
                        ModelListState.Loaded(loaded)
                    }
                    )
            }
            if (loaded != null && loaded.isNotEmpty()) openPicker(profileKey)
        }
    }

    fun saveProfile(profile: ApiProfile, plainApiKey: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val keyRef = if (plainApiKey.isNotBlank()) {
                    container.encryptApiKey(plainApiKey)
                } else {
                    profile.keyRef
                }
                // 名字留空就自动编号，保证方案列表里每套都能认出来
                val named = profile.copy(
                    name = profile.name.ifBlank { "配置方案 ${_profiles.value.size + 1}" },
                    keyRef = keyRef,
                )
                container.settingsRepository.saveProfile(named)
            }.onSuccess {
                onDone("已保存")
                // 只有保存成功才退出编辑态并刷新：失败时输入得以保留，用户改完能直接重试
                cancelEdit()
                load()
            }.onFailure { onDone("保存失败：${it.message}") }
            _busy.value = false
        }
    }

    fun deleteProfile(profile: ApiProfile, onDone: (String) -> Unit) {
        viewModelScope.launch {
            container.settingsRepository.deleteProfile(profile.id)
            onDone("已删除「${profile.name}」")
            load()
        }
    }

    /** 复制一套方案（含已保存的 Key）：想用同一个服务商只改温度/模型时最快 */
    fun duplicateProfile(profile: ApiProfile) {
        viewModelScope.launch {
            container.settingsRepository.saveProfile(
                profile.copy(id = 0, name = "${profile.name}（副本）"),
            )
            load()
        }
    }

    fun assignRole(role: ModelRole, profileId: Long?) {
        viewModelScope.launch {
            container.settingsRepository.assignRole(role, profileId)
            load()
        }
    }

    /** 一键把三个环节都指到同一套方案：整站试一个新 LLM 用 */
    fun assignAll(profileId: Long?) {
        viewModelScope.launch {
            val repo = container.settingsRepository
            ModelRole.entries.forEach { repo.assignRole(it, profileId) }
            load()
        }
    }

    /**
     * 拉取模型列表验证地址与 Key 是否可用（B18），并追加一次**真实的最小生成调用**。
     *
     * 只拉 /models 测不出「模型名有效但没额度」「推理模型当普通模型用」这类失败：
     * 列表照样返回成功，用户到生成时才炸（用户反馈：「测试连接说成功，一生成就失败」）。
     * 现在两步都跑：列表失败就报列表问题；列表成功再做一次极小生成（几分钱以内），
     * 把真实生成才会暴露的问题提前到配置页。
     */
    fun testProfile(profileKey: Long) {
        viewModelScope.launch {
            _busy.value = true
            _testResults.update { it + (profileKey to "正在测试…") }
            val listResult = container.router.listModels(profileKey)
            val text = listResult.fold(
                onSuccess = { models ->
                    // 列表通了，再做一次真实小生成
                    container.router.probe(profileKey).fold(
                        onSuccess = {
                            if (models.isEmpty()) {
                                "连接成功（该接口不提供模型列表，可手动填写模型名）"
                            } else {
                                "连接成功，可用模型 ${models.size} 个"
                            }
                        },
                        // 生成失败时把原因原样带出来——多半就是「为什么生成时炸」的答案
                        onFailure = { "接口能连通，但实际生成失败：${it.message}" },
                    )
                },
                onFailure = { "连接失败：${it.message}" },
            )
            _testResults.update { it + (profileKey to text) }
            _busy.value = false
        }
    }

    fun setBusy(value: Boolean) {
        _busy.value = value
    }

    companion object {
        /** 新增方案的草稿 key（方案自增 id 从 1 开始，不会撞上） */
        const val NEW_PROFILE_KEY = -1L
    }
}
