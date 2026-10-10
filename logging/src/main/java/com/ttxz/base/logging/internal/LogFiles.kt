package com.ttxz.base.logging.internal

import com.ttxz.base.logging.LogConfig
import com.ttxz.base.logging.LogLevel
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.FilterOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.time.Instant
import java.time.LocalDate

internal class LogRecordTooLargeException : IllegalStateException("Log record exceeds storage budget")

/** 仅由日志消费者访问。文件句柄、滚动和清理不与生产线程共享。 */
internal class LogFiles(private val directory: File, private val config: LogConfig, private val diagnostic: InternalLog = InternalLog(config.isDebugLog)) {
    private var stream: BufferedWriter? = null
    private var active: File? = null
    private var activeDay: LocalDate? = null
    private var size = 0L
    private var total = 0L
    private var archiveCursor: String? = null

    fun write(timestamp: Long, line: String) = write(timestamp, line, emptyList())

    fun write(timestamp: Long, prefix: String, bodyParts: List<String>, suffix: String = "", preserveLayout: Boolean = false) {
        val bytes = utf8Bytes(prefix) + escapedUtf8Bytes(bodyParts, preserveLayout) + utf8Bytes(suffix) + 1
        // 总目录预算仍是硬限制；过大记录不清理历史文件、不部分写入。
        if (bytes > config.maxDirectoryBytes) throw LogRecordTooLargeException()
        val day = Instant.ofEpochMilli(timestamp).atZone(config.zoneId).toLocalDate()
        if (stream == null || activeDay != day || size >= config.maxFileBytes || size > config.maxDirectoryBytes - bytes || active?.exists() != true) {
            close()
            ensureDirectory()
            val plain = File(directory, "$day.log")
            val numbered = logs().filter { it.name.startsWith("$day-") }
                .sortedWith(compareBy<File> { fileIndex(it) }.thenBy { it.name.endsWith(".gz") })
            // 同时存在无序号与编号文件时，不覆盖已有分片；无序号文件接到末尾。
            var last = numbered.lastOrNull()
            if (plain.isFile) {
                val archivedPlain = File(directory, plain.name + ".gz")
                if (archivedPlain.exists()) {
                    // 发布后删源失败：保留原名供维护验证删除，不能重命名后重复归档。
                    if (last == null) last = archivedPlain
                } else if (last == null) {
                    last = plain
                } else {
                    val target = shard(day, Math.addExact(fileIndex(last), 1L))
                    check(!target.exists() && plain.renameTo(target)) { "Cannot rename log shard" }
                    last = target
                }
            }
            // 进程可能在一次写入中被杀；追加前去掉没有换行结束的残缺尾记录。
            if (last != null && !last.name.endsWith(".gz")) repairTail(last)
            val next = if (last == null) {
                if (File(directory, "$day.log.gz").exists()) shard(day, 0L) else plain
            } else if (!last.name.endsWith(".gz") && last.length() < config.maxFileBytes && last.length() <= config.maxDirectoryBytes - bytes) last else {
                if (last == plain) {
                    val first = shard(day, 0L)
                    check(!first.exists() && plain.renameTo(first)) { "Cannot rename first log shard" }
                    shard(day, 1L)
                } else if (last.name == "$day.log.gz") shard(day, 0L) else shard(day, Math.addExact(fileIndex(last), 1L))
            }
            active = next
            activeDay = day
            size = next.length()
            cleanup(timestamp, bytes)
            stream = BufferedWriter(OutputStreamWriter(FileOutputStream(next, true), Charsets.UTF_8), 8192)
            diagnostic.write(LogLevel.DEBUG) { "日志文件已打开或滚动：${next.absolutePath}" }
        }
        if (total > config.maxDirectoryBytes - bytes) cleanup(timestamp, bytes)
        check(total <= config.maxDirectoryBytes - bytes) { "Log storage budget exhausted" }
        val current = stream!!
        current.write(prefix)
        writeEscaped(bodyParts, suffix = suffix, preserveLayout = preserveLayout) { current.write(it) }
        current.write("\n")
        size += bytes
        total += bytes
    }

    /** false 表示没有文件流，不可据此认定磁盘恢复。 */
    fun flush(): Boolean {
        val current = stream ?: return false
        current.flush()
        return true
    }

    fun close() {
        val previous = stream
        stream = null
        active = null
        activeDay = null
        previous?.close()
    }

    fun cleanup(now: Long, incoming: Long = 0) {
        ensureDirectory()
        removeArchiveTemps()
        // Buffered bytes aren't reflected in File.length until flush; include them in the budget.
        val files = logs().sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        total = files.fold(0L) { sum, file -> Math.addExact(sum, if (file == active) size else file.length()) }
        val cutoff = Instant.ofEpochMilli(now).atZone(config.zoneId).toLocalDate().minusDays(config.retentionDays.toLong() - 1)
        for (file in files) {
            if (file == active) continue
            val day = runCatching { LocalDate.parse(file.name.take(10)) }.getOrNull()
            if ((day != null && day < cutoff) || total > config.maxDirectoryBytes - incoming) {
                val length = file.length()
                check(file.delete()) { "Cannot delete old log file" }
                total -= length
                diagnostic.write(LogLevel.DEBUG) { "已清理过期或超预算日志文件：${file.name}，字节数：$length" }
            }
        }
    }

    /** 串行消费者每次最多处理一片；临时输出也受剩余预算限制，不覆盖已有归档。 */
    fun archiveOne(now: Long): Boolean {
        if (!directory.exists()) return false
        val today = Instant.ofEpochMilli(now).atZone(config.zoneId).toLocalDate()
        if (activeDay?.let { it < today } == true) close()
        cleanup(now)
        val files = logs()
        val candidates = files.filter { it != active && !it.name.endsWith(".gz") }
            .filter { runCatching { LocalDate.parse(it.name.take(10)) < today }.getOrDefault(false) }
            .sortedBy { it.name }
        val source = candidates.firstOrNull { archiveCursor?.let { cursor -> it.name > cursor } != false }
            ?: candidates.firstOrNull() ?: return false
        // 失败也前移游标，避免单个冲突或预算不足的文件永久阻塞其他分片；绕回后重试。
        archiveCursor = source.name
        val target = File(directory, source.name + ".gz")
        if (target.exists()) {
            // 发布后、删源文件前骤停：逐字节验证已有归档，不能仅凭文件名删除源文件。
            check(sameArchive(source, target)) { "Existing log archive differs from source" }
            check(source.delete()) { "Cannot delete archived source log" }
            total = logs().sumOf { if (it == active) size else it.length() }
            return true
        }
        total = files.sumOf { if (it == active) size else it.length() }
        val allowance = config.maxDirectoryBytes - total
        if (allowance < 20) return false
        val temporary = File(directory, source.name + ".gz.tmp")
        check(temporary.createNewFile()) { "Cannot create temporary log archive" }
        try {
            var remaining = allowance
            val limited = object : FilterOutputStream(FileOutputStream(temporary)) {
                override fun write(value: Int) {
                    if (remaining < 1) throw ArchiveBudgetExceededException()
                    out.write(value); remaining--
                }
                override fun write(buffer: ByteArray, offset: Int, count: Int) {
                    if (count.toLong() > remaining) throw ArchiveBudgetExceededException()
                    out.write(buffer, offset, count); remaining -= count
                }
            }
            limited.use { output ->
                GZIPOutputStream(output, 8192).use { zipped -> source.inputStream().use { it.copyTo(zipped, 8192) } }
            }
            // 同一消费者持有文件生命周期；校验关闭后的归档，成功发布后才允许删除源文件。
            check(sameArchive(source, temporary)) { "Log archive verification failed" }
            // 容量清理按修改时间排序；压缩不能把旧日志变成最新历史。
            check(temporary.setLastModified(source.lastModified())) { "Cannot preserve archived log age" }
            check(!target.exists() && temporary.renameTo(target)) { "Cannot publish log archive" }
            check(source.delete()) { "Cannot delete archived source log" }
            total = logs().sumOf { if (it == active) size else it.length() }
            diagnostic.write(LogLevel.DEBUG) { "历史日志压缩完成：${target.name}" }
            return true
        } catch (_: ArchiveBudgetExceededException) {
            return false
        } finally {
            if (temporary.exists()) check(temporary.delete()) { "Cannot delete temporary log archive" }
        }
    }

    private fun sameArchive(source: File, archive: File): Boolean = source.inputStream().use { original ->
        archive.inputStream().use { raw -> GZIPInputStream(raw, 8192).use { zipped ->
            val left = ByteArray(8192)
            val right = ByteArray(8192)
            while (true) {
                val count = original.read(left)
                if (count < 0) return@use zipped.read() == -1
                var read = 0
                while (read < count) {
                    val next = zipped.read(right, read, count - read)
                    if (next < 0) return@use false
                    read += next
                }
                for (index in 0 until count) if (left[index] != right[index]) return@use false
            }
            @Suppress("UNREACHABLE_CODE") false
        } }
    }

    private fun removeArchiveTemps() {
        // 没有并行压缩任务：该严格命名的临时文件只可能来自本进程上次中断。
        // 首次写入的清理也调用此处，防止遗留临时字节游离于目录预算之外。
        directory.listFiles()?.filter { it.isFile && it.name.matches(ARCHIVE_TEMP_PATTERN) }?.forEach {
            check(it.delete()) { "Cannot delete incomplete log archive" }
        } ?: error("Cannot list log directory")
    }

    private fun shard(day: LocalDate, index: Long): File =
        File(directory, "$day-${index.toString().padStart(6, '0')}.log")

    private fun fileIndex(file: File): Long =
        file.name.removeSuffix(".gz").removeSuffix(".log").substringAfterLast('-').toLongOrNull()
            ?: error("Invalid log file index")

    private fun ensureDirectory() {
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create log directory" }
    }

    private fun repairTail(file: File) {
        RandomAccessFile(file, "rw").use { target ->
            var end = target.length()
            if (end == 0L) return
            target.seek(end - 1)
            if (target.read() == '\n'.code) return
            val buffer = ByteArray(8192)
            while (end > 0) {
                val start = maxOf(0, end - buffer.size)
                val count = (end - start).toInt()
                target.seek(start)
                target.readFully(buffer, 0, count)
                for (index in count - 1 downTo 0) {
                    if (buffer[index] == '\n'.code.toByte()) {
                        target.setLength(start + index + 1)
                        return
                    }
                }
                end = start
            }
            target.setLength(0)
        }
    }

    private fun logs(): List<File> = directory.listFiles()?.filter {
        it.isFile && it.name.matches(LOG_FILE_PATTERN)
    } ?: error("Cannot list log directory")

    companion object {
        private val LOG_FILE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}(?:-\\d{6,})?\\.log(?:\\.gz)?")
        private val ARCHIVE_TEMP_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}(?:-\\d{6,})?\\.log\\.gz\\.tmp")
    }
}

private class ArchiveBudgetExceededException : IOException("No temporary archive budget")
