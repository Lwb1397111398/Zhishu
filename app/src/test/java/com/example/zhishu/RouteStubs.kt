package com.example.zhishu

/**
 * 三段式讲述路线的测试桩（大纲 / 契约两个阶段）。
 *
 * 放在一处而不是散在各个 Router 里，是因为三个测试 Router（ScriptedRouter、
 * VolumeRouter、GenerationTest.FakeRouter）都要回答同一个问题：
 * 「给模型看的 prompt 长什么样、模型该回什么」。分开写三份，改一次契约字段就要改三处。
 *
 * 契约桩**按章节类型生成**能通过 `contractViolation` 的输出：
 * 类型不同最低要求不同（INTRODUCE 必须有新引入、REVIEW 的 introduced 必须为空、
 * CONTRAST 至少要两个对象、ASSESS 必须有拍子），一个固定 JSON 只能覆盖一种类型。
 */
internal object RouteStubs {

    /** 大纲模板。保留关键占位符，让断言能验「喂进去的到底是什么」 */
    const val OUTLINE_TEMPLATE =
        "[[OUTLINE]] 章数={chapterHint} 节点={nodes} 分组={volumeGroups}"

    /** 契约模板 */
    const val CONTRACT_TEMPLATE =
        "[[CONTRACT]] 类型={chapterType} 允许={allowedNodeKeys} 已引入={alreadyIntroduced} " +
            "大纲={chapterOutline} 节点={nodeDetails}"

    private val KEY = Regex("""#(nk-[^\s｜、,，]+)""")
    private val NONE_MARK = Regex("""（无[^）]*）""")

    /** 大纲返回几章（可被测试覆盖，用来验证卷内上限） */
    @Volatile
    var outlineChapterCount: Int? = null

    /** 契约桩是否故意返回违规输出（用来验证「失败只影响一章」） */
    @Volatile
    var contractViolationAtChapter: Int? = null

    /**
     * 大纲桩是否故意返回清单外的编号。
     *
     * 用来验证「引用不存在的知识点」这个硬校验：三段式改成编号匹配之后，
     * 这条校验的实现变了，但「凭空造一个知识点必须拦下」的性质不能丢。
     */
    @Volatile
    var outlineForcesUnknownKey: Boolean = false

    /**
     * 开场钩子文案（按章序取，取不到退回默认文案）。
     *
     * 「喂进正文提示词的是哪一段开场」只能靠文案比对，所以需要可指定。
     */
    @Volatile
    var openingHooks: List<String> = emptyList()

    /**
     * 清空全部桩状态。
     *
     * 桩是全局单例（三个 Router 共用），一个测试设了「故意违规」不复位，
     * 后面所有测试都会莫名失败——而且失败点和设值点隔得很远，极难查。
     * 每个用到桩的测试类都在 @Before 里调它。
     */
    fun reset() {
        outlineChapterCount = null
        contractViolationAtChapter = null
        outlineForcesUnknownKey = false
        openingHooks = emptyList()
    }

    /**
     * 从大纲 prompt 里解析出节点编号清单，生成一份合规的大纲 JSON。
     *
     * 类型分布按 [types] 轮转而不是全给 INTRODUCE——`enforceTypes` 的配额修复
     * 只有在模型给的分布不合规时才会动，测试要能同时覆盖「模型给得好」和「程序修」。
     */
    fun outlineJson(prompt: String, types: List<String> = DEFAULT_TYPES): String {
        val nodesBlock = prompt.substringAfter("节点=", "").substringBefore(" 分组=")
        val keys = KEY.findAll(nodesBlock).map { it.groupValues[1] }.distinct().toList()
        val count = outlineChapterCount
            ?: Regex("""章数=(\d+)""").find(prompt)?.groupValues?.get(1)?.toIntOrNull()
            ?: 4
        val chapters = (0 until count).joinToString(",") { index ->
            val order = index + 1
            val nodeKeys = keys.takeIf { it.isNotEmpty() }?.let {
                listOf(it[index % it.size])
            }.orEmpty()
            val rendered = if (outlineForcesUnknownKey) listOf("nk-does-not-exist") else nodeKeys
            """{"order":$order,"title":"第${order}章",""" +
                """"derivationChain":"从既有规则出发 → 撞上它解决不了的问题 → 长出新的规则",""" +
                """"plotGoal":"走完第${order}段推演","learningGoals":["这一章想通什么？"],""" +
                """"nodeKeys":[${rendered.joinToString(",") { "\"#$it\"" }}],""" +
                """"chapterType":"${types[index % types.size]}","wordTarget":1200,""" +
                """"openingHook":"${openingHooks.getOrElse(index) { "第${order}章的开场处境" }}",""" +
                """"closingHook":"第${order}章留下的口子"}"""
        }
        return """{"volumeTitle":"第一卷","volumeGoal":"把这门学科讲通","chapters":[$chapters]}"""
    }

    /**
     * 从契约 prompt 里解析类型与白名单，生成一份**该类型合规**的契约 JSON。
     *
     * 编号一律取自 prompt（白名单或本章知识点清单），绝不自己发明——
     * 契约校验的第一条就是「引用的编号必须存在」。
     */
    fun contractJson(prompt: String): String {
        val type = prompt.substringAfter("类型=", "").substringBefore(" ").trim()
            .uppercase()
        val allowedBlock = prompt.substringAfter("允许=", "").substringBefore(" 已引入=")
        val chapterBlock = prompt.substringAfter("大纲=", "").substringBefore(" 节点=")
        val introducedBlock = prompt.substringAfter("已引入=", "").substringBefore(" 大纲=")
        val allowed = parseKeys(allowedBlock)
        val chapterKeys = chapterBlock.substringAfter("本章涉及的知识点：", "")
            .let { parseKeys(it) }
            .ifEmpty { allowed }
        val alreadyIntroduced = parseKeys(introducedBlock)

        if (contractViolationAtChapter != null &&
            Regex("""第(\d+)章""").find(chapterBlock)?.groupValues?.get(1)?.toIntOrNull()
            == contractViolationAtChapter
        ) {
            // 故意违规：introduced 里塞一个不在白名单里的编号
            return contractBody(introduced = listOf("#nk-999"), applied = chapterKeys)
        }

        val introduced = when (type) {
            "REVIEW", "ASSESS" -> emptyList()
            "APPLY", "CONTRAST" -> emptyList()
            else -> allowed.ifEmpty { chapterKeys.filter { it !in alreadyIntroduced } }
        }
        val applied = (chapterKeys + alreadyIntroduced).distinct().filter { it !in introduced }
            .ifEmpty { chapterKeys }
        return contractBody(introduced, applied)
    }

    private fun contractBody(introduced: List<String>, applied: List<String>): String =
        """{"entryQuestion":"这一章开头面对的具体问题","priorIntuition":"原本的直觉或旧办法",""" +
            """"obstacle":"旧办法撞上的阻碍","knowledgeGap":"因此需要一个新的解释",""" +
            """"introducedNodeKeys":[${introduced.joinToString(",") { "\"$it\"" }}],""" +
            """"appliedNodeKeys":[${applied.joinToString(",") { "\"$it\"" }}],""" +
            """"requiredBeats":["开场处境","撞上阻碍","用上知识","看到变化"],""" +
            """"resultingChange":"知识用上之后发生的可观察变化",""" +
            """"bridgeToNext":"交给下一章的问题"}"""

    /** 从一段文本里抓编号；「（无）」这类占位说明要排除 */
    private fun parseKeys(block: String): List<String> {
        val cleaned = NONE_MARK.replace(block, "")
        return KEY.findAll(cleaned).map { it.groupValues[0] }.distinct().toList()
    }

    private val DEFAULT_TYPES = listOf("INTRODUCE", "APPLY", "CONTRAST", "REVIEW")
}
