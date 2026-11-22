package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.filechooser.FileSystemView

/**
 * Extracts and caches native Win32 application icons (`HICON` -> `BufferedImage` -> [ImageIcon])
 * using `SendMessageTimeoutW(WM_GETICON, SMTO_ABORTIFHUNG, 50ms)`, `GetClassLongPtrW`, and `gdi32.dll` (`FR-4`).
 */
class IconExtractor(
    private val maxCacheEntries: Int = 128,
    private val targetSize: Int = 20,
) {
    private val cacheLock = Any()
    private val iconCache = object : LinkedHashMap<String, Icon>(maxCacheEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Icon>?): Boolean =
            size > maxCacheEntries
    }

    private val defaultIcon: Icon by lazy { createDefaultAppIcon(targetSize) }

    /**
     * Resolves an [Icon] for [window], returning a cached instance when `exePath` has already been resolved.
     */
    fun getIcon(window: WindowInfo): Icon {
        val cacheKey = window.exePath.lowercase().ifBlank { "hwnd:${window.hwnd.value}" }
        synchronized(cacheLock) {
            iconCache[cacheKey]?.let { return it }
        }

        val resolved = extractFromHwnd(window.hwnd)
            ?: extractFromFileSystem(window.exePath)
            ?: defaultIcon

        synchronized(cacheLock) {
            iconCache[cacheKey] = resolved
        }
        return resolved
    }

    /**
     * Clears all cached icons.
     */
    fun clearCache() {
        synchronized(cacheLock) {
            iconCache.clear()
        }
    }

    internal fun extractFromHwnd(handle: WindowHandle): Icon? {
        if (!handle.isValid) return null
        val hwnd = MemorySegment.ofAddress(handle.value)

        Arena.ofConfined().use { arena ->
            val hIconAddress = queryWindowIconHandle(arena = arena, hwnd = hwnd)
            if (hIconAddress == 0L) {
                return null
            }
            val image = convertHIconToImage(
                arena = arena,
                hIcon = MemorySegment.ofAddress(hIconAddress),
            ) ?: return null
            val scaled = if (image.width != targetSize || image.height != targetSize) {
                scaleImage(image, targetSize)
            } else {
                image
            }
            return ImageIcon(scaled)
        }
    }

    private fun queryWindowIconHandle(arena: Arena, hwnd: MemorySegment): Long {
        val resultSegment = arena.allocate(ValueLayout.JAVA_LONG)

        for (iconType in longArrayOf(Win32Bindings.ICON_SMALL2, Win32Bindings.ICON_SMALL, Win32Bindings.ICON_BIG)) {
            resultSegment.set(ValueLayout.JAVA_LONG, 0L, 0L)
            val ok = Win32Bindings.SendMessageTimeoutW.invokeExact(
                hwnd,
                Win32Bindings.WM_GETICON,
                iconType,
                0L,
                Win32Bindings.SMTO_ABORTIFHUNG,
                50,
                resultSegment,
            ) as Long
            if (ok != 0L) {
                val hIcon = resultSegment.get(ValueLayout.JAVA_LONG, 0L)
                if (hIcon != 0L) return hIcon
            }
        }

        for (classIndex in intArrayOf(Win32Bindings.GCLP_HICONSM, Win32Bindings.GCLP_HICON)) {
            val hIcon = Win32Bindings.GetClassLongPtrW.invokeExact(hwnd, classIndex) as Long
            if (hIcon != 0L) return hIcon
        }
        return 0L
    }

    private fun convertHIconToImage(arena: Arena, hIcon: MemorySegment): BufferedImage? {
        val iconInfo = arena.allocate(Win32Bindings.ICONINFO_LAYOUT)
        val ok = Win32Bindings.GetIconInfo.invokeExact(hIcon, iconInfo) as Int
        if (ok == 0) {
            return null
        }

        val hbmMask = iconInfo.get(ValueLayout.ADDRESS, 16L)
        val hbmColor = iconInfo.get(ValueLayout.ADDRESS, 24L)
        val bitmapToRead = if (hbmColor != MemorySegment.NULL) hbmColor else hbmMask
        if (bitmapToRead == MemorySegment.NULL) {
            return null
        }

        var hdc = MemorySegment.NULL
        try {
            val bitmapStruct = arena.allocate(Win32Bindings.BITMAP_LAYOUT)
            val gotObj = Win32Bindings.GetObjectW.invokeExact(
                bitmapToRead,
                Win32Bindings.BITMAP_LAYOUT.byteSize().toInt(),
                bitmapStruct,
            ) as Int
            if (gotObj == 0) {
                return null
            }

            val width = bitmapStruct.get(ValueLayout.JAVA_INT, 4L)
            val height = bitmapStruct.get(ValueLayout.JAVA_INT, 8L)
            if (width <= 0 || height <= 0 || width > 256 || height > 256) {
                return null
            }

            hdc = Win32Bindings.CreateCompatibleDC.invokeExact(MemorySegment.NULL) as MemorySegment
            if (hdc == MemorySegment.NULL) {
                return null
            }

            val bmi = arena.allocate(Win32Bindings.BITMAPINFOHEADER_LAYOUT)
            bmi.set(ValueLayout.JAVA_INT, 0L, Win32Bindings.BITMAPINFOHEADER_LAYOUT.byteSize().toInt())
            bmi.set(ValueLayout.JAVA_INT, 4L, width)
            bmi.set(ValueLayout.JAVA_INT, 8L, -height) // Top-down DIB
            bmi.set(ValueLayout.JAVA_SHORT, 12L, 1.toShort())
            bmi.set(ValueLayout.JAVA_SHORT, 14L, 32.toShort())
            bmi.set(ValueLayout.JAVA_INT, 16L, Win32Bindings.BI_RGB)

            val pixelCount = width * height
            val pixelsSegment = arena.allocate(ValueLayout.JAVA_INT, pixelCount.toLong())
            val linesCopied = Win32Bindings.GetDIBits.invokeExact(
                hdc,
                bitmapToRead,
                0,
                height,
                pixelsSegment,
                bmi,
                Win32Bindings.DIB_RGB_COLORS,
            ) as Int
            if (linesCopied <= 0) {
                return null
            }

            val argb = IntArray(pixelCount)
            var hasNonZeroAlpha = false
            for (i in 0 until pixelCount) {
                val pixel = pixelsSegment.getAtIndex(ValueLayout.JAVA_INT, i.toLong())
                if ((pixel ushr 24) != 0) {
                    hasNonZeroAlpha = true
                }
                argb[i] = pixel
            }

            if (!hasNonZeroAlpha) {
                var anyVisible = false
                for (i in 0 until pixelCount) {
                    if ((argb[i] and 0x00FFFFFF) != 0) {
                        argb[i] = argb[i] or (0xFF shl 24)
                        anyVisible = true
                    }
                }
                if (!anyVisible) return null
            }

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, width, height, argb, 0, width)
            return image
        } finally {
            if (hdc != MemorySegment.NULL) {
                Win32Bindings.DeleteDC.invokeExact(hdc) as Int
            }
            if (hbmMask != MemorySegment.NULL) {
                Win32Bindings.DeleteObject.invokeExact(hbmMask) as Int
            }
            if (hbmColor != MemorySegment.NULL) {
                Win32Bindings.DeleteObject.invokeExact(hbmColor) as Int
            }
        }
    }

    private fun extractFromFileSystem(exePath: String): Icon? {
        if (exePath.isBlank()) return null
        return runCatching {
            val file = File(exePath)
            if (!file.exists()) return null
            val sysIcon = FileSystemView.getFileSystemView().getSystemIcon(file) ?: return null
            val buffered = BufferedImage(
                sysIcon.iconWidth.coerceAtLeast(1),
                sysIcon.iconHeight.coerceAtLeast(1),
                BufferedImage.TYPE_INT_ARGB,
            )
            val g = buffered.createGraphics()
            try {
                sysIcon.paintIcon(null, g, 0, 0)
            } finally {
                g.dispose()
            }
            ImageIcon(scaleImage(buffered, targetSize))
        }.getOrNull()
    }

    private fun scaleImage(source: BufferedImage, size: Int): BufferedImage {
        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g2 = scaled.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.drawImage(source.getScaledInstance(size, size, Image.SCALE_SMOOTH), 0, 0, null)
        } finally {
            g2.dispose()
        }
        return scaled
    }

    companion object {
        internal fun createDefaultAppIcon(size: Int): ImageIcon {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g2 = image.createGraphics()
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(0x3B, 0x82, 0xF6)
                g2.fillRoundRect(1, 2, size - 2, size - 4, 5, 5)
                g2.color = Color(0xDB, 0xEA, 0xFE)
                g2.stroke = BasicStroke(1.5f)
                g2.drawLine(4, 6, size - 5, 6)
            } finally {
                g2.dispose()
            }
            return ImageIcon(image)
        }
    }
}
