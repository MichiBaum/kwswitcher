package com.michibaum.kwswitcher.core.coordinator

import com.michibaum.kwswitcher.core.domain.WindowFilter
import com.michibaum.kwswitcher.core.domain.WindowInfo
import com.michibaum.kwswitcher.core.domain.WindowMatch
import java.awt.Rectangle

/**
 * Snapshot read once when opening a session from [SwitcherMode.HIDDEN].
 */
data class WindowDesktopSnapshot(val windows: List<WindowInfo>, val activeMonitorWorkArea: Rectangle?)

/**
 * Pure state-transition result pairing the next [SwitcherUiState] with ordered [SwitcherEffect]s.
 */
data class SwitcherReduction(val state: SwitcherUiState, val effects: List<SwitcherEffect> = emptyList())

/**
 * Pure unidirectional state reducer for the switcher (`DX-3`).
 * Contains zero threading, locks, or native I/O calls.
 */
object SwitcherReducer {

    fun reduce(
        current: SwitcherUiState,
        intent: SwitcherIntent,
        snapshotProvider: () -> WindowDesktopSnapshot,
    ): SwitcherReduction = when (intent) {
        is SwitcherIntent.TriggerHotkey -> reduceTriggerHotkey(
            current = current,
            reverse = intent.reverse,
            snapshotProvider = snapshotProvider,
        )

        SwitcherIntent.ShowInteractive -> reduceShowInteractive(
            current = current,
            snapshotProvider = snapshotProvider,
        )

        SwitcherIntent.ReleaseModifier -> reduceReleaseModifier(current)

        is SwitcherIntent.SetQuery -> reduceQueryUpdate(current) { intent.query }

        is SwitcherIntent.AppendQueryChar -> reduceQueryUpdate(current) { query -> query + intent.char }

        SwitcherIntent.BackspaceQuery -> reduceQueryUpdate(current) { query ->
            if (query.isNotEmpty()) query.dropLast(1) else ""
        }

        is SwitcherIntent.MoveSelection -> reduceMoveSelection(
            current = current,
            delta = intent.delta,
            lockInteractive = intent.lockInteractive,
        )

        SwitcherIntent.CloseSelected -> reduceCloseSelected(current)

        SwitcherIntent.MinimizeSelected -> reduceMinimizeSelected(current)

        SwitcherIntent.KillSelectedProcess -> reduceKillSelectedProcess(current)

        SwitcherIntent.ConfirmSelection -> reduceConfirm(current)

        is SwitcherIntent.ConfirmAtIndex -> reduceConfirm(current = current, targetIndex = intent.index)

        is SwitcherIntent.Dismiss -> reduceDismiss(current = current, fromFocusLoss = intent.fromFocusLoss)

        is SwitcherIntent.SetPinned -> SwitcherReduction(state = current.copy(pinned = intent.pinned))
    }

    private fun reduceTriggerHotkey(
        current: SwitcherUiState,
        reverse: Boolean,
        snapshotProvider: () -> WindowDesktopSnapshot,
    ): SwitcherReduction {
        val nextState = when (current.mode) {
            SwitcherMode.HIDDEN -> {
                val snapshot = snapshotProvider()
                val matches = snapshot.windows.map { WindowMatch(window = it, score = 0) }
                val initialIndex = when {
                    matches.isEmpty() -> 0
                    reverse -> matches.lastIndex
                    matches.size > 1 -> 1
                    else -> 0
                }
                SwitcherUiState(
                    mode = SwitcherMode.QUICK_SWITCH,
                    query = "",
                    allWindows = snapshot.windows,
                    matches = matches,
                    selectedIndex = initialIndex,
                    armedKillHwnd = null,
                    multiMonitorDetected = hasMultipleMonitors(snapshot.windows),
                    activeMonitorWorkArea = snapshot.activeMonitorWorkArea,
                    pinned = current.pinned,
                )
            }

            SwitcherMode.QUICK_SWITCH,
            SwitcherMode.INTERACTIVE_SEARCH,
            -> {
                val nextIndex = cycleIndex(
                    currentIndex = current.selectedIndex,
                    count = current.matches.size,
                    delta = if (reverse) -1 else 1,
                )
                current.copy(
                    selectedIndex = nextIndex,
                    armedKillHwnd = null,
                )
            }
        }
        return SwitcherReduction(
            state = nextState,
            effects = listOf(SwitcherEffect.AcknowledgeHotkey),
        )
    }

    private fun reduceShowInteractive(
        current: SwitcherUiState,
        snapshotProvider: () -> WindowDesktopSnapshot,
    ): SwitcherReduction {
        val snapshot = snapshotProvider()
        val matches = snapshot.windows.map { WindowMatch(window = it, score = 0) }
        return SwitcherReduction(
            state = SwitcherUiState(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                query = "",
                allWindows = snapshot.windows,
                matches = matches,
                selectedIndex = 0,
                armedKillHwnd = null,
                multiMonitorDetected = hasMultipleMonitors(snapshot.windows),
                activeMonitorWorkArea = snapshot.activeMonitorWorkArea,
                pinned = current.pinned,
            ),
        )
    }

    private fun reduceReleaseModifier(current: SwitcherUiState): SwitcherReduction {
        if (current.mode != SwitcherMode.QUICK_SWITCH) {
            return SwitcherReduction(state = current)
        }
        val target = current.selectedWindow
        val hidden = hiddenState(pinned = current.pinned)
        val effects = if (target != null) listOf(SwitcherEffect.ActivateWindow(target)) else emptyList()
        return SwitcherReduction(state = hidden, effects = effects)
    }

    private fun reduceQueryUpdate(current: SwitcherUiState, transform: (String) -> String): SwitcherReduction {
        if (current.mode == SwitcherMode.HIDDEN) {
            return SwitcherReduction(state = current)
        }
        val nextQuery = transform(current.query)
        val matches = WindowFilter.match(windows = current.allWindows, query = nextQuery)
        return SwitcherReduction(
            state = current.copy(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                query = nextQuery,
                matches = matches,
                selectedIndex = 0,
                armedKillHwnd = null,
            ),
        )
    }

    private fun reduceMoveSelection(
        current: SwitcherUiState,
        delta: Int,
        lockInteractive: Boolean,
    ): SwitcherReduction {
        if (current.mode == SwitcherMode.HIDDEN) {
            return SwitcherReduction(state = current)
        }
        val nextIndex = cycleIndex(
            currentIndex = current.selectedIndex,
            count = current.matches.size,
            delta = delta,
        )
        val nextMode = if (lockInteractive) SwitcherMode.INTERACTIVE_SEARCH else current.mode
        return SwitcherReduction(
            state = current.copy(
                mode = nextMode,
                selectedIndex = nextIndex,
                armedKillHwnd = null,
            ),
        )
    }

    private fun reduceCloseSelected(current: SwitcherUiState): SwitcherReduction {
        if (current.mode == SwitcherMode.HIDDEN) {
            return SwitcherReduction(state = current)
        }
        val target = current.selectedWindow ?: return SwitcherReduction(state = current)
        val remainingWindows = current.allWindows.filterNot { it.hwnd == target.hwnd }
        val remainingMatches = WindowFilter.match(windows = remainingWindows, query = current.query)
        val clampedIndex = clampIndex(current.selectedIndex, remainingMatches.size)
        return SwitcherReduction(
            state = current.copy(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                allWindows = remainingWindows,
                matches = remainingMatches,
                selectedIndex = clampedIndex,
                armedKillHwnd = null,
            ),
            effects = listOf(SwitcherEffect.CloseWindow(target)),
        )
    }

    private fun reduceMinimizeSelected(current: SwitcherUiState): SwitcherReduction {
        if (current.mode == SwitcherMode.HIDDEN) {
            return SwitcherReduction(state = current)
        }
        val target = current.selectedWindow ?: return SwitcherReduction(state = current)
        val updatedAll = current.allWindows.map { win ->
            if (win.hwnd == target.hwnd) win.copy(isMinimized = true) else win
        }
        val updatedMatches = current.matches.map { match ->
            if (match.window.hwnd == target.hwnd) {
                match.copy(window = match.window.copy(isMinimized = true))
            } else {
                match
            }
        }
        return SwitcherReduction(
            state = current.copy(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                allWindows = updatedAll,
                matches = updatedMatches,
                armedKillHwnd = null,
            ),
            effects = listOf(SwitcherEffect.MinimizeWindow(target)),
        )
    }

    private fun reduceKillSelectedProcess(current: SwitcherUiState): SwitcherReduction {
        if (current.mode == SwitcherMode.HIDDEN) {
            return SwitcherReduction(state = current)
        }
        val target = current.selectedWindow ?: return SwitcherReduction(state = current)
        if (current.armedKillHwnd != target.hwnd) {
            return SwitcherReduction(
                state = current.copy(
                    mode = SwitcherMode.INTERACTIVE_SEARCH,
                    armedKillHwnd = target.hwnd,
                ),
            )
        }
        val remainingWindows = current.allWindows.filterNot { win ->
            win.hwnd == target.hwnd || (target.pid > 0 && win.pid == target.pid)
        }
        val remainingMatches = WindowFilter.match(windows = remainingWindows, query = current.query)
        val clampedIndex = clampIndex(current.selectedIndex, remainingMatches.size)
        return SwitcherReduction(
            state = current.copy(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                allWindows = remainingWindows,
                matches = remainingMatches,
                selectedIndex = clampedIndex,
                armedKillHwnd = null,
            ),
            effects = listOf(SwitcherEffect.TerminateProcess(target)),
        )
    }

    private fun reduceConfirm(current: SwitcherUiState, targetIndex: Int? = null): SwitcherReduction {
        if (current.mode == SwitcherMode.HIDDEN) {
            return SwitcherReduction(state = current)
        }
        val target = if (targetIndex != null) {
            current.matches.getOrNull(targetIndex)?.window ?: return SwitcherReduction(state = current)
        } else {
            current.selectedWindow
        }
        val hidden = hiddenState(pinned = current.pinned)
        val effects = if (target != null) listOf(SwitcherEffect.ActivateWindow(target)) else emptyList()
        return SwitcherReduction(state = hidden, effects = effects)
    }

    private fun reduceDismiss(current: SwitcherUiState, fromFocusLoss: Boolean): SwitcherReduction {
        if (current.mode == SwitcherMode.HIDDEN) {
            return SwitcherReduction(state = current)
        }
        if (fromFocusLoss && (current.pinned || current.mode != SwitcherMode.INTERACTIVE_SEARCH)) {
            return SwitcherReduction(state = current)
        }
        return SwitcherReduction(state = hiddenState(pinned = current.pinned))
    }

    private fun hiddenState(pinned: Boolean): SwitcherUiState =
        SwitcherUiState(mode = SwitcherMode.HIDDEN, pinned = pinned)

    private fun cycleIndex(currentIndex: Int, count: Int, delta: Int): Int {
        if (count <= 0) return 0
        return ((currentIndex + delta) % count + count) % count
    }

    private fun clampIndex(index: Int, count: Int): Int {
        if (count <= 0) return 0
        return index.coerceIn(0, count - 1)
    }

    private fun hasMultipleMonitors(windows: List<WindowInfo>): Boolean =
        windows.mapNotNull { it.monitorId.takeIf(String::isNotBlank) }.distinct().size > 1
}
