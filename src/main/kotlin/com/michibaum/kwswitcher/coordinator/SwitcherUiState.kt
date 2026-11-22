package com.michibaum.kwswitcher.coordinator

import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import com.michibaum.kwswitcher.domain.WindowMatch

/**
 * Immutable snapshot of the switcher's current UI state (`FR-2`, `FR-5`, `FR-6`, `FR-8`).
 */
data class SwitcherUiState(
    val mode: SwitcherMode = SwitcherMode.HIDDEN,
    val query: String = "",
    val allWindows: List<WindowInfo> = emptyList(),
    val matches: List<WindowMatch> = emptyList(),
    val selectedIndex: Int = 0,
    val armedKillHwnd: WindowHandle? = null,
    val multiMonitorDetected: Boolean = false,
) {
    val isVisible: Boolean
        get() = mode != SwitcherMode.HIDDEN

    val filteredWindows: List<WindowInfo>
        get() = matches.map { it.window }

    val selectedMatch: WindowMatch?
        get() = matches.getOrNull(selectedIndex)

    val selectedWindow: WindowInfo?
        get() = selectedMatch?.window
}
