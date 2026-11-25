package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileSystemView

/**
 * Extracts and caches native Win32 application icons (`HICON` -> `BufferedImage` -> [ImageIcon])
 * using `SendMessageTimeoutW(WM_GETICON, SMTO_ABORTIFHUNG, 50ms)`, `GetClassLongPtrW`, and `gdi32.dll` (`FR-4`).
 */
class IconExtractor internal constructor(
    private val maxCacheEntries: Int,
    private val targetSize: Int,
    parentScope: CoroutineScope?,
    ioDispatcher: CoroutineDispatcher,
    private val uiExecutor: (Runnable) -> Unit,
    private val customExtractor: ((WindowInfo) -> Icon?)?,
) : AutoCloseable {

    constructor(
        maxCacheEntries: Int = 128,
        targetSize: Int = 20,
        parentScope: CoroutineScope? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4),
        uiExecutor: (Runnable) -> Unit = SwingUtilities::invokeLater,
    ) : this(
        maxCacheEntries = maxCacheEntries,
        targetSize = targetSize,
        parentScope = parentScope,
        ioDispatcher = ioDispatcher,
        uiExecutor = uiExecutor,
        customExtractor = null,
    )

    private val scopeJob: CompletableJob = SupervisorJob(parentScope?.coroutineContext?.get(Job))
    private val extractorScope: CoroutineScope = CoroutineScope(scopeJob + ioDispatcher)

    private val cacheLock = Any()
    private var cacheGeneration: Long = 0L
    private var closed: Boolean = false
    private val inFlightRequests = HashMap<String, InFlightRequest>()

    private val iconCache = object : LinkedHashMap<String, Icon>(maxCacheEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Icon>?): Boolean =
            size > maxCacheEntries
    }

    internal val defaultIcon: Icon by lazy { createDefaultAppIcon(targetSize) }

    /**
     * Synchronously resolves an [Icon] for [window], returning a cached instance when available.
     */
    fun getIcon(window: WindowInfo): Icon {
        val cacheKey = cacheKeyFor(window)
        val startGeneration: Long
        synchronized(cacheLock) {
            iconCache[cacheKey]?.let { return it }
            startGeneration = cacheGeneration
        }

        val resolved = resolveWindowIcon(window)

        synchronized(cacheLock) {
            if (!closed && cacheGeneration == startGeneration) {
                iconCache[cacheKey] = resolved
            }
        }
        return resolved
    }

    /**
     * Non-blocking EDT-safe icon lookup: returns the cached [Icon] immediately on a cache hit,
     * or returns [defaultIcon] and schedules deduplicated background extraction that invokes
     * [onResolved] via [uiExecutor] once cached.
     */
    fun getIconOrDefault(window: WindowInfo, onResolved: (() -> Unit)? = null): Icon {
        val cacheKey = cacheKeyFor(window)
        val request: InFlightRequest
        synchronized(cacheLock) {
            iconCache[cacheKey]?.let { return it }
            if (closed || !scopeJob.isActive) {
                return defaultIcon
            }
            val existing = inFlightRequests[cacheKey]
            if (existing != null) {
                if (onResolved != null) {
                    existing.callbacks.add(onResolved)
                }
                return defaultIcon
            }
            val callbacks = mutableListOf<() -> Unit>()
            if (onResolved != null) {
                callbacks.add(onResolved)
            }
            request = InFlightRequest(generation = cacheGeneration, callbacks = callbacks)
            inFlightRequests[cacheKey] = request
        }

        extractorScope.launch {
            var resolved: Icon? = null
            try {
                resolved = resolveWindowIcon(window)
            } finally {
                val callbacksToInvoke: List<() -> Unit> = synchronized(cacheLock) {
                    if (inFlightRequests[cacheKey] === request) {
                        inFlightRequests.remove(cacheKey)
                    }
                    if (resolved != null && !closed && cacheGeneration == request.generation) {
                        iconCache[cacheKey] = resolved
                        request.callbacks.toList()
                    } else {
                        emptyList()
                    }
                }
                if (callbacksToInvoke.isNotEmpty()) {
                    uiExecutor(
                        Runnable {
                            for (callback in callbacksToInvoke) {
                                callback()
                            }
                        },
                    )
                }
            }
        }

        return defaultIcon
    }

    /**
     * Clears all cached icons and invalidates any in-flight background completions from earlier generations.
     */
    fun clearCache() {
        synchronized(cacheLock) {
            cacheGeneration++
            iconCache.clear()
            inFlightRequests.clear()
        }
    }

    override fun close() {
        synchronized(cacheLock) {
            if (closed) {
                return
            }
            closed = true
            inFlightRequests.clear()
        }
        scopeJob.cancel()
    }

    internal fun peekCachedIcon(window: WindowInfo): Icon? = synchronized(cacheLock) {
        iconCache[cacheKeyFor(window)]
    }

    private fun cacheKeyFor(window: WindowInfo): String =
        window.exePath.lowercase().ifBlank { "hwnd:${window.hwnd.value}" }

    private fun resolveWindowIcon(window: WindowInfo): Icon {
        val extracted = try {
            if (customExtractor != null) {
                customExtractor.invoke(window)
            } else {
                extractFromHwnd(window.hwnd) ?: extractFromFileSystem(window.exePath)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return extracted ?: defaultIcon
    }

    private class InFlightRequest(
        val generation: Long,
        val callbacks: MutableList<() -> Unit>,
    )

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

        var hbmMask = MemorySegment.NULL
        var hbmColor = MemorySegment.NULL
        var hdc = MemorySegment.NULL
        try {
            hbmMask = Win32Bindings.ICONINFO_HBM_MASK.get(iconInfo) as MemorySegment
            hbmColor = Win32Bindings.ICONINFO_HBM_COLOR.get(iconInfo) as MemorySegment
            val bitmapToRead = if (hbmColor != MemorySegment.NULL) hbmColor else hbmMask
            if (bitmapToRead == MemorySegment.NULL) {
                return null
            }

            val bitmapStruct = arena.allocate(Win32Bindings.BITMAP_LAYOUT)
            val gotObj = Win32Bindings.GetObjectW.invokeExact(
                bitmapToRead,
                Win32Bindings.BITMAP_LAYOUT.byteSize().toInt(),
                bitmapStruct,
            ) as Int
            if (gotObj == 0) {
                return null
            }

            val width = Win32Bindings.BITMAP_BM_WIDTH.get(bitmapStruct) as Int
            val height = Win32Bindings.BITMAP_BM_HEIGHT.get(bitmapStruct) as Int
            if (width <= 0 || height <= 0 || width > 256 || height > 256) {
                return null
            }

            hdc = Win32Bindings.CreateCompatibleDC.invokeExact(MemorySegment.NULL) as MemorySegment
            if (hdc == MemorySegment.NULL) {
                return null
            }

            val bmi = arena.allocate(Win32Bindings.BITMAPINFOHEADER_LAYOUT)
            Win32Bindings.BMIH_BI_SIZE.set(bmi, Win32Bindings.BITMAPINFOHEADER_LAYOUT.byteSize().toInt())
            Win32Bindings.BMIH_BI_WIDTH.set(bmi, width)
            Win32Bindings.BMIH_BI_HEIGHT.set(bmi, -height) // Top-down DIB
            Win32Bindings.BMIH_BI_PLANES.set(bmi, 1.toShort())
            Win32Bindings.BMIH_BI_BIT_COUNT.set(bmi, 32.toShort())
            Win32Bindings.BMIH_BI_COMPRESSION.set(bmi, Win32Bindings.BI_RGB)

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
        return try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal fun scaleImage(source: BufferedImage, size: Int): BufferedImage {
        val target = size.coerceAtLeast(1)
        var current = source
        var currentWidth = source.width.coerceAtLeast(1)
        var currentHeight = source.height.coerceAtLeast(1)

        if (currentWidth == target && currentHeight == target && current.type == BufferedImage.TYPE_INT_ARGB) {
            return current
        }

        while (currentWidth > target * 2 || currentHeight > target * 2) {
            val nextWidth = if (currentWidth > target * 2) (currentWidth / 2).coerceAtLeast(target) else currentWidth
            val nextHeight = if (currentHeight > target * 2) (currentHeight / 2).coerceAtLeast(target) else currentHeight
            val stepImage = BufferedImage(nextWidth, nextHeight, BufferedImage.TYPE_INT_ARGB)
            val g2 = stepImage.createGraphics()
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.drawImage(current, 0, 0, nextWidth, nextHeight, null)
            } finally {
                g2.dispose()
            }
            current = stepImage
            currentWidth = nextWidth
            currentHeight = nextHeight
        }

        if (currentWidth == target && currentHeight == target && current.type == BufferedImage.TYPE_INT_ARGB) {
            return current
        }

        val scaled = BufferedImage(target, target, BufferedImage.TYPE_INT_ARGB)
        val g2 = scaled.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.drawImage(current, 0, 0, target, target, null)
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
