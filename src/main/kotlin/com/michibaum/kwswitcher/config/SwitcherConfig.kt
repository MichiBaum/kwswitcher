package com.michibaum.kwswitcher.config

import com.michibaum.kwswitcher.win32.Win32Bindings

/**
 * Supported theme modes for the switcher overlay.
 */
enum class ThemeOption {
    DARK,
    LIGHT,
    SYSTEM,
}

/**
 * Hotkey binding definition for invoking and cycling the window switcher.
 */
data class HotkeyConfig(
    val modifier: String = "ALT",
    val triggerKey: String = "TAB",
    val reverseModifier: String = "SHIFT",
) {
    /**
     * Returns the Win32 virtual-key codes corresponding to the configured primary [modifier].
     */
    fun modifierVkCodes(): Set<Int> = when (modifier.uppercase()) {
        "CTRL", "CONTROL" -> setOf(Win32Bindings.VK_CONTROL, Win32Bindings.VK_LCONTROL, Win32Bindings.VK_RCONTROL)
        "WIN", "WINDOWS" -> setOf(Win32Bindings.VK_LWIN, Win32Bindings.VK_RWIN)
        else -> setOf(Win32Bindings.VK_MENU, Win32Bindings.VK_LMENU, Win32Bindings.VK_RMENU)
    }

    /**
     * Returns the primary Win32 virtual-key code for querying [Win32Bindings.GetAsyncKeyState] of [reverseModifier].
     */
    fun reverseModifierPrimaryVk(): Int = when (reverseModifier.uppercase()) {
        "CTRL", "CONTROL" -> Win32Bindings.VK_CONTROL
        "ALT" -> Win32Bindings.VK_MENU
        else -> Win32Bindings.VK_SHIFT
    }

    /**
     * Returns all Win32 virtual-key codes that match the configured [reverseModifier].
     */
    fun reverseModifierVkCodes(): Set<Int> = when (reverseModifier.uppercase()) {
        "CTRL", "CONTROL" -> setOf(Win32Bindings.VK_CONTROL, Win32Bindings.VK_LCONTROL, Win32Bindings.VK_RCONTROL)
        "ALT" -> setOf(Win32Bindings.VK_MENU, Win32Bindings.VK_LMENU, Win32Bindings.VK_RMENU)
        else -> setOf(Win32Bindings.VK_SHIFT, Win32Bindings.VK_LSHIFT, Win32Bindings.VK_RSHIFT)
    }

    /**
     * Returns the Win32 virtual-key code for the [triggerKey].
     */
    fun triggerVkCode(): Int {
        val normalized = triggerKey.trim().uppercase()
        return when (normalized) {
            "TAB" -> Win32Bindings.VK_TAB
            "SPACE" -> Win32Bindings.VK_SPACE
            "BACKTICK", "GRAVE", "`" -> Win32Bindings.VK_OEM_3
            else -> if (normalized.length == 1 && normalized[0] in 'A'..'Z') {
                normalized[0].code
            } else {
                Win32Bindings.VK_TAB
            }
        }
    }

    /**
     * Whether the primary modifier is the `Alt` key (`VK_MENU`).
     */
    val isAltModifier: Boolean
        get() = modifier.uppercase() !in setOf("CTRL", "CONTROL", "WIN", "WINDOWS")
}

/**
 * User configuration loaded from `%APPDATA%\kwswitcher\config.json`.
 */
data class SwitcherConfig(
    val hotkey: HotkeyConfig = HotkeyConfig(),
    val theme: ThemeOption = ThemeOption.SYSTEM,
    val showMonitorBadge: Boolean = true,
    val showMinimizedBadge: Boolean = true,
    val ignoredProcesses: List<String> = DEFAULT_IGNORED_PROCESSES,
    val ignoredTitlePatterns: List<String> = DEFAULT_IGNORED_TITLE_PATTERNS,
) {
    private val normalizedIgnoredProcesses: Set<String> = ignoredProcesses
        .map { it.trim().removeSuffix(".exe").removeSuffix(".EXE").lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val compiledTitleRegexes: List<Regex> = ignoredTitlePatterns.mapNotNull { pattern ->
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
    }

    /**
     * Returns `true` if a window with [processName] or [title] matches any configured ignore rule.
     */
    fun isIgnored(processName: String, title: String): Boolean {
        val cleanProcess = processName.trim().removeSuffix(".exe").removeSuffix(".EXE").lowercase()
        if (cleanProcess.isNotEmpty() && cleanProcess in normalizedIgnoredProcesses) {
            return true
        }
        return compiledTitleRegexes.any { regex -> regex.containsMatchIn(title) }
    }

    companion object {
        val DEFAULT_IGNORED_PROCESSES: List<String> = listOf(
            "TextInputHost.exe",
            "SearchHost.exe",
        )

        val DEFAULT_IGNORED_TITLE_PATTERNS: List<String> = listOf(
            "^NVIDIA GeForce Overlay$",
            "^Program Manager$",
        )
    }
}
