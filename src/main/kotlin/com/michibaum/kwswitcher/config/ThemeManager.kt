package com.michibaum.kwswitcher.config

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.Color
import java.awt.Toolkit

/**
 * Color palette used by the switcher overlay and list cell renderer for Dark and Light themes.
 */
data class ThemePalette(
    val isDark: Boolean,
    val dialogBackground: Color,
    val dialogBorder: Color,
    val listBackground: Color,
    val searchBorder: Color,
    val rowBackground: Color,
    val rowSelectedBackground: Color,
    val titleForeground: Color,
    val titleSelectedForeground: Color,
    val highlightHex: String,
    val highlightSelectedHex: String,
    val badgeBackground: Color,
    val badgeForeground: Color,
    val badgeSelectedBackground: Color,
    val badgeSelectedForeground: Color,
    val minimizedBadgeBackground: Color,
    val minimizedBadgeForeground: Color,
    val killArmedBackground: Color,
    val killArmedForeground: Color,
)

/**
 * Manages FlatLaf Look-and-Feel initialization and resolves Dark/Light [ThemePalette]s.
 */
object ThemeManager {

    val DARK_PALETTE = ThemePalette(
        isDark = true,
        dialogBackground = Color(0x1E, 0x1E, 0x1E),
        dialogBorder = Color(0x45, 0x45, 0x45),
        listBackground = Color(0x25, 0x25, 0x26),
        searchBorder = Color(0x3C, 0x3C, 0x3C),
        rowBackground = Color(0x25, 0x25, 0x26),
        rowSelectedBackground = Color(0x2D, 0x5A, 0x88),
        titleForeground = Color(0xE0, 0xE0, 0xE0),
        titleSelectedForeground = Color.WHITE,
        highlightHex = "#61AFEF",
        highlightSelectedHex = "#FFD866",
        badgeBackground = Color(0x33, 0x33, 0x37),
        badgeForeground = Color(0x9D, 0xA5, 0xB4),
        badgeSelectedBackground = Color(0x1E, 0x3F, 0x60),
        badgeSelectedForeground = Color(0xD8, 0xE9, 0xFF),
        minimizedBadgeBackground = Color(0x3E, 0x38, 0x2A),
        minimizedBadgeForeground = Color(0xE5, 0xC0, 0x7B),
        killArmedBackground = Color(0x8B, 0x26, 0x26),
        killArmedForeground = Color(0xFF, 0xE0, 0xE0),
    )

    val LIGHT_PALETTE = ThemePalette(
        isDark = false,
        dialogBackground = Color(0xF8, 0xF9, 0xFA),
        dialogBorder = Color(0xC8, 0xCD, 0xD4),
        listBackground = Color.WHITE,
        searchBorder = Color(0xDC, 0xE0, 0xE5),
        rowBackground = Color.WHITE,
        rowSelectedBackground = Color(0x00, 0x66, 0xCC),
        titleForeground = Color(0x1F, 0x23, 0x28),
        titleSelectedForeground = Color.WHITE,
        highlightHex = "#005CC5",
        highlightSelectedHex = "#FFE58F",
        badgeBackground = Color(0xEB, 0xEE, 0xF2),
        badgeForeground = Color(0x4E, 0x56, 0x61),
        badgeSelectedBackground = Color(0x00, 0x4C, 0x99),
        badgeSelectedForeground = Color(0xE6, 0xF2, 0xFF),
        minimizedBadgeBackground = Color(0xFF, 0xF3, 0xCD),
        minimizedBadgeForeground = Color(0x85, 0x64, 0x04),
        killArmedBackground = Color(0xC9, 0x2A, 0x2A),
        killArmedForeground = Color.WHITE,
    )

    /**
     * Resolves whether the given [option] should use the dark theme, querying the OS when `SYSTEM` is selected.
     */
    fun resolvePalette(option: ThemeOption): ThemePalette {
        val useDark = when (option) {
            ThemeOption.DARK -> true
            ThemeOption.LIGHT -> false
            ThemeOption.SYSTEM -> isWindowsSystemDarkTheme()
        }
        return if (useDark) DARK_PALETTE else LIGHT_PALETTE
    }

    /**
     * Installs the FlatLaf theme matching [option] and returns its [ThemePalette].
     */
    fun applyTheme(option: ThemeOption): ThemePalette {
        val palette = resolvePalette(option)
        if (palette.isDark) {
            FlatDarkLaf.setup()
        } else {
            FlatLightLaf.setup()
        }
        FlatLaf.updateUI()
        return palette
    }

    internal fun isWindowsSystemDarkTheme(): Boolean {
        val desktopProp = runCatching {
            Toolkit.getDefaultToolkit().getDesktopProperty("win.lightTheme.on") as? Boolean
        }.getOrNull()
        if (desktopProp != null) {
            return !desktopProp
        }
        return true
    }
}
