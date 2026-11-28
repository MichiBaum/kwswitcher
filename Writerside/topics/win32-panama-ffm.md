# Win32 & Panama FFM Internals

%product% (`%cmd_name%`) interacts with the Windows OS exclusively through JDK %jdk_version%'s finalized **Project Panama Foreign Function and Memory (`java.lang.foreign`)** API—requiring zero JNI C/C++ glue libraries and zero third-party native dependencies such as JNA.

## Modular Native Binding Architecture (`kwswitcher-win32/bindings`)

All Win32 downcall `MethodHandle` bindings, C struct `MemoryLayout` definitions, `VarHandle` field accessors, and callback `FunctionDescriptor` declarations are isolated inside the `kwswitcher-win32` module (`com.michibaum.kwswitcher.win32.bindings.*`):

* **Subsystem Binding Holders (`@JvmField` for HotSpot C2 Inlining):**
    * `PanamaNative` — shared `Linker`, `Arena.global()`, lazy DLL `SymbolLookup`s, and `Arena.allocateWideString` / `MemorySegment.readWideString` UTF-16 helpers.
    * `Win32Constants` & `Win32Structs` — ABI-verified 64-bit Windows struct layouts (`KBDLLHOOKSTRUCT`, `MSG`, `INPUT`, `POINT`, `RECT`, `MONITORINFO`, `ICONINFO`, `BITMAP`, `BITMAPINFOHEADER`) and pre-bound `VarHandle`s with zero DLL initialization side effects.
    * `MutexNative`, `HookNative`, `WindowEnumNative`, `WindowControlNative`, `GdiIconNative`, and `RegistryNative` — lazy `internal object`s exposing `@JvmField val` `MethodHandle`s across `user32.dll`, `kernel32.dll`, `dwmapi.dll`, `gdi32.dll`, and `advapi32.dll`.
* **Zero-Boxing Invocation & Structured Diagnostics:** Every native call uses `MethodHandle.invokeExact(...)` against precomputed `FunctionDescriptor` layouts, while `Win32ErrorFormatter` decodes `GetLastError()` and registry status codes via `FormatMessageW` into structured `NativeResult.Failed` diagnostics.

## Five Critical Win32 & Panama Edge-Case Mitigations

Desktop window managers on Windows face subtle OS traps—from frozen GUI message queues to focus-stealing locks and GDI handle exhaustion. `%cmd_name%` enforces five explicit engineering mitigations across `kwswitcher-win32`:

### 1. Deterministic Panama `Arena` Lifetimes

Choosing the wrong `Arena` scope in `java.lang.foreign` causes either premature use-after-free crashes or native memory leaks:

* **Short-lived native buffers (`Arena.ofConfined()`):** Temporary structs and UTF-16 buffers (`POINT`, `MONITORINFO`, `ICONINFO`, `BITMAPINFOHEADER`, `INPUT[]`, registry strings, and `Win32WindowEnumerator.ReusableScratch`) are allocated inside `Arena.ofConfined().use { arena -> ... }`. Native memory is freed deterministically the instant the lexical block exits—generating zero GC pressure.
* **Persistent hook upcall (`Arena.ofShared()`):** `Win32KeyboardHook` allocates its `LowLevelKeyboardProc` upcall stub inside an `Arena.ofShared()` owned by the hook thread. On shutdown, `Win32KeyboardHook.close()` posts `WM_QUIT` (`PostThreadMessageW`), guarantees `UnhookWindowsHookEx` runs in `finally`, and only then exits the `Arena.ofShared().use` block so Windows can never invoke a freed function pointer.
* **Zero-allocation enumeration callback (`Arena.global()` + `ThreadLocal`):** Rather than allocating a fresh native upcall stub on every `Alt+Tab` press, `Win32WindowEnumerator.UpcallBridge` binds a single `EnumWindowsProc` stub in `Arena.global()` and dispatches to the active call's `WindowVisitor` via a `ThreadLocal` cleared in `finally`.

### 2. Non-Blocking Icon Extraction on Hung Windows (`SMTO_ABORTIFHUNG`)

Calling a synchronous `SendMessageW(hwnd, WM_GETICON, ...)` on an unresponsive ("Not Responding") application blocks the calling thread indefinitely until the target process recovers or is killed.

`Win32IconExtractor` prevents hung windows from ever stalling the switcher:

1. **50 ms hung-abort timeout:** Queries `ICON_SMALL2`, `ICON_SMALL`, and `ICON_BIG` using `SendMessageTimeoutW` with `SMTO_ABORTIFHUNG` and a strict `50 ms` timeout.
2. **Multi-tier fallback chain:** If `SendMessageTimeoutW` times out or returns `NULL`, `Win32IconExtractor` falls back to `GetClassLongPtrW` (`GCLP_HICONSM` / `GCLP_HICON`), then `FileSystemView.getSystemIcon(File(exePath))`, and finally a crisp vector-painted fallback icon.
3. **Async EDT decoupling & LRU caching:** `WindowListCellRenderer` calls `WindowIconPort.getIconOrDefault()`, which returns immediately on the Swing EDT (serving from a `128`-entry LRU cache keyed by lowercase `exePath` or returning the default icon while deduplicated background coroutines resolve and repaint the row).

### 3. Bypassing Windows Foreground Lock (`SetForegroundWindow`)

Windows prevents background processes from stealing focus via `SetForegroundWindow` unless the calling thread currently owns the foreground window or satisfies strict foreground-lock rules. Unlike `RegisterHotKey`, intercepting `Alt+Tab` via `WH_KEYBOARD_LL` does **not** automatically grant foreground activation rights to the background JVM process.

`Win32WindowActivator` and `Win32KeyboardHook` coordinate foreground activation both when **opening `SwitcherOverlay`** (`Win32WindowActivator.activateOverlay`) and when **switching to the chosen target window**:

1. **Overlay `HWND` discovery & caching:** `Win32WindowActivator.resolveOverlayHandle()` locates the Swing `JDialog` `HWND` via pure Panama FFM (`FindWindowExW` paired with `GetWindowThreadProcessId` verified against `ProcessHandle.current().pid()`), caching the handle while `IsWindow(hwnd)` remains non-zero—avoiding `sun.awt.windows` reflection and preserving `java.base,java.desktop` `jpackage` runtimes.
2. **Minimized restore & hung-window guard:** If `IsIconic(targetHwnd)` is non-zero, `ShowWindow(targetHwnd, SW_RESTORE)` unminimizes the window first. Before calling `AttachThreadInput(..., 1)` against `GetForegroundWindow()`, `Win32WindowActivator.activate()` verifies `foregroundHwnd != targetHwnd` and `IsHungAppWindow(foregroundHwnd) == 0` so switching away from a frozen ("Not Responding") application never stalls input synchronization.
3. **Side-effect-free `0xFF` unlock tap + `LLKHF_INJECTED` filtering:** `sendZeroEffectUnlockKeyTap` dispatches a two-event keydown + keyup sequence using unassigned virtual key `0xFF` (`VK_UNLOCK_FOREGROUND`) via `SendInput` inside a confined `Arena`, while `Win32KeyboardHook` ignores synthetic events flagged with `LLKHF_INJECTED` (`0x10`). Unlike injecting `VK_MENU` (`Alt`), `0xFF` unlocks `SetForegroundWindow` without prematurely triggering `ReleaseModifier` during `Alt+Tab` or activating application menu bars.
4. **Hook-level shortcut guard:** `BringWindowToTop` and `SetForegroundWindow` promote the window (with `AttachThreadInput(..., 0)` guaranteed in `finally`) so Swing's `JTextField` receives genuine OS keyboard focus for free-form text and `AltGr` characters. Simultaneously, `Win32KeyboardHook` translates keystrokes into `KeyGesture`s evaluated by `KeyCommandMapper` whenever the switcher is visible or mid-open so fast chords never leak to the background window.

### 4. Administrator & Elevated Windows (UIPI)

Windows **User Interface Privilege Isolation (UIPI)** isolates lower-integrity processes from higher-integrity (Administrator) windows:

* When an elevated window (such as Task Manager or an Administrator terminal) owns focus, Windows does not deliver `WH_KEYBOARD_LL` hook callbacks to unelevated processes, so the native OS `Alt+Tab` handles input automatically.
* Similarly, unelevated `PostMessageW(WM_CLOSE)` and `OpenProcess(PROCESS_TERMINATE)` calls against elevated targets return structured `NativeResult.Failed` values logged via `Win32ErrorFormatter` without throwing.
* Users who want seamless switching out of elevated windows can run `kwswitcher.exe` elevated via a Windows Task Scheduler logon task configured with **Run with highest privileges**.

### 5. Preventing GDI & Kernel `HANDLE` Leaks

Long-running tray utilities must never leak kernel handles or GDI objects across thousands of invocations:

* **Process & Mutex Handles:** Every `HANDLE` returned by `OpenProcess` (`Win32WindowEnumerator.queryProcessImagePath` and `Win32WindowActivator.terminateProcess`) is closed via `CloseHandle` inside a `try / finally` block. `Win32SingleInstanceMutex.close()` releases and closes the named mutex (`ReleaseMutex` + `CloseHandle`) via `RunningAppSession.close()`.
* **GDI Bitmaps & Device Contexts:** `GetIconInfo` allocates *new* `HBITMAP` copies (`hbmMask` and `hbmColor`) that the caller owns. `HIconDibDecoder.convertHIconToImage()` extracts 32-bit top-down ARGB pixels via `CreateCompatibleDC` + `GetDIBits` and unconditionally calls `DeleteDC(hdc)`, `DeleteObject(hbmMask)`, and `DeleteObject(hbmColor)` in `finally`.

<seealso>
    <category ref="developer-guide">
        <a href="architecture.md"/>
        <a href="building-and-releasing.md"/>
    </category>
</seealso>
