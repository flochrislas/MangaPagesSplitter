package mangapagessplitter.image;

import java.awt.image.BufferedImage;

/**
 * Outcome of {@link AutoCrop#apply}: the (possibly cropped) image, the detected
 * spine column in cropped-image coordinates (or -1), whether any crop was applied,
 * and how many pixels were removed on each side.
 */
public final class AutoCropResult {
    public final BufferedImage image;
    public final int splitX;
    public final boolean applied;
    public final int leftCropped, rightCropped, topCropped, bottomCropped;

    public AutoCropResult(BufferedImage image, int splitX, boolean applied,
                          int leftCropped, int rightCropped, int topCropped, int bottomCropped) {
        this.image = image;
        this.splitX = splitX;
        this.applied = applied;
        this.leftCropped = leftCropped;
        this.rightCropped = rightCropped;
        this.topCropped = topCropped;
        this.bottomCropped = bottomCropped;
    }
}
