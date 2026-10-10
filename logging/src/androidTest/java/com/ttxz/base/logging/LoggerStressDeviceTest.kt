package com.ttxz.base.logging

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 独立测试宿主中的持续写入与保留堆观测，不把单设备结果当作性能保证。 */
@RunWith(AndroidJUnit4::class)
class LoggerStressDeviceTest {
    /** 多生产线程、大正文和人为慢消费者；同时检查正文完整性与排空后的保留堆。 */
    @Test fun concurrentLargeMessagesWithSlowConsumerRecoverWithoutRetention() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        fun heap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        suspend fun settle() { System.gc(); delay(100); System.gc(); delay(100) }
        run {
            val engine = LogEngine.create(app, LogConfig(directoryName = "parallel-${UUID.randomUUID()}",
                queueCapacity = 16, logcatMinLevel = null,
                redact = { Thread.sleep(2); it }))
            val root = File(engine.logDirectory)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            val accepted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val latencies = LongArray(1200)
            val heaps = LongArray(4)
            try {
                // 预热类加载/文件 IO，再取基线；每次正文独立构造，避免共享字符串掩盖积压。
                assertTrue(engine.info("Parallel", "warmup")); assertTrue(engine.flush().isSuccessful)
                settle(); heaps[0] = heap()
                repeat(3) { round ->
                    val start = java.util.concurrent.CountDownLatch(1)
                    val tasks = (0 until 8).map { worker -> pool.submit {
                        check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        repeat(50) { index ->
                            val id = "$round-$worker-$index"
                            val body = "$id:" + "x".repeat(16384)
                            val at = System.nanoTime()
                            val ok = engine.info("Parallel", body)
                            latencies[round * 400 + worker * 50 + index] = System.nanoTime() - at
                            if (ok) accepted.add(id)
                        }
                    } }
                    start.countDown()
                    tasks.forEach { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                    assertTrue(engine.flush().isSuccessful)
                    settle(); heaps[round + 1] = heap()
                }
                val status = engine.status()
                assertEquals(1201L, status.accepted + status.dropped)
                assertEquals(accepted.size.toLong() + 1, status.accepted)
                assertEquals(status.accepted, status.written)
                assertEquals(0L, status.failed)
                val actual = mutableSetOf<String>()
                root.listFiles()!!.filter { it.extension == "log" }.forEach { file ->
                    file.useLines { lines -> lines.forEach { line ->
                        val body = line.substringAfter("[Parallel] ")
                        if (body != "warmup") {
                            val id = body.substringBefore(':')
                            assertTrue("Duplicate $id", actual.add(id))
                            assertEquals(16384, body.substringAfter(':').length)
                            assertTrue(body.substringAfter(':').all { it == 'x' })
                        }
                    } }
                }
                assertEquals(accepted, actual)
                assertTrue("Retained heap grew: ${heaps.toList()}", heaps.max() - heaps[0] < 16L * 1024 * 1024)
                latencies.sort()
                println("LOGGER_PARALLEL nonBlocking=true producers=8 chars=16384 slowMs=2 attempts=1200 accepted=${accepted.size} dropped=${status.dropped} p95Us=${latencies[1140] / 1000} maxUs=${latencies.last() / 1000} heaps=${heaps.toList()}")
            } finally {
                try {
                    pool.shutdownNow()
                    assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                } finally {
                    try { engine.close() } finally { root.deleteRecursively() }
                }
            }
        }
    }

    @Test fun fullQueueRejectsImmediatelyOnRealMainThread() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val engine = LogEngine.create(instrumentation.targetContext, LogConfig(
            directoryName = "main-wait-${UUID.randomUUID()}", queueCapacity = 1,
            logcatMinLevel = null, redact = {
                entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); it
            }))
        val root = File(engine.logDirectory)
        try {
            assertTrue(engine.info("Test", "first"))
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val start = System.nanoTime()
                assertFalse(engine.info("Test", "main-full"))
                assertTrue("Main thread waited for capacity", (System.nanoTime() - start) / 1_000_000 < 500)
            }
            release.countDown()
            assertTrue(engine.flush().isSuccessful)
        } finally { release.countDown(); engine.close(); root.deleteRecursively() }
    }

    /** 明确的到达速率与突发形态；输出每档丢弃数及采样峰值，不能当作全设备性能保证。 */
    @Test fun measureNonBlockingRateRangeAndHeap() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val payload = "diagnostic-" + "测".repeat(100)
        fun heap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        suspend fun settle() { System.gc(); delay(100); System.gc(); delay(100) }
        for (rate in listOf(1000, 1500, 2000, 2500, 3000)) {
            val engine = LogEngine.create(app, LogConfig(directoryName = "range-${UUID.randomUUID()}", logcatMinLevel = null))
            val root = File(engine.logDirectory)
            val count = rate * 10
            val accepted = BooleanArray(count)
            val seen = BooleanArray(count)
            val times = LongArray(count)
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val peak = java.util.concurrent.atomic.AtomicLong()
            val sampler = Thread {
                while (running.get()) {
                    peak.accumulateAndGet(heap(), ::maxOf)
                    Thread.sleep(10)
                }
            }
            try {
                repeat(100) { engine.info("Warmup", payload) }; assertTrue(engine.flush().isSuccessful)
                val before = engine.status()
                settle(); val baseline = heap(); peak.set(baseline); sampler.start()
                val start = System.nanoTime()
                repeat(count) { index ->
                    // 每 100ms 一批；每秒条数相同也可能因突发形态不同得到不同结果。
                    if (index % (rate / 10) == 0) {
                        val remaining = start + index.toLong() * 1_000_000_000 / rate - System.nanoTime()
                        if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining)
                    }
                    val at = System.nanoTime()
                    accepted[index] = engine.info("Range", "$index:$payload")
                    times[index] = System.nanoTime() - at
                }
                val remaining = start + 10_000_000_000L - System.nanoTime()
                if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining)
                val submitMs = (System.nanoTime() - start) / 1_000_000
                assertTrue(engine.flush().isSuccessful)
                running.set(false); sampler.join(1000); assertFalse(sampler.isAlive)
                settle(); val afterHeap = heap()
                val status = engine.status()
                val acceptedCount = accepted.count { it }
                assertEquals(acceptedCount.toLong(), status.accepted - before.accepted)
                assertEquals(count.toLong(), status.accepted - before.accepted + status.dropped - before.dropped)
                assertEquals(status.accepted, status.written); assertEquals(0L, status.failed)
                root.listFiles()!!.filter { it.extension == "log" }.forEach { file -> file.useLines { lines ->
                    lines.filter { "[Range] " in it }.forEach { line ->
                        val body = line.substringAfter("[Range] ")
                        val id = body.substringBefore(':').toInt()
                        assertTrue(accepted[id]); assertFalse(seen[id]); seen[id] = true
                        assertEquals(payload, body.substringAfter(':'))
                    }
                } }
                assertArrayEquals(accepted, seen)
                times.sort()
                println("LOGGER_RANGE rate=$rate batchMs=100 seconds=10 attempts=$count accepted=$acceptedCount dropped=${status.dropped - before.dropped} submitMs=$submitMs p95Us=${times[count * 95 / 100] / 1000} maxUs=${times.last() / 1000} baselineHeap=$baseline sampledPeakHeap=${peak.get()} afterGcHeap=$afterHeap")
            } finally {
                running.set(false); if (sampler.isAlive) sampler.join(1000)
                engine.close(); root.deleteRecursively()
            }
        }
    }

    @Test fun sustainedMainThreadSubmissionKeepsMemoryBounded() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val engine = LogEngine.create(app, LogConfig(directoryName = "stress-${UUID.randomUUID()}", logcatMinLevel = null))
        val root = File(engine.logDirectory)
        val context = LoggingContext()
        val message = "diagnostic-" + "测".repeat(100)
        val withThrowable = InstrumentationRegistry.getArguments().getString("stressWithThrowable") == "true"
        val exception = IllegalStateException("压力测试异常").apply {
            stackTrace = arrayOf(StackTraceElement("Stress", "submit", "Stress.kt", 1))
        }
        val rounds = InstrumentationRegistry.getArguments().getString("stressRounds")?.toIntOrNull()?.coerceIn(1, 120) ?: 3
        val attempts = rounds * 10_000
        val samples = LongArray(attempts)
        val heaps = LongArray(rounds + 1)
        fun heap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        suspend fun settle() { System.gc(); delay(100); System.gc(); delay(100) }
        try {
            context.initialize { engine }
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
                            if (withThrowable && at % 20 == 0) logger.warn(message, exception) else logger.info(message)
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
            println("LOGGER_STRESS throwable=$withThrowable directory=${root.absolutePath} api=${android.os.Build.VERSION.SDK_INT} attempts=$attempts accepted=${status.accepted - before.accepted} dropped=${status.dropped - before.dropped} p95Us=${samples[(samples.size * 95 / 100) - 1] / 1000} maxUs=${samples.last() / 1000} elapsedMs=$elapsedMillis heaps=${heaps.toList()} allocatedBytes=$allocated")
        } finally { context.close(5000); root.deleteRecursively() }
    }
}
