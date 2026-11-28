package com.michibaum.kwswitcher.core.coordinator

import com.michibaum.kwswitcher.core.port.HotkeyHookPort
import com.michibaum.kwswitcher.core.port.NativeResult
import com.michibaum.kwswitcher.core.port.WindowCatalogPort
import com.michibaum.kwswitcher.core.port.WindowControlPort
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * Single-threaded unidirectional actor consuming [SwitcherIntent]s sequentially via a [Channel],
 * updating [StateFlow] for UI subscribers, and dispatching [SwitcherEffect]s to platform ports (`DX-3`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitcherCoordinator(
    private val windowCatalog: WindowCatalogPort,
    private val windowControl: WindowControlPort,
    private val onHotkeyAcknowledged: () -> Unit = {},
    initialPinned: Boolean = false,
    parentScope: CoroutineScope? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : AutoCloseable {

    private val actorScope: CoroutineScope = parentScope?.let {
        CoroutineScope(SupervisorJob(it.coroutineContext[Job]) + dispatcher)
    } ?: CoroutineScope(SupervisorJob() + dispatcher)

    private val mailbox = Channel<IntentEnvelope>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(SwitcherUiState(pinned = initialPinned))
    private val hookBindLock = Any()
    private var hookCollectorJob: Job? = null

    /**
     * Read-only state stream observed by the Swing overlay and global hook snapshot provider.
     */
    val state: StateFlow<SwitcherUiState> = _state.asStateFlow()

    @Volatile
    private var attachedHook: HotkeyHookPort? = null

    init {
        actorScope.launch {
            var currentEnvelope: IntentEnvelope? = null
            try {
                for (envelope in mailbox) {
                    currentEnvelope = envelope
                    val updated = handleIntentSafely(envelope.intent)
                    envelope.completion?.complete(updated)
                    currentEnvelope = null
                }
            } finally {
                currentEnvelope?.completion?.complete(_state.value)
                drainPendingEnvelopes()
            }
        }
        actorScope.coroutineContext[Job]?.invokeOnCompletion {
            mailbox.close()
            drainPendingEnvelopes()
        }
    }

    /**
     * Binds [hookPort] so [SwitcherIntent]s from the global hook feed into this coordinator
     * and [SwitcherEffect.AcknowledgeHotkey] notifies [HotkeyHookPort.acknowledgeHotkeyHandled].
     */
    fun bindHook(hookPort: HotkeyHookPort) {
        synchronized(hookBindLock) {
            attachedHook = hookPort
            hookCollectorJob?.cancel()
            hookCollectorJob = actorScope.launch {
                hookPort.events.collect { intent ->
                    dispatch(intent)
                }
            }
        }
    }

    /**
     * Enqueues [intent] without blocking the caller (safe from both the Swing EDT and Win32 hook paths).
     */
    fun dispatch(intent: SwitcherIntent): Boolean = mailbox.trySend(IntentEnvelope(intent = intent)).isSuccess

    /**
     * Enqueues [intent] and suspends until the coordinator actor has applied the state transition and effects.
     */
    suspend fun submitAndAwait(intent: SwitcherIntent): SwitcherUiState {
        val deferred = CompletableDeferred<SwitcherUiState>()
        if (mailbox.trySend(IntentEnvelope(intent = intent, completion = deferred)).isFailure) {
            return _state.value
        }
        return deferred.await()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleIntentSafely(intent: SwitcherIntent): SwitcherUiState {
        var acknowledged = false
        try {
            val reduction = SwitcherReducer.reduce(
                current = _state.value,
                intent = intent,
                snapshotProvider = ::captureDesktopSnapshot,
            )
            _state.value = reduction.state
            for (effect in reduction.effects) {
                if (effect is SwitcherEffect.AcknowledgeHotkey) {
                    acknowledgeHotkeyOnce()
                    acknowledged = true
                } else {
                    executeEffect(effect)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            logger.error(e) { "Unhandled error while processing intent $intent" }
        } finally {
            if (intent is SwitcherIntent.TriggerHotkey && !acknowledged) {
                acknowledgeHotkeyOnce()
            }
        }
        return _state.value
    }

    @Suppress("TooGenericExceptionCaught")
    private fun captureDesktopSnapshot(): WindowDesktopSnapshot {
        val windows = try {
            windowCatalog.enumerateOpenWindows()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            logger.error(e) { "Failed to enumerate open windows; opening switcher with empty list" }
            emptyList()
        }
        val workArea = try {
            windowCatalog.getActiveMonitorWorkArea()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            logger.warn(e) { "Failed to query active monitor work area; falling back to default screen centering" }
            null
        }
        return WindowDesktopSnapshot(windows = windows, activeMonitorWorkArea = workArea)
    }

    private fun executeEffect(effect: SwitcherEffect) {
        val result: NativeResult = when (effect) {
            is SwitcherEffect.ActivateWindow -> windowControl.activate(effect.target.hwnd)
            is SwitcherEffect.CloseWindow -> windowControl.closeGracefully(effect.target.hwnd)
            is SwitcherEffect.MinimizeWindow -> windowControl.minimize(effect.target.hwnd)
            is SwitcherEffect.TerminateProcess -> windowControl.terminateProcess(effect.target.pid)
            SwitcherEffect.AcknowledgeHotkey -> NativeResult.Ok
        }
        if (result is NativeResult.Failed) {
            logger.warn {
                "Platform effect $effect failed in ${result.operation} (code=${result.errorCode}): ${result.detail}"
            }
        }
    }

    private fun acknowledgeHotkeyOnce() {
        attachedHook?.acknowledgeHotkeyHandled()
        onHotkeyAcknowledged()
    }

    override fun close() {
        synchronized(hookBindLock) {
            hookCollectorJob?.cancel()
            hookCollectorJob = null
        }
        mailbox.close()
        actorScope.cancel()
        drainPendingEnvelopes()
    }

    private fun drainPendingEnvelopes() {
        while (true) {
            val pending = mailbox.tryReceive().getOrNull() ?: break
            pending.completion?.complete(_state.value)
        }
    }

    private data class IntentEnvelope(
        val intent: SwitcherIntent,
        val completion: CompletableDeferred<SwitcherUiState>? = null,
    )
}
