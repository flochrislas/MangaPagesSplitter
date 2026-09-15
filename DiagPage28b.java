import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import javax.imageio.ImageIO;

public class DiagPage28b {
    public static void main(String[] args) throws Exception {
        String path = "N:\\Manga\\Japanese\\Dragon Quest Aban weird\\test\\DLRAW.TO_Doragon Kuesuto dai no v05\\DLRAW.TO_Net_0028.jpg";
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

        // Find the actual left / right content edges (columns with >minPix content in a run of >5)
        System.out.println("--- Scan every 10 cols across full width, mark ' * ' if content>=11 ---");
        for (int x = 0; x < W; x += 10) {
            int c = (Integer) ccc.invoke(null, pixels, W, x, 0, H, rowStep, bgMean, delta);
            String tag = (c >= 11) ? " *" : "  ";
            if (c > 5 || (x % 100 == 0)) {
                System.out.printf("  x=%4d content=%4d %s%n", x, c, tag);
            }
        }
    }
}
