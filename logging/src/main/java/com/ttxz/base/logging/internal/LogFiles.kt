package com.ttxz.base.logging.internal

import com.ttxz.base.logging.LogConfig
import com.ttxz.base.logging.LogLevel
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
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
            val numbered = logs().filter { it.name.startsWith("$day-") }.sortedBy { fileIndex(it) }
            // 同时存在无序号与编号文件时，不覆盖已有分片；无序号文件接到末尾。
            var last = numbered.lastOrNull()
            if (plain.isFile) {
                if (last == null) last = plain else {
                    val target = shard(day, Math.addExact(fileIndex(last), 1L))
                    check(!target.exists() && plain.renameTo(target)) { "Cannot rename log shard" }
                    last = target
                }
            }
            // 进程可能在一次写入中被杀；追加前去掉没有换行结束的残缺尾记录。
            if (last != null) repairTail(last)
            val next = if (last == null) plain else if (last.length() < config.maxFileBytes && last.length() <= config.maxDirectoryBytes - bytes) last else {
                if (last == plain) {
                    val first = shard(day, 0L)
                    check(!first.exists() && plain.renameTo(first)) { "Cannot rename first log shard" }
                    shard(day, 1L)
                } else shard(day, Math.addExact(fileIndex(last), 1L))
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

    fun snapshot(destination: File) {
        ensureDirectory()
        check(destination.mkdirs() || destination.isDirectory) { "Cannot create snapshot directory" }
        logs().forEach {
            val copy = it.copyTo(File(destination, it.name))
            // 上次写入失败或进程骤停后，尚未重新打开的源文件可能有残尾。
            // 只修复副本，导出不修改诊断源文件。
            repairTail(copy)
        }
    }

    private fun shard(day: LocalDate, index: Long): File =
        File(directory, "$day-${index.toString().padStart(6, '0')}.log")

    private fun fileIndex(file: File): Long =
        file.name.removeSuffix(".log").substringAfterLast('-').toLongOrNull()
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
        private val LOG_FILE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}(?:-\\d{6,})?\\.log")
    }
}
