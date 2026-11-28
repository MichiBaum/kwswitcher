package com.michibaum.kwswitcher.core.sandbox

import com.michibaum.kwswitcher.core.domain.WindowHandle
import com.michibaum.kwswitcher.core.port.NativeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SandboxWindowSystemTest {

    @Test
    fun `SandboxWindowSystem supports interactive activate minimize close terminateProcess and reset`() {
        val sandbox = SandboxWindowSystem()
        val initial = sandbox.enumerateOpenWindows()
        assertEquals(6, initial.size)

        // Activate minimized Slack window -> promotes to zOrder 0 and clears isMinimized
        val slackHwnd = WindowHandle(1004L)
        assertTrue(initial.first { it.hwnd == slackHwnd }.isMinimized)
        assertEquals(NativeResult.Ok, sandbox.activate(slackHwnd))

        val afterActivate = sandbox.enumerateOpenWindows()
        assertEquals(slackHwnd, afterActivate.first().hwnd)
        assertEquals(0, afterActivate.first().zOrder)
        assertFalse(afterActivate.first().isMinimized)

        // Minimize top window
        assertEquals(NativeResult.Ok, sandbox.minimize(slackHwnd))
        assertTrue(sandbox.enumerateOpenWindows().first().isMinimized)

        // Close Firefox window
        val firefoxHwnd = WindowHandle(1002L)
        assertEquals(NativeResult.Ok, sandbox.closeGracefully(firefoxHwnd))
        assertFalse(sandbox.enumerateOpenWindows().any { it.hwnd == firefoxHwnd })

        // Terminate IntelliJ PID (4120) removes both idea64 windows at once
        val ideaPid = initial.first { it.processName == "idea64" }.pid
        assertEquals(2, sandbox.enumerateOpenWindows().count { it.pid == ideaPid })
        assertEquals(NativeResult.Ok, sandbox.terminateProcess(ideaPid))
        assertEquals(0, sandbox.enumerateOpenWindows().count { it.pid == ideaPid })

        // Unknown handle returns NativeResult.Failed
        assertIs<NativeResult.Failed>(sandbox.activate(WindowHandle(999_999L)))

        // Reset restores initial sample windows
        sandbox.reset()
        assertEquals(initial, sandbox.enumerateOpenWindows())
    }

    @Test
    fun `SyntheticWindowIconPort caches monogram icons per process and InMemoryStartupPort toggles cleanly`() {
        SyntheticWindowIconPort().use { icons ->
            val samples = SandboxWindowSystem.defaultSampleWindows()
            val firstIdea = icons.getIcon(samples[0])
            val secondIdea = icons.getIcon(samples[4])
            assertSame(firstIdea, secondIdea)
            assertEquals(SyntheticWindowIconPort.DEFAULT_ICON_SIZE, firstIdea.iconWidth)

            icons.clearCache()
        }

        val startup = InMemoryStartupPort(initialRegistered = false)
        assertFalse(startup.isRegistered())
        assertTrue(startup.setRegistered(true))
        assertTrue(startup.isRegistered())
    }
}
