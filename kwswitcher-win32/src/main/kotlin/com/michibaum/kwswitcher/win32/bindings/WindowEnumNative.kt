package com.michibaum.kwswitcher.win32.bindings

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Lazy `@JvmField` `user32.dll`, `dwmapi.dll`, and `kernel32.dll` bindings for window and monitor enumeration.
 */
internal object WindowEnumNative {
    @JvmField
    val EnumWindows: MethodHandle = PanamaNative.downcallCapturingLastError(
        lookup = PanamaNative.User32Lib.lookup,
        name = "EnumWindows",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )

    @JvmField
    val IsWindowVisible: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "IsWindowVisible",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val IsWindowEnabled: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "IsWindowEnabled",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val IsIconic: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "IsIconic",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val GetWindowTextLengthW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "GetWindowTextLengthW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val GetWindowTextW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "GetWindowTextW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val GetWindowLongPtrW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "GetWindowLongPtrW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val GetWindowThreadProcessId: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "GetWindowThreadProcessId",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val GetForegroundWindow: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "GetForegroundWindow",
        descriptor = FunctionDescriptor.of(ValueLayout.ADDRESS),
    )

    @JvmField
    val DwmGetWindowAttribute: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.DwmapiLib.lookup,
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
    val OpenProcess: MethodHandle = PanamaNative.downcallCapturingLastError(
        lookup = PanamaNative.Kernel32Lib.lookup,
        name = "OpenProcess",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val QueryFullProcessImageNameW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Kernel32Lib.lookup,
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
    val MonitorFromWindow: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "MonitorFromWindow",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val MonitorFromPoint: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "MonitorFromPoint",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val GetMonitorInfoW: MethodHandle = PanamaNative.downcallCapturingLastError(
        lookup = PanamaNative.User32Lib.lookup,
        name = "GetMonitorInfoW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val GetCursorPos: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "GetCursorPos",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )
}
