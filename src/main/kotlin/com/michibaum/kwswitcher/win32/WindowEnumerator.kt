package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.config.SwitcherConfig
import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import java.awt.Rectangle
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.io.path.Path
import kotlin.io.path.nameWithoutExtension

/**
 * Enumerates visible top-level windows on the current virtual desktop in Z-order (`FR-3`, `FR-4`),
 * resolving process metadata, minimized state, monitor labels, and ignore-list filtering.
 */
class WindowEnumerator internal constructor(
    private val excludedHwndsProvider: () -> Set<WindowHandle>,
    private val configProvider: () -> SwitcherConfig,
    private val processImagePathResolver: (pid: Int, scratch: ReusableScratch) -> String,
) {
    constructor(
        excludedHwndsProvider: () -> Set<WindowHandle> = { emptySet() },
        configProvider: () -> SwitcherConfig = { DEFAULT_CONFIG },
    ) : this(
        excludedHwndsProvider = excludedHwndsProvider,
        configProvider = configProvider,
        processImagePathResolver = Companion::queryProcessImagePath,
    )

    private val currentProcessId: Long = ProcessHandle.current().pid()

    /**
     * Enumerates all eligible open windows in top-to-bottom Z-order.
     */
    fun enumerateOpenWindows(): List<WindowInfo> {
        val rawWindows = mutableListOf<RawWindowEntry>()
        val excludedHwnds = excludedHwndsProvider()
        val config = configProvider()
        val monitors = linkedMapOf<Long, Boolean>() // hMonitor -> isPrimary
        val processCache = HashMap<Int, ProcessMetadata>()

        Arena.ofConfined().use { arena ->
            val scratch = ReusableScratch(arena)
            val visitor = WindowVisitor { hwndSegment ->
                val hwndAddress = hwndSegment.address()
                val handle = WindowHandle(hwndAddress)
                if (handle.isValid && handle !in excludedHwnds) {
                    inspectWindow(
                        arena = arena,
                        scratch = scratch,
                        processCache = processCache,
                        hwnd = hwndSegment,
                        handle = handle,
                        config = config,
                    )?.let { entry ->
                        if (entry.hMonitor != 0L && entry.hMonitor !in monitors) {
                            monitors[entry.hMonitor] = isPrimaryMonitor(
                                scratch = scratch,
                                hMonitorAddress = entry.hMonitor,
                            )
                        }
                        rawWindows.add(entry)
                    }
                }
                1 // TRUE to continue enumeration
            }

            val previousVisitor = UpcallBridge.activeVisitor.get()
            UpcallBridge.activeVisitor.set(visitor)
            try {
                Win32Bindings.Enumeration.EnumWindows.invokeExact(UpcallBridge.sharedUpcallStub, 0L) as Int
            } finally {
                if (previousVisitor != null) {
                    UpcallBridge.activeVisitor.set(previousVisitor)
                } else {
                    UpcallBridge.activeVisitor.remove()
                }
            }
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
            val cursorOk = Win32Bindings.Enumeration.GetCursorPos.invokeExact(pointSegment) as Int
            val hMonitor: MemorySegment = if (cursorOk != 0) {
                val x = Win32Bindings.POINT_X.get(pointSegment) as Int
                val y = Win32Bindings.POINT_Y.get(pointSegment) as Int
                val packedPoint = packPoint(x = x, y = y)
                Win32Bindings.Enumeration.MonitorFromPoint.invokeExact(
                    packedPoint,
                    Win32Bindings.MONITOR_DEFAULTTONEAREST,
                ) as MemorySegment
            } else {
                val fg = Win32Bindings.Enumeration.GetForegroundWindow.invokeExact() as MemorySegment
                Win32Bindings.Enumeration.MonitorFromWindow.invokeExact(
                    fg,
                    Win32Bindings.MONITOR_DEFAULTTONEAREST,
                ) as MemorySegment
            }

            if (hMonitor == MemorySegment.NULL) {
                return null
            }

            val monitorInfo = arena.allocate(Win32Bindings.MONITORINFO_LAYOUT)
            Win32Bindings.MONITORINFO_CB_SIZE.set(monitorInfo, Win32Bindings.MONITORINFO_LAYOUT.byteSize().toInt())
            val ok = Win32Bindings.Enumeration.GetMonitorInfoW.invokeExact(hMonitor, monitorInfo) as Int
            if (ok == 0) {
                return null
            }

            val left = Win32Bindings.MONITORINFO_WORK_LEFT.get(monitorInfo) as Int
            val top = Win32Bindings.MONITORINFO_WORK_TOP.get(monitorInfo) as Int
            val right = Win32Bindings.MONITORINFO_WORK_RIGHT.get(monitorInfo) as Int
            val bottom = Win32Bindings.MONITORINFO_WORK_BOTTOM.get(monitorInfo) as Int
            val width = right - left
            val height = bottom - top
            return if (width > 0 && height > 0) Rectangle(left, top, width, height) else null
        }
    }

    private fun inspectWindow(
        arena: Arena,
        scratch: ReusableScratch,
        processCache: MutableMap<Int, ProcessMetadata>,
        hwnd: MemorySegment,
        handle: WindowHandle,
        config: SwitcherConfig,
    ): RawWindowEntry? {
        val visible = Win32Bindings.Enumeration.IsWindowVisible.invokeExact(hwnd) as Int
        if (visible == 0) {
            return null
        }

        val enabled = Win32Bindings.Enumeration.IsWindowEnabled.invokeExact(hwnd) as Int
        if (enabled == 0) {
            return null
        }

        val exStyle = Win32Bindings.Enumeration.GetWindowLongPtrW.invokeExact(hwnd, Win32Bindings.GWL_EXSTYLE) as Long
        val isToolWindow = (exStyle and Win32Bindings.WS_EX_TOOLWINDOW) != 0L
        val isAppWindow = (exStyle and Win32Bindings.WS_EX_APPWINDOW) != 0L
        val isNoActivate = (exStyle and Win32Bindings.WS_EX_NOACTIVATE) != 0L
        if ((isToolWindow && !isAppWindow) || isNoActivate) {
            return null
        }

        if (isWindowCloaked(scratch = scratch, hwnd = hwnd)) {
            return null
        }

        val title = readWindowTitle(arena = arena, scratch = scratch, hwnd = hwnd).trim()
        if (title.isEmpty()) {
            return null
        }

        scratch.pidSegment.set(ValueLayout.JAVA_INT, 0L, 0)
        Win32Bindings.Enumeration.GetWindowThreadProcessId.invokeExact(hwnd, scratch.pidSegment) as Int
        val pid = scratch.pidSegment.get(ValueLayout.JAVA_INT, 0L)
        if (pid.toLong() == currentProcessId && title == OVERLAY_WINDOW_TITLE) {
            return null
        }

        val metadata = resolveProcessMetadata(
            pid = pid,
            scratch = scratch,
            processCache = processCache,
        )

        if (config.isIgnored(processName = metadata.processName, title = title)) {
            return null
        }

        val isMinimized = (Win32Bindings.Enumeration.IsIconic.invokeExact(hwnd) as Int) != 0
        val hMonitorSegment = Win32Bindings.Enumeration.MonitorFromWindow.invokeExact(
            hwnd,
            Win32Bindings.MONITOR_DEFAULTTONEAREST,
        ) as MemorySegment

        return RawWindowEntry(
            hwnd = handle,
            title = title,
            processName = metadata.processName,
            exePath = metadata.exePath,
            pid = pid,
            isMinimized = isMinimized,
            hMonitor = hMonitorSegment.address(),
        )
    }

    internal fun resolveProcessMetadata(
        pid: Int,
        scratch: ReusableScratch,
        processCache: MutableMap<Int, ProcessMetadata>,
    ): ProcessMetadata {
        if (pid <= 0) {
            return UNKNOWN_PROCESS_METADATA
        }
        return processCache.getOrPut(pid) {
            val exePath = processImagePathResolver(pid, scratch)
            ProcessMetadata(
                exePath = exePath,
                processName = extractProcessName(exePath),
            )
        }
    }

    private fun isPrimaryMonitor(scratch: ReusableScratch, hMonitorAddress: Long): Boolean {
        if (hMonitorAddress == 0L) return false
        val monitorInfo = scratch.monitorInfoSegment
        Win32Bindings.MONITORINFO_CB_SIZE.set(monitorInfo, Win32Bindings.MONITORINFO_LAYOUT.byteSize().toInt())
        val ok = Win32Bindings.Enumeration.GetMonitorInfoW.invokeExact(
            MemorySegment.ofAddress(hMonitorAddress),
            monitorInfo,
        ) as Int
        if (ok == 0) return false
        val flags = Win32Bindings.MONITORINFO_DW_FLAGS.get(monitorInfo) as Int
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

    internal data class ProcessMetadata(
        val exePath: String,
        val processName: String,
    )

    internal class ReusableScratch(arena: Arena) {
        val pidSegment: MemorySegment = arena.allocate(ValueLayout.JAVA_INT)
        val cloakedSegment: MemorySegment = arena.allocate(ValueLayout.JAVA_INT)
        val pathBuffer: MemorySegment = arena.allocate(ValueLayout.JAVA_CHAR, PATH_BUFFER_CAPACITY.toLong())
        val pathSizeSegment: MemorySegment = arena.allocate(ValueLayout.JAVA_INT)
        val titleBuffer: MemorySegment = arena.allocate(ValueLayout.JAVA_CHAR, TITLE_BUFFER_CAPACITY.toLong())
        val monitorInfoSegment: MemorySegment = arena.allocate(Win32Bindings.MONITORINFO_LAYOUT)
    }

    private fun isWindowCloaked(scratch: ReusableScratch, hwnd: MemorySegment): Boolean {
        scratch.cloakedSegment.set(ValueLayout.JAVA_INT, 0L, 0)
        val hr = Win32Bindings.Enumeration.DwmGetWindowAttribute.invokeExact(
            hwnd,
            Win32Bindings.DWMWA_CLOAKED,
            scratch.cloakedSegment,
            ValueLayout.JAVA_INT.byteSize().toInt(),
        ) as Int
        if (hr != 0) {
            return false
        }
        return scratch.cloakedSegment.get(ValueLayout.JAVA_INT, 0L) != 0
    }

    private fun readWindowTitle(arena: Arena, scratch: ReusableScratch, hwnd: MemorySegment): String {
        val length = Win32Bindings.Enumeration.GetWindowTextLengthW.invokeExact(hwnd) as Int
        if (length <= 0) {
            return ""
        }
        val maxChars = length + 1
        val buffer = if (maxChars <= TITLE_BUFFER_CAPACITY) {
            scratch.titleBuffer
        } else {
            arena.allocate(ValueLayout.JAVA_CHAR, maxChars.toLong())
        }
        val copied = Win32Bindings.Enumeration.GetWindowTextW.invokeExact(hwnd, buffer, maxChars) as Int
        if (copied <= 0) {
            return ""
        }
        val chars = CharArray(copied)
        for (i in 0 until copied) {
            chars[i] = buffer.getAtIndex(ValueLayout.JAVA_CHAR, i.toLong())
        }
        return String(chars)
    }

    internal fun extractProcessName(exePath: String): String = Companion.extractProcessName(exePath)

    internal fun interface EnumCallback {
        fun invoke(hwnd: MemorySegment, lParam: Long): Int
    }

    private fun interface WindowVisitor {
        fun visit(hwnd: MemorySegment): Int
    }

    private object UpcallBridge {
        val activeVisitor = ThreadLocal<WindowVisitor?>()

        private val dispatchCallback = EnumCallback { hwnd, _ ->
            activeVisitor.get()?.visit(hwnd) ?: 1
        }

        @JvmField
        val sharedUpcallStub: MemorySegment = Win32Bindings.linker.upcallStub(
            ENUM_CALLBACK_HANDLE.bindTo(dispatchCallback),
            Win32Bindings.ENUM_WINDOWS_PROC_DESC,
            Arena.global(),
        )
    }

    companion object {
        const val OVERLAY_WINDOW_TITLE: String = "Kotlin Switcheroo Overlay"
        private const val PATH_BUFFER_CAPACITY: Int = 1024
        private const val TITLE_BUFFER_CAPACITY: Int = 512
        private val DEFAULT_CONFIG = SwitcherConfig()
        private val UNKNOWN_PROCESS_METADATA = ProcessMetadata(exePath = "", processName = "Unknown")

        internal val ENUM_CALLBACK_HANDLE: MethodHandle = MethodHandles.lookup().findVirtual(
            EnumCallback::class.java,
            "invoke",
            MethodType.methodType(
                Int::class.javaPrimitiveType,
                MemorySegment::class.java,
                Long::class.javaPrimitiveType,
            ),
        )

        internal val sharedUpcallStubAddress: Long
            get() = UpcallBridge.sharedUpcallStub.address()

        internal fun packPoint(x: Int, y: Int): Long =
            (x.toLong() and 0xFFFF_FFFFL) or (y.toLong() shl 32)

        internal fun queryProcessImagePath(pid: Int, scratch: ReusableScratch): String {
            if (pid <= 0) return ""
            val processHandle = Win32Bindings.Enumeration.OpenProcess.invokeExact(
                Win32Bindings.PROCESS_QUERY_LIMITED_INFORMATION,
                0,
                pid,
            ) as MemorySegment
            if (processHandle == MemorySegment.NULL) {
                return ""
            }
            try {
                scratch.pathSizeSegment.set(ValueLayout.JAVA_INT, 0L, PATH_BUFFER_CAPACITY)
                val ok = Win32Bindings.Enumeration.QueryFullProcessImageNameW.invokeExact(
                    processHandle,
                    0,
                    scratch.pathBuffer,
                    scratch.pathSizeSegment,
                ) as Int
                if (ok == 0) {
                    return ""
                }
                val actualLength = scratch.pathSizeSegment.get(ValueLayout.JAVA_INT, 0L)
                if (actualLength <= 0) {
                    return ""
                }
                val chars = CharArray(actualLength)
                for (i in 0 until actualLength) {
                    chars[i] = scratch.pathBuffer.getAtIndex(ValueLayout.JAVA_CHAR, i.toLong())
                }
                return String(chars)
            } finally {
                Win32Bindings.Mutex.CloseHandle.invokeExact(processHandle) as Int
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
    }
}
