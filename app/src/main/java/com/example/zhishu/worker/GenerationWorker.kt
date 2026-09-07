package com.example.zhishu.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.zhishu.ZhishuApp
import com.example.zhishu.ai.GenerationLog
import com.example.zhishu.ai.pipeline.ChapterPhase
import com.example.zhishu.ai.pipeline.KnowledgeMapMode
import com.example.zhishu.di.AppContainer
import kotlinx.coroutines.CancellationException

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

            // 缺配置就直接给出可读原因，不要等模型调用失败后静默结束（问题1）。
            // 检查的角色按本阶段实际要用的算，不再无条件要求三样齐全。
            missingRoles(stage)?.let { return Result.failure(workDataOf(KEY_ERROR to it)) }

            runStage(stage, project.title)
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
                }.getOrThrow()
                report(stage.label, unitsBefore + done, projectTitle)
            }

            GenerationStage.STORY_BIBLE -> {
                // 逐章契约是最长的一段：大纲一次调用 + 每章一次。进度往上报，
                // 前台通知随之刷新——这既是让用户看到进展，也是在向系统证明
                // 这个任务还活着（长任务的存活就靠这条通知）。
                var done = 0
                container.pipeline.generateStoryBible(projectId) { progress ->
                    val text = progress.label.ifBlank { stage.label }
                    val completed = unitsBefore + progress.completed
                    val total = (unitsBefore + progress.total)
                        .coerceAtLeast(unitsTotal)
                        .coerceAtLeast(completed + 1)
                    report(text, completed, projectTitle, total)
                    done = progress.completed
                }.getOrThrow()
                report(stage.label, unitsBefore + 1, projectTitle)
            }

            GenerationStage.SINGLE_CHAPTER, GenerationStage.FIRST_CHAPTER ->
                runChapter(stage, projectTitle)

            GenerationStage.SUMMARY -> Unit
        }
        return Result.success()
    }

    /** 生成一章。FIRST_CHAPTER 现查「第一个还没写的章节」，不依赖入队时的快照。 */
    private suspend fun runChapter(stage: GenerationStage, projectTitle: String) {
        val chapterId = when (stage) {
            GenerationStage.FIRST_CHAPTER -> container.chapterRepository.getPlans(projectId)
                .sortedBy { it.order }
                .firstOrNull { !it.status.isReadable }
                ?.id
                // 章节都写完了不算失败，一键开讲到这里就算达成目的
                ?: return
            else -> inputData.getString(KEY_CHAPTER_ID)
                ?: throw IllegalStateException("未指定章节")
        }
        val label = inputData.getString(KEY_CHAPTER_LABEL).orEmpty()
        var done = 0
        container.pipeline.generateChapter(projectId, chapterId) { phase ->
            val text = if (label.isBlank()) phase.label else "$label · ${phase.label}"
            report(text, unitsBefore + done, projectTitle)
            done += 1
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
        val missingQuiz = plans.count { it.quizState == com.example.zhishu.domain.model.QuizState.FAILED }
        val detail = buildString {
            append("已完成 $readable/${plans.size} 章，可以开始读了。")
            if (missingQuiz > 0) append("其中 $missingQuiz 章的自测题没生成，正文不受影响。")
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
        // 缓存最新进度，给 ForegroundKeeper 的重试用（重试时要用当下最新内容重建通知）
        lastProjectTitle = projectTitle
        lastStageText = text
        lastCompleted = completed
        lastTotal = totalOverride
        runCatching {
            setProgress(
                workDataOf(
                    KEY_PROGRESS to text,
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
                    stageText = text,
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
        val missing = stage.requiredRoles.filter { role ->
            val profile = container.settingsRepository.getAssignedProfile(role)
            profile == null || profile.baseUrl.isBlank() || profile.modelName.isBlank()
        }
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
