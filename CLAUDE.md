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

```bash
# Run the unit tests (JUnit 5, src/test/java)
mvn test
```

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

All code lives under the `mangapagessplitter` package (`src/main/java/mangapagessplitter/`):

- **`Main`** — Entry point: applies the saved FlatLaf theme and opens the window.
- **`BatchProcessor`** — Batch orchestration: discovers folders and archives under the root, extracts archives, runs every image through crop → split decision → rotation → split → second-pass autocrop, writes the output (CBZ/CBR/ZIP/RAR/folder) and cleans up. Talks to the UI only through `ProcessingListener`.
- **`ProcessingListener`** — What the engine needs from its driver: `isCancelled()`, `log()`, `progress()`.
- **`image.AutoCrop` / `AutoCropResult`** — Smart autocrop: margin trimming and spine detection. Pure image analysis, documented in `doc/autocrop.md`.
- **`image.PageTransform`** — Manual crop, 90° rotation, double-page split (`doc/autosplit.md`).
- **`archive.ZipArchive`** — ZIP/CBZ extract and create with `java.util.zip`.
- **`archive.RarArchive`** — RAR/CBR: junrar extraction with external-tool fallback; RAR creation with ZIP fallback.
- **`archive.ExternalTools`** — 7-Zip / WinRAR discovery and process execution.
- **`ui.MangaPagesSplitterUI`** — Swing `JFrame`. Collects configuration, runs `BatchProcessor.processWithUI()` on a `SwingWorker`, implements `ProcessingListener` to show the log and progress bar.

Tests are under `src/test/java` mirroring the package layout. `test_images/` holds two real scans used by the autocrop tests (skipped when absent).

## Key Processing Logic

- **Split detection (auto mode):** An image is split only if its width > height (landscape orientation).
- **Reading direction:** Japanese (right-to-left) outputs right half first; Western (left-to-right) outputs left half first.
- **Archive extraction:** Uses junrar library for RAR/CBR (up to RAR4). Falls back to external 7-Zip or WinRAR for RAR5. Uses `java.util.zip` for ZIP/CBZ.
- **Output formats:** CBZ, CBR, ZIP, RAR, or plain folder. RAR/CBR output requires an external WinRAR/rar; when none is installed the engine writes CBZ/ZIP under the matching extension and reports a warning.

## Dependencies

- **junrar 8.1.1** — RAR archive extraction (RAR4 and below). Keep it at or above 7.6.1: older releases have published path-traversal and infinite-loop advisories.
- **flatlaf 3.7** — Modern Swing look-and-feel with dark/light themes
- **jpackage** (JDK 14+ tool, invoked via `exec-maven-plugin`) — Produces
  the self-contained Windows app-image (launcher EXE + bundled JRE) during
  `mvn package`.
