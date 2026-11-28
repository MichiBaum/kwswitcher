package com.michibaum.kwswitcher.core.domain

/**
 * Type-safe wrapper around a native window handle (`HWND` address or synthetic sandbox ID).
 */
@JvmInline
value class WindowHandle(val value: Long) {
    val isValid: Boolean
        get() = value != 0L
}

/**
 * Snapshot of an open top-level window on the current virtual desktop.
 */
data class WindowInfo(
    val hwnd: WindowHandle,
    val title: String,
    val processName: String,
    val exePath: String,
    val zOrder: Int,
    val pid: Int = 0,
    val isMinimized: Boolean = false,
    val monitorId: String = "",
)
