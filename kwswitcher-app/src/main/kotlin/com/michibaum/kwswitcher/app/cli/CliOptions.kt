package com.michibaum.kwswitcher.app.cli

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Minimum log verbosity threshold (`DX-2`, `DX-5`).
 */
enum class LogLevel(val severity: Int) {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    ;

    companion object {
        fun parseOrNull(raw: String?): LogLevel? {
            if (raw.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

/**
 * Execution profile controlling mutex, configuration path, hotkey defaults, and platform adapters (`DX-2`).
 */
enum class RuntimeMode {
    PRODUCTION,
    DEV,
    SANDBOX,
    ;

    companion object {
        fun parseOrNull(raw: String?): RuntimeMode? {
            if (raw.isNullOrBlank()) return null
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

/**
 * Parsed command-line and `kwswitcher.*` system-property options (`DX-2`).
 */
data class CliOptions(
    val mode: RuntimeMode = RuntimeMode.PRODUCTION,
    val installHook: Boolean = true,
    val pinOverlay: Boolean = false,
    val openOnStart: Boolean = false,
    val configPath: Path? = null,
    val logLevel: LogLevel = LogLevel.INFO,
    val showHelp: Boolean = false,
) {
    companion object {
        const val PROP_MODE = "kwswitcher.mode"
        const val PROP_NO_HOOK = "kwswitcher.noHook"
        const val PROP_PIN_OVERLAY = "kwswitcher.pinOverlay"
        const val PROP_OPEN_ON_START = "kwswitcher.openOnStart"
        const val PROP_CONFIG = "kwswitcher.config"
        const val PROP_LOG_LEVEL = "kwswitcher.logLevel"

        /**
         * Parses CLI [args] combined with optional system-property fallbacks ([propertyLookup]).
         * Explicit CLI arguments always take precedence over system properties.
         */
        fun parse(args: Array<String>, propertyLookup: (String) -> String? = System::getProperty): CliOptions {
            val state = MutableParseState(
                mode = RuntimeMode.parseOrNull(propertyLookup(PROP_MODE)),
                noHook = propertyLookup(PROP_NO_HOOK)?.toBooleanStrictOrNull() == true,
                pinOverlay = propertyLookup(PROP_PIN_OVERLAY)?.toBooleanStrictOrNull() == true,
                openOnStart = propertyLookup(PROP_OPEN_ON_START)?.toBooleanStrictOrNull(),
                configPath = propertyLookup(PROP_CONFIG)?.takeIf(String::isNotBlank)?.let(::Path),
                logLevel = LogLevel.parseOrNull(propertyLookup(PROP_LOG_LEVEL)),
            )

            var index = 0
            while (index < args.size) {
                index = consumeArg(args = args, index = index, state = state)
            }

            val resolvedMode = state.mode ?: RuntimeMode.PRODUCTION
            val resolvedInstallHook = when {
                state.noHook -> false
                resolvedMode == RuntimeMode.SANDBOX -> false
                else -> true
            }
            val resolvedOpenOnStart = state.openOnStart ?: (resolvedMode == RuntimeMode.SANDBOX)
            val resolvedLogLevel = state.logLevel ?: when (resolvedMode) {
                RuntimeMode.PRODUCTION -> LogLevel.INFO
                RuntimeMode.DEV, RuntimeMode.SANDBOX -> LogLevel.DEBUG
            }

            return CliOptions(
                mode = resolvedMode,
                installHook = resolvedInstallHook,
                pinOverlay = state.pinOverlay,
                openOnStart = resolvedOpenOnStart,
                configPath = state.configPath,
                logLevel = resolvedLogLevel,
                showHelp = state.showHelp,
            )
        }

        private fun consumeArg(args: Array<String>, index: Int, state: MutableParseState): Int {
            val token = args[index].trim()
            return when {
                token == "--dev" -> {
                    state.mode = RuntimeMode.DEV
                    index + 1
                }

                token == "--sandbox" -> {
                    state.mode = RuntimeMode.SANDBOX
                    index + 1
                }

                token == "--no-hook" -> {
                    state.noHook = true
                    index + 1
                }

                token == "--pin-overlay" -> {
                    state.pinOverlay = true
                    index + 1
                }

                token == "--open-on-start" -> {
                    state.openOnStart = true
                    index + 1
                }

                token == "--help" || token == "-h" -> {
                    state.showHelp = true
                    index + 1
                }

                token == "--config" && index + 1 < args.size -> {
                    state.configPath = Path(args[index + 1])
                    index + 2
                }

                token.startsWith("--config=") -> {
                    state.configPath = Path(token.removePrefix("--config="))
                    index + 1
                }

                token == "--log-level" && index + 1 < args.size -> {
                    LogLevel.parseOrNull(args[index + 1])?.let { state.logLevel = it }
                    index + 2
                }

                token.startsWith("--log-level=") -> {
                    LogLevel.parseOrNull(token.removePrefix("--log-level="))?.let { state.logLevel = it }
                    index + 1
                }

                else -> index + 1
            }
        }

        fun usageText(): String = """
            Kotlin Switcheroo (kwswitcher) — Developer-Friendly CLI Options:
              --dev                 Isolated dev mode (DevMutex, %LOCALAPPDATA%\kwswitcher-dev, Alt+Space default, [DEV] tray)
              --no-hook             Disable global WH_KEYBOARD_LL hook (breakpoint-safe IDE debugging)
              --sandbox             Run cross-platform UI sandbox with interactive sample windows (implies --no-hook --open-on-start)
              --pin-overlay         Prevent overlay auto-dismissal on window focus loss
              --open-on-start       Show the switcher overlay immediately on startup
              --config <path>       Override path to config.json
              --log-level <level>   Set log verbosity: TRACE, DEBUG, INFO, WARN, ERROR
              --help, -h            Print this help message
        """.trimIndent()

        private class MutableParseState(
            var mode: RuntimeMode? = null,
            var noHook: Boolean = false,
            var pinOverlay: Boolean = false,
            var openOnStart: Boolean? = null,
            var configPath: Path? = null,
            var logLevel: LogLevel? = null,
            var showHelp: Boolean = false,
        )
    }
}
