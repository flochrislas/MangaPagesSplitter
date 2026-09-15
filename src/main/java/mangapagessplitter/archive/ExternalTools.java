package mangapagessplitter.archive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Discovery and invocation of external archivers (7-Zip, WinRAR, rar) for the
 * formats Java cannot handle natively: RAR5 extraction and any RAR creation.
 */
public final class ExternalTools {

    private ExternalTools() {}

    /** Tries 7-Zip then WinRAR at their usual install paths. Returns true on a zero exit code. */
    public static boolean extractRar(Path archivePath, Path extractDir) {
        System.out.println("Attempting external extraction for: " + archivePath);

        // Try 7-Zip first (most common)
        String[] sevenZipPaths = {
            "C:\\Program Files\\7-Zip\\7z.exe",
            "C:\\Program Files (x86)\\7-Zip\\7z.exe",
            "/usr/bin/7z",
            "/usr/local/bin/7z"
        };

        // Try WinRAR paths
        String[] winRarPaths = {
            "C:\\Program Files\\WinRAR\\WinRAR.exe",
            "C:\\Program Files (x86)\\WinRAR\\WinRAR.exe"
        };

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
                    int exitCode = runProcess(pb);
                    if (exitCode == 0) {
                        System.out.println("Successfully extracted with 7-Zip");
                        return true;
                    }
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
                    int exitCode = runProcess(pb);
                    if (exitCode == 0) {
                        System.out.println("Successfully extracted with WinRAR");
                        return true;
                    }
                } catch (Exception e) {
                    System.err.println("WinRAR extraction failed: " + e.getMessage());
                }
            }
        }

        return false;
    }

    /** Tries WinRAR / rar at their usual install paths. Returns true on a zero exit code. */
    public static boolean createRar(List<Path> imageFiles, File outputFile, Consumer<String> log) {
        // Try WinRAR paths
        String[] winRarPaths = {
            "C:\\Program Files\\WinRAR\\WinRAR.exe",
            "C:\\Program Files (x86)\\WinRAR\\WinRAR.exe",
            "/usr/bin/rar",
            "/usr/local/bin/rar"
        };
        
        for (String winRarPath : winRarPaths) {
            File winRar = new File(winRarPath);
            if (winRar.exists()) {
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

                    int exitCode = runProcess(pb);
                    
                    // Clean up temp file
                    tempListFile.delete();
                    
                    if (exitCode == 0) {
                        log.accept("Successfully created RAR file with " + new File(winRarPath).getName());
                        return true;
                    } else {
                        log.accept("Failed to create RAR file - exit code: " + exitCode);
                    }
                } catch (Exception e) {
                    log.accept("Error creating RAR file: " + e.getMessage());
                }
            }
        }
        
        return false;
    }

    /** Runs the process, drains its merged output, and returns the exit code. */
    static int runProcess(ProcessBuilder pb) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (InputStream is = process.getInputStream()) {
            byte[] buf = new byte[1024];
            while (is.read(buf) != -1) {}
        }
        return process.waitFor();
    }
}
