package com.ttxz.base.logging

import com.ttxz.base.logging.internal.FileRecovery
import com.ttxz.base.logging.internal.LogFiles
import com.ttxz.base.logging.internal.escapedRecord
import com.ttxz.base.logging.internal.escaped
import com.ttxz.base.logging.internal.writeEscaped
import com.ttxz.base.logging.internal.escapedUtf8Bytes
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StageOneRegressionTest {
    private fun config() = LogConfig(logcatMinLevel = null, zoneId = ZoneId.of("UTC"),
        maxFileBytes = 1536, maxDirectoryBytes = 3072, flushIntervalMillis = 60_000)

    @Test fun bodyAndRedactionExpansionAreCompleteWhileTagAndStackStayBounded() = runBlocking {
        val root = Files.createTempDirectory("logger-complete").toFile()
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), config().copy(
            maxDirectoryBytes = 1024 * 1024,
            redact = { if (it == "expand") "\\".repeat(10_000) else it },
        )) { _, _, _ -> }
        try {
            engine.info("T", "\n".repeat(10_000))
            engine.info("T", "expand")
            engine.info("T".repeat(200), "x".repeat(10_000))
            engine.error("T", "stack", IllegalStateException().apply {
                stackTrace = Array(17) { StackTraceElement("C", "m", null, -1) }
            })
            assertTrue(engine.flush().isSuccessful)
            assertEquals(2L, engine.status().truncated)
            val lines = File(root, "logs").listFiles()!!.sortedBy { it.name }.flatMap { it.readLines() }
            assertEquals(21, lines.size)
            assertTrue(lines[0].endsWith("\\n".repeat(10_000)))
            assertTrue(lines[1].endsWith("\\\\".repeat(10_000)))
            assertTrue(lines[2].endsWith("x".repeat(10_000) + " [truncated]"))
            assertTrue(lines[3].endsWith("[T] stack"))
            assertTrue(lines.last().endsWith(" [truncated]"))
            assertEquals(0L, engine.status().failed)
            val escaped = escapedRecord("😀".repeat(100), 128)
            assertEquals(128, escaped.text.length)
            assertFalse(escaped.text.dropLast(12).last().isHighSurrogate())
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun largeUtf8RecordIsCompleteAndNextRecordRollsWithBoundedOutputChunks() = runBlocking {
        val root = Files.createTempDirectory("logger-large").toFile()
        val chunks = mutableListOf<String>()
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), LogConfig(
            logcatMinLevel = LogLevel.INFO, maxFileBytes = 1024 * 1024, maxDirectoryBytes = 4 * 1024 * 1024,
        )) { _, _, text -> chunks += text }
        val message = "测😀[\n".repeat(120_000) + "END"
        val expected = escaped(message, Int.MAX_VALUE)
        try {
            assertTrue(engine.info("Large", message))
            assertTrue(engine.flush().isSuccessful)
            assertEquals(expected, chunks.joinToString(""))
            assertTrue(chunks.all { it.length <= 1024 && !it.last().isHighSurrogate() })
            assertTrue(engine.info("Large", "next"))
            engine.flush()
            val files = File(root, "logs").listFiles()!!.sortedBy { it.name }
            assertEquals(2, files.size)
            assertTrue(files.first().length() > 1024 * 1024)
            assertEquals(expected, files.first().readLines().single().substringAfter("[Large] "))
            assertTrue(files.last().readText().endsWith("[Large] next\n"))
            assertEquals(0L, engine.status().truncated)
            assertEquals(2L, engine.status().written)
            ZipFile((engine.export() as ExportResult.Success).file).use { zip ->
                assertEquals(files.first().readText(), zip.getInputStream(zip.getEntry(files.first().name)).reader(Charsets.UTF_8).use { it.readText() })
            }
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun streamedEscapingMatchesByteBudgetAndOversizedRecordPreservesHistory() {
        val text = buildString { for (code in 0..65535) append(code.toChar()) }
        val chunks = mutableListOf<String>()
        writeEscaped(listOf(text)) { chunks += it }
        val output = chunks.joinToString("")
        assertEquals(escaped(text, Int.MAX_VALUE), output)
        assertEquals(output.toByteArray(Charsets.UTF_8).size.toLong(), escapedUtf8Bytes(listOf(text)))
        assertTrue(chunks.all { it.length <= 4096 })
        val root = Files.createTempDirectory("logger-large-budget").toFile()
        val files = LogFiles(root, config())
        val day = Instant.parse("2026-10-09T00:00:00Z").toEpochMilli()
        try {
            files.write(day, "keep"); files.flush()
            assertThrows(IllegalStateException::class.java) { files.write(day, "", listOf("x".repeat(4000))) }
            files.write(day, "next"); files.flush()
            assertEquals("keep\nnext\n", root.listFiles()!!.single().readText())
            // 单条放得下总预算、但当前活动文件放不下时，提前滚动并清理旧分片。
            files.write(day, "", listOf("测".repeat(1000))); files.flush()
            files.write(day, "", listOf("测".repeat(1000))); files.flush()
            assertTrue(root.listFiles()!!.sumOf { it.length() } <= 3072)
        } finally { files.close(); root.deleteRecursively() }
    }

    @Test fun oversizedRecordDoesNotBackOffHealthyWriter() = runBlocking {
        val root = Files.createTempDirectory("logger-oversize").toFile()
        val recovery = FileRecovery { 0L }
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), config(), recovery) { _, _, _ -> }
        try {
            engine.info("T", "before")
            engine.info("T", "x".repeat(4000))
            engine.info("T", "after")
            assertTrue(engine.flush().isSuccessful)
            assertEquals(2L, engine.status().written)
            assertEquals(1L, engine.status().failed)
            assertFalse(recovery.isWaiting())
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun exportFailuresAreCountedOnceAndCanRecover() = runBlocking {
        for (blockedSource in listOf(false, true)) {
            val root = Files.createTempDirectory("logger-export-error").toFile()
            val logs = File(root, "logs")
            val exports = File(root, "exports")
            val blocked = if (blockedSource) logs else exports
            blocked.writeText("occupied")
            val engine = LogEngine(logs, exports, config()) { _, _, _ -> }
            try {
                assertTrue(engine.export() is ExportResult.Failure)
                assertEquals(1L, engine.status().failed)
                assertEquals("IllegalStateException", engine.status().lastError)
                assertTrue(blocked.delete())
                engine.info("T", "healthy")
                engine.flush()
                assertEquals(1L, engine.status().written)
                assertTrue(engine.export() is ExportResult.Success)
                assertEquals(1L, engine.status().failed)
            } finally { engine.close(); root.deleteRecursively() }
        }
    }

    @Test fun boundedExceptionMessageIsRedactedAndPreservesFrames() = runBlocking {
        val root = Files.createTempDirectory("logger-message").toFile()
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), config().copy(
            maxDirectoryBytes = 16384, redact = { it.replace("secret", "masked") },
        )) { _, _, _ -> }
        try {
            val error = IllegalArgumentException("secret" + "x".repeat(1000), IllegalStateException("secret cause")).apply {
                stackTrace = arrayOf(StackTraceElement("Example", "run", "Example.kt", 42))
                cause!!.stackTrace = emptyArray()
            }
            engine.error("T", "diagnostic", error)
            engine.flush()
            val line = File(root, "logs").listFiles()!!.single().readText().trimEnd()
            assertFalse(line.contains("secret"))
            assertTrue(line.contains("IllegalArgumentException: masked"))
            assertTrue(line.contains("at Example.run(Example.kt:42)"))
            assertTrue(line.contains("\nCaused by: java.lang.IllegalStateException: masked cause"))
            assertTrue(line.endsWith(" [truncated]"))
            assertEquals(1L, engine.status().truncated)
            assertEquals(0L, engine.status().failed)
        } finally { engine.close(); root.deleteRecursively() }
    }

    // 替换真实写入器的流，确定性模拟 IO 故障；不依赖权限或磁盘竞态。
    private fun replaceStream(engine: LogEngine, replacement: (java.io.BufferedWriter) -> java.io.BufferedWriter) {
        val filesField = LogEngine::class.java.getDeclaredField("files").apply { isAccessible = true }
        val files = filesField.get(engine)
        val streamField = LogFiles::class.java.getDeclaredField("stream").apply { isAccessible = true }
        streamField.set(files, replacement(streamField.get(files) as java.io.BufferedWriter))
    }

    @Test fun redactionFailureKeepsHealthyStreamAndBufferedRecords() = runBlocking {
        val root = Files.createTempDirectory("logger-redact-isolation").toFile()
        var pendingWasUnflushed = false
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), config().copy(redact = {
            if (it == "bad") {
                pendingWasUnflushed = File(root, "logs").listFiles()!!.none { file -> file.readText().contains("pending") }
                throw IllegalArgumentException("redaction")
            }
            it
        })) { _, _, _ -> }
        var closes = 0
        try {
            engine.info("T", "before"); engine.flush()
            replaceStream(engine) { original -> object : java.io.BufferedWriter(original) {
                override fun close() { closes++; super.close() }
            } }
            engine.info("T", "pending")
            engine.info("T", "bad")
            engine.info("T", "after")
            assertTrue(engine.flush().isSuccessful)
            assertEquals(0, closes)
            assertTrue(pendingWasUnflushed)
            assertEquals(1L, engine.status().failed)
            assertEquals(3L, engine.status().written)
            val text = File(root, "logs").listFiles()!!.single().readText()
            assertTrue(text.contains("before")); assertTrue(text.contains("pending"))
            assertTrue(text.contains("after")); assertFalse(text.contains("bad"))
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun exportFlushFailureCountsOnceAndWriterRecovers() = runBlocking {
        val root = Files.createTempDirectory("logger-export-flush").toFile()
        var nanos = 0L
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), config(), FileRecovery { nanos }) { _, _, _ -> }
        try {
            engine.info("T", "before"); engine.flush()
            replaceStream(engine) { original -> object : java.io.BufferedWriter(original) {
                override fun flush() { throw java.io.IOException("injected") }
            } }
            assertEquals("IOException", (engine.export() as ExportResult.Failure).message)
            assertEquals(1L, engine.status().failed)
            assertEquals("IOException", engine.status().lastError)
            nanos += 1_000_000_000L
            engine.info("T", "after"); engine.flush()
            assertEquals(2L, engine.status().written)
            assertTrue(engine.export() is ExportResult.Success)
            assertEquals(1L, engine.status().failed)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun archiveWriteAndPublishFailuresCountOnceCleanTempsAndRecover() = runBlocking {
        for (failWrite in listOf(true, false)) {
            val root = Files.createTempDirectory("logger-archive-io").toFile()
            var inject = true
            val engine = LogEngine(File(root, "logs"), File(root, "exports"), config(),
                archiveOutput = { file ->
                    if (inject && failWrite) object : java.io.OutputStream() {
                        override fun write(value: Int) { throw java.io.IOException("injected") }
                    } else file.outputStream()
                },
                publishArchive = { source, target -> if (inject && !failWrite) false else source.renameTo(target) },
            ) { _, _, _ -> }
            try {
                engine.info("T", "retained"); engine.flush()
                assertTrue(engine.export() is ExportResult.Failure)
                assertEquals(1L, engine.status().failed)
                assertEquals(if (failWrite) "IOException" else "IllegalStateException", engine.status().lastError)
                assertTrue(File(root, "exports").listFiles()!!.isEmpty())
                assertTrue(File(root, "logs").listFiles()!!.single().readText().contains("retained"))
                inject = false
                assertTrue(engine.export() is ExportResult.Success)
                assertEquals(1L, engine.status().failed)
            } finally { engine.close(); root.deleteRecursively() }
        }
    }

    @Test fun emptyExportSucceedsWithoutAWriteAndDoesNotReportIoFailure() = runBlocking {
        val root = Files.createTempDirectory("logger-empty").toFile()
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), config()) { _, _, _ -> }
        try {
            val archive = engine.export() as ExportResult.Success
            ZipFile(archive.file).use { assertEquals(0, it.size()) }
            assertEquals(0L, engine.status().failed)
            assertTrue(engine.flush().isSuccessful)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun absentStreamFlushDoesNotResetBackoffAndRealFlushRecovers() = runBlocking {
        val root = Files.createTempDirectory("logger-backoff").toFile()
        val directory = File(root, "logs").apply { writeText("occupied") }
        var nanos = 0L
        val recovery = FileRecovery { nanos }
        val engine = LogEngine(directory, File(root, "exports"), config(), recovery) { _, _, _ -> }
        try {
            engine.info("T", "failure1"); engine.flush() // 1 秒
            nanos += 1_000_000_000L
            engine.flush() // 没有流，不得清除失败状态
            engine.info("T", "failure2"); engine.flush() // 2 秒
            directory.delete()
            nanos += 1_000_000_000L
            engine.info("T", "skip"); engine.flush()
            assertEquals(0L, engine.status().written)
            nanos += 1_000_000_000L
            engine.info("T", "recover"); engine.flush()
            assertEquals(1L, engine.status().written)
            assertFalse(recovery.isWaiting())
            assertEquals(3L, engine.status().failed)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun backoffCapsAtSixtySecondsAndHandlesNanoTimeWrap() {
        var nanos = Long.MAX_VALUE - 100
        val recovery = FileRecovery { nanos }
        for (delay in listOf(1, 2, 4, 8, 16, 32, 60, 60)) {
            recovery.failed()
            nanos += delay * 1_000_000_000L - 1
            assertTrue(recovery.isWaiting())
            nanos++
            assertFalse(recovery.isWaiting())
        }
        recovery.recovered()
        recovery.failed()
        nanos += 1_000_000_000L
        assertFalse(recovery.isWaiting())
    }

    @Test fun fullQueueNeverEvaluatesLazyBodyAndCallbackErrorDoesNotHideNewIoError() = runBlocking {
        val root = Files.createTempDirectory("logger-lazy").toFile()
        val context = LoggingContext()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val directory = File(root, "logs").apply { writeText("occupied") }
        try {
            context.initialize {
                LogEngine(directory, File(root, "exports"), config().copy(queueCapacity = 1, redact = {
                    entered.countDown(); release.await(5, TimeUnit.SECONDS); it
                })) { _, _, _ -> }
            }
            val logger = context.getLogger("T")
            assertFalse(logger.info { throw IllegalArgumentException("private") })
            assertEquals("IllegalArgumentException", context.status().lastError)
            assertTrue(logger.info("hold"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(logger.error { fail("Full queue evaluated expensive lambda"); "never" })
            release.countDown()
            context.flush()
            assertEquals("IllegalStateException", context.status().lastError)
            assertEquals(2L, context.status().failed)
        } finally { release.countDown(); context.close(1000); root.deleteRecursively() }
    }

    @Test fun producerAndOutputCallbacksCannotRecursivelyLogOrWaitForThemselves() = runBlocking {
        val root = Files.createTempDirectory("logger-recursive").toFile()
        val context = LoggingContext()
        try {
            context.initialize {
                LogEngine(File(root, "logs"), File(root, "exports"), config().copy(redact = {
                    assertFalse(runBlocking { context.flush() }.isSuccessful)
                    assertTrue(runBlocking { context.export() } is ExportResult.Failure)
                    assertFalse(context.getLogger("Recursive").info { fail("Recursive lambda evaluated"); "never" })
                    it
                })) { _, _, _ -> }
            }
            val logger = context.getLogger("T")
            assertTrue(logger.info {
                assertFalse(logger.warn { fail("Producer recursive lambda evaluated"); "never" })
                assertFalse(runBlocking { context.flush() }.isSuccessful)
                "safe"
            })
            assertTrue(context.flush().isSuccessful)
            assertEquals(1L, context.status().written)
            assertEquals(2L, context.status().dropped)
        } finally { context.close(1000); root.deleteRecursively() }
    }

    @Test fun everyLazyLevelSupportsThrowableAndOutputFiltersAreIndependent() = runBlocking {
        val root = Files.createTempDirectory("logger-levels").toFile()
        val context = LoggingContext()
        val output = mutableListOf<String>()
        try {
            context.initialize {
                LogEngine(File(root, "logs"), File(root, "exports"), config().copy(
                    fileMinLevel = LogLevel.WARN, logcatMinLevel = LogLevel.DEBUG,
                    redact = { it.replace("private", "masked") },
                )) { _, _, body -> output += body }
            }
            val logger = context.getLogger("Levels")
            val error = IllegalStateException("private").apply { stackTrace = emptyArray() }
            assertTrue(logger.isEnabled(LogLevel.DEBUG))
            assertTrue(logger.debug(error) { "debug" })
            assertTrue(logger.info(error) { "info" })
            assertTrue(logger.warn(error) { "warn" })
            assertTrue(logger.error(error) { "error" })
            assertTrue(context.flush().isSuccessful)
            assertEquals(4, output.size)
            assertTrue(output.all { it.contains("java.lang.IllegalStateException") && !it.contains("private") })
            val lines = File(root, "logs").listFiles()!!.flatMap { it.readLines() }
            assertEquals(4, lines.size)
            assertTrue(lines[0].contains("warn")); assertTrue(lines[2].contains("error"))
            assertEquals(2L, context.status().written)
        } finally { context.close(1000); root.deleteRecursively() }
    }

    @Test fun logcatFailureDoesNotPreventFileOutputOrSubsequentRecords() = runBlocking {
        val root = Files.createTempDirectory("logger-output-failure").toFile()
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), config().copy(logcatMinLevel = LogLevel.DEBUG)) {
            _, _, _ -> throw IllegalStateException("private")
        }
        try {
            engine.info("T", "first"); engine.info("T", "second")
            assertTrue(engine.flush().isSuccessful)
            assertEquals(2L, engine.status().written)
            assertEquals(2L, engine.status().failed)
            assertEquals("IllegalStateException", engine.status().lastError)
            assertEquals(2, File(root, "logs").listFiles()!!.single().readLines().size)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun reopeningRepairsPartialUtf8TailBeforeAppending() {
        val root = Files.createTempDirectory("logger-tail").toFile()
        val day = Instant.parse("2026-10-09T00:00:00Z").toEpochMilli()
        val file = File(root, "2026-10-09-000000.log")
        file.writeBytes("complete\npartial".toByteArray() + byteArrayOf(0xe4.toByte()))
        val files = LogFiles(root, config())
        try {
            files.write(day, "next")
            files.flush()
            assertEquals("complete\nnext\n", file.readText())
        } finally { files.close(); root.deleteRecursively() }
    }
}
