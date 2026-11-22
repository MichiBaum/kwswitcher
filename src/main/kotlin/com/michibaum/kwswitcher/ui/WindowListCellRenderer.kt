package com.michibaum.kwswitcher.ui

import com.michibaum.kwswitcher.domain.WindowInfo
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class WindowListCellRenderer : ListCellRenderer<WindowInfo> {
    private val panel = JPanel(BorderLayout(12, 0)).apply {
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        isOpaque = true
    }

    private val titleLabel = JLabel().apply {
        font = Font("Segoe UI", Font.PLAIN, 14)
    }

    private val processBadge = JLabel().apply {
        font = Font("Segoe UI", Font.BOLD, 11)
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        isOpaque = true
    }

    init {
        panel.add(titleLabel, BorderLayout.CENTER)
        panel.add(processBadge, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out WindowInfo>,
        value: WindowInfo?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        if (value == null) {
            titleLabel.text = ""
            processBadge.text = ""
            return panel
        }

        titleLabel.text = value.title
        processBadge.text = value.processName

        if (isSelected) {
            panel.background = Color(0x2D, 0x5A, 0x88)
            titleLabel.foreground = Color.WHITE
            processBadge.background = Color(0x1E, 0x3F, 0x60)
            processBadge.foreground = Color(0xD8, 0xE9, 0xFF)
        } else {
            panel.background = Color(0x25, 0x25, 0x26)
            titleLabel.foreground = Color(0xE0, 0xE0, 0xE0)
            processBadge.background = Color(0x33, 0x33, 0x37)
            processBadge.foreground = Color(0x9D, 0xA5, 0xB4)
        }

        return panel
    }
}
