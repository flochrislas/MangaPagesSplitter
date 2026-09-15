# Smart autocrop

Smart autocrop trims the uniform scan borders around manga pages and, on
double-page spreads, finds the real spine so the split lands on the seam rather
than at the geometric middle of the image. It is an alternative to the manual
left/right/top/bottom crop values: the manual values are fixed offsets applied to
every image, whereas smart autocrop measures each image individually.

All logic lives in `src/main/java/mangapagessplitter/image/AutoCrop.java`;
the entry point is `AutoCrop.apply`. Regression tests are in
`src/test/java/mangapagessplitter/image/AutoCropTest.java`. The UI controls are
in `src/main/java/mangapagessplitter/ui/MangaPagesSplitterUI.java`.

## Using it

In the **Image Cropping (applied before splitting)** panel:

- **Smart autocrop outer margins** checkbox turns the feature on.
- **Sensitivity (1-10)** spinner tunes how aggressive the detector is. Default is
  5. Lower values are more conservative and stop at fainter details; higher
  values ignore small or low-contrast features and trim more.

Smart autocrop and manual cropping are mutually exclusive. Enabling the checkbox
greys out the manual L/R/T/B spinners and their values are ignored for the run,
so a leftover manual offset cannot silently over-crop a batch. Turn the checkbox
off to use fixed manual offsets again.

The processing log shows what was decided for each image. For the first three
images it prints the exact amounts trimmed:

```
Smart autocrop: DLRAW.TO_Net_0028.jpg (L=542 R=542 T=0 B=0)
Smart autocrop applied to remaining images...
```

Other log lines you may see are explained in the sections below.

## Where it runs in the pipeline

For each image:

1. **Primary pass.** `AutoCrop.apply` runs on the full image with spine
   detection enabled. It trims the outer margins and, for landscape images,
   returns the X coordinate of the detected gutter.
2. **Split.** If the image is landscape (or forced by the split mode), it is cut
   at the detected gutter, falling back to `width / 2` when no gutter was found.
   The log line `Split image (...) at detected gutter x=1378` shows which.
3. **Second pass on each half.** Each half is run through `AutoCrop.apply`
   again with spine detection disabled. This removes the half-gutter whitespace
   that ends up on the inner side of each page, plus any page-number strip or
   watermark that only becomes an outer margin once the page stands alone.

Rotation and re-archiving happen afterwards and are not affected.

## How margin detection works

### Background reference from the corners

The four corner blocks of the image (10 to 40 px square depending on image size)
are sampled for their mean luminance and standard deviation. A corner is "calm"
if its standard deviation is below a uniformity threshold derived from the
sensitivity.

- At least **two calm corners** are required. Otherwise the page is treated as
  full-bleed art and returned untouched, with the log line
  `Spine detection: skipped (only N calm corners; treating as full-bleed art page, splitting at width/2)`.
- The mean of the calm corners becomes the background luminance `bgMean`. This
  is what lets the detector work on black-bordered scans as well as white ones.

### Content pixels, content lines

A pixel is **content** if its luminance differs from `bgMean` by more than
`delta`. `delta` is the larger of a sensitivity-based value and four times the
average corner noise, so speckled scans automatically get a wider tolerance.

A column or row is a **content line** if it contains at least `minPix` content
pixels. Counting pixels rather than averaging the whole line is deliberate: a
thin sword handle or speech-bubble tail poking into an otherwise empty margin
still registers, so the crop stops before shaving it.

Lines are subsampled for speed (roughly 800 samples per column and 1200 per
row), which is why crop amounts can differ by a pixel or two between
sensitivities.

### Crawling inward from each edge

`advanceEdge` walks from each of the four edges toward the centre and returns
the distance to the first **run of 5 consecutive content lines**. The run
requirement filters out isolated one- or two-pixel specks and scanner noise.

Two further guards make the crawl robust:

- **Edge artefact skipping.** Scanners often leave a faint smear or a dark
  shadow a few pixels wide along the very border of the sheet, wide enough to
  satisfy the 5-line run. If the first content run starts inside a narrow border
  strip (1% of the image dimension, at least 16 px), dies out inside that strip,
  and is followed by an equally long stretch of pure background, it is treated as
  an artefact and the crawl continues past it. Real content never looks like
  this: art or a panel border keeps going inward. The log reports
  `Outer crop: ignoring 8 px edge artefact on right side`.
- **Safety cap.** No side is ever trimmed by more than a third of the image
  dimension. If the caps collide the image is left untouched and the log shows
  `Spine detection: skipped (outer crop hit safety cap; splitting at width/2)`.

Each side then gets **3 px of padding** put back so line art touching the margin
is never clipped.

### Symmetric left/right crop on spreads

When the image is still landscape after the trim, the left and right crop are
forced to the **smaller** of the two values. Two facing pages come from the same
sheet and have symmetric outer margins by construction, so a disagreement almost
always means a real feature on one side (page number, watermark, edge art)
stopped the crawler early. Using the minimum keeps the horizontal centre of the
cropped image aligned with the true centre of the spread, so the `width / 2`
fallback split still lands on the gutter. The log line is
`Outer crop: enforcing symmetric L/R for landscape spread (was L=542 R=543, using 542 on both sides)`.

## How spine detection works

Only runs on the primary pass and only when the cropped image is landscape.

1. The **middle band**, 40% to 60% of the cropped width, is scanned and the
   content-pixel count of every column is recorded.
2. A column is **quiet** if its count is below `max(minPix, 30% of the band
   mean)`. The mean-based term is what makes this work on dark, busy spreads: it
   picks out columns that are meaningfully quieter than their neighbours.
3. The **longest run of quiet columns** is the gutter candidate. It must be at
   least `max(30 px, width / 60)` wide, which rules out gaps between panels.
4. The candidate's centre must lie within about **5% of the image centre**.
   Anything farther out is an internal panel gap, not the spine.

The split is placed at the centre of the accepted run, so each half inherits
half the gutter, which the second pass then trims. Log lines:

```
Spine detection: gutter run of 162 px, 0% off cropped-image center (accepted)
Spine detection: rejected (found 9% off center; falling back to width/2 split)
Spine detection: no gutter run in middle band (bandMean=592, quietThreshold=177, bestRunLen=0); falling back to width/2 split
```

The last case is normal for spreads where a single drawing runs across both
pages with no visible gutter. The symmetric crop above ensures `width / 2` is
still the right place to cut.

## Sensitivity in detail

| Sensitivity | `delta` (min luminance difference) | `minPix` (content pixels per line) | corner uniformity |
|---|---|---|---|
| 1 | 6 | 3 | 8 |
| 5 (default) | 18 | 11 | 16 |
| 10 | 33 | 21 | 26 |

Values in between are linear. In practice:

- **Lower** the sensitivity when a faint detail (light grey emblem, thin
  hairline) is being cropped off.
- **Raise** it when a noisy or yellowed scan is not being trimmed at all, or
  when the corner check keeps reporting fewer than two calm corners on pages
  that clearly have plain borders.

## Limitations

- A page number or watermark that sits in the margin stops the crawl on that
  side and, on spreads, on the opposite side too because of the symmetric rule.
  The margin outside it is still trimmed; the strip containing it is kept.
- Pages whose corners are not uniform (full-bleed art, heavy vignetting) are
  left as-is by design.
- Detection relies on luminance only. A margin that differs from the page in
  hue but not brightness will not be seen as a border.

## Test material

`test_images/0028.jpg` is a spread with a faint smear on the right border that
used to block the crop at low sensitivity. `test_images/0041.jpg` is a spread
with a dark shadow column on the right edge and no visible gutter, used to check
that the symmetric crop and the `width / 2` fallback behave. Both should come
out with roughly 540 px trimmed on each side at every sensitivity.
