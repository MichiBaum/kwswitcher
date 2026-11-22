# Software Requirements & Architecture Specification: Kotlin Switcheroo (`kwswitcher`)

**Version:** 1.2  
**Target Platform:** Windows 10 (21H2+) & Windows 11 (x64 / ARM64)  
**Primary Stack:** Kotlin/JVM (JDK 25), Swing with FlatLaf, Project Panama Foreign Function & Memory API (`java.lang.foreign`), Apache Maven

---

## 1. Product Overview & Objectives

**Kotlin Switcheroo (`kwswitcher`)** is a lightweight, keyboard-driven window switcher for Windows that replaces or augments the native `Alt + Tab` experience with incremental search. Designed as a background system-tray utility, it allows power users to switch between dozens of open windows by typing fragments of a window's title or application name.

### Core Design Goals
* **Sub-20ms Popup Latency:** Keep the JVM warm in the background and toggle an undecorated Swing + FlatLaf overlay rather than cold-starting processes or heavy GPU pipelines.
* **Zero-Click Keyboard Ergonomics:** Every action—invoking, filtering, navigating, switching, minimizing, closing, and terminating windows—must be executable without touching the mouse.
* **Zero-Dependency Native Interop:** Use JDK 25's built-in Project Panama Foreign Function & Memory (`java.lang.foreign`) API (`Linker`, `SymbolLookup`, `MemorySegment`, `Arena`) to invoke Win32 APIs (`user32.dll`, `kernel32.dll`, `dwmapi.dll`, `gdi32.dll`, `advapi32.dll`) directly without JNA/JNI overhead.
* **Resilient Win32 Interop:** Safely handle hung applications, elevated processes, UWP/cloaked windows, and multi-DPI monitor setups without freezing the Windows input pipeline.

---

## 2. Functional Requirements

| ID | Feature | Requirement Details |
| :--- | :--- | :--- |
| **FR-1** | **Configurable Global Hotkey Interception** | Intercept `Alt + Tab` (forward) and `Alt + Shift + Tab` (backward) system-wide by default via a low-level keyboard hook (`WH_KEYBOARD_LL`), swallowing the trigger key event when the modifier is depressed so Windows Explorer's native switcher does not activate. Allow users to customize the trigger modifier and key in `config.json`. Same-application window filtering is performed directly via search rather than a separate global hotkey. |
| **FR-2** | **Immediate Overlay (`QUICK_SWITCH` vs. `INTERACTIVE_SEARCH`)** | Pressing the trigger hotkey immediately displays the switcher overlay in `QUICK_SWITCH` mode with the 2nd window in Z-order (previously active window) pre-selected. Repeated trigger key presses while holding the modifier cycle selection forward/backward. Releasing the modifier while still in `QUICK_SWITCH` mode immediately activates the selected window and hides the overlay. Typing any search character, pressing arrow keys, or invoking an in-switcher window action transitions the session into `INTERACTIVE_SEARCH` mode, keeping the overlay open even after the modifier is released until `Enter`, `Esc`, or focus loss occurs. |
| **FR-3** | **Real Window Enumeration & Ignore List** | Enumerate top-level windows (`EnumWindows`) on the **current virtual desktop** in top-to-bottom Z-order and exclude: invisible windows (`IsWindowVisible`), empty-title windows, tool windows (`WS_EX_TOOLWINDOW` unless `WS_EX_APPWINDOW` is set), disabled windows, DWM-cloaked UWP or other-virtual-desktop windows (`DWMWA_CLOAKED`), the switcher's own `HWND`, and any window matching user-configured process names or title patterns in `config.json`. |
| **FR-4** | **Metadata, State & Icon Caching** | Resolve each window's title (`GetWindowTextW`), process ID (`GetWindowThreadProcessId`), executable path (`OpenProcess` with `PROCESS_QUERY_LIMITED_INFORMATION` + `QueryFullProcessImageNameW`), minimized state (`IsIconic`), display monitor (`MonitorFromWindow` + `GetMonitorInfoW`), and application icon (`WM_GETICON` / `GetClassLongPtrW`). Cache converted icons by executable path to avoid repeated GDI allocations (Phase 2). |
| **FR-5** | **Multi-Token Ranked Fuzzy Search** | Split the search query on whitespace into independent tokens. Every token must match against either the window title or the process name (allowing queries like `"chrome github"` or `"idea kwswitcher"`). Rank matching windows by cumulative token score across: (1) exact prefix match on process name or title, (2) word-boundary / camelCase / acronym match, (3) contiguous substring match, (4) fuzzy character subsequence match, and (5) Z-order recency tie-breaker. Return matched character index spans for UI highlighting. |
| **FR-6** | **List Navigation & In-Switcher Window Actions** | Support `Down`/`Up`, `Tab`/`Shift+Tab`, and `Ctrl+N`/`Ctrl+P` to move selection with wrap-around; `Enter` to activate; `Esc` or window deactivation to dismiss. Support in-place window management without closing the switcher: `Ctrl+W` or `Delete` to gracefully close (`PostMessageW(hwnd, WM_CLOSE, 0, 0)`), `Ctrl+M` to minimize in-place (`ShowWindow(hwnd, SW_MINIMIZE)`), and `Ctrl+Shift+W` to force-terminate the owning process (`OpenProcess(PROCESS_TERMINATE)` -> `TerminateProcess`) after a second confirmation press. |
| **FR-7** | **Reliable Focus Activation** | Restore minimized windows (`IsIconic` -> `ShowWindow(hwnd, SW_RESTORE)`) and reliably bring target windows to the foreground (`SetForegroundWindow`) by bypassing Windows' foreground-lock restrictions via synthetic input (`SendInput`) and/or `AttachThreadInput`. |
| **FR-8** | **Visual Badges & Theme Selection** | Render each window row with its application icon, highlighted title match spans, process name badge, a minimized indicator badge (`[Minimized]`) when `isMinimized` is true, and a monitor badge (`[Mon 2]`) when multiple monitors are connected. Support `DARK`, `LIGHT`, and `SYSTEM` (automatic Windows OS theme detection) via FlatLaf (`FlatDarkLaf` / `FlatLightLaf`). |
| **FR-9** | **System Tray, Config & Single Instance** | Enforce a single running instance (via Win32 named mutex `CreateMutexW`), persist configuration in `%APPDATA%\kwswitcher\config.json`, and provide a system tray icon with actions for *Show Switcher*, *Reload Config*, *Theme*, *Run at Windows Startup*, and *Quit* (Phase 3). |

---

## 3. Non-Functional Requirements

* **Invocation Performance:** Overlay must render and accept text input within **20 ms** of the hotkey press; multi-token fuzzy filtering across 100 open windows must complete in under **5 ms**.
* **Memory Footprint:** Resident idle memory usage should remain under **85 MB** (configured via `-XX:+UseG1GC` or `-XX:+UseZGC` with bounded heap `-Xmx128m`, `--enable-native-access=ALL-UNNAMED`, and scoped `Arena.ofConfined()` memory segments).
* **Multi-Monitor & DPI Awareness:** The overlay must open centered on the active monitor and scale crisply across mixed-DPI displays (100%–250%) using JDK 25 Swing HiDPI scaling and FlatLaf vector rendering.
* **Hook Safety:** The `WH_KEYBOARD_LL` upcall stub must execute in O(1) time and return in under **5 ms** (well below Windows' 300 ms `LowLevelHooksTimeout` threshold) so system-wide keyboard input never stutters.

---

## 4. System Architecture & Threading Model

Because Win32 hooks, window enumeration, and Swing UI rendering have strict thread-affinity rules, the application is divided into isolated layers across three execution contexts:

```text
┌──────────────────────────────────────────────────────────────────────┐
│ 1. Win32 Hook Thread (Dedicated OS Platform Thread)                  │
│    • Installs WH_KEYBOARD_LL via Panama FFM upcall stub              │
│    • Runs GetMessageW() / DispatchMessageW() loop                    │
│    • Emits HookKeyEvent via non-blocking Channel.trySend()           │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ Non-blocking Channel.trySend()
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 2. Application Coordinator & Domain Layer (Coroutine Scope)          │
│    • State machine: HIDDEN <-> QUICK_SWITCH <-> INTERACTIVE_SEARCH   │
│    • WindowEnumerator + WindowActivator + FuzzyMatcher + IconCache   │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ StateFlow<SwitcherUiState>
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 3. Presentation Layer (Swing AWT Event Dispatch Thread + FlatLaf)    │
│    • Undecorated, alwaysOnTop JDialog with FlatLaf Dark/Light theme  │
│    • Renders search field, highlighted match spans, and row badges   │
└──────────────────────────────────────────────────────────────────────┘
```

### State Machine Transitions (`FR-2` & `FR-6`)

| Current Mode | Event | Next Mode | Side Effects |
| :--- | :--- | :--- | :--- |
| `HIDDEN` | `Alt + Tab` (or configured hotkey) pressed | `QUICK_SWITCH` | Refresh open windows; set query `""`; select index `1` (or `0` if only 1 window); show overlay immediately. |
| `HIDDEN` | `Alt + Shift + Tab` pressed | `QUICK_SWITCH` | Refresh open windows; set query `""`; select last window (`size - 1`); show overlay immediately. |
| `QUICK_SWITCH` | `Tab` pressed (with modifier held) | `QUICK_SWITCH` | Advance `selectedIndex` cyclic `(index + 1) % count`. |
| `QUICK_SWITCH` | `Shift + Tab` pressed (with modifier held) | `QUICK_SWITCH` | Move `selectedIndex` cyclic `(index - 1 + count) % count`. |
| `QUICK_SWITCH` | Modifier (`Alt`) released | `HIDDEN` | Activate currently selected `WindowInfo`; hide overlay. |
| `QUICK_SWITCH` | Character typed or Arrow key pressed | `INTERACTIVE_SEARCH` | Lock overlay open; apply query filter or move selection; subsequent `Alt` release is ignored. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Ctrl + W` or `Delete` pressed | `INTERACTIVE_SEARCH` | Send `WM_CLOSE` to selected window; remove or refresh window list; clamp `selectedIndex`; keep overlay open. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Ctrl + M` pressed | `INTERACTIVE_SEARCH` | Minimize selected window (`SW_MINIMIZE`); mark `isMinimized = true` in UI state; keep overlay open. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Ctrl + Shift + W` pressed | `INTERACTIVE_SEARCH` | First press arms kill confirmation on selected `hwnd`; second press terminates process (`TerminateProcess`) and refreshes window list. |
| `INTERACTIVE_SEARCH` | Modifier (`Alt`) released | `INTERACTIVE_SEARCH` | No-op (overlay remains open for continued typing). |
| `INTERACTIVE_SEARCH` | Query updated | `INTERACTIVE_SEARCH` | Re-filter and rank windows via multi-token `FuzzyMatcher`; reset `selectedIndex = 0`. |
| `INTERACTIVE_SEARCH` | `Up` / `Down` / `Tab` / `Shift+Tab` / `Ctrl+N` / `Ctrl+P` | `INTERACTIVE_SEARCH` | Move `selectedIndex` cyclic forward or backward; clear any armed kill confirmation. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Enter` pressed | `HIDDEN` | Activate currently selected `WindowInfo`; hide overlay. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Esc` pressed or focus lost | `HIDDEN` | Cancel without switching; hide overlay. |

### Configuration Schema (`%APPDATA%\kwswitcher\config.json`)

```json
{
  "hotkey": {
    "modifier": "ALT",
    "triggerKey": "TAB",
    "reverseModifier": "SHIFT"
  },
  "theme": "SYSTEM",
  "showMonitorBadge": true,
  "showMinimizedBadge": true,
  "ignoredProcesses": [
    "TextInputHost.exe",
    "SearchHost.exe"
  ],
  "ignoredTitlePatterns": [
    "^NVIDIA GeForce Overlay$",
    "^Program Manager$"
  ]
}
```

### Package Layout

```text
src/main/kotlin/com/michibaum/kwswitcher/
├── Main.kt                         # Entry point, single-instance mutex, theme & tray wiring
├── config/
│   ├── SwitcherConfig.kt           # Configuration data model (hotkey, theme, ignore rules)
│   ├── ConfigRepository.kt         # Loads/saves %APPDATA%\kwswitcher\config.json
│   └── ThemeManager.kt             # FlatDarkLaf / FlatLightLaf / Windows OS theme detection
├── coordinator/
│   ├── SwitcherMode.kt             # HIDDEN, QUICK_SWITCH, INTERACTIVE_SEARCH
│   ├── SwitcherUiState.kt          # Immutable UI state snapshot + match highlights + armed kill
│   └── SwitcherStateMachine.kt     # Pure state transition logic + coordinator
├── win32/
│   ├── Win32Bindings.kt            # Panama FFM Linker, SymbolLookup, MethodHandles & StructLayouts
│   ├── KeyboardHook.kt             # WH_KEYBOARD_LL upcall stub & Win32 message pump thread
│   ├── WindowEnumerator.kt         # EnumWindows, DWMWA_CLOAKED, monitor info, ignore-list filter
│   ├── WindowActivator.kt          # SW_RESTORE, SW_MINIMIZE, WM_CLOSE, TerminateProcess, focus
│   ├── IconExtractor.kt            # HICON -> BufferedImage -> ImageIcon + LRU cache by exePath
│   └── SingleInstanceMutex.kt      # CreateMutexW single-instance guard
├── domain/
│   ├── WindowInfo.kt               # Data model (hwnd, title, pid, processName, exePath, zOrder, isMinimized, monitorId)
│   ├── WindowMatch.kt              # WindowInfo + score + matched character index ranges
│   └── WindowFilter.kt             # Multi-token ranked fuzzy matcher & highlight span calculator
└── ui/
    ├── SwitcherOverlay.kt          # Undecorated FlatLaf JDialog, search input, key bindings
    ├── WindowListCellRenderer.kt   # Row renderer with icon, highlighted title, state/monitor/process badges
    └── SystemTrayController.kt     # SystemTray menu (Show, Reload Config, Theme, Startup, Quit)
```

---

## 5. Critical Win32 & Panama FFM Edge Cases & Mitigations

1. **Panama FFM Arena Lifetimes (`java.lang.foreign`):**
   * Temporary buffers for `GetWindowTextW`, `QueryFullProcessImageNameW`, `DwmGetWindowAttribute`, `GetMonitorInfoW`, and `SendInput` must be allocated inside `Arena.ofConfined().use { arena -> ... }` so native memory is deterministic and freed immediately without GC pressure.
   * Upcall stubs for `EnumWindowsProc` must live inside the enumeration call's confined `Arena`, whereas the persistent `LowLevelKeyboardProc` upcall stub must be allocated in an `Arena.ofShared()` owned by `KeyboardHook` and closed when the hook thread shuts down.
2. **Blocking on Hung Windows (`WM_GETICON`):**  
   Calling `SendMessage(hwnd, WM_GETICON, ...)` on a frozen application blocks the caller indefinitely. Always use `SendMessageTimeoutW` with `SMTO_ABORTIFHUNG` and a `50 ms` timeout, falling back to `GetClassLongPtrW(hwnd, GCLP_HICON)` and finally the default Windows application icon.
3. **Foreground Lock (`SetForegroundWindow` Rejection):**  
   Windows blocks background processes from stealing focus unless the calling thread owns the foreground window or received the last input event. Because the switcher is activated via a low-level hook, attach thread input (`AttachThreadInput`) or simulate a zero-effect key event via `SendInput` immediately prior to calling `SetForegroundWindow`.
4. **Administrator / Elevated Windows (UIPI):**  
   Windows User Interface Privilege Isolation prevents a standard-privilege `WH_KEYBOARD_LL` hook from intercepting hotkeys when an elevated window (e.g., Task Manager, elevated PowerShell) is focused, and prevents `WM_CLOSE` / `TerminateProcess` against elevated targets. Provide an optional installation flag or Task Scheduler registration to launch the app with highest privileges at logon.
5. **GDI & Process Handle Leaks:**  
   Every `HANDLE` opened via `OpenProcess` must be closed in a `finally` block via `CloseHandle`. Every `HICON` / `HBITMAP` obtained during icon extraction (`GetIconInfo`, `GetDIBits`) must be released via `DeleteObject` / `DestroyIcon` immediately after pixel extraction.

---

## 6. Implementation Roadmap

* **Phase 1 — Core Win32 Engine & Maven Foundation (Completed):**
  * Convert repository to Apache Maven targeting JDK 25 with Kotlin 2.x and `--enable-native-access=ALL-UNNAMED`.
  * Implement Panama FFM bindings (`Win32Bindings`), `WindowEnumerator`, `WindowActivator`, and `KeyboardHook` (`WH_KEYBOARD_LL` with `Alt + Tab` / `Alt + Shift + Tab` interception).
  * Implement `SwitcherStateMachine` supporting the **Immediate Overlay** `QUICK_SWITCH` and `INTERACTIVE_SEARCH` state transitions and case-insensitive substring filtering (`WindowFilter`).
  * Implement the Swing + FlatLaf `SwitcherOverlay` UI and wire everything in `Main.kt`.
* **Phase 2 — Multi-Token Fuzzy Search, Icons, Badges & Window Actions (Completed):**
  * Add `IconExtractor` (`HICON` -> `BufferedImage` -> `ImageIcon` with LRU cache by executable path).
  * Enrich `WindowInfo` with `pid`, `isMinimized` (`IsIconic`), and `monitorId` (`MonitorFromWindow` / `GetMonitorInfoW`), and render `[Minimized]` and `[Mon N]` badges in `WindowListCellRenderer`.
  * Upgrade `WindowFilter` to a multi-token ranked fuzzy matcher returning matched character spans for bold/colored highlighting in `WindowListCellRenderer`.
  * Add in-switcher window actions: `Ctrl + W` / `Delete` (`WM_CLOSE`), `Ctrl + M` (`SW_MINIMIZE` in-place), and `Ctrl + Shift + W` (two-step confirmed `TerminateProcess`).
* **Phase 3 — Configuration, Themes, Multi-Monitor Placement, System Tray & Packaging (Completed):**
  * Implement `SwitcherConfig` & `ConfigRepository` (`%APPDATA%\kwswitcher\config.json`) supporting custom hotkeys, process/title ignore lists, and `DARK` / `LIGHT` / `SYSTEM` theme switching (`ThemeManager`).
  * Center `SwitcherOverlay` on the currently active monitor's work area (`GetCursorPos` / `GetForegroundWindow` -> `MonitorFromWindow`).
  * Add single-instance mutex (`SingleInstanceMutex` via `CreateMutexW`) and `SystemTrayController` (`java.awt.SystemTray`).
  * Configure `jpackage` via Maven for native Windows `.msi` / `.exe` installer generation.