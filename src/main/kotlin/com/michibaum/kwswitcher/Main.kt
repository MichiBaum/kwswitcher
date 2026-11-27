package com.michibaum.kwswitcher

import com.michibaum.kwswitcher.config.ConfigRepository
import com.michibaum.kwswitcher.config.SwitcherConfig
import com.michibaum.kwswitcher.config.ThemeManager
import com.michibaum.kwswitcher.config.ThemeOption
import com.michibaum.kwswitcher.coordinator.SwitcherStateMachine
import com.michibaum.kwswitcher.ui.SwitcherOverlay
import com.michibaum.kwswitcher.ui.SystemTrayController
import com.michibaum.kwswitcher.win32.HookAction
import com.michibaum.kwswitcher.win32.HookKeyEvent
import com.michibaum.kwswitcher.win32.IconExtractor
import com.michibaum.kwswitcher.win32.KeyboardHook
import com.michibaum.kwswitcher.win32.OverlayHookSnapshot
import com.michibaum.kwswitcher.win32.SingleInstanceMutex
import com.michibaum.kwswitcher.win32.WindowActivator
import com.michibaum.kwswitcher.win32.WindowEnumerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * Application entry point for Kotlin Switcheroo (`kwswitcher`).
 * Enforces a single instance, loads `%APPDATA%\kwswitcher\config.json`, initializes the FlatLaf theme,
 * installs the `WH_KEYBOARD_LL` hotkey hook, and wires the system tray and overlay UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun main() {
    val instanceMutex = SingleInstanceMutex()
    if (!instanceMutex.tryAcquire()) {
        System.err.println("Another instance of Kotlin Switcheroo (kwswitcher) is already running.")
        return
    }

    val configRepository = ConfigRepository()
    val configRef = AtomicReference(configRepository.loadOrCreate())
    val initialPalette = ThemeManager.applyTheme(
        option = configRef.get().theme,
        updateExistingComponents = false,
    )

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val win32Dispatcher = Dispatchers.IO.limitedParallelism(1)
    val iconExtractor = IconExtractor(parentScope = appScope)
    val windowEnumerator = WindowEnumerator(
        configProvider = { configRef.get() },
    )
    val windowActivator = WindowActivator()
    val stateMachine = SwitcherStateMachine(
        enumerateWindows = { windowEnumerator.enumerateOpenWindows() },
        queryActiveMonitorWorkArea = { windowEnumerator.getActiveMonitorWorkArea() },
        onActivateWindow = { target ->
            appScope.launch(win32Dispatcher) {
                windowActivator.activate(target.hwnd)
            }
        },
        onCloseWindow = { target ->
            appScope.launch(win32Dispatcher) {
                windowActivator.closeGracefully(target.hwnd)
            }
        },
        onMinimizeWindow = { target ->
            appScope.launch(win32Dispatcher) {
                windowActivator.minimize(target.hwnd)
            }
        },
        onTerminateProcess = { target ->
            appScope.launch(win32Dispatcher) {
                windowActivator.terminateProcess(target.pid)
            }
        },
    )

    var overlay: SwitcherOverlay? = null

    SwingUtilities.invokeAndWait {
        overlay = SwitcherOverlay(
            stateMachine = stateMachine,
            iconExtractor = iconExtractor,
            activeMonitorBoundsProvider = { windowEnumerator.getActiveMonitorWorkArea() },
            onRequestForegroundFocus = {
                appScope.launch(win32Dispatcher) {
                    if (stateMachine.state.value.isVisible) {
                        windowActivator.activateOverlay()
                        withContext(Dispatchers.Swing) {
                            if (stateMachine.state.value.isVisible) {
                                overlay?.ensureSearchFocus()
                            }
                        }
                    }
                }
            },
            initialConfig = configRef.get(),
            initialPalette = initialPalette,
        )
    }

    val shutdownLatch = CountDownLatch(1)
    val keyboardHook = KeyboardHook(
        hotkeyConfigProvider = { configRef.get().hotkey },
        overlayStateProvider = {
            val current = stateMachine.state.value
            if (!current.isVisible) {
                OverlayHookSnapshot.HIDDEN
            } else {
                OverlayHookSnapshot(
                    mode = current.mode,
                    isQueryEmpty = current.query.isEmpty(),
                )
            }
        },
    )
    val hookInstalled = keyboardHook.start()
    if (!hookInstalled) {
        System.err.println("Failed to install Win32 low-level keyboard hook (WH_KEYBOARD_LL).")
        overlay?.dispose()
        iconExtractor.close()
        appScope.cancel()
        instanceMutex.close()
        return
    }

    val trayController = SystemTrayController(
        onShowSwitcher = {
            appScope.launch(win32Dispatcher) {
                stateMachine.onShowInteractive()
            }
        },
        onReloadConfig = {
            appScope.launch(Dispatchers.IO) {
                val reloaded = configRepository.loadOrCreate()
                configRef.set(reloaded)
                withContext(Dispatchers.Swing) {
                    val palette = ThemeManager.applyTheme(reloaded.theme)
                    overlay?.updateConfigAndTheme(reloaded, palette)
                }
            }
        },
        onSelectTheme = { theme: ThemeOption ->
            val updated = configRef.updateAndGet { it.copy(theme = theme) }
            appScope.launch(Dispatchers.IO) {
                runCatching { configRepository.save(updated) }
            }
            SwingUtilities.invokeLater {
                val palette = ThemeManager.applyTheme(theme)
                overlay?.updateConfigAndTheme(updated, palette)
            }
        },
        onQuit = {
            shutdownLatch.countDown()
        },
    )
    trayController.install(initialTheme = configRef.get().theme)

    appScope.launch(Dispatchers.Swing) {
        stateMachine.state.collect { uiState ->
            overlay?.render(uiState)
        }
    }

    appScope.launch(win32Dispatcher) {
        keyboardHook.events.collect { event ->
            dispatchHookEvent(
                event = event,
                stateMachine = stateMachine,
                keyboardHook = keyboardHook,
            )
        }
    }

    val cleanedUp = AtomicBoolean(false)
    val cleanup = {
        if (cleanedUp.compareAndSet(false, true)) {
            trayController.close()
            keyboardHook.close()
            overlay?.dispose()
            iconExtractor.close()
            appScope.cancel()
            instanceMutex.close()
        }
    }

    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                cleanup()
                shutdownLatch.countDown()
            },
            "kwswitcher-shutdown",
        ),
    )

    println("Kotlin Switcheroo (kwswitcher) is running. Press Alt+Tab to invoke, or Ctrl+C to exit.")
    shutdownLatch.await()
    cleanup()
}

internal fun dispatchHookEvent(
    event: HookKeyEvent,
    stateMachine: SwitcherStateMachine,
    keyboardHook: KeyboardHook,
) {
    when (event) {
        is HookKeyEvent.AltTabPressed -> {
            try {
                stateMachine.onAltTabPressed(reverse = event.reverse)
            } finally {
                keyboardHook.acknowledgeAltTabHandled()
            }
        }

        HookKeyEvent.AltReleased -> stateMachine.onAltReleased()
        HookKeyEvent.BackspaceSearch -> stateMachine.onBackspaceQuery()
        is HookKeyEvent.AppendSearchChar -> stateMachine.onAppendQueryChar(event.char)
        is HookKeyEvent.Action -> dispatchHookAction(action = event.action, stateMachine = stateMachine)
    }
}

private fun dispatchHookAction(
    action: HookAction,
    stateMachine: SwitcherStateMachine,
) {
    when (action) {
        is HookAction.MoveSelection -> stateMachine.onMoveSelection(
            delta = action.delta,
            lockInteractive = action.lockInteractive,
        )

        HookAction.CloseSelectedWindow -> stateMachine.onCloseSelectedWindow()
        HookAction.MinimizeSelectedWindow -> stateMachine.onMinimizeSelectedWindow()
        HookAction.KillSelectedProcess -> stateMachine.onKillSelectedWindowProcess()
        HookAction.ConfirmSelection -> stateMachine.onConfirmSelection()
        HookAction.Dismiss -> stateMachine.onDismiss()
    }
}
