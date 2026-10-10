package com.ttxz.base.logging

import com.ttxz.base.logging.internal.LogFiles
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LogArchiveTest {
    private val now = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
    private val config = LogConfig(zoneId = ZoneId.of("UTC"), logcatMinLevel = null)
    private fun unzip(file: File) = GZIPInputStream(file.inputStream()).use { it.readBytes() }
    private fun gzip(file: File, bytes: ByteArray) = GZIPOutputStream(file.outputStream()).use { it.write(bytes) }
    private fun directory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("log-archive").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun archivesOneHistoricalShardAtATimeAndPreservesExactMultilineUtf8() = directory { root ->
        val first = File(root, "2026-10-09-000000.log").apply { writeText("中文\n\tat Stack.call(Stack.kt:1)\n残尾") }
        val bytes = first.readBytes()
        assertTrue(first.setLastModified(1_600_000_000_000L))
        val second = File(root, "2026-10-09-000001.log").apply { writeText("second\n") }
        val today = File(root, "2026-10-10.log").apply { writeText("today\n") }
        val writer = LogFiles(root, config)
        try {
            assertTrue(writer.archiveOne(now)); assertFalse(first.exists()); assertTrue(second.exists())
            assertArrayEquals(bytes, unzip(File(root, first.name + ".gz")))
            assertEquals(1_600_000_000_000L, File(root, first.name + ".gz").lastModified())
            assertTrue(writer.archiveOne(now)); assertFalse(second.exists())
            assertFalse(writer.archiveOne(now)); assertEquals("today\n", today.readText())
        } finally { writer.close() }
    }

    @Test fun closesPreviousDayBufferBeforeArchivingAndContinuesWriting() = directory { root ->
        val writer = LogFiles(root, config)
        try {
            writer.write(now - 86_400_000, "buffered yesterday")
            assertTrue(writer.archiveOne(now))
            assertEquals("buffered yesterday\n", unzip(File(root, "2026-10-09.log.gz")).toString(Charsets.UTF_8))
            writer.write(now, "today"); writer.flush()
            assertEquals("today\n", File(root, "2026-10-10.log").readText())
        } finally { writer.close() }
    }

    @Test fun recoversInterruptedTemporaryAndPublishedArchiveWithoutLosingSource() = directory { root ->
        val source = File(root, "2026-10-09.log").apply { writeText("original\n") }
        File(root, source.name + ".gz.tmp").writeText("partial")
        val writer = LogFiles(root, config)
        try {
            assertTrue(writer.archiveOne(now)); assertFalse(File(root, source.name + ".gz.tmp").exists())
            source.writeText("original\n") // 模拟发布后尚未删除源文件。
            assertTrue(writer.archiveOne(now)); assertFalse(source.exists())
            assertEquals("original\n", unzip(File(root, source.name + ".gz")).toString(Charsets.UTF_8))
        } finally { writer.close() }
    }

    @Test fun conflictingOrCorruptArchiveNeverDeletesOrOverwritesSource() = directory { root ->
        val source = File(root, "2026-10-09.log").apply { writeText("keep\n") }
        val target = File(root, source.name + ".gz")
        gzip(target, "different\n".toByteArray())
        val writer = LogFiles(root, config)
        try {
            assertThrows(IllegalStateException::class.java) { writer.archiveOne(now) }
            assertEquals("keep\n", source.readText())
            assertEquals("different\n", unzip(target).toString(Charsets.UTF_8))
            target.writeText("broken")
            assertThrows(java.io.IOException::class.java) { writer.archiveOne(now) }
            assertEquals("keep\n", source.readText()); assertEquals("broken", target.readText())
        } finally { writer.close() }
    }

    @Test fun temporaryBudgetExhaustionKeepsSourceAndRemovesTemporary() = directory { root ->
        val source = File(root, "2026-10-09.log").apply {
            writeBytes(ByteArray(1000).also { java.util.Random(1).nextBytes(it) })
        }
        val bytes = source.readBytes()
        val writer = LogFiles(root, config.copy(maxFileBytes = 1024, maxDirectoryBytes = 1030))
        try {
            assertFalse(writer.archiveOne(now))
            assertArrayEquals(bytes, source.readBytes())
            assertEquals(listOf(source.name), root.listFiles()!!.map { it.name })
        } finally { writer.close() }
    }

    @Test fun firstWriteRemovesInterruptedTemporaryBeforeCalculatingBudget() = directory { root ->
        val source = File(root, "2026-10-09.log").apply { writeText("keep\n") }
        val temporary = File(root, source.name + ".gz.tmp").apply { writeBytes(ByteArray(1024)) }
        val writer = LogFiles(root, config.copy(maxFileBytes = 1024, maxDirectoryBytes = 1024))
        try {
            writer.write(now, "new"); writer.flush()
            assertFalse(temporary.exists()); assertEquals("keep\n", source.readText())
            assertTrue(root.listFiles()!!.sumOf { it.length() } <= 1024)
        } finally { writer.close() }
    }

    @Test fun expiredArchivesAreDeletedAndCompressedBytesCountTowardBudget() = directory { root ->
        gzip(File(root, "2026-10-01.log.gz"), "expired".toByteArray())
        gzip(File(root, "2026-10-09.log.gz"), ByteArray(500).also { java.util.Random(2).nextBytes(it) })
        File(root, "unrelated.gz").writeText("keep")
        val writer = LogFiles(root, config.copy(maxFileBytes = 100, maxDirectoryBytes = 100))
        try {
            writer.cleanup(now)
            assertFalse(File(root, "2026-10-01.log.gz").exists())
            assertFalse(File(root, "2026-10-09.log.gz").exists())
            assertEquals("keep", File(root, "unrelated.gz").readText())
        } finally { writer.close() }
    }

    @Test fun clockRollbackCreatesNewShardWithoutAppendingToCompressedFile() = directory { root ->
        gzip(File(root, "2026-10-09.log.gz"), "old\n".toByteArray())
        gzip(File(root, "2026-10-09-000000.log.gz"), "shard\n".toByteArray())
        val writer = LogFiles(root, config)
        try {
            writer.write(now - 86_400_000, "rollback"); writer.flush()
            assertEquals("rollback\n", File(root, "2026-10-09-000001.log").readText())
            assertEquals("shard\n", unzip(File(root, "2026-10-09-000000.log.gz")).toString(Charsets.UTF_8))
        } finally { writer.close() }
    }

    @Test fun rollbackDoesNotAppendToPublishedButNotDeletedPlainSource() = directory { root ->
        val source = File(root, "2026-10-09.log").apply { writeText("old\n") }
        gzip(File(root, source.name + ".gz"), source.readBytes())
        val writer = LogFiles(root, config)
        try {
            writer.write(now - 86_400_000, "new"); writer.flush()
            assertEquals("old\n", source.readText())
            assertEquals("new\n", File(root, "2026-10-09-000000.log").readText())
            repeat(2) { assertTrue(writer.archiveOne(now)) }
            assertFalse(source.exists())
            assertEquals("old\n", unzip(File(root, source.name + ".gz")).toString(Charsets.UTF_8))
        } finally { writer.close() }
    }

    @Test fun rollbackWithExistingShardsKeepsPublishedSourceNameForRecovery() = directory { root ->
        val source = File(root, "2026-10-09.log").apply { writeText("old\n") }
        gzip(File(root, source.name + ".gz"), source.readBytes())
        gzip(File(root, "2026-10-09-000000.log.gz"), "shard\n".toByteArray())
        val writer = LogFiles(root, config)
        try {
            writer.write(now - 86_400_000, "new"); writer.flush()
            assertEquals("old\n", source.readText())
            assertEquals("new\n", File(root, "2026-10-09-000001.log").readText())
            repeat(2) { assertTrue(writer.archiveOne(now)) }
            assertFalse(source.exists())
            assertEquals("new\n", unzip(File(root, "2026-10-09-000001.log.gz")).toString(Charsets.UTF_8))
        } finally { writer.close() }
    }

    @Test fun queryReturnsArchivesInNumericOrderAndIgnoresTemporary() = runBlocking {
        val root = Files.createTempDirectory("log-archive-query").toFile()
        try {
            val names = listOf("2026-10-09-000010.log.gz", "2026-10-09-000002.log", "2026-10-09.log.gz")
            (names + "2026-10-09.log.gz.tmp").forEach { File(root, it).writeText("not read by query") }
            val found = LogInitialization(true, emptyList(), root.path).logFiles(LocalDate.parse("2026-10-09")).getOrThrow()
            assertEquals(names, found.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun sourceDeletionFailureIsRecoveredOnlyAfterVerifyingPublishedArchive() = directory { root ->
        val source = File(root, "2026-10-09.log").apply { writeText("preserve\n") }
        var allowDelete = false
        val directory = object : File(root.path) {
            override fun listFiles(): Array<File>? = super.listFiles()?.map { child ->
                if (child.name != source.name) child else object : File(child.path) {
                    override fun delete(): Boolean = allowDelete && super.delete()
                }
            }?.toTypedArray()
        }
        val writer = LogFiles(directory, config)
        try {
            assertThrows(IllegalStateException::class.java) { writer.archiveOne(now) }
            assertEquals("preserve\n", source.readText())
            assertEquals("preserve\n", unzip(File(root, source.name + ".gz")).toString(Charsets.UTF_8))
            allowDelete = true
            assertTrue(writer.archiveOne(now)); assertFalse(source.exists())
        } finally { writer.close() }
    }

    @Test fun conflictingArchiveDoesNotStarveOtherFilesAndIsRetried() = directory { root ->
        val first = File(root, "2026-10-08.log").apply { writeText("first\n") }
        val target = File(root, first.name + ".gz")
        gzip(target, "conflict".toByteArray())
        val second = File(root, "2026-10-09.log").apply { writeText("second\n") }
        val writer = LogFiles(root, config)
        try {
            assertThrows(IllegalStateException::class.java) { writer.archiveOne(now) }
            assertTrue(writer.archiveOne(now))
            assertFalse(second.exists()); assertTrue(first.exists())
            assertEquals("second\n", unzip(File(root, second.name + ".gz")).toString(Charsets.UTF_8))
            assertTrue(target.delete())
            assertTrue(writer.archiveOne(now)); assertFalse(first.exists())
        } finally { writer.close() }
    }

    @Test fun budgetBlockedArchiveDoesNotStarveSmallerOutput() = directory { root ->
        val first = File(root, "2026-10-08.log").apply {
            writeBytes(ByteArray(1000).also { java.util.Random(1).nextBytes(it) })
        }
        val second = File(root, "2026-10-09.log").apply { writeBytes(ByteArray(1000)) }
        val writer = LogFiles(root, config.copy(maxFileBytes = 1024, maxDirectoryBytes = 2100))
        try {
            assertFalse(writer.archiveOne(now)); assertTrue(first.exists())
            assertTrue(writer.archiveOne(now)); assertFalse(second.exists())
            assertArrayEquals(ByteArray(1000), unzip(File(root, second.name + ".gz")))
            assertTrue(writer.archiveOne(now)); assertFalse(first.exists())
            assertTrue(root.listFiles()!!.sumOf { it.length() } <= 2100)
        } finally { writer.close() }
    }

    @Test fun queryRanksRollbackShardBeforeBareArchiveAndItsLeftoverSource() = runBlocking {
        val root = Files.createTempDirectory("log-query-rollback").toFile()
        val writer = LogFiles(root, config)
        try {
            val source = File(root, "2026-10-09.log").apply { writeText("old\n") }
            gzip(File(root, source.name + ".gz"), source.readBytes())
            writer.write(now - 86_400_000, "new"); writer.flush()
            val result = LogInitialization(true, emptyList(), root.path).logFiles().getOrThrow()
            assertEquals(listOf("2026-10-09-000000.log", "2026-10-09.log", "2026-10-09.log.gz"), result.map { it.name })
            assertEquals("new\n", result.first().readText())
            assertTrue(source.delete())
            assertEquals("2026-10-09-000000.log", LogInitialization(true, emptyList(), root.path).logFiles().getOrThrow().first().name)
        } finally { writer.close(); root.deleteRecursively() }
    }

    @Test fun configuredCalendarDayIsUsedAndFutureAndUnknownFilesAreProtected() = directory { root ->
        val writer = LogFiles(root, config.copy(zoneId = ZoneId.of("Asia/Shanghai")))
        // UTC 10 日 23 点是配置时区 11 日 7 点，10 日已可归档。
        val timestamp = Instant.parse("2026-10-10T23:00:00Z").toEpochMilli()
        File(root, "2026-10-10.log").writeText("yesterday\n")
        File(root, "2026-10-11.log").writeText("today\n")
        File(root, "2026-10-12.log").writeText("future\n")
        File(root, "other.log.gz.tmp").writeText("unknown\n")
        try {
            assertTrue(writer.archiveOne(timestamp)); assertFalse(writer.archiveOne(timestamp))
            assertTrue(File(root, "2026-10-10.log.gz").exists())
            assertEquals("today\n", File(root, "2026-10-11.log").readText())
            assertEquals("future\n", File(root, "2026-10-12.log").readText())
            assertEquals("unknown\n", File(root, "other.log.gz.tmp").readText())
        } finally { writer.close() }
    }
}
