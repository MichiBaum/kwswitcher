package com.michibaum.kwswitcher.win32

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Queries and updates the `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` startup entry
 * directly via Panama FFM (`advapi32.dll`) without spawning external `reg.exe` subprocesses (`FR-9`).
 */
class StartupRegistry(
    private val subKey: String = DEFAULT_RUN_SUBKEY,
    private val valueName: String = DEFAULT_APP_VALUE_NAME,
) {

    /**
     * Checks whether [valueName] is currently registered as a `REG_SZ` entry under `HKCU\[subKey]`.
     */
    fun isRegistered(): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val subKeySeg = arena.allocateWideString(subKey)
            val valueNameSeg = arena.allocateWideString(valueName)
            val status = Win32Bindings.Registry.RegGetValueW.invokeExact(
                HKEY_CURRENT_USER_SEGMENT,
                subKeySeg,
                valueNameSeg,
                Win32Bindings.RRF_RT_REG_SZ,
                MemorySegment.NULL,
                MemorySegment.NULL,
                MemorySegment.NULL,
            ) as Int
            status == Win32Bindings.ERROR_SUCCESS
        }
    }.getOrDefault(false)

    /**
     * Reads the currently registered `REG_SZ` launch command, or `null` if absent.
     */
    internal fun getRegisteredCommand(): String? = runCatching {
        Arena.ofConfined().use { arena ->
            val subKeySeg = arena.allocateWideString(subKey)
            val valueNameSeg = arena.allocateWideString(valueName)
            val sizeSeg = arena.allocate(ValueLayout.JAVA_INT)
            val initialBytes = INITIAL_BUFFER_CHARS * ValueLayout.JAVA_CHAR.byteSize().toInt()
            sizeSeg.set(ValueLayout.JAVA_INT, 0L, initialBytes)
            var dataSeg = arena.allocate(ValueLayout.JAVA_CHAR, INITIAL_BUFFER_CHARS.toLong())

            var status = Win32Bindings.Registry.RegGetValueW.invokeExact(
                HKEY_CURRENT_USER_SEGMENT,
                subKeySeg,
                valueNameSeg,
                Win32Bindings.RRF_RT_REG_SZ,
                MemorySegment.NULL,
                dataSeg,
                sizeSeg,
            ) as Int

            if (status == Win32Bindings.ERROR_MORE_DATA) {
                val requiredBytes = sizeSeg.get(ValueLayout.JAVA_INT, 0L)
                if (requiredBytes <= 0) return@use null
                val requiredChars = (requiredBytes + 1) / 2
                dataSeg = arena.allocate(ValueLayout.JAVA_CHAR, requiredChars.toLong())
                sizeSeg.set(ValueLayout.JAVA_INT, 0L, requiredChars * 2)
                status = Win32Bindings.Registry.RegGetValueW.invokeExact(
                    HKEY_CURRENT_USER_SEGMENT,
                    subKeySeg,
                    valueNameSeg,
                    Win32Bindings.RRF_RT_REG_SZ,
                    MemorySegment.NULL,
                    dataSeg,
                    sizeSeg,
                ) as Int
            }

            if (status != Win32Bindings.ERROR_SUCCESS) {
                return@use null
            }
            val totalChars = (sizeSeg.get(ValueLayout.JAVA_INT, 0L) / 2).coerceAtLeast(0)
            var len = totalChars
            while (len > 0 && dataSeg.getAtIndex(ValueLayout.JAVA_CHAR, (len - 1).toLong()) == 0.toChar()) {
                len--
            }
            val chars = CharArray(len)
            for (i in 0 until len) {
                chars[i] = dataSeg.getAtIndex(ValueLayout.JAVA_CHAR, i.toLong())
            }
            String(chars)
        }
    }.getOrNull()

    /**
     * Enables or removes the startup registry value under `HKCU\[subKey]`.
     */
    fun setRegistered(
        enabled: Boolean,
        commandProvider: () -> String = ::defaultLaunchCommand,
    ): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val subKeySeg = arena.allocateWideString(subKey)
            val valueNameSeg = arena.allocateWideString(valueName)
            if (enabled) {
                val command = commandProvider()
                val dataSeg = arena.allocateWideString(command)
                val status = Win32Bindings.Registry.RegSetKeyValueW.invokeExact(
                    HKEY_CURRENT_USER_SEGMENT,
                    subKeySeg,
                    valueNameSeg,
                    Win32Bindings.REG_SZ,
                    dataSeg,
                    dataSeg.byteSize().toInt(),
                ) as Int
                status == Win32Bindings.ERROR_SUCCESS
            } else {
                val status = Win32Bindings.Registry.RegDeleteKeyValueW.invokeExact(
                    HKEY_CURRENT_USER_SEGMENT,
                    subKeySeg,
                    valueNameSeg,
                ) as Int
                status == Win32Bindings.ERROR_SUCCESS || status == Win32Bindings.ERROR_FILE_NOT_FOUND
            }
        }
    }.getOrDefault(false)

    internal fun deleteSubKey(): Boolean = runCatching {
        Arena.ofConfined().use { arena ->
            val subKeySeg = arena.allocateWideString(subKey)
            val status = Win32Bindings.Registry.RegDeleteKeyW.invokeExact(
                HKEY_CURRENT_USER_SEGMENT,
                subKeySeg,
            ) as Int
            status == Win32Bindings.ERROR_SUCCESS || status == Win32Bindings.ERROR_FILE_NOT_FOUND
        }
    }.getOrDefault(false)

    companion object {
        const val DEFAULT_RUN_SUBKEY: String = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val DEFAULT_APP_VALUE_NAME: String = "kwswitcher"
        private const val INITIAL_BUFFER_CHARS: Int = 512

        // Sign-extended 64-bit HKEY_CURRENT_USER ((HKEY)(ULONG_PTR)((LONG)0x80000001))
        private val HKEY_CURRENT_USER_SEGMENT: MemorySegment =
            MemorySegment.ofAddress(0x80000001.toInt().toLong())

        internal fun defaultLaunchCommand(): String {
            val exe = ProcessHandle.current().info().command().orElse("kwswitcher.exe")
            return "\"$exe\""
        }

        private fun Arena.allocateWideString(value: String): MemorySegment {
            val segment = allocate(ValueLayout.JAVA_CHAR, (value.length + 1).toLong())
            for (i in value.indices) {
                segment.setAtIndex(ValueLayout.JAVA_CHAR, i.toLong(), value[i])
            }
            segment.setAtIndex(ValueLayout.JAVA_CHAR, value.length.toLong(), 0.toChar())
            return segment
        }
    }
}
