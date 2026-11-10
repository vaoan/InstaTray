package instatray.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Netscape cookie files (browser exports such as "www.youtube.com_cookies.txt") in a folder of the caller's choice:
 * which one belongs to a site, and writing a copy that holds only one site's cookies (a tool never gets a whole
 * browser's sessions, and the original is never rewritten). Cookie values are never logged or returned.
 */
public final class Cookies {

    private Cookies() {
    }

    /**
     * Cookies for a site: the newest *cookie*.txt in the folder whose name mentions the site; otherwise the newest
     * general export (e.g. cookies.txt, not named after another known site) that holds at least one of its cookies.
     * Null when there is none (or the folder can't be read).
     */
    public static Path find(Path cookiesDir, Site site) {
        List<Path> files = cookieFiles(cookiesDir);
        if (files == null) return null;
        for (Path p : files) {
            if (site.matchesCookieFile(lowerName(p))) return p;
        }
        for (Path p : files) {
            String name = lowerName(p);
            boolean otherSite = Arrays.stream(Site.values()).anyMatch(s -> s.matchesCookieFile(name));
            if (!otherSite && hasDomainCookies(p, site.domainArray())) return p;
        }
        return null;
    }

    /**
     * Cookies for a domain (catch-all sites): the newest *cookie*.txt whose name mentions the domain (nicovideo for
     * nicovideo.jp) and holds cookies for it; otherwise a general export (not named after one of the known sites)
     * that does. Null when there is none.
     */
    public static Path forDomain(Path cookiesDir, String domain) {
        String key = domain.split("\\.")[0];
        List<Path> files = cookieFiles(cookiesDir);
        if (files == null) return null;
        for (Path p : files) {
            if (lowerName(p).contains(key) && hasDomainCookies(p, domain)) return p;
        }
        for (Path p : files) {
            String name = lowerName(p);
            boolean namedSite = Site.named().stream().anyMatch(s -> s.matchesCookieFile(name));
            if (!namedSite && hasDomainCookies(p, domain)) return p;
        }
        return null;
    }

    /** The cookie file for a link: by site, or by the URL's registrable domain for catch-alls. Null when none. */
    public static Path forLink(Path cookiesDir, Link link) {
        return link.site().isCatchAll() ? forDomain(cookiesDir, domainsFor(link)[0]) : find(cookiesDir, link.site());
    }

    /** The domains whose cookies a link's download may use: the site's, or the URL's registrable domain. */
    public static String[] domainsFor(Link link) {
        return link.site().isCatchAll()
                ? new String[]{Links.baseDomain(Links.host(link.url()))} : link.site().domainArray();
    }

    /** Writes a Netscape cookie file with only the site's cookies. */
    public static void writeSite(Path src, Path dst, Site site) throws IOException {
        writeDomain(src, dst, site.domainArray());
    }

    /** Writes a Netscape cookie file with only the cookies of these domains (and their subdomains). */
    public static void writeDomain(Path src, Path dst, String... domains) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("# Netscape HTTP Cookie File");
        for (String line : Files.readAllLines(src, StandardCharsets.UTF_8)) {
            if (isCookieLine(line) && lineIn(line, domains)) out.add(line);
        }
        Files.write(dst, out, StandardCharsets.UTF_8);
    }

    /** True if a Netscape cookie line belongs to one of the domains (or any subdomain). */
    public static boolean lineIn(String line, String... domains) {
        String domain = line.split("\t", 2)[0].toLowerCase(Locale.ROOT);
        if (domain.startsWith("#httponly_")) domain = domain.substring("#httponly_".length());
        if (domain.startsWith(".")) domain = domain.substring(1);
        for (String d : domains) {
            if (domain.equals(d) || domain.endsWith("." + d)) return true;
        }
        return false;
    }

    /** A cookie line (not a comment, except the "#HttpOnly_" prefix browsers use). */
    public static boolean isCookieLine(String line) {
        return !line.isBlank() && (!line.startsWith("#") || line.startsWith("#HttpOnly_")) && line.indexOf('\t') > 0;
    }

    /** True if the file holds at least one cookie of these domains. */
    public static boolean hasDomainCookies(Path file, String... domains) {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.anyMatch(l -> isCookieLine(l) && lineIn(l, domains));
        } catch (Exception e) {
            return false;
        }
    }

    /** The folder's *cookie*.txt files, newest first; null if it can't be listed. */
    private static List<Path> cookieFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path p : ds) {
                if (lowerName(p).contains("cookie")) files.add(p);
            }
        } catch (IOException e) {
            return null;
        }
        files.sort(Comparator.comparingLong(Cookies::modifiedTime).reversed());
        return files;
    }

    private static String lowerName(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT);
    }

    private static long modifiedTime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
