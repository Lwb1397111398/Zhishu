package com.example.zhishu.domain.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 核验问题的类型（4B）。
 *
 * 枚举而不是自由字符串：UI 要按类型分色、给不同的处理建议，
 * 而模型给的 `type` 写法不固定（`citation_error` / `引用错误` / `CITATION`）。
 *
 * 取值直接对齐 `verify.txt` 与 `verify_model.txt` 里定义的那套词表——
 * 提示词让模型输出什么，这里就认什么。凭印象另立一套的后果很具体：
 * 提示词让模型报 `contradiction`，枚举里却只有 `FACT_ERROR`，
 * 于是**每一条**核验问题都落进「其他问题」，分类当场失去意义。
 *
 * 对不上的一律落到 [OTHER]，原文留在 [VerificationIssue.rawType] 里——
 * 不认识的类型不该被丢掉，那条问题本身是真的。
 */
@Serializable
enum class IssueType {
    /** 资料支撑不了这个断言 */
    UNSUPPORTED,

    /** 与资料直接矛盾 */
    CONTRADICTION,

    /** 引用错误、缺失，或引的片段与内容无关 */
    CITATION_ERROR,

    /** 编造了资料里没有的条文、判例、数字，或填了不存在的片段 ID */
    FABRICATION,

    /** 只在某个法域/某个生效时间之后成立的规则被讲成了普适规则（4A 引入） */
    SCOPE_ERROR,

    /** 有地区差异、版本差异或学界分歧的说法被写成了确定无疑的结论（无资料模式） */
    OVERREACH,

    /** 推演跳步：某个概念登场前，读者没有被推到「这里缺一块」的位置 */
    BROKEN_DERIVATION,

    /** 本章该想通的问题没真的被回答，只提了个名字 */
    MISSING,

    /** 概念被讲解但没有被用于判断/行动/排除（契约要求应用时才报，第二轮 P1-3） */
    NO_APPLICATION,

    /** 人物行动/判断了但没有可观察的结果或后续（行动悬空，第二轮 P1-3） */
    MISSING_RESULT,

    /**
     * 文风问题：不像小说、读着费劲（2026-09 文风修复）。
     *
     * 典型形态：通篇无名「他」/「你」的推理独白、全章零对话、
     * 模板句复读、说理连篇。它不伤知识准确性，但正是用户抱怨
     * 「云里雾里」的直接来源——没有这一类，文风劣化没有任何反馈回路，
     * 自动修复永远不会朝「更好读」的方向修。
     */
    STYLE,

    /** 认不出来的类型。原样保留 rawType，不丢 */
    OTHER,
    ;

    val label: String
        get() = when (this) {
            UNSUPPORTED -> "资料不支持"
            CONTRADICTION -> "与资料矛盾"
            CITATION_ERROR -> "引用有问题"
            FABRICATION -> "疑似编造"
            SCOPE_ERROR -> "适用范围不清"
            OVERREACH -> "说得过于绝对"
            BROKEN_DERIVATION -> "推演跳步"
            MISSING -> "没讲透"
            NO_APPLICATION -> "没应用起来"
            MISSING_RESULT -> "行动没有结果"
            STYLE -> "文风问题"
            OTHER -> "其他问题"
        }

    companion object {
        /**
         * 把模型给的字符串映射成枚举。认不出来 → [OTHER]。
         *
         * 宽松匹配（去下划线、忽略大小写、认中文关键词）是因为这个字段
         * 每一次生成都由模型自由填写，严格匹配的结果是绝大多数问题都变成「其他」，
         * 分类也就失去意义了。
         */
        fun parse(raw: String): IssueType {
            val normalized = raw.trim().lowercase().replace("_", "").replace("-", "")
            return when {
                normalized.isEmpty() -> OTHER
                normalized.contains("citation") || raw.contains("引用") -> CITATION_ERROR
                normalized.contains("fabricat") || raw.contains("编造") || raw.contains("虚构") ->
                    FABRICATION
                normalized.contains("unsupported") || raw.contains("无依据") || raw.contains("没有依据") ->
                    UNSUPPORTED
                normalized.contains("contradict") || raw.contains("矛盾") || raw.contains("事实") ->
                    CONTRADICTION
                normalized.contains("scope") || raw.contains("法域") || raw.contains("适用") ->
                    SCOPE_ERROR
                normalized.contains("overreach") || raw.contains("过于") || raw.contains("绝对") ->
                    OVERREACH
                normalized.contains("derivation") || normalized.contains("logic") ||
                    raw.contains("跳步") || raw.contains("推演") || raw.contains("逻辑") ->
                    BROKEN_DERIVATION
                // 「missing_result」必须先于「missing」匹配，否则会被吞成 MISSING
                normalized.contains("missingresult") || raw.contains("没有结果") || raw.contains("悬空") ->
                    MISSING_RESULT
                normalized.contains("noapplication") || raw.contains("没有应用") ||
                    raw.contains("没用起来") || raw.contains("没应用") ->
                    NO_APPLICATION
                // 文风类关键词与上面各组无重叠；「像散文」「说教」是模型描述文风问题的常用语
                normalized.contains("style") || raw.contains("文风") || raw.contains("生硬") ||
                    raw.contains("散文") || raw.contains("独白") || raw.contains("说教") ||
                    raw.contains("复读") || raw.contains("不像小说") ->
                    STYLE
                normalized.contains("missing") || raw.contains("没讲") || raw.contains("未回答") ->
                    MISSING
                else -> OTHER
            }
        }
    }
}

/**
 * 用户对一条核验问题做了什么（4B）。
 *
 * 「已修」不是一个用户能手点的状态：它由「重写这一块 → 重新核验 → 这条问题没再出现」
 * 推出来。用户能做的只有 [ACCEPTED]——「我看过了，这条我认，不改」。
 * 让用户手动标「已修」等于让他给自己发一张没有依据的合格证。
 */
@Serializable
enum class IssueResolution {
    /** 还没处理 */
    OPEN,

    /** 这一块重写并重新核验后，这条问题没有再出现 */
    FIXED,

    /** 用户看过了，判断不需要改（例如模型误报） */
    ACCEPTED,
    ;

    val isOpen: Boolean get() = this == OPEN
}

/**
 * 一条核验问题（第四阶段 4B）。
 *
 * 4B 之前，核验结果是这样处理的：
 *
 * ```kotlin
 * val note = verified.issues.take(5).joinToString("\n") { "[${it.type}] ${it.message}" }
 * ```
 *
 * 一整章的问题被拼成一个字符串塞进 `verificationNote`，第 6 条起直接丢掉，
 * `blockId`、`sourceRefs`、`suggestedFix` 全部蒸发。用户看到的是一段没法点、
 * 没法定位、没法处理的文字，唯一的出路是「整章重新生成」——把写对的部分
 * 连同写错的部分一起推翻重来，还要重新扣一次故事模型的钱。
 *
 * 落成表之后，每条问题都钉在具体的 [blockId] 上，于是「只重写这一块」才有了对象。
 *
 * ## 为什么绑 versionId 而不是只绑 chapterId
 *
 * 问题是**针对某一版正文**提出的。局部重写会派生一个新版本、给重写过的块换新 ID，
 * 旧版本的问题必须原样留在旧版本上：它们描述的是那一版的文字，
 * 拿到新版本上既对不上块，也可能早就不成立了。
 */
@Serializable
@Entity(
    tableName = "verification_issues",
    indices = [
        Index(value = ["versionId"]),
        Index(value = ["blockId"]),
        Index(value = ["chapterId"]),
        Index(value = ["projectId"]),
    ],
)
data class VerificationIssue(
    /**
     * `versionId#序号`。
     *
     * 和 [Citation] 一样用确定性 ID：重新核验同一版时先清后写，
     * 备份导入要能算出同一个 ID，否则同一条问题会留下两行。
     */
    @PrimaryKey val id: String,
    val projectId: String,
    val chapterId: String,
    /** 这条问题是针对哪一版正文提的 */
    val versionId: String,
    /**
     * 出问题的正文块。null 表示模型没能定位到具体块（例如「全章语气不一致」）。
     *
     * 定位不到就是定位不到——不猜一个最像的块。猜错的后果是用户点开一段
     * 没有问题的正文，看不出问题在哪，然后开始怀疑核验本身。
     */
    val blockId: String? = null,
    val type: IssueType = IssueType.OTHER,
    /** 模型原样给出的类型字符串。认不出来时它是唯一线索 */
    @ColumnInfo(defaultValue = "")
    val rawType: String = "",
    val message: String = "",
    /** 相关资料片段 ID，用户点开可直接看原文 */
    @ColumnInfo(defaultValue = "")
    val sourceRefs: List<String> = emptyList(),
    val suggestedFix: String = "",
    val resolution: IssueResolution = IssueResolution.OPEN,
    @ColumnInfo(defaultValue = "0")
    val order: Int = 0,
    val createdAt: Long = 0L,
) {
    /** 能不能「只重写这一块」——定位不到块的问题只能整章重写 */
    val isBlockScoped: Boolean get() = !blockId.isNullOrBlank()

    companion object {
        fun idFor(versionId: String, order: Int): String = "$versionId#$order"
    }
}
