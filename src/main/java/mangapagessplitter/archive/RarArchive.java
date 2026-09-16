package mangapagessplitter.archive;

import com.github.junrar.Junrar;
import com.github.junrar.exception.RarException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * RAR / CBR support: Junrar for RAR4 and below, external tools for RAR5 and for
 * creating archives.
 */
public final class RarArchive {

    private RarArchive() {}

    /**
     * Extracts {@code archivePath} into {@code extractDir}: Junrar first, then
     * 7-Zip / WinRAR if Junrar rejects the archive (typically RAR5).
     *
     * @throws IOException when neither method could extract the archive, or the run was cancelled
     *                     while an external tool was running.
     */
    public static void extract(Path archivePath, Path extractDir, Consumer<String> log, BooleanSupplier cancelled)
            throws IOException {
        try {
            // Try Junrar first
            Junrar.extract(archivePath.toFile(), extractDir.toFile());
            log.accept("Extracted with Junrar: " + archivePath.getFileName());
        } catch (RarException e) {
            // If Junrar fails (likely due to RAR5 format), try external program
            log.accept("Junrar failed, might be RAR5 format: " + e.getMessage());
            if (!ExternalTools.extractRar(archivePath, extractDir, cancelled)) {
                throw new IOException("Junrar and external extraction both failed: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Creates a RAR with WinRAR / rar. The caller must check {@link ExternalTools#findRarCreator()}
     * first and pick another format when it returns null.
     *
     * @throws IOException when no RAR tool is installed or it did not produce the archive.
     */
    public static void create(List<Path> imageFiles, File outputFile, Consumer<String> log, BooleanSupplier cancelled)
            throws IOException {
        if (ExternalTools.findRarCreator() == null) {
            throw new IOException("no WinRAR / rar executable found to create " + outputFile.getName());
        }
        if (!ExternalTools.createRar(imageFiles, outputFile, log, cancelled)) {
            throw new IOException("the RAR tool failed to create " + outputFile.getName());
        }
    }
}
