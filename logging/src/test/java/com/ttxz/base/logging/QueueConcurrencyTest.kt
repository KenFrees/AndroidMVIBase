package com.ttxz.base.logging

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueueConcurrencyTest {
    @Test fun fullQueueRejectsAllLevelsConcurrentlyWithoutPreparingBodyAndSlotsRemainReusable() = runBlocking {
        val root = Files.createTempDirectory("queue-concurrency").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val prepared = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val engine = LogEngine(File(root, "logs"),
            LogConfig(queueCapacity = 1, fileMinLevel = LogLevel.DEBUG, logcatMinLevel = null, redact = {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); it
            })) { _, _, _ -> }
        try {
            assertTrue(engine.info("Test", "first")); assertTrue(entered.await(5, TimeUnit.SECONDS))
            val tasks = (0 until 32).map { index -> pool.submit<Boolean> {
                engine.recordLazy(LogLevel.entries[index % LogLevel.entries.size], "Test", null) { prepared.incrementAndGet(); "unexpected" }
            } }
            tasks.forEach { assertFalse(it.get(1, TimeUnit.SECONDS)) }
            assertEquals(0, prepared.get()); assertEquals(32L, engine.status().dropped)
            release.countDown(); assertTrue(engine.flush().isSuccessful)
            assertTrue(engine.info("Test", "after-full")); assertTrue(engine.flush().isSuccessful)
            assertEquals(2L, engine.status().written)
            engine.requestClose()
            val closed = (0 until 32).map { pool.submit<Boolean> { engine.info("Test", "closed") } }
            closed.forEach { assertFalse(it.get(1, TimeUnit.SECONDS)) }
            assertEquals(64L, engine.status().dropped)
        } finally {
            release.countDown()
            try {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            } finally {
                try { engine.close() } finally { root.deleteRecursively() }
            }
        }
    }
}
