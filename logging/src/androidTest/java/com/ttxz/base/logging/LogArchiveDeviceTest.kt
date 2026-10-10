package com.ttxz.base.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实外部目录和后台 Tick 验收，不修改设备时间或示例 app 数据。 */
@RunWith(AndroidJUnit4::class)
class LogArchiveDeviceTest {
    @Test fun backgroundMaintenanceArchivesHistoryAndKeepsCurrentWritesWorking() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val config = LogConfig(directoryName = "archive-${UUID.randomUUID()}",
            logcatMinLevel = null, flushIntervalMillis = 100)
        val root = File(app.getExternalFilesDir(null), config.directoryName).apply { mkdirs() }
        val source = File(root, "${LocalDate.now(config.zoneId).minusDays(1)}.log")
        val expected = "中文诊断\njava.lang.IllegalStateException: 示例\n\tat Demo.run(Demo.kt:1)\n".repeat(1000)
        source.writeText(expected)
        val archive = File(root, source.name + ".gz")
        val engine = LogEngine.create(app, config)
        try {
            assertTrue(engine.info("Archive", "current-before")); assertTrue(engine.flush().isSuccessful)
            val deadline = System.nanoTime() + 15_000_000_000L
            while ((!archive.exists() || source.exists()) && System.nanoTime() < deadline) delay(50)
            assertTrue(archive.isFile); assertFalse(source.exists())
            assertEquals(expected, GZIPInputStream(archive.inputStream()).bufferedReader().use { it.readText() })
            assertTrue(engine.info("Archive", "current-after")); assertTrue(engine.flush().isSuccessful)
            val initialization = LogInitialization(true, emptyList(), root.path)
            assertEquals(listOf(archive), initialization.logFiles(LocalDate.now(config.zoneId).minusDays(1)).getOrThrow())
            val current = initialization.logFiles(LocalDate.now(config.zoneId)).getOrThrow().single()
            assertTrue(current.name.endsWith(".log"))
            val text = current.readText()
            assertTrue(text.contains("current-before")); assertTrue(text.contains("current-after"))
            assertEquals(0L, engine.status().failed)
        } finally { engine.close(); root.deleteRecursively() }
    }
    @Test fun archivesWhilePacedMainThreadWritesRemainAccountedFor() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val config = LogConfig(directoryName = "archive-load-${UUID.randomUUID()}",
            maxDirectoryBytes = 64L * 1024 * 1024, logcatMinLevel = null, flushIntervalMillis = 100)
        val root = File(app.getExternalFilesDir(null), config.directoryName).apply { mkdirs() }
        val source = File(root, "${LocalDate.now(config.zoneId).minusDays(1)}.log")
        val random = java.util.Random(24)
        source.outputStream().use { output ->
            val buffer = ByteArray(8192)
            repeat(1024) { random.nextBytes(buffer); output.write(buffer) }
        }
        val archive = File(root, source.name + ".gz")
        val accepted = BooleanArray(10000)
        var maximumNanos = 0L
        var peakHeap = 0L
        var observedTemporary = false
        val engine = LogEngine.create(app, config)
        try {
            repeat(1000) { tick ->
                instrumentation.runOnMainSync {
                    repeat(10) { item ->
                        val id = tick * 10 + item
                        val started = System.nanoTime()
                        accepted[id] = engine.info("ArchiveLoad", "record=$id")
                        maximumNanos = maxOf(maximumNanos, System.nanoTime() - started)
                    }
                }
                observedTemporary = observedTemporary || File(root, source.name + ".gz.tmp").exists()
                peakHeap = maxOf(peakHeap, Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() })
                delay(10)
            }
            assertTrue(engine.flush().isSuccessful)
            val deadline = System.nanoTime() + 20_000_000_000L
            while ((!archive.exists() || source.exists()) && System.nanoTime() < deadline) delay(50)
            assertTrue("No compression overlap observed", observedTemporary)
            assertTrue(archive.isFile); assertFalse(source.exists())
            val status = engine.status()
            assertEquals(10000L, status.accepted + status.dropped)
            assertEquals(accepted.count { it }.toLong(), status.written)
            assertEquals(0L, status.failed)
            val seen = BooleanArray(10000)
            root.listFiles()!!.filter { it.name.endsWith(".log") }.forEach { file ->
                file.forEachLine { line ->
                    val id = line.substringAfter("[ArchiveLoad] record=", "").toIntOrNull()
                    if (id != null) { assertFalse(seen[id]); seen[id] = true }
                }
            }
            assertArrayEquals(accepted, seen)
            println("LOGGER_ARCHIVE_LOAD_PASS accepted=${status.accepted} dropped=${status.dropped} failed=${status.failed} peakHeap=$peakHeap maxSubmitUs=${maximumNanos / 1000}")
        } finally { engine.close(); root.deleteRecursively() }
    }

}
