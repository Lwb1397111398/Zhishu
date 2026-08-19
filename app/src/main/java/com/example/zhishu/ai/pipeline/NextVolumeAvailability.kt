package com.example.zhishu.ai.pipeline

/**
 * 「继续下一卷」这个入口该不该出现（第三阶段 3C）。
 *
 * 分成四种而不是一个布尔：入口显不显示是一件事，为什么不显示是另一件事。
 * 当前卷没读完就把按钮点亮，故事顺序会被打乱；
 * 主线已经讲完还叫「继续主线」，模型会被无止境地要求再来一卷。
 */
sealed interface NextVolumeAvailability {

    /** 还没有讲述路线，先规划第一卷 */
    data object NoPlanYet : NextVolumeAvailability

    /** 当前卷还有 [remaining] 章没写完。先读完再往下讲 */
    data class CurrentVolumeUnfinished(val remaining: Int) : NextVolumeAvailability

    /** 可以继续主线，还有 [pendingCount] 个知识点没讲到 */
    data class CanContinue(val pendingCount: Int) : NextVolumeAvailability

    /**
     * 主线讲完了：知识地图上的节点都安排过了。
     * 仍然可以再要一卷，但那是「扩展阅读」而不是「继续主线」，必须和前者区分开。
     */
    data object MainLineDone : NextVolumeAvailability
}
