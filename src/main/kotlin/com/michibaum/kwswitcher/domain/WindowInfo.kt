package com.michibaum.kwswitcher.domain

@JvmInline
value class WindowHandle(val value: Long) {
    val isValid: Boolean
        get() = value != 0L
}

data class WindowInfo(
    val hwnd: WindowHandle,
    val title: String,
    val processName: String,
    val exePath: String,
    val zOrder: Int,
)
