package com.michibaum.kwswitcher.coordinator

import com.michibaum.kwswitcher.domain.WindowFilter
import com.michibaum.kwswitcher.domain.WindowInfo
import com.michibaum.kwswitcher.domain.WindowMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.awt.Rectangle

/**
 * Outcome returned by state-machine actions that trigger native window side effects.
 */
sealed interface SwitcherActionResult {
    data object None : SwitcherActionResult
    data class ActivateWindow(val target: WindowInfo) : SwitcherActionResult
    data class CloseWindow(val target: WindowInfo) : SwitcherActionResult
    data class MinimizeWindow(val target: WindowInfo) : SwitcherActionResult
    data class KillArmed(val target: WindowInfo) : SwitcherActionResult
    data class TerminateProcess(val target: WindowInfo) : SwitcherActionResult
    data object Dismissed : SwitcherActionResult
}

/**
 * Coordinates the `HIDDEN` <-> `QUICK_SWITCH` <-> `INTERACTIVE_SEARCH` state machine
 * and dispatches window activation, closing, minimizing, and termination actions (`FR-2`, `FR-6`).
 */
class SwitcherStateMachine(
    private val enumerateWindows: () -> List<WindowInfo>,
    private val queryActiveMonitorWorkArea: () -> Rectangle? = { null },
    private val onActivateWindow: (WindowInfo) -> Unit = {},
    private val onCloseWindow: (WindowInfo) -> Unit = {},
    private val onMinimizeWindow: (WindowInfo) -> Unit = {},
    private val onTerminateProcess: (WindowInfo) -> Unit = {},
) {
    private val _state = MutableStateFlow(SwitcherUiState())
    val state: StateFlow<SwitcherUiState> = _state.asStateFlow()

    /**
     * Handles the primary hotkey press (`Alt + Tab` or `Alt + Shift + Tab`).
     */
    fun onAltTabPressed(reverse: Boolean = false): SwitcherUiState {
        var updatedState = _state.value
        _state.update { current ->
            val next = when (current.mode) {
                SwitcherMode.HIDDEN -> {
                    val windows = enumerateWindows()
                    val workArea = queryActiveMonitorWorkArea()
                    val matches = windows.map { WindowMatch(window = it, score = 0) }
                    val initialIndex = when {
                        matches.isEmpty() -> 0
                        reverse -> matches.lastIndex
                        matches.size > 1 -> 1
                        else -> 0
                    }
                    SwitcherUiState(
                        mode = SwitcherMode.QUICK_SWITCH,
                        query = "",
                        allWindows = windows,
                        matches = matches,
                        selectedIndex = initialIndex,
                        armedKillHwnd = null,
                        multiMonitorDetected = hasMultipleMonitors(windows),
                        activeMonitorWorkArea = workArea,
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
            updatedState = next
            next
        }
        return updatedState
    }

    /**
     * Opens the switcher directly in [SwitcherMode.INTERACTIVE_SEARCH] mode (e.g., from the System Tray).
     */
    fun onShowInteractive(): SwitcherUiState {
        var updatedState = _state.value
        _state.update {
            val windows = enumerateWindows()
            val workArea = queryActiveMonitorWorkArea()
            val matches = windows.map { WindowMatch(window = it, score = 0) }
            val next = SwitcherUiState(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                query = "",
                allWindows = windows,
                matches = matches,
                selectedIndex = 0,
                armedKillHwnd = null,
                multiMonitorDetected = hasMultipleMonitors(windows),
                activeMonitorWorkArea = workArea,
            )
            updatedState = next
            next
        }
        return updatedState
    }

    /**
     * Handles release of the hotkey modifier (`Alt`). Activates the selected window only in `QUICK_SWITCH` mode.
     */
    fun onAltReleased(): SwitcherActionResult {
        while (true) {
            val current = _state.value
            if (current.mode != SwitcherMode.QUICK_SWITCH) {
                return SwitcherActionResult.None
            }
            val target = current.selectedWindow
            if (_state.compareAndSet(current, SwitcherUiState(mode = SwitcherMode.HIDDEN))) {
                return if (target != null) {
                    onActivateWindow(target)
                    SwitcherActionResult.ActivateWindow(target)
                } else {
                    SwitcherActionResult.Dismissed
                }
            }
        }
    }

    /**
     * Locks the session in `INTERACTIVE_SEARCH` mode and filters/ranks windows against [newQuery].
     */
    fun onQueryChanged(newQuery: String): SwitcherUiState =
        updateSearchQuery { newQuery }

    /**
     * Appends [char] to the search query, locking `INTERACTIVE_SEARCH` mode and re-ranking matches.
     */
    fun onAppendQueryChar(char: Char): SwitcherUiState =
        updateSearchQuery { currentQuery -> currentQuery + char }

    /**
     * Removes the trailing character from the search query (if any) and locks `INTERACTIVE_SEARCH` mode.
     */
    fun onBackspaceQuery(): SwitcherUiState =
        updateSearchQuery { currentQuery ->
            if (currentQuery.isNotEmpty()) currentQuery.dropLast(1) else ""
        }

    private fun updateSearchQuery(transform: (String) -> String): SwitcherUiState {
        var updatedState = _state.value
        _state.update { current ->
            if (current.mode == SwitcherMode.HIDDEN) {
                updatedState = current
                return@update current
            }
            val nextQuery = transform(current.query)
            val matches = WindowFilter.match(windows = current.allWindows, query = nextQuery)
            val next = current.copy(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                query = nextQuery,
                matches = matches,
                selectedIndex = 0,
                armedKillHwnd = null,
            )
            updatedState = next
            next
        }
        return updatedState
    }

    /**
     * Moves the selection index by [delta] with wrap-around, clearing any armed kill confirmation.
     */
    fun onMoveSelection(delta: Int, lockInteractive: Boolean = true): SwitcherUiState {
        var updatedState = _state.value
        _state.update { current ->
            if (current.mode == SwitcherMode.HIDDEN) {
                updatedState = current
                return@update current
            }
            val nextIndex = cycleIndex(
                currentIndex = current.selectedIndex,
                count = current.matches.size,
                delta = delta,
            )
            val nextMode = if (lockInteractive) SwitcherMode.INTERACTIVE_SEARCH else current.mode
            val next = current.copy(
                mode = nextMode,
                selectedIndex = nextIndex,
                armedKillHwnd = null,
            )
            updatedState = next
            next
        }
        return updatedState
    }

    /**
     * Gracefully closes the currently selected window (`Ctrl + W` or `Delete`) without dismissing the overlay (`FR-6`).
     */
    fun onCloseSelectedWindow(): SwitcherActionResult {
        while (true) {
            val current = _state.value
            if (current.mode == SwitcherMode.HIDDEN) {
                return SwitcherActionResult.None
            }
            val target = current.selectedWindow ?: return SwitcherActionResult.None
            val remainingWindows = current.allWindows.filterNot { it.hwnd == target.hwnd }
            val remainingMatches = WindowFilter.match(windows = remainingWindows, query = current.query)
            val clampedIndex = clampIndex(current.selectedIndex, remainingMatches.size)
            val next = current.copy(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                allWindows = remainingWindows,
                matches = remainingMatches,
                selectedIndex = clampedIndex,
                armedKillHwnd = null,
            )
            if (_state.compareAndSet(current, next)) {
                onCloseWindow(target)
                return SwitcherActionResult.CloseWindow(target)
            }
        }
    }

    /**
     * Minimizes the currently selected window in-place (`Ctrl + M`) without dismissing the overlay (`FR-6`).
     */
    fun onMinimizeSelectedWindow(): SwitcherActionResult {
        while (true) {
            val current = _state.value
            if (current.mode == SwitcherMode.HIDDEN) {
                return SwitcherActionResult.None
            }
            val target = current.selectedWindow ?: return SwitcherActionResult.None
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
            val next = current.copy(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                allWindows = updatedAll,
                matches = updatedMatches,
                armedKillHwnd = null,
            )
            if (_state.compareAndSet(current, next)) {
                onMinimizeWindow(target)
                return SwitcherActionResult.MinimizeWindow(target)
            }
        }
    }

    /**
     * Two-step process termination (`Ctrl + Shift + W`): first press arms confirmation on the selected window;
     * second consecutive press terminates the owning process and removes its windows from the list (`FR-6`).
     */
    fun onKillSelectedWindowProcess(): SwitcherActionResult {
        while (true) {
            val current = _state.value
            if (current.mode == SwitcherMode.HIDDEN) {
                return SwitcherActionResult.None
            }
            val target = current.selectedWindow ?: return SwitcherActionResult.None

            if (current.armedKillHwnd != target.hwnd) {
                val armedState = current.copy(
                    mode = SwitcherMode.INTERACTIVE_SEARCH,
                    armedKillHwnd = target.hwnd,
                )
                if (_state.compareAndSet(current, armedState)) {
                    return SwitcherActionResult.KillArmed(target)
                }
                continue
            }

            val remainingWindows = current.allWindows.filterNot { win ->
                win.hwnd == target.hwnd || (target.pid > 0 && win.pid == target.pid)
            }
            val remainingMatches = WindowFilter.match(windows = remainingWindows, query = current.query)
            val clampedIndex = clampIndex(current.selectedIndex, remainingMatches.size)
            val next = current.copy(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                allWindows = remainingWindows,
                matches = remainingMatches,
                selectedIndex = clampedIndex,
                armedKillHwnd = null,
            )
            if (_state.compareAndSet(current, next)) {
                onTerminateProcess(target)
                return SwitcherActionResult.TerminateProcess(target)
            }
        }
    }

    /**
     * Activates the currently selected window (`Enter` or mouse click) and hides the overlay.
     */
    fun onConfirmSelection(): SwitcherActionResult {
        while (true) {
            val current = _state.value
            if (current.mode == SwitcherMode.HIDDEN) {
                return SwitcherActionResult.None
            }
            val target = current.selectedWindow
            if (_state.compareAndSet(current, SwitcherUiState(mode = SwitcherMode.HIDDEN))) {
                return if (target != null) {
                    onActivateWindow(target)
                    SwitcherActionResult.ActivateWindow(target)
                } else {
                    SwitcherActionResult.Dismissed
                }
            }
        }
    }

    /**
     * Cancels the switcher session (`Esc` or focus loss) without switching windows.
     */
    fun onDismiss(): SwitcherActionResult {
        while (true) {
            val current = _state.value
            if (current.mode == SwitcherMode.HIDDEN) {
                return SwitcherActionResult.None
            }
            if (_state.compareAndSet(current, SwitcherUiState(mode = SwitcherMode.HIDDEN))) {
                return SwitcherActionResult.Dismissed
            }
        }
    }

    private fun cycleIndex(
        currentIndex: Int,
        count: Int,
        delta: Int,
    ): Int {
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
