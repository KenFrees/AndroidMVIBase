package com.ttxz.base.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 仅宿主显式传入专用模拟器的小容量挂载点才运行，不填充真机存储。 */
@RunWith(AndroidJUnit4::class)
class DiskFullDeviceTest {
    @Test fun physicalEnospcPreservesHistoryAndRecoversAfterSpaceIsReleased() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("diskFullDirectory")
        assumeTrue(path != null)
        require(path == "/data/local/tmp/logging-enospc")
        val root = File(path!!)
        check(root.isDirectory && root.totalSpace in 1..(2L * 1024 * 1024))
        var engine: LogEngine? = null
        try {
            val history = File(root, "${java.time.LocalDate.now().minusDays(1)}.log").apply { writeText("preserve history\n") }
            val filler = File(root, "test-filler.bin")
            var exhausted = false
            try {
                filler.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    repeat(512) { output.write(buffer) }
                }
            } catch (error: IOException) {
                assertTrue("Expected ENOSPC: $error", error.toString().contains("ENOSPC") || error.toString().contains("No space left"))
                exhausted = true
            }
            assertTrue("Did not reach real ENOSPC", exhausted)
            val current = LogEngine(root, LogConfig(logcatMinLevel = null, flushIntervalMillis = 100)) { _, _, _ -> }
            engine = current
            assertTrue(current.info("DiskFull", "failed while full"))
            current.flush()
            assertTrue(current.status().failed > 0)
            assertEquals("preserve history\n", history.readText())
            assertTrue(filler.delete())
            delay(1500)
            assertTrue(current.info("DiskFull", "recovered after releasing space"))
            assertTrue(current.flush().isSuccessful)
            assertTrue(root.listFiles()!!.filter { it.name.endsWith(".log") }.any { it.readText().contains("recovered after releasing space") })
            println("LOGGER_ENOSPC_PASS failed=${current.status().failed} written=${current.status().written}")
        } finally {
            try { engine?.close() } finally {
                val files = checkNotNull(root.listFiles()) { "Cannot list test mount" }
                var removed = true
                files.forEach { if (!it.delete()) removed = false }
                check(removed) { "Cannot clean test mount" }
            }
        }
    }
}
