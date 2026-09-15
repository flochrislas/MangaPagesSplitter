# Changelog

All notable changes to **MangaPagesSplitter** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Smart autocrop outer margins** — new checkbox in the *Image Cropping*
  panel. When enabled, MangaPagesSplitter analyzes each image to trim
  uniform white/black scan borders on all four sides (with a small
  safety padding), and on landscape double-page spreads locates the
  actual spine / gutter so the split cut lands exactly on the seam
  instead of at `width / 2`. The detector samples the four corners for
  a background reference and counts how many pixels in each column /
  row deviate meaningfully from it, so thin details protruding into a
  dark margin (e.g. a sword handle) stop the crop where they should
  instead of getting shaved. A `1`–`10` sensitivity spinner tunes how
  aggressive the detector is; images whose corners aren't uniform
  background (e.g. full-bleed art pages) are left untouched.
  Narrow scanner artefacts hugging the image border (a faint smear or
  dark shadow a few pixels wide, followed by a long stretch of blank
  margin) are recognised and skipped, so they no longer block the
  margin crawler and leave a wide white border untrimmed on that side.

### Fixed
- **Input files can no longer be lost by a failed or cancelled run.** Archives
  are extracted into a hidden per-run workspace instead of next to your folders,
  so an existing folder with the same name is never overwritten or deleted.
  Outputs are written to a staging file, verified, then moved into place; an
  existing file at the destination is renamed to a unique `_original` backup
  rather than replaced. A source folder or archive is deleted only after every
  output derived from it has been published, and never when the run was
  cancelled or that item failed. Output names are validated (no `..`, no path
  separators, must stay inside the selected folder) and colliding names get a
  `(2)` suffix instead of overwriting each other. The final log line now says
  whether the run completed, was cancelled, or finished with errors, and lists
  the failed items.
- RAR/CBR archives that neither junrar nor an external tool could extract are
  now reported as failures instead of being silently treated as empty.
- ZIP/CBZ extraction now validates directory entries as well as file entries,
  so a crafted archive can no longer create folders outside the extraction
  directory.

### Security
- junrar upgraded from 7.5.5 to 8.1.1, covering the published advisories for
  path traversal (GHSA-j273-m5qq-6825, GHSA-hf5p-q87m-crj7,
  GHSA-89m4-43j5-vhhx) and the RAR4 infinite-loop denial of service
  (GHSA-h9h9-2rmf-rvhp).

### Changed
- Smart autocrop and manual four-side cropping are now **mutually
  exclusive**. Enabling smart autocrop disables the manual L/R/T/B
  spinners so leftover manual values can't silently over-crop a batch.
  Turn smart autocrop off to use fixed manual offsets again.
- Internal: the two monolithic classes are split into the `mangapagessplitter`
  package (`image`, `archive`, `ui` sub-packages, `BatchProcessor` engine,
  `Main` entry point) and the project now has a JUnit 5 test suite
  (`mvn test`). No functional change.

## [2.2.2] - 2026-06-28

### Changed
- Windows distribution is now a self-contained portable ZIP
  (`MangaPagesSplitter-windows-<version>.zip`) built with `jpackage`.
  It bundles a trimmed Java 17 runtime, so users no longer need Java
  installed on their machine and just run the included
  `MangaPagesSplitter.exe`.
- Build now requires JDK 17+ (for `jpackage`). The library JAR still
  targets Java 8 bytecode, so the standalone `.jar` keeps working on
  Java 8+.

### Removed
- Standalone `MangaPagesSplitter.exe` asset (the previous launch4j
  wrapper). It was flagged as malware by Bitdefender's Advanced Threat
  Defense heuristic, a known false positive caused by malware authors
  reusing the launch4j stub. The jpackage-based portable bundle
  replaces it.

## [2.2.1] - 2026-06-28

### Fixed
- Windows EXE no longer opens an extra console window when launched.
  Closing that console previously also killed the app. The launcher is now
  built in GUI mode; Java-startup errors are shown as a dialog instead.

## [2.2.0] - 2026-05-09

### Added
- Custom output title field, so generated archives/folders can be named freely.
- Option to handle nested folders inside input archives/folders.
- Ability to paste a path directly into the Input Files Location field.

### Changed
- Improved layout and wording of the Input Files Location section.

## [2.1.0] - 2026-04-26

### Added
- FlatLaf-based modern UI with a dark/light theme switch.
- README now includes manga image examples illustrating the splitting behavior.

### Fixed
- Images are now sorted alphabetically before "skip from start/end" is applied
  (`Files.walk()` results were previously unsorted).

## [2.0.0] - 2026-02-14

### Added
- New unified UI replacing the previous separate dialogs.
- Optional cropping pass applied before splitting.
- Choice of output format: CBZ, CBR, ZIP, RAR, or plain folder.

### Fixed
- Cleanup logic now preserves user folders and only removes archives it
  extracted itself.

## [1.5.0] - 2025-03-08

### Added
- Reading-direction option (left-to-right for Western comics,
  right-to-left for manga).
- Auto-detect mode: an image is split only if it's a landscape spread,
  so single-page art inside an otherwise double-page volume is preserved.
- Option to keep all original files (no deletion during processing).
- Option to skip a number of images from the start and end of each volume.
- Option to auto-rotate wide images for better tablet/phone viewing.
- Single-field version management in `pom.xml`.

## [1.0.0] - 2025-03-07

### Added
- Initial public release: batch-split double-page manga spreads into single
  pages, with archive (CBZ/CBR/ZIP/RAR) input support.

[Unreleased]: https://github.com/flochrislas/MangaPagesSplitter/compare/v2.2.2...HEAD
[2.2.2]: https://github.com/flochrislas/MangaPagesSplitter/compare/v2.2.1...v2.2.2
[2.2.1]: https://github.com/flochrislas/MangaPagesSplitter/compare/v2.2.0...v2.2.1
[2.2.0]: https://github.com/flochrislas/MangaPagesSplitter/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/flochrislas/MangaPagesSplitter/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/flochrislas/MangaPagesSplitter/compare/v1.5.0...v2.0.0
[1.5.0]: https://github.com/flochrislas/MangaPagesSplitter/compare/v1.0.0...v1.5.0
[1.0.0]: https://github.com/flochrislas/MangaPagesSplitter/releases/tag/v1.0.0
