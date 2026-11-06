import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class HomebrewTest {
    /** Records each brew call; "outdated" and "deps" print the given lines, "fetch" succeeds or not. */
    static final class Fake implements InstaTray.BrewRunner {
        final List<String> calls = new ArrayList<>();
        final List<String> outdatedLines, depLines;
        final boolean fetchOk;

        Fake(List<String> outdatedLines, List<String> depLines, boolean fetchOk) {
            this.outdatedLines = outdatedLines;
            this.depLines = depLines;
            this.fetchOk = fetchOk;
        }

        @Override
        public boolean run(List<String> cmd, boolean holdQueue, Consumer<String> out) {
            calls.add((holdQueue ? "HOLD " : "") + String.join(" ", cmd));
            if (cmd.contains("outdated")) outdatedLines.forEach(out);
            if (cmd.contains("deps")) depLines.forEach(out);
            return !cmd.contains("fetch") || fetchOk;
        }
    }

    public static void main(String[] args) {
        String json = """
                {"tag_name":"7.0.10","assets":[
                  {"name":"Homebrew-7.0.10.tar.gz","browser_download_url": "https://github.com/Homebrew/brew/releases/download/7.0.10/Homebrew-7.0.10.tar.gz"},
                  {"name":"Homebrew.pkg","browser_download_url": "https://github.com/Homebrew/brew/releases/download/7.0.10/Homebrew.pkg"}]}""";
        T.eq("pkgUrlFound", "https://github.com/Homebrew/brew/releases/download/7.0.10/Homebrew.pkg", InstaTray.homebrewPkgUrl(json));
        T.eq("pkgUrlMissing", null, InstaTray.homebrewPkgUrl("{\"assets\":[]}"));
        T.eq("pkgUrlOnlyGithub", null, InstaTray.homebrewPkgUrl(
                "{\"browser_download_url\": \"https://evil.example/Homebrew.pkg\"}"));

        // the weekly upgrade: everything slow runs with the queue going, only the swap itself holds it
        List<String> tools = List.of("yt-dlp", "gallery-dl");
        List<String> warning = List.of("Warning: yt-dlp is not a real result here");
        // python@3.15 is a runtime dep that upgrade would also replace; cmake is outdated but no business of ours
        Fake outdated = new Fake(List.of("Warning: The following taps are not trusted:", "cmake", "python@3.15", "yt-dlp"),
                List.of("lz4", "python@3.15"), true);
        T.check("upgradeOk", InstaTray.upgradeSteps(List.of("brew"), tools, outdated), "");
        T.eq("upgradeOrder", List.of("brew update", "brew outdated --quiet --formula", "brew deps yt-dlp",
                "brew fetch yt-dlp python@3.15", "HOLD brew upgrade yt-dlp"), outdated.calls);

        Fake upToDate = new Fake(List.of("cmake", warning.get(0)), List.of(), true);
        T.check("upToDateOk", InstaTray.upgradeSteps(List.of("brew"), tools, upToDate), "");
        T.eq("upToDateNeverHolds", List.of("brew update", "brew outdated --quiet --formula"), upToDate.calls);

        Fake noFetch = new Fake(List.of("yt-dlp"), List.of(), false);
        T.check("fetchFailedReported", !InstaTray.upgradeSteps(List.of("brew"), tools, noFetch), "");
        T.check("fetchFailedNeverHolds", noFetch.calls.stream().noneMatch(c -> c.startsWith("HOLD")), noFetch.calls.toString());
        T.done();
    }
}
