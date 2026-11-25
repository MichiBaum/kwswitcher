# Installation

%product% (`%cmd_name%`) runs as a lightweight background utility in the Windows system tray and intercepts your configured global switcher hotkey with zero cold-start delay.

## System Requirements

* **Operating System:** Windows 10 (version 21H2 or newer) or Windows 11 (`x64` / `ARM64`).
* **Java Runtime:**
    * **Native `.zip` bundle and `.msi` installer:** None required — a minimal custom JVM runtime is bundled via `jpackage`.
    * **Executable Fat JAR:** **JDK %jdk_version%+** is required because `%cmd_name%` calls Win32 APIs directly through the finalized Project Panama Foreign Function & Memory (`java.lang.foreign`) API.

## Installation Options

Choose the distribution from [GitHub Releases](https://github.com/MichiBaum/kwswitcher/releases/latest) that best fits your setup.

<tabs>
<tab title="Portable Native Bundle (.zip)">

The portable `jpackage` archive includes `kwswitcher.exe` and a trimmed Java runtime with preconfigured memory and GC flags—no administrator rights or system-wide Java installation needed.

1. Download `kwswitcher-<version>-windows-x64.zip` from [GitHub Releases](https://github.com/MichiBaum/kwswitcher/releases/latest).
2. Extract the archive to a permanent folder (for example, `C:\Tools\kwswitcher` or `%\LOCALAPPDATA%\Programs\kwswitcher`).
3. Launch `kwswitcher\kwswitcher.exe`.
4. Verify that the blue switch-arrow **%product%** icon appears in the Windows system tray, then press `Alt+Tab` to open the switcher.

</tab>
<tab title="Windows Installer (.msi)">

If you prefer Start Menu integration, a desktop shortcut, and standard Windows Add/Remove Programs management, use the `.msi` installer (available in releases built with WiX or generated locally via Maven).

1. Run `kwswitcher-<version>.msi` (or build it locally with `.\mvnw.cmd package -Pjpackage-installer`).
2. Choose your installation directory in the setup wizard and complete installation.
3. Launch **kwswitcher** from the Start Menu or desktop shortcut.

</tab>
<tab title="Executable Fat JAR">

If you already have **JDK %jdk_version%+** installed on `PATH`, you can run the self-contained `-jar-with-dependencies.jar` directly:

```powershell
java --enable-native-access=ALL-UNNAMED `
  -Xms16m -Xmx64m `
  -XX:+UseCompactObjectHeaders `
  -XX:+UseSerialGC `
  -jar kwswitcher-%version%-jar-with-dependencies.jar
```

> `--enable-native-access=ALL-UNNAMED` is mandatory on JDK %jdk_version%+ so the JVM permits unrestricted Project Panama `Linker` downcalls and `WH_KEYBOARD_LL` upcall stubs.
>
{style="note"}

</tab>
</tabs>

## Run at Windows Startup

You can configure `%cmd_name%` to start automatically whenever you sign in to Windows directly from the system tray:

1. Right-click the **%product% (%cmd_name%)** icon in the Windows notification area (system tray).
2. Click **Run at Windows Startup** so the checkmark is enabled.

Under the hood, `%cmd_name%` writes a `REG_SZ` entry named `kwswitcher` under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` pointing to the current executable path. Unchecking **Run at Windows Startup** removes the registry value immediately.

> If you use the portable `.zip` bundle, move the extracted folder to its final location *before* enabling **Run at Windows Startup**. If you later move the folder, toggle **Run at Windows Startup** off and back on to refresh the registered path.
>
{style="tip"}

### Elevated Windows & Administrator Mode (UIPI)

By design, Windows **User Interface Privilege Isolation (UIPI)** blocks standard-privilege applications from intercepting low-level keyboard hooks (`WH_KEYBOARD_LL`) while an elevated Administrator window (such as Task Manager or an elevated PowerShell prompt) holds focus, and prevents sending `WM_CLOSE` or `TerminateProcess` to elevated processes.

If you regularly switch *out of* elevated Administrator windows using `%cmd_name%`:

* Launch `kwswitcher.exe` as Administrator, or
* Register a **Windows Task Scheduler** logon task configured with **Run with highest privileges** (instead of the standard `HKCU\...\Run` registry toggle) so `%cmd_name%` starts elevated at sign-in without triggering a UAC prompt each time.

## Single-Instance Protection

`%cmd_name%` acquires a named Win32 mutex (`Local\KotlinSwitcherooSingleInstanceMutex`) on startup. Attempting to start a second instance exits immediately without installing a duplicate keyboard hook or second tray icon.

<seealso>
    <category ref="user-guide">
        <a href="usage-and-shortcuts.md"/>
        <a href="configuration.md"/>
    </category>
</seealso>
