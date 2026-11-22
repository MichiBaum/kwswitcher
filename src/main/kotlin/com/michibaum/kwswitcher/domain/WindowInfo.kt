package com.michibaum.kwswitcher.domain

/**
 * Type-safe wrapper around a native Win32 `HWND` pointer address.
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
