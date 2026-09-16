package mangapagessplitter.archive;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExternalToolsTest {

    private static boolean hasSleep() {
        return new File("/bin/sleep").canExecute() || new File("/usr/bin/sleep").canExecute();
    }

    @Test
    void runProcessReturnsExitCodeOfAFinishedProcess() throws Exception {
        assumeTrue(hasSleep(), "needs a POSIX sleep binary");
        int code = ExternalTools.runProcess(new ProcessBuilder("sleep", "0"), () -> false);
        assertEquals(0, code);
    }

    @Test
    void runProcessKillsTheChildWhenCancelled() throws Exception {
        assumeTrue(hasSleep(), "needs a POSIX sleep binary");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            cancelled.set(true);
        });
        canceller.start();

        long start = System.nanoTime();
        assertThrows(IOException.class,
                () -> ExternalTools.runProcess(new ProcessBuilder("sleep", "30"), cancelled::get));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 5_000, "process was killed promptly, took " + elapsedMs + " ms");
    }
}
