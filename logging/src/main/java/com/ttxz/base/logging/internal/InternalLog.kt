package com.ttxz.base.logging.internal

import android.util.Log
import com.ttxz.base.logging.LogLevel

/** 库自身诊断，直接输出 Logcat，不经过业务队列；关闭时不构造正文。 */
internal class InternalLog(
    private val enabled: Boolean,
    private val output: (LogLevel, String) -> Unit = { level, message ->
        val priority = when (level) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
        Log.println(priority, "Logging", message)
    },
) {
    fun write(level: LogLevel, message: () -> String) {
        if (enabled) {
            try { output(level, message()) } catch (_: Exception) { /* 诊断失败不影响宿主。 */ }
        }
    }
}
