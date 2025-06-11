package com.michibaum.kwswitcher

import com.sun.jna.platform.win32.WinDef.HWND

/**
 * WindowsApi class that provides access to Windows API functions through JNA
 * to retrieve information about open windows.
 * 
 * This class acts as a facade for the various utility classes that handle specific functionality.
 */
class WindowsApi {
    private val processMemoryUtils = ProcessMemoryUtils()
    private val windowManagement = WindowManagement()
    private val hotkeyUtils = HotkeyUtils()

    /**
     * Gets memory usage information for a process
     *
     * @param processId The ID of the process to get memory information for
     * @return A pair containing memory usage in MB and memory usage as a percentage of total system memory,
     *         or null if the information could not be retrieved
     */
    fun getProcessMemoryInfo(processId: Int): Pair<Double, Double>? {
        return processMemoryUtils.getProcessMemoryInfo(processId)
    }

    /**
     * Gets a list of all open windows
     *
     * @param visibleOnly If true, only returns visible windows
     * @return List of WindowInfo objects representing open windows
     */
    fun getOpenWindows(visibleOnly: Boolean = true): List<WindowInfo> {
        return windowManagement.getOpenWindows(visibleOnly)
    }

    /**
     * Brings the specified window to the foreground and activates it.
     *
     * @param windowInfo The WindowInfo object of the window to focus
     * @return true if the window was successfully focused, false otherwise
     */
    fun focusWindow(windowInfo: WindowInfo): Boolean {
        return windowManagement.focusWindow(windowInfo)
    }

    /**
     * Brings the specified window to the foreground and activates it.
     *
     * @param hwnd The handle of the window to focus
     * @return true if the window was successfully focused, false otherwise
     */
    fun focusWindow(hwnd: HWND): Boolean {
        return windowManagement.focusWindow(hwnd)
    }

    /**
     * Finds the application window by its title.
     *
     * @param windowTitle The title of the window to find
     * @return The handle of the window, or null if not found
     */
    fun findApplicationWindow(windowTitle: String): HWND? {
        return windowManagement.findApplicationWindow(windowTitle)
    }

    /**
     * Brings the application window to the foreground.
     *
     * @param windowTitle The title of the window to bring to the foreground
     * @return true if the window was successfully focused, false otherwise
     */
    fun focusApplicationWindow(windowTitle: String): Boolean {
        return windowManagement.focusApplicationWindow(windowTitle)
    }

    /**
     * Registers a global hotkey.
     *
     * @param id The identifier of the hot key
     * @param modifiers The modifier keys (MOD_ALT, MOD_CONTROL, MOD_SHIFT, MOD_WIN)
     * @param vk The virtual-key code of the hot key
     * @return true if the hotkey was successfully registered, false otherwise
     */
    fun registerHotKey(id: Int, modifiers: Int, vk: Int): Boolean {
        return hotkeyUtils.registerHotKey(id, modifiers, vk)
    }

    /**
     * Unregisters a global hotkey.
     *
     * @param id The identifier of the hot key to be freed
     * @return true if the hotkey was successfully unregistered, false otherwise
     */
    fun unregisterHotKey(id: Int): Boolean {
        return hotkeyUtils.unregisterHotKey(id)
    }

    /**
     * Closes the specified window by sending a WM_CLOSE message.
     *
     * @param windowInfo The WindowInfo object of the window to close
     * @return true if the message was successfully posted, false otherwise
     */
    fun closeWindow(windowInfo: WindowInfo): Boolean {
        return windowManagement.closeWindow(windowInfo)
    }

    /**
     * Closes the specified window by sending a WM_CLOSE message.
     *
     * @param hwnd The handle of the window to close
     * @return true if the message was successfully posted, false otherwise
     */
    fun closeWindow(hwnd: HWND): Boolean {
        return windowManagement.closeWindow(hwnd)
    }

    /**
     * Starts a message loop to listen for hotkey events.
     * This method blocks the current thread until a WM_QUIT message is received.
     *
     * @param onHotKeyPressed Callback function to be called when a hotkey is pressed
     */
    fun startHotKeyMessageLoop(onHotKeyPressed: (Int) -> Unit) {
        hotkeyUtils.startHotKeyMessageLoop(onHotKeyPressed)
    }
}
