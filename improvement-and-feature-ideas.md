# Kotlin Switcheroo (`kwswitcher`) — Open Improvements, Feature Ideas & Diagnostic Tools

> Completed architectural overhauls—including the three-module reactor (`kwswitcher-core`, `kwswitcher-win32`, `kwswitcher-app`), single-threaded `SwitcherCoordinator` actor, `KeyCommandMapper`, `kotlinx-serialization-json` config persistence, `KwSlf4jProvider` rotating/ring-buffer diagnostics, `Win32ErrorFormatter`, incremental `WindowMatchListModel` updates, `0xFF`/`LLKHF_INJECTED` foreground-unlock handling, Spotless + zero-baseline Detekt, Kover CI, and trimmed `java.base,java.desktop` `jpackage`/MSI releases—have been delivered and removed from this backlog.

## Improvements

### UI/UX Polish (`kwswitcher-core/ui`, `kwswitcher-win32`)
- **Dynamic overlay height & Windows 11 styling (`SwitcherOverlay.kt`, `kwswitcher-win32`):** Resize the overlay height dynamically to fit the current number of visible matches (up to a max row count), show an explicit empty-state message (`"No matching windows"`) when a query yields zero results, and apply native Windows 11 rounded corners/drop shadow via `DwmSetWindowAttribute(DWMWA_WINDOW_CORNER_PREFERENCE)`.
- **Automatic OS theme tracking (`ThemeManager.kt`, `KwSwitcherBootstrap.kt`):** Register a `PropertyChangeListener` on `Toolkit.getDefaultToolkit()` for `"win.lightTheme.on"` so `ThemeOption.SYSTEM` updates live when Windows switches between Light and Dark mode without requiring `Reload Config`.
- **HiDPI multi-resolution icons (`SystemTrayController.kt`, `Win32IconExtractor.kt`):** Use `BaseMultiResolutionImage` (or `flatlaf-extras` SVG icons) for the system tray icon and window icons so they render crisply at 125%–250% display scaling.
- **Toast / tray balloon feedback on invalid `config.json`:** In addition to logging warnings and `ConfigLoadResult` diagnostics, display a non-modal tray notification when `Reload Config` encounters malformed JSON or invalid regex patterns.

---

## New Features

- **Same-Application Window Cycling (`Alt + Backtick` / `Ctrl + G`):** Dedicated global hotkey and/or in-switcher toggle to filter and cycle strictly among windows belonging to the currently active process (e.g., all open IntelliJ IDEA projects or browser windows).
- **Frecency / MRU Search Boosting:** Persist lightweight usage history pairing search tokens with activated process/window patterns so frequently chosen windows rank first for habitual short queries.
- **Graphical Settings Dialog:** Accessible from the System Tray and via `Ctrl + ,` in the overlay to interactively record global hotkeys, edit process/title ignore rules, toggle visual badges, and adjust theme/layout options without hand-editing `config.json`.
- **Quick "Ignore Window / Process" Action:** In-switcher shortcut (e.g., `Ctrl + I`) to append the selected window's process or title pattern directly to `config.json` ignore rules.
- **Elevated / Administrator Launch Option (UIPI Support):** Optional Windows Task Scheduler integration (`Run with highest privileges` at logon) via the System Tray or CLI flag so `WH_KEYBOARD_LL`, `WM_CLOSE`, and focus switching work even when elevated apps (Task Manager, Admin PowerShell) have focus.
- **Virtual Desktop Awareness & Cross-Desktop Switching:** Option or toggle (`Ctrl + D`) to view and switch to windows across all Windows 10/11 Virtual Desktops with `[Desktop N]` badges.
- **Expanded In-Switcher Window Management:**
  - Maximize / restore (`Ctrl + Up`), snap left/right (`Ctrl + Left` / `Ctrl + Right`), or move the selected window to the current monitor (`Ctrl + Shift + Enter`).
  - Batch-close all currently filtered windows (`Ctrl + Shift + Delete` with confirmation) or copy the selected window's title/PID/executable path (`Ctrl + C`).
- **Live DWM Window Thumbnail Preview:** Optional side preview pane powered by `DwmRegisterThumbnail` / `DwmUpdateThumbnailProperties` (`dwmapi.dll`) to visually distinguish windows with identical titles.
- **Command Palette Prefix (`>`):** Typing `>` in the search box exposes built-in switcher actions (`> reload config`, `> theme dark/light/system`, `> copy diagnostics`, `> quit`).
- **Discoverable Shortcut Hint Bar:** Compact, configurable footer bar in `SwitcherOverlay` summarizing keybindings (`Enter` Switch · `Ctrl+W` Close · `Ctrl+M` Minimize · `Ctrl+Shift+W` Kill · `Esc` Cancel).
- **Extensible Search Providers (Tabs & Workspaces):** Optional plugin/provider hooks to surface browser tabs (Edge/Chrome/Firefox), Windows Terminal tabs, or recent IDE workspaces alongside top-level Win32 windows.

---

## Open Platform Edge Cases

- **"Run at Windows Startup" command when launched via `java.exe` (`Win32StartupRegistry.kt`):** `defaultLaunchCommand()` uses `ProcessHandle.current().info().command().orElse("kwswitcher.exe")`. In production `jpackage` installs this points to `kwswitcher.exe`, whereas launching via `java -jar ...` in production mode would register bare `java.exe` without `-jar` arguments (`--dev` and `--sandbox` modes already isolate startup registration via `InMemoryStartupPort`).
- **UWP `ApplicationFrameHost.exe` child process resolution (`Win32IconExtractor.kt`, `Win32WindowEnumerator.kt`):** Classic Win32 queries resolve UWP apps (Calculator, Settings, Microsoft Store) to `C:\Windows\System32\ApplicationFrameHost.exe`, causing UWP windows to share a single `exePath` icon cache key and `"ApplicationFrameHost"` process badge unless child `CoreWindow` PIDs are inspected.
- **Mixed-DPI multi-monitor coordinate conversion (`SwitcherOverlay.kt`, `Win32WindowEnumerator.kt`):** `Win32WindowEnumerator.getActiveMonitorWorkArea()` returns physical pixel coordinates from `GetMonitorInfoW`, whereas `SwitcherOverlay.positionOnActiveMonitor()` passes them to Swing's logical coordinate space (`dialog.setLocation(x, y)`), which can offset centering on mixed-DPI multi-monitor setups.
- **Physical display count vs. window-occupied monitors (`SwitcherReducer.kt`, `Win32WindowEnumerator.kt`):** `hasMultipleMonitors` checks whether open windows span `> 1` distinct `monitorId`s rather than enumerating connected physical displays (`EnumDisplayMonitors`), so `[Mon N]` badges hide when all open windows sit on a single monitor of a dual-monitor setup.
- **`WM_GETICON` handle ownership & `hwnd:<address>` fallback cache invalidation (`Win32IconExtractor.kt`):** Fallback `hwnd:<address>` cache keys (used when `exePath` is blank for elevated processes) could outlive a closed `HWND` if Windows later reuses the same handle value before `clearCache()` runs.

---

## Helpful External Tools

### Win32, Windowing & Panama FFM Diagnostics
- **`jextract` (OpenJDK Panama Tool):** Generates `java.lang.foreign` bindings, `FunctionDescriptor`s, `MemoryLayout`s, and `VarHandle`s directly from Windows SDK headers (`Windows.h`, `WinUser.h`, `dwmapi.h`).
- **Microsoft Spy++ (`spyxx.exe`) / WinSpy++ / System Informer:** Inspect live `HWND` properties, window styles (`WS_EX_TOOLWINDOW`, `WS_EX_NOACTIVATE`), DWM cloaking states, class icons, and Win32 message traffic (`WM_CLOSE`, `WM_GETICON`).
- **Sysinternals Suite (`Process Explorer`, `Process Monitor`, `Handle`, `DebugView`):** Verify zero GDI/USER/Process handle leaks (`HICON`, `HBITMAP`, `HDC`, `HANDLE`) across thousands of switcher invocations and inspect UIPI privilege levels.
- **Accessibility Insights for Windows / `Inspect.exe` (Windows SDK):** Diagnose UWP `ApplicationFrameHost` child windows, virtual desktop cloaking behavior, and per-monitor DPI scaling.

### JVM Profiling, Benchmarking & Windows Distribution
- **FlatLaf Inspector (`flatlaf-extras`):** Live runtime inspector for Swing component bounds, borders, HiDPI scale factors, and FlatLaf client properties inside `SwitcherOverlay` (pairs well with `--sandbox --pin-overlay`).
- **JDK Flight Recorder (`jfr`) & JDK Mission Control (`jmc`):** Profile `WH_KEYBOARD_LL` callback latency (`< 5 ms` budget), Swing EDT frame times, allocations, and resident memory (`< 85 MB` budget) on JDK 25.
- **JMH (Java Microbenchmark Harness):** Benchmark `WindowFilter.match` and `Win32KeyboardHook.processKeyMessage` against latency budgets.
- **Native Memory Tracking (`-XX:NativeMemoryTracking=summary`):** Monitor off-heap `Arena` allocations alongside JVM heap usage during soak testing.
- **SignTool / Azure Trusted Signing & WinGet (`wingetcreate`):** Authenticode-sign `kwswitcher.exe` / `.msi` artifacts and automate WinGet/Scoop manifest publishing from GitHub Actions.
