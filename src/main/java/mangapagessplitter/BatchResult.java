package mangapagessplitter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What actually happened during a run. The UI uses it to tell a clean success
 * apart from a partial one or a cancellation; the engine uses the same facts to
 * decide which inputs may be deleted.
 */
public final class BatchResult {

    /** Outputs that were fully written, verified and moved into place. */
    public final List<Path> outputs = new ArrayList<>();

    /** One human-readable line per job or archive that failed. */
    public final List<String> failures = new ArrayList<>();

    /** Non-fatal problems: a page copied unchanged, a format substituted, a leftover not removed. */
    public final List<String> warnings = new ArrayList<>();

    /** Jobs that produced nothing because their folder held no images. */
    public int skipped = 0;

    /** True when the user cancelled before the run finished. Nothing is deleted in that case. */
    public boolean cancelled = false;

    public boolean isCleanSuccess() {
        return !cancelled && failures.isEmpty();
    }

    public String summary() {
        if (cancelled) {
            return "Processing cancelled. No input files were deleted.";
        }
        String warn = warnings.isEmpty() ? "" : " " + warnings.size() + " warning(s).";
        if (failures.isEmpty()) {
            return "Processing completed successfully: " + outputs.size() + " output(s) created." + warn;
        }
        return "Processing finished with " + failures.size() + " error(s): "
                + outputs.size() + " output(s) created. Inputs of failed items were kept." + warn;
    }
}
