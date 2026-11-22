package com.michibaum.kwswitcher.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Loads and persists [SwitcherConfig] in `%APPDATA%\kwswitcher\config.json`.
 */
class ConfigRepository(
    val configPath: Path = resolveDefaultConfigPath(),
) {

    /**
     * Loads the configuration from [configPath], creating the default configuration file if none exists.
     */
    fun loadOrCreate(): SwitcherConfig {
        if (!configPath.exists()) {
            val defaultConfig = SwitcherConfig()
            runCatching { save(defaultConfig) }
            return defaultConfig
        }
        return runCatching {
            parseJson(configPath.readText())
        }.getOrElse { SwitcherConfig() }
    }

    /**
     * Writes [config] to [configPath] as formatted JSON.
     */
    fun save(config: SwitcherConfig) {
        configPath.parent?.let { Files.createDirectories(it) }
        configPath.writeText(toJson(config))
    }

    companion object {
        private val STRING_ARRAY_ITEM_REGEX = Regex(""""((?:[^"\\]|\\.)*)"""")

        fun resolveDefaultConfigPath(): Path {
            val appData = System.getenv("APPDATA")
            val baseDir = if (!appData.isNullOrBlank()) {
                Path(appData, "kwswitcher")
            } else {
                Path(System.getProperty("user.home", "."), ".kwswitcher")
            }
            return baseDir.resolve("config.json")
        }

        /**
         * Serializes [config] into the JSON schema defined in the specification.
         */
        fun toJson(config: SwitcherConfig): String {
            val processesJson = config.ignoredProcesses.joinToString(",\n") { "    \"${escapeJson(it)}\"" }
            val titlesJson = config.ignoredTitlePatterns.joinToString(",\n") { "    \"${escapeJson(it)}\"" }
            return buildString {
                appendLine("{")
                appendLine("  \"hotkey\": {")
                appendLine("    \"modifier\": \"${escapeJson(config.hotkey.modifier)}\",")
                appendLine("    \"triggerKey\": \"${escapeJson(config.hotkey.triggerKey)}\",")
                appendLine("    \"reverseModifier\": \"${escapeJson(config.hotkey.reverseModifier)}\"")
                appendLine("  },")
                appendLine("  \"theme\": \"${config.theme.name}\",")
                appendLine("  \"showMonitorBadge\": ${config.showMonitorBadge},")
                appendLine("  \"showMinimizedBadge\": ${config.showMinimizedBadge},")
                if (processesJson.isEmpty()) {
                    appendLine("  \"ignoredProcesses\": [],")
                } else {
                    appendLine("  \"ignoredProcesses\": [")
                    appendLine(processesJson)
                    appendLine("  ],")
                }
                if (titlesJson.isEmpty()) {
                    appendLine("  \"ignoredTitlePatterns\": []")
                } else {
                    appendLine("  \"ignoredTitlePatterns\": [")
                    appendLine(titlesJson)
                    appendLine("  ]")
                }
                append("}")
            }
        }

        /**
         * Parses a JSON string into a [SwitcherConfig], falling back to defaults for any omitted fields.
         */
        fun parseJson(json: String): SwitcherConfig {
            val defaults = SwitcherConfig()
            val modifier = extractString(json, "modifier") ?: defaults.hotkey.modifier
            val triggerKey = extractString(json, "triggerKey") ?: defaults.hotkey.triggerKey
            val reverseModifier = extractString(json, "reverseModifier") ?: defaults.hotkey.reverseModifier

            val themeStr = extractString(json, "theme")?.uppercase()
            val theme = themeStr?.let { name ->
                ThemeOption.entries.firstOrNull { it.name == name }
            } ?: defaults.theme

            val showMonitorBadge = extractBoolean(json, "showMonitorBadge") ?: defaults.showMonitorBadge
            val showMinimizedBadge = extractBoolean(json, "showMinimizedBadge") ?: defaults.showMinimizedBadge
            val ignoredProcesses = extractStringList(json, "ignoredProcesses") ?: defaults.ignoredProcesses
            val ignoredTitlePatterns = extractStringList(json, "ignoredTitlePatterns") ?: defaults.ignoredTitlePatterns

            return SwitcherConfig(
                hotkey = HotkeyConfig(
                    modifier = modifier,
                    triggerKey = triggerKey,
                    reverseModifier = reverseModifier,
                ),
                theme = theme,
                showMonitorBadge = showMonitorBadge,
                showMinimizedBadge = showMinimizedBadge,
                ignoredProcesses = ignoredProcesses,
                ignoredTitlePatterns = ignoredTitlePatterns,
            )
        }

        private fun extractString(json: String, key: String): String? {
            val regex = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            val match = regex.find(json) ?: return null
            return unescapeJson(match.groupValues[1])
        }

        private fun extractBoolean(json: String, key: String): Boolean? {
            val regex = Regex(""""${Regex.escape(key)}"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
            val match = regex.find(json) ?: return null
            return match.groupValues[1].lowercase().toBooleanStrictOrNull()
        }

        private fun extractStringList(json: String, key: String): List<String>? {
            val regex = Regex(""""${Regex.escape(key)}"\s*:\s*\[([^\]]*)]""", RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(json) ?: return null
            val arrayBody = match.groupValues[1]
            return STRING_ARRAY_ITEM_REGEX.findAll(arrayBody)
                .map { unescapeJson(it.groupValues[1]) }
                .toList()
        }

        private fun escapeJson(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun unescapeJson(value: String): String =
            value.replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
