# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MangaPagesSplitter is a Java Swing desktop application that batch-processes manga/comic archives by splitting double-page spread images into single pages, with optional cropping, rotation, and re-archiving. The library JAR targets Java 8+; the bundled Windows app-image ships with its own Java 17 runtime.

## Build Commands

```bash
# Compile and package: produces JAR + self-contained Windows app-image
# (target/jpackage/MangaPagesSplitter/). Build requires JDK 17+ for jpackage.
mvn package

# Package with code signing for the jpackage launcher EXE
mvn package -Psign-exe -Dsigning.keystore=path\to\keystore.pfx -Dsigning.storepass=password

# Run the application from the fat JAR
java -jar target/MangaPagesSplitter-<version>-jar-with-dependencies.jar
```

There are no tests configured in this project.

## Releasing

Releases are automated by GitHub Actions (`.github/workflows/release.yml`) on
`v*` tag push. **Do not commit built artifacts to the repo** — the
`releases/` folder is gitignored. See `RELEASING.md` for the full process.

Short version:

1. Update `CHANGELOG.md` (move `[Unreleased]` items under a new `[X.Y.Z]` section).
2. Bump `<version>` in `pom.xml`.
3. `git commit -m "Release vX.Y.Z"`
4. `git tag -a vX.Y.Z -m "Release vX.Y.Z" && git push --follow-tags`

CI builds the JAR + the Windows portable ZIP, extracts the matching CHANGELOG section as release notes, and publishes the GitHub Release with all four assets (`.jar`, `MangaPagesSplitter-windows-X.Y.Z.zip`, `.bat`, `.sh`).

## Architecture

The application consists of two classes with no package structure (default package):

- **`MangaPagesSplitter`** — Entry point (`main()`) and all processing logic: archive extraction, image splitting/cropping/rotation, and output archive creation. Orchestrates the pipeline: extract archives → process each folder's images → create output (CBZ/CBR/ZIP/RAR/folder).

- **`MangaPagesSplitterUI`** — Swing GUI built with `JFrame`. Collects user configuration (split mode, reading direction, crop values, output format, rotation, exception images). Launches processing on a `SwingWorker` background thread and displays real-time progress via a log pane and progress bar.

The UI calls `MangaPagesSplitter.processWithUI()`, passing all configuration. Processing callbacks update the UI's log and progress bar.

## Key Processing Logic

- **Split detection (auto mode):** An image is split only if its width > height (landscape orientation).
- **Reading direction:** Japanese (right-to-left) outputs right half first; Western (left-to-right) outputs left half first.
- **Archive extraction:** Uses junrar library for RAR/CBR (up to RAR4). Falls back to external 7-Zip or WinRAR for RAR5. Uses `java.util.zip` for ZIP/CBZ.
- **Output formats:** CBZ, CBR, ZIP, RAR, or plain folder. RAR/CBR output requires external WinRAR; falls back to ZIP if unavailable.

## Dependencies

- **junrar 7.5.5** — RAR archive extraction (RAR4 and below)
- **flatlaf 3.7** — Modern Swing look-and-feel with dark/light themes
- **jpackage** (JDK 14+ tool, invoked via `exec-maven-plugin`) — Produces
  the self-contained Windows app-image (launcher EXE + bundled JRE) during
  `mvn package`.
