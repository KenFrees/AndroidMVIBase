package com.ttxz.base.logging

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LogConfigPolicyTest {
    @Test fun positiveValuesBeyondPreviousPolicyLimitsAreValid() {
        val config = LogConfig(queueCapacity = 4097, maxDirectoryBytes = 512L * 1024 * 1024,
            retentionDays = 366, flushIntervalMillis = 60_001)
        assertTrue(config.validationWarnings().isEmpty())
        assertTrue(config.copy(flushIntervalMillis = 1).validationWarnings().isEmpty())
        assertTrue(config.copy(queueCapacity = Int.MAX_VALUE, discardingThreshold = Int.MAX_VALUE,
            maxFileBytes = Long.MAX_VALUE, maxDirectoryBytes = Long.MAX_VALUE,
            retentionDays = Int.MAX_VALUE, flushIntervalMillis = Long.MAX_VALUE).validationWarnings().isEmpty())
        assertTrue(config.copy(maxDirectoryBytes = config.maxFileBytes - 1).validationWarnings().contains("maxDirectoryBytes"))
        assertTrue(config.copy(discardingThreshold = config.queueCapacity + 1).validationWarnings().contains("discardingThreshold"))
    }

    @Test fun largestNumericConfigurationCanWriteWithoutOverflowOrPrematureDeletion() = runBlocking {
        val root = Files.createTempDirectory("logger-large-config").toFile()
        val config = LogConfig(logcatMinLevel = null, queueCapacity = Int.MAX_VALUE,
            maxFileBytes = Long.MAX_VALUE, maxDirectoryBytes = Long.MAX_VALUE,
            retentionDays = Int.MAX_VALUE, flushIntervalMillis = Long.MAX_VALUE)
        val engine = LogEngine(File(root, "logs"), config) { _, _, _ -> }
        try {
            assertTrue(engine.isEnabled)
            assertTrue(engine.info("Policy", "first"))
            assertTrue(engine.flush().isSuccessful)
            val first = File(root, "logs").listFiles()!!.single()
            assertTrue(engine.info("Policy", "second"))
            assertTrue(engine.flush().isSuccessful)
            val second = File(root, "logs").listFiles()!!.single()
            assertTrue(first.exists())
            assertTrue(second.exists())
            assertEquals(2L, engine.status().written)
            assertEquals(0L, engine.status().failed)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun shortPositiveFlushIntervalActuallyFlushesWithoutExplicitControl() = runBlocking {
        val root = Files.createTempDirectory("logger-short-flush").toFile()
        val directory = File(root, "logs")
        val engine = LogEngine(directory, LogConfig(
            logcatMinLevel = null, queueCapacity = 4097, retentionDays = 366, flushIntervalMillis = 1,
        )) { _, _, _ -> }
        try {
            assertTrue(engine.info("Policy", "auto-flush"))
            withTimeout(5000) {
                while (directory.listFiles().orEmpty().none { it.readText().contains("auto-flush") }) delay(10)
            }
            assertEquals(0L, engine.status().failed)
        } finally { engine.close(); root.deleteRecursively() }
    }
}
