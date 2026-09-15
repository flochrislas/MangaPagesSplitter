package mangapagessplitter;

import mangapagessplitter.archive.RarArchive;
import mangapagessplitter.archive.ZipArchive;
import mangapagessplitter.image.AutoCrop;
import mangapagessplitter.image.AutoCropResult;
import mangapagessplitter.image.PageTransform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BatchProcessor {

    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"};
    private static final String[] ARCHIVE_EXTENSIONS = {".rar", ".zip", ".cbr", ".cbz"};
    
    private static ProcessingListener ui = null;

    private static class ArchiveExtractionResult {
        public final List<Path> archivePaths;
        public final List<Path> extractedFolders;
        
        public ArchiveExtractionResult(List<Path> archivePaths, List<Path> extractedFolders) {
            this.archivePaths = archivePaths;
            this.extractedFolders = extractedFolders;
        }
    }


    
    /**
     * Runs the whole batch: extract archives under {@code rootFolder}, process every
     * folder, create the outputs and clean up. Progress, log lines and cancellation
     * go through {@code listener}.
     */
    public static void processWithUI(
            String rootFolder, int splitMode, boolean isJapaneseManga, boolean deleteOriginals,
            int skipImagesFromStart, int skipImagesFromEnd, boolean rotateWideImages, 
            String outputFormat, int cropLeft, int cropRight, int cropTop, int cropBottom,
            boolean smartAutoCrop, int smartAutoCropSensitivity,
            boolean flattenDirectories, boolean useCustomTitle, String customTitle,
            ProcessingListener listener) throws IOException {

        ui = listener;
        
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
            ui.log(message);
        }
    }
    
    private static void updateProgress(String status, int percentage) {
        if (ui != null) {
            ui.progress(status, percentage);
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
                    RarArchive.extract(archivePath, extractDir, BatchProcessor::logMessage);
                } else {
                    // Extract ZIP
                    ZipArchive.extract(archivePath.toFile(), extractDir.toFile());
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
                        AutoCropResult autoResult = AutoCrop.apply(img, smartAutoCropSensitivity, true,
                                BatchProcessor::logMessage);
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
                        img = PageTransform.crop(img, cropLeft, cropRight, cropTop, cropBottom,
                                BatchProcessor::logMessage);
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
                        img = PageTransform.rotate(img);
                        modified = true;
                        logMessage("Rotated wide image: " + imagePath.getFileName());
                    }

                    if (isExceptionImage && (splitMode == 0 || splitMode == 2)) {
                        logMessage("Skipping split for exception image: " + imagePath.getFileName());
                    }

                    if (shouldSplit) {
                        BufferedImage[] halves = PageTransform.split(img, isJapaneseManga, autoSplitX);
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
                                AutoCropResult postCrop = AutoCrop.apply(
                                        halves[hi], smartAutoCropSensitivity, false,
                                        BatchProcessor::logMessage);
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
                        ZipArchive.create(processedFiles, finalPath.toFile());
                        break;
                    case "cbr":
                    case "rar":
                        RarArchive.create(processedFiles, finalPath.toFile(), BatchProcessor::logMessage);
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


}
