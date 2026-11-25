package com.michibaum.kwswitcher.ui

import com.michibaum.kwswitcher.config.SwitcherConfig
import com.michibaum.kwswitcher.config.ThemeManager
import com.michibaum.kwswitcher.config.ThemePalette
import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowMatch
import com.michibaum.kwswitcher.win32.IconExtractor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Renders each switcher list row with its native application icon, highlighted match spans,
 * end-ellipsis truncation for overflowing titles, state/monitor badges (`[Minimized]`, `[Mon N]`),
 * armed kill indicator, and process badge (`FR-5`, `FR-8`).
 */
class WindowListCellRenderer(
    private val iconExtractor: IconExtractor = IconExtractor(),
) : ListCellRenderer<WindowMatch> {

    var config: SwitcherConfig = SwitcherConfig()
    var palette: ThemePalette = ThemeManager.DARK_PALETTE
    var armedKillHwnd: WindowHandle? = null
    var multiMonitorDetected: Boolean = false

    internal val titleLabel = TruncatingHighlightLabel().apply {
        font = Font("Segoe UI", Font.PLAIN, 14)
        iconTextGap = 10
        minimumSize = Dimension(0, 0)
    }

    private val badgesPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false
    }

    private val panel = object : JPanel(BorderLayout(12, 0)) {
        override fun doLayout() {
            super.doLayout()
            titleLabel.updateTextForCurrentWidth()
        }
    }.apply {
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        isOpaque = true
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
            titleLabel.configureText(rawText = "", ranges = emptyList(), highlightHex = "")
            titleLabel.icon = null
            killBadge.isVisible = false
            minimizedBadge.isVisible = false
            monitorBadge.isVisible = false
            processBadge.text = ""
            panel.invalidate()
            return panel
        }

        val window = value.window
        val currentPalette = palette
        val highlightColor = if (isSelected) currentPalette.highlightSelectedHex else currentPalette.highlightHex

        titleLabel.icon = iconExtractor.getIconOrDefault(window) {
            list.repaint()
        }
        titleLabel.configureText(
            rawText = window.title,
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

        panel.invalidate()
        return panel
    }

    private fun createBadgeLabel(): JLabel = JLabel().apply {
        font = Font("Segoe UI", Font.BOLD, 11)
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        isOpaque = true
    }

    /**
     * `JLabel` that reports a zero preferred width so long titles never expand the list row
     * and truncates overflowing text with `...` while preserving search highlight spans.
     */
    internal class TruncatingHighlightLabel : JLabel() {
        private var rawText: String = ""
        private var highlightRanges: List<IntRange> = emptyList()
        private var highlightHex: String = ""

        fun configureText(rawText: String, ranges: List<IntRange>, highlightHex: String) {
            this.rawText = rawText
            this.highlightRanges = ranges
            this.highlightHex = highlightHex
            text = formatHighlightedText(
                text = rawText,
                ranges = ranges,
                highlightHex = highlightHex,
            )
        }

        fun updateTextForCurrentWidth() {
            if (width <= 0) return
            val labelInsets = insets
            val iconSpace = icon?.let { it.iconWidth + iconTextGap } ?: 0
            val availableTextWidth = (width - labelInsets.left - labelInsets.right - iconSpace - SAFETY_MARGIN_PX)
                .coerceAtLeast(0)
            val plainMetrics = getFontMetrics(font)
            val boldMetrics = getFontMetrics(font.deriveFont(Font.BOLD))
            text = formatHighlightedText(
                text = rawText,
                ranges = highlightRanges,
                highlightHex = highlightHex,
                plainMetrics = plainMetrics,
                boldMetrics = boldMetrics,
                maxWidthPx = availableTextWidth,
            )
        }

        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize()
            return Dimension(0, base.height)
        }
    }

    companion object {
        private const val ELLIPSIS = "..."
        private const val SAFETY_MARGIN_PX = 6

        /**
         * Formats [text] as HTML with bold colored spans for [ranges] (or plain text when no highlights apply),
         * truncating with `...` when [plainMetrics] is provided and the rendered width exceeds [maxWidthPx].
         */
        internal fun formatHighlightedText(
            text: String,
            ranges: List<IntRange>,
            highlightHex: String,
            plainMetrics: FontMetrics? = null,
            boldMetrics: FontMetrics? = plainMetrics,
            maxWidthPx: Int = Int.MAX_VALUE,
        ): String {
            if (text.isEmpty()) {
                return ""
            }
            val merged = if (ranges.isEmpty()) emptyList() else WindowMatch.mergeRanges(ranges)

            var displayText = text
            var displayRanges = merged
            var truncated = false

            if (plainMetrics != null && maxWidthPx < Int.MAX_VALUE) {
                val effectiveBoldMetrics = boldMetrics ?: plainMetrics
                val totalWidth = measureTextWidth(text, merged, plainMetrics, effectiveBoldMetrics)
                if (totalWidth > maxWidthPx) {
                    truncated = true
                    val ellipsisWidth = plainMetrics.stringWidth(ELLIPSIS)
                    if (maxWidthPx <= ellipsisWidth) {
                        return ELLIPSIS
                    }
                    val targetWidth = maxWidthPx - ellipsisWidth
                    val visibleLength = computeVisiblePrefixLength(
                        text = text,
                        mergedRanges = merged,
                        plainMetrics = plainMetrics,
                        boldMetrics = effectiveBoldMetrics,
                        targetWidthPx = targetWidth,
                    )
                    if (visibleLength <= 0) {
                        return ELLIPSIS
                    }
                    displayText = text.substring(0, visibleLength)
                    displayRanges = merged.mapNotNull { range ->
                        if (range.first >= visibleLength) {
                            null
                        } else {
                            range.first..minOf(range.last, visibleLength - 1)
                        }
                    }
                }
            }

            if (displayRanges.isEmpty()) {
                return if (truncated) displayText + ELLIPSIS else displayText
            }

            val sb = StringBuilder("<html><nobr>")
            var cursor = 0
            for (range in displayRanges) {
                val start = range.first.coerceIn(0, displayText.length)
                val endExclusive = (range.last + 1).coerceIn(start, displayText.length)
                if (start > cursor) {
                    sb.append(escapeHtml(displayText.substring(cursor, start)))
                }
                if (endExclusive > start) {
                    sb.append("<b><font color=\"")
                        .append(highlightHex)
                        .append("\">")
                        .append(escapeHtml(displayText.substring(start, endExclusive)))
                        .append("</font></b>")
                }
                cursor = endExclusive
            }
            if (cursor < displayText.length) {
                sb.append(escapeHtml(displayText.substring(cursor)))
            }
            if (truncated) {
                sb.append(ELLIPSIS)
            }
            sb.append("</nobr></html>")
            return sb.toString()
        }

        private fun measureTextWidth(
            text: String,
            mergedRanges: List<IntRange>,
            plainMetrics: FontMetrics,
            boldMetrics: FontMetrics,
        ): Int {
            var totalWidth = 0
            var rangeIdx = 0
            for (i in text.indices) {
                while (rangeIdx < mergedRanges.size && mergedRanges[rangeIdx].last < i) {
                    rangeIdx++
                }
                val isHighlighted = rangeIdx < mergedRanges.size && i >= mergedRanges[rangeIdx].first
                val metrics = if (isHighlighted) boldMetrics else plainMetrics
                totalWidth += metrics.charWidth(text[i])
            }
            return totalWidth
        }

        private fun computeVisiblePrefixLength(
            text: String,
            mergedRanges: List<IntRange>,
            plainMetrics: FontMetrics,
            boldMetrics: FontMetrics,
            targetWidthPx: Int,
        ): Int {
            var accumulatedWidth = 0
            var visibleLength = 0
            var rangeIdx = 0
            for (i in text.indices) {
                while (rangeIdx < mergedRanges.size && mergedRanges[rangeIdx].last < i) {
                    rangeIdx++
                }
                val isHighlighted = rangeIdx < mergedRanges.size && i >= mergedRanges[rangeIdx].first
                val metrics = if (isHighlighted) boldMetrics else plainMetrics
                val charWidth = metrics.charWidth(text[i])
                if (accumulatedWidth + charWidth > targetWidthPx) {
                    break
                }
                accumulatedWidth += charWidth
                visibleLength = i + 1
            }
            return visibleLength
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
