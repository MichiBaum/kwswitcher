package com.michibaum.kwswitcher.win32

import com.michibaum.kwswitcher.config.HotkeyConfig
import com.michibaum.kwswitcher.coordinator.SwitcherMode
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

/**
 * Lock-free snapshot of the switcher overlay visibility and query state read on the hook thread.
 */
data class OverlayHookSnapshot(
    val mode: SwitcherMode = SwitcherMode.HIDDEN,
    val isQueryEmpty: Boolean = true,
) {
    val isVisible: Boolean
        get() = mode != SwitcherMode.HIDDEN

    companion object {
        val HIDDEN = OverlayHookSnapshot(mode = SwitcherMode.HIDDEN, isQueryEmpty = true)
    }
}

/**
 * Non-text switcher management and navigation actions intercepted at the `WH_KEYBOARD_LL` level.
 */
sealed interface HookAction {
    data class MoveSelection(val delta: Int, val lockInteractive: Boolean) : HookAction
    data object CloseSelectedWindow : HookAction
    data object MinimizeSelectedWindow : HookAction
    data object KillSelectedProcess : HookAction
    data object ConfirmSelection : HookAction
    data object Dismiss : HookAction
}

/**
 * Events emitted by the low-level Win32 keyboard hook (`WH_KEYBOARD_LL`).
 */
sealed interface HookKeyEvent {
    data class AltTabPressed(val reverse: Boolean) : HookKeyEvent
    data object AltReleased : HookKeyEvent
    data class Action(val action: HookAction) : HookKeyEvent
    data class AppendSearchChar(val char: Char) : HookKeyEvent
    data object BackspaceSearch : HookKeyEvent
}

/**
 * Dedicated OS thread running a Win32 message loop (`GetMessageW` / `DispatchMessageW`)
 * with a `WH_KEYBOARD_LL` upcall stub to intercept the configured switcher hotkey (`FR-1`)
 * and guard active switcher shortcuts against leaking into background windows.
 */
class KeyboardHook(
    private val hotkeyConfigProvider: () -> HotkeyConfig = { DEFAULT_HOTKEY_CONFIG },
    private val overlayStateProvider: () -> OverlayHookSnapshot = { OverlayHookSnapshot() },
    private val keyStateProvider: (Int) -> Boolean = Companion::isAsyncKeyDown,
) : AutoCloseable {
    private val eventChannel = Channel<HookKeyEvent>(Channel.UNLIMITED)
    val events: Flow<HookKeyEvent> = eventChannel.receiveAsFlow()

    private val running = AtomicBoolean(false)
    private val hookThreadId = AtomicInteger(0)
    private val pendingOpenCount = AtomicInteger(0)

    @Volatile
    private var modifierHeld: Boolean = false

    @Volatile
    private var reverseModifierHeld: Boolean = false

    private val modifiers = ModifierTracker(keyStateProvider = keyStateProvider)

    @Volatile
    private var hookThread: Thread? = null

    /**
     * Acknowledges completion of an [HookKeyEvent.AltTabPressed] transition so the pending-open
     * shortcut guard hands over cleanly to [overlayStateProvider].
     */
    fun acknowledgeAltTabHandled() {
        pendingOpenCount.updateAndGet { current -> if (current > 0) current - 1 else 0 }
    }

    internal fun pollEvent(): HookKeyEvent? = eventChannel.tryReceive().getOrNull()

    /**
     * Starts the dedicated Win32 hook thread and installs the `WH_KEYBOARD_LL` hook.
     */
    fun start(): Boolean {
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

    private fun runMessageLoop(
        startedLatch: CountDownLatch,
        installedOk: AtomicBoolean,
    ) {
        val threadId = Win32Bindings.Hook.GetCurrentThreadId.invokeExact() as Int
        hookThreadId.set(threadId)

        Arena.ofShared().use { arena ->
            val callback = HookCallback { nCode, wParam, lParam ->
                handleLowLevelKey(nCode = nCode, wParam = wParam, lParam = lParam)
            }
            val callbackHandle = HOOK_CALLBACK_HANDLE.bindTo(callback)

            val upcallStub = Win32Bindings.linker.upcallStub(
                callbackHandle,
                Win32Bindings.LOW_LEVEL_KEYBOARD_PROC_DESC,
                arena,
            )

            val moduleHandle = Win32Bindings.Hook.GetModuleHandleW.invokeExact(MemorySegment.NULL) as MemorySegment
            val hookHandle = Win32Bindings.Hook.SetWindowsHookExW.invokeExact(
                Win32Bindings.WH_KEYBOARD_LL,
                upcallStub,
                moduleHandle,
                0,
            ) as MemorySegment

            if (hookHandle == MemorySegment.NULL) {
                running.set(false)
                startedLatch.countDown()
                return
            }

            installedOk.set(true)
            startedLatch.countDown()

            try {
                val msg = arena.allocate(Win32Bindings.MSG_LAYOUT)
                while (running.get()) {
                    val result = Win32Bindings.Hook.GetMessageW.invokeExact(
                        msg,
                        MemorySegment.NULL,
                        0,
                        0,
                    ) as Int
                    if (result <= 0) {
                        break
                    }
                    Win32Bindings.Hook.TranslateMessage.invokeExact(msg) as Int
                    Win32Bindings.Hook.DispatchMessageW.invokeExact(msg) as Long
                }
            } finally {
                Win32Bindings.Hook.UnhookWindowsHookEx.invokeExact(hookHandle) as Int
            }
        }
    }

    internal fun handleLowLevelKey(
        nCode: Int,
        wParam: Long,
        lParam: MemorySegment,
    ): Long {
        if (nCode >= Win32Bindings.HC_ACTION && lParam != MemorySegment.NULL) {
            val structSegment = lParam.reinterpret(Win32Bindings.KBDLLHOOKSTRUCT_LAYOUT.byteSize())
            val flags = Win32Bindings.KBDLL_FLAGS.get(structSegment) as Int
            if ((flags and Win32Bindings.LLKHF_INJECTED) == 0) {
                val vkCode = Win32Bindings.KBDLL_VK_CODE.get(structSegment) as Int
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
        return Win32Bindings.Hook.CallNextHookEx.invokeExact(
            MemorySegment.NULL,
            nCode,
            wParam,
            lParam,
        ) as Long
    }

    internal fun processKeyMessage(
        message: Int,
        vkCode: Int,
        flags: Int,
    ): Boolean {
        if ((flags and Win32Bindings.LLKHF_INJECTED) != 0) {
            return false
        }
        val isKeyDown = message == Win32Bindings.WM_KEYDOWN || message == Win32Bindings.WM_SYSKEYDOWN
        val isKeyUp = message == Win32Bindings.WM_KEYUP || message == Win32Bindings.WM_SYSKEYUP
        if (!isKeyDown && !isKeyUp) {
            return false
        }

        modifiers.update(vkCode = vkCode, isKeyDown = isKeyDown)
        val hotkey = hotkeyConfigProvider()

        if (hotkey.matchesModifierVk(vkCode)) {
            if (isKeyDown) {
                modifierHeld = true
            } else if (modifierHeld) {
                modifierHeld = false
                eventChannel.trySend(HookKeyEvent.AltReleased)
            }
            return false
        }

        if (hotkey.matchesReverseModifierVk(vkCode)) {
            reverseModifierHeld = isKeyDown
            return false
        }

        if (!isKeyDown) {
            return false
        }

        val altDownFlag = (flags and Win32Bindings.LLKHF_ALTDOWN) != 0
        val triggerModifierDown = modifierHeld || (hotkey.isAltModifier && altDownFlag)
        if (vkCode == hotkey.triggerVkCode && triggerModifierDown) {
            modifierHeld = true
            val reverse = reverseModifierHeld || keyStateProvider(hotkey.reverseModifierPrimaryVk)
            pendingOpenCount.incrementAndGet()
            if (eventChannel.trySend(HookKeyEvent.AltTabPressed(reverse = reverse)).isFailure) {
                pendingOpenCount.decrementAndGet()
            }
            return true
        }

        return handleActiveOverlayKey(vkCode = vkCode, altDownFlag = altDownFlag, hotkey = hotkey)
    }

    private fun handleActiveOverlayKey(
        vkCode: Int,
        altDownFlag: Boolean,
        hotkey: HotkeyConfig,
    ): Boolean {
        val snapshot = overlayStateProvider()
        if (pendingOpenCount.get() <= 0 && !snapshot.isVisible) {
            return false
        }

        val ctrlDown = modifiers.isCtrlDown()
        val shiftDown = modifiers.isShiftDown()
        val isAltGr = modifiers.isAltGrActive(ctrlDown = ctrlDown)

        val event = if (isAltGr) {
            null
        } else {
            val effectiveQueryEmpty = !snapshot.isVisible || snapshot.isQueryEmpty
            val altActive = modifiers.isAnyAltDown(altDownFlag = altDownFlag) ||
                (hotkey.isAltModifier && modifierHeld)
            matchOverlayAction(
                vkCode = vkCode,
                ctrlDown = ctrlDown,
                shiftDown = shiftDown,
                isQueryEmpty = effectiveQueryEmpty,
            )?.let(HookKeyEvent::Action) ?: resolveAltTypingIfApplicable(
                vkCode = vkCode,
                ctrlDown = ctrlDown,
                shiftDown = shiftDown,
                anyAltDown = altActive,
                triggerVkCode = hotkey.triggerVkCode,
            )
        }

        if (event != null) {
            eventChannel.trySend(event)
        }
        return event != null
    }

    override fun close() {
        pendingOpenCount.set(0)
        if (!running.compareAndSet(true, false)) {
            eventChannel.close()
            return
        }
        val tid = hookThreadId.get()
        if (tid != 0) {
            Win32Bindings.Hook.PostThreadMessageW.invokeExact(
                tid,
                Win32Bindings.WM_QUIT,
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

    private class ModifierTracker(
        private val keyStateProvider: (Int) -> Boolean,
    ) {
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
                Win32Bindings.VK_LCONTROL -> leftCtrl = isKeyDown
                Win32Bindings.VK_RCONTROL -> rightCtrl = isKeyDown
                Win32Bindings.VK_CONTROL -> genericCtrl = isKeyDown
                Win32Bindings.VK_LSHIFT -> leftShift = isKeyDown
                Win32Bindings.VK_RSHIFT -> rightShift = isKeyDown
                Win32Bindings.VK_SHIFT -> genericShift = isKeyDown
                Win32Bindings.VK_LMENU -> leftAlt = isKeyDown
                Win32Bindings.VK_RMENU -> rightAlt = isKeyDown
                Win32Bindings.VK_MENU -> genericAlt = isKeyDown
            }
        }

        fun isCtrlDown(): Boolean =
            leftCtrl || rightCtrl || genericCtrl || keyStateProvider(Win32Bindings.VK_CONTROL)

        fun isShiftDown(): Boolean =
            leftShift || rightShift || genericShift || keyStateProvider(Win32Bindings.VK_SHIFT)

        fun isAltGrActive(ctrlDown: Boolean): Boolean {
            val leftAltDown = leftAlt || keyStateProvider(Win32Bindings.VK_LMENU)
            val rightAltDown = rightAlt || keyStateProvider(Win32Bindings.VK_RMENU)
            return rightAltDown && ctrlDown && !leftAltDown
        }

        fun isAnyAltDown(altDownFlag: Boolean): Boolean {
            val trackedAlt = leftAlt || rightAlt || genericAlt || altDownFlag
            return trackedAlt || keyStateProvider(Win32Bindings.VK_MENU) ||
                keyStateProvider(Win32Bindings.VK_LMENU) || keyStateProvider(Win32Bindings.VK_RMENU)
        }
    }

    companion object {
        private const val KEY_DOWN_MASK: Int = 0x8000
        private const val HOOK_STARTUP_TIMEOUT_SECONDS: Long = 3L
        private const val THREAD_JOIN_TIMEOUT_MS: Long = 1_000L
        private const val VK_CHAR_A: Int = 'A'.code
        private const val VK_CHAR_M: Int = 'M'.code
        private const val VK_CHAR_N: Int = 'N'.code
        private const val VK_CHAR_P: Int = 'P'.code
        private const val VK_CHAR_W: Int = 'W'.code
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
            val state = Win32Bindings.Hook.GetAsyncKeyState.invokeExact(vkCode) as Short
            return (state.toInt() and KEY_DOWN_MASK) != 0
        }

        private fun matchOverlayAction(
            vkCode: Int,
            ctrlDown: Boolean,
            shiftDown: Boolean,
            isQueryEmpty: Boolean,
        ): HookAction? = matchNavigationOrConfirmAction(vkCode = vkCode, shiftDown = shiftDown)
            ?: matchDeleteOrCtrlAction(
                vkCode = vkCode,
                ctrlDown = ctrlDown,
                shiftDown = shiftDown,
                isQueryEmpty = isQueryEmpty,
            )

        private fun matchNavigationOrConfirmAction(vkCode: Int, shiftDown: Boolean): HookAction? = when (vkCode) {
            Win32Bindings.VK_DOWN -> HookAction.MoveSelection(delta = 1, lockInteractive = true)
            Win32Bindings.VK_UP -> HookAction.MoveSelection(delta = -1, lockInteractive = true)
            Win32Bindings.VK_TAB -> HookAction.MoveSelection(delta = if (shiftDown) -1 else 1, lockInteractive = false)
            Win32Bindings.VK_RETURN -> HookAction.ConfirmSelection
            Win32Bindings.VK_ESCAPE -> HookAction.Dismiss
            else -> null
        }

        private fun matchDeleteOrCtrlAction(
            vkCode: Int,
            ctrlDown: Boolean,
            shiftDown: Boolean,
            isQueryEmpty: Boolean,
        ): HookAction? = when {
            vkCode == Win32Bindings.VK_DELETE -> if (ctrlDown || isQueryEmpty) HookAction.CloseSelectedWindow else null
            !ctrlDown -> null
            vkCode == VK_CHAR_N -> HookAction.MoveSelection(delta = 1, lockInteractive = true)
            vkCode == VK_CHAR_P -> HookAction.MoveSelection(delta = -1, lockInteractive = true)
            vkCode == VK_CHAR_M -> HookAction.MinimizeSelectedWindow
            vkCode == VK_CHAR_W -> if (shiftDown) HookAction.KillSelectedProcess else HookAction.CloseSelectedWindow
            else -> null
        }

        private fun resolveAltTypingIfApplicable(
            vkCode: Int,
            ctrlDown: Boolean,
            shiftDown: Boolean,
            anyAltDown: Boolean,
            triggerVkCode: Int,
        ): HookKeyEvent? {
            if (!anyAltDown || ctrlDown) {
                return null
            }
            return when {
                vkCode == Win32Bindings.VK_BACK -> HookKeyEvent.BackspaceSearch
                vkCode == Win32Bindings.VK_SPACE && triggerVkCode != Win32Bindings.VK_SPACE ->
                    HookKeyEvent.AppendSearchChar(' ')
                vkCode in VK_CHAR_A..VK_CHAR_Z -> {
                    val ch = if (shiftDown) vkCode.toChar() else vkCode.toChar().lowercaseChar()
                    HookKeyEvent.AppendSearchChar(ch)
                }
                vkCode in VK_DIGIT_0..VK_DIGIT_9 && !shiftDown ->
                    HookKeyEvent.AppendSearchChar(vkCode.toChar())
                vkCode in Win32Bindings.VK_NUMPAD0..Win32Bindings.VK_NUMPAD9 -> {
                    val digit = (VK_DIGIT_0 + (vkCode - Win32Bindings.VK_NUMPAD0)).toChar()
                    HookKeyEvent.AppendSearchChar(digit)
                }
                else -> null
            }
        }
    }
}
