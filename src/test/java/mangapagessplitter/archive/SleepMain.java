package mangapagessplitter.archive;

/**
 * Test fixture launched as a child JVM by {@link ExternalToolsTest}: sleeps for the
 * given number of seconds, then exits 0. Portable replacement for a POSIX
 * {@code sleep} binary so the process-cancellation tests also run on Windows.
 */
public final class SleepMain {

    private SleepMain() {}

    public static void main(String[] args) throws InterruptedException {
        long seconds = args.length > 0 ? Long.parseLong(args[0]) : 0;
        Thread.sleep(seconds * 1000L);
    }
}
