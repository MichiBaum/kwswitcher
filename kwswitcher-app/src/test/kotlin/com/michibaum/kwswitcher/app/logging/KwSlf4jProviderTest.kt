package com.michibaum.kwswitcher.app.logging

import com.michibaum.kwswitcher.app.cli.CliOptions
import com.michibaum.kwswitcher.app.cli.LogLevel
import com.michibaum.kwswitcher.app.cli.RuntimeMode
import com.michibaum.kwswitcher.core.config.SwitcherConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.slf4j.spi.SLF4JServiceProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KwSlf4jProviderTest {

    @AfterEach
    fun tearDown() {
        KwLoggingHub.reset()
    }

    @Test
    fun `ServiceLoader discovers KwSlf4jProvider and KotlinLogging writes to ring buffer and rotating file`(
        @TempDir tempDir: Path,
    ) {
        val providers = ServiceLoader.load(SLF4JServiceProvider::class.java).toList()
        assertTrue(
            providers.any { it is KwSlf4jProvider },
            "KwSlf4jProvider must be registered in META-INF/services/org.slf4j.spi.SLF4JServiceProvider",
        )

        val logFile = tempDir.resolve("logs").resolve("kwswitcher.log")
        KwLoggingHub.configure(
            minLevel = LogLevel.DEBUG,
            logFile = logFile,
            consoleEnabled = false,
            maxFileBytes = 320L,
            ringCapacity = 3,
        )

        val logger = KotlinLogging.logger("com.michibaum.kwswitcher.app.TestComponent")
        logger.trace { "trace-should-be-filtered" }
        logger.debug { "entry-1-alpha-payload-for-rotation-check" }
        logger.info { "entry-2-bravo-payload-for-rotation-check" }
        logger.warn { "entry-3-charlie-payload-for-rotation-check" }
        logger.error(IllegalStateException("boom")) { "entry-4-delta-error" }

        // Ring buffer has capacity 3 -> oldest entry-1 evicted, entries 2..4 retained
        val snapshot = KwLoggingHub.ringBuffer.snapshot()
        assertEquals(3, snapshot.size)
        assertTrue(snapshot[0].contains("entry-2-bravo"))
        assertTrue(snapshot[1].contains("entry-3-charlie"))
        assertTrue(snapshot[2].contains("entry-4-delta-error"))
        assertTrue(snapshot[2].contains("IllegalStateException: boom"))

        // File sink rotated kwswitcher.log -> kwswitcher.log.1 once maxFileBytes was exceeded
        val sink = requireNotNull(KwLoggingHub.fileSink)
        assertTrue(sink.logFile.exists())
        assertTrue(sink.backupFile.exists(), "Backup log file ${sink.backupFile} must be created on rotation")
        val combinedLogs = sink.backupFile.readText() + "\n" + sink.logFile.readText()
        assertTrue(combinedLogs.contains("entry-1-alpha"))
        assertTrue(combinedLogs.contains("entry-4-delta-error"))

        val report = KwLoggingHub.ringBuffer.buildDiagnosticsReport(
            options = CliOptions(mode = RuntimeMode.DEV, installHook = false, pinOverlay = true),
            configPath = tempDir.resolve("config.json"),
            logDirectory = KwLoggingHub.logDirectory,
            activeConfig = SwitcherConfig(),
        )
        assertTrue(report.contains("Runtime Mode   : DEV"))
        assertTrue(report.contains("entry-4-delta-error"))
    }

    @Test
    fun `DiagnosticsRingBuffer clamps non-positive maxEntries and formats live pinned flag`(
        @TempDir tempDir: Path,
    ) {
        val zeroCap = DiagnosticsRingBuffer(maxEntries = 0)
        assertEquals(1, zeroCap.capacity)
        zeroCap.append("line-1")
        zeroCap.append("line-2")
        assertEquals(listOf("line-2"), zeroCap.snapshot())

        val negativeCap = DiagnosticsRingBuffer(maxEntries = -5)
        assertEquals(1, negativeCap.capacity)
        negativeCap.append("first")
        negativeCap.append("second")
        assertEquals(listOf("second"), negativeCap.snapshot())

        val report = negativeCap.buildDiagnosticsReport(
            options = CliOptions(mode = RuntimeMode.DEV, pinOverlay = false),
            configPath = tempDir.resolve("config.json"),
            logDirectory = null,
            activeConfig = SwitcherConfig(),
            pinned = true,
        )
        assertTrue(report.contains("Overlay Pinned : true"))
        assertTrue(report.contains("--- Recent Log Entries (1 / 1) ---"))
    }

    @Test
    fun `RotatingFileSink rotates persistent writer cleanly and releases Windows file locks on close`(
        @TempDir tempDir: Path,
    ) {
        val logFile = tempDir.resolve("persistent.log")
        RotatingFileSink(logFile = logFile, maxBytes = 80L).use { sink ->
            sink.writeLine("alpha-0123456789-0123456789-0123456789")
            sink.writeLine("bravo-0123456789-0123456789-0123456789")
            sink.writeLine("charlie-0123456789-0123456789-0123456789")
            assertTrue(sink.backupFile.exists())
            assertTrue(sink.logFile.exists())
        }

        // After close(), Windows NTFS handle must be released so Files.delete succeeds immediately
        Files.delete(logFile)
        assertFalse(logFile.exists())

        KwLoggingHub.reset()
        assertNull(KwLoggingHub.fileSink)
        assertEquals(DiagnosticsRingBuffer.DEFAULT_CAPACITY, KwLoggingHub.ringBuffer.capacity)
    }
}
