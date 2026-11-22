# Kotlin Switcheroo (`kwswitcher`)

A lightweight, keyboard-driven window switcher for Windows built with **Kotlin (JDK 25)**, **Swing + FlatLaf**, and **Project Panama Foreign Function & Memory API (`java.lang.foreign`)**.

See [`kwswitcher_specification.md`](kwswitcher_specification.md) for the full software requirements and architecture specification.

## Build & Test

```powershell
.\mvnw.cmd test
```

## Run Application

```powershell
.\mvnw.cmd exec:exec
```

## Package Native Windows Bundle (`jpackage`)

```powershell
.\mvnw.cmd package -Pjpackage
```

## Package Windows Installer (MSI)

Requires the [WiX Toolset](https://wixtoolset.org/) on `PATH`.

```powershell
.\mvnw.cmd package -Pjpackage-installer
```

## Executable (fat) JAR

Builds a self-contained jar (requires JDK 25+ to run):

```powershell
.\mvnw.cmd package
# run with:
java -jar target\kwswitcher-*-jar-with-dependencies.jar
```

## Release

Releases are automated via GitHub Actions (`.github/workflows/release.yml`):

- Push a tag `vX.Y.Z` (or run the workflow manually with a version) to build and publish a GitHub Release.
- Artifacts: native app-image zip and executable fat jar.

```powershell
git tag v1.0.0
git push origin v1.0.0
```
