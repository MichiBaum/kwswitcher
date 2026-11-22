package com.michibaum.kwswitcher.coordinator

import com.michibaum.kwswitcher.domain.WindowFilter
import com.michibaum.kwswitcher.domain.WindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface SwitcherActionResult {
    data object None : SwitcherActionResult
    data class ActivateWindow(val target: WindowInfo) : SwitcherActionResult
    data object Dismissed : SwitcherActionResult
}

class SwitcherStateMachine(
    private val enumerateWindows: () -> List<WindowInfo>,
    private val onActivateWindow: (WindowInfo) -> Unit = {},
) {
    private val _state = MutableStateFlow(SwitcherUiState())
    val state: StateFlow<SwitcherUiState> = _state.asStateFlow()

    fun onAltTabPressed(reverse: Boolean = false): SwitcherUiState {
        var updatedState = _state.value
        _state.update { current ->
            val next = when (current.mode) {
                SwitcherMode.HIDDEN -> {
                    val windows = enumerateWindows()
                    val initialIndex = when {
                        windows.isEmpty() -> 0
                        reverse -> windows.lastIndex
                        windows.size > 1 -> 1
                        else -> 0
                    }
                    SwitcherUiState(
                        mode = SwitcherMode.QUICK_SWITCH,
                        query = "",
                        allWindows = windows,
                        filteredWindows = windows,
                        selectedIndex = initialIndex,
                    )
                }

                SwitcherMode.QUICK_SWITCH,
                SwitcherMode.INTERACTIVE_SEARCH,
                -> {
                    val nextIndex = cycleIndex(
                        currentIndex = current.selectedIndex,
                        count = current.filteredWindows.size,
                        delta = if (reverse) -1 else 1,
                    )
                    current.copy(selectedIndex = nextIndex)
                }
            }
            updatedState = next
            next
        }
        return updatedState
    }

    fun onAltReleased(): SwitcherActionResult {
        val current = _state.value
        if (current.mode != SwitcherMode.QUICK_SWITCH) {
            return SwitcherActionResult.None
        }
        val target = current.selectedWindow
        _state.value = SwitcherUiState(mode = SwitcherMode.HIDDEN)
        return if (target != null) {
            onActivateWindow(target)
            SwitcherActionResult.ActivateWindow(target)
        } else {
            SwitcherActionResult.Dismissed
        }
    }

    fun onQueryChanged(newQuery: String): SwitcherUiState {
        var updatedState = _state.value
        _state.update { current ->
            if (current.mode == SwitcherMode.HIDDEN) {
                updatedState = current
                return@update current
            }
            val filtered = WindowFilter.filter(windows = current.allWindows, query = newQuery)
            val next = current.copy(
                mode = SwitcherMode.INTERACTIVE_SEARCH,
                query = newQuery,
                filteredWindows = filtered,
                selectedIndex = 0,
            )
            updatedState = next
            next
        }
        return updatedState
    }

    fun onMoveSelection(delta: Int, lockInteractive: Boolean = true): SwitcherUiState {
        var updatedState = _state.value
        _state.update { current ->
            if (current.mode == SwitcherMode.HIDDEN) {
                updatedState = current
                return@update current
            }
            val nextIndex = cycleIndex(
                currentIndex = current.selectedIndex,
                count = current.filteredWindows.size,
                delta = delta,
            )
            val nextMode = if (lockInteractive) SwitcherMode.INTERACTIVE_SEARCH else current.mode
            val next = current.copy(
                mode = nextMode,
                selectedIndex = nextIndex,
            )
            updatedState = next
            next
        }
        return updatedState
    }

    fun onConfirmSelection(): SwitcherActionResult {
        val current = _state.value
        if (current.mode == SwitcherMode.HIDDEN) {
            return SwitcherActionResult.None
        }
        val target = current.selectedWindow
        _state.value = SwitcherUiState(mode = SwitcherMode.HIDDEN)
        return if (target != null) {
            onActivateWindow(target)
            SwitcherActionResult.ActivateWindow(target)
        } else {
            SwitcherActionResult.Dismissed
        }
    }

    fun onDismiss(): SwitcherActionResult {
        val current = _state.value
        if (current.mode == SwitcherMode.HIDDEN) {
            return SwitcherActionResult.None
        }
        _state.value = SwitcherUiState(mode = SwitcherMode.HIDDEN)
        return SwitcherActionResult.Dismissed
    }

    private fun cycleIndex(
        currentIndex: Int,
        count: Int,
        delta: Int,
    ): Int {
        if (count <= 0) return 0
        return ((currentIndex + delta) % count + count) % count
    }
}
