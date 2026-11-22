package com.michibaum.kwswitcher.win32

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Win32EngineTest {

    @Test
    fun `Panama FFM struct layouts match 64-bit Windows ABI sizes`() {
        assertEquals(24L, Win32Bindings.KBDLLHOOKSTRUCT_LAYOUT.byteSize())
        assertEquals(48L, Win32Bindings.MSG_LAYOUT.byteSize())
        assertEquals(40L, Win32Bindings.INPUT_LAYOUT.byteSize())
    }

    @Test
    fun `WindowEnumerator extracts process name and enumerates top-level windows`() {
        val enumerator = WindowEnumerator()
        assertEquals("idea64", enumerator.extractProcessName("C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe"))
        assertEquals("Unknown", enumerator.extractProcessName(""))

        val openWindows = enumerator.enumerateOpenWindows()
        // All returned windows must have non-empty titles and valid HWNDs
        for ((index, window) in openWindows.withIndex()) {
            assertTrue(window.hwnd.isValid, "Window HWND should be non-zero")
            assertTrue(window.title.isNotBlank(), "Window title should be non-blank")
            assertEquals(index, window.zOrder, "zOrder should be sequential")
        }
    }

    @Test
    fun `KeyboardHook state machine swallows Tab only when Alt is depressed`() {
        KeyboardHook().use { hook ->
            // Plain Tab without Alt is not swallowed
            val swallowedWithoutAlt = hook.processKeyMessage(
                message = Win32Bindings.WM_KEYDOWN,
                vkCode = Win32Bindings.VK_TAB,
                flags = 0,
            )
            assertFalse(swallowedWithoutAlt)

            // Alt key down followed by Tab key down is swallowed
            hook.processKeyMessage(
                message = Win32Bindings.WM_SYSKEYDOWN,
                vkCode = Win32Bindings.VK_LMENU,
                flags = Win32Bindings.LLKHF_ALTDOWN,
            )
            val swallowedWithAlt = hook.processKeyMessage(
                message = Win32Bindings.WM_SYSKEYDOWN,
                vkCode = Win32Bindings.VK_TAB,
                flags = Win32Bindings.LLKHF_ALTDOWN,
            )
            assertTrue(swallowedWithAlt)
        }
    }
}
