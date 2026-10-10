package com.ttxz.base.logging

import com.ttxz.base.logging.internal.FileRecovery
import com.ttxz.base.logging.internal.LogFiles
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 完整链路的故障与生命周期回归；不依赖真实磁盘满、权限变化或 sleep 竞态。 */
class ComprehensiveRegressionTest {
    private fun config() = LogConfig(logcatMinLevel = null, zoneId = ZoneId.of("UTC"),
        flushIntervalMillis = Long.MAX_VALUE)

    @Test fun partialWriteIsRepairedBeforeNextAppend() = runBlocking {
        val root = Files.createTempDirectory("logger-partial-write").toFile()
        var nanos = 0L
        val engine = LogEngine(File(root, "logs"), config(), FileRecovery { nanos }) { _, _, _ -> }
        try {
            engine.info("T", "before"); engine.flush()
            val filesField = LogEngine::class.java.getDeclaredField("files").apply { isAccessible = true }
            val files = filesField.get(engine)
            val streamField = LogFiles::class.java.getDeclaredField("stream").apply { isAccessible = true }
            val original = streamField.get(files) as BufferedWriter
            streamField.set(files, object : BufferedWriter(original) {
                override fun write(text: String, offset: Int, length: Int) {
                    if (text.contains("broken")) {
                        original.write(text, offset, minOf(3, length))
                        original.flush()
                        throw IOException("partial write")
                    }
                    original.write(text, offset, length)
                }
            })
            engine.info("T", "broken"); engine.flush()
            val source = File(root, "logs").listFiles()!!.single()
            val damaged = source.readText()
            assertFalse(damaged.endsWith("\n"))
            assertEquals(1L, engine.status().failed)

            assertEquals(damaged, source.readText())
            nanos += 1_000_000_000L
            engine.info("T", "after"); engine.flush()
            assertEquals(listOf("before", "after"), source.readLines().map { it.substringAfter("[T] ") })
            assertEquals(2L, engine.status().written)
            assertEquals(1L, engine.status().failed)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun rolloverUsesNumericIndicesBeyondSixDigitsAndIntRange() {
        val root = Files.createTempDirectory("logger-indices").toFile()
        val day = Instant.parse("2026-10-09T00:00:00Z").toEpochMilli()
        val config = config().copy(maxFileBytes = 100, maxDirectoryBytes = 4096)
        File(root, "2026-10-09-999999.log").writeText("older\n")
        val latest = File(root, "2026-10-09-1000000.log").apply { writeText("latest\n") }
        val files = LogFiles(root, config)
        try {
            files.write(day, "next"); files.flush()
            assertEquals("latest\nnext\n", latest.readText())
        } finally { files.close() }
        root.listFiles()!!.forEach { assertTrue(it.delete()) }
        File(root, "2026-10-09-2147483647.log").writeText("x".repeat(100) + "\n")
        val reopened = LogFiles(root, config)
        try {
            reopened.write(day, "next"); reopened.flush()
            assertEquals("next\n", File(root, "2026-10-09-2147483648.log").readText())
        } finally { reopened.close(); root.deleteRecursively() }
    }

    @Test fun interruptedCallerKeepsInterruptFlagAndLoggingStillWorks() = runBlocking {
        val root = Files.createTempDirectory("logger-interruption").toFile()
        val engine = LogEngine(File(root, "logs"), config()) { _, _, _ -> }
        var preserved = false
        var accepted = false
        val caller = Thread {
            Thread.currentThread().interrupt()
            accepted = engine.info("T", "interrupted caller")
            preserved = Thread.currentThread().isInterrupted
        }
        try {
            caller.start(); caller.join(5000)
            assertFalse(caller.isAlive)
            assertTrue(accepted); assertTrue(preserved)
            engine.flush()
            assertEquals(1L, engine.status().written)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun closeRejectsRecordStillBeingPreparedAndAllControlsFinish() = runBlocking {
        val root = Files.createTempDirectory("logger-close-preparation").toFile()
        val context = LoggingContext()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            context.initialize { LogEngine(File(root, "logs"), config()) { _, _, _ -> } }
            val logger = context.getLogger("T")
            val attempt = pool.submit<Boolean> { logger.info {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "late preparation"
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(context.close(1000).isSuccessful)
            release.countDown()
            assertFalse(attempt.get(5, TimeUnit.SECONDS))
            assertEquals(0L, context.status().accepted)
            assertEquals(1L, context.status().dropped)
            assertTrue(context.flush().isSuccessful)
        } finally { release.countDown(); pool.shutdownNow(); context.close(1000); root.deleteRecursively() }
    }
}
