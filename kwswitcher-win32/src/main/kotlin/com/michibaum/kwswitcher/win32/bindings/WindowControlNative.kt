package com.michibaum.kwswitcher.win32.bindings

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Lazy `@JvmField` `user32.dll` + `kernel32.dll` bindings for window focus, close, minimize, and process kill.
 */
internal object WindowControlNative {
    @JvmField
    val SetForegroundWindow: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "SetForegroundWindow",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val BringWindowToTop: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "BringWindowToTop",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val ShowWindow: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "ShowWindow",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val AttachThreadInput: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "AttachThreadInput",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val SendInput: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "SendInput",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val PostMessageW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
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
    val TerminateProcess: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Kernel32Lib.lookup,
        name = "TerminateProcess",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val FindWindowExW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
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
    val IsWindow: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "IsWindow",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val IsHungAppWindow: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "IsHungAppWindow",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )
}
