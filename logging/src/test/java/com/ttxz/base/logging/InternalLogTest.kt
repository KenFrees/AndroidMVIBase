package com.ttxz.base.logging

import com.ttxz.base.logging.internal.InternalLog
import org.junit.Assert.*
import org.junit.Test

class InternalLogTest {
    @Test fun engineDiagnosticsIdentifyOperationWithoutLeakingExceptionMessage() = kotlinx.coroutines.runBlocking {
        val root = java.nio.file.Files.createTempDirectory("log-operation").toFile()
        val output = java.util.Collections.synchronizedList(mutableListOf<String>())
        val engine = LogEngine(java.io.File(root, "logs"),
            LogConfig(logcatMinLevel = null, redact = { throw IllegalStateException("private content") }),
            diagnostic = InternalLog(true) { _, text -> output.add(text) }) { _, _, _ -> }
        try {
            engine.info("T", "secret")
            engine.flush()
            assertTrue(output.any { it.contains("脱敏或处理日志正文") && it.contains("IllegalStateException") })
            assertFalse(output.any { it.contains("private content") || it.contains("secret") })
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun closeFailureIsReportedAsFailureInsteadOfSuccess() = kotlinx.coroutines.runBlocking {
        val root = java.nio.file.Files.createTempDirectory("log-close-feedback").toFile()
        val output = java.util.Collections.synchronizedList(mutableListOf<Pair<LogLevel, String>>())
        val engine = LogEngine(java.io.File(root, "logs"),
            LogConfig(logcatMinLevel = null, flushIntervalMillis = Long.MAX_VALUE),
            diagnostic = InternalLog(true) { level, text -> output.add(level to text) }) { _, _, _ -> }
        try {
            engine.info("T", "before")
            engine.flush()
            val filesField = LogEngine::class.java.getDeclaredField("files").apply { isAccessible = true }
            val files = filesField.get(engine)
            val streamField = files.javaClass.getDeclaredField("stream").apply { isAccessible = true }
            val original = streamField.get(files) as java.io.BufferedWriter
            streamField.set(files, object : java.io.BufferedWriter(original) {
                override fun close() { super.close(); throw java.io.IOException("private close detail") }
            })
            assertFalse(engine.close().isSuccessful)
            assertEquals(1L, engine.status().failed)
            assertTrue(output.any { it.second.contains("关闭日志文件流") && it.second.contains("IOException") })
            assertTrue(output.any { it.first == LogLevel.ERROR && it.second.contains("日志后台已停止") })
            assertFalse(output.any { it.second.contains("已排空并关闭") || it.second.contains("private close detail") })
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun multilineEncodingPreservesIndentAndMatchesStorageBudget() {
        val value = "异常😀\r\n\tat Example.run(Test.kt:1)\nCaused by: error"
        val output = StringBuilder()
        com.ttxz.base.logging.internal.writeEscaped(listOf(value), chunkChars = 8, preserveLayout = true) { output.append(it) }
        assertEquals(value, output.toString())
        assertEquals(value.toByteArray(Charsets.UTF_8).size.toLong(), com.ttxz.base.logging.internal.escapedUtf8Bytes(listOf(value), true))
    }

    @Test fun disabledDoesNotBuildOrOutputMessages() {
        val diagnostic = InternalLog(false) { _, _ -> error("不能输出") }
        diagnostic.write(LogLevel.ERROR) { error("不能构造正文") }
    }

    @Test fun enabledOutputsEveryLevelAndContainsOutputFailure() {
        val levels = mutableListOf<LogLevel>()
        val diagnostic = InternalLog(true) { level, _ -> levels.add(level) }
        LogLevel.entries.forEach { diagnostic.write(it) { "内部诊断" } }
        assertEquals(LogLevel.entries.toList(), levels)
        InternalLog(true) { _, _ -> throw IllegalStateException() }.write(LogLevel.ERROR) { "失败" }
    }
}
