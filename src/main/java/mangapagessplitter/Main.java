package mangapagessplitter;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import mangapagessplitter.ui.MangaPagesSplitterUI;

import javax.swing.SwingUtilities;
import java.util.prefs.Preferences;

/** Application entry point: applies the saved theme and opens the main window. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        try {
            boolean darkTheme = Preferences.userRoot()
                    .node("MangaPagesSplitter").getBoolean("darkTheme", true);
            if (darkTheme) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> new MangaPagesSplitterUI().setVisible(true));
    }
}
