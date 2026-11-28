package com.michibaum.kwswitcher.core.ui

import com.michibaum.kwswitcher.core.config.ThemeOption
import com.michibaum.kwswitcher.core.port.StartupRegistrationPort
import com.michibaum.kwswitcher.core.sandbox.InMemoryStartupPort
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.AWTException
import java.awt.BasicStroke
import java.awt.CheckboxMenuItem
import java.awt.Color
import java.awt.EventQueue
import java.awt.HeadlessException
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

private val logger = KotlinLogging.logger {}

/**
 * Visual badge and tooltip profile for the system tray icon (`DX-2`).
 */
enum class TrayVisualStyle(val tooltipLabel: String, val tileColor: Color) {
    PRODUCTION("Kotlin Switcheroo (kwswitcher)", Color(0x25, 0x63, 0xEB)),
    DEV("Kotlin Switcheroo [DEV] (kwswitcher)", Color(0xD9, 0x77, 0x06)),
    SANDBOX("Kotlin Switcheroo [SANDBOX] (kwswitcher)", Color(0x05, 0x96, 0x69)),
}

/**
 * Optional diagnostic and developer-mode callbacks exposed in the system tray menu (`DX-2`, `DX-5`).
 */
data class TrayDiagnosticsCallbacks(
    val showPinOverlayToggle: Boolean = false,
    val initialPinned: Boolean = false,
    val onTogglePinOverlay: (Boolean) -> Unit = {},
    val onOpenLogDirectory: (() -> Unit)? = null,
    val onCopyDiagnostics: (() -> Unit)? = null,
)

/**
 * Manages the system tray icon and context menu (`FR-9`, `DX-2`, `DX-5`),
 * isolated from Win32 registry details via [StartupRegistrationPort].
 */
class SystemTrayController(
    private val onShowSwitcher: () -> Unit,
    private val onReloadConfig: () -> Unit,
    private val onSelectTheme: (ThemeOption) -> Unit,
    private val onQuit: () -> Unit,
    private val startupPort: StartupRegistrationPort = InMemoryStartupPort(),
    private val visualStyle: TrayVisualStyle = TrayVisualStyle.PRODUCTION,
    private val diagnostics: TrayDiagnosticsCallbacks = TrayDiagnosticsCallbacks(),
    private val backgroundExecutor: (Runnable) -> Unit = { task -> Thread.ofVirtual().start(task) },
    private val uiExecutor: (Runnable) -> Unit = EventQueue::invokeLater,
) : AutoCloseable {

    private var trayIcon: TrayIcon? = null
    private var pinMenuItem: CheckboxMenuItem? = null
    internal var startupMenuItem: CheckboxMenuItem? = null
    internal var themeItems: Map<ThemeOption, CheckboxMenuItem> = emptyMap()

    /**
     * Installs the system tray icon if supported by the current desktop environment.
     * Calling [install] when already installed is an idempotent no-op returning `true`.
     */
    fun install(initialTheme: ThemeOption = ThemeOption.SYSTEM): Boolean {
        if (trayIcon != null) {
            return true
        }
        if (!SystemTray.isSupported()) {
            logger.info { "SystemTray is not supported in this desktop environment" }
            return false
        }
        return try {
            val tray = SystemTray.getSystemTray()
            val popup = buildPopupMenu(initialTheme)
            val icon = TrayIcon(createTrayImage(visualStyle), visualStyle.tooltipLabel, popup).apply {
                isImageAutoSize = true
                addActionListener { onShowSwitcher() }
            }
            tray.add(icon)
            trayIcon = icon
            logger.debug { "Installed system tray icon (${visualStyle.name})" }
            true
        } catch (e: AWTException) {
            logger.warn(e) { "Failed to add icon to system tray" }
            false
        } catch (e: HeadlessException) {
            logger.debug(e) { "Headless environment prevented system tray installation" }
            false
        } catch (e: UnsupportedOperationException) {
            logger.warn(e) { "System tray operation unsupported" }
            false
        }
    }

    /**
     * Updates the checked state of the "Pin Overlay" tray item when toggled programmatically.
     */
    fun updatePinnedState(pinned: Boolean) {
        pinMenuItem?.state = pinned
    }

    /**
     * Synchronizes the "Theme" radio-style checkmarks with [theme] (e.g., after reloading `config.json`).
     */
    fun updateSelectedTheme(theme: ThemeOption) {
        for ((option, item) in themeItems) {
            item.state = (option == theme)
        }
    }

    internal fun buildPopupMenu(initialTheme: ThemeOption = ThemeOption.SYSTEM): PopupMenu {
        val popup = PopupMenu()
        popup.add(MenuItem("Show Switcher").apply { addActionListener { onShowSwitcher() } })
        popup.add(MenuItem("Reload Config").apply { addActionListener { onReloadConfig() } })
        popup.add(buildThemeMenu(initialTheme))

        if (diagnostics.showPinOverlayToggle ||
            diagnostics.onOpenLogDirectory != null ||
            diagnostics.onCopyDiagnostics != null
        ) {
            popup.addSeparator()
            appendDiagnosticsItems(popup)
        }

        popup.addSeparator()
        val currentlyRegistered = startupPort.isRegistered()
        val startupItem = CheckboxMenuItem("Run at Windows Startup", currentlyRegistered).apply {
            isEnabled = startupPort.isSupported || currentlyRegistered
            addItemListener {
                handleStartupToggle(this)
            }
        }
        startupMenuItem = startupItem
        popup.add(startupItem)
        popup.addSeparator()
        popup.add(MenuItem("Quit").apply { addActionListener { onQuit() } })
        return popup
    }

    internal fun handleStartupToggle(item: CheckboxMenuItem) {
        val desiredState = item.state
        backgroundExecutor(
            Runnable {
                val ok = startupPort.setRegistered(desiredState)
                if (!ok) {
                    uiExecutor(
                        Runnable {
                            item.state = !desiredState
                        },
                    )
                } else if (!desiredState && !startupPort.isSupported) {
                    uiExecutor(
                        Runnable {
                            item.isEnabled = false
                        },
                    )
                }
            },
        )
    }

    private fun buildThemeMenu(initialTheme: ThemeOption): Menu {
        val themeMenu = Menu("Theme")
        val items = ThemeOption.entries.associateWith { option ->
            CheckboxMenuItem(option.name.lowercase().replaceFirstChar(Char::uppercase), option == initialTheme)
        }
        themeItems = items
        for ((option, item) in items) {
            item.addItemListener {
                updateSelectedTheme(option)
                onSelectTheme(option)
            }
            themeMenu.add(item)
        }
        return themeMenu
    }

    private fun appendDiagnosticsItems(popup: PopupMenu) {
        if (diagnostics.showPinOverlayToggle) {
            val pinItem = CheckboxMenuItem("Pin Overlay", diagnostics.initialPinned).apply {
                addItemListener { diagnostics.onTogglePinOverlay(state) }
            }
            pinMenuItem = pinItem
            popup.add(pinItem)
        }
        diagnostics.onOpenLogDirectory?.let { openLogs ->
            popup.add(MenuItem("Open Log Directory").apply { addActionListener { openLogs() } })
        }
        diagnostics.onCopyDiagnostics?.let { copyDiag ->
            popup.add(MenuItem("Copy Diagnostics").apply { addActionListener { copyDiag() } })
        }
    }

    override fun close() {
        val icon = trayIcon
        trayIcon = null
        pinMenuItem = null
        startupMenuItem = null
        themeItems = emptyMap()
        if (icon == null) {
            return
        }
        try {
            if (SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(icon)
            }
        } catch (e: HeadlessException) {
            logger.debug(e) { "Ignored HeadlessException while removing tray icon" }
        }
    }

    companion object {
        internal fun createTrayImage(style: TrayVisualStyle = TrayVisualStyle.PRODUCTION): BufferedImage {
            val size = 16
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g2 = image.createGraphics()
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = style.tileColor
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
    }
}
