package instatray.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Probe without real libraries: no Python means no answers, and the shebang reader. */
public class ProbeTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("instatray-probe-test");
        Path tool = Files.writeString(dir.resolve("yt-dlp"), "#!/bin/sh\necho hi\n");
        Path plain = Files.writeString(dir.resolve("plain"), "no shebang\n");
        Check.eq("shebang", "/bin/sh", Probe.shebangPython(tool.toString()));
        Check.eq("noShebang", null, Probe.shebangPython(plain.toString()));
        Check.eq("missingTool", null, Probe.shebangPython(null));

        Probe probe = new Probe(t -> null, Map.of(), m -> { });
        Check.eq("noPythonNoLinks", List.of(), probe.links(List.of("https://www.nicovideo.jp/watch/sm9"), true));
        Check.that("creatorPlatformsOnly", Probe.CREATOR_PLATFORMS.contains("artstation")
                && !Probe.CREATOR_PLATFORMS.contains("kemono"), "");
        Check.done();
    }
}
