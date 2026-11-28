package com.michibaum.kwswitcher.app

import com.michibaum.kwswitcher.app.cli.CliOptions
import com.michibaum.kwswitcher.app.cli.LogLevel
import com.michibaum.kwswitcher.app.cli.RuntimeMode
import com.michibaum.kwswitcher.app.logging.KwLoggingHub
import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import com.michibaum.kwswitcher.core.coordinator.SwitcherMode
import com.michibaum.kwswitcher.core.port.HotkeyHookPort
import com.michibaum.kwswitcher.core.port.SingleInstancePort
import com.michibaum.kwswitcher.core.ui.TrayVisualStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KwSwitcherBootstrapSandboxTest {

    @AfterEach
    fun tearDown() {
        KwLoggingHub.reset()
    }

    @Test
    fun `resolveProfilePaths isolates DEV and SANDBOX defaults from PRODUCTION`() {
        val prodPaths = KwSwitcherBootstrap.resolveProfilePaths(CliOptions(mode = RuntimeMode.PRODUCTION))
        assertEquals("TAB", prodPaths.defaultConfig.hotkey.triggerKey)
        assertEquals(TrayVisualStyle.PRODUCTION, prodPaths.trayStyle)

        val devPaths = KwSwitcherBootstrap.resolveProfilePaths(CliOptions(mode = RuntimeMode.DEV))
        assertEquals("SPACE", devPaths.defaultConfig.hotkey.triggerKey)
        assertEquals(TrayVisualStyle.DEV, devPaths.trayStyle)
        assertTrue(devPaths.showPinOverlayToggle)

        val sandboxPaths = KwSwitcherBootstrap.resolveProfilePaths(CliOptions(mode = RuntimeMode.SANDBOX))
        assertEquals(TrayVisualStyle.SANDBOX, sandboxPaths.trayStyle)
        assertTrue(sandboxPaths.showPinOverlayToggle)
    }

    @Test
    fun `KwSwitcherBootstrap starts in SANDBOX mode opens interactive sample windows and shuts down cleanly`(
        @TempDir tempDir: Path,
    ) {
        val sandboxConfig = tempDir.resolve("sandbox-config.json")
        val options = CliOptions(
            mode = RuntimeMode.SANDBOX,
            installHook = false,
            pinOverlay = true,
            openOnStart = true,
            configPath = sandboxConfig,
            logLevel = LogLevel.DEBUG,
        )

        val started = KwSwitcherBootstrap.start(
            options = options,
            enableUi = false,
            consoleLogging = false,
        )
        val session = assertNotNull(started)
        session.use { active ->
            assertTrue(sandboxConfig.exists(), "Bootstrap should initialize sandbox config on disk")
            assertEquals("SPACE", active.activeConfig.hotkey.triggerKey)

            val searched = runBlocking {
                active.coordinator.submitAndAwait(SwitcherIntent.SetQuery("Firefox"))
            }
            assertEquals(SwitcherMode.INTERACTIVE_SEARCH, searched.mode)
            assertTrue(searched.pinned)
            assertEquals(1, searched.filteredWindows.size)
            assertEquals("firefox", searched.filteredWindows.first().processName)

            val afterClose = runBlocking {
                active.coordinator.submitAndAwait(SwitcherIntent.CloseSelected)
            }
            assertTrue(afterClose.filteredWindows.isEmpty())

            val diagnostics = active.buildDiagnosticsReport()
            assertTrue(diagnostics.contains("Runtime Mode   : SANDBOX"))
            assertTrue(diagnostics.contains("Overlay Pinned : true"))
            assertTrue(diagnostics.contains("Starting Kotlin Switcheroo"))

            // Toggling pin dynamically updates diagnostics report even though options.pinOverlay was true
            runBlocking {
                active.coordinator.submitAndAwait(SwitcherIntent.SetPinned(pinned = false))
            }
            val unpinnedDiagnostics = active.buildDiagnosticsReport()
            assertTrue(unpinnedDiagnostics.contains("Overlay Pinned : false"))
        }
    }

    @Test
    fun `KwSwitcherBootstrap releases single-instance mutex when hook startup fails`(@TempDir tempDir: Path) {
        val held = AtomicBoolean(false)
        val closeCalls = AtomicInteger(0)
        val recordingMutex = object : SingleInstancePort {
            override fun tryAcquire(): Boolean = held.compareAndSet(false, true)
            override fun close() {
                closeCalls.incrementAndGet()
                held.set(false)
            }
        }
        val failingHook = object : HotkeyHookPort {
            override val events: Flow<SwitcherIntent> = emptyFlow()
            override fun start(): Boolean = false
            override fun acknowledgeHotkeyHandled() = Unit
            override fun close() = Unit
        }

        val options = CliOptions(
            mode = RuntimeMode.SANDBOX,
            configPath = tempDir.resolve("sandbox-config.json"),
            warnings = listOf("Simulated CLI warning"),
        )
        val session = KwSwitcherBootstrap.startInternal(
            options = options,
            enableUi = false,
            consoleLogging = false,
            singleInstanceOverride = recordingMutex,
            hookOverride = failingHook,
        )
        assertNull(session)
        assertEquals(1, closeCalls.get(), "singleInstance.close() must run when hook.start() returns false")
        assertFalse(held.get())
        assertTrue(recordingMutex.tryAcquire(), "Subsequent tryAcquire() must succeed after failed startup")
    }

    @Test
    fun `KwSwitcherBootstrap runSelfCheck verifies sandbox subsystems and Panama linkage`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("selfcheck-config.json")
        val logFile = tempDir.resolve("logs").resolve("kwswitcher.log")
        val options = CliOptions.parse(
            arrayOf("--self-check", "--config", configFile.toString()),
        ) { null }

        val ok = KwSwitcherBootstrap.runSelfCheck(
            options = options,
            enableUi = false,
            consoleLogging = false,
        )
        assertTrue(ok)
        assertNull(KwLoggingHub.fileSink, "runSelfCheck must close fileSink on exit")
        assertTrue(logFile.exists())
        assertTrue(logFile.readText().contains("Self-check passed"))
    }
}
