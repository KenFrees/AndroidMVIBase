package com.ttxz.base.logging

import com.ttxz.base.logging.internal.FileRecovery
import com.ttxz.base.logging.internal.LogFiles
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 通过目录占用和确定性时钟模拟故障，不依赖宿主磁盘满或权限差异。 */
class StorageReliabilityTest {
    private fun config() = LogConfig(zoneId = ZoneId.of("UTC"), logcatMinLevel = null,
        flushIntervalMillis = Long.MAX_VALUE)

    @Test fun directoryUnavailableBeforeReopenPreservesHistoryAndRecoversAfterBackoff() = runBlocking {
        val root = Files.createTempDirectory("log-directory-recovery").toFile()
        val directory = File(root, "configured")
        val saved = File(root, "temporarily-unavailable")
        var nanos = 0L
        val engine = LogEngine(directory, config(), FileRecovery { nanos }) { _, _, _ -> }
        try {
            assertTrue(engine.info("Storage", "persisted"))
            assertTrue(engine.flush().isSuccessful)
            // Windows 不允许移动含打开文件句柄的目录；先模拟流失效，再占用目录。
            val field = LogEngine::class.java.getDeclaredField("files").apply { isAccessible = true }
            (field.get(engine) as LogFiles).close()
            assertTrue(directory.renameTo(saved))
            directory.writeText("occupied")
            assertTrue(engine.info("Storage", "failed"))
            engine.flush()
            assertEquals(1L, engine.status().failed)
            assertTrue(engine.info("Storage", "during backoff"))
            engine.flush()
            assertEquals(2L, engine.status().failed)
            assertTrue(saved.listFiles()!!.single().readText().contains("persisted"))
            assertTrue(directory.delete())
            assertTrue(saved.renameTo(directory))
            nanos += 1_000_000_000L
            assertTrue(engine.info("Storage", "recovered"))
            assertTrue(engine.flush().isSuccessful)
            val text = directory.listFiles()!!.single().readText()
            assertTrue(text.contains("persisted")); assertTrue(text.contains("recovered"))
            assertFalse(text.contains("failed")); assertFalse(text.contains("during backoff"))
            assertEquals(4L, engine.status().accepted)
            assertEquals(2L, engine.status().written)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun firstShardRenameFailureKeepsSourceAndLaterRecoversWithoutOverwrite() = runBlocking {
        val root = Files.createTempDirectory("log-rename-recovery").toFile()
        val directory = File(root, "configured")
        var nanos = 0L
        val engine = LogEngine(directory, config().copy(maxFileBytes = 1),
            FileRecovery { nanos }) { _, _, _ -> }
        try {
            engine.info("Storage", "original"); engine.flush()
            val source = directory.listFiles()!!.single()
            val original = source.readBytes()
            val collision = File(directory, source.name.removeSuffix(".log") + "-000000.log")
            assertTrue(collision.mkdir())
            File(collision, "occupied").writeText("keep")
            engine.info("Storage", "cannot roll"); engine.flush()
            assertEquals(1L, engine.status().failed)
            assertArrayEquals(original, source.readBytes())
            assertEquals("keep", File(collision, "occupied").readText())
            assertTrue(collision.deleteRecursively())
            nanos += 1_000_000_000L
            engine.info("Storage", "after recovery"); engine.flush()
            val files = directory.listFiles()!!.sortedBy { it.name }
            assertEquals(2, files.size)
            assertArrayEquals(original, files[0].readBytes())
            assertTrue(files[1].readText().contains("after recovery"))
            assertEquals(2L, engine.status().written)
            assertEquals(1L, engine.status().failed)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun retentionDeletionFailurePreservesFilesAndCanRetryInConfiguredDirectory() {
        val root = Files.createTempDirectory("log-cleanup-retry").toFile()
        val configured = File(root, "custom").apply { mkdir() }
        val untouched = File(root, "other.log").apply { writeText("unrelated") }
        val old = File(configured, "2026-10-01.log").apply { writeText("old\n") }
        val current = File(configured, "2026-10-10.log").apply { writeText("current\n") }
        var refuseDelete = true
        val directory = object : File(configured.path) {
            override fun listFiles(): Array<File>? = super.listFiles()?.map { file ->
                if (file == old) object : File(file.path) {
                    override fun delete(): Boolean = if (refuseDelete) false else super.delete()
                } else file
            }?.toTypedArray()
        }
        val files = LogFiles(directory, config().copy(retentionDays = 2))
        val time = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        try {
            try { files.cleanup(time); fail("Expected cleanup failure") } catch (_: IllegalStateException) { }
            assertEquals("old\n", old.readText())
            assertEquals("current\n", current.readText())
            refuseDelete = false
            files.cleanup(time)
            assertFalse(old.exists())
            assertEquals("current\n", current.readText())
            assertEquals("unrelated", untouched.readText())
        } finally { files.close(); root.deleteRecursively() }
    }

    @Test fun partialMultilineTailReopenPreservesCompletedLines() = runBlocking {
        val root = Files.createTempDirectory("log-multiline-tail").toFile()
        val directory = File(root, "custom").apply { mkdir() }
        val file = File(directory, "2026-10-10.log")
        val complete = "2026-10-10 00:00:00.000 [Storage] failure\njava.lang.IllegalStateException: reason\n\tat Example.run(Example.kt:1)\n"
        file.writeBytes(complete.toByteArray() + "\tat broken".toByteArray() + byteArrayOf(0xe4.toByte()))
        try {
            assertTrue(file.length() > complete.toByteArray().size)
            // 使用历史文件对应的日期直接验证重开修复，不依赖执行当天。
            val files = LogFiles(directory, config())
            try {
                files.write(Instant.parse("2026-10-10T00:00:01Z").toEpochMilli(), "next historical")
                files.flush()
                assertEquals(complete + "next historical\n", file.readText())
            } finally { files.close() }
        } finally { root.deleteRecursively() }
    }
}
