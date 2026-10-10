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
        val root = File(app.filesDir, "crash-recovery-test")
        root.deleteRecursively()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = LogEngine(File(root, "logs"), File(root, "exports"), LogConfig(logcatMinLevel = null,
            redact = { if (it == "pending") { entered.countDown(); release.await(45, TimeUnit.SECONDS) }; it })) { _, _, _ -> }
        try {
            assertTrue(engine.info("Recovery", "persisted"))
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
        val root = File(app.filesDir, "crash-recovery-test")
        val directory = File(root, "logs")
        val before = directory.listFiles()!!.flatMap { it.readLines() }
        assertTrue(before.any { it.endsWith("[Recovery] persisted") })
        assertFalse(before.any { it.contains("pending") })
        val engine = LogEngine(directory, File(root, "exports"), LogConfig(logcatMinLevel = null)) { _, _, _ -> }
        try {
            assertTrue(engine.info("Recovery", "after restart"))
            assertTrue(engine.flush().isSuccessful)
            val lines = directory.listFiles()!!.flatMap { it.readLines() }
            assertEquals(2, lines.size)
            assertTrue(lines.any { it.endsWith("[Recovery] after restart") })
            println("LOGGER_CRASH_RECOVERY persisted=1 pendingLost=1 newWritten=1")
        } finally { engine.close(); root.deleteRecursively() }
    }
}
