package com.michibaum.kwswitcher.ui

import com.formdev.flatlaf.FlatClientProperties
import com.michibaum.kwswitcher.coordinator.SwitcherStateMachine
import com.michibaum.kwswitcher.coordinator.SwitcherUiState
import com.michibaum.kwswitcher.domain.WindowInfo
import com.michibaum.kwswitcher.win32.WindowEnumerator
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JDialog
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SwitcherOverlay(
    private val stateMachine: SwitcherStateMachine,
) {
    private val listModel = DefaultListModel<WindowInfo>()
    private var suppressDocumentEvents = false

    private val searchField = JTextField().apply {
        font = Font("Segoe UI", Font.PLAIN, 16)
        putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Type to filter open windows...")
        putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0x3C, 0x3C, 0x3C)),
            BorderFactory.createEmptyBorder(10, 12, 10, 12),
        )
        focusTraversalKeysEnabled = false
    }

    private val windowList = JList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = WindowListCellRenderer()
        background = Color(0x25, 0x25, 0x26)
        fixedCellHeight = 40
        focusTraversalKeysEnabled = false
    }

    private val dialog = JDialog().apply {
        title = WindowEnumerator.OVERLAY_WINDOW_TITLE
        isUndecorated = true
        isAlwaysOnTop = true
        defaultCloseOperation = JDialog.HIDE_ON_CLOSE
        focusTraversalKeysEnabled = false

        val rootPanel = JPanel(BorderLayout(0, 0)).apply {
            background = Color(0x1E, 0x1E, 0x1E)
            border = BorderFactory.createLineBorder(Color(0x45, 0x45, 0x45), 1)
            preferredSize = Dimension(680, 420)
        }

        val scrollPane = JScrollPane(windowList).apply {
            border = BorderFactory.createEmptyBorder()
            viewport.background = Color(0x25, 0x25, 0x26)
        }

        rootPanel.add(searchField, BorderLayout.NORTH)
        rootPanel.add(scrollPane, BorderLayout.CENTER)

        contentPane = rootPanel
        pack()
        setLocationRelativeTo(null)
    }

    init {
        configureInputHandlers()
    }

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
            } finally {
                suppressDocumentEvents = false
            }
        }

        updateListItems(state.filteredWindows, state.selectedIndex)

        if (!dialog.isVisible) {
            dialog.setLocationRelativeTo(null)
            dialog.isVisible = true
            dialog.toFront()
        }
        if (!searchField.hasFocus()) {
            searchField.requestFocusInWindow()
        }
    }

    private fun updateListItems(windows: List<WindowInfo>, selectedIndex: Int) {
        listModel.clear()
        for (window in windows) {
            listModel.addElement(window)
        }
        if (windows.isNotEmpty() && selectedIndex in windows.indices) {
            windowList.selectedIndex = selectedIndex
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

                    KeyEvent.VK_ENTER -> {
                        e.consume()
                        stateMachine.onConfirmSelection()
                    }

                    KeyEvent.VK_ESCAPE -> {
                        e.consume()
                        stateMachine.onDismiss()
                    }
                }
            }
        })

        windowList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 1 && windowList.selectedIndex >= 0) {
                    stateMachine.onConfirmSelection()
                }
            }
        })

        dialog.addWindowFocusListener(object : WindowAdapter() {
            override fun windowLostFocus(e: WindowEvent?) {
                if ( stateMachine.state.value.mode == com.michibaum.kwswitcher.coordinator.SwitcherMode.INTERACTIVE_SEARCH) {
                    stateMachine.onDismiss()
                }
            }
        })
    }

    fun dispose() {
        SwingUtilities.invokeLater {
            dialog.dispose()
        }
    }
}
