# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MangaPagesSplitter is a Java Swing desktop application that batch-processes manga/comic archives by splitting double-page spread images into single pages, with optional cropping, rotation, and re-archiving. It targets Java 8+.

## Build Commands

```bash
# Compile and package (JAR + Windows EXE)
mvn package

# Package with code signing for the EXE
mvn package -Psign-exe -Dsigning.keystore=path\to\keystore.pfx -Dsigning.storepass=password

# Run the application
java -jar target/MangaPagesSplitter-1.5-jar-with-dependencies.jar
```

There are no tests configured in this project.

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
- **launch4j-maven-plugin** — Generates Windows `.exe` wrapper during `mvn package`
