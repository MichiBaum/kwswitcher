package com.michibaum.kwswitcher.win32

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

object Win32Bindings {
    const val WH_KEYBOARD_LL: Int = 13
    const val HC_ACTION: Int = 0

    const val WM_KEYDOWN: Int = 0x0100
    const val WM_KEYUP: Int = 0x0101
    const val WM_SYSKEYDOWN: Int = 0x0104
    const val WM_SYSKEYUP: Int = 0x0105
    const val WM_QUIT: Int = 0x0012

    const val VK_TAB: Int = 0x09
    const val VK_SHIFT: Int = 0x10
    const val VK_MENU: Int = 0x12
    const val VK_LMENU: Int = 0xA4
    const val VK_RMENU: Int = 0xA5
    const val VK_LSHIFT: Int = 0xA0
    const val VK_RSHIFT: Int = 0xA1

    const val LLKHF_ALTDOWN: Int = 0x20

    const val GWL_EXSTYLE: Int = -20
    const val WS_EX_TOOLWINDOW: Long = 0x00000080L
    const val WS_EX_APPWINDOW: Long = 0x00040000L
    const val WS_EX_NOACTIVATE: Long = 0x08000000L

    const val DWMWA_CLOAKED: Int = 14
    const val PROCESS_QUERY_LIMITED_INFORMATION: Int = 0x1000

    const val SW_RESTORE: Int = 9
    const val INPUT_KEYBOARD: Int = 1
    const val KEYEVENTF_KEYUP: Int = 0x0002

    val linker: Linker = Linker.nativeLinker()
    private val globalArena: Arena = Arena.global()

    private val user32: SymbolLookup = SymbolLookup.libraryLookup("user32.dll", globalArena)
    private val kernel32: SymbolLookup = SymbolLookup.libraryLookup("kernel32.dll", globalArena)
    private val dwmapi: SymbolLookup = SymbolLookup.libraryLookup("dwmapi.dll", globalArena)

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
