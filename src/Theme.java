import java.awt.Color;
import java.awt.EventQueue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Light and dark colours for the hand-painted windows, following the macOS appearance. The appearance is read at
 * startup and, while the window is visible, every few seconds by its poll (never by a thread of its own).
 */
final class Theme {

    private Theme() {
    }

    record Palette(Color window, Color row, Color rowSelected, Color text, Color secondary, Color separator,
                   Color accent, Color accentText, Color ok, Color bad, Color button, Color buttonBorder,
                   Color buttonText, Color chip, Color chipOn, Color chipOnText, Color track, Color footer) {}

    static final Palette LIGHT = new Palette(
            new Color(0xF5F5F7), Color.WHITE, new Color(0xE3EEFF), new Color(0x1D1D1F), new Color(0x6E6E73),
            new Color(0xE5E5EA), new Color(0x0A84FF), Color.WHITE, new Color(0x248A3D), new Color(0xD70015),
            Color.WHITE, new Color(0xC7C7CC), new Color(0x1D1D1F), new Color(0xE9E9EE), new Color(0x1D1D1F),
            Color.WHITE, new Color(0xE5E5EA), new Color(0xFAFAFA));

    static final Palette DARK = new Palette(
            new Color(0x1E1E1E), new Color(0x2A2A2A), new Color(0x1F3A5F), new Color(0xF5F5F7), new Color(0x98989D),
            new Color(0x3A3A3C), new Color(0x0A84FF), Color.WHITE, new Color(0x32D74B), new Color(0xFF453A),
            new Color(0x3A3A3C), new Color(0x4A4A4E), new Color(0xF5F5F7), new Color(0x3A3A3C), new Color(0xF5F5F7),
            new Color(0x1D1D1F), new Color(0x3A3A3C), new Color(0x232323));

    private static volatile boolean dark = false; // set by refresh() off the EDT at startup
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    static boolean isDark() {
        return dark;
    }

    static Palette current() {
        return dark ? DARK : LIGHT;
    }

    /** For --preview renders: force one appearance without notifying. */
    static void force(boolean isDark) {
        dark = isDark;
    }

    /** Runs on the EDT whenever the appearance flips. */
    static void addListener(Runnable onChangeEdt) {
        listeners.add(onChangeEdt);
    }

    /** Re-reads the system appearance; call off the EDT (it starts a process). */
    static void refresh() {
        update(readSystemDark());
    }

    static void update(boolean nowDark) {
        if (nowDark == dark) return;
        dark = nowDark;
        for (Runnable r : listeners) EventQueue.invokeLater(r);
    }

    /** `defaults read -g AppleInterfaceStyle` prints "Dark" in dark mode and fails in light mode. */
    private static boolean readSystemDark() {
        try {
            Process p = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && out.equalsIgnoreCase("Dark");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
