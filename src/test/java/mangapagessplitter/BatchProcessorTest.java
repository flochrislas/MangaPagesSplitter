package mangapagessplitter;

import mangapagessplitter.archive.ExternalTools;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void cancellationDuringPageProcessingDeletesNothingAndLeavesNoOutput() throws IOException {
        Path book = folderWithPages("book", 3);
        Path zip = zipWithPages("other.zip", "001.png");
        BatchOptions o = options();
        o.deleteOriginals = true;
        FakeListener listener = new FakeListener();
        listener.cancelWhenLogContains("Page 001.png");   // first page of the first volume

        BatchResult r = BatchProcessor.run(o, listener);

        assertTrue(r.cancelled);
        assertTrue(r.failures.isEmpty(), "a cancelled job is not an error: " + r.failures);
        assertTrue(r.outputs.isEmpty());
        assertTrue(Files.isRegularFile(book.resolve("003.png")), "folder kept");
        assertTrue(Files.isRegularFile(zip), "archive kept");
        assertFalse(Files.exists(root.resolve("book.cbz")));
        assertFalse(Files.exists(root.resolve("other.cbz")));
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
    void sameNameZipOutputReplacesOwnSourceOnlyAfterVerification() throws IOException {
        Path zip = zipWithPages("book.zip", "b.png", "a.png");
        BatchOptions o = options();
        o.outputFormat = "zip";          // output path == input path
        o.deleteOriginals = true;

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertZipEntries(zip, "001.png", "002.png");   // the verified output now sits at the input path
        assertFalse(Files.exists(root.resolve("book_original.zip")));
        assertNoLeftovers();
    }

    @Test
    void sameNameZipOutputKeepsOwnSourceAsBackupWhenKeepingOriginals() throws IOException {
        Path zip = zipWithPages("book.zip", "b.png", "a.png");
        byte[] before = Files.readAllBytes(zip);
        BatchOptions o = options();
        o.outputFormat = "zip";

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertZipEntries(zip, "001.png", "002.png");
        assertArrayEquals(before, Files.readAllBytes(root.resolve("book_original.zip")), "original bytes preserved");
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

    // ---- publication failure and late cancellation --------------------------------

    @Test
    void failedFinalMoveRestoresTheOriginalFolder() throws IOException {
        Path book = folderWithPages("book", 2);
        byte[] page = Files.readAllBytes(book.resolve("001.png"));
        BatchOptions o = options();
        o.outputFormat = "folder";       // output path == source folder
        o.deleteOriginals = true;
        BatchProcessor.beforePublishHook = () -> { throw new IOException("disk full (injected)"); };
        try {
            BatchResult r = BatchProcessor.run(o, new FakeListener());

            assertFalse(r.isCleanSuccess());
            assertTrue(r.outputs.isEmpty());
            assertArrayEquals(page, Files.readAllBytes(book.resolve("001.png")), "original restored in place");
            assertTrue(Files.isRegularFile(book.resolve("002.png")));
            assertFalse(Files.exists(root.resolve("book_original")), "no stray backup left behind");
            assertNoLeftovers();
        } finally {
            BatchProcessor.beforePublishHook = null;
        }
    }

    @Test
    void failedFinalMoveRestoresTheOriginalArchive() throws IOException {
        Path zip = zipWithPages("book.zip", "001.png");
        byte[] before = Files.readAllBytes(zip);
        BatchOptions o = options();
        o.outputFormat = "zip";          // output path == input path
        o.deleteOriginals = true;
        BatchProcessor.beforePublishHook = () -> { throw new IOException("disk full (injected)"); };
        try {
            BatchResult r = BatchProcessor.run(o, new FakeListener());

            assertFalse(r.isCleanSuccess());
            assertArrayEquals(before, Files.readAllBytes(zip), "original archive restored in place");
            assertFalse(Files.exists(root.resolve("book_original.zip")));
            assertNoLeftovers();
        } finally {
            BatchProcessor.beforePublishHook = null;
        }
    }

    @Test
    void cancellationDuringCleanupStopsDeletingAndIsReported() throws IOException {
        Path a = folderWithPages("a-book", 1);
        Path b = folderWithPages("b-book", 1);
        Path zip = zipWithPages("c-book.zip", "001.png");
        BatchOptions o = options();
        o.deleteOriginals = true;
        FakeListener listener = new FakeListener();
        listener.cancelWhenLogContains("Cleaning up");

        BatchResult r = BatchProcessor.run(o, listener);

        assertTrue(r.cancelled, "cancel during cleanup must be reported");
        assertEquals(3, r.outputs.size(), "outputs published before cleanup stay");
        assertTrue(r.deletedInputs.isEmpty(), "nothing deleted after the cancel: " + r.deletedInputs);
        assertTrue(Files.isDirectory(a) && Files.isDirectory(b) && Files.isRegularFile(zip));
        assertTrue(r.summary().contains("cancelled"), r.summary());
        assertNoLeftovers();
    }

    @Test
    void completedDeletionsAreReported() throws IOException {
        folderWithPages("book", 1);
        zipWithPages("other.zip", "001.png");
        BatchOptions o = options();
        o.deleteOriginals = true;

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertEquals(2, r.deletedInputs.size(), r.deletedInputs.toString());
    }

    // ---- honest reporting -------------------------------------------------------

    @Test
    void cbrFallsBackToCbzUnderTruthfulNameWhenNoRarToolIsInstalled() throws IOException {
        assumeTrue(ExternalTools.findRarCreator() == null, "a RAR tool is installed on this machine");
        folderWithPages("book", 1);
        BatchOptions o = options();
        o.outputFormat = "cbr";

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertEquals(1, r.warnings.size(), "format substitution is reported");
        assertZipEntries(root.resolve("book.cbz"), "001.png");
        assertFalse(Files.exists(root.resolve("book.cbr")), "no ZIP bytes under a .cbr name");
        assertNoLeftovers();
    }

    @Test
    void undecodablePageIsCopiedUnchangedAndReportedAsWarning() throws IOException {
        Path book = folderWithPages("book", 1);
        Files.write(book.resolve("002.png"), "this is not a png".getBytes(StandardCharsets.UTF_8));
        BatchOptions o = options();
        o.splitMode = 2;   // ask for a transformation so the page cannot silently pass through

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertEquals(1, r.warnings.size(), r.warnings.toString());
        assertZipEntries(root.resolve("book.cbz"), "001.png", "002.png", "003.png");
        assertNoLeftovers();
    }

    // ---- page ordering and naming -----------------------------------------------

    @Test
    void pagesAreRenumberedInNaturalOrder() throws IOException {
        Path book = Files.createDirectories(root.resolve("book"));
        Files.write(book.resolve("1.png"), pngBytes(0xff0000));
        Files.write(book.resolve("2.png"), pngBytes(0x00ff00));
        Files.write(book.resolve("10.png"), pngBytes(0x0000ff));
        BatchOptions o = options();

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertZipEntries(root.resolve("book.cbz"), "001.png", "002.png", "003.png");
        assertEquals(0xff0000, colourOfEntry(root.resolve("book.cbz"), "001.png"));
        assertEquals(0x00ff00, colourOfEntry(root.resolve("book.cbz"), "002.png"));
        assertEquals(0x0000ff, colourOfEntry(root.resolve("book.cbz"), "003.png"), "10.png comes last");
    }

    @Test
    void duplicateNamesInSubFoldersNoLongerCollide() throws IOException {
        Path book = root.resolve("book");
        Files.createDirectories(book.resolve("ch1"));
        Files.createDirectories(book.resolve("ch2"));
        Files.write(book.resolve("ch1").resolve("001.png"), pngBytes(0x111111));
        Files.write(book.resolve("ch1").resolve("002.png"), pngBytes(0x222222));
        Files.write(book.resolve("ch2").resolve("001.png"), pngBytes(0x333333));
        BatchOptions o = options();

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertZipEntries(root.resolve("book.cbz"), "001.png", "002.png", "003.png");
        assertEquals(0x333333, colourOfEntry(root.resolve("book.cbz"), "003.png"));
    }

    @Test
    void splitSpreadTakesTwoConsecutiveNumbersInReadingOrder() throws IOException {
        Path book = Files.createDirectories(root.resolve("book"));
        // wide spread: left half green, right half red
        BufferedImage spread = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 40; y++) for (int x = 0; x < 80; x++) spread.setRGB(x, y, x < 40 ? 0x00ff00 : 0xff0000);
        ImageIO.write(spread, "png", book.resolve("page05.png").toFile());
        Files.write(book.resolve("page06.png"), pngBytes(0x0000ff));
        BatchOptions o = options();
        o.splitMode = 0;            // auto: only the wide image is split
        o.isJapaneseManga = true;   // right half first

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertZipEntries(root.resolve("book.cbz"), "001.png", "002.png", "003.png");
        assertEquals(0xff0000, colourOfEntry(root.resolve("book.cbz"), "001.png"), "right half first");
        assertEquals(0x00ff00, colourOfEntry(root.resolve("book.cbz"), "002.png"), "then left half");
        assertEquals(0x0000ff, colourOfEntry(root.resolve("book.cbz"), "003.png"));
    }

    @Test
    void flattenModeProcessesEachFolderOnlyOnce() throws IOException {
        Path series = Files.createDirectories(root.resolve("series"));
        Files.write(series.resolve("cover.png"), pngBytes(0x101010));
        Path chapter = Files.createDirectories(series.resolve("chapter"));
        Files.write(chapter.resolve("page.png"), pngBytes(0x202020));
        BatchOptions o = options();
        o.flattenDirectories = true;

        BatchResult r = BatchProcessor.run(o, new FakeListener());

        assertTrue(r.isCleanSuccess(), r.summary());
        assertEquals(2, r.outputs.size());
        assertZipEntries(root.resolve("series.cbz"), "001.png");
        assertZipEntries(root.resolve("series - chapter.cbz"), "001.png");
        assertEquals(0x101010, colourOfEntry(root.resolve("series.cbz"), "001.png"), "cover only in series.cbz");
    }

    // ---- helpers --------------------------------------------------------------

    private static int colourOfEntry(Path zip, String entry) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            BufferedImage img = ImageIO.read(zf.getInputStream(zf.getEntry(entry)));
            return img.getRGB(1, 1) & 0xffffff;
        }
    }


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
        return pngBytes(0x000000);
    }

    private static byte[] pngBytes(int rgb) throws IOException {
        BufferedImage img = new BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 60; y++) for (int x = 0; x < 40; x++) img.setRGB(x, y, rgb);
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
