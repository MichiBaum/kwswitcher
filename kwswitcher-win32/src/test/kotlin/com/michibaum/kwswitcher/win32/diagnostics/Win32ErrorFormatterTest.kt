package com.michibaum.kwswitcher.win32.diagnostics

import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.Test
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
}
