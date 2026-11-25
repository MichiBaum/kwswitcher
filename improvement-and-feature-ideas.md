# Kotlin Switcheroo (`kwswitcher`) — Improvements, New Features, Bugs & Helpful Tools

## Improvements

### Performance & Native Interop (`win32`)
- **Zero-allocation keyboard hook hot path (`KeyboardHook.kt`, `SwitcherConfig.kt`):** Pre-compute `modifierVkCodes`, `reverseModifierVkCodes`, `reverseModifierPrimaryVk`, and `triggerVkCode` once on `HotkeyConfig` construction instead of allocating new `Set<Int>` instances and uppercase `String`s on every system-wide keystroke inside `KeyboardHook.processKeyMessage`.
- **Asynchronous icon loading off the Swing EDT (`WindowListCellRenderer.kt`, `IconExtractor.kt`):** Move `IconExtractor.getIcon(window)` out of `getListCellRendererComponent` so uncached windows never stall UI rendering with synchronous `SendMessageTimeoutW` calls (up to `3 × 50 ms`) or `FileSystemView` disk I/O; render the fallback icon first and repaint rows as icons resolve in the background.
- **Per-enumeration process path caching (`WindowEnumerator.kt`):** Cache `pid -> exePath` lookups within `enumerateOpenWindows()` (or with a short-lived PID cache) to avoid repeated `OpenProcess` + `QueryFullProcessImageNameW` + `CloseHandle` round-trips when one application (e.g., browser, IDE, Explorer) owns many open windows.
- **Type-safe Panama `VarHandle` struct access (`Win32Bindings.kt`, `IconExtractor.kt`, `WindowEnumerator.kt`, `WindowActivator.kt`):** Derive named `VarHandle`s from `MemoryLayout`s (`KBDLLHOOKSTRUCT_LAYOUT`, `MONITORINFO_LAYOUT`, `ICONINFO_LAYOUT`, `BITMAP_LAYOUT`, `INPUT_LAYOUT`) instead of relying on hardcoded byte offsets (`8L`, `16L`, `20L`, `24L`, `36L`).
- **Replace legacy AWT `getScaledInstance` (`IconExtractor.kt`):** Replace `source.getScaledInstance(size, size, Image.SCALE_SMOOTH)` in `IconExtractor.scaleImage` with direct multi-step `Graphics2D` bicubic scaling (`VALUE_INTERPOLATION_BICUBIC`) for faster, deterministic, sharper icon downscaling.

### Architecture, Concurrency & Configuration (`coordinator`, `config`)
- **Single-threaded state-machine confinement (`SwitcherStateMachine.kt`, `Main.kt`):** Serialize all state-machine events (`KeyboardHook` events from `Dispatchers.Default` and UI/tray callbacks from the Swing EDT) through an actor channel or `Dispatchers.Swing` while offloading blocking Win32 side effects (`EnumWindows`, `activate`, `terminateProcess`) to `Dispatchers.IO`.
- **Replace regex JSON parser with a real serializer (`ConfigRepository.kt`, `pom.xml`):** Adopt `kotlinx-serialization-json` (compile-time, reflection-free, minimal footprint) to handle arbitrary regex strings, escape sequences, and schema evolution cleanly, and surface a tray warning instead of silently discarding user edits when `config.json` fails to parse.
- **Structured logging & diagnostics:** Add lightweight ring-buffer/file logging (`%LOCALAPPDATA%\kwswitcher\logs\kwswitcher.log`) instead of `println`/`System.err.println` (which are invisible in packaged GUI builds without a console) and log failed Win32 calls with `GetLastError()`.

### UI/UX Polish (`ui`)
- **Incremental `JList` model updates (`SwitcherOverlay.kt`):** Avoid calling `listModel.clear()` and rebuilding all elements on every `Up`/`Down`/`Tab` selection change in `updateListItems`; only rebuild list contents when `state.matches` actually changes, and simply update `selectedIndex` otherwise.
- **Dynamic overlay height & Windows 11 styling (`SwitcherOverlay.kt`, `Win32Bindings.kt`):** Resize the overlay height dynamically to fit the current number of visible matches (up to a max row count), show an explicit empty-state message (`"No matching windows"`) when a query yields zero results, and apply native Windows 11 rounded corners/drop shadow via `DwmSetWindowAttribute(DWMWA_WINDOW_CORNER_PREFERENCE)`.
- **Automatic OS theme tracking (`ThemeManager.kt`, `Main.kt`):** Register a `PropertyChangeListener` on `Toolkit.getDefaultToolkit()` for `"win.lightTheme.on"` so `ThemeOption.SYSTEM` updates live when Windows switches between Light and Dark mode without requiring `Reload Config`.
- **HiDPI multi-resolution icons (`SystemTrayController.kt`, `IconExtractor.kt`):** Use `BaseMultiResolutionImage` (or `flatlaf-extras` SVG icons) for the system tray icon and window icons so they render crisply at 125%–250% display scaling.

### Build, Packaging & CI/CD (`pom.xml`, `.github/workflows`)
- **Continuous Integration workflow:** Add `.github/workflows/ci.yml` to run `.\mvnw.cmd -B test` on every push and pull request (currently tests only run during tagged releases in `release.yml`).
- **Smaller native runtime image (`pom.xml`):** Configure `jpackage` / `jlink` `--add-modules java.base,java.desktop` (instead of bundling the entire JDK image) to shrink the native Windows distribution by ~60–70%, and supply a custom `.ico` via `--icon`.
- **JDK 25 runtime tuning (`pom.xml`):** Evaluate `-XX:+UseCompactObjectHeaders` and generational ZGC (`-XX:+UseZGC`) in `jpackage` and `exec-maven-plugin` options to further reduce resident memory below the 85 MB target and eliminate GC pauses.

---

## New Features

- **Same-Application Window Cycling (`Alt + Backtick` / `Ctrl + G`):** Dedicated global hotkey and/or in-switcher toggle to filter and cycle strictly among windows belonging to the currently active process (e.g., all open IntelliJ IDEA projects or browser windows).
- **Frecency / MRU Search Boosting:** Persist lightweight usage history pairing search tokens with activated process/window patterns so frequently chosen windows rank first for habitual short queries.
- **Graphical Settings Dialog:** Accessible from the System Tray and via `Ctrl + ,` in the overlay to interactively record global hotkeys, edit process/title ignore rules, toggle visual badges, and adjust theme/layout options without hand-editing `config.json`.
- **Quick "Ignore Window / Process" Action:** In-switcher shortcut (e.g., `Ctrl + I`) to append the selected window's process or title pattern directly to `config.json` ignore rules.
- **Elevated / Administrator Launch Option (UIPI Support):** Implement the specification's planned Task Scheduler integration (`Run with highest privileges` at logon) via the System Tray or CLI flag so `WH_KEYBOARD_LL`, `WM_CLOSE`, and focus switching work even when elevated apps (Task Manager, Admin PowerShell) have focus.
- **Virtual Desktop Awareness & Cross-Desktop Switching:** Option or toggle (`Ctrl + D`) to view and switch to windows across all Windows 10/11 Virtual Desktops with `[Desktop N]` badges.
- **Expanded In-Switcher Window Management:**
  - Maximize / restore (`Ctrl + Up`), snap left/right (`Ctrl + Left` / `Ctrl + Right`), or move the selected window to the current monitor (`Ctrl + Shift + Enter`).
  - Batch-close all currently filtered windows (`Ctrl + Shift + Delete` with confirmation) or copy the selected window's title/PID/executable path (`Ctrl + C`).
- **Live DWM Window Thumbnail Preview:** Optional side preview pane powered by `DwmRegisterThumbnail` / `DwmUpdateThumbnailProperties` (`dwmapi.dll`) to visually distinguish windows with identical titles.
- **Command Palette Prefix (`>`):** Typing `>` in the search box exposes built-in switcher actions (`> reload config`, `> theme dark/light/system`, `> toggle startup`, `> quit`).
- **Discoverable Shortcut Hint Bar:** Compact, configurable footer bar in `SwitcherOverlay` summarizing keybindings (`Enter` Switch · `Ctrl+W` Close · `Ctrl+M` Minimize · `Ctrl+Shift+W` Kill · `Esc` Cancel).
- **Extensible Search Providers (Tabs & Workspaces):** Optional plugin/provider hooks to surface browser tabs (Edge/Chrome/Firefox), Windows Terminal tabs, or recent IDE workspaces alongside top-level Win32 windows.

---

## Bugs

- **Broken "Run at Windows Startup" when running via `java.exe` (`SystemTrayController.kt`):** `setStartupRegistered` uses `ProcessHandle.current().info().command().orElse("kwswitcher.exe")`. When running from a fat JAR or Maven (`java.exe -jar ...`), this writes bare `"C:\...\java.exe"` (without `-jar` or arguments) into `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, which silently fails on Windows login.
- **UWP apps share the wrong cached icon (`IconExtractor.kt`, `WindowEnumerator.kt`):** Classic Win32 queries resolve UWP apps (Calculator, Settings, Microsoft Store, etc.) to the host process `C:\Windows\System32\ApplicationFrameHost.exe`. Because `IconExtractor.getIcon` keys `iconCache` solely by `window.exePath.lowercase()`, whichever UWP app is rendered first caches its icon for all other UWP apps (and displays `"ApplicationFrameHost"` as their process badge).
- **HiDPI / multi-DPI overlay mispositioning (`SwitcherOverlay.kt`, `WindowEnumerator.kt`):** `WindowEnumerator.getActiveMonitorWorkArea()` returns physical pixel coordinates from Win32 `GetMonitorInfoW`, whereas `SwitcherOverlay.positionOnActiveMonitor()` combines them directly with Swing's DPI-scaled logical `dialog.width`/`dialog.height` and `dialog.setLocation(x, y)`. On 125%/150%/200% or mixed-DPI multi-monitor setups, the overlay renders off-center or partially off-screen.
- **Regex character classes break `ConfigRepository.extractStringList` (`ConfigRepository.kt`):** `extractStringList` uses `Regex(""""key"\s*:\s*\[([^\]]*)]""")`, which prematurely terminates at the first `]` inside a JSON string—breaking any `ignoredTitlePatterns` entry that uses regex character classes such as `^\[Admin\]` or `Build #[0-9]+`. Additionally, `unescapeJson` chains `.replace("\\\"", "\"").replace("\\\\", "\\")`, corrupting escaped backslashes preceding quotes (`\\"`).
- **Synthetic `Alt` tap re-enters `KeyboardHook` and triggers app menu bars (`WindowActivator.kt`, `KeyboardHook.kt`):** `WindowActivator.sendZeroEffectAltKeyTap` injects `VK_MENU` keydown/keyup via `SendInput`, and `KeyboardHook` does not filter out `LLKHF_INJECTED`. Every window activation therefore mutates `modifierHeld`, emits a spurious `HookKeyEvent.AltReleased`, and can activate/focus the menu bar of classic Win32 or Swing applications (using an unassigned virtual key such as `0xFF` or checking `LLKHF_INJECTED` avoids both issues).
- **Inaccurate multi-monitor badge detection and shifting monitor numbers (`SwitcherStateMachine.kt`, `WindowEnumerator.kt`):** `hasMultipleMonitors` checks whether open windows span `> 1` distinct `monitorId`s rather than whether multiple physical displays are connected. If two monitors are connected, but all open windows happen to sit on one monitor, `[Mon N]` badges disappear; if the primary monitor has no open windows, a secondary monitor gets labeled `[Mon 1]`.
- **GDI bitmap leak on early `NULL` check in `IconExtractor.convertHIconToImage` (`IconExtractor.kt`):** `GetIconInfo` allocates native `hbmMask` and `hbmColor` bitmaps before the `try / finally` cleanup block starts (lines 113–123); moving `try / finally` immediately after `GetIconInfo` succeeds guarantees `DeleteObject` always runs on every code path. Furthermore, if `SendMessageTimeoutW(WM_GETICON)` returns a shared vs. dynamically created `HICON`, handle ownership nuances and fallback `hwnd:<address>` cache keys (when `exePath` is blank for elevated processes) can retain stale entries after `HWND` reuse.
- **Race conditions between hook coroutine and Swing EDT in `SwitcherStateMachine` (`SwitcherStateMachine.kt`, `Main.kt`):** `onAltReleased()`, `onConfirmSelection()`, `onCloseSelectedWindow()`, and `onKillSelectedWindowProcess()` read `_state.value` outside atomic transitions before mutating `_state` and invoking native callbacks, allowing concurrent `Dispatchers.Default` hook events and Swing EDT key events to race.
- **Double resource cleanup on Tray Quit (`Main.kt`):** Clicking *Quit* in the system tray triggers `shutdownLatch.countDown()`, causing `main()` to invoke `cleanup()` and then immediately triggering the `kwswitcher-shutdown` JVM shutdown hook, which executes `cleanup()` a second time.
- **MSI build fails with `-SNAPSHOT` version and is omitted from GitHub Releases (`pom.xml`, `.github/workflows/release.yml`):** Running `.\mvnw.cmd package -Pjpackage-installer` locally fails because `jpackage --type msi` rejects `-SNAPSHOT` in `--app-version ${project.version}`, and `.github/workflows/release.yml` builds the MSI installer without including `target/dist/*.msi` in the `softprops/action-gh-release@v2` `files` upload list.

---

## Helpful Tools

### Win32, Windowing & Panama FFM Diagnostics
- **`jextract` (OpenJDK Panama Tool):** Automatically generates `java.lang.foreign` bindings, `FunctionDescriptor`s, `MemoryLayout`s, and `VarHandle`s directly from Windows SDK headers (`Windows.h`, `WinUser.h`, `dwmapi.h`).
- **Microsoft Spy++ (`spyxx.exe`) / WinSpy++ / System Informer:** Inspect live `HWND` properties, window styles (`WS_EX_TOOLWINDOW`, `WS_EX_NOACTIVATE`), DWM cloaking states, class icons, and Win32 message traffic (`WM_CLOSE`, `WM_GETICON`).
- **Sysinternals Suite (`Process Explorer`, `Process Monitor`, `Handle`, `DebugView`):** Verify zero GDI/USER/Process handle leaks (`HICON`, `HBITMAP`, `HDC`, `HANDLE`) across thousands of switcher invocations, audit `HKCU\...\Run` registry writes, and inspect UIPI privilege levels.
- **Accessibility Insights for Windows / `Inspect.exe` (Windows SDK):** Diagnose UWP `ApplicationFrameHost` child windows, virtual desktop cloaking behavior, and per-monitor DPI scaling.

### Kotlin Code Quality, Testing & UI Inspection
- **Detekt (`detekt-maven-plugin`):** Static analysis for Kotlin to flag concurrency anti-patterns, swallowed exceptions in `runCatching`, magic numbers, and cognitive complexity.
- **Ktlint / Spotless (`spotless-maven-plugin`):** Automated Kotlin formatting and import ordering checks for CI.
- **Kotlinx Kover (`kover-maven-plugin`):** Branch and line coverage reports for JUnit 5 unit tests (`WindowFilter`, `SwitcherStateMachine`, `ConfigRepository`, `WindowListCellRenderer`).
- **FlatLaf Inspector & Extras (`flatlaf-extras`):** Live runtime inspector for Swing component bounds, borders, HiDPI scale factors, and FlatLaf client properties inside `SwitcherOverlay`, plus crisp `FlatSVGIcon` rendering.
- **AssertJ Swing:** Automated keyboard/focus/rendering integration tests for Swing `JDialog` and `JList` interactions.

### JVM Profiling, Benchmarking & Windows Distribution
- **JDK Flight Recorder (`jfr`) & JDK Mission Control (`jmc`):** Profile `WH_KEYBOARD_LL` callback latency (`<5 ms` budget), Swing EDT frame times, allocations, and resident memory (`<85 MB` budget) on JDK 25 with near-zero overhead.
- **JMH (Java Microbenchmark Harness):** Benchmark `WindowFilter.match` and `KeyboardHook.processKeyMessage` against regression budgets.
- **Native Memory Tracking (`-XX:NativeMemoryTracking=summary`) & VisualVM:** Monitor off-heap `Arena` allocations alongside JVM heap usage during soak testing.
- **WiX Toolset & Orca:** Build and inspect Windows `.msi` installer tables generated by `jpackage`.
- **SignTool / Azure Trusted Signing:** Authenticode-sign `kwswitcher.exe` and `.msi` artifacts so Windows Defender SmartScreen does not flag an unsigned binary installing a global low-level keyboard hook (`WH_KEYBOARD_LL`).
- **WinGet (`wingetcreate`) / Scoop / JReleaser:** Automate publishing versioned `kwswitcher` installers and portable zips to Windows package managers from GitHub Actions.
