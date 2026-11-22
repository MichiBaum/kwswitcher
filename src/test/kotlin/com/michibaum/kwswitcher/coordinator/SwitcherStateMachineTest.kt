package com.michibaum.kwswitcher.coordinator

import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwitcherStateMachineTest {

    private val windows = listOf(
        WindowInfo(WindowHandle(1L), "Current Window", "app1", "C:\\app1.exe", 0, pid = 100, monitorId = "Mon 1"),
        WindowInfo(WindowHandle(2L), "Previous Window", "app2", "C:\\app2.exe", 1, pid = 200, monitorId = "Mon 2"),
        WindowInfo(WindowHandle(3L), "Third Window", "app3", "C:\\app3.exe", 2, pid = 200, monitorId = "Mon 1"),
    )

    @Test
    fun `Alt+Tab from HIDDEN enters QUICK_SWITCH with second window selected`() {
        val activated = mutableListOf<WindowInfo>()
        val sm = SwitcherStateMachine(
            enumerateWindows = { windows },
            onActivateWindow = { activated.add(it) },
        )

        val state = sm.onAltTabPressed(reverse = false)
        assertEquals(SwitcherMode.QUICK_SWITCH, state.mode)
        assertEquals(1, state.selectedIndex)
        assertEquals(windows[1], state.selectedWindow)
        assertTrue(state.multiMonitorDetected)

        val releaseResult = sm.onAltReleased()
        val activateResult = assertIs<SwitcherActionResult.ActivateWindow>(releaseResult)
        assertEquals(windows[1], activateResult.target)
        assertEquals(listOf(windows[1]), activated)
        assertEquals(SwitcherMode.HIDDEN, sm.state.value.mode)
    }

    @Test
    fun `repeated Alt+Tab cycles selection and reverse cycles backward`() {
        val sm = SwitcherStateMachine(enumerateWindows = { windows })

        sm.onAltTabPressed(reverse = false) // index 1
        val afterSecondTab = sm.onAltTabPressed(reverse = false)
        assertEquals(2, afterSecondTab.selectedIndex)

        val afterWrap = sm.onAltTabPressed(reverse = false)
        assertEquals(0, afterWrap.selectedIndex)

        val afterReverse = sm.onAltTabPressed(reverse = true)
        assertEquals(2, afterReverse.selectedIndex)
    }

    @Test
    fun `typing query transitions to INTERACTIVE_SEARCH and ignores Alt release`() {
        val activated = mutableListOf<WindowInfo>()
        val sm = SwitcherStateMachine(
            enumerateWindows = { windows },
            onActivateWindow = { activated.add(it) },
        )

        sm.onAltTabPressed(reverse = false)
        val searchState = sm.onQueryChanged("Third")
        assertEquals(SwitcherMode.INTERACTIVE_SEARCH, searchState.mode)
        assertEquals(1, searchState.filteredWindows.size)
        assertEquals(windows[2], searchState.selectedWindow)

        // Releasing Alt in INTERACTIVE_SEARCH must NOT close or activate
        val altReleaseResult = sm.onAltReleased()
        assertEquals(SwitcherActionResult.None, altReleaseResult)
        assertEquals(SwitcherMode.INTERACTIVE_SEARCH, sm.state.value.mode)
        assertEquals(0, activated.size)

        // Pressing Enter activates the filtered selection
        val confirmResult = sm.onConfirmSelection()
        val activateResult = assertIs<SwitcherActionResult.ActivateWindow>(confirmResult)
        assertEquals(windows[2], activateResult.target)
        assertEquals(SwitcherMode.HIDDEN, sm.state.value.mode)
    }

    @Test
    fun `in-switcher close minimize and two-step process kill work without dismissing overlay`() {
        val closed = mutableListOf<WindowInfo>()
        val minimized = mutableListOf<WindowInfo>()
        val terminated = mutableListOf<WindowInfo>()
        val sm = SwitcherStateMachine(
            enumerateWindows = { windows },
            onCloseWindow = { closed.add(it) },
            onMinimizeWindow = { minimized.add(it) },
            onTerminateProcess = { terminated.add(it) },
        )

        sm.onAltTabPressed(reverse = false) // selects index 1 (windows[1])

        // Minimize in-place via Ctrl+M
        val minResult = sm.onMinimizeSelectedWindow()
        assertIs<SwitcherActionResult.MinimizeWindow>(minResult)
        assertEquals(listOf(windows[1]), minimized)
        assertEquals(SwitcherMode.INTERACTIVE_SEARCH, sm.state.value.mode)
        assertTrue(sm.state.value.selectedWindow!!.isMinimized) // verified non-null after selection

        // First Ctrl+Shift+W arms kill confirmation; moving selection disarms it
        val armResult = sm.onKillSelectedWindowProcess()
        assertIs<SwitcherActionResult.KillArmed>(armResult)
        assertEquals(windows[1].hwnd, sm.state.value.armedKillHwnd)
        assertTrue(terminated.isEmpty())

        sm.onMoveSelection(delta = 1) // moves to index 2 (windows[2], also pid 200)
        assertNull(sm.state.value.armedKillHwnd)

        // Graceful close via Ctrl+W removes windows[2] and clamps selection to index 1
        val closeResult = sm.onCloseSelectedWindow()
        assertIs<SwitcherActionResult.CloseWindow>(closeResult)
        assertEquals(listOf(windows[2]), closed)
        assertEquals(2, sm.state.value.filteredWindows.size)
        assertEquals(1, sm.state.value.selectedIndex)

        // Two consecutive Ctrl+Shift+W presses on windows[1] terminate pid 200
        sm.onKillSelectedWindowProcess()
        val killResult = sm.onKillSelectedWindowProcess()
        assertIs<SwitcherActionResult.TerminateProcess>(killResult)
        assertEquals(1, terminated.size)
        assertEquals(listOf(windows[0]), sm.state.value.filteredWindows)
        assertFalse(sm.state.value.selectedWindow!!.isMinimized) // windows[0] is not minimized
    }
}
