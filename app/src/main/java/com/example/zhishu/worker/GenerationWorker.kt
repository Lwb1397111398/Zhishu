package com.example.zhishu.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.zhishu.ZhishuApp
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.pipeline.ChapterPhase
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ModelRole
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** 生成阶段。由 UI 显式指定，不再由项目状态反推（B5） */
enum class GenerationStage {
    KNOWLEDGE_MAP,
    STORY_BIBLE,
    CHAPTERS,
    SINGLE_CHAPTER,

    /** 一键开讲：知识地图 → 讲述路线 → 首章，串成一次操作（问题4） */
    ONE_TAP,
    ;

    val label: String
        get() = when (this) {
            KNOWLEDGE_MAP -> "正在梳理知识主干"
            STORY_BIBLE -> "正在规划讲述路线"
            CHAPTERS -> "正在生成章节"
            SINGLE_CHAPTER -> "正在生成本章"
            ONE_TAP -> "正在准备"
        }
}

/**
 * 批量生成时应当处理哪些章节（B3/B5）。
 * 已发布的跳过；待复核 / 测验待补只在用户显式重试时纳入。
 */
internal fun selectChaptersToGenerate(
    plans: List<ChapterPlan>,
    includeNeedsReview: Boolean,
): List<ChapterPlan> {
    val retryable = setOf(ChapterStatus.NEEDS_REVIEW, ChapterStatus.QUIZ_FAILED)
    return plans
        .filter { plan ->
            when {
                plan.status == ChapterStatus.PUBLISHED -> false
                plan.status in retryable -> includeNeedsReview
                else -> true
            }
        }
        .sortedBy { it.order }
}

/**
 * 该异常是否值得让 WorkManager 重试（B4）。
 * 协程取消不走这里（由调用方直接重抛）；配置类错误与业务前置条件失败不重试。
 */
internal fun isRetryableFailure(e: Throwable): Boolean = when (e) {
    is CancellationException -> false
    is ModelError -> e.retryable
    is IllegalStateException, is IllegalArgumentException -> false
    else -> true
}

class GenerationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return Result.failure()
        val stage = inputData.getString(KEY_STAGE)
            ?.let { name -> GenerationStage.entries.firstOrNull { it.name == name } }
            ?: return Result.failure(workDataOf(KEY_ERROR to "未指定生成阶段"))
        val chapterId = inputData.getString(KEY_CHAPTER_ID)
        val includeNeedsReview = inputData.getBoolean(KEY_INCLUDE_NEEDS_REVIEW, false)
        val container = (applicationContext as ZhishuApp).container

        return try {
            container.projectRepository.get(projectId)
                ?: return Result.failure(workDataOf(KEY_ERROR to "项目不存在"))
            // 缺配置就直接给出可读原因，不要等模型调用失败后静默结束（问题1）
            checkModelConfigured(container, stage)?.let { missing ->
                return Result.failure(workDataOf(KEY_ERROR to missing))
            }
            when (stage) {
                GenerationStage.KNOWLEDGE_MAP -> {
                    // 知识梳理是一次模型调用，没有可细分的子步骤
                    val tracker = ProgressTracker(total = 1)
                    report(stage.label, tracker)
                    container.pipeline.generateKnowledgeMap(projectId).getOrThrow()
                    tracker.complete()
                    report(stage.label, tracker)
                }

                GenerationStage.STORY_BIBLE -> {
                    val tracker = ProgressTracker(total = 1)
                    report(stage.label, tracker)
                    container.pipeline.generateStoryBible(projectId).getOrThrow()
                    tracker.complete()
                    report(stage.label, tracker)
                }

                GenerationStage.SINGLE_CHAPTER -> {
                    val id = chapterId
                        ?: return Result.failure(workDataOf(KEY_ERROR to "未指定章节"))
                    val tracker = ProgressTracker(total = ChapterPhase.UNITS_PER_CHAPTER)
                    report(stage.label, tracker)
                    container.pipeline.generateChapter(projectId, id) { phase ->
                        report(phase.label, tracker)
                        tracker.complete()
                    }.getOrThrow()
                }

                GenerationStage.CHAPTERS -> generateAllChapters(container, projectId, includeNeedsReview)

                GenerationStage.ONE_TAP -> runOneTap(container, projectId)
            }
            Result.success()
        } catch (e: CancellationException) {
            // 协程取消必须原样抛出，不能当成业务失败重试（B4）
            throw e
        } catch (e: Exception) {
            if (isRetryableFailure(e) && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                // 达到重试上限也要给出可读原因，避免「一直转圈还在扣费」（问题1）
                val reason = e.message ?: "生成失败"
                val detail = if (isRetryableFailure(e) && runAttemptCount >= MAX_RETRY_ATTEMPTS) {
                    "$reason（已重试 $runAttemptCount 次仍失败，已停止以避免继续消耗额度）"
                } else {
                    reason
                }
                Result.failure(workDataOf(KEY_ERROR to detail))
            }
        }
    }

    /**
     * 把当前阶段与百分比写进 progress，UI 才能显示确定进度条（问题1、问题2）。
     *
     * 百分比按「已完成的工作单元 / 总单元」算，单元边界都是真实事件，
     * 所以不会出现卡在某个数字上假装还在动的情况。
     */
    private suspend fun report(text: String, tracker: ProgressTracker? = null) {
        runCatching {
            setProgress(
                workDataOf(
                    KEY_PROGRESS to text,
                    KEY_PROGRESS_COMPLETED to (tracker?.completed ?: 0),
                    KEY_PROGRESS_TOTAL to (tracker?.total ?: 0),
                ),
            )
        }
    }

    /** 可变的工作单元计数器。总量在任务开始时就能确定，因此百分比是真实的而非估算。 */
    private class ProgressTracker(val total: Int) {
        var completed: Int = 0
            private set

        fun complete() {
            if (completed < total) completed += 1
        }
    }

    /**
     * 一键开讲（问题4）：把三步串成一次操作，中途按需跳过已完成的阶段。
     * 任一阶段失败就停下并把原因抛出，由 UI 展示。
     *
     * 进度总量先按「需要做的阶段」算出来，所以百分比从一开始就是准的（问题2）。
     */
    private suspend fun runOneTap(container: AppContainer, projectId: String) {
        val needsMap = container.knowledgeRepository.getByProject(projectId).isEmpty()
        val needsBible = container.chapterRepository.getPlans(projectId).isEmpty()
        // 知识梳理、路线规划各算 1 个单元；首章按子步骤算 3 个
        val total = (if (needsMap) 1 else 0) +
            (if (needsBible) 1 else 0) +
            ChapterPhase.UNITS_PER_CHAPTER
        val tracker = ProgressTracker(total = total)

        if (needsMap) {
            report(GenerationStage.KNOWLEDGE_MAP.label, tracker)
            container.pipeline.generateKnowledgeMap(projectId).getOrThrow()
            tracker.complete()
        }
        if (needsBible) {
            report(GenerationStage.STORY_BIBLE.label, tracker)
            container.pipeline.generateStoryBible(projectId).getOrThrow()
            tracker.complete()
        }
        val first = container.chapterRepository.getPlans(projectId)
            .sortedBy { it.order }
            .firstOrNull { it.status != ChapterStatus.PUBLISHED }
            ?: return
        container.pipeline.generateChapter(projectId, first.id) { phase ->
            report("第 ${first.order} 章 · ${phase.label}", tracker)
            tracker.complete()
        }.getOrThrow()
    }

    /**
     * 入队后最常见的「点了没反应」其实是没配模型：
     * 模型调用返回 Auth 错误、Worker 判定不可重试、静默结束，UI 什么也不显示。
     * 这里提前检查并给出明确文案。
     */
    private suspend fun checkModelConfigured(
        container: AppContainer,
        stage: GenerationStage,
    ): String? {
        val roles = when (stage) {
            GenerationStage.KNOWLEDGE_MAP -> listOf(ModelRole.FAST)
            GenerationStage.STORY_BIBLE -> listOf(ModelRole.STORY)
            else -> listOf(ModelRole.FAST, ModelRole.STORY, ModelRole.VERIFY)
        }
        val missing = roles.filter { role ->
            val config = container.settingsRepository.getConfig(role)
            config == null || config.baseUrl.isBlank() || config.modelName.isBlank()
        }
        if (missing.isEmpty()) return null
        return "还没有配置模型（${missing.joinToString("、") { it.displayName }}）。" +
            "请到「我的」页填写接口地址、模型名和 API Key。"
    }

    /**
     * 批量生成：单章失败不终止整批，最后按是否全部失败决定 Worker 结果。
     * NEEDS_REVIEW / QUIZ_FAILED 只有在用户显式重试时才纳入（B3）。
     */
    private suspend fun generateAllChapters(
        container: AppContainer,
        projectId: String,
        includeNeedsReview: Boolean,
    ) {
        val plans = selectChaptersToGenerate(
            plans = container.chapterRepository.getPlans(projectId),
            includeNeedsReview = includeNeedsReview,
        )
        if (plans.isEmpty()) {
            container.pipeline.refreshProjectStatus(projectId)
            return
        }

        var lastError: Throwable? = null
        var succeeded = 0
        // 总量 = 章数 × 每章子步骤数，进度条按真实完成的子步骤推进（问题2）
        val tracker = ProgressTracker(total = plans.size * ChapterPhase.UNITS_PER_CHAPTER)
        for ((index, plan) in plans.withIndex()) {
            val prefix = "第 ${plan.order} 章（${index + 1}/${plans.size}）"
            report(prefix, tracker)
            val result = container.pipeline.generateChapter(projectId, plan.id) { phase ->
                report("$prefix · ${phase.label}", tracker)
                tracker.complete()
            }
            if (result.isSuccess) {
                succeeded += 1
            } else {
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                lastError = error
                // 认证、配置类错误继续跑下去毫无意义，直接中断
                if (error is ModelError && !error.retryable && error !is ModelError.Invalid) break
            }
        }
        container.pipeline.refreshProjectStatus(projectId)
        if (succeeded == 0 && lastError != null) throw lastError
    }

    companion object {
        const val KEY_PROJECT_ID = "projectId"
        const val KEY_STAGE = "stage"
        const val KEY_CHAPTER_ID = "chapterId"
        const val KEY_INCLUDE_NEEDS_REVIEW = "includeNeedsReview"
        const val KEY_ERROR = "error"
        /** WorkManager 层的重试上限，防止可重试错误无限退避、持续消耗 API 额度 */
        const val MAX_RETRY_ATTEMPTS = 3
        /** Worker 通过 setProgress 上报当前阶段文案，UI 订阅 WorkInfo 展示（问题1） */
        const val KEY_PROGRESS = "progress"
        /** 已完成 / 总工作单元数，UI 据此显示百分比与确定进度条（问题2） */
        const val KEY_PROGRESS_COMPLETED = "progressCompleted"
        const val KEY_PROGRESS_TOTAL = "progressTotal"
        const val UNIQUE_WORK = "generation"

        fun uniqueWorkName(projectId: String): String = "$UNIQUE_WORK-$projectId"

        fun enqueue(
            context: Context,
            projectId: String,
            stage: GenerationStage,
            chapterId: String? = null,
            includeNeedsReview: Boolean = false,
        ) {
            val data = workDataOf(
                KEY_PROJECT_ID to projectId,
                KEY_STAGE to stage.name,
                KEY_CHAPTER_ID to chapterId,
                KEY_INCLUDE_NEEDS_REVIEW to includeNeedsReview,
            )
            val request = OneTimeWorkRequestBuilder<GenerationWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(projectId),
                // 用 APPEND_OR_REPLACE 保证连续点击不会互相取消已排队的阶段
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
