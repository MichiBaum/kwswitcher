package com.michibaum.kwswitcher.app

import com.michibaum.kwswitcher.app.cli.CliOptions
import com.michibaum.kwswitcher.app.cli.RuntimeMode
import com.michibaum.kwswitcher.app.logging.KwLoggingHub
import com.michibaum.kwswitcher.core.config.ConfigRepository
import com.michibaum.kwswitcher.core.config.HotkeyConfig
import com.michibaum.kwswitcher.core.config.SwitcherConfig
import com.michibaum.kwswitcher.core.config.ThemeManager
import com.michibaum.kwswitcher.core.config.ThemeOption
import com.michibaum.kwswitcher.core.coordinator.SwitcherCoordinator
import com.michibaum.kwswitcher.core.coordinator.SwitcherIntent
import com.michibaum.kwswitcher.core.port.HotkeyHookPort
import com.michibaum.kwswitcher.core.port.OverlayHookSnapshot
import com.michibaum.kwswitcher.core.port.SingleInstancePort
import com.michibaum.kwswitcher.core.port.StartupRegistrationPort
import com.michibaum.kwswitcher.core.port.WindowCatalogPort
import com.michibaum.kwswitcher.core.port.WindowControlPort
import com.michibaum.kwswitcher.core.port.WindowIconPort
import com.michibaum.kwswitcher.core.sandbox.InMemoryStartupPort
import com.michibaum.kwswitcher.core.sandbox.NoOpHotkeyHookPort
import com.michibaum.kwswitcher.core.sandbox.NoOpSingleInstancePort
import com.michibaum.kwswitcher.core.sandbox.SandboxWindowSystem
import com.michibaum.kwswitcher.core.sandbox.SyntheticWindowIconPort
import com.michibaum.kwswitcher.core.ui.SwitcherOverlay
import com.michibaum.kwswitcher.core.ui.SystemTrayController
import com.michibaum.kwswitcher.core.ui.TrayDiagnosticsCallbacks
import com.michibaum.kwswitcher.core.ui.TrayVisualStyle
import com.michibaum.kwswitcher.win32.Win32IconExtractor
import com.michibaum.kwswitcher.win32.Win32KeyboardHook
import com.michibaum.kwswitcher.win32.Win32SingleInstanceMutex
import com.michibaum.kwswitcher.win32.Win32StartupRegistry
import com.michibaum.kwswitcher.win32.Win32WindowActivator
import com.michibaum.kwswitcher.win32.Win32WindowEnumerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private val logger = KotlinLogging.logger {}

/**
 * Resolved profile defaults for a [RuntimeMode] (`DX-2`).
 */
data class ProfilePaths(
    val configPath: Path,
    val logFilePath: Path,
    val defaultConfig: SwitcherConfig,
    val trayStyle: TrayVisualStyle,
    val showPinOverlayToggle: Boolean,
)

/**
 * Active application session returned by [KwSwitcherBootstrap.start], supporting clean shutdown and test inspection.
 */
class RunningAppSession internal constructor(
    val options: CliOptions,
    val profilePaths: ProfilePaths,
    val configRepository: ConfigRepository,
    val coordinator: SwitcherCoordinator,
    val windowCatalog: WindowCatalogPort,
    val windowControl: WindowControlPort,
    private val singleInstance: SingleInstancePort,
    private val hotkeyHook: HotkeyHookPort,
    private val iconPort: WindowIconPort,
    private val overlay: SwitcherOverlay?,
    private val traySupplier: () -> SystemTrayController?,
    private val activeConfigRef: AtomicReference<SwitcherConfig>,
    private val appScope: CoroutineScope,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val shutdownLatch = CountDownLatch(1)

    val activeConfig: SwitcherConfig
        get() = activeConfigRef.get()

    fun buildDiagnosticsReport(): String = KwLoggingHub.ringBuffer.buildDiagnosticsReport(
        options = options,
        configPath = profilePaths.configPath,
        logDirectory = KwLoggingHub.logDirectory,
        activeConfig = activeConfigRef.get(),
    )

    fun awaitShutdown() {
        shutdownLatch.await()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        logger.info { "Shutting down Kotlin Switcheroo (mode=${options.mode})" }
        traySupplier()?.close()
        hotkeyHook.close()
        coordinator.close()
        appScope.cancel()
        iconPort.close()
        overlay?.dispose()
        singleInstance.close()
        shutdownLatch.countDown()
    }
}

/**
 * Mode-aware application composition root (`PRODUCTION`, `DEV`, `SANDBOX`) (`DX-2`, `DX-5`).
 */
object KwSwitcherBootstrap {

    fun resolveProfilePaths(options: CliOptions): ProfilePaths {
        val explicitConfig = options.configPath
        val resolvedConfigPath = explicitConfig ?: when (options.mode) {
            RuntimeMode.PRODUCTION -> ConfigRepository.resolveDefaultConfigPath()
            RuntimeMode.DEV -> ConfigRepository.resolveDevConfigPath()
            RuntimeMode.SANDBOX -> ConfigRepository.resolveDevConfigPath().resolveSibling("sandbox-config.json")
        }
        val baseDir = resolvedConfigPath.parent ?: Path.of(".")
        val logFilePath = baseDir.resolve("logs").resolve("kwswitcher.log")
        val defaultConfig = when (options.mode) {
            RuntimeMode.PRODUCTION -> SwitcherConfig()

            RuntimeMode.DEV, RuntimeMode.SANDBOX -> SwitcherConfig(
                hotkey = HotkeyConfig(modifier = "ALT", triggerKey = "SPACE"),
            )
        }
        val trayStyle = when (options.mode) {
            RuntimeMode.PRODUCTION -> TrayVisualStyle.PRODUCTION
            RuntimeMode.DEV -> TrayVisualStyle.DEV
            RuntimeMode.SANDBOX -> TrayVisualStyle.SANDBOX
        }
        return ProfilePaths(
            configPath = resolvedConfigPath,
            logFilePath = logFilePath,
            defaultConfig = defaultConfig,
            trayStyle = trayStyle,
            showPinOverlayToggle = options.mode != RuntimeMode.PRODUCTION || options.pinOverlay,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(
        options: CliOptions,
        enableUi: Boolean = !GraphicsEnvironment.isHeadless(),
        consoleLogging: Boolean = true,
    ): RunningAppSession? {
        val profilePaths = resolveProfilePaths(options)
        KwLoggingHub.configure(
            minLevel = options.logLevel,
            logFile = profilePaths.logFilePath,
            consoleEnabled = consoleLogging,
        )
        logger.info {
            "Starting Kotlin Switcheroo (mode=${options.mode}, hook=${options.installHook}, " +
                "pinned=${options.pinOverlay}, config=${profilePaths.configPath})"
        }

        val singleInstance = createSingleInstancePort(options.mode)
        if (!singleInstance.tryAcquire()) {
            logger.warn { "Exiting because another ${options.mode} instance is already running" }
            singleInstance.close()
            return null
        }

        return try {
            assembleSession(
                options = options,
                profilePaths = profilePaths,
                singleInstance = singleInstance,
                enableUi = enableUi,
            )
        } catch (ce: CancellationException) {
            singleInstance.close()
            throw ce
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(e) { "Fatal error during KwSwitcherBootstrap startup" }
            singleInstance.close()
            throw e
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun assembleSession(
        options: CliOptions,
        profilePaths: ProfilePaths,
        singleInstance: SingleInstancePort,
        enableUi: Boolean,
    ): RunningAppSession? {
        val configRepository = ConfigRepository(
            configPath = profilePaths.configPath,
            defaultConfig = profilePaths.defaultConfig,
        )
        val initialLoad = configRepository.loadResult()
        val activeConfig = AtomicReference(initialLoad.config)

        val appJob = SupervisorJob()
        val appScope = CoroutineScope(appJob + Dispatchers.Default)
        val win32Dispatcher = Dispatchers.IO.limitedParallelism(1)

        val ports = createPlatformPorts(
            options = options,
            activeConfig = activeConfig,
            appScope = appScope,
        )

        val coordinator = SwitcherCoordinator(
            windowCatalog = ports.catalog,
            windowControl = ports.control,
            initialPinned = options.pinOverlay,
            parentScope = appScope,
            dispatcher = win32Dispatcher,
        )

        val hook = createHotkeyHookPort(
            options = options,
            activeConfig = activeConfig,
            coordinator = coordinator,
        )
        if (!hook.start()) {
            logger.error { "Global keyboard hook failed to start; aborting session" }
            hook.close()
            coordinator.close()
            ports.icons.close()
            appScope.cancel()
            return null
        }
        coordinator.bindHook(hook)

        val trayRef = AtomicReference<SystemTrayController?>(null)
        val sessionHolder = AtomicReference<RunningAppSession?>(null)
        val overlay = if (enableUi) {
            initializeSwingUi(
                options = options,
                profilePaths = profilePaths,
                configRepository = configRepository,
                activeConfig = activeConfig,
                coordinator = coordinator,
                ports = ports,
                appScope = appScope,
                win32Dispatcher = win32Dispatcher,
                trayRef = trayRef,
                sessionSupplier = { sessionHolder.get() },
            )
        } else {
            null
        }

        val session = RunningAppSession(
            options = options,
            profilePaths = profilePaths,
            configRepository = configRepository,
            coordinator = coordinator,
            windowCatalog = ports.catalog,
            windowControl = ports.control,
            singleInstance = singleInstance,
            hotkeyHook = hook,
            iconPort = ports.icons,
            overlay = overlay,
            traySupplier = { trayRef.get() },
            activeConfigRef = activeConfig,
            appScope = appScope,
        )
        sessionHolder.set(session)

        if (options.openOnStart) {
            coordinator.dispatch(SwitcherIntent.ShowInteractive)
        }
        return session
    }

    private fun createSingleInstancePort(mode: RuntimeMode): SingleInstancePort = when (mode) {
        RuntimeMode.PRODUCTION -> Win32SingleInstanceMutex(Win32SingleInstanceMutex.DEFAULT_MUTEX_NAME)
        RuntimeMode.DEV -> Win32SingleInstanceMutex(Win32SingleInstanceMutex.DEV_MUTEX_NAME)
        RuntimeMode.SANDBOX -> NoOpSingleInstancePort()
    }

    private fun createPlatformPorts(
        options: CliOptions,
        activeConfig: AtomicReference<SwitcherConfig>,
        appScope: CoroutineScope,
    ): PlatformPorts = if (options.mode == RuntimeMode.SANDBOX) {
        val sandbox = SandboxWindowSystem()
        PlatformPorts(
            catalog = sandbox,
            control = sandbox,
            icons = SyntheticWindowIconPort(),
            startup = InMemoryStartupPort(),
        )
    } else {
        PlatformPorts(
            catalog = Win32WindowEnumerator(configProvider = { activeConfig.get() }),
            control = Win32WindowActivator(),
            icons = Win32IconExtractor(parentScope = appScope),
            startup = if (options.mode == RuntimeMode.PRODUCTION) {
                Win32StartupRegistry()
            } else {
                InMemoryStartupPort()
            },
        )
    }

    private fun createHotkeyHookPort(
        options: CliOptions,
        activeConfig: AtomicReference<SwitcherConfig>,
        coordinator: SwitcherCoordinator,
    ): HotkeyHookPort {
        if (!options.installHook || options.mode == RuntimeMode.SANDBOX) {
            logger.info { "Running with NoOpHotkeyHookPort (breakpoint-safe, WH_KEYBOARD_LL disabled)" }
            return NoOpHotkeyHookPort()
        }
        return Win32KeyboardHook(
            hotkeyConfigProvider = { activeConfig.get().hotkey },
            overlayStateProvider = {
                val state = coordinator.state.value
                if (!state.isVisible) {
                    OverlayHookSnapshot.HIDDEN
                } else {
                    OverlayHookSnapshot(mode = state.mode, isQueryEmpty = state.query.isEmpty())
                }
            },
        )
    }

    @Suppress("LongParameterList")
    private fun initializeSwingUi(
        options: CliOptions,
        profilePaths: ProfilePaths,
        configRepository: ConfigRepository,
        activeConfig: AtomicReference<SwitcherConfig>,
        coordinator: SwitcherCoordinator,
        ports: PlatformPorts,
        appScope: CoroutineScope,
        win32Dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        trayRef: AtomicReference<SystemTrayController?>,
        sessionSupplier: () -> RunningAppSession?,
    ): SwitcherOverlay {
        var createdOverlay: SwitcherOverlay? = null
        SwingUtilities.invokeAndWait {
            val initialPalette = ThemeManager.applyTheme(activeConfig.get().theme, updateExistingComponents = false)
            createdOverlay = SwitcherOverlay(
                coordinator = coordinator,
                iconPort = ports.icons,
                activeMonitorBoundsProvider = { ports.catalog.getActiveMonitorWorkArea() },
                onRequestForegroundFocus = {
                    appScope.launch(win32Dispatcher) {
                        ports.control.activateOverlay()
                        createdOverlay?.ensureSearchFocus()
                    }
                },
                initialConfig = activeConfig.get(),
                initialPalette = initialPalette,
            )
        }
        val overlay = requireNotNull(createdOverlay) { "SwitcherOverlay failed to initialize on Swing EDT" }

        appScope.launch(Dispatchers.Swing) {
            coordinator.state.collect { state ->
                overlay.render(state)
                trayRef.get()?.updatePinnedState(state.pinned)
            }
        }

        val tray = buildTrayController(
            options = options,
            profilePaths = profilePaths,
            configRepository = configRepository,
            activeConfig = activeConfig,
            coordinator = coordinator,
            ports = ports,
            overlay = overlay,
            sessionSupplier = sessionSupplier,
        )
        tray.install(initialTheme = activeConfig.get().theme)
        trayRef.set(tray)
        return overlay
    }

    @Suppress("LongParameterList")
    private fun buildTrayController(
        options: CliOptions,
        profilePaths: ProfilePaths,
        configRepository: ConfigRepository,
        activeConfig: AtomicReference<SwitcherConfig>,
        coordinator: SwitcherCoordinator,
        ports: PlatformPorts,
        overlay: SwitcherOverlay,
        sessionSupplier: () -> RunningAppSession?,
    ): SystemTrayController = SystemTrayController(
        onShowSwitcher = { coordinator.dispatch(SwitcherIntent.ShowInteractive) },
        onReloadConfig = {
            val reloaded = configRepository.loadOrCreate()
            activeConfig.set(reloaded)
            ports.icons.clearCache()
            SwingUtilities.invokeLater {
                val palette = ThemeManager.applyTheme(reloaded.theme)
                overlay.updateConfigAndTheme(reloaded, palette)
            }
        },
        onSelectTheme = { theme: ThemeOption ->
            val updated = activeConfig.updateAndGet { it.copy(theme = theme) }
            configRepository.save(updated)
            SwingUtilities.invokeLater {
                val palette = ThemeManager.applyTheme(theme)
                overlay.updateConfigAndTheme(updated, palette)
            }
        },
        onQuit = { sessionSupplier()?.close() },
        startupPort = ports.startup,
        visualStyle = profilePaths.trayStyle,
        diagnostics = TrayDiagnosticsCallbacks(
            showPinOverlayToggle = profilePaths.showPinOverlayToggle,
            initialPinned = options.pinOverlay,
            onTogglePinOverlay = { pinned -> coordinator.dispatch(SwitcherIntent.SetPinned(pinned)) },
            onOpenLogDirectory = { openLogDirectory(KwLoggingHub.logDirectory) },
            onCopyDiagnostics = {
                sessionSupplier()?.let { copyDiagnosticsToClipboard(it.buildDiagnosticsReport()) }
            },
        ),
    )

    private fun openLogDirectory(logDir: Path?) {
        if (logDir == null) {
            logger.warn { "Log directory is not configured" }
            return
        }
        try {
            java.nio.file.Files.createDirectories(logDir)
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(logDir.toFile())
            } else {
                logger.info { "Desktop API unsupported; log directory is at $logDir" }
            }
        } catch (e: IOException) {
            logger.warn(e) { "Failed opening log directory $logDir" }
        } catch (e: UnsupportedOperationException) {
            logger.warn(e) { "Opening directories is unsupported on this platform ($logDir)" }
        }
    }

    private fun copyDiagnosticsToClipboard(report: String) {
        try {
            val selection = StringSelection(report)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
            logger.info { "Copied diagnostics report (${report.length} chars) to system clipboard" }
        } catch (e: HeadlessException) {
            logger.warn(e) { "Clipboard unavailable in headless environment" }
        } catch (e: IllegalStateException) {
            logger.warn(e) { "System clipboard is currently unavailable" }
        }
    }

    private data class PlatformPorts(
        val catalog: WindowCatalogPort,
        val control: WindowControlPort,
        val icons: WindowIconPort,
        val startup: StartupRegistrationPort,
    )
}
