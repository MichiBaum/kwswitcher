package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.config.HotkeyConfig
import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Win32EngineTest {

    @Test
    fun `Panama FFM struct layouts match 64-bit Windows ABI sizes`() {
        assertEquals(24L, Win32Bindings.KBDLLHOOKSTRUCT_LAYOUT.byteSize())
        assertEquals(48L, Win32Bindings.MSG_LAYOUT.byteSize())
        assertEquals(40L, Win32Bindings.INPUT_LAYOUT.byteSize())
        assertEquals(8L, Win32Bindings.POINT_LAYOUT.byteSize())
        assertEquals(16L, Win32Bindings.RECT_LAYOUT.byteSize())
        assertEquals(40L, Win32Bindings.MONITORINFO_LAYOUT.byteSize())
        assertEquals(32L, Win32Bindings.ICONINFO_LAYOUT.byteSize())
        assertEquals(32L, Win32Bindings.BITMAP_LAYOUT.byteSize())
        assertEquals(40L, Win32Bindings.BITMAPINFOHEADER_LAYOUT.byteSize())
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
            assertTrue(window.pid > 0, "Window PID should be positive")
        }

        val workArea = enumerator.getActiveMonitorWorkArea()
        assertNotNull(workArea)
        assertTrue(workArea.width > 0 && workArea.height > 0)
    }

    @Test
    fun `IconExtractor resolves and caches icons by executable path`() {
        val extractor = IconExtractor()
        val win = WindowInfo(
            hwnd = WindowHandle(0L),
            title = "Test Window",
            processName = "notepad",
            exePath = "C:\\Windows\\System32\\notepad.exe",
            zOrder = 0,
        )
        val firstIcon = extractor.getIcon(win)
        val secondIcon = extractor.getIcon(win)
        assertTrue(firstIcon.iconWidth > 0 && firstIcon.iconHeight > 0)
        assertSame(firstIcon, secondIcon)
    }

    @Test
    fun `SingleInstanceMutex acquires primary lock and rejects duplicate acquisition`() {
        val mutexName = "Local\\kwswitcher-test-mutex-${ProcessHandle.current().pid()}"
        SingleInstanceMutex(mutexName = mutexName).use { first ->
            assertTrue(first.tryAcquire())
            SingleInstanceMutex(mutexName = mutexName).use { second ->
                assertFalse(second.tryAcquire())
            }
        }
    }

    @Test
    fun `KeyboardHook state machine swallows trigger key when configured modifier is depressed`() {
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

        // Custom hotkey: CTRL + SPACE
        KeyboardHook(
            hotkeyConfigProvider = { HotkeyConfig(modifier = "CTRL", triggerKey = "SPACE") },
        ).use { customHook ->
            customHook.processKeyMessage(
                message = Win32Bindings.WM_KEYDOWN,
                vkCode = Win32Bindings.VK_LCONTROL,
                flags = 0,
            )
            val swallowedSpace = customHook.processKeyMessage(
                message = Win32Bindings.WM_KEYDOWN,
                vkCode = Win32Bindings.VK_SPACE,
                flags = 0,
            )
            assertTrue(swallowedSpace)
        }
    }
}
