package instatray.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Asks yt-dlp and gallery-dl (their Python packages, through each library's own interpreter) whether URLs no rule
 * knows are single videos/posts they can download: the catch-all sites {@link Site#OTHER},
 * {@link Site#OTHER_GALLERY} and {@link Site#EMBED}. Answers are cached per URL (only complete ones).
 */
public final class Probe {

    /**
     * gallery-dl categories handled by OTHER_GALLERY: platforms where creators post their own work. Aggregators,
     * boorus, manga/doujin sites and file hosts that re-host others' work stay out on purpose.
     */
    public static final List<String> CREATOR_PLATFORMS = List.of(
            "500px", "artstation", "boosty", "cara", "cien", "fansly", "flickr", "foriio", "furaffinity",
            "inkbunny", "itaku", "lofter", "pexels", "pillowfort", "plurk", "poipiku", "postype", "skeb",
            "smugmug", "sofurry", "subscribestar", "vk", "vsco", "weasyl", "xfolio");

    /**
     * Asks yt-dlp which extractor takes each URL (the first suitable one, as yt-dlp itself picks). Accepted:
     * working extractors that return a single video, or "any" whose name doesn't say playlist/channel/etc.
     * Prints "ExtractorKey<TAB>id", "*" (only the Generic extractor would try it) or "-" per URL.
     */
    private static final String YT_DLP_PROBE = """
            import sys
            from yt_dlp.extractor import gen_extractor_classes
            ies = list(gen_extractor_classes())
            lists = ('playlist', 'user', 'channel', 'tab', 'series', 'season', 'album', 'show', 'search', 'feed')
            for url in sys.argv[1:]:
                ie = next((i for i in ies if i.suitable(url)), None)
                ok = (ie is not None and ie.ie_key() != 'Generic' and ie.working()
                      and (ie._RETURN_TYPE == 'video'
                           or ie._RETURN_TYPE == 'any' and not any(w in ie.ie_key().lower() for w in lists)))
                generic = ie is not None and ie.ie_key() == 'Generic'
                print(ie.ie_key() + '\t' + str(ie.get_temp_id(url) or '') if ok else '*' if generic else '-', flush=True)
            """;

    /** Asks gallery-dl whether each URL is a single post on one of the platforms in argv[1]. Prints category or "-". */
    private static final String GALLERY_DL_PROBE = """
            import sys
            from gallery_dl import extractor
            platforms = set(sys.argv[1].split(','))
            posts = {'post', 'artwork', 'image', 'media', 'submission', 'work', 'article', 'video', 'wall-post'}
            for url in sys.argv[2:]:
                try:
                    ex = extractor.find(url)
                except Exception:
                    ex = None
                ok = ex is not None and ex.category in platforms and ex.subcategory in posts
                print(ex.category if ok else '-', flush=True)
            """;

    private final Function<String, String> python;
    private final Map<String, String> environment;
    private final Consumer<String> log;
    /** Library answers per URL; empty = not supported. Only complete answers are cached. */
    private final Map<String, Optional<Link>> cache = new ConcurrentHashMap<>();
    /** Unsupported URLs only yt-dlp's Generic extractor would try: EMBED links when asked for. */
    private final Set<String> genericUrls = ConcurrentHashMap.newKeySet();

    /**
     * @param python      the Python interpreter that has a tool's package ("yt-dlp" or "gallery-dl" → its path, or
     *                    null when missing); {@link #shebangPython} reads it from a pip/Homebrew script
     * @param environment variables put into the probes' environment (e.g. {@link Tools#environment()})
     * @param log         receives a line when a probe fails
     */
    public Probe(Function<String, String> python, Map<String, String> environment, Consumer<String> log) {
        this.python = python;
        this.environment = Map.copyOf(environment);
        this.log = log;
    }

    /** The interpreter in a Python script's shebang (a Homebrew/pip tool's own venv), or null. */
    public static String shebangPython(String toolPath) {
        if (toolPath == null) return null;
        try (BufferedReader r = Files.newBufferedReader(Path.of(toolPath).toRealPath(), StandardCharsets.UTF_8)) {
            String first = r.readLine();
            if (first == null || !first.startsWith("#!")) return null;
            String interpreter = first.substring(2).trim();
            return Files.isExecutable(Path.of(interpreter)) ? interpreter : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Catch-all links for the URLs: first whatever yt-dlp takes, then creator-platform posts gallery-dl takes; with
     * {@code withEmbeds}, also EMBED links for what only yt-dlp's Generic extractor would try. Blocks while the
     * probes run (at most 20 s each).
     */
    public List<Link> links(List<String> urls, boolean withEmbeds) {
        List<String> todo = urls.stream().filter(u -> !cache.containsKey(u)).toList();
        if (!todo.isEmpty()) {
            if (cache.size() > 1000) {
                cache.clear();
                genericUrls.clear();
            }
            List<String> ytdl = run("yt-dlp", YT_DLP_PROBE, List.of(), todo);
            List<String> rest = new ArrayList<>();
            for (int i = 0; i < todo.size(); i++) {
                String url = todo.get(i);
                String r = ytdl == null ? "-" : ytdl.get(i);
                if (r.equals("-") || r.equals("*")) {
                    if (r.equals("*")) genericUrls.add(url);
                    rest.add(url);
                    continue;
                }
                String[] f = r.split("\t", 2);
                String id = f.length > 1 && !f[1].isBlank() ? f[1] : Integer.toHexString(url.hashCode());
                cache.put(url, Optional.of(new Link(Site.OTHER, "ytdl:" + f[0] + ":" + id, url)));
            }
            List<String> gdl = rest.isEmpty() ? List.of()
                    : run("gallery-dl", GALLERY_DL_PROBE, List.of(String.join(",", CREATOR_PLATFORMS)), rest);
            for (int i = 0; i < rest.size() && gdl != null && ytdl != null; i++) {
                String url = rest.get(i);
                String r = gdl.get(i);
                cache.put(url, r.equals("-") ? Optional.empty()
                        : Optional.of(new Link(Site.OTHER_GALLERY, "gdl:" + r + ":" + url, url)));
            }
            if (gdl != null && ytdl == null) { // yt-dlp couldn't answer: keep gallery-dl's hits, retry the rest later
                for (int i = 0; i < rest.size(); i++) {
                    if (!gdl.get(i).equals("-")) {
                        cache.put(rest.get(i), Optional.of(new Link(Site.OTHER_GALLERY,
                                "gdl:" + gdl.get(i) + ":" + rest.get(i), rest.get(i))));
                    }
                }
            }
        }
        List<Link> links = new ArrayList<>();
        for (String u : urls) {
            Optional<Link> l = cache.get(u);
            if (l != null && l.isPresent()) links.add(l.get());
            else if (l != null && withEmbeds && genericUrls.contains(u)) {
                links.add(new Link(Site.EMBED, "embed:" + Links.host(u) + ":" + Integer.toHexString(u.hashCode()), u));
            }
        }
        return links;
    }

    /** Runs a probe script with the library's own Python; one output line per URL, or null if it failed. */
    private List<String> run(String tool, String script, List<String> args, List<String> urls) {
        String py = python.apply(tool);
        if (py == null) return null;
        List<String> cmd = new ArrayList<>(List.of(py, "-I", "-c", script));
        cmd.addAll(args);
        cmd.addAll(urls);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.environment().putAll(environment);
            Process p = pb.start();
            CompletableFuture.delayedExecutor(20, TimeUnit.SECONDS).execute(p::destroyForcibly);
            List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.add(line.strip());
            }
            if (p.waitFor() == 0 && out.size() == urls.size()) return out;
            log.accept("Probe " + tool + " failed (exit " + p.exitValue() + ", " + out.size() + " answers)");
        } catch (Exception e) {
            log.accept("Probe " + tool + " failed: " + e);
        }
        return null;
    }
}
