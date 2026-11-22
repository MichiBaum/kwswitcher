package com.michibaum.kwswitcher.coordinator

import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SwitcherStateMachineTest {

    private val windows = listOf(
        WindowInfo(WindowHandle(1L), "Current Window", "app1", "C:\\app1.exe", 0),
        WindowInfo(WindowHandle(2L), "Previous Window", "app2", "C:\\app2.exe", 1),
        WindowInfo(WindowHandle(3L), "Third Window", "app3", "C:\\app3.exe", 2),
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
}
