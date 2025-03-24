import com.github.junrar.Junrar;
import com.github.junrar.exception.RarException;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.*;

public class MangaPagesSplitter {

    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"};
    private static final String[] ARCHIVE_EXTENSIONS = {".rar", ".zip", ".cbr", ".cbz"};
    
    private static MangaPagesSplitterUI ui = null;

    public static void main(String[] args) {
        try {
            // Set system look and feel
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
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
            String outputFormat, MangaPagesSplitterUI uiInstance) throws IOException {
        
        ui = uiInstance;
        
        try {
            logMessage("Starting extraction of archives...");
            // Step 1: Extract all archives and collect paths
            List<Path> originalArchives = extractAllArchives(rootFolder);
            logMessage("Extracted " + originalArchives.size() + " archives.");
            
            // Count total folders to process
            long folderCount;
            try {
                folderCount = Files.list(Paths.get(rootFolder))
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
            
            Files.list(Paths.get(rootFolder))
                    .filter(Files::isDirectory)
                    .forEach(folder -> {
                        try {
                            logMessage("Processing folder: " + folder.getFileName());
                            
                            // Update progress
                            if (totalFolders > 0) {
                                updateProgress("Processing folder " + (processedFolders[0] + 1) + "/" + totalFolders,
                                              (int)((processedFolders[0] * 100) / totalFolders));
                            }
                            
                            Path newOutputPath = processFolderAndCreateOutput(folder, Paths.get(rootFolder), splitMode, 
                                                                  isJapaneseManga, deleteOriginals,
                                                                  skipImagesFromStart, skipImagesFromEnd,
                                                                  rotateWideImages, outputFormat);
                            if (newOutputPath != null) {
                                newlyCreatedOutputFiles.add(newOutputPath);
                                logMessage("Created: " + newOutputPath.getFileName());
                            }
                            
                            processedFolders[0]++;
                            
                        } catch (IOException e) {
                            logMessage("Error processing folder: " + folder + " - " + e.getMessage());
                        }
                        
                        // Check if the processing should be cancelled
                        if (Thread.currentThread().isInterrupted() || (ui != null && ui.isCancelled())) {
                            logMessage("Processing cancelled by user.");
                        }
                    });
            
            updateProgress("Cleaning up...", 90);
                    
            // Delete original archives if needed
            if (deleteOriginals) {
                logMessage("Deleting original archives...");
                for (Path archive : originalArchives) {
                    try {
                        Files.delete(archive);
                        logMessage("Deleted original archive: " + archive.getFileName());
                    } catch (IOException e) {
                        logMessage("Error deleting archive: " + archive + " - " + e.getMessage());
                    }
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

    private static List<Path> extractAllArchives(String rootFolder) throws IOException {
        logMessage("Extracting archives in: " + rootFolder);
        List<Path> archivePaths = new ArrayList<>();

        // Count total archives
        long archiveCount;
        try {
            archiveCount = Files.list(Paths.get(rootFolder))
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

        List<Path> archives = new ArrayList<>();
        Files.list(Paths.get(rootFolder))
            .filter(Files::isRegularFile)
            .filter(path -> isArchiveFile(path.toString()))
            .forEach(archives::add);

        for (Path archivePath : archives) {
            // Check if processing was cancelled
            if (Thread.currentThread().isInterrupted() || (ui != null && ui.isCancelled())) {
                return archivePaths;
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
                }

                logMessage("Extracted: " + archivePath.getFileName());
                processedArchives[0]++;
            } catch (IOException e) {
                logMessage("Error extracting archive: " + archivePath + " - " + e.getMessage());
            }
        }

        return archivePaths;
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
    private static Path processFolderAndCreateOutput(Path folder, Path rootFolder, int splitMode, boolean isJapaneseManga, 
                                                boolean deleteOriginals, int skipImagesFromStart, int skipImagesFromEnd,
                                                boolean rotateWideImages, String outputFormat) throws IOException {
        logMessage("Processing folder: " + folder);

        List<Path> imagePaths = new ArrayList<>();
        List<Path> processedFiles = new ArrayList<>();

        // Find all image files
        Files.walk(folder)
                .filter(Files::isRegularFile)
                .filter(path -> isImageFile(path.toString()))
                .forEach(imagePaths::add);

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
            
            // Check if this image should be skipped based on position
            boolean isExceptionImage = (i < firstImageToProcess) || (i >= lastImageToProcess);
            
            // Check if the image is wide (width > height)
            try {
                BufferedImage img = ImageIO.read(imagePath.toFile());
                if (img != null) {
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
                    
                    // Apply rotation for wide images if requested and not splitting
                    if (rotateWideImages && isWideImage && !shouldSplit) {
                        if (rotateImage(imagePath.toFile(), 90)) {
                            logMessage("Rotated wide image: " + imagePath.getFileName());
                        }
                    }
                }
            } catch (IOException e) {
                logMessage("Error processing image: " + imagePath + " - " + e.getMessage());
            }
            
            if (isExceptionImage && (splitMode == 0 || splitMode == 2)) {
                logMessage("Skipping split for exception image: " + imagePath.getFileName());
            }

            if (shouldSplit) {
                if (splitImage(imagePath.toFile(), isJapaneseManga)) {
                    String baseName = imagePath.getFileName().toString();
                    baseName = baseName.substring(0, baseName.lastIndexOf('.'));
                    String ext = imagePath.toString().substring(imagePath.toString().lastIndexOf('.'));

                    Path firstPage = imagePath.resolveSibling(baseName + "_1" + ext);
                    Path secondPage = imagePath.resolveSibling(baseName + "_2" + ext);

                    processedFiles.add(firstPage);
                    processedFiles.add(secondPage);

                    // Delete original file only if requested
                    if (deleteOriginals) {
                        Files.delete(imagePath);
                    }
                }
            } else {
                processedFiles.add(imagePath);
            }
        }

        if (!processedFiles.isEmpty()) {
            // Create output based on selected format
            String folderName = folder.getFileName().toString();
            Path finalPath;
            
            // Create the appropriate output based on format
            if (outputFormat.equals("folder")) {
                // For folder format, create a dedicated output folder
                finalPath = rootFolder.resolve(folderName + "_processed");
                Files.createDirectories(finalPath);
                
                // Copy all processed files to the output folder
                for (Path file : processedFiles) {
                    Path targetFile = finalPath.resolve(file.getFileName());
                    Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
                
                logMessage("Created output folder: " + finalPath.getFileName());
            } else {
                // For archive formats
                String extension = "." + outputFormat;
                String outputName = folderName + extension;
                finalPath = rootFolder.resolve(outputName);
                
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
            
            // Delete the processed folder only if requested
            if (deleteOriginals) {
                deleteDirectory(folder);
                logMessage("Deleted processed folder: " + folder.getFileName());
            }
            
            return finalPath;
        }

        return null;
    }
    
    private static boolean rotateImage(File imageFile, int degrees) {
        try {
            BufferedImage originalImage = ImageIO.read(imageFile);
            if (originalImage == null) {
                System.err.println("Could not read image for rotation: " + imageFile);
                return false;
            }
            
            // Calculate the new dimensions for the rotated image
            int width = originalImage.getWidth();
            int height = originalImage.getHeight();
            
            // Create a new rotated image
            BufferedImage rotatedImage = new BufferedImage(height, width, originalImage.getType());
            
            // Create the rotation transformation
            AffineTransform rotation = new AffineTransform();
            rotation.translate(height, 0);
            rotation.rotate(Math.toRadians(degrees));
            
            // Apply the transformation
            Graphics2D g2d = rotatedImage.createGraphics();
            g2d.setTransform(rotation);
            g2d.drawImage(originalImage, 0, 0, null);
            g2d.dispose();
            
            // Get file extension
            String fileName = imageFile.getName();
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
            
            // Save the rotated image over the original
            ImageIO.write(rotatedImage, extension, imageFile);
            
            return true;
        } catch (IOException e) {
            System.err.println("Error rotating image " + imageFile + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean splitImage(File imageFile, boolean isJapaneseManga) {
        try {
            BufferedImage originalImage = ImageIO.read(imageFile);
            if (originalImage == null) {
                System.err.println("Could not read image: " + imageFile);
                return false;
            }

            int width = originalImage.getWidth();
            int height = originalImage.getHeight();

            String fileName = imageFile.getName();
            String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1);

            // For Japanese manga, right half is first (_1)
            // For Western style, left half is first (_1)
            BufferedImage firstHalf, secondHalf;

            if (isJapaneseManga) {
                // Japanese manga: right half is read first
                firstHalf = getRightHalf(originalImage, width, height);
                secondHalf = getLeftHalf(originalImage, width, height);
                System.out.println("Split image for Japanese manga (right to left): " + imageFile.getName());
            } else {
                // Western style: left half is read first
                firstHalf = getLeftHalf(originalImage, width, height);
                secondHalf = getRightHalf(originalImage, width, height);
                System.out.println("Split image for Western style (left to right): " + imageFile.getName());
            }

            File firstFile = new File(imageFile.getParent(), baseName + "_1." + extension);
            ImageIO.write(firstHalf, extension, firstFile);

            File secondFile = new File(imageFile.getParent(), baseName + "_2." + extension);
            ImageIO.write(secondHalf, extension, secondFile);

            return true;
        } catch (IOException e) {
            System.err.println("Error splitting image " + imageFile + ": " + e.getMessage());
            return false;
        }
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
                    
                    Process process = pb.start();
                    int exitCode = process.waitFor();
                    
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
        Files.walk(directory)
            .sorted((a, b) -> b.compareTo(a)) // Reverse order to delete children first
            .forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    System.err.println("Error deleting: " + path + " - " + e.getMessage());
                }
            });
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
                    Process process = pb.start();
                    int exitCode = process.waitFor();
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
                    Process process = pb.start();
                    int exitCode = process.waitFor();
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
}