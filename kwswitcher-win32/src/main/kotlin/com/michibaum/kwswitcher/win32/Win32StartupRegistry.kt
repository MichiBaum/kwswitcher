package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.core.port.StartupRegistrationPort
import com.michibaum.kwswitcher.win32.bindings.PanamaNative.allocateWideString
import com.michibaum.kwswitcher.win32.bindings.PanamaNative.readWideString
import com.michibaum.kwswitcher.win32.bindings.RegistryNative
import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import com.michibaum.kwswitcher.win32.diagnostics.Win32ErrorFormatter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

private val logger = KotlinLogging.logger {}

/**
 * Queries and updates the `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` startup entry
 * directly via Panama FFM (`advapi32.dll`) (`FR-9`, `DX-5`).
 */
class Win32StartupRegistry(
    private val subKey: String = DEFAULT_RUN_SUBKEY,
    private val valueName: String = DEFAULT_APP_VALUE_NAME,
    private val commandProvider: () -> String = ::defaultLaunchCommand,
) : StartupRegistrationPort {

    @Suppress("TooGenericExceptionCaught")
    override fun isRegistered(): Boolean = try {
        Arena.ofConfined().use { arena ->
            val subKeySeg = arena.allocateWideString(subKey)
            val valueNameSeg = arena.allocateWideString(valueName)
            val status = RegistryNative.RegGetValueW.invokeExact(
                HKEY_CURRENT_USER_SEGMENT,
                subKeySeg,
                valueNameSeg,
                Win32Constants.RRF_RT_REG_SZ,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
            ) as Int
            if (status != Win32Constants.ERROR_SUCCESS && status != Win32Constants.ERROR_FILE_NOT_FOUND) {
                logger.debug {
                    "RegGetValueW($subKey\\$valueName) returned ${Win32ErrorFormatter.formatErrorCode(status)}"
                }
            }
            status == Win32Constants.ERROR_SUCCESS
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed querying Windows startup registry key '$subKey\\$valueName'" }
        false
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun getRegisteredCommand(): String? = try {
        Arena.ofConfined().use { arena ->
            readRegisteredValue(arena)
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed reading Windows startup command from '$subKey\\$valueName'" }
        null
    }

    private fun readRegisteredValue(arena: Arena): String? {
        val subKeySeg = arena.allocateWideString(subKey)
        val valueNameSeg = arena.allocateWideString(valueName)
        val sizeSeg = arena.allocate(ValueLayout.JAVA_INT)
        val initialBytes = INITIAL_BUFFER_CHARS * ValueLayout.JAVA_CHAR.byteSize().toInt()
        sizeSeg.set(ValueLayout.JAVA_INT, 0L, initialBytes)
        var dataSeg = arena.allocate(ValueLayout.JAVA_CHAR, INITIAL_BUFFER_CHARS.toLong())

        var status = RegistryNative.RegGetValueW.invokeExact(
            HKEY_CURRENT_USER_SEGMENT,
            subKeySeg,
            valueNameSeg,
            Win32Constants.RRF_RT_REG_SZ,
            MemorySegment.NULL,
            dataSeg,
            sizeSeg,
        ) as Int

        if (status == Win32Constants.ERROR_MORE_DATA) {
            val requiredBytes = sizeSeg.get(ValueLayout.JAVA_INT, 0L)
            if (requiredBytes <= 0) return null
            val requiredChars = (requiredBytes + 1) / 2
            dataSeg = arena.allocate(ValueLayout.JAVA_CHAR, requiredChars.toLong())
            sizeSeg.set(ValueLayout.JAVA_INT, 0L, requiredChars * 2)
            status = RegistryNative.RegGetValueW.invokeExact(
                HKEY_CURRENT_USER_SEGMENT,
                subKeySeg,
                valueNameSeg,
                Win32Constants.RRF_RT_REG_SZ,
                MemorySegment.NULL,
                dataSeg,
                sizeSeg,
            ) as Int
        }

        if (status != Win32Constants.ERROR_SUCCESS) {
            return null
        }
        val totalChars = (sizeSeg.get(ValueLayout.JAVA_INT, 0L) / 2).coerceAtLeast(0)
        return dataSeg.readWideString(totalChars)
    }

    override fun setRegistered(enabled: Boolean): Boolean = setRegistered(enabled, commandProvider)

    @Suppress("TooGenericExceptionCaught")
    fun setRegistered(enabled: Boolean, customCommandProvider: () -> String): Boolean = try {
        Arena.ofConfined().use { arena ->
            val subKeySeg = arena.allocateWideString(subKey)
            val valueNameSeg = arena.allocateWideString(valueName)
            if (enabled) {
                val command = customCommandProvider()
                val dataSeg = arena.allocateWideString(command)
                val status = RegistryNative.RegSetKeyValueW.invokeExact(
                    HKEY_CURRENT_USER_SEGMENT,
                    subKeySeg,
                    valueNameSeg,
                    Win32Constants.REG_SZ,
                    dataSeg,
                    dataSeg.byteSize().toInt(),
                ) as Int
                if (status != Win32Constants.ERROR_SUCCESS) {
                    logger.warn {
                        "RegSetKeyValueW($subKey\\$valueName) failed: ${Win32ErrorFormatter.formatErrorCode(status)}"
                    }
                    false
                } else {
                    logger.info { "Registered startup entry '$valueName' -> $command" }
                    true
                }
            } else {
                val status = RegistryNative.RegDeleteKeyValueW.invokeExact(
                    HKEY_CURRENT_USER_SEGMENT,
                    subKeySeg,
                    valueNameSeg,
                ) as Int
                val ok = status == Win32Constants.ERROR_SUCCESS || status == Win32Constants.ERROR_FILE_NOT_FOUND
                if (!ok) {
                    logger.warn {
                        "RegDeleteKeyValueW($subKey\\$valueName) failed: ${Win32ErrorFormatter.formatErrorCode(status)}"
                    }
                } else {
                    logger.info { "Removed startup entry '$valueName'" }
                }
                ok
            }
        }
    } catch (e: Exception) {
        logger.warn(e) { "Failed updating Windows startup registry key '$subKey\\$valueName'" }
        false
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun deleteSubKey(): Boolean = try {
        Arena.ofConfined().use { arena ->
            val subKeySeg = arena.allocateWideString(subKey)
            val status = RegistryNative.RegDeleteKeyW.invokeExact(
                HKEY_CURRENT_USER_SEGMENT,
                subKeySeg,
            ) as Int
            status == Win32Constants.ERROR_SUCCESS || status == Win32Constants.ERROR_FILE_NOT_FOUND
        }
    } catch (e: Exception) {
        logger.debug(e) { "Failed deleting test registry subkey '$subKey'" }
        false
    }

    companion object {
        const val DEFAULT_RUN_SUBKEY: String = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val DEFAULT_APP_VALUE_NAME: String = "kwswitcher"
        private const val INITIAL_BUFFER_CHARS: Int = 512

        private val HKEY_CURRENT_USER_SEGMENT: MemorySegment =
            MemorySegment.ofAddress(Win32Constants.HKEY_CURRENT_USER)

        internal fun defaultLaunchCommand(): String {
            val exe = ProcessHandle.current().info().command().orElse("kwswitcher.exe")
            return "\"$exe\""
        }
    }
}
