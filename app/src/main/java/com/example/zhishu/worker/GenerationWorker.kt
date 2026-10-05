package com.example.zhishu.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.zhishu.ZhishuApp
import com.example.zhishu.ai.GenerationLog
import com.example.zhishu.ai.UsageAccumulator
import com.example.zhishu.ai.UsageTrackerElement
import com.example.zhishu.ai.pipeline.ChapterPhase
import com.example.zhishu.ai.pipeline.KnowledgeMapMode
import com.example.zhishu.ai.pipeline.NextVolumeAvailability
import com.example.zhishu.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 单一阶段的生成 Worker（第二阶段）。
 *
 * 一个实例只负责一件事：一次知识梳理、一次路线规划、或者一章正文。
 * 批量由入队方展开成链（见 [GenerationEnqueuer]），这里不知道也不关心「下一章是谁」。
 *
 * 真正调模型的阶段会 [setForeground]：单章要写正文、过核验、出题三次调用，
 * 加上退避重试，最坏情况能顶到普通 Worker 的执行窗口边上。
 */
class GenerationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val container: AppContainer
        get() = (applicationContext as ZhishuApp).container

    private val projectId: String get() = inputData.getString(KEY_PROJECT_ID).orEmpty()

    private val stage: GenerationStage?
        get() = inputData.getString(KEY_STAGE)
            ?.let { name -> GenerationStage.entries.firstOrNull { it.name == name } }

    /**
     * 会话级进度基数：本 Worker 之前已经完成了多少个工作单元、整条链一共多少个。
     * 存在 inputData 里而不是内存计数器，所以进程重启后百分比不会跳回去。
     */
    private val unitsBefore: Int get() = inputData.getInt(KEY_UNITS_BEFORE, 0)
    private val unitsTotal: Int get() = inputData.getInt(KEY_UNITS_TOTAL, 0)

    /**
     * 本 Worker（通常是「写一章」）累计的 token 用量（3.2）。
     *
     * 每个阶段一个 Worker 实例，这个数就是本阶段的消耗；进度条文字带上它，
     * 用户看着数字涨就知道钱花在哪一步。会话总量在链尾 [summarize] 从
     * AppContainer 的会话账本里取（每个阶段结束时往账本里加一笔）。
     */
    private val usage = UsageAccumulator()

    /**
     * 整段收在同一个 try 里（含读项目、查配置、链尾收尾）。
     *
     * 原来这些前置步骤在 try 之外：它们一旦抛异常，WorkManager 只收得到一个
     * 「失败」，outputData 是空的，界面于是退回兜底文案
     * 「生成失败，请检查网络与模型配置后重试」——用户既不知道真正原因，
     * 还会被这句话误导去改网络与模型配置，而问题可能压根不在那儿。
     */
    override suspend fun doWork(): Result {
        val stage = stage ?: return Result.failure(workDataOf(KEY_ERROR to "未指定生成阶段"))
        return try {
            val project = container.projectRepository.get(projectId)
                ?: return Result.failure(workDataOf(KEY_ERROR to "项目不存在"))

            if (stage == GenerationStage.SUMMARY) return summarize(project.title)
            // ADVANCE 只查库 + 续订，不调模型：在 missingRoles 检查之前顶层短路。
            // 它不该进 runStage（那里是每个 WorkRequest 做一次模型工作的地方），
            // 所以也不给它占一个 when 分支——短路即最清楚的边界。
            if (stage == GenerationStage.ADVANCE) return advance()

            // 缺配置就直接给出可读原因，不要等模型调用失败后静默结束（问题1）。
            // 检查的角色按本阶段实际要用的算，不再无条件要求三样齐全。
            missingRoles(stage)?.let { return Result.failure(workDataOf(KEY_ERROR to it)) }

            val result = runStage(stage, project.title)
            // 本阶段用量记进会话账本（进程内内存）：链尾总结好报总账。
            // 进程被系统收走后账本清零——通知里说「约」，不假装精确
            inputData.getString(KEY_SESSION)?.let { session ->
                if (usage.totalTokens > 0) {
                    container.sessionTokenUsage.merge(session, usage.totalTokens, Long::plus)
                }
            }
            return result
        } catch (e: CancellationException) {
            // 协程取消必须原样抛出：包装成业务失败会让 WorkManager 以为该重试，
            // 用户按了取消反而又开始扣费（B4）
            GenerationNotifier.clearProgress(applicationContext)
            throw e
        } catch (t: Throwable) {
            // 接 Throwable 而不是 Exception：Error 冒出去时同样丢掉全部原因。
            // 真正的「取消」已经在上一支处理过，这里剩下的都按失败如实上报。
            failure(t, stage.label)
        } finally {
            // Worker 结束（成功/失败/被取消）都要停掉前台化重试循环，不让协程泄漏
            foregroundKeeper.stop()
        }
    }

    private suspend fun runStage(stage: GenerationStage, projectTitle: String): Result {
        report(stage.label, unitsBefore, projectTitle)
        when (stage) {
            GenerationStage.KNOWLEDGE_MAP -> {
                // 批次化生成：骨架 → 逐链 → 审计，逐阶段上报进度。
                // mode 由入队方决定推进节奏（全部展开 / 只出骨架 / 单步推一条链）
                val mapMode = inputData.getString(KEY_MAP_MODE)
                    ?.let { name -> runCatching { KnowledgeMapMode.valueOf(name) }.getOrNull() }
                    ?: KnowledgeMapMode.FULL
                var done = 0
                withContext(UsageTrackerElement(usage)) {
                    container.pipeline.generateKnowledgeMap(projectId, mapMode) { progress ->
                        // 知识地图整链路只占 1 个工作单元（一键开讲的百分比以章节为准），
                        // 骨架/逐链/审计的细分进度用地图自己的单元数并进 UI 百分比：
                        // 骨架 =1、每条链 =1、审计 =1，用户能看到「正在展开第 3/8 条链」确实在动，
                        // 而不是卡在 0/1 上像没反应（B3）。
                        val text = progress.label.ifBlank { stage.label }
                        val completed = unitsBefore + progress.completed
                        val total = unitsBefore + progress.total.coerceAtLeast(progress.completed + 1)
                        report(text, completed, projectTitle, total)
                        done = progress.completed
                    }
                }.getOrThrow()
                // 审计完成、gap 已落库：开自动时把 actionable 的漏知识点补上（成本护栏 10 条）。
                // 只认 FULL——SKELETON/NEXT_CHAIN 是用户明确要「先看结构再决定」的节奏，
                // 地图还没定稿就自动补节点，等于越过用户做主。
                // 补漏失败不推翻地图：单条 gap 留 OPEN，下次点「继续」再补（generateKnowledgeMapPatch 已保证）。
                if (mapMode == KnowledgeMapMode.FULL &&
                    container.learningPrefs.generationAutoAdvance.value
                ) {
                    val n = withContext(UsageTrackerElement(usage)) {
                        container.pipeline.autoResolveGaps(projectId)
                    }
                    if (n > 0) {
                        report("已自动补上 $n 个漏掉的知识点", unitsBefore + done, projectTitle)
                    }
                }
                report(stage.label, unitsBefore + done, projectTitle)
            }

            GenerationStage.STORY_BIBLE -> {
                // 逐章契约是最长的一段：大纲一次调用 + 每章一次。进度往上报，
                // 前台通知随之刷新——这既是让用户看到进展，也是在向系统证明
                // 这个任务还活着（长任务的存活就靠这条通知）。
                var done = 0
                withContext(UsageTrackerElement(usage)) {
                    container.pipeline.generateStoryBible(projectId) { progress ->
                        val text = progress.label.ifBlank { stage.label }
                        val completed = unitsBefore + progress.completed
                        val total = (unitsBefore + progress.total)
                            .coerceAtLeast(unitsTotal)
                            .coerceAtLeast(completed + 1)
                        report(text, completed, projectTitle, total)
                        done = progress.completed
                    }
                }.getOrThrow()
                report(stage.label, unitsBefore + 1, projectTitle)
            }

            GenerationStage.SINGLE_CHAPTER, GenerationStage.FIRST_CHAPTER ->
                runChapter(stage, projectTitle)

            GenerationStage.SUMMARY -> Unit

            // 永不进到这里：ADVANCE 在 doWork 顶层就短路成 advance() 了。
            // 留一支只为 when 穷尽——真走到了说明短路位置被挪错，直接抛错暴露问题。
            GenerationStage.ADVANCE -> throw IllegalStateException("ADVANCE 应在 doWork 顶层短路")

            GenerationStage.PLAN_NEXT_VOLUME -> {
                // 规划下一卷：一次模型调用，append-only（只往后加章节，已发布的一字不改）。
                // 失败即本阶段失败，走 WorkManager 退避重试；重试耗尽停链给可读原因。
                withContext(UsageTrackerElement(usage)) {
                    container.pipeline.generateNextVolume(projectId)
                }.getOrThrow()
                report(stage.label, unitsBefore + 1, projectTitle)
            }
        }
        // 本阶段的 token 消耗随成功一起上报（诊断用；进度文字里已实时显示）
        return Result.success(workDataOf(KEY_TOKENS to usage.totalTokens))
    }

    /** 生成一章。FIRST_CHAPTER 现查「第一个需要写的章节」，不依赖入队时的快照。 */
    private suspend fun runChapter(stage: GenerationStage, projectTitle: String) {
        val chapterId: String
        val label: String
        when (stage) {
            GenerationStage.FIRST_CHAPTER -> {
                val plan = selectFirstChapterToWrite(
                    container.chapterRepository.getPlans(projectId),
                )
                    // 章节都写完了不算失败，一键开讲到这里就算达成目的
                    ?: return
                chapterId = plan.id
                // 入队时不知道会选哪章，运行时按实际章号报告进度
                label = "第 ${plan.order} 章"
            }
            else -> {
                chapterId = inputData.getString(KEY_CHAPTER_ID)
                    ?: throw IllegalStateException("未指定章节")
                label = inputData.getString(KEY_CHAPTER_LABEL).orEmpty()
            }
        }
        var done = 0
        withContext(UsageTrackerElement(usage)) {
            container.pipeline.generateChapter(projectId, chapterId) { phase ->
                val text = if (label.isBlank()) phase.label else "$label · ${phase.label}"
                report(text, unitsBefore + done, projectTitle)
                done += 1
            }
        }.getOrThrow()
        report(ChapterPhase.QUIZ.label, unitsBefore + ChapterPhase.UNITS_PER_CHAPTER, projectTitle)
    }

    /**
     * 链尾总结：只发一条会话级通知，不调模型。
     *
     * 走到这里说明前面每一环都成功了——链中任何一环失败，WorkManager 都不会执行到链尾，
     * 那条「部分完成 / 失败」的通知由失败的那个 Worker 自己发。
     */
    private suspend fun summarize(projectTitle: String): Result {
        val plans = container.chapterRepository.getPlans(projectId)
        val readable = plans.count { it.status.isReadable }
        // 带问题发布（核验挑出问题、自动修复没修干净）的章：正文可读，状态停在待复核。
        // 不算进「已完成」也不能装作不存在——通知里如实说，用户才知道去哪处理。
        val needsReviewPlans = plans.filter {
            it.status == com.example.zhishu.domain.model.ChapterStatus.NEEDS_REVIEW
        }
        val missingQuiz = plans.count { it.quizState == com.example.zhishu.domain.model.QuizState.FAILED }
        // 剩余问题只数待复核章上的：已发布的章要么没有问题、要么问题已被处理完
        val openIssues = runCatching {
            needsReviewPlans.sumOf { container.issueRepository.countActiveOpen(it.id) }
        }.getOrDefault(0)
        val autoHandleOn = container.learningPrefs.autoHandleIssues.value
        val detail = buildString {
            append("已完成 $readable/${plans.size} 章，可以开始读了。")
            if (needsReviewPlans.isNotEmpty()) {
                append("其中 ${needsReviewPlans.size} 章还有 $openIssues 个核验问题没修掉")
                if (autoHandleOn) append("（文风类已自动放过）")
                append("，正文可读，点开章节可逐条查看。")
            }
            if (missingQuiz > 0) append("其中 $missingQuiz 章的自测题没生成，正文不受影响。")
            // 会话总账：每个阶段的 Worker 结束时往账本里加了一笔（3.2）。
            // 账本在内存里，进程中途被杀就取不到——取不到就不写这句，不编数字
            val sessionTokens = inputData.getString(KEY_SESSION)
                ?.let { container.sessionTokenUsage[it] }
                ?: 0L
            if (sessionTokens > 0) {
                append("本次生成共消耗约 ${formatTokens(sessionTokens)} tokens。")
            }
        }
        GenerationNotifier.notifyOutcome(
            context = applicationContext,
            projectId = projectId,
            projectTitle = projectTitle,
            outcome = GenerationOutcome.COMPLETED,
            detail = detail,
        )
        GenerationNotifier.clearProgress(applicationContext)
        return Result.success(workDataOf(KEY_OUTCOME to GenerationOutcome.COMPLETED.name))
    }

    /**
     * 自动闭环的一步（Task 6 的 `doWork` 顶层短路到这里）：查库 → [decideAdvance] → 续订或刹车。
     *
     * 全程只读库 + 调 WorkManager 续订，**不调模型**——模型调用在它 APPEND 出去的
     * 新 WorkRequest 里，各自带 WorkManager 的退避重试。这里失败（读库抛异常）即
     * ADVANCE 失败，链停；用户点「继续」按总开关从断点恢复整个循环。
     *
     * 判据用 isVolumeBlocking（Task 1）而不是 !isReadable：待复核的章正文已可读，
     * 用 isReadable 会让它永久堵死续卷——那正是用户「老是在第一卷」的头号病根。
     */
    private suspend fun advance(): Result {
        val autoOn = container.learningPrefs.generationAutoAdvance.value
        val plans = container.chapterRepository.getPlans(projectId)
        val currentVolume = if (plans.isEmpty()) 0 else plans.maxOf { it.volumeIndex }
        val volumePlans = plans.filter { it.volumeIndex == currentVolume }
        val blocking = volumePlans.count { it.status.isVolumeBlocking }
        // 与批量入口同一条选择规则：排除已发布、排除契约未生成的章（Task 7 之前 targets 恒含 QUEUED 章）
        val targets = selectChaptersToGenerate(volumePlans, includeNeedsReview = false)
        val session = inputData.getString(KEY_SESSION).orEmpty()
        val projectTitle = container.projectRepository.get(projectId)?.title.orEmpty()

        // pending 从 nextVolumeAvailability 拿——它已经算过 coverageReport，这里不重复查一遍
        val pendingCount = when (val av = container.pipeline.nextVolumeAvailability(projectId)) {
            is NextVolumeAvailability.CanContinue -> av.pendingCount
            NextVolumeAvailability.MainLineDone -> 0
            // NoPlanYet / CurrentVolumeUnfinished：一定还有 blocking 或根本没有章，交给 blocking 分支定
            else -> Int.MAX_VALUE
        }

        report(GenerationStage.ADVANCE.label, unitsBefore, projectTitle)
        when (decideAdvance(autoOn, blocking, targets.size, pendingCount)) {
            AdvanceDecision.StoppedBySwitch -> {
                // 关开关不是一种「结果」，不发通知——用户没要求停，只是不要求继续。
                // 进度条该收掉，不然界面永远挂着一条「正在推进下一步」。
                GenerationNotifier.clearProgress(applicationContext)
            }

            AdvanceDecision.ContinueCurrentVolume -> GenerationEnqueuer.appendAutoChunk(
                context = applicationContext,
                projectId = projectId,
                session = session,
                chapterIds = targets.map { it.id },
                chapterLabels = targets.map { "第 ${it.order} 章" },
                unitsTotal = targets.size * ChapterPhase.UNITS_PER_CHAPTER,
            )

            AdvanceDecision.PlanNextVolume -> GenerationEnqueuer.appendVolumePlan(
                context = applicationContext, projectId = projectId, session = session,
            )

            AdvanceDecision.PausedForUser -> {
                GenerationNotifier.notifyOutcome(
                    context = applicationContext, projectId = projectId,
                    projectTitle = projectTitle,
                    outcome = GenerationOutcome.PARTIAL,
                    detail = "自动停在第 ${currentVolume + 1} 卷：剩下的章节因果契约还没生成，" +
                        "不能凭空写正文。到「讲述路线」逐章点「重试契约」后再点「继续」。",
                )
                GenerationNotifier.clearProgress(applicationContext)
            }

            AdvanceDecision.MainLineDone -> {
                GenerationNotifier.notifyOutcome(
                    context = applicationContext, projectId = projectId,
                    projectTitle = projectTitle,
                    outcome = GenerationOutcome.COMPLETED,
                    detail = "主线讲完了：知识主干上的知识点都已安排进章节并写完。" +
                        "想再读可在「讲述路线」点「扩展阅读」另起一卷。",
                )
                GenerationNotifier.clearProgress(applicationContext)
            }
        }
        return Result.success()
    }

    /**
     * 失败收尾：先判断还要不要重试，确定不再试了才发结果通知。
     * 退避重试期间不发通知，否则用户会为同一次失败收到好几条。
     */
    private suspend fun failure(e: Throwable, projectTitle: String): Result {
        if (isRetryableFailure(e) && runAttemptCount < MAX_RETRY_ATTEMPTS) return Result.retry()

        // 模型调用之外的异常没有 HTTP 记录可查，落一条到诊断日志：
        // 「生成失败」四个字本身不是信息，能回答「为什么」的只有这一行。
        GenerationLog.recordFailure(applicationContext, stage?.name ?: "UNKNOWN", e)

        // 没有 message 的异常（典型是超时、被系统收走）不能只回一句「生成失败」——
        // 那会把人引向「网络/模型没配好」，而真实原因常常是任务被系统中断。
        val reason = failureReason(e)
        val detail = if (isRetryableFailure(e)) {
            "$reason（已重试 $runAttemptCount 次仍失败，已停止以避免继续消耗额度）"
        } else {
            reason
        }
        // 已经有章节可读就算「部分完成」，不要把一次尾部失败说成整批失败。
        // 这里查库失败也不能吞掉上面的原因——那等于又把「为什么」弄丢了。
        val readable = runCatching {
            container.chapterRepository.getPlans(projectId).count { it.status.isReadable }
        }.getOrDefault(0)
        val outcome = if (readable > 0) GenerationOutcome.PARTIAL else GenerationOutcome.FAILED
        runCatching {
            GenerationNotifier.notifyOutcome(
                context = applicationContext,
                projectId = projectId,
                projectTitle = projectTitle,
                outcome = outcome,
                // 通知里只放一句人话，不放完整错误响应
                detail = if (outcome == GenerationOutcome.PARTIAL) {
                    "已完成 $readable 章可以先读。剩下的没做完：$reason"
                } else {
                    reason
                },
            )
        }
        GenerationNotifier.clearProgress(applicationContext)
        return Result.failure(
            workDataOf(
                KEY_ERROR to detail,
                KEY_OUTCOME to outcome.name,
                // 永久性配置错误在这里标出来，链上后面几章不必再白调一次
                KEY_PERMANENT to isPermanentConfigFailure(e),
            ),
        )
    }

    /**
     * 上报阶段与进度，同时刷新前台通知。
     *
     * 刷新只发生在阶段边界（正文写完、核验过、题出好），不是每个正文块一次。
     */
    private suspend fun report(text: String, completed: Int, projectTitle: String, totalOverride: Int = unitsTotal) {
        // 已消耗的 token 挂在阶段文字后面：这是本次生成最直接的「花费」，
        // 数字在涨说明模型确实在工作，也让人对成本心里有数（3.2）
        val used = usage.totalTokens
        val shownText = if (used > 0) "$text · 已用 ${formatTokens(used)} tokens" else text
        // 缓存最新进度，给 ForegroundKeeper 的重试用（重试时要用当下最新内容重建通知）
        lastProjectTitle = projectTitle
        lastStageText = shownText
        lastCompleted = completed
        lastTotal = totalOverride
        runCatching {
            setProgress(
                workDataOf(
                    KEY_PROGRESS to shownText,
                    KEY_PROGRESS_COMPLETED to completed,
                    KEY_PROGRESS_TOTAL to totalOverride,
                    KEY_SESSION to inputData.getString(KEY_SESSION),
                ),
            )
        }
        // 前台化失败（权限、系统限制）不能影响生成本身，但要留痕并启动重试循环：
        // Android 12+ 不允许后台启动前台服务（用户切走/锁屏后 setForeground 被拒），
        // 前台服务起不来，长任务就失去了保护，系统随时可能按后台任务的执行窗口
        // 把它收走，而界面上只会剩一句没有原因的「生成失败」。
        // 重试循环保证用户任何时候切回 app，最多一个周期后保护自动补上。
        runCatching {
            setForeground(
                GenerationNotifier.progressForegroundInfo(
                    context = applicationContext,
                    projectId = projectId,
                    projectTitle = projectTitle,
                    stageText = shownText,
                    completed = completed,
                    total = totalOverride,
                ),
            )
        }
            .onSuccess { foregroundKeeper.stop() }
            .onFailure {
                GenerationLog.recordFailure(applicationContext, "FOREGROUND", it)
                foregroundKeeper.start()
            }
    }

    /** 1 万以下直接报数，往上报「x.x 万」——进度条小字里塞不下长数字 */
    private fun formatTokens(tokens: Long): String = if (tokens >= 10_000) {
        String.format(java.util.Locale.CHINA, "%.1f 万", tokens / 10_000.0)
    } else {
        tokens.toString()
    }

    /** 最近一次上报的进度快照：前台化重试时按它重建通知 */
    private var lastProjectTitle: String = ""
    private var lastStageText: String = ""
    private var lastCompleted: Int = 0
    private var lastTotal: Int = 0

    private val foregroundKeeper = ForegroundKeeper(
        attempt = {
            runCatching {
                setForeground(
                    GenerationNotifier.progressForegroundInfo(
                        context = applicationContext,
                        projectId = projectId,
                        projectTitle = lastProjectTitle,
                        stageText = lastStageText,
                        completed = lastCompleted,
                        total = lastTotal,
                    ),
                )
            }.isSuccess
        },
    )

    private suspend fun missingRoles(stage: GenerationStage): String? {
        // 判据统一放在 SettingsRepository.missingModelRoles()：UI 生成前的前置引导
        // 和这里的失败判定必须看到同一个「缺什么」，否则会出现界面说配好了、
        // Worker 还是报缺配置（或反过来）的裂缝
        val missing = container.settingsRepository.missingModelRoles()
            .filter { it in stage.requiredRoles }
        if (missing.isEmpty()) return null
        return "还没有为以下环节选择 API 配置方案：${missing.joinToString("、") { it.displayName }}。" +
            "请到「我的」页新增配置方案，并在「各环节使用的方案」里选择。"
    }

    companion object {
        const val KEY_PROJECT_ID = "projectId"
        const val KEY_STAGE = "stage"
        const val KEY_CHAPTER_ID = "chapterId"
        const val KEY_CHAPTER_LABEL = "chapterLabel"

        /** 知识主干生成节奏（KnowledgeMapMode.name；缺省 = FULL 一口气跑完） */
        const val KEY_MAP_MODE = "knowledgeMapMode"
        const val KEY_ERROR = "error"
        const val KEY_OUTCOME = "outcome"
        const val KEY_PERMANENT = "permanent"

        /** WorkManager 层的重试上限，防止可重试错误无限退避、持续消耗 API 额度 */
        const val MAX_RETRY_ATTEMPTS = 3

        const val KEY_PROGRESS = "progress"
        const val KEY_PROGRESS_COMPLETED = "progressCompleted"
        const val KEY_PROGRESS_TOTAL = "progressTotal"

        /** 本 WorkRequest（通常是一章）累计消耗的 token 数（3.2） */
        const val KEY_TOKENS = "tokensUsed"

        /** 会话标识。UI 靠它把当前会话与上一次失败的 WorkInfo 分开（第二阶段） */
        const val KEY_SESSION = "sessionId"
        const val KEY_UNITS_BEFORE = "unitsBefore"
        const val KEY_UNITS_TOTAL = "unitsTotal"

        const val UNIQUE_WORK = "generation"

        /** 项目级唯一工作名：不同项目各自生成，不用全局锁 */
        fun uniqueWorkName(projectId: String): String = "$UNIQUE_WORK-$projectId"

        /** 会话 tag，形如 `gen-session:<uuid>`，同一次入队的所有 WorkRequest 共用 */
        fun sessionTag(sessionId: String): String = "gen-session:$sessionId"
    }
}
