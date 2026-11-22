# Software Requirements & Architecture Specification: Kotlin Switcheroo

**Version:** 1.0  
**Target Platform:** Windows 10 (21H2+) & Windows 11 (x64 / ARM64)  
**Primary Stack:** Kotlin/JVM (JDK 21), Compose Multiplatform Desktop, JNA (`jna-platform`)

---

## 1. Product Overview & Objectives

**Kotlin Switcheroo** is a lightweight, keyboard-driven window switcher for Windows that replaces or augments the native `Alt + Tab` experience with incremental fuzzy search. Designed as a background system-tray utility, it allows power users to switch between dozens of open windows by typing fragments of a window's title or application name.

### Core Design Goals
* **Sub-20ms Popup Latency:** Keep the JVM warm in the background and toggle an undecorated Compose surface rather than cold-starting processes.
* **Zero-Click Keyboard Ergonomics:** Every action—invoking, filtering, navigating, switching, and closing windows—must be executable without touching the mouse.
* **Resilient Win32 Interop:** Safely handle hung applications, elevated processes, UWP/cloaked windows, and multi-DPI monitor setups without freezing the Windows input pipeline.

---

## 2. Functional Requirements

| ID | Feature | Requirement Details |
| :--- | :--- | :--- |
| **FR-1** | **Global Hotkey Interception** | Intercept `Alt + Tab` and `Alt + Shift + Tab` system-wide via a low-level keyboard hook (`WH_KEYBOARD_LL`). Swallow the key event so Windows Explorer's native switcher does not trigger. |
| **FR-2** | **Quick-Switch vs. Search Mode** | If `Alt + Tab` is pressed and `Alt` is released immediately without typing, switch directly to the previously active window (MRU order). If the user types characters or presses arrow keys, keep the overlay open in interactive filter mode until `Enter` or `Esc` is pressed. |
| **FR-3** | **Real Window Enumeration** | Enumerate top-level windows (`EnumWindows`) and exclude invisible windows (`IsWindowVisible`), empty-title windows, tool windows (`WS_EX_TOOLWINDOW`), DWM-cloaked UWP/virtual-desktop windows (`DWMWA_CLOAKED`), and the switcher's own `HWND`. |
| **FR-4** | **Metadata & Icon Caching** | Resolve each window's title (`GetWindowTextW`), executable name (`QueryFullProcessImageNameW`), and application icon (`WM_GETICON` / `GetClassLongPtrW`). Cache converted `ImageBitmap` icons by executable path to avoid repeated GDI/Skia allocations. |
| **FR-5** | **Fuzzy Search & Ranking** | Filter the window list in real time against both window title and executable name. Rank matches by: (1) exact prefix match, (2) word-boundary acronym/substring match, (3) fuzzy character subsequence, and (4) Z-order recency tie-breaker. |
| **FR-6** | **List Navigation & Actions** | Support `Down`/`Up`, `Tab`/`Shift+Tab`, and `Ctrl+N`/`Ctrl+P` to move selection; `Enter` to activate; `Esc` or outside click to dismiss; and `Ctrl+W` or `Delete` to send `WM_CLOSE` to the highlighted window. |
| **FR-7** | **Reliable Focus Activation** | Restore minimized windows (`ShowWindow(hwnd, SW_RESTORE)`) and reliably bring target windows to the foreground (`SetForegroundWindow`) by bypassing Windows' foreground-lock restrictions. |
| **FR-8** | **System Tray & Single Instance** | Enforce a single running instance (via Win32 named mutex `CreateMutexW`). Provide a system tray icon with actions for *Show Switcher*, *Run at Windows Startup*, and *Quit*. |

---

## 3. Non-Functional Requirements

* **Invocation Performance:** Overlay must render and accept text input within **20 ms** of the `Alt + Tab` keypress; keystroke filtering across 100 open windows must complete in under **5 ms**.
* **Memory Footprint:** Resident idle memory usage should remain under **85 MB** (configured via `-XX:+UseSerialGC` or `-XX:+UseG1GC` with bounded heap `-Xmx128m` and explicit GDI handle cleanup via `DestroyIcon`).
* **Multi-Monitor & DPI Awareness:** The overlay must open centered on the monitor containing the active foreground window (or mouse cursor) and scale crisply across mixed-DPI displays (100%–250%).
* **Hook Safety:** The `WH_KEYBOARD_LL` callback must return in under **5 ms** (well below Windows' 300 ms `LowLevelHooksTimeout` threshold) so system-wide keyboard input never stutters.

---

## 4. System Architecture & Threading Model

Because Win32 hooks, window enumeration, and Compose UI rendering have strict thread-affinity rules, the application is divided into four isolated layers across three execution contexts:

```text
┌──────────────────────────────────────────────────────────────────────┐
│ 1. Win32 Hook Thread (Dedicated OS Daemon Thread)                    │
│    • Runs GetMessage() / DispatchMessage() loop                      │
│    • Hosts WH_KEYBOARD_LL callback -> emits HookEvent to Channel     │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ Non-blocking Channel.trySend()
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 2. Application Coordinator & Domain Layer (Coroutine Default Pool)   │
│    • State machine: HIDDEN <-> QUICK_SWITCH <-> INTERACTIVE_SEARCH   │
│    • WindowEnumerator + IconCache + FuzzyMatcher                     │
└──────────────────────────────────┬───────────────────────────────────┘
                                   │ Compose StateFlow / SnapshotState
                                   ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 3. Presentation Layer (Swing / Compose AWT EDT)                      │
│    • Undecorated, transparent, alwaysOnTop Compose Window            │
│    • Renders search bar, highlighted match spans, and window rows    │
└──────────────────────────────────────────────────────────────────────┘
```

### Package Layout

```text
src/main/kotlin/com/switcher/
├── Main.kt                     # Entry point, single-instance mutex, Compose Tray & Window
├── coordinator/
│   └── SwitcherStateMachine.kt # Manages visibility state, MRU order, and key routing
├── win32/
│   ├── KeyboardHook.kt         # WH_KEYBOARD_LL installation & Win32 message pump
│   ├── WindowEnumerator.kt     # EnumWindows, DWM cloaked checks, process path lookup
│   ├── WindowActivator.kt      # SW_RESTORE + AttachThreadInput / SendInput focus logic
│   └── IconExtractor.kt        # HICON -> BufferedImage -> Compose ImageBitmap + LRU cache
├── domain/
│   ├── WindowInfo.kt           # Data model (hwnd, title, processName, exePath, zOrder)
│   └── FuzzyMatcher.kt         # Scoring algorithm + matched character index ranges
└── ui/
    ├── SwitcherOverlay.kt      # Root Compose card, search input, keyboard shortcuts
    └── WindowListItem.kt       # Row component with app icon, highlighted title, and exe badge
```

---

## 5. Critical Win32 Edge Cases & Mitigations

1. **Blocking on Hung Windows (`WM_GETICON`):**  
   Calling `SendMessage(hwnd, WM_GETICON, ...)` on a frozen application blocks the caller indefinitely. Always use `SendMessageTimeout` with `SMTO_ABORTIFHUNG` and a `50 ms` timeout, falling back to `GetClassLongPtrW(hwnd, GCLP_HICON)` and finally the default Windows application icon.
2. **Foreground Lock (`SetForegroundWindow` Rejection):**  
   Windows blocks background processes from stealing focus unless the calling thread owns the foreground window or received the last input event. Because the switcher is activated via a low-level hook, simulate a zero-effect `Alt` key-up/key-down via `SendInput` (or attach thread input via `AttachThreadInput` to the current foreground thread) immediately prior to calling `SetForegroundWindow`.
3. **Administrator / Elevated Windows (UIPI):**  
   Windows User Interface Privilege Isolation prevents a standard-privilege `WH_KEYBOARD_LL` hook from intercepting `Alt + Tab` when an elevated window (e.g., Task Manager, elevated PowerShell) is focused. Provide an optional installation flag or Task Scheduler XML registration to launch the app with highest privileges at logon.
4. **GDI Handle Leaks:**  
   Every `HICON` copied via `CopyIcon` or `ExtractIconEx` consumes a process GDI handle (Windows caps processes at 10,000 GDI handles). Ensure `User32.INSTANCE.DestroyIcon(hIcon)` is called in a `finally` block immediately after converting the icon pixels into a JVM `ImageBitmap`.

---

## 6. Implementation Roadmap

* **Phase 1 — Core Win32 Engine (MVP):** Implement `WindowEnumerator`, `WindowActivator`, and `KeyboardHook` with `Alt + Tab` interception and basic substring filtering.
* **Phase 2 — Search & Icon Polish:** Add `IconExtractor` with LRU caching, `FuzzyMatcher` with matched-character highlighting in Compose, and `Ctrl+W` window closing.
* **Phase 3 — Multi-Monitor & Packaging:** Add cursor/active-window monitor detection for popup placement, persistent JSON configuration (`%APPDATA%\KotlinSwitcheroo\config.json`), and native `.msi` packaging via Compose Desktop's `packageMsi` Gradle task.