package com.michibaum.kwswitcher.app.cli

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliOptionsTest {

    @Test
    fun `default PRODUCTION profile enables hook INFO logging and unpinned overlay`() {
        val options = CliOptions.parse(emptyArray()) { null }
        assertEquals(RuntimeMode.PRODUCTION, options.mode)
        assertTrue(options.installHook)
        assertFalse(options.pinOverlay)
        assertFalse(options.openOnStart)
        assertNull(options.configPath)
        assertEquals(LogLevel.INFO, options.logLevel)
        assertFalse(options.showHelp)
    }

    @Test
    fun `dev and sandbox flags apply developer-safe defaults`() {
        val devSafe = CliOptions.parse(arrayOf("--dev", "--no-hook", "--pin-overlay")) { null }
        assertEquals(RuntimeMode.DEV, devSafe.mode)
        assertFalse(devSafe.installHook)
        assertTrue(devSafe.pinOverlay)
        assertFalse(devSafe.openOnStart)
        assertEquals(LogLevel.DEBUG, devSafe.logLevel)

        val sandbox = CliOptions.parse(arrayOf("--sandbox")) { null }
        assertEquals(RuntimeMode.SANDBOX, sandbox.mode)
        assertFalse(sandbox.installHook)
        assertTrue(sandbox.openOnStart)
        assertEquals(LogLevel.DEBUG, sandbox.logLevel)
    }

    @Test
    fun `CLI flags override kwswitcher system properties and support both spaced and equals syntax`() {
        val sysProps = mapOf(
            CliOptions.PROP_MODE to "DEV",
            CliOptions.PROP_NO_HOOK to "false",
            CliOptions.PROP_LOG_LEVEL to "WARN",
            CliOptions.PROP_CONFIG to "C:\\prop\\config.json",
        )

        val fromProps = CliOptions.parse(emptyArray()) { sysProps[it] }
        assertEquals(RuntimeMode.DEV, fromProps.mode)
        assertTrue(fromProps.installHook)
        assertEquals(LogLevel.WARN, fromProps.logLevel)
        assertEquals(Path("C:\\prop\\config.json"), fromProps.configPath)

        val overridden = CliOptions.parse(
            arrayOf("--no-hook", "--config=C:\\cli\\custom.json", "--log-level", "trace", "--open-on-start"),
        ) { sysProps[it] }
        assertEquals(RuntimeMode.DEV, overridden.mode)
        assertFalse(overridden.installHook)
        assertTrue(overridden.openOnStart)
        assertEquals(Path("C:\\cli\\custom.json"), overridden.configPath)
        assertEquals(LogLevel.TRACE, overridden.logLevel)

        val help = CliOptions.parse(arrayOf("--help")) { null }
        assertTrue(help.showHelp)
        assertTrue(CliOptions.usageText().contains("--sandbox"))
    }
}
