# Contributing to Kotlin Switcheroo (`kwswitcher`)

Thank you for contributing to **Kotlin Switcheroo (`kwswitcher`)**! This guide covers local setup, safe breakpoint debugging (without freezing Windows keyboard input), module boundaries, formatting/linting gates, documentation authoring, and the release workflow.

---

## Prerequisites

- **JDK 25+** (e.g., Eclipse Temurin 25) with `JAVA_HOME` configured and `java` / `jpackage` on `PATH`.
- **Maven Wrapper:** Included in the repository (`.\mvnw.cmd` on Windows, `./mvnw` on Linux/macOS).
- **Operating System:**
  - **Windows 10 (21H2+) or Windows 11:** Runs all modules, live Panama FFM Win32 tests, and `jpackage` native bundles.
  - **Linux / macOS:** Compiles the entire reactor and runs all `kwswitcher-core`, `kwswitcher-app`, and pure ABI/key-mapping unit tests (live Win32 tests are gated with `@EnabledOnOs(OS.WINDOWS)`), plus full `--sandbox` UI mode.
- **WiX Toolset v3–v5 *(optional)*:** Required only when building `.msi` installers with `-Pjpackage-installer`.

---

## Safe Local Running & Breakpoint Debugging

> **Why special dev flags exist:** In production mode, `kwswitcher` installs a Windows global low-level keyboard hook (`WH_KEYBOARD_LL`), acquires `Local\KotlinSwitcherooSingleInstanceMutex`, reads `%APPDATA%\kwswitcher\config.json`, and auto-hides `SwitcherOverlay` whenever focus shifts to another window. Pausing at an IntelliJ IDEA breakpoint while `WH_KEYBOARD_LL` is active stalls OS-wide keyboard input until Windows' `LowLevelHooksTimeout` expires, and clicking into the IDE immediately dismisses the overlay.

Use the built-in runtime profiles and flags to iterate safely alongside an installed daily-driver instance:

| Flag | Effect |
| :--- | :--- |
| `--dev` | Uses `Local\KotlinSwitcherooDevMutex`, `%LOCALAPPDATA%\kwswitcher-dev\config.json`, default `Alt + Space` hotkey, `InMemoryStartupPort` (never touches `HKCU\...\Run`), `DEBUG` logging, and an amber `[DEV]` tray icon. |
| `--no-hook` | Replaces `WH_KEYBOARD_LL` with `NoOpHotkeyHookPort` so IDE breakpoints **never** freeze OS keyboard input. |
| `--sandbox` | Cross-platform UI sandbox backed by `SandboxWindowSystem` and `SyntheticWindowIconPort` with 6 interactive sample windows (implies `--no-hook` and `--open-on-start`). |
| `--pin-overlay` | Prevents `SwitcherOverlay` from auto-dismissing on `windowLostFocus` when clicking into IntelliJ IDEA or UI inspectors (`Esc` and `Enter` still dismiss). Also toggleable from the System Tray in `DEV` and `SANDBOX` modes. |
| `--open-on-start` | Opens the switcher overlay immediately on startup. |
| `--config <path>` | Overrides the `config.json` file path. |
| `--log-level <level>` | Sets log verbosity (`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`). |
| `--self-check` | Boots `KwSwitcherBootstrap` in `SANDBOX` mode, verifies Swing/FlatLaf and Panama FFM `Linker` linkage, closes cleanly, and exits `0` (also triggerable via `-Dkwswitcher.selfCheck=true` with `.\mvnw.cmd test-compile exec:exec`). |

### Shared IntelliJ IDEA Run Configurations (`.run/`)

Opening the repository in IntelliJ IDEA automatically loads seven preconfigured run configurations (five application/test configurations with `--enable-native-access=ALL-UNNAMED` and JDK 25 GC flags already set, plus two Maven build helpers):

1. **`Run UI Sandbox`** — Launches `--sandbox --pin-overlay --log-level DEBUG` with interactive mock windows (safe on any OS, breakpoint-safe).
2. **`Run Dev Safe (No Hook)`** — Launches `--dev --no-hook --pin-overlay --open-on-start --log-level DEBUG` against real Windows desktop enumeration without installing `WH_KEYBOARD_LL` (breakpoint-safe).
3. **`Run Dev Live Hook (Alt+Space)`** — Launches `--dev --log-level DEBUG` with a live `Alt + Space` hook and isolated dev mutex/config so your installed `Alt + Tab` switcher keeps running undisturbed.
4. **`Run All Tests`** — Runs `test` across all Maven modules.
5. **`Verify Quality Gate`** — Runs `verify` (JUnit 5 + Kover + Spotless + zero-baseline Detekt).
6. **`clean compile`** — Runs `clean compile` across the full Maven reactor.
7. **`clean package`** — Runs `clean package` across the full Maven reactor to produce module and fat JARs.

---

## Multi-Module Architecture & Boundaries

The repository is organized as a three-module Maven reactor enforcing strict compile-time separation between pure application logic and native Panama FFM bindings:

```text
kwswitcher/
├── kwswitcher-core/    # Pure Kotlin + Swing/FlatLaf (ZERO java.lang.foreign or Win32 imports; runs without --enable-native-access)
│   ├── config/         # @Serializable SwitcherConfig, ConfigRepository (kotlinx-serialization-json), ThemeManager
│   ├── domain/         # WindowHandle, WindowInfo, WindowMatch, WindowFilter, OverlayConstants
│   ├── input/          # KeyGesture, LogicalKey, KeyCommandMapper (single source of truth for shortcuts)
│   ├── port/           # Platform port interfaces (WindowCatalogPort, WindowControlPort, HotkeyHookPort, etc.)
│   ├── coordinator/    # SwitcherIntent, SwitcherEffect, pure SwitcherReducer, single-threaded SwitcherCoordinator actor
│   ├── ui/             # SwitcherOverlay, WindowListCellRenderer, SystemTrayController
│   └── sandbox/        # SandboxWindowSystem, SyntheticWindowIconPort, NoOp/InMemory fake ports
├── kwswitcher-win32/   # JDK 25 Project Panama FFM (java.lang.foreign) Windows backend
│   ├── bindings/       # PanamaNative, Win32Constants, Win32Structs, lazy @JvmField *Native handle holders
│   ├── diagnostics/    # Win32ErrorFormatter (GetLastError + FormatMessageW)
│   └── Win32*.kt       # Logged Win32 implementations of core.port interfaces + Win32HotkeySpec
└── kwswitcher-app/     # Composition root, CLI parser, SLF4J provider, fat JAR & jpackage profiles
    ├── cli/            # CliOptions & RuntimeMode (PRODUCTION, DEV, SANDBOX)
    ├── logging/        # KwSlf4jProvider, RotatingFileSink (2 x 1 MB), DiagnosticsRingBuffer (500 entries)
    └── KwSwitcherBootstrap.kt / Main.kt
```

### Key Architectural Rules

1. **Keep `kwswitcher-core` 100% Native-Free:** Never import `java.lang.foreign.*` or Win32 constants in `kwswitcher-core` (enforced both by Maven module dependencies and Detekt's `ForbiddenImport` rule).
2. **Single Shortcut Table (`KeyCommandMapper`):** Add or modify keyboard shortcuts in `KeyCommandMapper` (`core.input`) so Swing (`SwitcherOverlay`) and `Win32KeyboardHook` stay automatically in sync.
3. **Unidirectional State Confinement:** Mutate switcher state exclusively via `SwitcherIntent` -> `SwitcherReducer` -> `SwitcherCoordinator` (`Channel` actor) -> `SwitcherEffect`. Do not mutate UI state directly across threads.
4. **Structured Logging & Cancellation Safety:** Use `KotlinLogging.logger {}` instead of `println`/`System.err`. Never swallow `CancellationException` in `try / catch (Exception)` blocks, and never log synchronously on non-matching hot keystrokes inside `WH_KEYBOARD_LL`.
5. **Minimal `jpackage` Runtime (`java.base,java.desktop`):** Avoid dependencies that pull in `java.logging`, `java.sql`, or reflection-heavy frameworks so packaged Windows bundles stay compact and under the `< 85 MB` idle memory budget.

---

## Formatting, Static Analysis & Testing

Format all Kotlin source and test files (`ktlint` via Spotless, configured by `.editorconfig`):

```powershell
.\mvnw.cmd spotless:apply
```

Run standalone **Detekt** static analysis across all modules (zero baseline — `config/detekt/baseline.xml` is intentionally absent):

```powershell
.\mvnw.cmd -N antrun:run
```

Run the full CI quality gate locally (tests, Kover coverage reports, `spotless:check`, Detekt, and JAR assembly):

```powershell
.\mvnw.cmd verify
```

---

## Diagnostics & Logs

- **Log files:** Written to `%APPDATA%\kwswitcher\logs\kwswitcher.log` in production or `%LOCALAPPDATA%\kwswitcher-dev\logs\kwswitcher.log` in `--dev`/`--sandbox` modes (size-capped at `2 x 1 MB`).
- **System Tray actions:** Right-click the tray icon and use **Open Log Directory** or **Copy Diagnostics** (copies runtime mode, OS/JDK metadata, active config path, hotkey, and recent ring-buffer log lines to the clipboard).

---

## Documentation (`Writerside/`) & Release Process

- **Writerside topics** live under `Writerside/topics/`. Escape literal Windows `%...%` variables in prose as `%\APPDATA%` (or attach `{ignore-vars="true"}` beneath fenced code blocks). Pull requests touching `Writerside/**` automatically run the Writerside Docker build and inspection gate in CI.
- **Release tags** must be numeric `MAJOR.MINOR.PATCH` **without** a leading `v` (for example, `1.2.0`) so Windows MSI `ProductVersion` validation and `.github/workflows/release.yml` / `docs.yml` succeed. Use `gitflow-maven-plugin` (`.\mvnw.cmd gitflow:release-start` / `gitflow:release-finish`) or push a matching numeric tag on `main`.
