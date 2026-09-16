package mangapagessplitter;

import mangapagessplitter.archive.ExternalTools;
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
import java.util.Comparator;
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
                    Path out = processFolderAndCreateOutput(job, root, workspace, outputName, o, inputPaths, result);
                    if (out != null) {
                        // Record what really happened before looking at the cancel flag: the
                        // output is in place (and its source may already be gone).
                        published.put(job, out);
                        result.outputs.add(out);
                        logMessage("Created: " + out.getFileName());
                    }
                    if (isCancelled()) {
                        result.cancelled = true;
                        break;
                    }
                    if (out == null) {
                        // Nothing produced: give the name back so a later job can use it.
                        reservedNames.remove(outputName.toLowerCase(Locale.ROOT));
                        result.skipped++;
                    }
                } catch (IOException e) {
                    if (isCancelled()) {
                        result.cancelled = true;
                        break;
                    }
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
            if (o.deleteOriginals) {
                logMessage("Cleaning up: deleting inputs whose outputs were published...");
            }
            cleanUp(o, root, archives, jobs, published, failed, result);
            if (result.cancelled) {
                logMessage("Processing cancelled during cleanup. " + result.deletedInputs.size()
                        + " input(s) had already been deleted; the rest were kept.");
                return result;
            }

            updateProgress("Complete", 100);
            String outputType = o.outputFormat.equals("folder") ? "folders" : o.outputFormat.toUpperCase() + " files";
            logMessage("Created " + result.outputs.size() + " " + outputType);
            return result;
        } finally {
            try {
                deleteDirectory(workspace);
            } catch (IOException e) {
                warn(result, "failed to remove workspace " + workspace + ": " + e.getMessage());
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
                    RarArchive.extract(archivePath, extractDir, BatchProcessor::logMessage, BatchProcessor::isCancelled);
                } else {
                    ZipArchive.extract(archivePath.toFile(), extractDir.toFile(), BatchProcessor::isCancelled);
                    logMessage("Extracted: " + fileName);
                }
                ea.ok = true;
            } catch (IOException e) {
                if (isCancelled()) {
                    break;
                }
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
                                                     String outputName, BatchOptions o, Set<Path> inputPaths,
                                                     BatchResult result) throws IOException {
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

        // Find the image files. In flatten mode every image-bearing directory is its own
        // volume, so only direct children count; otherwise the whole tree is one volume.
        // Natural order (2 before 10) decides reading order and which pages the
        // skip-from-start/end counters hit.
        try (Stream<Path> files = o.flattenDirectories ? Files.list(folder) : Files.walk(folder)) {
            imagePaths = files
                    .filter(Files::isRegularFile)
                    .filter(path -> isImageFile(path.toString()))
                    .sorted(Comparator.comparing(
                            (Path path) -> folder.relativize(path).toString(), NaturalOrder.INSTANCE))
                    .collect(Collectors.toList());
        }

        if (imagePaths.isEmpty()) {
            logMessage("No images found in: " + job.displayName);
            return null;
        }

        int totalImages = imagePaths.size();
        logMessage("Found " + totalImages + " images in " + job.displayName);

        // Output pages are renumbered in reading order: 001.jpg, 002.jpg, ... (a split
        // spread takes two consecutive numbers). This guarantees the reader's order and
        // rules out name collisions between sub-folders or with pre-existing "_1" names.
        int pageWidth = Math.max(3, String.valueOf(totalImages * 2L).length());
        int[] pageSeq = {0};

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
                        String ext = extensionOf(imagePath);
                        Path firstPage = tempDir.resolve(pageName(++pageSeq[0], pageWidth, ext));
                        Path secondPage = tempDir.resolve(pageName(++pageSeq[0], pageWidth, ext));

                        writeImage(halves[0], ext, firstPage);
                        writeImage(halves[1], ext, secondPage);

                        processedFiles.add(firstPage);
                        processedFiles.add(secondPage);
                        logPageNames(i, imagePath, firstPage, secondPage);
                    } else if (modified) {
                        String ext = extensionOf(imagePath);
                        Path tempFile = tempDir.resolve(pageName(++pageSeq[0], pageWidth, ext));
                        writeImage(img, ext, tempFile);
                        processedFiles.add(tempFile);
                        logPageNames(i, imagePath, tempFile);
                    } else {
                        Path copy = copyUnchanged(imagePath, tempDir, ++pageSeq[0], pageWidth);
                        processedFiles.add(copy);
                        logPageNames(i, imagePath, copy);
                    }
                } else {
                    warn(result, "Unsupported image format, page copied unchanged: " + imagePath.getFileName());
                    processedFiles.add(copyUnchanged(imagePath, tempDir, ++pageSeq[0], pageWidth));
                }
            } catch (IOException e) {
                warn(result, "Page copied unchanged (" + e.getMessage() + "): " + imagePath.getFileName());
                processedFiles.add(copyUnchanged(imagePath, tempDir, ++pageSeq[0], pageWidth));
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
            publishDirectory(staged, finalPath, job, o.deleteOriginals, inputPaths, result);
            logMessage("Created output folder: " + finalPath.getFileName());
            return finalPath;
        }

        String format = o.outputFormat;
        if ((format.equals("cbr") || format.equals("rar")) && ExternalTools.findRarCreator() == null) {
            String fallback = format.equals("cbr") ? "cbz" : "zip";
            warn(result, "No WinRAR / rar installed: writing " + fallback.toUpperCase()
                    + " instead of " + format.toUpperCase() + " for " + outputName);
            format = fallback;
        }
        String extension = "." + format;
        Path finalPath = rootFolder.resolve(outputName + extension);
        Path staged = workspace.resolve(outputName + extension + ".part");
        logMessage("Creating " + format.toUpperCase() + " archive: " + finalPath.getFileName());
        switch (format) {
            case "cbz":
            case "zip":
                ZipArchive.create(processedFiles, staged.toFile(), BatchProcessor::isCancelled);
                verifyZip(staged, processedFiles.size());
                break;
            case "cbr":
            case "rar":
                RarArchive.create(processedFiles, staged.toFile(), BatchProcessor::logMessage, BatchProcessor::isCancelled);
                if (!Files.isRegularFile(staged) || Files.size(staged) == 0) {
                    throw new IOException("archive was not written");
                }
                break;
            default:
                throw new IOException("Unknown output format: " + format);
        }
        publishFile(staged, finalPath, job, o.deleteOriginals, result);
        return finalPath;

        } finally {
            try {
                deleteDirectory(tempDir);
            } catch (IOException e) {
                logMessage("Warning: failed to clean up temp directory: " + e.getMessage());
            }
        }
    }

    private static String extensionOf(Path image) {
        String name = image.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** Zero-padded sequence name: {@code 007.jpg}. The extension is lower-cased for consistency. */
    private static String pageName(int seq, int width, String ext) {
        return String.format("%0" + width + "d.%s", seq, ext.toLowerCase(Locale.ROOT));
    }

    private static Path copyUnchanged(Path image, Path tempDir, int seq, int width) throws IOException {
        Path target = tempDir.resolve(pageName(seq, width, extensionOf(image)));
        Files.copy(image, target);
        return target;
    }

    /** Logs the original-to-output name mapping for the first few pages of a volume. */
    private static void logPageNames(int index, Path original, Path... outputs) {
        if (index < 3) {
            StringBuilder sb = new StringBuilder("Page ").append(original.getFileName()).append(" -> ");
            for (int k = 0; k < outputs.length; k++) {
                if (k > 0) sb.append(", ");
                sb.append(outputs[k].getFileName());
            }
            logMessage(sb.toString());
        } else if (index == 3) {
            logMessage("Renumbering remaining pages...");
        }
    }

    /** Writes the image, failing when no encoder exists for {@code format} (e.g. webp). */
    private static void writeImage(BufferedImage img, String format, Path file) throws IOException {
        if (!ImageIO.write(img, format, file.toFile())) {
            throw new IOException("no encoder for ." + format);
        }
    }

    private static void warn(BatchResult result, String message) {
        logMessage("Warning: " + message);
        result.warnings.add(message);
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
     * moved aside first: to a unique "_original" backup that is kept, or, when it is this
     * job's own source archive and originals are to be deleted, to a temporary backup
     * that is removed only once the output is in place. If the final move fails the
     * original is put back, so no combination of outcomes leaves the user with nothing.
     * A directory in the way is never touched.
     */
    private static void publishFile(Path staged, Path finalPath, InputJob job, boolean deleteOriginals,
                                    BatchResult result) throws IOException {
        if (Files.exists(finalPath) && Files.isDirectory(finalPath)) {
            throw new IOException("destination " + finalPath.getFileName() + " is an existing folder");
        }
        boolean ownSource = Files.exists(finalPath) && job.archive != null && Files.isSameFile(finalPath, job.archive);
        swapIntoPlace(staged, finalPath, ownSource && deleteOriginals, "archive", result);
    }

    /**
     * Moves a fully staged output directory to its destination with the same rules as
     * {@link #publishFile}: the existing directory is moved aside (kept as a backup, or
     * removed after success when it is the job's own source and originals are to be
     * deleted) and restored if the final move fails. A directory that is another input
     * of this run is refused.
     */
    private static void publishDirectory(Path staged, Path finalPath, InputJob job, boolean deleteOriginals,
                                         Set<Path> inputPaths, BatchResult result) throws IOException {
        boolean ownSource = Files.exists(finalPath) && job.archive == null && Files.isSameFile(finalPath, job.folder);
        if (Files.exists(finalPath) && !ownSource && Files.isDirectory(finalPath)
                && inputPaths.contains(finalPath.normalize())) {
            throw new IOException("destination " + finalPath.getFileName() + " is another input of this run");
        }
        swapIntoPlace(staged, finalPath, ownSource && deleteOriginals, "folder", result);
    }

    /**
     * The one place that touches a destination in the root. Order of operations:
     * existing destination -> backup, staged -> destination, then (only if asked and only
     * after success) backup -> deleted. Any failure of the second step restores the backup.
     */
    private static void swapIntoPlace(Path staged, Path finalPath, boolean discardExisting, String kind,
                                      BatchResult result) throws IOException {
        Path backup = null;
        if (Files.exists(finalPath)) {
            backup = uniqueBackupPath(finalPath);
            Files.move(finalPath, backup);
            if (!discardExisting) {
                logMessage("Preserved existing " + finalPath.getFileName() + " as: " + backup.getFileName());
            }
        }
        try {
            moveIntoPlace(staged, finalPath);
        } catch (IOException e) {
            if (backup != null) {
                try {
                    Files.move(backup, finalPath);
                    logMessage("Publication failed; restored original " + kind + ": " + finalPath.getFileName());
                } catch (IOException restore) {
                    logMessage("Publication failed and the original could not be restored; it is kept as: "
                            + backup.getFileName());
                }
            }
            throw e;
        }
        if (backup != null && discardExisting) {
            deleteDirectoryOrFile(backup);
            result.deletedInputs.add(finalPath);   // the original that lived at this path is gone
            logMessage("Replaced original " + kind + ": " + finalPath.getFileName());
        }
    }

    /** Test hook: when set, invoked right before the staged output is moved into the root. */
    static IOAction beforePublishHook = null;

    interface IOAction {
        void run() throws IOException;
    }

    private static void moveIntoPlace(Path staged, Path finalPath) throws IOException {
        if (beforePublishHook != null) {
            beforePublishHook.run();
        }
        try {
            Files.move(staged, finalPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staged, finalPath);
        }
    }

    private static void deleteDirectoryOrFile(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            deleteDirectory(p);
        } else {
            Files.deleteIfExists(p);
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
            if (isCancelled()) {
                result.cancelled = true;
                return;
            }
            try {
                Files.delete(ea.archive);
                result.deletedInputs.add(ea.archive);
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
            if (isCancelled()) {
                result.cancelled = true;
                return;
            }
            try {
                deleteDirectory(top);
                result.deletedInputs.add(top);
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

    static boolean isCancelled() {
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
