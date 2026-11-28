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
        assertTrue(CliOptions.usageText().contains("--self-check"))
    }

    @Test
    fun `self-check flag forces safe SANDBOX profile without opening visible window by default`() {
        val selfCheck = CliOptions.parse(arrayOf("--self-check")) { null }
        assertTrue(selfCheck.selfCheck)
        assertEquals(RuntimeMode.SANDBOX, selfCheck.mode)
        assertFalse(selfCheck.installHook)
        assertFalse(selfCheck.openOnStart)
        assertTrue(selfCheck.warnings.isEmpty())

        val fromProp = CliOptions.parse(emptyArray()) { key ->
            if (key == CliOptions.PROP_SELF_CHECK) "true" else null
        }
        assertTrue(fromProp.selfCheck)
        assertEquals(RuntimeMode.SANDBOX, fromProp.mode)
    }

    @Test
    fun `unknown flags invalid log levels and missing option values populate actionable warnings`() {
        val parsed = CliOptions.parse(
            arrayOf("--pin", "--log-level", "VERBOSE", "--config"),
        ) { null }
        assertEquals(3, parsed.warnings.size)
        assertTrue(parsed.warnings[0].contains("--pin"))
        assertTrue(parsed.warnings[1].contains("VERBOSE"))
        assertTrue(parsed.warnings[2].contains("--config"))

        val equalsErrors = CliOptions.parse(
            arrayOf("--config=", "--log-level=LOUD", "--log-level"),
        ) { null }
        assertEquals(3, equalsErrors.warnings.size)
    }
}
