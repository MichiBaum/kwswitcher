# Usage &amp; Keyboard Shortcuts

%product% (`%cmd_name%`) combines classic muscle-memory `Alt+Tab` window flipping with an interactive, keyboard-driven fuzzy launcher and window manager.

## Two Switching Modes

Every switcher session operates in one of two modes managed by `SwitcherStateMachine`:

### 1. Quick-Switch Mode (`QUICK_SWITCH`)

Pressing `Alt+Tab` (or your configured hotkey) opens the switcher immediately in **`QUICK_SWITCH`** mode:

* **`Alt+Tab`** pre-selects the **second** window in Z-order (your previously active window) so a single quick tap flips between your two most recent windows.
* **`Alt+Shift+Tab`** pre-selects the **last** window in Z-order and cycles backward.
* While keeping `Alt` held down, repeated `Tab` or `Shift+Tab` presses cycle forward and backward through the list with wrap-around.
* **Releasing `Alt`** immediately activates the highlighted window (restoring it first if minimized) and closes the overlay.

### 2. Interactive Search Mode (`INTERACTIVE_SEARCH`)

At any moment while the overlay is open, performing any interactive action transitions and **locks** the session into **`INTERACTIVE_SEARCH`** mode:

* Typing any search character into the filter box
* Pressing `Up`, `Down`, `Ctrl+N`, or `Ctrl+P`
* Triggering an in-switcher window action (`Ctrl+W`, `Delete`, `Ctrl+M`, or `Ctrl+Shift+W`)
* Opening the overlay via **Show Switcher** (or clicking the icon) in the System Tray

Once locked in **`INTERACTIVE_SEARCH`** mode, **releasing `Alt` does not close the overlay**. You can release all modifiers, type multi-word search queries comfortably with both hands, close or minimize clutter windows in-place, and finally press `Enter` (or click a row) to switch—or press `Esc` to cancel.

## Keyboard &amp; Mouse Shortcuts

### Global Hotkeys

| Shortcut | Action |
| :--- | :--- |
| `Alt+Tab` | Open the switcher in `QUICK_SWITCH` mode (or advance selection forward if already open). Intercepts the key so the native Windows switcher does not appear. |
| `Alt+Shift+Tab` | Open the switcher with the last window selected (or cycle selection backward). |

> You can customize the primary modifier (`ALT`, `CTRL`, `WIN`), trigger key (`TAB`, `SPACE`, `` ` ``, `A`–`Z`), and reverse modifier (`SHIFT`, `CTRL`, `ALT`) in [Configuration Reference](configuration.md).
>
{style="tip"}

### Navigation &amp; Selection (Overlay Open)

| Shortcut / Input | Mode Effect | Action |
| :--- | :--- | :--- |
| *Type any text* | Locks `INTERACTIVE_SEARCH` | Filters and ranks open windows in real time; resets selection to the top match (`index 0`). |
| `Down` / `Up` | Locks `INTERACTIVE_SEARCH` | Move selection down or up with wrap-around. |
| `Ctrl+N` / `Ctrl+P` | Locks `INTERACTIVE_SEARCH` | Move selection next (down) or previous (up) with wrap-around. |
| `Tab` / `Shift+Tab` | Preserves current mode | Cycle selection forward or backward with wrap-around. |
| `Enter` | Closes overlay (`HIDDEN`) | Restore (if minimized) and focus the selected window. |
| *Left-click a row* | Closes overlay (`HIDDEN`) | Activate the clicked window immediately. |
| `Esc` | Closes overlay (`HIDDEN`) | Dismiss the switcher without changing the active window. |
| *Click outside overlay* | Closes overlay (`HIDDEN`) | Losing window focus while in `INTERACTIVE_SEARCH` dismisses the switcher cleanly. |

### In-Switcher Window Management

You can tidy up open windows directly from the switcher list without dismissing the overlay. Every window management shortcut transitions the session into `INTERACTIVE_SEARCH` so the list stays open for further actions:

| Shortcut | Action | Behavior |
| :--- | :--- | :--- |
| `Ctrl+W` | Close window gracefully | Posts a non-blocking `WM_CLOSE` message to the selected window and removes it from the list, keeping neighboring selection clamped in place. |
| `Delete` | Close window gracefully *(empty query)* | Closes the selected window when the search input box is empty. When text is present in the search box, plain `Delete` edits the query text normally. |
| `Ctrl+Delete` | Close window gracefully *(any query)* | Closes the selected window even when the search input box contains filter text. |
| `Ctrl+M` | Minimize window in-place | Minimizes the selected window (`SW_MINIMIZE`) and immediately displays the `[Minimized]` badge on its row without closing the overlay. |
| `Ctrl+Shift+W` *(×2)* | Force-terminate process | **Two-step safety confirmation:** the first press arms termination on the selected window and displays a `Confirm Kill (Ctrl+Shift+W)` badge. Pressing `Ctrl+Shift+W` a second consecutive time calls `TerminateProcess` on the owning PID and removes all windows belonging to that process. |

> Moving the selection, editing the search query, running another window action, or closing the overlay automatically disarms a pending `Confirm Kill (Ctrl+Shift+W)` state.
>
{style="note"}

## Row Layout &amp; Visual Badges

Each row in the switcher overlay displays contextual cues from left to right:

1. **Application Icon:** Extracted natively from the window or executable and cached in memory by executable path so scrolling and re-opening remain instantaneous.
2. **Highlighted Window Title:** Matched search characters are rendered in bold accent color; long titles truncate cleanly with `...` without widening the dialog.
3. **Kill Confirmation Badge (`Confirm Kill (Ctrl+Shift+W)`):** Shown only when `Ctrl+Shift+W` has been pressed once on the current row.
4. **Minimized Badge (`[Minimized]`):** Shown when the window is currently minimized (`IsIconic`). Can be toggled via `showMinimizedBadge` in `config.json`.
5. **Monitor Badge (`[Mon 1]`, `[Mon 2]`, ...):** Shown automatically when open windows span **two or more monitors** (`Mon 1` always denotes the primary Windows display). Can be toggled via `showMonitorBadge` in `config.json`.
6. **Process Name Badge:** Displays the owning executable's base name (such as `idea64`, `chrome`, or `WindowsTerminal`) with bold accent highlights when your search matches the process name.

In multi-monitor setups, the overlay automatically centers itself inside the work area of the active monitor (determined from the mouse cursor position or current foreground window) and scales crisply across mixed-DPI displays.

## System Tray Controls

Right-clicking the **%product% (%cmd_name%)** icon in the Windows system tray opens the management menu:

* **Show Switcher:** Opens the overlay directly in `INTERACTIVE_SEARCH` mode (double-clicking the tray icon triggers the same action).
* **Reload Config:** Re-reads `%\APPDATA%\kwswitcher\config.json` from disk and applies updated hotkeys, themes, badge toggles, and ignore lists immediately without restarting.
* **Theme (`Dark` / `Light` / `System`):** Switches the FlatLaf UI theme live and saves the selection back to `%\APPDATA%\kwswitcher\config.json`. `System` automatically follows Windows' *Choose your mode* app theme setting.
* **Run at Windows Startup:** Adds or removes the per-user Windows logon registry entry (`HKCU\Software\Microsoft\Windows\CurrentVersion\Run`).
* **Quit:** Unhooks the low-level keyboard listener, removes the tray icon, releases the single-instance mutex, and exits cleanly.

<seealso>
    <category ref="user-guide">
        <a href="search-and-filtering.md">Search &amp; Window Filtering</a>
        <a href="configuration.md">Configuration Reference</a>
    </category>
</seealso>
