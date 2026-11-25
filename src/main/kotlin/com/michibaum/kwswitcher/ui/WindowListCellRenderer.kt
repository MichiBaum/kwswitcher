package com.michibaum.kwswitcher.ui

import com.michibaum.kwswitcher.config.SwitcherConfig
import com.michibaum.kwswitcher.config.ThemeManager
import com.michibaum.kwswitcher.config.ThemePalette
import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowMatch
import com.michibaum.kwswitcher.win32.IconExtractor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * Renders each switcher list row with its native application icon, direct-painted highlighted match spans
 * (without Swing `BasicHTML` parsing), end-ellipsis truncation for overflowing titles, state/monitor badges
 * (`[Minimized]`, `[Mon N]`), armed kill indicator, and process badge (`FR-5`, `FR-8`).
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

    private val killBadge = createBadgeLabel().apply {
        text = KILL_CONFIRM_TEXT
    }
    private val minimizedBadge = createBadgeLabel().apply {
        text = MINIMIZED_BADGE_TEXT
    }
    private val monitorBadge = createBadgeLabel()
    internal val processBadge = HighlightLabel(constrainPreferredWidth = false).apply {
        font = Font("Segoe UI", Font.BOLD, 11)
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        isOpaque = true
    }

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
            titleLabel.configureText(rawText = "", ranges = emptyList(), highlightColor = palette.highlightColor)
            titleLabel.icon = null
            killBadge.isVisible = false
            minimizedBadge.isVisible = false
            monitorBadge.isVisible = false
            processBadge.configureText(rawText = "", ranges = emptyList(), highlightColor = palette.highlightColor)
            panel.invalidate()
            return panel
        }

        val window = value.window
        val currentPalette = palette
        val highlightColor = if (isSelected) currentPalette.highlightSelectedColor else currentPalette.highlightColor

        titleLabel.icon = iconExtractor.getIconOrDefault(window) {
            list.repaint()
        }
        titleLabel.configureText(
            rawText = window.title,
            ranges = value.titleHighlights,
            highlightColor = highlightColor,
        )

        val isKillArmed = armedKillHwnd == window.hwnd
        killBadge.isVisible = isKillArmed
        if (isKillArmed) {
            killBadge.background = currentPalette.killArmedBackground
            killBadge.foreground = currentPalette.killArmedForeground
        }

        val showMin = config.showMinimizedBadge && window.isMinimized
        minimizedBadge.isVisible = showMin
        if (showMin) {
            minimizedBadge.background = currentPalette.minimizedBadgeBackground
            minimizedBadge.foreground = currentPalette.minimizedBadgeForeground
        }

        val showMon = config.showMonitorBadge && multiMonitorDetected && window.monitorId.isNotBlank()
        monitorBadge.isVisible = showMon
        if (showMon) {
            val monitorText = "[${window.monitorId}]"
            if (monitorBadge.text != monitorText) {
                monitorBadge.text = monitorText
            }
            monitorBadge.background = if (isSelected) currentPalette.badgeSelectedBackground else currentPalette.badgeBackground
            monitorBadge.foreground = if (isSelected) currentPalette.badgeSelectedForeground else currentPalette.badgeForeground
        }

        processBadge.configureText(
            rawText = window.processName,
            ranges = value.processHighlights,
            highlightColor = highlightColor,
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
     * Custom-painted `JLabel` that renders plain and bold/colored highlight spans directly via [Graphics2D],
     * caching its derived bold [Font] and completely bypassing Swing's `BasicHTML` CSS/view engine.
     */
    internal open class HighlightLabel(
        private val constrainPreferredWidth: Boolean,
    ) : JLabel() {
        protected var rawText: String = ""
        protected var mergedRanges: List<IntRange> = emptyList()
        var highlightColor: Color = ThemeManager.DARK_PALETTE.highlightColor
            private set

        protected var resolvedLayout: HighlightedTextLayout = EMPTY_LAYOUT
        protected var layoutDirty: Boolean = false
        private var cachedBoldFont: Font? = null

        val displayedText: String
            get() = ensureLayout().displayText

        val displayedHighlightRanges: List<IntRange>
            get() = ensureLayout().highlightRanges

        val isTruncated: Boolean
            get() = ensureLayout().truncated

        override fun setFont(font: Font?) {
            super.setFont(font)
            cachedBoldFont = font?.let { if (it.isBold) it else it.deriveFont(Font.BOLD) }
            layoutDirty = true
        }

        internal fun boldFont(): Font {
            cachedBoldFont?.let { return it }
            val baseFont = font ?: Font("Segoe UI", Font.PLAIN, 14)
            val derived = if (baseFont.isBold) baseFont else baseFont.deriveFont(Font.BOLD)
            cachedBoldFont = derived
            return derived
        }

        fun configureText(rawText: String, ranges: List<IntRange>, highlightColor: Color) {
            this.rawText = rawText
            this.mergedRanges = if (ranges.isEmpty()) emptyList() else WindowMatch.mergeRanges(ranges)
            this.highlightColor = highlightColor
            this.layoutDirty = true
        }

        protected open fun computeCurrentLayout(): HighlightedTextLayout =
            if (rawText.isEmpty()) {
                EMPTY_LAYOUT
            } else {
                HighlightedTextLayout(
                    displayText = rawText,
                    highlightRanges = mergedRanges,
                    truncated = false,
                )
            }

        protected fun ensureLayout(): HighlightedTextLayout {
            if (layoutDirty) {
                resolvedLayout = computeCurrentLayout()
                layoutDirty = false
            }
            return resolvedLayout
        }

        override fun getPreferredSize(): Dimension {
            val ins = insets
            val baseFont = font
            val plainMetrics = getFontMetrics(baseFont)
            val boldMetrics = getFontMetrics(boldFont())
            val currentIcon = icon
            val iconW = currentIcon?.iconWidth ?: 0
            val iconH = currentIcon?.iconHeight ?: 0
            val textW = if (constrainPreferredWidth || rawText.isEmpty()) {
                0
            } else {
                measureTextWidth(rawText, mergedRanges, plainMetrics, boldMetrics)
            }
            val textH = if (rawText.isEmpty() && iconH > 0) 0 else maxOf(plainMetrics.height, boldMetrics.height)
            val gap = if (!constrainPreferredWidth && iconW > 0 && rawText.isNotEmpty()) iconTextGap else 0
            val contentW = if (constrainPreferredWidth) 0 else (iconW + gap + textW)
            val contentH = maxOf(iconH, textH)
            return Dimension(ins.left + ins.right + contentW, ins.top + ins.bottom + contentH)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            val originalColor = g2.color
            val originalFont = g2.font
            if (isOpaque) {
                g2.color = background
                g2.fillRect(0, 0, width, height)
            }

            val ins = insets
            val availHeight = (height - ins.top - ins.bottom).coerceAtLeast(0)
            var currentX = ins.left

            icon?.let { ic ->
                val iconY = ins.top + (availHeight - ic.iconHeight) / 2
                ic.paintIcon(this, g2, currentX, iconY)
                currentX += ic.iconWidth + iconTextGap
            }

            val layout = ensureLayout()
            val displayText = layout.displayText
            if (displayText.isNotEmpty()) {
                applyDesktopFontHints(g2)
                val plainFont = font
                val bold = boldFont()
                val plainMetrics = g2.getFontMetrics(plainFont)
                val baselineY = ins.top + (availHeight - plainMetrics.height) / 2 + plainMetrics.ascent

                val spans = layout.highlightRanges
                if (spans.isEmpty()) {
                    g2.font = plainFont
                    g2.color = foreground
                    g2.drawString(displayText, currentX, baselineY)
                } else {
                    val boldMetrics = g2.getFontMetrics(bold)
                    var cursor = 0
                    for (range in spans) {
                        val start = range.first.coerceIn(0, displayText.length)
                        val endExclusive = (range.last + 1).coerceIn(start, displayText.length)
                        if (start > cursor) {
                            val plainSegment = displayText.substring(cursor, start)
                            g2.font = plainFont
                            g2.color = foreground
                            g2.drawString(plainSegment, currentX, baselineY)
                            currentX += plainMetrics.stringWidth(plainSegment)
                        }
                        if (endExclusive > start) {
                            val highlightedSegment = displayText.substring(start, endExclusive)
                            g2.font = bold
                            g2.color = highlightColor
                            g2.drawString(highlightedSegment, currentX, baselineY)
                            currentX += boldMetrics.stringWidth(highlightedSegment)
                        }
                        cursor = endExclusive
                    }
                    if (cursor < displayText.length) {
                        val trailingSegment = displayText.substring(cursor)
                        g2.font = plainFont
                        g2.color = foreground
                        g2.drawString(trailingSegment, currentX, baselineY)
                    }
                }
            }

            g2.font = originalFont
            g2.color = originalColor
        }
    }

    /**
     * [HighlightLabel] that reports a zero preferred width so long titles never expand the list row
     * and truncates overflowing text with `...` while preserving search highlight spans.
     */
    internal class TruncatingHighlightLabel : HighlightLabel(constrainPreferredWidth = true) {

        fun updateTextForCurrentWidth() {
            if (width <= 0) return
            resolvedLayout = computeCurrentLayout()
            layoutDirty = false
        }

        override fun computeCurrentLayout(): HighlightedTextLayout {
            if (rawText.isEmpty()) {
                return EMPTY_LAYOUT
            }
            if (width <= 0) {
                return HighlightedTextLayout(
                    displayText = rawText,
                    highlightRanges = mergedRanges,
                    truncated = false,
                )
            }
            val labelInsets = insets
            val iconSpace = icon?.let { it.iconWidth + iconTextGap } ?: 0
            val availableTextWidth = (width - labelInsets.left - labelInsets.right - iconSpace - SAFETY_MARGIN_PX)
                .coerceAtLeast(0)
            val plainMetrics = getFontMetrics(font)
            val boldMetrics = getFontMetrics(boldFont())
            return layoutHighlightedText(
                text = rawText,
                ranges = mergedRanges,
                plainMetrics = plainMetrics,
                boldMetrics = boldMetrics,
                maxWidthPx = availableTextWidth,
                rangesAlreadyMerged = true,
            )
        }
    }

    internal data class HighlightedTextLayout(
        val displayText: String,
        val highlightRanges: List<IntRange>,
        val truncated: Boolean,
    )

    companion object {
        private const val ELLIPSIS = "..."
        private const val SAFETY_MARGIN_PX = 6
        private const val KILL_CONFIRM_TEXT = "Confirm Kill (Ctrl+Shift+W)"
        private const val MINIMIZED_BADGE_TEXT = "[Minimized]"
        private val EMPTY_LAYOUT = HighlightedTextLayout(
            displayText = "",
            highlightRanges = emptyList(),
            truncated = false,
        )

        private val DESKTOP_FONT_HINTS: Map<*, *>? by lazy {
            runCatching {
                Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") as? Map<*, *>
            }.getOrNull()
        }

        private fun applyDesktopFontHints(g2: Graphics2D) {
            val hints = DESKTOP_FONT_HINTS
            if (hints != null) {
                g2.addRenderingHints(hints)
            } else {
                g2.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                )
            }
        }

        /**
         * Computes the plain display text and merged/clamped highlight spans for [text],
         * truncating with `...` when [plainMetrics] is provided and rendered width exceeds [maxWidthPx].
         */
        internal fun layoutHighlightedText(
            text: String,
            ranges: List<IntRange>,
            plainMetrics: FontMetrics? = null,
            boldMetrics: FontMetrics? = plainMetrics,
            maxWidthPx: Int = Int.MAX_VALUE,
            rangesAlreadyMerged: Boolean = false,
        ): HighlightedTextLayout {
            if (text.isEmpty()) {
                return EMPTY_LAYOUT
            }
            val merged = when {
                ranges.isEmpty() -> emptyList()
                rangesAlreadyMerged -> ranges
                else -> WindowMatch.mergeRanges(ranges)
            }

            if (plainMetrics != null && maxWidthPx < Int.MAX_VALUE) {
                val effectiveBoldMetrics = boldMetrics ?: plainMetrics
                val totalWidth = measureTextWidth(text, merged, plainMetrics, effectiveBoldMetrics)
                if (totalWidth > maxWidthPx) {
                    val ellipsisWidth = plainMetrics.stringWidth(ELLIPSIS)
                    if (maxWidthPx <= ellipsisWidth) {
                        return HighlightedTextLayout(
                            displayText = ELLIPSIS,
                            highlightRanges = emptyList(),
                            truncated = true,
                        )
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
                        return HighlightedTextLayout(
                            displayText = ELLIPSIS,
                            highlightRanges = emptyList(),
                            truncated = true,
                        )
                    }
                    val prefix = text.substring(0, visibleLength)
                    val clampedRanges = if (merged.isEmpty()) {
                        emptyList()
                    } else {
                        merged.mapNotNull { range ->
                            if (range.first >= visibleLength) {
                                null
                            } else {
                                range.first..minOf(range.last, visibleLength - 1)
                            }
                        }
                    }
                    return HighlightedTextLayout(
                        displayText = prefix + ELLIPSIS,
                        highlightRanges = clampedRanges,
                        truncated = true,
                    )
                }
            }

            return HighlightedTextLayout(
                displayText = text,
                highlightRanges = merged,
                truncated = false,
            )
        }

        private fun measureTextWidth(
            text: String,
            mergedRanges: List<IntRange>,
            plainMetrics: FontMetrics,
            boldMetrics: FontMetrics,
        ): Int {
            if (mergedRanges.isEmpty()) {
                return plainMetrics.stringWidth(text)
            }
            var totalWidth = 0
            var cursor = 0
            for (range in mergedRanges) {
                val start = range.first.coerceIn(0, text.length)
                val endExclusive = (range.last + 1).coerceIn(start, text.length)
                if (start > cursor) {
                    totalWidth += plainMetrics.stringWidth(text.substring(cursor, start))
                }
                if (endExclusive > start) {
                    totalWidth += boldMetrics.stringWidth(text.substring(start, endExclusive))
                }
                cursor = endExclusive
            }
            if (cursor < text.length) {
                totalWidth += plainMetrics.stringWidth(text.substring(cursor))
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
    }
}
