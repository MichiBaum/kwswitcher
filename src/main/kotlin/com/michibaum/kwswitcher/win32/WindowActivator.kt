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

    private val currentProcessId: Long = ProcessHandle.current().pid()

    @Volatile
    private var cachedOverlay: CachedOverlay? = null

    /**
     * Restores [handle] if minimized and brings it to the foreground, bypassing Windows foreground lock.
     */
    fun activate(handle: WindowHandle): Boolean {
        if (!handle.isValid) {
            return false
        }
        val targetHwnd = MemorySegment.ofAddress(handle.value)

        val iconic = Win32Bindings.Enumeration.IsIconic.invokeExact(targetHwnd) as Int
        if (iconic != 0) {
            Win32Bindings.Activator.ShowWindow.invokeExact(targetHwnd, Win32Bindings.SW_RESTORE) as Int
        }

        val currentThreadId = Win32Bindings.Hook.GetCurrentThreadId.invokeExact() as Int
        val foregroundHwnd = Win32Bindings.Enumeration.GetForegroundWindow.invokeExact() as MemorySegment

        Arena.ofConfined().use { arena ->
            val foregroundThreadId = resolveAttachableForegroundThreadId(
                arena = arena,
                foregroundHwnd = foregroundHwnd,
                targetHwnd = targetHwnd,
                currentThreadId = currentThreadId,
            )
            val attached = if (foregroundThreadId != 0) {
                val attachResult = Win32Bindings.Activator.AttachThreadInput.invokeExact(
                    currentThreadId,
                    foregroundThreadId,
                    1,
                ) as Int
                attachResult != 0
            } else {
                false
            }

            try {
                sendZeroEffectUnlockKeyTap(arena)
                Win32Bindings.Activator.BringWindowToTop.invokeExact(targetHwnd) as Int
                val focused = Win32Bindings.Activator.SetForegroundWindow.invokeExact(targetHwnd) as Int
                return focused != 0
            } finally {
                if (attached) {
                    Win32Bindings.Activator.AttachThreadInput.invokeExact(
                        currentThreadId,
                        foregroundThreadId,
                        0,
                    ) as Int
                }
            }
        }
    }

    /**
     * Resolves and brings the switcher overlay dialog ([overlayTitle]) owned by the current JVM process
     * to the Win32 foreground so Swing's search field receives real OS keyboard focus.
     */
    fun activateOverlay(overlayTitle: String = WindowEnumerator.OVERLAY_WINDOW_TITLE): Boolean {
        val handle = resolveOverlayHandle(overlayTitle)
        return handle.isValid && activate(handle)
    }

    /**
     * Finds the native `HWND` for a top-level window matching [overlayTitle] owned by this JVM process,
     * caching the result while `IsWindow` remains true.
     */
    internal fun resolveOverlayHandle(overlayTitle: String = WindowEnumerator.OVERLAY_WINDOW_TITLE): WindowHandle {
        if (overlayTitle.isBlank()) {
            return WindowHandle(0L)
        }
        return getValidCachedOverlay(overlayTitle) ?: lookupOverlayHandle(overlayTitle)
    }

    /**
     * Posts a non-blocking `WM_CLOSE` message to [handle] (`Ctrl + W` / `Delete`).
     */
    fun closeGracefully(handle: WindowHandle): Boolean {
        if (!handle.isValid) return false
        val targetHwnd = MemorySegment.ofAddress(handle.value)
        val posted = Win32Bindings.Activator.PostMessageW.invokeExact(
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
        Win32Bindings.Activator.ShowWindow.invokeExact(targetHwnd, Win32Bindings.SW_MINIMIZE) as Int
        return true
    }

    /**
     * Force-terminates the process identified by [pid] via `TerminateProcess` (`Ctrl + Shift + W`).
     */
    fun terminateProcess(pid: Int): Boolean {
        if (pid <= 0) return false
        val processHandle = Win32Bindings.Enumeration.OpenProcess.invokeExact(
            Win32Bindings.PROCESS_TERMINATE,
            0,
            pid,
        ) as MemorySegment
        if (processHandle == MemorySegment.NULL) {
            return false
        }
        try {
            val result = Win32Bindings.Activator.TerminateProcess.invokeExact(processHandle, 1) as Int
            return result != 0
        } finally {
            Win32Bindings.Mutex.CloseHandle.invokeExact(processHandle) as Int
        }
    }

    private fun getValidCachedOverlay(overlayTitle: String): WindowHandle? {
        val cached = cachedOverlay
        val isMatch = cached != null && cached.title == overlayTitle && cached.handle.isValid
        if (!isMatch) {
            return null
        }
        val hwndSegment = MemorySegment.ofAddress(cached.handle.value)
        val isAlive = (Win32Bindings.Activator.IsWindow.invokeExact(hwndSegment) as Int) != 0
        return if (isAlive) cached.handle else null
    }

    private fun lookupOverlayHandle(overlayTitle: String): WindowHandle {
        Arena.ofConfined().use { arena ->
            val titleSegment = arena.allocateWideString(overlayTitle)
            val pidSegment = arena.allocate(ValueLayout.JAVA_INT)
            var childAfter = MemorySegment.NULL
            var resolved = WindowHandle(0L)

            while (!resolved.isValid) {
                val candidate = Win32Bindings.Activator.FindWindowExW.invokeExact(
                    MemorySegment.NULL,
                    childAfter,
                    MemorySegment.NULL,
                    titleSegment,
                ) as MemorySegment
                if (candidate == MemorySegment.NULL) {
                    break
                }
                pidSegment.set(ValueLayout.JAVA_INT, 0L, 0)
                Win32Bindings.Enumeration.GetWindowThreadProcessId.invokeExact(candidate, pidSegment) as Int
                val pid = pidSegment.get(ValueLayout.JAVA_INT, 0L)
                if (pid.toLong() == currentProcessId) {
                    resolved = WindowHandle(candidate.address())
                    cachedOverlay = CachedOverlay(title = overlayTitle, handle = resolved)
                } else {
                    childAfter = candidate
                }
            }
            return resolved
        }
    }

    private fun resolveAttachableForegroundThreadId(
        arena: Arena,
        foregroundHwnd: MemorySegment,
        targetHwnd: MemorySegment,
        currentThreadId: Int,
    ): Int {
        val canAttach = foregroundHwnd != MemorySegment.NULL &&
            foregroundHwnd != targetHwnd &&
            (Win32Bindings.Activator.IsHungAppWindow.invokeExact(foregroundHwnd) as Int) == 0
        if (!canAttach) {
            return 0
        }
        val dummyPid = arena.allocate(ValueLayout.JAVA_INT)
        val threadId = Win32Bindings.Enumeration.GetWindowThreadProcessId.invokeExact(
            foregroundHwnd,
            dummyPid,
        ) as Int
        return if (threadId != 0 && threadId != currentThreadId) threadId else 0
    }

    private fun sendZeroEffectUnlockKeyTap(arena: Arena) {
        val inputs = arena.allocate(Win32Bindings.INPUT_LAYOUT, UNLOCK_INPUT_COUNT.toLong())

        // Key down (unassigned virtual key 0xFF to unlock foreground without altering Alt/menu state)
        Win32Bindings.INPUT_ARR_TYPE.set(inputs, 0L, Win32Bindings.INPUT_KEYBOARD)
        Win32Bindings.INPUT_ARR_W_VK.set(inputs, 0L, Win32Bindings.VK_UNLOCK_FOREGROUND.toShort())
        Win32Bindings.INPUT_ARR_DW_FLAGS.set(inputs, 0L, 0)

        // Key up (0xFF)
        Win32Bindings.INPUT_ARR_TYPE.set(inputs, 1L, Win32Bindings.INPUT_KEYBOARD)
        Win32Bindings.INPUT_ARR_W_VK.set(inputs, 1L, Win32Bindings.VK_UNLOCK_FOREGROUND.toShort())
        Win32Bindings.INPUT_ARR_DW_FLAGS.set(inputs, 1L, Win32Bindings.KEYEVENTF_KEYUP)

        Win32Bindings.Activator.SendInput.invokeExact(
            UNLOCK_INPUT_COUNT,
            inputs,
            Win32Bindings.INPUT_LAYOUT.byteSize().toInt(),
        ) as Int
    }

    private data class CachedOverlay(
        val title: String,
        val handle: WindowHandle,
    )

    companion object {
        private const val UNLOCK_INPUT_COUNT: Int = 2

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
