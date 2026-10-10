package com.ttxz.base.demo.logging

import com.ttxz.base.logging.LogLevel
import com.ttxz.base.logging.Logging
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 宿主接入检查；只通过公开 API 写入、刷新和查询，按块读取文件，避免加载整个文件。 */
internal suspend fun checkLogIntegration(): String {
    val logger = Logging.getLogger("LogDemoCheck")
    val lines = mutableListOf("初始化：${if (Logging.initialization?.enabled == true) "已启用" else "未启用"}",
        "同名 Logger 缓存：${logger === Logging.getLogger("LogDemoCheck")}")
    val id = UUID.randomUUID().toString()
    val expected = mutableListOf<String>()
    var enabledRecordsAccepted = true
    for (level in LogLevel.entries) {
        val token = "$id-${level.name}"
        val enabled = logger.isEnabled(level)
        val error = IllegalStateException("$token-stack")
        val accepted = when (level) {
            LogLevel.DEBUG -> logger.debug(token, error)
            LogLevel.INFO -> logger.info(token, error)
            LogLevel.WARN -> logger.warn(token, error)
            LogLevel.ERROR -> logger.error(token, error)
        }
        lines += "${level.name}：启用=$enabled，接受=$accepted"
        if (enabled && !accepted) enabledRecordsAccepted = false
        if (accepted) {
            // 正文必须位于固定 TAG 之后，不能仅凭异常 message 中的相同标识判定正文存在。
            expected += "[LogDemoCheck] $token\n"
            expected += "java.lang.IllegalStateException: $token-stack\n\tat "
        }
    }
    val flushed = Logging.flush().isSuccessful
    lines += "刷新：$flushed"
    val files = Logging.logFiles().getOrThrow()
    val found = withContext(Dispatchers.IO) {
        val remaining = expected.toMutableSet()
        val buffer = CharArray(4096)
        for (file in files) {
            currentCoroutineContext().ensureActive()
            if (remaining.isEmpty()) break
            file.bufferedReader().use { reader ->
                var tail = ""
                while (remaining.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val chunk = tail + String(buffer, 0, count)
                    remaining.removeAll { chunk.contains(it) }
                    tail = chunk.takeLast(512)
                }
            }
        }
        expected.size - remaining.size
    }
    lines += "已启用等级全部接受：$enabledRecordsAccepted"
    lines += "文件正文及多行堆栈核对：$found/${expected.size}"
    lines += if (enabledRecordsAccepted && flushed && expected.isNotEmpty() && found == expected.size) "本次文件核对通过" else "本次未通过，请检查配置、拒绝情况和磁盘状态"
    return lines.joinToString("\n")
}

/** 预览最新文件开头，最多读取 8192 个字符；文件可能在查询后被清理，由调用方展示 IO 失败。 */
internal suspend fun previewLogFile(file: File): String = withContext(Dispatchers.IO) {
    val buffer = CharArray(8192)
    val content = file.bufferedReader().use { reader ->
        var size = 0
        while (size < buffer.size) {
            currentCoroutineContext().ensureActive()
            val count = reader.read(buffer, size, buffer.size - size)
            if (count < 0) break
            size += count
        }
        String(buffer, 0, size)
    }
    "${file.absolutePath}\n（仅预览开头，最多 8192 字符）\n$content"
}

/** 仅用于当前示例默认配置：写入约 3 MiB，触发 2 MiB 分片和过期清理，不修改日志库配置。 */
internal suspend fun checkLogLifecycle(): String = withContext(Dispatchers.IO) {
    val initialization = checkNotNull(Logging.initialization) { "日志尚未初始化" }
    check(initialization.enabled) { "日志未启用" }
    check(Logging.flush().isSuccessful) { "检查前刷新失败" }
    val directory = File(checkNotNull(initialization.directory))
    check(directory.mkdirs() || directory.isDirectory) { "无法创建日志目录" }
    val expired = File(directory, "2000-01-01-${System.currentTimeMillis()}.log")
    check(expired.createNewFile()) { "过期测试文件已存在，未覆盖" }
    try {
        expired.writeText("LogLifecycle expired fixture\n")
        val id = UUID.randomUUID().toString()
        val logger = Logging.getLogger("LogLifecycle")
        val payload = "x".repeat(4096)
        repeat(768) { index ->
            currentCoroutineContext().ensureActive()
            check(logger.info("$id record=$index $payload")) { "测试记录 $index 被拒绝" }
            if ((index + 1) % 32 == 0) check(Logging.flush().isSuccessful) { "分批刷新失败" }
        }
        check(Logging.flush().isSuccessful) { "最终刷新失败" }
        val files = Logging.logFiles().getOrThrow()
        val seen = BooleanArray(768)
        val matchedFiles = mutableSetOf<String>()
        val pattern = Regex(Regex.escape("[LogLifecycle] $id record=") + "(\\d+) ")
        val buffer = CharArray(4096)
        for (file in files) {
            file.bufferedReader().use { reader ->
                var tail = ""
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    val chunk = tail + String(buffer, 0, count)
                    pattern.findAll(chunk).forEach { match ->
                        // 只处理本次新读入部分结束的匹配，避免尾部重叠造成重复计数。
                        if (match.range.last >= tail.length) {
                            val index = match.groupValues[1].toInt()
                            check(index in seen.indices && !seen[index]) { "记录编号错误或重复" }
                            seen[index] = true
                            matchedFiles += file.name
                        }
                    }
                    tail = chunk.takeLast(128)
                }
            }
        }
        check(seen.all { it }) { "测试记录缺失" }
        check(matchedFiles.size >= 2) { "测试记录未跨分片" }
        check(!expired.exists()) { "过期测试文件未清理" }
        "分片与清理检查通过\n标识：$id\n记录：768/768，分片：${matchedFiles.size}\n过期测试文件：已清理\n${matchedFiles.joinToString("\n")}"
    } finally {
        // 仅清理由本次 createNewFile 成功创建的夹具，绝不删除其他日志。
        if (expired.exists()) expired.delete()
    }
}
