import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * What the downloads window and the Ajustes dialog need from the app. InstaTray implements it; the windows'
 * --preview mode uses a stub so they can be rendered without a running app.
 */
interface WindowHost {

    /** What is downloading now followed by what waits, in order. */
    List<Job> queueSnapshot();

    List<Job> finishedSnapshot();

    /** Downloads every link in text (like the clipboard, re-downloads allowed); runs onNothingFound on the EDT. */
    void submit(String text, Runnable onNothingFound);

    /** delta -1 = up, +1 = down, Integer.MIN_VALUE = to the top. */
    void move(Job job, int delta);

    void cancel(Job job);

    /** Cancels the download in progress and takes the waiting ones out of the list. */
    void cancelAll();

    void retry(Job job);

    /** Takes a waiting or finished job out of the list; the download in progress is never removed. */
    void remove(Job job);

    void removeFinished(Predicate<Job> which);

    boolean isPaused();

    void setPaused(boolean on);

    boolean isWatching();

    void setWatching(boolean on);

    /** "Documentos/Videos" (+ "/<red social>" with subfolders). */
    String videosFolderLabel();

    void openVideosFolder();

    void revealInFinder(Path file);

    void openUrl(String url);

    void copyToClipboardSilently(String text);

    boolean subfolders();

    void setSubfolders(boolean on);

    boolean embeds();

    void setEmbeds(boolean on);

    boolean notifications();

    void setNotifications(boolean on);

    int maxRetries();

    void setMaxRetries(int n);

    int retryDelaySec();

    void setRetryDelaySec(int sec);

    void chooseVideosDir(java.awt.Window owner);

    /** Cookie files found in Downloads, computed off the EDT (it may trigger the privacy prompt). */
    void cookiesSummary(Consumer<String> onEdt);

    /** Download tools not found (yt-dlp, gallery-dl, ffmpeg, deno), empty when all are there. */
    List<String> missingTools();

    /**
     * Installs what is missing and upgrades all the tools with Homebrew (or opens the Homebrew installer in Terminal
     * when there is no Homebrew). Runs in the background; progress and done run on the EDT.
     */
    void installTools(Consumer<String> progress, Runnable done);

    boolean detailsOpen();

    void setDetailsOpen(boolean open);
}
