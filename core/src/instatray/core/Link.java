package instatray.core;

/**
 * A supported link found in some text. {@code key} identifies the video for history checks (site prefix + id:
 * "yt:ID", "ig:ID"…); catch-all keys carry the library's name for the site: "ytdl:Niconico:sm9" (extractor + id),
 * "gdl:artstation:URL" (category + URL) or "embed:host:hash". {@code url} is the clean URL handed to the tool.
 */
public record Link(Site site, String key, String url) {

    /** For catch-alls: the extractor / category in the key, capitalized ("Niconico", "Artstation"); else null. */
    public String libraryName() {
        if (!site.isCatchAll()) return null;
        String[] k = key.split(":", 3);
        if (k.length < 2 || k[1].isEmpty()) return null;
        return Character.toUpperCase(k[1].charAt(0)) + k[1].substring(1); // gallery-dl categories are lower-case
    }

    /** What to show as the site: the extractor/category name for catch-alls, otherwise the site's label. */
    public String label() {
        String name = libraryName();
        return name != null ? name : site.label();
    }
}
