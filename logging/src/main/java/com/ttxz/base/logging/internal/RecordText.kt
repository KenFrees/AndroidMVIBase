package com.ttxz.base.logging.internal

internal const val TRUNCATION_MARKER = " [truncated]"

/** 文本与是否发生省略；内部结果，不暴露调用者原文。 */
internal data class LimitedText(val text: String, val wasTruncated: Boolean)

/** 截断不拆开 UTF-16 代理对。 */
internal fun prefixLength(value: String, limit: Int): Int {
    var end = minOf(value.length, limit.coerceAtLeast(0))
    if (end > 0 && end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
    return end
}

/** 单遍有界转义；普通字符直接追加，避免为每个字符分配临时字符串。 */
private fun escape(value: String, limit: Int): LimitedText {
    val result = StringBuilder(minOf(value.length, limit))
    var index = 0
    while (index < value.length) {
        val char = value[index]
        val code = when (char) {
            '\n' -> 'n'
            '\r' -> 'r'
            '\t' -> 't'
            '\\', '[', ']' -> char
            else -> null
        }
        val pair = char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()
        val width = if (code != null || pair) 2 else 1
        if (result.length + width > limit) break
        when {
            code != null -> { result.append('\\'); result.append(code) }
            pair -> result.append(value, index, index + 2)
            char.isISOControl() || char == '\u2028' || char == '\u2029' || char.isSurrogate() -> result.append('?')
            else -> result.append(char)
        }
        index += if (pair) 2 else 1
    }
    return LimitedText(result.toString(), index < value.length)
}

/** 基础转义，无截断标记；用于需要原始长度限制语义的内部调用。 */
internal fun escaped(value: String, limit: Int): String = escape(value, limit).text

/** 最终输出截断统一追加标记；标记本身计入长度，且不截断转义序列或代理对。 */
internal fun escapedRecord(value: String, limit: Int, alreadyTruncated: Boolean = false): LimitedText {
    val full = escape(value, limit)
    if (!full.wasTruncated && !alreadyTruncated) return full
    val prefix = escape(value, limit - TRUNCATION_MARKER.length)
    return LimitedText(prefix.text + TRUNCATION_MARKER, true)
}

/** 有界分块转义完整正文；不创建与正文总长度相等的转义字符串或字节数组。 */
internal fun writeEscaped(parts: List<String>, chunkChars: Int = 4096, suffix: String = "", preserveLayout: Boolean = false, output: (String) -> Unit) {
    val buffer = StringBuilder(minOf(chunkChars.toLong(), parts.sumOf { it.length.toLong() }).toInt())
    var emitted = false
    for (value in parts) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val code = when (char) {
                '\n' -> if (preserveLayout) null else 'n'
                '\r' -> if (preserveLayout) null else 'r'
                '\t' -> if (preserveLayout) null else 't'
                '\\', '[', ']' -> char
                else -> null
            }
            val pair = char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()
            val width = if (code != null || pair) 2 else 1
            if (buffer.length + width > chunkChars) {
                output(buffer.toString()); buffer.setLength(0); emitted = true
            }
            when {
                code != null -> { buffer.append('\\'); buffer.append(code) }
                preserveLayout && char in "\n\r\t" -> buffer.append(char)
                pair -> buffer.append(value, index, index + 2)
                char.isISOControl() || char == '\u2028' || char == '\u2029' || char.isSurrogate() -> buffer.append('?')
                else -> buffer.append(char)
            }
            index += if (pair) 2 else 1
        }
    }
    if (suffix.isNotEmpty()) {
        if (buffer.length + suffix.length > chunkChars) {
            output(buffer.toString()); buffer.setLength(0); emitted = true
        }
        buffer.append(suffix)
    }
    if (buffer.isNotEmpty() || !emitted) output(buffer.toString())
}

/** UTF-8 实际字节数，非法单独代理字符按编码器默认的 '?' 计数；无编码数组分配。 */
internal fun utf8Bytes(value: String): Long {
    var count = 0L
    var index = 0
    while (index < value.length) {
        val char = value[index]
        count += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> { index++; 4 }
            char.isSurrogate() -> 1
            else -> 3
        }
        index++
    }
    return count
}

/** 与 writeEscaped 相同规则的字节预估，用于写入前检查预算，不生成转义副本。 */
internal fun escapedUtf8Bytes(parts: List<String>, preserveLayout: Boolean = false): Long {
    var count = 0L
    for (value in parts) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            count += when {
                preserveLayout && char in "\n\r\t" -> 1
                char == '\n' || char == '\r' || char == '\t' || char == '\\' || char == '[' || char == ']' -> 2
                char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> { index++; 4 }
                char.isISOControl() || char == '\u2028' || char == '\u2029' || char.isSurrogate() -> 1
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                else -> 3
            }
            index++
        }
    }
    return count
}
