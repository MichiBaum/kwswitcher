package com.michibaum.kwswitcher.win32.bindings

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemoryLayout.PathElement
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

/**
 * 64-bit Windows ABI struct [MemoryLayout]s, pre-bound [VarHandle]s, and upcall [FunctionDescriptor]s.
 * Contains zero native DLL lookups so ABI layout verification runs on any OS.
 */
object Win32Structs {

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

    private fun structVarHandle(layout: MemoryLayout, vararg elements: PathElement): VarHandle =
        MethodHandles.insertCoordinates(layout.varHandle(*elements), 1, 0L)

    private fun structArrayVarHandle(layout: MemoryLayout, vararg elements: PathElement): VarHandle =
        MethodHandles.insertCoordinates(layout.arrayElementVarHandle(*elements), 1, 0L)
}
