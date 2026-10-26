import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One download request: what was asked, what happened, and yt-dlp's output for the history window. */
final class Job {

    enum Status {
        QUEUED("En cola"), DOWNLOADING("Descargando"), DONE("Listo"), FAILED("Falló"), CANCELLED("Cancelado");

        final String label;

        Status(String label) {
            this.label = label;
        }
    }

    private static final int MAX_OUTPUT_LINES = 400;

    final InstaTray.Link link;
    final long createdAt;
    volatile Status status = Status.QUEUED;
    /** 0..100 while downloading, -1 when unknown. */
    volatile int percent = -1;
    volatile String message = "";
    volatile List<String> files = List.of();
    /** Page the link was found in (pasted HTML), or "" when the link itself was copied. */
    volatile String page = "";
    /** Automatic retries already used for the current request. */
    volatile int attempt = 0;
    /** Video title (or file name): tray tooltip while downloading, and the row title in the window. */
    volatile String title = "";
    volatile String speed = "";
    volatile String eta = "";
    /** Size of the first downloaded file in bytes, -1 when unknown or missing (not persisted). */
    volatile long bytes = -1;
    /** A waiting automatic retry is not started before this time (epoch ms). */
    volatile long notBefore = 0;
    volatile boolean cancelRequested = false;
    /** Files yt-dlp announced ("[download] Destination: …"), so a cancel can delete exactly those. */
    final List<String> destinations = Collections.synchronizedList(new ArrayList<>());
    private final List<String> output = Collections.synchronizedList(new ArrayList<>());

    Job(InstaTray.Link link, long createdAt) {
        this.link = link;
        this.createdAt = createdAt;
    }

    void addOutput(String line) {
        synchronized (output) {
            if (output.size() >= MAX_OUTPUT_LINES) output.remove(0);
            output.add(line);
        }
    }

    void clearOutput() {
        output.clear();
    }

    String outputText() {
        synchronized (output) {
            return String.join("\n", output);
        }
    }

    String statusText() {
        if (status == Status.DOWNLOADING && percent >= 0) return status.label + " " + percent + "%";
        if (status == Status.QUEUED && notBefore > System.currentTimeMillis()) {
            return "Reintento en " + Math.max(1, (notBefore - System.currentTimeMillis()) / 1000) + " s";
        }
        return status.label;
    }

    boolean isFinished() {
        return status == Status.DONE || status == Status.FAILED || status == Status.CANCELLED;
    }

    // ---------------------------------------------------------------- persistence (one TSV line per job)

    String toLine() {
        return String.join("\t", String.valueOf(createdAt), link.key(), link.url(), link.site().name(),
                status.name(), clean(message), clean(String.join("|", files)), clean(page),
                clean(title));
    }

    /** Jobs still queued or downloading when the app quit come back as QUEUED and are resumed. */
    static Job fromLine(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 7) return null;
        try {
            InstaTray.Link link = new InstaTray.Link(InstaTray.Site.valueOf(f[3]), f[1], f[2]);
            Job job = new Job(link, Long.parseLong(f[0]));
            Status st = Status.valueOf(f[4]);
            job.status = st == Status.DOWNLOADING ? Status.QUEUED : st;
            job.message = job.isFinished() ? f[5] : "Retomado al abrir InstaTray";
            job.files = f[6].isEmpty() ? List.of() : List.of(f[6].split("\\|"));
            if (f.length > 7) job.page = f[7];
            if (f.length > 8) job.title = f[8];
            return job;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
