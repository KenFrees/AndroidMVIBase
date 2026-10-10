package com.ttxz.base.logging

/**
 * 固定名称的轻量记录入口；由 Logging 获取，不拥有文件或后台任务。
 * 所有记录返回 true 只代表入队接受，不代表落盘。等级过滤、容量不足、未启用或关闭返回 false。
 * 不执行磁盘 IO、不等待队列空间；异常在调用线程生成有界快照（含每层最多 512 个 UTF-16 字符单元的 message），后台与正文统一脱敏，不自动采集调用堆栈。
 * 延迟正文在等级/容量允许后于调用线程执行；可预期回调异常被捕获，失败计数可由 Logging.status 查看。
 * 回调不得阻塞或递归管理日志系统；JVM 内存耗尽等系统故障不在恢复承诺内。
 */
class Logger internal constructor(
    /** 原始 Logger 名称；写入时进行有界转义。 */
    val name: String,
    private val context: LoggingContext,
) {
    /** 记录调试信息及可选的有界异常快照；true 仅表示被接受。不自动采集调用堆栈。 */
    fun debug(message: String, throwable: Throwable? = null): Boolean = context.record(LogLevel.DEBUG, name, message, throwable)
    /** 记录过程信息及可选的有界异常快照。 */
    fun info(message: String, throwable: Throwable? = null): Boolean = context.record(LogLevel.INFO, name, message, throwable)
    /** 记录警告及可选的有界异常快照。 */
    fun warn(message: String, throwable: Throwable? = null): Boolean = context.record(LogLevel.WARN, name, message, throwable)
    /** 记录错误及有界异常快照。 */
    fun error(message: String, throwable: Throwable? = null): Boolean = context.record(LogLevel.ERROR, name, message, throwable)
    /** 等级过滤和容量检查通过后才构造正文；lambda 在调用线程执行，异常记入失败状态。 */
    fun debug(throwable: Throwable? = null, message: () -> String): Boolean = context.recordLazy(LogLevel.DEBUG, name, throwable, message)
    /** 等级与容量允许后才构造过程正文；执行线程与异常处理同 debug。 */
    fun info(throwable: Throwable? = null, message: () -> String): Boolean = context.recordLazy(LogLevel.INFO, name, throwable, message)
    /** 等级与容量允许后才构造警告正文。 */
    fun warn(throwable: Throwable? = null, message: () -> String): Boolean = context.recordLazy(LogLevel.WARN, name, throwable, message)
    /** 等级与容量允许后才构造错误正文。 */
    fun error(throwable: Throwable? = null, message: () -> String): Boolean = context.recordLazy(LogLevel.ERROR, name, throwable, message)
    /** 当前等级能否输出；不保证随后队列有空间。 */
    fun isEnabled(level: LogLevel): Boolean = context.isLoggable(level)
}
