package com.example.zhishu.ai.outputs

import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.QuizType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * conflicts 字段的容错反序列化：部分模型（实测含 deepseek-v4 系列）在骨架/审计
 * 阶段会把「对象数组」写成纯字符串数组 `["对立观点描述"]`，默认解析会整次调用
 * 失败。这里在解析前把字符串元素包成 `{topic=描述, description=描述}`，
 * 让输出变体不再炸掉整张知识地图；标准对象数组原样通过。
 */
internal object ConflictDtoListSerializer : JsonTransformingSerializer<List<ConflictDto>>(ListSerializer(ConflictDto.serializer())) {    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(
            element.map { item ->
                if (item is JsonPrimitive && item.isString) {
                    buildJsonObject {
                        put("topic", item.content)
                        put("description", item.content)
                    }
                } else {
                    item
                }
            },
        )
        else -> element
    }
}

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
    @Serializable(with = ConflictDtoListSerializer::class)
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
    /**
     * 结构化缺口（gaps 闭环）。
     *
     * 旧模型不输出本字段 → 空数组，[gaps] 照常落库；两者并存而不是替换，
     * 是因为把 [gaps] 直接改成对象数组会让旧模型输出反序列化失败、整批审计崩掉。
     * 结构化优先：有它时按类型分流（MISSING_NODE 可驱动补节点，其余只是提示）。
     */
    val structuredGaps: List<GapDto> = emptyList(),
    @Serializable(with = ConflictDtoListSerializer::class)
    val conflicts: List<ConflictDto> = emptyList(),
)

/**
 * 一条结构化的知识缺口（gaps 闭环）。
 *
 * [type] 是宽松字符串，解析层用 [com.example.zhishu.domain.model.GapType.parse]
 * 归一化——未知值落 UNSPECIFIED，绝不让模型的输出变体炸掉审计。
 */
@Serializable
data class GapDto(
    val type: String = "UNSPECIFIED",
    val description: String = "",
    /** 缺口挂靠的知识点编号（当前批次 nodeKey），可为空 */
    val anchorNodeKey: String = "",
    /** 建议补充的知识点标题（type = MISSING_NODE 时给出） */
    val suggestedTitle: String = "",
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
    /**
     * 默认空串而不是必填：截断修复把最后一个节点剪到 title 之前时，
     * 必填字段会让**整个** payload 抛 MissingFieldException，
     * 连前面已经完整的节点一起陪葬。空标题的节点由
     * [OutputValidators.validateChainNodes] 按「无内容」丢弃。
     */
    val title: String = "",
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

/**
 * 章节类型（第二轮 P1-2）。
 *
 * 决定本章的最低写作要求——不要求每一章机械走完八拍因果链，
 * 而是按类型只保证该类型必须满足的那几拍：
 * - INTRODUCE：新授。必须显式制造 knowledgeGap，且给出 resultingChange
 * - APPLY：应用。必须列出 appliedNodeTitles，且给出行动的结果
 * - CONTRAST：辨析。必须摆出易混点（priorIntuition/obstacle），并给出区分结果
 * - REVIEW：回顾迁移。只能引用已学节点，不能把新概念伪装成首次介绍
 * - ASSESS：通过案例验证掌握。必须给案例与判断结果
 */
@Serializable
enum class ChapterType {
    INTRODUCE,
    APPLY,
    CONTRAST,
    REVIEW,
    ASSESS,
}

/**
 * 章节因果契约（第二轮 P1-2）：路线规划时为每一章定下的因果结构。
 *
 * 路线模型输出契约，正文模型遵循它写作，核验模型拿着它逐项检查——
 * 这是把「故事因果」从 prompt 软约束变成可校验产物的第一步。
 * 所有字段可空/可缺省：旧模型输出（无契约字段）解析后得到空契约，
 * 兼容老流程；只有新 Prompt 版本且校验开启时才要求契约完整。
 */
@Serializable
data class ChapterContractDto(
    /** 本章开头人物要面对的具体问题或目标 */
    val entryQuestion: String = "",
    /** 读者/人物对这个问题原本就有的直觉、旧方法或初步方案 */
    val priorIntuition: String = "",
    /** 旧方法遇到的阻碍或矛盾 */
    val obstacle: String = "",
    /** 读者因此明确感到需要一个新的解释 */
    val knowledgeGap: String = "",
    /** 本章**新引入**的知识点标题（首次讲解） */
    val introducedNodeTitles: List<String> = emptyList(),
    /** 本章**应用**的知识点标题（用来判断/行动/排除，可含已学节点） */
    val appliedNodeTitles: List<String> = emptyList(),
    /** 本章必须走到的叙事节点（拍子），不要求每拍都有独立段落 */
    val requiredBeats: List<String> = emptyList(),
    /** 知识被用起来之后带来的可观察变化 */
    val resultingChange: String = "",
    /** 本章结尾留下、交给下一章的问题 */
    val bridgeToNext: String = "",
) {
    /** 契约是否完全缺失（旧模型输出或模型没填）。缺失 ≠ 伪造通过。 */
    val isEmpty: Boolean
        get() = entryQuestion.isBlank() &&
            priorIntuition.isBlank() &&
            obstacle.isBlank() &&
            knowledgeGap.isBlank() &&
            introducedNodeTitles.isEmpty() &&
            appliedNodeTitles.isEmpty() &&
            requiredBeats.isEmpty() &&
            resultingChange.isBlank() &&
            bridgeToNext.isBlank()
}

@Serializable
data class ChapterPlanDto(
    val order: Int,
    val title: String,
    /** 本章推演骨架：从哪出发 → 撞上什么问题 → 长出什么（问题7） */
    val derivationChain: String = "",
    val plotGoal: String = "",
    val learningGoals: List<String> = emptyList(),
    val nodeTitles: List<String> = emptyList(),
    /**
     * 本章涉及的知识点**编号**（三段式改造）。
     *
     * 编号是当前 active 批次内的 `nodeKey`（nk-1、nk-2…），只在模型交互层使用：
     * 标题会被模型改一个空格/标点就匹配失败——这正是旧路线「引用了不存在的
     * 知识点」整卷作废的最大来源；编号短到抄不错。落库前由调用方反解成节点 ID，
     * [nodeTitles] 保留给旧流程兼容，新路径只填 [nodeKeys]。
     */
    val nodeKeys: List<String> = emptyList(),
    val wordTarget: Int = 1200,
    val openingHook: String = "",
    val closingHook: String = "",
    /** 章节类型。空串 = 未指定（旧模型输出），校验开启时才要求给出 */
    val chapterType: String = "",
    /** 本章的因果契约（第二轮 P1-2）。旧模型输出时为默认空契约 */
    val contract: ChapterContractDto = ChapterContractDto(),
    /**
     * 本章依赖的前置知识点标题（第二轮 P1-4，路线模型输出）。
     *
     * 只用于提示与软校验：程序只对能精确匹配到节点标题/ID 的关系做硬校验，
     * 自由文本一律不建立程序关系，匹配不到的记 UNRESOLVED_PREREQUISITE。
     */
    val prerequisiteNodeTitles: List<String> = emptyList(),
)

/**
 * 三段式路线规划：大纲阶段的输出（分卷是纯程序计算，没有模型调用）。
 *
 * 与旧 [StoryBibleOutput] 的区别：一次只规划一卷、只出**大纲**不出契约、
 * 章节用 [ChapterPlanDto.nodeKeys] 编号引用知识点。卷标题/卷目标由大纲顺带给出。
 */
@Serializable
data class VolumeOutlineOutput(
    val volumeTitle: String = "",
    val volumeGoal: String = "",
    val chapters: List<ChapterPlanDto> = emptyList(),
    /**
     * 故事舞台（正文脚手架，2026-09 文风修复）。
     *
     * 三段式改造后大纲成了故事设定的唯一来源，但原来只出章节计划——
     * 正文调用里 {world}/{characters}/{style} 三个槽全是空的，模型没有人物可用，
     * 只能写成无名「他」或「你」的推理独白（samples/20260905 全部如此）。
     * 三个字段都带默认值：旧模型输出或解码失败时为空，走原有降级文案，不会炸。
     */
    val world: String = "",
    /** 每项一个角色的单行描述：「姓名：身份，性格动机，与这门学科的关系」 */
    val characters: List<String> = emptyList(),
    /** 叙事载体的选择理由（风格指令要求模型写，此前模板没给输出位，写了一直被丢掉） */
    val approach: String = "",
)

/**
 * 三段式路线规划：单章契约的输出。
 *
 * 知识点用**编号**（nodeKey）引用——[allowedNodeKeys] 白名单约束下，
 * 编号匹配杜绝了标题匹配「差一个字整章作废」的失败模式。
 * 落库前由调用方反解成标题再 encode 进 [ChapterContractDto]。
 */
@Serializable
data class ChapterContractOutput(
    val entryQuestion: String = "",
    val priorIntuition: String = "",
    val obstacle: String = "",
    val knowledgeGap: String = "",
    val introducedNodeKeys: List<String> = emptyList(),
    val appliedNodeKeys: List<String> = emptyList(),
    val requiredBeats: List<String> = emptyList(),
    val resultingChange: String = "",
    val bridgeToNext: String = "",
) {
    /** 全空 = 模型什么都没给。校验层按「缺契约」处理，不静默通过 */
    val isEmpty: Boolean
        get() = entryQuestion.isBlank() &&
            priorIntuition.isBlank() &&
            obstacle.isBlank() &&
            knowledgeGap.isBlank() &&
            introducedNodeKeys.isEmpty() &&
            appliedNodeKeys.isEmpty() &&
            requiredBeats.isEmpty() &&
            resultingChange.isBlank() &&
            bridgeToNext.isBlank()
}

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
    // 默认值是解码兜底的关键：jsonDecoder 的 coerceInputValues 只对声明了默认值的
    // 字段生效。模型偶尔会把章节类型（CONTRAST 等）串进块 type（长输出里
    // 「本章类型」一节就在上下文里，极易混淆），没有默认值时一个未知值就炸掉
    // 上万 token 的整章正文，还要再烧一次修复调用；有默认值则该块自动降级为
    // 普通正文块，阅读与核验不受影响。
    val type: BlockType = BlockType.STORY,
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
    /**
     * 结构化契约检查（批次 B 后续：contractChecks）。
     *
     * 对契约声明的 applied/introduced 节点逐项给出正文证据判定，使双轨比对
     * 「契约声明 vs 正文证据」从 issues 推断升级为真证据。旧模型不输出本字段
     * （解析默认空数组，完全不破坏兼容）；双轨判定在 checks 缺失时退回 issues 推断。
     */
    val checks: List<ContractCheck> = emptyList(),
)

/**
 * 一个知识点的契约检查结果（批次 B 后续）。
 *
 * [evidence] 为宽松字符串（模型可能输出大小写/措辞变体，解析层不做枚举强校验，
 * 避免旧模型或变体输出把整个核验结果炸掉）；判定时映射到 [Evidence]，
 * 未知值一律归 [Evidence.UNKNOWN]——**不得默认为 PRESENT**。
 *
 * 判定口径（防误报）：
 * - PRESENT：正文有明确应用/引入证据，且 [evidenceBlockIds] 可定位到段落；
 * - PARTIAL：正文有涉及但没真正用起来（只提了名字、没走完应用）；
 * - ABSENT：正文没有对应内容；
 * - UNKNOWN：核验无法判断（正文未生成/上下文不足）。
 */
@Serializable
data class ContractCheck(
    /** 被检查的知识点标题（来自契约 appliedNodeTitles / introducedNodeTitles） */
    val nodeTitle: String,
    /** 契约声称的用法：APPLIED / INTRODUCED / RESULT */
    val claim: String = "APPLIED",
    /** 宽松字符串，判定层映射到 [Evidence]；未知值归 UNKNOWN */
    val evidence: String = "UNKNOWN",
    /** 证据所在段落（blockId 原样复制，可定位到正文） */
    val evidenceBlockIds: List<String> = emptyList(),
    /** 一句说明；无证据（PARTIAL/ABSENT）时解释为什么判定 */
    val comment: String = "",
)

@Serializable
enum class Evidence {
    PRESENT,
    PARTIAL,
    ABSENT,
    UNKNOWN;

    companion object {
        /** 宽松映射：未知/空值一律 UNKNOWN（防误报，绝不当 PRESENT） */
        fun parse(raw: String?): Evidence = when (raw?.trim()?.uppercase()) {
            "PRESENT" -> PRESENT
            "PARTIAL" -> PARTIAL
            "ABSENT" -> ABSENT
            else -> UNKNOWN
        }
    }
}

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
    // 同 BlockDto.type：默认值让 coerceInputValues 能把未知题型兜底成 RECALL，
    // 而不是让一章的自测题整体报废。
    val type: QuizType = QuizType.RECALL,
    val options: List<String> = emptyList(),
    val answer: String,
    val explanation: String,
    val nodeTitles: List<String> = emptyList(),
)
