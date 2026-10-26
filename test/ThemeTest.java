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
