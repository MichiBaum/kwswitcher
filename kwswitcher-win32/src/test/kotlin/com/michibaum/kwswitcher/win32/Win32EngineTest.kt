package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.core.config.HotkeyConfig
import com.michibaum.kwswitcher.core.config.SwitcherConfig
import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import com.michibaum.kwswitcher.core.coordinator.SwitcherMode
import com.michibaum.kwswitcher.core.domain.WindowHandle
import com.michibaum.kwswitcher.core.domain.WindowInfo
import com.michibaum.kwswitcher.core.port.NativeResult
import com.michibaum.kwswitcher.core.port.OverlayHookSnapshot
import com.michibaum.kwswitcher.win32.bindings.PanamaNative
import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import com.michibaum.kwswitcher.win32.bindings.Win32Structs
import com.michibaum.kwswitcher.win32.bindings.WindowControlNative
import com.michibaum.kwswitcher.win32.bindings.WindowEnumNative
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.ImageIcon
import javax.swing.JDialog
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Win32EngineTest {

    @Test
    fun `Panama FFM struct layouts match 64-bit Windows ABI sizes`() {
        assertEquals(24L, Win32Structs.KBDLLHOOKSTRUCT_LAYOUT.byteSize())
        assertEquals(48L, Win32Structs.MSG_LAYOUT.byteSize())
        assertEquals(40L, Win32Structs.INPUT_LAYOUT.byteSize())
        assertEquals(8L, Win32Structs.POINT_LAYOUT.byteSize())
        assertEquals(16L, Win32Structs.RECT_LAYOUT.byteSize())
        assertEquals(40L, Win32Structs.MONITORINFO_LAYOUT.byteSize())
        assertEquals(32L, Win32Structs.ICONINFO_LAYOUT.byteSize())
        assertEquals(32L, Win32Structs.BITMAP_LAYOUT.byteSize())
        assertEquals(40L, Win32Structs.BITMAPINFOHEADER_LAYOUT.byteSize())
    }

    @Test
    fun `Panama VarHandles read and write exact 64-bit Windows ABI byte offsets`() {
        Arena.ofConfined().use { arena ->
            val kbd = arena.allocate(Win32Structs.KBDLLHOOKSTRUCT_LAYOUT)
            Win32Structs.KBDLL_VK_CODE.set(kbd, Win32Constants.VK_TAB)
            Win32Structs.KBDLL_FLAGS.set(kbd, Win32Constants.LLKHF_ALTDOWN)
            assertEquals(Win32Constants.VK_TAB, kbd.get(ValueLayout.JAVA_INT, 0L))
            assertEquals(Win32Constants.LLKHF_ALTDOWN, kbd.get(ValueLayout.JAVA_INT, 8L))
            assertEquals(Win32Constants.VK_TAB, Win32Structs.KBDLL_VK_CODE.get(kbd) as Int)
            assertEquals(Win32Constants.LLKHF_ALTDOWN, Win32Structs.KBDLL_FLAGS.get(kbd) as Int)

            val pt = arena.allocate(Win32Structs.POINT_LAYOUT)
            Win32Structs.POINT_X.set(pt, -1920)
            Win32Structs.POINT_Y.set(pt, -100)
            assertEquals(-1920, pt.get(ValueLayout.JAVA_INT, 0L))
            assertEquals(-100, pt.get(ValueLayout.JAVA_INT, 4L))
            val packed = Win32WindowEnumerator.packPoint(x = -1920, y = -100)
            assertEquals(-1920, packed.toInt())
            assertEquals(-100, (packed ushr 32).toInt())

            val mi = arena.allocate(Win32Structs.MONITORINFO_LAYOUT)
            Win32Structs.MONITORINFO_CB_SIZE.set(mi, 40)
            Win32Structs.MONITORINFO_WORK_LEFT.set(mi, 10)
            Win32Structs.MONITORINFO_WORK_TOP.set(mi, 20)
            Win32Structs.MONITORINFO_WORK_RIGHT.set(mi, 1930)
            Win32Structs.MONITORINFO_WORK_BOTTOM.set(mi, 1060)
            Win32Structs.MONITORINFO_DW_FLAGS.set(mi, Win32Constants.MONITORINFOF_PRIMARY)
            assertEquals(40, mi.get(ValueLayout.JAVA_INT, 0L))
            assertEquals(10, mi.get(ValueLayout.JAVA_INT, 20L))
            assertEquals(20, mi.get(ValueLayout.JAVA_INT, 24L))
            assertEquals(1930, mi.get(ValueLayout.JAVA_INT, 28L))
            assertEquals(1060, mi.get(ValueLayout.JAVA_INT, 32L))
            assertEquals(Win32Constants.MONITORINFOF_PRIMARY, mi.get(ValueLayout.JAVA_INT, 36L))

            val iconInfo = arena.allocate(Win32Structs.ICONINFO_LAYOUT)
            val maskPtr = MemorySegment.ofAddress(0x1111_2222L)
            val colorPtr = MemorySegment.ofAddress(0x3333_4444L)
            Win32Structs.ICONINFO_HBM_MASK.set(iconInfo, maskPtr)
            Win32Structs.ICONINFO_HBM_COLOR.set(iconInfo, colorPtr)
            assertEquals(maskPtr, iconInfo.get(ValueLayout.ADDRESS, 16L))
            assertEquals(colorPtr, iconInfo.get(ValueLayout.ADDRESS, 24L))

            val bmp = arena.allocate(Win32Structs.BITMAP_LAYOUT)
            Win32Structs.BITMAP_BM_WIDTH.set(bmp, 32)
            Win32Structs.BITMAP_BM_HEIGHT.set(bmp, 32)
            assertEquals(32, bmp.get(ValueLayout.JAVA_INT, 4L))
            assertEquals(32, bmp.get(ValueLayout.JAVA_INT, 8L))

            val bmi = arena.allocate(Win32Structs.BITMAPINFOHEADER_LAYOUT)
            Win32Structs.BMIH_BI_SIZE.set(bmi, 40)
            Win32Structs.BMIH_BI_WIDTH.set(bmi, 24)
            Win32Structs.BMIH_BI_HEIGHT.set(bmi, -24)
            Win32Structs.BMIH_BI_PLANES.set(bmi, 1.toShort())
            Win32Structs.BMIH_BI_BIT_COUNT.set(bmi, 32.toShort())
            Win32Structs.BMIH_BI_COMPRESSION.set(bmi, Win32Constants.BI_RGB)
            assertEquals(40, bmi.get(ValueLayout.JAVA_INT, 0L))
            assertEquals(24, bmi.get(ValueLayout.JAVA_INT, 4L))
            assertEquals(-24, bmi.get(ValueLayout.JAVA_INT, 8L))
            assertEquals(1.toShort(), bmi.get(ValueLayout.JAVA_SHORT, 12L))
            assertEquals(32.toShort(), bmi.get(ValueLayout.JAVA_SHORT, 14L))
            assertEquals(Win32Constants.BI_RGB, bmi.get(ValueLayout.JAVA_INT, 16L))

            val inputs = arena.allocate(Win32Structs.INPUT_LAYOUT, 2L)
            Win32Structs.INPUT_ARR_TYPE.set(inputs, 0L, Win32Constants.INPUT_KEYBOARD)
            Win32Structs.INPUT_ARR_W_VK.set(inputs, 0L, Win32Constants.VK_MENU.toShort())
            Win32Structs.INPUT_ARR_DW_FLAGS.set(inputs, 0L, 0)
            Win32Structs.INPUT_ARR_TYPE.set(inputs, 1L, Win32Constants.INPUT_KEYBOARD)
            Win32Structs.INPUT_ARR_W_VK.set(inputs, 1L, Win32Constants.VK_MENU.toShort())
            Win32Structs.INPUT_ARR_DW_FLAGS.set(inputs, 1L, Win32Constants.KEYEVENTF_KEYUP)
            assertEquals(Win32Constants.INPUT_KEYBOARD, inputs.get(ValueLayout.JAVA_INT, 40L))
            assertEquals(Win32Constants.VK_MENU.toShort(), inputs.get(ValueLayout.JAVA_SHORT, 48L))
            assertEquals(Win32Constants.KEYEVENTF_KEYUP, inputs.get(ValueLayout.JAVA_INT, 52L))
        }
    }

    @Test
    fun `Win32IconExtractor scaleImage progressively scales icons with bicubic quality and preserves alpha`() {
        Win32IconExtractor().use { extractor ->
            for (sourceSize in listOf(128, 48, 16, 20)) {
                val src = BufferedImage(sourceSize, sourceSize, BufferedImage.TYPE_INT_ARGB)
                val g = src.createGraphics()
                try {
                    val inset = (sourceSize / 4).coerceAtLeast(2)
                    g.color = Color(0x25, 0x63, 0xEB, 0xFF)
                    g.fillRect(inset, inset, sourceSize - inset * 2, sourceSize - inset * 2)
                } finally {
                    g.dispose()
                }

                val scaled = extractor.scaleImage(src, 20)
                assertEquals(20, scaled.width)
                assertEquals(20, scaled.height)
                assertEquals(BufferedImage.TYPE_INT_ARGB, scaled.type)

                val cornerAlpha = (scaled.getRGB(0, 0) ushr 24) and 0xFF
                val centerAlpha = (scaled.getRGB(10, 10) ushr 24) and 0xFF
                assertEquals(0, cornerAlpha)
                assertTrue(centerAlpha > 200)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Win32IconExtractor getIconOrDefault deduplicates concurrent misses caches fallback and dispatches callbacks`() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val uiQueue = ArrayDeque<Runnable>()
        val extractionCalls = AtomicInteger(0)
        val customIcon = ImageIcon(BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB))

        Win32IconExtractor(
            maxCacheEntries = 16,
            targetSize = 20,
            parentScope = backgroundScope,
            ioDispatcher = ioDispatcher,
            uiExecutor = { runnable -> uiQueue.addLast(runnable) },
            customExtractor = { window ->
                extractionCalls.incrementAndGet()
                if (window.exePath.contains("broken")) {
                    error("Simulated shell failure")
                }
                if (window.exePath.isBlank()) null else customIcon
            },
        ).use { extractor ->
            val winA = WindowInfo(WindowHandle(10L), "Editor 1", "idea64", "C:\\Apps\\idea64.exe", 0)
            val winB = WindowInfo(WindowHandle(11L), "Editor 2", "idea64", "c:\\apps\\IDEA64.EXE", 1)

            var callback1Count = 0
            var callback2Count = 0
            assertSame(extractor.defaultIcon, extractor.getIconOrDefault(winA) { callback1Count++ })
            assertSame(extractor.defaultIcon, extractor.getIconOrDefault(winB) { callback2Count++ })
            assertEquals(0, extractionCalls.get())

            testScheduler.advanceUntilIdle()
            assertEquals(1, extractionCalls.get())
            assertEquals(1, uiQueue.size)

            while (uiQueue.isNotEmpty()) {
                uiQueue.removeFirst().run()
            }
            assertEquals(1, callback1Count)
            assertEquals(1, callback2Count)

            assertSame(customIcon, extractor.getIconOrDefault(winA) { callback1Count++ })
            assertEquals(1, extractionCalls.get())

            val brokenWin = WindowInfo(WindowHandle(0L), "Broken", "broken", "C:\\broken\\app.exe", 2)
            assertSame(extractor.defaultIcon, extractor.getIconOrDefault(brokenWin))
            testScheduler.advanceUntilIdle()
            while (uiQueue.isNotEmpty()) {
                uiQueue.removeFirst().run()
            }
            assertSame(extractor.defaultIcon, extractor.peekCachedIcon(brokenWin))
            assertEquals(2, extractionCalls.get())
        }
    }

    @Test
    fun `Win32IconExtractor clearCache discards stale in-flight writes and close cancels active jobs`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            val startedLatch = CountDownLatch(1)
            val releaseLatch = CountDownLatch(1)
            val finishedLatch = CountDownLatch(1)
            val staleCallbackInvoked = AtomicInteger(0)
            val resolvedIcon = ImageIcon(BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB))

            Win32IconExtractor(
                maxCacheEntries = 16,
                targetSize = 20,
                parentScope = null,
                ioDispatcher = dispatcher,
                uiExecutor = { it.run() },
                customExtractor = {
                    startedLatch.countDown()
                    releaseLatch.await(2, TimeUnit.SECONDS)
                    resolvedIcon
                },
            ).use { extractor ->
                val win = WindowInfo(WindowHandle(99L), "Slow App", "slow", "C:\\Apps\\slow.exe", 0)
                assertSame(
                    extractor.defaultIcon,
                    extractor.getIconOrDefault(win) { staleCallbackInvoked.incrementAndGet() },
                )
                assertTrue(startedLatch.await(2, TimeUnit.SECONDS))

                extractor.clearCache()
                releaseLatch.countDown()
                executor.submit { finishedLatch.countDown() }
                assertTrue(finishedLatch.await(2, TimeUnit.SECONDS))

                assertNull(extractor.peekCachedIcon(win))
                assertEquals(0, staleCallbackInvoked.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun `Win32WindowEnumerator extracts process name deduplicates PIDs and enumerates top-level windows`() {
        val enumerator = Win32WindowEnumerator()
        assertEquals(
            "idea64",
            enumerator.extractProcessName("C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe"),
        )
        assertEquals("Unknown", enumerator.extractProcessName(""))

        val openWindows = enumerator.enumerateOpenWindows()
        for ((index, window) in openWindows.withIndex()) {
            assertTrue(window.hwnd.isValid)
            assertTrue(window.title.isNotBlank())
            assertEquals(index, window.zOrder)
            assertTrue(window.pid > 0)
        }

        val workArea = enumerator.getActiveMonitorWorkArea()
        assertNotNull(workArea)
        assertTrue(workArea.width > 0 && workArea.height > 0)

        val firstStubAddr = Win32WindowEnumerator.sharedUpcallStubAddress
        enumerator.enumerateOpenWindows()
        assertEquals(firstStubAddr, Win32WindowEnumerator.sharedUpcallStubAddress)
        assertNotNull(Win32WindowEnumerator.ENUM_CALLBACK_HANDLE)

        val liveLookups = HashMap<Int, Int>()
        val liveEnumerator = Win32WindowEnumerator(
            excludedHwndsProvider = { emptySet() },
            configProvider = { SwitcherConfig() },
            processImagePathResolver = { pid, scratch ->
                liveLookups[pid] = (liveLookups[pid] ?: 0) + 1
                Win32WindowEnumerator.queryProcessImagePath(pid, scratch)
            },
        )
        liveEnumerator.enumerateOpenWindows()
        for ((pid, count) in liveLookups) {
            assertTrue(pid > 0)
            assertEquals(1, count)
        }
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun `Win32IconExtractor resolves and caches real Windows icons by executable path`() {
        Win32IconExtractor().use { extractor ->
            val win = WindowInfo(
                hwnd = WindowHandle(0L),
                title = "Test Window",
                processName = "notepad",
                exePath = "C:\\Windows\\System32\\notepad.exe",
                zOrder = 0,
            )
            val firstIcon = extractor.getIcon(win)
            val secondIcon = extractor.getIcon(win)
            assertTrue(firstIcon.iconWidth > 0 && firstIcon.iconHeight > 0)
            assertSame(firstIcon, secondIcon)
        }
    }

    @Test
    fun `HIconDibDecoder decodes stacked 2x-height monochrome AND-XOR masks into 1-to-1 ARGB image`() {
        Arena.ofConfined().use { arena ->
            val width = 16
            val iconHeight = 16
            val maskHeight = iconHeight * 2
            val totalPixels = width * maskHeight
            val segment = arena.allocate(ValueLayout.JAVA_INT, totalPixels.toLong())

            // Fill default as transparent (AND = 1 -> 0x00FFFFFF, XOR = 0 -> 0x00000000)
            val iconPixelCount = width * iconHeight
            for (i in 0 until iconPixelCount) {
                segment.setAtIndex(ValueLayout.JAVA_INT, i.toLong(), 0x00FFFFFF)
                segment.setAtIndex(ValueLayout.JAVA_INT, (i + iconPixelCount).toLong(), 0)
            }
            assertNull(HIconDibDecoder.decodeMonochromeMask(segment, width = width, maskHeight = maskHeight))

            // Pixel (1, 1): opaque black (AND = 0, XOR = 0)
            val blackIdx = 1 * width + 1
            segment.setAtIndex(ValueLayout.JAVA_INT, blackIdx.toLong(), 0)
            segment.setAtIndex(ValueLayout.JAVA_INT, (blackIdx + iconPixelCount).toLong(), 0)

            // Pixel (2, 2): opaque white (AND = 0, XOR = 1)
            val whiteIdx = 2 * width + 2
            segment.setAtIndex(ValueLayout.JAVA_INT, whiteIdx.toLong(), 0)
            segment.setAtIndex(ValueLayout.JAVA_INT, (whiteIdx + iconPixelCount).toLong(), 0x00FFFFFF)

            // Pixel (3, 3): inverted pixel rendered as opaque white (AND = 1, XOR = 1)
            val invertIdx = 3 * width + 3
            segment.setAtIndex(ValueLayout.JAVA_INT, invertIdx.toLong(), 0x00FFFFFF)
            segment.setAtIndex(ValueLayout.JAVA_INT, (invertIdx + iconPixelCount).toLong(), 0x00FFFFFF)

            val decoded = HIconDibDecoder.decodeMonochromeMask(segment, width = width, maskHeight = maskHeight)
            assertNotNull(decoded)
            assertEquals(16, decoded.width)
            assertEquals(16, decoded.height)
            assertEquals(0x00000000, decoded.getRGB(0, 0))
            assertEquals(0xFF000000.toInt(), decoded.getRGB(1, 1))
            assertEquals(0xFFFFFFFF.toInt(), decoded.getRGB(2, 2))
            assertEquals(0xFFFFFFFF.toInt(), decoded.getRGB(3, 3))
        }
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun `HIconDibDecoder converts live Win32 monochrome HICON without 2x vertical stretching`() {
        val createIcon = PanamaNative.downcall(
            lookup = PanamaNative.User32Lib.lookup,
            name = "CreateIcon",
            descriptor = FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_BYTE,
                ValueLayout.JAVA_BYTE,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
            ),
        )
        val destroyIcon = PanamaNative.downcall(
            lookup = PanamaNative.User32Lib.lookup,
            name = "DestroyIcon",
            descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )

        Arena.ofConfined().use { arena ->
            // 16x16 1-bpp planes = 2 bytes per row * 16 rows = 32 bytes each
            val planeBytes = 32L
            val andPlane = arena.allocate(planeBytes)
            val xorPlane = arena.allocate(planeBytes)
            andPlane.fill(0xFF.toByte())
            xorPlane.fill(0x00.toByte())
            // Make top row opaque white (AND = 0x00, XOR = 0xFF)
            andPlane.set(ValueLayout.JAVA_BYTE, 0L, 0x00.toByte())
            xorPlane.set(ValueLayout.JAVA_BYTE, 0L, 0xFF.toByte())

            val hIcon = createIcon.invokeExact(
                MemorySegment.NULL,
                16,
                16,
                1.toByte(),
                1.toByte(),
                andPlane,
                xorPlane,
            ) as MemorySegment
            assertTrue(hIcon != MemorySegment.NULL)
            try {
                val image = HIconDibDecoder.convertHIconToImage(arena = arena, hIcon = hIcon)
                assertNotNull(image)
                assertEquals(16, image.width)
                assertEquals(16, image.height)
                assertEquals(0xFFFFFFFF.toInt(), image.getRGB(0, 0))
                assertEquals(0x00000000, image.getRGB(0, 15))
            } finally {
                destroyIcon.invokeExact(hIcon) as Int
            }
        }
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun `Win32WindowEnumerator contains uncaught exceptions inside live EnumWindows native upcall`() {
        var threwConfigOnce = false
        val configThrowingEnumerator = Win32WindowEnumerator(
            configProvider = {
                if (!threwConfigOnce) {
                    threwConfigOnce = true
                    error("Simulated configProvider failure inside EnumWindows upcall")
                }
                SwitcherConfig()
            },
        )
        // Must not abort JVM or propagate exception out of EnumWindows
        val windowsAfterSingleThrow = configThrowingEnumerator.enumerateOpenWindows()
        assertTrue(threwConfigOnce || windowsAfterSingleThrow.isEmpty())

        var threwResolverOnce = false
        val resolverThrowingEnumerator = Win32WindowEnumerator(
            excludedHwndsProvider = { emptySet() },
            configProvider = { SwitcherConfig() },
            processImagePathResolver = { pid, scratch ->
                if (!threwResolverOnce) {
                    threwResolverOnce = true
                    error("Simulated processImagePathResolver failure inside EnumWindows upcall")
                }
                Win32WindowEnumerator.queryProcessImagePath(pid, scratch)
            },
        )
        resolverThrowingEnumerator.enumerateOpenWindows()
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun `Win32SingleInstanceMutex acquires primary lock and isolates dev mutex from production mutex`() {
        val prodMutexName = "Local\\kwswitcher-prod-test-${ProcessHandle.current().pid()}"
        val devMutexName = "Local\\kwswitcher-dev-test-${ProcessHandle.current().pid()}"
        Win32SingleInstanceMutex(mutexName = prodMutexName).use { firstProd ->
            assertTrue(firstProd.tryAcquire())
            Win32SingleInstanceMutex(mutexName = prodMutexName).use { duplicateProd ->
                assertFalse(duplicateProd.tryAcquire())
            }
            Win32SingleInstanceMutex(mutexName = devMutexName).use { parallelDev ->
                assertTrue(parallelDev.tryAcquire())
            }

            // Closing primary unowned mutex from a different thread must cleanly release the OS kernel handle
            val closeThread = Thread { firstProd.close() }
            closeThread.start()
            closeThread.join(2_000)
            assertFalse(closeThread.isAlive)

            Win32SingleInstanceMutex(mutexName = prodMutexName).use { reacquired ->
                assertTrue(reacquired.tryAcquire(), "Mutex should be re-acquirable after cross-thread close()")
            }
        }
    }

    @Test
    fun `Win32StartupRegistry resolveNativeLauncherCommand rejects java launchers and relative paths`() {
        assertNull(Win32StartupRegistry.resolveNativeLauncherCommand(null))
        assertNull(Win32StartupRegistry.resolveNativeLauncherCommand(""))
        assertNull(Win32StartupRegistry.resolveNativeLauncherCommand("kwswitcher.exe"))
        assertNull(Win32StartupRegistry.resolveNativeLauncherCommand("bin\\kwswitcher.exe"))
        assertNull(Win32StartupRegistry.resolveNativeLauncherCommand("\\kwswitcher.exe"))
        assertNull(Win32StartupRegistry.resolveNativeLauncherCommand("javaw.exe"))
        assertNull(
            Win32StartupRegistry.resolveNativeLauncherCommand(
                "C:\\Program Files\\Java\\jdk-25\\bin\\java.exe",
            ),
        )
        assertNull(
            Win32StartupRegistry.resolveNativeLauncherCommand(
                "\"C:\\Program Files\\Java\\jdk-25\\bin\\JAVAW.EXE\"",
            ),
        )
        assertEquals(
            "\"C:\\Tools\\kwswitcher\\kwswitcher.exe\"",
            Win32StartupRegistry.resolveNativeLauncherCommand("C:\\Tools\\kwswitcher\\kwswitcher.exe"),
        )
        assertEquals(
            "\"C:\\Tools\\kwswitcher\\kwswitcher.exe\"",
            Win32StartupRegistry.resolveNativeLauncherCommand("\"C:\\Tools\\kwswitcher\\kwswitcher.exe\""),
        )
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun `Win32StartupRegistry queries writes and deletes HKCU entries via Panama FFM`() {
        val testSubKey = "Software\\kwswitcher-test-${ProcessHandle.current().pid()}"
        val registry = Win32StartupRegistry(subKey = testSubKey, valueName = "kwswitcher-unit-test")
        try {
            assertFalse(registry.isRegistered())
            assertNull(registry.getRegisteredCommand())

            val expectedCommand = "\"C:\\Apps\\kwswitcher\\kwswitcher.exe\""
            assertTrue(registry.setRegistered(enabled = true) { expectedCommand })
            assertTrue(registry.isRegistered())
            assertEquals(expectedCommand, registry.getRegisteredCommand())

            // Unsupported/java.exe launcher refuses setRegistered(true), leaves existing or empty state safe,
            // while setRegistered(false) still cleans up existing entries
            val unsupportedRegistry = Win32StartupRegistry(
                subKey = testSubKey,
                valueName = "kwswitcher-unit-test",
                commandProvider = { null },
            )
            assertFalse(unsupportedRegistry.isSupported)
            assertFalse(unsupportedRegistry.setRegistered(enabled = true))
            assertFalse(
                registry.setRegistered(enabled = true) {
                    "C:\\Program Files\\Java\\jdk-25\\bin\\java.exe"
                },
            )
            assertTrue(unsupportedRegistry.setRegistered(enabled = false))
            assertFalse(registry.isRegistered())
            assertNull(registry.getRegisteredCommand())
            assertTrue(registry.setRegistered(enabled = false))
        } finally {
            registry.deleteSubKey()
        }
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun `Win32WindowActivator resolves caches and invalidates live Swing dialog HWND with NativeResult`() {
        val activator = Win32WindowActivator()
        assertIs<NativeResult.Failed>(activator.activate(WindowHandle(0L)))
        assertFalse(activator.resolveOverlayHandle("").isValid)
        assertIs<NativeResult.Failed>(activator.activateOverlay("kwswitcher-nonexistent-${System.nanoTime()}"))

        assumeFalse(GraphicsEnvironment.isHeadless())

        val previousForeground = WindowEnumNative.GetForegroundWindow.invokeExact() as MemorySegment
        val testTitle = "kwswitcher-overlay-test-${ProcessHandle.current().pid()}-${System.nanoTime()}"
        var dialog: JDialog? = null

        try {
            SwingUtilities.invokeAndWait {
                dialog = JDialog(null as java.awt.Frame?, testTitle, false).apply {
                    isUndecorated = true
                    setBounds(-32000, -32000, 16, 16)
                    isVisible = true
                }
            }

            val firstHandle = activator.resolveOverlayHandle(testTitle)
            assertTrue(firstHandle.isValid)

            val hwndSeg = MemorySegment.ofAddress(firstHandle.value)
            assertTrue((WindowControlNative.IsWindow.invokeExact(hwndSeg) as Int) != 0)
            assertEquals(0, WindowControlNative.IsHungAppWindow.invokeExact(hwndSeg) as Int)
            assertEquals(firstHandle, activator.resolveOverlayHandle(testTitle))

            activator.activateOverlay(testTitle)

            SwingUtilities.invokeAndWait {
                dialog?.dispose()
                dialog = null
            }
            Toolkit.getDefaultToolkit().sync()
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while ((WindowControlNative.IsWindow.invokeExact(hwndSeg) as Int) != 0 &&
                System.nanoTime() < deadlineNanos
            ) {
                Thread.sleep(5)
            }
            assertEquals(0, WindowControlNative.IsWindow.invokeExact(hwndSeg) as Int)
            assertFalse(activator.resolveOverlayHandle(testTitle).isValid)
        } finally {
            if (dialog != null) {
                SwingUtilities.invokeAndWait { dialog?.dispose() }
            }
            if (previousForeground != MemorySegment.NULL) {
                activator.activate(WindowHandle(previousForeground.address()))
            }
        }
    }
}

class Win32KeyboardHookGuardTest {

    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun `Win32KeyboardHook ignores LLKHF_INJECTED synthetic events without altering modifier state`() {
        Arena.ofConfined().use { arena ->
            val kbd = arena.allocate(Win32Structs.KBDLLHOOKSTRUCT_LAYOUT)
            Win32KeyboardHook(keyStateProvider = { false }).use { hook ->
                hook.processKeyMessage(Win32Constants.WM_SYSKEYDOWN, Win32Constants.VK_LMENU, Win32Constants.LLKHF_ALTDOWN)

                Win32Structs.KBDLL_VK_CODE.set(kbd, Win32Constants.VK_UNLOCK_FOREGROUND)
                Win32Structs.KBDLL_FLAGS.set(kbd, Win32Constants.LLKHF_INJECTED)
                hook.handleLowLevelKey(Win32Constants.HC_ACTION, Win32Constants.WM_KEYDOWN.toLong(), kbd)
                hook.handleLowLevelKey(Win32Constants.HC_ACTION, Win32Constants.WM_KEYUP.toLong(), kbd)

                Win32Structs.KBDLL_VK_CODE.set(kbd, Win32Constants.VK_MENU)
                Win32Structs.KBDLL_FLAGS.set(kbd, Win32Constants.LLKHF_INJECTED)
                hook.handleLowLevelKey(Win32Constants.HC_ACTION, Win32Constants.WM_KEYUP.toLong(), kbd)

                assertNull(hook.pollIntent())
                assertTrue(
                    hook.processKeyMessage(
                        message = Win32Constants.WM_SYSKEYDOWN,
                        vkCode = Win32Constants.VK_TAB,
                        flags = Win32Constants.LLKHF_ALTDOWN,
                    ),
                )
                assertEquals(SwitcherIntent.TriggerHotkey(reverse = false), hook.pollIntent())
            }
        }
    }

    @Test
    fun `Win32KeyboardHook routes shortcuts through KeyCommandMapper and guards pending-open chords`() {
        var snapshot = OverlayHookSnapshot(mode = SwitcherMode.HIDDEN, isQueryEmpty = true)
        Win32KeyboardHook(
            overlayStateProvider = { snapshot },
            keyStateProvider = { false },
        ).use { hook ->
            // When HIDDEN, shortcuts pass through untouched
            hook.processKeyMessage(Win32Constants.WM_KEYDOWN, Win32Constants.VK_LCONTROL, 0)
            assertFalse(hook.processKeyMessage(Win32Constants.WM_KEYDOWN, 'W'.code, 0))
            hook.processKeyMessage(Win32Constants.WM_KEYUP, Win32Constants.VK_LCONTROL, 0)
            assertNull(hook.pollIntent())

            // Press Alt+Tab while snapshot is still HIDDEN -> pendingOpenCount guards fast chords
            hook.processKeyMessage(Win32Constants.WM_SYSKEYDOWN, Win32Constants.VK_LMENU, Win32Constants.LLKHF_ALTDOWN)
            assertTrue(
                hook.processKeyMessage(
                    Win32Constants.WM_SYSKEYDOWN,
                    Win32Constants.VK_TAB,
                    Win32Constants.LLKHF_ALTDOWN,
                ),
            )
            hook.processKeyMessage(Win32Constants.WM_KEYDOWN, Win32Constants.VK_LCONTROL, Win32Constants.LLKHF_ALTDOWN)
            assertTrue(hook.processKeyMessage(Win32Constants.WM_KEYDOWN, 'W'.code, Win32Constants.LLKHF_ALTDOWN))
            hook.processKeyMessage(Win32Constants.WM_KEYUP, Win32Constants.VK_LCONTROL, Win32Constants.LLKHF_ALTDOWN)
            assertTrue(hook.processKeyMessage(Win32Constants.WM_SYSKEYDOWN, 'A'.code, Win32Constants.LLKHF_ALTDOWN))

            assertEquals(SwitcherIntent.TriggerHotkey(reverse = false), hook.pollIntent())
            assertEquals(SwitcherIntent.CloseSelected, hook.pollIntent())
            assertEquals(SwitcherIntent.AppendQueryChar('a'), hook.pollIntent())

            hook.acknowledgeHotkeyHandled()
            assertFalse(hook.processKeyMessage(Win32Constants.WM_KEYDOWN, Win32Constants.VK_RETURN, 0))

            // Visible INTERACTIVE_SEARCH with non-empty query: Delete passes through, Ctrl+Shift+W kills, AltGr passes
            snapshot = OverlayHookSnapshot(mode = SwitcherMode.INTERACTIVE_SEARCH, isQueryEmpty = false)
            assertFalse(hook.processKeyMessage(Win32Constants.WM_KEYDOWN, Win32Constants.VK_DELETE, 0))

            hook.processKeyMessage(Win32Constants.WM_KEYUP, Win32Constants.VK_LMENU, 0)
            assertEquals(SwitcherIntent.ReleaseModifier, hook.pollIntent())

            hook.processKeyMessage(Win32Constants.WM_KEYDOWN, Win32Constants.VK_LCONTROL, 0)
            hook.processKeyMessage(Win32Constants.WM_KEYDOWN, Win32Constants.VK_LSHIFT, 0)
            assertTrue(hook.processKeyMessage(Win32Constants.WM_KEYDOWN, 'W'.code, 0))
            assertEquals(SwitcherIntent.KillSelectedProcess, hook.pollIntent())
            hook.processKeyMessage(Win32Constants.WM_KEYUP, Win32Constants.VK_LSHIFT, 0)

            // European AltGr (LCtrl + RAlt) passes through
            hook.processKeyMessage(Win32Constants.WM_SYSKEYDOWN, Win32Constants.VK_RMENU, Win32Constants.LLKHF_ALTDOWN)
            assertFalse(hook.processKeyMessage(Win32Constants.WM_KEYDOWN, 'W'.code, Win32Constants.LLKHF_ALTDOWN))
            assertFalse(hook.processKeyMessage(Win32Constants.WM_KEYDOWN, 'Q'.code, Win32Constants.LLKHF_ALTDOWN))
            assertFalse(hook.processKeyMessage(Win32Constants.WM_KEYDOWN, '2'.code, Win32Constants.LLKHF_ALTDOWN))
            assertNull(hook.pollIntent())
        }
    }

    @Test
    fun `Win32KeyboardHook clamps multi-press pendingOpenCount race window and types digits while Alt is held`() {
        var snapshot = OverlayHookSnapshot(mode = SwitcherMode.HIDDEN, isQueryEmpty = true)
        Win32KeyboardHook(
            overlayStateProvider = { snapshot },
            keyStateProvider = { false },
        ).use { hook ->
            // Press Alt+Tab twice rapidly before coordinator publishes visible snapshot (pendingOpenCount = 2)
            hook.processKeyMessage(Win32Constants.WM_SYSKEYDOWN, Win32Constants.VK_LMENU, Win32Constants.LLKHF_ALTDOWN)
            assertTrue(
                hook.processKeyMessage(
                    Win32Constants.WM_SYSKEYDOWN,
                    Win32Constants.VK_TAB,
                    Win32Constants.LLKHF_ALTDOWN,
                ),
            )
            assertTrue(
                hook.processKeyMessage(
                    Win32Constants.WM_SYSKEYDOWN,
                    Win32Constants.VK_TAB,
                    Win32Constants.LLKHF_ALTDOWN,
                ),
            )
            assertEquals(SwitcherIntent.TriggerHotkey(reverse = false), hook.pollIntent())
            assertEquals(SwitcherIntent.TriggerHotkey(reverse = false), hook.pollIntent())

            // First acknowledge decrements pendingOpenCount from 2 -> 1: keys still route to active session!
            hook.acknowledgeHotkeyHandled()
            assertTrue(hook.processKeyMessage(Win32Constants.WM_SYSKEYDOWN, '5'.code, Win32Constants.LLKHF_ALTDOWN))
            assertTrue(
                hook.processKeyMessage(
                    Win32Constants.WM_SYSKEYDOWN,
                    Win32Constants.VK_NUMPAD7,
                    Win32Constants.LLKHF_ALTDOWN,
                ),
            )
            assertEquals(SwitcherIntent.AppendQueryChar('5'), hook.pollIntent())
            assertEquals(SwitcherIntent.AppendQueryChar('7'), hook.pollIntent())

            // Second acknowledge decrements pendingOpenCount from 1 -> 0; extra acknowledges clamp at 0
            hook.acknowledgeHotkeyHandled()
            hook.acknowledgeHotkeyHandled()
            assertFalse(hook.processKeyMessage(Win32Constants.WM_SYSKEYDOWN, 'A'.code, Win32Constants.LLKHF_ALTDOWN))

            // Once coordinator snapshot reports visible, Alt-held search typing works again
            snapshot = OverlayHookSnapshot(mode = SwitcherMode.QUICK_SWITCH, isQueryEmpty = true)
            assertTrue(hook.processKeyMessage(Win32Constants.WM_SYSKEYDOWN, 'F'.code, Win32Constants.LLKHF_ALTDOWN))
            assertEquals(SwitcherIntent.AppendQueryChar('f'), hook.pollIntent())
        }
    }

    @EnabledOnOs(OS.WINDOWS)
    @Test
    fun `Win32KeyboardHook contains uncaught provider exceptions in upcall and shuts down immediate start-close`() {
        Arena.ofConfined().use { arena ->
            val kbd = arena.allocate(Win32Structs.KBDLLHOOKSTRUCT_LAYOUT)
            Win32Structs.KBDLL_VK_CODE.set(kbd, Win32Constants.VK_TAB)
            Win32Structs.KBDLL_FLAGS.set(kbd, Win32Constants.LLKHF_ALTDOWN)

            Win32KeyboardHook(
                hotkeyConfigProvider = { error("Simulated hotkeyConfigProvider failure") },
                keyStateProvider = { false },
            ).use { throwingConfigHook ->
                // Must catch exception and delegate to CallNextHookEx instead of aborting JVM
                throwingConfigHook.handleLowLevelKey(
                    Win32Constants.HC_ACTION,
                    Win32Constants.WM_SYSKEYDOWN.toLong(),
                    kbd,
                )
            }

            Win32KeyboardHook(
                hotkeyConfigProvider = { HotkeyConfig() },
                overlayStateProvider = { error("Simulated overlayStateProvider failure") },
                keyStateProvider = { false },
            ).use { throwingStateHook ->
                Win32Structs.KBDLL_VK_CODE.set(kbd, 'A'.code)
                throwingStateHook.handleLowLevelKey(
                    Win32Constants.HC_ACTION,
                    Win32Constants.WM_SYSKEYDOWN.toLong(),
                    kbd,
                )
            }
        }

        // Immediate start() followed by close() must exit GetMessageW via pre-created PeekMessageW queue
        val hook = Win32KeyboardHook(keyStateProvider = { false })
        hook.processKeyMessage(Win32Constants.WM_SYSKEYDOWN, Win32Constants.VK_LMENU, Win32Constants.LLKHF_ALTDOWN)
        val startNanos = System.nanoTime()
        try {
            assertTrue(hook.start(), "WH_KEYBOARD_LL hook should install cleanly on Windows")
        } finally {
            hook.close()
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
        assertTrue(elapsedMillis < 900, "Immediate start/close took ${elapsedMillis}ms (expected fast WM_QUIT exit)")

        // Verify modifier state was reset on close() (Tab without Alt held must not trigger hotkey)
        assertFalse(hook.processKeyMessage(Win32Constants.WM_KEYDOWN, Win32Constants.VK_TAB, 0))
    }
}
