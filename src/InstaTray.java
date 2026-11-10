import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
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
import instatray.core.Cookies;
import instatray.core.Engine;
import instatray.core.Fetch;
import instatray.core.Link;
import instatray.core.Links;
import instatray.core.Probe;
import instatray.core.Progress;
import instatray.core.Site;
import instatray.core.Tools;
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
    private static final Path STATE_DIR = Platform.stateDir();
    private static final Path HISTORY = STATE_DIR.resolve("downloaded.txt");
    private static final Path SETTINGS = STATE_DIR.resolve("settings.properties");
    private static final Path JOBS = STATE_DIR.resolve("jobs.tsv");
    private static final int MAX_JOBS = 200;
    private static final Path LOG = STATE_DIR.resolve("instatray.log");

    private static final int INSTANCE_PORT = 48623;

    /** Waiting jobs in download order (includes automatic retries waiting for their delay). Guarded by itself. */
    private final LinkedList<Job> pending = new LinkedList<>();
    private volatile Job current;
    private volatile boolean paused = false;
    /** True while Homebrew installs/upgrades tools: the queue waits so yt-dlp is never replaced mid-download. */
    private volatile boolean toolsBusy = false;
    private volatile JobView.Notice notice;
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
    /** The native menu-bar item (narrower than TrayIcon); null when the helper is missing or died: then trayIcon shows. */
    private volatile NativeBar bar;
    private MenuItem statusItem;
    private MenuItem cookiesItem;
    private CheckboxMenuItem watchItem;
    private CheckboxMenuItem notifyItem;
    private volatile boolean notifications = true;
    private static final Path DEFAULT_VIDEOS_DIR = Platform.defaultVideosDir();
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
        if (Platform.WINDOWS) { // the painted windows look the same everywhere; folder picker and dialogs look native
            try {
                javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
        }
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

        Lang.set(Lang.Choice.parse(settings.getProperty("language", "system")));
        Theme.setMode(Theme.Mode.parse(settings.getProperty("appearance", "system"))); // LIGHT/DARK apply at once
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

        // Ignore whatever is already in the clipboard at startup, but the window offers it
        lastClip = readClipboard();
        List<Link> copiedBefore = Links.find(lastClip).stream()
                .filter(l -> !done.contains(l.key()) && !queued.contains(l.key())).toList();
        if (!copiedBefore.isEmpty()) notice = new JobView.Notice(JobView.Notice.Kind.COPIED_BEFORE, copiedBefore);
        poller.execute(() -> setStatus(Lang.key("Vigilando portapapeles")));
        poller.scheduleWithFixedDelay(this::pollClipboard, 1, 1, TimeUnit.SECONDS);
        log("InstaTray started. Output: " + videosDir + (subfolders ? "/<Site>" : ""));
    }

    // ---------------------------------------------------------------- UI

    private void buildTray() {
        PopupMenu menu = new PopupMenu();

        statusItem = new MenuItem(Lang.t("Listo — copia un enlace de video"));
        statusItem.setEnabled(false);
        menu.add(statusItem);

        cookiesItem = new MenuItem(Lang.t("Cookies: buscando en Descargas…"));
        cookiesItem.setEnabled(false);
        menu.add(cookiesItem);
        menu.addSeparator();

        watchItem = new CheckboxMenuItem("", true);
        Lang.item(watchItem, "Vigilar portapapeles");
        watchItem.addItemListener(e -> setWatching(watchItem.getState()));
        menu.add(watchItem);

        notifyItem = new CheckboxMenuItem("", notifications);
        Lang.item(notifyItem, "Avisar si una descarga falla");
        notifyItem.addItemListener(e -> setNotifications(notifyItem.getState()));
        menu.add(notifyItem);

        MenuItem now = new MenuItem();
        Lang.item(now, "Descargar enlace copiado ahora");
        now.addActionListener(e -> downloadClipboardNow());
        menu.add(now);

        subfoldersItem = new CheckboxMenuItem("", subfolders);
        Lang.item(subfoldersItem, "Una subcarpeta por red social");
        subfoldersItem.addItemListener(e -> setSubfolders(subfoldersItem.getState()));
        menu.add(subfoldersItem);

        embedsItem = new CheckboxMenuItem("", embeds);
        Lang.item(embedsItem, "Buscar video embebido en otras páginas");
        embedsItem.addItemListener(e -> setEmbeds(embedsItem.getState()));
        menu.add(embedsItem);

        MenuItem hist = new MenuItem();
        Lang.item(hist, "Cola e historial…");
        hist.addActionListener(e -> showWindow());
        menu.add(hist);

        pauseItem = new CheckboxMenuItem("", false);
        Lang.item(pauseItem, "Pausar cola");
        pauseItem.addItemListener(e -> setPaused(pauseItem.getState()));
        menu.add(pauseItem);

        MenuItem show = new MenuItem();
        Lang.item(show, "Mostrar ventana");
        show.addActionListener(e -> showWindow());
        menu.add(show);
        menu.addSeparator();

        MenuItem open = new MenuItem();
        Lang.item(open, "Abrir carpeta de videos");
        open.addActionListener(e -> openPath(videosDir));
        menu.add(open);

        MenuItem openLog = new MenuItem();
        Lang.item(openLog, "Ver registro");
        openLog.addActionListener(e -> openPath(LOG));
        menu.add(openLog);
        menu.addSeparator();

        MenuItem quit = new MenuItem();
        Lang.item(quit, "Salir");
        quit.addActionListener(e -> {
            SystemTray.getSystemTray().remove(trayIcon);
            System.exit(0);
        });
        menu.add(quit);

        // language changed in Ajustes: fixed labels from their keys, the status line, cookies line and tooltip anew
        Lang.addListener(() -> {
            Lang.relabel(menu);
            poller.execute(this::restatus);
            updateTrayIcon();
            NativeBar b = bar;
            if (b != null) b.syncMenu();
        });

        BufferedImage icon = AeroIcon.render(AeroIcon.TRAY_PX);
        trayIcon = new TrayIcon(icon, "InstaTray", menu);
        trayIcon.setImageAutoSize(true);

        if (Platform.WINDOWS) trayIcon.addActionListener(e -> showWindow()); // double-click on the notification-area icon
        NativeBar nb = Platform.WINDOWS ? null : NativeBar.start(helperPath(), menu, () -> EventQueue.invokeLater(this::fallBackToAwtTray));
        if (nb == null) {
            addAwtTray();
            return;
        }
        bar = nb;
        log("Menu-bar item: native helper");
        nb.setIcon(AeroIcon.renderTrayArrow(AeroIcon.TRAY_PX), true);
        nb.setTooltip("InstaTray");
        nb.syncMenu();
        // the AWT menu stays the model; labels and check marks change in many places, so mirror it on a timer
        new javax.swing.Timer(500, e -> {
            NativeBar b = bar;
            if (b != null) b.syncMenu();
        }).start();
    }

    private void addAwtTray() {
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            throw new RuntimeException(e);
        }
    }

    /** The helper died: show the plain Java icon instead (on the EDT). */
    private void fallBackToAwtTray() {
        if (bar == null) return;
        bar = null;
        log("Native menu-bar helper exited; using the Java tray icon");
        lastIconKey = null;
        addAwtTray();
        updateTrayIcon();
    }

    /** Contents/Resources/InstaTrayBar, next to the jar; null when running from classes (tests, previews). */
    private static Path helperPath() {
        try {
            Path jar = Paths.get(InstaTray.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(jar) ? jar.getParent().resolve("InstaTrayBar") : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void setTrayTooltip(String tip) {
        NativeBar b = bar;
        if (b != null) b.setTooltip(tip);
        else trayIcon.setToolTip(tip);
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
     * top KEY, cancel KEY, remove KEY, menu.
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
            case "notice" -> { JobView.Notice n = notice; return n == null ? "none" : n.kind() + " | " + n.text() + " | " + n.action(); }
            case "accept" -> acceptNotice();
            case "get" -> { return handleText(arg, true) ? "ok" : "no supported link"; } // like the window field
            case "menu" -> { // opens the menu-bar menu as a click would, or closes it (to capture the highlight)
                NativeBar b = bar;
                if (b == null) return "no native bar";
                b.open();
            }
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
        poller.execute(() -> setStatus(on ? Lang.key("Vigilando portapapeles") : Lang.key("En pausa")));
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
    public Lang.Choice language() {
        return Lang.choice();
    }

    @Override
    public void setLanguage(Lang.Choice choice) {
        Lang.set(choice);
        saveSetting("language", choice.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public Theme.Mode appearance() {
        return Theme.mode();
    }

    @Override
    public void setAppearance(Theme.Mode mode) {
        Theme.setMode(mode);
        saveSetting("appearance", mode.name().toLowerCase(java.util.Locale.ROOT));
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
        return displayPath(videosDir) + (subfolders ? "/" + Lang.t("<red social>") : "");
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
        if (Platform.WINDOWS) { // FileDialog cannot pick folders on Windows
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser(videosDir.toFile());
            fc.setDialogTitle(Lang.t("Elige la carpeta para los videos"));
            fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(owner) != javax.swing.JFileChooser.APPROVE_OPTION) return;
            setVideosDir(fc.getSelectedFile().toPath());
            return;
        }
        // Native macOS folder picker
        System.setProperty("apple.awt.fileDialogForDirectories", "true");
        String title = Lang.t("Elige la carpeta para los videos");
        FileDialog fd = owner instanceof Dialog d ? new FileDialog(d, title, FileDialog.LOAD)
                : new FileDialog(owner instanceof Frame f ? f : null, title, FileDialog.LOAD);
        fd.setDirectory(videosDir.toString());
        fd.setVisible(true);
        System.setProperty("apple.awt.fileDialogForDirectories", "false");
        if (fd.getFile() == null) return;
        setVideosDir(Paths.get(fd.getDirectory(), fd.getFile()));
    }

    private void setVideosDir(Path chosen) {
        videosDir = chosen;
        saveSetting("videosDir", chosen.toString());
        log("Videos folder -> " + chosen);
    }

    /** Folder for a site's videos, created on demand. */
    private Path outDir(Site site) {
        return subfolders ? videosDir.resolve(folderName(site)) : videosDir;
    }

    /** "~/Documents/Videos" -> "Documentos/Videos" (Finder's name for Documents in the current language). */
    static String displayPath(Path p) {
        Path docs = HOME.resolve("Documents");
        if (p.startsWith(docs)) return Lang.t("Documentos") + "/" + docs.relativize(p);
        if (p.startsWith(HOME)) return Platform.WINDOWS ? HOME.relativize(p).toString() : "~/" + HOME.relativize(p);
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
                setStatus(Lang.key("No hay un enlace de video compatible copiado"));
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
        AeroIcon.Palette palette = pending == 0 || site == null ? AeroIcon.Palette.IDLE : AeroIcon.palette(site);
        int pct = palette == AeroIcon.Palette.IDLE ? -1 : activePercent;
        // One item: the number is its download percentage. Several: the number is how many are pending.
        // (one item with unknown progress, e.g. gallery-dl: the site-colored orb shows the download arrow)
        String badge = palette == AeroIcon.Palette.IDLE ? null
                : pending == 1 ? (pct < 0 ? null : pct + "%")
                : pending > 9 ? "9+" : String.valueOf(pending);
        int step = pct < 0 ? -1 : pct / 5 * 5; // 5% steps keep the icon cache small (fill level)
        String key = palette + ":" + badge + ":" + step;
        NativeBar b = bar;
        float level = step < 0 ? -1f : step / 100f;
        // only the native item can be tinted to suit the bar; a fixed white arrow would vanish on a light one
        boolean template = b != null && palette == AeroIcon.Palette.IDLE;
        BufferedImage img = iconCache.computeIfAbsent(template ? "ARROW" : key,
                k -> template ? AeroIcon.renderTrayArrow(AeroIcon.TRAY_PX) : AeroIcon.render(AeroIcon.TRAY_PX, palette, badge, level));
        if (!key.equals(lastIconKey)) log("Tray icon -> " + key);
        lastIconKey = key;
        String tip = tooltip(pending);
        lastTooltip = tip;
        lastTooltipAt = System.currentTimeMillis();
        EventQueue.invokeLater(() -> {
            if (b != null) {
                b.setIcon(img, template);
            } else {
                trayIcon.setImage(img);
            }
            setTrayTooltip(tip);
        });
    }

    /** "YouTube · Me at the zoo — 45% · 2.1 MB/s · quedan 0:12 · +2 en cola" */
    private String tooltip(int pending) {
        if (paused && pending > 0) {
            return pending == 1 ? Lang.t("InstaTray — cola en pausa, 1 pendiente") : Lang.f("InstaTray — cola en pausa, %d pendientes", pending);
        }
        Job j = current;
        if (j == null || j.isFinished()) {
            if (pending == 0) return Lang.t("InstaTray — sin descargas");
            return toolsBusy ? Lang.f("InstaTray — %d en cola, actualizando herramientas", pending) : Lang.f("InstaTray — %d en cola", pending);
        }
        StringBuilder sb = new StringBuilder(label(j.link));
        if (!j.title.isBlank()) sb.append(" · ").append(shorten(j.title, 50));
        sb.append(" — ").append(j.percent >= 0 ? j.percent + "%" : Lang.t("preparando…"));
        if (!j.speed.isBlank()) sb.append(" · ").append(j.speed);
        if (!j.eta.isBlank()) sb.append(" · ").append(Lang.f("quedan %s", j.eta));
        if (pending > 1) sb.append(" · ").append(Lang.f("+%d en cola", pending - 1));
        if (j.attempt > 0) sb.append(" · ").append(Lang.f("reintento %d", j.attempt));
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
        EventQueue.invokeLater(() -> setTrayTooltip(tip));
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String formatBytes(double bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.2f GB", bytes / (1024L * 1024 * 1024));
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024 * 1024));
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024);
    }


    /** The status line's Spanish key and arguments, to redo it after a language change. */
    private volatile String statusKey = Lang.key("Listo — copia un enlace de video");
    private volatile Object[] statusArgs = {};

    /**
     * es: Spanish key (pass it through Lang.key), formatted with args in the current language.
     * Must not run on the EDT: reading ~/Downloads may block on the macOS privacy prompt.
     */
    private void setStatus(String es, Object... args) {
        statusKey = es;
        statusArgs = args;
        String text = Lang.f(es, args);
        String cookies = cookiesLabel();
        EventQueue.invokeLater(() -> {
            statusItem.setLabel(text);
            cookiesItem.setLabel(cookies);
        });
    }

    /** The status and cookies lines again, in the current language (off the EDT). */
    private void restatus() {
        setStatus(statusKey, statusArgs);
    }

    private String cookiesLabel() {
        List<String> with = new ArrayList<>();
        for (Site site : Site.named()) {
            if (Cookies.find(DOWNLOADS, site) != null) with.add(display(site));
        }
        return with.isEmpty() ? Lang.t("Cookies: ninguna en Descargas")
                : Lang.f("Cookies: %s (%d de %d sitios)", String.join(", ", with), with.size(), Site.named().size());
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

    private boolean handleText(String text, boolean force) {
        return handleText(text, "", force);
    }

    /**
     * Queues every supported link found in the text, or in the page when the text is HTML source / the clipboard
     * also carries HTML (rich copy). Returns true if any link was found.
     */
    private boolean handleText(String text, String richHtml, boolean force) {
        boolean found = false;
        boolean source = Links.looksLikeHtml(text);
        String plain = source ? "" : text;
        // explicit requests (window field, "Descargar enlace copiado ahora") may use the generic extractor even with embeds off
        List<Link> links = new ArrayList<>(Links.find(plain));
        links.addAll(probeLibraries(Links.otherCandidates(plain), embeds || force));
        String html = source ? text : richHtml;
        Links.PageVideos pv = html.isEmpty() ? null : Links.pageVideos(html);
        List<Link> fromPage = pv == null ? List.of() : pageLinks(pv, force);
        links.addAll(fromPage);
        List<Link> ignored = new ArrayList<>();
        for (Link link : links) {
            found = true;
            String key = link.key();
            if (queued.contains(key)) continue;
            if (done.contains(key) && !force) {
                setStatus(Lang.key("Ya descargado: %s %s"), label(link), Links.shortKey(key));
                ignored.add(link);
                continue;
            }
            queueLink(link, pv != null && fromPage.contains(link) ? pv.page() : null);
        }
        if (found) notice = ignored.isEmpty() ? null : new JobView.Notice(JobView.Notice.Kind.ALREADY_DONE, List.copyOf(ignored));
        return found;
    }

    /** page: the pasted page the link came from, for "Abrir página"; may be null. */
    private void queueLink(Link link, String page) {
        Job job = new Job(link, System.currentTimeMillis());
        if (page != null) job.page = page;
        synchronized (jobs) {
            JobView.dropFinished(jobs, link.key());
            jobs.add(0, job);
        }
        enqueue(job);
    }

    @Override
    public boolean toolsBusy() {
        return toolsBusy;
    }

    @Override
    public JobView.Notice notice() {
        return notice;
    }

    @Override
    public void acceptNotice() {
        JobView.Notice n = notice;
        notice = null;
        if (n == null) return;
        poller.execute(() -> {
            for (Link link : n.links()) {
                if (!queued.contains(link.key())) queueLink(link, null);
            }
        });
    }

    @Override
    public void dismissNotice() {
        notice = null;
    }

    /**
     * Links for the videos of a pasted page. What the page itself plays may go to the generic extractor (copying a
     * page with a video in it is the request); embedded players only through a real extractor unless embeds is on,
     * so ad and widget iframes stay out. Falls back to the page's own canonical URL.
     */
    private List<Link> pageLinks(Links.PageVideos pv, boolean force) {
        if (pv.isEmpty()) return List.of();
        log("Pasted page: " + pv.media().size() + " media, " + pv.players().size() + " players, page " + pv.page());
        String media = String.join("\n", pv.media()), players = String.join("\n", pv.players());
        List<Link> links = new ArrayList<>(Links.find(media));
        links.addAll(probeLibraries(Links.otherCandidates(media), true));
        links.addAll(Links.find(players));
        links.addAll(probeLibraries(Links.otherCandidates(players), embeds || force));
        if (links.isEmpty() && pv.page() != null) {
            links.addAll(Links.find(pv.page()));
            links.addAll(probeLibraries(Links.otherCandidates(pv.page()), embeds || force));
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
        setStatus(Lang.key("En cola: %s %s"), label(job.link), Links.shortKey(job.link.key()));
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
            job.cancelRequested = true; // seen by download() before Fetch.run starts
            synchronized (fetchLock) {
                // Fetch.run kills the tool (ffmpeg children too), deletes partial files and returns CANCELLED
                if (fetching != null) fetching.interrupt();
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
        poller.execute(() -> setStatus(on ? Lang.key("Cola en pausa (lo que se está bajando termina)") : Lang.key("Vigilando portapapeles")));
        updateTrayIcon();
    }

    private void markCancelled(Job job) {
        job.status = Job.Status.CANCELLED;
        job.percent = -1;
        job.message = Lang.t("Cancelado");
        job.notBefore = 0;
        queued.remove(job.link.key());
        if (queued.isEmpty()) activeSite = null;
        updateTrayIcon();
        saveJobs();
        log("Cancelled " + job.link.url());
    }

    /** Fetch.toPlayableMp4 with what it did in the job's output. Returns the final path. */
    private static String toPlayableMp4(String file, String ffmpeg, Job job) {
        try {
            Path out = Fetch.toPlayableMp4(Paths.get(file), ffmpeg);
            if (out.toString().equals(file)) return file;
            job.addOutput("[InstaTray] " + Lang.f("convertido a mp4: %s", out));
            return out.toString();
        } catch (IOException e) {
            job.addOutput("[InstaTray] " + Lang.f("no se pudo convertir %s: %s", file, e.getMessage()));
            return file;
        }
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
            notify("InstaTray", Lang.t("gallery-dl todavía no está instalado; vuelve a intentarlo en un minuto"));
            poller.execute(this::ensureTools);
            return;
        }
        // the title goes inside '…' in a shell line inside an AppleScript string: no quotes in its translations
        String script = "tell application \"Terminal\"\n activate\n do script \"clear; echo '" + Lang.t("InstaTray: conectar Pixiv (solo una vez)") + "'; "
                + galleryDl + " oauth:pixiv\"\nend tell";
        try {
            if (Platform.WINDOWS) {
                ProcessBuilder pb = new ProcessBuilder(Platform.consoleCommand(true, Lang.t("InstaTray - conectar Pixiv"), galleryDl, "oauth:pixiv"));
                toolEnvironment(pb);
                pb.start();
                log("Opened a console for gallery-dl oauth:pixiv");
                return;
            }
            new ProcessBuilder("osascript", "-e", script).start();
            log("Opened Terminal for gallery-dl oauth:pixiv");
        } catch (IOException e) {
            log("ERROR opening Terminal: " + e);
        }
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

    /** Asks the libraries about URLs no rule knows (catch-all sites). */
    private final Probe probe = new Probe(InstaTray::libraryPython, toolEnvironment(), InstaTray::log);

    /** Catch-all links for the URLs: first whatever yt-dlp takes, then creator-platform posts gallery-dl takes. */
    List<Link> probeLibraries(List<String> urls, boolean withEmbeds) {
        return probe.links(urls, withEmbeds);
    }

    /** The interpreter in the Homebrew formula's shebang (its private venv), or the app's venv on Windows; or null. */
    private static String libraryPython(String tool) {
        if (Platform.WINDOWS) {
            Path python = Platform.venvPython(Platform.venv());
            return Files.isRegularFile(python) ? python.toString() : null;
        }
        return Probe.shebangPython(findBin(tool));
    }

    // ---------------------------------------------------------------- download

    private void download(Job job) {
        Link link = job.link;
        Site site = link.site();
        String name = label(link) + " " + Links.shortKey(link.key());
        Path outDir = outDir(site);
        job.cancelRequested = false;
        job.title = "";
        job.speed = "";
        job.eta = "";
        try {
            boolean gallery = site.engine() == Engine.GALLERY_DL;
            String tool = gallery ? "gallery-dl" : "yt-dlp";
            String bin = findBin(tool);
            if (bin == null && ensureTools()) bin = findBin(tool);
            if (bin == null) {
                fail(job, name, Lang.f("Falta %s y no se pudo instalar (mira el registro)", tool), true);
                return;
            }
            Path cookies = Cookies.forLink(DOWNLOADS, link);
            activeSite = site;
            // gallery-dl prints no percentage while it reads the post: until then the site-colored orb shows the arrow
            activePercent = gallery ? -1 : 0;
            job.status = Job.Status.DOWNLOADING;
            job.percent = gallery ? -1 : 0;
            updateTrayIcon();
            setStatus(Lang.key("Descargando %s…"), name);
            log("Downloading " + link.url() + (cookies != null ? " with cookies " + cookies : " without cookies"));

            String ffmpeg = findBin("ffmpeg");
            ToolPaths tools = new ToolPaths(findBin("yt-dlp"), findBin("gallery-dl"), ffmpeg);
            Fetch.Result result;
            boolean cancelledBefore;
            synchronized (fetchLock) {
                cancelledBefore = job.cancelRequested; // e.g. while the tools were being installed
                if (!cancelledBefore) fetching = Thread.currentThread();
            }
            if (cancelledBefore) {
                result = new Fetch.Result(Fetch.Outcome.CANCELLED, Fetch.CANCELLED, List.of(), "");
            } else {
                try {
                    result = Fetch.run(new Fetch.Request(link, outDir, null, cookies, null), tools, listener(job, name));
                } finally {
                    synchronized (fetchLock) {
                        fetching = null;
                    }
                    Thread.interrupted(); // a cancel that came as the download ended must not reach the queue's wait
                }
            }
            if (result.outcome() == Fetch.Outcome.CANCELLED) {
                log("Cleaned partials of " + Links.shortKey(link.key()) + " in " + outDir);
                job.destinations.clear();
                markCancelled(job);
                setStatus(Lang.key("Cancelado: %s"), name);
                return;
            }
            if (result.outcome() == Fetch.Outcome.DONE) {
                List<String> files = new ArrayList<>();
                for (Path f : result.files()) {
                    // gallery-dl keeps the site's own format (webm, gif…): make everything QuickTime-friendly
                    files.add(gallery ? toPlayableMp4(f.toString(), ffmpeg, job) : f.toString());
                }
                done.add(link.key());
                Files.writeString(HISTORY, link.key() + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                job.files = List.copyOf(files);
                job.bytes = fileSize(files.get(0));
                job.percent = 100;
                job.message = job.destinations.isEmpty() ? JobView.ALREADY_THERE // nothing was written: they were there
                        : files.size() > 1 ? Lang.f("%d videos en %s", files.size(), displayPath(outDir))
                        : Lang.f("Guardado en %s", displayPath(outDir) + "/" + Paths.get(files.get(0)).getFileName());
                job.status = Job.Status.DONE;
                setStatus(Lang.key("Listo: %s"), name); // success is silent: only failures notify
                return;
            }
            boolean permanent = result.outcome() == Fetch.Outcome.FAILED_PERMANENT;
            String reason = result.reason();
            String msg = !permanent ? reason : switch (reason) {
                case Fetch.NO_VIDEO_IN_POST -> Lang.t("Ese post no tiene video ni animación (solo imágenes)");
                case Fetch.PIXIV_LOGIN -> {
                    connectPixiv();
                    yield Lang.t("Pixiv pide conectar tu cuenta (solo una vez): sigue los pasos en la Terminal y reintenta");
                }
                case Fetch.LOGIN_NEEDED ->
                        Lang.f("%s pide sesión: exporta cookies nuevas de %s a Descargas", label(link), cookieSource(link));
                case Fetch.ANTI_BOT -> Lang.t("El sitio bloquea las descargas automáticas (Cloudflare)");
                // the server refuses anything but its own player: no workaround, the user can still open the page
                case Fetch.PAGE_ONLY -> Lang.t("El sitio no deja bajar este video fuera de su página (403): usa «Abrir página»");
                case Fetch.NO_VIDEO_ON_PAGE -> Lang.t("No se encontró ningún video en la página");
                case Fetch.NOT_FOUND -> Lang.t("No existe, se borró o es privado");
                case Fetch.LIVE -> Lang.t("Es una transmisión en vivo: no se graba");
                case Fetch.NO_VIDEO -> Lang.t("Ese post no tiene video");
                default -> reason;
            };
            // the page was most likely copied for its text: never notify
            fail(job, name, msg, permanent, Fetch.NO_VIDEO_ON_PAGE.equals(reason));
        } catch (Exception e) {
            log("ERROR " + e);
            job.addOutput("ERROR " + e);
            fail(job, name, String.valueOf(e.getMessage()));
        } finally {
            if (job.status != Job.Status.QUEUED) queued.remove(link.key());
            activePercent = -1;
            if (queued.isEmpty()) activeSite = null;
            updateTrayIcon();
            saveJobs();
        }
    }

    /** Guards {@link #fetching}: a cancel interrupts the worker only while it is inside Fetch.run. */
    private final Object fetchLock = new Object();
    /** The worker thread while it runs Fetch.run; null otherwise. */
    private Thread fetching;

    /** Where the tools are (for Fetch), with the app's tool environment. */
    record ToolPaths(String ytDlp, String galleryDl, String ffmpeg) implements Tools {
        @Override
        public Map<String, String> environment() {
            return toolEnvironment();
        }
    }

    /** Fetch's reports → the job, the tray icon, the tooltip, the status line and the log. */
    private Fetch.Listener listener(Job job, String name) {
        return new Fetch.Listener() {
            @Override
            public void progress(double fraction, String speed, String eta) {
                job.speed = speed;
                job.eta = eta;
                refreshTooltip();
                int pct = Progress.percent(fraction);
                if (pct >= 0 && pct != job.percent) {
                    job.percent = pct;
                    activePercent = pct;
                    updateTrayIcon();
                    if (pct % 10 == 0) setStatus(Lang.key("Descargando %s… %d%%"), name, pct);
                }
            }

            @Override
            public void title(String title) {
                job.title = title;
                refreshTooltip();
            }

            @Override
            public void line(String line) {
                InstaTray.log("  " + line);
                if (line.startsWith(Fetch.FOUND_PREFIX)) {
                    for (String l : describeFound(line.substring(Fetch.FOUND_PREFIX.length())).split("\n")) {
                        job.addOutput(l);
                    }
                } else {
                    job.addOutput(line);
                }
            }

            @Override
            public void destination(String path) {
                job.destinations.add(path);
            }

            @Override
            public void log(String message) {
                InstaTray.log(message);
            }
        };
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
            job.message = Lang.f("Falló (%s). Reintento %d/%d en %s", msg, job.attempt, maxRetries,
                    wait < 60 ? wait + " s" : wait / 60 + " min");
            job.addOutput("--- " + Lang.f("reintento automático %d/%d en %d s", job.attempt, maxRetries, wait) + " ---");
            log("Auto-retry " + job.attempt + "/" + maxRetries + " in " + wait + "s: " + job.link.url());
            setStatus(Lang.key("Reintentará %s en %d s"), name, wait);
            job.notBefore = System.currentTimeMillis() + wait * 1000;
            synchronized (pending) {
                pending.addLast(job);
                pending.notifyAll();
            }
            return;
        }
        job.status = Job.Status.FAILED;
        job.message = job.attempt == 0 ? msg : job.attempt == 1 ? Lang.f("%s (tras 1 reintento)", msg)
                : Lang.f("%s (tras %d reintentos)", msg, job.attempt);
        setStatus(Lang.key("Falló %s — mira Fallidas en la ventana"), name);
        if (silent) return;
        notify(Lang.f("No se pudo descargar %s", name),
                Lang.f("%s — abre la ventana (Fallidas) para ver qué pasó o reintentar", job.message));
    }


    /** "Generic · hls-1080 · m3u8_native · 1920x1080 · 734003200 · https://…" → readable line for "Detalles". */
    static String describeFound(String raw) {
        String[] f = raw.split(" · ", 6);
        if (f.length < 6) return Lang.f("Encontrado: %s", raw);
        String size = f[4].equals("NA") ? Lang.t("tamaño desconocido") : formatBytes(parseOr(f[4], 0));
        String how = f[2].startsWith("m3u8") ? Lang.t("HLS (fragmentos .ts/.m4s)")
                : f[2].startsWith("http_dash") || f[2].contains("dash") ? Lang.t("DASH (fragmentos)")
                : f[2].startsWith("http") ? Lang.t("archivo directo por HTTP") : f[2];
        return Lang.f("Encontrado por %s: formato %s, %s, %s, %s\n  origen: %s", f[0], f[1], how,
                f[3].equals("NA") ? Lang.t("resolución desconocida") : f[3], size, f[5]);
    }
    private static double parseOr(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Where the user should export cookies from, as shown in messages. */
    private static String cookieSource(Link link) {
        return link.site().isCatchAll() ? Links.baseDomain(Links.host(link.url())) : display(link.site());
    }

    /** A site's name in the current language (only the catch-alls have Spanish names). */
    static String display(Site site) {
        return Lang.t(folderName(site));
    }

    /**
     * Spanish name of a site: its subfolder on disk (never changes with the language) and the key of its English
     * name. Only the catch-alls differ from the library's label.
     */
    static String folderName(Site site) {
        return switch (site) {
            case OTHER, OTHER_GALLERY -> Lang.key("Otros sitios");
            case EMBED -> Lang.key("Video embebido");
            default -> site.label();
        };
    }

    /** What the user sees as a link's site: its name, or the extractor/category name for catch-alls. */
    static String label(Link link) {
        String name = link.libraryName();
        return name != null ? name : display(link.site());
    }

    /**
     * Windows: yt-dlp and gallery-dl go into the app's venv with pip (Python from winget if there is none);
     * ffmpeg and deno come from winget. With upgrade, everything already there is updated too.
     * Returns true when every requested tool is there afterwards.
     */
    private boolean windowsTools(List<String> tools, boolean upgrade, Consumer<String> say) {
        Path venv = Platform.venv();
        boolean ok = true;
        List<String> venvTools = tools.stream().filter(Platform::inVenv).toList();
        if (!venvTools.isEmpty()) {
            if (!Files.isRegularFile(Platform.venvPython(venv))) {
                String python = findPython();
                if (python == null) {
                    say.accept(Lang.t("Instalando Python… (la cola espera)"));
                    String winget = findWinget();
                    if (winget != null) runInstall(Platform.wingetInstall(winget, Platform.wingetId("python")), "winget install python");
                    python = findPython();
                }
                if (python == null) {
                    log("No Python to create the tools venv (winget " + (findWinget() == null ? "missing" : "failed") + ")");
                    return false;
                }
                say.accept(Lang.t("Preparando yt-dlp y gallery-dl…"));
                ok = runInstall(List.of(python, "-m", "venv", venv.toString()), "python -m venv " + venv);
            }
            List<String> need = upgrade ? venvTools : venvTools.stream().filter(t -> findBin(t) == null).toList();
            if (ok && !need.isEmpty()) {
                String names = String.join(", ", need);
                say.accept(upgrade ? Lang.f("Actualizando %s… (la cola espera)", names) : Lang.f("Instalando %s… (la cola espera)", names));
                ok = runInstall(Platform.pipInstall(venv, upgrade, need), "pip " + (upgrade ? "upgrade " : "install ") + need);
            }
        }
        List<String> wingetTools = tools.stream().filter(t -> Platform.wingetId(t) != null).toList();
        if (!wingetTools.isEmpty()) {
            String winget = findWinget();
            List<String> missing = wingetTools.stream().filter(t -> findBin(t) == null).toList();
            if (winget == null) {
                if (!missing.isEmpty()) log("winget is missing: cannot install " + missing);
                ok &= missing.isEmpty();
            } else {
                for (String t : wingetTools) {
                    boolean isMissing = missing.contains(t);
                    if (!isMissing && !upgrade) continue;
                    say.accept(isMissing ? Lang.f("Instalando %s… (la cola espera)", t) : Lang.f("Actualizando %s… (la cola espera)", t));
                    String id = Platform.wingetId(t);
                    // "no newer version" is a non-zero exit too, so an upgrade only fails if the tool is gone
                    runInstall(isMissing ? Platform.wingetInstall(winget, id) : Platform.wingetUpgrade(winget, id),
                            "winget " + (isMissing ? "install " : "upgrade ") + id);
                    ok &= findBin(t) != null;
                }
            }
        }
        return ok && tools.stream().allMatch(t -> findBin(t) != null);
    }

    /** winget.exe is an app-execution alias under WindowsApps: a link that ordinary file checks may not see. */
    private static String findWinget() {
        String bin = findBin("winget");
        if (bin != null) return bin;
        String local = System.getenv("LOCALAPPDATA");
        if (local == null) return null;
        Path alias = Paths.get(local, "Microsoft", "WindowsApps", "winget.exe");
        return Files.exists(alias, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? alias.toString() : null;
    }

    /** A real Python 3 to create the venv (never the Microsoft Store shortcut in WindowsApps). */
    private static String findPython() {
        for (Path p : Platform.pythonCandidates(Platform.HOME, System.getenv())) {
            if (Files.isRegularFile(p)) return p.toString();
        }
        return null;
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
        for (String t : TOOLS) {
            if (findBin(t) == null) missing.add(t);
        }
        if (missing.isEmpty()) return true;
        if (Platform.WINDOWS) {
            setStatus(Lang.key("Instalando %s…"), String.join(", ", missing));
            boolean ok = windowsTools(missing, false, line -> {});
            if (!ok) notify("InstaTray", Lang.f("No se pudo instalar %s (mira el registro)", String.join(", ", missing)));
            setStatus(ok ? Lang.key("Vigilando portapapeles") : Lang.key("Faltan herramientas — mira el registro"));
            return findBin("yt-dlp") != null;
        }
        String brew = findBrew();
        if (brew == null) {
            log("Missing " + missing + " and Homebrew is not installed");
            notify("InstaTray", Lang.f("Falta %s. Abre InstaTray de nuevo para instalarlo", String.join(", ", missing)));
            return false;
        }
        setStatus(Lang.key("Instalando %s…"), String.join(", ", missing));
        List<String> cmd = new ArrayList<>(brewCommand(brew, "install"));
        cmd.addAll(missing);
        boolean ok = runInstall(cmd, "install " + missing);
        if (!ok) notify("InstaTray", Lang.f("No se pudo instalar %s (mira el registro)", String.join(", ", missing)));
        setStatus(ok ? Lang.key("Vigilando portapapeles") : Lang.key("Faltan herramientas — mira el registro"));
        return findBin("yt-dlp") != null;
    }

    private static final String[] TOOLS = {"yt-dlp", "gallery-dl", "ffmpeg", "deno"};
    private final java.util.concurrent.atomic.AtomicBoolean installingTools = new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public List<String> missingTools() {
        return Arrays.stream(TOOLS).filter(t -> findBin(t) == null).toList();
    }

    /** The Ajustes button: install what is missing, then upgrade everything (the weekly upgrade only does yt-dlp). */
    @Override
    public void installTools(Consumer<String> progress, Runnable done) {
        Consumer<String> say = text -> EventQueue.invokeLater(() -> progress.accept(text));
        if (!installingTools.compareAndSet(false, true)) {
            EventQueue.invokeLater(done);
            return;
        }
        // no lock here: runInstall waits for the running download, which may itself call ensureTools()
        Thread t = new Thread(() -> {
            try {
                if (Platform.WINDOWS) {
                    boolean ok = windowsTools(List.of(TOOLS), true, say);
                    if (ok) saveSetting("ytdlpUpgradedAt", String.valueOf(System.currentTimeMillis()));
                    say.accept(ok && missingTools().isEmpty() ? Lang.t("Listo: todo instalado y al día ✓")
                            : Lang.t("No se pudo completar. Mira el registro (menú del ícono → Ver registro)."));
                    return;
                }
                String brew = findBrew();
                if (brew == null) {
                    HomebrewInstall hb = installHomebrew(say);
                    brew = hb.brew();
                    if (brew == null && hb.cancelled()) {
                        say.accept(Lang.t("No se instaló nada: hace falta tu contraseña para instalar Homebrew."));
                    } else if (brew == null) { // last resort: the official script in Terminal
                        openSetupCommand();
                        say.accept(Lang.t("No se pudo instalar Homebrew desde aquí: se abrió el instalador en Terminal."));
                    }
                }
                if (brew != null) {
                    boolean ok = true;
                    List<String> missing = missingTools();
                    if (!missing.isEmpty()) {
                        say.accept(Lang.f("Instalando %s… (la cola espera)", String.join(", ", missing)));
                        List<String> cmd = new ArrayList<>(brewCommand(brew, "install"));
                        cmd.addAll(missing);
                        ok = runInstall(cmd, "install " + missing);
                    }
                    ok &= upgradeTools(brew, List.of(TOOLS), say);
                    if (ok) saveSetting("ytdlpUpgradedAt", String.valueOf(System.currentTimeMillis()));
                    say.accept(ok && missingTools().isEmpty() ? Lang.t("Listo: todo instalado y al día ✓")
                            : Lang.t("No se pudo completar. Mira el registro (menú del ícono → Ver registro)."));
                }
            } finally {
                installingTools.set(false);
                EventQueue.invokeLater(done);
            }
        }, "install-tools");
        t.setDaemon(true);
        t.start();
    }

    record HomebrewInstall(String brew, boolean cancelled) {}

    /**
     * Installs Homebrew from its official .pkg (latest GitHub release). macOS asks for the administrator password
     * in its own dialog, so no Terminal is needed. Returns the brew path, or null and whether the user cancelled.
     */
    private static HomebrewInstall installHomebrew(Consumer<String> say) {
        Path pkg = null;
        try {
            say.accept(Lang.t("Descargando Homebrew…"));
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20)).build();
            String json = http.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/Homebrew/brew/releases/latest"))
                    .header("Accept", "application/vnd.github+json").build(), HttpResponse.BodyHandlers.ofString()).body();
            String url = homebrewPkgUrl(json);
            if (url == null) {
                log("Homebrew .pkg not found in the latest release");
                return new HomebrewInstall(null, false);
            }
            pkg = Files.createTempFile("Homebrew", ".pkg");
            HttpResponse<Path> got = http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofFile(pkg));
            if (got.statusCode() != 200) {
                log("Homebrew .pkg download -> HTTP " + got.statusCode());
                return new HomebrewInstall(null, false);
            }
            say.accept(Lang.t("Instalando Homebrew… macOS te pedirá tu contraseña."));
            String script = "do shell script \"/usr/sbin/installer -pkg \" & quoted form of \"" + esc(pkg.toString())
                    + "\" & \" -target /\" with administrator privileges";
            Process p = new ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = p.waitFor();
            log("Homebrew .pkg install -> exit " + exit + (out.isEmpty() ? "" : ": " + out));
            boolean cancelled = exit != 0 && (out.contains("-128") || out.toLowerCase(Locale.ROOT).contains("cancel"));
            return new HomebrewInstall(findBrew(), cancelled);
        } catch (Exception e) {
            log("ERROR installing Homebrew: " + e);
            return new HomebrewInstall(null, false);
        } finally {
            try {
                if (pkg != null) Files.deleteIfExists(pkg);
            } catch (IOException ignored) {
            }
        }
    }

    /** The Homebrew.pkg asset of a GitHub release JSON; only github.com downloads are accepted. */
    static String homebrewPkgUrl(String releaseJson) {
        Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"(https://github\\.com/Homebrew/brew/releases/download/[^\"]+\\.pkg)\"")
                .matcher(releaseJson);
        return m.find() ? m.group(1) : null;
    }

    /** setup.command sits next to InstaTray.jar in the bundle's Resources; it installs Homebrew (asks for the password). */
    private static void openSetupCommand() {
        try {
            Path jar = Paths.get(InstaTray.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path setup = jar.resolveSibling("setup.command");
            new ProcessBuilder("open", "-a", "Terminal", setup.toString()).start();
            log("Opened " + setup);
        } catch (Exception e) {
            log("ERROR opening setup.command: " + e);
        }
    }

    /** Sites change often and old yt-dlp versions stop working: upgrade it silently once a week. */
    private void upgradeYtDlpIfStale() {
        long last = Long.parseLong(loadSettings().getProperty("ytdlpUpgradedAt", "0"));
        if (System.currentTimeMillis() - last < UPGRADE_EVERY_MS) return;
        if (Platform.WINDOWS) {
            if (!Files.isRegularFile(Platform.venvPython(Platform.venv()))) return;
            if (runInstall(Platform.pipInstall(Platform.venv(), true, List.of("yt-dlp", "gallery-dl")), "pip upgrade yt-dlp gallery-dl")) {
                saveSetting("ytdlpUpgradedAt", String.valueOf(System.currentTimeMillis()));
            }
            return;
        }
        String brew = findBrew();
        if (brew == null || findBin("yt-dlp") == null) return;
        if (findBin("gallery-dl") == null) ensureTools();
        // both track sites that change often
        if (upgradeTools(brew, List.of("yt-dlp", "gallery-dl"), text -> {})) {
            saveSetting("ytdlpUpgradedAt", String.valueOf(System.currentTimeMillis()));
        }
    }

    /** How upgradeSteps runs each brew command; out receives its output lines. */
    interface BrewRunner {
        boolean run(List<String> cmd, boolean holdQueue, Consumer<String> out);
    }

    /**
     * Upgrades without stopping the queue for the slow part (a bottle can take many minutes): the index refresh and the
     * downloads run with the queue going, and it only waits for "brew upgrade", which then just swaps the files.
     * Nothing outdated: it never waits. Failing to download leaves the tools as they are.
     */
    static boolean upgradeSteps(List<String> brew, List<String> tools, BrewRunner run) {
        run.run(with(brew, "update"), false, line -> {}); // a failed refresh can still work from the last index
        List<String> outdated = new ArrayList<>();
        if (!run.run(with(brew, "outdated", "--quiet", "--formula"), false, line -> outdated.add(line.strip()))) return false;
        List<String> stale = tools.stream().filter(outdated::contains).toList();
        if (stale.isEmpty()) return true;
        // not "fetch --deps": it also brings build dependencies (llvm, rust… hundreds of MB that upgrade never uses)
        List<String> fetch = new ArrayList<>(stale);
        run.run(with(brew, "deps", stale), false, line -> {
            if (outdated.contains(line.strip())) fetch.add(line.strip()); // upgrade replaces these too
        });
        return run.run(with(brew, "fetch", fetch), false, line -> {})
                && run.run(with(brew, "upgrade", stale), true, line -> {});
    }

    private static List<String> with(List<String> brew, Object... args) {
        List<String> cmd = new ArrayList<>(brew);
        for (Object a : args) {
            if (a instanceof List<?> l) l.forEach(x -> cmd.add(x.toString()));
            else cmd.add(a.toString());
        }
        return cmd;
    }

    private boolean upgradeTools(String brew, List<String> tools, Consumer<String> say) {
        String names = String.join(", ", tools);
        say.accept(Lang.t("Buscando actualizaciones… (la cola sigue)"));
        return upgradeSteps(brewCommand(brew), tools, (cmd, hold, out) -> {
            String what = String.join(" ", cmd.subList(cmd.indexOf(brew) + 1, cmd.size()));
            if (cmd.contains("fetch")) say.accept(Lang.f("Bajando actualizaciones de %s… (la cola sigue)", names));
            if (hold) {
                say.accept(Lang.f("Actualizando %s… (la cola espera)", names));
                return runInstall(cmd, what);
            }
            return runLogged(cmd, what, out);
        });
    }

    /** Runs an install command (Homebrew, or winget/pip on Windows) with the queue on hold; output goes to the log. */
    private boolean runInstall(List<String> cmd, String what) {
        toolsBusy = true;
        updateTrayIcon();
        try {
            // let a download that is already running finish first
            while (current != null) Thread.sleep(1000);
            return runLogged(cmd, what, line -> {});
        } catch (InterruptedException e) {
            log("ERROR " + (Platform.WINDOWS ? "install " : "Homebrew ") + what + ": " + e);
            return false;
        } finally {
            toolsBusy = false;
            updateTrayIcon();
            synchronized (pending) {
                pending.notifyAll();
            }
        }
    }

    private boolean runLogged(List<String> cmd, String what, Consumer<String> out) {
        try {
            log((Platform.WINDOWS ? "Install: " : "Homebrew: ") + what);
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            toolEnvironment(pb);
            // brew's implicit refresh would be slow on top of the explicit "brew update" of upgradeSteps
            if (!Platform.WINDOWS) pb.environment().put("HOMEBREW_NO_AUTO_UPDATE", "1");
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log((Platform.WINDOWS ? "  > " : "  brew: ") + line);
                    out.accept(line);
                }
            }
            boolean finished = p.waitFor(20, TimeUnit.MINUTES);
            int exit = finished ? p.exitValue() : -1;
            log((Platform.WINDOWS ? "Install " : "Homebrew ") + what + " -> exit " + exit);
            return exit == 0;
        } catch (Exception e) {
            log("ERROR " + (Platform.WINDOWS ? "install " : "Homebrew ") + what + ": " + e);
            return false;
        }
    }

    private static String findBin(String name) {
        for (Path dir : Platform.binDirs()) {
            for (String file : Platform.exeNames(Platform.WINDOWS, name)) {
                Path p = dir.resolve(file);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
            }
        }
        return null;
    }

    /** PATH with the tool folders (yt-dlp finds ffmpeg and deno through it); UTF-8 output from Python on Windows. */
    private static void toolEnvironment(ProcessBuilder pb) {
        pb.environment().putAll(toolEnvironment());
    }

    private static Map<String, String> toolEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("PATH", Platform.pathEnv(Platform.binDirs(), Platform.WINDOWS));
        if (Platform.WINDOWS) { // the console code page cannot print "✔" or non-Latin titles
            env.put("PYTHONUTF8", "1");
            env.put("PYTHONIOENCODING", "utf-8");
        }
        return env;
    }

    // ---------------------------------------------------------------- helpers

    private void notify(String title, String text) {
        if (!notifications) { // the tray icon and the window still show progress
            log("Notification muted: " + title + " — " + text);
            return;
        }
        log("Notification: " + title + " — " + text);
        if (Platform.WINDOWS) { // the tray icon's balloon is a regular Windows notification
            trayIcon.displayMessage(title, text, TrayIcon.MessageType.INFO);
            return;
        }
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
            new ProcessBuilder(Platform.revealCommand(Platform.WINDOWS, file.toString())).start();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void openUrl(String url) {
        if (Platform.WINDOWS) {
            openWithDesktop(url);
            return;
        }
        try {
            new ProcessBuilder(Platform.openCommand(url)).start();
        } catch (IOException ignored) {
        }
    }

    /** Windows: a file or folder opens with its default app, anything else goes to the browser. */
    private static void openWithDesktop(String target) {
        try {
            if (Platform.isAbsolutePath(target)) Desktop.getDesktop().open(new java.io.File(target));
            else Desktop.getDesktop().browse(URI.create(target));
        } catch (Exception e) {
            log("ERROR opening " + target + ": " + e);
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
        if (Platform.WINDOWS) {
            openWithDesktop(p.toString());
            return;
        }
        try {
            new ProcessBuilder(Platform.openCommand(p.toString())).start();
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
