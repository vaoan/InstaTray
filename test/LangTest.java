import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LangTest {
    /** The Spanish literal given to Lang.t/f/key/text/tip/upper/item (first string argument). */
    static final Pattern CALL = Pattern.compile(
            "Lang\\.(?:t|f|key|text|tip|upper|item)\\((?:[^\"()]*?,\\s*)?\"((?:[^\"\\\\]|\\\\.)*)\"");
    static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]");

    public static void main(String[] args) throws Exception {
        Map<String, String> en = Lang.table();
        T.check("tableLoaded", !en.isEmpty(), "en.tsv not on the classpath");

        Set<String> used = new HashSet<>();
        List<String> missing = new ArrayList<>();
        try (var files = Files.list(Paths.get("src"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                Matcher m = CALL.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    String es = unjava(m.group(1));
                    used.add(es);
                    if (!en.containsKey(es)) missing.add(p.getFileName() + ": " + es);
                }
            }
        }
        T.check("everyTextTranslated", missing.isEmpty(), "\n  " + String.join("\n  ", missing));
        List<String> stale = en.keySet().stream().filter(k -> !used.contains(k)).sorted().toList();
        T.check("noUnusedTranslations", stale.isEmpty(), "\n  " + String.join("\n  ", stale));
        List<String> badFormat = new ArrayList<>();
        en.forEach((es, eng) -> {
            if (!placeholders(es).equals(placeholders(eng))) badFormat.add(es + " -> " + eng);
        });
        T.check("samePlaceholders", badFormat.isEmpty(), "\n  " + String.join("\n  ", badFormat));

        // switching
        Lang.force(false);
        T.eq("spanishByDefault", "Sistema", Lang.t("Sistema"));
        Lang.force(true);
        T.eq("english", "System", Lang.t("Sistema"));
        T.eq("unknownStaysSpanish", "no está en la tabla", Lang.t("no está en la tabla"));
        Lang.force(false);
        T.eq("savedEnglishShownInSpanish", "Sistema", Lang.t("System"));
        T.check("systemSpanish", !Lang.englishFor(Lang.Choice.SYSTEM, "es") && Lang.englishFor(Lang.Choice.SYSTEM, "en"), "");
        T.check("fixedChoice", Lang.englishFor(Lang.Choice.EN, "es") && !Lang.englishFor(Lang.Choice.ES, "en"), "");
        T.eq("parseUnknown", Lang.Choice.SYSTEM, Lang.Choice.parse(null));
        T.eq("ordinalEs", "2.º", Lang.ordinal(2));
        Lang.force(true);
        T.eq("ordinalEn", "1st 2nd 3rd 4th 11th 12th 13th 21st 22nd 101st",
                String.join(" ", List.of(1, 2, 3, 4, 11, 12, 13, 21, 22, 101).stream().map(Lang::ordinal).toList()));
        Lang.force(false);
        Map<String, String> parsed = Lang.parse(new BufferedReader(new StringReader("# c\na\\nb\tx\\ty\n\nno tab\n")));
        T.check("parseEscapes", parsed.size() == 1 && "x\ty".equals(parsed.get("a\nb")), parsed.toString());
        T.done();
    }

    static List<String> placeholders(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** The Java escapes used in the literals. */
    static String unjava(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
