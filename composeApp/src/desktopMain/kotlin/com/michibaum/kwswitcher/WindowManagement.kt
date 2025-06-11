package com.michibaum.kwswitcher

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser

/**
 * Utility class for window management operations
 */
class WindowManagement {
    private val processMemoryUtils = ProcessMemoryUtils()
    
    /**
     * Gets a list of all open windows
     *
     * @param visibleOnly If true, only returns visible windows
     * @return List of WindowInfo objects representing open windows
     */
    fun getOpenWindows(visibleOnly: Boolean = true): List<WindowInfo> {
        val windows = mutableListOf<WindowInfo>()

        User32.INSTANCE.EnumWindows({ hwnd, _ ->
            val isVisible = User32.INSTANCE.IsWindowVisible(hwnd)
            val isMinimized = User32.INSTANCE.IsIconic(hwnd)

            if (!visibleOnly || isVisible) {
                val textLength = User32.INSTANCE.GetWindowTextLengthW(hwnd)

                if (textLength > 0) {
                    val buffer = CharArray(textLength + 1)
                    User32.INSTANCE.GetWindowTextW(hwnd, buffer, buffer.size)
                    val title = String(buffer.dropLast(1).toCharArray())

                    val processIdArray = IntArray(1)
                    val threadId = User32.INSTANCE.GetWindowThreadProcessId(hwnd, processIdArray)
                    val processId = processIdArray[0]

                    // Get memory usage information
                    val memoryInfo = processMemoryUtils.getProcessMemoryInfo(processId)
                    val memoryUsageMB = memoryInfo?.first ?: 0.0
                    val memoryUsagePercent = memoryInfo?.second ?: 0.0

                    windows.add(
                        WindowInfo(
                            hwnd = hwnd,
                            title = title,
                            processId = processId,
                            threadId = threadId,
                            isVisible = isVisible,
                            isMinimized = isMinimized,
                            memoryUsageMB = memoryUsageMB,
                            memoryUsagePercent = memoryUsagePercent
                        )
                    )
                }
            }

            true // Continue enumeration
        }, null)

        return windows
    }

    /**
     * Brings the specified window to the foreground and activates it.
     *
     * @param windowInfo The WindowInfo object of the window to focus
     * @return true if the window was successfully focused, false otherwise
     */
    fun focusWindow(windowInfo: WindowInfo): Boolean {
        return User32.INSTANCE.SetForegroundWindow(windowInfo.hwnd)
    }

    /**
     * Brings the specified window to the foreground and activates it.
     *
     * @param hwnd The handle of the window to focus
     * @return true if the window was successfully focused, false otherwise
     */
    fun focusWindow(hwnd: HWND): Boolean {
        return User32.INSTANCE.SetForegroundWindow(hwnd)
    }

    /**
     * Finds the application window by its title.
     *
     * @param windowTitle The title of the window to find
     * @return The handle of the window, or null if not found
     */
    fun findApplicationWindow(windowTitle: String): HWND? {
        val hwnd = User32.INSTANCE.FindWindowW(null, windowTitle)
        return if (hwnd == null) null else hwnd
    }

    /**
     * Brings the application window to the foreground.
     *
     * @param windowTitle The title of the window to bring to the foreground
     * @return true if the window was successfully focused, false otherwise
     */
    fun focusApplicationWindow(windowTitle: String): Boolean {
        val hwnd = findApplicationWindow(windowTitle) ?: return false
        return User32.INSTANCE.SetForegroundWindow(hwnd)
    }

    /**
     * Closes the specified window by sending a WM_CLOSE message.
     *
     * @param windowInfo The WindowInfo object of the window to close
     * @return true if the message was successfully posted, false otherwise
     */
    fun closeWindow(windowInfo: WindowInfo): Boolean {
        return User32.INSTANCE.PostMessageW(windowInfo.hwnd, User32.WM_CLOSE, 0, 0)
    }

    /**
     * Closes the specified window by sending a WM_CLOSE message.
     *
     * @param hwnd The handle of the window to close
     * @return true if the message was successfully posted, false otherwise
     */
    fun closeWindow(hwnd: HWND): Boolean {
        return User32.INSTANCE.PostMessageW(hwnd, User32.WM_CLOSE, 0, 0)
    }
}