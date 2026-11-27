package com.michibaum.kwswitcher.core.ui

import com.michibaum.kwswitcher.core.coordinator.SwitcherCoordinator
import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import com.michibaum.kwswitcher.core.coordinator.SwitcherMode
import com.michibaum.kwswitcher.core.sandbox.SandboxWindowSystem
import com.michibaum.kwswitcher.core.sandbox.SyntheticWindowIconPort
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.event.KeyEvent
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
                } finally {
                    overlay?.let { created ->
                        SwingUtilities.invokeAndWait { created.dispose() }
                    }
                }
            }
        }
    }
}
