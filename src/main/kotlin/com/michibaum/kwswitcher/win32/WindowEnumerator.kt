package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.io.path.Path
import kotlin.io.path.nameWithoutExtension

class WindowEnumerator(
    private val excludedHwndsProvider: () -> Set<WindowHandle> = { emptySet() },
) {
    private val currentProcessId: Long = ProcessHandle.current().pid()

    fun enumerateOpenWindows(): List<WindowInfo> {
        val results = mutableListOf<WindowInfo>()
        val excludedHwnds = excludedHwndsProvider()

        Arena.ofConfined().use { arena ->
            val callback = EnumCallback { hwndSegment, _ ->
                val hwndAddress = hwndSegment.address()
                val handle = WindowHandle(hwndAddress)
                if (handle.isValid && handle !in excludedHwnds) {
                    inspectWindow(
                        arena = arena,
                        hwnd = hwndSegment,
                        handle = handle,
                        zOrder = results.size,
                    )?.let { info ->
                        results.add(info)
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

        return results
    }

    private fun inspectWindow(
        arena: Arena,
        hwnd: MemorySegment,
        handle: WindowHandle,
        zOrder: Int,
    ): WindowInfo? {
        val visible = Win32Bindings.IsWindowVisible.invokeExact(hwnd) as Int
        if (visible == 0) {
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

        return WindowInfo(
            hwnd = handle,
            title = title,
            processName = processName,
            exePath = exePath,
            zOrder = zOrder,
        )
    }

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
