package com.michibaum.kwswitcher.core.coordinator

import com.michibaum.kwswitcher.core.domain.WindowInfo

/**
 * User or platform intents processed sequentially by [SwitcherCoordinator].
 */
sealed interface SwitcherIntent {
    data class TriggerHotkey(val reverse: Boolean) : SwitcherIntent
    data object ReleaseModifier : SwitcherIntent
    data object ShowInteractive : SwitcherIntent
    data class SetQuery(val query: String) : SwitcherIntent
    data class AppendQueryChar(val char: Char) : SwitcherIntent
    data object BackspaceQuery : SwitcherIntent
    data class MoveSelection(val delta: Int, val lockInteractive: Boolean) : SwitcherIntent
    data object CloseSelected : SwitcherIntent
    data object MinimizeSelected : SwitcherIntent
    data object KillSelectedProcess : SwitcherIntent
    data object ConfirmSelection : SwitcherIntent
    data class Dismiss(val fromFocusLoss: Boolean = false) : SwitcherIntent
    data class SetPinned(val pinned: Boolean) : SwitcherIntent
}

/**
 * Side effects emitted by [SwitcherReducer] and executed against platform ports by [SwitcherCoordinator].
 */
sealed interface SwitcherEffect {
    data class ActivateWindow(val target: WindowInfo) : SwitcherEffect
    data class CloseWindow(val target: WindowInfo) : SwitcherEffect
    data class MinimizeWindow(val target: WindowInfo) : SwitcherEffect
    data class TerminateProcess(val target: WindowInfo) : SwitcherEffect
    data object AcknowledgeHotkey : SwitcherEffect
}
