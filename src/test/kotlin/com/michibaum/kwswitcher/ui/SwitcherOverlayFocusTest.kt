package com.michibaum.kwswitcher.ui

import com.michibaum.kwswitcher.coordinator.SwitcherMode
import com.michibaum.kwswitcher.coordinator.SwitcherStateMachine
import com.michibaum.kwswitcher.dispatchHookEvent
import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import com.michibaum.kwswitcher.win32.HookAction
import com.michibaum.kwswitcher.win32.HookKeyEvent
import com.michibaum.kwswitcher.win32.IconExtractor
import com.michibaum.kwswitcher.win32.KeyboardHook
import com.michibaum.kwswitcher.win32.OverlayHookSnapshot
import com.michibaum.kwswitcher.win32.Win32Bindings
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwitcherOverlayFocusTest {

    private val sampleWindows = listOf(
        WindowInfo(WindowHandle(101L), "kwswitcher - Main.kt", "idea64", "", 0, pid = 10),
        WindowInfo(WindowHandle(102L), "Docs - Mozilla Firefox", "firefox", "", 1, pid = 20),
        WindowInfo(WindowHandle(103L), "Windows PowerShell", "pwsh", "", 2, pid = 30),
    )

    @Test
    fun `SwitcherOverlay requests native foreground focus once per open transition and syncs caret to query end`() {
        assumeFalse(GraphicsEnvironment.isHeadless())

        val focusRequests = AtomicInteger(0)
        val stateMachine = SwitcherStateMachine(
            enumerateWindows = { sampleWindows },
            queryActiveMonitorWorkArea = { Rectangle(-32000, -32000, 800, 600) },
        )

        IconExtractor().use { extractor ->
            var overlay: SwitcherOverlay? = null
            try {
                SwingUtilities.invokeAndWait {
                    overlay = SwitcherOverlay(
                        stateMachine = stateMachine,
                        iconExtractor = extractor,
                        onRequestForegroundFocus = { focusRequests.incrementAndGet() },
                    )
                }
                val activeOverlay = requireNotNull(overlay) { "Overlay must be initialized on EDT" }

                // Hidden -> Visible fires onRequestForegroundFocus exactly once
                val opened = stateMachine.onAltTabPressed(reverse = false)
                SwingUtilities.invokeAndWait { activeOverlay.render(opened) }
                assertEquals(1, focusRequests.get())

                // Subsequent selection moves and programmatic query updates while visible do NOT re-fire focus request
                val moved = stateMachine.onMoveSelection(delta = 1, lockInteractive = true)
                SwingUtilities.invokeAndWait { activeOverlay.render(moved) }
                assertEquals(1, focusRequests.get())

                val afterF = stateMachine.onAppendQueryChar('f')
                val afterFi = stateMachine.onAppendQueryChar('i')
                SwingUtilities.invokeAndWait {
                    activeOverlay.render(afterF)
                    activeOverlay.render(afterFi)
                    activeOverlay.ensureSearchFocus()
                }
                assertEquals(1, focusRequests.get())
                assertEquals("fi", activeOverlay.searchField.text)
                assertEquals(2, activeOverlay.searchField.caretPosition)

                val afterBackspace = stateMachine.onBackspaceQuery()
                SwingUtilities.invokeAndWait { activeOverlay.render(afterBackspace) }
                assertEquals("f", activeOverlay.searchField.text)
                assertEquals(1, activeOverlay.searchField.caretPosition)

                // Dismissing and re-opening triggers onRequestForegroundFocus a second time
                stateMachine.onDismiss()
                SwingUtilities.invokeAndWait { activeOverlay.render(stateMachine.state.value) }
                val reopened = stateMachine.onShowInteractive()
                SwingUtilities.invokeAndWait { activeOverlay.render(reopened) }
                assertEquals(2, focusRequests.get())
                assertEquals(0, activeOverlay.searchField.caretPosition)
            } finally {
                overlay?.let { created ->
                    SwingUtilities.invokeAndWait { created.dispose() }
                }
            }
        }
    }

    @Test
    fun `dispatchHookEvent routes hook chords and Alt typing into state machine`() {
        val closed = mutableListOf<WindowInfo>()
        val minimized = mutableListOf<WindowInfo>()
        val activated = mutableListOf<WindowInfo>()
        val stateMachine = SwitcherStateMachine(
            enumerateWindows = { sampleWindows },
            onActivateWindow = { activated.add(it) },
            onCloseWindow = { closed.add(it) },
            onMinimizeWindow = { minimized.add(it) },
        )

        KeyboardHook(
            overlayStateProvider = {
                val s = stateMachine.state.value
                if (!s.isVisible) OverlayHookSnapshot.HIDDEN else OverlayHookSnapshot(s.mode, s.query.isEmpty())
            },
            keyStateProvider = { false },
        ).use { hook ->
            // Simulate Alt+Tab on hook thread -> pending guard active even before dispatch
            hook.processKeyMessage(Win32Bindings.WM_SYSKEYDOWN, Win32Bindings.VK_LMENU, Win32Bindings.LLKHF_ALTDOWN)
            assertTrue(
                hook.processKeyMessage(Win32Bindings.WM_SYSKEYDOWN, Win32Bindings.VK_TAB, Win32Bindings.LLKHF_ALTDOWN),
            )
            dispatchHookEvent(requireNotNull(hook.pollEvent()), stateMachine, hook)
            assertEquals(SwitcherMode.QUICK_SWITCH, stateMachine.state.value.mode)

            // Route minimize, close, Alt-held search char, backspace, move, and confirm
            dispatchHookEvent(HookKeyEvent.Action(HookAction.MinimizeSelectedWindow), stateMachine, hook)
            assertEquals(1, minimized.size)
            assertEquals(SwitcherMode.INTERACTIVE_SEARCH, stateMachine.state.value.mode)

            dispatchHookEvent(HookKeyEvent.AppendSearchChar('p'), stateMachine, hook)
            dispatchHookEvent(HookKeyEvent.AppendSearchChar('w'), stateMachine, hook)
            dispatchHookEvent(HookKeyEvent.BackspaceSearch, stateMachine, hook)
            assertEquals("p", stateMachine.state.value.query)

            dispatchHookEvent(
                event = HookKeyEvent.Action(HookAction.MoveSelection(delta = 0, lockInteractive = true)),
                stateMachine = stateMachine,
                keyboardHook = hook,
            )
            dispatchHookEvent(HookKeyEvent.Action(HookAction.ConfirmSelection), stateMachine, hook)
            assertEquals(SwitcherMode.HIDDEN, stateMachine.state.value.mode)
            assertEquals(1, activated.size)

            // After session ends and pending-open was acknowledged, shortcuts pass through
            assertFalse(hook.processKeyMessage(Win32Bindings.WM_KEYDOWN, Win32Bindings.VK_ESCAPE, 0))
        }
    }
}
