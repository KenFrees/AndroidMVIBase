package com.ttxz.base.logging

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 显式传 soakMinutes 才执行；固定小批次持续写入，不在内存中保存整场日志或延迟样本。 */
@RunWith(AndroidJUnit4::class)
class LoggerSoakDeviceTest {
    @Test fun pacedWritesRemainCompleteWithinDirectoryBudget() = runBlocking {
        val minutes = InstrumentationRegistry.getArguments().getString("soakMinutes")?.toIntOrNull()
        assumeTrue("长稳测试需显式传 soakMinutes=1..1440", minutes != null)
        require(minutes!! in 1..1440)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = LogConfig(directoryName = "soak-${UUID.randomUUID()}", logcatMinLevel = null)
        val engine = LogEngine.create(instrumentation.targetContext, config)
        val root = File(engine.logDirectory)
        val payload = "测".repeat(100)
        val exception = IllegalStateException("长稳测试异常").apply {
            stackTrace = arrayOf(StackTraceElement("Soak", "submit", "Soak.kt", 1))
        }
        val heaps = LongArray(minutes + 1)
        var peakHeap = 0L
        var maxSubmitNanos = 0L
        var maxDirectoryBytes = 0L
        var rolled = false
        var cleaned = false
        var previousFiles = emptySet<String>()
        fun heap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        suspend fun settle(): Long { System.gc(); delay(100); System.gc(); delay(100); return heap() }
        fun report(message: String) {
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$message\n") })
            Log.i("LOGGER_SOAK", message)
        }
        try {
            assertTrue(engine.info("Soak", "warmup"))
            assertTrue(engine.flush().isSuccessful)
            heaps[0] = settle()
            peakHeap = heaps[0]
            val before = engine.status()
            val started = System.nanoTime()
            repeat(minutes) { minute ->
                // 每 100ms 提交 10 条，超时后不追赶补发；每分钟另做刷新和磁盘扫描。
                repeat(600) { tick ->
                    val tickStart = System.nanoTime()
                    instrumentation.runOnMainSync {
                        repeat(10) { item ->
                            val id = minute * 6000 + tick * 10 + item
                            val at = System.nanoTime()
                            val body = "$id:$payload"
                            val accepted = if (id % 100 == 0) engine.record(LogLevel.WARN, "Soak", body, exception)
                                else engine.info("Soak", body)
                            maxSubmitNanos = maxOf(maxSubmitNanos, System.nanoTime() - at)
                            assertTrue("Rejected record $id", accepted)
                        }
                    }
                    peakHeap = maxOf(peakHeap, heap())
                    val remaining = 100_000_000L - (System.nanoTime() - tickStart)
                    if (remaining > 0) delay((remaining + 999_999) / 1_000_000)
                }
                assertTrue(engine.flush().isSuccessful)
                val status = engine.status()
                assertEquals((minute + 1) * 6000L, status.accepted - before.accepted)
                assertEquals(status.accepted, status.written)
                assertEquals(before.dropped, status.dropped)
                assertEquals(0L, status.failed)
                val files = root.listFiles()!!.filter { it.extension == "log" || it.name.endsWith(".log.gz") }
                val bytes = files.sumOf { it.length() }
                assertTrue("Directory budget exceeded: $bytes", bytes <= config.maxDirectoryBytes)
                maxDirectoryBytes = maxOf(maxDirectoryBytes, bytes)
                rolled = rolled || files.size > 1
                val names = files.map { it.name.removeSuffix(".gz") }.toSet()
                // 首次滚动会把无序号文件重命名，不能将该消失误报为容量清理。
                cleaned = cleaned || (previousFiles - names).any { it.matches(Regex(".*-\\d{6}\\.log(?:\\.gz)?")) }
                previousFiles = names
                // 只核对最近一分钟；更早记录允许被默认目录预算正常清理。
                val seen = BooleanArray(6000)
                val first = minute * 6000
                files.forEach { file -> file.inputStream().use { raw ->
                    val input = if (file.name.endsWith(".gz")) java.util.zip.GZIPInputStream(raw) else raw
                    input.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if ("[Soak] " in line) {
                        val body = line.substringAfter("[Soak] ")
                        val id = body.substringBefore(':').toIntOrNull()
                        assertTrue("Malformed record: $body", id != null || body == "warmup")
                        if (id != null && id in first until first + 6000) {
                            assertFalse("Duplicate record $id", seen[id - first])
                            seen[id - first] = true
                            assertEquals(payload, body.substringAfter(':'))
                        }
                    }
                } } } }
                assertTrue("Missing recent records", seen.all { it })
                heaps[minute + 1] = settle()
                assertTrue("Retained heap grew: ${heaps.take(minute + 2)}",
                    heaps[minute + 1] - heaps[0] < 16L * 1024 * 1024)
                report("LOGGER_SOAK minute=${minute + 1}/$minutes elapsedMs=${(System.nanoTime() - started) / 1_000_000} accepted=${status.accepted - before.accepted} dropped=${status.dropped - before.dropped} failed=${status.failed} bytes=$bytes files=${files.size} heapAfterGc=${heaps[minute + 1]} sampledPeakHeap=$peakHeap maxSubmitUs=${maxSubmitNanos / 1000}")
            }
            if (minutes >= 20) { assertTrue("No rollover observed", rolled); assertTrue("No budget cleanup observed", cleaned) }
            report("LOGGER_SOAK_PASS api=${android.os.Build.VERSION.SDK_INT} minutes=$minutes elapsedMs=${(System.nanoTime() - started) / 1_000_000} attempts=${minutes * 6000L} baselineHeap=${heaps[0]} maxRetainedGrowth=${heaps.max() - heaps[0]} sampledPeakHeap=$peakHeap maxSubmitUs=${maxSubmitNanos / 1000} maxDirectoryBytes=$maxDirectoryBytes rolled=$rolled cleaned=$cleaned")
        } finally {
            try { engine.close() } finally { root.deleteRecursively() }
        }
    }
}
