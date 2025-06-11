package com.michibaum.kwswitcher

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities

// Hotkey ID
private const val HOTKEY_ID = 1

@OptIn(DelicateCoroutinesApi::class)
fun main() = application {
    // Create WindowsApi instance
    val windowsApi = WindowsApi()

    // Register global hotkey (Alt+Shift+K)
    val hotkeyRegistered = windowsApi.registerHotKey(
        HOTKEY_ID,
        User32.MOD_ALT or User32.MOD_SHIFT,
        User32.VK_K
    )

    if (!hotkeyRegistered) {
        println("Failed to register hotkey")
    } else {
        println("Hotkey registered: Alt+Shift+K")
    }

    // Start hotkey message loop in a separate thread
    GlobalScope.launch(Dispatchers.IO) {
        windowsApi.startHotKeyMessageLoop { hotkeyId ->
            if (hotkeyId == HOTKEY_ID) {
                // Bring application window to front
                SwingUtilities.invokeLater {
                    windowsApi.focusApplicationWindow("kwswitcher")
                }
            }
        }
    }

    // Create and show the window
    Window(
        onCloseRequest = {
            // Unregister hotkey when application is closed
            windowsApi.unregisterHotKey(HOTKEY_ID)
            exitApplication()
        },
        title = "kwswitcher",
    ) {
        App()
    }
}
