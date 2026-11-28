package com.michibaum.kwswitcher.app

import com.michibaum.kwswitcher.app.cli.CliOptions
import kotlin.system.exitProcess

/**
 * Application entry point delegating CLI parsing and mode-aware port assembly to [KwSwitcherBootstrap].
 */
fun main(args: Array<String>) {
    val options = CliOptions.parse(args)
    if (options.showHelp) {
        for (warning in options.warnings) {
            System.err.println("Warning: $warning")
        }
        println(CliOptions.usageText())
        return
    }
    if (options.selfCheck) {
        val ok = KwSwitcherBootstrap.runSelfCheck(options = options)
        exitProcess(if (ok) 0 else 1)
    }

    val session = KwSwitcherBootstrap.start(options = options) ?: exitProcess(0)
    Runtime.getRuntime().addShutdownHook(
        Thread(
            { session.close() },
            "kwswitcher-shutdown-hook",
        ),
    )
    session.awaitShutdown()
    exitProcess(0)
}
