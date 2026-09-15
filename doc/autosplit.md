# Automatic double-page splitting

MangaPagesSplitter's core job is to turn scanned double-page spreads (one image
holding two facing pages) into two single-page images, in the right reading
order, so a volume reads naturally on a phone or tablet. This document covers
how an image is decided to be a spread, where the cut is placed, how the halves
are ordered and named, and the options that influence it.

The spread decision lives in the per-image loop of
`src/main/java/mangapagessplitter/BatchProcessor.java`; the cut itself is
`PageTransform.split` in `src/main/java/mangapagessplitter/image/PageTransform.java`.
The options are in `src/main/java/mangapagessplitter/ui/MangaPagesSplitterUI.java`.

## Options

### Image Splitting Options

| Radio button | Internal mode | Behaviour |
|---|---|---|
| **Only split wide images (smart)** (default) | 0, "Auto-detect" | Splits an image only when it is detected as a double-page spread. |
| **No split at all** | 1, "Keep original" | Never splits. Cropping, rotation and re-archiving still apply. |
| **Split all images in half** | 2, "Split all" | Splits every image, portrait or landscape, unless it is an exception. |

Reading direction, exceptions and rotation are greyed out when they cannot
apply to the chosen mode.

### Reading Direction

- **Japanese style (right to left) [mangas]** (default): the right half becomes
  the first page, the left half the second.
- **Western style (left to right) [comics]**: the left half comes first.

### Auto-split Exceptions

**Skip splitting certain images**, with **Skip from start** and **Skip from
end** counters. Images are sorted by file name; the first N and last N images of
each folder are never split. This is meant for covers, colour inserts, tables of
contents and back matter, which are often landscape but are not spreads. The
log shows `Skipping split for exception image: <name>` for each one.

### Image Rotation

**Rotate wide images 90° clockwise** rotates a landscape image that is *not*
being split, so a wide illustration fills a portrait screen. It never touches an
image that is about to be split. In "Split all" mode it is only available when
exceptions are enabled, because only exception images can remain wide.

## Detection rule in auto mode

Images in a folder are processed in sorted file-name order. For each image:

1. Cropping runs first (smart autocrop or manual offsets), so the decision is
   made on the cropped dimensions.
2. The image is **wide** if `width > height`.
3. An exception image (inside the skip-from-start or skip-from-end ranges) is
   never split.
4. A wide image is split unless it is a **special spread**: a wide image that
   sits in one of the two positions directly after the most recent single-page
   (portrait) image. The reasoning is that a genuine double-page artwork embedded in a run
   of otherwise single pages is usually already meant to be viewed as one wide
   image, whereas a scan where every page is a spread has no portrait pages
   between them. Concretely:
   - a volume scanned entirely as spreads has no portrait pages, so every wide
     image is split;
   - a volume scanned as single pages with the occasional wide artwork keeps
     that artwork intact (and rotates it if rotation is enabled);
   - from the third consecutive wide image after a portrait one, the splitter
     treats the folder as spread-scanned again and resumes splitting.

   Accepted wide images are logged as `Auto-detected double page for: <name>`.

In "Split all" mode steps 2 and 4 are skipped: every non-exception image is cut,
including portrait ones.

## Where the cut lands

`PageTransform.split` cuts at a single X coordinate:

- If **smart autocrop** is on and its spine detection found the gutter, the cut
  is placed at that column. The log reads
  `Split image (right to left) at detected gutter x=1378: <name>`.
  See `autocrop.md` for how the gutter is located.
- Otherwise the cut is at `width / 2`. With smart autocrop on, the symmetric
  left/right crop keeps the middle of the cropped image aligned with the real
  gutter, so `width / 2` is normally still on the seam.
- When manual cropping is used, the detected gutter coordinate is shifted by
  the left crop amount so it stays on the same pixel of the page.

Pixels are never resampled: each half is a sub-image of the original, so quality
is preserved and the two halves together cover every column of the source.

When smart autocrop is on, each half is run through the autocrop a second time
(without spine detection) to trim the half-gutter whitespace left on its inner
edge and any margin that only became an outer border after the split.

## Ordering and naming of the halves

Each split produces two files named after the source with a suffix:

```
0028.jpg  ->  0028_1.jpg   (first page in reading order)
              0028_2.jpg   (second page)
```

For Japanese reading direction `_1` is the **right** half; for Western it is
the **left** half. Because output archives are read in file-name order by comic
readers, this suffix scheme keeps pages in sequence between unsplit and split
images. The original extension is kept and the image is re-encoded with Java's
`ImageIO` writer for that format.

## Interaction with other steps

Per image, the order is:

1. Smart autocrop or manual crop.
2. Wide / spread decision (this document).
3. Rotation, only if the image is wide and not being split.
4. Split, then second-pass autocrop on each half.
5. Write to a temporary folder; the source files are never modified in place.

After all images in a folder are processed they are packed into the chosen
output format (CBZ, ZIP, CBR, RAR or a plain folder).

## Log lines summary

```
Split mode: Auto-detect
Reading direction: Japanese (right to left)
Skipping 1 images from start and 2 images from end of each manga
Auto-detected double page for: 0028.jpg
Skipping split for exception image: 0001.jpg
Rotated wide image: 0002.jpg
Split image (right to left) at detected gutter x=1378: 0028.jpg
```

## Limitations

- Detection is purely geometric (`width > height`). A landscape single page,
  such as a rotated illustration, is treated as a spread unless it falls under
  the special-spread rule or is declared an exception.
- The special-spread rule is positional. A real spread that sits right after a
  portrait page (for example page 2 following a portrait cover) is kept whole.
  Use "Split all images in half" with exceptions when a volume is known to be
  all spreads apart from a few covers.
- Without smart autocrop the cut is always at the exact middle. Scans where the
  two pages are not centred will have a sliver of the neighbouring page on each
  half.
