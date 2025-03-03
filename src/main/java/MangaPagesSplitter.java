import com.github.junrar.Junrar;
import com.github.junrar.exception.RarException;

import javax.imageio.ImageIO;
import javax.swing.*;
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
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Root Directory for Manga Processing");

        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            String rootFolder = chooser.getSelectedFile().getAbsolutePath();

            int confirm = JOptionPane.showConfirmDialog(null,
                    "This will extract archives, split all images, and create CBZ files.\n" +
                    "Original archives and extracted folders will be deleted.\n" +
                    "Are you sure you want to continue?",
                    "Confirm Operation",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);

            if (confirm == JOptionPane.YES_OPTION) {
                process(rootFolder);
                JOptionPane.showMessageDialog(null,
                        "Processing complete!",
                        "Done",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        }
    }

    private static void process(String rootFolder) {
        try {
            // Step 1: Extract all archives and collect paths
            List<Path> originalArchives = extractAllArchives(rootFolder);

            // Step 2: Process each folder and track new CBZ files
            List<Path> newlyCreatedCbzFiles = new ArrayList<>();

            Files.list(Paths.get(rootFolder))
                    .filter(Files::isDirectory)
                    .forEach(folder -> {
                        try {
                            Path newCbz = processFolderAndCreateCBZ(folder, Paths.get(rootFolder));
                            if (newCbz != null) {
                                newlyCreatedCbzFiles.add(newCbz);
                            }
                        } catch (IOException e) {
                            System.err.println("Error processing folder: " + folder + " - " + e.getMessage());
                        }
                    });

            // Step 3: Delete original archive files (except newly created ones)
            for (Path archivePath : originalArchives) {
                // Skip if this is one of our newly created CBZ files
                if (newlyCreatedCbzFiles.contains(archivePath)) {
                    System.out.println("Preserving newly created CBZ: " + archivePath.getFileName());
                    continue;
                }

                try {
                    Files.delete(archivePath);
                    System.out.println("Deleted original archive: " + archivePath.getFileName());
                } catch (IOException e) {
                    System.err.println("Error deleting archive: " + archivePath + " - " + e.getMessage());
                }
            }
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

    private static Path processFolderAndCreateCBZ(Path folder, Path rootFolder) throws IOException {
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

        // Process each image
        for (Path imagePath : imagePaths) {
            if (splitImage(imagePath.toFile())) {
                String baseName = imagePath.getFileName().toString();
                baseName = baseName.substring(0, baseName.lastIndexOf('.'));
                String ext = imagePath.toString().substring(imagePath.toString().lastIndexOf('.'));

                Path rightPage = imagePath.resolveSibling(baseName + "_1" + ext);
                Path leftPage = imagePath.resolveSibling(baseName + "_2" + ext);

                processedFiles.add(rightPage);
                processedFiles.add(leftPage);

                // Delete original file
                Files.delete(imagePath);
            }
        }

        if (!processedFiles.isEmpty()) {
            // Create CBZ file with original name
            String folderName = folder.getFileName().toString();
            String cbzName = folderName + ".cbz";
            Path finalPath = rootFolder.resolve(cbzName);

            createCBZ(processedFiles, finalPath.toFile());

            // Delete the processed folder
            deleteDirectory(folder);

            System.out.println("Created CBZ: " + finalPath.getFileName());
            return finalPath;
        }

        return null;
    }

    private static boolean splitImage(File imageFile) {
        try {
            BufferedImage originalImage = ImageIO.read(imageFile);
            if (originalImage == null) {
                System.err.println("Could not read image: " + imageFile);
                return false;
            }

            int width = originalImage.getWidth();
            int height = originalImage.getHeight();
            int halfWidth = width / 2;

            // Create right half image (page 1)
            BufferedImage rightHalf = originalImage.getSubimage(0, 0, halfWidth, height);
            String fileName = imageFile.getName();
            String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
            String extension = fileName.substring(fileName.lastIndexOf('.') + 1);

            File rightFile = new File(imageFile.getParent(), baseName + "_1." + extension);
            ImageIO.write(rightHalf, extension, rightFile);

            // Create left half image (page 2)
            BufferedImage leftHalf = originalImage.getSubimage(halfWidth, 0, width - halfWidth, height);
            File leftFile = new File(imageFile.getParent(), baseName + "_2." + extension);
            ImageIO.write(leftHalf, extension, leftFile);

            System.out.println("Split image: " + imageFile.getName());
            return true;
        } catch (IOException e) {
            System.err.println("Error splitting image " + imageFile + ": " + e.getMessage());
            return false;
        }
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