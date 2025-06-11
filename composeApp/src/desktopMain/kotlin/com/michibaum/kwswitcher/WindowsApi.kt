package com.michibaum.kwswitcher

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.BaseTSD.SIZE_T
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.util.*

/**
 * WindowsApi class that provides access to Windows API functions through JNA
 * to retrieve information about open windows.
 */
class WindowsApi {
    /**
     * Structure representing memory usage information for a process
     */
    @Structure.FieldOrder("cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize", 
                         "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", 
                         "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")
    class PROCESS_MEMORY_COUNTERS : Structure() {
        @JvmField var cb: DWORD = DWORD(0)
        @JvmField var PageFaultCount: DWORD = DWORD(0)
        @JvmField var PeakWorkingSetSize: SIZE_T = SIZE_T(0)
        @JvmField var WorkingSetSize: SIZE_T = SIZE_T(0)
        @JvmField var QuotaPeakPagedPoolUsage: SIZE_T = SIZE_T(0)
        @JvmField var QuotaPagedPoolUsage: SIZE_T = SIZE_T(0)
        @JvmField var QuotaPeakNonPagedPoolUsage: SIZE_T = SIZE_T(0)
        @JvmField var QuotaNonPagedPoolUsage: SIZE_T = SIZE_T(0)
        @JvmField var PagefileUsage: SIZE_T = SIZE_T(0)
        @JvmField var PeakPagefileUsage: SIZE_T = SIZE_T(0)
    }

    /**
     * Interface for the Kernel32 DLL which contains Windows API functions for process management
     */
    interface Kernel32 : StdCallLibrary {
        companion object {
            val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java)

            // Process access rights
            const val PROCESS_QUERY_INFORMATION = 0x0400
            const val PROCESS_VM_READ = 0x0010
        }

        /**
         * Opens an existing local process object.
         *
         * @param dwDesiredAccess The access to the process object
         * @param bInheritHandle If this value is TRUE, processes created by this process will inherit the handle
         * @param dwProcessId The identifier of the local process to be opened
         * @return If the function succeeds, the return value is an open handle to the specified process
         */
        fun OpenProcess(dwDesiredAccess: Int, bInheritHandle: Boolean, dwProcessId: Int): HANDLE

        /**
         * Closes an open object handle.
         *
         * @param hObject A valid handle to an open object
         * @return If the function succeeds, the return value is nonzero
         */
        fun CloseHandle(hObject: HANDLE): Boolean
    }

    /**
     * Interface for the PSAPI DLL which contains Windows API functions for process status
     */
    interface Psapi : StdCallLibrary {
        companion object {
            val INSTANCE: Psapi = Native.load("psapi", Psapi::class.java)
        }

        /**
         * Retrieves information about the memory usage of the specified process.
         *
         * @param hProcess A handle to the process
         * @param ppsmemCounters A pointer to the PROCESS_MEMORY_COUNTERS structure that receives information about the memory usage of the process
         * @param cb The size of the PROCESS_MEMORY_COUNTERS structure, in bytes
         * @return If the function succeeds, the return value is nonzero
         */
        fun GetProcessMemoryInfo(hProcess: HANDLE, ppsmemCounters: PROCESS_MEMORY_COUNTERS, cb: Int): Boolean
    }

    /**
     * Interface for the User32 DLL which contains Windows API functions
     */
    interface User32 : StdCallLibrary {
        companion object {
            val INSTANCE: User32 = Native.load("user32", User32::class.java)

            // Constants for RegisterHotKey
            const val MOD_ALT = 0x0001
            const val MOD_CONTROL = 0x0002
            const val MOD_SHIFT = 0x0004
            const val MOD_WIN = 0x0008
            const val MOD_NOREPEAT = 0x4000

            // Constants for PeekMessage
            const val PM_REMOVE = 0x0001

            // Window messages
            const val WM_CLOSE = 0x0010

            // Virtual key codes
            const val VK_A = 0x41
            const val VK_B = 0x42
            const val VK_C = 0x43
            const val VK_D = 0x44
            const val VK_E = 0x45
            const val VK_F = 0x46
            const val VK_G = 0x47
            const val VK_H = 0x48
            const val VK_I = 0x49
            const val VK_J = 0x4A
            const val VK_K = 0x4B
            const val VK_L = 0x4C
            const val VK_M = 0x4D
            const val VK_N = 0x4E
            const val VK_O = 0x4F
            const val VK_P = 0x50
            const val VK_Q = 0x51
            const val VK_R = 0x52
            const val VK_S = 0x53
            const val VK_T = 0x54
            const val VK_U = 0x55
            const val VK_V = 0x56
            const val VK_W = 0x57
            const val VK_X = 0x58
            const val VK_Y = 0x59
            const val VK_Z = 0x5A

            // Custom message ID for hotkey
            const val WM_HOTKEY = 0x0312
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

        /**
         * Registers a hot key with the system.
         *
         * @param hWnd The handle of the window that will receive WM_HOTKEY messages
         * @param id The identifier of the hot key
         * @param fsModifiers The keys that must be pressed in combination with the key specified by uVirtKey
         * @param vk The virtual-key code of the hot key
         * @return If the function succeeds, the return value is nonzero
         */
        fun RegisterHotKey(hWnd: Pointer?, id: Int, fsModifiers: Int, vk: Int): Boolean

        /**
         * Unregisters a hot key previously registered by the calling thread.
         *
         * @param hWnd The handle of the window associated with the hot key to be freed
         * @param id The identifier of the hot key to be freed
         * @return If the function succeeds, the return value is nonzero
         */
        fun UnregisterHotKey(hWnd: Pointer?, id: Int): Boolean

        /**
         * Retrieves a message from the calling thread's message queue.
         *
         * @param lpMsg A pointer to an MSG structure that receives message information
         * @param hWnd A handle to the window whose messages are to be retrieved
         * @param wMsgFilterMin The integer value of the lowest message value to be retrieved
         * @param wMsgFilterMax The integer value of the highest message value to be retrieved
         * @return If the function succeeds, the return value is nonzero
         */
        fun GetMessage(lpMsg: WinUser.MSG, hWnd: HWND?, wMsgFilterMin: Int, wMsgFilterMax: Int): Boolean

        /**
         * Checks the thread message queue for a message and places it in the specified structure.
         *
         * @param lpMsg A pointer to an MSG structure that receives message information
         * @param hWnd A handle to the window whose messages are to be retrieved
         * @param wMsgFilterMin The integer value of the lowest message value to be retrieved
         * @param wMsgFilterMax The integer value of the highest message value to be retrieved
         * @param wRemoveMsg Specifies how messages are to be handled
         * @return If a message is available, the return value is nonzero
         */
        fun PeekMessage(lpMsg: WinUser.MSG, hWnd: HWND?, wMsgFilterMin: Int, wMsgFilterMax: Int, wRemoveMsg: Int): Boolean

        /**
         * Translates virtual-key messages into character messages.
         *
         * @param lpMsg A pointer to an MSG structure that contains message information
         * @return If the message is translated (that is, a character message is posted to the thread's message queue), the return value is nonzero
         */
        fun TranslateMessage(lpMsg: WinUser.MSG): Boolean

        /**
         * Dispatches a message to a window procedure.
         *
         * @param lpMsg A pointer to an MSG structure that contains message information
         * @return The return value specifies the value returned by the window procedure
         */
        fun DispatchMessage(lpMsg: WinUser.MSG): Long

        /**
         * Finds a window by its class name and window name.
         *
         * @param lpClassName The class name or a class atom
         * @param lpWindowName The window name (the window's title)
         * @return If the function succeeds, the return value is a handle to the window that has the specified class name and window name
         */
        fun FindWindowW(lpClassName: String?, lpWindowName: String?): HWND

        /**
         * Places (posts) a message in the message queue associated with the thread that created
         * the specified window and returns without waiting for the thread to process the message.
         *
         * @param hWnd A handle to the window whose window procedure will receive the message
         * @param msg The message to be posted
         * @param wParam Additional message-specific information
         * @param lParam Additional message-specific information
         * @return If the function succeeds, the return value is nonzero
         */
        fun PostMessageW(hWnd: HWND, msg: Int, wParam: Long, lParam: Long): Boolean
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
        val isMinimized: Boolean,
        val memoryUsageMB: Double = 0.0,
        val memoryUsagePercent: Double = 0.0
    )

    /**
     * Gets memory usage information for a process
     *
     * @param processId The ID of the process to get memory information for
     * @return A pair containing memory usage in MB and memory usage as a percentage of total system memory,
     *         or null if the information could not be retrieved
     */
    fun getProcessMemoryInfo(processId: Int): Pair<Double, Double>? {
        var handle: HANDLE? = null
        try {
            // Open the process with query information access rights
            handle = Kernel32.INSTANCE.OpenProcess(
                Kernel32.PROCESS_QUERY_INFORMATION or Kernel32.PROCESS_VM_READ,
                false,
                processId
            )

            if (handle == null || handle.pointer == Pointer.NULL) {
                return null
            }

            // Create a PROCESS_MEMORY_COUNTERS structure
            val memoryCounters = PROCESS_MEMORY_COUNTERS()
            memoryCounters.cb = DWORD(memoryCounters.size().toLong())

            // Get memory information
            val success = Psapi.INSTANCE.GetProcessMemoryInfo(
                handle,
                memoryCounters,
                memoryCounters.size()
            )

            if (!success) {
                return null
            }

            // Calculate memory usage in MB
            val memoryUsageMB = memoryCounters.WorkingSetSize.toLong().toDouble() / (1024 * 1024)

            // Get total system memory (this is a simplified approach)
            val totalMemoryMB = Runtime.getRuntime().maxMemory().toDouble() / (1024 * 1024)

            // Calculate memory usage as a percentage
            val memoryUsagePercent = if (totalMemoryMB > 0) {
                (memoryUsageMB / totalMemoryMB) * 100
            } else {
                0.0
            }

            return Pair(memoryUsageMB, memoryUsagePercent)
        } catch (e: Exception) {
            println("Error getting memory info for process $processId: ${e.message}")
            return null
        } finally {
            // Close the process handle
            if (handle != null && handle.pointer != Pointer.NULL) {
                Kernel32.INSTANCE.CloseHandle(handle)
            }
        }
    }

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
                    val memoryInfo = getProcessMemoryInfo(processId)
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
