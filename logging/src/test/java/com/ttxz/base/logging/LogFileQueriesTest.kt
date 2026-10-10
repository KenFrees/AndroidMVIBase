package com.ttxz.base.logging

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class LogFileQueriesTest {
    @Test fun filtersAndSortsByDateAndNumericShardWithoutReadingOrChangingContent() = runBlocking {
        val root = Files.createTempDirectory("log-query").toFile()
        val names = listOf("2026-10-10-999999.log", "2026-10-10-1000000.log", "2026-10-10.log", "2026-10-11-000000.log", "2026-10-09.log")
        try {
            names.forEach { File(root, it).writeText("partial-tail") }
            listOf("2026-02-30.log", "unrelated.log", "2026-10-10-999999999999999999999.log", "2026-10-10.log.tmp").forEach { File(root, it).writeText("ignore") }
            File(root, "2026-10-12.log").mkdir()
            val initialization = LogInitialization(true, emptyList(), root.absolutePath)
            assertEquals(listOf(names[3], names[2], names[1], names[0], names[4]), initialization.logFiles().getOrThrow().map { it.name })
            assertEquals(listOf(names[2], names[1], names[0]), initialization.logFiles(LocalDate.parse("2026-10-10")).getOrThrow().map { it.name })
            assertTrue(initialization.logFiles(LocalDate.parse("2026-10-01")).getOrThrow().isEmpty())
            names.forEach { assertEquals("partial-tail", File(root, it).readText()) }
        } finally { root.deleteRecursively() }
    }

    @Test fun missingDirectoryReturnsEmptyAndIsNotCreated() = runBlocking {
        val root = Files.createTempDirectory("log-query-missing").toFile()
        val missing = File(root, "not-created")
        try {
            assertTrue(LogInitialization(true, emptyList(), missing.path).logFiles().getOrThrow().isEmpty())
            assertFalse(missing.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun disabledMissingInitializationAndInvalidPathAreFailures() = runBlocking {
        val file = Files.createTempFile("log-query-file", ".txt").toFile()
        try {
            assertTrue(LogInitialization(false, listOf("invalid"), file.path).logFiles().isFailure)
            assertTrue(LogInitialization(true, emptyList()).logFiles().isFailure)
            assertTrue(LogInitialization(true, emptyList(), file.path).logFiles().isFailure)
        } finally { file.delete() }
    }

    @Test fun cancellationDuringDirectoryListingPropagatesInsteadOfReturningFailure() = runBlocking {
        val root = Files.createTempDirectory("log-query-cancel").toFile()
        var entered = false
        try {
            try {
                queryLogFiles(LogInitialization(true, emptyList(), root.path), null) {
                    entered = true
                    throw CancellationException("cancel-during-listing")
                }
                fail("Query swallowed cancellation")
            } catch (cancelled: CancellationException) {
                assertEquals("cancel-during-listing", cancelled.message)
            }
            assertTrue("Directory listing was never entered", entered)
        } finally { root.deleteRecursively() }
    }

    @Test fun uninitializedPublicEntryChecksCancellationBeforeReturningFailure() = runBlocking {
        assertNull(Logging.initialization)
        var entered = false
        var propagated = false
        val caller = launch {
            currentCoroutineContext().cancel()
            entered = true
            try {
                Logging.logFiles()
                fail("Cancelled caller returned a query result")
            } catch (_: CancellationException) { propagated = true }
        }
        caller.join()
        assertTrue(entered)
        assertTrue(propagated)
    }

    @Test fun initializedDirectoryCanBeReadAfterFlushAndClose() = runBlocking {
        val root = Files.createTempDirectory("log-query-live").toFile()
        val context = LoggingContext()
        try {
            val initialized = context.initialize { LogEngine(File(root, "custom"), LogConfig(logcatMinLevel = null)) { _, _, _ -> } }
            assertTrue(context.getLogger("Query").info("persisted"))
            assertTrue(context.flush().isSuccessful)
            val files = initialized.logFiles().getOrThrow()
            assertEquals(1, files.size)
            assertTrue(files.single().readText().contains("persisted"))
            assertTrue(context.close(1000).isSuccessful)
            assertEquals(files, initialized.logFiles().getOrThrow())
        } finally { context.close(1000); root.deleteRecursively() }
    }
    @Test fun listingFailuresReturnFailureAndLaterQueriesCanRecover() = runBlocking {
        val root = Files.createTempDirectory("log-query-list-failure").toFile()
        try {
            val file = File(root, "2026-10-10.log").apply { writeText("preserved\n") }
            val initialization = LogInitialization(true, emptyList(), root.path)
            val unavailable = queryLogFiles(initialization, null) { null }
            assertTrue(unavailable.exceptionOrNull() is java.io.IOException)
            val denied = queryLogFiles(initialization, null) { throw SecurityException("denied") }
            assertTrue(denied.exceptionOrNull() is SecurityException)
            assertEquals("preserved\n", file.readText())
            assertEquals(listOf(file), initialization.logFiles().getOrThrow())
        } finally { root.deleteRecursively() }
    }

    @Test fun returnedFilesAreLiveReferencesAndRequeryReflectsRenameAndDeletion() = runBlocking {
        val root = Files.createTempDirectory("log-query-race").toFile()
        try {
            val original = File(root, "2026-10-10.log").apply { writeText("preserved\n") }
            val initialization = LogInitialization(true, emptyList(), root.path)
            val queried = initialization.logFiles().getOrThrow().single()
            val renamed = File(root, "2026-10-10-000000.log")
            assertTrue(original.renameTo(renamed))
            assertFalse(queried.exists())
            assertThrows(java.io.FileNotFoundException::class.java) { queried.inputStream().use { it.read() } }
            assertEquals(listOf(renamed), initialization.logFiles().getOrThrow())
            assertEquals("preserved\n", renamed.readText())
            assertTrue(renamed.delete())
            assertTrue(initialization.logFiles().getOrThrow().isEmpty())
        } finally { root.deleteRecursively() }
    }

}
