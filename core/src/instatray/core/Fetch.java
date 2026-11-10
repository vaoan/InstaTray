package instatray.core;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Downloads one link with yt-dlp or gallery-dl ({@link Site#engine()}) and reports progress while it runs. Blocking;
 * one call per download. Nothing is ever overwritten (a file already there counts as downloaded).
 *
 * <p>Cancelling: interrupt the thread that called {@link #run}. The tool and its child processes are killed (and
 * waited for), the partial files it announced are deleted, and {@code run} returns {@link Outcome#CANCELLED} with
 * the thread's interrupt status cleared (the interrupt was the request, and it has been carried out).
 */
public final class Fetch {

    private Fetch() {
    }

    /** gallery-dl file names; "{num:?_//}" adds "_N" only for multi-file posts, so single files don't end in "_None". */
    public static final String GALLERY_DL_FILENAME = "{category}_{id}{num:?_//}.{extension}";
    /** Video/animation files kept from gallery-dl posts ("zip" = Pixiv ugoira, converted to mp4 by gallery-dl). */
    public static final String GALLERY_DL_FILTER = "extension in ('mp4', 'webm', 'mov', 'm4v', 'mkv', 'gif', 'zip')";
    /** Most items a catch-all yt-dlp link may download (an extractor can return an album or a multi-part episode). */
    public static final int CATCH_ALL_MAX_ITEMS = 10;
    /** yt-dlp prints the title on a line starting with this, before downloading (reported through Listener.title). */
    public static final String TITLE_PREFIX = "TITLE ";
    /**
     * EMBED downloads print what the Generic extractor found on a line starting with this (passed to Listener.line):
     * "extractor · format · protocol · resolution · size · url".
     */
    public static final String FOUND_PREFIX = "FOUND ";

    // Reasons of FAILED_PERMANENT results (callers compare them to show their own text)
    public static final String NO_VIDEO_IN_POST = "The post has no video or animation (only images)";
    public static final String PIXIV_LOGIN = "Pixiv needs a one-time login: gallery-dl oauth:pixiv";
    public static final String LOGIN_NEEDED = "The site asks for a login: export fresh cookies";
    public static final String ANTI_BOT = "The site blocks automated downloads (anti-bot challenge)";
    public static final String PAGE_ONLY = "The site only plays this video on its own page (HTTP 403)";
    /** EMBED only: most likely the page was copied for its text, so callers may stay silent. */
    public static final String NO_VIDEO_ON_PAGE = "No video found on the page";
    public static final String NOT_FOUND = "Not found, removed or private";
    public static final String LIVE = "It is a live stream: not recorded";
    public static final String NO_VIDEO = "The post has no video";
    /** Suffix of the reason when the tool is missing: "yt-dlp is not installed". */
    public static final String NOT_INSTALLED = " is not installed";
    /** Reason of a FAILED result that ran past {@link Request#limit()}. */
    public static final String TIMED_OUT = "Took longer than the time limit";
    /** Reason of a CANCELLED result. */
    public static final String CANCELLED = "Cancelled";

    /**
     * What to download and where.
     *
     * @param nameTag nullable: when given, every saved file's name gets " [nameTag]" right before its extension
     *                (characters not allowed in file names become "_"); when null, names are the site's templates
     * @param cookies nullable: a cookie file (see {@link Cookies}); the tool gets a temporary copy with only the
     *                link's cookies, deleted afterwards
     * @param limit   nullable: no time limit; otherwise the download is stopped (and FAILED) after this long
     */
    public record Request(Link link, Path outDir, String nameTag, Path cookies, Duration limit) {}

    /** What a running download reports. Called from a thread {@link #run} starts, one call at a time. */
    public interface Listener {
        /** fraction 0..1 (never 1 before the end), or -1 while unknown (gallery-dl reading a post). */
        void progress(double fraction, String speed, String eta);

        /** The video's title (yt-dlp) or the name of the file being written (gallery-dl). */
        void title(String title);

        /** Every other output line of the tool (progress and title lines excluded), as printed. */
        void line(String rawOutputLine);

        /** A file the tool is about to write ("[download] Destination: …"). */
        default void destination(String path) {
        }

        /** Diagnostics that are not tool output (partial files deleted…). */
        default void log(String message) {
        }
    }

    public enum Outcome { DONE, FAILED, FAILED_PERMANENT, CANCELLED }

    /**
     * @param reason in the tools' words, or one of this class's constants for permanent failures; "" when DONE
     * @param files  the saved files (DONE only)
     * @param title  the last title reported, "" if none
     */
    public record Result(Outcome outcome, String reason, List<Path> files, String title) {
        public Result {
            files = List.copyOf(files);
        }
    }

    /**
     * Downloads the link (see the class comment for cancelling). Returns DONE when the tool ended well and saved (or
     * already had) at least one file; otherwise a failure whose reason says why. Missing tool → FAILED_PERMANENT.
     *
     * @throws InterruptedException never: an interrupt cancels the download and returns CANCELLED instead; declared
     *                              so callers handle cancellation where they call it
     */
    public static Result run(Request r, Tools tools, Listener l) throws InterruptedException {
        if (Thread.interrupted()) return new Result(Outcome.CANCELLED, CANCELLED, List.of(), "");
        boolean gallery = r.link().site().engine() == Engine.GALLERY_DL;
        String tool = gallery ? "gallery-dl" : "yt-dlp";
        if ((gallery ? tools.galleryDl() : tools.ytDlp()) == null) {
            return new Result(Outcome.FAILED_PERMANENT, tool + NOT_INSTALLED, List.of(), "");
        }
        Path cookieCopy = null;
        try {
            Process p;
            try {
                Files.createDirectories(r.outDir());
                if (r.cookies() != null) {
                    // yt-dlp rewrites the cookie jar on exit, so it gets a copy holding only this link's cookies:
                    // the original stays intact and a full-browser export never leaks other sites' sessions
                    cookieCopy = Files.createTempFile("instatray-cookies-", ".txt"); // owner-only on POSIX
                    Cookies.writeDomain(r.cookies(), cookieCopy, Cookies.domainsFor(r.link()));
                }
                ProcessBuilder pb = new ProcessBuilder(command(r, tools, cookieCopy)).redirectErrorStream(true);
                pb.environment().putAll(tools.environment());
                p = pb.start();
            } catch (IOException e) {
                l.line("ERROR " + e);
                return new Result(Outcome.FAILED, String.valueOf(e.getMessage()), List.of(), "");
            }
            return follow(p, r, l, gallery);
        } finally {
            if (cookieCopy != null) {
                try {
                    Files.deleteIfExists(cookieCopy);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Reads the running tool's output on its own thread while this one waits (and can be interrupted). */
    private static Result follow(Process p, Request r, Listener l, boolean gallery) {
        Output out = new Output(gallery, l);
        Thread reader = new Thread(() -> out.read(p), "fetch-output");
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = true;
            if (r.limit() == null) reader.join();
            else finished = reader.join(r.limit());
            if (!finished) {
                stop(p, reader, out, l);
                return new Result(Outcome.FAILED, TIMED_OUT, List.of(), out.title);
            }
            int exit = p.waitFor();
            if (out.error != null) {
                l.line("ERROR " + out.error);
                return new Result(Outcome.FAILED, String.valueOf(out.error.getMessage()), List.of(), out.title);
            }
            if (exit == 0 && !out.files.isEmpty()) {
                return new Result(Outcome.DONE, "", out.files.stream().map(Path::of).toList(), out.title);
            }
            return Failure.classify(r.link().site(), exit, out.tail, String.join("\n", out.all), out.title);
        } catch (InterruptedException e) {
            stop(p, reader, out, l);
            return new Result(Outcome.CANCELLED, CANCELLED, List.of(), out.title);
        }
    }

    /** Kills the tool, lets the reader finish, deletes what it left half-written. Clears the interrupt status. */
    private static void stop(Process p, Thread reader, Output out, Listener l) {
        out.stopping = true;
        killAndWait(p);
        while (reader.isAlive()) {
            try {
                reader.join();
            } catch (InterruptedException again) {
                // already stopping
            }
        }
        Thread.interrupted();
        deletePartials(out.destinations, l);
    }

    /** The command line of a download: tool, options, cookie copy (nullable), URL. */
    public static List<String> command(Request r, Tools tools, Path cookiesCopy) {
        Site site = r.link().site();
        boolean gallery = site.engine() == Engine.GALLERY_DL;
        String tag = r.nameTag() == null ? null : safeTag(r.nameTag());
        String outDir = r.outDir().toString();
        List<String> cmd = gallery ? new ArrayList<>(List.of(
                tools.galleryDl(),
                "--no-colors",
                // terminal mode prints progress (pipe mode prints none); full paths; progress from the start
                "-o", "output.mode=terminal", "-o", "output.shorten=false", "-o", "downloader.progress=0",
                "-D", outDir,
                "-f", tag == null ? GALLERY_DL_FILENAME : withTag(GALLERY_DL_FILENAME, ".{extension}",
                        tag.replace("{", "{{").replace("}", "}}")),
                "--filter", GALLERY_DL_FILTER, // video posts only; images are skipped
                "--ugoira", "mp4"))
                : new ArrayList<>(List.of(
                tools.ytDlp(),
                // --print implies quiet mode, which hides progress: force it back on
                "--newline", "--no-quiet", "--progress", "--no-overwrites", "--restrict-filenames",
                "--progress-template",
                "download:" + Progress.PREFIX + "%(progress.downloaded_bytes)s %(progress.total_bytes,progress.total_bytes_estimate)s"
                        + " %(progress.speed)s %(progress.eta)s",
                "--no-playlist",
                "--match-filter", "!is_live", // never start recording a live stream
                "-f", "bv*+ba/b",
                // H.264 + AAC first: QuickTime on Apple Silicon cannot play AV1/VP9
                "-S", "vcodec:h264,res,acodec:aac",
                "--merge-output-format", "mp4",
                // sites that only offer VP8/VP9 webm (e.g. 9GAG) get converted; files already in mp4 are left alone
                "--recode-video", "mp4",
                "-o", outDir + "/" + (tag == null ? site.template()
                        : withTag(site.template(), ".%(ext)s", tag.replace("%", "%%"))),
                "--print", "after_move:filepath",
                "--print", "before_dl:" + TITLE_PREFIX + "%(title)s",
                "--no-simulate"));
        if (tools.ffmpeg() != null && !gallery) cmd.addAll(List.of("--ffmpeg-location", tools.ffmpeg()));
        // a catch-all extractor may hand back a list (album, episode with parts): cap it
        if (site == Site.OTHER || site == Site.EMBED) {
            cmd.addAll(List.of("--playlist-items", "1:" + CATCH_ALL_MAX_ITEMS));
        }
        // what the Generic extractor found: extractor, format, protocol, size
        if (site == Site.EMBED) {
            cmd.addAll(List.of("--print", "before_dl:" + FOUND_PREFIX + "%(extractor_key)s · %(format_id)s · "
                    + "%(protocol)s · %(resolution)s · %(filesize,filesize_approx)s · %(url).120s"));
        }
        if (cookiesCopy != null) cmd.addAll(List.of("--cookies", cookiesCopy.toString()));
        cmd.add(r.link().url());
        return cmd;
    }

    /** "name.%(ext)s" → "name [tag].%(ext)s". */
    private static String withTag(String template, String extension, String tag) {
        if (!template.endsWith(extension)) return template + " [" + tag + "]";
        return template.substring(0, template.length() - extension.length()) + " [" + tag + "]" + extension;
    }

    /** The tag with characters file names can't hold (on any system) replaced by "_". */
    static String safeTag(String tag) {
        return tag.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    /** "/Users/x/a.mp4", "C:\Users\x\a.mp4" or "\\server\share\a.mp4": a full file path printed by a tool. */
    public static boolean isAbsolutePath(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.startsWith("/")) return true;
        if (s.startsWith("\\\\")) return true;
        return s.length() > 2 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':' && (s.charAt(2) == '\\' || s.charAt(2) == '/');
    }

    /**
     * Converts a file to mp4 H.264 + AAC with ffmpeg unless it already is mp4 (or ffmpeg is null): GIFs and webm
     * become mp4 and the original is removed. Returns the final path (the same one when nothing was done).
     *
     * @throws IOException with ffmpeg's error output (or the failure's description) when the conversion failed
     */
    public static Path toPlayableMp4(Path file, String ffmpeg) throws IOException {
        String name = file.toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".mp4") || ffmpeg == null) return file;
        Path out = Path.of(name.replaceFirst("\\.[A-Za-z0-9]+$", "") + ".mp4");
        List<String> cmd = List.of(ffmpeg, "-y", "-loglevel", "error", "-i", name,
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:a", "aac", "-movflags", "+faststart", out.toString());
        String err;
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            err = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && Files.exists(out)) {
                Files.deleteIfExists(file);
                return out;
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException(e.toString(), e);
        }
        throw new IOException(err);
    }

    /**
     * Stops the tool and its children and waits until they are really gone: a dying yt-dlp can still reopen its
     * .part file for the next fragment, so cleaning up earlier leaves the file behind.
     */
    static void killAndWait(Process p) {
        if (p == null) return;
        List<ProcessHandle> children = p.descendants().toList();
        children.forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                children.forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
            }
            for (ProcessHandle c : children) c.onExit().get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /**
     * Removes what a stopped download left behind (.part, .ytdl, unmerged streams). macOS privacy (TCC) may not let
     * the app list the folder, but files can be removed by exact path, so it deletes the paths the tool announced.
     */
    static void deletePartials(List<String> destinations, Listener l) {
        List<String> paths;
        synchronized (destinations) {
            paths = new ArrayList<>(destinations);
        }
        for (String d : paths) {
            for (String candidate : new String[]{d, d + ".part", d + ".ytdl", d + ".part.ytdl"}) {
                try {
                    if (Files.deleteIfExists(Path.of(candidate))) l.log("Deleted partial " + candidate);
                } catch (IOException | RuntimeException e) {
                    l.log("ERROR deleting " + candidate + ": " + e);
                }
            }
        }
    }

    /** Next piece of output ending in "\n" or "\r"; null at end of stream. */
    static String readSegment(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = r.read()) != -1) {
            if (c == '\n' || c == '\r') return sb.toString();
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /** What one run's output said: files, announced destinations, title, last line, the last lines. */
    private static final class Output {
        private static final int MAX_LINES = 400;
        private final boolean gallery;
        private final Listener l;
        private final Progress progress = new Progress();
        final List<String> files = Collections.synchronizedList(new ArrayList<>());
        final List<String> destinations = Collections.synchronizedList(new ArrayList<>());
        final Deque<String> all = new ArrayDeque<>();
        volatile String title = "";
        volatile String tail = "";
        volatile IOException error;
        volatile boolean stopping;

        Output(boolean gallery, Listener l) {
            this.gallery = gallery;
            this.l = l;
        }

        void read(Process p) {
            try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                String line;
                // gallery-dl redraws its progress with "\r", so the output is split on "\r" as well as "\n"
                while ((line = readSegment(r)) != null) {
                    if (!line.isBlank()) take(line);
                }
            } catch (IOException e) {
                if (!stopping) error = e; // killing the tool usually ends the read with an error
            }
        }

        private void take(String line) {
            if (gallery) {
                Progress.Update u = Progress.galleryDl(line);
                if (u != null) {
                    l.progress(u.fraction(), u.speed(), u.eta());
                    return;
                }
                String trimmed = line.strip();
                if (line.startsWith("  ") && isAbsolutePath(trimmed)) { // file about to be written
                    announce(trimmed);
                    title = Path.of(trimmed).getFileName().toString();
                    l.title(title);
                }
                if ((trimmed.startsWith("✔ ") || trimmed.startsWith("* ")) && isAbsolutePath(trimmed.substring(2))) {
                    String f = trimmed.substring(2);
                    if (Files.exists(Path.of(f))) files.add(f);
                }
            }
            if (line.startsWith(TITLE_PREFIX)) {
                title = line.substring(TITLE_PREFIX.length()).strip();
                l.title(title);
                return;
            }
            if (line.startsWith(FOUND_PREFIX)) {
                remember(line);
                l.line(line);
                return;
            }
            Progress.Update u = progress.ytDlp(line);
            if (u != null) {
                l.progress(u.fraction(), u.speed(), u.eta());
                return;
            }
            progress.onLine(line);
            if (line.startsWith("[download] Destination: ")) {
                announce(line.substring("[download] Destination: ".length()).trim());
            }
            remember(line);
            l.line(line);
            if (isAbsolutePath(line) && Files.exists(Path.of(line))) files.add(line);
            // gallery-dl marks files it already has with "# "
            if (gallery && line.startsWith("# ") && isAbsolutePath(line.substring(2))
                    && Files.exists(Path.of(line.substring(2)))) {
                files.add(line.substring(2));
            }
            tail = line;
        }

        private void announce(String path) {
            destinations.add(path);
            l.destination(path);
        }

        private void remember(String line) {
            synchronized (all) {
                if (all.size() >= MAX_LINES) all.removeFirst();
                all.addLast(line);
            }
        }
    }
}
