# Building, Testing & Releasing

This guide covers building and testing %product% (`%cmd_name%`) locally with Maven, packaging native Windows distributions, authoring Writerside documentation, and cutting automated GitHub releases.

## Prerequisites

* **OS:** Windows 10 (21H2+) or Windows 11 (`x64` / `ARM64`) for running Win32 integration tests and `jpackage` Windows bundles.
* **JDK:** **JDK %jdk_version%** (such as Eclipse Temurin %jdk_version%) with `JAVA_HOME` set and `java` / `jpackage` on `PATH`.
* **Maven:** Included via the repository's Maven Wrapper (`.\mvnw.cmd`).
* **WiX Toolset *(optional)*:** Required on `PATH` only when building `.msi` installers with `-Pjpackage-installer` ([wixtoolset.org](https://wixtoolset.org/)).

## Build, Format, Test & Run Commands

### Run Unit, UI & Win32 Engine Tests

```powershell
.\mvnw.cmd test
```

`kwswitcher-core` executes completely without `--enable-native-access`, while `kwswitcher-win32` and `kwswitcher-app` configure `maven-surefire-plugin` with `--enable-native-access=ALL-UNNAMED` (gating live Win32 tests via `@EnabledOnOs(OS.WINDOWS)` so non-Windows builds remain green).

### Format Kotlin Code & Run the Full Quality Gate

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
```

`verify` runs JUnit 5 tests, Kover coverage generation, Spotless (`ktlint`) formatting verification, and zero-baseline Detekt static analysis across `kwswitcher-core`, `kwswitcher-win32`, and `kwswitcher-app`.

### Safe Local Debugging & Dogfooding Modes

Pausing at an IntelliJ IDEA breakpoint while `WH_KEYBOARD_LL` is active stalls OS-wide keyboard input until Windows' `LowLevelHooksTimeout`. Open the repository in IntelliJ IDEA to use the pre-shared `.run/` configurations (`Run UI Sandbox`, `Run Dev Safe (No Hook)`, `Run Dev Live Hook (Alt+Space)`), or launch from the CLI:

```powershell
# Launch production mode from the root Maven reactor (compiles all modules from live source):
.\mvnw.cmd test-compile exec:exec

# Run deterministic sandbox & Panama FFM linkage verification and exit 0:
.\mvnw.cmd test-compile exec:exec "-Dkwswitcher.selfCheck=true"

# Launch cross-platform interactive UI sandbox (no native hook, pinned overlay):
java --enable-native-access=ALL-UNNAMED -jar kwswitcher-app\target\kwswitcher-%version%-jar-with-dependencies.jar --sandbox --pin-overlay

# Launch isolated dev mode alongside an installed instance (DevMutex, Alt+Space, [DEV] amber tray):
java --enable-native-access=ALL-UNNAMED -jar kwswitcher-app\target\kwswitcher-%version%-jar-with-dependencies.jar --dev --no-hook --pin-overlay --open-on-start
```

## Packaging Distributions

<tabs>
<tab title="Executable Fat JAR">

Builds both the standard JAR and the self-contained `-jar-with-dependencies.jar` under `kwswitcher-app\target\`:

```powershell
.\mvnw.cmd package
```

Run the generated fat JAR on any Windows machine with JDK %jdk_version%+:

```powershell
java --enable-native-access=ALL-UNNAMED `
  -XX:+UseCompactObjectHeaders `
  -XX:+UseG1GC `
  -Xms16m -Xmx96m `
  -XX:MaxGCPauseMillis=10 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 `
  -jar kwswitcher-app\target\kwswitcher-%version%-jar-with-dependencies.jar
```

</tab>
<tab title="Portable Native Bundle (jpackage)">

Stages runtime dependencies and invokes `jpackage --type app-image` (`--add-modules java.base,java.desktop`) to produce a standalone Windows directory at `kwswitcher-app\target\app-image\kwswitcher\`:

```powershell
.\mvnw.cmd package -Pjpackage
```

Launch `kwswitcher-app\target\app-image\kwswitcher\kwswitcher.exe` directly—no external Java installation required. Pass `--self-check` (`kwswitcher.exe --self-check`) to smoke-test the trimmed `jlink` runtime non-interactively (as executed in CI).

</tab>
<tab title="Windows MSI Installer">

Requires the [WiX Toolset](https://wixtoolset.org/) on `PATH`. Invokes `jpackage --type app-image` followed by `jpackage --type msi` with directory chooser, Start Menu entry, and desktop shortcut options:

```powershell
.\mvnw.cmd package -Pjpackage-installer
```

The resulting `.msi` installer is written to `kwswitcher-app\target\dist\`.

</tab>
</tabs>

## Authoring & Validating Documentation (`Writerside/`)

All user and developer documentation lives in the `Writerside/` help module (`kws` instance defined in `Writerside/kws.tree`):

* **IDE Preview:** Open the repository in IntelliJ IDEA with the **JetBrains Writerside** plugin installed to get live preview, schema completion, and link inspections. Because `.idea/` is gitignored, `Writerside/` is completely self-contained.
* **Escaping Windows `%...%` Variables:** Writerside interpolates `%\var%` tokens from `Writerside/v.list` by default. When writing literal Windows environment variables such as `%\APPDATA%`, place a backslash immediately after the opening percent sign in prose/inline code, or attach `{ignore-vars="true"}` immediately below any fenced code block containing literal `%...%` paths.
* **Local Docker Build & Check:** If Docker is running locally, you can reproduce the exact CI build and quality gate from PowerShell:

```powershell
docker run --rm -v "${PWD}:/opt/sources" `
  registry.jetbrains.team/p/writerside/builder/writerside-builder:latest `
  /bin/bash -c "export DISPLAY=:99 && Xvfb :99 & /opt/builder/bin/idea.sh helpbuilderinspect -source-dir /opt/sources -product Writerside/kws --runner other -output-dir /opt/sources/artifacts/"
```

## Release & GitHub Pages CI/CD

Releases and documentation deployments are automated by two decoupled GitHub Actions workflows triggered whenever a numeric `MAJOR.MINOR.PATCH` tag (without a leading `v`, for Windows MSI `ProductVersion` compatibility) is pushed—or via `gitflow-maven-plugin` / manual **Run workflow** dispatch:

1. **Application Release (`.github/workflows/release.yml` on `windows-latest`):**
    * Validates the numeric `MAJOR.MINOR.PATCH` version, stamps all reactor POMs via `versions:set`, runs `.\mvnw.cmd -B verify`, builds the `jpackage` native app-image `.zip`, `.msi` installer, and executable fat JAR under `kwswitcher-app\target\`, and publishes a GitHub Release via `softprops/action-gh-release@v2`.
2. **Documentation Publishing (`.github/workflows/docs.yml` on `ubuntu-latest`):**
    * Validates the numeric release version, stamps `<version>` into `Writerside/writerside.cfg` and `Writerside/v.list`, builds `webHelpKWS2-all.zip` and `report.json` with `JetBrains/writerside-github-action@v4`, enforces zero documentation errors with `JetBrains/writerside-checker-action@v1`, and deploys the verified site to GitHub Pages (`https://michibaum.github.io/kwswitcher/`). Pull requests touching `Writerside/**` run the same build and checker steps automatically in `ci.yml`.

> **One-Time Repository Setup for GitHub Pages:** In GitHub, open **Settings -> Pages -> Build and deployment** and set **Source** to **GitHub Actions** so `actions/deploy-pages@v4` is authorized to publish the `github-pages` environment.
>
{style="important"}

### Cutting a New Release

```powershell
git tag 1.2.0
git push origin 1.2.0
```

## Engineering Milestone History

* **Phase 1 — Core Win32 Engine & Maven Foundation:** JDK %jdk_version% Maven setup, Panama FFM bindings, window enumeration, focus activation, `WH_KEYBOARD_LL` message loop, `QUICK_SWITCH` / `INTERACTIVE_SEARCH` state transitions, and FlatLaf `SwitcherOverlay`.
* **Phase 2 — Multi-Token Fuzzy Search, Icons, Badges & Window Actions:** Async LRU icon caching, `WindowInfo` monitor/minimized metadata, multi-token ranked `WindowFilter` with direct-painted highlight spans, and in-switcher close/minimize/two-step kill actions.
* **Phase 3 — Configuration, Themes, Multi-Monitor Placement, Tray & Packaging:** `%\APPDATA%\kwswitcher\config.json` persistence, `DARK`/`LIGHT`/`SYSTEM` theme management, active-monitor centering, single-instance mutex, system tray, startup registry, and `jpackage` profiles.
* **Phase 4 — Developer-Experience Overhaul & Multi-Module Architecture:** Compiler-isolated `kwswitcher-core` / `kwswitcher-win32` / `kwswitcher-app` modules, unidirectional `SwitcherCoordinator` + `KeyCommandMapper`, `kotlinx-serialization-json` config diagnostics, `--dev` / `--no-hook` / `--sandbox` / `--pin-overlay` modes, `KwSlf4jProvider` rotating logs + `Win32ErrorFormatter`, and Spotless + zero-baseline Detekt quality gates.

<seealso>
    <category ref="developer-guide">
        <a href="architecture.md"/>
        <a href="win32-panama-ffm.md"/>
    </category>
    <category ref="external">
        <a href="https://github.com/MichiBaum/kwswitcher/actions">GitHub Actions Workflows</a>
        <a href="https://github.com/MichiBaum/kwswitcher/releases">GitHub Releases</a>
    </category>
</seealso>
