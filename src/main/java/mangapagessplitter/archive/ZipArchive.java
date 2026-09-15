package mangapagessplitter.archive;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** ZIP / CBZ reading and writing with java.util.zip. */
public final class ZipArchive {

    private ZipArchive() {}

    /** Extracts every entry of {@code zipFile} under {@code destDir}, rejecting entries that escape it. */
    public static void extract(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buffer = new byte[1024];

            String destRoot = destDir.getCanonicalPath() + File.separator;
            while ((entry = zis.getNextEntry()) != null) {
                File outputFile = new File(destDir, entry.getName());

                // Validate every entry, directories included, before any filesystem write
                // (Zip Slip: "../x", absolute paths, or a prefix trick like "dest2/x").
                if (!outputFile.getCanonicalPath().startsWith(destRoot)) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(outputFile.toPath());
                    continue;
                }

                // Create parent directories if they don't exist
                Files.createDirectories(outputFile.getParentFile().toPath());

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
        }
    }

    /** Writes {@code imageFiles} into a new ZIP at {@code outputFile}, using bare file names as entry names. */
    public static void create(List<Path> imageFiles, File outputFile) throws IOException {
        System.out.println("Creating ZIP/CBZ: " + outputFile);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputFile))) {
            byte[] buffer = new byte[1024];

            for (Path file : imageFiles) {
                ZipEntry entry = new ZipEntry(file.getFileName().toString());
                zos.putNextEntry(entry);

                try (FileInputStream fis = new FileInputStream(file.toFile())) {
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, length);
                    }
                }
                zos.closeEntry();
            }
        }
    }
}
