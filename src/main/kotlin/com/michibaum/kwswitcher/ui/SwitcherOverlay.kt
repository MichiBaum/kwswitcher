package com.michibaum.kwswitcher.ui

import com.formdev.flatlaf.FlatClientProperties
import com.michibaum.kwswitcher.config.SwitcherConfig
import com.michibaum.kwswitcher.config.ThemeManager
import com.michibaum.kwswitcher.config.ThemePalette
import com.michibaum.kwswitcher.coordinator.SwitcherMode
import com.michibaum.kwswitcher.coordinator.SwitcherStateMachine
import com.michibaum.kwswitcher.coordinator.SwitcherUiState
import com.michibaum.kwswitcher.domain.WindowMatch
import com.michibaum.kwswitcher.win32.IconExtractor
import com.michibaum.kwswitcher.win32.WindowEnumerator
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
 * and managing open windows (`FR-2`, `FR-6`, `FR-8`).
 */
class SwitcherOverlay(
    private val stateMachine: SwitcherStateMachine,
    private val iconExtractor: IconExtractor = IconExtractor(),
    private val activeMonitorBoundsProvider: () -> Rectangle? = { null },
    private val onRequestForegroundFocus: () -> Unit = {},
    initialConfig: SwitcherConfig = SwitcherConfig(),
    initialPalette: ThemePalette = ThemeManager.DARK_PALETTE,
) {
    internal val listModel = WindowMatchListModel()
    private var suppressDocumentEvents = false
    private val renderer = WindowListCellRenderer(iconExtractor = iconExtractor).apply {
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

    private val dialog = JDialog().apply {
        title = WindowEnumerator.OVERLAY_WINDOW_TITLE
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
                    stateMachine.onQueryChanged(searchField.text)
                }
            }
        })

        searchField.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
        searchField.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())

        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        e.consume()
                        stateMachine.onMoveSelection(delta = 1, lockInteractive = true)
                    }

                    KeyEvent.VK_UP -> {
                        e.consume()
                        stateMachine.onMoveSelection(delta = -1, lockInteractive = true)
                    }

                    KeyEvent.VK_TAB -> {
                        e.consume()
                        val delta = if (e.isShiftDown) -1 else 1
                        stateMachine.onMoveSelection(delta = delta, lockInteractive = false)
                    }

                    KeyEvent.VK_N -> {
                        if (e.isControlDown) {
                            e.consume()
                            stateMachine.onMoveSelection(delta = 1, lockInteractive = true)
                        }
                    }

                    KeyEvent.VK_P -> {
                        if (e.isControlDown) {
                            e.consume()
                            stateMachine.onMoveSelection(delta = -1, lockInteractive = true)
                        }
                    }

                    KeyEvent.VK_W -> {
                        if (e.isControlDown && e.isShiftDown) {
                            e.consume()
                            stateMachine.onKillSelectedWindowProcess()
                        } else if (e.isControlDown) {
                            e.consume()
                            stateMachine.onCloseSelectedWindow()
                        }
                    }

                    KeyEvent.VK_DELETE -> {
                        if (searchField.text.isEmpty() || e.isControlDown) {
                            e.consume()
                            stateMachine.onCloseSelectedWindow()
                        }
                    }

                    KeyEvent.VK_M -> {
                        if (e.isControlDown) {
                            e.consume()
                            stateMachine.onMinimizeSelectedWindow()
                        }
                    }

                    KeyEvent.VK_ENTER -> {
                        e.consume()
                        if (dialog.isVisible) {
                            dialog.isVisible = false
                        }
                        stateMachine.onConfirmSelection()
                    }

                    KeyEvent.VK_ESCAPE -> {
                        e.consume()
                        if (dialog.isVisible) {
                            dialog.isVisible = false
                        }
                        stateMachine.onDismiss()
                    }
                }
            }
        })

        windowList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 1 && windowList.selectedIndex >= 0) {
                    if (dialog.isVisible) {
                        dialog.isVisible = false
                    }
                    stateMachine.onConfirmSelection()
                }
            }
        })

        dialog.addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent?) {
                if (stateMachine.state.value.mode == SwitcherMode.INTERACTIVE_SEARCH) {
                    stateMachine.onDismiss()
                }
            }
        })
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
     * Bulk-updating [AbstractListModel] backed directly by an immutable [List] of [WindowMatch],
     * avoiding `DefaultListModel`'s `Vector` copies and per-item `ListDataEvent` storms on every keystroke.
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
