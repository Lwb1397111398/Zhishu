package com.example.zhishu.ai.outputs

import com.example.zhishu.ai.ModelError
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.QuizType

/**
 * 规范化模型回传的知识点编号，让「带不带 #」这类抄写差异不再是整章作废的理由。
 *
 * 交互层给模型看的编号有两种写法：大纲/契约 prompt 里是 `#nk-3`（[nodeKeyLabel] 刻意
 * 加 `#`，是让模型把它当成编号原样抄回的提示），单链展开 prompt 的清单里是裸 `nk-3`。
 * 而模型往回抄时什么都有可能：`nk-3`、`#nk-3`、全角 `＃nk-3`、`NK-3`、`# nk-3`、
 * 带首尾空格——原来这些一律按精确字符串比对（白名单 `it in validKeys`、反解
 * `keyToId[it.trim()]`），差一个 `#` 就判「引用了不存在的知识点编号」，
 * 于是大纲整卷重来、契约标 FAILED、正文被 `generateChapter` 入口直接拦下。
 * 这正是「生成正文时跑不通」的一条真实路径，且与模型选择强相关（有的服务商更爱
 * 加 #、有的更爱丢 #），换个模型就时对时错，最难排查。
 *
 * 做法：两端都过这个函数——白名单建表、回传查表、引入/应用归属比对，全部归一到
 * 裸小写 `nk-N`。`#` 只是提示层的糖，匹配层不需要它。非 `nk-` 前缀的输入原样保留
 * （资料片段 ID 等也走这里，不该被改写）。
 */
internal fun canonicalNodeKey(raw: String): String {
    var k = raw.trim().trimStart('#', '＃').trim()
    if (k.startsWith("NK-", ignoreCase = true)) k = "nk-" + k.substring(3)
    return k
}

/**
 * 模型输出的业务校验（N14）。
 * JSON 能反序列化不代表内容可用：标题为空、引用不存在的节点、题目缺答案都必须直接失败，
 * 不做「盲目重试」，避免把无依据内容写进数据库。
 *
 * [requireSourceRefs] 区分两种知识来源模式（问题3）：
 * 有资料时事实段落必须可追溯到资料片段；无资料（模型自备知识）时没有片段可引，
 * 改由核验环节输出「待核实清单」承担同样的责任，因此这里不能再强制引用。
 */
object OutputValidators {

    /**
     * 骨架校验（批次化生成）。
     *
     * 只做「能开跑」的硬校验：至少一条链、每条链至少两个节点标题、节点标题
     * 跨链去重后非空。节点数量低于学习深度预期只算软问题——由调用方写进 gaps
     * 提示用户补充，而不是整批失败（骨架重试便宜，但给模型「列少点也行」的
     * 退路总比反复失败强）。
     */
    fun validateSkeleton(output: KnowledgeMapSkeletonOutput): Result<KnowledgeMapSkeletonOutput> {
        val chains = output.chains
            .map { chain ->
                chain.copy(nodeTitles = chain.nodeTitles.map { it.trim() }.filter { it.isNotBlank() })
            }
            .filter { it.nodeTitles.size >= 2 }
        if (chains.isEmpty()) {
            return Result.failure(ModelError.Invalid("知识地图骨架没有任何有效演进链"))
        }
        val allTitles = chains.flatMap { it.nodeTitles }.map { it.trim() }.distinct()
        if (allTitles.isEmpty()) {
            return Result.failure(ModelError.Invalid("知识地图骨架没有列出任何知识点"))
        }
        return Result.success(output.copy(chains = chains))
    }

    /**
     * 单链展开校验（批次化生成）。
     *
     * [expectedKeys] 是骨架阶段程序分配的 nodeKey 集合——模型原样回传标识
     * 即可匹配；[knownTitles] 是骨架阶段的标题集合，作为第二通道兜底：
     * 标识抄错/没带但标题与骨架一致时仍算有效（回填方按标题找节点）。
     * 原来只认标识、静默丢弃，一个字符抄错就丢掉整个节点且链照样标「完成」，
     * 用户看到的是一张缺内容的地图却没有任何报错。
     * 标题也不在骨架里的（超纲节点）按「模型改了标题」丢弃，写进 gaps 提示，
     * 而不是让整条链失败。
     */
    fun validateChainNodes(
        output: ChainNodesOutput,
        expectedKeys: Set<String>,
        knownTitles: Set<String>,
    ): Result<Pair<List<NodeDto>, List<String>>> {
        val expected = expectedKeys.map(::canonicalNodeKey).toSet()
        val valid = output.nodes.filter { dto ->
            // 没有 definition 的节点没有任何可写入的内容：多半是截断修复剪出来的
            // 残壳。放它过去会把占位节点标成 COMPLETED——用户看到一个空节点，
            // 而 COMPLETED 永远不再重试，这个洞就补不回来了。丢弃后它保持
            // 占位，本链会被判为「没填满」并重新展开。
            dto.definition.isNotBlank() &&
                (
                    (dto.nodeKey.isNotBlank() && canonicalNodeKey(dto.nodeKey) in expected) ||
                        dto.title.trim() in knownTitles
                    )
        }
        val unmatched = output.nodes
            .filter { it.definition.isNotBlank() }
            .map { it.title.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { title -> title !in knownTitles }
        return Result.success(valid to unmatched)
    }

    /** 全局审计校验（批次化生成）：gaps 非空但 conflicts 结构异常时按脏数据清洗 */
    fun validateAudit(output: KnowledgeMapAuditOutput): Result<KnowledgeMapAuditOutput> {
        val gaps = output.gaps.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val conflicts = output.conflicts
            .filter { it.topic.isNotBlank() }
            .distinctBy { it.topic.trim() }
        return Result.success(output.copy(gaps = gaps, conflicts = conflicts))
    }

    /**
     * 讲述路线校验（第二轮 P1-2 / P1-5 / 批次 A.1）。
     *
     * 硬校验：
     * 1. 有有效章节
     * 2. 章节引用的知识点**必须存在**（引用不存在的节点 = 凭空讲知识，直接失败）
     * 3. `chapterType` 非空时必须是五种合法值之一——未知字符串不能静默当作正常章节
     *    （这条与是否要求契约无关：老数据没有类型=空串放行，非空非法值一律失败）
     * 4. [requireContracts] 开启时（新 Prompt 版本）：每章必须有因果契约、
     *    契约引用的节点必须存在、且满足该类型的最低要求（批次 A.1 逐项收紧）
     *
     * 不在这里做「一链一章」检查——那是一条应删除的耦合（第二轮 §一.1）：
     * 地图链解释知识如何推导，章节链是本章实际采用的讲述路径，两者可以不相等。
     * 拆链、合并、回顾都是合理的教学设计，由 [RouteAudit] 做软审计。
     */
    fun validateStoryBible(
        output: StoryBibleOutput,
        knownNodeTitles: Set<String>,
        requireContracts: Boolean = false,
    ): Result<StoryBibleOutput> {
        val chapters = output.chapters.filter { it.title.isNotBlank() }
        if (chapters.isEmpty()) {
            return Result.failure(ModelError.Invalid("讲述路线没有有效章节"))
        }
        // 节点存在性：章节引用的知识点必须在地图上（硬校验）
        val unknown = chapters
            .flatMap { it.nodeTitles }
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in knownNodeTitles }
            .distinct()
        if (unknown.isNotEmpty()) {
            return Result.failure(
                ModelError.Invalid("章节引用了不存在的知识点：${unknown.take(5).joinToString("、")}"),
            )
        }
        // 未知章节类型：非空非法值无论是否要求契约都失败（批次 A.1 #1）
        chapters.forEach { chapter ->
            val typeName = chapter.chapterType.trim()
            if (typeName.isNotEmpty() && ChapterType.entries.none { it.name == typeName }) {
                return Result.failure(
                    ModelError.Invalid(
                        "章节「${chapter.title}」声明了未知章节类型「$typeName」（第 ${chapter.order} 章）。" +
                            "合法值为 INTRODUCE / APPLY / CONTRAST / REVIEW / ASSESS。",
                    ),
                )
            }
        }
        if (requireContracts) {
            chapters.forEach { chapter ->
                contractViolation(chapter, knownNodeTitles)?.let { return Result.failure(ModelError.Invalid(it)) }
            }
        }
        return Result.success(output.copy(chapters = chapters))
    }

    /**
     * 按章节类型检查因果契约的最低要求（第二轮 P1-2 / 批次 A.1）。
     *
     * 批次 A.1 逐项收紧（编号对应执行指令）：
     * #2 契约引用的节点必须存在；
     * #3 INTRODUCE 至少一个有效新引入节点；
     * #4 APPLY 的应用节点不能全部都是首次引入节点（「应用已有概念」的前提不成立）；
     * #5 CONTRAST 至少两个可区分的概念；
     * #6 REVIEW 不能把节点列入 introduced，空契约不算已完成回顾；
     * #7 ASSESS 必须声明被检验节点并有案例步骤。
     *
     * 注意边界：APPLY 的「正文真的应用了知识」由核验阶段判断（no_application），
     * 这里只校验模型的**声明**，不越权替核验下结论。
     *
     * [knownNodeKeys] 非空时按**编号**比对（三段式逐章契约）：编号是批次内稳定标识，
     * 杜绝了标题匹配「差一个字整章作废」的失败模式。为空时退回标题比对（旧一次成型路径）。
     *
     * 之所以做成 public：三段式里契约是逐章生成、逐章校验的，校验入口在
     * `PipelineService` 而不是某次整体输出的 `validateXxx` 里。
     */
    fun contractViolation(
        chapter: ChapterPlanDto,
        knownNodeTitles: Set<String>,
        knownNodeKeys: Set<String> = emptySet(),
    ): String? {
        // 编号模式下「存在的节点」= 白名单里的编号；标题模式不变
        val known = if (knownNodeKeys.isNotEmpty()) knownNodeKeys else knownNodeTitles
        if (chapter.contract.isEmpty) {
            return "章节「${chapter.title}」缺少因果契约（第 ${chapter.order} 章）。" +
                "新版本路线必须为每一章生成契约，否则无法保证因果结构。"
        }
        val type = ChapterType.entries.firstOrNull { it.name == chapter.chapterType.trim() }
        if (type == null) {
            return "章节「${chapter.title}」没有声明章节类型（第 ${chapter.order} 章）。" +
                "必须为 INTRODUCE / APPLY / CONTRAST / REVIEW / ASSESS 之一。"
        }
        val c = chapter.contract
        // 契约引用的节点必须存在（批次 A.1 #2）
        val contractTitles = (c.introducedNodeTitles + c.appliedNodeTitles)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val unknownInContract = contractTitles.filter { it !in known }.distinct()
        if (unknownInContract.isNotEmpty()) {
            val unit = if (knownNodeKeys.isNotEmpty()) "编号" else "知识点"
            return "章节「${chapter.title}」的契约引用了不存在的$unit：" +
                "${unknownInContract.take(5).joinToString("、")}（第 ${chapter.order} 章）"
        }
        // 有效节点（存在于地图上的去重标题/编号）
        val effectiveNodes = contractTitles.filter { it in known }.distinct()
        val introduced = c.introducedNodeTitles.map { it.trim() }.filter { it in known }.toSet()
        val applied = c.appliedNodeTitles.map { it.trim() }.filter { it in known }.toSet()
        return when (type) {
            ChapterType.INTRODUCE -> when {
                c.knowledgeGap.isBlank() -> "INTRODUCE 章节「${chapter.title}」缺少 knowledgeGap（读者必须感到缺一块解释）"
                c.resultingChange.isBlank() -> "INTRODUCE 章节「${chapter.title}」缺少 resultingChange（新概念必须带来可观察变化）"
                introduced.isEmpty() -> "INTRODUCE 章节「${chapter.title}」没有有效的 introducedNodeTitles（至少一个存在的新引入节点）"
                else -> null
            }
            ChapterType.APPLY -> when {
                applied.isEmpty() -> "APPLY 章节「${chapter.title}」没有列出有效的 appliedNodeTitles（必须应用已有概念）"
                c.resultingChange.isBlank() -> "APPLY 章节「${chapter.title}」缺少 resultingChange（应用必须带来可观察结果）"
                // 应用节点全部都是本章首次引入 → 「应用已有概念」不成立（批次 A.1 #4）
                applied.isNotEmpty() && applied.all { it in introduced } ->
                    "APPLY 章节「${chapter.title}」的应用节点全是本章首次引入的节点——" +
                        "「应用已有概念」的前提不成立，至少需要一个先前章节引入过的节点"
                else -> null
            }
            ChapterType.CONTRAST -> when {
                c.obstacle.isBlank() -> "CONTRAST 章节「${chapter.title}」缺少 obstacle（必须摆出容易混的点）"
                c.resultingChange.isBlank() -> "CONTRAST 章节「${chapter.title}」缺少 resultingChange（必须给出区分后的结果）"
                // 至少两个需要区分的概念（批次 A.1 #5）
                effectiveNodes.size < 2 -> "CONTRAST 章节「${chapter.title}」的可区分概念不足两个（当前 ${effectiveNodes.size} 个）——" +
                    "辨析必须有至少两个对象"
                else -> null
            }
            ChapterType.REVIEW -> when {
                c.introducedNodeTitles.isNotEmpty() -> "REVIEW 章节「${chapter.title}」把新知识点标成了 introducedNodeTitles——回顾章节不能伪装成首次介绍"
                applied.isEmpty() -> "REVIEW 章节「${chapter.title}」没有列出有效的 appliedNodeTitles（回顾必须引用已学节点）"
                else -> null
            }
            ChapterType.ASSESS -> when {
                applied.isEmpty() -> "ASSESS 章节「${chapter.title}」没有列出有效的 appliedNodeTitles（必须声明被检验的节点）"
                c.requiredBeats.isEmpty() -> "ASSESS 章节「${chapter.title}」没有 requiredBeats（案例推演至少需要一个判断目标/步骤）"
                c.resultingChange.isBlank() -> "ASSESS 章节「${chapter.title}」缺少 resultingChange（案例必须给出判断结果）"
                else -> null
            }
        }
    }

    /**
     * 续卷规划的校验（第三阶段 3C）。
     *
     * 比首次规划多两条：章数不能超过卷内上限（多给的丢掉，留给再下一卷），
     * 且至少要有一章真的落在**还没讲过**的知识点上——否则这一卷就是把讲过的
     * 再讲一遍，白花钱。已讲知识点仍可作为承接引用，只是不能是全部。
     */
    fun validateNextVolume(
        output: NextVolumeOutput,
        knownNodeTitles: Set<String>,
        pendingNodeTitles: Set<String>,
        maxChapters: Int,
        requireContracts: Boolean = false,
    ): Result<NextVolumeOutput> {
        val chapters = output.chapters.filter { it.title.isNotBlank() }.take(maxChapters)
        if (chapters.isEmpty()) {
            return Result.failure(ModelError.Invalid("续卷规划没有有效章节"))
        }
        if (chapters.none { chapter -> chapter.nodeTitles.any { it.trim() in knownNodeTitles } }) {
            return Result.failure(ModelError.Invalid("续卷规划没有任何一章关联到已有知识点"))
        }
        // 还有没讲的知识点时，新卷必须至少讲到一个；全讲完了才允许纯深化
        if (pendingNodeTitles.isNotEmpty() &&
            chapters.none { chapter -> chapter.nodeTitles.any { it.trim() in pendingNodeTitles } }
        ) {
            return Result.failure(
                ModelError.Invalid("续卷规划全是已经讲过的知识点，没有推进主线"),
            )
        }
        // 节点存在性（与首次规划同一套硬校验）
        val unknown = chapters
            .flatMap { it.nodeTitles }
            .map { it.trim() }
            .filter { it.isNotBlank() && it !in knownNodeTitles }
            .distinct()
        if (unknown.isNotEmpty()) {
            return Result.failure(
                ModelError.Invalid("续卷章节引用了不存在的知识点：${unknown.take(5).joinToString("、")}"),
            )
        }
        // 未知章节类型：非空非法值无论是否要求契约都失败（批次 A.1 #1）
        chapters.forEach { chapter ->
            val typeName = chapter.chapterType.trim()
            if (typeName.isNotEmpty() && ChapterType.entries.none { it.name == typeName }) {
                return Result.failure(
                    ModelError.Invalid(
                        "续卷章节「${chapter.title}」声明了未知章节类型「$typeName」。",
                    ),
                )
            }
        }
        if (requireContracts) {
            chapters.forEach { chapter ->
                contractViolation(chapter, knownNodeTitles)?.let { return Result.failure(ModelError.Invalid(it)) }
            }
        }
        return Result.success(output.copy(chapters = chapters))
    }

    fun validateChapter(
        output: ChapterOutput,
        knownNodeTitles: Set<String>,
        validChunkIds: Set<String>,
        requireSourceRefs: Boolean = true,
    ): Result<ChapterOutput> {
        val blocks = output.blocks.filter { it.text.isNotBlank() }
        if (blocks.isEmpty()) {
            return Result.failure(ModelError.Invalid("章节正文为空"))
        }
        // 先把模型回传的标题对齐到本章真实标题（变体归位 + 文内取证），
        // 对齐是确定性修复，见 relinkKnowledgeNodeTitles。归位后的标题随校验通过
        // 流回调用方（runChapter 按标题反解成节点 ID），真实落库。
        //
        // 对齐后讲解段落**全部**没有关联上知识点时，这里不再拒收：拒收等于把
        // 一次上万 token 的正文整个作废（2026-09-14 实测「生成没有完成」死循环）。
        // 降级交给流水线——正文照常发布、说明写进核验备注、章节标待复核，
        // 与「核验带问题发布」同一个处理原则。
        val relinked = relinkKnowledgeNodeTitles(blocks, knownNodeTitles)
        // 引用不在这里过滤（4A）。
        //
        // 原来这一步会把对不上片段的引用静默丢掉：问题随之消失，正文里那句
        // 需要出处的话变成了没人管的断言，阅读器还会显示「本段无引用」。
        // 现在原样交给 CitationResolver，由它记成 RESOLVED 或 UNCONFIRMED。
        // 校验这一层只判断「有没有可追溯的引用」这件事本身。
        if (requireSourceRefs) {
            val factBlocks = blocks.filter { it.type == BlockType.FACT }
            val traceable = factBlocks.filter { block ->
                block.sourceRefs.any { it.trim() in validChunkIds }
            }
            if (factBlocks.isNotEmpty() && traceable.isEmpty()) {
                return Result.failure(ModelError.Invalid("原始事实段落缺少可追溯的资料引用"))
            }
        }
        return Result.success(output.copy(blocks = relinked))
    }

    /**
     * 把正文块回传的知识点标题对齐到本章真实标题（确定性修复，泛化自 [canonicalNodeKey] 的思路）。
     *
     * 线上反复出现同一类问题：正文本身写得没问题，但 `knowledgeNodeTitles` 要么整段
     * 留空（批次 B 样本实测部分模型从不填），要么抄成了标题的变体——标点全半角不同、
     * 夹了空格、多了引号或书名号。按精确比对落库时这些块就全都不带知识点 ID：
     * 阅读器的知识点角标、节点回写、复习清单全都拿不到；对齐不上时流水线还会
     * 把整章降级成待复核（2026-09 实测「生成没有完成」的直接原因）。
     *
     * 这类修复是确定性的字符串对齐，不需要模型判断力；回炉让模型重写既慢又贵，
     * 还会顺手改坏别处（ContractIntroducedFix 的注释里有完整账）。两步，都只动标题、不动正文：
     *
     * 1. **变体归位**：归一化（只保留文字与数字）后与真实标题一致的，替换回真实标题；
     *    对不上的原样保留，不静默丢弃。
     * 2. **文内取证**：归位后仍没有任何有效关联的讲解段落，回正文里找——哪个真实
     *    标题（归一化后）出现在这段文字里，就关联哪个。这是有证据的关联：标题确实是
     *    这段话在讲的概念，不是凭空贴标签；STORY 块不参与（它们不承担讲解责任）。
     *    取证也找不到就不硬造，原样交回调用方——由流水线按「全未关联」降级处理。
     *
     * 归位后的标题会随校验通过流回调用方（`runChapter` 按标题反解成节点 ID），
     * 所以这里补上的关联会真实落库，而不只是让校验放行。
     */
    private fun relinkKnowledgeNodeTitles(
        blocks: List<BlockDto>,
        knownNodeTitles: Set<String>,
    ): List<BlockDto> {
        if (knownNodeTitles.isEmpty()) return blocks
        val byNormalized = knownNodeTitles
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associateBy { normalizeTitle(it) }
        if (byNormalized.isEmpty()) return blocks
        return blocks.map { block ->
            // 第一步：变体归位——模型抄写的标题哪怕带上了多余标点也能对回真实标题
            val canonical = block.knowledgeNodeTitles.map { raw ->
                val trimmed = raw.trim()
                byNormalized[normalizeTitle(trimmed)] ?: trimmed
            }.filter { it.isNotEmpty() }.distinct()
            val linked =
                if (canonical.any { it in knownNodeTitles } || !isKnowledgeType(block.type)) {
                    canonical
                } else {
                    // 第二步：文内取证——只对承担讲解责任的段落兜底
                    val textNorm = normalizeTitle(block.text)
                    val found = byNormalized.entries
                        .map { it.value to it.key }
                        .filter { (_, norm) -> norm.length >= MIN_FALLBACK_TITLE_LENGTH && textNorm.contains(norm) }
                        .sortedByDescending { (_, norm) -> norm.length }
                        .take(FALLBACK_LINK_LIMIT)
                        .map { it.first }
                    (canonical + found).distinct()
                }
            if (linked == block.knowledgeNodeTitles) {
                block
            } else {
                block.copy(knowledgeNodeTitles = linked)
            }
        }
    }

    /** 标题归一化：只保留文字与数字——标点、空格、全半角差异一律抹平 */
    private fun normalizeTitle(raw: String): String = raw.filter { it.isLetterOrDigit() }

    /** 承担讲解责任的段落类型，文内取证只对它们兜底 */
    private fun isKnowledgeType(type: BlockType): Boolean =
        type == BlockType.EXPLANATION || type == BlockType.FACT || type == BlockType.KEY_POINT

    /** 文内取证兜底：单段最多关联几个知识点（防止把一整串标题都贴上去） */
    private const val FALLBACK_LINK_LIMIT = 4

    /** 标题归一化后短于这个长度的（如单字标题）不做文内子串匹配——太容易误命中 */
    private const val MIN_FALLBACK_TITLE_LENGTH = 2

    fun validateQuizSet(
        output: QuizSetOutput,
        knownNodeTitles: Set<String>,
        minQuestions: Int,
    ): Result<QuizSetOutput> {
        val valid = output.questions.filter { quiz ->
            quiz.question.isNotBlank() &&
                quiz.answer.isNotBlank() &&
                quiz.nodeTitles.any { it.trim() in knownNodeTitles } &&
                optionsValid(quiz)
        }
        if (valid.size < minQuestions) {
            return Result.failure(
                ModelError.Invalid("有效题目不足：需要 $minQuestions 题，实际 ${valid.size} 题"),
            )
        }
        return Result.success(output.copy(questions = valid))
    }

    private fun optionsValid(quiz: QuizDto): Boolean = when (quiz.type) {
        QuizType.RECALL -> true
        else -> quiz.options.size >= 2 &&
            quiz.options.none { it.isBlank() } &&
            quiz.options.contains(quiz.answer)
    }
}
