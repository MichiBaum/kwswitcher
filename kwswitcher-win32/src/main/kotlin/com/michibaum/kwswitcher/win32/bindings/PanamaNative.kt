package com.michibaum.kwswitcher.win32.bindings

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/**
 * Shared Project Panama (`java.lang.foreign`) linker, lazy Win32 DLL [SymbolLookup]s,
 * and UTF-16 (`LPWSTR`) memory helpers (`DX-3`).
 */
object PanamaNative {

    val linker: Linker
        get() = LinkerHolder.linker

    val globalArena: Arena
        get() = LinkerHolder.globalArena

    internal val CAPTURE_STATE_LAYOUT: StructLayout
        get() = CaptureStateHolder.layout

    internal fun downcall(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol: MemorySegment = lookup.find(name).orElseThrow {
            IllegalStateException("Win32 symbol not found: $name")
        }
        return LinkerHolder.linker.downcallHandle(symbol, descriptor)
    }

    internal fun downcallCapturingLastError(
        lookup: SymbolLookup,
        name: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle {
        val symbol: MemorySegment = lookup.find(name).orElseThrow {
            IllegalStateException("Win32 symbol not found: $name")
        }
        return LinkerHolder.linker.downcallHandle(symbol, descriptor, CaptureStateHolder.captureOption)
    }

    internal fun Arena.allocateCaptureState(): MemorySegment = allocate(CaptureStateHolder.layout)

    internal fun MemorySegment.readCapturedLastError(): Int = CaptureStateHolder.lastErrorHandle.get(this) as Int

    /**
     * Allocates a null-terminated UTF-16 (`WCHAR*` / `LPWSTR`) string in [this] [Arena].
     */
    fun Arena.allocateWideString(value: String): MemorySegment {
        val segment = allocate(ValueLayout.JAVA_CHAR, (value.length + 1).toLong())
        for (i in value.indices) {
            segment.setAtIndex(ValueLayout.JAVA_CHAR, i.toLong(), value[i])
        }
        segment.setAtIndex(ValueLayout.JAVA_CHAR, value.length.toLong(), 0.toChar())
        return segment
    }

    /**
     * Reads [charCount] UTF-16 `WCHAR` code units from [this] [MemorySegment], stripping trailing NUL characters.
     */
    fun MemorySegment.readWideString(charCount: Int): String {
        if (charCount <= 0) {
            return ""
        }
        var len = charCount
        while (len > 0 && getAtIndex(ValueLayout.JAVA_CHAR, (len - 1).toLong()) == 0.toChar()) {
            len--
        }
        if (len <= 0) {
            return ""
        }
        val chars = CharArray(len)
        for (i in 0 until len) {
            chars[i] = getAtIndex(ValueLayout.JAVA_CHAR, i.toLong())
        }
        return String(chars)
    }

    private object LinkerHolder {
        @JvmField
        val linker: Linker = Linker.nativeLinker()

        @JvmField
        val globalArena: Arena = Arena.global()
    }

    private object CaptureStateHolder {
        @JvmField
        val captureOption: Linker.Option = Linker.Option.captureCallState("GetLastError")

        @JvmField
        val layout: StructLayout = Linker.Option.captureStateLayout()

        @JvmField
        val lastErrorHandle: VarHandle = MethodHandles.insertCoordinates(
            layout.varHandle(MemoryLayout.PathElement.groupElement("GetLastError")),
            1,
            0L,
        )
    }

    internal object Kernel32Lib {
        @JvmField
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("kernel32.dll", LinkerHolder.globalArena)
    }

    internal object User32Lib {
        @JvmField
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("user32.dll", LinkerHolder.globalArena)
    }

    internal object DwmapiLib {
        @JvmField
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("dwmapi.dll", LinkerHolder.globalArena)
    }

    internal object Gdi32Lib {
        @JvmField
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("gdi32.dll", LinkerHolder.globalArena)
    }

    internal object Advapi32Lib {
        @JvmField
        val lookup: SymbolLookup = SymbolLookup.libraryLookup("advapi32.dll", LinkerHolder.globalArena)
    }
}
