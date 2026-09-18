package mangapagessplitter;

/** How the engine decides whether to cut an image into two pages. */
public enum SplitMode {
    /** Split only images that are wider than tall, guarding special spreads. */
    WIDE_ONLY("Only split wide images"),
    /** Never split; pages are copied (and optionally cropped or rotated) as they are. */
    NEVER("No split"),
    /** Split every image in half, except the configured exceptions. */
    ALL("Split all images");

    private final String label;

    SplitMode(String label) {
        this.label = label;
    }

    /** Short human-readable name for logs and previews. */
    public String label() {
        return label;
    }

    /** True when this mode can produce two pages from one image. */
    public boolean splits() {
        return this != NEVER;
    }
}
