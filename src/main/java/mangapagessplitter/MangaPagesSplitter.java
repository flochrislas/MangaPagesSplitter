package mangapagessplitter;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static class AutoCropResult {
        final BufferedImage image;
        final int splitX;
        final boolean applied;
        final int leftCropped, rightCropped, topCropped, bottomCropped;

        AutoCropResult(BufferedImage image, int splitX, boolean applied,
                       int leftCropped, int rightCropped, int topCropped, int bottomCropped) {
            this.image = image;
            this.splitX = splitX;
            this.applied = applied;
            this.leftCropped = leftCropped;
            this.rightCropped = rightCropped;
            this.topCropped = topCropped;
            this.bottomCropped = bottomCropped;
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
            boolean smartAutoCrop, int smartAutoCropSensitivity,
            boolean flattenDirectories, boolean useCustomTitle, String customTitle,
            MangaPagesSplitterUI uiInstance) throws IOException {
        
        ui = uiInstance;
        
        try {
            logMessage("Starting extraction of archives...");
            // Step 1: Extract all archives and collect paths
            ArchiveExtractionResult extractionResult = extractAllArchives(rootFolder);
            List<Path> originalArchives = extractionResult.archivePaths;
            List<Path> extractedFolders = extractionResult.extractedFolders;
            
            logMessage("Extracted " + originalArchives.size() + " archives.");
            
            // Step 2: Collect folders to process
            Path root = Paths.get(rootFolder);
            List<Path> folders;
            if (flattenDirectories) {
                // Find every directory (at any depth) that directly contains image files
                try (Stream<Path> walk = Files.walk(root)) {
                    folders = walk
                        .filter(Files::isDirectory)
                        .filter(dir -> !dir.equals(root))
                        .filter(dir -> {
                            try (Stream<Path> children = Files.list(dir)) {
                                return children.anyMatch(p -> Files.isRegularFile(p) && isImageFile(p.toString()));
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .sorted()
                        .collect(Collectors.toList());
                }
            } else {
                try (Stream<Path> stream = Files.list(root)) {
                    folders = stream.filter(Files::isDirectory).sorted().collect(Collectors.toList());
                }
            }

            final long totalFolders = folders.size();
            List<Path> newlyCreatedOutputFiles = new ArrayList<>();
            final int[] processedFolders = {0};

            logMessage("Processing " + totalFolders + " folders...");

            // Keep track of folders to delete after processing
            List<Path> foldersToDelete = new ArrayList<>();

            for (Path folder : folders) {
                // Check if the processing should be cancelled
                if (Thread.currentThread().isInterrupted() || (ui != null && ui.isCancelled())) {
                    logMessage("Processing cancelled by user.");
                    break;
                }

                try {
                    // In flatten mode use "ParentName - LeafName" as the output name
                    String outputName;
                    if (flattenDirectories) {
                        Path relativePath = root.relativize(folder);
                        StringBuilder nameBuilder = new StringBuilder();
                        for (int i = 0; i < relativePath.getNameCount(); i++) {
                            if (i > 0) nameBuilder.append(" - ");
                            nameBuilder.append(relativePath.getName(i).toString());
                        }
                        outputName = nameBuilder.toString();
                    } else {
                        outputName = folder.getFileName().toString();
                    }

                    // Apply custom title if enabled: replace outputName with "<title> <number>"
                    if (useCustomTitle && customTitle != null && !customTitle.isEmpty()) {
                        String number = extractLastNumber(folder.getFileName().toString());
                        outputName = customTitle + (number.isEmpty() ? "" : " " + number);
                    }

                    logMessage("Processing folder: " + outputName);

                    // Update progress
                    if (totalFolders > 0) {
                        updateProgress("Processing folder " + (processedFolders[0] + 1) + "/" + totalFolders,
                                      (int)((processedFolders[0] * 100) / totalFolders));
                    }

                    // Pass crop parameters to processFolderAndCreateOutput
                    Path newOutputPath = processFolderAndCreateOutput(folder, root, outputName, splitMode,
                                                          isJapaneseManga, deleteOriginals,
                                                          skipImagesFromStart, skipImagesFromEnd,
                                                          rotateWideImages, outputFormat,
                                                          cropLeft, cropRight, cropTop, cropBottom,
                                                          smartAutoCrop, smartAutoCropSensitivity,
                                                          extractedFolders);
                    if (newOutputPath != null) {
                        newlyCreatedOutputFiles.add(newOutputPath);
                        logMessage("Created: " + newOutputPath.getFileName());

                        // Add folder to cleanup list if output is not "folder"
                        // Only clean up extracted archive folders (intermediate) or if user wants originals deleted
                        if (!outputFormat.equals("folder")) {
                            if (flattenDirectories) {
                                // Clean up the top-level ancestor (direct child of root), not the leaf itself
                                Path topLevelAncestor = root.resolve(root.relativize(folder).getName(0));
                                if ((extractedFolders.contains(topLevelAncestor) || deleteOriginals)
                                        && !foldersToDelete.contains(topLevelAncestor)) {
                                    foldersToDelete.add(topLevelAncestor);
                                }
                            } else {
                                if (extractedFolders.contains(folder) || deleteOriginals) {
                                    foldersToDelete.add(folder);
                                }
                            }
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
    private static Path processFolderAndCreateOutput(Path folder, Path rootFolder, String outputName, int splitMode, boolean isJapaneseManga,
                                                boolean deleteOriginals, int skipImagesFromStart, int skipImagesFromEnd,
                                                boolean rotateWideImages, String outputFormat,
                                                int cropLeft, int cropRight, int cropTop, int cropBottom,
                                                boolean smartAutoCrop, int smartAutoCropSensitivity,
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
                int autoSplitX = -1;
                if (img != null) {
                    // Apply smart autocrop first (also detects the spine/gutter for landscape spreads)
                    if (smartAutoCrop) {
                        AutoCropResult autoResult = smartAutoCropImage(img, smartAutoCropSensitivity);
                        if (autoResult.applied) {
                            img = autoResult.image;
                            modified = true;
                            if (i < 3) {
                                logMessage(String.format(
                                    "Smart autocrop: %s (L=%d R=%d T=%d B=%d)",
                                    imagePath.getFileName(),
                                    autoResult.leftCropped, autoResult.rightCropped,
                                    autoResult.topCropped, autoResult.bottomCropped));
                            } else if (i == 3) {
                                logMessage("Smart autocrop applied to remaining images...");
                            }
                        }
                        autoSplitX = autoResult.splitX;
                    }

                    // Apply cropping in memory
                    if (cropLeft > 0 || cropRight > 0 || cropTop > 0 || cropBottom > 0) {
                        img = cropImage(img, cropLeft, cropRight, cropTop, cropBottom);
                        modified = true;

                        // Manual crop shifts the detected gutter coordinate leftward
                        if (autoSplitX > 0) {
                            autoSplitX -= cropLeft;
                            if (autoSplitX <= 0 || autoSplitX >= img.getWidth()) {
                                autoSplitX = -1;
                            }
                        }

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
                        BufferedImage[] halves = splitImage(img, isJapaneseManga, autoSplitX);
                        String splitDesc = (autoSplitX > 0)
                                ? " at detected gutter x=" + autoSplitX
                                : "";
                        logMessage("Split image (" + (isJapaneseManga ? "right to left" : "left to right") + ")"
                                + splitDesc + ": " + imagePath.getFileName());

                        // Second-pass autocrop on each half: cleans up any gutter whitespace
                        // that ended up on the inner side of a half after the split, plus any
                        // per-page watermark / page-number strip we didn't catch on the spread.
                        // Halves aren't spreads, so we ask smartAutoCropImage to skip gutter
                        // detection on them (avoids misleading log lines and spurious splits).
                        if (smartAutoCrop) {
                            for (int hi = 0; hi < halves.length; hi++) {
                                AutoCropResult postCrop = smartAutoCropImage(
                                        halves[hi], smartAutoCropSensitivity, false);
                                if (postCrop.applied) {
                                    halves[hi] = postCrop.image;
                                }
                            }
                        }
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
            String folderName = outputName;
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
                String archiveFileName = folderName + extension;
                finalPath = rootFolder.resolve(archiveFileName);
                
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

    private static BufferedImage[] splitImage(BufferedImage originalImage, boolean isJapaneseManga, int splitX) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();

        int cut = (splitX > 0 && splitX < width) ? splitX : width / 2;

        BufferedImage leftHalf = originalImage.getSubimage(0, 0, cut, height);
        BufferedImage rightHalf = originalImage.getSubimage(cut, 0, width - cut, height);

        BufferedImage firstHalf, secondHalf;
        if (isJapaneseManga) {
            firstHalf = rightHalf;
            secondHalf = leftHalf;
        } else {
            firstHalf = leftHalf;
            secondHalf = rightHalf;
        }

        return new BufferedImage[]{firstHalf, secondHalf};
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

    private static String extractLastNumber(String name) {
        Matcher m = Pattern.compile("\\d+(?:-\\d+)?").matcher(name);
        String last = "";
        while (m.find()) last = m.group();
        return last;
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

    /**
     * Detects and trims uniform outer margins (typically the white/black scan borders
     * on manga double-page spreads) and, when the image is landscape, tries to locate
     * the actual spine/gutter column so the split cut lands exactly on the seam
     * instead of at width / 2.
     *
     * Approach: sample the four corners to establish the background luminance mean.
     * A column/row is considered "margin" if fewer than {@code minPix} of its pixels
     * deviate from that background mean by more than {@code delta}. Counting pixels
     * (rather than averaging over the whole column) is what lets small features such
     * as a sword handle sticking out into an otherwise uniform dark margin still
     * stop the crawler.
     *
     * @param img         The image to analyze.
     * @param sensitivity 1 (conservative) to 10 (aggressive). 5 is a reasonable default.
     * @return The (possibly cropped) image plus the detected split X (or -1).
     */
    private static AutoCropResult smartAutoCropImage(BufferedImage img, int sensitivity) {
        return smartAutoCropImage(img, sensitivity, true);
    }

    private static AutoCropResult smartAutoCropImage(BufferedImage img, int sensitivity, boolean detectGutter) {
        final int w = img.getWidth();
        final int h = img.getHeight();
        if (w < 40 || h < 40) {
            return new AutoCropResult(img, -1, false, 0, 0, 0, 0);
        }

        int s = Math.max(1, Math.min(10, sensitivity));
        // Base per-pixel deviation from background to count as content. Kept small so
        // low-contrast features (e.g. a dark-grey emblem on a near-black page) still
        // register; the effective delta is bumped up when the corner background is
        // itself noisy — see the {@code delta} calculation below.
        // sensitivity 1 -> 6, 5 -> 18, 10 -> 33
        int baseDelta = 6 + (s - 1) * 3;
        // How many differing pixels a column/row needs to be considered content.
        // sensitivity 1 -> 3, 10 -> 21 (aggressive settings shrug off small features).
        int minPix = 3 + (s - 1) * 2;
        // How uniform a corner block must be to be trusted as background.
        // sensitivity 1 -> 8 (strict), 10 -> 26 (accepts noisy scans).
        double uniformity = 6.0 + s * 2.0;

        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);

        int rowStep = Math.max(1, h / 800);
        int colStep = Math.max(1, w / 1200);

        // Corner check + background-mean estimation.
        int cs = Math.max(10, Math.min(40, Math.min(w, h) / 40));
        double[] cornerMean = new double[4];
        double[] cornerStd  = new double[4];
        blockMeanAndStddev(pixels, w, 0,      0,      cs, cs, cornerMean, cornerStd, 0);
        blockMeanAndStddev(pixels, w, w - cs, 0,      cs, cs, cornerMean, cornerStd, 1);
        blockMeanAndStddev(pixels, w, 0,      h - cs, cs, cs, cornerMean, cornerStd, 2);
        blockMeanAndStddev(pixels, w, w - cs, h - cs, cs, cs, cornerMean, cornerStd, 3);

        double bgSum = 0;
        double stdSum = 0;
        int calmCorners = 0;
        for (int i = 0; i < 4; i++) {
            if (cornerStd[i] < uniformity) {
                bgSum += cornerMean[i];
                stdSum += cornerStd[i];
                calmCorners++;
            }
        }
        if (calmCorners < 2) {
            // Probably a full-bleed art page; do not touch it.
            if (detectGutter) {
                logMessage("Spine detection: skipped (only " + calmCorners
                        + " calm corners; treating as full-bleed art page, splitting at width/2)");
            }
            return new AutoCropResult(img, -1, false, 0, 0, 0, 0);
        }
        int bgMean = (int) Math.round(bgSum / calmCorners);
        double avgCornerStd = stdSum / calmCorners;
        // Adaptive delta: on very clean backgrounds we can afford to be sensitive
        // (low base value catches low-contrast features), on noisier scans the
        // 4x stddev term keeps us robust against speckle.
        int delta = Math.max(baseDelta, (int) Math.round(avgCornerStd * 4.0));

        // Generous per-side cap. With the corner-uniformity gate + content-pixel
        // criterion the algorithm won't runaway; the cap is just a last-resort
        // safety net so that pages with unusually wide margins (or the wide
        // whitespace inside a half after splitting a wide-gutter spread) can be
        // fully trimmed instead of leaving residual borders.
        int maxCropSide = w / 3;
        int maxCropTB   = h / 3;

        // Number of consecutive content columns/rows required to consider "content edge
        // reached". Filters out isolated near-edge parasites (a stray dark speckle, a
        // scanner artifact, a page-number strip only a couple of pixels wide, ...) so
        // a huge white margin does not go untrimmed just because the very last column
        // has a 30-pixel smudge.
        final int minEdgeRun = 5;

        int leftCrop = advanceEdge(pixels, w, h, +1, 0, maxCropSide,
                true, rowStep, bgMean, delta, minPix, minEdgeRun, "left");
        int rightCrop = advanceEdge(pixels, w, h, -1, w - 1, maxCropSide,
                true, rowStep, bgMean, delta, minPix, minEdgeRun, "right");
        int topCrop = advanceEdge(pixels, w, h, +1, 0, maxCropTB,
                false, colStep, bgMean, delta, minPix, minEdgeRun, "top");
        int bottomCrop = advanceEdge(pixels, w, h, -1, h - 1, maxCropTB,
                false, colStep, bgMean, delta, minPix, minEdgeRun, "bottom");

        // Small safety padding so we do not shave line art that touches the margin.
        final int pad = 3;
        leftCrop   = Math.max(0, leftCrop   - pad);
        rightCrop  = Math.max(0, rightCrop  - pad);
        topCrop    = Math.max(0, topCrop    - pad);
        bottomCrop = Math.max(0, bottomCrop - pad);

        // For a landscape image (a double-page spread that we're about to split),
        // enforce symmetric left/right outer crop. Two facing pages come from the
        // same physical paper stock and thus have symmetric outer margins by
        // construction; when the two detected values disagree it's almost always
        // because an edge-hugging feature on one side (a watermark, a page number,
        // a scanning artifact) blocked the crawler. Using min() on both sides keeps
        // the horizontal center of the cropped image aligned with the true center
        // of the scanned pages, so the width/2 fallback split lands on the real
        // gutter instead of being shifted into one page's content.
        if (detectGutter && (w - leftCrop - rightCrop) > (h - topCrop - bottomCrop)
                && leftCrop != rightCrop) {
            int symmetric = Math.min(leftCrop, rightCrop);
            logMessage("Outer crop: enforcing symmetric L/R for landscape spread"
                    + " (was L=" + leftCrop + " R=" + rightCrop
                    + ", using " + symmetric + " on both sides)");
            leftCrop = symmetric;
            rightCrop = symmetric;
        }

        if (leftCrop + rightCrop >= w - 20 || topCrop + bottomCrop >= h - 20) {
            if (detectGutter) {
                logMessage("Spine detection: skipped (outer crop hit safety cap; splitting at width/2)");
            }
            return new AutoCropResult(img, -1, false, 0, 0, 0, 0);
        }

        int cw = w - leftCrop - rightCrop;
        int ch = h - topCrop - bottomCrop;
        BufferedImage cropped = img;
        boolean applied = (leftCrop > 0 || rightCrop > 0 || topCrop > 0 || bottomCrop > 0);
        if (applied) {
            cropped = img.getSubimage(leftCrop, topCrop, cw, ch);
        }

        // Gutter detection: only meaningful when the cropped image is a landscape spread.
        // Strategy: scan the middle 40%..60% band and pick the longest contiguous run of
        // "quiet" columns. What counts as quiet is set *adaptively* per image so this works
        // on dark-background pages too:
        //   - Compute the content-pixel count for every column in the band.
        //   - "Quiet" = below max(minPix, meanBandContent * 0.30). On a clean margin the
        //     mean-based term is tiny and minPix dominates; on a busy dark-bg spread the
        //     mean-based term dominates and picks out the columns that are meaningfully
        //     quieter than their neighbours (which is where the gutter is).
        //   - A real inter-page gutter always spans dozens of consecutive quiet columns,
        //     while accidental between-panel gaps on a single page are typically 10-30 px
        //     and get filtered out by the minimum run-length requirement.
        // The split lands at the center of the detected run so each half only inherits half
        // the gutter width — the second-pass autocrop on each half then trims that leftover.
        int splitX = -1;
        if (detectGutter && cw > ch) {
            int bandStart = leftCrop + (int)(cw * 0.40);
            int bandEnd   = leftCrop + (int)(cw * 0.60);
            int bandLen   = bandEnd - bandStart + 1;

            int[] colContent = new int[bandLen];
            long bandSum = 0;
            for (int i = 0; i < bandLen; i++) {
                colContent[i] = columnContentCount(
                        pixels, w, bandStart + i, topCrop, ch, rowStep, bgMean, delta);
                bandSum += colContent[i];
            }
            int bandMean = (int) (bandSum / bandLen);
            int quietThreshold = Math.max(minPix, bandMean * 3 / 10);

            int bestRunStart = -1;
            int bestRunLen = 0;
            int runStart = -1;
            for (int i = 0; i < bandLen; i++) {
                if (colContent[i] < quietThreshold) {
                    if (runStart == -1) runStart = i;
                } else if (runStart != -1) {
                    int len = i - runStart;
                    if (len > bestRunLen) {
                        bestRunLen = len;
                        bestRunStart = runStart;
                    }
                    runStart = -1;
                }
            }
            if (runStart != -1) {
                int len = bandLen - runStart;
                if (len > bestRunLen) {
                    bestRunLen = len;
                    bestRunStart = runStart;
                }
            }

            // Minimum meaningful gutter width. Real double-page gutters are always
            // at least ~30 px wide; anything shorter is almost certainly an
            // internal between-panel gap that we want to leave alone.
            int minGutterRun = Math.max(30, cw / 60);
            if (bestRunStart != -1 && bestRunLen >= minGutterRun) {
                int gutterCenter = bandStart + bestRunStart + bestRunLen / 2;
                int imageCenter  = leftCrop + cw / 2;
                // Sanity gate: a real inter-page gutter is at most ~5% off the
                // geometric center of the (already outer-cropped) image. If the
                // longest quiet run is farther out, it's almost certainly an
                // internal between-panel gap rather than the true spine, so we
                // reject the detection and let splitImage fall back to width/2.
                // The second-pass autocrop then cleans each half symmetrically.
                int maxOffset = Math.max(15, cw / 20);
                int offsetPx = Math.abs(gutterCenter - imageCenter);
                if (offsetPx <= maxOffset) {
                    splitX = gutterCenter - leftCrop;
                    int offsetPct = (int) Math.round(100.0 * offsetPx / cw);
                    logMessage("Spine detection: gutter run of " + bestRunLen + " px, "
                            + offsetPct + "% off cropped-image center (accepted)");
                } else {
                    int offsetPct = (int) Math.round(100.0 * offsetPx / cw);
                    logMessage("Spine detection: rejected (found "
                            + offsetPct + "% off center; falling back to width/2 split)");
                }
            } else {
                logMessage("Spine detection: no gutter run in middle band"
                        + " (bandMean=" + bandMean + ", quietThreshold=" + quietThreshold
                        + ", bestRunLen=" + bestRunLen
                        + "); falling back to width/2 split");
            }
        }

        return new AutoCropResult(cropped, splitX, applied, leftCrop, rightCrop, topCrop, bottomCrop);
    }

    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xff;
        int g = (argb >> 8)  & 0xff;
        int b =  argb        & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static int columnContentCount(int[] pixels, int stride, int x, int y0, int height,
                                          int step, int bgMean, int delta) {
        int count = 0;
        int yEnd = y0 + height;
        int lo = bgMean - delta;
        int hi = bgMean + delta;
        for (int y = y0; y < yEnd; y += step) {
            int lum = luminance(pixels[y * stride + x]);
            if (lum < lo || lum > hi) count++;
        }
        return count;
    }

    private static int rowContentCount(int[] pixels, int stride, int y, int x0, int width,
                                       int step, int bgMean, int delta) {
        int count = 0;
        int base = y * stride;
        int xEnd = x0 + width;
        int lo = bgMean - delta;
        int hi = bgMean + delta;
        for (int x = x0; x < xEnd; x += step) {
            int lum = luminance(pixels[base + x]);
            if (lum < lo || lum > hi) count++;
        }
        return count;
    }

    private static void blockMeanAndStddev(int[] pixels, int stride, int x0, int y0, int bw, int bh,
                                           double[] meanOut, double[] stdOut, int idx) {
        long sum = 0;
        long sumSq = 0;
        int n = 0;
        for (int y = y0; y < y0 + bh; y++) {
            int base = y * stride;
            for (int x = x0; x < x0 + bw; x++) {
                int lum = luminance(pixels[base + x]);
                sum += lum;
                sumSq += (long) lum * lum;
                n++;
            }
        }
        if (n == 0) { meanOut[idx] = 0; stdOut[idx] = 0; return; }
        double mean = (double) sum / n;
        double var  = (double) sumSq / n - mean * mean;
        meanOut[idx] = mean;
        stdOut[idx]  = var > 0 ? Math.sqrt(var) : 0;
    }

    /**
     * Scans inward from an image edge and returns the number of pixels between the edge
     * and the first "real" content region. Uses a run-length criterion: the returned
     * distance corresponds to the first column (if {@code columnScan}) or row that begins
     * a run of {@code minRun} consecutive content columns / rows. This filters out
     * isolated near-edge parasites (a stray dark speckle, a scanner artifact, an
     * unusually thin page-number strip) that would otherwise stop the crawler and leave
     * a huge white margin untrimmed.
     *
     * <p>A second guard handles clusters that are wider than {@code minRun} but still
     * clearly artefacts: the faint smear or dark shadow a scanner leaves along the very
     * border of the sheet. Such a cluster hugs the edge, is only a handful of pixels
     * wide, and is followed by a long stretch of pure background. Real content at a
     * page edge never looks like that — art or a panel border keeps going inward. When
     * the first content run starts inside {@code parasiteZone} and dies out again inside
     * that zone with at least {@code parasiteZone} background lines behind it, the
     * cluster is skipped and the crawl continues. See {@link #edgeParasiteEnd}.
     *
     * @param direction +1 to scan inward from the low edge, -1 to scan inward from the high edge.
     * @param start     starting absolute coordinate (0 for low edge, w-1 or h-1 for high edge).
     * @param cap       hard maximum distance to advance (per-side safety cap).
     * @param columnScan true for left/right (scans columns), false for top/bottom (scans rows).
     * @param otherStep row-step for column scans; col-step for row scans (subsampling factor).
     * @param sideLabel human-readable side name used in the processing log.
     * @return the distance from the edge to the first content run, or {@code cap} if none found.
     */
    private static int advanceEdge(int[] pixels, int w, int h, int direction, int start, int cap,
                                   boolean columnScan, int otherStep,
                                   int bgMean, int delta, int minPix, int minRun, String sideLabel) {
        // Border strip in which a narrow, isolated content cluster is treated as a
        // scanner artefact instead of a content edge: ~1% of the scanned dimension,
        // never less than 16 px (3840 px spread -> 38 px, 1400 px half -> 16 px).
        int dim = columnScan ? w : h;
        int parasiteZone = Math.max(16, dim / 100);

        int dist = 0;
        int runStart = -1;
        int runLen = 0;
        while (dist < cap) {
            int c = lineContentCount(pixels, w, h, columnScan, start + direction * dist,
                    otherStep, bgMean, delta);
            if (c >= minPix) {
                if (runStart < 0) runStart = dist;
                runLen++;
                if (runLen >= minRun) {
                    if (runStart < parasiteZone) {
                        int clusterEnd = edgeParasiteEnd(pixels, w, h, direction, start, cap,
                                columnScan, otherStep, bgMean, delta, minPix,
                                runStart, parasiteZone);
                        if (clusterEnd >= 0) {
                            logMessage("Outer crop: ignoring " + (clusterEnd - runStart)
                                    + " px edge artefact on " + sideLabel + " side");
                            dist = clusterEnd;
                            runStart = -1;
                            runLen = 0;
                            continue;
                        }
                    }
                    return runStart;
                }
            } else {
                runStart = -1;
                runLen = 0;
            }
            dist++;
        }
        return cap;
    }

    /**
     * Decides whether the content run starting at distance {@code runStart} from the
     * edge is an edge artefact. Walks inward from {@code runStart}: the cluster is an
     * artefact if its last content line lies within {@code parasiteZone} of the edge and
     * is followed by at least {@code parasiteZone} consecutive background lines.
     *
     * @return the distance of the first line after the artefact (where the crawl should
     *         resume), or -1 if the run is real content.
     */
    private static int edgeParasiteEnd(int[] pixels, int w, int h, int direction, int start, int cap,
                                       boolean columnScan, int otherStep,
                                       int bgMean, int delta, int minPix,
                                       int runStart, int parasiteZone) {
        int lastContent = runStart;
        int gap = 0;
        int limit = Math.min(cap, parasiteZone * 2);
        for (int d = runStart; d < limit; d++) {
            int c = lineContentCount(pixels, w, h, columnScan, start + direction * d,
                    otherStep, bgMean, delta);
            if (c >= minPix) {
                lastContent = d;
                gap = 0;
                if (lastContent >= parasiteZone) {
                    return -1; // cluster extends past the border strip: real content
                }
            } else {
                gap++;
                if (gap >= parasiteZone) {
                    return lastContent + 1;
                }
            }
        }
        return -1;
    }

    private static int lineContentCount(int[] pixels, int w, int h, boolean columnScan, int idx,
                                        int otherStep, int bgMean, int delta) {
        return columnScan
                ? columnContentCount(pixels, w, idx, 0, h, otherStep, bgMean, delta)
                : rowContentCount(pixels, w, idx, 0, w, otherStep, bgMean, delta);
    }
}