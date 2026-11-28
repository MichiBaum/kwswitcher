# Kotlin Switcheroo (`kwswitcher`) — Bugs & Pre-Release Fixes

> Findings from the review of commit `81381772d0dd7ed79eb48f1d436e5c3eaab0050c` (`develpoer-friendly-and-separation` vs. `develop`). Organized by priority so items can be fixed incrementally before tagging the `1.2.0` release.

---

## 1. Confirmed Bugs

### 1.1 Clicking a non-selected window row activates the wrong window (`kwswitcher-core`)
- **Locations:**
  - `kwswitcher-core/src/main/kotlin/com/michibaum/kwswitcher/core/ui/SwitcherOverlay.kt` (lines `253–262`, `277–283`)
  - `kwswitcher-core/src/main/kotlin/com/michibaum/kwswitcher/core/coordinator/SwitcherIntent.kt`
  - `kwswitcher-core/src/main/kotlin/com/michibaum/kwswitcher/core/coordinator/SwitcherReducer.kt` (lines `264–272`)
- **Problem:**
  1. Clicking a row in `windowList` updates `windowList.selectedIndex` locally in Swing on `mousePressed`, and `mouseClicked` dispatches `SwitcherIntent.ConfirmSelection`. However, `SwitcherCoordinator` is never informed of the clicked row index (`SwitcherIntent` has no index-targeted selection/confirm intent, and `windowList` has no selection listener). `SwitcherReducer.reduceConfirmSelection` therefore activates `current.selectedWindow` at the old keyboard `selectedIndex` instead of the row the user clicked.
  2. Clicking blank space below the last rendered row in `JList` still fires `mouseClicked` while `windowList.selectedIndex >= 0`, unexpectedly confirming selection.
  3. `windowList` defaults to `isFocusable = true`, so a press-with-slight-drag (which does not fire `mouseClicked`) steals focus away from `searchField`.
  4. Both the mouse click handler (`253–262`) and `Enter`/`Esc` key handler (`277–283`) set `dialog.isVisible = false` eagerly before `coordinator.dispatch(...)` processes the intent. If the intent is dropped or no-ops, the dialog hides while `coordinator.state` remains visible.
- **Recommended Fix:**
  - Set `windowList.isFocusable = false`.
  - Resolve the clicked index with `val idx = windowList.locationToIndex(e.point)` and verify `idx >= 0 && windowList.getCellBounds(idx, idx)?.contains(e.point) == true`.
  - Add `SwitcherIntent.ConfirmAtIndex(val index: Int)` (or `SelectIndex` + `ConfirmSelection`) in `SwitcherIntent` / `SwitcherReducer`.
  - Stop hiding `dialog.isVisible = false` imperatively inside input listeners; let `render(state)` hide the dialog once the reducer transitions to hidden.

### 1.2 Malformed or BOM-prefixed `config.json` falls back to defaults and gets overwritten on theme switch (`kwswitcher-core`, `kwswitcher-app`)
- **Locations:**
  - `kwswitcher-core/src/main/kotlin/com/michibaum/kwswitcher/core/config/ConfigRepository.kt` (lines `55–68`, `90–96`)
  - `kwswitcher-app/src/main/kotlin/com/michibaum/kwswitcher/app/KwSwitcherBootstrap.kt` (lines `414–417`)
- **Problem:**
  1. `1.1.x` used a permissive regex parser that tolerated UTF-8 BOMs (`\uFEFF`, written by Windows Notepad and PowerShell 5), comments, and trailing commas. The `kotlinx-serialization-json` parser rejects all three with `SerializationException` and falls back to `SwitcherConfig()` defaults for the entire file.
  2. If `configRepository.load()` returns `ConfigLoadResult.FallbackOnError` and the user then switches themes via the System Tray (`onSelectTheme`), `configRepository.save(updated)` **overwrites `config.json` on disk with default values**, permanently wiping the user's custom `hotkey`, `ignoredProcesses`, and `ignoredTitlePatterns`.
- **Recommended Fix:**
  - Strip leading UTF-8 BOM in `ConfigRepository.parseJson` (`json.removePrefix("\uFEFF")`).
  - Enable `allowTrailingComma = true` and `allowComments = true` (`@OptIn(ExperimentalSerializationApi::class)`) on `ConfigRepository.jsonCodec`.
  - Track whether the active config came from `FallbackOnError`, and back up the broken file (e.g., `config.json.corrupt.bak`) before `save()` overwrites it (or refuse automatic overwrite until fixed).

### 1.3 Single-instance OS mutex leaked if `hook.start()` fails (`kwswitcher-app`)
- **Location:** `kwswitcher-app/src/main/kotlin/com/michibaum/kwswitcher/app/KwSwitcherBootstrap.kt` (lines `173–195`, `234–241`)
- **Problem:** `KwSwitcherBootstrap.start` acquires `singleInstance.tryAcquire()`, then delegates to `assembleSession(...)`. If `hook.start()` returns `false`, `assembleSession` closes `hook`, `coordinator`, `ports.icons`, and `appScope` and returns `null` without closing `singleInstance`. Because no exception is thrown, `start()`'s `catch` block does not run `singleInstance.close()`, leaking the Win32 mutex handle for programmatic/test callers.
- **Recommended Fix:** Call `singleInstance.close()` in the `!hook.start()` failure branch (or ensure `start()` closes `singleInstance` whenever `assembleSession(...)` returns `null`).

### 1.4 System Tray "Theme" radio checkmarks go out of sync after "Reload Config" (`kwswitcher-core`, `kwswitcher-app`)
- **Locations:**
  - `kwswitcher-core/src/main/kotlin/com/michibaum/kwswitcher/core/ui/SystemTrayController.kt` (lines `126–141`)
  - `kwswitcher-app/src/main/kotlin/com/michibaum/kwswitcher/app/KwSwitcherBootstrap.kt` (lines `405–413`)
- **Problem:** Editing `"theme"` in `config.json` and clicking `Reload Config` updates the Swing L&F and overlay palette, but `SystemTrayController` stores `themeItems` only as a local variable inside `buildThemeMenu` and exposes no `updateSelectedTheme(theme: ThemeOption)` method, leaving the tray radio checkmark on the previous theme.
- **Recommended Fix:** Promote `themeItems` to an instance property on `SystemTrayController`, add `updateSelectedTheme(theme: ThemeOption)`, and invoke it from `KwSwitcherBootstrap`'s `onReloadConfig` callback.

### 1.5 `DiagnosticsRingBuffer` reports stale `pinOverlay` state, double-snapshots non-atomically, and throws when `maxEntries <= 0` (`kwswitcher-app`)
- **Locations:**
  - `kwswitcher-app/src/main/kotlin/com/michibaum/kwswitcher/app/logging/DiagnosticsRingBuffer.kt` (lines `12–20`, `45`, `62–65`)
  - `kwswitcher-app/src/main/kotlin/com/michibaum/kwswitcher/app/KwSwitcherBootstrap.kt` (lines `94–99`)
- **Problem:**
  1. `DiagnosticsRingBuffer.formatDiagnosticsReport` prints `options.pinOverlay` (initial CLI flag) rather than `coordinator.state.value.pinned`, so toggling `Pin Overlay` from the tray menu is never reflected in `Copy Diagnostics`.
  2. Lines `62–63` call `snapshot()` twice (`snapshot().size` then `for (line in snapshot())`), which copies the ring buffer twice and can mismatch under concurrent logging.
  3. Line `12` initializes `ArrayDeque(maxEntries.coerceAtLeast(1))`, whereas `append` checks `if (entries.size >= maxEntries) entries.removeFirst()`, throwing `NoSuchElementException` if `maxEntries <= 0`.
- **Recommended Fix:** Coerce `val capacity = maxEntries.coerceAtLeast(1)` once and use `capacity` in `append`; capture `val lines = snapshot()` into a local variable in `formatDiagnosticsReport`; read live pin state from `state.pinned`.

### 1.6 Documented `exec:exec` command fails on fresh clones and uses stale `~/.m2` artifacts (`README.md`, `Writerside`)
- **Locations:**
  - `README.md` (line `31`)
  - `Writerside/topics/building-and-releasing.md` (line `37`)
  - `kwswitcher-app/pom.xml` (lines `197–216`)
- **Problem:** `.\mvnw.cmd test-compile exec:exec -pl kwswitcher-app` restricts the Maven reactor to `kwswitcher-app` only. On a clean clone, Maven fails to resolve `kwswitcher-core:1.2.0-SNAPSHOT` and `kwswitcher-win32:1.2.0-SNAPSHOT` because they are not in `~/.m2`. Worse, if an older snapshot exists in `~/.m2`, local source changes in `kwswitcher-core` or `kwswitcher-win32` are silently ignored! The same caveat applies to running `-pl kwswitcher-app package -Pjpackage` directly.
- **Recommended Fix:** Update `README.md` and `Writerside/topics/building-and-releasing.md` to run `.\mvnw.cmd test-compile install -DskipTests` before `exec:exec -pl kwswitcher-app` (or recommend the shared IntelliJ `.run/` configurations / root reactor commands).

### 1.7 Fat-JAR "Run at Windows Startup" registers bare `java.exe` (`kwswitcher-win32`, `kwswitcher-app`)
- **Location:** `kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32StartupRegistry.kt` (lines `176–179`)
- **Problem:** `release.yml` publishes `kwswitcher-X.Y.Z-jar-with-dependencies.jar`, which runs in `PRODUCTION` mode by default (`--dev` and `--sandbox` already use `InMemoryStartupPort`). When launched via `java -jar`, `ProcessHandle.current().info().command()` resolves to `C:\...\java.exe` (or `javaw.exe`) with no `-jar` arguments. Toggling `Run at Windows Startup` writes bare `"C:\...\java.exe"` into `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`. Additionally, the `.orElse("kwswitcher.exe")` fallback is a relative path that will not resolve from the `Run` key.
- **Recommended Fix:** Detect when the executable filename is `java.exe` / `javaw.exe` (or when no absolute native launcher path is available) and refuse/disable registry startup registration with a clear warning log.

---

## 2. Important Pre-Release Reliability & Safety Fixes

### 2.1 Guard Panama FFM upcalls against uncaught exceptions (`kwswitcher-win32`)
- **Locations:**
  - `kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32KeyboardHook.kt` (lines `97–99`, `148–170`)
  - `kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32WindowEnumerator.kt` (lines `59–80`, `324–326`)
- **Problem:** Per the JDK 25 Panama `Linker.upcallStub` specification, **any uncaught exception escaping an upcall target immediately aborts the entire JVM process**. Neither `Win32KeyboardHook.handleLowLevelKey` (which calls `hotkeyConfigProvider()` and `overlayStateProvider()`) nor `Win32WindowEnumerator.dispatchCallback` (which runs window inspection and regex filtering) wraps its body in a top-level `try { ... } catch (e: Exception)`.
- **Recommended Fix:** Wrap both upcall entrypoints in `try { ... } catch (e: Exception)` (do not catch `Throwable`). On error, log and fall back safely (`CallNextHookEx` or `0L` in the hook; `return 1` to continue enumeration in the enumerator).

### 2.2 Capture Win32 `GetLastError` via `Linker.Option.captureCallState("GetLastError")` (`kwswitcher-win32`)
- **Locations:**
  - `kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/native/MutexNative.kt` (lines `34–39`)
  - `kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32SingleInstanceMutex.kt` (lines `39–40`)
  - `kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32ErrorFormatter.kt` (line `21`)
  - `kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32KeyboardHook.kt` (line `115`)
  - `kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32WindowActivator.kt` (lines `69`, `120`, `151`, `161`)
  - `kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32WindowEnumerator.kt` (lines `87`, `126`)
- **Problem:** Reading `GetLastError` via a separate subsequent Panama downcall is unsafe because JVM transitions, class initialization, safepoints, or logging between the two downcalls can overwrite the OS thread's `LastError`. In `Win32SingleInstanceMutex.tryAcquire()`, if `ERROR_ALREADY_EXISTS` (`183`) is clobbered after `CreateMutexW`, a duplicate `kwswitcher` instance starts instead of exiting.
- **Recommended Fix:** Link error-sensitive Win32 downcalls (`CreateMutexW`, `SetWindowsHookExW`, `SetForegroundWindow`, `PostMessageW`, `OpenProcess`, `TerminateProcess`, `EnumWindows`, `GetMonitorInfoW`) with `Linker.Option.captureCallState("GetLastError")`, pass a `Linker.Option.captureStateLayout()` segment as the first `invokeExact` argument, and extract `"GetLastError"` via `VarHandle`.

### 2.3 Ensure hook thread message queue exists before `startedLatch.countDown()` and reset state on `close()` (`kwswitcher-win32`)
- **Location:** `kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32KeyboardHook.kt` (lines `93–125`, `262–279`)
- **Problem:**
  1. `startedLatch.countDown()` runs before the hook thread's first `GetMessageW` call. Win32 creates a thread's message queue lazily on its first message API call; if `close()` runs immediately after `start()`, `PostThreadMessageW(WM_QUIT)` fails with Win32 error `1444` (`ERROR_INVALID_THREAD_ID`), leaving `GetMessageW` blocked and timing out `join(1000)`.
  2. `modifierHeld`, `reverseModifierHeld`, and `ModifierTracker` are not reset in `close()`, leaving stale modifier state if a hook instance is restarted, and `start()` ignores `startedLatch.await(3, SECONDS)` returning `false`.
- **Recommended Fix:** Call `PeekMessageW(..., PM_NOREMOVE)` before `startedLatch.countDown()` to guarantee message queue creation, check the `PostThreadMessageW` return value, check the `await` boolean result in `start()`, and reset modifier tracking fields in `close()`.

### 2.4 Cancel child `actorScope`, guard `bindHook`, and complete pending `submitAndAwait` waiters on `SwitcherCoordinator.close()` (`kwswitcher-core`)
- **Location:** `kwswitcher-core/src/main/kotlin/com/michibaum/kwswitcher/core/coordinator/SwitcherCoordinator.kt` (lines `38–41`, `67–74`, `84–90`, `163–168`)
- **Problem:**
  1. When `parentScope` is passed (as in `KwSwitcherBootstrap`), `ownsScope` is `false`, so `coordinator.close()` never cancels `actorScope`. Both the child `SupervisorJob` and the `bindHook` event-collector coroutine stay alive until `appScope` terminates. Because `actorScope` uses `SupervisorJob(parentJob)`, cancelling `actorScope` in `close()` is always safe and will not cancel `parentScope`.
  2. Calling `bindHook` more than once launches duplicate collectors without cancelling the previous one.
  3. Closing `mailbox` with queued `IntentEnvelope`s or cancelling the actor mid-flight leaves `submitAndAwait` callers suspended forever on `deferred.await()`.
- **Recommended Fix:** Track and cancel the `bindHook` `Job` on rebind/close, always cancel `actorScope` in `close()`, and drain remaining envelopes from `mailbox` on shutdown, completing/cancelling each `IntentEnvelope.completion`.

### 2.5 Keep a buffered writer open in `RotatingFileSink` instead of opening/closing on every log call (`kwswitcher-app`)
- **Location:** `kwswitcher-app/src/main/kotlin/com/michibaum/kwswitcher/app/logging/RotatingFileSink.kt` (lines `18–35`)
- **Problem:** `RotatingFileSink.writeLine` runs `Files.createDirectories`, `Files.exists`, `Files.size`, and `Files.write(..., CREATE, WRITE, APPEND)` (opening and closing the NTFS handle on every single log statement) inside `synchronized(lock)` on the caller's thread. Under `--dev` (`DEBUG` level), synchronous per-line file open/close overhead can stall the Swing EDT or jeopardize Win32 `LowLevelHooksTimeout` if a log runs on the hook thread.
- **Recommended Fix:** Keep an open `BufferedWriter` (with tracked byte count for rotation and clean `Closeable` shutdown) or offload file writes to an asynchronous bounded background channel.

### 2.6 Reset global `KwLoggingHub` state in `KwSlf4jProviderTest` (`kwswitcher-app`)
- **Location:** `kwswitcher-app/src/test/kotlin/com/michibaum/kwswitcher/app/logging/KwSlf4jProviderTest.kt` (lines `31–37`)
- **Problem:** `KwSlf4jProviderTest` reconfigures the process-wide `KwLoggingHub` singleton with a JUnit `@TempDir` `RotatingFileSink` and `ringCapacity = 3`, and never resets it in `@AfterEach`. Subsequent tests in the same Surefire JVM inherit a `3`-entry ring buffer and write logs to a deleted temporary directory.
- **Recommended Fix:** Add an `@AfterEach` (or `KwLoggingHub.resetForTests()`) that clears `fileSink` and restores default `KwLoggingHub` state after each test.

### 2.7 Smoke-test the `jlink` / `jpackage` bundled runtime in CI (`.github/workflows/ci.yml`)
- **Location:** `.github/workflows/ci.yml` (lines `57–80`)
- **Problem:** CI currently runs the `--help` smoke test using the fat JAR on the full runner JDK (`java -jar ... --help`), never executing the trimmed `jlink` runtime (`java.base, java.desktop, java.datatransfer, java.prefs, java.xml`) inside `kwswitcher-app/target/app-image/kwswitcher`. Any accidental dependency on an excluded JDK module (e.g., `java.logging` or `jdk.unsupported`) would only surface as a `NoClassDefFoundError` at runtime in packaged builds.
- **Recommended Fix:** Add a CI step running `kwswitcher-app\target\app-image\kwswitcher\runtime\bin\java.exe` (or `kwswitcher.exe --sandbox` / `--help`) against the `app-image` classpath, and prefer asserting required/forbidden modules in `release` rather than brittle exact string equality on `MODULES`.

### 2.8 Restore hook-level `pendingOpenCount` & `AltGr` tests (`kwswitcher-win32`)
- **Location:** `kwswitcher-win32/src/test/kotlin/com/michibaum/kwswitcher/win32/Win32EngineTest.kt`
- **Problem:** Pure key mapping tests moved cleanly to `KeyCommandMapperTest`, but trimming `Win32EngineTest` from `885` to `502` lines dropped the hook-level test covering `Win32KeyboardHook`'s `pendingOpenCount` + `acknowledgeHotkeyHandled()` race window (fast key chords pressed immediately after `Alt+Tab` before `StateFlow` publishes `isVisible = true`) and the enumerator upcall-stub reuse check.
- **Recommended Fix:** Re-add targeted unit tests in `Win32EngineTest` for `pendingOpenCount` / `acknowledgeHotkeyHandled()`, `AltGr` passthrough at the hook boundary, and `Win32WindowEnumerator` upcall-stub reuse.

---

## 3. Minor Polish & Cleanup

- **CLI flag validation (`kwswitcher-app/src/main/kotlin/com/michibaum/kwswitcher/app/cli/CliOptions.kt`, lines `137–158`):** Unknown CLI flags (e.g., `--pin` instead of `--pin-overlay`), a trailing `--config` with no path, or invalid `--log-level` values are silently ignored. Log a warning or print usage errors so typos are obvious.
- **`Win32SingleInstanceMutex` thread ownership (`kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32SingleInstanceMutex.kt`, lines `27–31`, `52–58`):** Pass `bInitialOwner = 0` to `CreateMutexW` (uniqueness relies on `ERROR_ALREADY_EXISTS`, not thread ownership, whereas `ReleaseMutex` fails if `close()` runs on a different thread than `tryAcquire()`) and mark `mutexHandle` `@Volatile`.
- **Monochrome icon fallback height (`kwswitcher-win32/src/main/kotlin/com/michibaum/kwswitcher/win32/Win32IconExtractor.kt`, line `335`):** When `hbmColor == 0L`, `hbmMask` for monochrome Win32 icons is `2x` height (stacked AND + XOR masks), producing a vertically stretched 2:1 image unless cropped.
- **System Tray EDT responsiveness & idempotency (`kwswitcher-core/src/main/kotlin/com/michibaum/kwswitcher/core/ui/SystemTrayController.kt`, lines `76`, `159–170`):** Guard `install()` against being called twice (`if (trayIcon != null) return true`), and avoid running blocking I/O or Win32 registry writes (`Desktop.open`, `startupPort.setRegistered`) directly on the AWT event thread.
- **Redundant pre-check on focus loss (`kwswitcher-core/src/main/kotlin/com/michibaum/kwswitcher/core/ui/SwitcherOverlay.kt`, lines `286–291`):** `handleWindowLostFocus` checks `current.mode` and `!current.pinned` on the EDT before dispatching `Dismiss(fromFocusLoss = true)`. Since `SwitcherReducer` already enforces both conditions atomically on the actor, the EDT pre-check can be simplified.
- **Import style (`kwswitcher-core/src/main/kotlin/com/michibaum/kwswitcher/core/coordinator/SwitcherCoordinator.kt`, line `40`):** Import `kotlinx.coroutines.Job` instead of referencing it by fully qualified name.
- **Documentation count (`CONTRIBUTING.md`, line `36`):** Mentions five shared `.run/` configurations, whereas `.run/` contains seven (`clean compile` and `clean package` plus the five app/test run configurations).
