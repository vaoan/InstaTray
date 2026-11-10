package instatray.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finding supported links in text: single video/post URLs of the named sites ({@link #find}), other URLs worth
 * asking the libraries about ({@link #otherCandidates}, then {@link Probe}), and the videos of a pasted page's HTML
 * ({@link #pageVideos}).
 */
public final class Links {

    private Links() {
    }

    /**
     * How a supported link is recognized: only URLs of a single video/post are matched, never profiles,
     * channels or live pages, so copying a profile can't start hundreds of downloads.
     *
     * @param key the history key (site prefix + id) used to skip repeats
     * @param url the clean URL handed to the tool (tracking parameters dropped)
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
                    m -> "rd:s:" + m.group(1), Links::clean),
            rule(Site.REDDIT, "https?://v\\.redd\\.it/([a-z0-9]+)", m -> "rd:v:" + m.group(1), Links::clean),
            rule(Site.REDDIT, "https?://redd\\.it/([a-z0-9]+)", m -> "rd:" + m.group(1), Links::clean),
            // Facebook watch, reel, /videos/, share links and fb.watch
            rule(Site.FACEBOOK, "https?://" + SUB + "facebook\\.com/watch/?\\?(?:\\S*?&)?v=(\\d+)",
                    m -> "fb:" + m.group(1), m -> "https://www.facebook.com/watch/?v=" + m.group(1)),
            rule(Site.FACEBOOK, "https?://" + SUB + "facebook\\.com/reel/(\\d+)",
                    m -> "fb:" + m.group(1), m -> "https://www.facebook.com/reel/" + m.group(1)),
            rule(Site.FACEBOOK, "https?://" + SUB + "facebook\\.com/[A-Za-z0-9.]+/videos/(?:[^/\\s?#]+/)?(\\d+)",
                    m -> "fb:" + m.group(1), Links::clean),
            rule(Site.FACEBOOK, "https?://" + SUB + "facebook\\.com/share/[vr]/([A-Za-z0-9]+)",
                    m -> "fb:s:" + m.group(1), Links::clean),
            rule(Site.FACEBOOK, "https?://fb\\.watch/([A-Za-z0-9_-]+)", m -> "fb:w:" + m.group(1), Links::clean),
            // Pinterest pin (any country domain) and pin.it short links
            rule(Site.PINTEREST, "https?://" + SUB + "pinterest\\.[a-z]{2,3}(?:\\.[a-z]{2})?/pin/(\\d+)",
                    m -> "pin:" + m.group(1), m -> "https://www.pinterest.com/pin/" + m.group(1) + "/"),
            rule(Site.PINTEREST, "https?://pin\\.it/([A-Za-z0-9]+)", m -> "pin:s:" + m.group(1), Links::clean),
            // Bluesky post
            rule(Site.BLUESKY, "https?://bsky\\.app/profile/([^/\\s]+)/post/([a-z0-9]+)",
                    m -> "bsky:" + m.group(2), m -> "https://bsky.app/profile/" + m.group(1) + "/post/" + m.group(2)),
            // Kick clips (path or ?clip=) and past broadcasts; channel pages are live and ignored
            rule(Site.KICK, "https?://" + SUB + "kick\\.com/([A-Za-z0-9_-]+)(?:/clips/|\\?clip=)(clip_[A-Za-z0-9]+)",
                    m -> "kick:" + m.group(2), m -> "https://kick.com/" + m.group(1) + "/clips/" + m.group(2)),
            rule(Site.KICK, "https?://" + SUB + "kick\\.com/(?:video|[A-Za-z0-9_-]+/videos)/([0-9a-f-]{36})",
                    m -> "kick:" + m.group(1), Links::clean),
            // Vimeo video (optional private hash) and player embeds
            rule(Site.VIMEO, "https?://(?:www\\.|player\\.)?vimeo\\.com/(?:video/)?(\\d{5,})(?:/([0-9a-f]{6,}))?",
                    m -> "vimeo:" + m.group(1),
                    m -> "https://vimeo.com/" + m.group(1) + (m.group(2) != null ? "/" + m.group(2) : "")),
            // Dailymotion video and dai.ly
            rule(Site.DAILYMOTION, "https?://(?:" + SUB + "dailymotion\\.com/video/|dai\\.ly/)([a-z0-9]+)",
                    m -> "dm:" + m.group(1), m -> "https://www.dailymotion.com/video/" + m.group(1)),
            // Rumble video page and embed
            rule(Site.RUMBLE, "https?://" + SUB + "rumble\\.com/(v[a-z0-9]+)-[^\\s?#]*\\.html",
                    m -> "rumble:" + m.group(1), Links::clean),
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
                    m -> "imgur:" + m.group(1), Links::clean),
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
                    m -> "li:" + m.group(1), Links::clean),
            // Snapchat public Spotlight
            rule(Site.SNAPCHAT, "https?://" + SUB + "snapchat\\.com/spotlight/([A-Za-z0-9_-]+)",
                    m -> "snap:" + m.group(1), Links::clean),
            // Bilibili video (BV / av ids) and b23.tv short links
            rule(Site.BILIBILI, "https?://" + SUB + "bilibili\\.com/video/(BV[A-Za-z0-9]+|av\\d+)",
                    m -> "bili:" + m.group(1), m -> "https://www.bilibili.com/video/" + m.group(1) + "/"),
            rule(Site.BILIBILI, "https?://b23\\.tv/([A-Za-z0-9]+)", m -> "bili:s:" + m.group(1), Links::clean),
            // Newgrounds movie
            rule(Site.NEWGROUNDS, "https?://" + SUB + "newgrounds\\.com/portal/view/(\\d+)",
                    m -> "ng:" + m.group(1), m -> "https://www.newgrounds.com/portal/view/" + m.group(1)),
            // Iwara video
            rule(Site.IWARA, "https?://" + SUB + "iwara\\.tv/video/([A-Za-z0-9]+)",
                    m -> "iwara:" + m.group(1), m -> "https://www.iwara.tv/video/" + m.group(1)),
            // Patreon post (only what the account in the cookies can see)
            rule(Site.PATREON, "https?://" + SUB + "patreon\\.com/posts/(?:[A-Za-z0-9-]*-)?(\\d+)",
                    m -> "patreon:" + m.group(1), Links::clean),
            // Weibo post (desktop and mobile)
            rule(Site.WEIBO, "https?://(?:www\\.)?weibo\\.com/\\d+/([A-Za-z0-9]+)",
                    m -> "weibo:" + m.group(1), Links::clean),
            rule(Site.WEIBO, "https?://m\\.weibo\\.cn/(?:detail|status)/(\\d+)",
                    m -> "weibo:" + m.group(1), Links::clean),
            // Pixiv artwork (ugoira animations are converted to mp4)
            rule(Site.PIXIV, "https?://" + SUB + "pixiv\\.net/(?:[a-z]{2}/)?(?:artworks|i)/(\\d+)",
                    m -> "px:" + m.group(1), m -> "https://www.pixiv.net/artworks/" + m.group(1)),
            rule(Site.PIXIV, "https?://" + SUB + "pixiv\\.net/member_illust\\.php\\?\\S*?illust_id=(\\d+)",
                    m -> "px:" + m.group(1), m -> "https://www.pixiv.net/artworks/" + m.group(1)),
            // pixiv FANBOX post (creator.fanbox.cc or fanbox.cc/@creator)
            rule(Site.FANBOX, "https?://(?:[a-z0-9-]+\\.fanbox\\.cc|(?:www\\.)?fanbox\\.cc/@[a-z0-9_-]+)/posts/(\\d+)",
                    m -> "fanbox:" + m.group(1), Links::clean),
            // Fantia post
            rule(Site.FANTIA, "https?://(?:www\\.)?fantia\\.jp/posts/(\\d+)",
                    m -> "fantia:" + m.group(1), m -> "https://fantia.jp/posts/" + m.group(1)),
            // DeviantArt deviation (slug ends with the numeric id)
            rule(Site.DEVIANTART, "https?://(?:www\\.)?deviantart\\.com/[A-Za-z0-9_-]+/art/[A-Za-z0-9_-]*?(\\d{6,})",
                    m -> "da:" + m.group(1), Links::clean),
            // Misskey note
            rule(Site.MISSKEY, "https?://misskey\\.io/notes/([a-z0-9]+)", m -> "mk:" + m.group(1), Links::clean),
            // Mastodon status on the instances gallery-dl knows
            rule(Site.MASTODON, "https?://(mastodon\\.social|pawoo\\.net)/@[A-Za-z0-9_]+/(\\d+)",
                    m -> "masto:" + m.group(1) + ":" + m.group(2), Links::clean));


    /** Most unknown URLs returned per text by {@link #otherCandidates}. */
    public static final int MAX_CANDIDATES = 5;
    private static final Pattern ANY_URL = Pattern.compile("https?://[^\\s<>\"'`]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_HOST = Pattern.compile("^https?://(?:[^@/?#]*@)?([^/?#:]+)", Pattern.CASE_INSENSITIVE);

    /** Every link of a named site in the text, once each (by key), in rule order. */
    public static List<Link> find(String text) {
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
    public static List<String> otherCandidates(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = ANY_URL.matcher(text);
        while (m.find() && out.size() < MAX_CANDIDATES) {
            String url = m.group().replaceFirst("[.,;:!?)\\]}>]+$", "");
            String host = host(url);
            if (host.isEmpty() || out.contains(url)) continue;
            if (RULES.stream().anyMatch(r -> r.pattern().matcher(url).find())) continue;
            if (Site.named().stream().anyMatch(s -> s.ownsHost(host))) continue;
            if (url.substring(m.group().indexOf(host) + host.length()).replaceAll("[/?#]", "").isEmpty()) continue;
            out.add(url);
        }
        return out;
    }

    /** The short id shown to people: "dQw4w9WgXcQ" for "yt:dQw4w9WgXcQ", the post's last path segment for gdl keys. */
    public static String shortKey(String key) {
        if (key.startsWith("ytdl:") || key.startsWith("embed:")) return key.split(":", 3)[2];
        if (key.startsWith("gdl:")) { // last path segment of the post URL
            return key.split(":", 3)[2].replaceFirst("[?#].*$", "").replaceFirst("/+$", "").replaceFirst("^.*/", "");
        }
        return key.substring(key.indexOf(':') + 1);
    }

    /**
     * Video URLs found in a pasted page (its HTML source, or the HTML flavor of a rich copy), best first.
     * {@code media}: what the page itself plays (video/source tags, og:video, JSON-LD contentUrl, .mp4/.m3u8/.mpd
     * URLs); {@code players}: embedded players (iframes, JSON-LD embedUrl); {@code page}: canonical URL or null.
     */
    public record PageVideos(List<String> media, List<String> players, String page) {
        public boolean isEmpty() {
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
    public static boolean looksLikeHtml(String text) {
        return HTML_SOURCE.matcher(text).find();
    }

    public static PageVideos pageVideos(String html) {
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
    public static List<String> bestQualities(Collection<String> urls) {
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
    public static String resolveUrl(String base, String value) {
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
    public static String host(String url) {
        Matcher m = URL_HOST.matcher(url);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "") : "";
    }

    /** Registrable domain: "nicovideo.jp" for "sp.nicovideo.jp", "bbc.co.uk" for "www.bbc.co.uk". */
    public static String baseDomain(String host) {
        String[] l = host.split("\\.");
        if (l.length < 3) return host;
        boolean secondLevel = l[l.length - 1].length() == 2
                && Set.of("co", "com", "net", "org", "ac", "gov", "edu", "ne", "or", "go").contains(l[l.length - 2]);
        int keep = secondLevel ? 3 : 2;
        return String.join(".", Arrays.copyOfRange(l, l.length - keep, l.length));
    }
}
