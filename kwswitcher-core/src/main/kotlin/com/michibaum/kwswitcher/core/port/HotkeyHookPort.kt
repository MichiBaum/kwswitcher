package com.michibaum.kwswitcher.core.port

import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import com.michibaum.kwswitcher.core.coordinator.SwitcherMode
import kotlinx.coroutines.flow.Flow

/**
 * Lock-free snapshot of the switcher overlay visibility and query state read on the global hook thread.
 */
data class OverlayHookSnapshot(val mode: SwitcherMode = SwitcherMode.HIDDEN, val isQueryEmpty: Boolean = true) {
    val isVisible: Boolean
        get() = mode != SwitcherMode.HIDDEN

    companion object {
        val HIDDEN = OverlayHookSnapshot(mode = SwitcherMode.HIDDEN, isQueryEmpty = true)
    }
}

/**
 * Platform port for global hotkey interception.
 */
interface HotkeyHookPort : AutoCloseable {
    val events: Flow<SwitcherIntent>
    fun start(): Boolean
    fun acknowledgeHotkeyHandled()
}
