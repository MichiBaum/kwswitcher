package com.michibaum.kwswitcher

import com.michibaum.kwswitcher.config.ConfigRepository
import com.michibaum.kwswitcher.config.SwitcherConfig
import com.michibaum.kwswitcher.config.ThemeManager
import com.michibaum.kwswitcher.config.ThemeOption
import com.michibaum.kwswitcher.coordinator.SwitcherStateMachine
import com.michibaum.kwswitcher.ui.SwitcherOverlay
import com.michibaum.kwswitcher.ui.SystemTrayController
import com.michibaum.kwswitcher.win32.HookKeyEvent
import com.michibaum.kwswitcher.win32.IconExtractor
import com.michibaum.kwswitcher.win32.KeyboardHook
import com.michibaum.kwswitcher.win32.SingleInstanceMutex
import com.michibaum.kwswitcher.win32.WindowActivator
import com.michibaum.kwswitcher.win32.WindowEnumerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * Application entry point for Kotlin Switcheroo (`kwswitcher`).
 * Enforces a single instance, loads `%APPDATA%\kwswitcher\config.json`, initializes the FlatLaf theme,
 * installs the `WH_KEYBOARD_LL` hotkey hook, and wires the system tray and overlay UI.
 */
fun main() {
    val instanceMutex = SingleInstanceMutex()
    if (!instanceMutex.tryAcquire()) {
        System.err.println("Another instance of Kotlin Switcheroo (kwswitcher) is already running.")
        return
    }

    val configRepository = ConfigRepository()
    val configRef = AtomicReference<SwitcherConfig>(configRepository.loadOrCreate())
    val initialPalette = ThemeManager.applyTheme(configRef.get().theme)

    val iconExtractor = IconExtractor()
    val windowEnumerator = WindowEnumerator(
        configProvider = { configRef.get() },
    )
    val windowActivator = WindowActivator()
    val stateMachine = SwitcherStateMachine(
        enumerateWindows = { windowEnumerator.enumerateOpenWindows() },
        onActivateWindow = { target -> windowActivator.activate(target.hwnd) },
        onCloseWindow = { target -> windowActivator.closeGracefully(target.hwnd) },
        onMinimizeWindow = { target -> windowActivator.minimize(target.hwnd) },
        onTerminateProcess = { target -> windowActivator.terminateProcess(target.pid) },
    )

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var overlay: SwitcherOverlay? = null

    SwingUtilities.invokeAndWait {
        overlay = SwitcherOverlay(
            stateMachine = stateMachine,
            iconExtractor = iconExtractor,
            activeMonitorBoundsProvider = { windowEnumerator.getActiveMonitorWorkArea() },
            initialConfig = configRef.get(),
            initialPalette = initialPalette,
        )
    }

    val shutdownLatch = CountDownLatch(1)
    val keyboardHook = KeyboardHook(
        hotkeyConfigProvider = { configRef.get().hotkey },
    )
    val hookInstalled = keyboardHook.start()
    if (!hookInstalled) {
        System.err.println("Failed to install Win32 low-level keyboard hook (WH_KEYBOARD_LL).")
        overlay?.dispose()
        appScope.cancel()
        instanceMutex.close()
        return
    }

    val trayController = SystemTrayController(
        onShowSwitcher = {
            stateMachine.onShowInteractive()
        },
        onReloadConfig = {
            val reloaded = configRepository.loadOrCreate()
            configRef.set(reloaded)
            SwingUtilities.invokeLater {
                val palette = ThemeManager.applyTheme(reloaded.theme)
                overlay?.updateConfigAndTheme(reloaded, palette)
            }
        },
        onSelectTheme = { theme: ThemeOption ->
            val updated = configRef.get().copy(theme = theme)
            configRef.set(updated)
            runCatching { configRepository.save(updated) }
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

    appScope.launch {
        keyboardHook.events.collect { event ->
            when (event) {
                is HookKeyEvent.AltTabPressed -> stateMachine.onAltTabPressed(reverse = event.reverse)
                HookKeyEvent.AltReleased -> stateMachine.onAltReleased()
            }
        }
    }

    val cleanup = {
        trayController.close()
        keyboardHook.close()
        overlay?.dispose()
        appScope.cancel()
        instanceMutex.close()
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
