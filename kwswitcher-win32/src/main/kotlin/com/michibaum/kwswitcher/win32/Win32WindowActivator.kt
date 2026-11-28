package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.core.domain.OverlayConstants
import com.michibaum.kwswitcher.core.domain.WindowHandle
import com.michibaum.kwswitcher.core.port.NativeResult
import com.michibaum.kwswitcher.core.port.WindowControlPort
import com.michibaum.kwswitcher.win32.bindings.HookNative
import com.michibaum.kwswitcher.win32.bindings.MutexNative
import com.michibaum.kwswitcher.win32.bindings.PanamaNative.allocateWideString
import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import com.michibaum.kwswitcher.win32.bindings.Win32Structs
import com.michibaum.kwswitcher.win32.bindings.WindowControlNative
import com.michibaum.kwswitcher.win32.bindings.WindowEnumNative
import com.michibaum.kwswitcher.win32.diagnostics.Win32ErrorFormatter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

private val logger = KotlinLogging.logger {}

/**
 * Executes native Win32 window state transitions (`FR-6`, `FR-7`, `DX-5`): foreground activation,
 * graceful close (`WM_CLOSE`), in-place minimize (`SW_MINIMIZE`), and forced process termination.
 */
class Win32WindowActivator : WindowControlPort {

    private val currentProcessId: Long = ProcessHandle.current().pid()

    @Volatile
    private var cachedOverlay: CachedOverlay? = null

    override fun activate(handle: WindowHandle): NativeResult {
        if (!handle.isValid) {
            return NativeResult.Failed(operation = "activate", detail = "Invalid zero WindowHandle")
        }
        val targetHwnd = MemorySegment.ofAddress(handle.value)

        val iconic = WindowEnumNative.IsIconic.invokeExact(targetHwnd) as Int
        if (iconic != 0) {
            WindowControlNative.ShowWindow.invokeExact(targetHwnd, Win32Constants.SW_RESTORE) as Int
        }

        val currentThreadId = HookNative.GetCurrentThreadId.invokeExact() as Int
        val foregroundHwnd = WindowEnumNative.GetForegroundWindow.invokeExact() as MemorySegment

        Arena.ofConfined().use { arena ->
            val foregroundThreadId = resolveAttachableForegroundThreadId(
                arena = arena,
                foregroundHwnd = foregroundHwnd,
                targetHwnd = targetHwnd,
                currentThreadId = currentThreadId,
            )
            val attached = foregroundThreadId != 0 && (
                WindowControlNative.AttachThreadInput.invokeExact(
                    currentThreadId,
                    foregroundThreadId,
                    1,
                ) as Int
                ) != 0

            try {
                sendZeroEffectUnlockKeyTap(arena)
                WindowControlNative.BringWindowToTop.invokeExact(targetHwnd) as Int
                val focused = WindowControlNative.SetForegroundWindow.invokeExact(targetHwnd) as Int
                return if (focused != 0) {
                    NativeResult.Ok
                } else {
                    val failure = Win32ErrorFormatter.describeLastError(
                        operation = "SetForegroundWindow(hwnd=${handle.value})",
                        fallbackDetail = "SetForegroundWindow returned FALSE",
                    )
                    logger.debug { "Window activation rejected for hwnd=${handle.value}: ${failure.detail}" }
                    failure
                }
            } finally {
                if (attached) {
                    WindowControlNative.AttachThreadInput.invokeExact(
                        currentThreadId,
                        foregroundThreadId,
                        0,
                    ) as Int
                }
            }
        }
    }

    override fun activateOverlay(overlayTitle: String): NativeResult {
        val handle = resolveOverlayHandle(overlayTitle)
        if (!handle.isValid) {
            return NativeResult.Failed(
                operation = "activateOverlay",
                detail = "Overlay window '$overlayTitle' owned by PID $currentProcessId not found",
            )
        }
        return activate(handle)
    }

    internal fun resolveOverlayHandle(overlayTitle: String = OverlayConstants.OVERLAY_WINDOW_TITLE): WindowHandle {
        if (overlayTitle.isBlank()) {
            return WindowHandle(0L)
        }
        return getValidCachedOverlay(overlayTitle) ?: lookupOverlayHandle(overlayTitle)
    }

    override fun closeGracefully(handle: WindowHandle): NativeResult {
        if (!handle.isValid) {
            return NativeResult.Failed(operation = "closeGracefully", detail = "Invalid zero WindowHandle")
        }
        val targetHwnd = MemorySegment.ofAddress(handle.value)
        val posted = WindowControlNative.PostMessageW.invokeExact(
            targetHwnd,
            Win32Constants.WM_CLOSE,
            0L,
            0L,
        ) as Int
        return if (posted != 0) {
            NativeResult.Ok
        } else {
            val failure = Win32ErrorFormatter.describeLastError("PostMessageW(WM_CLOSE, hwnd=${handle.value})")
            logger.warn { "Failed posting WM_CLOSE to hwnd=${handle.value}: ${failure.detail}" }
            failure
        }
    }

    override fun minimize(handle: WindowHandle): NativeResult {
        if (!handle.isValid) {
            return NativeResult.Failed(operation = "minimize", detail = "Invalid zero WindowHandle")
        }
        val targetHwnd = MemorySegment.ofAddress(handle.value)
        if ((WindowControlNative.IsWindow.invokeExact(targetHwnd) as Int) == 0) {
            return Win32ErrorFormatter.describeLastError(
                operation = "minimize(hwnd=${handle.value})",
                fallbackDetail = "Target HWND is no longer a valid window",
            )
        }
        WindowControlNative.ShowWindow.invokeExact(targetHwnd, Win32Constants.SW_MINIMIZE) as Int
        return NativeResult.Ok
    }

    override fun terminateProcess(pid: Int): NativeResult {
        if (pid <= 0) {
            return NativeResult.Failed(operation = "terminateProcess", detail = "Invalid non-positive PID $pid")
        }
        val processHandle = WindowEnumNative.OpenProcess.invokeExact(
            Win32Constants.PROCESS_TERMINATE,
            0,
            pid,
        ) as MemorySegment
        if (processHandle == MemorySegment.NULL) {
            val failure = Win32ErrorFormatter.describeLastError("OpenProcess(PROCESS_TERMINATE, pid=$pid)")
            logger.warn { "Cannot open PID $pid for termination: ${failure.detail}" }
            return failure
        }
        try {
            val result = WindowControlNative.TerminateProcess.invokeExact(processHandle, 1) as Int
            return if (result != 0) {
                logger.info { "Terminated process PID $pid" }
                NativeResult.Ok
            } else {
                val failure = Win32ErrorFormatter.describeLastError("TerminateProcess(pid=$pid)")
                logger.warn { "TerminateProcess failed for PID $pid: ${failure.detail}" }
                failure
            }
        } finally {
            MutexNative.CloseHandle.invokeExact(processHandle) as Int
        }
    }

    private fun getValidCachedOverlay(overlayTitle: String): WindowHandle? {
        val cached = cachedOverlay
        val isMatch = cached != null && cached.title == overlayTitle && cached.handle.isValid
        if (!isMatch) {
            return null
        }
        val hwndSegment = MemorySegment.ofAddress(cached.handle.value)
        val isAlive = (WindowControlNative.IsWindow.invokeExact(hwndSegment) as Int) != 0
        return if (isAlive) cached.handle else null
    }

    private fun lookupOverlayHandle(overlayTitle: String): WindowHandle {
        Arena.ofConfined().use { arena ->
            val titleSegment = arena.allocateWideString(overlayTitle)
            val pidSegment = arena.allocate(ValueLayout.JAVA_INT)
            var childAfter = MemorySegment.NULL
            var resolved = WindowHandle(0L)

            while (!resolved.isValid) {
                val candidate = WindowControlNative.FindWindowExW.invokeExact(
                    MemorySegment.NULL,
                    childAfter,
                    MemorySegment.NULL,
                    titleSegment,
                ) as MemorySegment
                if (candidate == MemorySegment.NULL) {
                    break
                }
                pidSegment.set(ValueLayout.JAVA_INT, 0L, 0)
                WindowEnumNative.GetWindowThreadProcessId.invokeExact(candidate, pidSegment) as Int
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
            (WindowControlNative.IsHungAppWindow.invokeExact(foregroundHwnd) as Int) == 0
        if (!canAttach) {
            return 0
        }
        val dummyPid = arena.allocate(ValueLayout.JAVA_INT)
        val threadId = WindowEnumNative.GetWindowThreadProcessId.invokeExact(
            foregroundHwnd,
            dummyPid,
        ) as Int
        return if (threadId != 0 && threadId != currentThreadId) threadId else 0
    }

    private fun sendZeroEffectUnlockKeyTap(arena: Arena) {
        val inputs = arena.allocate(Win32Structs.INPUT_LAYOUT, UNLOCK_INPUT_COUNT.toLong())

        Win32Structs.INPUT_ARR_TYPE.set(inputs, 0L, Win32Constants.INPUT_KEYBOARD)
        Win32Structs.INPUT_ARR_W_VK.set(inputs, 0L, Win32Constants.VK_UNLOCK_FOREGROUND.toShort())
        Win32Structs.INPUT_ARR_DW_FLAGS.set(inputs, 0L, 0)

        Win32Structs.INPUT_ARR_TYPE.set(inputs, 1L, Win32Constants.INPUT_KEYBOARD)
        Win32Structs.INPUT_ARR_W_VK.set(inputs, 1L, Win32Constants.VK_UNLOCK_FOREGROUND.toShort())
        Win32Structs.INPUT_ARR_DW_FLAGS.set(inputs, 1L, Win32Constants.KEYEVENTF_KEYUP)

        WindowControlNative.SendInput.invokeExact(
            UNLOCK_INPUT_COUNT,
            inputs,
            Win32Structs.INPUT_LAYOUT.byteSize().toInt(),
        ) as Int
    }

    private data class CachedOverlay(val title: String, val handle: WindowHandle)

    companion object {
        private const val UNLOCK_INPUT_COUNT: Int = 2
    }
}
