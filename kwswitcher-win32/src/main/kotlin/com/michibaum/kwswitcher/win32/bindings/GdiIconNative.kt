package com.michibaum.kwswitcher.win32.bindings

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Lazy `@JvmField` `user32.dll` + `gdi32.dll` bindings for `HICON` lookup and DIB pixel extraction.
 */
internal object GdiIconNative {
    @JvmField
    val SendMessageTimeoutW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
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
    val GetClassLongPtrW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "GetClassLongPtrW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
        ),
    )

    @JvmField
    val GetIconInfo: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "GetIconInfo",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val DestroyIcon: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.User32Lib.lookup,
        name = "DestroyIcon",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val CreateCompatibleDC: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Gdi32Lib.lookup,
        name = "CreateCompatibleDC",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val DeleteDC: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Gdi32Lib.lookup,
        name = "DeleteDC",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val DeleteObject: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Gdi32Lib.lookup,
        name = "DeleteObject",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val GetObjectW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Gdi32Lib.lookup,
        name = "GetObjectW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val GetDIBits: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Gdi32Lib.lookup,
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
