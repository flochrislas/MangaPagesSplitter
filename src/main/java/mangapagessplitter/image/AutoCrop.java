package mangapagessplitter.image;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Smart autocrop: trims uniform scan borders and locates the spine of double-page
 * spreads. Pure image analysis, no filesystem or UI dependencies. See
 * {@code doc/autocrop.md} for the full description of the heuristics.
 */
public final class AutoCrop {

    private AutoCrop() {}

    /**
     * Detects and trims uniform outer margins (typically the white/black scan borders
     * on manga double-page spreads) and, when the image is landscape, tries to locate
     * the actual spine/gutter column so the split cut lands exactly on the seam
     * instead of at width / 2.
     *
     * Approach: sample the four corners to establish the background luminance mean.
     * A column/row is considered "margin" if fewer than {@code minPix} of its pixels
     * deviate from that background mean by more than {@code delta}. Counting pixels
     * (rather than averaging over the whole column) is what lets small features such
     * as a sword handle sticking out into an otherwise uniform dark margin still
     * stop the crawler.
     *
     * @param img         The image to analyze.
     * @param sensitivity 1 (conservative) to 10 (aggressive). 5 is a reasonable default.
     * @param detectGutter true to also locate the spine on landscape images (primary pass);
     *                     false for the second pass on already-split halves.
     * @param log         receives the human-readable decision log lines.
     * @return The (possibly cropped) image plus the detected split X (or -1).
     */
    public static AutoCropResult apply(BufferedImage img, int sensitivity, boolean detectGutter,
                                       Consumer<String> log) {
        final int w = img.getWidth();
        final int h = img.getHeight();
        if (w < 40 || h < 40) {
            return new AutoCropResult(img, -1, false, 0, 0, 0, 0);
        }

        int s = Math.max(1, Math.min(10, sensitivity));
        // Base per-pixel deviation from background to count as content. Kept small so
        // low-contrast features (e.g. a dark-grey emblem on a near-black page) still
        // register; the effective delta is bumped up when the corner background is
        // itself noisy — see the {@code delta} calculation below.
        // sensitivity 1 -> 6, 5 -> 18, 10 -> 33
        int baseDelta = 6 + (s - 1) * 3;
        // How many differing pixels a column/row needs to be considered content.
        // sensitivity 1 -> 3, 10 -> 21 (aggressive settings shrug off small features).
        int minPix = 3 + (s - 1) * 2;
        // How uniform a corner block must be to be trusted as background.
        // sensitivity 1 -> 8 (strict), 10 -> 26 (accepts noisy scans).
        double uniformity = 6.0 + s * 2.0;

        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);

        int rowStep = Math.max(1, h / 800);
        int colStep = Math.max(1, w / 1200);

        // Corner check + background-mean estimation.
        int cs = Math.max(10, Math.min(40, Math.min(w, h) / 40));
        double[] cornerMean = new double[4];
        double[] cornerStd  = new double[4];
        blockMeanAndStddev(pixels, w, 0,      0,      cs, cs, cornerMean, cornerStd, 0);
        blockMeanAndStddev(pixels, w, w - cs, 0,      cs, cs, cornerMean, cornerStd, 1);
        blockMeanAndStddev(pixels, w, 0,      h - cs, cs, cs, cornerMean, cornerStd, 2);
        blockMeanAndStddev(pixels, w, w - cs, h - cs, cs, cs, cornerMean, cornerStd, 3);

        double bgSum = 0;
        double stdSum = 0;
        int calmCorners = 0;
        for (int i = 0; i < 4; i++) {
            if (cornerStd[i] < uniformity) {
                bgSum += cornerMean[i];
                stdSum += cornerStd[i];
                calmCorners++;
            }
        }
        if (calmCorners < 2) {
            // Probably a full-bleed art page; do not touch it.
            if (detectGutter) {
                log.accept("Spine detection: skipped (only " + calmCorners
                        + " calm corners; treating as full-bleed art page, splitting at width/2)");
            }
            return new AutoCropResult(img, -1, false, 0, 0, 0, 0);
        }
        int bgMean = (int) Math.round(bgSum / calmCorners);
        double avgCornerStd = stdSum / calmCorners;
        // Adaptive delta: on very clean backgrounds we can afford to be sensitive
        // (low base value catches low-contrast features), on noisier scans the
        // 4x stddev term keeps us robust against speckle.
        int delta = Math.max(baseDelta, (int) Math.round(avgCornerStd * 4.0));

        // Generous per-side cap. With the corner-uniformity gate + content-pixel
        // criterion the algorithm won't runaway; the cap is just a last-resort
        // safety net so that pages with unusually wide margins (or the wide
        // whitespace inside a half after splitting a wide-gutter spread) can be
        // fully trimmed instead of leaving residual borders.
        int maxCropSide = w / 3;
        int maxCropTB   = h / 3;

        // Number of consecutive content columns/rows required to consider "content edge
        // reached". Filters out isolated near-edge parasites (a stray dark speckle, a
        // scanner artifact, a page-number strip only a couple of pixels wide, ...) so
        // a huge white margin does not go untrimmed just because the very last column
        // has a 30-pixel smudge.
        final int minEdgeRun = 5;

        int leftCrop = advanceEdge(pixels, w, h, +1, 0, maxCropSide,
                true, rowStep, bgMean, delta, minPix, minEdgeRun, "left", log);
        int rightCrop = advanceEdge(pixels, w, h, -1, w - 1, maxCropSide,
                true, rowStep, bgMean, delta, minPix, minEdgeRun, "right", log);
        int topCrop = advanceEdge(pixels, w, h, +1, 0, maxCropTB,
                false, colStep, bgMean, delta, minPix, minEdgeRun, "top", log);
        int bottomCrop = advanceEdge(pixels, w, h, -1, h - 1, maxCropTB,
                false, colStep, bgMean, delta, minPix, minEdgeRun, "bottom", log);

        // Small safety padding so we do not shave line art that touches the margin.
        final int pad = 3;
        leftCrop   = Math.max(0, leftCrop   - pad);
        rightCrop  = Math.max(0, rightCrop  - pad);
        topCrop    = Math.max(0, topCrop    - pad);
        bottomCrop = Math.max(0, bottomCrop - pad);

        // For a landscape image (a double-page spread that we're about to split),
        // enforce symmetric left/right outer crop. Two facing pages come from the
        // same physical paper stock and thus have symmetric outer margins by
        // construction; when the two detected values disagree it's almost always
        // because an edge-hugging feature on one side (a watermark, a page number,
        // a scanning artifact) blocked the crawler. Using min() on both sides keeps
        // the horizontal center of the cropped image aligned with the true center
        // of the scanned pages, so the width/2 fallback split lands on the real
        // gutter instead of being shifted into one page's content.
        if (detectGutter && (w - leftCrop - rightCrop) > (h - topCrop - bottomCrop)
                && leftCrop != rightCrop) {
            int symmetric = Math.min(leftCrop, rightCrop);
            log.accept("Outer crop: enforcing symmetric L/R for landscape spread"
                    + " (was L=" + leftCrop + " R=" + rightCrop
                    + ", using " + symmetric + " on both sides)");
            leftCrop = symmetric;
            rightCrop = symmetric;
        }

        if (leftCrop + rightCrop >= w - 20 || topCrop + bottomCrop >= h - 20) {
            if (detectGutter) {
                log.accept("Spine detection: skipped (outer crop hit safety cap; splitting at width/2)");
            }
            return new AutoCropResult(img, -1, false, 0, 0, 0, 0);
        }

        int cw = w - leftCrop - rightCrop;
        int ch = h - topCrop - bottomCrop;
        BufferedImage cropped = img;
        boolean applied = (leftCrop > 0 || rightCrop > 0 || topCrop > 0 || bottomCrop > 0);
        if (applied) {
            cropped = img.getSubimage(leftCrop, topCrop, cw, ch);
        }

        // Gutter detection: only meaningful when the cropped image is a landscape spread.
        // Strategy: scan the middle 40%..60% band and pick the longest contiguous run of
        // "quiet" columns. What counts as quiet is set *adaptively* per image so this works
        // on dark-background pages too:
        //   - Compute the content-pixel count for every column in the band.
        //   - "Quiet" = below max(minPix, meanBandContent * 0.30). On a clean margin the
        //     mean-based term is tiny and minPix dominates; on a busy dark-bg spread the
        //     mean-based term dominates and picks out the columns that are meaningfully
        //     quieter than their neighbours (which is where the gutter is).
        //   - A real inter-page gutter always spans dozens of consecutive quiet columns,
        //     while accidental between-panel gaps on a single page are typically 10-30 px
        //     and get filtered out by the minimum run-length requirement.
        // The split lands at the center of the detected run so each half only inherits half
        // the gutter width — the second-pass autocrop on each half then trims that leftover.
        int splitX = -1;
        if (detectGutter && cw > ch) {
            int bandStart = leftCrop + (int)(cw * 0.40);
            int bandEnd   = leftCrop + (int)(cw * 0.60);
            int bandLen   = bandEnd - bandStart + 1;

            int[] colContent = new int[bandLen];
            long bandSum = 0;
            for (int i = 0; i < bandLen; i++) {
                colContent[i] = columnContentCount(
                        pixels, w, bandStart + i, topCrop, ch, rowStep, bgMean, delta);
                bandSum += colContent[i];
            }
            int bandMean = (int) (bandSum / bandLen);
            int quietThreshold = Math.max(minPix, bandMean * 3 / 10);

            int bestRunStart = -1;
            int bestRunLen = 0;
            int runStart = -1;
            for (int i = 0; i < bandLen; i++) {
                if (colContent[i] < quietThreshold) {
                    if (runStart == -1) runStart = i;
                } else if (runStart != -1) {
                    int len = i - runStart;
                    if (len > bestRunLen) {
                        bestRunLen = len;
                        bestRunStart = runStart;
                    }
                    runStart = -1;
                }
            }
            if (runStart != -1) {
                int len = bandLen - runStart;
                if (len > bestRunLen) {
                    bestRunLen = len;
                    bestRunStart = runStart;
                }
            }

            // Minimum meaningful gutter width. Real double-page gutters are always
            // at least ~30 px wide; anything shorter is almost certainly an
            // internal between-panel gap that we want to leave alone.
            int minGutterRun = Math.max(30, cw / 60);
            if (bestRunStart != -1 && bestRunLen >= minGutterRun) {
                int gutterCenter = bandStart + bestRunStart + bestRunLen / 2;
                int imageCenter  = leftCrop + cw / 2;
                // Sanity gate: a real inter-page gutter is at most ~5% off the
                // geometric center of the (already outer-cropped) image. If the
                // longest quiet run is farther out, it's almost certainly an
                // internal between-panel gap rather than the true spine, so we
                // reject the detection and let splitImage fall back to width/2.
                // The second-pass autocrop then cleans each half symmetrically.
                int maxOffset = Math.max(15, cw / 20);
                int offsetPx = Math.abs(gutterCenter - imageCenter);
                if (offsetPx <= maxOffset) {
                    splitX = gutterCenter - leftCrop;
                    int offsetPct = (int) Math.round(100.0 * offsetPx / cw);
                    log.accept("Spine detection: gutter run of " + bestRunLen + " px, "
                            + offsetPct + "% off cropped-image center (accepted)");
                } else {
                    int offsetPct = (int) Math.round(100.0 * offsetPx / cw);
                    log.accept("Spine detection: rejected (found "
                            + offsetPct + "% off center; falling back to width/2 split)");
                }
            } else {
                log.accept("Spine detection: no gutter run in middle band"
                        + " (bandMean=" + bandMean + ", quietThreshold=" + quietThreshold
                        + ", bestRunLen=" + bestRunLen
                        + "); falling back to width/2 split");
            }
        }

        return new AutoCropResult(cropped, splitX, applied, leftCrop, rightCrop, topCrop, bottomCrop);
    }

    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8)  & 0xff;
        int b =  argb        & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static int columnContentCount(int[] pixels, int stride, int x, int y0, int height,
                                          int step, int bgMean, int delta) {
        int count = 0;
        int yEnd = y0 + height;
        int lo = bgMean - delta;
        int hi = bgMean + delta;
        for (int y = y0; y < yEnd; y += step) {
            int lum = luminance(pixels[y * stride + x]);
            if (lum < lo || lum > hi) count++;
        }
        return count;
    }

    private static int rowContentCount(int[] pixels, int stride, int y, int x0, int width,
                                       int step, int bgMean, int delta) {
        int count = 0;
        int base = y * stride;
        int xEnd = x0 + width;
        int lo = bgMean - delta;
        int hi = bgMean + delta;
        for (int x = x0; x < xEnd; x += step) {
            int lum = luminance(pixels[base + x]);
            if (lum < lo || lum > hi) count++;
        }
        return count;
    }

    private static void blockMeanAndStddev(int[] pixels, int stride, int x0, int y0, int bw, int bh,
                                           double[] meanOut, double[] stdOut, int idx) {
        long sum = 0;
        long sumSq = 0;
        int n = 0;
        for (int y = y0; y < y0 + bh; y++) {
            int base = y * stride;
            for (int x = x0; x < x0 + bw; x++) {
                int lum = luminance(pixels[base + x]);
                sum += lum;
                sumSq += (long) lum * lum;
                n++;
            }
        }
        if (n == 0) { meanOut[idx] = 0; stdOut[idx] = 0; return; }
        double mean = (double) sum / n;
        double var  = (double) sumSq / n - mean * mean;
        meanOut[idx] = mean;
        stdOut[idx]  = var > 0 ? Math.sqrt(var) : 0;
    }

    /**
     * Scans inward from an image edge and returns the number of pixels between the edge
     * and the first "real" content region. Uses a run-length criterion: the returned
     * distance corresponds to the first column (if {@code columnScan}) or row that begins
     * a run of {@code minRun} consecutive content columns / rows. This filters out
     * isolated near-edge parasites (a stray dark speckle, a scanner artifact, an
     * unusually thin page-number strip) that would otherwise stop the crawler and leave
     * a huge white margin untrimmed.
     *
     * <p>A second guard handles clusters that are wider than {@code minRun} but still
     * clearly artefacts: the faint smear or dark shadow a scanner leaves along the very
     * border of the sheet. Such a cluster hugs the edge, is only a handful of pixels
     * wide, and is followed by a long stretch of pure background. Real content at a
     * page edge never looks like that — art or a panel border keeps going inward. When
     * the first content run starts inside {@code parasiteZone} and dies out again inside
     * that zone with at least {@code parasiteZone} background lines behind it, the
     * cluster is skipped and the crawl continues. See {@link #edgeParasiteEnd}.
     *
     * @param direction +1 to scan inward from the low edge, -1 to scan inward from the high edge.
     * @param start     starting absolute coordinate (0 for low edge, w-1 or h-1 for high edge).
     * @param cap       hard maximum distance to advance (per-side safety cap).
     * @param columnScan true for left/right (scans columns), false for top/bottom (scans rows).
     * @param otherStep row-step for column scans; col-step for row scans (subsampling factor).
     * @param sideLabel human-readable side name used in the processing log.
     * @return the distance from the edge to the first content run, or {@code cap} if none found.
     */
    private static int advanceEdge(int[] pixels, int w, int h, int direction, int start, int cap,
                                   boolean columnScan, int otherStep,
                                   int bgMean, int delta, int minPix, int minRun, String sideLabel,
                                   Consumer<String> log) {
        // Border strip in which a narrow, isolated content cluster is treated as a
        // scanner artefact instead of a content edge: ~1% of the scanned dimension,
        // never less than 16 px (3840 px spread -> 38 px, 1400 px half -> 16 px).
        int dim = columnScan ? w : h;
        int parasiteZone = Math.max(16, dim / 100);

        int dist = 0;
        int runStart = -1;
        int runLen = 0;
        while (dist < cap) {
            int c = lineContentCount(pixels, w, h, columnScan, start + direction * dist,
                    otherStep, bgMean, delta);
            if (c >= minPix) {
                if (runStart < 0) runStart = dist;
                runLen++;
                if (runLen >= minRun) {
                    if (runStart < parasiteZone) {
                        int clusterEnd = edgeParasiteEnd(pixels, w, h, direction, start, cap,
                                columnScan, otherStep, bgMean, delta, minPix,
                                runStart, parasiteZone);
                        if (clusterEnd >= 0) {
                            log.accept("Outer crop: ignoring " + (clusterEnd - runStart)
                                    + " px edge artefact on " + sideLabel + " side");
                            dist = clusterEnd;
                            runStart = -1;
                            runLen = 0;
                            continue;
                        }
                    }
                    return runStart;
                }
            } else {
                runStart = -1;
                runLen = 0;
            }
            dist++;
        }
        return cap;
    }

    /**
     * Decides whether the content run starting at distance {@code runStart} from the
     * edge is an edge artefact. Walks inward from {@code runStart}: the cluster is an
     * artefact if its last content line lies within {@code parasiteZone} of the edge and
     * is followed by at least {@code parasiteZone} consecutive background lines.
     *
     * @return the distance of the first line after the artefact (where the crawl should
     *         resume), or -1 if the run is real content.
     */
    private static int edgeParasiteEnd(int[] pixels, int w, int h, int direction, int start, int cap,
                                       boolean columnScan, int otherStep,
                                       int bgMean, int delta, int minPix,
                                       int runStart, int parasiteZone) {
        int lastContent = runStart;
        int gap = 0;
        int limit = Math.min(cap, parasiteZone * 2);
        for (int d = runStart; d < limit; d++) {
            int c = lineContentCount(pixels, w, h, columnScan, start + direction * d,
                    otherStep, bgMean, delta);
            if (c >= minPix) {
                lastContent = d;
                gap = 0;
                if (lastContent >= parasiteZone) {
                    return -1; // cluster extends past the border strip: real content
                }
            } else {
                gap++;
                if (gap >= parasiteZone) {
                    return lastContent + 1;
                }
            }
        }
        return -1;
    }

    private static int lineContentCount(int[] pixels, int w, int h, boolean columnScan, int idx,
                                        int otherStep, int bgMean, int delta) {
        return columnScan
                ? columnContentCount(pixels, w, idx, 0, h, otherStep, bgMean, delta)
                : rowContentCount(pixels, w, idx, 0, w, otherStep, bgMean, delta);
    }
}
