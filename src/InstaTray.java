import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.LinkedList;
import java.awt.desktop.AppReopenedListener;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.WindowConstants;

/**
 * macOS menu-bar (tray) app that watches the clipboard for Instagram, YouTube,
 * X and Twitch video links and downloads them in the background with yt-dlp,
 * using each site's cookies.txt found in ~/Downloads.
 * Videos are saved to ~/Documents/<Site> Videos.
 */
public final class InstaTray implements WindowHost {

    private static final Path HOME = Paths.get(System.getProperty("user.home"));
    private static final Path DOWNLOADS = HOME.resolve("Downloads");
    private static final Path STATE_DIR = HOME.resolve("Library/Application Support/InstaTray");
    private static final Path HISTORY = STATE_DIR.resolve("downloaded.txt");
    private static final Path SETTINGS = STATE_DIR.resolve("settings.properties");
    private static final Path JOBS = STATE_DIR.resolve("jobs.tsv");
    private static final int MAX_JOBS = 200;
    private static final Path LOG = STATE_DIR.resolve("instatray.log");

    private static final String TITLED = "%(title).80B_[%(id)s].%(ext)s";
    /** "{num:?_//}" adds "_N" only for multi-file posts, so single files don't end in "_None". */
    private static final String GALLERY_DL_FILENAME = "{category}_{id}{num:?_//}.{extension}";
    /** Video/animation files kept from gallery-dl posts ("zip" = Pixiv ugoira, converted to mp4). */
    private static final String GALLERY_DL_FILTER =
            "extension in ('mp4', 'webm', 'mov', 'm4v', 'mkv', 'gif', 'zip')";

    /** Which downloader handles a site. */
    enum Engine { YT_DLP, GALLERY_DL }

    enum Site {
        INSTAGRAM("Instagram", "%(channel,uploader|instagram)s_%(id)s_%(autonumber)02d.%(ext)s",
                new String[]{"instagram"}, "instagram.com"),
        YOUTUBE("YouTube", TITLED, new String[]{"youtube"}, "youtube.com"),
        TIKTOK("TikTok", "%(uploader|tiktok)s_%(id)s.%(ext)s", new String[]{"tiktok"}, "tiktok.com"),
        X("X", "%(uploader_id|x)s_%(id)s.%(ext)s", new String[]{"x.com", "twitter"}, "x.com", "twitter.com"),
        TWITCH("Twitch", TITLED, new String[]{"twitch"}, "twitch.tv"),
        REDDIT("Reddit", TITLED, new String[]{"reddit"}, "reddit.com", "redd.it"),
        FACEBOOK("Facebook", "%(uploader|facebook)s_%(id)s.%(ext)s", new String[]{"facebook"},
                "facebook.com", "fb.watch"),
        PINTEREST("Pinterest", "pinterest_%(id)s.%(ext)s", new String[]{"pinterest"}, "pinterest.com", "pin.it"),
        BLUESKY("Bluesky", "%(uploader_id|bluesky)s_%(id)s.%(ext)s", new String[]{"bsky"}, "bsky.app"),
        KICK("Kick", TITLED, new String[]{"kick.com"}, "kick.com"),
        VIMEO("Vimeo", TITLED, new String[]{"vimeo"}, "vimeo.com"),
        DAILYMOTION("Dailymotion", TITLED, new String[]{"dailymotion"}, "dailymotion.com"),
        RUMBLE("Rumble", TITLED, new String[]{"rumble"}, "rumble.com"),
        STREAMABLE("Streamable", TITLED, new String[]{"streamable"}, "streamable.com"),
        IMGUR("Imgur", "imgur_%(id)s.%(ext)s", new String[]{"imgur"}, "imgur.com"),
        NINEGAG("9GAG", TITLED, new String[]{"9gag"}, "9gag.com"),
        TUMBLR("Tumblr", "%(uploader_id|tumblr)s_%(id)s.%(ext)s", new String[]{"tumblr"}, "tumblr.com"),
        LINKEDIN("LinkedIn", TITLED, new String[]{"linkedin"}, "linkedin.com"),
        SNAPCHAT("Snapchat", "snapchat_%(id)s.%(ext)s", new String[]{"snapchat"}, "snapchat.com"),
        BILIBILI("Bilibili", TITLED, new String[]{"bilibili"}, "bilibili.com"),
        NEWGROUNDS("Newgrounds", TITLED, new String[]{"newgrounds"}, "newgrounds.com"),
        IWARA("Iwara", TITLED, new String[]{"iwara"}, "iwara.tv"),
        PATREON("Patreon", TITLED, new String[]{"patreon"}, "patreon.com"),
        WEIBO("Weibo", "weibo_%(id)s.%(ext)s", new String[]{"weibo"}, "weibo.com", "weibo.cn"),
        // gallery-dl sites: artists' own platforms (yt-dlp has no extractor for them)
        PIXIV(Engine.GALLERY_DL, "Pixiv", new String[]{"pixiv"}, "pixiv.net"),
        FANBOX(Engine.GALLERY_DL, "FANBOX", new String[]{"fanbox"}, "fanbox.cc"),
        FANTIA(Engine.GALLERY_DL, "Fantia", new String[]{"fantia"}, "fantia.jp"),
        DEVIANTART(Engine.GALLERY_DL, "DeviantArt", new String[]{"deviantart"}, "deviantart.com"),
        MISSKEY(Engine.GALLERY_DL, "Misskey", new String[]{"misskey"}, "misskey.io"),
        MASTODON(Engine.GALLERY_DL, "Mastodon", new String[]{"mastodon", "pawoo"}, "mastodon.social", "pawoo.net"),
        // Catch-alls: any other single-video URL yt-dlp has an extractor for, and posts on the creator-owned
        // platforms gallery-dl knows (CREATOR_PLATFORMS). Detected by asking the libraries (probeLibraries)
        OTHER("Otros sitios", "%(extractor_key)s_%(title).70B_[%(id)s].%(ext)s", new String[0]),
        OTHER_GALLERY(Engine.GALLERY_DL, "Otros sitios", new String[0]),
        // Opt-in ("Buscar video embebido en otras páginas"): pages only yt-dlp's Generic extractor takes, which
        // looks for <video>/<source>, HLS/DASH manifests, og:video and known player iframes in the HTML
        EMBED("Video embebido", "%(webpage_url_domain)s_%(title).70B_[%(id)s].%(ext)s", new String[0]);

        final Engine engine;
        final String label;
        /** yt-dlp output template (gallery-dl sites use {@link #GALLERY_DL_FILENAME}). */
        final String template;
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

        /** True if a Netscape cookie line belongs to this site (domain or any subdomain). */
        boolean ownsCookieLine(String line) {
            return cookieLineIn(line, domains);
        }

        /** True if the host is this site's (domain or any subdomain). */
        boolean ownsHost(String host) {
            for (String d : domains) {
                if (host.equals(d) || host.endsWith("." + d)) return true;
            }
            return false;
        }

        /** Each site has an orb color of the same name; both catch-alls share OTHER. */
        AeroIcon.Palette palette() {
            return isCatchAll() ? AeroIcon.Palette.OTHER : AeroIcon.Palette.valueOf(name());
        }

        boolean isCatchAll() {
            return this == OTHER || this == OTHER_GALLERY || this == EMBED;
        }

        boolean matchesCookieFile(String lowerName) {
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

    /**
     * How a supported link is recognized: only URLs of a single video/post are matched, never profiles,
     * channels or live pages, so copying a profile can't start hundreds of downloads.
     *
     * @param key the history key (site prefix + id) used to skip repeats
     * @param url the clean URL handed to yt-dlp (tracking parameters dropped)
     */
    record LinkRule(Site site, Pattern pattern, Function<Matcher, String> key, Function<Matcher, String> url) {}

    private static final String SUB = "(?:[a-z0-9-]+\\.)*"; // any subdomain: www., m., old., co. …

    private static LinkRule rule(Site site, String regex, Function<Matcher, String> key, Function<Matcher, String> url) {
        return new LinkRule(site, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), key, url);
    }

    /** The matched URL without query string or fragment. */
    private static String clean(Matcher m) {
        return m.group(0).replaceFirst("[?#].*$", "");
    }

    static final List<LinkRule> RULES = List.of(
            // Instagram reel / post / tv, with or without the user in the path, and kk/dd/ig mirrors
            rule(Site.INSTAGRAM, "https?://" + SUB + "(?:kk|dd|ig)?instagram\\.com/(?:[A-Za-z0-9_.]+/)?(reels?|p|tv)/([A-Za-z0-9_-]+)",
                    m -> "ig:" + m.group(2),
                    m -> "https://www.instagram.com/" + m.group(1).toLowerCase(Locale.ROOT).replace("reels", "reel") + "/" + m.group(2) + "/"),
            // YouTube watch / shorts / live (finished) / embed, youtu.be
            rule(Site.YOUTUBE, "https?://(?:" + SUB + "youtube\\.com/(?:watch\\?(?:\\S*?&)?v=|shorts/|live/|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})",
                    m -> "yt:" + m.group(1), m -> "https://www.youtube.com/watch?v=" + m.group(1)),
            // TikTok video page and short links
            rule(Site.TIKTOK, "https?://" + SUB + "tiktok\\.com/@([A-Za-z0-9_.-]+)/video/(\\d+)",
                    m -> "tt:" + m.group(2), m -> "https://www.tiktok.com/@" + m.group(1) + "/video/" + m.group(2)),
            rule(Site.TIKTOK, "https?://(?:vm|vt)\\.tiktok\\.com/([A-Za-z0-9]+)",
                    m -> "tt:short:" + m.group(1), m -> clean(m) + "/"),
            rule(Site.TIKTOK, "https?://" + SUB + "tiktok\\.com/t/([A-Za-z0-9]+)",
                    m -> "tt:short:" + m.group(1), m -> clean(m) + "/"),
            // X / Twitter and fxtwitter / vxtwitter / fixupx mirrors
            rule(Site.X, "https?://" + SUB + "(?:x|twitter|fxtwitter|vxtwitter|fixupx|fixvx)\\.com/([A-Za-z0-9_]+)/status(?:es)?/(\\d+)",
                    m -> "x:" + m.group(2), m -> "https://x.com/" + m.group(1) + "/status/" + m.group(2)),
            // Twitch clips and past broadcasts (bare channel URLs are live and ignored on purpose)
            rule(Site.TWITCH, "https?://(?:clips\\.twitch\\.tv/|" + SUB + "twitch\\.tv/[A-Za-z0-9_]+/clip/)([A-Za-z0-9_-]+)",
                    m -> "twclip:" + m.group(1), m -> "https://clips.twitch.tv/" + m.group(1)),
            rule(Site.TWITCH, "https?://" + SUB + "twitch\\.tv/videos/(\\d+)",
                    m -> "twvod:" + m.group(1), m -> "https://www.twitch.tv/videos/" + m.group(1)),
            // Reddit post, share link (/s/), redd.it and v.redd.it
            rule(Site.REDDIT, "https?://" + SUB + "reddit\\.com/r/([A-Za-z0-9_]+)/comments/([a-z0-9]+)",
                    m -> "rd:" + m.group(2), m -> "https://www.reddit.com/r/" + m.group(1) + "/comments/" + m.group(2) + "/"),
            rule(Site.REDDIT, "https?://" + SUB + "reddit\\.com/r/[A-Za-z0-9_]+/s/([A-Za-z0-9]+)",
                    m -> "rd:s:" + m.group(1), InstaTray::clean),
            rule(Site.REDDIT, "https?://v\\.redd\\.it/([a-z0-9]+)", m -> "rd:v:" + m.group(1), InstaTray::clean),
            rule(Site.REDDIT, "https?://redd\\.it/([a-z0-9]+)", m -> "rd:" + m.group(1), InstaTray::clean),
            // Facebook watch, reel, /videos/, share links and fb.watch
            rule(Site.FACEBOOK, "https?://" + SUB + "facebook\\.com/watch/?\\?(?:\\S*?&)?v=(\\d+)",
                    m -> "fb:" + m.group(1), m -> "https://www.facebook.com/watch/?v=" + m.group(1)),
            rule(Site.FACEBOOK, "https?://" + SUB + "facebook\\.com/reel/(\\d+)",
                    m -> "fb:" + m.group(1), m -> "https://www.facebook.com/reel/" + m.group(1)),
            rule(Site.FACEBOOK, "https?://" + SUB + "facebook\\.com/[A-Za-z0-9.]+/videos/(?:[^/\\s?#]+/)?(\\d+)",
                    m -> "fb:" + m.group(1), InstaTray::clean),
            rule(Site.FACEBOOK, "https?://" + SUB + "facebook\\.com/share/[vr]/([A-Za-z0-9]+)",
                    m -> "fb:s:" + m.group(1), InstaTray::clean),
            rule(Site.FACEBOOK, "https?://fb\\.watch/([A-Za-z0-9_-]+)", m -> "fb:w:" + m.group(1), InstaTray::clean),
            // Pinterest pin (any country domain) and pin.it short links
            rule(Site.PINTEREST, "https?://" + SUB + "pinterest\\.[a-z]{2,3}(?:\\.[a-z]{2})?/pin/(\\d+)",
                    m -> "pin:" + m.group(1), m -> "https://www.pinterest.com/pin/" + m.group(1) + "/"),
            rule(Site.PINTEREST, "https?://pin\\.it/([A-Za-z0-9]+)", m -> "pin:s:" + m.group(1), InstaTray::clean),
            // Bluesky post
            rule(Site.BLUESKY, "https?://bsky\\.app/profile/([^/\\s]+)/post/([a-z0-9]+)",
                    m -> "bsky:" + m.group(2), m -> "https://bsky.app/profile/" + m.group(1) + "/post/" + m.group(2)),
            // Kick clips (path or ?clip=) and past broadcasts; channel pages are live and ignored
            rule(Site.KICK, "https?://" + SUB + "kick\\.com/([A-Za-z0-9_-]+)(?:/clips/|\\?clip=)(clip_[A-Za-z0-9]+)",
                    m -> "kick:" + m.group(2), m -> "https://kick.com/" + m.group(1) + "/clips/" + m.group(2)),
            rule(Site.KICK, "https?://" + SUB + "kick\\.com/(?:video|[A-Za-z0-9_-]+/videos)/([0-9a-f-]{36})",
                    m -> "kick:" + m.group(1), InstaTray::clean),
            // Vimeo video (optional private hash) and player embeds
            rule(Site.VIMEO, "https?://(?:www\\.|player\\.)?vimeo\\.com/(?:video/)?(\\d{5,})(?:/([0-9a-f]{6,}))?",
                    m -> "vimeo:" + m.group(1),
                    m -> "https://vimeo.com/" + m.group(1) + (m.group(2) != null ? "/" + m.group(2) : "")),
            // Dailymotion video and dai.ly
            rule(Site.DAILYMOTION, "https?://(?:" + SUB + "dailymotion\\.com/video/|dai\\.ly/)([a-z0-9]+)",
                    m -> "dm:" + m.group(1), m -> "https://www.dailymotion.com/video/" + m.group(1)),
            // Rumble video page and embed
            rule(Site.RUMBLE, "https?://" + SUB + "rumble\\.com/(v[a-z0-9]+)-[^\\s?#]*\\.html",
                    m -> "rumble:" + m.group(1), InstaTray::clean),
            rule(Site.RUMBLE, "https?://" + SUB + "rumble\\.com/embed/(v[a-z0-9]+)",
                    m -> "rumble:" + m.group(1), m -> "https://rumble.com/embed/" + m.group(1) + "/"),
            // Streamable video (short id; site pages like /login are excluded)
            rule(Site.STREAMABLE, "https?://" + SUB + "streamable\\.com/(?:[eo]/)?"
                            + "(?!(?:login|signup|upload|settings|pricing|about|privacy|terms|blog|careers|documents?)\\b)"
                            + "([a-z0-9]{4,10})(?![a-z0-9/])",
                    m -> "streamable:" + m.group(1), m -> "https://streamable.com/" + m.group(1)),
            // Imgur post, gallery, album or direct video file
            rule(Site.IMGUR, "https?://(?:i\\.)?imgur\\.com/(?:(?:gallery|a|t/[a-z0-9_]+)/)?"
                            + "(?!(?:upload|signin|register|user|search|hot|new|top|about|privacy|rules)\\b)"
                            + "([A-Za-z0-9]{5,8})(?:\\.(?:mp4|gifv|webm))?(?![A-Za-z0-9/])",
                    m -> "imgur:" + m.group(1), InstaTray::clean),
            // 9GAG post
            rule(Site.NINEGAG, "https?://" + SUB + "9gag\\.com/gag/([A-Za-z0-9]+)",
                    m -> "9gag:" + m.group(1), m -> "https://9gag.com/gag/" + m.group(1)),
            // Tumblr post: blog.tumblr.com/post/ID or tumblr.com/blog/ID
            rule(Site.TUMBLR, "https?://([a-z0-9-]+)\\.tumblr\\.com/post/(\\d+)",
                    m -> "tumblr:" + m.group(2), m -> "https://" + m.group(1) + ".tumblr.com/post/" + m.group(2)),
            rule(Site.TUMBLR, "https?://(?:www\\.)?tumblr\\.com/([a-z0-9-]+)/(\\d+)",
                    m -> "tumblr:" + m.group(2), m -> "https://" + m.group(1) + ".tumblr.com/post/" + m.group(2)),
            // LinkedIn post (the only URL form yt-dlp supports)
            rule(Site.LINKEDIN, "https?://" + SUB + "linkedin\\.com/posts/[^\\s?#/]*?-(\\d{15,})-[A-Za-z0-9_-]{2,}",
                    m -> "li:" + m.group(1), InstaTray::clean),
            // Snapchat public Spotlight
            rule(Site.SNAPCHAT, "https?://" + SUB + "snapchat\\.com/spotlight/([A-Za-z0-9_-]+)",
                    m -> "snap:" + m.group(1), InstaTray::clean),
            // Bilibili video (BV / av ids) and b23.tv short links
            rule(Site.BILIBILI, "https?://" + SUB + "bilibili\\.com/video/(BV[A-Za-z0-9]+|av\\d+)",
                    m -> "bili:" + m.group(1), m -> "https://www.bilibili.com/video/" + m.group(1) + "/"),
            rule(Site.BILIBILI, "https?://b23\\.tv/([A-Za-z0-9]+)", m -> "bili:s:" + m.group(1), InstaTray::clean),
            // Newgrounds movie
            rule(Site.NEWGROUNDS, "https?://" + SUB + "newgrounds\\.com/portal/view/(\\d+)",
                    m -> "ng:" + m.group(1), m -> "https://www.newgrounds.com/portal/view/" + m.group(1)),
            // Iwara video
            rule(Site.IWARA, "https?://" + SUB + "iwara\\.tv/video/([A-Za-z0-9]+)",
                    m -> "iwara:" + m.group(1), m -> "https://www.iwara.tv/video/" + m.group(1)),
            // Patreon post (only what the account in the cookies can see)
            rule(Site.PATREON, "https?://" + SUB + "patreon\\.com/posts/(?:[A-Za-z0-9-]*-)?(\\d+)",
                    m -> "patreon:" + m.group(1), InstaTray::clean),
            // Weibo post (desktop and mobile)
            rule(Site.WEIBO, "https?://(?:www\\.)?weibo\\.com/\\d+/([A-Za-z0-9]+)",
                    m -> "weibo:" + m.group(1), InstaTray::clean),
            rule(Site.WEIBO, "https?://m\\.weibo\\.cn/(?:detail|status)/(\\d+)",
                    m -> "weibo:" + m.group(1), InstaTray::clean),
            // Pixiv artwork (ugoira animations are converted to mp4)
            rule(Site.PIXIV, "https?://" + SUB + "pixiv\\.net/(?:[a-z]{2}/)?(?:artworks|i)/(\\d+)",
                    m -> "px:" + m.group(1), m -> "https://www.pixiv.net/artworks/" + m.group(1)),
            rule(Site.PIXIV, "https?://" + SUB + "pixiv\\.net/member_illust\\.php\\?\\S*?illust_id=(\\d+)",
                    m -> "px:" + m.group(1), m -> "https://www.pixiv.net/artworks/" + m.group(1)),
            // pixiv FANBOX post (creator.fanbox.cc or fanbox.cc/@creator)
            rule(Site.FANBOX, "https?://(?:[a-z0-9-]+\\.fanbox\\.cc|(?:www\\.)?fanbox\\.cc/@[a-z0-9_-]+)/posts/(\\d+)",
                    m -> "fanbox:" + m.group(1), InstaTray::clean),
            // Fantia post
            rule(Site.FANTIA, "https?://(?:www\\.)?fantia\\.jp/posts/(\\d+)",
                    m -> "fantia:" + m.group(1), m -> "https://fantia.jp/posts/" + m.group(1)),
            // DeviantArt deviation (slug ends with the numeric id)
            rule(Site.DEVIANTART, "https?://(?:www\\.)?deviantart\\.com/[A-Za-z0-9_-]+/art/[A-Za-z0-9_-]*?(\\d{6,})",
                    m -> "da:" + m.group(1), InstaTray::clean),
            // Misskey note
            rule(Site.MISSKEY, "https?://misskey\\.io/notes/([a-z0-9]+)", m -> "mk:" + m.group(1), InstaTray::clean),
            // Mastodon status on the instances gallery-dl knows
            rule(Site.MASTODON, "https?://(mastodon\\.social|pawoo\\.net)/@[A-Za-z0-9_]+/(\\d+)",
                    m -> "masto:" + m.group(1) + ":" + m.group(2), InstaTray::clean));

    /** Every site except the two catch-alls. */
    static final List<Site> NAMED_SITES = Arrays.stream(Site.values()).filter(s -> !s.isCatchAll()).toList();

    /**
     * gallery-dl categories handled by OTHER_GALLERY: platforms where creators post their own work. Aggregators,
     * boorus, manga/doujin sites and file hosts that re-host others' work stay out on purpose.
     */
    static final List<String> CREATOR_PLATFORMS = List.of(
            "500px", "artstation", "boosty", "cara", "cien", "fansly", "flickr", "foriio", "furaffinity",
            "inkbunny", "itaku", "lofter", "pexels", "pillowfort", "plurk", "poipiku", "postype", "skeb",
            "smugmug", "sofurry", "subscribestar", "vk", "vsco", "weasyl", "xfolio");

    /** Most items a catch-all yt-dlp link may download (an extractor can return an album or a multi-part episode). */
    private static final int CATCH_ALL_MAX_ITEMS = 10;
    /** Most unknown URLs checked per clipboard change. */
    private static final int MAX_PROBED_URLS = 5;
    private static final Pattern ANY_URL = Pattern.compile("https?://[^\\s<>\"'`]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_HOST = Pattern.compile("^https?://(?:[^@/?#]*@)?([^/?#:]+)", Pattern.CASE_INSENSITIVE);

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

    private static final int INSTANCE_PORT = 48623;

    private static final String[] BIN_DIRS = {"/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin"};

    /** Waiting jobs in download order (includes automatic retries waiting for their delay). Guarded by itself. */
    private final LinkedList<Job> pending = new LinkedList<>();
    private volatile Job current;
    private volatile Process currentProcess;
    private volatile boolean paused = false;
    /** True while Homebrew installs/upgrades tools: the queue waits so yt-dlp is never replaced mid-download. */
    private volatile boolean toolsBusy = false;
    private static final long UPGRADE_EVERY_MS = 7L * 24 * 3600 * 1000;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clipboard");
        t.setDaemon(true);
        return t;
    });

    private final Set<String> done = ConcurrentHashMap.newKeySet();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private volatile boolean watching = true;
    /** Site currently downloading (or about to); drives the tray icon color. Null when idle. */
    private volatile Site activeSite;
    private final Map<String, BufferedImage> iconCache = new ConcurrentHashMap<>();
    private volatile String lastIconKey = "IDLE:null:-1";
    private volatile String lastTooltip = "";
    private volatile long lastTooltipAt = 0;
    /** Progress of the current download, 0..100, or -1 when unknown. */
    private volatile int activePercent = -1;
    private volatile String lastClip = "";
    /** Newest first. Guarded by itself. */
    private final List<Job> jobs = new ArrayList<>();
    private DownloadsWindow downloads;
    private SettingsDialog settingsDialog;

    private TrayIcon trayIcon;
    private MenuItem statusItem;
    private MenuItem cookiesItem;
    private CheckboxMenuItem watchItem;
    private CheckboxMenuItem notifyItem;
    private volatile boolean notifications = true;
    private static final Path DEFAULT_VIDEOS_DIR = HOME.resolve("Documents").resolve("Videos");
    private volatile Path videosDir = DEFAULT_VIDEOS_DIR;
    /** One subfolder per site (Videos/Instagram, Videos/YouTube…) instead of everything together. */
    private volatile boolean subfolders = false;
    private CheckboxMenuItem subfoldersItem;
    private CheckboxMenuItem pauseItem;
    /** Opt-in: pages no extractor knows go to yt-dlp's Generic extractor (embedded players, HLS, <video>). */
    private volatile boolean embeds = false;
    private CheckboxMenuItem embedsItem;
    private volatile int maxRetries = 2;
    private volatile int retryDelaySec = 30;
    /** Downloads window: the "Detalles" panel is open. */
    private volatile boolean detailsOpen = false;
    private static final Integer[] RETRY_DELAYS = {10, 30, 60, 300};

    public static void main(String[] args) throws Exception {
        System.setProperty("apple.awt.UIElement", "true"); // no Dock icon
        System.setProperty("apple.awt.application.appearance", "system"); // title bars and dialogs follow dark mode
        if (!SystemTray.isSupported()) {
            System.err.println("System tray not supported");
            System.exit(1);
        }
        // Single instance: if another copy is running, ask it to show its window and quit
        ServerSocket server;
        try {
            server = new ServerSocket(INSTANCE_PORT, 5, InetAddress.getLoopbackAddress());
        } catch (IOException alreadyRunning) {
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), INSTANCE_PORT)) {
                s.getOutputStream().write('\n');
            } catch (IOException ignored) {
            }
            System.exit(0);
            return;
        }
        InstaTray app = new InstaTray();
        app.start();
        app.listenForOtherInstances(server);
    }

    private void start() throws Exception {
        Files.createDirectories(STATE_DIR);
        Files.createDirectories(Paths.get(loadSettings().getProperty("videosDir", DEFAULT_VIDEOS_DIR.toString())));
        Files.createDirectories(STATE_DIR);
        if (Files.exists(HISTORY)) {
            done.addAll(Files.readAllLines(HISTORY, StandardCharsets.UTF_8));
        }
        Properties settings = loadSettings();
        notifications = Boolean.parseBoolean(settings.getProperty("notifications", "true"));
        subfolders = Boolean.parseBoolean(settings.getProperty("subfolders", "false"));
        embeds = Boolean.parseBoolean(settings.getProperty("embeds", "false"));
        detailsOpen = Boolean.parseBoolean(settings.getProperty("details", "false"));
        maxRetries = Integer.parseInt(settings.getProperty("maxRetries", "2"));
        retryDelaySec = Integer.parseInt(settings.getProperty("retryDelaySec", "30"));
        videosDir = Paths.get(settings.getProperty("videosDir", DEFAULT_VIDEOS_DIR.toString()));
        loadJobs();

        Theme.refresh(); // reads the appearance with a process: never on the EDT
        EventQueue.invokeAndWait(() -> {
            buildTray();
            // stays hidden: the app starts silently in the menu bar
            downloads = new DownloadsWindow(this, () -> settingsDialog.open());
            settingsDialog = new SettingsDialog(downloads.frame(), this);
        });
        // Double-click on the app while it is already running -> show the window again
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            Desktop.getDesktop().addAppEventListener((AppReopenedListener) e -> EventQueue.invokeLater(this::showWindow));
        }

        Thread maintenance = new Thread(() -> {
            ensureTools();
            upgradeYtDlpIfStale();
        }, "maintenance");
        maintenance.setDaemon(true);
        maintenance.start();

        updateTrayIcon(); // initial tooltip

        Thread downloader = new Thread(this::workerLoop, "downloader");
        downloader.setDaemon(true);
        downloader.start();
        resumeInterruptedJobs();

        // Ignore whatever is already in the clipboard at startup
        lastClip = readClipboard();
        poller.execute(() -> setStatus("Vigilando portapapeles"));
        poller.scheduleWithFixedDelay(this::pollClipboard, 1, 1, TimeUnit.SECONDS);
        log("InstaTray started. Output: " + videosDir + (subfolders ? "/<Site>" : ""));
    }

    // ---------------------------------------------------------------- UI

    private void buildTray() {
        PopupMenu menu = new PopupMenu();

        statusItem = new MenuItem("Listo — copia un enlace de video");
        statusItem.setEnabled(false);
        menu.add(statusItem);

        cookiesItem = new MenuItem("Cookies: buscando en Descargas…");
        cookiesItem.setEnabled(false);
        menu.add(cookiesItem);
        menu.addSeparator();

        watchItem = new CheckboxMenuItem("Vigilar portapapeles", true);
        watchItem.addItemListener(e -> setWatching(watchItem.getState()));
        menu.add(watchItem);

        notifyItem = new CheckboxMenuItem("Avisar si una descarga falla", notifications);
        notifyItem.addItemListener(e -> setNotifications(notifyItem.getState()));
        menu.add(notifyItem);

        MenuItem now = new MenuItem("Descargar enlace copiado ahora");
        now.addActionListener(e -> downloadClipboardNow());
        menu.add(now);

        subfoldersItem = new CheckboxMenuItem("Una subcarpeta por red social", subfolders);
        subfoldersItem.addItemListener(e -> setSubfolders(subfoldersItem.getState()));
        menu.add(subfoldersItem);

        embedsItem = new CheckboxMenuItem("Buscar video embebido en otras páginas", embeds);
        embedsItem.addItemListener(e -> setEmbeds(embedsItem.getState()));
        menu.add(embedsItem);

        MenuItem hist = new MenuItem("Cola e historial…");
        hist.addActionListener(e -> showWindow());
        menu.add(hist);

        pauseItem = new CheckboxMenuItem("Pausar cola", false);
        pauseItem.addItemListener(e -> setPaused(pauseItem.getState()));
        menu.add(pauseItem);

        MenuItem show = new MenuItem("Mostrar ventana");
        show.addActionListener(e -> showWindow());
        menu.add(show);
        menu.addSeparator();

        MenuItem open = new MenuItem("Abrir carpeta de videos");
        open.addActionListener(e -> openPath(videosDir));
        menu.add(open);

        MenuItem openLog = new MenuItem("Ver registro");
        openLog.addActionListener(e -> openPath(LOG));
        menu.add(openLog);
        menu.addSeparator();

        MenuItem quit = new MenuItem("Salir");
        quit.addActionListener(e -> {
            SystemTray.getSystemTray().remove(trayIcon);
            System.exit(0);
        });
        menu.add(quit);

        BufferedImage icon = AeroIcon.render(44); // 22pt @2x for Retina menu bar
        trayIcon = new TrayIcon(icon, "InstaTray", menu);
        trayIcon.setImageAutoSize(true);
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            throw new RuntimeException(e);
        }
    }

    private void listenForOtherInstances(ServerSocket server) {
        Thread t = new Thread(() -> {
            while (true) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(2000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    String cmd = in.readLine();
                    String reply = control(cmd == null ? "" : cmd.trim());
                    s.getOutputStream().write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                }
            }
        }, "instance");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Loopback-only control channel. An empty line (a second launch) shows the window; the other
     * commands exist so the queue can be exercised from a terminal: queue, tooltip, get TEXT, pause, resume,
     * top KEY, cancel KEY, remove KEY.
     */
    private String control(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1] : "";
        Job job = queueSnapshot().stream().filter(j -> j.link.key().equals(arg)).findFirst().orElse(null);
        switch (parts[0]) {
            case "" -> EventQueue.invokeLater(this::showWindow);
            case "queue" -> {
                StringBuilder sb = new StringBuilder(paused ? "paused" : "running");
                for (Job j : queueSnapshot()) sb.append(" | ").append(j.link.key()).append(' ').append(j.statusText());
                return sb.toString();
            }
            case "tooltip" -> { return lastTooltip; }
            case "get" -> { return handleText(arg, true) ? "ok" : "no supported link"; } // like the window field
            case "pause" -> setPaused(true);
            case "resume" -> setPaused(false);
            case "top" -> { if (job != null) move(job, Integer.MIN_VALUE); else return "no such job"; }
            case "cancel" -> { if (job != null) cancel(job); else return "no such job"; }
            case "remove" -> {
                Job any = job != null ? job
                        : jobsSnapshot().stream().filter(j -> j.link.key().equals(arg)).findFirst().orElse(null);
                if (any != null) remove(any); else return "no such job";
            }
            default -> { return "unknown command"; }
        }
        return "ok";
    }

    private void showWindow() {
        downloads.show(JobView.Filter.ALL);
    }

    @Override
    public void setWatching(boolean on) {
        watching = on;
        EventQueue.invokeLater(() -> {
            watchItem.setState(on);
        });
        poller.execute(() -> setStatus(on ? "Vigilando portapapeles" : "En pausa"));
    }

    @Override
    public void setNotifications(boolean on) {
        notifications = on;
        EventQueue.invokeLater(() -> {
            notifyItem.setState(on);
        });
        saveSetting("notifications", String.valueOf(on));
        log("Notifications " + (on ? "on" : "off"));
    }

    @Override
    public void setSubfolders(boolean on) {
        subfolders = on;
        EventQueue.invokeLater(() -> {
            subfoldersItem.setState(on);
        });
        saveSetting("subfolders", String.valueOf(on));
        log("Subfolders " + (on ? "on" : "off"));
    }

    @Override
    public void setEmbeds(boolean on) {
        embeds = on;
        EventQueue.invokeLater(() -> {
            embedsItem.setState(on);
        });
        saveSetting("embeds", String.valueOf(on));
        log("Embedded-video pages " + (on ? "on" : "off"));
    }

    @Override
    public boolean isWatching() {
        return watching;
    }

    @Override
    public boolean subfolders() {
        return subfolders;
    }

    @Override
    public boolean embeds() {
        return embeds;
    }

    @Override
    public boolean notifications() {
        return notifications;
    }

    @Override
    public int maxRetries() {
        return maxRetries;
    }

    @Override
    public void setMaxRetries(int n) {
        maxRetries = n;
        saveSetting("maxRetries", String.valueOf(n));
    }

    @Override
    public int retryDelaySec() {
        return retryDelaySec;
    }

    @Override
    public void setRetryDelaySec(int sec) {
        retryDelaySec = sec;
        saveSetting("retryDelaySec", String.valueOf(sec));
    }

    @Override
    public boolean detailsOpen() {
        return detailsOpen;
    }

    @Override
    public void setDetailsOpen(boolean open) {
        detailsOpen = open;
        saveSetting("details", String.valueOf(open));
    }

    @Override
    public String videosFolderLabel() {
        return displayPath(videosDir) + (subfolders ? "/<red social>" : "");
    }

    @Override
    public void openVideosFolder() {
        openPath(videosDir);
    }

    @Override
    public void cookiesSummary(Consumer<String> onEdt) {
        poller.execute(() -> {
            String text = cookiesLabel();
            EventQueue.invokeLater(() -> onEdt.accept(text));
        });
    }

    @Override
    public void submit(String text, Runnable onNothingFound) {
        poller.execute(() -> {
            if (!handleText(text, true)) EventQueue.invokeLater(onNothingFound);
        });
    }

    /** -1 when the file is gone (moved or deleted by the user). */
    static long fileSize(String file) {
        try {
            return Files.size(Paths.get(file));
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    @Override
    public void chooseVideosDir(Window owner) {
        // Native macOS folder picker
        System.setProperty("apple.awt.fileDialogForDirectories", "true");
        String title = "Elige la carpeta para los videos";
        FileDialog fd = owner instanceof Dialog d ? new FileDialog(d, title, FileDialog.LOAD)
                : new FileDialog(owner instanceof Frame f ? f : null, title, FileDialog.LOAD);
        fd.setDirectory(videosDir.toString());
        fd.setVisible(true);
        System.setProperty("apple.awt.fileDialogForDirectories", "false");
        if (fd.getFile() == null) return;
        Path chosen = Paths.get(fd.getDirectory(), fd.getFile());
        videosDir = chosen;
        saveSetting("videosDir", chosen.toString());
        log("Videos folder -> " + chosen);
    }

    /** Folder for a site's videos, created on demand. */
    private Path outDir(Site site) {
        return subfolders ? videosDir.resolve(site.label) : videosDir;
    }

    /** "~/Documents/Videos" -> "Documentos/Videos" for messages. */
    static String displayPath(Path p) {
        Path docs = HOME.resolve("Documents");
        if (p.startsWith(docs)) return "Documentos/" + docs.relativize(p);
        if (p.startsWith(HOME)) return "~/" + HOME.relativize(p);
        return p.toString();
    }

    private static synchronized void saveSetting(String key, String value) {
        Properties props = loadSettings();
        props.setProperty(key, value);
        try (OutputStream out = Files.newOutputStream(SETTINGS)) {
            props.store(out, "InstaTray settings");
        } catch (IOException e) {
            log("ERROR saving settings: " + e);
        }
    }

    private static Properties loadSettings() {
        Properties props = new Properties();
        if (Files.exists(SETTINGS)) {
            try (InputStream in = Files.newInputStream(SETTINGS)) {
                props.load(in);
            } catch (IOException ignored) {
            }
        }
        return props;
    }

    private void downloadClipboardNow() {
        poller.execute(() -> {
            String clip = readClipboard();
            if (!handleText(clip, readClipboardHtml(), true)) {
                setStatus("No hay un enlace de video compatible copiado");
            }
        });
    }

    /**
     * Idle: project orb with the download arrow. Busy: orb in the active site's color with the
     * number of pending downloads (current one + queue) in the center.
     */
    private void updateTrayIcon() {
        int pending = queued.size();
        Site site = activeSite;
        AeroIcon.Palette palette = pending == 0 || site == null ? AeroIcon.Palette.IDLE : site.palette();
        int pct = palette == AeroIcon.Palette.IDLE ? -1 : activePercent;
        // One item: the number is its download percentage. Several: the number is how many are pending.
        // (one item with unknown progress, e.g. gallery-dl: the site-colored orb shows the download arrow)
        String badge = palette == AeroIcon.Palette.IDLE ? null
                : pending == 1 ? (pct < 0 ? null : pct + "%")
                : pending > 9 ? "9+" : String.valueOf(pending);
        int step = pct < 0 ? -1 : pct / 5 * 5; // 5% steps keep the icon cache small (fill level)
        String key = palette + ":" + badge + ":" + step;
        BufferedImage img = iconCache.computeIfAbsent(key,
                k -> AeroIcon.render(44, palette, badge, step < 0 ? -1f : step / 100f));
        if (!key.equals(lastIconKey)) log("Tray icon -> " + key);
        lastIconKey = key;
        String tip = tooltip(pending);
        lastTooltip = tip;
        lastTooltipAt = System.currentTimeMillis();
        EventQueue.invokeLater(() -> {
            trayIcon.setImage(img);
            trayIcon.setToolTip(tip);
        });
    }

    /** "YouTube · Me at the zoo — 45% · 2.1 MB/s · quedan 0:12 · +2 en cola" */
    private String tooltip(int pending) {
        if (paused && pending > 0) return "InstaTray — cola en pausa, " + pending + (pending == 1 ? " pendiente" : " pendientes");
        Job j = current;
        if (j == null || j.isFinished()) {
            return pending == 0 ? "InstaTray — sin descargas" : "InstaTray — " + pending + " en cola";
        }
        StringBuilder sb = new StringBuilder(j.link.label());
        if (!j.title.isBlank()) sb.append(" · ").append(shorten(j.title, 50));
        sb.append(" — ").append(j.percent >= 0 ? j.percent + "%" : "preparando…");
        if (!j.speed.isBlank()) sb.append(" · ").append(j.speed);
        if (!j.eta.isBlank()) sb.append(" · quedan ").append(j.eta);
        if (pending > 1) sb.append(" · +").append(pending - 1).append(" en cola");
        if (j.attempt > 0) sb.append(" · reintento ").append(j.attempt);
        return sb.toString();
    }

    /** Live tooltip update for speed / time-left changes, at most about once a second. */
    private void refreshTooltip() {
        long now = System.currentTimeMillis();
        if (now - lastTooltipAt < 900) return;
        String tip = tooltip(queued.size());
        if (tip.equals(lastTooltip)) return;
        lastTooltip = tip;
        lastTooltipAt = now;
        EventQueue.invokeLater(() -> trayIcon.setToolTip(tip));
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String formatSpeed(double bytesPerSec) {
        if (bytesPerSec >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSec / (1024 * 1024));
        if (bytesPerSec >= 1024) return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSec / 1024);
        return String.format(Locale.ROOT, "%.0f B/s", bytesPerSec);
    }

    static String formatBytes(double bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f GB", bytes / (1024L * 1024 * 1024));
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024 * 1024));
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024);
    }

    static String formatEta(long seconds) {
        long h = seconds / 3600, m = seconds % 3600 / 60, sec = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    // Must not run on the EDT: reading ~/Downloads may block on the macOS privacy prompt
    private void setStatus(String text) {
        String cookies = cookiesLabel();
        EventQueue.invokeLater(() -> {
            statusItem.setLabel(text);
            cookiesItem.setLabel(cookies);
        });
    }

    private String cookiesLabel() {
        List<String> with = new ArrayList<>();
        for (Site site : NAMED_SITES) {
            if (findCookies(site) != null) with.add(site.label);
        }
        return with.isEmpty() ? "Cookies: ninguna en Descargas"
                : "Cookies: " + String.join(", ", with) + " (" + with.size() + " de " + NAMED_SITES.size() + " sitios)";
    }

    // ---------------------------------------------------------------- clipboard

    private String readClipboard() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                Object data = cb.getData(DataFlavor.stringFlavor);
                return data == null ? "" : data.toString();
            }
        } catch (Exception ignored) {
            // Clipboard can be momentarily locked by another app
        }
        return "";
    }

    /** The HTML flavor of a rich copy (a selection copied in a browser), or "". */
    private String readClipboardHtml() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb.isDataFlavorAvailable(DataFlavor.allHtmlFlavor)) {
                Object data = cb.getData(DataFlavor.allHtmlFlavor);
                return data == null ? "" : data.toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private void pollClipboard() {
        if (!watching) return;
        String clip = readClipboard();
        if (clip.equals(lastClip)) return;
        lastClip = clip;
        handleText(clip, readClipboardHtml(), false);
    }

    /**
     * A supported link found in the clipboard. Catch-all keys carry the library's name for the site:
     * "ytdl:Niconico:sm9" (extractor + id) or "gdl:artstation:URL" (category + URL).
     */
    record Link(Site site, String key, String url) {
        /** What the user sees as the site: the label, or the extractor/category name for catch-alls. */
        String label() {
            if (!site.isCatchAll()) return site.label;
            String[] k = key.split(":", 3);
            if (k.length < 2 || k[1].isEmpty()) return site.label;
            return Character.toUpperCase(k[1].charAt(0)) + k[1].substring(1); // gallery-dl categories are lower-case
        }
    }

    private boolean handleText(String text, boolean force) {
        return handleText(text, "", force);
    }

    /**
     * Queues every supported link found in the text, or in the page when the text is HTML source / the clipboard
     * also carries HTML (rich copy). Returns true if any link was found.
     */
    private boolean handleText(String text, String richHtml, boolean force) {
        boolean found = false;
        boolean source = looksLikeHtml(text);
        String plain = source ? "" : text;
        // explicit requests (window field, "Descargar enlace copiado ahora") may use the generic extractor even with embeds off
        List<Link> links = new ArrayList<>(findLinks(plain));
        links.addAll(probeLibraries(otherCandidates(plain), embeds || force));
        String html = source ? text : richHtml;
        PageVideos pv = html.isEmpty() ? null : pageVideos(html);
        List<Link> fromPage = pv == null ? List.of() : pageLinks(pv, force);
        links.addAll(fromPage);
        for (Link link : links) {
            found = true;
            String key = link.key();
            if (queued.contains(key)) continue;
            if (done.contains(key) && !force) {
                setStatus("Ya descargado: " + link.label() + " " + shortKey(key));
                continue;
            }
            Job job = new Job(link, System.currentTimeMillis());
            if (pv != null && pv.page() != null && fromPage.contains(link)) job.page = pv.page(); // for "Abrir página"
            synchronized (jobs) {
                jobs.add(0, job);
            }
            enqueue(job);
        }
        return found;
    }

    /**
     * Links for the videos of a pasted page. What the page itself plays may go to the generic extractor (copying a
     * page with a video in it is the request); embedded players only through a real extractor unless embeds is on,
     * so ad and widget iframes stay out. Falls back to the page's own canonical URL.
     */
    private List<Link> pageLinks(PageVideos pv, boolean force) {
        if (pv.isEmpty()) return List.of();
        log("Pasted page: " + pv.media().size() + " media, " + pv.players().size() + " players, page " + pv.page());
        String media = String.join("\n", pv.media()), players = String.join("\n", pv.players());
        List<Link> links = new ArrayList<>(findLinks(media));
        links.addAll(probeLibraries(otherCandidates(media), true));
        links.addAll(findLinks(players));
        links.addAll(probeLibraries(otherCandidates(players), embeds || force));
        if (links.isEmpty() && pv.page() != null) {
            links.addAll(findLinks(pv.page()));
            links.addAll(probeLibraries(otherCandidates(pv.page()), embeds || force));
        }
        return links;
    }

    private void enqueue(Job job) {
        job.attempt = 0;
        queued.add(job.link.key());
        job.status = Job.Status.QUEUED;
        job.percent = -1;
        job.message = "";
        job.files = List.of();
        job.clearOutput();
        if (activeSite == null) activeSite = job.link.site();
        updateTrayIcon();
        setStatus("En cola: " + job.link.label() + " " + shortKey(job.link.key()));
        saveJobs();
        synchronized (pending) {
            pending.addLast(job);
            pending.notifyAll();
        }
    }

    /** Re-runs a finished or failed job (from the downloads window). */
    @Override
    public void retry(Job job) {
        if (!job.isFinished()) return;
        if (queued.contains(job.link.key())) return;
        log("Retry " + job.link.url());
        enqueue(job);
    }

    List<Job> jobsSnapshot() {
        synchronized (jobs) {
            return new ArrayList<>(jobs);
        }
    }

    // ---------------------------------------------------------------- queue

    private void workerLoop() {
        while (true) {
            Job next = null;
            synchronized (pending) {
                long now = System.currentTimeMillis();
                if (!paused && !toolsBusy) {
                    for (Job j : pending) {
                        if (j.notBefore <= now) {
                            next = j;
                            break;
                        }
                    }
                }
                if (next == null) {
                    try {
                        pending.wait(500); // wakes on enqueue / resume, or re-checks waiting retries
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                pending.remove(next);
                current = next;
            }
            try {
                download(next);
            } catch (Throwable t) {
                log("ERROR worker: " + t);
            } finally {
                current = null;
                currentProcess = null;
                updateTrayIcon(); // tooltip back to "sin descargas" / next in queue
            }
        }
    }

    private void resumeInterruptedJobs() {
        for (Job j : jobsSnapshot()) {
            if (!j.isFinished() && !queued.contains(j.link.key())) {
                log("Resuming " + j.link.url());
                enqueue(j);
            }
        }
    }

    /** What is downloading now followed by what waits, in order. */
    @Override
    public List<Job> queueSnapshot() {
        List<Job> list = new ArrayList<>();
        Job c = current;
        if (c != null) list.add(c);
        synchronized (pending) {
            list.addAll(pending);
        }
        return list;
    }

    @Override
    public List<Job> finishedSnapshot() {
        return jobsSnapshot().stream().filter(Job::isFinished).toList();
    }

    /** delta -1 = up, +1 = down, Integer.MIN_VALUE = to the top. */
    @Override
    public void move(Job job, int delta) {
        synchronized (pending) {
            int i = pending.indexOf(job);
            if (i < 0) return;
            int to = delta == Integer.MIN_VALUE ? 0 : Math.max(0, Math.min(pending.size() - 1, i + delta));
            pending.remove(i);
            pending.add(to, job);
            pending.notifyAll();
        }
        log("Queue move " + job.link.url() + " -> " + (delta == Integer.MIN_VALUE ? "top" : delta));
    }

    @Override
    public void cancel(Job job) {
        boolean wasPending;
        synchronized (pending) {
            wasPending = pending.remove(job);
        }
        if (wasPending) {
            markCancelled(job);
            return;
        }
        if (job == current) {
            job.cancelRequested = true; // download() sees it after the process dies
            Process p = currentProcess;
            if (p != null) {
                p.descendants().forEach(ProcessHandle::destroy); // ffmpeg children too
                p.destroy();
            }
            log("Cancel requested " + job.link.url());
        }
    }

    @Override
    public void cancelAll() {
        List<Job> waiting;
        synchronized (pending) {
            waiting = new ArrayList<>(pending);
        }
        waiting.forEach(this::remove);
        Job c = current;
        if (c != null) cancel(c);
    }

    @Override
    public void remove(Job job) {
        boolean wasPending;
        synchronized (pending) { // the worker sets current under this lock
            if (job == current) return; // only cancelled, never removed while downloading
            wasPending = pending.remove(job);
        }
        if (wasPending) {
            queued.remove(job.link.key());
            if (queued.isEmpty()) activeSite = null;
        }
        synchronized (jobs) {
            jobs.remove(job);
        }
        saveJobs();
        updateTrayIcon();
        log("Removed " + job.link.url());
    }

    @Override
    public void removeFinished(Predicate<Job> which) {
        synchronized (jobs) {
            jobs.removeIf(j -> j.isFinished() && which.test(j));
        }
        saveJobs();
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void setPaused(boolean on) {
        paused = on;
        synchronized (pending) {
            pending.notifyAll();
        }
        EventQueue.invokeLater(() -> pauseItem.setState(on));
        log("Queue " + (on ? "paused" : "resumed"));
        poller.execute(() -> setStatus(on ? "Cola en pausa (lo que se está bajando termina)" : "Vigilando portapapeles"));
        updateTrayIcon();
    }

    private void markCancelled(Job job) {
        job.status = Job.Status.CANCELLED;
        job.percent = -1;
        job.message = "Cancelado";
        job.notBefore = 0;
        queued.remove(job.link.key());
        if (queued.isEmpty()) activeSite = null;
        updateTrayIcon();
        saveJobs();
        log("Cancelled " + job.link.url());
    }

    /**
     * Converts a downloaded file to mp4 H.264 + AAC unless it already is mp4 (Pixiv ugoira come out as
     * H.264 mp4 already). GIFs and webm become mp4; the original is removed. Returns the final path.
     */
    private static String toPlayableMp4(String file, String ffmpeg, Job job) {
        if (file.toLowerCase(Locale.ROOT).endsWith(".mp4") || ffmpeg == null) return file;
        String out = file.replaceFirst("\\.[A-Za-z0-9]+$", "") + ".mp4";
        List<String> cmd = List.of(ffmpeg, "-y", "-loglevel", "error", "-i", file,
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:a", "aac", "-movflags", "+faststart", out);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String err = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (p.waitFor() == 0 && Files.exists(Paths.get(out))) {
                Files.deleteIfExists(Paths.get(file));
                job.addOutput("[InstaTray] convertido a mp4: " + out);
                return out;
            }
            job.addOutput("[InstaTray] no se pudo convertir " + file + ": " + err);
        } catch (Exception e) {
            job.addOutput("[InstaTray] no se pudo convertir " + file + ": " + e);
        }
        return file;
    }

    /** Set once Terminal was opened for the Pixiv login, so repeated failures don't keep opening it. */
    private volatile boolean pixivLoginOpened;

    /**
     * Pixiv needs a one-time OAuth login: gallery-dl walks the user through it in Terminal and caches the token.
     * Opened by the first Pixiv download that fails for lack of a token (once per session).
     */
    void connectPixiv() {
        if (pixivLoginOpened) return;
        pixivLoginOpened = true;
        String galleryDl = findBin("gallery-dl");
        if (galleryDl == null) {
            notify("InstaTray", "gallery-dl todavía no está instalado; vuelve a intentarlo en un minuto");
            poller.execute(this::ensureTools);
            return;
        }
        String script = "tell application \"Terminal\"\n activate\n do script \"clear; echo 'InstaTray: conectar Pixiv (solo una vez)'; "
                + galleryDl + " oauth:pixiv\"\nend tell";
        try {
            new ProcessBuilder("osascript", "-e", script).start();
            log("Opened Terminal for gallery-dl oauth:pixiv");
        } catch (IOException e) {
            log("ERROR opening Terminal: " + e);
        }
    }

    /**
     * Stops yt-dlp and its children and waits until they are really gone: a dying yt-dlp can still
     * reopen its .part file for the next fragment, so cleaning up earlier leaves the file behind.
     */
    private static void killAndWait(Process p) {
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
     * Removes what a cancelled download left behind (.part, .ytdl, unmerged streams). macOS privacy
     * (TCC) does not let this app list ~/Documents, but files can be removed by exact path, so it
     * deletes the paths yt-dlp announced instead of scanning the folder.
     */
    private static void deletePartials(Job job) {
        List<String> paths;
        synchronized (job.destinations) {
            paths = new ArrayList<>(job.destinations);
        }
        for (String d : paths) {
            for (String candidate : new String[]{d, d + ".part", d + ".ytdl", d + ".part.ytdl"}) {
                try {
                    if (Files.deleteIfExists(Paths.get(candidate))) log("Deleted partial " + candidate);
                } catch (IOException e) {
                    log("ERROR deleting " + candidate + ": " + e);
                }
            }
        }
        job.destinations.clear();
    }

    private void loadJobs() {
        if (!Files.exists(JOBS)) return;
        try {
            for (String line : Files.readAllLines(JOBS, StandardCharsets.UTF_8)) {
                Job j = Job.fromLine(line);
                if (j != null) {
                    if (j.status == Job.Status.DONE && !j.files.isEmpty()) j.bytes = fileSize(j.files.get(0));
                    jobs.add(j);
                }
            }
        } catch (IOException e) {
            log("ERROR loading jobs: " + e);
        }
    }

    private void saveJobs() {
        List<String> lines;
        synchronized (jobs) {
            while (jobs.size() > MAX_JOBS) jobs.remove(jobs.size() - 1);
            lines = jobs.stream().map(Job::toLine).toList();
        }
        try {
            Files.write(JOBS, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log("ERROR saving jobs: " + e);
        }
    }

    static List<Link> findLinks(String text) {
        List<Link> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LinkRule r : RULES) {
            Matcher m = r.pattern().matcher(text);
            while (m.find()) {
                String key = r.key().apply(m);
                if (seen.add(key)) links.add(new Link(r.site(), key, r.url().apply(m)));
            }
        }
        return links;
    }

    /**
     * URLs in the text that no rule matched and that don't belong to a known site (their profiles, channels and
     * live pages must stay ignored), skipping bare home pages. These are the ones worth asking the libraries about.
     */
    static List<String> otherCandidates(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = ANY_URL.matcher(text);
        while (m.find() && out.size() < MAX_PROBED_URLS) {
            String url = m.group().replaceFirst("[.,;:!?)\\]}>]+$", "");
            String host = host(url);
            if (host.isEmpty() || out.contains(url)) continue;
            if (RULES.stream().anyMatch(r -> r.pattern().matcher(url).find())) continue;
            if (NAMED_SITES.stream().anyMatch(s -> s.ownsHost(host))) continue;
            if (url.substring(m.group().indexOf(host) + host.length()).replaceAll("[/?#]", "").isEmpty()) continue;
            out.add(url);
        }
        return out;
    }

    /**
     * Video URLs found in a pasted page (its HTML source, or the HTML flavor of a rich copy), best first.
     * {@code media}: what the page itself plays (video/source tags, og:video, JSON-LD contentUrl, .mp4/.m3u8/.mpd
     * URLs); {@code players}: embedded players (iframes, JSON-LD embedUrl); {@code page}: canonical URL or null.
     */
    record PageVideos(List<String> media, List<String> players, String page) {
        boolean isEmpty() {
            return media.isEmpty() && players.isEmpty() && page == null;
        }
    }

    private static final Pattern HTML_SOURCE =
            Pattern.compile("<(?:!doctype\\s+html|html|head|body|video|iframe|meta)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG =
            Pattern.compile("<(video|source|iframe|embed|meta|link|base)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ATTR =
            Pattern.compile("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");
    private static final Pattern MEDIA_URL = Pattern.compile(
            "(?:https?:)?//[^\\s<>\"'`()]+?\\.(?:mp4|m4v|mov|webm|m3u8|mpd)(?:\\?[^\\s<>\"'`()]*)?(?=[\\s<>\"'`()]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_VIDEO = Pattern.compile("\"(contentUrl|embedUrl)\"\\s*:\\s*\"([^\"]+)\"");
    /** Media paths relative to the page: "/media/clip.mp4", './v/index.m3u8'. */
    private static final Pattern RELATIVE_MEDIA = Pattern.compile(
            "[\"'](\\.{0,2}/(?!/)[^\\s<>\"'`()]+?\\.(?:mp4|m4v|mov|webm|m3u8|mpd)(?:\\?[^\\s<>\"'`()]*)?)[\"']",
            Pattern.CASE_INSENSITIVE);
    /** Video attributes of any element: data-video="…", data-hls-src="…", data-mp4="…". */
    private static final Pattern DATA_VIDEO_ATTR = Pattern.compile("\\sdata-(?:video|video-src|video-url|src-video|hls|"
            + "hls-src|hls-url|dash|dash-src|mp4|stream|stream-url|stream-src)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
            Pattern.CASE_INSENSITIVE);
    /** Player configs (JW Player, Clappr, custom ones): file: "…", hls: "…", videoUrl: "…". */
    private static final Pattern PLAYER_CONFIG_URL = Pattern.compile("[\"']?(?:file|hls|hlsUrl|hls_url|dash|dashUrl|mp4|"
            + "videoUrl|video_url|videoSrc|streamUrl|stream_url|manifestUrl|playbackUrl)[\"']?\\s*:\\s*"
            + "[\"']((?:https?:)?/[^\"'\\s<>]+)[\"']", Pattern.CASE_INSENSITIVE);
    /** Plyr players that wrap YouTube/Vimeo: data-plyr-provider="youtube" data-plyr-embed-id="ID" (either order). */
    private static final Pattern PLYR_EMBED = Pattern.compile("<[^>]*?data-plyr-provider\\s*=\\s*[\"'](youtube|vimeo)[\"']"
            + "[^>]*>|<[^>]*?data-plyr-embed-id[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLYR_ATTRS = Pattern.compile(
            "data-plyr-(provider|embed-id)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    /** Quality lists of player configs: sources: [{file: "…", label: "720p"}, …] (Video.js, JW Player, Plyr). */
    private static final Pattern SOURCE_LIST = Pattern.compile(
            "[\"']?(?:sources|qualities|renditions|levels)[\"']?\\s*:\\s*\\[([^\\[\\]]*)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE_ENTRY = Pattern.compile("\\{([^{}]*)\\}");
    private static final Pattern ENTRY_URL =
            Pattern.compile("[\"']?(?:file|src|url)[\"']?\\s*:\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTRY_QUALITY = Pattern.compile(
            "[\"']?(?:label|quality|res|height|size)[\"']?\\s*:\\s*[\"']?(\\d{3,4})", Pattern.CASE_INSENSITIVE);
    /** Quality in a <source> attribute (Plyr size="720", res="1080", label="480p"). */
    private static final Pattern ATTR_QUALITY = Pattern.compile("(\\d{3,4})");
    /** Same video in several qualities: ".../clip_720p.mp4" vs ".../clip_1080p.mp4". */
    private static final Pattern URL_QUALITY =
            Pattern.compile("(?<=[/_.=-])(2160|1440|1080|720|540|480|360|240|144)p?(?=[/_.&-]|$)", Pattern.CASE_INSENSITIVE);
    /** Files a page lists that are never video (JSON-LD contentUrl also names images, og:video can be a thumbnail). */
    private static final Pattern NOT_VIDEO = Pattern.compile(
            "\\.(?:jpe?g|png|gif|webp|avif|svg|ico|bmp|mp3|m4a|aac|ogg|wav|flac|css|js|json|pdf|vtt|srt)(?:[?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    /** True if the text is HTML source rather than plain text with links. */
    static boolean looksLikeHtml(String text) {
        return HTML_SOURCE.matcher(text).find();
    }

    static PageVideos pageVideos(String html) {
        // URLs inside scripts come JSON-escaped: "https:\/\/cdn\u002Fv.mp4"
        String text = html.replace("\\/", "/").replaceAll("(?i)\\\\u002f", "/").replaceAll("(?i)\\\\u0026", "&");
        String page = null, base = null;
        List<String[]> found = new ArrayList<>(); // {kind, raw value}: "m" = media, "p" = player
        List<List<String[]>> qualityLists = new ArrayList<>(); // {raw url, quality}: only the best of each is kept
        List<String[]> videoSources = null; // <source> tags with a quality attribute in the current <video>
        Matcher t = HTML_TAG.matcher(text);
        while (t.find()) {
            String tag = t.group(1).toLowerCase(Locale.ROOT);
            Map<String, String> a = new HashMap<>();
            Matcher am = HTML_ATTR.matcher(t.group(2));
            while (am.find()) {
                String v = am.group(2) != null ? am.group(2) : am.group(3) != null ? am.group(3) : am.group(4);
                a.putIfAbsent(am.group(1).toLowerCase(Locale.ROOT), v);
            }
            String prop = a.getOrDefault("property", a.getOrDefault("name", "")).toLowerCase(Locale.ROOT);
            String type = a.getOrDefault("type", "").toLowerCase(Locale.ROOT);
            switch (tag) {
                case "base" -> base = a.get("href");
                case "link" -> {
                    if (a.getOrDefault("rel", "").equalsIgnoreCase("canonical")) page = a.get("href");
                }
                case "meta" -> {
                    String content = a.get("content");
                    if (content == null) continue;
                    if (prop.equals("og:url") && page == null) page = content;
                    else if (prop.matches("og:video(?::url|:secure_url)?|twitter:player:stream")) {
                        found.add(new String[] {"m", content});
                    } else if (prop.equals("twitter:player")) found.add(new String[] {"p", content});
                }
                case "video", "source" -> {
                    if (tag.equals("video")) qualityLists.add(videoSources = new ArrayList<>());
                    if (type.startsWith("audio/") || type.startsWith("image/")) continue;
                    String src = a.getOrDefault("src", a.get("data-src"));
                    if (src == null) continue;
                    String q = Stream.of("size", "res", "label", "data-quality", "data-res", "height")
                            .map(a::get).filter(v -> v != null && ATTR_QUALITY.matcher(v).find()).findFirst().orElse(null);
                    if (tag.equals("source") && q != null && videoSources != null) {
                        Matcher qm = ATTR_QUALITY.matcher(q);
                        qm.find();
                        videoSources.add(new String[] {src, qm.group(1)});
                    } else {
                        found.add(new String[] {"m", src});
                    }
                }
                default -> { // iframe, embed
                    String src = a.getOrDefault("src", a.get("data-src"));
                    if (src != null) found.add(new String[] {"p", src});
                }
            }
        }
        Matcher d = DATA_VIDEO_ATTR.matcher(text);
        while (d.find()) found.add(new String[] {"m", d.group(1) != null ? d.group(1) : d.group(2)});
        Matcher pl = PLYR_EMBED.matcher(text);
        while (pl.find()) {
            Map<String, String> a = new HashMap<>();
            Matcher pa = PLYR_ATTRS.matcher(pl.group());
            while (pa.find()) a.put(pa.group(1).toLowerCase(Locale.ROOT), pa.group(2));
            String id = a.get("embed-id"), provider = a.getOrDefault("provider", "").toLowerCase(Locale.ROOT);
            if (id == null) continue;
            if (id.matches("(?i)(?:https?:)?//.+")) found.add(new String[] {"p", id});
            else if (provider.equals("youtube")) found.add(new String[] {"p", "https://www.youtube.com/watch?v=" + id});
            else if (provider.equals("vimeo")) found.add(new String[] {"p", "https://vimeo.com/" + id});
        }
        // configs inside attributes (Video.js data-setup) come with &quot; for quotes
        String json = text.replace("&quot;", "\"").replace("&#34;", "\"");
        Matcher j = JSON_VIDEO.matcher(json);
        while (j.find()) found.add(new String[] {j.group(1).equals("contentUrl") ? "m" : "p", j.group(2)});
        Matcher sl = SOURCE_LIST.matcher(json);
        while (sl.find()) {
            List<String[]> list = new ArrayList<>();
            Matcher e = SOURCE_ENTRY.matcher(sl.group(1));
            while (e.find()) {
                Matcher u = ENTRY_URL.matcher(e.group(1));
                if (!u.find()) continue;
                Matcher q = ENTRY_QUALITY.matcher(e.group(1));
                list.add(new String[] {u.group(1), q.find() ? q.group(1) : "0"});
            }
            qualityLists.add(list);
        }
        Matcher pc = PLAYER_CONFIG_URL.matcher(json);
        while (pc.find()) found.add(new String[] {"m", pc.group(1)});
        Matcher mu = MEDIA_URL.matcher(text);
        while (mu.find()) found.add(new String[] {"m", mu.group()});
        Matcher rm = RELATIVE_MEDIA.matcher(text);
        while (rm.find()) found.add(new String[] {"m", rm.group(1)});

        String pageUrl = resolveUrl(null, page);
        String baseUrl = base != null ? resolveUrl(pageUrl, base) : pageUrl;
        // a quality list names one video: keep its best entry (a manifest has every quality), drop the others
        Set<String> best = new LinkedHashSet<>(), worse = new HashSet<>();
        for (List<String[]> list : qualityLists) {
            String top = null;
            int topRank = -1;
            for (String[] s : list) {
                String url = resolveUrl(baseUrl, s[0]);
                if (url == null || NOT_VIDEO.matcher(url).find()) continue;
                int rank = url.matches("(?i).*\\.(?:m3u8|mpd)(?:\\?.*)?") ? 100000 : Integer.parseInt(s[1]);
                if (top != null) worse.add(rank > topRank ? top : url);
                if (rank > topRank) {
                    top = url;
                    topRank = rank;
                }
            }
            if (top != null) best.add(top);
        }
        worse.removeAll(best);
        Set<String> media = new LinkedHashSet<>(best), players = new LinkedHashSet<>();
        for (String[] f : found) {
            String url = resolveUrl(baseUrl, f[1]);
            if (url == null || url.equals(pageUrl) || worse.contains(url) || NOT_VIDEO.matcher(url).find()) continue;
            (f[0].equals("m") ? media : players).add(url);
        }
        players.removeAll(media);
        return new PageVideos(bestQualities(media), new ArrayList<>(players), pageUrl);
    }

    /** Of URLs that differ only by a quality token (clip_720p.mp4, clip_1080p.mp4), keeps the highest one. */
    static List<String> bestQualities(Collection<String> urls) {
        Map<String, String> byVideo = new LinkedHashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        for (String url : urls) {
            Matcher q = URL_QUALITY.matcher(url);
            int r = 0;
            while (q.find()) r = Integer.parseInt(q.group(1));
            String video = URL_QUALITY.matcher(url).replaceAll("Q");
            if (!byVideo.containsKey(video) || r > rank.get(video)) {
                byVideo.put(video, url);
                rank.put(video, r);
            }
        }
        return new ArrayList<>(byVideo.values());
    }

    /** Absolute http(s) URL for an attribute value (entities decoded), or null (blob:, data:, unresolvable). */
    static String resolveUrl(String base, String value) {
        if (value == null) return null;
        String v = value.trim().replace("&amp;", "&").replace("&#38;", "&").replace("&#x2F;", "/")
                .replace("&#47;", "/").replace("&quot;", "").replace(" ", "%20");
        if (v.startsWith("//")) v = "https:" + v;
        if (v.matches("(?i)https?://.+")) return v;
        if (v.isEmpty() || v.matches("(?i)[a-z][a-z0-9+.-]*:.*") || base == null) return null;
        try {
            String r = java.net.URI.create(base).resolve(v).toString();
            return r.matches("(?i)https?://.+") ? r : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Lower-case host without "www.", or "" if the text isn't a URL. */
    static String host(String url) {
        Matcher m = URL_HOST.matcher(url);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "") : "";
    }

    /** Registrable domain: "nicovideo.jp" for "sp.nicovideo.jp", "bbc.co.uk" for "www.bbc.co.uk". */
    static String baseDomain(String host) {
        String[] l = host.split("\\.");
        if (l.length < 3) return host;
        boolean secondLevel = l[l.length - 1].length() == 2
                && Set.of("co", "com", "net", "org", "ac", "gov", "edu", "ne", "or", "go").contains(l[l.length - 2]);
        int keep = secondLevel ? 3 : 2;
        return String.join(".", Arrays.copyOfRange(l, l.length - keep, l.length));
    }

    /** Library answers per URL; empty = not supported. Only complete answers are cached. */
    private final Map<String, Optional<Link>> probeCache = new ConcurrentHashMap<>();
    /** Unsupported URLs only yt-dlp's Generic extractor would try: EMBED links while {@link #embeds} is on. */
    private final Set<String> genericUrls = ConcurrentHashMap.newKeySet();

    /** Catch-all links for the URLs: first whatever yt-dlp takes, then creator-platform posts gallery-dl takes. */
    List<Link> probeLibraries(List<String> urls, boolean withEmbeds) {
        List<String> todo = urls.stream().filter(u -> !probeCache.containsKey(u)).toList();
        if (!todo.isEmpty()) {
            if (probeCache.size() > 1000) {
                probeCache.clear();
                genericUrls.clear();
            }
            List<String> ytdl = runProbe("yt-dlp", YT_DLP_PROBE, List.of(), todo);
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
                probeCache.put(url, Optional.of(new Link(Site.OTHER, "ytdl:" + f[0] + ":" + id, url)));
            }
            List<String> gdl = rest.isEmpty() ? List.of()
                    : runProbe("gallery-dl", GALLERY_DL_PROBE, List.of(String.join(",", CREATOR_PLATFORMS)), rest);
            for (int i = 0; i < rest.size() && gdl != null && ytdl != null; i++) {
                String url = rest.get(i);
                String r = gdl.get(i);
                probeCache.put(url, r.equals("-") ? Optional.empty()
                        : Optional.of(new Link(Site.OTHER_GALLERY, "gdl:" + r + ":" + url, url)));
            }
            if (gdl != null && ytdl == null) { // yt-dlp couldn't answer: keep gallery-dl's hits, retry the rest later
                for (int i = 0; i < rest.size(); i++) {
                    if (!gdl.get(i).equals("-")) {
                        probeCache.put(rest.get(i), Optional.of(new Link(Site.OTHER_GALLERY,
                                "gdl:" + gdl.get(i) + ":" + rest.get(i), rest.get(i))));
                    }
                }
            }
        }
        List<Link> links = new ArrayList<>();
        for (String u : urls) {
            Optional<Link> l = probeCache.get(u);
            if (l != null && l.isPresent()) links.add(l.get());
            else if (l != null && withEmbeds && genericUrls.contains(u)) {
                links.add(new Link(Site.EMBED, "embed:" + host(u) + ":" + Integer.toHexString(u.hashCode()), u));
            }
        }
        return links;
    }

    /** Runs a probe script with the library's own Python; one output line per URL, or null if it failed. */
    private static List<String> runProbe(String tool, String script, List<String> args, List<String> urls) {
        String python = libraryPython(tool);
        if (python == null) return null;
        List<String> cmd = new ArrayList<>(List.of(python, "-I", "-c", script));
        cmd.addAll(args);
        cmd.addAll(urls);
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            CompletableFuture.delayedExecutor(20, TimeUnit.SECONDS).execute(p::destroyForcibly);
            List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.add(line.strip());
            }
            if (p.waitFor() == 0 && out.size() == urls.size()) return out;
            log("Probe " + tool + " failed (exit " + p.exitValue() + ", " + out.size() + " answers)");
        } catch (Exception e) {
            log("Probe " + tool + " failed: " + e);
        }
        return null;
    }

    /** The interpreter in the Homebrew formula's shebang (its private venv), or null. */
    private static String libraryPython(String tool) {
        String bin = findBin(tool);
        if (bin == null) return null;
        try (BufferedReader r = Files.newBufferedReader(Paths.get(bin).toRealPath(), StandardCharsets.UTF_8)) {
            String first = r.readLine();
            if (first == null || !first.startsWith("#!")) return null;
            String python = first.substring(2).trim();
            return Files.isExecutable(Paths.get(python)) ? python : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String shortKey(String key) {
        if (key.startsWith("ytdl:") || key.startsWith("embed:")) return key.split(":", 3)[2];
        if (key.startsWith("gdl:")) { // last path segment of the post URL
            return key.split(":", 3)[2].replaceFirst("[?#].*$", "").replaceFirst("/+$", "").replaceFirst("^.*/", "");
        }
        return key.substring(key.indexOf(':') + 1);
    }

    // ---------------------------------------------------------------- download

    private void download(Job job) {
        Link link = job.link;
        Site site = link.site();
        String name = link.label() + " " + shortKey(link.key());
        Path outDir = outDir(site);
        job.cancelRequested = false;
        job.title = "";
        job.speed = "";
        job.eta = "";
        try {
            boolean gallery = site.engine == Engine.GALLERY_DL;
            String tool = gallery ? "gallery-dl" : "yt-dlp";
            String bin = findBin(tool);
            if (bin == null && ensureTools()) bin = findBin(tool);
            if (bin == null) {
                fail(job, name, "Falta " + tool + " y no se pudo instalar con Homebrew (mira el registro)", true);
                return;
            }
            Files.createDirectories(outDir);
            String[] cookieDomains = site.isCatchAll() ? new String[]{baseDomain(host(link.url()))} : site.domains;
            Path cookies = site.isCatchAll() ? findCookiesForDomain(cookieDomains[0]) : findCookies(site);
            activeSite = site;
            // gallery-dl prints no percentage while it reads the post: until then the site-colored orb shows the arrow
            activePercent = gallery ? -1 : 0;
            job.status = Job.Status.DOWNLOADING;
            job.percent = gallery ? -1 : 0;
            updateTrayIcon();
            setStatus("Descargando " + name + "…");
            log("Downloading " + link.url() + (cookies != null ? " with cookies " + cookies : " without cookies"));

            List<String> cmd = gallery ? new ArrayList<>(List.of(
                    bin,
                    "--no-colors",
                    // terminal mode prints progress (pipe mode prints none); full paths; progress from the start
                    "-o", "output.mode=terminal", "-o", "output.shorten=false", "-o", "downloader.progress=0",
                    "-D", outDir.toString(),
                    "-f", GALLERY_DL_FILENAME,
                    "--filter", GALLERY_DL_FILTER, // video posts only; images are skipped
                    "--ugoira", "mp4"))
                    : new ArrayList<>(List.of(
                    bin,
                    // --print implies quiet mode, which hides progress: force it back on
                    "--newline", "--no-quiet", "--progress", "--no-overwrites", "--restrict-filenames",
                    "--progress-template",
                    "download:" + PROGRESS_PREFIX + "%(progress.downloaded_bytes)s %(progress.total_bytes,progress.total_bytes_estimate)s"
                            + " %(progress.speed)s %(progress.eta)s",
                    "--no-playlist",
                    "--match-filter", "!is_live", // never start recording a live stream
                    "-f", "bv*+ba/b",
                    // H.264 + AAC first: M1 Macs cannot play AV1/VP9 in QuickTime
                    "-S", "vcodec:h264,res,acodec:aac",
                    "--merge-output-format", "mp4",
                    // sites that only offer VP8/VP9 webm (e.g. 9GAG) get converted; files already in mp4 are left alone
                    "--recode-video", "mp4",
                    "-o", outDir + "/" + site.template,
                    "--print", "after_move:filepath",
                    "--print", "before_dl:" + TITLE_PREFIX + "%(title)s",
                    "--no-simulate"));
            String ffmpeg = findBin("ffmpeg");
            if (ffmpeg != null && !gallery) cmd.addAll(List.of("--ffmpeg-location", ffmpeg));
            // a catch-all extractor may hand back a list (album, episode with parts): cap it
            if (site == Site.OTHER || site == Site.EMBED) {
                cmd.addAll(List.of("--playlist-items", "1:" + CATCH_ALL_MAX_ITEMS));
            }
            // what the Generic extractor found, shown in "Detalles": extractor, format, protocol, size
            if (site == Site.EMBED) {
                cmd.addAll(List.of("--print", "before_dl:" + FOUND_PREFIX + "%(extractor_key)s · %(format_id)s · "
                        + "%(protocol)s · %(resolution)s · %(filesize,filesize_approx)s · %(url).120s"));
            }
            if (cookies != null) {
                // yt-dlp rewrites the cookie jar on exit, so it gets a copy holding only this site's cookies:
                // the original stays intact and a full-browser export never leaks other sites' sessions
                Path tmpCookies = STATE_DIR.resolve("cookies-session.txt");
                writeDomainCookies(cookies, tmpCookies, cookieDomains);
                cmd.addAll(List.of("--cookies", tmpCookies.toString()));
            }
            cmd.add(link.url());

            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("PATH", String.join(":", BIN_DIRS));
            Process p = pb.start();
            currentProcess = p;

            List<String> files = new ArrayList<>();
            StringBuilder tail = new StringBuilder();
            Progress progress = new Progress();
            try (Reader r = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
                String line;
                // gallery-dl redraws its progress with "\r", so the output is split on "\r" as well as "\n"
                while ((line = readSegment(r)) != null) {
                    if (line.isBlank()) continue;
                    if (gallery) {
                        Matcher gp = GALLERY_DL_PERCENT.matcher(line);
                        if (gp.find()) {
                            job.speed = gp.group(2) != null ? gp.group(2).replace("kB/s", " KB/s").replace("MB/s", " MB/s") : "";
                            refreshTooltip();
                            int pct = Math.min(99, Integer.parseInt(gp.group(1))); // 100 once the file is saved
                            if (pct != job.percent) {
                                job.percent = pct;
                                activePercent = pct;
                                updateTrayIcon();
                                if (pct % 10 == 0) setStatus("Descargando " + name + "… " + pct + "%");
                            }
                            continue; // progress lines are not logged
                        }
                        String trimmed = line.strip();
                        if (line.startsWith("  /")) {
                            job.destinations.add(trimmed); // file about to be written
                            job.title = Paths.get(trimmed).getFileName().toString();
                        }
                        if (trimmed.startsWith("✔ /") || trimmed.startsWith("* /")) {
                            String f = trimmed.substring(2);
                            if (Files.exists(Paths.get(f))) files.add(f);
                        }
                    }
                    if (line.startsWith(TITLE_PREFIX)) {
                        job.title = line.substring(TITLE_PREFIX.length()).strip();
                        refreshTooltip();
                        continue;
                    }
                    if (line.startsWith(FOUND_PREFIX)) {
                        for (String l : describeFound(line.substring(FOUND_PREFIX.length())).split("\n")) {
                            job.addOutput(l);
                        }
                        log("  " + line);
                        continue;
                    }
                    if (line.startsWith(PROGRESS_PREFIX)) {
                        String[] pf = line.substring(PROGRESS_PREFIX.length()).trim().split("\\s+");
                        job.speed = pf.length > 2 && !pf[2].equals("NA") ? formatSpeed(parseOr(pf[2], 0)) : "";
                        job.eta = pf.length > 3 && !pf[3].equals("NA") ? formatEta((long) parseOr(pf[3], 0)) : "";
                        refreshTooltip();
                        int pct = progress.update(line.substring(PROGRESS_PREFIX.length()));
                        if (pct >= 0 && pct != job.percent) {
                            job.percent = pct;
                            activePercent = pct;
                            updateTrayIcon();
                            if (pct % 10 == 0) setStatus("Descargando " + name + "… " + pct + "%");
                        }
                        continue; // progress lines are not logged
                    }
                    progress.onLine(line);
                    if (line.startsWith("[download] Destination: ")) {
                        job.destinations.add(line.substring("[download] Destination: ".length()).trim());
                    }
                    log("  " + line);
                    job.addOutput(line);
                    if (line.startsWith("/") && Files.exists(Paths.get(line))) files.add(line);
                    // gallery-dl marks files it already has with "# "
                    if (gallery && line.startsWith("# /") && Files.exists(Paths.get(line.substring(2)))) {
                        files.add(line.substring(2));
                    }
                    tail.setLength(0);
                    tail.append(line);
                }
            }
            int exit = p.waitFor();
            if (job.cancelRequested) {
                log("Cleaning partials of " + shortKey(link.key()) + " in " + outDir);
                killAndWait(p);
                deletePartials(job);
                markCancelled(job);
                setStatus("Cancelado: " + name);
                return;
            }

            if (exit == 0 && !files.isEmpty() && gallery) {
                // gallery-dl keeps the site's own format (webm, gif…): make everything QuickTime-friendly
                List<String> converted = new ArrayList<>();
                for (String f : files) converted.add(toPlayableMp4(f, ffmpeg, job));
                files = converted;
            }
            if (exit == 0 && !files.isEmpty()) {
                done.add(link.key());
                Files.writeString(HISTORY, link.key() + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                job.files = List.copyOf(files);
                job.bytes = fileSize(files.get(0));
                job.percent = 100;
                job.message = (files.size() > 1 ? files.size() + " videos en " : "Guardado en ") + displayPath(outDir)
                        + (files.size() == 1 ? "/" + Paths.get(files.get(0)).getFileName() : "");
                job.status = Job.Status.DONE;
                setStatus("Listo: " + name); // success is silent: only failures notify
            } else {
                String msg = tail.toString();
                boolean permanent = true;
                boolean silent = false;
                String all = job.outputText();
                if (gallery && exit == 0) {
                    msg = "Ese post no tiene video ni animación (solo imágenes)";
                } else if (site == Site.PIXIV && (all.contains("refresh-token") || all.contains("401")
                        || all.contains("Unauthorized") || all.contains("OAuth"))) {
                    msg = "Pixiv pide conectar tu cuenta (solo una vez): sigue los pasos en la Terminal y reintenta";
                    connectPixiv();
                } else if (gallery && (all.contains("403") || all.contains("401") || all.contains("login")
                        || all.contains("Forbidden") || all.contains("cookies"))) {
                    msg = link.label() + " pide sesión: exporta cookies nuevas de " + cookieSource(link) + " a Descargas";
                } else if (all.contains("anti-bot challenge")) {
                    msg = "El sitio bloquea las descargas automáticas (Cloudflare)"; // retrying never helps
                } else if (site == Site.EMBED && (all.contains("HTTP Error 403") || all.contains("Forbidden"))) {
                    // the server refuses anything but its own player: no workaround, the user can still open the page
                    msg = "El sitio no deja bajar este video fuera de su página (403): usa «Abrir página»";
                } else if (site == Site.EMBED && (msg.contains("Unsupported URL") || all.contains("404"))) {
                    msg = "No se encontró ningún video en la página";
                    silent = true; // the page was most likely copied for its text: never notify
                } else if (all.contains("404") || all.contains("NotFound") || all.contains("not found")) {
                    msg = "No existe, se borró o es privado";
                } else if (exit == 0 || msg.contains("is_live")) {
                    msg = "Es una transmisión en vivo: no se graba";
                } else if (msg.contains("There is no video") || msg.contains("No video could be found")) {
                    msg = "Ese post no tiene video";
                } else if ((msg.contains("login") || msg.contains("cookies") || msg.contains("Sign in"))
                        && !msg.contains("rate-limit")) {
                    msg = link.label() + " pide sesión: exporta cookies nuevas de " + cookieSource(link) + " a Descargas";
                } else {
                    permanent = false; // network, rate limits, server errors: worth retrying
                    if (msg.startsWith("ERROR: ")) msg = msg.substring("ERROR: ".length());
                }
                fail(job, name, msg, permanent, silent);
            }
        } catch (Exception e) {
            if (!job.cancelRequested) {
                log("ERROR " + e);
                job.addOutput("ERROR " + e);
            }
            if (job.cancelRequested) {
                // killing yt-dlp usually ends here: reading its output fails once the process is gone
                log("Cleaning partials (after read error) of " + shortKey(link.key()) + " in " + outDir);
                killAndWait(currentProcess);
                deletePartials(job);
                markCancelled(job);
                setStatus("Cancelado: " + name);
            } else {
                fail(job, name, String.valueOf(e.getMessage()));
            }
        } finally {
            if (job.status != Job.Status.QUEUED) queued.remove(link.key());
            activePercent = -1;
            if (queued.isEmpty()) activeSite = null;
            updateTrayIcon();
            saveJobs();
        }
    }

    private void fail(Job job, String name, String msg) {
        fail(job, name, msg, false);
    }

    /**
     * Transient failures are retried silently (configurable count, growing delay); only the final failure
     * notifies. Permanent ones (live stream, no video, login needed, yt-dlp missing) fail right away.
     */
    private void fail(Job job, String name, String msg, boolean permanent) {
        fail(job, name, msg, permanent, false);
    }

    private void fail(Job job, String name, String msg, boolean permanent, boolean silent) {
        if (!permanent && job.attempt < maxRetries) {
            job.attempt++;
            long wait = (long) retryDelaySec * job.attempt;
            job.status = Job.Status.QUEUED; // keeps its slot in the queue (and in the tray count) while waiting
            job.percent = -1;
            job.message = "Falló (" + msg + "). Reintento " + job.attempt + "/" + maxRetries + " en "
                    + (wait < 60 ? wait + " s" : wait / 60 + " min");
            job.addOutput("--- reintento automático " + job.attempt + "/" + maxRetries + " en " + wait + " s ---");
            log("Auto-retry " + job.attempt + "/" + maxRetries + " in " + wait + "s: " + job.link.url());
            setStatus("Reintentará " + name + " en " + wait + " s");
            job.notBefore = System.currentTimeMillis() + wait * 1000;
            synchronized (pending) {
                pending.addLast(job);
                pending.notifyAll();
            }
            return;
        }
        job.status = Job.Status.FAILED;
        job.message = job.attempt > 0 ? msg + " (tras " + job.attempt + " reintentos)" : msg;
        setStatus("Falló " + name + " — mira Fallidas en la ventana");
        if (silent) return;
        notify("No se pudo descargar " + name, job.message + " — abre la ventana (Fallidas) para ver qué pasó o reintentar");
    }

    private static final String PROGRESS_PREFIX = "PROG ";
    private static final String TITLE_PREFIX = "TITLE ";
    private static final String FOUND_PREFIX = "FOUND ";

    /** "Generic · hls-1080 · m3u8_native · 1920x1080 · 734003200 · https://…" → readable line for "Detalles". */
    static String describeFound(String raw) {
        String[] f = raw.split(" · ", 6);
        if (f.length < 6) return "Encontrado: " + raw;
        String size = f[4].equals("NA") ? "tamaño desconocido" : formatBytes(parseOr(f[4], 0));
        String how = f[2].startsWith("m3u8") ? "HLS (fragmentos .ts/.m4s)"
                : f[2].startsWith("http_dash") || f[2].contains("dash") ? "DASH (fragmentos)"
                : f[2].startsWith("http") ? "archivo directo por HTTP" : f[2];
        return "Encontrado por " + f[0] + ": formato " + f[1] + ", " + how + ", " + (f[3].equals("NA") ? "resolución desconocida" : f[3]) + ", " + size
                + "\n  origen: " + f[5];
    }
    /** gallery-dl terminal-mode progress: " 45%  1.20MB 300.00kB/s". */
    private static final Pattern GALLERY_DL_PERCENT =
            Pattern.compile("^\\s*(\\d{1,3})%\\s+\\S+\\s+([\\d.]+[kMG]?B/s)?");

    private static double parseOr(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Next piece of output ending in "\n" or "\r"; null at end of stream. */
    private static String readSegment(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = r.read()) != -1) {
            if (c == '\n' || c == '\r') return sb.toString();
            sb.append((char) c);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Turns yt-dlp's per-file byte progress into one overall percentage. A "video+audio" format downloads
     * two files back to back; the video gets 90% of the bar since it is by far the larger one.
     */
    static final class Progress {
        private int parts = 1;
        private int part = -1;

        void onLine(String line) {
            Matcher m = Pattern.compile("Downloading \\d+ format\\(s\\): (\\S+)").matcher(line);
            if (m.find()) parts = m.group(1).contains("+") ? 2 : 1;
            if (line.startsWith("[download] Destination:")) part++;
        }

        int update(String data) {
            String[] f = data.trim().split("\\s+");
            if (f.length < 2) return -1;
            try {
                double done = Double.parseDouble(f[0]);
                double total = Double.parseDouble(f[1]);
                if (total <= 0) return -1;
                double frac = Math.min(1, done / total);
                int idx = Math.max(0, part);
                double overall = parts == 2 ? (idx == 0 ? frac * 0.9 : 0.9 + frac * 0.1) : frac;
                return (int) Math.min(99, Math.round(overall * 100)); // 100 only once the file is saved
            } catch (NumberFormatException e) {
                return -1; // "NA" while the size is unknown
            }
        }
    }

    /**
     * Cookies for a site, from ~/Downloads: the newest *cookie*.txt whose name mentions the site
     * (e.g. www.youtube.com_cookies.txt); otherwise the newest general export (e.g. cookies.txt)
     * that contains at least one cookie for the site.
     */
    static Path findCookies(Site site) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DOWNLOADS, "*.txt")) {
            for (Path p : ds) {
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).contains("cookie")) files.add(p);
            }
        } catch (IOException e) {
            return null;
        }
        files.sort(Comparator.comparingLong(InstaTray::modifiedTime).reversed());

        for (Path p : files) {
            if (site.matchesCookieFile(p.getFileName().toString().toLowerCase(Locale.ROOT))) return p;
        }
        for (Path p : files) {
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean otherSite = Arrays.stream(Site.values()).anyMatch(s -> s.matchesCookieFile(name));
            if (!otherSite && hasSiteCookies(p, site)) return p;
        }
        return null;
    }

    /**
     * Cookies for a catch-all site: the newest *cookie*.txt in ~/Downloads whose name mentions the domain
     * (nicovideo for nicovideo.jp) and holds cookies for it; otherwise a general export (not named after one
     * of the known sites) that does.
     */
    static Path findCookiesForDomain(String domain) {
        String key = domain.split("\\.")[0];
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DOWNLOADS, "*.txt")) {
            for (Path p : ds) {
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).contains("cookie")) files.add(p);
            }
        } catch (IOException e) {
            return null;
        }
        files.sort(Comparator.comparingLong(InstaTray::modifiedTime).reversed());
        for (Path p : files) {
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains(key) && hasDomainCookies(p, domain)) return p;
        }
        for (Path p : files) {
            String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean namedSite = NAMED_SITES.stream().anyMatch(s -> s.matchesCookieFile(name));
            if (!namedSite && hasDomainCookies(p, domain)) return p;
        }
        return null;
    }

    /** Where the user should export cookies from, as shown in messages. */
    private static String cookieSource(Link link) {
        return link.site().isCatchAll() ? baseDomain(host(link.url())) : link.site().label;
    }

    private static long modifiedTime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static boolean hasSiteCookies(Path file, Site site) {
        return hasDomainCookies(file, site.domains);
    }

    private static boolean hasDomainCookies(Path file, String... domains) {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.anyMatch(l -> isCookieLine(l) && cookieLineIn(l, domains));
        } catch (Exception e) {
            return false;
        }
    }

    /** True if a Netscape cookie line belongs to one of the domains (or any subdomain). */
    static boolean cookieLineIn(String line, String... domains) {
        String domain = line.split("\t", 2)[0].toLowerCase(Locale.ROOT);
        if (domain.startsWith("#httponly_")) domain = domain.substring("#httponly_".length());
        if (domain.startsWith(".")) domain = domain.substring(1);
        for (String d : domains) {
            if (domain.equals(d) || domain.endsWith("." + d)) return true;
        }
        return false;
    }

    private static boolean isCookieLine(String line) {
        return !line.isBlank() && (!line.startsWith("#") || line.startsWith("#HttpOnly_")) && line.indexOf('\t') > 0;
    }

    /** Writes a Netscape cookie file with only the site's cookies. */
    static void writeSiteCookies(Path src, Path dst, Site site) throws IOException {
        writeDomainCookies(src, dst, site.domains);
    }

    /** Writes a Netscape cookie file with only the cookies of these domains. */
    static void writeDomainCookies(Path src, Path dst, String... domains) throws IOException {
        List<String> out = new ArrayList<>();
        out.add("# Netscape HTTP Cookie File");
        for (String line : Files.readAllLines(src, StandardCharsets.UTF_8)) {
            if (isCookieLine(line) && cookieLineIn(line, domains)) out.add(line);
        }
        Files.write(dst, out, StandardCharsets.UTF_8);
    }

    private static String findBrew() {
        for (String b : new String[]{"/opt/homebrew/bin/brew", "/usr/local/bin/brew"}) {
            if (Files.isExecutable(Paths.get(b))) return b;
        }
        return null;
    }

    /** Homebrew on /opt/homebrew refuses installs when the JVM is running under Rosetta. */
    private static List<String> brewCommand(String brew, String... args) {
        List<String> cmd = new ArrayList<>();
        if (brew.startsWith("/opt/homebrew/") && "x86_64".equals(System.getProperty("os.arch"))) {
            cmd.add("arch");
            cmd.add("-arm64");
        }
        cmd.add(brew);
        cmd.addAll(List.of(args));
        return cmd;
    }

    /**
     * Installs missing download tools with Homebrew. The launcher already does this before Java starts;
     * this covers tools removed while the app is running. Returns true when yt-dlp is available.
     */
    private synchronized boolean ensureTools() {
        List<String> missing = new ArrayList<>();
        for (String t : new String[]{"yt-dlp", "gallery-dl", "ffmpeg", "deno"}) {
            if (findBin(t) == null) missing.add(t);
        }
        if (missing.isEmpty()) return true;
        String brew = findBrew();
        if (brew == null) {
            log("Missing " + missing + " and Homebrew is not installed");
            notify("InstaTray", "Falta " + String.join(", ", missing) + ". Abre InstaTray de nuevo para instalarlo");
            return false;
        }
        setStatus("Instalando " + String.join(", ", missing) + "…");
        List<String> cmd = new ArrayList<>(brewCommand(brew, "install"));
        cmd.addAll(missing);
        boolean ok = runBrew(cmd, "install " + missing);
        if (!ok) notify("InstaTray", "No se pudo instalar " + String.join(", ", missing) + " (mira el registro)");
        setStatus(ok ? "Vigilando portapapeles" : "Faltan herramientas — mira el registro");
        return findBin("yt-dlp") != null;
    }

    /** Sites change often and old yt-dlp versions stop working: upgrade it silently once a week. */
    private void upgradeYtDlpIfStale() {
        long last = Long.parseLong(loadSettings().getProperty("ytdlpUpgradedAt", "0"));
        if (System.currentTimeMillis() - last < UPGRADE_EVERY_MS) return;
        String brew = findBrew();
        if (brew == null || findBin("yt-dlp") == null) return;
        if (findBin("gallery-dl") == null) ensureTools();
        // both track sites that change often
        if (runBrew(brewCommand(brew, "upgrade", "yt-dlp", "gallery-dl"), "upgrade yt-dlp gallery-dl")) {
            saveSetting("ytdlpUpgradedAt", String.valueOf(System.currentTimeMillis()));
        }
    }

    /** Runs a Homebrew command with the queue on hold; output goes to the log. */
    private boolean runBrew(List<String> cmd, String what) {
        toolsBusy = true;
        try {
            // let a download that is already running finish first
            while (current != null) Thread.sleep(1000);
            log("Homebrew: " + what);
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("PATH", String.join(":", BIN_DIRS));
            // installs skip the slow index refresh; upgrades need it, or brew never sees a newer yt-dlp
            if (cmd.contains("install")) pb.environment().put("HOMEBREW_NO_AUTO_UPDATE", "1");
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) log("  brew: " + line);
            }
            boolean finished = p.waitFor(20, TimeUnit.MINUTES);
            int exit = finished ? p.exitValue() : -1;
            log("Homebrew " + what + " -> exit " + exit);
            return exit == 0;
        } catch (Exception e) {
            log("ERROR Homebrew " + what + ": " + e);
            return false;
        } finally {
            toolsBusy = false;
            synchronized (pending) {
                pending.notifyAll();
            }
        }
    }

    private static String findBin(String name) {
        for (String dir : BIN_DIRS) {
            Path p = Paths.get(dir, name);
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private void notify(String title, String text) {
        if (!notifications) { // the tray icon and the window still show progress
            log("Notification muted: " + title + " — " + text);
            return;
        }
        log("Notification: " + title + " — " + text);
        String script = "display notification \"" + esc(text) + "\" with title \"" + esc(title) + "\"";
        try {
            new ProcessBuilder("osascript", "-e", script).start();
        } catch (IOException e) {
            trayIcon.displayMessage(title, text, TrayIcon.MessageType.INFO);
        }
    }

    @Override
    public void revealInFinder(Path file) {
        try {
            new ProcessBuilder("open", "-R", file.toString()).start();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void openUrl(String url) {
        try {
            new ProcessBuilder("open", url).start();
        } catch (IOException ignored) {
        }
    }

    /** Copies text without the clipboard watcher treating it as a new link to download. */
    @Override
    public void copyToClipboardSilently(String text) {
        lastClip = text;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void openPath(Path p) {
        try {
            new ProcessBuilder("open", p.toString()).start();
        } catch (IOException ignored) {
        }
    }

    private static synchronized void log(String line) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        try {
            Files.writeString(LOG, ts + " " + line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
