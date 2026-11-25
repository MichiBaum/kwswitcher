# System Architecture

%product% (`%cmd_name%`) is architected around strict latency budgets and Win32 thread-affinity boundaries so system-wide keyboard input never stutters and the switcher overlay appears instantaneously.

* **Target Platform:** Windows 10 (21H2+) &amp; Windows 11 (`x64` / `ARM64`)
* **Primary Stack:** Kotlin/JVM (JDK %jdk_version%), Swing with FlatLaf, Project Panama Foreign Function &amp; Memory API (`java.lang.foreign`), Kotlin Coroutines, Apache Maven

## Core Design Goals

* **Sub-20 ms Popup Latency:** Keep the JVM warm in the background and toggle an undecorated Swing + FlatLaf overlay dialog rather than cold-starting processes or heavy GPU pipelines.
* **Zero-Click Keyboard Ergonomics:** Every action—invoking, filtering, navigating, switching, minimizing, closing, and terminating windows—is executable without touching the mouse.
* **Zero-Dependency Native Interop:** Use JDK %jdk_version%'s built-in Project Panama Foreign Function &amp; Memory (`java.lang.foreign`) API (`Linker`, `SymbolLookup`, `MemorySegment`, `Arena`) to invoke Win32 APIs (`user32.dll`, `kernel32.dll`, `dwmapi.dll`, `gdi32.dll`, `advapi32.dll`) directly without JNA or JNI overhead.
* **Resilient Win32 Interop:** Safely handle hung applications, elevated processes, UWP/cloaked windows, and multi-DPI monitor setups without freezing the Windows input pipeline.

## Functional Requirements Traceability (`FR-1` – `FR-9`)

Source KDoc comments across the codebase reference these functional requirement identifiers:

| ID | Feature | Requirement Details |
| :--- | :--- | :--- |
| **`FR-1`** | **Configurable Global Hotkey Interception** | Intercept `Alt + Tab` (forward) and `Alt + Shift + Tab` (backward) system-wide by default via a low-level keyboard hook (`WH_KEYBOARD_LL`), swallowing the trigger key event when the modifier is depressed so Windows Explorer's native switcher does not activate. Allow users to customize the trigger modifier, trigger key, and reverse modifier in `config.json`. |
| **`FR-2`** | **Immediate Overlay (`QUICK_SWITCH` vs. `INTERACTIVE_SEARCH`)** | Pressing the trigger hotkey immediately displays the switcher overlay in `QUICK_SWITCH` mode with the 2nd window in Z-order (previously active window) pre-selected. Repeated trigger key presses while holding the modifier cycle selection forward/backward. Releasing the modifier while still in `QUICK_SWITCH` mode immediately activates the selected window and hides the overlay. Typing any search character, pressing arrow keys, or invoking an in-switcher window action transitions the session into `INTERACTIVE_SEARCH` mode, keeping the overlay open until `Enter`, mouse click, `Esc`, or focus loss occurs. |
| **`FR-3`** | **Real Window Enumeration &amp; Ignore List** | Enumerate top-level windows (`EnumWindows`) on the current virtual desktop in top-to-bottom Z-order and exclude invisible windows (`IsWindowVisible`), disabled windows (`IsWindowEnabled`), empty-title windows, tool/non-activatable windows (`WS_EX_TOOLWINDOW` without `WS_EX_APPWINDOW`, or `WS_EX_NOACTIVATE`), DWM-cloaked UWP or other-virtual-desktop windows (`DWMWA_CLOAKED`), the switcher's own `HWND`, and any window matching `ignoredProcesses` or `ignoredTitlePatterns` in `config.json`. |
| **`FR-4`** | **Metadata, State &amp; Icon Caching** | Resolve each window's title (`GetWindowTextW`), process ID (`GetWindowThreadProcessId`), executable path (`OpenProcess` with `PROCESS_QUERY_LIMITED_INFORMATION` + `QueryFullProcessImageNameW`), minimized state (`IsIconic`), display monitor (`MonitorFromWindow` + `GetMonitorInfoW`), and application icon (`SendMessageTimeoutW` / `GetClassLongPtrW`), caching converted icons by executable path. |
| **`FR-5`** | **Multi-Token Ranked Fuzzy Search** | Split the search query on whitespace into independent tokens where every token must match either the window title or the process name. Rank matching windows across exact prefixes, word-boundary/camelCase prefixes, acronyms, contiguous substrings, and compact fuzzy subsequences with Z-order recency tie-breaking, returning matched character index spans for UI highlighting. |
| **`FR-6`** | **List Navigation &amp; In-Switcher Window Actions** | Support `Down`/`Up`, `Tab`/`Shift+Tab`, and `Ctrl+N`/`Ctrl+P` with wrap-around; `Enter` or mouse click to activate; `Esc` or focus loss to dismiss. Support in-place window actions without closing the switcher: `Ctrl+W`, `Delete` (empty query), or `Ctrl+Delete` to gracefully close (`PostMessageW(hwnd, WM_CLOSE, 0, 0)`), `Ctrl+M` to minimize in-place (`ShowWindow(hwnd, SW_MINIMIZE)`), and two-step `Ctrl+Shift+W` to force-terminate the owning process (`TerminateProcess`). |
| **`FR-7`** | **Reliable Focus Activation** | Restore minimized windows (`IsIconic` -> `ShowWindow(hwnd, SW_RESTORE)`) and reliably bring target windows to the foreground (`SetForegroundWindow`) by bypassing Windows' foreground-lock restrictions via `AttachThreadInput` and synthetic `SendInput`. |
| **`FR-8`** | **Visual Badges &amp; Theme Selection** | Render each window row with its application icon, highlighted title match spans, armed kill badge, minimized badge (`[Minimized]`), multi-monitor badge (`[Mon N]`), and process badge. Support `DARK`, `LIGHT`, and `SYSTEM` themes via FlatLaf. |
| **`FR-9`** | **System Tray, Config &amp; Single Instance** | Enforce a single running instance (`CreateMutexW`), persist configuration in `%\APPDATA%\kwswitcher\config.json`, and provide a system tray icon with *Show Switcher*, *Reload Config*, *Theme*, *Run at Windows Startup*, and *Quit* actions. |

## Non-Functional Budgets

* **Invocation &amp; Search Latency:** The overlay renders and accepts keyboard input within **20 ms** of the hotkey press; multi-token fuzzy filtering across 100 open windows completes in under **5 ms**.
* **Memory Footprint:** Resident idle memory stays well under **85 MB** (packaged with `-Xms16m -Xmx64m -XX:+UseCompactObjectHeaders -XX:+UseSerialGC`, `--enable-native-access=ALL-UNNAMED`, and deterministic `Arena.ofConfined()` native segments).
* **Multi-Monitor &amp; DPI Awareness:** The overlay centers on the active monitor's work area (`rcWork`) and scales crisply across mixed-DPI displays (`100%`–`250%`) using JDK %jdk_version% Swing HiDPI scaling and direct `Graphics2D` text rendering.
* **Hook Safety:** The `WH_KEYBOARD_LL` upcall executes in `O(1)` time with zero heap allocations on non-matching keys and returns in under **5 ms**—well below Windows' `300 ms` `LowLevelHooksTimeout` threshold.

## Three-Thread Execution Model

Because Win32 hooks, native window operations, and Swing UI components each enforce strict thread-affinity rules, `%cmd_name%` isolates execution into three layers connected by lock-free channels and immutable state flows:

```text
┌──────────────────────────────────────────────────────────────────────┐
│ 1. Win32 Hook Thread (Dedicated OS Platform Thread, MAX_PRIORITY)    │
│    • Installs WH_KEYBOARD_LL via Panama FFM upcall stub              │
│    • Runs GetMessageW() / TranslateMessage() / DispatchMessageW()    │
│    • Emits HookKeyEvent via non-blocking Channel.trySend()           │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ Non-blocking Channel.trySend()
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 2. Coordinator & Domain Layer (Coroutine Scope / win32Dispatcher)    │
│    • State machine: HIDDEN <-> QUICK_SWITCH <-> INTERACTIVE_SEARCH   │
│    • WindowEnumerator + WindowActivator + WindowFilter + IconCache   │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ StateFlow<SwitcherUiState>
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 3. Presentation Layer (Swing AWT Event Dispatch Thread + FlatLaf)    │
│    • Undecorated, alwaysOnTop JDialog (SwitcherOverlay)              │
│    • Direct Graphics2D highlight span & badge cell renderer          │
└──────────────────────────────────────────────────────────────────────┘
```

## State Machine Transitions

`SwitcherStateMachine` maintains an atomic `StateFlow<SwitcherUiState>` across three modes (`HIDDEN`, `QUICK_SWITCH`, and `INTERACTIVE_SEARCH`):

| Current Mode | Event | Next Mode | Side Effects |
| :--- | :--- | :--- | :--- |
| `HIDDEN` | `Alt + Tab` (or configured hotkey) pressed | `QUICK_SWITCH` | Enumerate open windows &amp; active monitor work area; set query `""`; select index `1` (or `0` if fewer than 2 windows); show overlay immediately. |
| `HIDDEN` | `Alt + Shift + Tab` pressed | `QUICK_SWITCH` | Enumerate open windows &amp; active monitor work area; set query `""`; select last window (`size - 1`); show overlay immediately. |
| `HIDDEN` (or any) | Tray **Show Switcher** invoked | `INTERACTIVE_SEARCH` | Enumerate open windows &amp; active monitor work area; set query `""`; select index `0`; lock overlay open. |
| `QUICK_SWITCH` | `Tab` / hotkey pressed (with modifier held) | `QUICK_SWITCH` | Advance `selectedIndex` cyclically `(index + 1) % count`; clear any armed kill. |
| `QUICK_SWITCH` | `Shift + Tab` / reverse hotkey pressed | `QUICK_SWITCH` | Move `selectedIndex` cyclically `(index - 1 + count) % count`; clear any armed kill. |
| `QUICK_SWITCH` | Primary modifier (`Alt`) released | `HIDDEN` | Activate currently selected `WindowInfo` and hide overlay. |
| `QUICK_SWITCH` | Character typed or `Up` / `Down` / `Ctrl+N` / `Ctrl+P` | `INTERACTIVE_SEARCH` | Lock overlay open; filter/rank windows or move selection; subsequent `Alt` release is ignored. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Ctrl + W`, `Delete` *(empty query)*, or `Ctrl + Delete` | `INTERACTIVE_SEARCH` | Post `WM_CLOSE` to selected window; remove from state; clamp `selectedIndex`; disarm kill; keep overlay open. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Ctrl + M` pressed | `INTERACTIVE_SEARCH` | Minimize selected window (`SW_MINIMIZE`); mark `isMinimized = true` in UI state; disarm kill; keep overlay open. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Ctrl + Shift + W` pressed | `INTERACTIVE_SEARCH` | **1st press:** arm kill confirmation (`armedKillHwnd = target.hwnd`). **2nd consecutive press:** call `TerminateProcess(pid)` and remove all windows of that PID. |
| `INTERACTIVE_SEARCH` | Primary modifier (`Alt`) released | `INTERACTIVE_SEARCH` | No-op (overlay remains open for hands-free typing). |
| `INTERACTIVE_SEARCH` | Query text updated | `INTERACTIVE_SEARCH` | Re-filter and rank windows via `WindowFilter.match`; reset `selectedIndex = 0`; disarm kill. |
| `INTERACTIVE_SEARCH` | `Up` / `Down` / `Tab` / `Shift+Tab` / `Ctrl+N` / `Ctrl+P` | `INTERACTIVE_SEARCH` | Move `selectedIndex` cyclically forward or backward; disarm kill. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Enter` pressed or list item clicked | `HIDDEN` | Activate currently selected `WindowInfo` and hide overlay. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Esc` pressed or focus lost *(in `INTERACTIVE_SEARCH`)* | `HIDDEN` | Dismiss without switching and hide overlay. |

## Package Layout

```text
src/main/kotlin/com/michibaum/kwswitcher/
├── Main.kt                         # Entry point, single-instance mutex, theme, coroutines & tray wiring
├── config/
│   ├── SwitcherConfig.kt           # Configuration data model (HotkeyConfig, ThemeOption, ignore rules)
│   ├── ConfigRepository.kt         # Loads/saves %APPDATA%\kwswitcher\config.json
│   └── ThemeManager.kt             # FlatDarkLaf / FlatLightLaf palettes & Windows OS theme detection
├── coordinator/
│   ├── SwitcherMode.kt             # HIDDEN, QUICK_SWITCH, INTERACTIVE_SEARCH
│   ├── SwitcherUiState.kt          # Immutable UI state snapshot + match highlights + armed kill HWND
│   └── SwitcherStateMachine.kt     # Pure state transition logic & side-effect callbacks
├── domain/
│   ├── WindowInfo.kt               # WindowHandle & WindowInfo (hwnd, title, pid, processName, exePath, zOrder, isMinimized, monitorId)
│   ├── WindowMatch.kt              # WindowInfo + score + merged character highlight ranges
│   └── WindowFilter.kt             # Multi-token ranked fuzzy matcher & highlight span calculator
├── ui/
│   ├── SwitcherOverlay.kt          # Undecorated FlatLaf JDialog, search field, key & mouse handlers
│   ├── WindowListCellRenderer.kt   # Direct Graphics2D highlight/truncation renderer & row badges
│   └── SystemTrayController.kt     # SystemTray icon & menu (Show, Reload Config, Theme, Startup, Quit)
└── win32/
    ├── Win32Bindings.kt            # Panama FFM Linker, SymbolLookup, MethodHandles & StructLayouts
    ├── KeyboardHook.kt             # WH_KEYBOARD_LL upcall stub & dedicated Win32 message pump thread
    ├── WindowEnumerator.kt         # EnumWindows, DWMWA_CLOAKED, monitor info & ignore-list filtering
    ├── WindowActivator.kt          # SW_RESTORE, SW_MINIMIZE, WM_CLOSE, TerminateProcess & focus bypass
    ├── IconExtractor.kt            # HICON -> BufferedImage -> ImageIcon + async LRU cache by exePath
    ├── SingleInstanceMutex.kt      # CreateMutexW single-instance guard
    └── StartupRegistry.kt          # HKCU\...\Run registry read/write via advapi32.dll
```
{ignore-vars="true"}

<seealso>
    <category ref="developer-guide">
        <a href="win32-panama-ffm.md">Win32 &amp; Panama FFM Internals</a>
        <a href="building-and-releasing.md">Building, Testing &amp; Releasing</a>
    </category>
</seealso>
