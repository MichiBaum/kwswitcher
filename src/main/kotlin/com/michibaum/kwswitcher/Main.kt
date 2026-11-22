package com.michibaum.kwswitcher

import com.formdev.flatlaf.FlatDarkLaf
import com.michibaum.kwswitcher.coordinator.SwitcherStateMachine
import com.michibaum.kwswitcher.ui.SwitcherOverlay
import com.michibaum.kwswitcher.win32.HookKeyEvent
import com.michibaum.kwswitcher.win32.KeyboardHook
import com.michibaum.kwswitcher.win32.WindowActivator
import com.michibaum.kwswitcher.win32.WindowEnumerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.util.concurrent.CountDownLatch
import javax.swing.SwingUtilities

fun main() {
    FlatDarkLaf.setup()

    val windowEnumerator = WindowEnumerator()
    val windowActivator = WindowActivator()
    val stateMachine = SwitcherStateMachine(
        enumerateWindows = { windowEnumerator.enumerateOpenWindows() },
        onActivateWindow = { target -> windowActivator.activate(target.hwnd) },
    )

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var overlay: SwitcherOverlay? = null

    SwingUtilities.invokeAndWait {
        overlay = SwitcherOverlay(stateMachine = stateMachine)
    }

    val keyboardHook = KeyboardHook()
    val hookInstalled = keyboardHook.start()
    if (!hookInstalled) {
        System.err.println("Failed to install Win32 low-level keyboard hook (WH_KEYBOARD_LL).")
        overlay?.dispose()
        appScope.cancel()
        return
    }

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

    val shutdownLatch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                keyboardHook.close()
                overlay?.dispose()
                appScope.cancel()
                shutdownLatch.countDown()
            },
            "kwswitcher-shutdown",
        ),
    )

    println("Kotlin Switcheroo (kwswitcher) is running. Press Alt+Tab to invoke, or Ctrl+C to exit.")
    shutdownLatch.await()
}
