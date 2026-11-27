package com.michibaum.kwswitcher.win32

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/**
 * Project Panama (`java.lang.foreign`) native bindings and struct layouts for Win32 APIs
 * across `user32.dll`, `kernel32.dll`, `dwmapi.dll`, `gdi32.dll`, and `advapi32.dll`.
 *
 * Downcall [MethodHandle]s and DLL [SymbolLookup]s are grouped into lazy subsystem holder objects
 * ([Mutex], [Hook], [Enumeration], [Activator], [Icons], [Registry]) so cold startup and fast-fail
 * single-instance checks only load and link the native symbols they actually invoke, while HotSpot C2
 * still constant-folds every `@JvmField` handle.
 */
object Win32Bindings {
    const val WH_KEYBOARD_LL: Int = 13
    const val HC_ACTION: Int = 0

    const val WM_CLOSE: Int = 0x0010
    const val WM_QUIT: Int = 0x0012
    const val WM_GETICON: Int = 0x007F
    const val WM_KEYDOWN: Int = 0x0100
    const val WM_KEYUP: Int = 0x0101
    const val WM_SYSKEYDOWN: Int = 0x0104
    const val WM_SYSKEYUP: Int = 0x0105

    const val ICON_SMALL: Long = 0L
    const val ICON_BIG: Long = 1L
    const val ICON_SMALL2: Long = 2L

    const val GCLP_HICON: Int = -14
    const val GCLP_HICONSM: Int = -34

    const val SMTO_ABORTIFHUNG: Int = 0x0002

    const val VK_BACK: Int = 0x08
    const val VK_TAB: Int = 0x09
    const val VK_RETURN: Int = 0x0D
    const val VK_SHIFT: Int = 0x10
    const val VK_CONTROL: Int = 0x11
    const val VK_MENU: Int = 0x12
    const val VK_ESCAPE: Int = 0x1B
    const val VK_SPACE: Int = 0x20
    const val VK_UP: Int = 0x26
    const val VK_DOWN: Int = 0x28
    const val VK_DELETE: Int = 0x2E
    const val VK_LWIN: Int = 0x5B
    const val VK_RWIN: Int = 0x5C
    const val VK_NUMPAD0: Int = 0x60
    const val VK_NUMPAD1: Int = 0x61
    const val VK_NUMPAD2: Int = 0x62
    const val VK_NUMPAD3: Int = 0x63
    const val VK_NUMPAD4: Int = 0x64
    const val VK_NUMPAD5: Int = 0x65
    const val VK_NUMPAD6: Int = 0x66
    const val VK_NUMPAD7: Int = 0x67
    const val VK_NUMPAD8: Int = 0x68
    const val VK_NUMPAD9: Int = 0x69
    const val VK_LSHIFT: Int = 0xA0
    const val VK_RSHIFT: Int = 0xA1
    const val VK_LCONTROL: Int = 0xA2
    const val VK_RCONTROL: Int = 0xA3
    const val VK_LMENU: Int = 0xA4
    const val VK_RMENU: Int = 0xA5
    const val VK_OEM_3: Int = 0xC0
    const val VK_UNLOCK_FOREGROUND: Int = 0xFF

    const val LLKHF_INJECTED: Int = 0x10
    const val LLKHF_ALTDOWN: Int = 0x20

    const val GWL_STYLE: Int = -16
    const val GWL_EXSTYLE: Int = -20
    const val WS_DISABLED: Long = 0x08000000L
    const val WS_EX_TOOLWINDOW: Long = 0x00000080L
    const val WS_EX_APPWINDOW: Long = 0x00040000L
    const val WS_EX_NOACTIVATE: Long = 0x08000000L

    const val DWMWA_CLOAKED: Int = 14
    const val PROCESS_TERMINATE: Int = 0x0001
    const val PROCESS_QUERY_LIMITED_INFORMATION: Int = 0x1000

    const val SW_MINIMIZE: Int = 6
    const val SW_RESTORE: Int = 9
    const val INPUT_KEYBOARD: Int = 1
    const val KEYEVENTF_KEYUP: Int = 0x0002

    const val MONITOR_DEFAULTTONEAREST: Int = 2
    const val MONITORINFOF_PRIMARY: Int = 1
    const val DIB_RGB_COLORS: Int = 0
    const val BI_RGB: Int = 0
    const val ERROR_SUCCESS: Int = 0
    const val ERROR_FILE_NOT_FOUND: Int = 2
    const val ERROR_ALREADY_EXISTS: Int = 183
    const val ERROR_MORE_DATA: Int = 234

    const val REG_SZ: Int = 1
    const val RRF_RT_REG_SZ: Int = 0x00000002
    val HKEY_CURRENT_USER: Long = 0x80000001.toInt().toLong()

    val linker: Linker
        get() = NativeLinkerHolder.linker

    private object NativeLinkerHolder {
        @JvmField
        val linker: Linker = Linker.nativeLinker()

        @JvmField
        val globalArena: Arena = Arena.global()

        fun downcall(
            lookup: SymbolLookup,
            name: String,
            descriptor: FunctionDescriptor,
        ): MethodHandle {
            val symbol: MemorySegment = lookup.find(name).orElseThrow {
                IllegalStateException("Win32 symbol not found: $name")
            }
            return linker.downcallHandle(symbol, descriptor)
        }
    }

    private object Kernel32Lib {
        @JvmField
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("kernel32.dll", NativeLinkerHolder.globalArena)
    }

    private object User32Lib {
        @JvmField
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("user32.dll", NativeLinkerHolder.globalArena)
    }

    private object DwmapiLib {
        @JvmField
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("dwmapi.dll", NativeLinkerHolder.globalArena)
    }

    private object Gdi32Lib {
        @JvmField
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("gdi32.dll", NativeLinkerHolder.globalArena)
    }

    private object Advapi32Lib {
        @JvmField
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("advapi32.dll", NativeLinkerHolder.globalArena)
    }

    val KBDLLHOOKSTRUCT_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("vkCode"),
        ValueLayout.JAVA_INT.withName("scanCode"),
        ValueLayout.JAVA_INT.withName("flags"),
        ValueLayout.JAVA_INT.withName("time"),
        ValueLayout.JAVA_LONG.withName("dwExtraInfo"),
    )

    val MSG_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("hwnd"),
        ValueLayout.JAVA_INT.withName("message"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.JAVA_LONG.withName("wParam"),
        ValueLayout.JAVA_LONG.withName("lParam"),
        ValueLayout.JAVA_INT.withName("time"),
        ValueLayout.JAVA_INT.withName("pt_x"),
        ValueLayout.JAVA_INT.withName("pt_y"),
        ValueLayout.JAVA_INT.withName("lPrivate"),
    )

    // 40-byte INPUT struct on 64-bit Windows
    val INPUT_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("type"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.JAVA_SHORT.withName("wVk"),
        ValueLayout.JAVA_SHORT.withName("wScan"),
        ValueLayout.JAVA_INT.withName("dwFlags"),
        ValueLayout.JAVA_INT.withName("time"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.JAVA_LONG.withName("dwExtraInfo"),
        MemoryLayout.paddingLayout(8),
    )

    val POINT_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("x"),
        ValueLayout.JAVA_INT.withName("y"),
    )

    val RECT_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("left"),
        ValueLayout.JAVA_INT.withName("top"),
        ValueLayout.JAVA_INT.withName("right"),
        ValueLayout.JAVA_INT.withName("bottom"),
    )

    // 40-byte MONITORINFO struct
    val MONITORINFO_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("cbSize"),
        RECT_LAYOUT.withName("rcMonitor"),
        RECT_LAYOUT.withName("rcWork"),
        ValueLayout.JAVA_INT.withName("dwFlags"),
    )

    // 32-byte ICONINFO struct on 64-bit Windows
    val ICONINFO_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("fIcon"),
        ValueLayout.JAVA_INT.withName("xHotspot"),
        ValueLayout.JAVA_INT.withName("yHotspot"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("hbmMask"),
        ValueLayout.ADDRESS.withName("hbmColor"),
    )

    // 32-byte BITMAP struct on 64-bit Windows
    val BITMAP_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("bmType"),
        ValueLayout.JAVA_INT.withName("bmWidth"),
        ValueLayout.JAVA_INT.withName("bmHeight"),
        ValueLayout.JAVA_INT.withName("bmWidthBytes"),
        ValueLayout.JAVA_SHORT.withName("bmPlanes"),
        ValueLayout.JAVA_SHORT.withName("bmBitsPixel"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("bmBits"),
    )

    // 40-byte BITMAPINFOHEADER struct
    val BITMAPINFOHEADER_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("biSize"),
        ValueLayout.JAVA_INT.withName("biWidth"),
        ValueLayout.JAVA_INT.withName("biHeight"),
        ValueLayout.JAVA_SHORT.withName("biPlanes"),
        ValueLayout.JAVA_SHORT.withName("biBitCount"),
        ValueLayout.JAVA_INT.withName("biCompression"),
        ValueLayout.JAVA_INT.withName("biSizeImage"),
        ValueLayout.JAVA_INT.withName("biXPelsPerMeter"),
        ValueLayout.JAVA_INT.withName("biYPelsPerMeter"),
        ValueLayout.JAVA_INT.withName("biClrUsed"),
        ValueLayout.JAVA_INT.withName("biClrImportant"),
    )

    @JvmField
    val KBDLL_VK_CODE: VarHandle = structVarHandle(KBDLLHOOKSTRUCT_LAYOUT, PathElement.groupElement("vkCode"))

    @JvmField
    val KBDLL_FLAGS: VarHandle = structVarHandle(KBDLLHOOKSTRUCT_LAYOUT, PathElement.groupElement("flags"))

    @JvmField
    val POINT_X: VarHandle = structVarHandle(POINT_LAYOUT, PathElement.groupElement("x"))

    @JvmField
    val POINT_Y: VarHandle = structVarHandle(POINT_LAYOUT, PathElement.groupElement("y"))

    @JvmField
    val MONITORINFO_CB_SIZE: VarHandle = structVarHandle(MONITORINFO_LAYOUT, PathElement.groupElement("cbSize"))

    @JvmField
    val MONITORINFO_WORK_LEFT: VarHandle = structVarHandle(
        MONITORINFO_LAYOUT,
        PathElement.groupElement("rcWork"),
        PathElement.groupElement("left"),
    )

    @JvmField
    val MONITORINFO_WORK_TOP: VarHandle = structVarHandle(
        MONITORINFO_LAYOUT,
        PathElement.groupElement("rcWork"),
        PathElement.groupElement("top"),
    )

    @JvmField
    val MONITORINFO_WORK_RIGHT: VarHandle = structVarHandle(
        MONITORINFO_LAYOUT,
        PathElement.groupElement("rcWork"),
        PathElement.groupElement("right"),
    )

    @JvmField
    val MONITORINFO_WORK_BOTTOM: VarHandle = structVarHandle(
        MONITORINFO_LAYOUT,
        PathElement.groupElement("rcWork"),
        PathElement.groupElement("bottom"),
    )

    @JvmField
    val MONITORINFO_DW_FLAGS: VarHandle = structVarHandle(MONITORINFO_LAYOUT, PathElement.groupElement("dwFlags"))

    @JvmField
    val ICONINFO_HBM_MASK: VarHandle = structVarHandle(ICONINFO_LAYOUT, PathElement.groupElement("hbmMask"))

    @JvmField
    val ICONINFO_HBM_COLOR: VarHandle = structVarHandle(ICONINFO_LAYOUT, PathElement.groupElement("hbmColor"))

    @JvmField
    val BITMAP_BM_WIDTH: VarHandle = structVarHandle(BITMAP_LAYOUT, PathElement.groupElement("bmWidth"))

    @JvmField
    val BITMAP_BM_HEIGHT: VarHandle = structVarHandle(BITMAP_LAYOUT, PathElement.groupElement("bmHeight"))

    @JvmField
    val BMIH_BI_SIZE: VarHandle = structVarHandle(BITMAPINFOHEADER_LAYOUT, PathElement.groupElement("biSize"))

    @JvmField
    val BMIH_BI_WIDTH: VarHandle = structVarHandle(BITMAPINFOHEADER_LAYOUT, PathElement.groupElement("biWidth"))

    @JvmField
    val BMIH_BI_HEIGHT: VarHandle = structVarHandle(BITMAPINFOHEADER_LAYOUT, PathElement.groupElement("biHeight"))

    @JvmField
    val BMIH_BI_PLANES: VarHandle = structVarHandle(BITMAPINFOHEADER_LAYOUT, PathElement.groupElement("biPlanes"))

    @JvmField
    val BMIH_BI_BIT_COUNT: VarHandle = structVarHandle(BITMAPINFOHEADER_LAYOUT, PathElement.groupElement("biBitCount"))

    @JvmField
    val BMIH_BI_COMPRESSION: VarHandle = structVarHandle(
        BITMAPINFOHEADER_LAYOUT,
        PathElement.groupElement("biCompression"),
    )

    @JvmField
    val INPUT_ARR_TYPE: VarHandle = structArrayVarHandle(INPUT_LAYOUT, PathElement.groupElement("type"))

    @JvmField
    val INPUT_ARR_W_VK: VarHandle = structArrayVarHandle(INPUT_LAYOUT, PathElement.groupElement("wVk"))

    @JvmField
    val INPUT_ARR_DW_FLAGS: VarHandle = structArrayVarHandle(INPUT_LAYOUT, PathElement.groupElement("dwFlags"))

    val ENUM_WINDOWS_PROC_DESC: FunctionDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
    )

    val LOW_LEVEL_KEYBOARD_PROC_DESC: FunctionDescriptor = FunctionDescriptor.of(
        ValueLayout.JAVA_LONG,
        ValueLayout.JAVA_INT,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS,
    )

    /**
     * Minimal `kernel32.dll` bindings required for single-instance enforcement prior to full app bootstrap.
     */
    object Mutex {
        @JvmField
        val CreateMutexW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Kernel32Lib.lookup,
            name = "CreateMutexW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val ReleaseMutex: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Kernel32Lib.lookup,
            name = "ReleaseMutex",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val GetLastError: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Kernel32Lib.lookup,
            name = "GetLastError",
            descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT),
        )

        @JvmField
        val CloseHandle: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Kernel32Lib.lookup,
            name = "CloseHandle",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )
    }

    /**
     * Low-level keyboard hook and Win32 message-loop bindings (`user32.dll` + `kernel32.dll`).
     */
    object Hook {
        @JvmField
        val SetWindowsHookExW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "SetWindowsHookExW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val CallNextHookEx: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "CallNextHookEx",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val UnhookWindowsHookEx: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "UnhookWindowsHookEx",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val GetMessageW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetMessageW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val TranslateMessage: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "TranslateMessage",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val DispatchMessageW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "DispatchMessageW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val PostThreadMessageW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "PostThreadMessageW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
            ),
        )

        @JvmField
        val GetAsyncKeyState: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetAsyncKeyState",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_SHORT,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val GetCurrentThreadId: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Kernel32Lib.lookup,
            name = "GetCurrentThreadId",
            descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT),
        )

        @JvmField
        val GetModuleHandleW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Kernel32Lib.lookup,
            name = "GetModuleHandleW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )
    }

    /**
     * Window, process, DWM cloaking, and monitor enumeration bindings (`user32.dll`, `dwmapi.dll`, `kernel32.dll`).
     */
    object Enumeration {
        @JvmField
        val EnumWindows: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "EnumWindows",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )

        @JvmField
        val IsWindowVisible: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "IsWindowVisible",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val IsWindowEnabled: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "IsWindowEnabled",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val IsIconic: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "IsIconic",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val GetWindowTextLengthW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetWindowTextLengthW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val GetWindowTextW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetWindowTextW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val GetWindowLongPtrW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetWindowLongPtrW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val GetWindowThreadProcessId: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetWindowThreadProcessId",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val GetForegroundWindow: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetForegroundWindow",
            descriptor = FunctionDescriptor.of(ValueLayout.ADDRESS),
        )

        @JvmField
        val DwmGetWindowAttribute: MethodHandle = NativeLinkerHolder.downcall(
            lookup = DwmapiLib.lookup,
            name = "DwmGetWindowAttribute",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val OpenProcess: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Kernel32Lib.lookup,
            name = "OpenProcess",
            descriptor = FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val QueryFullProcessImageNameW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Kernel32Lib.lookup,
            name = "QueryFullProcessImageNameW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val MonitorFromWindow: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "MonitorFromWindow",
            descriptor = FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val MonitorFromPoint: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "MonitorFromPoint",
            descriptor = FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val GetMonitorInfoW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetMonitorInfoW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val GetCursorPos: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetCursorPos",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )
    }

    /**
     * Window activation, restore/minimize, graceful close, and process termination bindings.
     */
    object Activator {
        @JvmField
        val SetForegroundWindow: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "SetForegroundWindow",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val BringWindowToTop: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "BringWindowToTop",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val ShowWindow: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "ShowWindow",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val AttachThreadInput: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "AttachThreadInput",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val SendInput: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "SendInput",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val PostMessageW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "PostMessageW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
            ),
        )

        @JvmField
        val TerminateProcess: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Kernel32Lib.lookup,
            name = "TerminateProcess",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val FindWindowExW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "FindWindowExW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val IsWindow: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "IsWindow",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val IsHungAppWindow: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "IsHungAppWindow",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )
    }

    /**
     * Icon query and GDI bitmap conversion bindings (`user32.dll` + `gdi32.dll`), linked lazily off the startup path.
     */
    object Icons {
        @JvmField
        val SendMessageTimeoutW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "SendMessageTimeoutW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val GetClassLongPtrW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetClassLongPtrW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val GetIconInfo: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "GetIconInfo",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val DestroyIcon: MethodHandle = NativeLinkerHolder.downcall(
            lookup = User32Lib.lookup,
            name = "DestroyIcon",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val CreateCompatibleDC: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Gdi32Lib.lookup,
            name = "CreateCompatibleDC",
            descriptor = FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val DeleteDC: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Gdi32Lib.lookup,
            name = "DeleteDC",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val DeleteObject: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Gdi32Lib.lookup,
            name = "DeleteObject",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val GetObjectW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Gdi32Lib.lookup,
            name = "GetObjectW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val GetDIBits: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Gdi32Lib.lookup,
            name = "GetDIBits",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )
    }

    /**
     * Windows Registry (`advapi32.dll`) bindings for querying and toggling `HKCU\...\Run` without spawning `reg.exe`.
     */
    object Registry {
        @JvmField
        val RegGetValueW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Advapi32Lib.lookup,
            name = "RegGetValueW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val RegSetKeyValueW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Advapi32Lib.lookup,
            name = "RegSetKeyValueW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
            ),
        )

        @JvmField
        val RegDeleteKeyValueW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Advapi32Lib.lookup,
            name = "RegDeleteKeyValueW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )

        @JvmField
        val RegDeleteKeyW: MethodHandle = NativeLinkerHolder.downcall(
            lookup = Advapi32Lib.lookup,
            name = "RegDeleteKeyW",
            descriptor = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )
    }

    val EnumWindows: MethodHandle get() = Enumeration.EnumWindows
    val IsWindowVisible: MethodHandle get() = Enumeration.IsWindowVisible
    val IsIconic: MethodHandle get() = Enumeration.IsIconic
    val GetWindowTextLengthW: MethodHandle get() = Enumeration.GetWindowTextLengthW
    val GetWindowTextW: MethodHandle get() = Enumeration.GetWindowTextW
    val GetWindowLongPtrW: MethodHandle get() = Enumeration.GetWindowLongPtrW
    val GetWindowThreadProcessId: MethodHandle get() = Enumeration.GetWindowThreadProcessId
    val GetForegroundWindow: MethodHandle get() = Enumeration.GetForegroundWindow
    val SetForegroundWindow: MethodHandle get() = Activator.SetForegroundWindow
    val BringWindowToTop: MethodHandle get() = Activator.BringWindowToTop
    val ShowWindow: MethodHandle get() = Activator.ShowWindow
    val AttachThreadInput: MethodHandle get() = Activator.AttachThreadInput
    val SendInput: MethodHandle get() = Activator.SendInput
    val SetWindowsHookExW: MethodHandle get() = Hook.SetWindowsHookExW
    val CallNextHookEx: MethodHandle get() = Hook.CallNextHookEx
    val UnhookWindowsHookEx: MethodHandle get() = Hook.UnhookWindowsHookEx
    val GetMessageW: MethodHandle get() = Hook.GetMessageW
    val TranslateMessage: MethodHandle get() = Hook.TranslateMessage
    val DispatchMessageW: MethodHandle get() = Hook.DispatchMessageW
    val PostThreadMessageW: MethodHandle get() = Hook.PostThreadMessageW
    val GetAsyncKeyState: MethodHandle get() = Hook.GetAsyncKeyState
    val DwmGetWindowAttribute: MethodHandle get() = Enumeration.DwmGetWindowAttribute
    val OpenProcess: MethodHandle get() = Enumeration.OpenProcess
    val QueryFullProcessImageNameW: MethodHandle get() = Enumeration.QueryFullProcessImageNameW
    val CloseHandle: MethodHandle get() = Mutex.CloseHandle
    val GetCurrentThreadId: MethodHandle get() = Hook.GetCurrentThreadId
    val GetModuleHandleW: MethodHandle get() = Hook.GetModuleHandleW
    val IsWindowEnabled: MethodHandle get() = Enumeration.IsWindowEnabled
    val PostMessageW: MethodHandle get() = Activator.PostMessageW
    val SendMessageTimeoutW: MethodHandle get() = Icons.SendMessageTimeoutW
    val GetClassLongPtrW: MethodHandle get() = Icons.GetClassLongPtrW
    val GetIconInfo: MethodHandle get() = Icons.GetIconInfo
    val DestroyIcon: MethodHandle get() = Icons.DestroyIcon
    val MonitorFromWindow: MethodHandle get() = Enumeration.MonitorFromWindow
    val MonitorFromPoint: MethodHandle get() = Enumeration.MonitorFromPoint
    val GetMonitorInfoW: MethodHandle get() = Enumeration.GetMonitorInfoW
    val GetCursorPos: MethodHandle get() = Enumeration.GetCursorPos
    val CreateCompatibleDC: MethodHandle get() = Icons.CreateCompatibleDC
    val DeleteDC: MethodHandle get() = Icons.DeleteDC
    val DeleteObject: MethodHandle get() = Icons.DeleteObject
    val GetObjectW: MethodHandle get() = Icons.GetObjectW
    val GetDIBits: MethodHandle get() = Icons.GetDIBits
    val TerminateProcess: MethodHandle get() = Activator.TerminateProcess
    val FindWindowExW: MethodHandle get() = Activator.FindWindowExW
    val IsWindow: MethodHandle get() = Activator.IsWindow
    val IsHungAppWindow: MethodHandle get() = Activator.IsHungAppWindow
    val CreateMutexW: MethodHandle get() = Mutex.CreateMutexW
    val ReleaseMutex: MethodHandle get() = Mutex.ReleaseMutex
    val GetLastError: MethodHandle get() = Mutex.GetLastError

    private fun structVarHandle(
        layout: MemoryLayout,
        vararg elements: PathElement,
    ): VarHandle = MethodHandles.insertCoordinates(layout.varHandle(*elements), 1, 0L)

    private fun structArrayVarHandle(
        layout: MemoryLayout,
        vararg elements: PathElement,
    ): VarHandle = MethodHandles.insertCoordinates(layout.arrayElementVarHandle(*elements), 1, 0L)
}
