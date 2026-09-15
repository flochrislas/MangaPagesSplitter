package mangapagessplitter.image;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Pure, in-memory page transformations: manual crop, 90-degree rotation and the
 * double-page split. None of these touch the filesystem or the UI.
 */
public final class PageTransform {

    private PageTransform() {}

    /**
     * Crops an image by removing specified number of pixels from each side.
     *
     * @param img The original image
     * @param left Pixels to crop from left
     * @param right Pixels to crop from right
     * @param top Pixels to crop from top
     * @param bottom Pixels to crop from bottom
     * @param log Receives a warning when the crop is skipped because it exceeds the image
     * @return The cropped image
     */
    public static BufferedImage crop(BufferedImage img, int left, int right, int top, int bottom,
                                     Consumer<String> log) {
        int origWidth = img.getWidth();
        int origHeight = img.getHeight();

        // Validate crop values don't exceed image dimensions
        if (left >= origWidth || top >= origHeight || left + right >= origWidth || top + bottom >= origHeight) {
            log.accept("Warning: crop values exceed image dimensions, skipping crop");
            return img;
        }

        // Calculate new dimensions
        int newWidth = Math.max(1, origWidth - left - right);
        int newHeight = Math.max(1, origHeight - top - bottom);

        // Create cropped image
        return img.getSubimage(left, top, newWidth, newHeight);
    }

    /** Rotates the image 90 degrees clockwise. */
    public static BufferedImage rotate(BufferedImage originalImage) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        BufferedImage rotatedImage = new BufferedImage(height, width, originalImage.getType());

        AffineTransform rotation = new AffineTransform();
        rotation.translate(height, 0);
        rotation.rotate(Math.toRadians(90));

        Graphics2D g2d = rotatedImage.createGraphics();
        g2d.setTransform(rotation);
        g2d.drawImage(originalImage, 0, 0, null);
        g2d.dispose();

        return rotatedImage;
    }

    /**
     * Cuts a spread into two pages at {@code splitX} (or at width / 2 when
     * {@code splitX} is not a valid column). The first element is the first page
     * in reading order: the right half for Japanese, the left half for Western.
     */
    public static BufferedImage[] split(BufferedImage originalImage, boolean isJapaneseManga, int splitX) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        int cut = (splitX > 0 && splitX < width) ? splitX : width / 2;

        BufferedImage leftHalf = originalImage.getSubimage(0, 0, cut, height);
        BufferedImage rightHalf = originalImage.getSubimage(cut, 0, width - cut, height);

        BufferedImage firstHalf, secondHalf;
        if (isJapaneseManga) {
            firstHalf = rightHalf;
            secondHalf = leftHalf;
        } else {
            firstHalf = leftHalf;
            secondHalf = rightHalf;
        }

        return new BufferedImage[]{firstHalf, secondHalf};
    }
}
