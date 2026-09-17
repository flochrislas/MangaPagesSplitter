package mangapagessplitter.image;

import java.awt.image.BufferedImage;

/**
 * Outcome of {@link AutoCrop#apply}.
 *
 * @param image         the (possibly cropped) image
 * @param splitX        detected spine column in cropped-image coordinates, or -1
 * @param applied       whether any crop was applied
 * @param leftCropped   pixels removed on the left
 * @param rightCropped  pixels removed on the right
 * @param topCropped    pixels removed at the top
 * @param bottomCropped pixels removed at the bottom
 */
public record AutoCropResult(BufferedImage image, int splitX, boolean applied,
                             int leftCropped, int rightCropped, int topCropped, int bottomCropped) {
}
