package instatray.core;

public class ProgressTest {
    public static void main(String[] args) {
        Progress p = new Progress();
        Progress.Update u = p.ytDlp("PROG 512 1024 2097152 75");
        Check.eq("ytDlpFraction", 0.5, u.fraction());
        Check.eq("ytDlpSpeed", "2.0 MB/s", u.speed());
        Check.eq("ytDlpEta", "1:15", u.eta());
        Check.eq("ytDlpUnknown", -1.0, p.ytDlp("PROG 10 NA NA NA").fraction());
        Check.eq("ytDlpUnknownSpeed", "", p.ytDlp("PROG 10 NA NA NA").speed());
        Check.eq("notProgress", null, p.ytDlp("[download] Destination: /tmp/a.mp4"));

        Progress two = new Progress();
        two.onLine("[info] abc: Downloading 1 format(s): 137+140");
        two.onLine("[download] Destination: /v/a.f137.mp4");
        Check.eq("videoPartWeighs90", 45, Progress.percent(two.update("50 100")));
        two.onLine("[download] Destination: /v/a.f140.m4a");
        Check.eq("audioPartLast10", 95, Progress.percent(two.update("50 100")));
        Check.eq("never100BeforeEnd", 99, Progress.percent(two.update("100 100")));

        Progress.Update g = Progress.galleryDl(" 45%  1.20MB 300.00kB/s");
        Check.eq("galleryPercent", 45, Progress.percent(g.fraction()));
        Check.eq("gallerySpeed", "300.00 KB/s", g.speed());
        Check.eq("galleryCapped", 99, Progress.percent(Progress.galleryDl("100%  3.0MB 1.0MB/s").fraction()));
        Check.eq("galleryNotProgress", null, Progress.galleryDl("  /v/pixiv_1.mp4"));
        Check.eq("unknownPercent", -1, Progress.percent(-1));
        Check.eq("etaHours", "1:01:01", Progress.formatEta(3661));
        Check.done();
    }
}
