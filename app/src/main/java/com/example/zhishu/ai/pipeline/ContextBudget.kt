package com.example.zhishu.ai.pipeline

import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.StoryBible

/**
 * 单章生成的上下文预算（问题5）。
 *
 * 用户的顾虑是对的：如果每生成一章都把前面所有正文重新喂给模型，
 * 到第 100 章时单章成本会是第 1 章的几十倍，几百章下来完全不可接受。
 *
 * 这里的做法是让上下文**恒定**：无论已经生成了 3 章还是 300 章，
 * 喂给模型的前情永远只有下面这几段，且每段都有字数上限：
 *
 * | 内容 | 上限 | 随章数增长？ |
 * |---|---|---|
 * | 世界观 world | 400 | 否，一次生成后固定 |
 * | 人物档案 | 6 行 | 否 |
 * | 全书主线 throughLine | 12 行 | 否 |
 * | 累积状态 runningState | 700 | 否，每章**改写**而不是追加 |
 * | 上一章 carryOver | 200 | 否，只取最近一章 |
 * | 已讲知识点标题 | 60 个 | 否，超出后只留最近的 |
 * | 未回收悬念 | 6 条 | 否 |
 *
 * 关键在 runningState：模型每章都被要求把它**改写**成新版本而不是追加，
 * 于是它像一份始终保持在 700 字的会议纪要——旧细节被压掉，
 * 后续章节仍需要的信息被保留。故事因此连得上，成本却不涨。
 */
internal object ContextBudget {

    const val WORLD_LIMIT = StoryBible.WORLD_BUDGET
    const val RUNNING_STATE_LIMIT = StoryBible.RUNNING_BUDGET
    const val CARRY_OVER_LIMIT = 200
    const val CHARACTER_LINES = 6
    const val THROUGH_LINE_LIMIT = 12
    const val COVERED_TITLE_LIMIT = 60
    const val OPEN_THREAD_LIMIT = StoryBible.MAX_OPEN_THREADS

    /** 按字符数截断，保留开头。超出时补省略号，让模型知道内容被裁过。 */
    fun clamp(text: String, limit: Int): String {
        val t = text.trim()
        return if (t.length <= limit) t else t.take(limit).trimEnd() + "…"
    }

    /** 空值兜底：模型看到空字符串容易自行编造，明确写「暂无」更稳。 */
    fun orNone(text: String): String = text.trim().ifBlank { "（暂无）" }

    fun formatCharacters(lines: List<String>): String =
        lines.take(CHARACTER_LINES).joinToString("\n") { "- $it" }.ifBlank { "（暂无）" }

    fun formatThroughLine(lines: List<String>): String =
        lines.take(THROUGH_LINE_LIMIT).mapIndexed { i, s -> "${i + 1}. $s" }
            .joinToString("\n").ifBlank { "（暂无）" }

    fun formatList(items: List<String>, limit: Int): String {
        if (items.isEmpty()) return "（暂无）"
        val kept = if (items.size <= limit) items else items.takeLast(limit)
        return kept.joinToString("、")
    }

    /**
     * 已讲知识点台账。只保留最近 [COVERED_TITLE_LIMIT] 个：
     * 更早的内容读者早已消化，模型不需要再被提醒「别重复讲」。
     */
    fun formatCovered(titles: List<String>): String = formatList(titles, COVERED_TITLE_LIMIT)

    /** 把新讲到的标题并入台账，去重并裁到上限。 */
    fun mergeCovered(existing: List<String>, added: List<String>): List<String> {
        val merged = LinkedHashSet(existing)
        added.map { it.trim() }.filter { it.isNotEmpty() }.forEach { merged += it }
        val list = merged.toList()
        return if (list.size <= COVERED_TITLE_LIMIT * 2) list else list.takeLast(COVERED_TITLE_LIMIT * 2)
    }

    /** 悬念钩子：本章回收掉的移除，新埋的加入，总数受限。 */
    fun mergeThreads(existing: List<String>, added: List<String>, resolved: List<String>): List<String> {
        val resolvedSet = resolved.map { it.trim() }.toSet()
        val kept = existing.filter { it.trim() !in resolvedSet }
        val merged = LinkedHashSet(kept)
        added.map { it.trim() }.filter { it.isNotEmpty() }.forEach { merged += it }
        return merged.toList().takeLast(OPEN_THREAD_LIMIT)
    }

    /**
     * 上一章的处境。优先用模型写的 [ChapterPlan.carryOver]（自足、150 字内），
     * 退回 summary，都没有就说明这是第一章。
     */
    fun carryOverOf(previous: ChapterPlan?): String {
        if (previous == null) return "这是第一章，还没有前情。"
        val text = previous.carryOver.ifBlank { previous.summary }
        return clamp(text, CARRY_OVER_LIMIT).ifBlank { "上一章尚无摘要。" }
    }
}
