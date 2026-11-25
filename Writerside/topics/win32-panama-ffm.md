# Win32 &amp; Panama FFM Internals

%product% (`%cmd_name%`) interacts with the Windows OS exclusively through JDK %jdk_version%'s finalized **Project Panama Foreign Function &amp; Memory (`java.lang.foreign`)** API—requiring zero JNI C/C++ glue libraries and zero third-party native dependencies such as JNA.

## Native Binding Architecture (`Win32Bindings.kt`)

All Win32 downcall `MethodHandle` bindings, C struct `MemoryLayout` definitions, `VarHandle` field accessors, and callback `FunctionDescriptor` declarations live in `com.michibaum.kwswitcher.win32.Win32Bindings`:

* **DLL Symbol Lookups:** Loaded eagerly via `SymbolLookup.libraryLookup(name, Arena.global())` across five standard Windows system libraries:
    * `user32.dll` — `SetWindowsHookExW`, `CallNextHookEx`, `GetMessageW`, `EnumWindows`, `GetWindowTextW`, `SetForegroundWindow`, `AttachThreadInput`, `SendInput`, `SendMessageTimeoutW`, `PostMessageW`, `ShowWindow`, `MonitorFromWindow`, `GetMonitorInfoW`
    * `kernel32.dll` — `GetCurrentThreadId`, `GetModuleHandleW`, `OpenProcess`, `QueryFullProcessImageNameW`, `TerminateProcess`, `CreateMutexW`, `ReleaseMutex`, `CloseHandle`, `GetLastError`
    * `dwmapi.dll` — `DwmGetWindowAttribute` (`DWMWA_CLOAKED`)
    * `gdi32.dll` — `GetObjectW`, `CreateCompatibleDC`, `DeleteDC`, `GetDIBits`, `DeleteObject`
    * `advapi32.dll` — `RegGetValueW`, `RegSetKeyValueW`, `RegDeleteKeyValueW`, `RegDeleteKeyW`
* **Zero-Boxing Invocation:** Every native call uses `MethodHandle.invokeExact(...)` against precomputed `FunctionDescriptor` layouts, avoiding reflection and per-call descriptor allocation.

## Five Critical Win32 &amp; Panama Edge-Case Mitigations

Desktop window managers on Windows face subtle OS traps—from frozen GUI message queues to focus-stealing locks and GDI handle exhaustion. `%cmd_name%` enforces five explicit engineering mitigations across `win32/`:

### 1. Deterministic Panama `Arena` Lifetimes

Choosing the wrong `Arena` scope in `java.lang.foreign` causes either premature use-after-free crashes or native memory leaks:

* **Short-lived native buffers (`Arena.ofConfined()`):** Temporary structs and UTF-16 buffers (`POINT`, `MONITORINFO`, `ICONINFO`, `BITMAPINFOHEADER`, `INPUT[]`, registry strings, and `WindowEnumerator.ReusableScratch`) are allocated inside `Arena.ofConfined().use { arena -> ... }`. Native memory is freed deterministically the instant the lexical block exits—generating zero GC pressure.
* **Persistent hook upcall (`Arena.ofShared()`):** `KeyboardHook` allocates its `LowLevelKeyboardProc` upcall stub inside an `Arena.ofShared()` owned by the hook thread. On shutdown, `KeyboardHook.close()` posts `WM_QUIT` (`PostThreadMessageW`), guarantees `UnhookWindowsHookEx` runs in `finally`, and only then exits the `Arena.ofShared().use` block so Windows can never invoke a freed function pointer.
* **Zero-allocation enumeration callback (`Arena.global()` + `ThreadLocal`):** Rather than allocating a fresh native upcall stub on every `Alt+Tab` press, `WindowEnumerator.UpcallBridge` binds a single `EnumWindowsProc` stub in `Arena.global()` and dispatches to the active call's `WindowVisitor` via a `ThreadLocal` cleared in `finally`.

### 2. Non-Blocking Icon Extraction on Hung Windows (`SMTO_ABORTIFHUNG`)

Calling a synchronous `SendMessageW(hwnd, WM_GETICON, ...)` on an unresponsive ("Not Responding") application blocks the calling thread indefinitely until the target process recovers or is killed.

`IconExtractor` prevents hung windows from ever stalling the switcher:

1. **50 ms hung-abort timeout:** Queries `ICON_SMALL2`, `ICON_SMALL`, and `ICON_BIG` using `SendMessageTimeoutW` with `SMTO_ABORTIFHUNG` and a strict `50 ms` timeout.
2. **Multi-tier fallback chain:** If `SendMessageTimeoutW` times out or returns `NULL`, `IconExtractor` falls back to `GetClassLongPtrW` (`GCLP_HICONSM` / `GCLP_HICON`), then `FileSystemView.getSystemIcon(File(exePath))`, and finally a crisp vector-painted fallback icon.
3. **Async EDT decoupling &amp; LRU caching:** `WindowListCellRenderer` calls `getIconOrDefault()`, which returns immediately on the Swing EDT (serving from a `128`-entry LRU cache keyed by lowercase `exePath` or returning the default icon while deduplicated background coroutines resolve and repaint the row).

### 3. Bypassing Windows Foreground Lock (`SetForegroundWindow`)

Windows prevents background processes from stealing focus via `SetForegroundWindow` unless the calling thread currently owns the foreground window or satisfies strict foreground-lock rules.

When activating a target window in `WindowActivator.activate()`:

1. If `IsIconic(targetHwnd)` is non-zero, `ShowWindow(targetHwnd, SW_RESTORE)` unminimizes the window first.
2. `GetForegroundWindow` + `GetWindowThreadProcessId` identifies the current foreground thread, and `AttachThreadInput(..., 1)` temporarily shares input-processing state when the threads differ.
3. `sendZeroEffectAltKeyTap` dispatches a synthetic two-event `VK_MENU` keydown + keyup sequence via `SendInput` inside a confined `Arena` to satisfy Windows' recent-input requirement.
4. `BringWindowToTop` and `SetForegroundWindow` promote the window, and a `finally` block guarantees `AttachThreadInput(..., 0)` detaches thread input cleanly.

### 4. Administrator &amp; Elevated Windows (UIPI)

Windows **User Interface Privilege Isolation (UIPI)** isolates lower-integrity processes from higher-integrity (Administrator) windows:

* When an elevated window (such as Task Manager or an Administrator terminal) owns focus, Windows does not deliver `WH_KEYBOARD_LL` hook callbacks to unelevated processes, so the native OS `Alt+Tab` handles input automatically.
* Similarly, unelevated `PostMessageW(WM_CLOSE)` and `OpenProcess(PROCESS_TERMINATE)` calls against elevated targets fail gracefully (`WindowActivator` checks return codes and `MemorySegment.NULL` handles without throwing).
* Users who want seamless switching out of elevated windows can run `kwswitcher.exe` elevated via a Windows Task Scheduler logon task configured with **Run with highest privileges**.

### 5. Preventing GDI &amp; Kernel `HANDLE` Leaks

Long-running tray utilities must never leak kernel handles or GDI objects across thousands of invocations:

* **Process &amp; Mutex Handles:** Every `HANDLE` returned by `OpenProcess` (`WindowEnumerator.queryProcessImagePath` and `WindowActivator.terminateProcess`) is closed via `CloseHandle` inside a `try / finally` block. `SingleInstanceMutex.close()` releases and closes the named mutex (`ReleaseMutex` + `CloseHandle`) via a JVM shutdown hook and explicit `Quit` handler.
* **GDI Bitmaps &amp; Device Contexts:** `GetIconInfo` allocates *new* `HBITMAP` copies (`hbmMask` and `hbmColor`) that the caller owns. `IconExtractor.convertHIconToImage()` extracts 32-bit top-down ARGB pixels via `CreateCompatibleDC` + `GetDIBits` and unconditionally calls `DeleteDC(hdc)`, `DeleteObject(hbmMask)`, and `DeleteObject(hbmColor)` in `finally`.

<seealso>
    <category ref="developer-guide">
        <a href="architecture.md">System Architecture</a>
        <a href="building-and-releasing.md">Building, Testing &amp; Releasing</a>
    </category>
</seealso>
