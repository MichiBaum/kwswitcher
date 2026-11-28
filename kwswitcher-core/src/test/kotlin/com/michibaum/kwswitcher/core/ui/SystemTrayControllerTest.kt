package com.michibaum.kwswitcher.core.ui

import com.michibaum.kwswitcher.core.config.ThemeOption
import com.michibaum.kwswitcher.core.port.StartupRegistrationPort
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.SystemTray
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SystemTrayControllerTest {

    @Test
    fun `updateSelectedTheme synchronizes radio checkmarks across theme menu items`() {
        SystemTrayController(
            onShowSwitcher = {},
            onReloadConfig = {},
            onSelectTheme = {},
            onQuit = {},
        ).use { controller ->
            controller.buildPopupMenu(initialTheme = ThemeOption.SYSTEM)
            assertEquals(true, controller.themeItems[ThemeOption.SYSTEM]?.state)
            assertEquals(false, controller.themeItems[ThemeOption.DARK]?.state)
            assertEquals(false, controller.themeItems[ThemeOption.LIGHT]?.state)

            controller.updateSelectedTheme(ThemeOption.LIGHT)
            assertEquals(false, controller.themeItems[ThemeOption.SYSTEM]?.state)
            assertEquals(false, controller.themeItems[ThemeOption.DARK]?.state)
            assertEquals(true, controller.themeItems[ThemeOption.LIGHT]?.state)

            controller.updateSelectedTheme(ThemeOption.DARK)
            assertEquals(false, controller.themeItems[ThemeOption.SYSTEM]?.state)
            assertEquals(true, controller.themeItems[ThemeOption.DARK]?.state)
            assertEquals(false, controller.themeItems[ThemeOption.LIGHT]?.state)
        }
    }

    @Test
    fun `unsupported StartupRegistrationPort disables item unless legacy entry exists for cleanup`() {
        val disabledPort = object : StartupRegistrationPort {
            override val isSupported: Boolean = false
            override fun isRegistered(): Boolean = false
            override fun setRegistered(enabled: Boolean): Boolean = false
        }

        SystemTrayController(
            onShowSwitcher = {},
            onReloadConfig = {},
            onSelectTheme = {},
            onQuit = {},
            startupPort = disabledPort,
        ).use { controller ->
            controller.buildPopupMenu()
            val item = assertNotNull(controller.startupMenuItem)
            assertFalse(item.state)
            assertFalse(item.isEnabled)
        }

        // Legacy registered entry when unsupported stays enabled until unchecked, then disables itself
        var legacyRegistered = true
        val legacyPort = object : StartupRegistrationPort {
            override val isSupported: Boolean = false
            override fun isRegistered(): Boolean = legacyRegistered
            override fun setRegistered(enabled: Boolean): Boolean {
                if (enabled) return false
                legacyRegistered = false
                return true
            }
        }
        val bgTasks = ArrayDeque<Runnable>()
        val uiTasks = ArrayDeque<Runnable>()

        SystemTrayController(
            onShowSwitcher = {},
            onReloadConfig = {},
            onSelectTheme = {},
            onQuit = {},
            startupPort = legacyPort,
            backgroundExecutor = { bgTasks.addLast(it) },
            uiExecutor = { uiTasks.addLast(it) },
        ).use { controller ->
            controller.buildPopupMenu()
            val item = assertNotNull(controller.startupMenuItem)
            assertTrue(item.state)
            assertTrue(item.isEnabled)

            item.state = false
            controller.handleStartupToggle(item)
            assertEquals(1, bgTasks.size)
            bgTasks.removeFirst().run()
            assertEquals(1, uiTasks.size)
            uiTasks.removeFirst().run()

            assertFalse(legacyRegistered)
            assertFalse(item.isEnabled)
        }
    }

    @Test
    fun `handleStartupToggle offloads setRegistered to backgroundExecutor and rolls back checkbox on failure`() {
        val setCalls = AtomicInteger(0)
        val failingPort = object : StartupRegistrationPort {
            override val isSupported: Boolean = true
            override fun isRegistered(): Boolean = false
            override fun setRegistered(enabled: Boolean): Boolean {
                setCalls.incrementAndGet()
                return false
            }
        }
        val bgTasks = ArrayDeque<Runnable>()
        val uiTasks = ArrayDeque<Runnable>()

        SystemTrayController(
            onShowSwitcher = {},
            onReloadConfig = {},
            onSelectTheme = {},
            onQuit = {},
            startupPort = failingPort,
            backgroundExecutor = { bgTasks.addLast(it) },
            uiExecutor = { uiTasks.addLast(it) },
        ).use { controller ->
            controller.buildPopupMenu()
            val item = assertNotNull(controller.startupMenuItem)
            assertTrue(item.isEnabled)
            assertFalse(item.state)

            // User checks item -> setRegistered has NOT run synchronously on EDT
            item.state = true
            controller.handleStartupToggle(item)
            assertEquals(0, setCalls.get())
            assertEquals(1, bgTasks.size)

            // Background worker executes and queues rollback onto uiExecutor
            bgTasks.removeFirst().run()
            assertEquals(1, setCalls.get())
            assertTrue(item.state)
            assertEquals(1, uiTasks.size)

            // UI rollback restores unchecked state
            uiTasks.removeFirst().run()
            assertFalse(item.state)
        }
    }

    @Test
    fun `install is idempotent and close removes single TrayIcon cleanly`() {
        assumeTrue(SystemTray.isSupported())
        val systemTray = SystemTray.getSystemTray()
        val baselineCount = systemTray.trayIcons.size

        SystemTrayController(
            onShowSwitcher = {},
            onReloadConfig = {},
            onSelectTheme = {},
            onQuit = {},
        ).use { controller ->
            assertTrue(controller.install(initialTheme = ThemeOption.DARK))
            assertEquals(baselineCount + 1, systemTray.trayIcons.size)

            // Second install() call must be an idempotent no-op
            assertTrue(controller.install(initialTheme = ThemeOption.LIGHT))
            assertEquals(baselineCount + 1, systemTray.trayIcons.size)
            assertEquals(true, controller.themeItems[ThemeOption.DARK]?.state)
        }

        assertEquals(baselineCount, systemTray.trayIcons.size)
    }
}
