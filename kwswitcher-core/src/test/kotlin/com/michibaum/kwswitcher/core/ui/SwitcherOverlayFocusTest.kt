package com.michibaum.kwswitcher.core.ui

import com.michibaum.kwswitcher.core.coordinator.SwitcherCoordinator
import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import com.michibaum.kwswitcher.core.coordinator.SwitcherMode
import com.michibaum.kwswitcher.core.domain.WindowHandle
import com.michibaum.kwswitcher.core.port.NativeResult
import com.michibaum.kwswitcher.core.port.WindowControlPort
import com.michibaum.kwswitcher.core.sandbox.SandboxWindowSystem
import com.michibaum.kwswitcher.core.sandbox.SyntheticWindowIconPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwitcherOverlayFocusTest {

    @Test
    fun `SwitcherOverlay requests foreground focus once per open syncs caret and honors pinOverlay on focus loss`() {
        assumeFalse(GraphicsEnvironment.isHeadless())

        val focusRequests = AtomicInteger(0)
        val sandbox = SandboxWindowSystem(workArea = Rectangle(-32000, -32000, 800, 600))

        SwitcherCoordinator(
            windowCatalog = sandbox,
            windowControl = sandbox,
        ).use { coordinator ->
            SyntheticWindowIconPort().use { iconPort ->
                var overlay: SwitcherOverlay? = null
                try {
                    SwingUtilities.invokeAndWait {
                        overlay = SwitcherOverlay(
                            coordinator = coordinator,
                            iconPort = iconPort,
                            onRequestForegroundFocus = { focusRequests.incrementAndGet() },
                        )
                    }
                    val activeOverlay = requireNotNull(overlay) { "Overlay must be initialized on EDT" }

                    // Hidden -> Visible fires onRequestForegroundFocus exactly once
                    val opened = runBlocking {
                        coordinator.submitAndAwait(SwitcherIntent.TriggerHotkey(reverse = false))
                    }
                    SwingUtilities.invokeAndWait { activeOverlay.render(opened) }
                    assertEquals(1, focusRequests.get())

                    // Subsequent selection moves and programmatic query updates do NOT re-fire focus request
                    val moved = runBlocking {
                        coordinator.submitAndAwait(SwitcherIntent.MoveSelection(delta = 1, lockInteractive = true))
                    }
                    SwingUtilities.invokeAndWait { activeOverlay.render(moved) }
                    assertEquals(1, focusRequests.get())

                    val afterFi = runBlocking {
                        coordinator.submitAndAwait(SwitcherIntent.AppendQueryChar('f'))
                        coordinator.submitAndAwait(SwitcherIntent.AppendQueryChar('i'))
                    }
                    SwingUtilities.invokeAndWait {
                        activeOverlay.render(afterFi)
                        activeOverlay.ensureSearchFocus()
                    }
                    assertEquals(1, focusRequests.get())
                    assertEquals("fi", activeOverlay.searchField.text)
                    assertEquals(2, activeOverlay.searchField.caretPosition)

                    // Standard unpinned INTERACTIVE_SEARCH dismisses on windowLostFocus
                    SwingUtilities.invokeAndWait { activeOverlay.handleWindowLostFocus() }
                    val afterUnpinnedFocusLoss = runBlocking {
                        coordinator.submitAndAwait(SwitcherIntent.SetPinned(pinned = true))
                    }
                    assertEquals(SwitcherMode.HIDDEN, afterUnpinnedFocusLoss.mode)

                    // Reopen with pinned = true -> focus loss keeps overlay open, Esc still dismisses
                    SwingUtilities.invokeAndWait { activeOverlay.render(afterUnpinnedFocusLoss) }
                    val reopenedPinned = runBlocking {
                        coordinator.submitAndAwait(SwitcherIntent.ShowInteractive)
                    }
                    SwingUtilities.invokeAndWait { activeOverlay.render(reopenedPinned) }
                    assertEquals(2, focusRequests.get())
                    assertTrue(activeOverlay.isDialogVisible)

                    SwingUtilities.invokeAndWait { activeOverlay.handleWindowLostFocus() }
                    val stillVisible = runBlocking {
                        coordinator.submitAndAwait(SwitcherIntent.MoveSelection(delta = 1, lockInteractive = true))
                    }
                    assertTrue(stillVisible.isVisible)

                    // Explicit Escape key still dismisses pinned overlay
                    SwingUtilities.invokeAndWait {
                        val escEvent = KeyEvent(
                            activeOverlay.searchField,
                            KeyEvent.KEY_PRESSED,
                            0L,
                            0,
                            KeyEvent.VK_ESCAPE,
                            KeyEvent.CHAR_UNDEFINED,
                        )
                        activeOverlay.handleOverlayKeyEvent(escEvent)
                    }
                    val afterEsc = runBlocking {
                        coordinator.submitAndAwait(SwitcherIntent.SetPinned(pinned = false))
                    }
                    assertFalse(afterEsc.isVisible)
                    SwingUtilities.invokeAndWait { activeOverlay.render(afterEsc) }
                    assertFalse(activeOverlay.isDialogVisible)
                } finally {
                    overlay?.let { created ->
                        SwingUtilities.invokeAndWait { created.dispose() }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `clicking non-selected row activates clicked window blank click no-ops and dialog hides only via render`() {
        assumeFalse(GraphicsEnvironment.isHeadless())

        val sandbox = SandboxWindowSystem(
            initialWindows = SandboxWindowSystem.defaultSampleWindows().take(3),
            workArea = Rectangle(-32000, -32000, 800, 600),
        )
        val activatedHandles = mutableListOf<WindowHandle>()
        val recordingControl = object : WindowControlPort by sandbox {
            override fun activate(handle: WindowHandle): NativeResult {
                activatedHandles.add(handle)
                return sandbox.activate(handle)
            }
        }
        val testScope = TestScope()
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)

        SwitcherCoordinator(
            windowCatalog = sandbox,
            windowControl = recordingControl,
            parentScope = testScope.backgroundScope,
            dispatcher = dispatcher,
        ).use { coordinator ->
            SyntheticWindowIconPort().use { iconPort ->
                var overlay: SwitcherOverlay? = null
                try {
                    SwingUtilities.invokeAndWait {
                        overlay = SwitcherOverlay(
                            coordinator = coordinator,
                            iconPort = iconPort,
                        )
                    }
                    val activeOverlay = requireNotNull(overlay) { "Overlay must be initialized on EDT" }
                    assertFalse(activeOverlay.windowList.isFocusable)

                    coordinator.dispatch(SwitcherIntent.ShowInteractive)
                    testScope.testScheduler.advanceUntilIdle()
                    val opened = coordinator.state.value
                    assertEquals(0, opened.selectedIndex)

                    SwingUtilities.invokeAndWait { activeOverlay.render(opened) }
                    assertTrue(activeOverlay.isDialogVisible)

                    // 1. Clicking blank space below the 3 rendered rows must NOT confirm or dismiss
                    SwingUtilities.invokeAndWait {
                        val lastBounds = requireNotNull(activeOverlay.windowList.getCellBounds(2, 2))
                        val blankY = lastBounds.y + lastBounds.height + 40
                        val blankClick = MouseEvent(
                            activeOverlay.windowList,
                            MouseEvent.MOUSE_CLICKED,
                            0L,
                            MouseEvent.BUTTON1_DOWN_MASK,
                            lastBounds.x + 20,
                            blankY,
                            1,
                            false,
                            MouseEvent.BUTTON1,
                        )
                        activeOverlay.windowList.dispatchEvent(blankClick)
                    }
                    testScope.testScheduler.advanceUntilIdle()
                    assertTrue(coordinator.state.value.isVisible)
                    assertTrue(activatedHandles.isEmpty())
                    assertTrue(activeOverlay.isDialogVisible)

                    // 2. Clicking row 2 while keyboard selectedIndex is 0 activates window 2,
                    // and dialog stays visible until render(hiddenState) runs on EDT.
                    SwingUtilities.invokeAndWait {
                        val row2Bounds = requireNotNull(activeOverlay.windowList.getCellBounds(2, 2))
                        val row2Click = MouseEvent(
                            activeOverlay.windowList,
                            MouseEvent.MOUSE_CLICKED,
                            0L,
                            MouseEvent.BUTTON1_DOWN_MASK,
                            row2Bounds.x + 20,
                            row2Bounds.y + row2Bounds.height / 2,
                            1,
                            false,
                            MouseEvent.BUTTON1,
                        )
                        activeOverlay.windowList.dispatchEvent(row2Click)
                    }
                    // Before actor processes intent and render() runs, dialog has NOT been hidden imperatively
                    assertTrue(activeOverlay.isDialogVisible)

                    testScope.testScheduler.advanceUntilIdle()
                    val hiddenState = coordinator.state.value
                    assertEquals(SwitcherMode.HIDDEN, hiddenState.mode)
                    assertEquals(listOf(opened.matches[2].window.hwnd), activatedHandles)

                    // Dialog remains visible until render(hiddenState) executes
                    assertTrue(activeOverlay.isDialogVisible)
                    SwingUtilities.invokeAndWait { activeOverlay.render(hiddenState) }
                    assertFalse(activeOverlay.isDialogVisible)
                } finally {
                    overlay?.let { created ->
                        SwingUtilities.invokeAndWait { created.dispose() }
                    }
                }
            }
        }
    }
}
