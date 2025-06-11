package com.michibaum.kwswitcher

import com.sun.jna.Structure
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.BaseTSD.SIZE_T

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