package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.core.config.HotkeyConfig
import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import com.michibaum.kwswitcher.core.input.KeyCommandMapper
import com.michibaum.kwswitcher.core.input.KeyGesture
import com.michibaum.kwswitcher.core.input.KeySource
import com.michibaum.kwswitcher.core.input.LogicalKey
import com.michibaum.kwswitcher.core.port.HotkeyHookPort
import com.michibaum.kwswitcher.core.port.OverlayHookSnapshot
import com.michibaum.kwswitcher.win32.bindings.HookNative
import com.michibaum.kwswitcher.win32.bindings.PanamaNative
import com.michibaum.kwswitcher.win32.bindings.Win32Constants
import com.michibaum.kwswitcher.win32.bindings.Win32Structs
import com.michibaum.kwswitcher.win32.diagnostics.Win32ErrorFormatter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Dedicated OS thread running a Win32 message loop (`GetMessageW` / `DispatchMessageW`)
 * with a `WH_KEYBOARD_LL` upcall stub, routing intercepted keystrokes through [KeyCommandMapper]
 * into [SwitcherIntent] events (`FR-1`, `DX-3`).
 */
class Win32KeyboardHook(
    private val hotkeyConfigProvider: () -> HotkeyConfig = { DEFAULT_HOTKEY_CONFIG },
    private val overlayStateProvider: () -> OverlayHookSnapshot = { OverlayHookSnapshot.HIDDEN },
    private val keyStateProvider: (Int) -> Boolean = Companion::isAsyncKeyDown,
) : HotkeyHookPort {

    private val eventChannel = Channel<SwitcherIntent>(Channel.UNLIMITED)
    override val events: Flow<SwitcherIntent> = eventChannel.receiveAsFlow()

    private val running = AtomicBoolean(false)
    private val hookThreadId = AtomicInteger(0)
    private val pendingOpenCount = AtomicInteger(0)

    @Volatile
    private var cachedHotkeySpec: Win32HotkeySpec = Win32HotkeySpec.DEFAULT

    @Volatile
    private var modifierHeld: Boolean = false

    @Volatile
    private var reverseModifierHeld: Boolean = false

    private val modifiers = ModifierTracker(keyStateProvider = keyStateProvider)

    @Volatile
    private var hookThread: Thread? = null

    override fun acknowledgeHotkeyHandled() {
        pendingOpenCount.updateAndGet { current -> if (current > 0) current - 1 else 0 }
    }

    internal fun pollIntent(): SwitcherIntent? = eventChannel.tryReceive().getOrNull()

    override fun start(): Boolean {
        if (!running.compareAndSet(false, true)) {
            return true
        }
        val startedLatch = CountDownLatch(1)
        val installedOk = AtomicBoolean(false)

        val thread = Thread(
            {
                runMessageLoop(startedLatch = startedLatch, installedOk = installedOk)
            },
            "kwswitcher-win32-hook",
        ).apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        hookThread = thread
        startedLatch.await(HOOK_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return installedOk.get()
    }

    private fun runMessageLoop(startedLatch: CountDownLatch, installedOk: AtomicBoolean) {
        val threadId = HookNative.GetCurrentThreadId.invokeExact() as Int
        hookThreadId.set(threadId)

        Arena.ofShared().use { arena ->
            val callback = HookCallback { nCode, wParam, lParam ->
                handleLowLevelKey(nCode = nCode, wParam = wParam, lParam = lParam)
            }
            val upcallStub = PanamaNative.linker.upcallStub(
                HOOK_CALLBACK_HANDLE.bindTo(callback),
                Win32Structs.LOW_LEVEL_KEYBOARD_PROC_DESC,
                arena,
            )

            val moduleHandle = HookNative.GetModuleHandleW.invokeExact(MemorySegment.NULL) as MemorySegment
            val hookHandle = HookNative.SetWindowsHookExW.invokeExact(
                Win32Constants.WH_KEYBOARD_LL,
                upcallStub,
                moduleHandle,
                0,
            ) as MemorySegment

            if (hookHandle == MemorySegment.NULL) {
                val err = Win32ErrorFormatter.describeLastError("SetWindowsHookExW(WH_KEYBOARD_LL)")
                logger.error { "Failed to install WH_KEYBOARD_LL keyboard hook: ${err.detail}" }
                running.set(false)
                startedLatch.countDown()
                return
            }

            installedOk.set(true)
            startedLatch.countDown()
            logger.info { "Installed WH_KEYBOARD_LL keyboard hook on threadId=$threadId" }

            try {
                val msg = arena.allocate(Win32Structs.MSG_LAYOUT)
                while (running.get()) {
                    val result = HookNative.GetMessageW.invokeExact(
                        msg,
                        MemorySegment.NULL,
                        0,
                        0,
                    ) as Int
                    if (result <= 0) {
                        break
                    }
                    HookNative.TranslateMessage.invokeExact(msg) as Int
                    HookNative.DispatchMessageW.invokeExact(msg) as Long
                }
            } finally {
                HookNative.UnhookWindowsHookEx.invokeExact(hookHandle) as Int
                logger.info { "Uninstalled WH_KEYBOARD_LL keyboard hook" }
            }
        }
    }

    internal fun handleLowLevelKey(nCode: Int, wParam: Long, lParam: MemorySegment): Long {
        if (nCode >= Win32Constants.HC_ACTION && lParam != MemorySegment.NULL) {
            val structSegment = lParam.reinterpret(Win32Structs.KBDLLHOOKSTRUCT_LAYOUT.byteSize())
            val flags = Win32Structs.KBDLL_FLAGS.get(structSegment) as Int
            if ((flags and Win32Constants.LLKHF_INJECTED) == 0) {
                val vkCode = Win32Structs.KBDLL_VK_CODE.get(structSegment) as Int
                val shouldSwallow = processKeyMessage(
                    message = wParam.toInt(),
                    vkCode = vkCode,
                    flags = flags,
                )
                if (shouldSwallow) {
                    return 1L
                }
            }
        }
        return HookNative.CallNextHookEx.invokeExact(
            MemorySegment.NULL,
            nCode,
            wParam,
            lParam,
        ) as Long
    }

    internal fun processKeyMessage(message: Int, vkCode: Int, flags: Int): Boolean {
        if ((flags and Win32Constants.LLKHF_INJECTED) != 0) {
            return false
        }
        val isKeyDown = message == Win32Constants.WM_KEYDOWN || message == Win32Constants.WM_SYSKEYDOWN
        val isKeyUp = message == Win32Constants.WM_KEYUP || message == Win32Constants.WM_SYSKEYUP
        if (!isKeyDown && !isKeyUp) {
            return false
        }

        modifiers.update(vkCode = vkCode, isKeyDown = isKeyDown)
        val hotkey = currentHotkeySpec()

        if (hotkey.matchesModifierVk(vkCode)) {
            handlePrimaryModifier(isKeyDown)
            return false
        }
        if (hotkey.matchesReverseModifierVk(vkCode)) {
            reverseModifierHeld = isKeyDown
            return false
        }
        if (!isKeyDown) {
            return false
        }

        val altDownFlag = (flags and Win32Constants.LLKHF_ALTDOWN) != 0
        val triggerModifierDown = modifierHeld || (hotkey.isAltModifier && altDownFlag)
        if (vkCode == hotkey.triggerVkCode && triggerModifierDown) {
            modifierHeld = true
            val reverse = reverseModifierHeld || keyStateProvider(hotkey.reverseModifierPrimaryVk)
            pendingOpenCount.incrementAndGet()
            if (eventChannel.trySend(SwitcherIntent.TriggerHotkey(reverse = reverse)).isFailure) {
                pendingOpenCount.decrementAndGet()
            }
            return true
        }

        return handleActiveOverlayKey(vkCode = vkCode, altDownFlag = altDownFlag, hotkey = hotkey)
    }

    private fun handlePrimaryModifier(isKeyDown: Boolean) {
        if (isKeyDown) {
            modifierHeld = true
        } else if (modifierHeld) {
            modifierHeld = false
            eventChannel.trySend(SwitcherIntent.ReleaseModifier)
        }
    }

    private fun currentHotkeySpec(): Win32HotkeySpec {
        val currentConfig = hotkeyConfigProvider()
        val cached = cachedHotkeySpec
        if (cached.config == currentConfig) {
            return cached
        }
        val updated = Win32HotkeySpec(currentConfig)
        cachedHotkeySpec = updated
        return updated
    }

    private fun handleActiveOverlayKey(vkCode: Int, altDownFlag: Boolean, hotkey: Win32HotkeySpec): Boolean {
        val snapshot = overlayStateProvider()
        if (pendingOpenCount.get() <= 0 && !snapshot.isVisible) {
            return false
        }

        val ctrlDown = modifiers.isCtrlDown()
        val shiftDown = modifiers.isShiftDown()
        val altActive = modifiers.isAnyAltDown(altDownFlag = altDownFlag) ||
            (hotkey.isAltModifier && modifierHeld)
        val gesture = KeyGesture(
            key = toLogicalKey(vkCode),
            ctrl = ctrlDown,
            shift = shiftDown,
            alt = altActive,
            altGr = modifiers.isAltGrActive(ctrlDown = ctrlDown),
            source = KeySource.GLOBAL_HOOK,
        )

        val effectiveQueryEmpty = !snapshot.isVisible || snapshot.isQueryEmpty
        val intent = KeyCommandMapper.mapActiveSessionKey(
            gesture = gesture,
            isQueryEmpty = effectiveQueryEmpty,
            isSpaceTriggerKey = hotkey.isSpaceTrigger,
        ) ?: return false

        eventChannel.trySend(intent)
        return true
    }

    override fun close() {
        pendingOpenCount.set(0)
        if (!running.compareAndSet(true, false)) {
            eventChannel.close()
            return
        }
        val tid = hookThreadId.get()
        if (tid != 0) {
            HookNative.PostThreadMessageW.invokeExact(
                tid,
                Win32Constants.WM_QUIT,
                0L,
                0L,
            ) as Int
        }
        hookThread?.join(THREAD_JOIN_TIMEOUT_MS)
        eventChannel.close()
    }

    private fun interface HookCallback {
        fun invoke(nCode: Int, wParam: Long, lParam: MemorySegment): Long
    }

    private class ModifierTracker(private val keyStateProvider: (Int) -> Boolean) {
        @Volatile
        private var leftCtrl: Boolean = false

        @Volatile
        private var rightCtrl: Boolean = false

        @Volatile
        private var genericCtrl: Boolean = false

        @Volatile
        private var leftShift: Boolean = false

        @Volatile
        private var rightShift: Boolean = false

        @Volatile
        private var genericShift: Boolean = false

        @Volatile
        private var leftAlt: Boolean = false

        @Volatile
        private var rightAlt: Boolean = false

        @Volatile
        private var genericAlt: Boolean = false

        fun update(vkCode: Int, isKeyDown: Boolean) {
            when (vkCode) {
                Win32Constants.VK_LCONTROL -> leftCtrl = isKeyDown
                Win32Constants.VK_RCONTROL -> rightCtrl = isKeyDown
                Win32Constants.VK_CONTROL -> genericCtrl = isKeyDown
                Win32Constants.VK_LSHIFT -> leftShift = isKeyDown
                Win32Constants.VK_RSHIFT -> rightShift = isKeyDown
                Win32Constants.VK_SHIFT -> genericShift = isKeyDown
                Win32Constants.VK_LMENU -> leftAlt = isKeyDown
                Win32Constants.VK_RMENU -> rightAlt = isKeyDown
                Win32Constants.VK_MENU -> genericAlt = isKeyDown
            }
        }

        fun isCtrlDown(): Boolean = leftCtrl || rightCtrl || genericCtrl || keyStateProvider(Win32Constants.VK_CONTROL)

        fun isShiftDown(): Boolean =
            leftShift || rightShift || genericShift || keyStateProvider(Win32Constants.VK_SHIFT)

        fun isAltGrActive(ctrlDown: Boolean): Boolean {
            val leftAltDown = leftAlt || keyStateProvider(Win32Constants.VK_LMENU)
            val rightAltDown = rightAlt || keyStateProvider(Win32Constants.VK_RMENU)
            return rightAltDown && ctrlDown && !leftAltDown
        }

        fun isAnyAltDown(altDownFlag: Boolean): Boolean {
            val trackedAlt = leftAlt || rightAlt || genericAlt || altDownFlag
            return trackedAlt || keyStateProvider(Win32Constants.VK_MENU) ||
                keyStateProvider(Win32Constants.VK_LMENU) || keyStateProvider(Win32Constants.VK_RMENU)
        }
    }

    companion object {
        private const val KEY_DOWN_MASK: Int = 0x8000
        private const val HOOK_STARTUP_TIMEOUT_SECONDS: Long = 3L
        private const val THREAD_JOIN_TIMEOUT_MS: Long = 1000L
        private const val VK_CHAR_A: Int = 'A'.code
        private const val VK_CHAR_Z: Int = 'Z'.code
        private const val VK_DIGIT_0: Int = '0'.code
        private const val VK_DIGIT_9: Int = '9'.code

        private val DEFAULT_HOTKEY_CONFIG = HotkeyConfig()

        private val HOOK_CALLBACK_HANDLE: MethodHandle = MethodHandles.lookup().findVirtual(
            HookCallback::class.java,
            "invoke",
            MethodType.methodType(
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                MemorySegment::class.java,
            ),
        )

        internal fun isAsyncKeyDown(vkCode: Int): Boolean {
            val state = HookNative.GetAsyncKeyState.invokeExact(vkCode) as Short
            return (state.toInt() and KEY_DOWN_MASK) != 0
        }

        internal fun toLogicalKey(vkCode: Int): LogicalKey = when (vkCode) {
            Win32Constants.VK_UP -> LogicalKey.Up

            Win32Constants.VK_DOWN -> LogicalKey.Down

            Win32Constants.VK_TAB -> LogicalKey.Tab

            Win32Constants.VK_RETURN -> LogicalKey.Enter

            Win32Constants.VK_ESCAPE -> LogicalKey.Escape

            Win32Constants.VK_DELETE -> LogicalKey.Delete

            Win32Constants.VK_BACK -> LogicalKey.Backspace

            Win32Constants.VK_SPACE -> LogicalKey.Space

            in VK_CHAR_A..VK_CHAR_Z -> LogicalKey.Character(vkCode.toChar())

            in VK_DIGIT_0..VK_DIGIT_9 -> LogicalKey.Character(vkCode.toChar())

            in Win32Constants.VK_NUMPAD0..Win32Constants.VK_NUMPAD9 ->
                LogicalKey.Character((VK_DIGIT_0 + (vkCode - Win32Constants.VK_NUMPAD0)).toChar())

            else -> LogicalKey.Other
        }
    }
}
