package mangapagessplitter;

/** Container the processed pages are written to. */
public enum OutputFormat {
    CBZ("cbz", "CBZ (Comic Book ZIP)", "CBZ files"),
    CBR("cbr", "CBR (Comic Book RAR)", "CBR files"),
    ZIP("zip", "ZIP archive", "ZIP archives"),
    RAR("rar", "RAR archive", "RAR archives"),
    FOLDER("", "Folder with images (no archive)", "folders with processed images");

    private final String extension;
    private final String label;
    private final String plural;

    OutputFormat(String extension, String label, String plural) {
        this.extension = extension;
        this.label = label;
        this.plural = plural;
    }

    /** File extension without the dot; empty for {@link #FOLDER}. */
    public String extension() {
        return extension;
    }

    /** Human-readable name, e.g. "CBZ (Comic Book ZIP)". */
    public String label() {
        return label;
    }

    /** Plural noun for summaries, e.g. "CBZ files" or "folders with processed images". */
    public String plural() {
        return plural;
    }

    public boolean isArchive() {
        return this != FOLDER;
    }

    /** True for the RAR-based formats, which need an external rar/WinRAR binary. */
    public boolean needsRar() {
        return this == CBR || this == RAR;
    }

    /** The ZIP-based format used when no rar binary is installed; identity otherwise. */
    public OutputFormat fallbackWithoutRar() {
        return switch (this) {
            case CBR -> CBZ;
            case RAR -> ZIP;
            case CBZ, ZIP, FOLDER -> this;
        };
    }
}
