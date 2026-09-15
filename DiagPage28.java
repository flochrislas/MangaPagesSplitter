import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import javax.imageio.ImageIO;

public class DiagPage28 {
    public static void main(String[] args) throws Exception {
        String path = "test_images\\0028.jpg";
        BufferedImage img = ImageIO.read(new File(path));
        int W = img.getWidth(), H = img.getHeight();

        Class<?> cls = Class.forName("MangaPagesSplitter");
        Method ccc = cls.getDeclaredMethod("columnContentCount",
                int[].class, int.class, int.class, int.class, int.class, int.class, int.class, int.class);
        ccc.setAccessible(true);

        int[] pixels = new int[W*H];
        img.getRGB(0, 0, W, H, pixels, 0, W);

        int bgMean = 255, delta = 18;
        int rowStep = Math.max(1, H / 800);

        // Sample EVERY individual column at the right edge
        System.out.println("--- Right edge column content (last 30 cols, every col) ---");
        for (int x = W - 30; x < W; x++) {
            int c = (Integer) ccc.invoke(null, pixels, W, x, 0, H, rowStep, bgMean, delta);
            System.out.printf("  x=%4d  content=%3d%n", x, c);
        }
        // What actually is at x=3839? Sample a few pixels down that column
        System.out.println("--- Individual pixels in column x=3839 (every 100 rows) ---");
        for (int y = 0; y < H; y += 100) {
            int argb = pixels[y * W + (W-1)];
            int r = (argb >> 16) & 0xff, g = (argb >> 8) & 0xff, b = argb & 0xff;
            System.out.printf("  y=%4d  rgb=(%3d,%3d,%3d)  lum=%d%n", y, r, g, b, (r*299+g*587+b*114)/1000);
        }
    }
}

