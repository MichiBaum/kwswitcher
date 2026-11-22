package com.michibaum.kwswitcher.ui

import com.michibaum.kwswitcher.config.ThemeOption
import java.awt.BasicStroke
import java.awt.CheckboxMenuItem
import java.awt.Color
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Manages the Windows system tray icon and context menu (`Show Switcher`, `Reload Config`,
 * `Theme`, `Run at Windows Startup`, and `Quit`) (`FR-9`).
 */
class SystemTrayController(
    private val onShowSwitcher: () -> Unit,
    private val onReloadConfig: () -> Unit,
    private val onSelectTheme: (ThemeOption) -> Unit,
    private val onQuit: () -> Unit,
) : AutoCloseable {

    private var trayIcon: TrayIcon? = null

    /**
     * Installs the system tray icon if supported by the current desktop environment.
     */
    fun install(initialTheme: ThemeOption = ThemeOption.SYSTEM): Boolean {
        if (!SystemTray.isSupported()) {
            return false
        }
        return runCatching {
            val tray = SystemTray.getSystemTray()
            val popup = PopupMenu()

            val showItem = MenuItem("Show Switcher").apply {
                addActionListener { onShowSwitcher() }
            }
            val reloadItem = MenuItem("Reload Config").apply {
                addActionListener { onReloadConfig() }
            }

            val themeMenu = Menu("Theme")
            val themeItems = ThemeOption.entries.associateWith { option ->
                CheckboxMenuItem(option.name.lowercase().replaceFirstChar(Char::uppercase), option == initialTheme)
            }
            for ((option, item) in themeItems) {
                item.addItemListener {
                    for ((otherOpt, otherItem) in themeItems) {
                        otherItem.state = (otherOpt == option)
                    }
                    onSelectTheme(option)
                }
                themeMenu.add(item)
            }

            val startupItem = CheckboxMenuItem("Run at Windows Startup", isStartupRegistered()).apply {
                addItemListener {
                    val enabled = state
                    if (!setStartupRegistered(enabled)) {
                        state = !enabled
                    }
                }
            }

            val quitItem = MenuItem("Quit").apply {
                addActionListener { onQuit() }
            }

            popup.add(showItem)
            popup.add(reloadItem)
            popup.add(themeMenu)
            popup.addSeparator()
            popup.add(startupItem)
            popup.addSeparator()
            popup.add(quitItem)

            val icon = TrayIcon(createTrayImage(), "Kotlin Switcheroo (kwswitcher)", popup).apply {
                isImageAutoSize = true
                addActionListener { onShowSwitcher() }
            }
            tray.add(icon)
            trayIcon = icon
            true
        }.getOrDefault(false)
    }

    override fun close() {
        val icon = trayIcon ?: return
        trayIcon = null
        runCatching {
            if (SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(icon)
            }
        }
    }

    private fun isStartupRegistered(): Boolean = runCatching {
        val process = ProcessBuilder(
            "reg",
            "query",
            RUN_REGISTRY_KEY,
            "/v",
            APP_REGISTRY_VALUE_NAME,
        ).redirectErrorStream(true).start()
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun setStartupRegistered(enabled: Boolean): Boolean = runCatching {
        val command = if (enabled) {
            val launchCommand = ProcessHandle.current().info().command().orElse("kwswitcher.exe")
            listOf(
                "reg",
                "add",
                RUN_REGISTRY_KEY,
                "/v",
                APP_REGISTRY_VALUE_NAME,
                "/t",
                "REG_SZ",
                "/d",
                "\"$launchCommand\"",
                "/f",
            )
        } else {
            listOf(
                "reg",
                "delete",
                RUN_REGISTRY_KEY,
                "/v",
                APP_REGISTRY_VALUE_NAME,
                "/f",
            )
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun createTrayImage(): BufferedImage {
        val size = 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(0x25, 0x63, 0xEB)
            g2.fillRoundRect(1, 1, size - 2, size - 2, 4, 4)
            g2.color = Color.WHITE
            g2.stroke = BasicStroke(1.6f)
            g2.drawLine(4, 6, 11, 6)
            g2.drawLine(9, 4, 11, 6)
            g2.drawLine(9, 8, 11, 6)
            g2.drawLine(4, 10, 11, 10)
            g2.drawLine(6, 8, 4, 10)
            g2.drawLine(6, 12, 4, 10)
        } finally {
            g2.dispose()
        }
        return image
    }

    companion object {
        private const val RUN_REGISTRY_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        private const val APP_REGISTRY_VALUE_NAME = "kwswitcher"
    }
}
