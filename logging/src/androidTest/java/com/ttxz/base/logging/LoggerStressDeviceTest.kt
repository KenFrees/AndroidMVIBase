package com.ttxz.base.logging

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 独立测试宿主中的持续写入与保留堆观测，不把单设备结果当作性能保证。 */
@RunWith(AndroidJUnit4::class)
class LoggerStressDeviceTest {
    @Test fun sustainedMainThreadSubmissionKeepsMemoryBounded() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val root = File(app.filesDir, "stress-test")
        val context = LoggingContext()
        val message = "diagnostic-" + "测".repeat(100)
        val rounds = InstrumentationRegistry.getArguments().getString("stressRounds")?.toIntOrNull()?.coerceIn(1, 120) ?: 3
        val attempts = rounds * 10_000
        val samples = LongArray(attempts)
        val heaps = LongArray(rounds + 1)
        fun heap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        suspend fun settle() { System.gc(); delay(100); System.gc(); delay(100) }
        try {
            context.initialize { LogEngine(File(root, "logs"), File(root, "exports"), LogConfig(logcatMinLevel = null)) { _, _, _ -> } }
            val logger = context.getLogger("Stress")
            repeat(4) {
                instrumentation.runOnMainSync { repeat(500) { logger.info(message) } }
                context.flush()
            }
            settle()
            heaps[0] = heap()
            val before = context.status()
            val allocatedBefore = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            val start = System.nanoTime()
            repeat(rounds) { round ->
                repeat(20) { batch ->
                    instrumentation.runOnMainSync {
                        repeat(500) { index ->
                            val at = round * 10_000 + batch * 500 + index
                            val time = System.nanoTime()
                            logger.info(message)
                            samples[at] = System.nanoTime() - time
                        }
                    }
                    context.flush()
                }
                settle()
                heaps[round + 1] = heap()
            }
            val elapsedMillis = (System.nanoTime() - start) / 1_000_000
            val status = context.status()
            assertEquals(attempts.toLong(), status.accepted - before.accepted + status.dropped - before.dropped)
            assertEquals(status.accepted, status.written)
            assertEquals(0L, status.failed)
            assertTrue(root.walkTopDown().filter { it.extension == "log" }.sumOf { it.length() } <= 20L * 1024 * 1024)
            assertTrue("Retained Java heap grew unexpectedly: ${heaps.toList()}", heaps.max() - heaps[0] < 16L * 1024 * 1024)
            samples.sort()
            val allocated = Debug.getRuntimeStat("art.gc.bytes-allocated").toLong() - allocatedBefore
            println("LOGGER_STRESS api=${android.os.Build.VERSION.SDK_INT} attempts=$attempts accepted=${status.accepted - before.accepted} dropped=${status.dropped - before.dropped} p95Us=${samples[(samples.size * 95 / 100) - 1] / 1000} maxUs=${samples.last() / 1000} elapsedMs=$elapsedMillis heaps=${heaps.toList()} allocatedBytes=$allocated")
        } finally { context.close(5000); root.deleteRecursively() }
    }
}
