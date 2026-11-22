# Software Requirements & Architecture Specification: Kotlin Switcheroo (`kwswitcher`)

**Version:** 1.1  
**Target Platform:** Windows 10 (21H2+) & Windows 11 (x64 / ARM64)  
**Primary Stack:** Kotlin/JVM (JDK 25), Swing with FlatLaf, Project Panama Foreign Function & Memory API (`java.lang.foreign`), Apache Maven

---

## 1. Product Overview & Objectives

**Kotlin Switcheroo (`kwswitcher`)** is a lightweight, keyboard-driven window switcher for Windows that replaces or augments the native `Alt + Tab` experience with incremental search. Designed as a background system-tray utility, it allows power users to switch between dozens of open windows by typing fragments of a window's title or application name.

### Core Design Goals
* **Sub-20ms Popup Latency:** Keep the JVM warm in the background and toggle an undecorated Swing + FlatLaf overlay rather than cold-starting processes or heavy GPU pipelines.
* **Zero-Click Keyboard Ergonomics:** Every action—invoking, filtering, navigating, switching, and closing windows—must be executable without touching the mouse.
* **Zero-Dependency Native Interop:** Use JDK 25's built-in Project Panama Foreign Function & Memory (`java.lang.foreign`) API (`Linker`, `SymbolLookup`, `MemorySegment`, `Arena`) to invoke Win32 APIs (`user32.dll`, `kernel32.dll`, `dwmapi.dll`) directly without JNA/JNI overhead.
* **Resilient Win32 Interop:** Safely handle hung applications, elevated processes, UWP/cloaked windows, and multi-DPI monitor setups without freezing the Windows input pipeline.

---

## 2. Functional Requirements

| ID | Feature | Requirement Details |
| :--- | :--- | :--- |
| **FR-1** | **Global Hotkey Interception** | Intercept `Alt + Tab` and `Alt + Shift + Tab` system-wide via a low-level keyboard hook (`WH_KEYBOARD_LL`). Swallow the `Tab` key event when `Alt` (`VK_LMENU` / `VK_RMENU`) is depressed so Windows Explorer's native switcher does not trigger. |
| **FR-2** | **Immediate Overlay (`QUICK_SWITCH` vs. `INTERACTIVE_SEARCH`)** | Pressing `Alt + Tab` immediately displays the switcher overlay in `QUICK_SWITCH` mode with the 2nd window in Z-order (previously active window) pre-selected. Repeated `Tab` / `Shift + Tab` while holding `Alt` cycles selection forward/backward. Releasing `Alt` while still in `QUICK_SWITCH` mode immediately activates the selected window and hides the overlay. Typing any search character or pressing arrow keys transitions the session into `INTERACTIVE_SEARCH` mode, keeping the overlay open even after `Alt` is released until `Enter`, `Esc`, or focus loss occurs. |
| **FR-3** | **Real Window Enumeration** | Enumerate top-level windows (`EnumWindows`) in top-to-bottom Z-order and exclude: invisible windows (`IsWindowVisible`), empty-title windows, tool windows (`WS_EX_TOOLWINDOW` unless `WS_EX_APPWINDOW` is set), disabled windows, DWM-cloaked UWP/virtual-desktop windows (`DWMWA_CLOAKED`), and the switcher's own `HWND`. |
| **FR-4** | **Metadata & Icon Caching** | Resolve each window's title (`GetWindowTextW`), process ID (`GetWindowThreadProcessId`), executable path (`OpenProcess` with `PROCESS_QUERY_LIMITED_INFORMATION` + `QueryFullProcessImageNameW`), and application icon (`WM_GETICON` / `GetClassLongPtrW`). Cache converted icons by executable path to avoid repeated GDI allocations (Phase 2). |
| **FR-5** | **Substring & Fuzzy Search Ranking** | Filter the window list in real time against both window title and executable name. Phase 1 provides case-insensitive substring matching across title and process name; Phase 2 expands this into ranked fuzzy matching by: (1) exact prefix match, (2) word-boundary acronym/substring match, (3) fuzzy character subsequence, and (4) Z-order recency tie-breaker. |
| **FR-6** | **List Navigation & Actions** | Support `Down`/`Up`, `Tab`/`Shift+Tab`, and `Ctrl+N`/`Ctrl+P` to move selection with wrap-around; `Enter` to activate; `Esc` or window deactivation to dismiss; and `Ctrl+W` or `Delete` to send `WM_CLOSE` (`PostMessageW`) to the highlighted window (Phase 2). |
| **FR-7** | **Reliable Focus Activation** | Restore minimized windows (`IsIconic` -> `ShowWindow(hwnd, SW_RESTORE)`) and reliably bring target windows to the foreground (`SetForegroundWindow`) by bypassing Windows' foreground-lock restrictions via synthetic `Alt` input (`SendInput`) and/or `AttachThreadInput`. |
| **FR-8** | **System Tray & Single Instance** | Enforce a single running instance (via Win32 named mutex `CreateMutexW`) and provide a system tray icon with actions for *Show Switcher*, *Run at Windows Startup*, and *Quit* (Phase 3). |

---

## 3. Non-Functional Requirements

* **Invocation Performance:** Overlay must render and accept text input within **20 ms** of the `Alt + Tab` keypress; keystroke filtering across 100 open windows must complete in under **5 ms**.
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
│    • WindowEnumerator + WindowActivator + WindowFilter               │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ StateFlow<SwitcherUiState>
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 3. Presentation Layer (Swing AWT Event Dispatch Thread + FlatLaf)    │
│    • Undecorated, alwaysOnTop JDialog with rounded FlatLaf styling   │
│    • Renders search field, window list rows, and process badges      │
└──────────────────────────────────────────────────────────────────────┘
```

### State Machine Transitions (`FR-2` Immediate Overlay)

| Current Mode | Event | Next Mode | Side Effects |
| :--- | :--- | :--- | :--- |
| `HIDDEN` | `Alt + Tab` pressed | `QUICK_SWITCH` | Refresh open windows; set query `""`; select index `1` (or `0` if only 1 window); show overlay immediately. |
| `HIDDEN` | `Alt + Shift + Tab` pressed | `QUICK_SWITCH` | Refresh open windows; set query `""`; select last window (`size - 1`); show overlay immediately. |
| `QUICK_SWITCH` | `Tab` pressed (with `Alt` held) | `QUICK_SWITCH` | Advance `selectedIndex` cyclic `(index + 1) % count`. |
| `QUICK_SWITCH` | `Shift + Tab` pressed (with `Alt` held) | `QUICK_SWITCH` | Move `selectedIndex` cyclic `(index - 1 + count) % count`. |
| `QUICK_SWITCH` | `Alt` released | `HIDDEN` | Activate currently selected `WindowInfo`; hide overlay. |
| `QUICK_SWITCH` | Character typed or Arrow key pressed | `INTERACTIVE_SEARCH` | Lock overlay open; apply query filter or move selection; subsequent `Alt` release is ignored. |
| `INTERACTIVE_SEARCH` | `Alt` released | `INTERACTIVE_SEARCH` | No-op (overlay remains open for continued typing). |
| `INTERACTIVE_SEARCH` | Query updated | `INTERACTIVE_SEARCH` | Re-filter windows; reset `selectedIndex = 0`. |
| `INTERACTIVE_SEARCH` | `Up` / `Down` / `Tab` / `Shift+Tab` / `Ctrl+N` / `Ctrl+P` | `INTERACTIVE_SEARCH` | Move `selectedIndex` cyclic forward or backward. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Enter` pressed | `HIDDEN` | Activate currently selected `WindowInfo`; hide overlay. |
| `QUICK_SWITCH` or `INTERACTIVE_SEARCH` | `Esc` pressed or focus lost | `HIDDEN` | Cancel without switching; hide overlay. |

### Package Layout

```text
src/main/kotlin/com/michibaum/kwswitcher/
├── Main.kt                         # Entry point, FlatLaf setup, coordinator wiring
├── coordinator/
│   ├── SwitcherMode.kt             # HIDDEN, QUICK_SWITCH, INTERACTIVE_SEARCH
│   ├── SwitcherUiState.kt          # Immutable UI state snapshot
│   └── SwitcherStateMachine.kt     # Pure state transition logic + coordinator
├── win32/
│   ├── Win32Bindings.kt            # Panama FFM Linker, SymbolLookup, MethodHandles & StructLayouts
│   ├── KeyboardHook.kt             # WH_KEYBOARD_LL upcall stub & Win32 message pump thread
│   ├── WindowEnumerator.kt         # EnumWindows, DWMWA_CLOAKED check, process path lookup
│   └── WindowActivator.kt          # SW_RESTORE + AttachThreadInput / SendInput + SetForegroundWindow
├── domain/
│   ├── WindowInfo.kt               # Data model (hwnd, title, processName, exePath, zOrder)
│   └── WindowFilter.kt             # Substring/fuzzy filtering & ranking (Phase 1 -> Phase 2)
└── ui/
    ├── SwitcherOverlay.kt          # Undecorated FlatLaf JDialog, search input, key bindings
    └── WindowListCellRenderer.kt   # Custom Swing cell renderer with title and process badge
```

---

## 5. Critical Win32 & Panama FFM Edge Cases & Mitigations

1. **Panama FFM Arena Lifetimes (`java.lang.foreign`):**
   * Temporary buffers for `GetWindowTextW`, `QueryFullProcessImageNameW`, `DwmGetWindowAttribute`, and `SendInput` must be allocated inside `Arena.ofConfined().use { arena -> ... }` so native memory is deterministic and freed immediately without GC pressure.
   * Upcall stubs for `EnumWindowsProc` must live inside the enumeration call's confined `Arena`, whereas the persistent `LowLevelKeyboardProc` upcall stub must be allocated in an `Arena.ofShared()` owned by `KeyboardHook` and closed when the hook thread shuts down.
2. **Blocking on Hung Windows (`WM_GETICON`):**  
   Calling `SendMessage(hwnd, WM_GETICON, ...)` on a frozen application blocks the caller indefinitely. Always use `SendMessageTimeoutW` with `SMTO_ABORTIFHUNG` and a `50 ms` timeout, falling back to `GetClassLongPtrW(hwnd, GCLP_HICON)` and finally the default Windows application icon.
3. **Foreground Lock (`SetForegroundWindow` Rejection):**  
   Windows blocks background processes from stealing focus unless the calling thread owns the foreground window or received the last input event. Because the switcher is activated via a low-level hook, attach thread input (`AttachThreadInput`) or simulate a zero-effect key event via `SendInput` immediately prior to calling `SetForegroundWindow`.
4. **Administrator / Elevated Windows (UIPI):**  
   Windows User Interface Privilege Isolation prevents a standard-privilege `WH_KEYBOARD_LL` hook from intercepting `Alt + Tab` when an elevated window (e.g., Task Manager, elevated PowerShell) is focused. Provide an optional installation flag or Task Scheduler XML registration to launch the app with highest privileges at logon.
5. **GDI & Process Handle Leaks:**  
   Every `HANDLE` opened via `OpenProcess` must be closed in a `finally` block via `CloseHandle`. Every `HICON` copied via `CopyIcon` or `ExtractIconEx` must be destroyed via `DestroyIcon` immediately after pixel extraction.

---

## 6. Implementation Roadmap

* **Phase 1 — Core Win32 Engine & Maven Foundation (Current Scope):**
  * Convert repository to Apache Maven targeting JDK 25 with Kotlin 2.x and `--enable-native-access=ALL-UNNAMED`.
  * Implement Panama FFM bindings (`Win32Bindings`), `WindowEnumerator`, `WindowActivator`, and `KeyboardHook` (`WH_KEYBOARD_LL` with `Alt + Tab` / `Alt + Shift + Tab` interception).
  * Implement `SwitcherStateMachine` supporting the **Immediate Overlay** `QUICK_SWITCH` and `INTERACTIVE_SEARCH` state transitions and case-insensitive substring filtering (`WindowFilter`).
  * Implement the Swing + FlatLaf `SwitcherOverlay` UI and wire everything in `Main.kt`.
* **Phase 2 — Fuzzy Search & Icon Polish:**
  * Add `IconExtractor` (`HICON` -> `BufferedImage` + LRU cache by executable path).
  * Upgrade `WindowFilter` to full `FuzzyMatcher` with matched-character index highlighting in `WindowListCellRenderer`.
  * Add `Ctrl + W` / `Delete` window closing via `PostMessageW(hwnd, WM_CLOSE, 0, 0)`.
* **Phase 3 — Multi-Monitor, System Tray & Packaging:**
  * Add active-monitor detection (`MonitorFromWindow` / `GetMonitorInfoW`) for multi-monitor overlay placement.
  * Add single-instance mutex (`CreateMutexW`), system tray icon (`SystemTray`), and persistent configuration (`%APPDATA%\kwswitcher\config.json`).
  * Configure `jpackage` via Maven for native Windows `.msi` / `.exe` installer generation.