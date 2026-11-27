# Configuration Reference

%product% (`%cmd_name%`) stores all user settings in a single human-readable JSON file. You can customize the global trigger hotkey, overlay theme, row badges, and window ignore rules without restarting the application.

## Configuration File Location

By default, `ConfigRepository` loads and saves settings at:

```text
%APPDATA%\kwswitcher\config.json
```
{ignore-vars="true"}

On a standard Windows installation, this resolves to `C:\Users\<YourUsername>\AppData\Roaming\kwswitcher\config.json` (if the `APPDATA` environment variable is not set, `%cmd_name%` falls back to `~/.kwswitcher/config.json`).

* **Developer & custom paths:** Launching with `--dev` isolates settings at `%\LOCALAPPDATA%\kwswitcher-dev\config.json` (defaulting to `Alt + Space` on first creation), and `--config <path>` overrides the configuration file location explicitly.
* **Automatic creation:** If `config.json` does not exist when `%cmd_name%` starts, it is created automatically with `kotlinx-serialization-json` pretty-printed defaults.
* **Fault-tolerant parsing with diagnostics:** Omitted fields, unknown future properties, case-insensitive enum names, or malformed regular expressions fall back cleanly to built-in defaults while recording exact parse or regex warnings in `logs\kwswitcher.log` (inspectable via **System Tray -> Open Log Directory** or **Copy Diagnostics**).
* **Live reload:** After editing `config.json` in any text editor, right-click the system tray icon and click **Reload Config** to apply your changes immediately.

To open the file quickly from PowerShell:

```powershell
notepad "$env:APPDATA\kwswitcher\config.json"
```

## Default `config.json`

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

## Settings Reference

### `hotkey` Object

Controls the global low-level keyboard shortcut intercepted by `KeyboardHook` (`WH_KEYBOARD_LL`). All string values are case-insensitive.

| Property | Type | Default | Accepted Values | Description |
| :--- | :--- | :--- | :--- | :--- |
| `hotkey.modifier` | `string` | `"ALT"` | `"ALT"`, `"CTRL"` (or `"CONTROL"`), `"WIN"` (or `"WINDOWS"`) | Primary modifier key that must be held to invoke `%cmd_name%` and released to complete a `QUICK_SWITCH`. Matches both left and right physical keys. Unrecognized values fall back to `"ALT"`. |
| `hotkey.triggerKey` | `string` | `"TAB"` | `"TAB"`, `"SPACE"`, `"BACKTICK"` (or `"GRAVE"`, `` "`" ``), `"A"`–`"Z"` | Key pressed while `modifier` is held to open the overlay and cycle windows. Unrecognized values fall back to `"TAB"`. |
| `hotkey.reverseModifier` | `string` | `"SHIFT"` | `"SHIFT"`, `"CTRL"` (or `"CONTROL"`), `"ALT"` | Secondary modifier held alongside `modifier + triggerKey` to open at the end of Z-order or cycle selection backward. Unrecognized values fall back to `"SHIFT"`. |

> Avoid setting `hotkey.modifier` and `hotkey.reverseModifier` to the same key (for example, both `"ALT"`), as the primary modifier is checked first when processing hook events.
>
{style="warning"}

### Appearance & Badges

| Property | Type | Default | Accepted Values | Description |
| :--- | :--- | :--- | :--- | :--- |
| `theme` | `string` | `"SYSTEM"` | `"SYSTEM"`, `"DARK"`, `"LIGHT"` | Controls the FlatLaf color palette for the overlay dialog. `"SYSTEM"` reads Windows' `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize\AppsUseLightTheme` setting automatically. Also changeable directly from **System Tray -> Theme**. |
| `showMonitorBadge` | `boolean` | `true` | `true`, `false` | Displays `[Mon 1]`, `[Mon 2]`, ... badges on rows whenever open windows are detected across two or more monitors. |
| `showMinimizedBadge` | `boolean` | `true` | `true`, `false` | Displays a `[Minimized]` badge on windows that are currently minimized to the taskbar. |

### Window Ignore Rules

Windows matching *either* `ignoredProcesses` or `ignoredTitlePatterns` are hidden during `EnumWindows` enumeration.

| Property | Type | Default | Matching Behavior |
| :--- | :--- | :--- | :--- |
| `ignoredProcesses` | `string[]` | `["TextInputHost.exe", "SearchHost.exe"]` | Case-insensitive exact match against the owning executable's base name. The `.exe` suffix is optional (`"SearchHost.exe"`, `"SearchHost"`, and `"searchhost"` are equivalent). Set to `[]` to disable process-based exclusions. |
| `ignoredTitlePatterns` | `string[]` | `["^NVIDIA GeForce Overlay$", "^Program Manager$"]` | Regular expressions compiled with `RegexOption.IGNORE_CASE` and evaluated with `containsMatchIn(title)`. Anchor patterns with `^` and `$` when you only want exact title matches, or omit anchors for substring/pattern matching. Invalid regex strings are skipped safely. |

## Example Configurations

### Keep Native `Alt+Tab` and Bind `%cmd_name%` to `Alt + Backtick`

If you want Windows Explorer's built-in `Alt+Tab` to remain untouched while invoking `%cmd_name%` with ``Alt+` `` (and ``Alt+Shift+` `` for reverse):

```json
{
  "hotkey": {
    "modifier": "ALT",
    "triggerKey": "BACKTICK",
    "reverseModifier": "SHIFT"
  },
  "theme": "DARK",
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

### Launcher-Style `Ctrl + Space` Binding with Extra Ignore Rules

```json
{
  "hotkey": {
    "modifier": "CTRL",
    "triggerKey": "SPACE",
    "reverseModifier": "SHIFT"
  },
  "theme": "SYSTEM",
  "showMonitorBadge": true,
  "showMinimizedBadge": false,
  "ignoredProcesses": [
    "TextInputHost.exe",
    "SearchHost.exe",
    "ShareX.exe"
  ],
  "ignoredTitlePatterns": [
    "^NVIDIA GeForce Overlay$",
    "^Program Manager$",
    "^Picture-in-Picture$"
  ]
}
```

<seealso>
    <category ref="user-guide">
        <a href="installation.md"/>
        <a href="usage-and-shortcuts.md"/>
        <a href="search-and-filtering.md"/>
    </category>
</seealso>
