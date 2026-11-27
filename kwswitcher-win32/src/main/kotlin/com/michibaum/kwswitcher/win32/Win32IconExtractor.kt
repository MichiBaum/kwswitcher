package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.core.domain.WindowHandle
import com.michibaum.kwswitcher.core.domain.WindowInfo
import com.michibaum.kwswitcher.core.port.WindowIconPort
import com.michibaum.kwswitcher.win32.bindings.GdiIconNative
import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import com.michibaum.kwswitcher.win32.bindings.Win32Structs
import io.github.oshai.kotlinlogging.KotlinLogging
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

private val logger = KotlinLogging.logger {}

/**
 * Extracts and caches native Win32 application icons (`HICON` -> `BufferedImage` -> [ImageIcon])
 * using `SendMessageTimeoutW(WM_GETICON, SMTO_ABORTIFHUNG, 50ms)`, `GetClassLongPtrW`, and `gdi32.dll` (`FR-4`, `DX-5`).
 */
class Win32IconExtractor internal constructor(
    private val maxCacheEntries: Int,
    private val targetSize: Int,
    parentScope: CoroutineScope?,
    ioDispatcher: CoroutineDispatcher,
    private val uiExecutor: (Runnable) -> Unit,
    private val customExtractor: ((WindowInfo) -> Icon?)?,
) : WindowIconPort {

    constructor(
        maxCacheEntries: Int = DEFAULT_CACHE_CAPACITY,
        targetSize: Int = DEFAULT_ICON_SIZE,
        parentScope: CoroutineScope? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(IO_PARALLELISM),
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

    private val iconCache = object : LinkedHashMap<String, Icon>(maxCacheEntries, LRU_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Icon>?): Boolean = size > maxCacheEntries
    }

    override val defaultIcon: Icon by lazy { createDefaultAppIcon(targetSize) }

    override fun getIcon(window: WindowInfo): Icon {
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

    override fun getIconOrDefault(window: WindowInfo, onResolved: (() -> Unit)?): Icon {
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

        scheduleBackgroundExtraction(cacheKey = cacheKey, window = window, request = request)
        return defaultIcon
    }

    private fun scheduleBackgroundExtraction(cacheKey: String, window: WindowInfo, request: InFlightRequest) {
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
    }

    override fun clearCache() {
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

    @Suppress("TooGenericExceptionCaught")
    private fun resolveWindowIcon(window: WindowInfo): Icon {
        val extracted = try {
            if (customExtractor != null) {
                customExtractor.invoke(window)
            } else {
                extractFromHwnd(window.hwnd) ?: extractFromFileSystem(window.exePath)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            logger.debug(e) { "Failed resolving icon for '${window.processName}' (${window.exePath}); using fallback" }
            null
        }
        return extracted ?: defaultIcon
    }

    internal fun extractFromHwnd(handle: WindowHandle): Icon? {
        if (!handle.isValid) return null
        val hwnd = MemorySegment.ofAddress(handle.value)

        Arena.ofConfined().use { arena ->
            val hIconAddress = HIconDibDecoder.queryWindowIconHandle(arena = arena, hwnd = hwnd)
            if (hIconAddress == 0L) {
                return null
            }
            val image = HIconDibDecoder.convertHIconToImage(
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

    @Suppress("TooGenericExceptionCaught")
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            logger.debug(e) { "Filesystem icon fallback failed for '$exePath'" }
            null
        }
    }

    internal fun scaleImage(source: BufferedImage, size: Int): BufferedImage =
        HIconDibDecoder.scaleImage(source = source, size = size)

    private class InFlightRequest(val generation: Long, val callbacks: MutableList<() -> Unit>)

    companion object {
        const val DEFAULT_CACHE_CAPACITY: Int = 128
        const val DEFAULT_ICON_SIZE: Int = 20
        private const val IO_PARALLELISM: Int = 4
        private const val LRU_LOAD_FACTOR: Float = 0.75f

        private const val DEFAULT_TILE_R: Int = 0x3B
        private const val DEFAULT_TILE_G: Int = 0x82
        private const val DEFAULT_TILE_B: Int = 0xF6
        private const val DEFAULT_ACCENT_R: Int = 0xDB
        private const val DEFAULT_ACCENT_G: Int = 0xEA
        private const val DEFAULT_ACCENT_B: Int = 0xFE
        private const val DEFAULT_CORNER_ARC: Int = 5
        private const val DEFAULT_LINE_Y: Int = 6
        private const val DEFAULT_RIGHT_INSET: Int = 5
        private const val DEFAULT_STROKE_WIDTH: Float = 1.5f

        internal fun createDefaultAppIcon(size: Int): ImageIcon {
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g2 = image.createGraphics()
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(DEFAULT_TILE_R, DEFAULT_TILE_G, DEFAULT_TILE_B)
                g2.fillRoundRect(1, 2, size - 2, size - 4, DEFAULT_CORNER_ARC, DEFAULT_CORNER_ARC)
                g2.color = Color(DEFAULT_ACCENT_R, DEFAULT_ACCENT_G, DEFAULT_ACCENT_B)
                g2.stroke = BasicStroke(DEFAULT_STROKE_WIDTH)
                g2.drawLine(4, DEFAULT_LINE_Y, size - DEFAULT_RIGHT_INSET, DEFAULT_LINE_Y)
            } finally {
                g2.dispose()
            }
            return ImageIcon(image)
        }
    }
}

/**
 * Low-level Win32 `HICON` lookup, 32-bit top-down DIB extraction, and multi-step bicubic scaling.
 */
private object HIconDibDecoder {

    private const val ICON_QUERY_TIMEOUT_MS: Int = 50
    private const val MAX_ICON_DIMENSION: Int = 256
    private const val RGB_COLOR_MASK: Int = 0x00FFFFFF
    private const val FULL_ALPHA_MASK: Int = 0xFF shl 24

    fun queryWindowIconHandle(arena: Arena, hwnd: MemorySegment): Long {
        val resultSegment = arena.allocate(ValueLayout.JAVA_LONG)
        val iconTypes = longArrayOf(
            Win32Constants.ICON_SMALL2,
            Win32Constants.ICON_SMALL,
            Win32Constants.ICON_BIG,
        )
        for (iconType in iconTypes) {
            resultSegment.set(ValueLayout.JAVA_LONG, 0L, 0L)
            val ok = GdiIconNative.SendMessageTimeoutW.invokeExact(
                hwnd,
                Win32Constants.WM_GETICON,
                iconType,
                0L,
                Win32Constants.SMTO_ABORTIFHUNG,
                ICON_QUERY_TIMEOUT_MS,
                resultSegment,
            ) as Long
            if (ok != 0L) {
                val hIcon = resultSegment.get(ValueLayout.JAVA_LONG, 0L)
                if (hIcon != 0L) return hIcon
            }
        }

        for (classIndex in intArrayOf(Win32Constants.GCLP_HICONSM, Win32Constants.GCLP_HICON)) {
            val hIcon = GdiIconNative.GetClassLongPtrW.invokeExact(hwnd, classIndex) as Long
            if (hIcon != 0L) return hIcon
        }
        return 0L
    }

    fun convertHIconToImage(arena: Arena, hIcon: MemorySegment): BufferedImage? {
        val iconInfo = arena.allocate(Win32Structs.ICONINFO_LAYOUT)
        if ((GdiIconNative.GetIconInfo.invokeExact(hIcon, iconInfo) as Int) == 0) {
            return null
        }

        var hbmMask = MemorySegment.NULL
        var hbmColor = MemorySegment.NULL
        var hdc = MemorySegment.NULL
        try {
            hbmMask = Win32Structs.ICONINFO_HBM_MASK.get(iconInfo) as MemorySegment
            hbmColor = Win32Structs.ICONINFO_HBM_COLOR.get(iconInfo) as MemorySegment
            val bitmapToRead = if (hbmColor != MemorySegment.NULL) hbmColor else hbmMask
            if (bitmapToRead == MemorySegment.NULL) {
                return null
            }

            val dimensions = queryBitmapDimensions(arena = arena, bitmap = bitmapToRead) ?: return null
            hdc = GdiIconNative.CreateCompatibleDC.invokeExact(MemorySegment.NULL) as MemorySegment
            if (hdc == MemorySegment.NULL) {
                return null
            }

            return readDibBufferedImage(
                arena = arena,
                hdc = hdc,
                bitmap = bitmapToRead,
                width = dimensions.first,
                height = dimensions.second,
            )
        } finally {
            if (hdc != MemorySegment.NULL) {
                GdiIconNative.DeleteDC.invokeExact(hdc) as Int
            }
            if (hbmMask != MemorySegment.NULL) {
                GdiIconNative.DeleteObject.invokeExact(hbmMask) as Int
            }
            if (hbmColor != MemorySegment.NULL) {
                GdiIconNative.DeleteObject.invokeExact(hbmColor) as Int
            }
        }
    }

    private fun queryBitmapDimensions(arena: Arena, bitmap: MemorySegment): Pair<Int, Int>? {
        val bitmapStruct = arena.allocate(Win32Structs.BITMAP_LAYOUT)
        val gotObj = GdiIconNative.GetObjectW.invokeExact(
            bitmap,
            Win32Structs.BITMAP_LAYOUT.byteSize().toInt(),
            bitmapStruct,
        ) as Int
        if (gotObj == 0) {
            return null
        }
        val width = Win32Structs.BITMAP_BM_WIDTH.get(bitmapStruct) as Int
        val height = Win32Structs.BITMAP_BM_HEIGHT.get(bitmapStruct) as Int
        if (width <= 0 || height <= 0 || width > MAX_ICON_DIMENSION || height > MAX_ICON_DIMENSION) {
            return null
        }
        return width to height
    }

    private fun readDibBufferedImage(
        arena: Arena,
        hdc: MemorySegment,
        bitmap: MemorySegment,
        width: Int,
        height: Int,
    ): BufferedImage? {
        val bmi = arena.allocate(Win32Structs.BITMAPINFOHEADER_LAYOUT)
        Win32Structs.BMIH_BI_SIZE.set(bmi, Win32Structs.BITMAPINFOHEADER_LAYOUT.byteSize().toInt())
        Win32Structs.BMIH_BI_WIDTH.set(bmi, width)
        Win32Structs.BMIH_BI_HEIGHT.set(bmi, -height)
        Win32Structs.BMIH_BI_PLANES.set(bmi, 1.toShort())
        Win32Structs.BMIH_BI_BIT_COUNT.set(bmi, 32.toShort())
        Win32Structs.BMIH_BI_COMPRESSION.set(bmi, Win32Constants.BI_RGB)

        val pixelCount = width * height
        val pixelsSegment = arena.allocate(ValueLayout.JAVA_INT, pixelCount.toLong())
        val linesCopied = GdiIconNative.GetDIBits.invokeExact(
            hdc,
            bitmap,
            0,
            height,
            pixelsSegment,
            bmi,
            Win32Constants.DIB_RGB_COLORS,
        ) as Int
        if (linesCopied <= 0) {
            return null
        }

        val argb = normalizeAlphaChannel(pixelsSegment = pixelsSegment, pixelCount = pixelCount) ?: return null
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, argb, 0, width)
        return image
    }

    private fun normalizeAlphaChannel(pixelsSegment: MemorySegment, pixelCount: Int): IntArray? {
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
                if ((argb[i] and RGB_COLOR_MASK) != 0) {
                    argb[i] = argb[i] or FULL_ALPHA_MASK
                    anyVisible = true
                }
            }
            if (!anyVisible) return null
        }
        return argb
    }

    fun scaleImage(source: BufferedImage, size: Int): BufferedImage {
        val target = size.coerceAtLeast(1)
        var current = source
        var currentWidth = source.width.coerceAtLeast(1)
        var currentHeight = source.height.coerceAtLeast(1)

        if (currentWidth == target && currentHeight == target && current.type == BufferedImage.TYPE_INT_ARGB) {
            return current
        }

        while (currentWidth > target * 2 || currentHeight > target * 2) {
            val nextWidth = if (currentWidth > target * 2) (currentWidth / 2).coerceAtLeast(target) else currentWidth
            val nextHeight =
                if (currentHeight > target * 2) (currentHeight / 2).coerceAtLeast(target) else currentHeight
            current = drawResizedStep(
                source = current,
                width = nextWidth,
                height = nextHeight,
                interpolation = RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            currentWidth = nextWidth
            currentHeight = nextHeight
        }

        if (currentWidth == target && currentHeight == target && current.type == BufferedImage.TYPE_INT_ARGB) {
            return current
        }
        return drawResizedStep(
            source = current,
            width = target,
            height = target,
            interpolation = RenderingHints.VALUE_INTERPOLATION_BICUBIC,
        )
    }

    private fun drawResizedStep(source: BufferedImage, width: Int, height: Int, interpolation: Any): BufferedImage {
        val dest = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g2 = dest.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.drawImage(source, 0, 0, width, height, null)
        } finally {
            g2.dispose()
        }
        return dest
    }
}
