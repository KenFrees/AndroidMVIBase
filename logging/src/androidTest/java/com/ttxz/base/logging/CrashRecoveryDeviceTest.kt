package com.ttxz.base.logging

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 两段手动 instrumentation 验收；普通 connected 测试排除该类，不操作示例应用。 */
@RunWith(AndroidJUnit4::class)
class CrashRecoveryDeviceTest {
    @Test fun prepareForForceStop() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("crashStage") == "prepare")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = LogEngine.create(app, LogConfig(directoryName = "crash-recovery-stage2", logcatMinLevel = null,
            redact = { if (it == "pending") { entered.countDown(); release.await(45, TimeUnit.SECONDS) }; it }))
        val directory = File(engine.logDirectory)
        check(directory.deleteRecursively()) { "Cannot prepare crash recovery directory" }
        try {
            assertTrue(engine.error("Recovery", "persisted", IllegalStateException("force-stop stack").apply {
                stackTrace = arrayOf(StackTraceElement("Recovery", "prepare", "Recovery.kt", 1))
            }))
            assertTrue(engine.flush().isSuccessful)
            assertTrue(engine.info("Recovery", "pending"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "LOGGER_CRASH_READY\n") })
            delay(40_000) // host 在 READY 后 force-stop；finally 不会被执行。
            fail("Host did not force-stop the test process")
        } finally { release.countDown(); engine.close() }
    }

    @Test fun verifyAfterForceStop() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("crashStage") == "verify")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = LogEngine.create(app, LogConfig(directoryName = "crash-recovery-stage2", logcatMinLevel = null))
        val directory = File(engine.logDirectory)
        try {
            val before = directory.listFiles()!!.flatMap { it.readLines() }
            assertTrue(before.any { it.endsWith("[Recovery] persisted") })
            assertFalse(before.any { it.contains("pending") })
            assertTrue(engine.info("Recovery", "after restart"))
            assertTrue(engine.flush().isSuccessful)
            val lines = directory.listFiles()!!.flatMap { it.readLines() }
            assertEquals(4, lines.size)
            assertTrue(lines.contains("java.lang.IllegalStateException: force-stop stack"))
            assertTrue(lines.contains("\tat Recovery.prepare(Recovery.kt:1)"))
            assertTrue(lines.any { it.endsWith("[Recovery] after restart") })
            println("LOGGER_CRASH_RECOVERY persisted=1 pendingLost=1 newWritten=1")
        } finally { engine.close(); directory.deleteRecursively() }
    }
    /** 显式 archiveCrashStage=prepare 才会杀死自身；保留现场供下一次 instrumentation 验证。 */
    @Test fun prepareArchiveCrash() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("archiveCrashStage") == "prepare")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.getExternalFilesDir(null), "archive-crash-check")
        check(!root.exists()) { "Archive crash fixture already exists; verify or inspect it first" }
        check(root.mkdirs())
        val day = java.time.LocalDate.now(java.time.ZoneId.of("UTC")).minusDays(1)
        val source = File(root, "$day.log")
        val random = java.util.Random(42)
        source.outputStream().use { output ->
            val block = ByteArray(8192)
            repeat(1024) { random.nextBytes(block); output.write(block) }
        }
        File(root, "expected.sha256").writeText(archiveDigest(source))
        val temporary = File(root, source.name + ".gz.tmp")
        val watcher = Thread {
            val deadline = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < deadline) {
                if (temporary.length() > 0) {
                    File(root, "kill-observed.txt").writeText("temporaryBytes=${temporary.length()}")
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@Thread
                }
                Thread.sleep(1)
            }
        }
        watcher.start()
        val engine = LogEngine.create(app, LogConfig(directoryName = root.name, zoneId = java.time.ZoneId.of("UTC"),
            maxDirectoryBytes = 64L * 1024 * 1024, flushIntervalMillis = 100, logcatMinLevel = null))
        try { delay(25_000); fail("Did not terminate during archive output") }
        finally { engine.close(); watcher.join() }
    }

    @Test fun verifyArchiveCrash() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("archiveCrashStage") == "verify")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.getExternalFilesDir(null), "archive-crash-check")
        assertTrue(File(root, "kill-observed.txt").isFile)
        val source = root.listFiles()!!.single { it.name.endsWith(".log") }
        val expected = File(root, "expected.sha256").readText()
        assertEquals(expected, archiveDigest(source))
        assertTrue(File(root, source.name + ".gz.tmp").length() > 0)
        val archive = File(root, source.name + ".gz")
        assertFalse(archive.exists())
        val engine = LogEngine.create(app, LogConfig(directoryName = root.name, zoneId = java.time.ZoneId.of("UTC"),
            maxDirectoryBytes = 64L * 1024 * 1024, flushIntervalMillis = 100, logcatMinLevel = null))
        try {
            assertTrue(engine.info("Recovery", "after archive crash"))
            assertTrue(engine.flush().isSuccessful)
            val deadline = System.nanoTime() + 20_000_000_000L
            while ((!archive.exists() || source.exists()) && System.nanoTime() < deadline) delay(50)
            assertFalse(source.exists()); assertTrue(archive.isFile)
            assertFalse(File(root, source.name + ".gz.tmp").exists())
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            java.util.zip.GZIPInputStream(archive.inputStream()).use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it) })
            assertEquals(0L, engine.status().failed)
            println("LOGGER_ARCHIVE_CRASH_PASS " + File(root, "kill-observed.txt").readText())
        } finally { engine.close(); root.deleteRecursively() }
    }

    private fun archiveDigest(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

}
