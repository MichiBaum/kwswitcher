package com.michibaum.kwswitcher.win32.diagnostics

import com.michibaum.kwswitcher.core.port.NativeResult
import com.michibaum.kwswitcher.win32.bindings.MutexNative
import com.michibaum.kwswitcher.win32.bindings.PanamaNative.readWideString
import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.Locale

/**
 * Formats Win32 `GetLastError()` and registry `LSTATUS` codes into human-readable messages via `FormatMessageW` (`DX-5`).
 */
object Win32ErrorFormatter {

    private const val MESSAGE_BUFFER_CHARS = 512
    private const val HEX_PAD_LENGTH = 8
    private const val HEX_RADIX = 16

    fun lastErrorCode(): Int = MutexNative.GetLastError.invokeExact() as Int

    fun formatErrorCode(errorCode: Int): String {
        val hex = "0x" + errorCode.toUInt().toString(HEX_RADIX).uppercase(Locale.ROOT).padStart(HEX_PAD_LENGTH, '0')
        if (errorCode == 0) {
            return "Win32 code 0 ($hex): The operation completed successfully."
        }
        val systemText = querySystemMessage(errorCode)
        return if (systemText.isNotBlank()) {
            "Win32 error $errorCode ($hex): $systemText"
        } else {
            "Win32 error $errorCode ($hex)"
        }
    }

    fun describeLastError(operation: String, fallbackDetail: String = ""): NativeResult.Failed {
        val code = lastErrorCode()
        val detail = if (code != 0) {
            formatErrorCode(code)
        } else {
            fallbackDetail.ifBlank { "Win32 call returned failure with GetLastError=0" }
        }
        return NativeResult.Failed(
            operation = operation,
            errorCode = code,
            detail = detail,
        )
    }

    private fun querySystemMessage(errorCode: Int): String {
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(ValueLayout.JAVA_CHAR, MESSAGE_BUFFER_CHARS.toLong())
            val flags = Win32Constants.FORMAT_MESSAGE_FROM_SYSTEM or Win32Constants.FORMAT_MESSAGE_IGNORE_INSERTS
            var copied = MutexNative.FormatMessageW.invokeExact(
                flags,
                MemorySegment.NULL,
                errorCode,
                Win32Constants.LANG_ENGLISH_US,
                buffer,
                MESSAGE_BUFFER_CHARS,
                MemorySegment.NULL,
            ) as Int
            if (copied <= 0) {
                copied = MutexNative.FormatMessageW.invokeExact(
                    flags,
                    MemorySegment.NULL,
                    errorCode,
                    0,
                    buffer,
                    MESSAGE_BUFFER_CHARS,
                    MemorySegment.NULL,
                ) as Int
            }
            if (copied <= 0) {
                return ""
            }
            return buffer.readWideString(copied).replace('\r', ' ').replace('\n', ' ').trim()
        }
    }
}
