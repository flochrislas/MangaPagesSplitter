# Handoff note — Resume "Smart Autocrop" work

> Purpose: this file is a handoff for another agent (with more token budget) to
> resume an in-progress task. The previous session ran out of usage mid-task.
> **This note is not meant to be committed** — delete it (or move it out of the
> repo) once the task is picked up.

## Where the work stopped

The last user request in the previous session (never answered) was:

> "Sometimes, like in `DLRAW.TO_Net_0028.jpg`, there are very tinty black pixels
> on the right border of the image, and because of that, the entire huge white
> margin that does exist left and right is left alone. See CBZ to see there is no
> crop for those images. Is there a way we could fix this behavior? Like, if a
> huge white margin is detected on the left, then we should expect one on the
> right as well, and vice versa, or run some additional logic that can handle a
> tiny bunch of incoherent parasite pixels, etc."

**The unfinished task:** make the outer-margin autocrop robust to a small cluster
of faint "parasite" pixels near a border. Today those pixels are counted as real
content, so the crawler stops immediately and a large white margin on that side is
never trimmed.

## Test assets

The two images used during development are now copied **into this repo** under
`test_images\` so they're always available (the diagnostic programs reference this
folder via a relative path):

- `test_images\0028.jpg` — the **failing** parasite-pixel case: huge
  white L/R margins left uncropped because of faint dark pixels on the right border.
  (originally `DLRAW.TO_Net_0028.jpg` in the source set)
- `test_images\0041.jpg` — previously-fixed reference: spine/gutter
  split, now works. (originally `DLRAW.TO_Net_0041.jpg`)

Original source folder on the user's machine (contains the full ~99-page set, if
you need more samples):
`N:\Manga\Japanese\Dragon Quest Aban weird\DLRAW.TO_Doragon Kuesuto dai no v05\`
(note: the old `...\test\...` sub-path from the previous session no longer exists —
the folder was moved up one level).


## How the feature works today (context you need)

All logic is in `src/main/java/MangaPagesSplitter.java` (default package, no
packages). UI toggles are in `src/main/java/MangaPagesSplitterUI.java`.

Key method: `smartAutoCropImage(BufferedImage img, int sensitivity, boolean detectGutter)`
around **line 960**. Returns an `AutoCropResult` (image, splitX, applied, and the
four crop amounts). Pipeline: primary autocrop (with gutter/spine detection) runs
before split; after split each half is re-run with `detectGutter=false`
(second-pass autocrop, ~line 515).

The margin detection uses a **content-pixel-count** criterion (not stddev):

- Corners (`cs`×`cs` blocks) are sampled to estimate a background luminance mean
  (`bgMean`) and average corner noise (`avgCornerStd`). Needs ≥2 "calm" corners or
  the page is treated as full-bleed art and left untouched.
- A pixel is "content" if `|luminance - bgMean| > delta`, where
  `delta = max(baseDelta, round(avgCornerStd * 4))`.
- A column/row is "content" if it has `>= minPix` content pixels.
- `advanceEdge(...)` (**line 1246**) crawls inward from each edge and returns the
  distance to the first **run of `minEdgeRun` (=5) consecutive content
  columns/rows**. The run-length gate already filters *thin* parasites, but a
  cluster ≥5 columns wide (or ≥5 rows) still stops the crawler.
- Sensitivity (1–10) maps to `baseDelta = 6 + (s-1)*3`, `minPix = 3 + (s-1)*2`,
  `uniformity = 6 + s*2`. Default sensitivity is 5.
- `maxCropSide = w/3`, `maxCropTB = h/3` safety caps. `pad = 3` px safety padding.
- Landscape spreads already get a **symmetric L/R** enforcement:
  `leftCrop = rightCrop = min(leftCrop, rightCrop)` (~line 1053) — but this only
  triggers when the image is detected as a landscape spread (before splitting),
  and it uses `min()`, which is the *opposite* direction from what's needed for the
  `0028` case (there a real margin exists on both sides but a parasite blocks one).

Helpers: `columnContentCount` (1182), `rowContentCount` (1195),
`blockMeanAndStddev` (1209), `advanceEdge` (1246).

## Why `0028.jpg` (`DLRAW.TO_Net_0028.jpg`) fails

The right border has a small cluster of faint dark pixels spanning ≥ `minEdgeRun`
columns, each column reaching `>= minPix` content pixels. So `advanceEdge` returns
almost immediately for the right side → `rightCrop ≈ 0`. On a spread, the symmetric
rule then does `min(leftCrop, 0) = 0`, wiping out the genuine left crop too, so the
whole white margin survives on both sides.

## Suggested approaches to implement (pick/combine; validate before finalizing)

1. **Parasite / outlier rejection in `advanceEdge`.** Instead of stopping at the
   first `minEdgeRun` run, require the content run to also be *persistent* — e.g.
   look ahead N columns and only accept the edge if the content region continues
   (a real page edge is a solid block, a parasite cluster is followed by more
   background). Or track the *fraction* of content columns over a sliding window.

2. **Whitespace-symmetry heuristic (user's idea).** If one side yields a large
   margin and the other yields ~0, and the near-zero side has only a *small
   isolated* content cluster near the very edge (bounded height/area), treat that
   cluster as a parasite and re-run that side's crawl with the parasite excluded
   (mask it, or ignore the first content run and continue). Then the symmetric-crop
   rule should probably switch from `min()` to something smarter (e.g. prefer the
   larger margin when the smaller side is explained by a parasite), otherwise a
   correctly detected wide margin still gets clobbered.

3. **Connected-component / bounding check on the near-edge content.** Measure the
   vertical extent and total area of the content that stopped the crawler. If it's a
   small blob (e.g. < a few % of image height/area) hugging the extreme edge, skip
   past it. This is the most robust but the most code.

Recommend combining (2) + a bounded version of (3): detect that the blocking
feature is small and edge-hugging, skip it, continue the crawl, and revisit the
`min()` symmetric rule so a valid margin isn't discarded.

**Watch out for regressions:** the symmetric-L/R `min()` rule (line ~1053) and the
full-bleed / safety-cap guards exist to prevent over-cropping. Any change that
makes cropping more aggressive must be verified against: full-bleed art pages,
pages with legitimate edge-touching art, and the previously-fixed `0041`
spine-split case. The previous session validated changes with small synthetic
reproductions in code — keep doing that plus test on the user's real folder.

## Build / run / test

```powershell
# Build (JDK 17+ required for jpackage). Produces fat JAR + Windows app-image.
mvn package

# Run the fat JAR (any Java 8+):
java -jar "target\MangaPagesSplitter-<version>-jar-with-dependencies.jar"

# Or the bundled EXE:
& "target\jpackage\MangaPagesSplitter\MangaPagesSplitter.exe"
```

No automated tests exist. The previous session verified each change with tiny
synthetic in-code reproductions of the failing image, then had the user re-run the
real batch and share the processing-log `Spine detection: ...` / `Outer crop: ...`
lines. Ask the user to test against the real `Dragon Quest` folder above and share
the log lines and before/after screenshots.

## UI notes (in case a new option is warranted)

`MangaPagesSplitterUI.java`: `smartAutoCropCheckbox`, `smartAutoCropSensitivitySpinner`
(sensitivity 1–10, default 5). Smart autocrop and manual crop are mutually
exclusive (manual spinners greyed out when smart is on — `setManualCropEnabled`,
line ~682). The processing log is preserved after a run until a new source is
chosen (`logShowsProcessingResults` flag). Keep this UX consistent if you add a
knob.

## Previous session for reference

Local session id (crop work history): `b88e0e77-d07c-4eb0-9b16-b1f512b29163`
— summary "Check Project Repo Sync". Turns 1–22 cover the entire smart-autocrop
development; turn 22 is the unanswered task above.
