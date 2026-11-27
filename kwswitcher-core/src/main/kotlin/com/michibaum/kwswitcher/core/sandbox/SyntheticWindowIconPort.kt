package com.michibaum.kwswitcher.core.sandbox

import com.michibaum.kwswitcher.core.domain.WindowInfo
import com.michibaum.kwswitcher.core.port.WindowIconPort
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.ImageIcon

/**
 * Pure JVM [WindowIconPort] rendering deterministic colored monogram badges per process name.
 * Used by `--sandbox` mode and cross-platform UI tests without touching Win32 or the filesystem.
 */
class SyntheticWindowIconPort(private val targetSize: Int = DEFAULT_ICON_SIZE) : WindowIconPort {

    private val cache = ConcurrentHashMap<String, Icon>()

    override val defaultIcon: Icon = renderMonogramIcon(
        letter = 'W',
        background = Color(0x3B, 0x82, 0xF6),
        size = targetSize,
    )

    override fun getIcon(window: WindowInfo): Icon {
        val key = window.processName.trim().lowercase().ifEmpty { "unknown" }
        return cache.computeIfAbsent(key) {
            val letter = window.processName.firstOrNull(Char::isLetterOrDigit)?.uppercaseChar() ?: 'W'
            val color = PALETTE[(key.hashCode() and Int.MAX_VALUE) % PALETTE.size]
            renderMonogramIcon(letter = letter, background = color, size = targetSize)
        }
    }

    override fun getIconOrDefault(window: WindowInfo, onResolved: (() -> Unit)?): Icon = getIcon(window)

    override fun clearCache() {
        cache.clear()
    }

    override fun close() {
        cache.clear()
    }

    companion object {
        const val DEFAULT_ICON_SIZE = 20

        private val PALETTE = listOf(
            Color(0x25, 0x63, 0xEB),
            Color(0xD9, 0x77, 0x06),
            Color(0x05, 0x96, 0x69),
            Color(0x7C, 0x3A, 0xED),
            Color(0xDB, 0x27, 0x77),
            Color(0x08, 0x91, 0xB2),
        )

        fun renderMonogramIcon(letter: Char, background: Color, size: Int): Icon {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g2 = image.createGraphics()
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = background
                g2.fillRoundRect(1, 1, size - 2, size - 2, 6, 6)
                g2.color = Color.WHITE
                g2.font = Font("Segoe UI", Font.BOLD, (size * 0.6f).toInt().coerceAtLeast(10))
                val metrics = g2.fontMetrics
                val text = letter.toString()
                val x = (size - metrics.stringWidth(text)) / 2
                val y = (size - metrics.height) / 2 + metrics.ascent
                g2.drawString(text, x, y)
            } finally {
                g2.dispose()
            }
            return ImageIcon(image)
        }
    }
}
