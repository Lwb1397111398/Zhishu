package com.example.zhishu.worker

/**
 * ADVANCE 查库后的下一步。纯函数 [decideAdvance] 产出，Worker（Task 7 的 `advance()`）据此执行。
 *
 * 单独放一个文件而不是塞进 GenerationStage：这是自动闭环的**决策**，
 * 与「有哪些阶段」是两件事——决策可以不看 WorkManager、不看数据库，
 * 也因此能用最便宜的纯 JVM 测试把每一条边界钉死（[com.example.zhishu.AdvanceDecisionTest]）。
 */
enum class AdvanceDecision {
    /** 总开关关：什么都不追加（用户随时可再点「继续」） */
    StoppedBySwitch,

    /** 当前卷还有在生成的章，且其中有能生成的 → 追加这些章节到链尾 */
    ContinueCurrentVolume,

    /**
     * 当前卷没完，但没有可生成的章。
     *
     * 这是**合法的刹车点**：多半整卷卡在「因果契约未生成」——契约失败的章
     * 被 [selectChaptersToGenerate] 排除，却仍算 [com.example.zhishu.domain.model.ChapterStatus.isVolumeBlocking]。
     * 没有这一支，ADVANCE 会陷入「 targets 恒为 0 → 什么都不追加 → 链静默结束」的死角，
     * 用户看到的就是「点了继续，没动静」。所以这里必须发一条能指向出路的通知。
     */
    PausedForUser,

    /** 当前卷已收尾，还有没讲到的知识点 → 追加「规划下一卷」 */
    PlanNextVolume,

    /** 主线讲完（无 pending）→ 发结果通知后停。唯一的自然终点。 */
    MainLineDone,
}

/**
 * 决定自动闭环的下一步（决策与执行分离，本函数无副作用）。
 *
 * @param autoOn 总开关 `generationAutoAdvance`。关掉时优先级最高——什么都不追加。
 * @param volumeBlockingCount 当前卷里 `status.isVolumeBlocking` 的章数。
 *        注意：待复核 / 失败 **不算** blocking——这正是「永远第一卷」的病根，
 *        判定必须用 isVolumeBlocking 而不是 !isReadable。
 * @param generatableCount 当前卷里 [selectChaptersToGenerate] 会挑出来的章数（已排除契约失败）。
 * @param pendingKnowledgeCount `coverageReport.pendingNodes.size`——还没排进任何章节的知识点数。
 *
 * 判定顺序即优先级：先看开关 → 再看本卷是否还在生成 → 卷内区分「有活可干 / 卡住」→
 * 本卷收尾后按 pending 决定续卷还是刹车。
 */
fun decideAdvance(
    autoOn: Boolean,
    volumeBlockingCount: Int,
    generatableCount: Int,
    pendingKnowledgeCount: Int,
): AdvanceDecision {
    if (!autoOn) return AdvanceDecision.StoppedBySwitch
    return when {
        volumeBlockingCount > 0 ->
            if (generatableCount > 0) AdvanceDecision.ContinueCurrentVolume
            else AdvanceDecision.PausedForUser
        pendingKnowledgeCount > 0 -> AdvanceDecision.PlanNextVolume
        else -> AdvanceDecision.MainLineDone
    }
}
