package com.example.zhishu.ai.outputs

import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.QuizType
import kotlinx.serialization.Serializable

@Serializable
data class KnowledgeMapOutput(
    val nodes: List<NodeDto> = emptyList(),
    /** 演进链，直接用作章节骨架（问题7） */
    val chains: List<ChainDto> = emptyList(),
    val gaps: List<String> = emptyList(),
    val conflicts: List<ConflictDto> = emptyList(),
)

/**
 * 知识地图分批生成（批次化）：骨架阶段的输出。
 *
 * 只让模型列出演进链结构与节点标题清单，不写节点内容——单次输出降到
 * 2-4 千 token，30 秒内可完成。节点内容由逐链展开阶段分批补齐。
 */
@Serializable
data class KnowledgeMapSkeletonOutput(
    /** 3-8 条演进链。所有链的 nodeTitles 并集 = 全部知识点，不重不漏 */
    val chains: List<ChainDto> = emptyList(),
    /** 骨架阶段的缺口预览（最终审计会整体替换） */
    val gaps: List<String> = emptyList(),
    /** 骨架阶段的冲突预览（最终审计会整体替换） */
    val conflicts: List<ConflictDto> = emptyList(),
)

/** 知识地图分批生成（批次化）：单条演进链展开的输出 */
@Serializable
data class ChainNodesOutput(
    /** 本链涉及的节点完整内容。nodeKey 必须与骨架阶段程序分配的标识一致 */
    val nodes: List<NodeDto> = emptyList(),
)

/** 知识地图分批生成（批次化）：全局审计的输出（骨架 → 逐链 → 审计） */
@Serializable
data class KnowledgeMapAuditOutput(
    val gaps: List<String> = emptyList(),
    val conflicts: List<ConflictDto> = emptyList(),
)

/** 一条「前一个制度长出后一个」的演进链 */
@Serializable
data class ChainDto(
    val theme: String = "",
    val opening: String = "",
    val nodeTitles: List<String> = emptyList(),
)

@Serializable
data class NodeDto(
    val title: String,
    val definition: String = "",
    /** 它从哪个前序概念、因为什么缺口才出现（问题2） */
    val arisesFrom: String = "",
    /** 它自身没解决、因而引出下一个概念的口子（问题2） */
    val leadsTo: String = "",
    val prerequisites: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    val confusions: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val sourceRefs: List<String> = emptyList(),
    val parentTitle: String? = null,
    val depth: Int = 1,
    /**
     * 稳定标识（批次化生成）。骨架阶段由程序分配并下发给模型，
     * 单链展开时模型必须原样回传；按它匹配节点，不按标题。
     */
    val nodeKey: String = "",
)

@Serializable
data class ConflictDto(
    val topic: String,
    val sources: List<String> = emptyList(),
    val description: String = "",
)

@Serializable
data class StoryBibleOutput(
    val world: String,
    val characters: List<CharacterDto> = emptyList(),
    val conflict: String,
    val style: String,
    /** 为什么这门学科适合这样讲（自由发挥模式下模型的自选理由，问题6） */
    val approach: String = "",
    /** 全书演进主线，每条一句「阶段 → 解决了什么」（问题2） */
    val throughLine: List<String> = emptyList(),
    val chapters: List<ChapterPlanDto> = emptyList(),
)

@Serializable
data class CharacterDto(
    val name: String,
    val role: String,
    val description: String,
)

@Serializable
data class ChapterPlanDto(
    val order: Int,
    val title: String,
    /** 本章推演骨架：从哪出发 → 撞上什么问题 → 长出什么（问题7） */
    val derivationChain: String = "",
    val plotGoal: String = "",
    val learningGoals: List<String> = emptyList(),
    val nodeTitles: List<String> = emptyList(),
    val wordTarget: Int = 1200,
    val openingHook: String = "",
    val closingHook: String = "",
)

/**
 * 续卷规划的产出（第三阶段 3C）。
 *
 * 与 [StoryBibleOutput] 分开是因为续卷不重写世界观、人物、全书主线——
 * 那些已经定下来了，重新给一遍只会让已发布的章节和设定对不上。
 */
@Serializable
data class NextVolumeOutput(
    val volumeTitle: String = "",
    val volumeGoal: String = "",
    val chapters: List<ChapterPlanDto> = emptyList(),
)

@Serializable
data class ChapterOutput(
    val title: String,
    val summary: String = "",
    val blocks: List<BlockDto> = emptyList(),
    /**
     * 本章结束时的处境，下一章唯一的前情提要（问题5）。
     *
     * 3D 起字段名从 `carryOver` 改成 `endingState`：那个旧名字在数据库里
     * 曾经同时表示「开头钩子」和「结尾状态」两件事，提示词里继续沿用它，
     * 迟早又有人把两个含义接回去。这里存的只有一件事——结尾状态。
     */
    val endingState: String = "",
    val coveredTitles: List<String> = emptyList(),
    val openThreads: List<String> = emptyList(),
    /** 改写后的累积状态，替换而非追加，长度受预算约束（问题5） */
    val runningStateUpdate: String = "",
    /** 无资料模式下模型自报的待核实断言（问题3） */
    val unverifiedClaims: List<String> = emptyList(),
)

@Serializable
data class BlockDto(
    val type: BlockType,
    val text: String,
    val knowledgeNodeTitles: List<String> = emptyList(),
    val sourceRefs: List<String> = emptyList(),
)

/**
 * 局部重写一个正文块的产出（第四阶段 4B）。
 *
 * 与 [BlockDto] 分开是因为多了 [changeNote]，而且**没有** type 字段：
 * 段落类型由原来那一块决定，不该让模型在修一个事实错误的时候
 * 顺手把 FACT 改成 STORY——那会让「本章有几处需要出处」这类统计
 * 因为一次修订而变化。
 */
@Serializable
data class BlockRewriteOutput(
    val text: String,
    val knowledgeNodeTitles: List<String> = emptyList(),
    val sourceRefs: List<String> = emptyList(),
    /** 模型自述改了什么。写进版本说明，用户能看到「这一版动了什么」 */
    val changeNote: String = "",
)

@Serializable
data class VerificationOutput(
    val issues: List<VerificationIssueDto> = emptyList(),
    /** 无资料模式下需要学习者自行核对权威出处的断言（问题3） */
    val unverifiedClaims: List<String> = emptyList(),
)

@Serializable
data class VerificationIssueDto(
    val type: String,
    val blockId: String? = null,
    val message: String,
    val sourceRefs: List<String> = emptyList(),
    val suggestedFix: String = "",
)

@Serializable
data class QuizSetOutput(
    val questions: List<QuizDto> = emptyList(),
)

@Serializable
data class QuizDto(
    val question: String,
    val type: QuizType,
    val options: List<String> = emptyList(),
    val answer: String,
    val explanation: String,
    val nodeTitles: List<String> = emptyList(),
)
