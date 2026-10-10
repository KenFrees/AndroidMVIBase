package com.ttxz.base.logging

import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 查询初始化目录内属于本库的日志文件，不读取正文、不创建目录、不刷新或修改文件。
 * @param date 文件名中的日期；null 查询全部，日期按初始化配置的时区生成。
 * @return 从新到旧排列的 .log 或 .log.gz 文件（日期降序、同日分片序号数值降序）。
 * 历史文件可能自动压缩，.gz 应由调用方使用 GZIPInputStream 流式读取。
 * 未归档的无序号文件按旧格式兼容视为最新；已归档的无序号文件及其遗留源文件排在编号分片之后。
 * 目录尚未创建时成功返回空列表；初始化停用、无目录、路径不是目录或无法列举时失败。
 * IO 在 Dispatchers.IO 执行，协程取消继续传播。返回的是实时文件引用，可能被追加、重命名或清理，
 * 不保证查询与上游读取之间的稳定性。需要时先 flush，再由宿主尽快读取或复制，处理 IO 异常。
 */
suspend fun LogInitialization.logFiles(date: LocalDate? = null): Result<List<File>> =
    queryLogFiles(this, date)

// 列举函数仅供内部确定性故障/取消测试注入，不增加公开 API 或配置项。
internal suspend fun queryLogFiles(
    initialization: LogInitialization,
    date: LocalDate?,
    listDirectory: (File) -> Array<File>? = { it.listFiles() },
): Result<List<File>> = withContext(Dispatchers.IO) {
    try {
        check(initialization.enabled) { "日志初始化未启用" }
        val root = File(initialization.directory ?: error("日志目录不可用"))
        if (!root.exists()) return@withContext Result.success(emptyList())
        if (!root.isDirectory) throw IOException("日志路径不是目录")
        val children = listDirectory(root) ?: throw IOException("无法列举日志目录")
        data class Entry(val file: File, val day: LocalDate, val index: Long?)
        val entries = children.mapNotNull { file ->
            ensureActive()
            if (!file.isFile) return@mapNotNull null
            val match = LOG_FILE_NAME.matchEntire(file.name) ?: return@mapNotNull null
            val day = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return@mapNotNull null
            if (date != null && day != date) return@mapNotNull null
            val suffix = match.groupValues[2]
            val index = if (suffix.isEmpty()) null else suffix.toLongOrNull() ?: return@mapNotNull null
            Entry(file, day, index)
        }
        val archivedPlainDays = entries.filter { it.index == null && it.file.name.endsWith(".gz") }
            .mapTo(mutableSetOf()) { it.day }
        Result.success(entries.sortedWith(compareByDescending<Entry> { it.day }
            .thenByDescending { it.index == null && it.day !in archivedPlainDays }.thenByDescending { it.index }
            .thenBy { it.file.name.endsWith(".gz") }).map { it.file })
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }
}

private val LOG_FILE_NAME = Regex("(\\d{4}-\\d{2}-\\d{2})(?:-(\\d{6,}))?\\.log(?:\\.gz)?")
