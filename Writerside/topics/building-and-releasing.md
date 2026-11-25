# Building, Testing & Releasing

This guide covers building and testing %product% (`%cmd_name%`) locally with Maven, packaging native Windows distributions, authoring Writerside documentation, and cutting automated GitHub releases.

## Prerequisites

* **OS:** Windows 10 (21H2+) or Windows 11 (`x64` / `ARM64`) for running Win32 integration tests and `jpackage` Windows bundles.
* **JDK:** **JDK %jdk_version%** (such as Eclipse Temurin %jdk_version%) with `JAVA_HOME` set and `java` / `jpackage` on `PATH`.
* **Maven:** Included via the repository's Maven Wrapper (`.\mvnw.cmd`).
* **WiX Toolset *(optional)*:** Required on `PATH` only when building `.msi` installers with `-Pjpackage-installer` ([wixtoolset.org](https://wixtoolset.org/)).

## Build, Test & Run Commands

### Run Unit & Win32 Engine Tests

```powershell
.\mvnw.cmd test
```

`maven-surefire-plugin` automatically passes `--enable-native-access=ALL-UNNAMED` so Panama FFM bindings, state machine transitions, fuzzy search scoring, config serialization, and cell-renderer layout tests run out of the box.

### Launch from Source

```powershell
.\mvnw.cmd exec:exec
```

Runs `com.michibaum.kwswitcher.MainKt` with production JVM flags (`--enable-native-access=ALL-UNNAMED -Xms16m -Xmx64m -XX:+UseCompactObjectHeaders -XX:+UseSerialGC`). Press `Ctrl+C` in the terminal or choose **Quit** from the system tray icon to stop.

## Packaging Distributions

<tabs>
<tab title="Executable Fat JAR">

Builds both the standard JAR and the self-contained `-jar-with-dependencies.jar` under `target\`:

```powershell
.\mvnw.cmd package
```

Run the generated fat JAR on any Windows machine with JDK %jdk_version%+:

```powershell
java --enable-native-access=ALL-UNNAMED `
  -Xms16m -Xmx64m `
  -XX:+UseCompactObjectHeaders `
  -XX:+UseSerialGC `
  -jar target\kwswitcher-1.0.0-SNAPSHOT-jar-with-dependencies.jar
```

</tab>
<tab title="Portable Native Bundle (jpackage)">

Stages runtime dependencies and invokes `jpackage --type app-image` to produce a standalone Windows directory at `target\dist\kwswitcher\`:

```powershell
.\mvnw.cmd package -Pjpackage
```

Launch `target\dist\kwswitcher\kwswitcher.exe` directly—no external Java installation required.

</tab>
<tab title="Windows MSI Installer">

Requires the [WiX Toolset](https://wixtoolset.org/) on `PATH`. Invokes `jpackage --type msi` with directory chooser, Start Menu entry, and desktop shortcut options:

```powershell
.\mvnw.cmd package -Pjpackage-installer
```

The resulting `.msi` installer is written to `target\dist\`.

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

Releases and documentation deployments are automated by two decoupled GitHub Actions workflows triggered whenever a `v*` tag is pushed (or via manual **Run workflow** dispatch in the GitHub Actions UI):

1. **Application Release (`.github/workflows/release.yml` on `windows-latest`):**
    * Runs `.\mvnw.cmd -B test`, stamps the Maven version, builds the `jpackage` native app-image `.zip` and executable fat JAR (plus optional MSI), and publishes a GitHub Release via `softprops/action-gh-release@v2`.
2. **Documentation Publishing (`.github/workflows/docs.yml` on `ubuntu-latest`):**
    * Resolves the release version, stamps `v<version>` into `Writerside/writerside.cfg` and `Writerside/v.list`, builds `webHelpKWS2-all.zip` and `report.json` with `JetBrains/writerside-github-action@v4`, enforces zero documentation errors with `JetBrains/writerside-checker-action@v1`, and deploys the verified site to GitHub Pages (`https://michibaum.github.io/kwswitcher/`).

> **One-Time Repository Setup for GitHub Pages:** In GitHub, open **Settings -> Pages -> Build and deployment** and set **Source** to **GitHub Actions** so `actions/deploy-pages@v4` is authorized to publish the `github-pages` environment.
>
{style="important"}

### Cutting a New Release

```powershell
git tag v1.0.0
git push origin v1.0.0
```

## Engineering Milestone History

* **Phase 1 — Core Win32 Engine & Maven Foundation:** JDK %jdk_version% Maven setup, Panama FFM `Win32Bindings`, `WindowEnumerator`, `WindowActivator`, `KeyboardHook` (`WH_KEYBOARD_LL`), `SwitcherStateMachine` (`QUICK_SWITCH` & `INTERACTIVE_SEARCH`), and FlatLaf `SwitcherOverlay`.
* **Phase 2 — Multi-Token Fuzzy Search, Icons, Badges & Window Actions:** Async LRU `IconExtractor`, `WindowInfo` monitor/minimized metadata, multi-token ranked `WindowFilter` with direct-painted highlight spans, and in-switcher close/minimize/two-step kill actions.
* **Phase 3 — Configuration, Themes, Multi-Monitor Placement, Tray & Packaging:** `%\APPDATA%\kwswitcher\config.json` persistence, `DARK`/`LIGHT`/`SYSTEM` theme management, active-monitor centering, `SingleInstanceMutex`, `SystemTrayController`, `StartupRegistry`, and `jpackage` profiles.

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
