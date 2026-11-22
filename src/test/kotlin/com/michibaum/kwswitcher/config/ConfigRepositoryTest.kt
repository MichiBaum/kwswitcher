package com.michibaum.kwswitcher.config

import com.michibaum.kwswitcher.ui.WindowListCellRenderer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigRepositoryTest {

    @Test
    fun `ConfigRepository serializes and parses SwitcherConfig round-trip`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("config.json")
        val repo = ConfigRepository(configPath = file)

        val customConfig = SwitcherConfig(
            hotkey = HotkeyConfig(modifier = "ALT", triggerKey = "SPACE", reverseModifier = "SHIFT"),
            theme = ThemeOption.LIGHT,
            showMonitorBadge = false,
            showMinimizedBadge = true,
            ignoredProcesses = listOf("TextInputHost.exe", "Spotify.exe"),
            ignoredTitlePatterns = listOf("^NVIDIA GeForce Overlay$", "^ Hidden .*"),
        )

        repo.save(customConfig)
        val loaded = repo.loadOrCreate()
        assertEquals(customConfig, loaded)
    }

    @Test
    fun `SwitcherConfig matches ignored processes and title regex patterns`() {
        val config = SwitcherConfig()
        assertTrue(config.isIgnored(processName = "TextInputHost", title = "Windows Input Experience"))
        assertTrue(config.isIgnored(processName = "searchhost.exe", title = "Search"))
        assertTrue(config.isIgnored(processName = "nvcontainer", title = "NVIDIA GeForce Overlay"))
        assertFalse(config.isIgnored(processName = "idea64", title = "kwswitcher - Main.kt"))
    }

    @Test
    fun `WindowListCellRenderer formats HTML match highlights and escapes special characters`() {
        val highlighted = WindowListCellRenderer.formatHighlightedText(
            text = "Kotlin & <Java>",
            ranges = listOf(0..2, 10..13),
            highlightHex = "#61AFEF",
        )
        assertEquals(
            "<html><nobr><b><font color=\"#61AFEF\">Kot</font></b>lin &amp; &lt;<b><font color=\"#61AFEF\">Java</font></b>&gt;</nobr></html>",
            highlighted,
        )
    }
}
