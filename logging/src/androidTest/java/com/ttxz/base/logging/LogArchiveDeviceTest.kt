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
}
