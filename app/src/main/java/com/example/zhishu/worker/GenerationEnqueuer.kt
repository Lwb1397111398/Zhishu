package com.example.zhishu.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkContinuation
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.zhishu.ai.pipeline.ChapterPhase
import com.example.zhishu.domain.model.ChapterPlan
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** 一次入队请求的结果，UI 据此给用户一句话反馈 */
sealed interface EnqueueResult {
    /** 已排入，[stageCount] 是这条链上的工作步数 */
    data class Started(val stageCount: Int) : EnqueueResult

    /** 已经有一个生成会话在跑，这次点击不产生任何新任务，也不会重复扣费 */
    data object AlreadyRunning : EnqueueResult

    /** 没有需要做的事（例如所有章节都已发布） */
    data class Nothing(val reason: String) : EnqueueResult
}

/**
 * 生成任务入队（第二阶段）。
 *
 * 两件事最重要：
 *
 * 1. **同一项目同时只有一个生成会话。** 原来用 APPEND_OR_REPLACE，连续点两下
 *    就把两个收费任务排进队列。现在用项目级唯一名 + [ExistingWorkPolicy.KEEP]：
 *    已有会话在排队或运行时，KEEP 让新请求原地作废——这道保险在调度层，
 *    不依赖按钮禁用，也不靠静态布尔（那个进程一重启就没了）。
 * 2. **批量在入队时就展开成一章一个 WorkRequest。** 章节顺序有依赖
 *    （runningState / carryOver 要上一章的结果），所以串成 [WorkContinuation] 顺序执行，
 *    而不是并行。进程重启后 WorkManager 自己接着跑剩下的。
 */
object GenerationEnqueuer {

    /** 知识梳理 / 路线规划各算 1 个工作单元；一章算 3 个（正文、核验、出题） */
    private const val UNITS_SIMPLE_STAGE = 1

    /**
     * 是否已经有一个活跃会话。
     *
     * 只看 ENQUEUED / RUNNING / BLOCKED：BLOCKED 是链上还没轮到的章节，
     * 那也算「正在生成」。退避重试期间 WorkInfo 是 ENQUEUED，同样算活跃。
     */
    suspend fun isActive(context: Context, projectId: String): Boolean =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(GenerationWorker.uniqueWorkName(projectId))
            .await()
            .any { !it.state.isFinished }

    /** 取消整条项目级链，包括还没轮到的章节 */
    fun cancel(context: Context, projectId: String) {
        GenerationScheduler.cancel(context, projectId)
        GenerationNotifier.clearProgress(context)
    }

    /** 只梳理知识主干 */
    suspend fun knowledgeMap(context: Context, projectId: String): EnqueueResult =
        enqueueChain(context, projectId, modelStages = 1) { session ->
            listOf(simpleRequest(projectId, GenerationStage.KNOWLEDGE_MAP, session, 0, UNITS_SIMPLE_STAGE))
        }

    /** 只规划讲述路线 */
    suspend fun storyBible(context: Context, projectId: String): EnqueueResult =
        enqueueChain(context, projectId, modelStages = 1) { session ->
            listOf(simpleRequest(projectId, GenerationStage.STORY_BIBLE, session, 0, UNITS_SIMPLE_STAGE))
        }

    /** 指定一章 */
    suspend fun singleChapter(
        context: Context,
        projectId: String,
        chapterId: String,
        chapterLabel: String = "",
    ): EnqueueResult = enqueueChain(context, projectId, modelStages = 1) { session ->
        listOf(
            chapterRequest(
                projectId = projectId,
                chapterId = chapterId,
                label = chapterLabel,
                session = session,
                unitsBefore = 0,
                unitsTotal = ChapterPhase.UNITS_PER_CHAPTER,
            ),
        )
    }

    /**
     * 批量章节：入队时读一次数据库，为每一章建一个 WorkRequest 并串起来。
     *
     * 已发布的章节不进链（含旧库里的 QUIZ_FAILED——正文好着呢，重排纯属浪费）。
     */
    suspend fun chapters(
        context: Context,
        projectId: String,
        plans: List<ChapterPlan>,
        includeNeedsReview: Boolean = false,
    ): EnqueueResult {
        val targets = selectChaptersToGenerate(plans, includeNeedsReview)
        if (targets.isEmpty()) {
            return EnqueueResult.Nothing("没有需要生成的章节，所有章节都已完成")
        }
        val total = targets.size * ChapterPhase.UNITS_PER_CHAPTER
        return enqueueChain(context, projectId, modelStages = targets.size) { session ->
            targets.mapIndexed { index, plan ->
                chapterRequest(
                    projectId = projectId,
                    chapterId = plan.id,
                    label = "第 ${plan.order} 章（${index + 1}/${targets.size}）",
                    session = session,
                    unitsBefore = index * ChapterPhase.UNITS_PER_CHAPTER,
                    unitsTotal = total,
                )
            } + summaryRequest(projectId, session, total)
        }
    }

    /**
     * 一键开讲：知识梳理 → 路线规划 → 首章，三个独立 WorkRequest 串一条链。
     *
     * 首章用 FIRST_CHAPTER（运行时现查），因为入队这一刻讲述路线还没生成，
     * 章节 ID 根本还不存在。
     */
    suspend fun oneTap(
        context: Context,
        projectId: String,
        needsMap: Boolean,
        needsBible: Boolean,
    ): EnqueueResult {
        val total = (if (needsMap) UNITS_SIMPLE_STAGE else 0) +
            (if (needsBible) UNITS_SIMPLE_STAGE else 0) +
            ChapterPhase.UNITS_PER_CHAPTER
        val stages = (if (needsMap) 1 else 0) + (if (needsBible) 1 else 0) + 1
        return enqueueChain(context, projectId, modelStages = stages) { session ->
            buildList {
                var before = 0
                if (needsMap) {
                    add(simpleRequest(projectId, GenerationStage.KNOWLEDGE_MAP, session, before, total))
                    before += UNITS_SIMPLE_STAGE
                }
                if (needsBible) {
                    add(simpleRequest(projectId, GenerationStage.STORY_BIBLE, session, before, total))
                    before += UNITS_SIMPLE_STAGE
                }
                add(
                    chapterRequest(
                        projectId = projectId,
                        chapterId = null,
                        label = "第一章",
                        session = session,
                        unitsBefore = before,
                        unitsTotal = total,
                    ),
                )
                add(summaryRequest(projectId, session, total))
            }
        }
    }

    /**
     * 把一串 WorkRequest 串成项目级唯一链。
     *
     * 先查活跃状态是为了给用户一句准话（「已经在生成了」），
     * 真正的防重靠 KEEP：查询和入队之间那点时间窗里再点一次，也只会被 KEEP 挡掉。
     */
    private suspend fun enqueueChain(
        context: Context,
        projectId: String,
        /** 这条链上真正要调模型的步数，用来给用户显示「几步」。链尾收尾不算。 */
        modelStages: Int,
        build: (session: String) -> List<OneTimeWorkRequest>,
    ): EnqueueResult {
        if (isActive(context, projectId)) return EnqueueResult.AlreadyRunning

        val session = UUID.randomUUID().toString()
        val requests = build(session)
        if (requests.isEmpty()) return EnqueueResult.Nothing("没有需要执行的步骤")

        val manager = WorkManager.getInstance(context)
        var continuation: WorkContinuation = manager.beginUniqueWork(
            GenerationWorker.uniqueWorkName(projectId),
            // KEEP：已有未完成的会话时保留旧的、丢弃新的，绝不追加第二个收费任务。
            // 上一条链已经结束（成功/失败/取消）时，KEEP 会用新链替换它，
            // 顺带把旧的 WorkInfo 清掉，新会话的状态不会被历史失败污染。
            ExistingWorkPolicy.KEEP,
            requests.first(),
        )
        requests.drop(1).forEach { continuation = continuation.then(it) }
        continuation.enqueue().result.await()
        return EnqueueResult.Started(modelStages)
    }

    private fun simpleRequest(
        projectId: String,
        stage: GenerationStage,
        session: String,
        unitsBefore: Int,
        unitsTotal: Int,
    ): OneTimeWorkRequest = request(
        session = session,
        data = baseData(projectId, stage, session, unitsBefore, unitsTotal),
    )

    private fun chapterRequest(
        projectId: String,
        chapterId: String?,
        label: String,
        session: String,
        unitsBefore: Int,
        unitsTotal: Int,
    ): OneTimeWorkRequest {
        val stage = if (chapterId == null) {
            GenerationStage.FIRST_CHAPTER
        } else {
            GenerationStage.SINGLE_CHAPTER
        }
        return request(
            session = session,
            data = Data.Builder()
                .putAll(baseData(projectId, stage, session, unitsBefore, unitsTotal))
                .putString(GenerationWorker.KEY_CHAPTER_ID, chapterId)
                .putString(GenerationWorker.KEY_CHAPTER_LABEL, label)
                .build(),
        )
    }

    private fun summaryRequest(
        projectId: String,
        session: String,
        unitsTotal: Int,
    ): OneTimeWorkRequest = request(
        session = session,
        data = baseData(projectId, GenerationStage.SUMMARY, session, unitsTotal, unitsTotal),
        // 收尾只发一条通知，不需要网络也不需要重试
        requiresNetwork = false,
    )

    private fun baseData(
        projectId: String,
        stage: GenerationStage,
        session: String,
        unitsBefore: Int,
        unitsTotal: Int,
    ): Data = workDataOf(
        GenerationWorker.KEY_PROJECT_ID to projectId,
        GenerationWorker.KEY_STAGE to stage.name,
        GenerationWorker.KEY_SESSION to session,
        GenerationWorker.KEY_UNITS_BEFORE to unitsBefore,
        GenerationWorker.KEY_UNITS_TOTAL to unitsTotal,
    )

    private fun request(
        session: String,
        data: Data,
        requiresNetwork: Boolean = true,
    ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<GenerationWorker>()
        .setInputData(data)
        .addTag(GenerationWorker.sessionTag(session))
        .apply {
            if (requiresNetwork) {
                setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
            }
        }
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        // 长任务会 setForeground，加急配额用完时退回普通排队即可
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
}

/**
 * 把 WorkManager 的 ListenableFuture 变成挂起调用。
 *
 * 自己写而不是引 coroutines-guava：只为一个 await 多拉一个依赖不值得，
 * 而且这里要的行为很确定——协程取消时把 future 也取消掉。
 */
private suspend fun <T> ListenableFuture<T>.await(): T {
    if (isDone) {
        return try {
            get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
    return suspendCancellableCoroutine { cont ->
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (e: ExecutionException) {
                    cont.resumeWithException(e.cause ?: e)
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                }
            },
            // 回调很轻（只是把结果交回协程），直接在完成线程上跑
            Runnable::run,
        )
        cont.invokeOnCancellation { cancel(false) }
    }
}
