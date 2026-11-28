package com.michibaum.kwswitcher.app

import com.michibaum.kwswitcher.app.cli.CliOptions
import com.michibaum.kwswitcher.app.cli.LogLevel
import com.michibaum.kwswitcher.app.cli.RuntimeMode
import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import com.michibaum.kwswitcher.core.coordinator.SwitcherMode
import com.michibaum.kwswitcher.core.ui.TrayVisualStyle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KwSwitcherBootstrapSandboxTest {

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
            assertTrue(diagnostics.contains("Starting Kotlin Switcheroo"))
        }
    }
}
