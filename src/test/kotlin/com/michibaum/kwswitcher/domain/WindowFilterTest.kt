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

    @Test
    fun `ranks exact prefix above word-boundary acronym substring and fuzzy subsequence`() {
        val candidates = listOf(
            WindowInfo(WindowHandle(1L), "My Power Document", "notepad", "C:\\notepad.exe", zOrder = 0),
            WindowInfo(WindowHandle(2L), "Windows PowerShell", "pwsh", "C:\\pwsh.exe", zOrder = 1),
            WindowInfo(WindowHandle(3L), "PowerToys Settings", "PowerToys", "C:\\PowerToys.exe", zOrder = 2),
        )

        val ranked = WindowFilter.match(windows = candidates, query = "power")
        assertEquals(3, ranked.size)
        // Exact prefix on title/process ("PowerToys") ranks above word-boundary ("Windows PowerShell" / "My Power Document")
        assertEquals(candidates[2], ranked[0].window)
        assertEquals(listOf(0..4), ranked[0].titleHighlights)
    }

    @Test
    fun `supports camelCase and word-boundary acronym matching with highlight spans`() {
        val matches = WindowFilter.match(windows = sampleWindows, query = "wp")
        // "Windows PowerShell" has word-boundary initials 'W' (0) and 'P' (8)
        assertTrue(matches.isNotEmpty())
        assertEquals(sampleWindows[1], matches.first().window)
        assertEquals(listOf(0..0, 8..8), matches.first().titleHighlights)
    }

    @Test
    fun `merges overlapping and adjacent highlight ranges`() {
        val merged = WindowMatch.mergeRanges(listOf(4..7, 0..2, 2..4, 10..12))
        assertEquals(listOf(0..7, 10..12), merged)
    }
}
