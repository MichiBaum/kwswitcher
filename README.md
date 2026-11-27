# Kotlin Switcheroo (`kwswitcher`)

A lightweight, keyboard-driven window switcher for Windows built with **Kotlin (JDK 25)**, **Swing + FlatLaf**, and **Project Panama Foreign Function & Memory API (`java.lang.foreign`)**.

## Documentation

Full user and developer documentation—including installation options, keyboard shortcuts, `config.json` reference, system architecture, and Win32/Panama FFM internals—is published on **[GitHub Pages](https://michibaum.github.io/kwswitcher/)** and maintained in [`Writerside/`](Writerside/).

For contributor onboarding, safe `WH_KEYBOARD_LL` breakpoint debugging, module boundaries (`kwswitcher-core`, `kwswitcher-win32`, `kwswitcher-app`), and IntelliJ IDEA `.run/` configurations, see **[`CONTRIBUTING.md`](CONTRIBUTING.md)**.

## Build, Format, Test, Coverage & Static Analysis

Run unit, UI, and Win32 engine tests across all modules:

```powershell
.\mvnw.cmd test
```

Auto-format Kotlin sources with **Spotless (`ktlint`)** and run the full verification pipeline—including JUnit 5 tests, **Kover** coverage (`*/target/site/kover/html/index.html`), **Spotless** check, and zero-baseline **Detekt** static analysis (`*/target/detekt/detekt.html`):

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd verify
```

## Run & Debug Locally

Launch `kwswitcher` from source (production profile):

```powershell
.\mvnw.cmd test-compile exec:exec -pl kwswitcher-app
```

For safe local development alongside an installed instance (or breakpoint-safe UI debugging without installing `WH_KEYBOARD_LL`), use the shared IntelliJ IDEA configurations in `.run/` or pass CLI flags:

```powershell
# Cross-platform interactive UI sandbox (no native hook, sample windows, pinned overlay):
java --enable-native-access=ALL-UNNAMED -jar kwswitcher-app\target\kwswitcher-*-jar-with-dependencies.jar --sandbox --pin-overlay

# Isolated dev mode (DevMutex, %LOCALAPPDATA%\kwswitcher-dev\config.json, Alt+Space, [DEV] amber tray):
java --enable-native-access=ALL-UNNAMED -jar kwswitcher-app\target\kwswitcher-*-jar-with-dependencies.jar --dev --no-hook --pin-overlay --open-on-start
```

## Package Native Windows Bundle (`jpackage`)

Builds a trimmed portable Windows application bundle (`java.base,java.desktop` only, with custom multi-resolution `.ico`) in `kwswitcher-app\target\app-image\kwswitcher`:

```powershell
.\mvnw.cmd package -Pjpackage
```

## Package Windows Installer (MSI)

Requires [WiX Toolset v3–v5](https://wixtoolset.org/) on `PATH`. Builds the trimmed `kwswitcher-app\target\app-image\kwswitcher` image first and wraps it into an upgradeable MSI installer under `kwswitcher-app\target\dist\kwswitcher-<version>.msi` (automatically stripping `-SNAPSHOT` suffixes for local MSI compatibility):

```powershell
.\mvnw.cmd package -Pjpackage-installer
```

## Executable (fat) JAR

Builds a self-contained JAR under `kwswitcher-app\target\` (requires JDK 25+ to run):

```powershell
.\mvnw.cmd package
# run with:
java --enable-native-access=ALL-UNNAMED -XX:+UseCompactObjectHeaders -XX:+UseG1GC -Xms16m -Xmx96m -XX:MaxGCPauseMillis=10 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -jar kwswitcher-app\target\kwswitcher-*-jar-with-dependencies.jar
```

## CI & Release

- **Continuous Integration (`.github/workflows/ci.yml`):** Automatically runs `.\mvnw.cmd -B verify -Pjpackage` (tests, Kover, Spotless, zero-baseline Detekt, and trimmed 5-module native runtime image smoke-check) on pushes to `main`/`develop` and all pull requests, plus conditional `Writerside/` documentation inspections on docs PRs.
- **Automated Releases (`.github/workflows/release.yml` & `.github/workflows/docs.yml`):** Push a numeric `MAJOR.MINOR.PATCH` tag without a leading `v` (or use `gitflow-maven-plugin`) to verify the build, deploy versioned Writerside docs to GitHub Pages, and publish a GitHub Release containing:
  - `kwswitcher-X.Y.Z-windows-x64.zip` (trimmed portable native app-image)
  - `kwswitcher-X.Y.Z.msi` (Windows MSI installer with Start Menu/Desktop shortcuts and in-place upgrade support)
  - `kwswitcher-X.Y.Z-jar-with-dependencies.jar` (executable fat JAR)

```powershell
git tag 1.2.0
git push origin 1.2.0
```
