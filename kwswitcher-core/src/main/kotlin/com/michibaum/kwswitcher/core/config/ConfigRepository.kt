package com.michibaum.kwswitcher.core.config

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/**
 * Structured outcome of loading or initializing `config.json` (`DX-4`).
 */
sealed interface ConfigLoadResult {
    val config: SwitcherConfig

    data class Loaded(override val config: SwitcherConfig, val warnings: List<String> = emptyList()) : ConfigLoadResult

    data class CreatedDefault(override val config: SwitcherConfig, val path: Path) : ConfigLoadResult

    data class FallbackOnError(override val config: SwitcherConfig, val error: String) : ConfigLoadResult
}

/**
 * Loads and persists [SwitcherConfig] using `kotlinx-serialization-json` with actionable diagnostics (`DX-4`).
 */
class ConfigRepository(
    val configPath: Path = resolveDefaultConfigPath(),
    private val defaultConfig: SwitcherConfig = SwitcherConfig(),
) {

    /**
     * Loads the configuration from [configPath], creating [defaultConfig] on disk if absent,
     * and returning structured diagnostics for parse errors or invalid regex patterns.
     */
    fun loadResult(): ConfigLoadResult {
        if (!configPath.exists()) {
            return try {
                save(defaultConfig)
                logger.info { "Created default configuration at $configPath" }
                ConfigLoadResult.CreatedDefault(config = defaultConfig, path = configPath)
            } catch (e: IOException) {
                val msg = "Failed to write initial configuration at $configPath: ${e.message}"
                logger.warn(e) { msg }
                ConfigLoadResult.FallbackOnError(config = defaultConfig, error = msg)
            }
        }

        return try {
            val rawJson = configPath.readText()
            val parsed = parseJson(rawJson)
            val warnings = parsed.validationWarnings
            for (warning in warnings) {
                logger.warn { "Configuration validation warning in $configPath: $warning" }
            }
            logger.debug { "Loaded configuration from $configPath (warnings=${warnings.size})" }
            ConfigLoadResult.Loaded(config = parsed, warnings = warnings)
        } catch (e: SerializationException) {
            val msg = "Malformed JSON in $configPath: ${e.message}"
            logger.warn(e) { "$msg; falling back to defaults" }
            ConfigLoadResult.FallbackOnError(config = defaultConfig, error = msg)
        } catch (e: IOException) {
            val msg = "Failed reading configuration from $configPath: ${e.message}"
            logger.warn(e) { "$msg; falling back to defaults" }
            ConfigLoadResult.FallbackOnError(config = defaultConfig, error = msg)
        }
    }

    /**
     * Convenience wrapper returning the resolved [SwitcherConfig] from [loadResult].
     */
    fun loadOrCreate(): SwitcherConfig = loadResult().config

    /**
     * Writes [config] to [configPath] as pretty-printed JSON.
     */
    fun save(config: SwitcherConfig) {
        configPath.parent?.let { Files.createDirectories(it) }
        configPath.writeText(toJson(config))
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val jsonCodec: Json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
            coerceInputValues = true
            decodeEnumsCaseInsensitive = true
        }

        fun resolveDefaultConfigPath(): Path {
            val appData = System.getenv("APPDATA")
            val baseDir = if (!appData.isNullOrBlank()) {
                Path(appData, "kwswitcher")
            } else {
                Path(System.getProperty("user.home", "."), ".kwswitcher")
            }
            return baseDir.resolve("config.json")
        }

        fun resolveDevConfigPath(): Path {
            val localAppData = System.getenv("LOCALAPPDATA")
            val baseDir = if (!localAppData.isNullOrBlank()) {
                Path(localAppData, "kwswitcher-dev")
            } else {
                Path(System.getProperty("user.home", "."), ".kwswitcher-dev")
            }
            return baseDir.resolve("config.json")
        }

        fun toJson(config: SwitcherConfig): String = jsonCodec.encodeToString(SwitcherConfig.serializer(), config)

        fun parseJson(json: String): SwitcherConfig = jsonCodec.decodeFromString(SwitcherConfig.serializer(), json)
    }
}
