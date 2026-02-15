import com.github.junrar.Junrar;
import com.github.junrar.exception.RarException;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.*;

public class MangaPagesSplitter {

    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"};
    private static final String[] ARCHIVE_EXTENSIONS = {".rar", ".zip", ".cbr", ".cbz"};
    
    private static MangaPagesSplitterUI ui = null;

    private static class ArchiveExtractionResult {
        public final List<Path> archivePaths;
        public final List<Path> extractedFolders;
        
        public ArchiveExtractionResult(List<Path> archivePaths, List<Path> extractedFolders) {
            this.archivePaths = archivePaths;
            this.extractedFolders = extractedFolders;
        }
    }

    public static void main(String[] args) {
        try {
            boolean darkTheme = java.util.prefs.Preferences.userRoot()
                    .node("MangaPagesSplitter").getBoolean("darkTheme", true);
            if (darkTheme) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> new MangaPagesSplitterUI().setVisible(true));
    }
    
    // Method to be called from the UI - pass the UI instance instead of worker
    // Updated method signature to include outputFormat
    public static void processWithUI(
            String rootFolder, int splitMode, boolean isJapaneseManga, boolean deleteOriginals,
            int skipImagesFromStart, int skipImagesFromEnd, boolean rotateWideImages, 
            String outputFormat, int cropLeft, int cropRight, int cropTop, int cropBottom,
            MangaPagesSplitterUI uiInstance) throws IOException {
        
        ui = uiInstance;
        
        try {
            logMessage("Starting extraction of archives...");
            // Step 1: Extract all archives and collect paths
            ArchiveExtractionResult extractionResult = extractAllArchives(rootFolder);
            List<Path> originalArchives = extractionResult.archivePaths;
            List<Path> extractedFolders = extractionResult.extractedFolders;
            
            logMessage("Extracted " + originalArchives.size() + " archives.");
            
            // Count total folders to process
            long folderCount;
            try (Stream<Path> folderStream = Files.list(Paths.get(rootFolder))) {
                folderCount = folderStream
                               .filter(Files::isDirectory)
                               .count();
            } catch (IOException e) {
                folderCount = 0;
                logMessage("Error counting folders: " + e.getMessage());
            }
            
            // Step 2: Process each folder and track new output files
            List<Path> newlyCreatedOutputFiles = new ArrayList<>();
            final long totalFolders = folderCount;
            final int[] processedFolders = {0};
            
            logMessage("Processing " + totalFolders + " folders...");
            
            // Keep track of folders to delete after processing
            List<Path> foldersToDelete = new ArrayList<>();
            
            List<Path> folders;
            try (Stream<Path> folderListStream = Files.list(Paths.get(rootFolder))) {
                folders = folderListStream.filter(Files::isDirectory).collect(Collectors.toList());
            }

            for (Path folder : folders) {
                // Check if the processing should be cancelled
                if (Thread.currentThread().isInterrupted() || (ui != null && ui.isCancelled())) {
                    logMessage("Processing cancelled by user.");
                    break;
                }

                try {
                    logMessage("Processing folder: " + folder.getFileName());

                    // Update progress
                    if (totalFolders > 0) {
                        updateProgress("Processing folder " + (processedFolders[0] + 1) + "/" + totalFolders,
                                      (int)((processedFolders[0] * 100) / totalFolders));
                    }

                    // Pass crop parameters to processFolderAndCreateOutput
                    Path newOutputPath = processFolderAndCreateOutput(folder, Paths.get(rootFolder), splitMode,
                                                          isJapaneseManga, deleteOriginals,
                                                          skipImagesFromStart, skipImagesFromEnd,
                                                          rotateWideImages, outputFormat,
                                                          cropLeft, cropRight, cropTop, cropBottom,
                                                          extractedFolders);
                    if (newOutputPath != null) {
                        newlyCreatedOutputFiles.add(newOutputPath);
                        logMessage("Created: " + newOutputPath.getFileName());

                        // Add folder to cleanup list if output is not "folder"
                        // Only clean up extracted archive folders (intermediate) or if user wants originals deleted
                        if (!outputFormat.equals("folder") && (extractedFolders.contains(folder) || deleteOriginals)) {
                            foldersToDelete.add(folder);
                        }
                    }

                    processedFolders[0]++;

                } catch (IOException e) {
                    logMessage("Error processing folder: " + folder + " - " + e.getMessage());
                }
            }
            
            updateProgress("Cleaning up...", 90);
                    
            // Delete original archives if needed, but exclude newly created archive files
            if (deleteOriginals) {
                logMessage("Deleting original archives...");
                
                // Make a copy of the original archives list and remove any newly created output files
                List<Path> archivesToDelete = new ArrayList<>(originalArchives);
                archivesToDelete.removeAll(newlyCreatedOutputFiles);
                
                for (Path archive : archivesToDelete) {
                    try {
                        Files.delete(archive);
                        logMessage("Deleted original archive: " + archive.getFileName());
                    } catch (IOException e) {
                        logMessage("Error deleting archive: " + archive + " - " + e.getMessage());
                    }
                }
            }
            
            // Clean up intermediate folders
            logMessage("Cleaning up intermediate folders used to create archives...");
            for (Path folder : foldersToDelete) {
                try {
                    deleteDirectory(folder);
                } catch (IOException e) {
                    logMessage("Error cleaning up intermediate folder: " + folder + " - " + e.getMessage());
                }
            }            

            updateProgress("Complete", 100);
            String outputType = outputFormat.equals("folder") ? "folders" : outputFormat.toUpperCase() + " files";
            logMessage("Created " + newlyCreatedOutputFiles.size() + " " + outputType);
            
        } catch (IOException e) {
            logMessage("Error during processing: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    private static void logMessage(String message) {
        System.out.println(message);
        if (ui != null) {
            SwingUtilities.invokeLater(() -> ui.publishLogMessage(message));
        }
    }
    
    private static void updateProgress(String status, int percentage) {
        if (ui != null) {
            SwingUtilities.invokeLater(() -> ui.updateProgress(status, percentage));
        }
    }

    private static ArchiveExtractionResult extractAllArchives(String rootFolder) throws IOException {
        logMessage("Extracting archives in: " + rootFolder);
        List<Path> archivePaths = new ArrayList<>();
        List<Path> extractedFolders = new ArrayList<>();

        // Count total archives
        long archiveCount;
        try (Stream<Path> archiveCountStream = Files.list(Paths.get(rootFolder))) {
            archiveCount = archiveCountStream
                            .filter(Files::isRegularFile)
                            .filter(path -> isArchiveFile(path.toString()))
                            .count();
        } catch (IOException e) {
            archiveCount = 0;
            logMessage("Error counting archives: " + e.getMessage());
        }

        final long totalArchives = archiveCount;
        final int[] processedArchives = {0};
        
        if (totalArchives > 0) {
            logMessage("Found " + totalArchives + " archives to extract");
        }

        List<Path> archives;
        try (Stream<Path> archiveListStream = Files.list(Paths.get(rootFolder))) {
            archives = archiveListStream
                .filter(Files::isRegularFile)
                .filter(path -> isArchiveFile(path.toString()))
                .collect(Collectors.toList());
        }

        for (Path archivePath : archives) {
            // Check if processing was cancelled
            if (Thread.currentThread().isInterrupted() || (ui != null && ui.isCancelled())) {
                return new ArchiveExtractionResult(archivePaths, extractedFolders);
            }
            
            archivePaths.add(archivePath); // Store the path for deletion later
            String baseName = archivePath.getFileName().toString();
            baseName = baseName.substring(0, baseName.lastIndexOf('.'));
            Path extractDir = Paths.get(rootFolder, baseName);
            try {
                Files.createDirectories(extractDir);

                // Update progress
                if (totalArchives > 0) {
                    int percentage = (int)((processedArchives[0] * 100) / totalArchives);
                    updateProgress("Extracting archive " + (processedArchives[0] + 1) + "/" + totalArchives, percentage);
                }

                if (archivePath.toString().toLowerCase().endsWith(".rar") ||
                    archivePath.toString().toLowerCase().endsWith(".cbr")) {
                    try {
                        // Try Junrar first
                        Junrar.extract(archivePath.toFile(), extractDir.toFile());
                        logMessage("Extracted with Junrar: " + archivePath.getFileName());
                    } catch (RarException e) {
                        // If Junrar fails (likely due to RAR5 format), try external program
                        logMessage("Junrar failed, might be RAR5 format: " + e.getMessage());
                        if (!extractWithExternalProgram(archivePath, extractDir)) {
                            logMessage("Both Junrar and external extraction failed for: " + archivePath);
                        }
                    }
                } else {
                    // Extract ZIP
                    extractZip(archivePath.toFile(), extractDir.toFile());
                    logMessage("Extracted: " + archivePath.getFileName());
                }
                extractedFolders.add(extractDir); // Track that this folder came from an archive
                processedArchives[0]++;
            } catch (IOException e) {
                logMessage("Error extracting archive: " + archivePath + " - " + e.getMessage());
            }
        }

        return new ArchiveExtractionResult(archivePaths, extractedFolders);
    }

    private static void extractZip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buffer = new byte[1024];

            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    File newDir = new File(destDir, entry.getName());
                    Files.createDirectories(newDir.toPath());
                    continue;
                }

                File outputFile = new File(destDir, entry.getName());

                // Validate path to prevent Zip Slip vulnerability
                if (!outputFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
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

    // Renamed method to reflect that it handles different output formats now
    // Updated method signature for processFolderAndCreateOutput
    private static Path processFolderAndCreateOutput(Path folder, Path rootFolder, int splitMode, boolean isJapaneseManga, 
                                                boolean deleteOriginals, int skipImagesFromStart, int skipImagesFromEnd,
                                                boolean rotateWideImages, String outputFormat,
                                                int cropLeft, int cropRight, int cropTop, int cropBottom,
                                                List<Path> extractedFolders) throws IOException {
        logMessage("Processing folder: " + folder);

        List<Path> imagePaths;
        List<Path> processedFiles = new ArrayList<>();

        // Find all image files (sorted for deterministic skip-from-start/end behavior)
        try (Stream<Path> walk = Files.walk(folder)) {
            imagePaths = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> isImageFile(path.toString()))
                    .sorted()
                    .collect(Collectors.toList());
        }

        if (imagePaths.isEmpty()) {
            logMessage("No images found in: " + folder);
            return null;
        }

        int totalImages = imagePaths.size();
        logMessage("Found " + totalImages + " images in " + folder.getFileName());

        // Calculate which images to actually process with exceptions
        int firstImageToProcess = Math.min(skipImagesFromStart, totalImages);
        int lastImageToProcess = Math.max(0, totalImages - skipImagesFromEnd);
        int latestSinglePageImageIndex = -99;

        // Create temp directory for processed images to avoid modifying originals
        Path tempDir = Files.createTempDirectory("manga_processing_");
        try { // Wrapped in try-finally to ensure temp directory cleanup

        // Process each image
        for (int i = 0; i < imagePaths.size(); i++) {
            // Check if processing was cancelled
            if (Thread.currentThread().isInterrupted() || (ui != null && ui.isCancelled())) {
                return null;
            }

            // Update progress (for image processing within the current folder)
            if (totalImages > 0) {
                updateProgress("Processing image " + (i + 1) + "/" + totalImages +
                               " in " + folder.getFileName(), (int)((i * 100) / totalImages));
            }

            Path imagePath = imagePaths.get(i);
            boolean shouldSplit = false;
            boolean isWideImage = false;
            boolean modified = false;

            // Check if this image should be skipped based on position
            boolean isExceptionImage = (i < firstImageToProcess) || (i >= lastImageToProcess);

            // Read image once and process entirely in memory
            try {
                BufferedImage img = ImageIO.read(imagePath.toFile());
                if (img != null) {
                    // Apply cropping in memory
                    if (cropLeft > 0 || cropRight > 0 || cropTop > 0 || cropBottom > 0) {
                        img = cropImage(img, cropLeft, cropRight, cropTop, cropBottom);
                        modified = true;

                        // Log only the first few cropped images to avoid flooding the log
                        if (i < 3) {
                            logMessage("Cropped image: " + imagePath.getFileName());
                        } else if (i == 3) {
                            logMessage("Cropping remaining images...");
                        }
                    }

                    int width = img.getWidth();
                    int height = img.getHeight();
                    isWideImage = width > height;

                    // Determine if this image should be split based on mode, dimensions, and exceptions
                    if (splitMode == 2 && !isExceptionImage) {
                        shouldSplit = true;
                    }
                    else if (splitMode == 0 && !isExceptionImage) {
                        // Try to preserve special double-page spreads that can exist within otherwise single page images
                        // Here we consider such special spread to be a wide image that is within 3 pages of the latest single page image
                        boolean isSpecialSpread = (i - latestSinglePageImageIndex < 3);
                        shouldSplit = isWideImage && !isSpecialSpread;
                        // Set the latest page index for single page images
                        if (!isWideImage) {
                            latestSinglePageImageIndex = i;
                        }
                        if (shouldSplit) {
                            logMessage("Auto-detected double page for: " + imagePath.getFileName());
                        }
                    }

                    // Rotate in memory if requested and not splitting
                    if (rotateWideImages && isWideImage && !shouldSplit) {
                        img = rotateImage(img);
                        modified = true;
                        logMessage("Rotated wide image: " + imagePath.getFileName());
                    }

                    if (isExceptionImage && (splitMode == 0 || splitMode == 2)) {
                        logMessage("Skipping split for exception image: " + imagePath.getFileName());
                    }

                    if (shouldSplit) {
                        BufferedImage[] halves = splitImage(img, isJapaneseManga);
                        logMessage("Split image (" + (isJapaneseManga ? "right to left" : "left to right") + "): " + imagePath.getFileName());
                        String baseName = imagePath.getFileName().toString();
                        baseName = baseName.substring(0, baseName.lastIndexOf('.'));
                        String ext = imagePath.toString().substring(imagePath.toString().lastIndexOf('.') + 1);

                        Path firstPage = tempDir.resolve(baseName + "_1." + ext);
                        Path secondPage = tempDir.resolve(baseName + "_2." + ext);

                        if (!ImageIO.write(halves[0], ext, firstPage.toFile())) {
                            logMessage("Warning: failed to write image: " + firstPage.getFileName());
                        }
                        if (!ImageIO.write(halves[1], ext, secondPage.toFile())) {
                            logMessage("Warning: failed to write image: " + secondPage.getFileName());
                        }

                        processedFiles.add(firstPage);
                        processedFiles.add(secondPage);
                    } else if (modified) {
                        String ext = imagePath.toString().substring(imagePath.toString().lastIndexOf('.') + 1);
                        Path tempFile = tempDir.resolve(imagePath.getFileName());
                        if (!ImageIO.write(img, ext, tempFile.toFile())) {
                            logMessage("Warning: failed to write image: " + tempFile.getFileName());
                        }
                        processedFiles.add(tempFile);
                    } else {
                        processedFiles.add(imagePath);
                    }
                } else {
                    processedFiles.add(imagePath);
                }
            } catch (IOException e) {
                logMessage("Error processing image: " + imagePath + " - " + e.getMessage());
                processedFiles.add(imagePath);
            }
        }

        if (!processedFiles.isEmpty()) {
            // Create output based on selected format
            String folderName = folder.getFileName().toString();
            Path finalPath;
            
            // Create the appropriate output based on format
            if (outputFormat.equals("folder")) {
                // For folder format, use the original folder name without any suffix
                finalPath = rootFolder.resolve(folderName);
                
                // Create a temporary folder to hold the processed files
                Path tempFolder = rootFolder.resolve(folderName + "_temp");
                Files.createDirectories(tempFolder);
                
                // Copy all processed files to the temporary folder
                for (Path file : processedFiles) {
                    Path targetFile = tempFolder.resolve(file.getFileName());
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
                
                try {
                    // Check if the folder was created from an archive
                    boolean wasExtractedFromArchive = extractedFolders.contains(folder);
                    
                    if (deleteOriginals) {
                        // Delete the folder if deleteOriginals is true
                        deleteDirectory(folder);
                        logMessage("Deleted " + (wasExtractedFromArchive ? "extracted " : "") + "folder: " + folder.getFileName());
                    } else if (Files.isSameFile(folder, finalPath) && !wasExtractedFromArchive) {
                        // Only preserve with "_original" suffix if:
                        // 1. The path would conflict (same folder name), AND
                        // 2. It was NOT an extracted archive folder
                        Path originalFolderPath = rootFolder.resolve(folderName + "_original");
                        if (Files.exists(originalFolderPath)) {
                            logMessage("Warning: overwriting previous backup: " + originalFolderPath.getFileName());
                        }
                        Files.move(folder, originalFolderPath, StandardCopyOption.REPLACE_EXISTING);
                        logMessage("Preserved original folder as: " + originalFolderPath.getFileName());
                    } else if (wasExtractedFromArchive) {
                        // This was an extracted archive folder, just delete it
                        deleteDirectory(folder);
                        logMessage("Cleaned up extracted folder: " + folder.getFileName());
                    }
                    // else - original folder with a different name is naturally preserved
                    
                    // Move the temp folder to the final path
                    if (Files.exists(finalPath) && !Files.isSameFile(tempFolder, finalPath)) {
                        deleteDirectory(finalPath);
                    }
                    Files.move(tempFolder, finalPath, StandardCopyOption.REPLACE_EXISTING);
                    logMessage("Created output folder: " + finalPath.getFileName());
                } catch (IOException e) {
                    logMessage("Error finalizing folder: " + e.getMessage());
                    finalPath = tempFolder; // Use temp folder if something fails
                }
            } else {
                // For archive formats
                String extension = "." + outputFormat;
                String outputName = folderName + extension;
                finalPath = rootFolder.resolve(outputName);
                
                // Check if this would overwrite an original archive file (same name and extension)
                if (!deleteOriginals && Files.exists(finalPath)) {
                    // This is an original archive with the same name - preserve it by renaming
                    Path backupPath = rootFolder.resolve(folderName + "_original" + extension);
                    try {
                        if (Files.exists(backupPath)) {
                            logMessage("Warning: overwriting previous backup: " + backupPath.getFileName());
                        }
                        Files.move(finalPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                        logMessage("Preserved original archive as: " + backupPath.getFileName());
                    } catch (IOException e) {
                        logMessage("Error preserving original archive: " + e.getMessage());
                    }
                }
                
                logMessage("Creating " + outputFormat.toUpperCase() + " archive: " + finalPath.getFileName());
                
                switch(outputFormat) {
                    case "cbz":
                    case "zip":
                        createZipArchive(processedFiles, finalPath.toFile());
                        break;
                    case "cbr":
                    case "rar":
                        createRarArchive(processedFiles, finalPath.toFile());
                        break;
                }
            }
            
            return finalPath;
        }

        return null;
        } finally {
            // Clean up temp directory used for processed images
            if (Files.exists(tempDir)) {
                try {
                    deleteDirectory(tempDir);
                } catch (IOException e) {
                    logMessage("Warning: failed to clean up temp directory: " + e.getMessage());
                }
            }
        }
    }
    
    private static BufferedImage rotateImage(BufferedImage originalImage) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        BufferedImage rotatedImage = new BufferedImage(height, width, originalImage.getType());

        AffineTransform rotation = new AffineTransform();
        rotation.translate(height, 0);
        rotation.rotate(Math.toRadians(90));

        Graphics2D g2d = rotatedImage.createGraphics();
        g2d.setTransform(rotation);
        g2d.drawImage(originalImage, 0, 0, null);
        g2d.dispose();

        return rotatedImage;
    }

    private static BufferedImage[] splitImage(BufferedImage originalImage, boolean isJapaneseManga) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        BufferedImage firstHalf, secondHalf;

        if (isJapaneseManga) {
            firstHalf = getRightHalf(originalImage, width, height);
            secondHalf = getLeftHalf(originalImage, width, height);
        } else {
            firstHalf = getLeftHalf(originalImage, width, height);
            secondHalf = getRightHalf(originalImage, width, height);
        }

        return new BufferedImage[]{firstHalf, secondHalf};
    }

    private static BufferedImage getLeftHalf(BufferedImage originalImage, int width, int height) {
        return originalImage.getSubimage(0, 0, width/2, height);
    }

    private static BufferedImage getRightHalf(BufferedImage originalImage, int width, int height) {
        return originalImage.getSubimage(width/2, 0, width - width/2, height);
    }

    // Renamed to be more specific
    private static void createZipArchive(List<Path> imageFiles, File outputFile) throws IOException {
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
    
    // New method for creating RAR archives
    private static void createRarArchive(List<Path> imageFiles, File outputFile) throws IOException {
        System.out.println("Creating RAR/CBR: " + outputFile);

        // Since Java doesn't have built-in RAR creation, try to use external tools
        boolean success = createRarWithExternalProgram(imageFiles, outputFile);
        
        if (!success) {
            // Fallback - create ZIP instead but rename it to the requested extension
            logMessage("WARNING: Could not create RAR/CBR file. No RAR program found. Creating ZIP instead.");
            
            // Create temporary zip file
            File tempZip = new File(outputFile.getParentFile(), outputFile.getName() + ".zip.tmp");
            createZipArchive(imageFiles, tempZip);
            
            // Rename to requested extension
            if (tempZip.exists()) {
                if (outputFile.exists()) {
                    outputFile.delete();
                }
                tempZip.renameTo(outputFile);
            }
        }
    }
    
    // New method to create RAR files using external RAR tools
    private static boolean createRarWithExternalProgram(List<Path> imageFiles, File outputFile) {
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
                        logMessage("Successfully created RAR file with " + new File(winRarPath).getName());
                        return true;
                    } else {
                        logMessage("Failed to create RAR file - exit code: " + exitCode);
                    }
                } catch (Exception e) {
                    logMessage("Error creating RAR file: " + e.getMessage());
                }
            }
        }
        
        return false;
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted((a, b) -> b.compareTo(a)) // Reverse order to delete children first
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.err.println("Error deleting: " + path + " - " + e.getMessage());
                    }
                });
        }
    }

    private static boolean isImageFile(String filePath) {
        String lowerCase = filePath.toLowerCase();
        for (String ext : IMAGE_EXTENSIONS) {
            if (lowerCase.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArchiveFile(String filePath) {
        String lowerCase = filePath.toLowerCase();
        for (String ext : ARCHIVE_EXTENSIONS) {
            if (lowerCase.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static int runProcess(ProcessBuilder pb) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (InputStream is = process.getInputStream()) {
            byte[] buf = new byte[1024];
            while (is.read(buf) != -1) {}
        }
        return process.waitFor();
    }

    private static boolean extractWithExternalProgram(Path archivePath, Path extractDir) {
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

    /**
     * Crops an image by removing specified number of pixels from each side.
     *
     * @param img The original image
     * @param left Pixels to crop from left
     * @param right Pixels to crop from right
     * @param top Pixels to crop from top
     * @param bottom Pixels to crop from bottom
     * @return The cropped image
     */
    private static BufferedImage cropImage(BufferedImage img, int left, int right, int top, int bottom) {
        int origWidth = img.getWidth();
        int origHeight = img.getHeight();

        // Validate crop values don't exceed image dimensions
        if (left >= origWidth || top >= origHeight || left + right >= origWidth || top + bottom >= origHeight) {
            logMessage("Warning: crop values exceed image dimensions, skipping crop");
            return img;
        }

        // Calculate new dimensions
        int newWidth = Math.max(1, origWidth - left - right);
        int newHeight = Math.max(1, origHeight - top - bottom);

        // Create cropped image
        return img.getSubimage(left, top, newWidth, newHeight);
    }
}