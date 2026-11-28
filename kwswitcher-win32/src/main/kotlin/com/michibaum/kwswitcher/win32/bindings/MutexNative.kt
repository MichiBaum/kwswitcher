package com.michibaum.kwswitcher.win32.bindings

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Lazy `@JvmField` `kernel32.dll` bindings for single-instance mutex management, handle cleanup,
 * and `GetLastError` / `FormatMessageW` diagnostics.
 */
internal object MutexNative {
    @JvmField
    val CreateMutexW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Kernel32Lib.lookup,
        name = "CreateMutexW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val ReleaseMutex: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Kernel32Lib.lookup,
        name = "ReleaseMutex",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val GetLastError: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Kernel32Lib.lookup,
        name = "GetLastError",
        descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT),
    )

    @JvmField
    val FormatMessageW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Kernel32Lib.lookup,
        name = "FormatMessageW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val CloseHandle: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Kernel32Lib.lookup,
        name = "CloseHandle",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )
}
