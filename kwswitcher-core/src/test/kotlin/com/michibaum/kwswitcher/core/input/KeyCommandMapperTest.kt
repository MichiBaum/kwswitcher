package com.michibaum.kwswitcher.core.input

import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JTextField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyCommandMapperTest {

    private val dummyComponent = JTextField()

    private fun swingEvent(keyCode: Int, modifiers: Int = 0): KeyEvent = KeyEvent(
        dummyComponent,
        KeyEvent.KEY_PRESSED,
        0L,
        modifiers,
        keyCode,
        KeyEvent.CHAR_UNDEFINED,
    )

    @Test
    fun `navigation confirmation and window management shortcuts match identically across Swing and Hook sources`() {
        for (source in KeySource.entries) {
            assertEquals(
                SwitcherIntent.MoveSelection(delta = 1, lockInteractive = true),
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Down, source = source),
                    isQueryEmpty = true,
                ),
            )
            assertEquals(
                SwitcherIntent.MoveSelection(delta = -1, lockInteractive = true),
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Up, source = source),
                    isQueryEmpty = true,
                ),
            )
            assertEquals(
                SwitcherIntent.MoveSelection(delta = 1, lockInteractive = false),
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Tab, source = source),
                    isQueryEmpty = true,
                ),
            )
            assertEquals(
                SwitcherIntent.MoveSelection(delta = -1, lockInteractive = false),
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Tab, shift = true, source = source),
                    isQueryEmpty = true,
                ),
            )
            assertEquals(
                SwitcherIntent.MoveSelection(delta = 1, lockInteractive = true),
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Character('N'), ctrl = true, source = source),
                    isQueryEmpty = false,
                ),
            )
            assertEquals(
                SwitcherIntent.MoveSelection(delta = -1, lockInteractive = true),
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Character('P'), ctrl = true, source = source),
                    isQueryEmpty = false,
                ),
            )
            assertEquals(
                SwitcherIntent.CloseSelected,
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Character('W'), ctrl = true, source = source),
                    isQueryEmpty = false,
                ),
            )
            assertEquals(
                SwitcherIntent.KillSelectedProcess,
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Character('W'), ctrl = true, shift = true, source = source),
                    isQueryEmpty = false,
                ),
            )
            assertEquals(
                SwitcherIntent.MinimizeSelected,
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Character('M'), ctrl = true, source = source),
                    isQueryEmpty = false,
                ),
            )
            assertEquals(
                SwitcherIntent.ConfirmSelection,
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Enter, source = source),
                    isQueryEmpty = false,
                ),
            )
            assertEquals(
                SwitcherIntent.Dismiss(fromFocusLoss = false),
                KeyCommandMapper.mapActiveSessionKey(
                    gesture = KeyGesture(key = LogicalKey.Escape, source = source),
                    isQueryEmpty = false,
                ),
            )
        }
    }

    @Test
    fun `Delete closes selected window only when query is empty or Ctrl is held`() {
        val plainDelete = swingEvent(KeyEvent.VK_DELETE)
        assertEquals(
            SwitcherIntent.CloseSelected,
            KeyCommandMapper.mapSwingKeyEvent(event = plainDelete, isQueryEmpty = true),
        )
        assertNull(KeyCommandMapper.mapSwingKeyEvent(event = plainDelete, isQueryEmpty = false))

        val ctrlDelete = swingEvent(KeyEvent.VK_DELETE, InputEvent.CTRL_DOWN_MASK)
        assertEquals(
            SwitcherIntent.CloseSelected,
            KeyCommandMapper.mapSwingKeyEvent(event = ctrlDelete, isQueryEmpty = false),
        )
    }

    @Test
    fun `AltGr combinations are never swallowed as Ctrl shortcuts`() {
        val altGrW = KeyGesture(
            key = LogicalKey.Character('W'),
            ctrl = true,
            alt = true,
            altGr = true,
            source = KeySource.GLOBAL_HOOK,
        )
        assertNull(KeyCommandMapper.mapActiveSessionKey(gesture = altGrW, isQueryEmpty = true))
    }

    @Test
    fun `Alt-held typing maps to AppendQueryChar and BackspaceQuery only on global hook and respects Space trigger`() {
        val hookAltF = KeyGesture(key = LogicalKey.Character('F'), alt = true, source = KeySource.GLOBAL_HOOK)
        val hookAltShiftF = KeyGesture(
            key = LogicalKey.Character('F'),
            alt = true,
            shift = true,
            source = KeySource.GLOBAL_HOOK,
        )
        assertEquals(
            SwitcherIntent.AppendQueryChar('f'),
            KeyCommandMapper.mapActiveSessionKey(gesture = hookAltF, isQueryEmpty = true),
        )
        assertEquals(
            SwitcherIntent.AppendQueryChar('F'),
            KeyCommandMapper.mapActiveSessionKey(gesture = hookAltShiftF, isQueryEmpty = true),
        )

        // Swing text field handles regular character typing via DocumentListener, so mapSwingKeyEvent returns null
        assertNull(
            KeyCommandMapper.mapSwingKeyEvent(
                event = swingEvent(KeyEvent.VK_F),
                isQueryEmpty = true,
            ),
        )

        val hookAltBackspace = KeyGesture(key = LogicalKey.Backspace, alt = true, source = KeySource.GLOBAL_HOOK)
        assertEquals(
            SwitcherIntent.BackspaceQuery,
            KeyCommandMapper.mapActiveSessionKey(gesture = hookAltBackspace, isQueryEmpty = false),
        )

        val hookAltSpace = KeyGesture(key = LogicalKey.Space, alt = true, source = KeySource.GLOBAL_HOOK)
        assertEquals(
            SwitcherIntent.AppendQueryChar(' '),
            KeyCommandMapper.mapActiveSessionKey(
                gesture = hookAltSpace,
                isQueryEmpty = true,
                isSpaceTriggerKey = false,
            ),
        )
        assertNull(
            KeyCommandMapper.mapActiveSessionKey(
                gesture = hookAltSpace,
                isQueryEmpty = true,
                isSpaceTriggerKey = true,
            ),
        )
    }
}
