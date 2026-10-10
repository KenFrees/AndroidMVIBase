package com.ttxz.base.logging

import com.ttxz.base.logging.internal.LogFiles
import com.ttxz.base.logging.internal.escaped
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class LoggerTest {
    private fun config() = LogConfig(logcatMinLevel = null, zoneId = ZoneId.of("UTC"), maxFileBytes = 1536, maxDirectoryBytes = 3072)

    @Test fun fixedFormatEscapesInjectedRecordsAndFlushPreservesOrder() = runBlocking {
        val root = Files.createTempDirectory("logger-test").toFile()
        val logger = LogEngine(File(root, "logs"), config()) { _, _, _ -> }
        try {
            assertTrue(logger.info("A]\nB", "first\nforged"))
            assertTrue(logger.info("A", "second"))
            assertTrue(logger.flush().isSuccessful)
            val lines = File(root, "logs").listFiles()!!.single().readLines()
            assertEquals(2, lines.size)
            assertTrue(lines[0].matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[.*")))
            assertTrue(lines[0].endsWith("[A\\]\\nB] first\\nforged"))
            assertTrue(lines[1].endsWith("[A] second"))
            assertTrue(logger.close().isSuccessful)
            repeat(10) { assertFalse(logger.info("A", "after close")) }
            assertEquals(10L, logger.status().dropped)
            assertTrue(logger.close().isSuccessful)
        } finally { logger.close(); root.deleteRecursively() }
    }

    @Test fun boundedQueueDropsWithoutWaiting() = runBlocking {
        val root = Files.createTempDirectory("logger-test").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val logger = LogEngine(File(root, "logs"), config().copy(queueCapacity = 1, redact = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); it
        })) { _, _, _ -> }
        try {
            assertTrue(logger.info("A", "accepted"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(logger.info("A", "dropped"))
            assertEquals(1L, logger.status().dropped)
            release.countDown()
            logger.flush()
            val text = File(root, "logs").listFiles()!!.single().readText()
            assertTrue(text.contains("accepted")); assertFalse(text.contains("dropped"))

        } finally { release.countDown(); logger.close(); root.deleteRecursively() }
    }

    @Test fun ioFailureIsReportedAndLaterWriteRecovers() = runBlocking {
        val root = Files.createTempDirectory("logger-test").toFile()
        val directory = File(root, "logs").apply { writeText("occupied") }
        val logger = LogEngine(directory, config()) { _, _, _ -> }
        try {
            logger.info("A", "fails"); logger.flush()
            assertEquals(1L, logger.status().failed)
            assertEquals(0L, logger.status().written)
            directory.delete()
            kotlinx.coroutines.delay(1100)
            logger.info("A", "recovers"); logger.flush()
            assertEquals(1L, logger.status().written)
        } finally { logger.close(); root.deleteRecursively() }
    }

    @Test fun rotationRetentionAndBudgetProtectActiveFile() {
        val root = Files.createTempDirectory("logger-files").toFile()
        val files = LogFiles(root, config().copy(retentionDays = 2))
        val day = Instant.parse("2026-10-09T00:00:00Z").toEpochMilli()
        try {
            File(root, "2026-10-01-000000.log").writeText("old")
            repeat(80) { files.write(day, "x".repeat(100)) }
            files.flush()
            assertFalse(File(root, "2026-10-01-000000.log").exists())
            assertTrue(root.listFiles()!!.sumOf { it.length() } <= 3072)
            assertTrue(root.listFiles()!!.all { it.length() < 1536 + 101 })
            files.write(day + 86_400_000, "next day")
            files.flush()
            assertTrue(root.listFiles()!!.any { it.name.startsWith("2026-10-10") })
        } finally { files.close(); root.deleteRecursively() }
    }

    @Test fun invalidDatePatternAndSurrogateTruncation() = runBlocking {
        for (pattern in listOf("'prefix' yyyy-MM-dd HH:mm", "yyyy-MM-dd\nHH:mm", "HH:mm")) {
            val root = Files.createTempDirectory("logger-date-fallback").toFile()
            val logger = LogEngine(File(root, "logs"), config().copy(datePattern = pattern)) { _, _, _ -> }
            try {
                assertTrue(logger.isEnabled)
                assertEquals(listOf("datePattern"), logger.configurationWarnings)
                assertTrue(logger.info("T", "fallback"))
                assertTrue(logger.flush().isSuccessful)
                assertTrue(File(root, "logs").listFiles()!!.single().readText().matches(Regex("\\d{4}-\\d{2}-\\d{2} .*\\[T] fallback\\n")))
            } finally { logger.close(); root.deleteRecursively() }
        }
        assertEquals("", escaped("😀", 1))
        assertEquals("😀", escaped("😀", 2))
    }

    @Test fun invalidSafetyConfigurationDisablesWithoutDiskOrExceptions() = runBlocking {
        val invalid = listOf(
            config().copy(directoryName = "../escape"), config().copy(directoryName = ""),
            config().copy(discardingThreshold = -1), config().copy(discardingThreshold = 257),
            config().copy(queueCapacity = Int.MIN_VALUE),
            config().copy(maxFileBytes = Long.MIN_VALUE), config().copy(maxDirectoryBytes = 0),
            config().copy(retentionDays = 0), config().copy(flushIntervalMillis = 0),
            config().copy(flushIntervalMillis = -1), config().copy(queueCapacity = 0),
        )
        assertTrue(LogConfig().validationWarnings().isEmpty())
        for (configuration in invalid) {
            val root = Files.createTempDirectory("logger-disabled").toFile()
            val logger = LogEngine(File(root, "logs"), configuration) { _, _, _ -> fail("Disabled Logcat") }
            try {
                assertFalse(logger.isEnabled)
                assertTrue(logger.configurationWarnings.isNotEmpty())
                assertFalse(logger.info("T", "ignored"))
                assertFalse(logger.flush().isSuccessful)
                assertFalse(File(root, "logs").exists())
            } finally { logger.close(); root.deleteRecursively() }
        }
    }

    @Test fun concurrentProducersAccountForEveryAttempt() = runBlocking {
        val root = Files.createTempDirectory("logger-concurrent").toFile()
        val logger = LogEngine(File(root, "logs"), LogConfig(logcatMinLevel = null)) { _, _, _ -> }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..4).map { producer -> pool.submit { repeat(500) { logger.info("P$producer", "record-$it") } } }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            logger.flush()
            val status = logger.status()
            assertEquals(2000L, status.accepted + status.dropped)
            assertEquals(status.accepted, status.written)
            assertEquals(0L, status.failed)
            assertEquals(status.accepted, File(root, "logs").listFiles()!!.sumOf { it.readLines().size }.toLong())
        } finally { pool.shutdownNow(); logger.close(); root.deleteRecursively() }
    }

    @Test fun redactionFailureDoesNotKillWorker() = runBlocking {
        val root = Files.createTempDirectory("logger-cancel").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val logger = LogEngine(File(root, "logs"), config().copy(redact = {
            if (it == "hold") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            if (it == "bad") throw IllegalArgumentException("redaction")
            it
        })) { _, _, _ -> }
        try {
            logger.info("A", "hold")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            release.countDown()
            logger.flush()
            logger.info("A", "bad")
            logger.info("A", "good")
            logger.flush()
            assertTrue(File(root, "logs").listFiles()!!.single().readText().contains("good"))
            assertEquals(1L, logger.status().failed)
        } finally { release.countDown(); logger.close(); root.deleteRecursively() }
    }
}
