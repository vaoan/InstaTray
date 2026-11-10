package instatray.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Where the caller's download tools are: absolute paths, or null when one is missing. This library never installs
 * anything. A record with the three accessors implements it directly.
 */
public interface Tools {

    String ytDlp();

    String galleryDl();

    String ffmpeg();

    /**
     * Variables put into the tools' environment (on top of the inherited one). yt-dlp finds ffmpeg and deno (YouTube
     * needs it) through PATH, so the default puts the tools' own folders before the inherited PATH; Python prints
     * UTF-8 (gallery-dl's "✔", non-Latin titles) even on a Windows console.
     */
    default Map<String, String> environment() {
        Set<String> dirs = new LinkedHashSet<>();
        Stream.of(ytDlp(), galleryDl(), ffmpeg()).filter(Objects::nonNull).map(Path::of)
                .map(Path::getParent).filter(Objects::nonNull).forEach(d -> dirs.add(d.toString()));
        String sep = System.getProperty("path.separator", ":");
        String inherited = Objects.requireNonNullElse(System.getenv("PATH"), "");
        if (!inherited.isEmpty()) dirs.add(inherited);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", String.join(sep, dirs)); // Windows environments ignore the case of names
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        return env;
    }
}
