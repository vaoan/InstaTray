package instatray.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the tools' progress lines. yt-dlp prints {@link #PREFIX} lines through the progress template that
 * {@link Fetch#command} gives it ("PROG downloaded total speed eta"); gallery-dl in terminal mode prints
 * " 45%  1.20MB 300.00kB/s". One instance follows one yt-dlp run (it needs the earlier lines to weigh video and
 * audio parts).
 */
public final class Progress {

    /** yt-dlp progress-template lines: "PROG downloaded_bytes total_bytes speed eta". */
    public static final String PREFIX = "PROG ";

    /** One reading: fraction 0..1 or -1 while unknown; speed and eta formatted for people, "" when unknown. */
    public record Update(double fraction, String speed, String eta) {}

    /** gallery-dl terminal-mode progress: " 45%  1.20MB 300.00kB/s". */
    private static final Pattern GALLERY_DL_PERCENT =
            Pattern.compile("^\\s*(\\d{1,3})%\\s+\\S+\\s+([\\d.]+[kMG]?B/s)?");
    private static final Pattern FORMATS = Pattern.compile("Downloading \\d+ format\\(s\\): (\\S+)");

    private int parts = 1;
    private int part = -1;

    /**
     * Turns yt-dlp's per-file byte progress into one overall fraction. A "video+audio" format downloads two files
     * back to back ({@link #onLine} counts them); the video gets 90% of the bar since it is by far the larger one.
     * Returns -1 while the size is unknown ("NA").
     */
    public double update(String data) {
        String[] f = data.trim().split("\\s+");
        if (f.length < 2) return -1;
        try {
            double done = Double.parseDouble(f[0]);
            double total = Double.parseDouble(f[1]);
            if (total <= 0) return -1;
            double frac = Math.min(1, done / total);
            int idx = Math.max(0, part);
            return parts == 2 ? (idx == 0 ? frac * 0.9 : 0.9 + frac * 0.1) : frac;
        } catch (NumberFormatException e) {
            return -1; // "NA" while the size is unknown
        }
    }

    /** Every other yt-dlp output line: notices how many parts the format has and when the next one starts. */
    public void onLine(String line) {
        Matcher m = FORMATS.matcher(line);
        if (m.find()) parts = m.group(1).contains("+") ? 2 : 1;
        if (line.startsWith("[download] Destination:")) part++;
    }

    /** A yt-dlp {@link #PREFIX} line (with the prefix), or null if it isn't one. */
    public Update ytDlp(String line) {
        if (!line.startsWith(PREFIX)) return null;
        String data = line.substring(PREFIX.length());
        String[] pf = data.trim().split("\\s+");
        String speed = pf.length > 2 && !pf[2].equals("NA") ? formatSpeed(parseOr(pf[2], 0)) : "";
        String eta = pf.length > 3 && !pf[3].equals("NA") ? formatEta((long) parseOr(pf[3], 0)) : "";
        return new Update(update(data), speed, eta);
    }

    /** A gallery-dl progress line, or null if it isn't one. The percentage stays below 100 until the file is saved. */
    public static Update galleryDl(String line) {
        Matcher gp = GALLERY_DL_PERCENT.matcher(line);
        if (!gp.find()) return null;
        String speed = gp.group(2) != null ? gp.group(2).replace("kB/s", " KB/s").replace("MB/s", " MB/s") : "";
        int pct = Math.min(99, Integer.parseInt(gp.group(1)));
        return new Update(pct / 100.0, speed, "");
    }

    /** Whole percent for a fraction, never 100 before the download ends; -1 for an unknown fraction. */
    public static int percent(double fraction) {
        return fraction < 0 ? -1 : (int) Math.min(99, Math.round(fraction * 100));
    }

    public static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / (1024 * 1024));
        if (bytesPerSec >= 1024) return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSec / 1024);
        return String.format(Locale.ROOT, "%.0f B/s", bytesPerSec);
    }

    public static String formatEta(long seconds) {
        long h = seconds / 3600, m = seconds % 3600 / 60, sec = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    static double parseOr(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
