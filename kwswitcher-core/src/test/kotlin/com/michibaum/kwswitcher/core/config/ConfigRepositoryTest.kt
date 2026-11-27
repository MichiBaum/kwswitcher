package com.michibaum.kwswitcher.core.config

import com.michibaum.kwswitcher.core.ui.WindowListCellRenderer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfigRepositoryTest {

    @Test
    fun `ConfigRepository creates default file when missing and round-trips bracketed regex patterns`(
        @TempDir tempDir: Path,
    ) {
        val file = tempDir.resolve("config.json")
        val devDefaults = SwitcherConfig(
            hotkey = HotkeyConfig(modifier = "ALT", triggerKey = "SPACE"),
        )
        val repo = ConfigRepository(configPath = file, defaultConfig = devDefaults)

        val created = assertIs<ConfigLoadResult.CreatedDefault>(repo.loadResult())
        assertEquals(devDefaults, created.config)
        assertEquals(file, created.path)

        val customConfig = SwitcherConfig(
            hotkey = HotkeyConfig(modifier = "ALT", triggerKey = "SPACE", reverseModifier = "SHIFT"),
            theme = ThemeOption.LIGHT,
            showMonitorBadge = false,
            showMinimizedBadge = true,
            ignoredProcesses = listOf("TextInputHost.exe", "Spotify.exe"),
            ignoredTitlePatterns = listOf("^\\[Admin\\].*", "^NVIDIA [A-Z]+ Overlay$"),
        )

        repo.save(customConfig)
        val loaded = assertIs<ConfigLoadResult.Loaded>(repo.loadResult())
        assertEquals(customConfig, loaded.config)
        assertTrue(loaded.warnings.isEmpty())
        assertTrue(loaded.config.isIgnored(processName = "cmd", title = "[Admin] Command Prompt"))
    }

    @Test
    fun `ConfigRepository tolerates unknown fields and case-insensitive theme names`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("config.json")
        file.writeText(
            """
            {
              "hotkey": {
                "modifier": "ALT",
                "triggerKey": "TAB",
                "futureField": 42
              },
              "theme": "light",
              "unknownTopLevelFlag": true
            }
            """.trimIndent(),
        )

        val repo = ConfigRepository(configPath = file)
        val loaded = assertIs<ConfigLoadResult.Loaded>(repo.loadResult())
        assertEquals(ThemeOption.LIGHT, loaded.config.theme)
        assertEquals("ALT", loaded.config.hotkey.modifier)
        assertTrue(loaded.warnings.isEmpty())
    }

    @Test
    fun `ConfigRepository reports FallbackOnError for malformed JSON and warnings for invalid regex`(
        @TempDir tempDir: Path,
    ) {
        val corruptedFile = tempDir.resolve("corrupted.json")
        corruptedFile.writeText("{ invalid json !!!")
        val fallback = assertIs<ConfigLoadResult.FallbackOnError>(
            ConfigRepository(configPath = corruptedFile).loadResult(),
        )
        assertEquals(SwitcherConfig(), fallback.config)
        assertTrue(fallback.error.contains("Malformed JSON"))

        val badRegexFile = tempDir.resolve("bad-regex.json")
        val repo = ConfigRepository(configPath = badRegexFile)
        repo.save(
            SwitcherConfig(
                ignoredTitlePatterns = listOf("^Valid$", "[unclosed-bracket"),
            ),
        )
        val loaded = assertIs<ConfigLoadResult.Loaded>(repo.loadResult())
        assertEquals(1, loaded.warnings.size)
        assertTrue(loaded.warnings.first().contains("[unclosed-bracket"))
        assertTrue(loaded.config.isIgnored(processName = "any", title = "Valid"))
    }

    @Test
    fun `HotkeyConfig normalizes platform-neutral descriptors and preserves data class equality`() {
        val defaultHotkey = HotkeyConfig()
        assertTrue(defaultHotkey.isAltModifier)
        assertFalse(defaultHotkey.isSpaceTrigger)
        assertEquals(HotkeyModifier.ALT, defaultHotkey.resolvedModifier)
        assertEquals(HotkeyTrigger.Tab, defaultHotkey.resolvedTrigger)
        assertEquals(HotkeyReverseModifier.SHIFT, defaultHotkey.resolvedReverseModifier)

        val paddedControlBacktick = HotkeyConfig(
            modifier = "  control ",
            triggerKey = " ` ",
            reverseModifier = " alt ",
        )
        assertFalse(paddedControlBacktick.isAltModifier)
        assertEquals(HotkeyModifier.CTRL, paddedControlBacktick.resolvedModifier)
        assertEquals(HotkeyTrigger.Backtick, paddedControlBacktick.resolvedTrigger)
        assertEquals(HotkeyReverseModifier.ALT, paddedControlBacktick.resolvedReverseModifier)

        val winLetter = HotkeyConfig(modifier = "windows", triggerKey = " k ", reverseModifier = "ctrl")
        assertEquals(HotkeyModifier.WIN, winLetter.resolvedModifier)
        assertEquals(HotkeyTrigger.Letter('K'), winLetter.resolvedTrigger)
        assertEquals(HotkeyReverseModifier.CTRL, winLetter.resolvedReverseModifier)

        val invalidTriggerFallback = HotkeyConfig(triggerKey = "INVALID_KEY")
        assertEquals(HotkeyTrigger.Tab, invalidTriggerFallback.resolvedTrigger)

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
    fun `WindowListCellRenderer lays out match highlight spans directly without HTML escaping`() {
        val layout = WindowListCellRenderer.layoutHighlightedText(
            text = "Kotlin & <Java>",
            ranges = listOf(0..2, 10..13),
        )
        assertEquals("Kotlin & <Java>", layout.displayText)
        assertEquals(listOf(0..2, 10..13), layout.highlightRanges)
        assertFalse(layout.truncated)
    }
}
