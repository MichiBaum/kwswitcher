package com.michibaum.kwswitcher.coordinator

import com.michibaum.kwswitcher.domain.WindowInfo

data class SwitcherUiState(
    val mode: SwitcherMode = SwitcherMode.HIDDEN,
    val query: String = "",
    val allWindows: List<WindowInfo> = emptyList(),
    val filteredWindows: List<WindowInfo> = emptyList(),
    val selectedIndex: Int = 0,
) {
    val isVisible: Boolean
        get() = mode != SwitcherMode.HIDDEN

    val selectedWindow: WindowInfo?
        get() = filteredWindows.getOrNull(selectedIndex)
}
