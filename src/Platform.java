import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What differs between macOS and Windows: folders, where the download tools live, how to open things.
 * Everything takes the OS as an argument so both sides are testable on either system; the no-argument
 * shortcuts use the running OS.
 *
 * <p>macOS: tools from Homebrew. Windows: Java, Python, ffmpeg and deno from winget; yt-dlp and gallery-dl in the
 * app's own Python venv (the catch-all probes import their Python packages, which the standalone .exe builds don't
 * allow).
 */
final class Platform {

    private Platform() {
    }

    static final boolean WINDOWS = isWindows(System.getProperty("os.name", ""));
    static final Path HOME = Paths.get(System.getProperty("user.home"));

    static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    // ---------------------------------------------------------------- folders

    /** Settings, history, log: ~/Library/Application Support/InstaTray or %APPDATA%\InstaTray. */
    static Path stateDir(boolean windows, Path home, Map<String, String> env) {
        if (!windows) return home.resolve("Library/Application Support/InstaTray");
        String appData = env.get("APPDATA");
        return (appData != null ? Paths.get(appData) : home.resolve("AppData").resolve("Roaming")).resolve("InstaTray");
    }

    static Path stateDir() {
        return stateDir(WINDOWS, HOME, System.getenv());
    }

    /** Windows only: %LOCALAPPDATA%\InstaTray (the tools venv lives in "tools"); null on macOS. */
    static Path localDir(boolean windows, Path home, Map<String, String> env) {
        if (!windows) return null;
        String local = env.get("LOCALAPPDATA");
        return (local != null ? Paths.get(local) : home.resolve("AppData").resolve("Local")).resolve("InstaTray");
    }

    /** The venv holding yt-dlp and gallery-dl on Windows; null on macOS. */
    static Path venv(boolean windows, Path home, Map<String, String> env) {
        Path local = localDir(windows, home, env);
        return local == null ? null : local.resolve("tools");
    }

    static Path venv() {
        return venv(WINDOWS, HOME, System.getenv());
    }

    /** macOS: ~/Documents/Videos (as always). Windows: the user's Videos folder, in its own InstaTray subfolder. */
    static Path defaultVideosDir(boolean windows, Path home) {
        return windows ? home.resolve("Videos").resolve("InstaTray") : home.resolve("Documents").resolve("Videos");
    }

    static Path defaultVideosDir() {
        return defaultVideosDir(WINDOWS, HOME);
    }

    // ---------------------------------------------------------------- finding the tools

    private static final String[] MAC_BIN_DIRS = {"/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin"};

    /**
     * Where to look for a tool, in order. A .app started from Finder has a minimal PATH, so macOS lists them
     * explicitly; Windows checks the venv, winget's links, scoop, then PATH.
     */
    static List<Path> binDirs(boolean windows, Path home, Map<String, String> env) {
        List<Path> dirs = new ArrayList<>();
        if (!windows) {
            for (String d : MAC_BIN_DIRS) dirs.add(Paths.get(d));
            return dirs;
        }
        dirs.add(venv(true, home, env).resolve("Scripts"));
        Path local = localDir(true, home, env).getParent(); // %LOCALAPPDATA%
        dirs.add(local.resolve("Microsoft").resolve("WinGet").resolve("Links"));
        dirs.add(home.resolve("scoop").resolve("shims"));
        String path = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        for (String d : path.split(";")) {
            if (d.isBlank()) continue;
            try {
                dirs.add(Paths.get(d.strip().replace("\"", "")));
            } catch (Exception ignored) { // malformed PATH entry
            }
        }
        return dirs;
    }

    static List<Path> binDirs() {
        return binDirs(WINDOWS, HOME, System.getenv());
    }

    /** File names a tool can have: "yt-dlp" on macOS; yt-dlp.exe / .cmd / .bat on Windows. */
    static List<String> exeNames(boolean windows, String tool) {
        return windows ? List.of(tool + ".exe", tool + ".cmd", tool + ".bat") : List.of(tool);
    }

    /** The PATH handed to yt-dlp (it finds ffmpeg and deno through it). */
    static String pathEnv(List<Path> dirs, boolean windows) {
        List<String> parts = new ArrayList<>();
        for (Path d : dirs) parts.add(d.toString());
        return String.join(windows ? ";" : ":", parts);
    }

    /**
     * Python installs that can create the venv (Windows), newest first. The python.exe under WindowsApps is only a
     * Microsoft Store shortcut, so it is never used.
     */
    static List<Path> pythonCandidates(Path home, Map<String, String> env) {
        List<Path> found = new ArrayList<>();
        Path local = localDir(true, home, env).getParent();
        String pf = env.getOrDefault("ProgramFiles", "C:\\Program Files");
        for (Path base : List.of(local.resolve("Programs").resolve("Python"), Paths.get(pf))) {
            for (int minor = 14; minor >= 9; minor--) found.add(base.resolve("Python3" + minor).resolve("python.exe"));
        }
        return found;
    }

    /** The venv's interpreter (Windows): the probes run with it. */
    static Path venvPython(Path venv) {
        return venv.resolve("Scripts").resolve("python.exe");
    }

    // ---------------------------------------------------------------- tool output

    /** "/Users/x/a.mp4", "C:\Users\x\a.mp4" or "\\server\share\a.mp4": a full file path printed by a tool. */
    static boolean isAbsolutePath(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.startsWith("/")) return true;
        if (s.startsWith("\\\\")) return true;
        return s.length() > 2 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':' && (s.charAt(2) == '\\' || s.charAt(2) == '/');
    }

    // ---------------------------------------------------------------- appearance

    /** `reg query …\Personalize /v AppsUseLightTheme` prints "AppsUseLightTheme    REG_DWORD    0x0" in dark mode. */
    static boolean parseWindowsDark(String regOutput) {
        for (String line : regOutput.split("\\R")) {
            String t = line.strip();
            if (!t.startsWith("AppsUseLightTheme")) continue;
            String[] parts = t.split("\\s+");
            return parts.length >= 3 && parts[parts.length - 1].equalsIgnoreCase("0x0");
        }
        return false;
    }

    static List<String> darkModeQuery(boolean windows) {
        return windows
                ? List.of("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme")
                : List.of("defaults", "read", "-g", "AppleInterfaceStyle");
    }

    // ---------------------------------------------------------------- opening things

    /** Shows the file selected in Finder / Explorer. */
    static List<String> revealCommand(boolean windows, String file) {
        return windows ? List.of("explorer.exe", "/select," + file) : List.of("open", "-R", file);
    }

    /** Opens a file, a folder or a web address with its default app (macOS; Windows uses java.awt.Desktop). */
    static List<String> openCommand(String target) {
        return List.of("open", target);
    }

    /** A visible console running a command and staying open (the Pixiv login). */
    static List<String> consoleCommand(boolean windows, String title, String... command) {
        if (!windows) throw new IllegalArgumentException("macOS uses Terminal via osascript");
        List<String> cmd = new ArrayList<>(List.of("cmd.exe", "/c", "start", title, "cmd.exe", "/k"));
        cmd.addAll(List.of(command));
        return cmd;
    }

    /** "Mostrar en Finder" / "Mostrar en carpeta" (fits the button column; "…en el Explorador" did not). */
    static String revealLabel(boolean windows) {
        return windows ? "Mostrar en carpeta" : "Mostrar en Finder";
    }

    // ---------------------------------------------------------------- Windows installs

    /** winget package of each tool that winget installs (yt-dlp and gallery-dl go in the venv with pip). */
    static String wingetId(String tool) {
        return switch (tool) {
            case "ffmpeg" -> "Gyan.FFmpeg";
            case "deno" -> "DenoLand.Deno";
            case "python" -> "Python.Python.3.12";
            default -> null;
        };
    }

    /** Tools that live in the venv on Windows. */
    static boolean inVenv(String tool) {
        return tool.equals("yt-dlp") || tool.equals("gallery-dl");
    }

    static List<String> wingetInstall(String winget, String id) {
        return List.of(winget, "install", "--id", id, "-e", "--silent", "--disable-interactivity",
                "--accept-source-agreements", "--accept-package-agreements");
    }

    static List<String> wingetUpgrade(String winget, String id) {
        return List.of(winget, "upgrade", "--id", id, "-e", "--silent", "--disable-interactivity",
                "--accept-source-agreements", "--accept-package-agreements");
    }

    static List<String> pipInstall(Path venv, boolean upgrade, List<String> packages) {
        List<String> cmd = new ArrayList<>(List.of(venvPython(venv).toString(), "-m", "pip", "install",
                "--disable-pip-version-check", "--quiet"));
        if (upgrade) cmd.add("--upgrade");
        cmd.addAll(packages);
        return cmd;
    }
}
