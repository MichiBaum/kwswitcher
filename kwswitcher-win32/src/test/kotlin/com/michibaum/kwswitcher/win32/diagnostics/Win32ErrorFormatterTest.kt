package com.michibaum.kwswitcher.win32.diagnostics

import com.michibaum.kwswitcher.win32.bindings.PanamaNative.allocateCaptureState
import com.michibaum.kwswitcher.win32.bindings.PanamaNative.readCapturedLastError
import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import com.michibaum.kwswitcher.win32.bindings.WindowControlNative
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS)
class Win32ErrorFormatterTest {

    @Test
    fun `formatErrorCode resolves known Win32 system messages with decimal and hex codes`() {
        val okMsg = Win32ErrorFormatter.formatErrorCode(Win32Constants.ERROR_SUCCESS)
        assertTrue(okMsg.contains("0x00000000"), "Expected hex code in '$okMsg'")

        val accessDenied = Win32ErrorFormatter.formatErrorCode(Win32Constants.ERROR_ACCESS_DENIED)
        assertTrue(accessDenied.contains("Win32 error 5 (0x00000005)"), "Expected code 5 in '$accessDenied'")
        assertTrue(accessDenied.lowercase().contains("access"), "Expected descriptive message in '$accessDenied'")

        val invalidHwnd = Win32ErrorFormatter.formatErrorCode(Win32Constants.ERROR_INVALID_WINDOW_HANDLE)
        assertTrue(invalidHwnd.contains("Win32 error 1400 (0x00000578)"), "Expected code 1400 in '$invalidHwnd'")
        assertTrue(invalidHwnd.lowercase().contains("window"), "Expected window handle text in '$invalidHwnd'")
    }

    @Test
    fun `describeError formats captured Win32 GetLastError codes and handles zero-code fallbacks`() {
        Arena.ofConfined().use { arena ->
            val captureState = arena.allocateCaptureState()
            val posted = WindowControlNative.PostMessageW.invokeExact(
                captureState,
                MemorySegment.ofAddress(0x7FFF_1234L),
                Win32Constants.WM_GETICON,
                0L,
                0L,
            ) as Int
            assertEquals(0, posted)
            val capturedCode = captureState.readCapturedLastError()
            assertEquals(Win32Constants.ERROR_INVALID_WINDOW_HANDLE, capturedCode)

            val failed = Win32ErrorFormatter.describeError(
                operation = "PostMessageW(test)",
                errorCode = capturedCode,
            )
            assertEquals("PostMessageW(test)", failed.operation)
            assertEquals(Win32Constants.ERROR_INVALID_WINDOW_HANDLE, failed.errorCode)
            assertTrue(failed.detail.contains("0x00000578"))
        }

        val customFallback = Win32ErrorFormatter.describeError(
            operation = "SetForegroundWindow",
            errorCode = 0,
            fallbackDetail = "SetForegroundWindow returned FALSE",
        )
        assertEquals(0, customFallback.errorCode)
        assertEquals("SetForegroundWindow returned FALSE", customFallback.detail)

        val defaultFallback = Win32ErrorFormatter.describeError(
            operation = "CustomOp",
            errorCode = 0,
        )
        assertEquals("Win32 call returned failure with GetLastError=0", defaultFallback.detail)
    }
}
