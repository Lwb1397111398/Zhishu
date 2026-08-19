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
    /** 本章结束时的处境，下一章唯一的前情提要（问题5） */
    val carryOver: String = "",
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
