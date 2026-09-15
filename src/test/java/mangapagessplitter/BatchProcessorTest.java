package mangapagessplitter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File-safety tests for the batch engine. Every case runs against a throwaway
 * directory with tiny generated PNGs, in "no split" mode so only discovery,
 * extraction, publication and cleanup are exercised.
 */
class BatchProcessorTest {

    @TempDir
    Path root;

    // ---- happy paths ----------------------------------------------------------

    @Test
    void folderIsPackagedIntoCbzAndKeptWhenKeepingOriginals() throws IOException {
        Path book = folderWithPages("book", 2);
        BatchOptions o = options();

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertEquals(1, r.outputs.size());
        assertZipEntries(root.resolve("book.cbz"), "001.png", "002.png");
        assertTrue(Files.isRegularFile(book.resolve("001.png")), "original page kept");
        assertNoLeftovers();
    }

    @Test
    void folderIsDeletedOnlyAfterCbzIsPublished() throws IOException {
        Path book = folderWithPages("book", 2);
        BatchOptions o = options();
        o.deleteOriginals = true;

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertZipEntries(root.resolve("book.cbz"), "001.png", "002.png");
        assertFalse(Files.exists(book), "source folder removed after publication");
        assertNoLeftovers();
    }

    @Test
    void archiveIsExtractedProcessedAndDeletedOnlyOnSuccess() throws IOException {
        Path zip = zipWithPages("book.zip", "001.png", "002.png");
        BatchOptions o = options();
        o.deleteOriginals = true;

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertZipEntries(root.resolve("book.cbz"), "001.png", "002.png");
        assertFalse(Files.exists(zip), "original archive removed after publication");
        assertFalse(Files.exists(root.resolve("book")), "no extraction folder left next to the archive");
        assertNoLeftovers();
    }

    @Test
    void newFolderOutputNameIsPublishedWithoutTempLeftovers() throws IOException {
        Path book = folderWithPages("book", 1);
        BatchOptions o = options();
        o.outputFormat = "folder";
        o.useCustomTitle = true;
        o.customTitle = "Renamed";

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertTrue(Files.isRegularFile(root.resolve("Renamed").resolve("001.png")));
        assertTrue(Files.isRegularFile(book.resolve("001.png")), "source kept");
        assertNoLeftovers();
    }

    @Test
    void sameNameFolderOutputKeepsOriginalAsBackup() throws IOException {
        folderWithPages("book", 1);
        BatchOptions o = options();
        o.outputFormat = "folder";

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertTrue(Files.isRegularFile(root.resolve("book").resolve("001.png")), "output in place");
        assertTrue(Files.isRegularFile(root.resolve("book_original").resolve("001.png")), "original preserved");
        assertNoLeftovers();
    }

    // ---- data loss cases reproduced from the Codex review ---------------------------

    @Test
    void archiveThatFailsToExtractIsNeverDeleted() throws IOException {
        Path evil = root.resolve("evil.zip");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("../escaped.png", pngBytes());
        writeZip(evil, entries);
        byte[] before = Files.readAllBytes(evil);
        BatchOptions o = options();
        o.deleteOriginals = true;

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertFalse(r.isCleanSuccess());
        assertTrue(r.outputs.isEmpty());
        assertArrayEquals(before, Files.readAllBytes(evil), "failed archive untouched");
        assertFalse(Files.exists(root.getParent().resolve("escaped.png")));
        assertNoLeftovers();
    }

    @Test
    void cancellationAfterExtractionKeepsOriginalArchive() throws IOException {
        Path zip = zipWithPages("book.zip", "001.png");
        BatchOptions o = options();
        o.deleteOriginals = true;
        FakeListener listener = new FakeListener();
        listener.cancelWhenLogContains("Processing 1 folder");

        BatchResult r = BatchProcessor.run(o, listener);

        assertTrue(r.cancelled);
        assertTrue(Files.isRegularFile(zip), "cancelled run must not delete inputs");
        assertFalse(Files.exists(root.resolve("book.cbz")));
        assertNoLeftovers();
    }

    @Test
    void existingFolderNamedLikeAnArchiveIsNotOverwrittenOrDeleted() throws IOException {
        Path book = folderWithPages("book", 1);
        Files.write(book.resolve("notes.txt"), "keep me".getBytes(StandardCharsets.UTF_8));
        byte[] originalPage = Files.readAllBytes(book.resolve("001.png"));
        zipWithPages("book.zip", "001.png", "002.png");   // different content, same basename
        BatchOptions o = options();

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertTrue(Files.isRegularFile(book.resolve("notes.txt")), "unrelated file survived");
        assertArrayEquals(originalPage, Files.readAllBytes(book.resolve("001.png")), "folder page not overwritten by extraction");
        assertEquals(2, r.outputs.size(), "both the folder and the archive produced an output");
        assertTrue(Files.isRegularFile(root.resolve("book.cbz")));
        assertTrue(Files.isRegularFile(root.resolve("book (2).cbz")), "colliding output name disambiguated");
        assertNoLeftovers();
    }

    @Test
    void dotDotCustomTitleIsRejectedWithoutDeletingAnything() throws IOException {
        Path parent = root.getParent();
        Path sibling = parent.resolve("unrelated-" + root.getFileName() + ".txt");
        Files.write(sibling, "x".getBytes(StandardCharsets.UTF_8));
        try {
            Path book = folderWithPages("book", 1);
            BatchOptions o = options();
            o.outputFormat = "folder";
            o.deleteOriginals = true;
            o.useCustomTitle = true;
            o.customTitle = "..";

            BatchResult r = BatchProcessor.run(o, new FakeListener());

            assertFalse(r.isCleanSuccess());
            assertTrue(r.outputs.isEmpty());
            assertTrue(Files.isRegularFile(sibling), "file outside the root untouched");
            assertTrue(Files.isRegularFile(book.resolve("001.png")), "source kept because the job failed");
            assertNoLeftovers();
        } finally {
            Files.deleteIfExists(sibling);
        }
    }

    @Test
    void failedArchiveCreationLeavesOriginalArchiveIntact() throws IOException {
        // Two pages with the same file name in different sub-folders: the flat ZIP
        // writer throws a duplicate-entry error half way through.
        Path zip = root.resolve("book.zip");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a/001.png", pngBytes());
        entries.put("b/001.png", pngBytes());
        writeZip(zip, entries);
        byte[] before = Files.readAllBytes(zip);
        BatchOptions o = options();
        o.outputFormat = "zip";          // output path == input path
        o.deleteOriginals = true;

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertFalse(r.isCleanSuccess());
        assertArrayEquals(before, Files.readAllBytes(zip), "original archive never truncated");
        assertNoLeftovers();
    }

    @Test
    void destinationThatIsAnotherInputFolderIsRefusedNotMoved() throws IOException {
        Path book = folderWithPages("book", 1);
        Path other = Files.createDirectories(root.resolve("Other"));
        Files.write(other.resolve("keep.txt"), "x".getBytes(StandardCharsets.UTF_8));
        BatchOptions o = options();
        o.outputFormat = "folder";
        o.deleteOriginals = true;
        o.useCustomTitle = true;
        o.customTitle = "Other";

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertFalse(r.isCleanSuccess(), "publishing over another input must fail");
        assertTrue(Files.isRegularFile(other.resolve("keep.txt")), "other folder untouched");
        assertFalse(Files.exists(root.resolve("Other_original")), "other folder not moved aside");
        assertTrue(Files.isRegularFile(book.resolve("001.png")), "source kept because the job failed");
        assertNoLeftovers();
    }

    @Test
    void existingUnrelatedFileAtDestinationIsBackedUpNotOverwritten() throws IOException {
        folderWithPages("book", 1);
        Path stale = root.resolve("book.cbz");
        Files.write(stale, "not a real archive".getBytes(StandardCharsets.UTF_8));
        BatchOptions o = options();   // cbz output, keep originals

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertZipEntries(root.resolve("book.cbz"), "001.png");
        assertArrayEquals("not a real archive".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(root.resolve("book_original.cbz")), "previous file preserved as backup");
        assertNoLeftovers();
    }

    @Test
    void sameBasenameArchivesBothProduceOutputs() throws IOException {
        zipWithPages("book.zip", "001.png");
        zipWithPages("book.cbz", "001.png", "002.png");
        BatchOptions o = options();
        o.outputFormat = "folder";

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertEquals(2, r.outputs.size());
        assertTrue(Files.isDirectory(root.resolve("book")));
        assertTrue(Files.isDirectory(root.resolve("book (2)")));
        assertNoLeftovers();
    }

    // ---- helpers --------------------------------------------------------------

    private BatchOptions options() {
        BatchOptions o = new BatchOptions();
        o.rootFolder = root.toString();
        o.splitMode = 1;              // no split: keeps the tests about files, not pixels
        o.outputFormat = "cbz";
        return o;
    }

    private Path folderWithPages(String name, int pages) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        for (int i = 1; i <= pages; i++) writePng(dir.resolve(String.format("%03d.png", i)));
        return dir;
    }

    private Path zipWithPages(String name, String... entryNames) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String e : entryNames) entries.put(e, pngBytes());
        Path zip = root.resolve(name);
        writeZip(zip, entries);
        return zip;
    }

    private static void writeZip(Path zip, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }

    private static void writePng(Path file) throws IOException {
        Files.write(file, pngBytes());
    }

    private static byte[] pngBytes() throws IOException {
        BufferedImage img = new BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static void assertZipEntries(Path zip, String... expected) throws IOException {
        assertTrue(Files.isRegularFile(zip), "missing " + zip);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            List<String> names = zf.stream().map(ZipEntry::getName).sorted().collect(Collectors.toList());
            List<String> exp = new ArrayList<>();
            for (String e : expected) exp.add(e);
            exp.sort(null);
            assertEquals(exp, names);
        }
    }

    /** No workspace, staging or "_temp" artefacts may survive a run, whatever its outcome. */
    private void assertNoLeftovers() throws IOException {
        try (Stream<Path> s = Files.list(root)) {
            List<String> bad = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(".") || n.endsWith("_temp") || n.endsWith(".part"))
                    .collect(Collectors.toList());
            assertTrue(bad.isEmpty(), "leftovers in root: " + bad);
        }
    }

    /** Listener that can flip to cancelled when a given log line appears. */
    static final class FakeListener implements ProcessingListener {
        private volatile boolean cancelled;
        private String cancelTrigger;
        final List<String> log = new ArrayList<>();

        void cancelWhenLogContains(String fragment) { this.cancelTrigger = fragment; }

        @Override public boolean isCancelled() { return cancelled; }

        @Override public void log(String message) {
            log.add(message);
            if (cancelTrigger != null && message.contains(cancelTrigger)) cancelled = true;
        }

        @Override public void progress(String status, int percentage) {}
    }
}
