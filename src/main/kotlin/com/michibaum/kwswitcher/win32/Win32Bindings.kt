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
 * across `user32.dll`, `kernel32.dll`, `dwmapi.dll`, and `gdi32.dll`.
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

    const val VK_TAB: Int = 0x09
    const val VK_SHIFT: Int = 0x10
    const val VK_CONTROL: Int = 0x11
    const val VK_MENU: Int = 0x12
    const val VK_SPACE: Int = 0x20
    const val VK_LWIN: Int = 0x5B
    const val VK_RWIN: Int = 0x5C
    const val VK_LSHIFT: Int = 0xA0
    const val VK_RSHIFT: Int = 0xA1
    const val VK_LCONTROL: Int = 0xA2
    const val VK_RCONTROL: Int = 0xA3
    const val VK_LMENU: Int = 0xA4
    const val VK_RMENU: Int = 0xA5
    const val VK_OEM_3: Int = 0xC0

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
    const val ERROR_ALREADY_EXISTS: Int = 183

    val linker: Linker = Linker.nativeLinker()
    private val globalArena: Arena = Arena.global()

    private val user32: SymbolLookup = SymbolLookup.libraryLookup("user32.dll", globalArena)
    private val kernel32: SymbolLookup = SymbolLookup.libraryLookup("kernel32.dll", globalArena)
    private val dwmapi: SymbolLookup = SymbolLookup.libraryLookup("dwmapi.dll", globalArena)
    private val gdi32: SymbolLookup = SymbolLookup.libraryLookup("gdi32.dll", globalArena)

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

    val EnumWindows: MethodHandle = downcall(
        lookup = user32,
        name = "EnumWindows",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )

    val IsWindowVisible: MethodHandle = downcall(
        lookup = user32,
        name = "IsWindowVisible",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val IsIconic: MethodHandle = downcall(
        lookup = user32,
        name = "IsIconic",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val GetWindowTextLengthW: MethodHandle = downcall(
        lookup = user32,
        name = "GetWindowTextLengthW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val GetWindowTextW: MethodHandle = downcall(
        lookup = user32,
        name = "GetWindowTextW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    val GetWindowLongPtrW: MethodHandle = downcall(
        lookup = user32,
        name = "GetWindowLongPtrW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    val GetWindowThreadProcessId: MethodHandle = downcall(
        lookup = user32,
        name = "GetWindowThreadProcessId",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    val GetForegroundWindow: MethodHandle = downcall(
        lookup = user32,
        name = "GetForegroundWindow",
        descriptor = FunctionDescriptor.of(ValueLayout.ADDRESS),
    )

    val SetForegroundWindow: MethodHandle = downcall(
        lookup = user32,
        name = "SetForegroundWindow",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val BringWindowToTop: MethodHandle = downcall(
        lookup = user32,
        name = "BringWindowToTop",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val ShowWindow: MethodHandle = downcall(
        lookup = user32,
        name = "ShowWindow",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    val AttachThreadInput: MethodHandle = downcall(
        lookup = user32,
        name = "AttachThreadInput",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
        ),
    )

    val SendInput: MethodHandle = downcall(
        lookup = user32,
        name = "SendInput",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    val SetWindowsHookExW: MethodHandle = downcall(
        lookup = user32,
        name = "SetWindowsHookExW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    val CallNextHookEx: MethodHandle = downcall(
        lookup = user32,
        name = "CallNextHookEx",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    )

    val UnhookWindowsHookEx: MethodHandle = downcall(
        lookup = user32,
        name = "UnhookWindowsHookEx",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val GetMessageW: MethodHandle = downcall(
        lookup = user32,
        name = "GetMessageW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
        ),
    )

    val TranslateMessage: MethodHandle = downcall(
        lookup = user32,
        name = "TranslateMessage",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val DispatchMessageW: MethodHandle = downcall(
        lookup = user32,
        name = "DispatchMessageW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    )

    val PostThreadMessageW: MethodHandle = downcall(
        lookup = user32,
        name = "PostThreadMessageW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
        ),
    )

    val GetAsyncKeyState: MethodHandle = downcall(
        lookup = user32,
        name = "GetAsyncKeyState",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_INT,
        ),
    )

    val DwmGetWindowAttribute: MethodHandle = downcall(
        lookup = dwmapi,
        name = "DwmGetWindowAttribute",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    val OpenProcess: MethodHandle = downcall(
        lookup = kernel32,
        name = "OpenProcess",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
        ),
    )

    val QueryFullProcessImageNameW: MethodHandle = downcall(
        lookup = kernel32,
        name = "QueryFullProcessImageNameW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    val CloseHandle: MethodHandle = downcall(
        lookup = kernel32,
        name = "CloseHandle",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val GetCurrentThreadId: MethodHandle = downcall(
        lookup = kernel32,
        name = "GetCurrentThreadId",
        descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT),
    )

    val GetModuleHandleW: MethodHandle = downcall(
        lookup = kernel32,
        name = "GetModuleHandleW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    val IsWindowEnabled: MethodHandle = downcall(
        lookup = user32,
        name = "IsWindowEnabled",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val PostMessageW: MethodHandle = downcall(
        lookup = user32,
        name = "PostMessageW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_LONG,
        ),
    )

    val SendMessageTimeoutW: MethodHandle = downcall(
        lookup = user32,
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

    val GetClassLongPtrW: MethodHandle = downcall(
        lookup = user32,
        name = "GetClassLongPtrW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    val GetIconInfo: MethodHandle = downcall(
        lookup = user32,
        name = "GetIconInfo",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    val DestroyIcon: MethodHandle = downcall(
        lookup = user32,
        name = "DestroyIcon",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val MonitorFromWindow: MethodHandle = downcall(
        lookup = user32,
        name = "MonitorFromWindow",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    val MonitorFromPoint: MethodHandle = downcall(
        lookup = user32,
        name = "MonitorFromPoint",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
        ),
    )

    val GetMonitorInfoW: MethodHandle = downcall(
        lookup = user32,
        name = "GetMonitorInfoW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    val GetCursorPos: MethodHandle = downcall(
        lookup = user32,
        name = "GetCursorPos",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val CreateCompatibleDC: MethodHandle = downcall(
        lookup = gdi32,
        name = "CreateCompatibleDC",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    val DeleteDC: MethodHandle = downcall(
        lookup = gdi32,
        name = "DeleteDC",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val DeleteObject: MethodHandle = downcall(
        lookup = gdi32,
        name = "DeleteObject",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val GetObjectW: MethodHandle = downcall(
        lookup = gdi32,
        name = "GetObjectW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val GetDIBits: MethodHandle = downcall(
        lookup = gdi32,
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

    val TerminateProcess: MethodHandle = downcall(
        lookup = kernel32,
        name = "TerminateProcess",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    val CreateMutexW: MethodHandle = downcall(
        lookup = kernel32,
        name = "CreateMutexW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val ReleaseMutex: MethodHandle = downcall(
        lookup = kernel32,
        name = "ReleaseMutex",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    val GetLastError: MethodHandle = downcall(
        lookup = kernel32,
        name = "GetLastError",
        descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT),
    )

    private fun structVarHandle(
        layout: MemoryLayout,
        vararg elements: PathElement,
    ): VarHandle = MethodHandles.insertCoordinates(layout.varHandle(*elements), 1, 0L)

    private fun structArrayVarHandle(
        layout: MemoryLayout,
        vararg elements: PathElement,
    ): VarHandle = MethodHandles.insertCoordinates(layout.arrayElementVarHandle(*elements), 1, 0L)

    private fun downcall(
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
