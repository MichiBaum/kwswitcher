package com.michibaum.kwswitcher.core.sandbox

import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import com.michibaum.kwswitcher.core.port.HotkeyHookPort
import com.michibaum.kwswitcher.core.port.SingleInstancePort
import com.michibaum.kwswitcher.core.port.StartupRegistrationPort
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Safe no-op [HotkeyHookPort] used in `--no-hook` and `--sandbox` modes so hitting IDE breakpoints
 * never stalls OS-wide `WH_KEYBOARD_LL` keyboard input (`DX-2`).
 */
class NoOpHotkeyHookPort : HotkeyHookPort {
    private val channel = Channel<SwitcherIntent>(Channel.UNLIMITED)
    override val events: Flow<SwitcherIntent> = channel.receiveAsFlow()

    val acknowledgeCount = AtomicInteger(0)

    override fun start(): Boolean = true

    override fun acknowledgeHotkeyHandled() {
        acknowledgeCount.incrementAndGet()
    }

    /**
     * Allows tests or dev harnesses to inject a synthetic [SwitcherIntent] as if emitted by the global hook.
     */
    fun emit(intent: SwitcherIntent): Boolean = channel.trySend(intent).isSuccess

    override fun close() {
        channel.close()
    }
}

/**
 * [SingleInstancePort] that always succeeds without creating OS mutex handles (`--sandbox` mode and tests).
 */
class NoOpSingleInstancePort : SingleInstancePort {
    override fun tryAcquire(): Boolean = true

    override fun close() = Unit
}

/**
 * In-memory [StartupRegistrationPort] used in `--dev` and `--sandbox` modes so toggling
 * "Run at Windows Startup" never overwrites the user's real `HKCU\...\Run` entry (`DX-2`).
 */
class InMemoryStartupPort(initialRegistered: Boolean = false) : StartupRegistrationPort {
    private val registered = AtomicBoolean(initialRegistered)

    override fun isRegistered(): Boolean = registered.get()

    override fun setRegistered(enabled: Boolean): Boolean {
        registered.set(enabled)
        return true
    }
}
