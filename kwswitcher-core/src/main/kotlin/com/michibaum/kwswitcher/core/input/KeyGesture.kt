package com.michibaum.kwswitcher.core.input

import java.awt.event.KeyEvent

/**
 * Platform-neutral logical key identifier shared between Swing [KeyEvent]s and Win32 `VK_*` keystrokes (`DX-3`).
 */
sealed interface LogicalKey {
    data object Up : LogicalKey
    data object Down : LogicalKey
    data object Tab : LogicalKey
    data object Enter : LogicalKey
    data object Escape : LogicalKey
    data object Delete : LogicalKey
    data object Backspace : LogicalKey
    data object Space : LogicalKey
    data class Character(val upperChar: Char) : LogicalKey
    data object Other : LogicalKey
}

/**
 * Origin of a normalized key gesture.
 */
enum class KeySource {
    OVERLAY_SWING,
    GLOBAL_HOOK,
}

/**
 * Normalized key gesture evaluated by [KeyCommandMapper].
 */
data class KeyGesture(
    val key: LogicalKey,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val altGr: Boolean = false,
    val source: KeySource = KeySource.OVERLAY_SWING,
)

/**
 * Converts a Swing [KeyEvent] into a platform-neutral [KeyGesture].
 */
fun KeyEvent.toKeyGesture(): KeyGesture {
    val logicalKey = when (keyCode) {
        KeyEvent.VK_UP -> LogicalKey.Up

        KeyEvent.VK_DOWN -> LogicalKey.Down

        KeyEvent.VK_TAB -> LogicalKey.Tab

        KeyEvent.VK_ENTER -> LogicalKey.Enter

        KeyEvent.VK_ESCAPE -> LogicalKey.Escape

        KeyEvent.VK_DELETE -> LogicalKey.Delete

        KeyEvent.VK_BACK_SPACE -> LogicalKey.Backspace

        KeyEvent.VK_SPACE -> LogicalKey.Space

        in KeyEvent.VK_A..KeyEvent.VK_Z -> LogicalKey.Character(keyCode.toChar())

        in KeyEvent.VK_0..KeyEvent.VK_9 -> LogicalKey.Character(keyCode.toChar())

        in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9 ->
            LogicalKey.Character(('0'.code + (keyCode - KeyEvent.VK_NUMPAD0)).toChar())

        else -> LogicalKey.Other
    }
    return KeyGesture(
        key = logicalKey,
        ctrl = isControlDown,
        shift = isShiftDown,
        alt = isAltDown,
        altGr = isAltGraphDown,
        source = KeySource.OVERLAY_SWING,
    )
}
