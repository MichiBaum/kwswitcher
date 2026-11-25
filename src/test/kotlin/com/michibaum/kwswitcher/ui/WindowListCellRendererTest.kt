package com.michibaum.kwswitcher.ui

import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import com.michibaum.kwswitcher.domain.WindowMatch
import com.michibaum.kwswitcher.win32.IconExtractor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.awt.Font
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.ImageIcon
import javax.swing.JList
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WindowListCellRendererTest {

    private fun createMetrics(fontStyle: Int = Font.PLAIN) =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            .createGraphics()
            .getFontMetrics(Font("Segoe UI", fontStyle, 14))

    @Test
    fun `formatHighlightedText keeps full text without ellipsis when within maxWidthPx`() {
        val plainMetrics = createMetrics(Font.PLAIN)
        val boldMetrics = createMetrics(Font.BOLD)
        val text = "Main.kt - kwswitcher"

        val formatted = WindowListCellRenderer.formatHighlightedText(
            text = text,
            ranges = listOf(0..3),
            highlightHex = "#4FC1FF",
            plainMetrics = plainMetrics,
            boldMetrics = boldMetrics,
            maxWidthPx = 500,
        )

        assertEquals(
            "<html><nobr><b><font color=\"#4FC1FF\">Main</font></b>.kt - kwswitcher</nobr></html>",
            formatted,
        )
    }

    @Test
    fun `formatHighlightedText truncates long highlighted text with end ellipsis and preserves visible highlights`() {
        val plainMetrics = createMetrics(Font.PLAIN)
        val boldMetrics = createMetrics(Font.BOLD)
        val longTitle = "kwswitcher - WindowListCellRenderer.kt - VeryLongProjectDescriptionThatOverflowsTheOverlayWidth - IntelliJ IDEA"

        val formatted = WindowListCellRenderer.formatHighlightedText(
            text = longTitle,
            ranges = listOf(0..9),
            highlightHex = "#4FC1FF",
            plainMetrics = plainMetrics,
            boldMetrics = boldMetrics,
            maxWidthPx = 220,
        )

        assertTrue(formatted.startsWith("<html><nobr><b><font color=\"#4FC1FF\">kwswitcher</font></b>"))
        assertTrue(formatted.endsWith("...</nobr></html>"))
        assertFalse(formatted.contains("IntelliJ IDEA"))
    }

    @Test
    fun `formatHighlightedText clamps highlight range that crosses truncation boundary`() {
        val plainMetrics = createMetrics(Font.PLAIN)
        val boldMetrics = createMetrics(Font.BOLD)
        val title = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        // Allow enough width for ~10 chars + "..."
        val maxWidth = plainMetrics.stringWidth("0123456789...")

        val formatted = WindowListCellRenderer.formatHighlightedText(
            text = title,
            ranges = listOf(5..20),
            highlightHex = "#4FC1FF",
            plainMetrics = plainMetrics,
            boldMetrics = boldMetrics,
            maxWidthPx = maxWidth,
        )

        assertTrue(formatted.startsWith("<html><nobr>01234<b><font color=\"#4FC1FF\">5678"))
        assertTrue(formatted.endsWith("</font></b>...</nobr></html>"))
    }

    @Test
    fun `renderer constrains long title width and truncates with ellipsis on layout`() {
        IconExtractor().use { extractor ->
            val renderer = WindowListCellRenderer(iconExtractor = extractor)
            val veryLongTitle = "A".repeat(300)
            val match = WindowMatch(
                window = WindowInfo(
                    hwnd = WindowHandle(42L),
                    title = veryLongTitle,
                    processName = "idea64",
                    exePath = "",
                    zOrder = 0,
                ),
                score = 100,
                titleHighlights = listOf(0..3),
                processHighlights = emptyList(),
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

            component.setBounds(0, 0, 720, 40)
            component.doLayout()

            val renderedText = renderer.titleLabel.text
            assertTrue(renderedText.endsWith("...</nobr></html>"))
            assertTrue(renderedText.length < veryLongTitle.length)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `renderer returns fallback icon immediately off EDT and repaints list once async icon resolves`() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val uiQueue = ArrayDeque<Runnable>()
        val extractionCalls = AtomicInteger(0)
        val resolvedAppIcon = ImageIcon(BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB))

        IconExtractor(
            maxCacheEntries = 16,
            targetSize = 20,
            parentScope = backgroundScope,
            ioDispatcher = ioDispatcher,
            uiExecutor = { uiQueue.addLast(it) },
            customExtractor = {
                extractionCalls.incrementAndGet()
                resolvedAppIcon
            },
        ).use { extractor ->
            val renderer = WindowListCellRenderer(iconExtractor = extractor)
            val match = WindowMatch(
                window = WindowInfo(
                    hwnd = WindowHandle(77L),
                    title = "Terminal",
                    processName = "WindowsTerminal",
                    exePath = "C:\\Apps\\WindowsTerminal.exe",
                    zOrder = 0,
                ),
                score = 80,
                titleHighlights = emptyList(),
                processHighlights = emptyList(),
            )

            var listRepaintCount = 0
            val list = object : JList<WindowMatch>(arrayOf(match)) {
                override fun repaint() {
                    listRepaintCount++
                    super.repaint()
                }
            }
            listRepaintCount = 0

            // First render on EDT: must return defaultIcon immediately without running extraction
            renderer.getListCellRendererComponent(
                list = list,
                value = match,
                index = 0,
                isSelected = false,
                cellHasFocus = false,
            )
            assertSame(extractor.defaultIcon, renderer.titleLabel.icon)
            assertEquals(0, extractionCalls.get())
            assertEquals(0, listRepaintCount)

            // Complete background extraction and flush UI callback queue
            testScheduler.advanceUntilIdle()
            assertEquals(1, extractionCalls.get())
            while (uiQueue.isNotEmpty()) {
                uiQueue.removeFirst().run()
            }
            assertTrue(listRepaintCount >= 1, "JList.repaint() should be triggered when async icon completes")

            // Subsequent render returns cached resolvedAppIcon synchronously
            renderer.getListCellRendererComponent(
                list = list,
                value = match,
                index = 0,
                isSelected = false,
                cellHasFocus = false,
            )
            assertSame(resolvedAppIcon, renderer.titleLabel.icon)
            assertEquals(1, extractionCalls.get())
        }
    }
}
