package mangapagessplitter.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression tests for {@link AutoCrop}. Synthetic spreads are 3840x2160 with a
 * busy "page" block from x=545 to x=3295 and y=300 to y=1900, so the expected
 * outer crop is 542 px left/right (545 minus the 3 px safety padding) and 297 px
 * on top. Every case is run at all ten sensitivities.
 */
class AutoCropTest {

    private static final int W = 3840, H = 2160;
    private static final int PAGE_X0 = 545, PAGE_Y0 = 300, PAGE_X1 = 3295, PAGE_Y1 = 1900;
    private static final int EXPECTED_SIDE = PAGE_X0 - 3;   // 542
    private static final int EXPECTED_TOP = PAGE_Y0 - 3;    // 297

    // ---- edge artefacts that must be skipped -------------------------------

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void faintSmearOnRightBorderIsIgnored(int sensitivity) {
        BufferedImage img = spread();
        Random rnd = new Random(1);
        for (int x = W - 8; x < W; x++) {
            for (int y = 1100; y < 2000; y += 10 + rnd.nextInt(60)) img.setRGB(x, y, grey(240));
        }
        for (int y = 1100; y < 2000; y += 25) img.setRGB(W - 1, y, grey(20));

        AutoCropResult r = AutoCrop.apply(img, sensitivity, true, m -> {});
        assertSides(r, EXPECTED_SIDE, EXPECTED_TOP);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void faintSmearOnLeftBorderIsIgnored(int sensitivity) {
        BufferedImage img = spread();
        for (int x = 0; x < 8; x++) {
            for (int y = 1100; y < 2000; y += 15) img.setRGB(x, y, grey(235));
        }
        AutoCropResult r = AutoCrop.apply(img, sensitivity, true, m -> {});
        assertSides(r, EXPECTED_SIDE, EXPECTED_TOP);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void sparseDotsAlongBottomBorderAreIgnored(int sensitivity) {
        BufferedImage img = spread();
        for (int y = H - 6; y < H; y++) {
            for (int x = 800; x < 2800; x += 12) img.setRGB(x, y, grey(230));
        }
        AutoCropResult r = AutoCrop.apply(img, sensitivity, true, m -> {});
        assertSides(r, EXPECTED_SIDE, EXPECTED_TOP);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void solidShadowColumnAtBorderIsIgnored(int sensitivity) {
        BufferedImage img = spread();
        for (int y = 500; y < H; y++) img.setRGB(W - 1, y, grey(0));
        for (int x = W - 7; x < W - 1; x++) {
            for (int y = 500; y < 1600; y += 8) img.setRGB(x, y, grey(238));
        }
        AutoCropResult r = AutoCrop.apply(img, sensitivity, true, m -> {});
        assertSides(r, EXPECTED_SIDE, EXPECTED_TOP);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void sliverOfNeighbouringPageOnSplitHalfIsIgnored(int sensitivity) {
        // Portrait half, content flush to the left; 6 px of the other page at the
        // inner edge, then 72 px of gutter whitespace.
        BufferedImage img = blank(1378, H);
        content(img, 0, PAGE_Y0, 1300, PAGE_Y1);
        for (int x = 1372; x < 1378; x++) {
            for (int y = PAGE_Y0; y < PAGE_Y1; y++) img.setRGB(x, y, grey(0));
        }
        AutoCropResult r = AutoCrop.apply(img, sensitivity, false, m -> {});
        assertEquals(0, r.leftCropped, "left");
        assertTrue(r.rightCropped >= 75 && r.rightCropped <= 78, "right=" + r.rightCropped);
    }

    // ---- real content that must NOT be skipped ------------------------------

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void frameLineWellInsideBorderStopsTheCrop(int sensitivity) {
        BufferedImage img = spread();
        for (int x = 60; x < 66; x++) for (int y = 100; y < 2060; y++) img.setRGB(x, y, grey(0));
        for (int x = W - 66; x < W - 60; x++) for (int y = 100; y < 2060; y++) img.setRGB(x, y, grey(0));

        AutoCropResult r = AutoCrop.apply(img, sensitivity, true, m -> {});
        assertEquals(57, r.leftCropped, "left");
        assertEquals(57, r.rightCropped, "right");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void artBleedingToTheEdgeIsNotCropped(int sensitivity) {
        BufferedImage img = blank(W, H);
        content(img, PAGE_X0, PAGE_Y0, W, PAGE_Y1);
        AutoCropResult r = AutoCrop.apply(img, sensitivity, true, m -> {});
        assertEquals(0, r.leftCropped, "left (symmetric with right)");
        assertEquals(0, r.rightCropped, "right");
        assertEquals(EXPECTED_TOP, r.topCropped, "top");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void featureTouchingTheEdgeAndWideningInwardIsNotCropped(int sensitivity) {
        BufferedImage img = spread();
        for (int x = PAGE_X1; x < W; x++) {
            int half = 24 + (W - x) / 8;
            for (int y = 1000 - half; y < 1000 + half; y++) img.setRGB(x, y, grey(0));
        }
        AutoCropResult r = AutoCrop.apply(img, sensitivity, true, m -> {});
        assertEquals(0, r.rightCropped, "right");
        assertEquals(0, r.leftCropped, "left (symmetric with right)");
    }

    @Test
    void fullBleedPageWithNoisyCornersIsLeftUntouched() {
        BufferedImage img = blank(W, H);
        content(img, 0, 0, W, H);
        AutoCropResult r = AutoCrop.apply(img, 5, true, m -> {});
        assertFalse(r.applied);
        assertEquals(-1, r.splitX);
    }

    @Test
    void tinyImageIsLeftUntouched() {
        AutoCropResult r = AutoCrop.apply(blank(30, 30), 5, true, m -> {});
        assertFalse(r.applied);
    }

    // ---- spine detection ----------------------------------------------------

    @Test
    void gutterBetweenTwoPagesIsDetectedNearTheCentre() {
        BufferedImage img = blank(W, H);
        content(img, PAGE_X0, PAGE_Y0, 1840, PAGE_Y1);   // left page, ends 80 px before centre
        content(img, 2000, PAGE_Y0, W - PAGE_X0, PAGE_Y1); // right page, starts 80 px after centre
        AutoCropResult r = AutoCrop.apply(img, 5, true, m -> {});
        assertTrue(r.applied);
        // gutter centre is x=1920 in the original, i.e. 1920 - leftCropped in cropped coords
        int expected = 1920 - r.leftCropped;
        assertTrue(Math.abs(r.splitX - expected) <= 2, "splitX=" + r.splitX + " expected~" + expected);
    }

    // ---- real scans (skipped when test_images/ is absent) --------------------

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void realSpreadWithFaintRightBorderSmearIsTrimmedOnBothSides(int sensitivity) throws IOException {
        BufferedImage img = loadOrSkip("test_images/0028.jpg");
        AutoCropResult r = AutoCrop.apply(img, sensitivity, true, m -> {});
        assertTrue(r.leftCropped >= 540 && r.leftCropped <= 543, "left=" + r.leftCropped);
        assertEquals(r.leftCropped, r.rightCropped, "symmetric");
        assertTrue(r.splitX > 0, "gutter detected");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void realSpreadWithDarkShadowColumnIsTrimmedOnBothSides(int sensitivity) throws IOException {
        BufferedImage img = loadOrSkip("test_images/0041.jpg");
        AutoCropResult r = AutoCrop.apply(img, sensitivity, true, m -> {});
        assertTrue(r.leftCropped >= 541 && r.leftCropped <= 545, "left=" + r.leftCropped);
        assertEquals(r.leftCropped, r.rightCropped, "symmetric");
    }

    // ---- helpers ------------------------------------------------------------

    private static void assertSides(AutoCropResult r, int side, int top) {
        assertEquals(side, r.leftCropped, "left");
        assertEquals(side, r.rightCropped, "right");
        assertEquals(top, r.topCropped, "top");
    }

    private static BufferedImage loadOrSkip(String path) throws IOException {
        File f = new File(path);
        assumeTrue(f.isFile(), "sample image not available: " + path);
        return ImageIO.read(f);
    }

    /** White spread with one busy page block in the middle. */
    private static BufferedImage spread() {
        BufferedImage img = blank(W, H);
        content(img, PAGE_X0, PAGE_Y0, PAGE_X1, PAGE_Y1);
        return img;
    }

    private static BufferedImage blank(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int white = grey(255);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, white);
        return img;
    }

    /** Fills a rectangle with pseudo-art dense enough that every column and row registers as content. */
    private static void content(BufferedImage img, int x0, int y0, int x1, int y1) {
        Random rnd = new Random(42);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                if (((x / 7) + (y / 7)) % 3 == 0 || rnd.nextInt(6) == 0) img.setRGB(x, y, grey(rnd.nextInt(120)));
            }
        }
    }

    private static int grey(int v) {
        return (0xff << 24) | (v << 16) | (v << 8) | v;
    }
}
