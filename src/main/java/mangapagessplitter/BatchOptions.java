package mangapagessplitter;

/**
 * Everything the batch engine needs to know about one run. Plain mutable fields:
 * the UI fills them from its widgets, tests fill them directly.
 */
public final class BatchOptions {

    /** Folder whose sub-folders and archives are processed. */
    public String rootFolder = "";

    /** 0 = only split wide images, 1 = never split, 2 = split every image. */
    public int splitMode = 0;
    public boolean isJapaneseManga = true;
    public int skipImagesFromStart = 0;
    public int skipImagesFromEnd = 0;
    public boolean rotateWideImages = false;

    /** "cbz", "cbr", "zip", "rar" or "folder". */
    public String outputFormat = "cbz";

    /** Manual crop in pixels; ignored when {@link #smartAutoCrop} is on. */
    public int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
    public boolean smartAutoCrop = false;
    public int smartAutoCropSensitivity = 5;

    /** Treat every directory that directly contains images as its own volume. */
    public boolean flattenDirectories = false;

    /** Replace the output name with {@code customTitle + " " + <last number of the source name>}. */
    public boolean useCustomTitle = false;
    public String customTitle = "";

    /** Delete source folders and archives after their output has been published. */
    public boolean deleteOriginals = false;
}
