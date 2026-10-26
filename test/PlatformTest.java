import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class PlatformTest {
    public static void main(String[] args) throws Exception {
        T.check("isWindows", Platform.isWindows("Windows 11") && !Platform.isWindows("Mac OS X"), "");

        Path macHome = Paths.get("/Users/ana");
        T.eq("stateDirMac", Paths.get("/Users/ana/Library/Application Support/InstaTray"),
                Platform.stateDir(false, macHome, Map.of()));
        // Windows paths built with "/" here: Path on the build machine is Unix, the logic is the same
        Path winHome = Paths.get("/C/Users/ana");
        Map<String, String> env = Map.of("APPDATA", "/C/Users/ana/AppData/Roaming", "LOCALAPPDATA", "/C/Users/ana/AppData/Local",
                "PATH", "/C/Windows/System32;\"/C/Tools\";;");
        T.eq("stateDirWindows", Paths.get("/C/Users/ana/AppData/Roaming/InstaTray"), Platform.stateDir(true, winHome, env));
        T.eq("stateDirWindowsNoEnv", winHome.resolve("AppData/Roaming/InstaTray"), Platform.stateDir(true, winHome, Map.of()));
        T.eq("venvWindows", Paths.get("/C/Users/ana/AppData/Local/InstaTray/tools"), Platform.venv(true, winHome, env));
        T.check("noVenvOnMac", Platform.venv(false, macHome, Map.of()) == null, "");
        T.eq("videosMac", macHome.resolve("Documents/Videos"), Platform.defaultVideosDir(false, macHome));
        T.eq("videosWindows", winHome.resolve("Videos/InstaTray"), Platform.defaultVideosDir(true, winHome));

        // tool lookup order: venv first, then winget links, scoop, PATH (quotes and blanks cleaned up)
        List<Path> dirs = Platform.binDirs(true, winHome, env);
        T.eq("binDirsWindows", List.of(Paths.get("/C/Users/ana/AppData/Local/InstaTray/tools/Scripts"),
                Paths.get("/C/Users/ana/AppData/Local/Microsoft/WinGet/Links"), winHome.resolve("scoop/shims"),
                Paths.get("/C/Windows/System32"), Paths.get("/C/Tools")), dirs);
        T.eq("binDirsMac", Paths.get("/opt/homebrew/bin"), Platform.binDirs(false, macHome, Map.of()).get(0));
        T.eq("exeNamesWindows", List.of("ffmpeg.exe", "ffmpeg.cmd", "ffmpeg.bat"), Platform.exeNames(true, "ffmpeg"));
        T.eq("exeNamesMac", List.of("ffmpeg"), Platform.exeNames(false, "ffmpeg"));
        T.eq("pathEnvWindows", "/a;/b", Platform.pathEnv(List.of(Paths.get("/a"), Paths.get("/b")), true));
        T.eq("pathEnvMac", "/a:/b", Platform.pathEnv(List.of(Paths.get("/a"), Paths.get("/b")), false));
        T.check("pythonNeverStoreAlias", Platform.pythonCandidates(winHome, env).stream()
                .noneMatch(p -> p.toString().contains("WindowsApps")), "");
        T.eq("pythonNewestFirst", Paths.get("/C/Users/ana/AppData/Local/Programs/Python/Python314/python.exe"),
                Platform.pythonCandidates(winHome, env).get(0));

        // file paths printed by yt-dlp / gallery-dl
        T.check("absMac", Platform.isAbsolutePath("/Users/ana/Videos/a.mp4"), "");
        T.check("absWindows", Platform.isAbsolutePath("C:\\Users\\ana\\Videos\\a.mp4"), "");
        T.check("absWindowsSlash", Platform.isAbsolutePath("D:/Videos/a.mp4"), "");
        T.check("absUnc", Platform.isAbsolutePath("\\\\nas\\videos\\a.mp4"), "");
        T.check("notAbs", !Platform.isAbsolutePath("[download] 45%") && !Platform.isAbsolutePath("C:")
                && !Platform.isAbsolutePath("https://x.com/a") && !Platform.isAbsolutePath(""), "");

        // dark mode from the registry
        String regDark = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n"
                + "    AppsUseLightTheme    REG_DWORD    0x0\r\n";
        T.check("windowsDark", Platform.parseWindowsDark(regDark), "");
        T.check("windowsLight", !Platform.parseWindowsDark(regDark.replace("0x0", "0x1")), "");
        T.check("windowsNoValue", !Platform.parseWindowsDark("ERROR: The system was unable to find the specified registry key"), "");

        T.eq("revealWindows", List.of("explorer.exe", "/select,C:\\v\\a.mp4"), Platform.revealCommand(true, "C:\\v\\a.mp4"));
        T.eq("revealMac", List.of("open", "-R", "/v/a.mp4"), Platform.revealCommand(false, "/v/a.mp4"));
        T.eq("revealLabels", "Mostrar en carpeta|Mostrar en Finder", Platform.revealLabel(true) + "|" + Platform.revealLabel(false));
        T.check("wingetSilent", Platform.wingetInstall("winget", "Gyan.FFmpeg").containsAll(
                List.of("--silent", "--accept-package-agreements", "--accept-source-agreements", "-e")), "");
        T.check("venvTools", Platform.inVenv("yt-dlp") && Platform.inVenv("gallery-dl") && Platform.wingetId("yt-dlp") == null
                && Platform.wingetId("ffmpeg") != null && Platform.wingetId("deno") != null, "");
        T.eq("pipUpgrade", List.of("/v/Scripts/python.exe", "-m", "pip", "install", "--disable-pip-version-check", "--quiet",
                "--upgrade", "yt-dlp"), Platform.pipInstall(Paths.get("/v"), true, List.of("yt-dlp")));
        T.eq("pixivConsole", List.of("cmd.exe", "/c", "start", "t", "cmd.exe", "/k", "gdl.exe", "oauth:pixiv"),
                Platform.consoleCommand(true, "t", "gdl.exe", "oauth:pixiv"));

        // the Windows .ico: header + one PNG per size
        Path ico = Files.createTempFile("instatray", ".ico");
        AeroIcon.writeIco(ico.toFile(), 16, 32, 256);
        byte[] b = Files.readAllBytes(ico);
        int count = (b[4] & 255) | (b[5] & 255) << 8;
        int firstOffset = (b[18] & 255) | (b[19] & 255) << 8 | (b[20] & 255) << 16 | (b[21] & 255) << 24;
        T.check("icoHeader", b[2] == 1 && count == 3 && b[6] == 16 && b[6 + 32] == 0, "count " + count);
        T.check("icoPng", b[firstOffset] == (byte) 0x89 && b[firstOffset + 1] == 'P', "offset " + firstOffset);
        Files.delete(ico);
        T.done();
    }
}
