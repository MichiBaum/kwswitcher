package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.core.config.HotkeyConfig
import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Win32HotkeySpecTest {

    @Test
    fun `Win32HotkeySpec compiles modifier reverse-modifier and trigger virtual-key sets accurately`() {
        val defaultSpec = Win32HotkeySpec.DEFAULT
        assertTrue(defaultSpec.isAltModifier)
        assertFalse(defaultSpec.isSpaceTrigger)
        assertEquals(Win32Constants.VK_TAB, defaultSpec.triggerVkCode)
        assertEquals(Win32Constants.VK_SHIFT, defaultSpec.reverseModifierPrimaryVk)
        assertEquals(
            setOf(Win32Constants.VK_MENU, Win32Constants.VK_LMENU, Win32Constants.VK_RMENU),
            defaultSpec.modifierVkCodes,
        )
        assertEquals(
            setOf(Win32Constants.VK_SHIFT, Win32Constants.VK_LSHIFT, Win32Constants.VK_RSHIFT),
            defaultSpec.reverseModifierVkCodes,
        )
        assertTrue(defaultSpec.matchesModifierVk(Win32Constants.VK_LMENU))
        assertFalse(defaultSpec.matchesModifierVk(Win32Constants.VK_LCONTROL))

        val devSpec = Win32HotkeySpec(HotkeyConfig(modifier = "ALT", triggerKey = "SPACE"))
        assertTrue(devSpec.isAltModifier)
        assertTrue(devSpec.isSpaceTrigger)
        assertEquals(Win32Constants.VK_SPACE, devSpec.triggerVkCode)

        val ctrlBacktick = Win32HotkeySpec(
            HotkeyConfig(modifier = " control ", triggerKey = " ` ", reverseModifier = " alt "),
        )
        assertFalse(ctrlBacktick.isAltModifier)
        assertEquals(Win32Constants.VK_OEM_3, ctrlBacktick.triggerVkCode)
        assertEquals(Win32Constants.VK_MENU, ctrlBacktick.reverseModifierPrimaryVk)
        assertTrue(ctrlBacktick.matchesModifierVk(Win32Constants.VK_RCONTROL))
        assertTrue(ctrlBacktick.matchesReverseModifierVk(Win32Constants.VK_LMENU))

        val winLetter = Win32HotkeySpec(HotkeyConfig(modifier = "windows", triggerKey = " k ", reverseModifier = "ctrl"))
        assertEquals('K'.code, winLetter.triggerVkCode)
        assertEquals(Win32Constants.VK_CONTROL, winLetter.reverseModifierPrimaryVk)
        assertTrue(winLetter.matchesModifierVk(Win32Constants.VK_LWIN))
    }
}
