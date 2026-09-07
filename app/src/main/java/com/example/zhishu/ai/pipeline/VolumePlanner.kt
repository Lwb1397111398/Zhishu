package com.example.zhishu.ai.pipeline

import com.example.zhishu.ai.outputs.ChainDto
import com.example.zhishu.ai.outputs.ChapterPlanDto
import com.example.zhishu.ai.outputs.ChapterType
import com.example.zhishu.domain.model.KnowledgeNode
import kotlin.math.ceil
import kotlin.math.min

/** 一章承载一整条演进链，所以章数远少于知识点数（问题7） */
internal const val NODES_PER_CHAPTER = 4

/**
 * 一卷 4~8 章（3C）。
 *
 * 这不是全书上限：读完一卷可以继续规划下一卷，总章数没有硬顶。
 * 之所以不把上限直接改成 100，是因为「一次规划多少」和「总共能讲多少」
 * 是两件事——让模型一次想出 100 章，越往后越糊，而用户根本还没读到那儿。
 */
internal const val VOLUME_MIN_CHAPTERS = 4
internal const val VOLUME_MAX_CHAPTERS = 8

/**
 * 讲述路线三段式的程序侧规划（纯函数，不碰数据库不碰模型）。
 *
 * ## 分卷为什么是纯程序（而不是再调用一次模型）
 *
 * 知识主干的演进链已经是「知识如何推导」的天然分组，卷是「故事讲到哪」的分组，
 * 两者一一对应。让模型再输出一次「链 → 卷映射」只会和程序分组打架，还要写对账。
 * 所以按链贪心累加，超过一卷的章数预算就断卷，一条链过长时按节点数切片。
 *
 * ## 章节类型为什么程序兜底
 *
 * 一次成型时模型能看到全卷，类型分布大致合理；拆成大纲调用后模型依然一次
 * 输出全卷所有章的类型（全局视野没丢），但偶尔会整卷全 INTRODUCE 或者
 * 第 2 章就来 REVIEW。`enforceTypes` 用确定性规则修复，与
 * `ContractIntroducedFix` 同一哲学：能程序修的绝不烧一次模型调用。
 */
internal object VolumePlanner {

    /**
     * 一卷的程序预分组结果。
     *
     * [chains] 是本卷包含的演进链（保持知识主干里的顺序），[nodes] 是这些链上
     * 全部节点的展开清单。卷标题/卷目标由大纲调用顺带产出，不在程序侧伪造。
     */
    data class DraftVolume(
        val chains: List<ChainDto>,
        val nodes: List<KnowledgeNode>,
        val suggestedChapters: Int,
    )

    /**
     * 把演进链贪心分组，返回**第一卷**的节点清单。
     *
     * 预算逻辑：目标章数 = [chaptersFor]（4~8 区间，且被节点数封顶），每条链
     * 折算 `ceil(链节点数 / NODES_PER_CHAPTER)` 章。按链序累加，
     * 放不进本章节预算的链留给下一卷（续卷时再从剩余链分组）。
     * 一条链自身超过预算时整条给入——切链会把一条推演拦腰斩断，
     * 由大纲调用用 openingHook/closingHook 衔接，而不是程序硬切。
     *
     * [chains] 为空（老批次无链结构）时退化为「按章数预算直接切节点」。
     */
    fun firstVolume(
        chains: List<ChainDto>,
        nodesByTitle: Map<String, KnowledgeNode>,
    ): DraftVolume {
        // 无链结构时 orderedNodes 不能从空链派生（那样恒为空，本卷拿到 0 个节点，
        // 讲述路线直接报「本卷没有可用知识点」——地图里明明有节点，错误信息还误导）。
        // 退化为「用全部节点」：associateBy 保留原列表顺序，取前若干即可。
        val orderedNodes = if (chains.isEmpty()) {
            nodesByTitle.values.toList()
        } else {
            chains
                .flatMap { it.nodeTitles }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .mapNotNull { nodesByTitle[it] }
        }
        if (chains.isEmpty()) {
            // 无链结构：直接按节点数预算切
            val budget = chaptersFor(orderedNodes.size)
            val takeNodes = orderedNodes.take((budget * NODES_PER_CHAPTER).coerceAtLeast(NODES_PER_CHAPTER))
            return DraftVolume(
                chains = emptyList(),
                nodes = takeNodes,
                suggestedChapters = chaptersFor(takeNodes.size),
            )
        }
        val budgetChapters = chaptersFor(orderedNodes.size)
        val picked = mutableListOf<ChainDto>()
        var used = 0
        for (chain in chains) {
            val cost = ceil(chain.nodeTitles.size / NODES_PER_CHAPTER.toDouble())
                .toInt()
                .coerceAtLeast(1)
            if (used > 0 && used + cost > budgetChapters) break
            picked += chain
            used += cost
        }
        // 一条链都放不下（单链超预算）：整条给入，宁多勿断
        if (picked.isEmpty()) picked += chains.first()
        val pickedTitles = picked
            .flatMap { it.nodeTitles }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val nodes = pickedTitles.mapNotNull { nodesByTitle[it] }
        return DraftVolume(
            chains = picked,
            nodes = nodes,
            suggestedChapters = chaptersFor(nodes.size),
        )
    }

    /**
     * 本卷该规划几章。
     *
     * 除了 4~8 的软区间，还要被**节点数**封顶：`ceil(节点数 / NODES_PER_CHAPTER)`
     * 是这一卷能撑起的章数上限，超过它就会出现「两个知识点硬拆成四章」——
     * 每章只有半个知识点可讲，模型只能注水。
     *
     * 下限也要跟着节点数走：只有 1~2 个知识点时硬要 4 章，
     * 同样是把一章的内容摊成四章。宁可这一卷短一点，
     * 剩下的知识点留给续卷。
     */
    fun chaptersFor(nodeCount: Int): Int {
        if (nodeCount <= 0) return 0
        // 每章至少一个节点（否则出现无内容的空章），每章至多 NODES_PER_CHAPTER 个
        // （否则一章塞不下，模型只能注水）。章数必须落在这个区间里：
        val upper = nodeCount.coerceAtMost(VOLUME_MAX_CHAPTERS)
        val lower = ceil(nodeCount / NODES_PER_CHAPTER.toDouble()).toInt().coerceAtLeast(1)
        // 区间内优先取软目标 4 章——4~8 是一卷读起来最舒服的长度，
        // 但节点不够时不能硬凑（只有 1 个知识点却拆 4 章，是把一章摊成四章）
        val target = VOLUME_MIN_CHAPTERS.coerceAtMost(nodeCount)
        // 节点数超过一卷承载量（NODES_PER_CHAPTER × VOLUME_MAX_CHAPTERS = 32 个）时，
        // lower 会大于 upper——比如 290 个节点 lower=73、upper=8，coerceIn 直接抛
        // IllegalArgumentException（「maximum 8 is less than minimum 73」），讲述路线
        // 整体失败。这种规模本来就该分多卷：本卷按上限 8 章规划，剩余节点由续卷接手
        // （firstVolume 的贪心装链正是按这个假设设计的），所以把 lower 压回 upper 即可。
        return target.coerceIn(lower.coerceAtMost(upper), upper)
    }

    /**
     * 章节类型的确定性兜底。
     *
     * 规则（按优先级）：
     * - R1 位置：第 1 章必须 INTRODUCE；REVIEW / ASSESS 不早于第 3 章；ASSESS 全卷 ≤1；
     * - R2 多样性：`distinct(types) >= min(3, N)`；
     * - R3 配额：`INTRODUCE <= ceil(N/2)`；`N>=3` 时 APPLY ≥1；`N>=5` 时 CONTRAST ≥1。
     *
     * 违规章从后往前按 [REPAIR_ROTATION] 轮转替换（从后往前是因为结尾章换成
     * REVIEW/ASSESS 最自然），每处替换记一条 [RepairNote] 给软审计提示。
     * 返回修复后的章节列表；调用方在修复后仍不满足 R2 时自行决定失败。
     */
    fun enforceTypes(chapters: List<ChapterPlanDto>): Pair<List<ChapterPlanDto>, List<String>> {
        if (chapters.isEmpty()) return chapters to emptyList()
        val notes = mutableListOf<String>()
        val repaired = chapters.toMutableList()

        fun typeAt(i: Int): ChapterType? =
            ChapterType.entries.firstOrNull { it.name == repaired[i].chapterType.trim().uppercase() }

        // R1：第 1 章必须 INTRODUCE
        if (repaired.isNotEmpty() && typeAt(0) != ChapterType.INTRODUCE) {
            notes += "第 1 章类型由 ${repaired[0].chapterType.ifBlank { "未指定" }} 调整为 INTRODUCE（开卷必须引入新概念）"
            repaired[0] = repaired[0].copy(chapterType = ChapterType.INTRODUCE.name)
        }
        // R1：REVIEW / ASSESS 不早于第 3 章
        repaired.forEachIndexed { i, chapter ->
            if (i >= 2) return@forEachIndexed
            val t = typeAt(i)
            if (t == ChapterType.REVIEW || t == ChapterType.ASSESS) {
                notes += "第 ${chapter.order} 章类型由 ${t.name} 调整为 APPLY（回顾/检验不能出现在开卷阶段）"
                repaired[i] = chapter.copy(chapterType = ChapterType.APPLY.name)
            }
        }
        // R1：ASSESS 全卷 ≤1（从后往前，超出配额的改成 REVIEW）
        val assessIndexes = repaired.indices.filter { typeAt(it) == ChapterType.ASSESS }
        if (assessIndexes.size > 1) {
            assessIndexes.drop(1).forEach { i ->
                notes += "第 ${repaired[i].order} 章类型由 ASSESS 调整为 REVIEW（一卷最多一次检验）"
                repaired[i] = repaired[i].copy(chapterType = ChapterType.REVIEW.name)
            }
        }
        // R3：INTRODUCE 配额 ≤ ceil(N/2)（从后往前超出的轮转替换）
        val n = repaired.size
        val introduceCap = ceil(n / 2.0).toInt()
        val introduceIndexes = repaired.indices.filter { typeAt(it) == ChapterType.INTRODUCE }
        var rotation = 0
        introduceIndexes.drop(introduceCap).forEach { i ->
            val replacement = REPAIR_ROTATION[rotation % REPAIR_ROTATION.size]
            rotation += 1
            notes += "第 ${repaired[i].order} 章类型由 INTRODUCE 调整为 $replacement（新授章占比过半，读者消化不了）"
            repaired[i] = repaired[i].copy(chapterType = replacement.name)
        }
        // R3：APPLY / CONTRAST 最低配额
        if (n >= 3 && repaired.indices.none { typeAt(it) == ChapterType.APPLY }) {
            // 找最后一个非第 1 章且可改的（避开 INTRODUCE 首章）
            val target = repaired.indices.lastOrNull { it > 0 && typeAt(it) != ChapterType.INTRODUCE }
            if (target != null && typeAt(target) != null) {
                notes += "第 ${repaired[target].order} 章类型由 ${typeAt(target)!!.name} 调整为 APPLY（应用已有概念是学习闭环的一半）"
                repaired[target] = repaired[target].copy(chapterType = ChapterType.APPLY.name)
            }
        }
        if (n >= 5 && repaired.indices.none { typeAt(it) == ChapterType.CONTRAST }) {
            val target = repaired.indices.lastOrNull { it > 0 && typeAt(it) != ChapterType.INTRODUCE }
            if (target != null && typeAt(target) != null && typeAt(target) != ChapterType.ASSESS) {
                notes += "第 ${repaired[target].order} 章类型由 ${typeAt(target)!!.name} 调整为 CONTRAST（辨析易混概念是理解深度的标志）"
                repaired[target] = repaired[target].copy(chapterType = ChapterType.CONTRAST.name)
            }
        }
        // R2：多样性。从后往前轮转替换到达标或轮转用尽
        if (repaired.indices.mapNotNull { typeAt(it) }.distinct().size < min(3, n)) {
            var rotationIdx = 0
            for (i in repaired.indices.reversed()) {
                if (repaired.indices.mapNotNull { typeAt(it) }.distinct().size >= min(3, n)) break
                if (i == 0) continue
                val replacement = REPAIR_ROTATION[rotationIdx % REPAIR_ROTATION.size]
                rotationIdx += 1
                if (typeAt(i) == replacement) continue
                notes += "第 ${repaired[i].order} 章类型由 ${repaired[i].chapterType.ifBlank { "未指定" }} 调整为 $replacement（全卷类型过于单一）"
                repaired[i] = repaired[i].copy(chapterType = replacement.name)
            }
        }
        // 未指定类型的章节：全部按 INTRODUCE 补齐（新授是默认语义），并记提示
        repaired.forEachIndexed { i, chapter ->
            if (typeAt(i) == null) {
                notes += "第 ${chapter.order} 章未声明类型，按 INTRODUCE 处理"
                repaired[i] = chapter.copy(chapterType = ChapterType.INTRODUCE.name)
            }
        }
        return repaired to notes
    }

    /** 违规章的替换轮转：结尾章补这些类型最自然 */
    private val REPAIR_ROTATION = listOf(
        ChapterType.APPLY,
        ChapterType.CONTRAST,
        ChapterType.REVIEW,
        ChapterType.INTRODUCE,
    )

    /**
     * 从章节计划推导「每个知识点首次出现在第几章」（按编号）。
     *
     * 纯程序推导，不采信模型在契约里的声明——`ContractIntroducedFix` 的存在
     * 恰恰证明模型声明不可信。可从已落库的章节计划重算，不依赖任何增量状态。
     *
     * 返回 nodeKey → 首次出现的章内序号（0 起始，按 [chapters] 顺序）。
     */
    fun computeIntroducedBy(chapters: List<ChapterPlanDto>): Map<String, Int> {
        val introduced = LinkedHashMap<String, Int>()
        chapters.forEachIndexed { index, chapter ->
            chapter.nodeKeys.forEach { key ->
                val k = key.trim()
                if (k.isNotEmpty() && k !in introduced) introduced[k] = index
            }
        }
        return introduced
    }
}
