package com.michibaum.kwswitcher

import com.sun.jna.platform.win32.WinUser

/**
 * Utility class for hotkey operations
 */
class HotkeyUtils {
    /**
     * Registers a global hotkey.
     *
     * @param id The identifier of the hot key
     * @param modifiers The modifier keys (MOD_ALT, MOD_CONTROL, MOD_SHIFT, MOD_WIN)
     * @param vk The virtual-key code of the hot key
     * @return true if the hotkey was successfully registered, false otherwise
     */
    fun registerHotKey(id: Int, modifiers: Int, vk: Int): Boolean {
        return User32.INSTANCE.RegisterHotKey(null, id, modifiers, vk)
    }

    /**
     * Unregisters a global hotkey.
     *
     * @param id The identifier of the hot key to be freed
     * @return true if the hotkey was successfully unregistered, false otherwise
     */
    fun unregisterHotKey(id: Int): Boolean {
        return User32.INSTANCE.UnregisterHotKey(null, id)
    }

    /**
     * Starts a message loop to listen for hotkey events.
     * This method blocks the current thread until a WM_QUIT message is received.
     *
     * @param onHotKeyPressed Callback function to be called when a hotkey is pressed
     */
    fun startHotKeyMessageLoop(onHotKeyPressed: (Int) -> Unit) { // TODO GetMessage and PeekMessage dont work
        val msg = WinUser.MSG()
        var running = true

        while (running) {
            // Use PeekMessage instead of GetMessage to avoid blocking
            if (User32.INSTANCE.PeekMessage(msg, null, 0, 0, User32.PM_REMOVE)) {
                if (msg.message == User32.WM_HOTKEY) {
                    val hotkeyId = msg.wParam.toInt()
                    onHotKeyPressed(hotkeyId)
                } else if (msg.message == WinUser.WM_QUIT) {
                    running = false
                }

                User32.INSTANCE.TranslateMessage(msg)
                User32.INSTANCE.DispatchMessage(msg)
            }

            // Add a small delay to avoid consuming too much CPU
            Thread.sleep(10)
        }
    }
}