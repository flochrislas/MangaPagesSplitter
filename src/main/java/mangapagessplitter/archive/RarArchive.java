package mangapagessplitter.archive;

import com.github.junrar.Junrar;
import com.github.junrar.exception.RarException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * RAR / CBR support: Junrar for RAR4 and below, external tools for RAR5 and for
 * creating archives. Falls back to ZIP content when no RAR creator is installed.
 */
public final class RarArchive {

    private RarArchive() {}

    /**
     * Extracts {@code archivePath} into {@code extractDir}: Junrar first, then
     * 7-Zip / WinRAR if Junrar rejects the archive (typically RAR5).
     *
     * @throws IOException when neither method could extract the archive.
     */
    public static void extract(Path archivePath, Path extractDir, Consumer<String> log) throws IOException {
        try {
            // Try Junrar first
            Junrar.extract(archivePath.toFile(), extractDir.toFile());
            log.accept("Extracted with Junrar: " + archivePath.getFileName());
        } catch (RarException e) {
            // If Junrar fails (likely due to RAR5 format), try external program
            log.accept("Junrar failed, might be RAR5 format: " + e.getMessage());
            if (!ExternalTools.extractRar(archivePath, extractDir)) {
                throw new IOException("Junrar and external extraction both failed: " + e.getMessage(), e);
            }
        }
    }

    /** Creates a RAR with an external tool, or a ZIP under the requested name when none is available. */
    public static void create(List<Path> imageFiles, File outputFile, Consumer<String> log) throws IOException {
        System.out.println("Creating RAR/CBR: " + outputFile);

        // Since Java doesn't have built-in RAR creation, try to use external tools
        boolean success = ExternalTools.createRar(imageFiles, outputFile, log);
        
        if (!success) {
            // Fallback - create ZIP instead but rename it to the requested extension
            log.accept("WARNING: Could not create RAR/CBR file. No RAR program found. Creating ZIP instead.");
            
            // Create temporary zip file
            File tempZip = new File(outputFile.getParentFile(), outputFile.getName() + ".zip.tmp");
            ZipArchive.create(imageFiles, tempZip);
            
            // Rename to requested extension
            if (tempZip.exists()) {
                if (outputFile.exists()) {
                    outputFile.delete();
                }
                tempZip.renameTo(outputFile);
            }
        }
    }
}
