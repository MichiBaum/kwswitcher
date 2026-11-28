package com.michibaum.kwswitcher.core.config

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Color
import java.awt.HeadlessException
import java.awt.Toolkit
import javax.swing.UIManager

private val logger = KotlinLogging.logger {}

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
    val highlightColor: Color = Color.decode(highlightHex),
    val highlightSelectedColor: Color = Color.decode(highlightSelectedHex),
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
        highlightColor = Color(0x61, 0xAF, 0xEF),
        highlightSelectedColor = Color(0xFF, 0xD8, 0x66),
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
        highlightColor = Color(0x00, 0x5C, 0xC5),
        highlightSelectedColor = Color(0xFF, 0xE5, 0x8F),
    )

    /**
     * Resolves whether the given [option] should use the dark theme, querying the OS when `SYSTEM` is selected.
     */
    fun resolvePalette(option: ThemeOption): ThemePalette {
        val useDark = when (option) {
            ThemeOption.DARK -> true
            ThemeOption.LIGHT -> false
            ThemeOption.SYSTEM -> isSystemDarkTheme()
        }
        return if (useDark) DARK_PALETTE else LIGHT_PALETTE
    }

    /**
     * Installs the FlatLaf theme matching [option] and returns its [ThemePalette].
     */
    fun applyTheme(option: ThemeOption, updateExistingComponents: Boolean = true): ThemePalette {
        val palette = resolvePalette(option)
        val targetLafClass = if (palette.isDark) FlatDarkLaf::class.java else FlatLightLaf::class.java
        if (UIManager.getLookAndFeel()?.javaClass != targetLafClass) {
            if (palette.isDark) {
                FlatDarkLaf.setup()
            } else {
                FlatLightLaf.setup()
            }
            if (updateExistingComponents) {
                FlatLaf.updateUI()
            }
        }
        return palette
    }

    internal fun isSystemDarkTheme(): Boolean {
        val lightThemeOn = try {
            Toolkit.getDefaultToolkit().getDesktopProperty("win.lightTheme.on") as? Boolean
        } catch (e: HeadlessException) {
            logger.debug(e) { "Headless toolkit when querying win.lightTheme.on; defaulting to dark theme" }
            null
        }
        return if (lightThemeOn != null) !lightThemeOn else true
    }
}
