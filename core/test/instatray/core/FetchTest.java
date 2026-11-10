package instatray.core;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Fetch: the command lines (name tag) and runs of fake tools (shell scripts in a temp folder). */
public class FetchTest {

    /** A record with these components implements Tools; {@code environment} overrides the default. */
    record FakeTools(String ytDlp, String galleryDl, String ffmpeg, Map<String, String> environment) implements Tools {}

    /** Records what Fetch reports. */
    static class Recorder implements Fetch.Listener {
        final List<String> progress = Collections.synchronizedList(new ArrayList<>());
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        final List<String> destinations = Collections.synchronizedList(new ArrayList<>());
        volatile String title = "";

        @Override
        public void progress(double fraction, String speed, String eta) {
            progress.add(Progress.percent(fraction) + "|" + speed + "|" + eta);
        }

        @Override
        public void title(String title) {
            this.title = title;
        }

        @Override
        public void line(String rawOutputLine) {
            lines.add(rawOutputLine);
        }

        @Override
        public void destination(String path) {
            destinations.add(path);
        }
    }

    static final Link YT = new Link(Site.YOUTUBE, "yt:dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    static final Link PIXIV = new Link(Site.PIXIV, "px:1", "https://www.pixiv.net/artworks/1");

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("instatray-fetch-test");
        commands(dir);
        segments();
        done(dir);
        galleryDone(dir);
        failures(dir);
        cancel(dir);
        timeout(dir);
        Check.done();
    }

    static void commands(Path dir) {
        Tools tools = new FakeTools("/bin/yt-dlp", "/bin/gallery-dl", "/bin/ffmpeg", Map.of());
        List<String> plain = Fetch.command(new Fetch.Request(YT, dir, null, null, null), tools, null);
        Check.eq("ytDlpFirst", "/bin/yt-dlp", plain.get(0));
        Check.eq("ytDlpTemplate", dir + "/" + Site.YOUTUBE.template(), after(plain, "-o"));
        Check.eq("ytDlpUrlLast", YT.url(), plain.get(plain.size() - 1));
        Check.eq("ytDlpOptionsEnd", "--", plain.get(plain.size() - 2));
        Check.eq("ffmpegLocation", "/bin/ffmpeg", after(plain, "--ffmpeg-location"));
        Check.that("noCookies", !plain.contains("--cookies"), plain.toString());

        List<String> tagged = Fetch.command(new Fetch.Request(YT, dir, "chat 5%", null, null), tools, Path.of("/c.txt"));
        Check.eq("ytDlpNameTag", dir + "/%(title).80B_[%(id)s] [chat 5%%].%(ext)s", after(tagged, "-o"));
        Check.eq("cookieCopy", "/c.txt", after(tagged, "--cookies"));

        List<String> gallery = Fetch.command(new Fetch.Request(PIXIV, dir, "a/b{c}", null, null), tools, null);
        Check.eq("galleryFirst", "/bin/gallery-dl", gallery.get(0));
        Check.eq("galleryDir", dir.toString(), after(gallery, "-D"));
        Check.eq("galleryNameTag", "{category}_{id}{num:?_//} [a_b{{c}}].{extension}", after(gallery, "-f"));
        Check.eq("galleryPlain", Fetch.GALLERY_DL_FILENAME,
                after(Fetch.command(new Fetch.Request(PIXIV, dir, null, null, null), tools, null), "-f"));
        Check.that("galleryNoFfmpeg", !gallery.contains("--ffmpeg-location"), gallery.toString());
        Check.eq("galleryOptionsEnd", "--", gallery.get(gallery.size() - 2));
        // A link can never pass for an option, whatever it says (an app may hand over text it didn't write).
        List<String> sneaky = Fetch.command(new Fetch.Request(new Link(Site.OTHER, "other:x", "--exec=rm -rf ~"), dir, null, null, null),
                tools, null);
        Check.eq("sneakyAfterEnd", "--", sneaky.get(sneaky.size() - 2));

        List<String> embed = Fetch.command(new Fetch.Request(new Link(Site.EMBED, "embed:e.com:1", "https://e.com/v"),
                dir, null, null, null), tools, null);
        Check.eq("catchAllCapped", "1:" + Fetch.CATCH_ALL_MAX_ITEMS, after(embed, "--playlist-items"));
    }

    static void segments() throws Exception {
        StringReader r = new StringReader(" 10%\r 20%\nlast");
        Check.eq("segments", List.of(" 10%", " 20%", "last"),
                List.of(Fetch.readSegment(r), Fetch.readSegment(r), Fetch.readSegment(r)));
        Check.eq("segmentsEnd", null, Fetch.readSegment(r));
    }

    static void done(Path dir) throws Exception {
        Path out = Files.createDirectories(dir.resolve("done"));
        Path tool = script(dir, "yt-dlp-done", """
                f="$FAKE_OUT/clip [tag].mp4"
                echo "TITLE Fake clip"
                echo "[download] Destination: $f"
                echo "PROG 512 1024 2048 3"
                printf video > "$f"
                echo "$f"
                """);
        Recorder rec = new Recorder();
        Fetch.Result res = Fetch.run(new Fetch.Request(YT, out, "tag", null, null),
                new FakeTools(tool.toString(), null, null, Map.of("FAKE_OUT", out.toString())), rec);
        Path file = out.resolve("clip [tag].mp4");
        Check.eq("doneOutcome", Fetch.Outcome.DONE, res.outcome());
        Check.eq("doneFiles", List.of(file), res.files());
        Check.eq("doneTitle", "Fake clip", res.title());
        Check.eq("doneProgress", List.of("50|2 KB/s|0:03"), rec.progress);
        Check.eq("doneDestination", List.of(file.toString()), rec.destinations);
        Check.that("doneLines", rec.lines.contains(file.toString()) && rec.lines.stream().noneMatch(l -> l.startsWith("PROG")),
                rec.lines.toString());
    }

    static void galleryDone(Path dir) throws Exception {
        Path out = Files.createDirectories(dir.resolve("gallery"));
        Path tool = script(dir, "gallery-dl-done", """
                f="$FAKE_OUT/pixiv_1.mp4"
                echo "  $f"
                printf ' 45%%  1.20MB 300.00kB/s\\r'
                printf video > "$f"
                echo "✔ $f"
                """);
        Recorder rec = new Recorder();
        Fetch.Result res = Fetch.run(new Fetch.Request(PIXIV, out, null, null, null),
                new FakeTools(null, tool.toString(), null, Map.of("FAKE_OUT", out.toString())), rec);
        Check.eq("galleryOutcome", Fetch.Outcome.DONE, res.outcome());
        Check.eq("galleryFiles", List.of(out.resolve("pixiv_1.mp4")), res.files());
        Check.eq("galleryTitleIsFileName", "pixiv_1.mp4", rec.title);
        Check.eq("galleryProgress", List.of("45|300.00 KB/s|"), rec.progress);
    }

    static void failures(Path dir) throws Exception {
        Path tool = script(dir, "yt-dlp-fail", """
                echo "$FAKE_MSG"
                exit 1
                """);
        Fetch.Result missing = Fetch.run(new Fetch.Request(YT, dir, null, null, null),
                new FakeTools(null, null, null, Map.of()), new Recorder());
        Check.eq("missingTool", new Fetch.Result(Fetch.Outcome.FAILED_PERMANENT, "yt-dlp" + Fetch.NOT_INSTALLED, List.of(), ""),
                missing);
        Fetch.Result notFound = Fetch.run(new Fetch.Request(YT, dir, null, null, null), new FakeTools(tool.toString(), null, null,
                Map.of("FAKE_MSG", "ERROR: [youtube] x: HTTP Error 404: Not Found")), new Recorder());
        Check.eq("notFoundPermanent", Fetch.Outcome.FAILED_PERMANENT, notFound.outcome());
        Check.eq("notFoundReason", Fetch.NOT_FOUND, notFound.reason());
        Fetch.Result transientError = Fetch.run(new Fetch.Request(YT, dir, null, null, null), new FakeTools(tool.toString(),
                null, null, Map.of("FAKE_MSG", "ERROR: HTTP Error 503: Service Unavailable")), new Recorder());
        Check.eq("transient", new Fetch.Result(Fetch.Outcome.FAILED, "HTTP Error 503: Service Unavailable", List.of(), ""),
                transientError);
    }

    /** Interrupting the calling thread while the tool runs kills it (and its child) and deletes the partial file. */
    static void cancel(Path dir) throws Exception {
        Path out = Files.createDirectories(dir.resolve("cancel"));
        Path tool = script(dir, "yt-dlp-sleep", """
                f="$FAKE_OUT/long.mp4"
                echo "[download] Destination: $f"
                printf x > "$f.part"
                sleep 1000 &
                echo "CHILD $!"
                echo "PID $$"
                echo READY
                wait
                """);
        CountDownLatch ready = new CountDownLatch(1);
        List<Long> pids = Collections.synchronizedList(new ArrayList<>());
        Recorder rec = new Recorder() {
            @Override
            public void line(String line) {
                super.line(line);
                if (line.startsWith("CHILD ") || line.startsWith("PID ")) pids.add(Long.parseLong(line.split(" ")[1]));
                if (line.equals("READY")) ready.countDown();
            }
        };
        AtomicReference<Fetch.Result> result = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean(true);
        Thread caller = new Thread(() -> {
            try {
                result.set(Fetch.run(new Fetch.Request(YT, out, null, null, null),
                        new FakeTools(tool.toString(), null, null, Map.of("FAKE_OUT", out.toString())), rec));
            } catch (InterruptedException e) {
                result.set(null);
            }
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        ready.await();
        Check.that("partialWritten", Files.exists(out.resolve("long.mp4.part")), "");
        caller.interrupt();
        caller.join();
        Check.eq("cancelOutcome", Fetch.Outcome.CANCELLED, result.get() == null ? null : result.get().outcome());
        Check.that("interruptConsumed", !stillInterrupted.get(), "");
        Check.that("partialDeleted", !Files.exists(out.resolve("long.mp4.part")), "");
        Check.eq("bothPidsSeen", 2, pids.size());
        Check.that("toolAndChildKilled", pids.stream().noneMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive)
                .orElse(false)), pids.toString());
    }

    /** A zero time limit stops a tool that would never end by itself. */
    static void timeout(Path dir) throws Exception {
        Path tool = script(dir, "yt-dlp-forever", "exec sleep 1000\n");
        Fetch.Result res = Fetch.run(new Fetch.Request(YT, dir, null, null, Duration.ZERO),
                new FakeTools(tool.toString(), null, null, Map.of()), new Recorder());
        Check.eq("timeoutOutcome", Fetch.Outcome.FAILED, res.outcome());
        Check.eq("timeoutReason", Fetch.TIMED_OUT, res.reason());
    }

    static String after(List<String> cmd, String option) {
        int i = cmd.indexOf(option);
        return i < 0 || i + 1 >= cmd.size() ? null : cmd.get(i + 1);
    }

    static Path script(Path dir, String name, String body) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        return p;
    }
}
