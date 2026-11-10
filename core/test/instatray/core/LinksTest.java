package instatray.core;

import java.util.List;

public class LinksTest {
    public static void main(String[] args) {
        List<Link> yt = Links.find("mira esto https://youtu.be/dQw4w9WgXcQ?si=abc y nada más");
        Check.eq("youtubeShort", List.of(new Link(Site.YOUTUBE, "yt:dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")), yt);
        Check.eq("instagramReel", List.of(new Link(Site.INSTAGRAM, "ig:C1abc", "https://www.instagram.com/reel/C1abc/")),
                Links.find("https://www.instagram.com/reels/C1abc/?igsh=x"));
        Check.eq("xMirror", "https://x.com/ana/status/123",
                Links.find("https://fxtwitter.com/ana/status/123?s=20").get(0).url());
        Link pixiv = Links.find("https://www.pixiv.net/en/artworks/98765").get(0);
        Check.that("pixivGallery", pixiv.site() == Site.PIXIV && pixiv.site().engine() == Engine.GALLERY_DL
                && pixiv.key().equals("px:98765"), pixiv.toString());
        Check.eq("sameVideoOnce", 1, Links.find("https://youtu.be/dQw4w9WgXcQ https://www.youtube.com/watch?v=dQw4w9WgXcQ").size());
        Check.that("profilesIgnored", Links.find("https://www.youtube.com/@canal https://www.instagram.com/ana/ "
                + "https://www.twitch.tv/streamer").isEmpty(), "");

        Check.eq("otherCandidates", List.of("https://www.nicovideo.jp/watch/sm9"), Links.otherCandidates(
                "https://www.nicovideo.jp/watch/sm9. https://youtu.be/dQw4w9WgXcQ https://www.youtube.com/@x https://example.com/"));
        Check.eq("shortKeyNamed", "dQw4w9WgXcQ", Links.shortKey("yt:dQw4w9WgXcQ"));
        Check.eq("shortKeyYtdl", "sm9", Links.shortKey("ytdl:Niconico:sm9"));
        Check.eq("shortKeyGdl", "123", Links.shortKey("gdl:artstation:https://www.artstation.com/artwork/123/"));
        Check.eq("host", "sp.nicovideo.jp", Links.host("https://www.sp.nicovideo.jp/x"));
        Check.eq("baseDomain", "bbc.co.uk", Links.baseDomain("www.bbc.co.uk"));
        Check.eq("baseDomain2", "nicovideo.jp", Links.baseDomain("sp.nicovideo.jp"));
        Check.eq("catchAllLabel", "Niconico", new Link(Site.OTHER, "ytdl:niconico:sm9", "u").label());
        Check.eq("namedLabel", "YouTube", new Link(Site.YOUTUBE, "yt:a", "u").label());

        String html = "<html><head><link rel=\"canonical\" href=\"https://ejemplo.com/v/1\"></head><body>"
                + "<video><source src=\"/media/clip_720p.mp4\" size=\"720\"><source src=\"/media/clip_1080p.mp4\" size=\"1080\">"
                + "</video><iframe src=\"https://www.youtube.com/embed/dQw4w9WgXcQ\"></iframe></body></html>";
        Check.that("looksLikeHtml", Links.looksLikeHtml(html) && !Links.looksLikeHtml("https://youtu.be/x"), "");
        Links.PageVideos pv = Links.pageVideos(html);
        Check.eq("pageMediaBest", List.of("https://ejemplo.com/media/clip_1080p.mp4"), pv.media());
        Check.eq("pagePlayers", List.of("https://www.youtube.com/embed/dQw4w9WgXcQ"), pv.players());
        Check.eq("pageCanonical", "https://ejemplo.com/v/1", pv.page());
        Check.done();
    }
}
