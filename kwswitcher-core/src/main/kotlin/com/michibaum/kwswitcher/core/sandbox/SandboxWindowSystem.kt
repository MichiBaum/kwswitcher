package com.michibaum.kwswitcher.core.sandbox

import com.michibaum.kwswitcher.core.domain.WindowHandle
import com.michibaum.kwswitcher.core.domain.WindowInfo
import com.michibaum.kwswitcher.core.port.NativeResult
import com.michibaum.kwswitcher.core.port.WindowCatalogPort
import com.michibaum.kwswitcher.core.port.WindowControlPort
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Rectangle

private val logger = KotlinLogging.logger {}

/**
 * Interactive, cross-platform in-memory window backend (`--sandbox` mode and integration tests).
 * Supports realistic multi-monitor, minimized, shared-PID, and long-title window fixtures
 * with live focus promotion, graceful close, minimize, and process-wide termination (`DX-2`).
 */
class SandboxWindowSystem(
    initialWindows: List<WindowInfo> = defaultSampleWindows(),
    private val workArea: Rectangle? = DEFAULT_WORK_AREA,
) : WindowCatalogPort,
    WindowControlPort {

    private val lock = Any()
    private val seedWindows: List<WindowInfo> = reindexZOrder(initialWindows)
    private var windows: List<WindowInfo> = seedWindows

    override fun enumerateOpenWindows(): List<WindowInfo> = synchronized(lock) {
        windows
    }

    override fun getActiveMonitorWorkArea(): Rectangle? = workArea?.let(::Rectangle)

    override fun activate(handle: WindowHandle): NativeResult = synchronized(lock) {
        val target = windows.firstOrNull { it.hwnd == handle }
            ?: return NativeResult.Failed(
                operation = "SandboxWindowSystem.activate",
                detail = "Window handle ${handle.value} not found",
            )
        val remaining = windows.filterNot { it.hwnd == handle }
        windows = reindexZOrder(listOf(target.copy(isMinimized = false)) + remaining)
        logger.debug { "Sandbox activated window '${target.title}' (hwnd=${handle.value})" }
        NativeResult.Ok
    }

    override fun activateOverlay(overlayTitle: String): NativeResult {
        logger.debug { "Sandbox acknowledged overlay focus request ('$overlayTitle')" }
        return NativeResult.Ok
    }

    override fun closeGracefully(handle: WindowHandle): NativeResult = synchronized(lock) {
        val target = windows.firstOrNull { it.hwnd == handle }
            ?: return NativeResult.Failed(
                operation = "SandboxWindowSystem.closeGracefully",
                detail = "Window handle ${handle.value} not found",
            )
        windows = reindexZOrder(windows.filterNot { it.hwnd == handle })
        logger.debug { "Sandbox closed window '${target.title}' (hwnd=${handle.value})" }
        NativeResult.Ok
    }

    override fun minimize(handle: WindowHandle): NativeResult = synchronized(lock) {
        val target = windows.firstOrNull { it.hwnd == handle }
            ?: return NativeResult.Failed(
                operation = "SandboxWindowSystem.minimize",
                detail = "Window handle ${handle.value} not found",
            )
        windows = windows.map { win ->
            if (win.hwnd == handle) win.copy(isMinimized = true) else win
        }
        logger.debug { "Sandbox minimized window '${target.title}' (hwnd=${handle.value})" }
        NativeResult.Ok
    }

    override fun terminateProcess(pid: Int): NativeResult = synchronized(lock) {
        if (pid <= 0) {
            return NativeResult.Failed(
                operation = "SandboxWindowSystem.terminateProcess",
                detail = "Invalid PID $pid",
            )
        }
        val matching = windows.filter { it.pid == pid }
        if (matching.isEmpty()) {
            return NativeResult.Failed(
                operation = "SandboxWindowSystem.terminateProcess",
                detail = "No sandbox windows owned by PID $pid",
            )
        }
        windows = reindexZOrder(windows.filterNot { it.pid == pid })
        logger.debug { "Sandbox terminated PID $pid (${matching.size} window(s) removed)" }
        NativeResult.Ok
    }

    /**
     * Restores the sandbox window list to its initial seed state.
     */
    fun reset() = synchronized(lock) {
        windows = seedWindows
    }

    companion object {
        private const val WORK_AREA_WIDTH = 1920
        private const val WORK_AREA_HEIGHT = 1040
        val DEFAULT_WORK_AREA = Rectangle(0, 0, WORK_AREA_WIDTH, WORK_AREA_HEIGHT)

        private const val HWND_IDEA_MAIN = 1001L
        private const val HWND_FIREFOX = 1002L
        private const val HWND_TERMINAL = 1003L
        private const val HWND_SLACK = 1004L
        private const val HWND_IDEA_DOCS = 1005L
        private const val HWND_NOTEPAD = 1006L

        private const val IDEA_PID = 4120
        private const val FIREFOX_PID = 5840
        private const val TERMINAL_PID = 6310
        private const val SLACK_PID = 7190
        private const val NOTEPAD_PID = 8450

        fun defaultSampleWindows(): List<WindowInfo> = reindexZOrder(
            listOf(
                WindowInfo(
                    hwnd = WindowHandle(HWND_IDEA_MAIN),
                    title = "kwswitcher - SwitcherCoordinator.kt - IntelliJ IDEA",
                    processName = "idea64",
                    exePath = "C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe",
                    zOrder = 0,
                    pid = IDEA_PID,
                    isMinimized = false,
                    monitorId = "Mon 1",
                ),
                WindowInfo(
                    hwnd = WindowHandle(HWND_FIREFOX),
                    title = "Panama Foreign Function & Memory API - Mozilla Firefox",
                    processName = "firefox",
                    exePath = "C:\\Program Files\\Mozilla Firefox\\firefox.exe",
                    zOrder = 0,
                    pid = FIREFOX_PID,
                    isMinimized = false,
                    monitorId = "Mon 1",
                ),
                WindowInfo(
                    hwnd = WindowHandle(HWND_TERMINAL),
                    title = "pwsh - .\\mvnw.cmd verify",
                    processName = "WindowsTerminal",
                    exePath = "C:\\Users\\dev\\AppData\\Local\\Microsoft\\WindowsApps\\wt.exe",
                    zOrder = 0,
                    pid = TERMINAL_PID,
                    isMinimized = false,
                    monitorId = "Mon 2",
                ),
                WindowInfo(
                    hwnd = WindowHandle(HWND_SLACK),
                    title = "#engineering-dx | Switcheroo Contributors - Slack",
                    processName = "slack",
                    exePath = "C:\\Users\\dev\\AppData\\Local\\slack\\slack.exe",
                    zOrder = 0,
                    pid = SLACK_PID,
                    isMinimized = true,
                    monitorId = "Mon 2",
                ),
                WindowInfo(
                    hwnd = WindowHandle(HWND_IDEA_DOCS),
                    title = "kwswitcher - WindowListCellRenderer.kt - " +
                        "VeryLongProjectDescriptionForTruncationAndHighlightVerificationInSandboxMode - IntelliJ IDEA",
                    processName = "idea64",
                    exePath = "C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\idea64.exe",
                    zOrder = 0,
                    pid = IDEA_PID,
                    isMinimized = false,
                    monitorId = "Mon 2",
                ),
                WindowInfo(
                    hwnd = WindowHandle(HWND_NOTEPAD),
                    title = "release-checklist.md - Notepad",
                    processName = "notepad",
                    exePath = "C:\\Windows\\System32\\notepad.exe",
                    zOrder = 0,
                    pid = NOTEPAD_PID,
                    isMinimized = true,
                    monitorId = "Mon 1",
                ),
            ),
        )

        private fun reindexZOrder(items: List<WindowInfo>): List<WindowInfo> =
            items.mapIndexed { idx, win -> win.copy(zOrder = idx) }
    }
}
