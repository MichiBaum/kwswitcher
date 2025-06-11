package com.michibaum.kwswitcher

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.util.*

/**
 * WindowsApi class that provides access to Windows API functions through JNA
 * to retrieve information about open windows.
 */
class WindowsApi {
    /**
     * Interface for the User32 DLL which contains Windows API functions
     */
    interface User32 : StdCallLibrary {
        companion object {
            val INSTANCE: User32 = Native.load("user32", User32::class.java)
        }

        /**
         * Enumerates all top-level windows on the screen by passing the handle to each window,
         * in turn, to an application-defined callback function.
         *
         * @param lpEnumFunc A pointer to an application-defined callback function
         * @param lParam An application-defined value to be passed to the callback function
         * @return If the function succeeds, the return value is nonzero
         */
        fun EnumWindows(lpEnumFunc: WinUser.WNDENUMPROC, lParam: Pointer?): Boolean

        /**
         * Determines whether the specified window is minimized (iconic).
         *
         * @param hWnd A handle to the window to be tested
         * @return If the window is iconic, the return value is nonzero
         */
        fun IsIconic(hWnd: HWND): Boolean

        /**
         * Determines whether the specified window is visible.
         *
         * @param hWnd A handle to the window to be tested
         * @return If the window is visible, the return value is nonzero
         */
        fun IsWindowVisible(hWnd: HWND): Boolean

        /**
         * Retrieves the text of the specified window's title bar (if it has one).
         *
         * @param hWnd A handle to the window or control containing the text
         * @param lpString A pointer to a buffer that receives the text
         * @param nMaxCount The maximum number of characters to copy to the buffer
         * @return The length, in characters, of the copied string, not including the terminating null character
         */
        fun GetWindowTextW(hWnd: HWND, lpString: CharArray, nMaxCount: Int): Int

        /**
         * Retrieves the length, in characters, of the specified window's title bar text.
         *
         * @param hWnd A handle to the window or control
         * @return The length, in characters, of the text
         */
        fun GetWindowTextLengthW(hWnd: HWND): Int

        /**
         * Retrieves the identifier of the thread that created the specified window.
         *
         * @param hWnd A handle to the window
         * @param lpdwProcessId A pointer to a variable that receives the process identifier
         * @return The return value is the identifier of the thread that created the window
         */
        fun GetWindowThreadProcessId(hWnd: HWND, lpdwProcessId: IntArray): Int

        /**
         * Brings the specified window to the foreground and activates it.
         *
         * @param hWnd A handle to the window to bring to the foreground
         * @return If the window was brought to the foreground, the return value is nonzero
         */
        fun SetForegroundWindow(hWnd: HWND): Boolean
    }

    /**
     * Represents information about a window
     */
    data class WindowInfo(
        val hwnd: HWND,
        val title: String,
        val processId: Int,
        val threadId: Int,
        val isVisible: Boolean,
        val isMinimized: Boolean
    )

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

                    windows.add(
                        WindowInfo(
                            hwnd = hwnd,
                            title = title,
                            processId = processIdArray[0],
                            threadId = threadId,
                            isVisible = isVisible,
                            isMinimized = isMinimized
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
}
