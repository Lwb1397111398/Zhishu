package com.example.zhishu.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
fun <T> jsonDecoder(serializer: KSerializer<T>): JsonDecoder<T> = object : JsonDecoder<T> {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun decode(text: String): T {
        val payload = extractJsonPayload(text)
        return try {
            json.decodeFromString(serializer, payload)
        } catch (e: Exception) {
            // 输出被 max_tokens 截断是解码失败的大头。先本地剪裁补全（免费、即时），
            // 救不回来才向上抛——上层那次「把损坏 JSON 全文重发」的模型修复是按 token 计费的
            val repaired = repairTruncatedJson(
                payload,
                rollbackIncompleteArrayObject =
                    serializer.descriptor.serialName.endsWith("StoryBibleOutput"),
            ) ?: throw e
            json.decodeFromString(serializer, repaired)
        }
    }
}

/**
 * 从模型原文中提取 JSON 本体。
 *
 * 模型经常把 JSON 包在 ```json ... ``` 围栏里，或在前后夹解释文字，
 * 直接解析会在 offset 0 处失败（且白费一次 JSON 修复调用）。
 * 解析顺序：先剥代码围栏；仍不以 { 开头时退回首个 { 到最后一个 }。
 */
internal fun extractJsonPayload(text: String): String {
    val trimmed = text.trim()
    if (trimmed.startsWith("`")) {
        val firstLineEnd = trimmed.indexOf('\n')
        val body = if (firstLineEnd >= 0) trimmed.substring(firstLineEnd + 1) else trimmed
        val fenceEnd = body.lastIndexOf("```")
        val candidate = (if (fenceEnd >= 0) body.substring(0, fenceEnd) else body).trim()
        if (candidate.startsWith("{")) return candidate
    }
    if (trimmed.startsWith("{")) return trimmed
    val first = trimmed.indexOf('{')
    val last = trimmed.lastIndexOf('}')
    return if (first >= 0 && last > first) trimmed.substring(first, last + 1) else trimmed
}

/**
 * 本地修复被截断的 JSON：剪掉最后一个不完整的成员，补齐未闭合的括号。
 *
 * 大 JSON（单链展开动辄几 KB）在输出长度上限处被切断是解码失败的主要来源，
 * 把残文重发给模型修复既慢又翻倍计费。绝大多数截断只坏在「最后一个成员」——
 * 前面所有完整成员都能救回来（少一个节点好过整链失败重跑）。
 *
 * 用一个「期待什么」的扫描状态机找出最后一个安全点：到那里为止是若干完整成员，
 * 从那里剪断、按括号栈补齐闭合。键名后、冒号后、字符串中间截断都不算安全点，
 * 会回退到上一个完整成员；救不了（结构已坏而非截断、或第一个成员就断了）返回 null。
 */
internal fun repairTruncatedJson(
    payload: String,
    rollbackIncompleteArrayObject: Boolean = false,
): String? {
    if (!payload.startsWith("{")) return null
    val stack = ArrayDeque<Char>()
    stack.addLast('{') // 位置 0 的 '{'
    var lastSafe = -1 // exclusive：[0, lastSafe) 是若干完整成员的合法前缀
    // key = 数组位于括号栈中的深度；value = 该数组最后一个完整对象元素的结尾。
    // 数组对象被截断时，必须回退到这里，而不是对象内部最后一个完整字段。
    val completeArrayElements = mutableMapOf<Int, Int>()
    var state = State.EXPECT_KEY_OR_END
    var inString = false
    var escaped = false
    var i = 1
    while (i < payload.length) {
        val c = payload[i]
        when {
            escaped -> escaped = false
            inString -> when (c) {
                '\\' -> escaped = true
                '"' -> {
                    inString = false
                    if (state == State.EXPECT_VALUE) {
                        // 字符串值刚完整结束
                        state = State.AFTER_VALUE
                        lastSafe = i + 1
                    } else {
                        // 键名刚结束，还欠一个冒号和值——不算安全点
                        state = State.EXPECT_COLON
                    }
                }
            }
            else -> when {
                c == '"' && (state == State.EXPECT_VALUE || state == State.EXPECT_KEY_OR_END) -> inString = true
                (c == '{' || c == '[') && (state == State.EXPECT_VALUE || state == State.EXPECT_KEY_OR_END) -> {
                    stack.addLast(c)
                    state = if (c == '{') State.EXPECT_KEY_OR_END else State.EXPECT_VALUE
                }
                (c == '{' || c == '[') && state == State.AFTER_VALUE -> return null // 该出分隔符的地方出了值
                // 空容器（[] / {}）：刚压栈就立刻闭合，此时状态还停在
                // EXPECT_VALUE（数组内）或 EXPECT_KEY_OR_END（对象内），
                // 不能落到下面「非法字符」的分支里。
                //
                // 这是本函数长期失效的原因：单链输出里 sourceRefs / examples /
                // confusions 等空数组几乎必然出现（无资料模式 sourceRefs 恒为 []），
                // 于是任何一次截断都在第一个 [] 处判定「坏 JSON」返回 null，
                // 本地剪裁形同不存在，全部落到按 token 计费的模型修复上——
                // 而修复请求要求重新输出完整 JSON，同样被截断，整条链必然失败。
                c == ']' && state == State.EXPECT_VALUE && stack.lastOrNull() == '[' -> {
                    stack.removeLast()
                    state = State.AFTER_VALUE
                    lastSafe = i + 1
                }
                c == '}' && state == State.EXPECT_KEY_OR_END && stack.lastOrNull() == '{' -> {
                    stack.removeLast()
                    state = State.AFTER_VALUE
                    lastSafe = i + 1
                }
                c == ':' && state == State.EXPECT_COLON -> state = State.EXPECT_VALUE
                (c.isDigit() || c == '-' || c.isLetter()) && state == State.EXPECT_VALUE -> state = State.IN_SCALAR
                (c.isDigit() || c.isLetter()) && state == State.IN_SCALAR -> Unit
                c == ',' && (state == State.AFTER_VALUE || state == State.IN_SCALAR) -> {
                    // 逗号后回到「期待键（对象内）或值（数组内）」
                    state = if (stack.lastOrNull() == '{') State.EXPECT_KEY_OR_END else State.EXPECT_VALUE
                    lastSafe = i
                }
                (c == '}' || c == ']') && (state == State.AFTER_VALUE || state == State.IN_SCALAR) -> {
                    val open = if (c == '}') '{' else '['
                    if (stack.isEmpty() || stack.removeLast() != open) return null
                    state = State.AFTER_VALUE
                    lastSafe = i + 1
                    if (open == '{' && stack.lastOrNull() == '[') {
                        completeArrayElements[stack.size] = i + 1
                    }
                }
                c.isWhitespace() -> Unit
                else -> return null // 该处的字符与期待不符，是坏 JSON 而非简单截断
            }
        }
        i++
    }
    // StoryBible 的截断可能发生在末章的嵌套 contract 内。只要当前仍位于某个
    // “对象数组”里，就回退到该数组上一个完整对象，而不是保留残缺末章。
    val openArraySafePoint = if (rollbackIncompleteArrayObject) {
        completeArrayElements.entries
            .filter { (depth, _) -> stack.elementAtOrNull(depth - 1) == '[' }
            .maxByOrNull { it.key }
            ?.value
    } else {
        null
    }
    val cutEnd = when {
        openArraySafePoint != null -> openArraySafePoint
        inString || escaped -> lastSafe
        state == State.AFTER_VALUE || state == State.IN_SCALAR -> payload.length
        else -> lastSafe
    }
    if (stack.isEmpty()) return null // 括号都闭合了，不是截断问题
    if (cutEnd <= 0) return null // 第一个成员就断了，剪无可剪
    val cut = payload.substring(0, cutEnd).trimEnd()
    val reopen = ArrayDeque<Char>()
    var str = false
    var esc = false
    for (ch in cut) {
        when {
            esc -> esc = false
            str -> when (ch) {
                '\\' -> esc = true
                '"' -> str = false
            }
            ch == '"' -> str = true
            ch == '{' || ch == '[' -> reopen.addLast(ch)
            ch == '}' || ch == ']' -> reopen.removeLastOrNull()
        }
    }
    if (str || reopen.isEmpty()) return null
    // 按开括号逆序补对应闭括号（栈顶先闭）
    return cut + reopen.reversed().joinToString("") { if (it == '{') "}" else "]" }
}

private enum class State {
    EXPECT_KEY_OR_END,
    EXPECT_COLON,
    EXPECT_VALUE,
    IN_SCALAR,
    AFTER_VALUE,
}
