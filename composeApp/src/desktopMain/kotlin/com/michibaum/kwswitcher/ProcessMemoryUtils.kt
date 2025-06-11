package com.michibaum.kwswitcher

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinNT.HANDLE

/**
 * Utility class for process memory operations
 */
class ProcessMemoryUtils {
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
}
