package mangapagessplitter.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipArchiveTest {

    @TempDir
    Path tmp;

    @Test
    void createThenExtractRoundTripsFileContents() throws IOException {
        Path a = Files.write(tmp.resolve("001.png"), "first".getBytes(StandardCharsets.UTF_8));
        Path b = Files.write(tmp.resolve("002.png"), "second".getBytes(StandardCharsets.UTF_8));
        Path zip = tmp.resolve("out.cbz");

        ZipArchive.create(Arrays.asList(a, b), zip.toFile());

        Path dest = tmp.resolve("dest");
        ZipArchive.extract(zip.toFile(), dest.toFile());

        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(dest.resolve("001.png")));
        assertArrayEquals("second".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(dest.resolve("002.png")));
    }

    @Test
    void extractRejectsFileEntryOutsideDestination() throws IOException {
        Path zip = tmp.resolve("evil.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../escaped.txt"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path dest = tmp.resolve("dest");
        Files.createDirectories(dest);

        assertThrows(IOException.class, () -> ZipArchive.extract(zip.toFile(), dest.toFile()));
        assertFalse(Files.exists(tmp.resolve("escaped.txt")));
    }

    @Test
    void extractRejectsDirectoryEntryOutsideDestination() throws IOException {
        Path zip = tmp.resolve("evil-dir.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../escaped-directory/"));
            zos.closeEntry();
        }
        Path dest = tmp.resolve("dest");
        Files.createDirectories(dest);

        assertThrows(IOException.class, () -> ZipArchive.extract(zip.toFile(), dest.toFile()));
        assertFalse(Files.exists(tmp.resolve("escaped-directory")));
    }

    @Test
    void extractRejectsSiblingPrefixTrick() throws IOException {
        // "dest" must not accept an entry that resolves to "dest2/..." next to it.
        Path zip = tmp.resolve("prefix.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../dest2/x.txt"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path dest = tmp.resolve("dest");
        Files.createDirectories(dest);

        assertThrows(IOException.class, () -> ZipArchive.extract(zip.toFile(), dest.toFile()));
        assertFalse(Files.exists(tmp.resolve("dest2")));
    }

    @Test
    void createStopsWhenCancelled() throws IOException {
        Path a = Files.write(tmp.resolve("001.png"), "a".getBytes(StandardCharsets.UTF_8));
        Path b = Files.write(tmp.resolve("002.png"), "b".getBytes(StandardCharsets.UTF_8));
        Path zip = tmp.resolve("out.cbz");
        int[] calls = {0};
        // not cancelled for the first entry, cancelled before the second
        assertThrows(IOException.class,
                () -> ZipArchive.create(Arrays.asList(a, b), zip.toFile(), () -> calls[0]++ > 0));
    }

    @Test
    void extractStopsWhenCancelled() throws IOException {
        Path zip = tmp.resolve("two.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String n : new String[]{"001.png", "002.png"}) {
                zos.putNextEntry(new ZipEntry(n));
                zos.write("x".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        Path dest = tmp.resolve("dest");
        Files.createDirectories(dest);
        int[] calls = {0};
        assertThrows(IOException.class, () -> ZipArchive.extract(zip.toFile(), dest.toFile(), () -> calls[0]++ > 0));
        assertFalse(Files.exists(dest.resolve("002.png")), "second entry never written");
    }

    @Test
    void extractCreatesNestedDirectoriesForNestedEntries() throws IOException {
        Path zip = tmp.resolve("nested.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("chapter/001.png"));
            zos.write("p".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path dest = tmp.resolve("dest");
        ZipArchive.extract(zip.toFile(), dest.toFile());
        assertTrue(Files.isRegularFile(dest.resolve("chapter").resolve("001.png")));
    }
}
