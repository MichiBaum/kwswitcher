package com.michibaum.kwswitcher.app.logging

import com.michibaum.kwswitcher.app.cli.CliOptions
import com.michibaum.kwswitcher.core.config.SwitcherConfig
import java.nio.file.Path

/**
 * Thread-safe bounded ring buffer retaining recent formatted log entries for tray diagnostics (`DX-5`).
 */
class DiagnosticsRingBuffer(maxEntries: Int = DEFAULT_CAPACITY) {
    val capacity: Int = maxEntries.coerceAtLeast(1)
    private val lock = Any()
    private val entries = ArrayDeque<String>(capacity)

    fun append(formattedLine: String) {
        synchronized(lock) {
            if (entries.size >= capacity) {
                entries.removeFirst()
            }
            entries.addLast(formattedLine)
        }
    }

    fun snapshot(): List<String> = synchronized(lock) {
        entries.toList()
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    /**
     * Formats a support/debugging summary suitable for copying to the system clipboard.
     */
    fun buildDiagnosticsReport(
        options: CliOptions,
        configPath: Path,
        logDirectory: Path?,
        activeConfig: SwitcherConfig,
        pinned: Boolean = options.pinOverlay,
    ): String {
        val lines = snapshot()
        return buildString {
            appendLine("=== Kotlin Switcheroo (kwswitcher) Diagnostics ===")
            appendLine("Runtime Mode   : ${options.mode}")
            appendLine("Hook Installed : ${options.installHook}")
            appendLine("Overlay Pinned : $pinned")
            appendLine("Log Level      : ${options.logLevel}")
            appendLine("Java Runtime   : ${System.getProperty("java.version")} (${System.getProperty("java.vm.name")})")
            appendLine(
                "OS             : ${System.getProperty(
                    "os.name",
                )} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
            )
            appendLine("PID            : ${ProcessHandle.current().pid()}")
            appendLine("Config Path    : $configPath")
            appendLine("Log Directory  : ${logDirectory ?: "(disabled)"}")
            appendLine(
                "Active Hotkey  : ${activeConfig.hotkey.modifier} + ${activeConfig.hotkey.triggerKey} " +
                    "(reverse=${activeConfig.hotkey.reverseModifier})",
            )
            appendLine("Active Theme   : ${activeConfig.theme}")
            appendLine()
            appendLine("--- Recent Log Entries (${lines.size} / $capacity) ---")
            for (line in lines) {
                appendLine(line)
            }
        }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 500
    }
}
