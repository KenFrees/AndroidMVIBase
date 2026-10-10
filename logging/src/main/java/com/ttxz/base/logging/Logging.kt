package com.ttxz.base.logging

import android.content.Context
import android.util.Log
import com.ttxz.base.logging.internal.InternalLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 进程内日志系统。Application.onCreate 显式初始化；所有命名 Logger 共用一个写入器。
 * 同名返回同一实例，不淘汰缓存；名称应使用固定类名/模块名，动态 ID 放正文。
 * 初始化前可获取 Logger，记录拒绝并计数；初始化后原 Logger 自动可用。
 */
object Logging {
    private val context = LoggingContext()

    /** 只初始化一次；重复调用保留首次配置。返回结果并直接向 Logcat 输出中文初始化反馈，不受 logcatMinLevel 影响。 */
    fun initialize(context: Context, config: LogConfig = LogConfig()): LogInitialization {
        val diagnostic = InternalLog(config.isDebugLog)
        diagnostic.write(LogLevel.DEBUG) { "开始初始化日志系统" }
        return this.context.initialize { LogEngine.create(context, config) }.also { result ->
            // 诊断输出失败不能影响宿主启动，也不能经过尚未就绪的日志队列。
            runCatching {
                Log.println(if (result.enabled) Log.INFO else Log.ERROR, "Logging", result.consoleMessage())
            }
        }
    }

    /** 按名称缓存获取；不执行文件 IO，不创建独立写入器。 */
    fun getLogger(name: String): Logger = context.getLogger(name)

    /** 使用完整类名，等价于 getLogger(type.name)。 */
    fun getLogger(type: Class<*>): Logger = getLogger(type.name)

    /** 首次初始化结果；未初始化时为 null。 */
    val initialization: LogInitialization? get() = context.initialization

    /** 累计统计，包括初始化前被拒绝的记录。 */
    fun status(): LogStatus = context.status()

    /** 挂起等待此前已接受记录刷新；不做 fsync。未启用或在记录回调中调用返回失败。 */
    suspend fun flush(): FlushResult = context.flush()

    /** 查询实际日志目录中的 .log / .log.gz 文件，gzip 由调用方流式解压；可按日志命名日期筛选；从新到旧排序，不刷新、不复制内容。
     * IO 在后台执行，目录尚未创建返回空列表；未初始化、初始化停用或 IO 失败返回 Result.failure，取消传播。
     * 文件可能继续写入、滚动重命名或被清理。读取前可先 flush；持久副本由宿主负责。 */
    suspend fun logFiles(date: java.time.LocalDate? = null): Result<List<java.io.File>> {
        currentCoroutineContext().ensureActive()
        return initialization?.logFiles(date) ?: Result.failure(IllegalStateException("日志尚未初始化"))
    }

    /**
     * 停止接受记录并请求排空。最多等待 timeoutMillis；超时返回失败，后台关闭继续。
     * 默认 1000ms，非正数超时按 1ms 处理；关闭后不可重新初始化。
     */
    suspend fun close(timeoutMillis: Long = 1000): FlushResult = context.close(timeoutMillis)
}

/** enabled 表示配置有效且后台已创建，不代表磁盘探测成功；warnings 为问题字段名或错误类型，不含原始配置。
 * @param directory 实际选择的日志目录绝对路径；创建实例失败时为 null，目录按需创建。 */
data class LogInitialization(val enabled: Boolean, val warnings: List<String>, val directory: String? = null)

internal fun LogInitialization.consoleMessage(): String = buildString {
    append(if (enabled) "日志初始化成功：后台已启动，尚未验证磁盘写入。" else "日志初始化失败：日志功能已停用。")
    directory?.let { append("保存目录：").append(it).append('。') }
    if (warnings.isNotEmpty()) {
        append("原因或提示：")
        append(warnings.joinToString("；") { warning ->
            when (warning) {
                "directoryName" -> "目录名必须为 1–64 个英文字母、数字、下划线或短横线"
                "datePattern" -> "日期格式无效，已回退默认格式"
                "queueCapacity" -> "队列容量必须大于 0"
                "discardingThreshold" -> "提前丢弃阈值必须在 0 到队列容量之间"
                "maxFileBytes" -> "单文件滚动大小必须大于 0"
                "maxDirectoryBytes" -> "目录总预算必须大于 0，且不少于单文件滚动大小"
                "retentionDays" -> "日志保留天数必须大于 0"
                "flushIntervalMillis" -> "刷新间隔必须大于 0 毫秒"
                "AlreadyInitialized" -> "已初始化，保留首次配置和结果"
                "Closed" -> "日志系统已关闭，不能重新初始化"
                "ExternalLogStorageUnavailableException" -> "外部应用专属存储不可用，无法确定日志保存目录"
                "SecurityException" -> "访问日志资源被拒绝（SecurityException）"
                "IllegalArgumentException" -> "初始化参数无效（IllegalArgumentException）"
                else -> "初始化发生异常，错误类型：$warning"
            }
        })
    }
}

/** 可独立验证的进程上下文；只缓存记录入口，不缓存日志内容。 */
internal class LoggingContext {
    private val cache = ConcurrentHashMap<String, Logger>()
    private val gate = Any()
    private val rejected = AtomicLong()
    @Volatile private var engine: LogEngine? = null
    @Volatile private var closed = false
    @Volatile var initialization: LogInitialization? = null
        private set

    fun getLogger(name: String): Logger = cache.computeIfAbsent(name) { Logger(it, this) }

    fun initialize(factory: () -> LogEngine): LogInitialization = synchronized(gate) {
        if (closed) return@synchronized LogInitialization(false, listOf("Closed"))
        initialization?.let { return@synchronized it.copy(warnings = it.warnings + "AlreadyInitialized") }
        val result = try {
            val created = factory()
            engine = created
            LogInitialization(created.isEnabled, created.configurationWarnings, created.logDirectory)
        } catch (error: Exception) {
            LogInitialization(false, listOf(error.javaClass.simpleName))
        }
        initialization = result
        result
    }

    fun isLoggable(level: LogLevel): Boolean = !closed && engine?.isLoggable(level) == true

    fun record(level: LogLevel, name: String, message: String, error: Throwable?): Boolean {
        val current = engine
        if (closed || current == null) { rejected.incrementAndGet(); return false }
        return current.record(level, name, message, error)
    }

    fun recordLazy(level: LogLevel, name: String, throwable: Throwable?, message: () -> String): Boolean {
        val current = engine
        if (closed || current == null) { rejected.incrementAndGet(); return false }
        return current.recordLazy(level, name, throwable, message)
    }

    fun status(): LogStatus {
        val value = engine?.status() ?: LogStatus(0, 0, 0, 0, 0, null)
        return value.copy(dropped = value.dropped + rejected.get())
    }

    suspend fun flush(): FlushResult = engine?.flush()?.let { it.copy(status = status()) } ?: FlushResult(false, status())

    suspend fun close(timeoutMillis: Long): FlushResult {
        val current = synchronized(gate) { closed = true; engine }
        if (current == null) return FlushResult(false, status())
        current.requestClose()
        val result = withTimeoutOrNull(timeoutMillis.coerceAtLeast(1)) { current.close() }
        return FlushResult(result?.isSuccessful == true, status())
    }
}
