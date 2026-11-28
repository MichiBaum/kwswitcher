package com.michibaum.kwswitcher.core.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.regex.PatternSyntaxException

/**
 * Supported theme modes for the switcher overlay.
 */
@Serializable
enum class ThemeOption {
    DARK,
    LIGHT,
    SYSTEM,
}

/**
 * Platform-neutral primary modifier family for invoking the switcher.
 */
enum class HotkeyModifier {
    ALT,
    CTRL,
    WIN,
}

/**
 * Platform-neutral reverse-cycle modifier family.
 */
enum class HotkeyReverseModifier {
    SHIFT,
    CTRL,
    ALT,
}

/**
 * Platform-neutral trigger key descriptor.
 */
sealed interface HotkeyTrigger {
    data object Tab : HotkeyTrigger
    data object Space : HotkeyTrigger
    data object Backtick : HotkeyTrigger
    data class Letter(val upperChar: Char) : HotkeyTrigger
}

/**
 * Hotkey binding definition for invoking and cycling the window switcher.
 * Platform-specific virtual-key compilation lives in platform adapter modules (e.g. `Win32HotkeySpec`).
 */
@Serializable
data class HotkeyConfig(
    val modifier: String = "ALT",
    val triggerKey: String = "TAB",
    val reverseModifier: String = "SHIFT",
) {
    val resolvedModifier: HotkeyModifier
        get() = when (modifier.trim().uppercase()) {
            "CTRL", "CONTROL" -> HotkeyModifier.CTRL
            "WIN", "WINDOWS" -> HotkeyModifier.WIN
            else -> HotkeyModifier.ALT
        }

    val resolvedReverseModifier: HotkeyReverseModifier
        get() = when (reverseModifier.trim().uppercase()) {
            "CTRL", "CONTROL" -> HotkeyReverseModifier.CTRL
            "ALT" -> HotkeyReverseModifier.ALT
            else -> HotkeyReverseModifier.SHIFT
        }

    val resolvedTrigger: HotkeyTrigger
        get() {
            val normalized = triggerKey.trim().uppercase()
            return when (normalized) {
                "TAB" -> HotkeyTrigger.Tab

                "SPACE" -> HotkeyTrigger.Space

                "BACKTICK", "GRAVE", "`" -> HotkeyTrigger.Backtick

                else -> if (normalized.length == 1 && normalized[0] in 'A'..'Z') {
                    HotkeyTrigger.Letter(normalized[0])
                } else {
                    HotkeyTrigger.Tab
                }
            }
        }

    val isAltModifier: Boolean
        get() = resolvedModifier == HotkeyModifier.ALT

    val isSpaceTrigger: Boolean
        get() = resolvedTrigger == HotkeyTrigger.Space
}

/**
 * User configuration loaded from `config.json` (`DX-4`).
 */
@Serializable
data class SwitcherConfig(
    val hotkey: HotkeyConfig = HotkeyConfig(),
    val theme: ThemeOption = ThemeOption.SYSTEM,
    val showMonitorBadge: Boolean = true,
    val showMinimizedBadge: Boolean = true,
    val ignoredProcesses: List<String> = DEFAULT_IGNORED_PROCESSES,
    val ignoredTitlePatterns: List<String> = DEFAULT_IGNORED_TITLE_PATTERNS,
) {
    @Transient
    private val normalizedIgnoredProcesses: Set<String> = ignoredProcesses
        .map { it.trim().removeSuffix(".exe").removeSuffix(".EXE").lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    @Transient
    private val compiledRegexOutcomes: List<RegexCompileOutcome> = ignoredTitlePatterns.map { pattern ->
        compileTitlePattern(pattern)
    }

    @Transient
    private val compiledTitleRegexes: List<Regex> = compiledRegexOutcomes.mapNotNull { it.regex }

    /**
     * Validation warnings for any malformed entries in [ignoredTitlePatterns].
     */
    val validationWarnings: List<String>
        get() = compiledRegexOutcomes.mapNotNull { it.warning }

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

    private data class RegexCompileOutcome(val regex: Regex? = null, val warning: String? = null)

    companion object {
        val DEFAULT_IGNORED_PROCESSES: List<String> = listOf(
            "TextInputHost.exe",
            "SearchHost.exe",
        )

        val DEFAULT_IGNORED_TITLE_PATTERNS: List<String> = listOf(
            "^NVIDIA GeForce Overlay$",
            "^Program Manager$",
        )

        private fun compileTitlePattern(pattern: String): RegexCompileOutcome = try {
            RegexCompileOutcome(regex = Regex(pattern, RegexOption.IGNORE_CASE))
        } catch (e: PatternSyntaxException) {
            RegexCompileOutcome(
                warning = "Invalid regex in ignoredTitlePatterns ('$pattern'): ${e.description}",
            )
        }
    }
}
