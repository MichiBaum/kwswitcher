package com.michibaum.kwswitcher.core.ui

import com.formdev.flatlaf.FlatClientProperties
import com.michibaum.kwswitcher.core.config.SwitcherConfig
import com.michibaum.kwswitcher.core.config.ThemeManager
import com.michibaum.kwswitcher.core.config.ThemePalette
import com.michibaum.kwswitcher.core.coordinator.SwitcherCoordinator
import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import com.michibaum.kwswitcher.core.coordinator.SwitcherMode
import com.michibaum.kwswitcher.core.coordinator.SwitcherUiState
import com.michibaum.kwswitcher.core.domain.OverlayConstants
import com.michibaum.kwswitcher.core.domain.WindowMatch
import com.michibaum.kwswitcher.core.input.KeyCommandMapper
import com.michibaum.kwswitcher.core.port.WindowIconPort
import com.michibaum.kwswitcher.core.sandbox.SyntheticWindowIconPort
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.AbstractListModel
import javax.swing.BorderFactory
import javax.swing.JDialog
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Undecorated, always-on-top Swing + FlatLaf overlay dialog for searching, cycling,
 * and managing open windows (`FR-2`, `FR-6`, `FR-8`, `DX-2`, `DX-3`).
 * Depends strictly on `core` ports, [KeyCommandMapper], and [SwitcherCoordinator].
 */
class SwitcherOverlay(
    private val coordinator: SwitcherCoordinator,
    private val iconPort: WindowIconPort = SyntheticWindowIconPort(),
    private val activeMonitorBoundsProvider: () -> Rectangle? = { null },
    private val onRequestForegroundFocus: () -> Unit = {},
    initialConfig: SwitcherConfig = SwitcherConfig(),
    initialPalette: ThemePalette = ThemeManager.DARK_PALETTE,
) {
    internal val listModel = WindowMatchListModel()
    private var suppressDocumentEvents = false
    private val renderer = WindowListCellRenderer(iconPort = iconPort).apply {
        config = initialConfig
        palette = initialPalette
    }

    internal val searchField = JTextField().apply {
        font = Font("Segoe UI", Font.PLAIN, 16)
        putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Type to filter open windows...")
        putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true)
        focusTraversalKeysEnabled = false
    }

    private val windowList = object : JList<WindowMatch>(listModel) {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }.apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = renderer
        fixedCellHeight = 40
        focusTraversalKeysEnabled = false
    }

    private val rootPanel = JPanel(BorderLayout(0, 0)).apply {
        preferredSize = Dimension(720, 440)
    }

    private val scrollPane = JScrollPane(windowList).apply {
        border = BorderFactory.createEmptyBorder()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    internal val dialog = JDialog().apply {
        title = OverlayConstants.OVERLAY_WINDOW_TITLE
        isUndecorated = true
        isAlwaysOnTop = true
        defaultCloseOperation = JDialog.HIDE_ON_CLOSE
        focusTraversalKeysEnabled = false

        rootPanel.add(searchField, BorderLayout.NORTH)
        rootPanel.add(scrollPane, BorderLayout.CENTER)

        contentPane = rootPanel
        pack()
        setLocationRelativeTo(null)
    }

    val isDialogVisible: Boolean
        get() = dialog.isVisible

    init {
        applyPalette(initialPalette)
        configureInputHandlers()
    }

    /**
     * Updates the active configuration and theme palette on the Swing Event Dispatch Thread.
     */
    fun updateConfigAndTheme(config: SwitcherConfig, palette: ThemePalette) {
        val applyBlock = {
            renderer.config = config
            renderer.palette = palette
            applyPalette(palette)
            SwingUtilities.updateComponentTreeUI(dialog)
            applyPalette(palette)
            windowList.repaint()
        }
        if (SwingUtilities.isEventDispatchThread()) {
            applyBlock()
        } else {
            SwingUtilities.invokeLater(applyBlock)
        }
    }

    private fun applyPalette(palette: ThemePalette) {
        rootPanel.background = palette.dialogBackground
        rootPanel.border = BorderFactory.createLineBorder(palette.dialogBorder, 1)
        searchField.background = palette.dialogBackground
        searchField.foreground = palette.titleForeground
        searchField.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, palette.searchBorder),
            BorderFactory.createEmptyBorder(10, 12, 10, 12),
        )
        windowList.background = palette.listBackground
        scrollPane.viewport.background = palette.listBackground
    }

    /**
     * Ensures Swing keyboard input focus is placed on the search field while the overlay remains visible.
     */
    fun ensureSearchFocus() {
        val focusBlock = {
            if (dialog.isVisible && !searchField.hasFocus()) {
                searchField.requestFocusInWindow()
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            focusBlock()
        } else {
            SwingUtilities.invokeLater(focusBlock)
        }
    }

    /**
     * Renders the latest [state] snapshot on the Swing Event Dispatch Thread.
     */
    fun render(state: SwitcherUiState) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { renderOnEdt(state) }
        } else {
            renderOnEdt(state)
        }
    }

    private fun renderOnEdt(state: SwitcherUiState) {
        if (!state.isVisible) {
            if (dialog.isVisible) {
                dialog.isVisible = false
            }
            return
        }

        if (searchField.text != state.query) {
            suppressDocumentEvents = true
            try {
                searchField.text = state.query
                searchField.caretPosition = state.query.length
            } finally {
                suppressDocumentEvents = false
            }
        }

        val badgeStateChanged = renderer.armedKillHwnd != state.armedKillHwnd ||
            renderer.multiMonitorDetected != state.multiMonitorDetected
        renderer.armedKillHwnd = state.armedKillHwnd
        renderer.multiMonitorDetected = state.multiMonitorDetected
        updateListItems(state.matches, state.selectedIndex)
        if (badgeStateChanged) {
            windowList.repaint()
        }

        val openingFromHidden = !dialog.isVisible
        if (openingFromHidden) {
            positionOnActiveMonitor(state.activeMonitorWorkArea)
            dialog.isVisible = true
            dialog.toFront()
        }
        if (!searchField.hasFocus()) {
            searchField.requestFocusInWindow()
        }
        if (openingFromHidden) {
            onRequestForegroundFocus()
        }
    }

    private fun positionOnActiveMonitor(precomputedWorkArea: Rectangle?) {
        val workArea = precomputedWorkArea ?: activeMonitorBoundsProvider()
        if (workArea != null && workArea.width > 0 && workArea.height > 0) {
            val x = workArea.x + (workArea.width - dialog.width) / 2
            val y = workArea.y + (workArea.height - dialog.height) / 2
            dialog.setLocation(x, y)
        } else {
            dialog.setLocationRelativeTo(null)
        }
    }

    private fun updateListItems(matches: List<WindowMatch>, selectedIndex: Int) {
        listModel.setItems(matches)
        if (matches.isNotEmpty() && selectedIndex in matches.indices) {
            if (windowList.selectedIndex != selectedIndex) {
                windowList.selectedIndex = selectedIndex
            }
            windowList.ensureIndexIsVisible(selectedIndex)
        } else {
            windowList.clearSelection()
        }
    }

    private fun configureInputHandlers() {
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onTextChanged()
            override fun removeUpdate(e: DocumentEvent?) = onTextChanged()
            override fun changedUpdate(e: DocumentEvent?) = onTextChanged()

            private fun onTextChanged() {
                if (!suppressDocumentEvents) {
                    coordinator.dispatch(SwitcherIntent.SetQuery(searchField.text))
                }
            }
        })

        searchField.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
        searchField.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())

        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                handleOverlayKeyEvent(e)
            }
        })

        windowList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 1 && windowList.selectedIndex >= 0) {
                    if (dialog.isVisible) {
                        dialog.isVisible = false
                    }
                    coordinator.dispatch(SwitcherIntent.ConfirmSelection)
                }
            }
        })

        dialog.addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent?) {
                handleWindowLostFocus()
            }
        })
    }

    internal fun handleOverlayKeyEvent(e: KeyEvent) {
        val intent = KeyCommandMapper.mapSwingKeyEvent(
            event = e,
            isQueryEmpty = searchField.text.isEmpty(),
        ) ?: return

        e.consume()
        if (intent is SwitcherIntent.ConfirmSelection || intent is SwitcherIntent.Dismiss) {
            if (dialog.isVisible) {
                dialog.isVisible = false
            }
        }
        coordinator.dispatch(intent)
    }

    internal fun handleWindowLostFocus() {
        val current = coordinator.state.value
        if (current.mode == SwitcherMode.INTERACTIVE_SEARCH && !current.pinned) {
            coordinator.dispatch(SwitcherIntent.Dismiss(fromFocusLoss = true))
        }
    }

    fun dispose() {
        if (SwingUtilities.isEventDispatchThread()) {
            dialog.dispose()
        } else {
            SwingUtilities.invokeLater {
                dialog.dispose()
            }
        }
    }

    /**
     * Bulk-updating [AbstractListModel] backed directly by an immutable [List] of [WindowMatch].
     */
    internal class WindowMatchListModel : AbstractListModel<WindowMatch>() {
        private var items: List<WindowMatch> = emptyList()

        override fun getSize(): Int = items.size

        override fun getElementAt(index: Int): WindowMatch = items[index]

        fun setItems(newItems: List<WindowMatch>) {
            if (items === newItems || items == newItems) {
                return
            }
            val oldSize = items.size
            val newSize = newItems.size
            items = newItems
            val minSize = minOf(oldSize, newSize)
            if (minSize > 0) {
                fireContentsChanged(this, 0, minSize - 1)
            }
            if (newSize > oldSize) {
                fireIntervalAdded(this, oldSize, newSize - 1)
            } else if (oldSize > newSize) {
                fireIntervalRemoved(this, newSize, oldSize - 1)
            }
        }
    }
}
