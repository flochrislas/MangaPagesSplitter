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

    public static void main(String[] args) {
        String[] options = {"Detect automatically for each manga", "Keep original images", "Split all images in half"};
        int choice = showVerticalOptionDialog(
                "Image Splitting Option",
                "Choose an option:",
                options,
                0);  // Set "Detect automatically" as default option

        if (choice == -1) {
            // User cancelled, exit program
            return;
        }

        int splitMode = choice; // 0=auto, 1=keep original, 2=split all
        boolean isJapaneseManga = true; // Default to Japanese manga (right to left)
        
        // Default values for exceptions
        int skipImagesFromStart = 0;
        int skipImagesFromEnd = 0;
        
        // Default for rotation
        boolean rotateWideImages = false;

        // Ask about reading direction if splitting is possible
        if (splitMode == 0 || splitMode == 2) {
            String[] directionOptions = {"Japanese manga (right to left)", "Western style (left to right)"};
            int directionChoice = showVerticalOptionDialog(
                    "Reading Direction",
                    "Choose reading direction:",
                    directionOptions,
                    0);

            if (directionChoice == -1) {
                // User cancelled, exit program
                return;
            }

            isJapaneseManga = (directionChoice == 0);
            
            // Ask if user wants to make exceptions for certain pages
            int makeExceptions = JOptionPane.showConfirmDialog(
                    null,
                    "Do you want to skip splitting certain pages at the beginning and/or end of each manga?",
                    "Exceptions for Splitting",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
                    
            if (makeExceptions == JOptionPane.YES_OPTION) {
                skipImagesFromStart = getNumberInput(
                        "Skip from Start",
                        "Number of images to skip (not split) from the beginning:",
                        0);
                        
                if (skipImagesFromStart < 0) {
                    // User cancelled, exit program
                    return;
                }
                
                skipImagesFromEnd = getNumberInput(
                        "Skip from End",
                        "Number of images to skip (not split) from the end:",
                        0);
                        
                if (skipImagesFromEnd < 0) {
                    // User cancelled, exit program
                    return;
                }
            }
        }
        
        // Ask about rotating wide images if not splitting all
        if (splitMode != 2) {
            int rotateChoice = JOptionPane.showConfirmDialog(
                    null,
                    "Would you like to automatically rotate wide images (width > height) 90° clockwise?\n" +
                    "This makes them easier to view in landscape mode on devices.",
                    "Rotate Wide Images",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
                    
            rotateWideImages = (rotateChoice == JOptionPane.YES_OPTION);
        }
        
        // Ask about deleting original files
        String[] deletionOptions = {"Keep original files", "Delete original files after processing"};
        int deletionChoice = showVerticalOptionDialog(
                "File Deletion Option",
                "Choose what to do with original files:",
                deletionOptions,
                0);  // "Keep original files" is now at index 0 and default
                
        if (deletionChoice == -1) {
            // User cancelled, exit program
            return;
        }
        
        boolean deleteOriginals = (deletionChoice == 1); // Now "Delete" is at index 1

        // Rest of the method remains the same
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Root Directory for Manga Processing");

        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            String rootFolder = chooser.getSelectedFile().getAbsolutePath();

            String confirmMessage = "This will extract archives, read all folders, process images according to your selection, and create CBZ files.\n";
            if (deleteOriginals) {
                confirmMessage += "Original images, archives and extracted folders will be deleted.\n";
            } else {
                confirmMessage += "Original images, archives and extracted folders will be kept.\n";
            }
            
            // Add exception info to confirmation message if applicable
            if ((splitMode == 0 || splitMode == 2) && (skipImagesFromStart > 0 || skipImagesFromEnd > 0)) {
                confirmMessage += "The program will skip splitting the first " + skipImagesFromStart + 
                                " and the last " + skipImagesFromEnd + " images of each manga.\n";
            }
            
            // Add rotation info to confirmation message
            if (rotateWideImages && splitMode != 2) {
                confirmMessage += "Wide images (width > height) that are not split will be rotated 90° clockwise.\n";
            }
            
            confirmMessage += "Are you sure you want to continue?";

            int confirm = JOptionPane.showConfirmDialog(null,
                    confirmMessage,
                    "Confirm Operation",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                process(rootFolder, splitMode, isJapaneseManga, deleteOriginals, skipImagesFromStart, skipImagesFromEnd, rotateWideImages);
                JOptionPane.showMessageDialog(null,
                        "Processing complete!",
                        "Done",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        }
    }

    private static int getNumberInput(String title, String message, int defaultValue) {
        String input = JOptionPane.showInputDialog(
                null,
                message,
                title,
                JOptionPane.QUESTION_MESSAGE);
                
        if (input == null) {
            // User cancelled
            return -1;
        }
        
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(
                    null,
                    "Please enter a valid number. Using default value: " + defaultValue,
                    "Invalid Input",
                    JOptionPane.WARNING_MESSAGE);
            return defaultValue;
        }
    }

    private static int showVerticalOptionDialog(String title, String message, String[] options, int defaultOption) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JLabel messageLabel = new JLabel(message);
        panel.add(messageLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 10))); // Add spacing

        ButtonGroup buttonGroup = new ButtonGroup();
        JRadioButton[] radioButtons = new JRadioButton[options.length];

        for (int i = 0; i < options.length; i++) {
            radioButtons[i] = new JRadioButton(options[i]);
            radioButtons[i].setActionCommand(String.valueOf(i));
            buttonGroup.add(radioButtons[i]);
            panel.add(radioButtons[i]);
        }

        // Select the default option
        radioButtons[defaultOption].setSelected(true);

        int result = JOptionPane.showConfirmDialog(
                null,
                panel,
                title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String actionCommand = buttonGroup.getSelection().getActionCommand();
            return Integer.parseInt(actionCommand);
        } else {
            return -1; // Cancelled
        }
    }

    private static void process(String rootFolder, int splitMode, boolean isJapaneseManga, boolean deleteOriginals, 
                              int skipImagesFromStart, int skipImagesFromEnd, boolean rotateWideImages) {
        try {
            // Step 1: Extract all archives and collect paths
            List<Path> originalArchives = extractAllArchives(rootFolder);

            // Step 2: Process each folder and track new CBZ files
            List<Path> newlyCreatedCbzFiles = new ArrayList<>();

            Files.list(Paths.get(rootFolder))
                    .filter(Files::isDirectory)
                    .forEach(folder -> {
                        try {
                            Path newCbz = processFolderAndCreateCBZ(folder, Paths.get(rootFolder), splitMode, 
                                                                  isJapaneseManga, deleteOriginals,
                                                                  skipImagesFromStart, skipImagesFromEnd,
                                                                  rotateWideImages);
                            if (newCbz != null) {
                                newlyCreatedCbzFiles.add(newCbz);
                            }
                        } catch (IOException e) {
                            System.err.println("Error processing folder: " + folder + " - " + e.getMessage());
                        }
                    });
                    
            // Delete original archives if needed
            if (deleteOriginals) {
                for (Path archive : originalArchives) {
                    try {
                        Files.delete(archive);
                        System.out.println("Deleted original archive: " + archive.getFileName());
                    } catch (IOException e) {
                        System.err.println("Error deleting archive: " + archive + " - " + e.getMessage());
                    }
                }
            }

            // Rest of the method remains unchanged
        } catch (IOException e) {
            System.err.println("Error during processing: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static List<Path> extractAllArchives(String rootFolder) throws IOException {
        System.out.println("Extracting archives in: " + rootFolder);
        List<Path> archivePaths = new ArrayList<>();

        Files.list(Paths.get(rootFolder))
            .filter(Files::isRegularFile)
            .filter(path -> isArchiveFile(path.toString()))
            .forEach(archivePath -> {
                archivePaths.add(archivePath); // Store the path for deletion later
                String baseName = archivePath.getFileName().toString();
                baseName = baseName.substring(0, baseName.lastIndexOf('.'));
                Path extractDir = Paths.get(rootFolder, baseName);

                try {
                    Files.createDirectories(extractDir);

                    if (archivePath.toString().toLowerCase().endsWith(".rar") ||
                        archivePath.toString().toLowerCase().endsWith(".cbr")) {
                        try {
                            // Try Junrar first
                            Junrar.extract(archivePath.toFile(), extractDir.toFile());
                            System.out.println("Extracted with Junrar: " + archivePath.getFileName());
                        } catch (RarException e) {
                            // If Junrar fails (likely due to RAR5 format), try external program
                            System.out.println("Junrar failed, might be RAR5 format: " + e.getMessage());
                            if (!extractWithExternalProgram(archivePath, extractDir)) {
                                System.err.println("Both Junrar and external extraction failed for: " + archivePath);
                            }
                        }
                    } else {
                        // Extract ZIP
                        extractZip(archivePath.toFile(), extractDir.toFile());
                    }

                    System.out.println("Extracted: " + archivePath.getFileName());
                } catch (IOException e) {
                    System.err.println("Error extracting archive: " + archivePath + " - " + e.getMessage());
                    e.printStackTrace();
                }
            });

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

    private static Path processFolderAndCreateCBZ(Path folder, Path rootFolder, int splitMode, boolean isJapaneseManga, 
                                                boolean deleteOriginals, int skipImagesFromStart, int skipImagesFromEnd,
                                                boolean rotateWideImages) throws IOException {
        System.out.println("Processing folder: " + folder);

        List<Path> imagePaths = new ArrayList<>();
        List<Path> processedFiles = new ArrayList<>();

        // Find all image files
        Files.walk(folder)
                .filter(Files::isRegularFile)
                .filter(path -> isImageFile(path.toString()))
                .forEach(imagePaths::add);

        if (imagePaths.isEmpty()) {
            System.out.println("No images found in: " + folder);
            return null;
        }
        
        int totalImages = imagePaths.size();
        System.out.println("Found " + totalImages + " images in " + folder.getFileName());
        
        // Calculate which images to actually process with exceptions
        int firstImageToProcess = Math.min(skipImagesFromStart, totalImages);
        int lastImageToProcess = Math.max(0, totalImages - skipImagesFromEnd);
        int latestSinglePageImageIndex = -99;

        // Process each image
        for (int i = 0; i < imagePaths.size(); i++) {
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
                            System.out.println("Auto-detected double page for: " + imagePath.getFileName());
                        }
                    }
                    
                    // Apply rotation for wide images if requested and not splitting
                    if (rotateWideImages && isWideImage && !shouldSplit) {
                        if (rotateImage(imagePath.toFile(), 90)) {
                            System.out.println("Rotated wide image: " + imagePath.getFileName());
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Error processing image: " + imagePath + " - " + e.getMessage());
            }
            
            if (isExceptionImage && (splitMode == 0 || splitMode == 2)) {
                System.out.println("Skipping split for exception image: " + imagePath.getFileName());
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
            // Create CBZ file with original name
            String folderName = folder.getFileName().toString();
            String cbzName = folderName + ".cbz";
            Path finalPath = rootFolder.resolve(cbzName);

            createCBZ(processedFiles, finalPath.toFile());

            // Delete the processed folder only if requested
            if (deleteOriginals) {
                deleteDirectory(folder);
                System.out.println("Deleted processed folder: " + folder.getFileName());
            }

            System.out.println("Created CBZ: " + finalPath.getFileName());
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

    private static void createCBZ(List<Path> imageFiles, File outputCBZ) throws IOException {
        System.out.println("Creating CBZ: " + outputCBZ);

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputCBZ))) {
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