package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.core.config.HotkeyConfig
import com.michibaum.kwswitcher.core.config.HotkeyModifier
import com.michibaum.kwswitcher.core.config.HotkeyReverseModifier
import com.michibaum.kwswitcher.core.config.HotkeyTrigger
import com.michibaum.kwswitcher.win32.bindings.Win32Constants

/**
 * Pre-compiled Win32 virtual-key specification derived from a platform-neutral [HotkeyConfig].
 * Uses primitive [IntArray]s so hot `WH_KEYBOARD_LL` checks perform zero allocations.
 */
class Win32HotkeySpec(val config: HotkeyConfig) {

    private val modifierVks: IntArray = resolveModifierVks(config.resolvedModifier)
    private val reverseModifierVks: IntArray = resolveReverseModifierVks(config.resolvedReverseModifier)

    val modifierVkCodes: Set<Int> = modifierVks.toSet()
    val reverseModifierVkCodes: Set<Int> = reverseModifierVks.toSet()
    val reverseModifierPrimaryVk: Int = resolveReverseModifierPrimaryVk(config.resolvedReverseModifier)
    val triggerVkCode: Int = resolveTriggerVk(config.resolvedTrigger)
    val isAltModifier: Boolean = config.isAltModifier
    val isSpaceTrigger: Boolean = triggerVkCode == Win32Constants.VK_SPACE

    fun matchesModifierVk(vkCode: Int): Boolean {
        for (code in modifierVks) {
            if (code == vkCode) {
                return true
            }
        }
        return false
    }

    fun matchesReverseModifierVk(vkCode: Int): Boolean {
        for (code in reverseModifierVks) {
            if (code == vkCode) {
                return true
            }
        }
        return false
    }

    companion object {
        val DEFAULT = Win32HotkeySpec(HotkeyConfig())

        private fun resolveModifierVks(modifier: HotkeyModifier): IntArray = when (modifier) {
            HotkeyModifier.CTRL -> intArrayOf(
                Win32Constants.VK_CONTROL,
                Win32Constants.VK_LCONTROL,
                Win32Constants.VK_RCONTROL,
            )

            HotkeyModifier.WIN -> intArrayOf(Win32Constants.VK_LWIN, Win32Constants.VK_RWIN)

            HotkeyModifier.ALT -> intArrayOf(
                Win32Constants.VK_MENU,
                Win32Constants.VK_LMENU,
                Win32Constants.VK_RMENU,
            )
        }

        private fun resolveReverseModifierPrimaryVk(reverseModifier: HotkeyReverseModifier): Int =
            when (reverseModifier) {
                HotkeyReverseModifier.CTRL -> Win32Constants.VK_CONTROL
                HotkeyReverseModifier.ALT -> Win32Constants.VK_MENU
                HotkeyReverseModifier.SHIFT -> Win32Constants.VK_SHIFT
            }

        private fun resolveReverseModifierVks(reverseModifier: HotkeyReverseModifier): IntArray =
            when (reverseModifier) {
                HotkeyReverseModifier.CTRL -> intArrayOf(
                    Win32Constants.VK_CONTROL,
                    Win32Constants.VK_LCONTROL,
                    Win32Constants.VK_RCONTROL,
                )

                HotkeyReverseModifier.ALT -> intArrayOf(
                    Win32Constants.VK_MENU,
                    Win32Constants.VK_LMENU,
                    Win32Constants.VK_RMENU,
                )

                HotkeyReverseModifier.SHIFT -> intArrayOf(
                    Win32Constants.VK_SHIFT,
                    Win32Constants.VK_LSHIFT,
                    Win32Constants.VK_RSHIFT,
                )
            }

        private fun resolveTriggerVk(trigger: HotkeyTrigger): Int = when (trigger) {
            HotkeyTrigger.Tab -> Win32Constants.VK_TAB
            HotkeyTrigger.Space -> Win32Constants.VK_SPACE
            HotkeyTrigger.Backtick -> Win32Constants.VK_OEM_3
            is HotkeyTrigger.Letter -> trigger.upperChar.code
        }
    }
}
