package com.michibaum.kwswitcher.core.coordinator

import com.michibaum.kwswitcher.core.domain.WindowHandle
import com.michibaum.kwswitcher.core.domain.WindowInfo
import com.michibaum.kwswitcher.core.port.NativeResult
import com.michibaum.kwswitcher.core.port.WindowCatalogPort
import com.michibaum.kwswitcher.core.port.WindowControlPort
import com.michibaum.kwswitcher.core.sandbox.NoOpHotkeyHookPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SwitcherCoordinatorTest {

    private val windows = listOf(
        WindowInfo(WindowHandle(1L), "Current Window", "app1", "C:\\app1.exe", 0, pid = 100, monitorId = "Mon 1"),
        WindowInfo(WindowHandle(2L), "Previous Window", "app2", "C:\\app2.exe", 1, pid = 200, monitorId = "Mon 2"),
        WindowInfo(WindowHandle(3L), "Third Window", "app3", "C:\\app3.exe", 2, pid = 200, monitorId = "Mon 1"),
    )

    private class RecordingControlPort : WindowControlPort {
        val activated = mutableListOf<WindowHandle>()
        val closed = mutableListOf<WindowHandle>()
        val minimized = mutableListOf<WindowHandle>()
        val terminatedPids = mutableListOf<Int>()

        override fun activate(handle: WindowHandle): NativeResult {
            activated.add(handle)
            return NativeResult.Ok
        }

        override fun activateOverlay(overlayTitle: String): NativeResult = NativeResult.Ok

        override fun closeGracefully(handle: WindowHandle): NativeResult {
            closed.add(handle)
            return NativeResult.Ok
        }

        override fun minimize(handle: WindowHandle): NativeResult {
            minimized.add(handle)
            return NativeResult.Ok
        }

        override fun terminateProcess(pid: Int): NativeResult {
            terminatedPids.add(pid)
            return NativeResult.Ok
        }
    }

    private fun TestScope.createCoordinator(
        catalogWindows: () -> List<WindowInfo> = { windows },
        workArea: () -> Rectangle? = { Rectangle(0, 0, 1920, 1040) },
        controlPort: WindowControlPort = RecordingControlPort(),
        onAcknowledge: () -> Unit = {},
        initialPinned: Boolean = false,
    ): SwitcherCoordinator {
        val catalog = object : WindowCatalogPort {
            override fun enumerateOpenWindows(): List<WindowInfo> = catalogWindows()
            override fun getActiveMonitorWorkArea(): Rectangle? = workArea()
        }
        return SwitcherCoordinator(
            windowCatalog = catalog,
            windowControl = controlPort,
            onHotkeyAcknowledged = onAcknowledge,
            initialPinned = initialPinned,
            parentScope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
    }

    @Test
    fun `TriggerHotkey from HIDDEN enters QUICK_SWITCH with second window selected and activates on ReleaseModifier`() = runTest {
        val control = RecordingControlPort()
        val acks = AtomicInteger(0)
        val coordinator = createCoordinator(
            controlPort = control,
            onAcknowledge = { acks.incrementAndGet() },
        )

        // Dispatch rapid TriggerHotkey + ReleaseModifier back-to-back without races
        coordinator.dispatch(SwitcherIntent.TriggerHotkey(reverse = false))
        coordinator.dispatch(SwitcherIntent.ReleaseModifier)
        testScheduler.advanceUntilIdle()

        assertEquals(1, acks.get())
        assertEquals(listOf(windows[1].hwnd), control.activated)
        assertEquals(SwitcherMode.HIDDEN, coordinator.state.value.mode)
    }

    @Test
    fun `session open precomputes active monitor work area once and preserves it across cycling and search`() = runTest {
        var monitorQueries = 0
        val expectedBounds = Rectangle(0, 0, 1920, 1040)
        val coordinator = createCoordinator(
            workArea = {
                monitorQueries++
                expectedBounds
            },
        )

        val initial = coordinator.submitAndAwait(SwitcherIntent.TriggerHotkey(reverse = false))
        assertEquals(expectedBounds, initial.activeMonitorWorkArea)
        assertEquals(1, monitorQueries)

        val cycled = coordinator.submitAndAwait(SwitcherIntent.TriggerHotkey(reverse = false))
        assertEquals(2, cycled.selectedIndex)
        assertEquals(expectedBounds, cycled.activeMonitorWorkArea)

        val searched = coordinator.submitAndAwait(SwitcherIntent.SetQuery("app"))
        assertEquals(expectedBounds, searched.activeMonitorWorkArea)
        assertEquals(1, monitorQueries)

        val dismissed = coordinator.submitAndAwait(SwitcherIntent.Dismiss())
        assertNull(dismissed.activeMonitorWorkArea)
    }

    @Test
    fun `typing query transitions to INTERACTIVE_SEARCH and ignores ReleaseModifier`() = runTest {
        val control = RecordingControlPort()
        val coordinator = createCoordinator(controlPort = control)

        coordinator.submitAndAwait(SwitcherIntent.TriggerHotkey(reverse = false))
        val searchState = coordinator.submitAndAwait(SwitcherIntent.SetQuery("Third"))
        assertEquals(SwitcherMode.INTERACTIVE_SEARCH, searchState.mode)
        assertEquals(listOf(windows[2]), searchState.filteredWindows)

        val afterRelease = coordinator.submitAndAwait(SwitcherIntent.ReleaseModifier)
        assertEquals(SwitcherMode.INTERACTIVE_SEARCH, afterRelease.mode)
        assertTrue(control.activated.isEmpty())

        val afterConfirm = coordinator.submitAndAwait(SwitcherIntent.ConfirmSelection)
        assertEquals(SwitcherMode.HIDDEN, afterConfirm.mode)
        assertEquals(listOf(windows[2].hwnd), control.activated)
    }

    @Test
    fun `in-switcher close minimize and two-step process kill execute sequentially without dismissing overlay`() = runTest {
        val control = RecordingControlPort()
        val coordinator = createCoordinator(controlPort = control)

        coordinator.submitAndAwait(SwitcherIntent.TriggerHotkey(reverse = false)) // index 1 (windows[1])

        val afterMinimize = coordinator.submitAndAwait(SwitcherIntent.MinimizeSelected)
        assertEquals(listOf(windows[1].hwnd), control.minimized)
        assertEquals(SwitcherMode.INTERACTIVE_SEARCH, afterMinimize.mode)
        assertTrue(requireNotNull(afterMinimize.selectedWindow).isMinimized)

        // First KillSelectedProcess arms confirmation; MoveSelection disarms it
        val armed = coordinator.submitAndAwait(SwitcherIntent.KillSelectedProcess)
        assertEquals(windows[1].hwnd, armed.armedKillHwnd)
        assertTrue(control.terminatedPids.isEmpty())

        val moved = coordinator.submitAndAwait(SwitcherIntent.MoveSelection(delta = 1, lockInteractive = true))
        assertNull(moved.armedKillHwnd)

        // CloseSelected removes windows[2] and clamps selection to index 1
        val afterClose = coordinator.submitAndAwait(SwitcherIntent.CloseSelected)
        assertEquals(listOf(windows[2].hwnd), control.closed)
        assertEquals(2, afterClose.filteredWindows.size)
        assertEquals(1, afterClose.selectedIndex)

        // Two consecutive KillSelectedProcess intents terminate pid 200
        coordinator.submitAndAwait(SwitcherIntent.KillSelectedProcess)
        val afterKill = coordinator.submitAndAwait(SwitcherIntent.KillSelectedProcess)
        assertEquals(listOf(200), control.terminatedPids)
        assertEquals(listOf(windows[0]), afterKill.filteredWindows)
        assertFalse(requireNotNull(afterKill.selectedWindow).isMinimized)
    }

    @Test
    fun `pinned overlay ignores focus-loss Dismiss while honoring explicit Esc Dismiss`() = runTest {
        val coordinator = createCoordinator(initialPinned = true)
        val opened = coordinator.submitAndAwait(SwitcherIntent.ShowInteractive)
        assertTrue(opened.isVisible)
        assertTrue(opened.pinned)

        val afterFocusLoss = coordinator.submitAndAwait(SwitcherIntent.Dismiss(fromFocusLoss = true))
        assertTrue(afterFocusLoss.isVisible)

        val afterEsc = coordinator.submitAndAwait(SwitcherIntent.Dismiss(fromFocusLoss = false))
        assertFalse(afterEsc.isVisible)
        assertTrue(afterEsc.pinned)
    }

    @Test
    fun `TriggerHotkey always acknowledges hook even when enumerateOpenWindows throws`() = runTest {
        val hook = NoOpHotkeyHookPort()
        val coordinator = createCoordinator(
            catalogWindows = { error("Simulated Win32 enumeration failure") },
        )
        coordinator.bindHook(hook)

        hook.emit(SwitcherIntent.TriggerHotkey(reverse = false))
        testScheduler.advanceUntilIdle()

        assertEquals(1, hook.acknowledgeCount.get())
        assertEquals(SwitcherMode.QUICK_SWITCH, coordinator.state.value.mode)
        assertTrue(coordinator.state.value.allWindows.isEmpty())
    }
}
