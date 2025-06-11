package com.michibaum.kwswitcher

import kotlin.test.Test
import kotlin.test.assertTrue

class WindowsApiTest {
    
    @Test
    fun testGetOpenWindows() {
        // Only run this test on Windows
        if (System.getProperty("os.name").lowercase().contains("windows")) {
            val windowsApi = WindowsApi()
            val windows = windowsApi.getOpenWindows()
            
            // Print out the windows for debugging
            println("[DEBUG_LOG] Found ${windows.size} open windows:")
            windows.take(10).forEach { window ->
                println("[DEBUG_LOG] Window: ${window.title} (PID: ${window.processId}, Visible: ${window.isVisible}, Minimized: ${window.isMinimized})")
            }
            
            // There should be at least one window (the test runner itself)
            assertTrue(windows.isNotEmpty(), "Should find at least one open window")
            
            // Test with only visible windows
            val visibleWindows = windowsApi.getOpenWindows(visibleOnly = true)
            println("[DEBUG_LOG] Found ${visibleWindows.size} visible windows")
            
            // Test with all windows including invisible ones
            val allWindows = windowsApi.getOpenWindows(visibleOnly = false)
            println("[DEBUG_LOG] Found ${allWindows.size} total windows (visible and invisible)")
            
            // All windows should include at least as many windows as visible windows
            assertTrue(allWindows.size >= visibleWindows.size, 
                "Total window count should be greater than or equal to visible window count")
        } else {
            println("[DEBUG_LOG] Skipping test on non-Windows platform")
        }
    }
}