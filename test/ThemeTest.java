import java.awt.Color;
import java.lang.reflect.RecordComponent;

public class ThemeTest {
    public static void main(String[] args) throws Exception {
        for (Theme.Palette p : new Theme.Palette[]{Theme.LIGHT, Theme.DARK}) {
            String name = p == Theme.LIGHT ? "light" : "dark";
            boolean complete = true;
            for (RecordComponent c : Theme.Palette.class.getRecordComponents()) {
                if (c.getAccessor().invoke(p) == null) complete = false;
            }
            T.check("palettesComplete " + name, complete, "a colour is null");
            double text = contrast(p.text(), p.row()), secondary = contrast(p.secondary(), p.row());
            T.check("textContrast " + name, text >= 7 && secondary >= 3, "text " + text + " secondary " + secondary);
            T.check("accentTextContrast " + name, contrast(p.accentText(), p.accent()) >= 3, "");
        }
        T.check("darkIsDark", luminance(Theme.DARK.window()) < luminance(Theme.LIGHT.window()), "");

        // every window's listener hears a change, and only a real change
        int[] heard = {0, 0};
        Theme.force(false);
        Theme.addListener(() -> heard[0]++);
        Theme.addListener(() -> heard[1]++);
        Theme.update(true);
        Theme.update(true);
        javax.swing.SwingUtilities.invokeAndWait(() -> {}); // listeners run on the EDT
        T.check("everyListenerHearsChange", heard[0] == 1 && heard[1] == 1, heard[0] + "/" + heard[1]);
        T.check("updateSetsMode", Theme.isDark(), "");

        // Ajustes "Apariencia": fixed choices ignore the system; unknown settings follow it
        T.check("modeDark", Theme.Mode.DARK.dark(false) && !Theme.Mode.LIGHT.dark(true), "");
        T.check("modeSystem", Theme.Mode.SYSTEM.dark(true) && !Theme.Mode.SYSTEM.dark(false), "");
        T.eq("modeParse", Theme.Mode.DARK, Theme.Mode.parse("dark"));
        T.eq("modeParseUnknown", Theme.Mode.SYSTEM, Theme.Mode.parse(null));
        Theme.setMode(Theme.Mode.LIGHT);
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        T.check("setModeLightAtOnce", !Theme.isDark(), "");
        T.done();
    }

    static double contrast(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    static double luminance(Color c) {
        double[] v = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) v[i] = v[i] <= 0.03928 ? v[i] / 12.92 : Math.pow((v[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }
}
