package com.example.zhishu.search

/**
 * 全文索引分词（P2）。
 *
 * SQLite FTS 的默认分词器（unicode61）把连续中文视为一个 token，
 * 因此「继承」无法命中「遗产继承纠纷」。FTS5 的 trigram 分词器要求 SQLite 3.34+，
 * 在 minSdk 26 上不可用。这里改为在索引侧存**字符二元组**：
 *
 *   遗产继承  →  "遗产 产继 继承"
 *
 * 查询时用同样规则生成二元组并作为**短语**匹配（要求位置连续），
 * 即可得到与 LIKE '%q%' 等价的子串语义，同时利用倒排索引避免逐行扫描。
 */
object SearchTokenizer {

    /** 索引侧：把原文转成空格分隔的二元组串 */
    fun indexBody(text: String): String {
        val normalized = normalize(text)
        if (normalized.length < 2) return normalized
        return buildString(normalized.length * 3) {
            for (i in 0 until normalized.length - 1) {
                if (isNotEmpty()) append(' ')
                append(normalized[i])
                append(normalized[i + 1])
            }
        }
    }

    /**
     * 查询侧：生成 FTS MATCH 表达式。
     * 多字查询用短语匹配保证二元组连续；单字查询退化为前缀匹配。
     * 无有效字符时返回 null，调用方应视为空结果。
     */
    fun matchQuery(query: String): String? {
        val normalized = normalize(query)
        return when {
            normalized.isEmpty() -> null
            normalized.length == 1 -> "${normalized}*"
            else -> {
                val bigrams = (0 until normalized.length - 1).map {
                    "${normalized[it]}${normalized[it + 1]}"
                }
                "\"${bigrams.joinToString(" ")}\""
            }
        }
    }

    /**
     * 归一化：小写化，丢弃空白与标点，只保留字母、数字与表意文字。
     * 去掉标点后「第1121条」与「第 1121 条」可互相命中。
     */
    private fun normalize(text: String): String = buildString(text.length) {
        text.lowercase().forEach { ch ->
            if (ch.isLetterOrDigit()) append(ch)
        }
    }
}
