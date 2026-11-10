package instatray.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

public class CookiesTest {
    static final String YT = ".youtube.com\tTRUE\t/\tTRUE\t0\tSID\tyt-secret";
    static final String VIMEO = "#HttpOnly_.vimeo.com\tTRUE\t/\tTRUE\t0\tvuid\tvimeo-secret";
    static final String NICO = ".nicovideo.jp\tTRUE\t/\tTRUE\t0\tuser_session\tnico-secret";

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("instatray-cookies-test");
        Path yt = write(dir.resolve("www.youtube.com_cookies.txt"), 1, YT);
        Path general = write(dir.resolve("cookies.txt"), 2, "# Netscape HTTP Cookie File", YT, VIMEO, NICO);
        write(dir.resolve("netflix.com_cookies.txt"), 3, ".netflix.com\tTRUE\t/\tTRUE\t0\tNID\tn");
        write(dir.resolve("notes.txt"), 4, VIMEO);

        Check.eq("byName", yt, Cookies.find(dir, Site.YOUTUBE));
        Check.eq("generalExport", general, Cookies.find(dir, Site.VIMEO));
        Check.eq("dotKeyMustStartName", null, Cookies.find(dir, Site.X)); // netflix.com_… is not x.com
        Check.eq("none", null, Cookies.find(dir, Site.TIKTOK));
        Check.eq("forDomain", general, Cookies.forDomain(dir, "nicovideo.jp"));
        Check.eq("forLinkCatchAll", general,
                Cookies.forLink(dir, new Link(Site.OTHER, "ytdl:Niconico:sm9", "https://sp.nicovideo.jp/watch/sm9")));
        Check.eq("missingFolder", null, Cookies.find(dir.resolve("nope"), Site.YOUTUBE));

        Path out = dir.resolve("out.txt");
        Cookies.writeSite(general, out, Site.VIMEO);
        Check.eq("onlySiteCookies", List.of("# Netscape HTTP Cookie File", VIMEO), Files.readAllLines(out));
        Cookies.writeDomain(general, out, "youtube.com", "nicovideo.jp");
        Check.eq("onlyDomainCookies", List.of("# Netscape HTTP Cookie File", YT, NICO), Files.readAllLines(out));
        Check.that("lineIn", Cookies.lineIn("m.youtube.com\tFALSE\t/\tFALSE\t0\ta\tb", "youtube.com")
                && !Cookies.lineIn("notyoutube.com\tFALSE\t/\tFALSE\t0\ta\tb", "youtube.com"), "");
        Check.that("siteOwnsLine", Site.VIMEO.ownsCookieLine(VIMEO) && !Site.VIMEO.ownsCookieLine(YT), "");
        Check.done();
    }

    static Path write(Path p, int age, String... lines) throws Exception {
        Files.write(p, List.of(lines));
        Files.setLastModifiedTime(p, FileTime.fromMillis(1_700_000_000_000L + age * 1000L)); // higher = newer
        return p;
    }
}
