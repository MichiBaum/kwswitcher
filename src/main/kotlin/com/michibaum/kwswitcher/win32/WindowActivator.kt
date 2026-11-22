package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.domain.WindowHandle
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Executes native Win32 window state transitions (`FR-6`, `FR-7`): foreground activation,
 * graceful close (`WM_CLOSE`), in-place minimize (`SW_MINIMIZE`), and forced process termination.
 */
class WindowActivator {

    /**
     * Restores [handle] if minimized and brings it to the foreground, bypassing Windows foreground lock.
     */
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

    /**
     * Posts a non-blocking `WM_CLOSE` message to [handle] (`Ctrl + W` / `Delete`).
     */
    fun closeGracefully(handle: WindowHandle): Boolean {
        if (!handle.isValid) return false
        val targetHwnd = MemorySegment.ofAddress(handle.value)
        val posted = Win32Bindings.PostMessageW.invokeExact(
            targetHwnd,
            Win32Bindings.WM_CLOSE,
            0L,
            0L,
        ) as Int
        return posted != 0
    }

    /**
     * Minimizes [handle] via `ShowWindow(hwnd, SW_MINIMIZE)` (`Ctrl + M`).
     */
    fun minimize(handle: WindowHandle): Boolean {
        if (!handle.isValid) return false
        val targetHwnd = MemorySegment.ofAddress(handle.value)
        Win32Bindings.ShowWindow.invokeExact(targetHwnd, Win32Bindings.SW_MINIMIZE) as Int
        return true
    }

    /**
     * Force-terminates the process identified by [pid] via `TerminateProcess` (`Ctrl + Shift + W`).
     */
    fun terminateProcess(pid: Int): Boolean {
        if (pid <= 0) return false
        val processHandle = Win32Bindings.OpenProcess.invokeExact(
            Win32Bindings.PROCESS_TERMINATE,
            0,
            pid,
        ) as MemorySegment
        if (processHandle == MemorySegment.NULL) {
            return false
        }
        try {
            val result = Win32Bindings.TerminateProcess.invokeExact(processHandle, 1) as Int
            return result != 0
        } finally {
            Win32Bindings.CloseHandle.invokeExact(processHandle) as Int
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
