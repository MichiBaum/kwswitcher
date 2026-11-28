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
    val selfCheck: Boolean = false,
    val warnings: List<String> = emptyList(),
) {
    companion object {
        const val PROP_MODE = "kwswitcher.mode"
        const val PROP_NO_HOOK = "kwswitcher.noHook"
        const val PROP_PIN_OVERLAY = "kwswitcher.pinOverlay"
        const val PROP_OPEN_ON_START = "kwswitcher.openOnStart"
        const val PROP_CONFIG = "kwswitcher.config"
        const val PROP_LOG_LEVEL = "kwswitcher.logLevel"
        const val PROP_SELF_CHECK = "kwswitcher.selfCheck"

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
                selfCheck = propertyLookup(PROP_SELF_CHECK)?.toBooleanStrictOrNull() == true,
            )

            var index = 0
            while (index < args.size) {
                index = consumeArg(args = args, index = index, state = state)
            }

            val resolvedMode = if (state.selfCheck) {
                RuntimeMode.SANDBOX
            } else {
                state.mode ?: RuntimeMode.PRODUCTION
            }
            val resolvedInstallHook = when {
                state.selfCheck || state.noHook -> false
                resolvedMode == RuntimeMode.SANDBOX -> false
                else -> true
            }
            val resolvedOpenOnStart = state.openOnStart ?: (!state.selfCheck && resolvedMode == RuntimeMode.SANDBOX)
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
                selfCheck = state.selfCheck,
                warnings = state.warnings.toList(),
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

                token == "--self-check" -> {
                    state.selfCheck = true
                    index + 1
                }

                token == "--help" || token == "-h" -> {
                    state.showHelp = true
                    index + 1
                }

                token == "--config" || token.startsWith("--config=") ->
                    consumeConfigArg(args = args, index = index, token = token, state = state)

                token == "--log-level" || token.startsWith("--log-level=") ->
                    consumeLogLevelArg(args = args, index = index, token = token, state = state)

                else -> {
                    if (token.isNotEmpty()) {
                        state.warnings.add("Unknown CLI option '$token' (run with --help to see supported flags)")
                    }
                    index + 1
                }
            }
        }

        private fun consumeConfigArg(args: Array<String>, index: Int, token: String, state: MutableParseState): Int {
            if (token.startsWith("--config=")) {
                val rawPath = token.removePrefix("--config=").trim()
                if (rawPath.isEmpty()) {
                    state.warnings.add("Missing path argument in '$token'")
                } else {
                    state.configPath = Path(rawPath)
                }
                return index + 1
            }
            if (index + 1 >= args.size || args[index + 1].isBlank()) {
                state.warnings.add("Missing path argument for '--config'")
                return index + 1
            }
            state.configPath = Path(args[index + 1].trim())
            return index + 2
        }

        private fun consumeLogLevelArg(args: Array<String>, index: Int, token: String, state: MutableParseState): Int {
            if (token.startsWith("--log-level=")) {
                val rawLevel = token.removePrefix("--log-level=").trim()
                val parsed = LogLevel.parseOrNull(rawLevel)
                if (parsed != null) {
                    state.logLevel = parsed
                } else {
                    state.warnings.add(
                        "Invalid log level '$rawLevel' in '$token' (expected TRACE, DEBUG, INFO, WARN, ERROR)",
                    )
                }
                return index + 1
            }
            if (index + 1 >= args.size) {
                state.warnings.add(
                    "Missing level argument for '--log-level' (expected TRACE, DEBUG, INFO, WARN, ERROR)",
                )
                return index + 1
            }
            val rawLevel = args[index + 1].trim()
            val parsed = LogLevel.parseOrNull(rawLevel)
            if (parsed != null) {
                state.logLevel = parsed
            } else {
                state.warnings.add(
                    "Invalid log level '$rawLevel' for '--log-level' (expected TRACE, DEBUG, INFO, WARN, ERROR)",
                )
            }
            return index + 2
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
              --self-check          Boot sandbox subsystems and Panama FFM runtime, verify linkage, and exit 0
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
            var selfCheck: Boolean = false,
            val warnings: MutableList<String> = mutableListOf(),
        )
    }
}
