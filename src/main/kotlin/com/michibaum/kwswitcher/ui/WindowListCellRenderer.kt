package com.michibaum.kwswitcher.ui

import com.michibaum.kwswitcher.config.SwitcherConfig
import com.michibaum.kwswitcher.config.ThemeManager
import com.michibaum.kwswitcher.config.ThemePalette
import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowMatch
import com.michibaum.kwswitcher.win32.IconExtractor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Renders each switcher list row with its native application icon, highlighted match spans,
 * state/monitor badges (`[Minimized]`, `[Mon N]`), armed kill indicator, and process badge (`FR-5`, `FR-8`).
 */
class WindowListCellRenderer(
    private val iconExtractor: IconExtractor = IconExtractor(),
) : ListCellRenderer<WindowMatch> {

    var config: SwitcherConfig = SwitcherConfig()
    var palette: ThemePalette = ThemeManager.DARK_PALETTE
    var armedKillHwnd: WindowHandle? = null
    var multiMonitorDetected: Boolean = false

    private val panel = JPanel(BorderLayout(12, 0)).apply {
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        isOpaque = true
    }

    private val titleLabel = JLabel().apply {
        font = Font("Segoe UI", Font.PLAIN, 14)
        iconTextGap = 10
    }

    private val badgesPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false
    }

    private val killBadge = createBadgeLabel()
    private val minimizedBadge = createBadgeLabel()
    private val monitorBadge = createBadgeLabel()
    private val processBadge = createBadgeLabel()

    init {
        badgesPanel.add(killBadge)
        badgesPanel.add(minimizedBadge)
        badgesPanel.add(monitorBadge)
        badgesPanel.add(processBadge)

        panel.add(titleLabel, BorderLayout.CENTER)
        panel.add(badgesPanel, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out WindowMatch>,
        value: WindowMatch?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        if (value == null) {
            titleLabel.text = ""
            titleLabel.icon = null
            killBadge.isVisible = false
            minimizedBadge.isVisible = false
            monitorBadge.isVisible = false
            processBadge.text = ""
            return panel
        }

        val window = value.window
        val currentPalette = palette
        val highlightColor = if (isSelected) currentPalette.highlightSelectedHex else currentPalette.highlightHex

        titleLabel.icon = iconExtractor.getIcon(window)
        titleLabel.text = formatHighlightedText(
            text = window.title,
            ranges = value.titleHighlights,
            highlightHex = highlightColor,
        )

        val isKillArmed = armedKillHwnd == window.hwnd
        killBadge.isVisible = isKillArmed
        if (isKillArmed) {
            killBadge.text = "Confirm Kill (Ctrl+Shift+W)"
            killBadge.background = currentPalette.killArmedBackground
            killBadge.foreground = currentPalette.killArmedForeground
        }

        val showMin = config.showMinimizedBadge && window.isMinimized
        minimizedBadge.isVisible = showMin
        if (showMin) {
            minimizedBadge.text = "[Minimized]"
            minimizedBadge.background = currentPalette.minimizedBadgeBackground
            minimizedBadge.foreground = currentPalette.minimizedBadgeForeground
        }

        val showMon = config.showMonitorBadge && multiMonitorDetected && window.monitorId.isNotBlank()
        monitorBadge.isVisible = showMon
        if (showMon) {
            monitorBadge.text = "[${window.monitorId}]"
            monitorBadge.background = if (isSelected) currentPalette.badgeSelectedBackground else currentPalette.badgeBackground
            monitorBadge.foreground = if (isSelected) currentPalette.badgeSelectedForeground else currentPalette.badgeForeground
        }

        processBadge.text = formatHighlightedText(
            text = window.processName,
            ranges = value.processHighlights,
            highlightHex = highlightColor,
        )

        if (isSelected) {
            panel.background = currentPalette.rowSelectedBackground
            titleLabel.foreground = currentPalette.titleSelectedForeground
            processBadge.background = currentPalette.badgeSelectedBackground
            processBadge.foreground = currentPalette.badgeSelectedForeground
        } else {
            panel.background = currentPalette.rowBackground
            titleLabel.foreground = currentPalette.titleForeground
            processBadge.background = currentPalette.badgeBackground
            processBadge.foreground = currentPalette.badgeForeground
        }

        return panel
    }

    private fun createBadgeLabel(): JLabel = JLabel().apply {
        font = Font("Segoe UI", Font.BOLD, 11)
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        isOpaque = true
    }

    companion object {
        /**
         * Formats [text] as HTML with bold colored spans for [ranges], or returns plain [text] when [ranges] is empty.
         */
        internal fun formatHighlightedText(
            text: String,
            ranges: List<IntRange>,
            highlightHex: String,
        ): String {
            if (ranges.isEmpty() || text.isEmpty()) {
                return text
            }
            val merged = WindowMatch.mergeRanges(ranges)
            if (merged.isEmpty()) {
                return text
            }
            val sb = StringBuilder("<html><nobr>")
            var cursor = 0
            for (range in merged) {
                val start = range.first.coerceIn(0, text.length)
                val endExclusive = (range.last + 1).coerceIn(start, text.length)
                if (start > cursor) {
                    sb.append(escapeHtml(text.substring(cursor, start)))
                }
                if (endExclusive > start) {
                    sb.append("<b><font color=\"")
                        .append(highlightHex)
                        .append("\">")
                        .append(escapeHtml(text.substring(start, endExclusive)))
                        .append("</font></b>")
                }
                cursor = endExclusive
            }
            if (cursor < text.length) {
                sb.append(escapeHtml(text.substring(cursor)))
            }
            sb.append("</nobr></html>")
            return sb.toString()
        }

        private fun escapeHtml(input: String): String = buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(ch)
                }
            }
        }
    }
}
