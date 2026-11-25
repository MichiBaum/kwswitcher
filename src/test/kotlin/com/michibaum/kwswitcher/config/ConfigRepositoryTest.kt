package com.michibaum.kwswitcher.config

import com.michibaum.kwswitcher.ui.WindowListCellRenderer
import com.michibaum.kwswitcher.win32.Win32Bindings
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun `HotkeyConfig pre-computes VK codes, normalizes case and whitespace, and preserves data class equality`() {
        val defaultHotkey = HotkeyConfig()
        assertTrue(defaultHotkey.isAltModifier)
        assertEquals(Win32Bindings.VK_TAB, defaultHotkey.triggerVkCode)
        assertEquals(Win32Bindings.VK_SHIFT, defaultHotkey.reverseModifierPrimaryVk)
        assertEquals(
            setOf(Win32Bindings.VK_MENU, Win32Bindings.VK_LMENU, Win32Bindings.VK_RMENU),
            defaultHotkey.modifierVkCodes,
        )
        assertEquals(
            setOf(Win32Bindings.VK_SHIFT, Win32Bindings.VK_LSHIFT, Win32Bindings.VK_RSHIFT),
            defaultHotkey.reverseModifierVkCodes,
        )
        assertTrue(defaultHotkey.matchesModifierVk(Win32Bindings.VK_LMENU))
        assertTrue(defaultHotkey.matchesModifierVk(Win32Bindings.VK_RMENU))
        assertFalse(defaultHotkey.matchesModifierVk(Win32Bindings.VK_LCONTROL))
        assertTrue(defaultHotkey.matchesReverseModifierVk(Win32Bindings.VK_LSHIFT))
        assertTrue(defaultHotkey.matchesReverseModifierVk(Win32Bindings.VK_RSHIFT))

        val paddedControlBacktick = HotkeyConfig(
            modifier = "  control ",
            triggerKey = " ` ",
            reverseModifier = " alt ",
        )
        assertFalse(paddedControlBacktick.isAltModifier)
        assertEquals(Win32Bindings.VK_OEM_3, paddedControlBacktick.triggerVkCode)
        assertEquals(Win32Bindings.VK_MENU, paddedControlBacktick.reverseModifierPrimaryVk)
        assertTrue(paddedControlBacktick.matchesModifierVk(Win32Bindings.VK_RCONTROL))
        assertTrue(paddedControlBacktick.matchesReverseModifierVk(Win32Bindings.VK_LMENU))

        val winLetter = HotkeyConfig(modifier = "windows", triggerKey = " k ", reverseModifier = "ctrl")
        assertFalse(winLetter.isAltModifier)
        assertEquals('K'.code, winLetter.triggerVkCode)
        assertEquals(Win32Bindings.VK_CONTROL, winLetter.reverseModifierPrimaryVk)
        assertTrue(winLetter.matchesModifierVk(Win32Bindings.VK_LWIN))
        assertTrue(winLetter.matchesModifierVk(Win32Bindings.VK_RWIN))
        assertTrue(winLetter.matchesReverseModifierVk(Win32Bindings.VK_RCONTROL))

        val invalidTriggerFallback = HotkeyConfig(triggerKey = "INVALID_KEY")
        assertEquals(Win32Bindings.VK_TAB, invalidTriggerFallback.triggerVkCode)

        // Data class equality and hashCode depend strictly on primary constructor properties
        val copy = defaultHotkey.copy()
        assertEquals(defaultHotkey, copy)
        assertEquals(defaultHotkey.hashCode(), copy.hashCode())
        assertNotEquals(defaultHotkey, paddedControlBacktick)
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
