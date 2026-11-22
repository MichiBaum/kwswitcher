package com.michibaum.kwswitcher.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowFilterTest {

    private val sampleWindows = listOf(
        WindowInfo(
            hwnd = WindowHandle(101L),
            title = "kwswitcher - Main.kt - IntelliJ IDEA",
            processName = "idea64",
            exePath = "C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe",
            zOrder = 0,
        ),
        WindowInfo(
            hwnd = WindowHandle(102L),
            title = "Windows PowerShell",
            processName = "powershell",
            exePath = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
            zOrder = 1,
        ),
        WindowInfo(
            hwnd = WindowHandle(103L),
            title = "Kotlin Foreign Function & Memory API - Mozilla Firefox",
            processName = "firefox",
            exePath = "C:\\Program Files\\Mozilla Firefox\\firefox.exe",
            zOrder = 2,
        ),
    )

    @Test
    fun `blank query returns all windows in original zOrder`() {
        val result = WindowFilter.filter(windows = sampleWindows, query = "   ")
        assertEquals(sampleWindows, result)
    }

    @Test
    fun `matches window by title or process name case-insensitively`() {
        val byProcess = WindowFilter.filter(windows = sampleWindows, query = "IDEA")
        assertEquals(listOf(sampleWindows[0]), byProcess)

        val byTitle = WindowFilter.filter(windows = sampleWindows, query = "firefox kotlin")
        assertEquals(listOf(sampleWindows[2]), byTitle)
    }

    @Test
    fun `non-matching query returns empty list`() {
        val result = WindowFilter.filter(windows = sampleWindows, query = "nonexistent-window-xyz")
        assertTrue(result.isEmpty())
    }
}
