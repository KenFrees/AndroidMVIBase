package com.ttxz.base.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 独立 library 测试宿主，验证真实 Android 文件 API，不操作示例 app 数据。 */
@RunWith(AndroidJUnit4::class)
class LoggerDeviceTest {
    @Test fun unavailableExternalStorageReturnsFailureWithoutFallbackOrCrash() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val unavailable = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getExternalFilesDir(type: String?): File? = null
        }
        val context = LoggingContext()
        try {
            val result = context.initialize { LogEngine.create(unavailable) }
            assertFalse(result.enabled)
            assertNull(result.directory)
            assertEquals(listOf("ExternalLogStorageUnavailableException"), result.warnings)
            assertTrue(result.consoleMessage().contains("外部应用专属存储不可用"))
            assertFalse(context.getLogger("Unavailable").error("ignored"))
            assertEquals(1L, context.status().dropped)
            assertFalse(context.flush().isSuccessful)
            assertTrue(result.logFiles().isFailure)
        } finally { context.close(1000) }
    }

    @Test fun publicFacadeCachesNamesAndSharesWriter() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = Logging.getLogger("PublicFacade")
        assertSame(first, Logging.getLogger("PublicFacade"))
        assertSame(Logging.getLogger(javaClass), Logging.getLogger(javaClass.name))
        val initialized = Logging.initialize(context, LogConfig(directoryName = "facade-${UUID.randomUUID()}", isDebugLog = true, logcatMinLevel = null))
        val directory = File(requireNotNull(initialized.directory))
        try {
            assertTrue(initialized.enabled)
            assertTrue(first.info("public first"))
            assertTrue(Logging.getLogger("Second").warn("public second"))
            assertTrue(Logging.flush().isSuccessful)
            assertEquals(2L, Logging.status().written)
            val text = Logging.logFiles().getOrThrow().joinToString { it.readText() }
            assertTrue(text.contains("[PublicFacade] public first"))
            assertTrue(text.contains("[Second] public second"))

        } finally {
            Logging.close()
            directory.deleteRecursively()
        }
    }

    @Test fun largeUtf8RecordSurvivesFlushAndRollsBeforeNextRecord() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "large-record-test")
        val engine = LogEngine(File(root, "logs"), LogConfig(
            logcatMinLevel = null, maxFileBytes = 1024 * 1024, maxDirectoryBytes = 4 * 1024 * 1024,
        )) { _, _, _ -> }
        val message = "测😀".repeat(160_000) + "END"
        try {
            assertTrue(engine.info("Large", message))
            assertTrue(engine.flush().isSuccessful)
            assertTrue(engine.info("Large", "next"))
            assertTrue(engine.flush().isSuccessful)
            val files = File(root, "logs").listFiles()!!.sortedBy { it.name }
            assertEquals(2, files.size)
            assertTrue(files.first().length() > 1024 * 1024)
            assertEquals(message, files.first().readLines().single().substringAfter("[Large] "))
            assertTrue(files.last().readText().endsWith("[Large] next\n"))

            assertEquals(0L, engine.status().truncated)
            assertEquals(0L, engine.status().failed)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun configuredExternalDirectoryMultilineStackAndFirstRoll() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = LogConfig(directoryName = "layout-${UUID.randomUUID()}", maxFileBytes = 1,
            maxDirectoryBytes = 4096, isDebugLog = true, logcatMinLevel = null)
        val engine = LogEngine.create(context, config)
        val directory = File(engine.logDirectory)
        try {
            assertEquals(File(context.getExternalFilesDir(null), config.directoryName).absolutePath, engine.logDirectory)
            assertTrue(engine.info("Layout", "first"))
            assertTrue(engine.flush().isSuccessful)
            assertTrue(directory.listFiles()!!.single().name.matches(Regex("\\d{4}-\\d{2}-\\d{2}\\.log")))
            val exception = IllegalStateException("测试异常").apply {
                stackTrace = arrayOf(StackTraceElement("Example", "run", "Example.kt", 42))
            }
            assertTrue(engine.error("Layout", "second", exception))
            assertTrue(engine.flush().isSuccessful)
            val files = directory.listFiles()!!.sortedBy { it.name }
            assertEquals(2, files.size)
            assertTrue(files[0].name.endsWith("-000000.log"))
            assertTrue(files[1].name.endsWith("-000001.log"))
            val expected = "[Layout] second\njava.lang.IllegalStateException: 测试异常\n\tat Example.run(Example.kt:42)\n"
            assertTrue(files[1].readText().contains(expected))

        } finally {
            engine.close()
            directory.deleteRecursively()
        }
    }

    @Test fun mainThreadSubmissionFlushAndReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = LogConfig(directoryName = "reopen-${UUID.randomUUID()}", isDebugLog = true, logcatMinLevel = null)
        val logger = LogEngine.create(context, config)
        val directory = File(logger.logDirectory)
        val latencies = LongArray(300)
        try {
            instrumentation.runOnMainSync {
                repeat(latencies.size) { index ->
                    val start = System.nanoTime()
                    logger.info("Device", "record-$index\ncontinuation")
                    latencies[index] = System.nanoTime() - start
                }
            }
            logger.flush()
            val status = logger.status()
            assertEquals(300L, status.accepted + status.dropped)
            assertEquals(status.accepted, status.written)
            assertEquals(0L, status.failed)
            val initialized = LogInitialization(true, emptyList(), directory.absolutePath)
            val files = initialized.logFiles().getOrThrow()
            val lines = files.flatMap { it.readLines() }
            assertEquals(status.accepted, lines.size.toLong())
            assertTrue(lines.all { it.contains("[Device]") && it.contains("\\ncontinuation") })
            val day = java.time.LocalDate.parse(files.first().name.take(10))
            assertEquals(files, initialized.logFiles(day).getOrThrow())

            logger.close()
            val reopened = LogEngine.create(context, config)
            try {
                assertTrue(reopened.info("Device", "after reopen"))
                reopened.flush()
                assertTrue(directory.listFiles()!!.any { it.readText().contains("after reopen") })
            } finally { reopened.close() }
            latencies.sort()
            println("LOGGER_DEVICE api=${android.os.Build.VERSION.SDK_INT} accepted=${status.accepted} dropped=${status.dropped} p95Us=${latencies[284] / 1000} maxUs=${latencies.last() / 1000}")
        } finally {
            logger.close()
            directory.deleteRecursively()
        }
    }
}
