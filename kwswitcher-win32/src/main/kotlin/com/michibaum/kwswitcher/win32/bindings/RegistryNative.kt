package com.michibaum.kwswitcher.win32.bindings

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Lazy `@JvmField` `advapi32.dll` bindings for querying and updating `HKCU\...\Run` startup entries.
 */
internal object RegistryNative {
    @JvmField
    val RegGetValueW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Advapi32Lib.lookup,
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
    val RegSetKeyValueW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Advapi32Lib.lookup,
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
    val RegDeleteKeyValueW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Advapi32Lib.lookup,
        name = "RegDeleteKeyValueW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    @JvmField
    val RegDeleteKeyW: MethodHandle = PanamaNative.downcall(
        lookup = PanamaNative.Advapi32Lib.lookup,
        name = "RegDeleteKeyW",
        descriptor = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )
}
