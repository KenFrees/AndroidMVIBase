package com.ttxz.base.logging

import com.ttxz.base.logging.internal.LogFiles
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LogFileNamingTest {
    @Test fun plainFileIsRenamedOnlyWhenRollingAndReopenAppendsLatestShard() {
        val root = Files.createTempDirectory("log-naming").toFile()
        val config = LogConfig(zoneId = ZoneId.of("UTC"), maxFileBytes = 6, maxDirectoryBytes = 100)
        val time = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        val files = LogFiles(root, config)
        try {
            files.write(time, "first")
            files.close()
            assertEquals("first\n", File(root, "2026-10-10.log").readText())
            files.write(time, "two")
            files.close()
            assertFalse(File(root, "2026-10-10.log").exists())
            assertEquals("first\n", File(root, "2026-10-10-000000.log").readText())
            assertEquals("two\n", File(root, "2026-10-10-000001.log").readText())
            files.write(time, "x")
            files.close()
            assertEquals("two\nx\n", File(root, "2026-10-10-000001.log").readText())
            files.write(time + 86_400_000, "new")
            files.close()
            assertEquals("new\n", File(root, "2026-10-11.log").readText())
        } finally { files.close(); root.deleteRecursively() }
    }

    @Test fun coexistenceDoesNotOverwriteExistingShardAndSnapshotIncludesPlainFile() {
        val root = Files.createTempDirectory("log-coexist").toFile()
        val snapshot = Files.createTempDirectory("log-snapshot").toFile()
        val time = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        val files = LogFiles(root, LogConfig(zoneId = ZoneId.of("UTC")))
        try {
            File(root, "2026-10-10-000000.log").writeText("old\n")
            File(root, "2026-10-10.log").writeText("plain\n")
            files.write(time, "next")
            files.close()
            assertEquals("old\n", File(root, "2026-10-10-000000.log").readText())
            assertEquals("plain\nnext\n", File(root, "2026-10-10-000001.log").readText())
            files.write(time + 86_400_000, "new")
            files.flush()
            files.snapshot(snapshot)
            assertTrue(File(snapshot, "2026-10-11.log").isFile)
        } finally { files.close(); root.deleteRecursively(); snapshot.deleteRecursively() }
    }
}
