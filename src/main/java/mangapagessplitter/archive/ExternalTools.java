package mangapagessplitter.archive;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Discovery and invocation of external archivers (7-Zip, WinRAR, rar) for the
 * formats Java cannot handle natively: RAR5 extraction and any RAR creation.
 */
public final class ExternalTools {

    private ExternalTools() {}

    /** Polling interval while waiting for an external process, so cancellation is noticed quickly. */
    private static final long POLL_MILLIS = 200;

    /**
     * Tries 7-Zip then WinRAR at their usual install paths. Returns true on a zero exit code.
     * {@code cancelled} is polled while the tool runs; when it turns true the process is killed.
     */
    public static boolean extractRar(Path archivePath, Path extractDir, BooleanSupplier cancelled) {
        System.out.println("Attempting external extraction for: " + archivePath);

        // Try 7-Zip first (most common)
        List<String> sevenZipPaths = List.of(
            "C:\\Program Files\\7-Zip\\7z.exe",
            "C:\\Program Files (x86)\\7-Zip\\7z.exe",
            "/usr/bin/7z",
            "/usr/local/bin/7z"
        );

        // Try WinRAR paths
        List<String> winRarPaths = List.of(
            "C:\\Program Files\\WinRAR\\WinRAR.exe",
            "C:\\Program Files (x86)\\WinRAR\\WinRAR.exe"
        );

        // Try 7-Zip
        for (String path : sevenZipPaths) {
            File sevenZip = new File(path);
            if (sevenZip.exists()) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        path, "x", "-y",
                        archivePath.toString(),
                        "-o" + extractDir.toString()
                    );
                    int exitCode = runProcess(pb, cancelled);
                    if (exitCode == 0) {
                        System.out.println("Successfully extracted with 7-Zip");
                        return true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception e) {
                    System.err.println("7-Zip extraction failed: " + e.getMessage());
                }
            }
        }

        // Try WinRAR
        for (String path : winRarPaths) {
            File winRar = new File(path);
            if (winRar.exists()) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        path, "x", archivePath.toString(), extractDir.toString()
                    );
                    int exitCode = runProcess(pb, cancelled);
                    if (exitCode == 0) {
                        System.out.println("Successfully extracted with WinRAR");
                        return true;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception e) {
                    System.err.println("WinRAR extraction failed: " + e.getMessage());
                }
            }
        }

        return false;
    }

    private static final List<String> RAR_CREATOR_PATHS = List.of(
        "C:\\Program Files\\WinRAR\\WinRAR.exe",
        "C:\\Program Files (x86)\\WinRAR\\WinRAR.exe",
        "/usr/bin/rar",
        "/usr/local/bin/rar"
    );

    /** Path of an installed WinRAR / rar executable able to create archives, or null if none is found. */
    public static String findRarCreator() {
        for (String path : RAR_CREATOR_PATHS) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    /**
     * Creates a RAR with WinRAR / rar. Returns true on a zero exit code, false if no tool
     * exists or it failed. The process is killed when {@code cancelled} turns true.
     */
    public static boolean createRar(List<Path> imageFiles, File outputFile, Consumer<String> log,
                                    BooleanSupplier cancelled) {
        String winRarPath = findRarCreator();
        if (winRarPath != null) {
            {
                try {
                    // Create a temporary file with list of files to add
                    File tempListFile = File.createTempFile("rarlist", ".txt");
                    try (PrintWriter writer = new PrintWriter(tempListFile)) {
                        for (Path file : imageFiles) {
                            writer.println(file.toAbsolutePath());
                        }
                    }
                    
                    // Build command for WinRAR
                    // WinRAR a -ep output.rar @filelist.txt
                    ProcessBuilder pb = new ProcessBuilder(
                        winRarPath, "a", "-ep", outputFile.getAbsolutePath(), "@" + tempListFile.getAbsolutePath()
                    );

                    int exitCode;
                    try {
                        exitCode = runProcess(pb, cancelled);
                    } finally {
                        tempListFile.delete();
                    }

                    if (exitCode == 0) {
                        log.accept("Successfully created RAR file with " + new File(winRarPath).getName());
                        return true;
                    } else {
                        log.accept("Failed to create RAR file - exit code: " + exitCode);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception e) {
                    log.accept("Error creating RAR file: " + e.getMessage());
                }
            }
        }
        
        return false;
    }

    /**
     * Runs the process and returns its exit code. Its output is discarded so the tool
     * cannot block on a full pipe. While waiting, {@code cancelled} is polled every
     * {@link #POLL_MILLIS}; when it turns true (or this thread is interrupted) the child
     * is killed and an {@link IOException} is thrown.
     */
    static int runProcess(ProcessBuilder pb, BooleanSupplier cancelled) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        try {
            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new IOException("cancelled: external tool was stopped");
                }
            }
            return process.exitValue();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
    }
}
