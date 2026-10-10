package com.ttxz.base.logging

import android.content.Context
import android.util.Log
import com.ttxz.base.logging.internal.InternalLog
import com.ttxz.base.logging.internal.LogRecordTooLargeException
import com.ttxz.base.logging.internal.LogFiles
import com.ttxz.base.logging.internal.escapedRecord
import com.ttxz.base.logging.internal.prefixLength
import com.ttxz.base.logging.internal.writeEscaped
import com.ttxz.base.logging.internal.TRUNCATION_MARKER
import com.ttxz.base.logging.internal.FileRecovery
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 应用级诊断日志。普通记录调用无磁盘 IO、不等待队列空间；返回 true 仅表示接受。
 * 文件行固定为「日期时间 [TAG] 内容」，字段和异常只能出现在内容区域。
 * 每个目录只能有一个实例/进程；宿主在应用生命周期内复用实例，不随页面创建。
 */
internal class LogEngine(
    private val directory: File,
    requestedConfig: LogConfig,
    private val recovery: FileRecovery = FileRecovery(),
    private val diagnostic: InternalLog = InternalLog(requestedConfig.isDebugLog),
    private val logcat: (LogLevel, String, String) -> Unit,
) {
    /** 配置问题字段名；日期格式问题回退默认，其余配置问题停用实例。 */
    val configurationWarnings: List<String> = requestedConfig.validationWarnings()
    /** 配置安全校验通过才启用；停用实例拒绝记录，不访问日志磁盘。 */
    val isEnabled: Boolean = configurationWarnings.none { it != "datePattern" }
    private val config: LogConfig = if (!isEnabled) LogConfig() else if (configurationWarnings.isNotEmpty()) {
        requestedConfig.copy(datePattern = LogConfig().datePattern)
    } else requestedConfig

    private sealed interface Command {
        data class Record(val time: Long, val level: LogLevel, val tag: String, val body: String, val exceptionText: String, val wasTruncated: Boolean) : Command
        data class Flush(val result: CompletableDeferred<FlushResult>) : Command
        data class Stop(val result: CompletableDeferred<FlushResult>) : Command
        data object Tick : Command
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Unlimited command channel is bounded by permits for records, one control caller, and one tick.
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val inOutput = ThreadLocal<Boolean>()
    private val slots = Semaphore(config.queueCapacity)
    private val gate = Any()
    private val controls = Mutex()
    private val accepted = AtomicLong()
    private val dropped = AtomicLong()
    private val truncated = AtomicLong()
    private val written = AtomicLong()
    private val failed = AtomicLong()
    @Volatile private var lastError: String? = null
    @Volatile private var closing = false
    private var tickPending = false
    private val stopped = CompletableDeferred<FlushResult>()
    private val files = LogFiles(directory, config, diagnostic)
    private val formatter = DateTimeFormatter.ofPattern(config.datePattern, Locale.ROOT).withZone(config.zoneId)
    private val inPreparation = ThreadLocal<Boolean>()
    private val maintenanceStartNanos = System.nanoTime()

    init {
        // Check actual formatting now, including unsupported combinations of otherwise valid fields.
        diagnostic.write(LogLevel.DEBUG) { "配置校验完成：${configurationWarnings.ifEmpty { listOf("通过") }}" }
        diagnostic.write(LogLevel.DEBUG) { "已选择日志目录：${directory.absolutePath}；队列容量：${config.queueCapacity}；文件最低等级：${config.fileMinLevel}；业务控制台最低等级：${config.logcatMinLevel}" }
        formatter.format(Instant.EPOCH)
        diagnostic.write(LogLevel.DEBUG) { "日期格式准备完成：${config.datePattern}；后台消费者启动，日志启用状态：$isEnabled" }
        scope.launch { consume() }
        if (isEnabled) scope.launch {
            var lastFlush = maintenanceStartNanos
            var lastCleanup = lastFlush
            while (true) {
                val before = System.nanoTime()
                val untilFlush = config.flushIntervalMillis - (before - lastFlush) / 1_000_000
                val untilCleanup = CLEANUP_INTERVAL_MILLIS - (before - lastCleanup) / 1_000_000
                // 唤醒时间取两个周期的下一截止点；例如刷新 90 分钟时仍在第 60 分钟清理。
                delay(minOf(untilFlush, untilCleanup).coerceAtLeast(1))
                val now = System.nanoTime()
                if ((now - lastFlush) / 1_000_000 >= config.flushIntervalMillis) {
                    lastFlush += ((now - lastFlush) / 1_000_000 / config.flushIntervalMillis) * config.flushIntervalMillis * 1_000_000
                }
                if ((now - lastCleanup) / 1_000_000 >= CLEANUP_INTERVAL_MILLIS) {
                    lastCleanup += ((now - lastCleanup) / 1_000_000 / CLEANUP_INTERVAL_MILLIS) * CLEANUP_INTERVAL_MILLIS * 1_000_000
                }
                synchronized(gate) {
                    if (closing) return@launch
                    if (!tickPending) { tickPending = true; commands.trySend(Command.Tick) }
                }
            }
        }
    }

    /** 记录调试日志；正文不截断，返回是否被接受。 */
    fun debug(tag: String, message: String): Boolean = record(LogLevel.DEBUG, tag, message, null)
    /** 记录关键过程；不会将等级插入固定前缀。 */
    fun info(tag: String, message: String): Boolean = record(LogLevel.INFO, tag, message, null)
    /** 记录警告。 */
    fun warn(tag: String, message: String): Boolean = record(LogLevel.WARN, tag, message, null)
    /** 记录错误；异常最多取 4 层原因，每层 16 帧，不调用 Throwable.toString/printStackTrace。 */
    fun error(tag: String, message: String, throwable: Throwable? = null): Boolean = record(LogLevel.ERROR, tag, message, throwable)

    internal fun isLoggable(level: LogLevel): Boolean = isEnabled && !closing &&
        (level >= config.fileMinLevel || (config.logcatMinLevel != null && level >= config.logcatMinLevel))

    /** 获取无 IO 的累计状态快照；各计数独立读取，不是事务快照。 */
    fun status(): LogStatus = LogStatus(accepted.get(), dropped.get(), truncated.get(), written.get(), failed.get(), lastError)

    internal fun record(level: LogLevel, tag: String, message: String, throwable: Throwable?): Boolean =
        submit(level, tag, message, throwable, null)

    internal fun recordLazy(level: LogLevel, tag: String, throwable: Throwable?, message: () -> String): Boolean =
        submit(level, tag, null, throwable, message)

    // 所有记录先取得槽位，再构造正文/异常；lambda 递归也会被拒绝。
    private fun submit(level: LogLevel, tag: String, message: String?, throwable: Throwable?, producer: (() -> String)?): Boolean {
        if (inOutput.get() == true || inPreparation.get() == true) { dropped.incrementAndGet(); return false }
        if (!isEnabled || closing) { dropped.incrementAndGet(); return false }
        if (!isLoggable(level)) return false
        if (level < LogLevel.WARN && slots.availablePermits() < (config.discardingThreshold ?: (config.queueCapacity / 5))) {
            dropped.incrementAndGet(); return false
        }
        if (!slots.tryAcquire()) { dropped.incrementAndGet(); return false }
        var ownsPermit = true
        try {
            inPreparation.set(true)
            val time = System.currentTimeMillis()
            val supplied = message ?: producer!!.invoke()
            val text = StringBuilder()
            val exceptionLimit = 4096
            var wasTruncated = false
            fun append(value: String, limit: Int = Int.MAX_VALUE) {
                val length = prefixLength(value, minOf(limit, exceptionLimit - text.length))
                text.append(value, 0, length)
                if (length < value.length) wasTruncated = true
            }
            var cause = throwable
            var depth = 0
            while (cause != null && depth++ < 4 && text.length < exceptionLimit) {
                append(if (depth == 1) "\n" else "\nCaused by: ")
                append(cause.javaClass.name)
                cause.message?.let { append(": "); append(it, 512) }
                // Android/Java getter 会复制完整数组；只遍历/保留前 16 帧，临时分配另行说明。
                val frames = cause.stackTrace
                for (index in 0 until minOf(frames.size, 16)) {
                    if (text.length >= exceptionLimit) { wasTruncated = true; break }
                    val frame = frames[index]
                    append("\n\tat "); append(frame.className); append("."); append(frame.methodName); append("(")
                    when {
                        frame.isNativeMethod -> append("Native Method")
                        frame.fileName != null -> { append(frame.fileName); if (frame.lineNumber >= 0) append(":${frame.lineNumber}") }
                        else -> append("Unknown Source")
                    }
                    append(")")
                }
                if (frames.size > 16) wasTruncated = true
                cause = cause.cause
            }
            if (cause != null) wasTruncated = true
            val safeTag = escapedRecord(tag, 128)
            wasTruncated = wasTruncated || safeTag.wasTruncated
            synchronized(gate) {
                if (closing) { dropped.incrementAndGet(); return false }
                val success = commands.trySend(Command.Record(time, level, safeTag.text, supplied, text.toString(), wasTruncated)).isSuccess
                if (success) {
                    ownsPermit = false
                    accepted.incrementAndGet()
                    if (wasTruncated) truncated.incrementAndGet()
                    return true
                }
                dropped.incrementAndGet()
                return false
            }
        } catch (error: Exception) {
            dropped.incrementAndGet()
            report("准备日志正文或异常快照", error)
            return false
        } finally {
            inPreparation.remove()
            if (ownsPermit) slots.release()
        }
    }

    /** 等待此前接受记录刷新到操作系统；可取消等待，不取消共享写入。不做 fsync。 */
    suspend fun flush(): FlushResult = if (inOutput.get() == true || inPreparation.get() == true) {
        FlushResult(false, status())
    } else controls.withLock {
        val result = CompletableDeferred<FlushResult>()
        val isClosing = synchronized(gate) {
            if (closing) true else { commands.trySend(Command.Flush(result)); false }
        }
        val completed = withContext(NonCancellable) { if (isClosing) stopped.await() else result.await() }
        currentCoroutineContext().ensureActive()
        completed
    }

    /** 关闭后拒绝记录，排空已接受记录并释放句柄；重复调用等待同一次关闭。 */
    internal fun requestClose() {
        synchronized(gate) {
            if (!closing) { closing = true; commands.trySend(Command.Stop(stopped)) }
        }
    }

    suspend fun close(): FlushResult = if (inOutput.get() == true || inPreparation.get() == true) {
        FlushResult(false, status())
    } else controls.withLock {
        requestClose()
        stopped.await()
    }

    private suspend fun consume() {
        var lastFlush = maintenanceStartNanos
        var lastCleanup = lastFlush
        var lastArchive = lastFlush - 60_000_000_000L
        var archiveIntervalMillis = 1000L
        for (command in commands) {
            when (command) {
                is Command.Record -> {
                    try {
                        inOutput.set(true)
                        // 普通正文沿用原字符串；仅异常存在时在后台组合，保持脱敏回调每条执行一次。
                        val input = if (command.exceptionText.isEmpty()) command.body else command.body + command.exceptionText
                        val body = config.redact(input)
                        val parts = listOf(body)
                        val suffix = if (command.wasTruncated) TRUNCATION_MARKER else ""
                        if (config.logcatMinLevel != null && command.level >= config.logcatMinLevel) {
                            try {
                                // 分块仅影响 Logcat 展示；文件仍是一条完整记录。每块不拆代理对或转义序列。
                                writeEscaped(parts, 1024, suffix, preserveLayout = command.exceptionText.isNotEmpty()) { logcat(command.level, command.tag, it) }
                            } catch (error: Exception) { report("输出业务 Logcat", error) }
                        }
                        if (command.level >= config.fileMinLevel) {
                            if (recovery.isWaiting()) {
                                failed.incrementAndGet()
                            } else {
                                try {
                                    files.write(command.time, "${formatter.format(Instant.ofEpochMilli(command.time))} [${command.tag}] ", parts, suffix, preserveLayout = command.exceptionText.isNotEmpty())
                                    written.incrementAndGet()
                                } catch (error: LogRecordTooLargeException) { report("检查单条日志存储预算", error) }
                                catch (error: Exception) { fileFailure("写入或滚动日志文件", error) }
                            }
                        }
                    } catch (error: Exception) { report("脱敏或处理日志正文", error) }
                    finally { inOutput.remove(); slots.release() }
                }
                is Command.Flush -> {
                    val result = flushFiles()
                    diagnostic.write(if (result.isSuccessful) LogLevel.INFO else LogLevel.WARN) { "手动刷新完成，成功：${result.isSuccessful}" }
                    command.result.complete(result)
                }
                is Command.Stop -> {
                    val result = flushFiles()
                    try { files.close() } catch (error: Exception) { report("关闭日志文件流", error) }
                    val completed = result.copy(isSuccessful = result.isSuccessful && failed.get() == result.status.failed, status = status())
                    diagnostic.write(if (completed.isSuccessful) LogLevel.INFO else LogLevel.ERROR) {
                        if (completed.isSuccessful) "日志后台已排空并关闭" else "日志后台已停止，刷新或关闭文件流失败，请检查失败记录"
                    }
                    command.result.complete(completed)
                    commands.close()
                    scope.cancel()
                    return
                }
                Command.Tick -> {
                    synchronized(gate) { tickPending = false }
                    val now = System.nanoTime()
                    // 单调时钟避免系统时间回拨影响调度；先转毫秒，避免大间隔转纳秒溢出。
                    if ((now - lastFlush) / 1_000_000 >= config.flushIntervalMillis) {
                        lastFlush += ((now - lastFlush) / 1_000_000 / config.flushIntervalMillis) * config.flushIntervalMillis * 1_000_000
                        flushFiles()
                    }
                    if ((now - lastCleanup) / 1_000_000 >= CLEANUP_INTERVAL_MILLIS) {
                        lastCleanup += ((now - lastCleanup) / 1_000_000 / CLEANUP_INTERVAL_MILLIS) * CLEANUP_INTERVAL_MILLIS * 1_000_000
                        try { files.cleanup(System.currentTimeMillis()) } catch (error: Exception) { report("定期清理日志文件", error) }
                    }
                    if ((now - lastArchive) / 1_000_000 >= archiveIntervalMillis) {
                        lastArchive = now
                        // 每次最多一片；失败独立退避，不关闭正常写入流、不阻塞业务调用线程。
                        archiveIntervalMillis = 60_000L
                        try {
                            if (files.archiveOne(System.currentTimeMillis())) archiveIntervalMillis = 1000L
                        } catch (error: Exception) { report("压缩历史日志文件", error) }
                    }
                }
            }
        }
    }

    private fun flushFiles(): FlushResult {
        if (!isEnabled) return FlushResult(false, status())
        val before = failed.get()
        try {
            if (files.flush()) recovery.recovered()
        } catch (error: Exception) { fileFailure("刷新日志文件缓冲", error) }
        return FlushResult(failed.get() == before, status())
    }

    private fun fileFailure(operation: String, error: Exception) {
        report(operation, error)
        recovery.failed()
        diagnostic.write(LogLevel.WARN) { "$operation 失败，已进入退避，稍后重试新日志" }
        try { files.close() } catch (closeError: Exception) { report("故障后关闭日志文件流", closeError) }
    }

    private fun report(operation: String, error: Exception) {
        failed.incrementAndGet()
        lastError = error.javaClass.simpleName
        diagnostic.write(LogLevel.ERROR) { "$operation 失败，错误类型：${error.javaClass.simpleName}；累计失败次数：${failed.get()}" }
    }

    internal val logDirectory: String get() = directory.absolutePath

    companion object {
        private const val CLEANUP_INTERVAL_MILLIS = 60L * 60 * 1000

        /** 创建应用级实例；仅使用 applicationContext，不保存 Activity。无效日期格式使用默认值，其他无效配置停用日志并提供 configurationWarnings。 */
        fun create(context: Context, config: LogConfig = LogConfig()): LogEngine {
            val app = context.applicationContext
            val storage = app.getExternalFilesDir(null) ?: throw ExternalLogStorageUnavailableException()
            return LogEngine(File(storage, if ("directoryName" in config.validationWarnings()) "logs" else config.directoryName), config) { level, tag, body ->
                val priority = when (level) { LogLevel.DEBUG -> Log.DEBUG; LogLevel.INFO -> Log.INFO; LogLevel.WARN -> Log.WARN; LogLevel.ERROR -> Log.ERROR }
                Log.println(priority, tag, body)
            }
        }
    }
}

/** 外部应用专属存储不可用时停用日志，由初始化结果报告。 */
internal class ExternalLogStorageUnavailableException : java.io.IOException()
