package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.config.SwitcherConfig
import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import java.awt.Rectangle
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.io.path.Path
import kotlin.io.path.nameWithoutExtension

/**
 * Enumerates visible top-level windows on the current virtual desktop in Z-order (`FR-3`, `FR-4`),
 * resolving process metadata, minimized state, monitor labels, and ignore-list filtering.
 */
class WindowEnumerator(
    private val excludedHwndsProvider: () -> Set<WindowHandle> = { emptySet() },
    private val configProvider: () -> SwitcherConfig = { SwitcherConfig() },
) {
    private val currentProcessId: Long = ProcessHandle.current().pid()

    /**
     * Enumerates all eligible open windows in top-to-bottom Z-order.
     */
    fun enumerateOpenWindows(): List<WindowInfo> {
        val rawWindows = mutableListOf<RawWindowEntry>()
        val excludedHwnds = excludedHwndsProvider()
        val config = configProvider()
        val monitors = linkedMapOf<Long, Boolean>() // hMonitor -> isPrimary

        Arena.ofConfined().use { arena ->
            val callback = EnumCallback { hwndSegment, _ ->
                val hwndAddress = hwndSegment.address()
                val handle = WindowHandle(hwndAddress)
                if (handle.isValid && handle !in excludedHwnds) {
                    inspectWindow(
                        arena = arena,
                        hwnd = hwndSegment,
                        handle = handle,
                        config = config,
                    )?.let { entry ->
                        if (entry.hMonitor != 0L && entry.hMonitor !in monitors) {
                            monitors[entry.hMonitor] = isPrimaryMonitor(arena = arena, hMonitorAddress = entry.hMonitor)
                        }
                        rawWindows.add(entry)
                    }
                }
                1 // TRUE to continue enumeration
            }

            val callbackHandle = MethodHandles.lookup().findVirtual(
                EnumCallback::class.java,
                "invoke",
                MethodType.methodType(
                    Int::class.javaPrimitiveType,
                    MemorySegment::class.java,
                    Long::class.javaPrimitiveType,
                ),
            ).bindTo(callback)

            val upcallStub = Win32Bindings.linker.upcallStub(
                callbackHandle,
                Win32Bindings.ENUM_WINDOWS_PROC_DESC,
                arena,
            )

            Win32Bindings.EnumWindows.invokeExact(upcallStub, 0L) as Int
        }

        val monitorLabels = buildMonitorLabels(monitors)
        return rawWindows.mapIndexed { index, entry ->
            WindowInfo(
                hwnd = entry.hwnd,
                title = entry.title,
                processName = entry.processName,
                exePath = entry.exePath,
                zOrder = index,
                pid = entry.pid,
                isMinimized = entry.isMinimized,
                monitorId = monitorLabels[entry.hMonitor].orEmpty(),
            )
        }
    }

    /**
     * Queries the work area bounds (`rcWork`) of the monitor containing the mouse cursor or foreground window.
     */
    fun getActiveMonitorWorkArea(): Rectangle? {
        Arena.ofConfined().use { arena ->
            val pointSegment = arena.allocate(Win32Bindings.POINT_LAYOUT)
            val cursorOk = Win32Bindings.GetCursorPos.invokeExact(pointSegment) as Int
            val hMonitor: MemorySegment = if (cursorOk != 0) {
                val packedPoint = pointSegment.get(ValueLayout.JAVA_LONG, 0L)
                Win32Bindings.MonitorFromPoint.invokeExact(
                    packedPoint,
                    Win32Bindings.MONITOR_DEFAULTTONEAREST,
                ) as MemorySegment
            } else {
                val fg = Win32Bindings.GetForegroundWindow.invokeExact() as MemorySegment
                Win32Bindings.MonitorFromWindow.invokeExact(
                    fg,
                    Win32Bindings.MONITOR_DEFAULTTONEAREST,
                ) as MemorySegment
            }

            if (hMonitor == MemorySegment.NULL) {
                return null
            }

            val monitorInfo = arena.allocate(Win32Bindings.MONITORINFO_LAYOUT)
            monitorInfo.set(ValueLayout.JAVA_INT, 0L, Win32Bindings.MONITORINFO_LAYOUT.byteSize().toInt())
            val ok = Win32Bindings.GetMonitorInfoW.invokeExact(hMonitor, monitorInfo) as Int
            if (ok == 0) {
                return null
            }

            // rcWork starts at byte offset 20 (cbSize=4 + rcMonitor=16)
            val left = monitorInfo.get(ValueLayout.JAVA_INT, 20L)
            val top = monitorInfo.get(ValueLayout.JAVA_INT, 24L)
            val right = monitorInfo.get(ValueLayout.JAVA_INT, 28L)
            val bottom = monitorInfo.get(ValueLayout.JAVA_INT, 32L)
            val width = right - left
            val height = bottom - top
            return if (width > 0 && height > 0) Rectangle(left, top, width, height) else null
        }
    }

    private fun inspectWindow(
        arena: Arena,
        hwnd: MemorySegment,
        handle: WindowHandle,
        config: SwitcherConfig,
    ): RawWindowEntry? {
        val visible = Win32Bindings.IsWindowVisible.invokeExact(hwnd) as Int
        if (visible == 0) {
            return null
        }

        val enabled = Win32Bindings.IsWindowEnabled.invokeExact(hwnd) as Int
        if (enabled == 0) {
            return null
        }

        val exStyle = Win32Bindings.GetWindowLongPtrW.invokeExact(hwnd, Win32Bindings.GWL_EXSTYLE) as Long
        val isToolWindow = (exStyle and Win32Bindings.WS_EX_TOOLWINDOW) != 0L
        val isAppWindow = (exStyle and Win32Bindings.WS_EX_APPWINDOW) != 0L
        val isNoActivate = (exStyle and Win32Bindings.WS_EX_NOACTIVATE) != 0L
        if ((isToolWindow && !isAppWindow) || isNoActivate) {
            return null
        }

        if (isWindowCloaked(arena = arena, hwnd = hwnd)) {
            return null
        }

        val title = readWindowTitle(arena = arena, hwnd = hwnd).trim()
        if (title.isEmpty()) {
            return null
        }

        val pidSegment = arena.allocate(ValueLayout.JAVA_INT)
        Win32Bindings.GetWindowThreadProcessId.invokeExact(hwnd, pidSegment) as Int
        val pid = pidSegment.get(ValueLayout.JAVA_INT, 0)
        if (pid.toLong() == currentProcessId && title == OVERLAY_WINDOW_TITLE) {
            return null
        }

        val exePath = queryProcessImagePath(arena = arena, pid = pid)
        val processName = extractProcessName(exePath)

        if (config.isIgnored(processName = processName, title = title)) {
            return null
        }

        val isMinimized = (Win32Bindings.IsIconic.invokeExact(hwnd) as Int) != 0
        val hMonitorSegment = Win32Bindings.MonitorFromWindow.invokeExact(
            hwnd,
            Win32Bindings.MONITOR_DEFAULTTONEAREST,
        ) as MemorySegment

        return RawWindowEntry(
            hwnd = handle,
            title = title,
            processName = processName,
            exePath = exePath,
            pid = pid,
            isMinimized = isMinimized,
            hMonitor = hMonitorSegment.address(),
        )
    }

    private fun isPrimaryMonitor(arena: Arena, hMonitorAddress: Long): Boolean {
        if (hMonitorAddress == 0L) return false
        val monitorInfo = arena.allocate(Win32Bindings.MONITORINFO_LAYOUT)
        monitorInfo.set(ValueLayout.JAVA_INT, 0L, Win32Bindings.MONITORINFO_LAYOUT.byteSize().toInt())
        val ok = Win32Bindings.GetMonitorInfoW.invokeExact(
            MemorySegment.ofAddress(hMonitorAddress),
            monitorInfo,
        ) as Int
        if (ok == 0) return false
        val flags = monitorInfo.get(ValueLayout.JAVA_INT, 36L)
        return (flags and Win32Bindings.MONITORINFOF_PRIMARY) != 0
    }

    private fun buildMonitorLabels(monitors: Map<Long, Boolean>): Map<Long, String> {
        if (monitors.isEmpty()) return emptyMap()
        val ordered = monitors.entries.sortedWith(
            compareByDescending<Map.Entry<Long, Boolean>> { it.value }
                .thenBy { it.key },
        )
        return ordered.mapIndexed { idx, entry ->
            entry.key to "Mon ${idx + 1}"
        }.toMap()
    }

    private data class RawWindowEntry(
        val hwnd: WindowHandle,
        val title: String,
        val processName: String,
        val exePath: String,
        val pid: Int,
        val isMinimized: Boolean,
        val hMonitor: Long,
    )

    private fun isWindowCloaked(arena: Arena, hwnd: MemorySegment): Boolean {
        val cloakedSegment = arena.allocate(ValueLayout.JAVA_INT)
        val hr = Win32Bindings.DwmGetWindowAttribute.invokeExact(
            hwnd,
            Win32Bindings.DWMWA_CLOAKED,
            cloakedSegment,
            ValueLayout.JAVA_INT.byteSize().toInt(),
        ) as Int
        if (hr != 0) {
            return false
        }
        return cloakedSegment.get(ValueLayout.JAVA_INT, 0) != 0
    }

    private fun readWindowTitle(arena: Arena, hwnd: MemorySegment): String {
        val length = Win32Bindings.GetWindowTextLengthW.invokeExact(hwnd) as Int
        if (length <= 0) {
            return ""
        }
        val maxChars = length + 1
        val buffer = arena.allocate(ValueLayout.JAVA_CHAR, maxChars.toLong())
        val copied = Win32Bindings.GetWindowTextW.invokeExact(hwnd, buffer, maxChars) as Int
        if (copied <= 0) {
            return ""
        }
        val chars = CharArray(copied)
        for (i in 0 until copied) {
            chars[i] = buffer.getAtIndex(ValueLayout.JAVA_CHAR, i.toLong())
        }
        return String(chars)
    }

    private fun queryProcessImagePath(arena: Arena, pid: Int): String {
        if (pid <= 0) return ""
        val processHandle = Win32Bindings.OpenProcess.invokeExact(
            Win32Bindings.PROCESS_QUERY_LIMITED_INFORMATION,
            0,
            pid,
        ) as MemorySegment
        if (processHandle == MemorySegment.NULL) {
            return ""
        }
        try {
            val capacity = 1024
            val buffer = arena.allocate(ValueLayout.JAVA_CHAR, capacity.toLong())
            val sizeSegment = arena.allocate(ValueLayout.JAVA_INT)
            sizeSegment.set(ValueLayout.JAVA_INT, 0, capacity)

            val ok = Win32Bindings.QueryFullProcessImageNameW.invokeExact(
                processHandle,
                0,
                buffer,
                sizeSegment,
            ) as Int
            if (ok == 0) {
                return ""
            }
            val actualLength = sizeSegment.get(ValueLayout.JAVA_INT, 0)
            if (actualLength <= 0) {
                return ""
            }
            val chars = CharArray(actualLength)
            for (i in 0 until actualLength) {
                chars[i] = buffer.getAtIndex(ValueLayout.JAVA_CHAR, i.toLong())
            }
            return String(chars)
        } finally {
            Win32Bindings.CloseHandle.invokeExact(processHandle) as Int
        }
    }

    internal fun extractProcessName(exePath: String): String {
        if (exePath.isBlank()) {
            return "Unknown"
        }
        return runCatching {
            Path(exePath).nameWithoutExtension.ifBlank { "Unknown" }
        }.getOrDefault("Unknown")
    }

    private fun interface EnumCallback {
        fun invoke(hwnd: MemorySegment, lParam: Long): Int
    }

    companion object {
        const val OVERLAY_WINDOW_TITLE: String = "Kotlin Switcheroo Overlay"
    }
}
