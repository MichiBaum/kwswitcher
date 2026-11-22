package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.domain.WindowHandle
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

class WindowActivator {

    fun activate(handle: WindowHandle): Boolean {
        if (!handle.isValid) {
            return false
        }
        val targetHwnd = MemorySegment.ofAddress(handle.value)

        val iconic = Win32Bindings.IsIconic.invokeExact(targetHwnd) as Int
        if (iconic != 0) {
            Win32Bindings.ShowWindow.invokeExact(targetHwnd, Win32Bindings.SW_RESTORE) as Int
        }

        val currentThreadId = Win32Bindings.GetCurrentThreadId.invokeExact() as Int
        val foregroundHwnd = Win32Bindings.GetForegroundWindow.invokeExact() as MemorySegment

        var foregroundThreadId = 0
        var attached = false

        Arena.ofConfined().use { arena ->
            if (foregroundHwnd != MemorySegment.NULL) {
                val dummyPid = arena.allocate(ValueLayout.JAVA_INT)
                foregroundThreadId = Win32Bindings.GetWindowThreadProcessId.invokeExact(
                    foregroundHwnd,
                    dummyPid,
                ) as Int
            }

            if (foregroundThreadId != 0 && foregroundThreadId != currentThreadId) {
                val attachResult = Win32Bindings.AttachThreadInput.invokeExact(
                    currentThreadId,
                    foregroundThreadId,
                    1,
                ) as Int
                attached = attachResult != 0
            }

            try {
                sendZeroEffectAltKeyTap(arena)
                Win32Bindings.BringWindowToTop.invokeExact(targetHwnd) as Int
                val focused = Win32Bindings.SetForegroundWindow.invokeExact(targetHwnd) as Int
                return focused != 0
            } finally {
                if (attached) {
                    Win32Bindings.AttachThreadInput.invokeExact(
                        currentThreadId,
                        foregroundThreadId,
                        0,
                    ) as Int
                }
            }
        }
    }

    private fun sendZeroEffectAltKeyTap(arena: Arena) {
        val inputSize = Win32Bindings.INPUT_LAYOUT.byteSize()
        val inputs = arena.allocate(inputSize * 2L, Win32Bindings.INPUT_LAYOUT.byteAlignment())

        // Key down (Alt)
        inputs.set(ValueLayout.JAVA_INT, 0L, Win32Bindings.INPUT_KEYBOARD)
        inputs.set(ValueLayout.JAVA_SHORT, 8L, Win32Bindings.VK_MENU.toShort())
        inputs.set(ValueLayout.JAVA_INT, 12L, 0)

        // Key up (Alt)
        val secondOffset = inputSize
        inputs.set(ValueLayout.JAVA_INT, secondOffset + 0L, Win32Bindings.INPUT_KEYBOARD)
        inputs.set(ValueLayout.JAVA_SHORT, secondOffset + 8L, Win32Bindings.VK_MENU.toShort())
        inputs.set(ValueLayout.JAVA_INT, secondOffset + 12L, Win32Bindings.KEYEVENTF_KEYUP)

        Win32Bindings.SendInput.invokeExact(2, inputs, inputSize.toInt()) as Int
    }
}
