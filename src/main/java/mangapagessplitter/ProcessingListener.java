package mangapagessplitter;

/**
 * What the engine needs from whoever drives it: a cancellation flag, a sink for
 * human-readable log lines and a progress indicator. Implementations are called
 * from the processing thread and must hop to their own UI thread if needed.
 */
public interface ProcessingListener {

    boolean isCancelled();

    void log(String message);

    void progress(String status, int percentage);
}
