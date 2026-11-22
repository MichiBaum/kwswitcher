package com.michibaum.kwswitcher.win32

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed interface HookKeyEvent {
    data class AltTabPressed(val reverse: Boolean) : HookKeyEvent
    data object AltReleased : HookKeyEvent
}

class KeyboardHook : AutoCloseable {
    private val eventChannel = Channel<HookKeyEvent>(Channel.UNLIMITED)
    val events: Flow<HookKeyEvent> = eventChannel.receiveAsFlow()

    private val running = AtomicBoolean(false)
    private val hookThreadId = AtomicInteger(0)

    @Volatile
    private var altHeld: Boolean = false

    @Volatile
    private var shiftHeld: Boolean = false

    @Volatile
    private var hookThread: Thread? = null

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
        startedLatch.await(3, TimeUnit.SECONDS)
        return installedOk.get()
    }

    private fun runMessageLoop(
        startedLatch: CountDownLatch,
        installedOk: AtomicBoolean,
    ) {
        val threadId = Win32Bindings.GetCurrentThreadId.invokeExact() as Int
        hookThreadId.set(threadId)

        Arena.ofConfined().use { arena ->
            val callback = HookCallback { nCode, wParam, lParam ->
                handleLowLevelKey(nCode = nCode, wParam = wParam, lParam = lParam)
            }
            val callbackHandle = MethodHandles.lookup().findVirtual(
                HookCallback::class.java,
                "invoke",
                MethodType.methodType(
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    MemorySegment::class.java,
                ),
            ).bindTo(callback)

            val upcallStub = Win32Bindings.linker.upcallStub(
                callbackHandle,
                Win32Bindings.LOW_LEVEL_KEYBOARD_PROC_DESC,
                arena,
            )

            val moduleHandle = Win32Bindings.GetModuleHandleW.invokeExact(MemorySegment.NULL) as MemorySegment
            val hookHandle = Win32Bindings.SetWindowsHookExW.invokeExact(
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
                    val result = Win32Bindings.GetMessageW.invokeExact(
                        msg,
                        MemorySegment.NULL,
                        0,
                        0,
                    ) as Int
                    if (result <= 0) {
                        break
                    }
                    Win32Bindings.TranslateMessage.invokeExact(msg) as Int
                    Win32Bindings.DispatchMessageW.invokeExact(msg) as Long
                }
            } finally {
                Win32Bindings.UnhookWindowsHookEx.invokeExact(hookHandle) as Int
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
            val vkCode = structSegment.get(ValueLayout.JAVA_INT, 0L)
            val flags = structSegment.get(ValueLayout.JAVA_INT, 8L)
            val message = wParam.toInt()

            val shouldSwallow = processKeyMessage(
                message = message,
                vkCode = vkCode,
                flags = flags,
            )
            if (shouldSwallow) {
                return 1L
            }
        }
        return Win32Bindings.CallNextHookEx.invokeExact(
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
        val isKeyDown = message == Win32Bindings.WM_KEYDOWN || message == Win32Bindings.WM_SYSKEYDOWN
        val isKeyUp = message == Win32Bindings.WM_KEYUP || message == Win32Bindings.WM_SYSKEYUP

        when (vkCode) {
            Win32Bindings.VK_MENU,
            Win32Bindings.VK_LMENU,
            Win32Bindings.VK_RMENU,
            -> {
                if (isKeyDown) {
                    altHeld = true
                } else if (isKeyUp && altHeld) {
                    altHeld = false
                    eventChannel.trySend(HookKeyEvent.AltReleased)
                }
                return false
            }

            Win32Bindings.VK_SHIFT,
            Win32Bindings.VK_LSHIFT,
            Win32Bindings.VK_RSHIFT,
            -> {
                if (isKeyDown) {
                    shiftHeld = true
                } else if (isKeyUp) {
                    shiftHeld = false
                }
                return false
            }

            Win32Bindings.VK_TAB -> {
                val altDownFlag = (flags and Win32Bindings.LLKHF_ALTDOWN) != 0
                if (isKeyDown && (altHeld || altDownFlag)) {
                    altHeld = true
                    val reverse = shiftHeld || isShiftCurrentlyDown()
                    eventChannel.trySend(HookKeyEvent.AltTabPressed(reverse = reverse))
                    return true
                }
            }
        }
        return false
    }

    private fun isShiftCurrentlyDown(): Boolean {
        val state = Win32Bindings.GetAsyncKeyState.invokeExact(Win32Bindings.VK_SHIFT) as Short
        return (state.toInt() and 0x8000) != 0
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        val tid = hookThreadId.get()
        if (tid != 0) {
            Win32Bindings.PostThreadMessageW.invokeExact(
                tid,
                Win32Bindings.WM_QUIT,
                0L,
                0L,
            ) as Int
        }
        hookThread?.join(1_000)
        eventChannel.close()
    }

    private fun interface HookCallback {
        fun invoke(nCode: Int, wParam: Long, lParam: MemorySegment): Long
    }
}
