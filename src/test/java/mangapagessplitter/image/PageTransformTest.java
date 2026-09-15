package mangapagessplitter.image;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PageTransformTest {

    private static final int LEFT = 0xff2020ff, RIGHT = 0xffff2020;

    /** Left half painted blue, right half red, so we can tell which half came out first. */
    private static BufferedImage twoColourSpread(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setRGB(x, y, x < w / 2 ? LEFT : RIGHT);
        }
        return img;
    }

    @Test
    void japaneseSplitReturnsRightHalfFirst() {
        BufferedImage[] halves = PageTransform.split(twoColourSpread(200, 100), true, -1);
        assertEquals(RIGHT, halves[0].getRGB(10, 10));
        assertEquals(LEFT, halves[1].getRGB(10, 10));
    }

    @Test
    void westernSplitReturnsLeftHalfFirst() {
        BufferedImage[] halves = PageTransform.split(twoColourSpread(200, 100), false, -1);
        assertEquals(LEFT, halves[0].getRGB(10, 10));
        assertEquals(RIGHT, halves[1].getRGB(10, 10));
    }

    @Test
    void oddWidthSplitKeepsEveryColumn() {
        BufferedImage[] halves = PageTransform.split(twoColourSpread(201, 100), false, -1);
        assertEquals(100, halves[0].getWidth());
        assertEquals(101, halves[1].getWidth());
        assertEquals(201, halves[0].getWidth() + halves[1].getWidth());
    }

    @Test
    void explicitSplitColumnIsHonoured() {
        BufferedImage[] halves = PageTransform.split(twoColourSpread(200, 100), false, 70);
        assertEquals(70, halves[0].getWidth());
        assertEquals(130, halves[1].getWidth());
    }

    @Test
    void invalidSplitColumnFallsBackToMiddle() {
        for (int bad : new int[]{0, -5, 200, 999}) {
            BufferedImage[] halves = PageTransform.split(twoColourSpread(200, 100), false, bad);
            assertEquals(100, halves[0].getWidth(), "splitX=" + bad);
        }
    }

    @Test
    void cropRemovesRequestedPixelsFromEachSide() {
        BufferedImage img = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        img.setRGB(10, 5, RIGHT);
        BufferedImage out = PageTransform.crop(img, 10, 20, 5, 15, m -> {});
        assertEquals(70, out.getWidth());
        assertEquals(60, out.getHeight());
        assertEquals(RIGHT, out.getRGB(0, 0), "top-left of the crop is the original (10,5)");
    }

    @Test
    void cropExceedingImageIsSkippedWithWarning() {
        BufferedImage img = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        List<String> log = new ArrayList<>();
        BufferedImage out = PageTransform.crop(img, 60, 50, 0, 0, log::add);
        assertSame(img, out);
        assertEquals(1, log.size());
    }

    @Test
    void rotateSwapsDimensionsAndTurnsClockwise() {
        BufferedImage img = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, RIGHT);            // top-left pixel
        BufferedImage out = PageTransform.rotate(img);
        assertEquals(100, out.getWidth());
        assertEquals(200, out.getHeight());
        // A clockwise turn sends the top-left corner to the top-right corner.
        assertEquals(RIGHT, out.getRGB(99, 0));
    }
}
