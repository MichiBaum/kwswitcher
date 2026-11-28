package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.core.config.SwitcherConfig
import com.michibaum.kwswitcher.core.domain.OverlayConstants
import com.michibaum.kwswitcher.core.domain.WindowHandle
import com.michibaum.kwswitcher.core.domain.WindowInfo
import com.michibaum.kwswitcher.core.port.WindowCatalogPort
import com.michibaum.kwswitcher.win32.bindings.MutexNative
import com.michibaum.kwswitcher.win32.bindings.PanamaNative
import com.michibaum.kwswitcher.win32.bindings.PanamaNative.allocateCaptureState
import com.michibaum.kwswitcher.win32.bindings.PanamaNative.readCapturedLastError
import com.michibaum.kwswitcher.win32.bindings.PanamaNative.readWideString
import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import com.michibaum.kwswitcher.win32.bindings.Win32Structs
import com.michibaum.kwswitcher.win32.bindings.WindowEnumNative
import com.michibaum.kwswitcher.win32.diagnostics.Win32ErrorFormatter
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Rectangle
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.InvalidPathException
import kotlin.io.path.Path
import kotlin.io.path.nameWithoutExtension

private val logger = KotlinLogging.logger {}

/**
 * Enumerates visible top-level windows on the current virtual desktop in Z-order (`FR-3`, `FR-4`, `DX-5`),
 * resolving process metadata, minimized state, monitor labels, and ignore-list filtering.
 */
class Win32WindowEnumerator internal constructor(
    private val excludedHwndsProvider: () -> Set<WindowHandle>,
    private val configProvider: () -> SwitcherConfig,
    private val processImagePathResolver: (pid: Int, scratch: ReusableScratch) -> String,
) : WindowCatalogPort {

    constructor(
        excludedHwndsProvider: () -> Set<WindowHandle> = { emptySet() },
        configProvider: () -> SwitcherConfig = { DEFAULT_CONFIG },
    ) : this(
        excludedHwndsProvider = excludedHwndsProvider,
        configProvider = configProvider,
        processImagePathResolver = Companion::queryProcessImagePath,
    )

    private val currentProcessId: Long = ProcessHandle.current().pid()

    override fun enumerateOpenWindows(): List<WindowInfo> {
        val rawWindows = mutableListOf<RawWindowEntry>()
        var resolvedExcluded: Set<WindowHandle>? = null
        var resolvedConfig: SwitcherConfig? = null
        val monitors = linkedMapOf<Long, Boolean>()
        val processCache = HashMap<Int, ProcessMetadata>()

        Arena.ofConfined().use { arena ->
            val scratch = ReusableScratch(arena)
            val enumCaptureState = arena.allocateCaptureState()
            val visitor = WindowVisitor { hwndSegment ->
                val excludedHwnds = resolvedExcluded ?: excludedHwndsProvider().also { resolvedExcluded = it }
                val handle = WindowHandle(hwndSegment.address())
                if (handle.isValid && handle !in excludedHwnds) {
                    val config = resolvedConfig ?: configProvider().also { resolvedConfig = it }
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
                1
            }

            val previousVisitor = UpcallBridge.activeVisitor.get()
            UpcallBridge.activeVisitor.set(visitor)
            try {
                val ok = WindowEnumNative.EnumWindows.invokeExact(
                    enumCaptureState,
                    UpcallBridge.sharedUpcallStub,
                    0L,
                ) as Int
                if (ok == 0) {
                    val err = Win32ErrorFormatter.describeError(
                        operation = "EnumWindows",
                        errorCode = enumCaptureState.readCapturedLastError(),
                    )
                    logger.warn { "EnumWindows reported failure: ${err.detail}" }
                }
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

    override fun getActiveMonitorWorkArea(): Rectangle? {
        Arena.ofConfined().use { arena ->
            val hMonitor = resolveActiveMonitorHandle(arena)
            if (hMonitor == MemorySegment.NULL) {
                logger.debug { "Monitor lookup returned NULL handle" }
                return null
            }

            val captureState = arena.allocateCaptureState()
            val monitorInfo = arena.allocate(Win32Structs.MONITORINFO_LAYOUT)
            Win32Structs.MONITORINFO_CB_SIZE.set(monitorInfo, Win32Structs.MONITORINFO_LAYOUT.byteSize().toInt())
            val ok = WindowEnumNative.GetMonitorInfoW.invokeExact(captureState, hMonitor, monitorInfo) as Int
            if (ok == 0) {
                val err = Win32ErrorFormatter.describeError(
                    operation = "GetMonitorInfoW",
                    errorCode = captureState.readCapturedLastError(),
                )
                logger.debug { "GetMonitorInfoW failed: ${err.detail}" }
                return null
            }

            val left = Win32Structs.MONITORINFO_WORK_LEFT.get(monitorInfo) as Int
            val top = Win32Structs.MONITORINFO_WORK_TOP.get(monitorInfo) as Int
            val right = Win32Structs.MONITORINFO_WORK_RIGHT.get(monitorInfo) as Int
            val bottom = Win32Structs.MONITORINFO_WORK_BOTTOM.get(monitorInfo) as Int
            val width = right - left
            val height = bottom - top
            return if (width > 0 && height > 0) Rectangle(left, top, width, height) else null
        }
    }

    private fun resolveActiveMonitorHandle(arena: Arena): MemorySegment {
        val pointSegment = arena.allocate(Win32Structs.POINT_LAYOUT)
        val cursorOk = WindowEnumNative.GetCursorPos.invokeExact(pointSegment) as Int
        return if (cursorOk != 0) {
            val x = Win32Structs.POINT_X.get(pointSegment) as Int
            val y = Win32Structs.POINT_Y.get(pointSegment) as Int
            WindowEnumNative.MonitorFromPoint.invokeExact(
                packPoint(x = x, y = y),
                Win32Constants.MONITOR_DEFAULTTONEAREST,
            ) as MemorySegment
        } else {
            val fg = WindowEnumNative.GetForegroundWindow.invokeExact() as MemorySegment
            WindowEnumNative.MonitorFromWindow.invokeExact(
                fg,
                Win32Constants.MONITOR_DEFAULTTONEAREST,
            ) as MemorySegment
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
        if (!isEligibleWindowStyle(scratch = scratch, hwnd = hwnd)) {
            return null
        }

        val title = readWindowTitle(arena = arena, scratch = scratch, hwnd = hwnd).trim()
        if (title.isEmpty()) {
            return null
        }

        scratch.pidSegment.set(ValueLayout.JAVA_INT, 0L, 0)
        WindowEnumNative.GetWindowThreadProcessId.invokeExact(hwnd, scratch.pidSegment) as Int
        val pid = scratch.pidSegment.get(ValueLayout.JAVA_INT, 0L)
        if (pid.toLong() == currentProcessId && title == OverlayConstants.OVERLAY_WINDOW_TITLE) {
            return null
        }

        val metadata = resolveProcessMetadata(pid = pid, scratch = scratch, processCache = processCache)
        if (config.isIgnored(processName = metadata.processName, title = title)) {
            return null
        }

        val isMinimized = (WindowEnumNative.IsIconic.invokeExact(hwnd) as Int) != 0
        val hMonitorSegment = WindowEnumNative.MonitorFromWindow.invokeExact(
            hwnd,
            Win32Constants.MONITOR_DEFAULTTONEAREST,
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

    private fun isEligibleWindowStyle(scratch: ReusableScratch, hwnd: MemorySegment): Boolean {
        if ((WindowEnumNative.IsWindowVisible.invokeExact(hwnd) as Int) == 0) {
            return false
        }
        if ((WindowEnumNative.IsWindowEnabled.invokeExact(hwnd) as Int) == 0) {
            return false
        }
        val exStyle = WindowEnumNative.GetWindowLongPtrW.invokeExact(hwnd, Win32Constants.GWL_EXSTYLE) as Long
        val isToolWindow = (exStyle and Win32Constants.WS_EX_TOOLWINDOW) != 0L
        val isAppWindow = (exStyle and Win32Constants.WS_EX_APPWINDOW) != 0L
        val isNoActivate = (exStyle and Win32Constants.WS_EX_NOACTIVATE) != 0L
        if ((isToolWindow && !isAppWindow) || isNoActivate) {
            return false
        }
        return !isWindowCloaked(scratch = scratch, hwnd = hwnd)
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
        Win32Structs.MONITORINFO_CB_SIZE.set(monitorInfo, Win32Structs.MONITORINFO_LAYOUT.byteSize().toInt())
        val ok = WindowEnumNative.GetMonitorInfoW.invokeExact(
            scratch.captureState,
            MemorySegment.ofAddress(hMonitorAddress),
            monitorInfo,
        ) as Int
        if (ok == 0) return false
        val flags = Win32Structs.MONITORINFO_DW_FLAGS.get(monitorInfo) as Int
        return (flags and Win32Constants.MONITORINFOF_PRIMARY) != 0
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

    internal data class ProcessMetadata(val exePath: String, val processName: String)

    internal class ReusableScratch(arena: Arena) {
        val captureState: MemorySegment = arena.allocateCaptureState()
        val pidSegment: MemorySegment = arena.allocate(ValueLayout.JAVA_INT)
        val cloakedSegment: MemorySegment = arena.allocate(ValueLayout.JAVA_INT)
        val pathBuffer: MemorySegment = arena.allocate(ValueLayout.JAVA_CHAR, PATH_BUFFER_CAPACITY.toLong())
        val pathSizeSegment: MemorySegment = arena.allocate(ValueLayout.JAVA_INT)
        val titleBuffer: MemorySegment = arena.allocate(ValueLayout.JAVA_CHAR, TITLE_BUFFER_CAPACITY.toLong())
        val monitorInfoSegment: MemorySegment = arena.allocate(Win32Structs.MONITORINFO_LAYOUT)
    }

    private fun isWindowCloaked(scratch: ReusableScratch, hwnd: MemorySegment): Boolean {
        scratch.cloakedSegment.set(ValueLayout.JAVA_INT, 0L, 0)
        val hr = WindowEnumNative.DwmGetWindowAttribute.invokeExact(
            hwnd,
            Win32Constants.DWMWA_CLOAKED,
            scratch.cloakedSegment,
            ValueLayout.JAVA_INT.byteSize().toInt(),
        ) as Int
        return hr == 0 && scratch.cloakedSegment.get(ValueLayout.JAVA_INT, 0L) != 0
    }

    private fun readWindowTitle(arena: Arena, scratch: ReusableScratch, hwnd: MemorySegment): String {
        val length = WindowEnumNative.GetWindowTextLengthW.invokeExact(hwnd) as Int
        if (length <= 0) {
            return ""
        }
        val maxChars = length + 1
        val buffer = if (maxChars <= TITLE_BUFFER_CAPACITY) {
            scratch.titleBuffer
        } else {
            arena.allocate(ValueLayout.JAVA_CHAR, maxChars.toLong())
        }
        val copied = WindowEnumNative.GetWindowTextW.invokeExact(hwnd, buffer, maxChars) as Int
        return if (copied <= 0) "" else buffer.readWideString(copied)
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

        @Suppress("TooGenericExceptionCaught")
        private val dispatchCallback = EnumCallback { hwnd, _ ->
            try {
                activeVisitor.get()?.visit(hwnd) ?: 1
            } catch (e: Exception) {
                logger.error(e) { "Uncaught exception in EnumWindows callback; continuing enumeration" }
                1
            }
        }

        @JvmField
        val sharedUpcallStub: MemorySegment = PanamaNative.linker.upcallStub(
            ENUM_CALLBACK_HANDLE.bindTo(dispatchCallback),
            Win32Structs.ENUM_WINDOWS_PROC_DESC,
            PanamaNative.globalArena,
        )
    }

    companion object {
        const val OVERLAY_WINDOW_TITLE: String = OverlayConstants.OVERLAY_WINDOW_TITLE
        private const val PATH_BUFFER_CAPACITY: Int = 1024
        private const val TITLE_BUFFER_CAPACITY: Int = 512
        private const val LOWER_32_BITS_MASK: Long = 0xFFFF_FFFFL
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

        internal fun packPoint(x: Int, y: Int): Long = (x.toLong() and LOWER_32_BITS_MASK) or (y.toLong() shl 32)

        internal fun queryProcessImagePath(pid: Int, scratch: ReusableScratch): String {
            if (pid <= 0) return ""
            val processHandle = WindowEnumNative.OpenProcess.invokeExact(
                scratch.captureState,
                Win32Constants.PROCESS_QUERY_LIMITED_INFORMATION,
                0,
                pid,
            ) as MemorySegment
            if (processHandle == MemorySegment.NULL) {
                val code = scratch.captureState.readCapturedLastError()
                logger.trace {
                    "OpenProcess(pid=$pid) skipped (errorCode=$code)"
                }
                return ""
            }
            try {
                scratch.pathSizeSegment.set(ValueLayout.JAVA_INT, 0L, PATH_BUFFER_CAPACITY)
                val ok = WindowEnumNative.QueryFullProcessImageNameW.invokeExact(
                    processHandle,
                    0,
                    scratch.pathBuffer,
                    scratch.pathSizeSegment,
                ) as Int
                if (ok == 0) {
                    return ""
                }
                val actualLength = scratch.pathSizeSegment.get(ValueLayout.JAVA_INT, 0L)
                return scratch.pathBuffer.readWideString(actualLength)
            } finally {
                MutexNative.CloseHandle.invokeExact(processHandle) as Int
            }
        }

        internal fun extractProcessName(exePath: String): String {
            if (exePath.isBlank()) {
                return "Unknown"
            }
            return try {
                Path(exePath).nameWithoutExtension.ifBlank { "Unknown" }
            } catch (e: InvalidPathException) {
                logger.debug(e) { "Invalid executable path '$exePath'; using 'Unknown'" }
                "Unknown"
            }
        }
    }
}
