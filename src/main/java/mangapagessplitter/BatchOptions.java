package mangapagessplitter;

import java.util.Objects;

/**
 * Everything the batch engine needs to know about one run. Immutable: build one
 * with {@link #builder()} when the run starts, so the engine never observes a
 * half-updated configuration. {@link Builder#build()} validates the values.
 */
public final class BatchOptions {

    public static final int MIN_SENSITIVITY = 1;
    public static final int MAX_SENSITIVITY = 10;

    private final String rootFolder;
    private final SplitMode splitMode;
    private final boolean japaneseManga;
    private final int skipImagesFromStart;
    private final int skipImagesFromEnd;
    private final boolean rotateWideImages;
    private final OutputFormat outputFormat;
    private final int cropLeft, cropRight, cropTop, cropBottom;
    private final boolean smartAutoCrop;
    private final int smartAutoCropSensitivity;
    private final boolean flattenDirectories;
    private final boolean useCustomTitle;
    private final String customTitle;
    private final boolean deleteOriginals;

    private BatchOptions(Builder b) {
        rootFolder = b.rootFolder;
        splitMode = b.splitMode;
        japaneseManga = b.japaneseManga;
        skipImagesFromStart = b.skipImagesFromStart;
        skipImagesFromEnd = b.skipImagesFromEnd;
        rotateWideImages = b.rotateWideImages;
        outputFormat = b.outputFormat;
        cropLeft = b.cropLeft;
        cropRight = b.cropRight;
        cropTop = b.cropTop;
        cropBottom = b.cropBottom;
        smartAutoCrop = b.smartAutoCrop;
        smartAutoCropSensitivity = b.smartAutoCropSensitivity;
        flattenDirectories = b.flattenDirectories;
        useCustomTitle = b.useCustomTitle;
        customTitle = b.customTitle;
        deleteOriginals = b.deleteOriginals;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Folder whose sub-folders and archives are processed. Never blank. */
    public String rootFolder() { return rootFolder; }
    public SplitMode splitMode() { return splitMode; }
    /** Right-to-left page order when splitting. */
    public boolean japaneseManga() { return japaneseManga; }
    public int skipImagesFromStart() { return skipImagesFromStart; }
    public int skipImagesFromEnd() { return skipImagesFromEnd; }
    public boolean rotateWideImages() { return rotateWideImages; }
    public OutputFormat outputFormat() { return outputFormat; }
    /** Manual crop in pixels; zero when {@link #smartAutoCrop()} is on. */
    public int cropLeft() { return cropLeft; }
    public int cropRight() { return cropRight; }
    public int cropTop() { return cropTop; }
    public int cropBottom() { return cropBottom; }
    public boolean smartAutoCrop() { return smartAutoCrop; }
    /** Between {@link #MIN_SENSITIVITY} and {@link #MAX_SENSITIVITY}. */
    public int smartAutoCropSensitivity() { return smartAutoCropSensitivity; }
    /** Treat every directory that directly contains images as its own volume. */
    public boolean flattenDirectories() { return flattenDirectories; }
    /** When true, {@link #customTitle()} is non-blank and replaces the output name. */
    public boolean useCustomTitle() { return useCustomTitle; }
    /** Trimmed custom title; empty when {@link #useCustomTitle()} is false. */
    public String customTitle() { return customTitle; }
    /** Delete source folders and archives after their output has been published. */
    public boolean deleteOriginals() { return deleteOriginals; }

    /** Mutable staging area; defaults match a fresh UI. */
    public static final class Builder {
        private String rootFolder = "";
        private SplitMode splitMode = SplitMode.WIDE_ONLY;
        private boolean japaneseManga = true;
        private int skipImagesFromStart = 0;
        private int skipImagesFromEnd = 0;
        private boolean rotateWideImages = false;
        private OutputFormat outputFormat = OutputFormat.CBZ;
        private int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
        private boolean smartAutoCrop = false;
        private int smartAutoCropSensitivity = 5;
        private boolean flattenDirectories = false;
        private boolean useCustomTitle = false;
        private String customTitle = "";
        private boolean deleteOriginals = false;

        private Builder() {}

        public Builder rootFolder(String v) { rootFolder = Objects.requireNonNull(v); return this; }
        public Builder splitMode(SplitMode v) { splitMode = Objects.requireNonNull(v); return this; }
        public Builder japaneseManga(boolean v) { japaneseManga = v; return this; }
        public Builder skipImagesFromStart(int v) { skipImagesFromStart = v; return this; }
        public Builder skipImagesFromEnd(int v) { skipImagesFromEnd = v; return this; }
        public Builder rotateWideImages(boolean v) { rotateWideImages = v; return this; }
        public Builder outputFormat(OutputFormat v) { outputFormat = Objects.requireNonNull(v); return this; }
        public Builder cropLeft(int v) { cropLeft = v; return this; }
        public Builder cropRight(int v) { cropRight = v; return this; }
        public Builder cropTop(int v) { cropTop = v; return this; }
        public Builder cropBottom(int v) { cropBottom = v; return this; }
        /** Convenience for all four margins at once. */
        public Builder crop(int left, int right, int top, int bottom) {
            cropLeft = left; cropRight = right; cropTop = top; cropBottom = bottom; return this;
        }
        public Builder smartAutoCrop(boolean v) { smartAutoCrop = v; return this; }
        public Builder smartAutoCropSensitivity(int v) { smartAutoCropSensitivity = v; return this; }
        public Builder flattenDirectories(boolean v) { flattenDirectories = v; return this; }
        public Builder useCustomTitle(boolean v) { useCustomTitle = v; return this; }
        public Builder customTitle(String v) { customTitle = Objects.requireNonNull(v); return this; }
        public Builder deleteOriginals(boolean v) { deleteOriginals = v; return this; }

        /**
         * Validates and freezes the options.
         *
         * @throws IllegalArgumentException when the root folder is blank, a count or a
         *         margin is negative, or the sensitivity is out of range
         */
        public BatchOptions build() {
            if (rootFolder.isBlank()) {
                throw new IllegalArgumentException("rootFolder must not be blank");
            }
            requireNonNegative("skipImagesFromStart", skipImagesFromStart);
            requireNonNegative("skipImagesFromEnd", skipImagesFromEnd);
            requireNonNegative("cropLeft", cropLeft);
            requireNonNegative("cropRight", cropRight);
            requireNonNegative("cropTop", cropTop);
            requireNonNegative("cropBottom", cropBottom);
            if (smartAutoCropSensitivity < MIN_SENSITIVITY || smartAutoCropSensitivity > MAX_SENSITIVITY) {
                throw new IllegalArgumentException("smartAutoCropSensitivity must be between "
                        + MIN_SENSITIVITY + " and " + MAX_SENSITIVITY + ", got " + smartAutoCropSensitivity);
            }
            // Smart autocrop and manual margins are exclusive: the engine only ever sees one.
            if (smartAutoCrop) {
                cropLeft = cropRight = cropTop = cropBottom = 0;
            }
            // A custom title that is blank is the same as no custom title.
            customTitle = customTitle.trim();
            if (customTitle.isEmpty()) {
                useCustomTitle = false;
            } else if (!useCustomTitle) {
                customTitle = "";
            }
            return new BatchOptions(this);
        }

        private static void requireNonNegative(String name, int value) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative, got " + value);
            }
        }
    }
}
