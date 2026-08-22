package com.example.zhishu

import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.outputs.ChapterContractDto
import com.example.zhishu.ai.outputs.ChapterPlanDto
import com.example.zhishu.ai.outputs.OutputValidators
import com.example.zhishu.ai.outputs.RouteAudit
import com.example.zhishu.ai.outputs.RouteAuditIssue
import com.example.zhishu.ai.outputs.StoryBibleOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二轮批次 A：路线结构反例测试（离线，不调模型）。
 *
 * 覆盖分析师反例清单（可离线验证的部分）：
 * 前置倒挂 / 前置文本无法匹配 / 同一节点重复引入 / 地图孤立节点 / 章节负荷超标 /
 * 类型单一化 / 链跨章跳跃 / 章节引用不存在的节点 / 契约缺失的兼容与强校验 /
 * 各章节类型的最低要求 / 回顾章节伪装首次介绍。
 */
class RouteAuditTest {

    // ---- 工具：构造章节与节点 ----

    private fun chapter(
        order: Int,
        titles: List<String>,
        type: String = "INTRODUCE",
        contract: ChapterContractDto = fullContract(introduced = titles),
        wordTarget: Int = 1200,
    ) = ChapterPlanDto(
        order = order,
        title = "第${order}章",
        derivationChain = "从X出发 → 撞上Y → 长出Z",
        plotGoal = "走完本章",
        learningGoals = listOf("想通什么"),
        nodeTitles = titles,
        wordTarget = wordTarget,
        openingHook = "开场",
        closingHook = "口子",
        chapterType = type,
        contract = contract,
    )

    private fun fullContract(introduced: List<String> = emptyList(), applied: List<String> = emptyList()) =
        ChapterContractDto(
            entryQuestion = "开头问题",
            priorIntuition = "原有直觉",
            obstacle = "遇到的阻碍",
            knowledgeGap = "需要一个新解释",
            introducedNodeTitles = introduced,
            appliedNodeTitles = applied,
            requiredBeats = listOf("开场", "引入", "收束"),
            resultingChange = "有了新判断",
            bridgeToNext = "下一个问题",
        )

    private fun bible(chapters: List<ChapterPlanDto>) = StoryBibleOutput(
        world = "w", characters = emptyList(), conflict = "c", style = "s",
        chapters = chapters,
    )

    // ---- 反例 1：前置节点出现在后章 ----

    @Test
    fun audit_prerequisiteInLaterChapter_reportsOrderIssue() {
        // A 是 B 的前置；B 在第 1 章，A 在第 2 章 → 前置倒挂
        val chapters = listOf(
            chapter(1, listOf("B"), contract = fullContract(applied = listOf("B"))),
            chapter(2, listOf("A")),
        )
        val issues = RouteAudit.audit(
            chapters = chapters,
            nodeTitlesByPrereqs = mapOf("B" to listOf("A")),
            allNodeTitles = setOf("A", "B"),
            dailyMinutes = 30,
        )
        val orderIssue = issues.firstOrNull { it.code == RouteAuditIssue.Code.PREREQUISITE_ORDER }!!
        assertEquals(RouteAuditIssue.Level.WARN, orderIssue.level)
        assertEquals(1, orderIssue.chapterOrder)
    }

    // ---- 反例 2：前置是自由文本，匹配不到任何节点标题 ----

    @Test
    fun audit_unresolvablePrerequisite_reportsUnresolved() {
        val chapters = listOf(chapter(1, listOf("A")))
        val issues = RouteAudit.audit(
            chapters = chapters,
            nodeTitlesByPrereqs = mapOf("A" to listOf("需要先理解财产转移的一般原理")),
            allNodeTitles = setOf("A"),
            dailyMinutes = 30,
        )
        val unresolved = issues.firstOrNull { it.code == RouteAuditIssue.Code.PREREQUISITE_UNRESOLVED }!!
        assertEquals(RouteAuditIssue.Level.INFO, unresolved.level)
    }

    // ---- 反例 3：同一节点在多章被标为首次介绍 ----

    @Test
    fun audit_duplicateIntroduce_reportsWarning() {
        val chapters = listOf(
            chapter(1, listOf("A")),
            chapter(2, listOf("A", "B")),
        )
        val issues = RouteAudit.audit(
            chapters = chapters,
            nodeTitlesByPrereqs = emptyMap(),
            allNodeTitles = setOf("A", "B"),
            dailyMinutes = 30,
        )
        val dup = issues.firstOrNull { it.code == RouteAuditIssue.Code.DUPLICATE_INTRODUCE }!!
        assertTrue(dup.message.contains("A"))
    }

    // ---- 反例 4：地图孤立节点（未被任何章节安排） ----

    @Test
    fun audit_unassignedNode_reportsRangeGap() {
        val chapters = listOf(chapter(1, listOf("A")))
        val issues = RouteAudit.audit(
            chapters = chapters,
            nodeTitlesByPrereqs = emptyMap(),
            allNodeTitles = setOf("A", "B", "C"),
            dailyMinutes = 30,
        )
        val gap = issues.firstOrNull { it.code == RouteAuditIssue.Code.RANGE_GAP }!!
        assertTrue(gap.message.contains("B") && gap.message.contains("C"))
    }

    // ---- 反例 5：章节负荷超过用户每日目标 ----

    @Test
    fun audit_overloadedChapter_reportsLoadIssue() {
        // 4000 字 ≈ 13+ 分钟 > 每日 10 分钟
        val chapters = listOf(chapter(1, listOf("A"), wordTarget = 4000))
        val issues = RouteAudit.audit(
            chapters = chapters,
            nodeTitlesByPrereqs = emptyMap(),
            allNodeTitles = setOf("A"),
            dailyMinutes = 10,
        )
        val load = issues.firstOrNull { it.code == RouteAuditIssue.Code.LOAD_EXCEEDS_TARGET }!!
        assertEquals(RouteAuditIssue.Level.WARN, load.level)
    }

    // ---- 反例 6：整卷章节类型单一 ----

    @Test
    fun audit_singleChapterType_reportsTypeSingleton() {
        val chapters = (1..4).map { chapter(it, listOf("A")) }
        val issues = RouteAudit.audit(
            chapters = chapters,
            nodeTitlesByPrereqs = emptyMap(),
            allNodeTitles = setOf("A"),
            dailyMinutes = 30,
        )
        val type = issues.firstOrNull { it.code == RouteAuditIssue.Code.TYPE_SINGLETON }!!
        assertTrue(type.message.isNotBlank())
    }

    // ---- 反例 7：同一演进链跨章时中间出现跳跃 ----

    @Test
    fun audit_chainJumpingOverChapters_reportsDiscontinuity() {
        // 链 [A,B,C]：A 在第 1 章、C 在第 3 章、B 未安排 → 跨过第 2 章，需要提示连续性
        val chapters = listOf(
            chapter(1, listOf("A")),
            chapter(2, listOf("X")),
            chapter(3, listOf("C")),
        )
        val issues = RouteAudit.audit(
            chapters = chapters,
            nodeTitlesByPrereqs = emptyMap(),
            allNodeTitles = setOf("A", "B", "C", "X"),
            chains = listOf(listOf("A", "B", "C")),
            dailyMinutes = 30,
        )
        val jump = issues.firstOrNull { it.code == RouteAuditIssue.Code.CHAIN_DISCONTINUITY }!!
        assertTrue(jump.message.isNotBlank())
    }

    // ---- 反例 8：章节引用不存在的节点（硬校验） ----

    @Test
    fun validateStoryBible_unknownNode_fails() {
        val output = bible(listOf(chapter(1, listOf("幽灵知识点"))))
        val result = OutputValidators.validateStoryBible(output, setOf("A"), requireContracts = false)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ModelError.Invalid)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("不存在的知识点"))
    }

    // ---- 反例 9：老模型输出（无契约字段）在兼容模式下通过 ----

    @Test
    fun validateStoryBible_legacyWithoutContract_passesWhenNotRequired() {
        // 老模型 JSON 解析后契约为空、类型为空——兼容模式（默认）必须放行
        val output = bible(listOf(chapter(1, listOf("A")).copy(chapterType = "", contract = ChapterContractDto())))
        val result = OutputValidators.validateStoryBible(output, setOf("A"), requireContracts = false)
        assertTrue("旧输出在兼容模式下必须通过", result.isSuccess)
    }

    // ---- 反例 10：老模型输出在强校验模式下明确失败（不伪造通过） ----

    @Test
    fun validateStoryBible_legacyWithoutContract_failsWhenRequired() {
        val output = bible(listOf(chapter(1, listOf("A")).copy(chapterType = "", contract = ChapterContractDto())))
        val result = OutputValidators.validateStoryBible(output, setOf("A"), requireContracts = true)
        assertTrue("新 Prompt 版本要求契约，缺失必须失败", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("失败信息必须可读", message.contains("缺少因果契约"))
    }

    // ---- 反例 11：有契约但没声明类型 → 失败 ----

    @Test
    fun validateStoryBible_missingType_failsWhenRequired() {
        val output = bible(listOf(chapter(1, listOf("A")).copy(chapterType = "")))
        val result = OutputValidators.validateStoryBible(output, setOf("A"), requireContracts = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("没有声明章节类型"))
    }

    // ---- 反例 12：INTRODUCE 缺 knowledgeGap → 失败 ----

    @Test
    fun validateStoryBible_introduceWithoutGap_fails() {
        val bad = chapter(1, listOf("A")).copy(
            contract = fullContract(introduced = listOf("A")).copy(knowledgeGap = ""),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(bad)), setOf("A"), requireContracts = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("knowledgeGap"))
    }

    // ---- 反例 13：APPLY 缺 appliedNodeTitles → 失败 ----

    @Test
    fun validateStoryBible_applyWithoutAppliedNodes_fails() {
        val bad = chapter(1, listOf("A"), type = "APPLY").copy(
            contract = fullContract(introduced = listOf("A")).copy(appliedNodeTitles = emptyList()),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(bad)), setOf("A"), requireContracts = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("appliedNodeTitles"))
    }

    // ---- 反例 14：REVIEW 把新节点当 introduced → 失败（回顾不能伪装首次介绍） ----

    @Test
    fun validateStoryBible_reviewPretendingIntroduce_fails() {
        val bad = chapter(1, listOf("A"), type = "REVIEW").copy(
            contract = fullContract(introduced = listOf("A"), applied = listOf("A")),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(bad)), setOf("A"), requireContracts = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("不能伪装成首次介绍"))
    }

    // ---- 正例：契约完整、类型正确 → 通过 ----

    @Test
    fun validateStoryBible_fullContract_passes() {
        val ok = chapter(1, listOf("A"))
        val result = OutputValidators.validateStoryBible(bible(listOf(ok)), setOf("A"), requireContracts = true)
        assertTrue(result.isSuccess)
    }

    // ---- 正例：APPLY 类型带应用节点与结果 → 通过 ----

    @Test
    fun validateStoryBible_applyWithAppliedNodes_passes() {
        val ok = chapter(1, listOf("A"), type = "APPLY").copy(
            contract = fullContract(applied = listOf("A")),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(ok)), setOf("A"), requireContracts = true)
        assertTrue(result.isSuccess)
    }

    // ---- 兼容：REVIEW 只引用已学节点（applied 非空、introduced 空）→ 通过 ----

    @Test
    fun validateStoryBible_reviewWithOnlyApplied_passes() {
        val ok = chapter(1, listOf("A"), type = "REVIEW").copy(
            contract = fullContract(applied = listOf("A")).copy(introducedNodeTitles = emptyList()),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(ok)), setOf("A"), requireContracts = true)
        assertTrue(result.isSuccess)
    }

    // ---- 续卷同样受契约强校验 ----

    @Test
    fun validateNextVolume_requiresContractAndNodeExistence() {
        // 存在性：章节引用不存在的节点（known 含 A，章节里混入「幽灵」）
        val withGhost = listOf(chapter(1, listOf("A", "幽灵")))
        val output = com.example.zhishu.ai.outputs.NextVolumeOutput(
            volumeTitle = "第二卷",
            volumeGoal = "深化",
            chapters = withGhost,
        )
        val unknown = OutputValidators.validateNextVolume(
            output, knownNodeTitles = setOf("A"), pendingNodeTitles = setOf("A"),
            maxChapters = 8, requireContracts = false,
        )
        assertTrue(unknown.isFailure)
        assertTrue(unknown.exceptionOrNull()?.message.orEmpty().contains("不存在的知识点"))
        // 契约缺失（强校验）
        val legacy = output.copy(
            chapters = listOf(chapter(1, listOf("A")).copy(chapterType = "", contract = ChapterContractDto())),
        )
        val noContract = OutputValidators.validateNextVolume(
            legacy, knownNodeTitles = setOf("A"), pendingNodeTitles = emptySet(),
            maxChapters = 8, requireContracts = true,
        )
        assertTrue(noContract.isFailure)
        assertTrue(noContract.exceptionOrNull()?.message.orEmpty().contains("缺少因果契约"))
        // 兼容模式放行
        val legacyOk = OutputValidators.validateNextVolume(
            legacy, knownNodeTitles = setOf("A"), pendingNodeTitles = emptySet(),
            maxChapters = 8, requireContracts = false,
        )
        assertTrue(legacyOk.isSuccess)
    }

    // ---- 契约 isEmpty 语义：全空字段才算缺失 ----

    @Test
    fun contract_isEmpty_onlyWhenAllFieldsBlank() {
        assertTrue(ChapterContractDto().isEmpty)
        assertFalse(fullContract().isEmpty)
        // 只有一个字段有值也不算「未生成」
        assertFalse(ChapterContractDto(entryQuestion = "x").isEmpty)
    }

    // ---- 拆链不报错：两条链拆进不同章节，只检查连续性不判「链被拆散」 ----

    @Test
    fun audit_splitChainAcrossAdjacentChapters_notReportedAsError() {
        // 链 [A,B]：A 在第 1 章、B 在第 2 章（相邻），这是合理的拆链，不应报连续性错误
        val chapters = listOf(
            chapter(1, listOf("A")),
            chapter(2, listOf("B")),
        )
        val issues = RouteAudit.audit(
            chapters = chapters,
            nodeTitlesByPrereqs = emptyMap(),
            allNodeTitles = setOf("A", "B"),
            chains = listOf(listOf("A", "B")),
            dailyMinutes = 30,
        )
        assertTrue("相邻章拆链不应报连续性错误", issues.none { it.code == RouteAuditIssue.Code.CHAIN_DISCONTINUITY })
    }

    // ==================== 批次 A.1 加固 ====================

    // ---- A.1 #1：未知章节类型无论是否要求契约都失败 ----

    @Test
    fun unknownChapterType_failsEvenWithoutContractRequirement() {
        val bad = chapter(1, listOf("A")).copy(chapterType = "MAGIC")
        val result = OutputValidators.validateStoryBible(bible(listOf(bad)), setOf("A"), requireContracts = false)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("未知章节类型"))
    }

    @Test
    fun unknownChapterType_inNextVolume_fails() {
        val bad = chapter(1, listOf("A")).copy(chapterType = "nonsense")
        val output = com.example.zhishu.ai.outputs.NextVolumeOutput(chapters = listOf(bad))
        val result = OutputValidators.validateNextVolume(
            output, knownNodeTitles = setOf("A"), pendingNodeTitles = setOf("A"),
            maxChapters = 8, requireContracts = false,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("未知章节类型"))
    }

    // ---- A.1 #2：契约引用的节点必须存在 ----

    @Test
    fun contractReferencingUnknownNode_fails() {
        val bad = chapter(1, listOf("A")).copy(
            contract = fullContract(introduced = listOf("幽灵节点")),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(bad)), setOf("A"), requireContracts = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("不存在的知识点"))
    }

    // ---- A.1 #3：INTRODUCE 至少一个有效新引入节点（未知节点不算） ----

    @Test
    fun introduceWithOnlyUnknownNodes_fails() {
        // introduced 非空但全是未知节点 → 被「契约引用不存在的节点」拦截（#2 优先于 #3）
        val bad = chapter(1, listOf("A")).copy(
            contract = fullContract(introduced = listOf("幽灵")),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(bad)), setOf("A"), requireContracts = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("不存在的知识点"))
    }

    // ---- A.1 #4：APPLY 的应用节点不能全部都是首次引入节点 ----

    @Test
    fun applyWithOnlyIntroducedNodes_fails() {
        // applied 与 introduced 是同一个节点 → 「应用已有概念」不成立
        val bad = chapter(1, listOf("A"), type = "APPLY").copy(
            contract = fullContract(introduced = listOf("A"), applied = listOf("A")),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(bad)), setOf("A"), requireContracts = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("全是本章首次引入"))
    }

    @Test
    fun applyWithAtLeastOnePriorNode_passes() {
        // applied 含 B（先前章节引入过），引入 A → 合法
        val ok = chapter(2, listOf("A", "B"), type = "APPLY").copy(
            contract = fullContract(introduced = listOf("A"), applied = listOf("B")),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(ok)), setOf("A", "B"), requireContracts = true)
        assertTrue(result.isSuccess)
    }

    // ---- A.1 #5：CONTRAST 至少两个可区分概念 ----

    @Test
    fun contrastWithSingleNode_fails() {
        val bad = chapter(1, listOf("A"), type = "CONTRAST").copy(
            contract = fullContract(introduced = listOf("A"), applied = emptyList()),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(bad)), setOf("A"), requireContracts = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("可区分概念不足两个"))
    }

    @Test
    fun contrastWithTwoNodes_passes() {
        val ok = chapter(1, listOf("A", "B"), type = "CONTRAST").copy(
            contract = fullContract(introduced = listOf("A", "B")),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(ok)), setOf("A", "B"), requireContracts = true)
        assertTrue(result.isSuccess)
    }

    // ---- A.1 #6：REVIEW 空契约不算已完成回顾（requireContracts 下空契约全局失败） ----

    @Test
    fun reviewWithEmptyContract_fails() {
        val bad = chapter(1, listOf("A"), type = "REVIEW").copy(
            contract = ChapterContractDto(), // 空契约
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(bad)), setOf("A"), requireContracts = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("缺少因果契约"))
    }

    // ---- A.1 #7：ASSESS 必须有被检验节点与案例步骤 ----

    @Test
    fun assessWithoutBeats_fails() {
        val bad = chapter(1, listOf("A"), type = "ASSESS").copy(
            contract = fullContract(applied = listOf("A")).copy(requiredBeats = emptyList()),
        )
        val result = OutputValidators.validateStoryBible(bible(listOf(bad)), setOf("A"), requireContracts = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("requiredBeats"))
    }

    // ---- A.1 #8：规划输出的前置声明（prerequisiteNodeTitles） ----

    @Test
    fun prerequisiteSelfReferencingCurrentIntroduce_warns() {
        // 第 1 章引入 A，同时声明前置 A → 自引警告
        val ch = chapter(1, listOf("A")).copy(
            contract = fullContract(introduced = listOf("A")),
            prerequisiteNodeTitles = listOf("A"),
        )
        val issues = RouteAudit.audit(
            chapters = listOf(ch),
            nodeTitlesByPrereqs = emptyMap(),
            allNodeTitles = setOf("A"),
            dailyMinutes = 30,
        )
        val selfRef = issues.firstOrNull { it.code == RouteAuditIssue.Code.PREREQUISITE_SELF_REF }!!
        assertEquals(RouteAuditIssue.Level.WARN, selfRef.level)
    }

    @Test
    fun prerequisiteAppearingInLaterChapter_warns() {
        // 第 2 章声明前置 B，但 B 首次出现在第 3 章 → 顺序倒挂
        val chapters = listOf(
            chapter(1, listOf("A")).copy(prerequisiteNodeTitles = listOf("B")),
            chapter(2, listOf("B")),
        )
        val issues = RouteAudit.audit(
            chapters = chapters,
            nodeTitlesByPrereqs = emptyMap(),
            allNodeTitles = setOf("A", "B"),
            dailyMinutes = 30,
        )
        // 第 1 章的前置 B 首次出现在第 2 章（晚于本章）
        val order = issues.firstOrNull { it.code == RouteAuditIssue.Code.PREREQUISITE_ORDER }!!
        assertEquals(1, order.chapterOrder)
    }

    @Test
    fun prerequisiteUnknownTitle_reportsUnresolved() {
        val ch = chapter(1, listOf("A")).copy(prerequisiteNodeTitles = listOf("不存在的概念"))
        val issues = RouteAudit.audit(
            chapters = listOf(ch),
            nodeTitlesByPrereqs = emptyMap(),
            allNodeTitles = setOf("A"),
            dailyMinutes = 30,
        )
        val unresolved = issues.firstOrNull { it.code == RouteAuditIssue.Code.PREREQUISITE_UNRESOLVED }!!
        assertEquals(RouteAuditIssue.Level.INFO, unresolved.level)
    }

    // ---- A.1 #9/#10：ContractCodec 状态区分（损坏 ≠ 空契约；老数据 ≠ 生成异常） ----

    @Test
    fun contractCodec_distinguishesValidNoneMissingAndCorrupt() {
        val valid = com.example.zhishu.ai.outputs.ContractCodec.decode(
            """{"entryQuestion":"q","knowledgeGap":"g"}""",
            "INTRODUCE",
        )
        assertTrue(valid is com.example.zhishu.ai.outputs.ContractCodec.State.Valid)
        assertEquals("q", (valid as com.example.zhishu.ai.outputs.ContractCodec.State.Valid).contract.entryQuestion)

        // 老数据：空串 + 无类型 → None
        assertTrue(
            com.example.zhishu.ai.outputs.ContractCodec.decode("", "") is
                com.example.zhishu.ai.outputs.ContractCodec.State.None,
        )

        // 声明了类型但契约缺失 → MissingAfterType
        assertTrue(
            com.example.zhishu.ai.outputs.ContractCodec.decode("", "APPLY") is
                com.example.zhishu.ai.outputs.ContractCodec.State.MissingAfterType,
        )

        // 损坏：非空但无法解析 → Corrupt，绝不回退成空契约
        val corrupt = com.example.zhishu.ai.outputs.ContractCodec.decode("{broken json", "INTRODUCE")
        assertTrue(corrupt is com.example.zhishu.ai.outputs.ContractCodec.State.Corrupt)
        assertTrue(com.example.zhishu.ai.outputs.ContractCodec.describe(corrupt).contains("契约不可用"))
        assertTrue(com.example.zhishu.ai.outputs.ContractCodec.describe(corrupt).contains("损坏"))
    }

    @Test
    fun contractCodec_encodeEmpty_returnsEmptyString() {
        assertEquals("", com.example.zhishu.ai.outputs.ContractCodec.encode(ChapterContractDto()))
        val nonEmpty = com.example.zhishu.ai.outputs.ContractCodec.encode(fullContract())
        assertTrue(nonEmpty.isNotBlank())
    }
}
