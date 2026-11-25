package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.config.HotkeyConfig
import com.michibaum.kwswitcher.config.SwitcherConfig
import com.michibaum.kwswitcher.domain.WindowHandle
import com.michibaum.kwswitcher.domain.WindowInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.ImageIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Win32EngineTest {

    @Test
    fun `Panama FFM struct layouts match 64-bit Windows ABI sizes`() {
        assertEquals(24L, Win32Bindings.KBDLLHOOKSTRUCT_LAYOUT.byteSize())
        assertEquals(48L, Win32Bindings.MSG_LAYOUT.byteSize())
        assertEquals(40L, Win32Bindings.INPUT_LAYOUT.byteSize())
        assertEquals(8L, Win32Bindings.POINT_LAYOUT.byteSize())
        assertEquals(16L, Win32Bindings.RECT_LAYOUT.byteSize())
        assertEquals(40L, Win32Bindings.MONITORINFO_LAYOUT.byteSize())
        assertEquals(32L, Win32Bindings.ICONINFO_LAYOUT.byteSize())
        assertEquals(32L, Win32Bindings.BITMAP_LAYOUT.byteSize())
        assertEquals(40L, Win32Bindings.BITMAPINFOHEADER_LAYOUT.byteSize())
    }

    @Test
    fun `Panama VarHandles read and write exact 64-bit Windows ABI byte offsets`() {
        Arena.ofConfined().use { arena ->
            // KBDLLHOOKSTRUCT: vkCode@0, flags@8
            val kbd = arena.allocate(Win32Bindings.KBDLLHOOKSTRUCT_LAYOUT)
            Win32Bindings.KBDLL_VK_CODE.set(kbd, Win32Bindings.VK_TAB)
            Win32Bindings.KBDLL_FLAGS.set(kbd, Win32Bindings.LLKHF_ALTDOWN)
            assertEquals(Win32Bindings.VK_TAB, kbd.get(ValueLayout.JAVA_INT, 0L))
            assertEquals(Win32Bindings.LLKHF_ALTDOWN, kbd.get(ValueLayout.JAVA_INT, 8L))
            assertEquals(Win32Bindings.VK_TAB, Win32Bindings.KBDLL_VK_CODE.get(kbd) as Int)
            assertEquals(Win32Bindings.LLKHF_ALTDOWN, Win32Bindings.KBDLL_FLAGS.get(kbd) as Int)
            KeyboardHook().use { hook ->
                val hookResult = hook.handleLowLevelKey(
                    nCode = Win32Bindings.HC_ACTION,
                    wParam = Win32Bindings.WM_SYSKEYDOWN.toLong(),
                    lParam = kbd,
                )
                assertEquals(1L, hookResult)
            }

            // POINT: x@0, y@4 + alignment-safe negative coordinate packing
            val pt = arena.allocate(Win32Bindings.POINT_LAYOUT)
            Win32Bindings.POINT_X.set(pt, -1920)
            Win32Bindings.POINT_Y.set(pt, -100)
            assertEquals(-1920, pt.get(ValueLayout.JAVA_INT, 0L))
            assertEquals(-100, pt.get(ValueLayout.JAVA_INT, 4L))
            assertEquals(-1920, Win32Bindings.POINT_X.get(pt) as Int)
            assertEquals(-100, Win32Bindings.POINT_Y.get(pt) as Int)
            val packed = WindowEnumerator.packPoint(x = -1920, y = -100)
            assertEquals(-1920, packed.toInt())
            assertEquals(-100, (packed ushr 32).toInt())

            // MONITORINFO: cbSize@0, rcWork@(20, 24, 28, 32), dwFlags@36
            val mi = arena.allocate(Win32Bindings.MONITORINFO_LAYOUT)
            Win32Bindings.MONITORINFO_CB_SIZE.set(mi, 40)
            Win32Bindings.MONITORINFO_WORK_LEFT.set(mi, 10)
            Win32Bindings.MONITORINFO_WORK_TOP.set(mi, 20)
            Win32Bindings.MONITORINFO_WORK_RIGHT.set(mi, 1930)
            Win32Bindings.MONITORINFO_WORK_BOTTOM.set(mi, 1060)
            Win32Bindings.MONITORINFO_DW_FLAGS.set(mi, Win32Bindings.MONITORINFOF_PRIMARY)
            assertEquals(40, mi.get(ValueLayout.JAVA_INT, 0L))
            assertEquals(10, mi.get(ValueLayout.JAVA_INT, 20L))
            assertEquals(20, mi.get(ValueLayout.JAVA_INT, 24L))
            assertEquals(1930, mi.get(ValueLayout.JAVA_INT, 28L))
            assertEquals(1060, mi.get(ValueLayout.JAVA_INT, 32L))
            assertEquals(Win32Bindings.MONITORINFOF_PRIMARY, mi.get(ValueLayout.JAVA_INT, 36L))
            assertEquals(40, Win32Bindings.MONITORINFO_CB_SIZE.get(mi) as Int)
            assertEquals(10, Win32Bindings.MONITORINFO_WORK_LEFT.get(mi) as Int)
            assertEquals(20, Win32Bindings.MONITORINFO_WORK_TOP.get(mi) as Int)
            assertEquals(1930, Win32Bindings.MONITORINFO_WORK_RIGHT.get(mi) as Int)
            assertEquals(1060, Win32Bindings.MONITORINFO_WORK_BOTTOM.get(mi) as Int)
            assertEquals(Win32Bindings.MONITORINFOF_PRIMARY, Win32Bindings.MONITORINFO_DW_FLAGS.get(mi) as Int)

            // ICONINFO: hbmMask@16, hbmColor@24 (after 4-byte padding on 64-bit Windows)
            val iconInfo = arena.allocate(Win32Bindings.ICONINFO_LAYOUT)
            val maskPtr = MemorySegment.ofAddress(0x1111_2222L)
            val colorPtr = MemorySegment.ofAddress(0x3333_4444L)
            Win32Bindings.ICONINFO_HBM_MASK.set(iconInfo, maskPtr)
            Win32Bindings.ICONINFO_HBM_COLOR.set(iconInfo, colorPtr)
            assertEquals(maskPtr, iconInfo.get(ValueLayout.ADDRESS, 16L))
            assertEquals(colorPtr, iconInfo.get(ValueLayout.ADDRESS, 24L))
            assertEquals(maskPtr, Win32Bindings.ICONINFO_HBM_MASK.get(iconInfo) as MemorySegment)
            assertEquals(colorPtr, Win32Bindings.ICONINFO_HBM_COLOR.get(iconInfo) as MemorySegment)

            // BITMAP: bmWidth@4, bmHeight@8
            val bmp = arena.allocate(Win32Bindings.BITMAP_LAYOUT)
            Win32Bindings.BITMAP_BM_WIDTH.set(bmp, 32)
            Win32Bindings.BITMAP_BM_HEIGHT.set(bmp, 32)
            assertEquals(32, bmp.get(ValueLayout.JAVA_INT, 4L))
            assertEquals(32, bmp.get(ValueLayout.JAVA_INT, 8L))
            assertEquals(32, Win32Bindings.BITMAP_BM_WIDTH.get(bmp) as Int)
            assertEquals(32, Win32Bindings.BITMAP_BM_HEIGHT.get(bmp) as Int)

            // BITMAPINFOHEADER: biSize@0, biWidth@4, biHeight@8, biPlanes@12, biBitCount@14, biCompression@16
            val bmi = arena.allocate(Win32Bindings.BITMAPINFOHEADER_LAYOUT)
            Win32Bindings.BMIH_BI_SIZE.set(bmi, 40)
            Win32Bindings.BMIH_BI_WIDTH.set(bmi, 24)
            Win32Bindings.BMIH_BI_HEIGHT.set(bmi, -24)
            Win32Bindings.BMIH_BI_PLANES.set(bmi, 1.toShort())
            Win32Bindings.BMIH_BI_BIT_COUNT.set(bmi, 32.toShort())
            Win32Bindings.BMIH_BI_COMPRESSION.set(bmi, Win32Bindings.BI_RGB)
            assertEquals(40, bmi.get(ValueLayout.JAVA_INT, 0L))
            assertEquals(24, bmi.get(ValueLayout.JAVA_INT, 4L))
            assertEquals(-24, bmi.get(ValueLayout.JAVA_INT, 8L))
            assertEquals(1.toShort(), bmi.get(ValueLayout.JAVA_SHORT, 12L))
            assertEquals(32.toShort(), bmi.get(ValueLayout.JAVA_SHORT, 14L))
            assertEquals(Win32Bindings.BI_RGB, bmi.get(ValueLayout.JAVA_INT, 16L))
            assertEquals(40, Win32Bindings.BMIH_BI_SIZE.get(bmi) as Int)
            assertEquals(24, Win32Bindings.BMIH_BI_WIDTH.get(bmi) as Int)
            assertEquals(-24, Win32Bindings.BMIH_BI_HEIGHT.get(bmi) as Int)
            assertEquals(1.toShort(), Win32Bindings.BMIH_BI_PLANES.get(bmi) as Short)
            assertEquals(32.toShort(), Win32Bindings.BMIH_BI_BIT_COUNT.get(bmi) as Short)
            assertEquals(Win32Bindings.BI_RGB, Win32Bindings.BMIH_BI_COMPRESSION.get(bmi) as Int)

            // INPUT[2] array: stride 40, type@0, wVk@8, dwFlags@12
            val inputs = arena.allocate(Win32Bindings.INPUT_LAYOUT, 2L)
            Win32Bindings.INPUT_ARR_TYPE.set(inputs, 0L, Win32Bindings.INPUT_KEYBOARD)
            Win32Bindings.INPUT_ARR_W_VK.set(inputs, 0L, Win32Bindings.VK_MENU.toShort())
            Win32Bindings.INPUT_ARR_DW_FLAGS.set(inputs, 0L, 0)
            Win32Bindings.INPUT_ARR_TYPE.set(inputs, 1L, Win32Bindings.INPUT_KEYBOARD)
            Win32Bindings.INPUT_ARR_W_VK.set(inputs, 1L, Win32Bindings.VK_MENU.toShort())
            Win32Bindings.INPUT_ARR_DW_FLAGS.set(inputs, 1L, Win32Bindings.KEYEVENTF_KEYUP)

            assertEquals(Win32Bindings.INPUT_KEYBOARD, inputs.get(ValueLayout.JAVA_INT, 0L))
            assertEquals(Win32Bindings.VK_MENU.toShort(), inputs.get(ValueLayout.JAVA_SHORT, 8L))
            assertEquals(0, inputs.get(ValueLayout.JAVA_INT, 12L))
            assertEquals(Win32Bindings.INPUT_KEYBOARD, inputs.get(ValueLayout.JAVA_INT, 40L))
            assertEquals(Win32Bindings.VK_MENU.toShort(), inputs.get(ValueLayout.JAVA_SHORT, 48L))
            assertEquals(Win32Bindings.KEYEVENTF_KEYUP, inputs.get(ValueLayout.JAVA_INT, 52L))
            assertEquals(Win32Bindings.KEYEVENTF_KEYUP, Win32Bindings.INPUT_ARR_DW_FLAGS.get(inputs, 1L) as Int)
        }
    }

    @Test
    fun `WindowEnumerator extracts process name and enumerates top-level windows`() {
        val enumerator = WindowEnumerator()
        assertEquals("idea64", enumerator.extractProcessName("C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe"))
        assertEquals("Unknown", enumerator.extractProcessName(""))

        val openWindows = enumerator.enumerateOpenWindows()
        // All returned windows must have non-empty titles and valid HWNDs
        for ((index, window) in openWindows.withIndex()) {
            assertTrue(window.hwnd.isValid, "Window HWND should be non-zero")
            assertTrue(window.title.isNotBlank(), "Window title should be non-blank")
            assertEquals(index, window.zOrder, "zOrder should be sequential")
            assertTrue(window.pid > 0, "Window PID should be positive")
        }

        val workArea = enumerator.getActiveMonitorWorkArea()
        assertNotNull(workArea)
        assertTrue(workArea.width > 0 && workArea.height > 0)
    }

    @Test
    fun `WindowEnumerator deduplicates PID path lookups within a pass and isolates caches across passes`() {
        val lookupCounts = HashMap<Int, Int>()
        var simulatedIdeaPath = "C:\\Apps\\idea64.exe"
        val enumerator = WindowEnumerator(
            excludedHwndsProvider = { emptySet() },
            configProvider = { SwitcherConfig() },
            processImagePathResolver = { pid, _ ->
                lookupCounts[pid] = (lookupCounts[pid] ?: 0) + 1
                when (pid) {
                    1001 -> simulatedIdeaPath
                    2002 -> "C:\\Windows\\explorer.exe"
                    else -> ""
                }
            },
        )

        // Pass 1: 5 windows across 2 distinct positive PIDs + invalid PIDs (0 and -1)
        Arena.ofConfined().use { arena ->
            val scratch = WindowEnumerator.ReusableScratch(arena)
            val pass1Cache = HashMap<Int, WindowEnumerator.ProcessMetadata>()

            val firstIdea = enumerator.resolveProcessMetadata(1001, scratch, pass1Cache)
            val explorer1 = enumerator.resolveProcessMetadata(2002, scratch, pass1Cache)
            val secondIdea = enumerator.resolveProcessMetadata(1001, scratch, pass1Cache)
            val thirdIdea = enumerator.resolveProcessMetadata(1001, scratch, pass1Cache)
            val explorer2 = enumerator.resolveProcessMetadata(2002, scratch, pass1Cache)
            val zeroPid = enumerator.resolveProcessMetadata(0, scratch, pass1Cache)
            val negativePid = enumerator.resolveProcessMetadata(-1, scratch, pass1Cache)

            assertEquals(WindowEnumerator.ProcessMetadata("C:\\Apps\\idea64.exe", "idea64"), firstIdea)
            assertSame(firstIdea, secondIdea)
            assertSame(firstIdea, thirdIdea)
            assertEquals(WindowEnumerator.ProcessMetadata("C:\\Windows\\explorer.exe", "explorer"), explorer1)
            assertSame(explorer1, explorer2)
            assertEquals(WindowEnumerator.ProcessMetadata("", "Unknown"), zeroPid)
            assertEquals(WindowEnumerator.ProcessMetadata("", "Unknown"), negativePid)

            assertEquals(1, lookupCounts[1001])
            assertEquals(1, lookupCounts[2002])
            assertEquals(2, lookupCounts.size)
        }

        // Pass 2: fresh per-enumeration cache picks up PID reuse without stale metadata
        simulatedIdeaPath = "C:\\Apps\\firefox.exe"
        Arena.ofConfined().use { arena ->
            val scratch = WindowEnumerator.ReusableScratch(arena)
            val pass2Cache = HashMap<Int, WindowEnumerator.ProcessMetadata>()

            val reusedPidMeta = enumerator.resolveProcessMetadata(1001, scratch, pass2Cache)
            assertEquals(WindowEnumerator.ProcessMetadata("C:\\Apps\\firefox.exe", "firefox"), reusedPidMeta)
            assertEquals(2, lookupCounts[1001])
        }

        // Live enumeration pass: verify each distinct positive PID is queried at most once
        val liveLookups = HashMap<Int, Int>()
        val liveEnumerator = WindowEnumerator(
            excludedHwndsProvider = { emptySet() },
            configProvider = { SwitcherConfig() },
            processImagePathResolver = { pid, scratch ->
                liveLookups[pid] = (liveLookups[pid] ?: 0) + 1
                WindowEnumerator.queryProcessImagePath(pid, scratch)
            },
        )
        liveEnumerator.enumerateOpenWindows()
        for ((pid, count) in liveLookups) {
            assertTrue(pid > 0, "Only positive PIDs should be queried")
            assertEquals(1, count, "PID $pid should be queried at most once per enumerateOpenWindows() call")
        }
    }

    @Test
    fun `IconExtractor resolves and caches icons by executable path`() {
        IconExtractor().use { extractor ->
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
    fun `IconExtractor scaleImage progressively scales icons with bicubic quality and preserves alpha`() {
        IconExtractor().use { extractor ->
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
                assertEquals(20, scaled.width, "Scaled width for ${sourceSize}x$sourceSize")
                assertEquals(20, scaled.height, "Scaled height for ${sourceSize}x$sourceSize")
                assertEquals(BufferedImage.TYPE_INT_ARGB, scaled.type)

                val cornerAlpha = (scaled.getRGB(0, 0) ushr 24) and 0xFF
                val centerAlpha = (scaled.getRGB(10, 10) ushr 24) and 0xFF
                assertEquals(0, cornerAlpha, "Transparent border should stay transparent for ${sourceSize}x$sourceSize")
                assertTrue(centerAlpha > 200, "Opaque center should remain opaque for ${sourceSize}x$sourceSize")
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `IconExtractor getIconOrDefault deduplicates concurrent misses, caches fallback on failure, and dispatches UI callbacks`() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val uiQueue = ArrayDeque<Runnable>()
        val extractionCalls = AtomicInteger(0)
        val customIcon = ImageIcon(BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB))

        IconExtractor(
            maxCacheEntries = 16,
            targetSize = 20,
            parentScope = backgroundScope,
            ioDispatcher = ioDispatcher,
            uiExecutor = { runnable -> uiQueue.addLast(runnable) },
            customExtractor = { window ->
                extractionCalls.incrementAndGet()
                if (window.exePath.contains("broken")) {
                    throw IllegalStateException("Simulated shell failure")
                }
                if (window.exePath.isBlank()) {
                    null
                } else {
                    customIcon
                }
            },
        ).use { extractor ->
            val winA = WindowInfo(
                hwnd = WindowHandle(10L),
                title = "Editor 1",
                processName = "idea64",
                exePath = "C:\\Apps\\idea64.exe",
                zOrder = 0,
            )
            val winB = WindowInfo(
                hwnd = WindowHandle(11L),
                title = "Editor 2",
                processName = "idea64",
                exePath = "c:\\apps\\IDEA64.EXE",
                zOrder = 1,
            )

            var callback1Count = 0
            var callback2Count = 0

            val initial1 = extractor.getIconOrDefault(winA) { callback1Count++ }
            val initial2 = extractor.getIconOrDefault(winB) { callback2Count++ }

            // Immediately returns defaultIcon without running IO on caller thread
            assertSame(extractor.defaultIcon, initial1)
            assertSame(extractor.defaultIcon, initial2)
            assertEquals(0, extractionCalls.get())
            assertEquals(0, uiQueue.size)

            // Execute queued IO coroutines
            testScheduler.advanceUntilIdle()
            assertEquals(1, extractionCalls.get(), "Duplicate exePath requests must share a single extraction")
            assertEquals(1, uiQueue.size, "Callbacks for deduplicated key should batch into one UI dispatch")

            while (uiQueue.isNotEmpty()) {
                uiQueue.removeFirst().run()
            }
            assertEquals(1, callback1Count)
            assertEquals(1, callback2Count)

            // Subsequent call hits cache synchronously with zero extra extractions
            val cached = extractor.getIconOrDefault(winA) { callback1Count++ }
            assertSame(customIcon, cached)
            assertEquals(1, extractionCalls.get())
            assertEquals(1, callback1Count)

            // Broken and blank paths fall back to defaultIcon and cache defaultIcon to prevent retry loops
            val brokenWin = WindowInfo(
                hwnd = WindowHandle(0L),
                title = "Broken",
                processName = "broken",
                exePath = "C:\\broken\\app.exe",
                zOrder = 2,
            )
            assertSame(extractor.defaultIcon, extractor.getIconOrDefault(brokenWin))
            testScheduler.advanceUntilIdle()
            while (uiQueue.isNotEmpty()) {
                uiQueue.removeFirst().run()
            }
            assertSame(extractor.defaultIcon, extractor.peekCachedIcon(brokenWin))
            assertEquals(2, extractionCalls.get())

            // Re-requesting brokenWin hits cached defaultIcon without scheduling another IO job
            assertSame(extractor.defaultIcon, extractor.getIconOrDefault(brokenWin))
            testScheduler.advanceUntilIdle()
            assertEquals(2, extractionCalls.get())
        }
    }

    @Test
    fun `IconExtractor clearCache discards stale in-flight writes and close cancels active jobs`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = executor.asCoroutineDispatcher()
            val startedLatch = CountDownLatch(1)
            val releaseLatch = CountDownLatch(1)
            val finishedLatch = CountDownLatch(1)
            val staleCallbackInvoked = AtomicInteger(0)
            val resolvedIcon = ImageIcon(BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB))

            IconExtractor(
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
                val win = WindowInfo(
                    hwnd = WindowHandle(99L),
                    title = "Slow App",
                    processName = "slow",
                    exePath = "C:\\Apps\\slow.exe",
                    zOrder = 0,
                )

                assertSame(
                    extractor.defaultIcon,
                    extractor.getIconOrDefault(win) { staleCallbackInvoked.incrementAndGet() },
                )
                assertTrue(startedLatch.await(2, TimeUnit.SECONDS))

                // Clear cache while extraction is mid-flight, then allow extraction to finish
                extractor.clearCache()
                releaseLatch.countDown()

                // Submit barrier task on the same single-thread executor to await coroutine completion
                executor.submit { finishedLatch.countDown() }
                assertTrue(finishedLatch.await(2, TimeUnit.SECONDS))

                assertNull(extractor.peekCachedIcon(win), "Stale generation result must not populate iconCache")
                assertEquals(0, staleCallbackInvoked.get(), "Stale generation must not fire UI callback")
            }

            // Verify close() cancels an in-flight extraction cleanly
            val closeStarted = CountDownLatch(1)
            val closeBlock = CountDownLatch(1)
            val extractorToClose = IconExtractor(
                maxCacheEntries = 16,
                targetSize = 20,
                parentScope = null,
                ioDispatcher = dispatcher,
                uiExecutor = { it.run() },
                customExtractor = {
                    closeStarted.countDown()
                    closeBlock.await(2, TimeUnit.SECONDS)
                    resolvedIcon
                },
            )
            val win2 = WindowInfo(
                hwnd = WindowHandle(100L),
                title = "Closing App",
                processName = "closing",
                exePath = "C:\\Apps\\closing.exe",
                zOrder = 0,
            )
            extractorToClose.getIconOrDefault(win2)
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            extractorToClose.close()
            closeBlock.countDown()
            assertNull(extractorToClose.peekCachedIcon(win2))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `SingleInstanceMutex acquires primary lock and rejects duplicate acquisition`() {
        val mutexName = "Local\\kwswitcher-test-mutex-${ProcessHandle.current().pid()}"
        SingleInstanceMutex(mutexName = mutexName).use { first ->
            assertTrue(first.tryAcquire())
            SingleInstanceMutex(mutexName = mutexName).use { second ->
                assertFalse(second.tryAcquire())
            }
        }
    }

    @Test
    fun `StartupRegistry queries writes and deletes HKCU entries via Panama FFM without subprocesses`() {
        val testSubKey = "Software\\kwswitcher-test-${ProcessHandle.current().pid()}"
        val registry = StartupRegistry(subKey = testSubKey, valueName = "kwswitcher-unit-test")
        try {
            assertFalse(registry.isRegistered())
            assertNull(registry.getRegisteredCommand())

            val expectedCommand = "\"C:\\Apps\\kwswitcher\\kwswitcher.exe\""
            assertTrue(registry.setRegistered(enabled = true) { expectedCommand })
            assertTrue(registry.isRegistered())
            assertEquals(expectedCommand, registry.getRegisteredCommand())

            assertTrue(registry.setRegistered(enabled = false))
            assertFalse(registry.isRegistered())
            assertNull(registry.getRegisteredCommand())

            // Deleting an already-absent value remains idempotent
            assertTrue(registry.setRegistered(enabled = false))
        } finally {
            registry.deleteSubKey()
        }
    }

    @Test
    fun `WindowEnumerator reuses cached MethodHandle and single Panama upcall stub across passes`() {
        val enumerator = WindowEnumerator()
        val firstStubAddr = WindowEnumerator.sharedUpcallStubAddress
        assertTrue(firstStubAddr != 0L)

        enumerator.enumerateOpenWindows()
        val secondStubAddr = WindowEnumerator.sharedUpcallStubAddress
        enumerator.enumerateOpenWindows()
        val thirdStubAddr = WindowEnumerator.sharedUpcallStubAddress

        assertEquals(firstStubAddr, secondStubAddr)
        assertEquals(secondStubAddr, thirdStubAddr)
        assertNotNull(WindowEnumerator.ENUM_CALLBACK_HANDLE)
    }

    @Test
    fun `KeyboardHook state machine swallows trigger key when configured modifier is depressed`() {
        KeyboardHook().use { hook ->
            // Plain Tab without Alt is not swallowed
            val swallowedWithoutAlt = hook.processKeyMessage(
                message = Win32Bindings.WM_KEYDOWN,
                vkCode = Win32Bindings.VK_TAB,
                flags = 0,
            )
            assertFalse(swallowedWithoutAlt)

            // Alt key down followed by Tab key down is swallowed
            hook.processKeyMessage(
                message = Win32Bindings.WM_SYSKEYDOWN,
                vkCode = Win32Bindings.VK_LMENU,
                flags = Win32Bindings.LLKHF_ALTDOWN,
            )
            val swallowedWithAlt = hook.processKeyMessage(
                message = Win32Bindings.WM_SYSKEYDOWN,
                vkCode = Win32Bindings.VK_TAB,
                flags = Win32Bindings.LLKHF_ALTDOWN,
            )
            assertTrue(swallowedWithAlt)
        }

        // Custom hotkey: CTRL + SPACE
        KeyboardHook(
            hotkeyConfigProvider = { HotkeyConfig(modifier = "CTRL", triggerKey = "SPACE") },
        ).use { customHook ->
            customHook.processKeyMessage(
                message = Win32Bindings.WM_KEYDOWN,
                vkCode = Win32Bindings.VK_LCONTROL,
                flags = 0,
            )
            val swallowedSpace = customHook.processKeyMessage(
                message = Win32Bindings.WM_KEYDOWN,
                vkCode = Win32Bindings.VK_SPACE,
                flags = 0,
            )
            assertTrue(swallowedSpace)
        }

        // Extended virtual keys (vkCode > 127: VK_RCONTROL = 0xA3, VK_OEM_3 = 0xC0) + letter trigger
        val backtickHotkey = HotkeyConfig(modifier = "ctrl", triggerKey = "backtick", reverseModifier = "shift")
        KeyboardHook(hotkeyConfigProvider = { backtickHotkey }).use { hook ->
            assertFalse(
                hook.processKeyMessage(
                    message = Win32Bindings.WM_KEYDOWN,
                    vkCode = 'A'.code,
                    flags = 0,
                ),
            )
            hook.processKeyMessage(
                message = Win32Bindings.WM_KEYDOWN,
                vkCode = Win32Bindings.VK_RCONTROL,
                flags = 0,
            )
            assertTrue(
                hook.processKeyMessage(
                    message = Win32Bindings.WM_KEYDOWN,
                    vkCode = Win32Bindings.VK_OEM_3,
                    flags = 0,
                ),
            )
        }

        val letterHotkey = HotkeyConfig(modifier = "win", triggerKey = "q")
        KeyboardHook(hotkeyConfigProvider = { letterHotkey }).use { hook ->
            hook.processKeyMessage(
                message = Win32Bindings.WM_KEYDOWN,
                vkCode = Win32Bindings.VK_LWIN,
                flags = 0,
            )
            assertTrue(
                hook.processKeyMessage(
                    message = Win32Bindings.WM_KEYDOWN,
                    vkCode = 'Q'.code,
                    flags = 0,
                ),
            )
        }
    }
}
