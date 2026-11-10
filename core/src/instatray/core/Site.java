package instatray.core;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The supported sites: which engine downloads them, the yt-dlp file name template, how their cookie files are named
 * and which domains their cookies belong to. Two catch-alls take any other URL the libraries accept ({@link #OTHER}
 * for yt-dlp, {@link #OTHER_GALLERY} for gallery-dl on creator platforms) and {@link #EMBED} pages only yt-dlp's
 * Generic extractor takes; see {@link Probe}.
 */
public enum Site {
    INSTAGRAM("Instagram", "%(channel,uploader|instagram)s_%(id)s_%(autonumber)02d.%(ext)s",
            new String[]{"instagram"}, "instagram.com"),
    YOUTUBE("YouTube", Site.TITLED, new String[]{"youtube"}, "youtube.com"),
    TIKTOK("TikTok", "%(uploader|tiktok)s_%(id)s.%(ext)s", new String[]{"tiktok"}, "tiktok.com"),
    X("X", "%(uploader_id|x)s_%(id)s.%(ext)s", new String[]{"x.com", "twitter"}, "x.com", "twitter.com"),
    TWITCH("Twitch", Site.TITLED, new String[]{"twitch"}, "twitch.tv"),
    REDDIT("Reddit", Site.TITLED, new String[]{"reddit"}, "reddit.com", "redd.it"),
    FACEBOOK("Facebook", "%(uploader|facebook)s_%(id)s.%(ext)s", new String[]{"facebook"},
            "facebook.com", "fb.watch"),
    PINTEREST("Pinterest", "pinterest_%(id)s.%(ext)s", new String[]{"pinterest"}, "pinterest.com", "pin.it"),
    BLUESKY("Bluesky", "%(uploader_id|bluesky)s_%(id)s.%(ext)s", new String[]{"bsky"}, "bsky.app"),
    KICK("Kick", Site.TITLED, new String[]{"kick.com"}, "kick.com"),
    VIMEO("Vimeo", Site.TITLED, new String[]{"vimeo"}, "vimeo.com"),
    DAILYMOTION("Dailymotion", Site.TITLED, new String[]{"dailymotion"}, "dailymotion.com"),
    RUMBLE("Rumble", Site.TITLED, new String[]{"rumble"}, "rumble.com"),
    STREAMABLE("Streamable", Site.TITLED, new String[]{"streamable"}, "streamable.com"),
    IMGUR("Imgur", "imgur_%(id)s.%(ext)s", new String[]{"imgur"}, "imgur.com"),
    NINEGAG("9GAG", Site.TITLED, new String[]{"9gag"}, "9gag.com"),
    TUMBLR("Tumblr", "%(uploader_id|tumblr)s_%(id)s.%(ext)s", new String[]{"tumblr"}, "tumblr.com"),
    LINKEDIN("LinkedIn", Site.TITLED, new String[]{"linkedin"}, "linkedin.com"),
    SNAPCHAT("Snapchat", "snapchat_%(id)s.%(ext)s", new String[]{"snapchat"}, "snapchat.com"),
    BILIBILI("Bilibili", Site.TITLED, new String[]{"bilibili"}, "bilibili.com"),
    NEWGROUNDS("Newgrounds", Site.TITLED, new String[]{"newgrounds"}, "newgrounds.com"),
    IWARA("Iwara", Site.TITLED, new String[]{"iwara"}, "iwara.tv"),
    PATREON("Patreon", Site.TITLED, new String[]{"patreon"}, "patreon.com"),
    WEIBO("Weibo", "weibo_%(id)s.%(ext)s", new String[]{"weibo"}, "weibo.com", "weibo.cn"),
    // gallery-dl sites: artists' own platforms (yt-dlp has no extractor for them)
    PIXIV(Engine.GALLERY_DL, "Pixiv", new String[]{"pixiv"}, "pixiv.net"),
    FANBOX(Engine.GALLERY_DL, "FANBOX", new String[]{"fanbox"}, "fanbox.cc"),
    FANTIA(Engine.GALLERY_DL, "Fantia", new String[]{"fantia"}, "fantia.jp"),
    DEVIANTART(Engine.GALLERY_DL, "DeviantArt", new String[]{"deviantart"}, "deviantart.com"),
    MISSKEY(Engine.GALLERY_DL, "Misskey", new String[]{"misskey"}, "misskey.io"),
    MASTODON(Engine.GALLERY_DL, "Mastodon", new String[]{"mastodon", "pawoo"}, "mastodon.social", "pawoo.net"),
    // Catch-alls: any other single-video URL yt-dlp has an extractor for, and posts on the creator-owned
    // platforms gallery-dl knows (Probe.CREATOR_PLATFORMS). Detected by asking the libraries (Probe)
    OTHER("Other sites", "%(extractor_key)s_%(title).70B_[%(id)s].%(ext)s", new String[0]),
    OTHER_GALLERY(Engine.GALLERY_DL, "Other sites", new String[0]),
    // Opt-in: pages only yt-dlp's Generic extractor takes, which looks for <video>/<source>, HLS/DASH manifests,
    // og:video and known player iframes in the HTML
    EMBED("Embedded video", "%(webpage_url_domain)s_%(title).70B_[%(id)s].%(ext)s", new String[0]);

    /** Template of the sites whose files are named after the video title. */
    private static final String TITLED = "%(title).80B_[%(id)s].%(ext)s";

    private final Engine engine;
    private final String label;
    private final String template;
    private final String[] cookieKeys;
    private final String[] domains;

    Site(String label, String template, String[] cookieKeys, String... domains) {
        this(Engine.YT_DLP, label, template, cookieKeys, domains);
    }

    Site(Engine engine, String label, String[] cookieKeys, String... domains) {
        this(engine, label, null, cookieKeys, domains);
    }

    Site(Engine engine, String label, String template, String[] cookieKeys, String... domains) {
        this.engine = engine;
        this.label = label;
        this.template = template;
        this.cookieKeys = cookieKeys;
        this.domains = domains;
    }

    /** Every site except the catch-alls. */
    public static List<Site> named() {
        return NAMED;
    }

    private static final List<Site> NAMED = Arrays.stream(values()).filter(s -> !s.isCatchAll()).toList();

    public Engine engine() {
        return engine;
    }

    /** The site's name ("YouTube"); the catch-alls have English descriptions ("Other sites"). */
    public String label() {
        return label;
    }

    /** yt-dlp output template; null for gallery-dl sites (they use {@link Fetch#GALLERY_DL_FILENAME}). */
    public String template() {
        return template;
    }

    /** The domains the site's cookies belong to (empty for the catch-alls: their domain comes from the URL). */
    public List<String> domains() {
        return List.of(domains);
    }

    String[] domainArray() {
        return domains.clone();
    }

    public boolean isCatchAll() {
        return this == OTHER || this == OTHER_GALLERY || this == EMBED;
    }

    /** True if the host is this site's (domain or any subdomain). */
    public boolean ownsHost(String host) {
        for (String d : domains) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    /** True if a Netscape cookie line belongs to this site (domain or any subdomain). */
    public boolean ownsCookieLine(String line) {
        return Cookies.lineIn(line, domains);
    }

    /** True if a cookie file's lower-case name says it is this site's (e.g. "www.youtube.com_cookies.txt"). */
    public boolean matchesCookieFile(String lowerName) {
        for (String k : cookieKeys) {
            // keys with a dot are whole domains ("x.com", "kick.com"): they must start the file name,
            // so "netflix.com_cookies.txt" is not taken for X
            boolean hit = k.contains(".")
                    ? lowerName.matches("(?:www\\.)?" + Pattern.quote(k) + ".*")
                    : lowerName.contains(k);
            if (hit) return true;
        }
        return false;
    }
}
