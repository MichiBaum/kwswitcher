package com.michibaum.kwswitcher.core.input

import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import java.awt.event.KeyEvent

/**
 * Single source of truth translating platform-neutral [KeyGesture]s from both the Swing overlay
 * and the global Win32 keyboard hook into [SwitcherIntent]s (`DX-3`).
 */
object KeyCommandMapper {

    /**
     * Resolves a normalized [gesture] while the switcher session is active (or pending open).
     * Returns `null` when the keystroke should be handled by standard text input or passed through.
     */
    fun mapActiveSessionKey(
        gesture: KeyGesture,
        isQueryEmpty: Boolean,
        isSpaceTriggerKey: Boolean = false,
    ): SwitcherIntent? {
        if (gesture.altGr) {
            return null
        }

        val commandIntent = resolveCommandIntent(gesture = gesture, isQueryEmpty = isQueryEmpty)
        if (commandIntent != null) {
            return commandIntent
        }

        return if (gesture.source == KeySource.GLOBAL_HOOK) {
            resolveHookAltTyping(
                gesture = gesture,
                isSpaceTriggerKey = isSpaceTriggerKey,
            )
        } else {
            null
        }
    }

    /**
     * Convenience entry point for Swing `searchField` `keyPressed` events.
     */
    fun mapSwingKeyEvent(event: KeyEvent, isQueryEmpty: Boolean): SwitcherIntent? = mapActiveSessionKey(
        gesture = event.toKeyGesture(),
        isQueryEmpty = isQueryEmpty,
    )

    private fun resolveCommandIntent(gesture: KeyGesture, isQueryEmpty: Boolean): SwitcherIntent? =
        when (val key = gesture.key) {
            LogicalKey.Down -> SwitcherIntent.MoveSelection(delta = 1, lockInteractive = true)

            LogicalKey.Up -> SwitcherIntent.MoveSelection(delta = -1, lockInteractive = true)

            LogicalKey.Tab -> SwitcherIntent.MoveSelection(
                delta = if (gesture.shift) -1 else 1,
                lockInteractive = false,
            )

            LogicalKey.Enter -> SwitcherIntent.ConfirmSelection

            LogicalKey.Escape -> SwitcherIntent.Dismiss(fromFocusLoss = false)

            LogicalKey.Delete -> if (gesture.ctrl || isQueryEmpty) SwitcherIntent.CloseSelected else null

            is LogicalKey.Character -> resolveCtrlCharShortcut(
                upperChar = key.upperChar,
                ctrl = gesture.ctrl,
                shift = gesture.shift,
            )

            LogicalKey.Backspace,
            LogicalKey.Space,
            LogicalKey.Other,
            -> null
        }

    private fun resolveCtrlCharShortcut(upperChar: Char, ctrl: Boolean, shift: Boolean): SwitcherIntent? {
        if (!ctrl) {
            return null
        }
        return when (upperChar) {
            'N' -> SwitcherIntent.MoveSelection(delta = 1, lockInteractive = true)
            'P' -> SwitcherIntent.MoveSelection(delta = -1, lockInteractive = true)
            'M' -> SwitcherIntent.MinimizeSelected
            'W' -> if (shift) SwitcherIntent.KillSelectedProcess else SwitcherIntent.CloseSelected
            else -> null
        }
    }

    private fun resolveHookAltTyping(gesture: KeyGesture, isSpaceTriggerKey: Boolean): SwitcherIntent? {
        if (!gesture.alt || gesture.ctrl) {
            return null
        }
        return when (val key = gesture.key) {
            LogicalKey.Backspace -> SwitcherIntent.BackspaceQuery

            LogicalKey.Space -> if (!isSpaceTriggerKey) SwitcherIntent.AppendQueryChar(' ') else null

            is LogicalKey.Character -> {
                val ch = key.upperChar
                when {
                    ch in 'A'..'Z' -> SwitcherIntent.AppendQueryChar(
                        if (gesture.shift) ch else ch.lowercaseChar(),
                    )

                    ch in '0'..'9' && !gesture.shift -> SwitcherIntent.AppendQueryChar(ch)

                    else -> null
                }
            }

            else -> null
        }
    }
}
