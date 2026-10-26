import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Menu;
import java.awt.MenuItem;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;

/**
 * The interface language: Spanish or English. Code keeps writing the Spanish text, which is also the key of its
 * English translation in en.tsv (gettext style; built from lang/en/<Source>.tsv, one per source file), so a missing translation shows Spanish instead of a blank.
 * {@code LangTest} checks that every literal passed to t()/f() has its line in en.tsv, with the same %-placeholders.
 *
 * <p>Ajustes "Idioma" (setting "language"): Sistema follows the system's first preferred language (Spanish only if
 * it is Spanish), or a fixed Español/English. Until the app calls {@link #set} the language is Spanish, so the
 * tests and previews read Spanish unless they force English.
 */
final class Lang {

    private Lang() {
    }

    enum Choice {
        SYSTEM(Lang.key("Sistema")), ES("Español"), EN("English");

        /** Spanish key (translated); the two language names stay as their own speakers write them. */
        final String label;

        Choice(String label) {
            this.label = label;
        }

        String display() {
            return this == SYSTEM ? t(label) : label;
        }

        static Choice parse(String s) {
            for (Choice c : values()) if (c.name().equalsIgnoreCase(s)) return c;
            return SYSTEM;
        }
    }

    private static final Map<String, String> EN = load();
    private static final Map<String, String> ES = reverse(EN);
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile Choice choice = Choice.SYSTEM;
    private static volatile boolean english = false;

    static boolean isEnglish() {
        return english;
    }

    static Choice choice() {
        return choice;
    }

    static boolean englishFor(Choice c, String systemLanguage) {
        return c == Choice.EN || (c == Choice.SYSTEM && !"es".equals(systemLanguage));
    }

    /** Applies the Ajustes choice; listeners run on the EDT if the language changed. */
    static void set(Choice c) {
        choice = c;
        force(englishFor(c, Locale.getDefault().getLanguage()));
    }

    /** For tests and --preview renders. */
    static void force(boolean nowEnglish) {
        if (nowEnglish == english) return;
        english = nowEnglish;
        for (Runnable r : listeners) EventQueue.invokeLater(r);
    }

    static void addListener(Runnable onChangeEdt) {
        listeners.add(onChangeEdt);
    }

    /**
     * The text in the current language. Also takes text saved earlier in the other language (a failure reason in
     * jobs.tsv), as long as it is a whole entry of the table.
     */
    static String t(String es) {
        if (es == null) return null;
        return english ? EN.getOrDefault(es, es) : ES.getOrDefault(es, es);
    }

    /**
     * Marks a Spanish literal that is translated later, where it is shown (an enum label, a table of texts), so
     * LangTest finds it. Returns it unchanged.
     */
    static String key(String es) {
        return es;
    }

    /** t() then String.format; the Spanish and English lines have the same placeholders in the same order. */
    static String f(String es, Object... args) {
        return String.format(Locale.ROOT, t(es), args);
    }

    /** "1.º" / "1st". */
    static String ordinal(int n) {
        if (!english) return n + ".º";
        int mod100 = n % 100, mod10 = n % 10;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : mod10 == 1 ? "st" : mod10 == 2 ? "nd" : mod10 == 3 ? "rd" : "th";
        return n + suffix;
    }

    // ---------------------------------------------------------------- components that relabel themselves

    /** Sets a label's or button's text and remembers its key, so relabel() can redo it in the other language. */
    static <C extends JComponent> C text(C c, String es) {
        c.putClientProperty("lang", es);
        apply(c);
        return c;
    }

    static <C extends JComponent> C tip(C c, String es) {
        c.putClientProperty("lang.tip", es);
        c.setToolTipText(t(es));
        return c;
    }

    /** Like text(), shown in capitals (System Settings' section headers). */
    static JLabel upper(JLabel l, String es) {
        l.putClientProperty("lang.upper", Boolean.TRUE);
        return text(l, es);
    }

    /** Redoes every remembered text under c (after a language change). */
    static void relabel(Component c) {
        if (c instanceof JComponent j) {
            apply(j);
            Object tip = j.getClientProperty("lang.tip");
            if (tip instanceof String s) j.setToolTipText(t(s));
        }
        if (c instanceof Container k) for (Component child : k.getComponents()) relabel(child);
    }

    private static void apply(JComponent c) {
        if (!(c.getClientProperty("lang") instanceof String es)) return;
        String s = t(es);
        if (Boolean.TRUE.equals(c.getClientProperty("lang.upper"))) s = s.toUpperCase(english ? Locale.ENGLISH : Locale.ROOT);
        if (c instanceof JLabel l) l.setText(s);
        else if (c instanceof AbstractButton b) b.setText(s);
    }

    /** A tray menu item whose label follows the language (the key rides in its name). */
    static <M extends MenuItem> M item(M m, String es) {
        m.setName("lang:" + es);
        m.setLabel(t(es));
        return m;
    }

    static void relabel(Menu menu) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem m = menu.getItem(i);
            String name = m.getName();
            if (name != null && name.startsWith("lang:")) m.setLabel(t(name.substring(5)));
            if (m instanceof Menu sub) relabel(sub);
        }
    }

    // ---------------------------------------------------------------- en.tsv

    /** Spanish → English, one "spanish<TAB>english" line each; \n and \t escaped; # comments. */
    static Map<String, String> parse(BufferedReader in) throws IOException {
        Map<String, String> map = new HashMap<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            map.put(unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)));
        }
        return map;
    }

    static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t");
    }

    static Map<String, String> table() {
        return EN;
    }

    private static Map<String, String> load() {
        try (InputStream in = Lang.class.getResourceAsStream("/en.tsv")) {
            if (in == null) return Map.of();
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static Map<String, String> reverse(Map<String, String> en) {
        Map<String, String> es = new HashMap<>();
        en.forEach((k, v) -> es.putIfAbsent(v, k));
        return es;
    }
}
