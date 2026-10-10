package com.ttxz.base.logging

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LoggingContextTest {
    @Test fun initializationFeedbackExplainsSuccessFailureAndFallback() {
        val success = LogInitialization(true, listOf("datePattern"), "/private/logs").consoleMessage()
        assertTrue(success.contains("初始化成功"))
        assertTrue(success.contains("尚未验证磁盘写入"))
        assertTrue(success.contains("/private/logs"))
        assertTrue(success.contains("已回退默认格式"))
        val failure = LogInitialization(false, listOf("queueCapacity", "SecurityException")).consoleMessage()
        assertTrue(failure.contains("初始化失败"))
        assertTrue(failure.contains("队列容量必须大于 0"))
        assertTrue(failure.contains("访问日志资源被拒绝"))
    }

    @Test fun everyLevelAcceptsBoundedThrowableWithoutChangingPrefix() = runBlocking {
        val root = Files.createTempDirectory("logging-level-stack").toFile()
        val context = LoggingContext()
        try {
            context.initialize { LogEngine(File(root, "logs"), File(root, "exports"),
                LogConfig(redact = { it.replace("sensitive message", "masked") }, fileMinLevel = LogLevel.DEBUG, logcatMinLevel = null)) { _, _, _ -> } }
            val logger = context.getLogger("Stack")
            val exception = IllegalStateException("sensitive message").apply {
                stackTrace = arrayOf(StackTraceElement("Example", "operation", "Example.kt", 42))
            }
            assertTrue(logger.debug("debug", exception))
            assertTrue(logger.info("info", exception))
            assertTrue(logger.warn("warn", exception))
            assertTrue(logger.error("error", exception))
            assertTrue(logger.info("plain"))
            context.flush()
            val lines = File(root, "logs").listFiles()!!.single().readLines()
            assertEquals(13, lines.size)
            for ((index, level) in listOf("debug", "info", "warn", "error").withIndex()) {
                assertTrue(lines[index * 3].endsWith("[Stack] $level"))
                assertEquals("java.lang.IllegalStateException: masked", lines[index * 3 + 1])
                assertEquals("\tat Example.operation(Example.kt:42)", lines[index * 3 + 2])
                assertFalse(lines.any { it.contains("sensitive message") })
            }
            assertTrue(lines.last().endsWith("[Stack] plain"))
        } finally { context.close(1000); root.deleteRecursively() }
    }

    @Test fun concurrentLookupSharesIdentityAndClassName() {
        val context = LoggingContext()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val values = (1..100).map { pool.submit<Logger> { context.getLogger(javaClass.name) } }
                .map { it.get(5, TimeUnit.SECONDS) }
            assertTrue(values.all { it === values.first() })
            assertEquals(javaClass.name, values.first().name)
        } finally { pool.shutdownNow() }
    }

    @Test fun earlyLoggerWorksAfterInitializationAndDuplicateDoesNotRecreate() = runBlocking {
        val root = Files.createTempDirectory("logging-context").toFile()
        val context = LoggingContext()
        val logger = context.getLogger("Upload")
        assertFalse(logger.info("before"))
        var creations = 0
        try {
            assertTrue(context.initialize {
                creations++
                LogEngine(File(root, "logs"), File(root, "exports"), LogConfig(logcatMinLevel = null)) { _, _, _ -> }
            }.enabled)
            assertTrue(context.initialize { error("Must not recreate") }.warnings.contains("AlreadyInitialized"))
            assertEquals(1, creations)
            assertSame(logger, context.getLogger("Upload"))
            assertTrue(logger.info("after"))
            var evaluated = false
            assertFalse(logger.debug { evaluated = true; "filtered" })
            assertFalse(evaluated)
            context.flush()
            assertEquals(1L, context.status().written)
            assertEquals(1L, context.status().dropped)
            assertTrue(File(root, "logs").listFiles()!!.single().readText().contains("[Upload] after"))
            assertTrue(context.close(1000).isSuccessful)
            assertFalse(logger.info("closed"))
            assertTrue(context.initialize { error("Closed") }.warnings.contains("Closed"))
        } finally { context.close(1000); root.deleteRecursively() }
    }

    @Test fun initializationAndMessageCallbackFailuresAreReported() = runBlocking {
        val failed = LoggingContext()
        assertFalse(failed.initialize { throw IllegalArgumentException("private detail") }.enabled)
        assertEquals(listOf("IllegalArgumentException"), failed.initialization!!.warnings)
        assertFalse(failed.flush().isSuccessful)
        assertTrue(failed.export() is ExportResult.Failure)
        failed.close(1000)
        val root = Files.createTempDirectory("logging-callback").toFile()
        val context = LoggingContext()
        try {
            context.initialize { LogEngine(File(root, "logs"), File(root, "exports"), LogConfig(fileMinLevel = LogLevel.DEBUG, logcatMinLevel = null)) { _, _, _ -> } }
            assertFalse(context.getLogger("T").debug { throw IllegalStateException("private detail") })
            assertEquals(1L, context.status().failed)
            assertEquals("IllegalStateException", context.status().lastError)
        } finally { context.close(1000); root.deleteRecursively() }
    }

    @Test fun pressureReservesCapacityForWarningsAndRejectsRecursiveOutput() = runBlocking {
        val root = Files.createTempDirectory("logging-pressure").toFile()
        val context = LoggingContext()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var recursiveAccepted = true
        try {
            context.initialize {
                LogEngine(File(root, "logs"), File(root, "exports"), LogConfig(queueCapacity = 5, discardingThreshold = 2,
                    logcatMinLevel = null, redact = { body ->
                        recursiveAccepted = context.getLogger("Recursive").info("reentry")
                        if (body == "hold") { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                        body
                    })) { _, _, _ -> }
            }
            val logger = context.getLogger("T")
            assertTrue(logger.info("hold"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(3) { assertTrue(logger.info("low")) }
            assertFalse(logger.info("discarded"))
            assertTrue(logger.warn("important"))
            assertFalse(logger.error("full"))
            release.countDown()
            context.flush()
            assertFalse(recursiveAccepted)
            assertEquals(5L, context.status().written)
            assertEquals(7L, context.status().dropped) // 2 capacity drops + 5 recursion drops
        } finally { release.countDown(); context.close(1000); root.deleteRecursively() }
    }

    @Test fun closeTimeoutStopsAdmissionAndWriterFinishesLater() = runBlocking {
        val root = Files.createTempDirectory("logging-close").toFile()
        val context = LoggingContext()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            context.initialize {
                LogEngine(File(root, "logs"), File(root, "exports"), LogConfig(logcatMinLevel = null,
                    redact = { entered.countDown(); release.await(5, TimeUnit.SECONDS); it })) { _, _, _ -> }
            }
            val logger = context.getLogger("T")
            assertTrue(logger.info("pending"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(context.close(20).isSuccessful)
            assertFalse(logger.info("late"))
            release.countDown()
            assertTrue(context.close(1000).isSuccessful)
            assertEquals(1L, context.status().written)
        } finally { release.countDown(); context.close(1000); root.deleteRecursively() }
    }
}
