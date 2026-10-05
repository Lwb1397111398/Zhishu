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
import com.example.zhishu.ai.pipeline.KnowledgeMapMode
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ContractStatus
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

    /**
     * 梳理知识主干。[mode] 决定推进节奏：一口气跑完（FULL，默认/一键开讲）、
     * 只出骨架（SKELETON，用户看完结构自己选逐条还是全部）、单步推一条链
     * （NEXT_CHAIN，链全部完成时该步直接做全局审计收尾）
     */
    suspend fun knowledgeMap(
        context: Context,
        projectId: String,
        mode: KnowledgeMapMode = KnowledgeMapMode.FULL,
    ): EnqueueResult = enqueueChain(context, projectId, modelStages = 1) { session ->
        listOf(
            request(
                session = session,
                data = Data.Builder()
                    .putAll(baseData(projectId, GenerationStage.KNOWLEDGE_MAP, session, 0, UNITS_SIMPLE_STAGE))
                    .putString(GenerationWorker.KEY_MAP_MODE, mode.name)
                    .build(),
            ),
        )
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
     *
     * [plans] 是调用方已查好的章节列表（通常就是刚才判 needsBible 用的那份），
     * 用于入队前预检：不需要梳理/规划、又没有任何可生成的章节时，直接给用户
     * 一句准话，而不是排一条静默结束的链——那会让「重试」看起来跑完了，
     * 实际什么都没做，还发一条「已完成 0 章」的假成功通知。
     */
    suspend fun oneTap(
        context: Context,
        projectId: String,
        needsMap: Boolean,
        needsBible: Boolean,
        /** 自动闭环：为 true 时链尾挂一个 ADVANCE，一路写到主线讲完（总开关开启才传 true） */
        autoAdvance: Boolean = false,
        plans: List<ChapterPlan>? = null,
    ): EnqueueResult {
        if (!needsMap && !needsBible && plans != null &&
            selectFirstChapterToWrite(plans) == null
        ) {
            val contractFailed = plans.count { it.contractStatus == ContractStatus.FAILED.name }
            return EnqueueResult.Nothing(
                if (contractFailed > 0) {
                    "没有需要生成的章节。有 $contractFailed 章的因果契约没生成成功，" +
                        "先在讲述路线页点它们的「补契约」，之后就能继续写了。"
                } else {
                    "没有需要生成的章节，所有章节都已完成"
                },
            )
        }
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
                        // 写到哪一章只有运行时知道，由 Worker 按实际章号标「第 N 章」
                        label = "",
                        session = session,
                        unitsBefore = before,
                        unitsTotal = total,
                    ),
                )
                add(summaryRequest(projectId, session, total))
                // 自动闭环：收尾之后挂一个 ADVANCE。它不烧模型，modelStages 不加计。
                // 挂同一条唯一链尾部 → 一次 cancelUniqueWork 连它一起清掉；isActive 认它是「还在生成」。
                if (autoAdvance) {
                    add(
                        simpleRequest(projectId, GenerationStage.ADVANCE, session, total, total),
                    )
                }
            }
        }
    }

    /**
     * 自动闭环续订：把「若干章节 → 收尾 → 下一个 ADVANCE」APPEND 到项目唯一链尾部。
     *
     * 与 [enqueueChain]（用户发起，KEEP）不同，这里是当前会话链的自我延长：
     * 用 APPEND_OR_REPLACE——链还在（前面步骤没跑完）就接到末尾；万一唯一名下无残留链
     * （竞态窗口），替换而非丢弃，避免续订丢失导致静默停摆。仍共用同一唯一名，
     * 所以取消（一次 cancelUniqueWork）与活跃判定原样生效。
     *
     * [session] 由调用方 Worker 从 inputData 透传，保证链上会话 tag 一致，UI 不把它当「另一次生成」。
     */
    suspend fun appendAutoChunk(
        context: Context,
        projectId: String,
        session: String,
        chapterIds: List<String>,
        chapterLabels: List<String>,
        unitsTotal: Int,
    ) {
        if (chapterIds.isEmpty()) return
        val manager = WorkManager.getInstance(context)
        var continuation: WorkContinuation? = null
        chapterIds.forEachIndexed { index, id ->
            val req = chapterRequest(
                projectId = projectId,
                chapterId = id,
                label = chapterLabels.getOrElse(index) { "" },
                session = session,
                unitsBefore = index * ChapterPhase.UNITS_PER_CHAPTER,
                unitsTotal = unitsTotal,
            )
            continuation = continuation?.then(req)
                ?: manager.beginUniqueWork(
                    GenerationWorker.uniqueWorkName(projectId),
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    req,
                )
        }
        val summary = summaryRequest(projectId, session, unitsTotal)
        val advance = simpleRequest(projectId, GenerationStage.ADVANCE, session, unitsTotal, unitsTotal)
        continuation = continuation?.then(summary)?.then(advance)
        continuation?.enqueue()?.result?.await()
    }

    /** 决策为 PlanNextVolume 时：挂「规划下一卷 → 下一个 ADVANCE」。规划阶段会调模型、可退避重试 */
    suspend fun appendVolumePlan(context: Context, projectId: String, session: String) {
        val plan = simpleRequest(projectId, GenerationStage.PLAN_NEXT_VOLUME, session, 0, 0)
        val advance = simpleRequest(projectId, GenerationStage.ADVANCE, session, 0, 0)
        WorkManager.getInstance(context).beginUniqueWork(
            GenerationWorker.uniqueWorkName(projectId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            plan,
        ).then(advance).enqueue().result.await()
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
        // 入队成功立刻建保活前台服务。必须趁用户还在前台（点按钮的那一刻）做：
        // Android 12+ 后台启动不了前台服务，错过这个时机，链上后续 Worker 在后台
        // 就只能裸奔（10 分钟窗口 + 进程冻结风险），表现就是「调入后台没工作」。
        // 启动失败不阻断生成，按失败留痕后走 Worker 自己 setForeground 的原路。
        GenerationKeeperService.start(context, projectId)
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
