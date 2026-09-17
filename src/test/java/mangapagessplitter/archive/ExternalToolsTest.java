package mangapagessplitter.archive;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalToolsTest {

    /** A child JVM running {@link SleepMain}; portable across Linux, macOS and Windows. */
    private static ProcessBuilder sleeper(int seconds) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        // Locate the compiled test classes from the fixture itself rather than trusting
        // java.class.path, which some test launchers leave nearly empty.
        String classes = new File(SleepMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        return new ProcessBuilder(javaBin, "-cp", classes, SleepMain.class.getName(), Integer.toString(seconds));
    }

    @Test
    void runProcessReturnsExitCodeOfAFinishedProcess() throws Exception {
        int code = ExternalTools.runProcess(sleeper(0), () -> false);
        assertEquals(0, code);
    }

    @Test
    void runProcessKillsTheChildWhenCancelled() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
            cancelled.set(true);
        });
        canceller.start();

        long start = System.nanoTime();
        assertThrows(IOException.class, () -> ExternalTools.runProcess(sleeper(60), cancelled::get));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 15_000, "process was killed promptly, took " + elapsedMs + " ms");
    }
}
