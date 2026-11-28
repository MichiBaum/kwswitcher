package com.michibaum.kwswitcher.win32.bindings

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Lazy `@JvmField` `user32.dll` + `kernel32.dll` bindings for `WH_KEYBOARD_LL` and message pumping.
 */
internal object HookNative {
    @JvmField
    val SetWindowsHookExW: MethodHandle = PanamaNative.downcallCapturingLastError(
        lookup = PanamaNative.User32Lib.lookup,
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
    val CallNextHookEx: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
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
    val UnhookWindowsHookEx: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "UnhookWindowsHookEx",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val GetMessageW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
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
    val PeekMessageW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "PeekMessageW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val TranslateMessage: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "TranslateMessage",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val DispatchMessageW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "DispatchMessageW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val PostThreadMessageW: MethodHandle = PanamaNative.downcallCapturingLastError(
        lookup = PanamaNative.User32Lib.lookup,
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
    val GetAsyncKeyState: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "GetAsyncKeyState",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_SHORT,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val GetCurrentThreadId: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Kernel32Lib.lookup,
        name = "GetCurrentThreadId",
        descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT),
    )

    @JvmField
    val GetModuleHandleW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Kernel32Lib.lookup,
        name = "GetModuleHandleW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )
}
