import java.awt.Color;
import java.awt.EventQueue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Light (Vista glass) and dark (WMP11) colours for the hand-painted windows; Glass paints the gradients, following the macOS appearance. The appearance is read at
 * startup and, while the window is visible, every few seconds by its poll (never by a thread of its own).
 */
final class Theme {

    private Theme() {
    }

    record Palette(Color window, Color row, Color rowSelected, Color text, Color secondary, Color separator,
                   Color accent, Color accentText, Color ok, Color bad, Color button, Color buttonBorder,
                   Color buttonText, Color chip, Color chipOn, Color chipOnText, Color track, Color footer) {}

    // Vista's light-blue glass
    static final Palette LIGHT = new Palette(
            new Color(0xEEF5FC), Color.WHITE, new Color(0xDFF1FD), new Color(0x0D2A4A), new Color(0x5A7089),
            new Color(0xE6EDF4), new Color(0x2D8FD5), Color.WHITE, new Color(0x1D7A2E), new Color(0xC42B1C),
            new Color(0xE6E6E6), new Color(0x707070), new Color(0x1E395B), new Color(0xF3F3F3), new Color(0xBFE2F8),
            new Color(0x0D2A4A), new Color(0xE2E2E2), new Color(0xDCE9F6));

    // WMP11's glossy black with cyan light
    static final Palette DARK = new Palette(
            new Color(0x0B0B0D), new Color(0x0F1114), new Color(0x0F2D47), new Color(0xE9EEF3), new Color(0x8B96A3),
            new Color(0x1C1F24), new Color(0x1A8AD3), Color.WHITE, new Color(0x6EE07A), new Color(0xFF6B5E),
            new Color(0x2C3037), new Color(0x000000), new Color(0xE6ECF2), new Color(0x1F2226), new Color(0x1E95D8),
            Color.WHITE, new Color(0x15171B), new Color(0x111316));

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
