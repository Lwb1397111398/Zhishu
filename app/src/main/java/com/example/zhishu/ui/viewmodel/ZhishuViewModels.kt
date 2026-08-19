package com.example.zhishu.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.example.zhishu.ZhishuApp
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.GENRE_AUTO
import com.example.zhishu.domain.model.KnowledgeConflict
import com.example.zhishu.domain.model.KnowledgeGap
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ModelConfig
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
import com.example.zhishu.search.ParseResult
import com.example.zhishu.search.SearchHit
import com.example.zhishu.search.SourceInput
import com.example.zhishu.worker.GenerationStage
import com.example.zhishu.worker.GenerationWorker
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

abstract class ZhishuViewModel(app: Application) : AndroidViewModel(app) {
    protected val container: AppContainer
        get() = (getApplication<Application>() as ZhishuApp).container
}

private fun now(): Long = System.currentTimeMillis()

/**
 * 删除项目全部数据。顺序上先删子数据再删项目，且 chapter_blocks 按 projectId 直接删，
 * 不依赖 chapters 表是否还在（N6）。整体放在一个事务里，避免中途失败留下半份数据。
 */
internal suspend fun deleteProjectCompletely(container: AppContainer, projectId: String) {
    container.inTransaction {
        container.chapterRepository.clearBlocksForProject(projectId)
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
    )

    val projects: StateFlow<List<Project>> =
        container.projectRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _dueCount = MutableStateFlow(0)
    val dueCount: StateFlow<Int> = _dueCount

    /** 关闭时首页不出现「今日复习」，主线保持「继续阅读」 */
    val spacedReviewEnabled: StateFlow<Boolean> = container.learningPrefs.spacedReviewEnabled

    private val _continueEntry = MutableStateFlow<ContinueEntry?>(null)
    val continueEntry: StateFlow<ContinueEntry?> = _continueEntry

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _dueCount.value = if (spacedReviewEnabled.value) {
                container.reviewRepository.getDue(now()).size
            } else {
                0
            }
            val all = container.projectRepository.observeAll().first()
            val latest = all.mapNotNull { project ->
                val progress = container.progressRepository.get(project.id) ?: return@mapNotNull null
                val chapter = container.chapterRepository.getPlan(progress.chapterId)
                    ?: return@mapNotNull null
                ContinueEntry(project, progress, chapter.title)
            }.maxByOrNull { it.progress.updatedAt }
            _continueEntry.value = latest
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            deleteProjectCompletely(container, projectId)
            refresh()
        }
    }

    fun seedDemo() {
        viewModelScope.launch {
            DemoSeeder.seed(container)
            refresh()
        }
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
        val blocksByChapter = container.chapterRepository.getBlocksByProject(projectId)
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

    fun deleteSource(sourceId: String) {
        viewModelScope.launch {
            container.sourceRepository.delete(sourceId)
        }
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

    fun enqueueKnowledgeMap() {
        enqueue(GenerationStage.KNOWLEDGE_MAP, message = "已开始梳理知识主干（需要网络）")
    }

    fun enqueueStoryBible() {
        enqueue(GenerationStage.STORY_BIBLE, message = "已开始规划讲述路线（需要网络）")
    }

    fun enqueueChapters() {
        enqueue(GenerationStage.CHAPTERS, message = "已开始生成未完成的章节（需要网络）")
    }

    /** 一键开讲：知识地图 → 讲述路线 → 首章，一次点完（问题4） */
    fun enqueueOneTap() {
        enqueue(GenerationStage.ONE_TAP, message = "已开始，将依次完成知识梳理、路线规划和第一章")
    }

    /** 切换知识来源模式（问题3）。切换后既有知识地图与章节都失效，需重新生成。 */
    fun setSourceMode(mode: SourceMode) {
        viewModelScope.launch {
            val current = container.projectRepository.get(projectId) ?: return@launch
            if (current.sourceMode == mode) return@launch
            container.projectRepository.update(current.copy(sourceMode = mode, updatedAt = now()))
            _message.value = when (mode) {
                SourceMode.MODEL -> "已切换到 AI 自备知识模式，可直接开始梳理知识主干"
                SourceMode.DOCUMENTS -> "已切换到以资料为准模式，请先添加学习资料"
            }
        }
    }

    /** 修改风格（含「AI 自由发挥」）。改完需重新规划讲述路线才生效（问题6） */
    fun setGenre(genre: String) {
        viewModelScope.launch {
            val current = container.projectRepository.get(projectId) ?: return@launch
            container.projectRepository.update(
                // resolvedGenre 清空，让模型在下次规划时重新自选
                current.copy(genre = genre, resolvedGenre = "", updatedAt = now()),
            )
            _message.value = "已改为「$genre」，重新规划讲述路线后生效"
        }
    }

    /** 单章生成 / 重试，明确指定阶段与章节（B5/B3） */
    fun enqueueChapter(planId: String) {
        GenerationWorker.enqueue(
            context = getApplication(),
            projectId = projectId,
            stage = GenerationStage.SINGLE_CHAPTER,
            chapterId = planId,
        )
        _message.value = "已开始生成该章节（需要网络）"
    }

    /** 重新核验并重生成待复核章节（B3） */
    fun retryNeedsReview() {
        GenerationWorker.enqueue(
            context = getApplication(),
            projectId = projectId,
            stage = GenerationStage.CHAPTERS,
            includeNeedsReview = true,
        )
        _message.value = "已开始重新生成待复核章节"
    }

    private fun enqueue(stage: GenerationStage, message: String) {
        GenerationWorker.enqueue(
            context = getApplication(),
            projectId = projectId,
            stage = stage,
        )
        _message.value = message
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
        container.chapterRepository.observeBlocks(chapterId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _nodes = MutableStateFlow<List<KnowledgeNode>>(emptyList())
    val nodes: StateFlow<List<KnowledgeNode>> = _nodes

    val denseMode = MutableStateFlow(false)
    val prevChapterId = MutableStateFlow<String?>(null)
    val nextChapterId = MutableStateFlow<String?>(null)

    /** 本章自测题数：0 时不显示「自测」按钮，但绝不阻塞继续读下一章 */
    val quizCount: StateFlow<Int> =
        container.quizRepository.observeByChapter(chapterId)
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** null 表示进度还没读出来，阅读器在此之前不要写库，否则会把位置覆盖成开头 */
    private val _savedAnchor = MutableStateFlow<ReadAnchor?>(null)
    val savedAnchor: StateFlow<ReadAnchor?> = _savedAnchor

    private var lastSaved: ReadAnchor? = null

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val plan = container.chapterRepository.getPlan(chapterId) ?: return@launch
            chapter.value = plan
            project.value = container.projectRepository.get(plan.projectId)
            _nodes.value = container.knowledgeRepository.getByProject(plan.projectId)
            val plans = container.chapterRepository.getPlans(plan.projectId).sortedBy { it.order }
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
        }
    }

    fun saveProgress(blockId: String?, offset: Int) {
        val anchor = ReadAnchor(blockId, offset)
        if (anchor == lastSaved) return
        lastSaved = anchor
        viewModelScope.launch {
            val plan = chapter.value ?: return@launch
            container.progressRepository.save(
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
        container.quizRepository.observeByChapter(chapterId)
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
        val existing = container.quizRepository.getByChapter(chapterId)
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

    fun openResult(hit: SearchHit): Pair<String, String?>? =
        hit.chapterId?.let { it to hit.blockId }
}

class SettingsViewModel(app: Application) : ZhishuViewModel(app) {

    /** 某个角色的可选模型列表状态（问题1） */
    sealed interface ModelListState {
        data object Idle : ModelListState
        data object Loading : ModelListState
        data class Loaded(val models: List<String>) : ModelListState
        data class Failed(val reason: String) : ModelListState
    }

    private val _configs = MutableStateFlow<List<ModelConfig>>(emptyList())
    val configs: StateFlow<List<ModelConfig>> = _configs

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** 各角色的连通性测试结果（B18） */
    private val _testResults = MutableStateFlow<Map<ModelRole, String>>(emptyMap())
    val testResults: StateFlow<Map<ModelRole, String>> = _testResults

    /** 各角色搜索到的可用模型（问题1）。用户从列表里挑，不用手打模型名。 */
    private val _modelLists = MutableStateFlow<Map<ModelRole, ModelListState>>(emptyMap())
    val modelLists: StateFlow<Map<ModelRole, ModelListState>> = _modelLists

    /** 间隔复习开关（默认关）。关掉时读完章节不会自动生出复习任务 */
    val spacedReviewEnabled: StateFlow<Boolean> = container.learningPrefs.spacedReviewEnabled

    fun setSpacedReviewEnabled(enabled: Boolean) {
        container.learningPrefs.setSpacedReviewEnabled(enabled)
    }

    init { load() }

    fun load() {
        viewModelScope.launch {
            _configs.value = container.settingsRepository.getAllConfigs()
        }
    }

    /**
     * 用页面上当前填写的地址与 Key 搜索可用模型（问题1）。
     * 不要求先保存——否则用户得先盲填模型名才能保存，顺序正好是反的。
     */
    fun fetchModels(role: ModelRole, baseUrl: String, apiKey: String) {
        viewModelScope.launch {
            _modelLists.update { it + (role to ModelListState.Loading) }
            val result = container.router.listModelsWith(role, baseUrl, apiKey)
            _modelLists.update { current ->
                current + (
                    role to result.fold(
                        onSuccess = { models ->
                            val cleaned = models.filter { it.isNotBlank() }.distinct().sorted()
                            if (cleaned.isEmpty()) {
                                ModelListState.Failed("这个接口没有返回任何模型，可手动填写模型名")
                            } else {
                                ModelListState.Loaded(cleaned)
                            }
                        },
                        onFailure = { ModelListState.Failed(it.message ?: "搜索失败") },
                    )
                    )
            }
        }
    }

    fun saveConfig(config: ModelConfig, plainApiKey: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                val keyRef = if (plainApiKey.isNotBlank()) {
                    container.encryptApiKey(plainApiKey)
                } else {
                    config.keyRef
                }
                container.settingsRepository.saveConfig(config.copy(keyRef = keyRef))
            }.onSuccess { onDone("已保存") }
                .onFailure { onDone("保存失败：${it.message}") }
            _busy.value = false
            load()
        }
    }

    /** 拉取模型列表验证地址与 Key 是否可用（B18） */
    fun testConnection(role: ModelRole) {
        viewModelScope.launch {
            _busy.value = true
            _testResults.update { it + (role to "正在测试…") }
            val result = container.router.listModels(role)
            val text = result.fold(
                onSuccess = { models ->
                    if (models.isEmpty()) "连接成功，但未返回模型列表" else "连接成功，可用模型 ${models.size} 个"
                },
                onFailure = { "连接失败：${it.message}" },
            )
            _testResults.update { it + (role to text) }
            _busy.value = false
        }
    }

    fun setBusy(value: Boolean) {
        _busy.value = value
    }
}
