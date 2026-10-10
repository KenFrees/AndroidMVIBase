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
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 完整链路的故障与生命周期回归；不依赖真实磁盘满、权限变化或 sleep 竞态。 */
class ComprehensiveRegressionTest {
    private fun config() = LogConfig(logcatMinLevel = null, zoneId = ZoneId.of("UTC"),
        flushIntervalMillis = Long.MAX_VALUE)

    @Test fun cleanupFalseOrExceptionIsReportedWithoutLosingUsableArchive() = runBlocking {
        for (throws in listOf(false, true)) {
            val root = Files.createTempDirectory("logger-cleanup").toFile()
            var inject = true
            val engine = LogEngine(File(root, "logs"), File(root, "exports"), config(),
                removeExportFile = { file ->
                    if (inject) {
                        if (throws) throw IllegalStateException("injected")
                        false
                    } else file.deleteRecursively()
                },
            ) { _, _, _ -> }
            try {
                engine.info("T", "retained")
                val archive = engine.export() as ExportResult.Success
                ZipFile(archive.file).use { assertEquals(1, it.size()) }
                assertEquals(1L, engine.status().failed)
                assertEquals(if (throws) "IllegalStateException" else "IOException", engine.status().lastError)
                assertTrue(File(root, "exports").listFiles()!!.any { it.name.startsWith("snapshot-") })
                inject = false
                assertTrue(engine.export() is ExportResult.Success)
                assertTrue(File(root, "exports").listFiles()!!.none { it.name.startsWith("snapshot-") || it.name.endsWith(".tmp") })
                assertEquals(1L, engine.status().failed)
            } finally { engine.close(); root.deleteRecursively() }
        }
    }

    @Test fun cleanupExceptionDoesNotReplacePrimaryFailureOrCancellation() = runBlocking {
        for (cancel in listOf(false, true)) {
            val root = Files.createTempDirectory("logger-primary-error").toFile()
            val engine = LogEngine(File(root, "logs"), File(root, "exports"), config(),
                archiveOutput = {
                    if (cancel) throw CancellationException("cancel export")
                    throw IOException("archive failure")
                },
                removeExportFile = { throw IllegalArgumentException("cleanup failure") },
            ) { _, _, _ -> }
            try {
                engine.info("T", "retained")
                if (cancel) {
                    val export = async { engine.export() }
                    try { export.await(); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
                    assertEquals(1L, engine.status().failed) // 清理失败；取消本身不计失败。
                } else {
                    assertEquals("IOException", (engine.export() as ExportResult.Failure).message)
                    assertEquals(2L, engine.status().failed) // 两个不同 IO 操作失败。
                }
                assertEquals("IllegalArgumentException", engine.status().lastError)
                assertTrue(File(root, "logs").listFiles()!!.single().readText().contains("retained"))
            } finally { engine.close(); root.deleteRecursively() }
        }
    }

    @Test fun cancellationDuringArchiveWriteCleansTempsAndDoesNotFailLogger() = runBlocking {
        val root = Files.createTempDirectory("logger-copy-cancel").toFile()
        var exportJob: Job? = null
        var cancelledDuringWrite = false
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), config(),
            archiveOutput = { file -> object : java.io.FilterOutputStream(file.outputStream()) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    out.write(bytes, offset, length)
                    if (!cancelledDuringWrite) {
                        cancelledDuringWrite = true
                        exportJob!!.cancel()
                    }
                }
            } },
        ) { _, _, _ -> }
        try {
            val random = java.util.Random(1)
            val message = buildString { repeat(200_000) { append(('a'.code + random.nextInt(26)).toChar()) } }
            engine.info("T", message)
            val export = async(start = CoroutineStart.LAZY) { engine.export() }
            exportJob = export
            export.start()
            try { export.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
            assertTrue(cancelledDuringWrite)
            assertTrue(File(root, "exports").listFiles()!!.isEmpty())
            assertEquals(0L, engine.status().failed)
            engine.info("T", "after cancellation"); engine.flush()
            assertEquals(2L, engine.status().written)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun partialWriteExportsOnlyCompleteLinesAndNextWriteRepairsSource() = runBlocking {
        val root = Files.createTempDirectory("logger-partial-write").toFile()
        var nanos = 0L
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), config(), FileRecovery { nanos }) { _, _, _ -> }
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
            val archive = engine.export() as ExportResult.Success
            ZipFile(archive.file).use { zip ->
                val text = zip.getInputStream(zip.entries().nextElement()).reader(Charsets.UTF_8).use { it.readText() }
                assertTrue(text.endsWith("[T] before\n"))
                assertEquals(1, text.lines().filter { it.isNotEmpty() }.size)
            }
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
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), config()) { _, _, _ -> }
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
            context.initialize { LogEngine(File(root, "logs"), File(root, "exports"), config()) { _, _, _ -> } }
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
            assertTrue(context.export() is ExportResult.Failure)
        } finally { release.countDown(); pool.shutdownNow(); context.close(1000); root.deleteRecursively() }
    }
}
