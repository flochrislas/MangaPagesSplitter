package mangapagessplitter;

import mangapagessplitter.archive.RarArchive;
import mangapagessplitter.archive.ZipArchive;
import mangapagessplitter.image.AutoCrop;
import mangapagessplitter.image.AutoCropResult;
import mangapagessplitter.image.PageTransform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Batch engine: discovers the volumes under a root folder, extracts archives into
 * an application-owned workspace, processes every image, publishes the outputs and
 * finally deletes the inputs the user asked to delete.
 *
 * <p>File-safety invariant: <b>an input is deleted only after every output derived
 * from it has been written, verified and moved into place, and never when the run
 * was cancelled.</b> Outputs are staged inside the workspace and moved into the
 * root in one step; an existing destination is renamed to a backup rather than
 * deleted, unless it is the job's own source and the user chose to delete originals.
 */
public class BatchProcessor {

    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"};
    private static final String[] ARCHIVE_EXTENSIONS = {".rar", ".zip", ".cbr", ".cbz"};

    /** Prefix of the per-run workspace directory created inside the root. */
    static final String WORKSPACE_PREFIX = ".mangapagessplitter-work-";

    private static ProcessingListener ui = null;

    /** An archive found in the root and where (if at all) it was extracted. */
    private static final class ExtractedArchive {
        final Path archive;
        final Path dir;
        boolean ok;

        ExtractedArchive(Path archive, Path dir) {
            this.archive = archive;
            this.dir = dir;
        }
    }

    /** One folder of images to turn into one output. */
    private static final class InputJob {
        final Path folder;
        /** Name shown to the user and used for the output; "Parent - Leaf" in flatten mode. */
        final String displayName;
        /** Last path segment of the source, used for the custom-title number. */
        final String leafName;
        /** The original archive this folder was extracted from, or null for a user folder. */
        final Path archive;
        /** For user folders: the direct child of the root that contains this folder. */
        final Path topLevel;

        InputJob(Path folder, String displayName, String leafName, Path archive, Path topLevel) {
            this.folder = folder;
            this.displayName = displayName;
            this.leafName = leafName;
            this.archive = archive;
            this.topLevel = topLevel;
        }
    }

    /**
     * Runs the whole batch. Progress, log lines and cancellation go through
     * {@code listener}. Never throws for a single failed volume: those are reported
     * in the result and their inputs are left alone.
     */
    public static BatchResult run(BatchOptions o, ProcessingListener listener) throws IOException {
        ui = listener;
        BatchResult result = new BatchResult();

        Path root = Paths.get(o.rootFolder).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Root folder does not exist: " + root);
        }

        Path workspace = Files.createTempDirectory(root, WORKSPACE_PREFIX);
        try {
            logMessage("Starting extraction of archives...");
            List<ExtractedArchive> archives = extractAllArchives(root, workspace, result);
            if (isCancelled()) {
                result.cancelled = true;
                logMessage("Processing cancelled by user.");
                return result;
            }

            List<InputJob> jobs = discoverJobs(root, workspace, archives, o.flattenDirectories);
            logMessage("Processing " + jobs.size() + " folder" + (jobs.size() == 1 ? "" : "s") + "...");

            Set<Path> inputPaths = inputPaths(jobs, archives);
            Set<String> reservedNames = new HashSet<>();
            Map<InputJob, Path> published = new LinkedHashMap<>();
            List<InputJob> failed = new ArrayList<>();
            int index = 0;
            for (InputJob job : jobs) {
                if (isCancelled()) {
                    result.cancelled = true;
                    break;
                }
                updateProgress("Processing folder " + (index + 1) + "/" + jobs.size(),
                               (int) ((index * 100L) / jobs.size()));
                index++;
                try {
                    String outputName = resolveOutputName(job, o, root, reservedNames);
                    logMessage("Processing folder: " + outputName);
                    Path out = processFolderAndCreateOutput(job, root, workspace, outputName, o, inputPaths);
                    if (isCancelled()) {
                        result.cancelled = true;
                        break;
                    }
                    if (out != null) {
                        published.put(job, out);
                        result.outputs.add(out);
                        logMessage("Created: " + out.getFileName());
                    } else {
                        // Nothing produced: give the name back so a later job can use it.
                        reservedNames.remove(outputName.toLowerCase(Locale.ROOT));
                        result.skipped++;
                    }
                } catch (IOException e) {
                    failed.add(job);
                    String msg = "Error processing folder " + job.displayName + ": " + e.getMessage();
                    logMessage(msg);
                    result.failures.add(msg);
                }
            }

            if (result.cancelled) {
                logMessage("Processing cancelled by user. No input files were deleted.");
                return result;
            }

            updateProgress("Cleaning up...", 90);
            cleanUp(o, root, archives, jobs, published, failed, result);

            updateProgress("Complete", 100);
            String outputType = o.outputFormat.equals("folder") ? "folders" : o.outputFormat.toUpperCase() + " files";
            logMessage("Created " + result.outputs.size() + " " + outputType);
            return result;
        } finally {
            try {
                deleteDirectory(workspace);
            } catch (IOException e) {
                logMessage("Warning: failed to remove workspace " + workspace + ": " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ discovery

    private static List<ExtractedArchive> extractAllArchives(Path root, Path workspace, BatchResult result)
            throws IOException {
        List<ExtractedArchive> out = new ArrayList<>();
        List<Path> archives;
        try (Stream<Path> s = Files.list(root)) {
            archives = s.filter(Files::isRegularFile)
                        .filter(p -> isArchiveFile(p.toString()))
                        .sorted()
                        .collect(Collectors.toList());
        }
        if (!archives.isEmpty()) {
            logMessage("Found " + archives.size() + " archives to extract");
        }

        int done = 0;
        for (Path archivePath : archives) {
            if (isCancelled()) {
                break;
            }
            updateProgress("Extracting archive " + (done + 1) + "/" + archives.size(),
                           (int) ((done * 100L) / archives.size()));

            String fileName = archivePath.getFileName().toString();
            String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
            Path extractDir = uniqueChild(workspace, sanitizeName(baseName));
            ExtractedArchive ea = new ExtractedArchive(archivePath, extractDir);
            out.add(ea);
            try {
                Files.createDirectories(extractDir);
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".rar") || lower.endsWith(".cbr")) {
                    RarArchive.extract(archivePath, extractDir, BatchProcessor::logMessage);
                } else {
                    ZipArchive.extract(archivePath.toFile(), extractDir.toFile());
                    logMessage("Extracted: " + fileName);
                }
                ea.ok = true;
            } catch (IOException e) {
                String msg = "Error extracting archive " + fileName + ": " + e.getMessage();
                logMessage(msg);
                result.failures.add(msg);
            }
            done++;
        }
        logMessage("Extracted " + out.stream().filter(a -> a.ok).count() + " archives.");
        return out;
    }

    private static List<InputJob> discoverJobs(Path root, Path workspace, List<ExtractedArchive> archives,
                                               boolean flatten) throws IOException {
        List<InputJob> jobs = new ArrayList<>();

        // User folders directly under the root (never the workspace, nor stale ones).
        List<Path> topLevel;
        try (Stream<Path> s = Files.list(root)) {
            topLevel = s.filter(Files::isDirectory)
                        .filter(p -> !isWorkspaceDir(p))
                        .sorted()
                        .collect(Collectors.toList());
        }
        for (Path dir : topLevel) {
            if (flatten) {
                for (Path leaf : imageFolders(dir)) {
                    jobs.add(new InputJob(leaf, joinedName(root.relativize(leaf)),
                            leaf.getFileName().toString(), null, dir));
                }
            } else {
                jobs.add(new InputJob(dir, dir.getFileName().toString(),
                        dir.getFileName().toString(), null, dir));
            }
        }

        // Folders extracted from archives. Named after the archive, not the workspace dir.
        for (ExtractedArchive ea : archives) {
            if (!ea.ok) continue;
            String archiveName = ea.archive.getFileName().toString();
            String baseName = archiveName.substring(0, archiveName.lastIndexOf('.'));
            if (flatten) {
                for (Path leaf : imageFolders(ea.dir)) {
                    Path rel = ea.dir.relativize(leaf);
                    String name = rel.getNameCount() == 0 || rel.toString().isEmpty()
                            ? baseName : baseName + " - " + joinedName(rel);
                    String leafName = leaf.equals(ea.dir) ? baseName : leaf.getFileName().toString();
                    jobs.add(new InputJob(leaf, name, leafName, ea.archive, null));
                }
            } else {
                jobs.add(new InputJob(ea.dir, baseName, baseName, ea.archive, null));
            }
        }

        jobs.sort((x, y) -> x.displayName.compareTo(y.displayName));
        return jobs;
    }

    /** Every directory at or below {@code start} that directly contains at least one image. */
    private static List<Path> imageFolders(Path start) throws IOException {
        try (Stream<Path> walk = Files.walk(start)) {
            return walk.filter(Files::isDirectory)
                       .filter(BatchProcessor::hasDirectImages)
                       .sorted()
                       .collect(Collectors.toList());
        }
    }

    private static boolean hasDirectImages(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.anyMatch(p -> Files.isRegularFile(p) && isImageFile(p.toString()));
        } catch (IOException e) {
            return false;
        }
    }

    private static String joinedName(Path relative) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) sb.append(" - ");
            sb.append(relative.getName(i));
        }
        return sb.toString();
    }

    private static boolean isWorkspaceDir(Path p) {
        return p.getFileName() != null && p.getFileName().toString().startsWith(WORKSPACE_PREFIX);
    }

    // ------------------------------------------------------------------ naming

    /**
     * Decides the output name for a job: the display name, or the custom title plus
     * the last number of the source name. The result is sanitised, must resolve to a
     * direct child of the root, and is made unique within this run.
     */
    private static String resolveOutputName(InputJob job, BatchOptions o, Path root, Set<String> reserved)
            throws IOException {
        String name = job.displayName;
        if (o.useCustomTitle && o.customTitle != null && !o.customTitle.trim().isEmpty()) {
            String number = extractLastNumber(job.leafName);
            name = o.customTitle.trim() + (number.isEmpty() ? "" : " " + number);
        }
        name = sanitizeName(name);
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw new IOException("Invalid output name \"" + name + "\" for " + job.displayName);
        }
        Path dest = root.resolve(name).normalize();
        if (!root.equals(dest.getParent()) || isWorkspaceDir(dest)) {
            throw new IOException("Output name \"" + name + "\" does not resolve inside the root folder");
        }

        String base = name;
        int n = 2;
        while (!reserved.add(name.toLowerCase(Locale.ROOT))) {
            name = base + " (" + n++ + ")";
        }
        return name;
    }

    /** Strips path separators, characters Windows forbids, control characters and trailing dots/spaces. */
    static String sanitizeName(String input) {
        if (input == null) return "";
        String s = input.replaceAll("[\\\\/:*?\"<>|\\u0000-\\u001F]", "").trim();
        while (s.endsWith(".") || s.endsWith(" ")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static Path uniqueChild(Path parent, String name) {
        if (name.isEmpty()) name = "archive";
        Path p = parent.resolve(name);
        int n = 2;
        while (Files.exists(p)) {
            p = parent.resolve(name + "~" + n++);
        }
        return p;
    }

    private static Path uniqueBackupPath(Path target) {
        String fileName = target.getFileName().toString();
        int dot = Files.isDirectory(target) ? -1 : fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        Path p = target.resolveSibling(stem + "_original" + ext);
        int n = 2;
        while (Files.exists(p)) {
            p = target.resolveSibling(stem + "_original" + n++ + ext);
        }
        return p;
    }

    // ------------------------------------------------------------------ processing

    /**
     * Transforms every image of the job into the workspace, then stages and publishes
     * the output. Returns the published path, or null when the folder holds no images
     * or the run was cancelled. Throws when the output could not be produced; in that
     * case nothing in the root has been modified for this job.
     */
    private static Path processFolderAndCreateOutput(InputJob job, Path rootFolder, Path workspace,
                                                     String outputName, BatchOptions o, Set<Path> inputPaths)
            throws IOException {
        Path folder = job.folder;
        int splitMode = o.splitMode;
        boolean isJapaneseManga = o.isJapaneseManga;
        int skipImagesFromStart = o.skipImagesFromStart;
        int skipImagesFromEnd = o.skipImagesFromEnd;
        boolean rotateWideImages = o.rotateWideImages;
        int cropLeft = o.cropLeft, cropRight = o.cropRight, cropTop = o.cropTop, cropBottom = o.cropBottom;
        boolean smartAutoCrop = o.smartAutoCrop;
        int smartAutoCropSensitivity = o.smartAutoCropSensitivity;

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
            logMessage("No images found in: " + job.displayName);
            return null;
        }

        int totalImages = imagePaths.size();
        logMessage("Found " + totalImages + " images in " + job.displayName);

        // Calculate which images to actually process with exceptions
        int firstImageToProcess = Math.min(skipImagesFromStart, totalImages);
        int lastImageToProcess = Math.max(0, totalImages - skipImagesFromEnd);
        int latestSinglePageImageIndex = -99;

        // Transformed pages go into the workspace, never next to the originals.
        Path tempDir = Files.createTempDirectory(workspace, "pages-");
        try {

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
        if (isCancelled() || processedFiles.isEmpty()) {
            return null;
        }

        if (o.outputFormat.equals("folder")) {
            Path finalPath = rootFolder.resolve(outputName);
            Path staged = Files.createTempDirectory(workspace, "out-");
            for (Path file : processedFiles) {
                Files.copy(file, staged.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            publishDirectory(staged, finalPath, job, o.deleteOriginals, inputPaths);
            logMessage("Created output folder: " + finalPath.getFileName());
            return finalPath;
        }

        String extension = "." + o.outputFormat;
        Path finalPath = rootFolder.resolve(outputName + extension);
        Path staged = workspace.resolve(outputName + extension + ".part");
        logMessage("Creating " + o.outputFormat.toUpperCase() + " archive: " + finalPath.getFileName());
        switch (o.outputFormat) {
            case "cbz":
            case "zip":
                ZipArchive.create(processedFiles, staged.toFile());
                verifyZip(staged, processedFiles.size());
                break;
            case "cbr":
            case "rar":
                RarArchive.create(processedFiles, staged.toFile(), BatchProcessor::logMessage);
                if (!Files.isRegularFile(staged) || Files.size(staged) == 0) {
                    throw new IOException("archive was not written");
                }
                break;
            default:
                throw new IOException("Unknown output format: " + o.outputFormat);
        }
        publishFile(staged, finalPath, job, o.deleteOriginals);
        return finalPath;

        } finally {
            try {
                deleteDirectory(tempDir);
            } catch (IOException e) {
                logMessage("Warning: failed to clean up temp directory: " + e.getMessage());
            }
        }
    }

    private static void verifyZip(Path zip, int expectedEntries) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            if (zf.size() != expectedEntries) {
                throw new IOException("archive holds " + zf.size() + " entries, expected " + expectedEntries);
            }
        }
    }

    // ------------------------------------------------------------------ publication

    /**
     * Moves a fully written staged file to its destination. An existing file there is
     * replaced only when it is this job's own source archive and originals are to be
     * deleted; anything else (including another input archive, which has already been
     * extracted by now) is renamed to a unique backup first. A directory in the way is
     * never touched.
     */
    private static void publishFile(Path staged, Path finalPath, InputJob job, boolean deleteOriginals)
            throws IOException {
        if (Files.exists(finalPath)) {
            if (Files.isDirectory(finalPath)) {
                throw new IOException("destination " + finalPath.getFileName() + " is an existing folder");
            }
            boolean ownSource = job.archive != null && Files.isSameFile(finalPath, job.archive);
            if (ownSource && deleteOriginals) {
                logMessage("Replacing original archive: " + finalPath.getFileName());
            } else {
                Path backup = uniqueBackupPath(finalPath);
                Files.move(finalPath, backup);
                logMessage("Preserved existing " + finalPath.getFileName() + " as: " + backup.getFileName());
            }
        }
        moveIntoPlace(staged, finalPath);
    }

    /**
     * Moves a fully staged output directory to its destination. An existing directory
     * there is deleted only when it is this job's own source folder and originals are
     * to be deleted; anything else is renamed to a unique backup first.
     */
    private static void publishDirectory(Path staged, Path finalPath, InputJob job, boolean deleteOriginals,
                                         Set<Path> inputPaths) throws IOException {
        if (Files.exists(finalPath)) {
            boolean ownSource = job.archive == null && Files.isSameFile(finalPath, job.folder);
            if (!ownSource && Files.isDirectory(finalPath) && inputPaths.contains(finalPath.normalize())) {
                throw new IOException("destination " + finalPath.getFileName() + " is another input of this run");
            }
            if (ownSource && deleteOriginals) {
                deleteDirectory(finalPath);
                logMessage("Deleted original folder (replaced by output): " + finalPath.getFileName());
            } else {
                Path backup = uniqueBackupPath(finalPath);
                Files.move(finalPath, backup);
                logMessage("Preserved existing " + finalPath.getFileName() + " as: " + backup.getFileName());
            }
        }
        moveIntoPlace(staged, finalPath);
    }

    private static void moveIntoPlace(Path staged, Path finalPath) throws IOException {
        try {
            Files.move(staged, finalPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, finalPath);
        }
    }

    // ------------------------------------------------------------------ cleanup

    /**
     * Deletes the inputs that may be deleted: only sources of jobs that were published,
     * only when the user asked for it, and an archive only if every job derived from
     * it succeeded. Extracted folders live in the workspace and vanish with it.
     */
    private static void cleanUp(BatchOptions o, Path root, List<ExtractedArchive> archives, List<InputJob> jobs,
                                Map<InputJob, Path> published, List<InputJob> failed, BatchResult result) {
        if (!o.deleteOriginals) {
            return;
        }

        // Original archives.
        for (ExtractedArchive ea : archives) {
            if (!ea.ok) continue;
            List<InputJob> derived = jobs.stream().filter(j -> ea.archive.equals(j.archive)).collect(Collectors.toList());
            boolean allPublished = !derived.isEmpty() && derived.stream().allMatch(published::containsKey);
            if (!allPublished) {
                logMessage("Keeping original archive (not fully processed): " + ea.archive.getFileName());
                continue;
            }
            if (!Files.exists(ea.archive) || result.outputs.stream().anyMatch(out -> samePath(out, ea.archive))) {
                continue; // already replaced by its own output, or moved aside as a backup
            }
            try {
                Files.delete(ea.archive);
                logMessage("Deleted original archive: " + ea.archive.getFileName());
            } catch (IOException e) {
                logMessage("Error deleting archive: " + ea.archive + " - " + e.getMessage());
            }
        }

        // User folders. In flatten mode a top-level folder goes only when all of its volumes succeeded.
        Set<Path> topLevels = new HashSet<>();
        for (InputJob job : jobs) {
            if (job.archive == null && job.topLevel != null) topLevels.add(job.topLevel);
        }
        for (Path top : topLevels) {
            List<InputJob> under = jobs.stream().filter(j -> top.equals(j.topLevel)).collect(Collectors.toList());
            boolean allOk = under.stream().allMatch(published::containsKey);
            if (!allOk) {
                logMessage("Keeping original folder (not fully processed): " + top.getFileName());
                continue;
            }
            if (!Files.exists(top)) {
                continue; // already replaced by a same-name output
            }
            boolean holdsOutput = result.outputs.stream().anyMatch(out -> out.startsWith(top) || samePath(out, top));
            if (holdsOutput || !top.startsWith(root) || top.equals(root)) {
                continue;
            }
            try {
                deleteDirectory(top);
                logMessage("Deleted original folder: " + top.getFileName());
            } catch (IOException e) {
                logMessage("Error deleting folder: " + top + " - " + e.getMessage());
            }
        }
    }

    /** Every path that belongs to an input of this run: source folders (and their top-level parents) and archives. */
    private static Set<Path> inputPaths(List<InputJob> jobs, List<ExtractedArchive> archives) {
        Set<Path> set = new HashSet<>();
        for (InputJob job : jobs) {
            if (job.archive == null) {
                set.add(job.folder.normalize());
                if (job.topLevel != null) set.add(job.topLevel.normalize());
            }
        }
        for (ExtractedArchive ea : archives) {
            set.add(ea.archive.normalize());
        }
        return set;
    }

    private static boolean samePath(Path a, Path b) {
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.normalize().equals(b.normalize());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isCancelled() {
        return Thread.currentThread().isInterrupted() || (ui != null && ui.isCancelled());
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

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
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
        String lowerCase = filePath.toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTENSIONS) {
            if (lowerCase.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArchiveFile(String filePath) {
        String lowerCase = filePath.toLowerCase(Locale.ROOT);
        for (String ext : ARCHIVE_EXTENSIONS) {
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
}
