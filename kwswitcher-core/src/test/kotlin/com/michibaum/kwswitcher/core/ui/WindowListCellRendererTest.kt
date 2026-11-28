package com.michibaum.kwswitcher.core.ui

import com.michibaum.kwswitcher.core.domain.WindowHandle
import com.michibaum.kwswitcher.core.domain.WindowInfo
import com.michibaum.kwswitcher.core.domain.WindowMatch
import com.michibaum.kwswitcher.core.port.WindowIconPort
import com.michibaum.kwswitcher.core.sandbox.SyntheticWindowIconPort
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener
import javax.swing.plaf.basic.BasicHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WindowListCellRendererTest {

    private fun createMetrics(fontStyle: Int = Font.PLAIN) = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        .createGraphics()
        .getFontMetrics(Font("Segoe UI", fontStyle, 14))

    @Test
    fun `layoutHighlightedText keeps full text without ellipsis when within maxWidthPx`() {
        val plainMetrics = createMetrics(Font.PLAIN)
        val boldMetrics = createMetrics(Font.BOLD)
        val text = "Main.kt - kwswitcher"

        val layout = WindowListCellRenderer.layoutHighlightedText(
            text = text,
            ranges = listOf(0..3),
            plainMetrics = plainMetrics,
            boldMetrics = boldMetrics,
            maxWidthPx = 500,
        )

        assertEquals("Main.kt - kwswitcher", layout.displayText)
        assertEquals(listOf(0..3), layout.highlightRanges)
        assertFalse(layout.truncated)
    }

    @Test
    fun `layoutHighlightedText truncates long highlighted text with end ellipsis and preserves visible highlights`() {
        val plainMetrics = createMetrics(Font.PLAIN)
        val boldMetrics = createMetrics(Font.BOLD)
        val longTitle = "kwswitcher - WindowListCellRenderer.kt - VeryLongProjectDescriptionThatOverflowsTheOverlayWidth - IntelliJ IDEA"

        val layout = WindowListCellRenderer.layoutHighlightedText(
            text = longTitle,
            ranges = listOf(0..9),
            plainMetrics = plainMetrics,
            boldMetrics = boldMetrics,
            maxWidthPx = 220,
        )

        assertTrue(layout.truncated)
        assertTrue(layout.displayText.startsWith("kwswitcher"))
        assertTrue(layout.displayText.endsWith("..."))
        assertFalse(layout.displayText.contains("IntelliJ IDEA"))
        assertEquals(listOf(0..9), layout.highlightRanges)
    }

    @Test
    fun `layoutHighlightedText clamps highlight range that crosses truncation boundary`() {
        val plainMetrics = createMetrics(Font.PLAIN)
        val boldMetrics = createMetrics(Font.BOLD)
        val title = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val maxWidth = plainMetrics.stringWidth("0123456789...")

        val layout = WindowListCellRenderer.layoutHighlightedText(
            text = title,
            ranges = listOf(5..20),
            plainMetrics = plainMetrics,
            boldMetrics = boldMetrics,
            maxWidthPx = maxWidth,
        )

        assertTrue(layout.truncated)
        assertTrue(layout.displayText.startsWith("012345678"))
        assertTrue(layout.displayText.endsWith("..."))
        val visibleCharsBeforeEllipsis = layout.displayText.length - 3
        assertEquals(listOf(5 until visibleCharsBeforeEllipsis), layout.highlightRanges)
    }

    @Test
    fun `renderer constrains long title width truncates on layout caches bold font and paints without BasicHTML`() {
        SyntheticWindowIconPort().use { iconPort ->
            val renderer = WindowListCellRenderer(iconPort = iconPort)
            val highlightColor = Color(0xFF, 0x20, 0x40)
            renderer.palette = renderer.palette.copy(highlightSelectedColor = highlightColor)
            val veryLongTitle = "M".repeat(300)
            val match = WindowMatch(
                window = WindowInfo(
                    hwnd = WindowHandle(42L),
                    title = veryLongTitle,
                    processName = "idea64",
                    exePath = "",
                    zOrder = 0,
                ),
                score = 100,
                titleHighlights = listOf(0..5),
                processHighlights = listOf(0..3),
            )

            val list = JList(arrayOf(match))
            val component = renderer.getListCellRendererComponent(
                list = list,
                value = match,
                index = 0,
                isSelected = true,
                cellHasFocus = true,
            ) as JPanel

            assertTrue(
                component.preferredSize.width < 300,
                "Row preferred width (${component.preferredSize.width}) should not expand with long window titles",
            )

            val boldBeforeLayout = renderer.titleLabel.boldFont()
            component.setBounds(0, 0, 720, 40)
            component.doLayout()
            val boldAfterLayout = renderer.titleLabel.boldFont()

            assertSame(boldBeforeLayout, boldAfterLayout)
            assertNull(renderer.titleLabel.getClientProperty(BasicHTML.propertyKey))
            assertNull(renderer.processBadge.getClientProperty(BasicHTML.propertyKey))
            assertEquals("", renderer.titleLabel.text)
            assertEquals("", renderer.processBadge.text)

            val renderedText = renderer.titleLabel.displayedText
            assertTrue(renderer.titleLabel.isTruncated)
            assertTrue(renderedText.endsWith("..."))
            assertTrue(renderedText.length < veryLongTitle.length)

            val canvas = BufferedImage(720, 40, BufferedImage.TYPE_INT_ARGB)
            val g2 = canvas.createGraphics()
            try {
                component.paint(g2)
            } finally {
                g2.dispose()
            }
            var foundHighlightPixel = false
            for (y in 0 until canvas.height) {
                for (x in 0 until canvas.width) {
                    val rgb = canvas.getRGB(x, y)
                    val r = (rgb shr 16) and 0xFF
                    val g = (rgb shr 8) and 0xFF
                    val b = rgb and 0xFF
                    if (r > 220 && g < 80 && b < 100) {
                        foundHighlightPixel = true
                        break
                    }
                }
                if (foundHighlightPixel) break
            }
            assertTrue(foundHighlightPixel, "Direct Graphics2D span painting should render the highlight color")
        }
    }

    @Test
    fun `WindowMatchListModel batches updates and skips events when items list is unchanged`() {
        val model = SwitcherOverlay.WindowMatchListModel()
        var eventCount = 0
        model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent?) {
                eventCount++
            }

            override fun intervalRemoved(e: ListDataEvent?) {
                eventCount++
            }

            override fun contentsChanged(e: ListDataEvent?) {
                eventCount++
            }
        })

        val items1 = (1..10).map { idx ->
            WindowMatch(
                window = WindowInfo(WindowHandle(idx.toLong()), "Win $idx", "proc", "", idx),
                score = idx,
            )
        }
        model.setItems(items1)
        assertEquals(10, model.size)
        assertEquals(1, eventCount)

        model.setItems(items1)
        assertEquals(1, eventCount)

        val items2 = items1.take(6).map { it.copy(score = it.score + 10) }
        model.setItems(items2)
        assertEquals(6, model.size)
        assertEquals(3, eventCount)
    }

    @Test
    fun `renderer returns fallback icon immediately and repaints list once async icon port resolves`() {
        val defaultIcon = ImageIcon(BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB))
        val resolvedIcon = ImageIcon(BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB))
        var pendingCallback: (() -> Unit)? = null
        var isResolved = false

        val asyncPort = object : WindowIconPort {
            override val defaultIcon: Icon = defaultIcon
            override fun getIcon(window: WindowInfo): Icon = resolvedIcon
            override fun getIconOrDefault(window: WindowInfo, onResolved: (() -> Unit)?): Icon {
                if (isResolved) return resolvedIcon
                pendingCallback = onResolved
                return defaultIcon
            }
            override fun clearCache() = Unit
            override fun close() = Unit
        }

        val renderer = WindowListCellRenderer(iconPort = asyncPort)
        val match = WindowMatch(
            window = WindowInfo(
                hwnd = WindowHandle(77L),
                title = "Terminal",
                processName = "WindowsTerminal",
                exePath = "C:\\Apps\\WindowsTerminal.exe",
                zOrder = 0,
            ),
            score = 80,
        )

        var listRepaintCount = 0
        val list = object : JList<WindowMatch>(arrayOf(match)) {
            override fun repaint() {
                listRepaintCount++
                super.repaint()
            }
        }
        listRepaintCount = 0

        renderer.getListCellRendererComponent(
            list = list,
            value = match,
            index = 0,
            isSelected = false,
            cellHasFocus = false,
        )
        assertSame(defaultIcon, renderer.titleLabel.icon)
        assertEquals(0, listRepaintCount)

        isResolved = true
        pendingCallback?.invoke()
        assertTrue(listRepaintCount >= 1)

        renderer.getListCellRendererComponent(
            list = list,
            value = match,
            index = 0,
            isSelected = false,
            cellHasFocus = false,
        )
        assertSame(resolvedIcon, renderer.titleLabel.icon)
    }
}
