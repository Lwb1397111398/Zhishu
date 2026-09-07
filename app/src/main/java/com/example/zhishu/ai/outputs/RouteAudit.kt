package com.example.zhishu.ai.outputs

/**
 * 讲述路线的**软审计**（第二轮 P1-5）。
 *
 * 硬校验（[OutputValidators.validateStoryBible]）负责「不能继续」的错误——
 * 引用不存在的节点、契约缺失、类型要求不满足。本审计负责「能继续但有风险」的提示：
 * 前置顺序、重复引入、范围覆盖、章节负荷、类型单一化、链的连续性。
 *
 * 与「一链一章」划清界限：拆链、合并链、回顾都是**合理的教学设计**，不报错；
 * 只检查拆开之后连续性是否被破坏（中间出现未解释的跳跃）。
 *
 * 前置关系的判定遵守「不做字符串猜测」（第二轮 P1-4）：
 * - 精确匹配当前节点标题 → 视为真实前置，可做顺序检查
 * - 匹配不到任何节点标题 → 记 PREREQUISITE_UNRESOLVED，不作硬断言
 * - 绝不从自由文本里推断关系
 */
data class RouteAuditIssue(
    val level: Level,
    val code: Code,
    /** 涉及章节的卷内序号；全局性问题的这一项为 null */
    val chapterOrder: Int? = null,
    val message: String,
) {
    enum class Level { WARN, INFO }
    enum class Code {
        /** 某章的前置节点出现在更晚的章节，或从未被安排 */
        PREREQUISITE_ORDER,
        /** 前置描述是自由文本，匹配不到任何节点标题 */
        PREREQUISITE_UNRESOLVED,
        /** 同一节点在多章被标记为「首次介绍」 */
        DUPLICATE_INTRODUCE,
        /** 地图上有节点没有被任何章节安排 */
        RANGE_GAP,
        /** 单章预计阅读时长超过用户每日目标 */
        LOAD_EXCEEDS_TARGET,
        /** 整卷章节类型单一 */
        TYPE_SINGLETON,
        /** 同一条地图链跨章时，中间出现了没有该链内容的章节（未解释跳跃） */
        CHAIN_DISCONTINUITY,
        /** 前置节点是本章自己首次引入的节点——新概念不可能同时是本章的前置 */
        PREREQUISITE_SELF_REF,
        /** 章节类型被程序按配额规则改动过（三段式：模型给的分布不合规） */
        TYPE_AUTO_ADJUSTED,
        /** 契约里的「引入/应用」归属被程序修正过（REVIEW 引入新节点、跨章重复引入等） */
        CONTRACT_AUTO_ADJUSTED,
    }
}

object RouteAudit {

    /**
     * 对路线做软审计。
     *
     * @param chapters 路线规划出的章节（已按 order 排序）
     * @param nodeTitlesByPrereqs 节点标题 → 该节点的前置描述列表（自由文本）
     * @param allNodeTitles 地图全部节点标题
     * @param chains 地图演进链（每条链的标题序列），用于连续性检查
     * @param dailyMinutes 用户每日目标时长（分钟），用于负荷检查
     */
    fun audit(
        chapters: List<ChapterPlanDto>,
        nodeTitlesByPrereqs: Map<String, List<String>>,
        allNodeTitles: Set<String>,
        chains: List<List<String>> = emptyList(),
        dailyMinutes: Int = 30,
    ): List<RouteAuditIssue> {
        val issues = mutableListOf<RouteAuditIssue>()
        val byOrder = chapters.associateBy { it.order }
        val titleToFirstChapter = mutableMapOf<String, Int>()
        chapters.forEach { ch ->
            ch.nodeTitles.map { it.trim() }.filter { it.isNotBlank() }.forEach { t ->
                if (t !in titleToFirstChapter) titleToFirstChapter[t] = ch.order
            }
        }

        // 1. 前置顺序：某章节点的前置（能精确匹配到标题的）不能出现在更晚的章节
        chapters.forEach { ch ->
            ch.nodeTitles.map { it.trim() }.filter { it.isNotBlank() }.forEach { title ->
                nodeTitlesByPrereqs[title].orEmpty().forEach { prereq ->
                    val clean = prereq.trim()
                    if (clean.isEmpty() || clean !in allNodeTitles) {
                        // 自由文本匹配不到节点：记录为待确认，不作硬断言
                        issues += RouteAuditIssue(
                            RouteAuditIssue.Level.INFO,
                            RouteAuditIssue.Code.PREREQUISITE_UNRESOLVED,
                            ch.order,
                            "第 ${ch.order} 章的「$title」提到前置「$clean」，它匹配不到任何知识点标题，请人工确认",
                        )
                        return@forEach
                    }
                    val prereqOrder = titleToFirstChapter[clean]
                    if (prereqOrder == null) {
                        issues += RouteAuditIssue(
                            RouteAuditIssue.Level.WARN,
                            RouteAuditIssue.Code.PREREQUISITE_ORDER,
                            ch.order,
                            "「$title」的前置「$clean」没有被任何章节安排，前置关系可能落空",
                        )
                    } else if (prereqOrder > ch.order) {
                        issues += RouteAuditIssue(
                            RouteAuditIssue.Level.WARN,
                            RouteAuditIssue.Code.PREREQUISITE_ORDER,
                            ch.order,
                            "「$title」的前置「$clean」被安排在第 $prereqOrder 章，晚于本章，前置关系倒挂",
                        )
                    }
                }
            }
        }

        // 1b. 规划输出的前置声明（ch.prerequisiteNodeTitles，批次 A.1 #8）：
        //     本章自己首次引入的节点不能是本章的前置；前置必须出现在本章或更早的章节
        chapters.forEach { ch ->
            ch.prerequisiteNodeTitles.map { it.trim() }.filter { it.isNotBlank() }.forEach { prereq ->
                if (prereq !in allNodeTitles) {
                    issues += RouteAuditIssue(
                        RouteAuditIssue.Level.INFO,
                        RouteAuditIssue.Code.PREREQUISITE_UNRESOLVED,
                        ch.order,
                        "第 ${ch.order} 章声明的前置「$prereq」匹配不到任何知识点标题，请人工确认",
                    )
                    return@forEach
                }
                val introducedHere = ch.contract.introducedNodeTitles
                    .map { it.trim() }
                    .any { it == prereq }
                if (introducedHere) {
                    issues += RouteAuditIssue(
                        RouteAuditIssue.Level.WARN,
                        RouteAuditIssue.Code.PREREQUISITE_SELF_REF,
                        ch.order,
                        "第 ${ch.order} 章把「$prereq」同时列为前置与首次引入——新概念不能是本章自己的前置",
                    )
                    return@forEach
                }
                val prereqOrder = titleToFirstChapter[prereq]
                if (prereqOrder == null || prereqOrder > ch.order) {
                    issues += RouteAuditIssue(
                        RouteAuditIssue.Level.WARN,
                        RouteAuditIssue.Code.PREREQUISITE_ORDER,
                        ch.order,
                        "第 ${ch.order} 章声明的前置「$prereq」" +
                            (if (prereqOrder == null) "没有被任何章节安排" else "首次出现在第 $prereqOrder 章（晚于本章）") +
                            "，前置关系倒挂",
                    )
                }
            }
        }

        // 2. 重复引入：同一节点在多章被标为首次介绍
        val introducedCount = mutableMapOf<String, MutableList<Int>>()
        chapters.forEach { ch ->
            ch.contract.introducedNodeTitles.map { it.trim() }.filter { it.isNotBlank() }.forEach { t ->
                introducedCount.getOrPut(t) { mutableListOf() } += ch.order
            }
        }
        introducedCount.forEach { (title, orders) ->
            if (orders.size > 1) {
                issues += RouteAuditIssue(
                    RouteAuditIssue.Level.WARN,
                    RouteAuditIssue.Code.DUPLICATE_INTRODUCE,
                    orders.first(),
                    "知识点「$title」被多章标为首次介绍（第 ${orders.joinToString("、")} 章），重复引入",
                )
            }
        }

        // 3. 范围覆盖：地图上没被安排的节点
        val scheduled = chapters.flatMap { it.nodeTitles.map(String::trim) }.toSet()
        val gaps = (allNodeTitles - scheduled).toList().sorted()
        if (gaps.isNotEmpty()) {
            issues += RouteAuditIssue(
                RouteAuditIssue.Level.INFO,
                RouteAuditIssue.Code.RANGE_GAP,
                null,
                "以下知识点没有被任何章节安排：${gaps.joinToString("、")}",
            )
        }

        // 4. 章节负荷：预计阅读时长超过每日目标（中文约 300 字/分钟，与 Project.wordTargetPerChapter 同口径）
        chapters.forEach { ch ->
            val minutes = ch.wordTarget / 300.0
            if (minutes > dailyMinutes) {
                issues += RouteAuditIssue(
                    RouteAuditIssue.Level.WARN,
                    RouteAuditIssue.Code.LOAD_EXCEEDS_TARGET,
                    ch.order,
                    "第 ${ch.order} 章目标 ${ch.wordTarget} 字，约需 ${minutes.toInt() + 1} 分钟，超过每日 $dailyMinutes 分钟目标",
                )
            }
        }

        // 5. 类型单一化
        val types = chapters.mapNotNull { ch ->
            ChapterType.entries.firstOrNull { it.name == ch.chapterType }
        }
        if (chapters.size >= 3 && types.isNotEmpty() && types.distinct().size == 1) {
            issues += RouteAuditIssue(
                RouteAuditIssue.Level.INFO,
                RouteAuditIssue.Code.TYPE_SINGLETON,
                null,
                "整卷都是「${types.first().name}」型章节，缺少类型变化（可考虑应用/辨析/回顾/测验章节）",
            )
        }

        // 6. 链的连续性：同一条链跨章时，中间不能出现完全没有该链内容的章节
        chains.forEach { chainTitles ->
            val relevant = chainTitles.filter { it in titleToFirstChapter }
            val orders = relevant.mapNotNull { titleToFirstChapter[it] }.distinct().sorted()
            if (orders.size >= 2) {
                for (i in 0 until orders.size - 1) {
                    val from = orders[i]
                    val to = orders[i + 1]
                    if (to - from > 1) {
                        val middle = (from + 1 until to).filter { byOrder[it] != null }
                        if (middle.isNotEmpty()) {
                            issues += RouteAuditIssue(
                                RouteAuditIssue.Level.INFO,
                                RouteAuditIssue.Code.CHAIN_DISCONTINUITY,
                                to,
                                "演进链「${chainTitles.firstOrNull().orEmpty()}…」在第 $from 章与第 $to 章之间跨过 ${middle.joinToString("、")} 章，"
                                    + "注意保持衔接（拆链可以，但不能出现未解释的跳跃）",
                            )
                        }
                    }
                }
            }
        }

        return issues.sortedWith(compareBy({ it.chapterOrder ?: Int.MAX_VALUE }, { it.code.name }))
    }
}
