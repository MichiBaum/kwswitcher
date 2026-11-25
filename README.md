# Kotlin Switcheroo (`kwswitcher`)

A lightweight, keyboard-driven window switcher for Windows built with **Kotlin (JDK 25)**, **Swing + FlatLaf**, and **Project Panama Foreign Function & Memory API (`java.lang.foreign`)**.

## Documentation

Full user and developer documentation—including installation options, keyboard shortcuts, `config.json` reference, system architecture, and Win32/Panama FFM internals—is published on **[GitHub Pages](https://michibaum.github.io/kwswitcher/)** and maintained in [`Writerside/`](Writerside/).

## Build, Test, Coverage & Static Analysis

Run unit and Win32 tests:

```powershell
.\mvnw.cmd test
```

Run the full verification pipeline—including JUnit 5 tests, **Kover** coverage (`target\site\kover\html\index.html` and `target\site\kover\report.xml`), and **Detekt 2.0** static analysis (`target\detekt\detekt.html` and `target\detekt\detekt.sarif`):

```powershell
.\mvnw.cmd verify
```

## Run Application

Launches `kwswitcher` locally with centralized JDK 25 Compact Object Headers and tuned G1GC settings:

```powershell
.\mvnw.cmd exec:exec
```

## Package Native Windows Bundle (`jpackage`)

Builds a trimmed portable Windows application bundle (`java.base,java.desktop` only, with custom multi-resolution `.ico`) in `target\app-image\kwswitcher`:

```powershell
.\mvnw.cmd package -Pjpackage
```

## Package Windows Installer (MSI)

Requires [WiX Toolset v3–v5](https://wixtoolset.org/) on `PATH`. Builds the trimmed `target\app-image\kwswitcher` image first and wraps it into an upgradeable MSI installer under `target\dist\kwswitcher-<version>.msi` (automatically stripping `-SNAPSHOT` suffixes for local MSI compatibility):

```powershell
.\mvnw.cmd package -Pjpackage-installer
```

## Executable (fat) JAR

Builds a self-contained JAR (requires JDK 25+ to run):

```powershell
.\mvnw.cmd package
# run with:
java --enable-native-access=ALL-UNNAMED -XX:+UseCompactObjectHeaders -XX:+UseG1GC -Xms16m -Xmx96m -XX:MaxGCPauseMillis=10 -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -jar target\kwswitcher-*-jar-with-dependencies.jar
```

## CI & Release

- **Continuous Integration (`.github/workflows/ci.yml`):** Automatically runs `.\mvnw.cmd -B verify -Pjpackage` on pushes to `main` and all pull requests on `windows-latest` (JDK 25), smoke-checks the trimmed 5-module runtime image, and uploads Kover coverage and Detekt static analysis reports.
- **Automated Releases (`.github/workflows/release.yml`):** Push a `vX.Y.Z` tag (or trigger `workflow_dispatch` with a version) to verify the build and publish a GitHub Release containing:
  - `kwswitcher-X.Y.Z-windows-x64.zip` (trimmed portable native app-image)
  - `kwswitcher-X.Y.Z.msi` (Windows MSI installer with Start Menu/Desktop shortcuts and in-place upgrade support)
  - `kwswitcher-X.Y.Z-jar-with-dependencies.jar` (executable fat JAR)

```powershell
git tag v1.0.0
git push origin v1.0.0
```
