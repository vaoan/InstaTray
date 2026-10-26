import java.awt.Rectangle;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * What the downloads window shows for a job: which filter it belongs to, its buttons, its grey status line.
 * Pure functions with no Swing state, so they are testable headless.
 */
final class JobView {

    private JobView() {
    }

    enum Filter {
        ALL(Lang.key("Todas")), DOWNLOADING(Lang.key("Descargando")), QUEUED(Lang.key("En cola")), DONE(Lang.key("Listas")),
        FAILED(Lang.key("Fallidas"));

        final String label; // Spanish key

        Filter(String label) {
            this.label = label;
        }

        String text() {
            return Lang.t(label);
        }
    }

    enum Action {
        CANCEL(Lang.key("Cancelar")), TOP(Lang.key("Poner primero")), REMOVE(Lang.key("Quitar")), PLAY(Lang.key("Ver")),
        REVEAL(Platform.revealLabel(Platform.WINDOWS)), OPEN_PAGE(Lang.key("Abrir página")), RETRY(Lang.key("Reintentar")),
        DELETE(Lang.key("Borrar de la lista"));

        final String label; // Spanish key: paintButton translates it

        Action(String label) {
            this.label = label;
        }

        String text() {
            return Lang.t(label);
        }
    }

    enum Tone { NORMAL, OK, BAD, MUTED }

    enum Bar { NONE, DETERMINATE, INDETERMINATE }

    enum FooterAction { PAUSE, CANCEL_ALL, RETRY_ALL, DELETE_ALL_FAILED, REMOVE_ALL_DONE, DETAILS }

    static boolean matches(Filter f, Job j) {
        return switch (f) {
            case ALL -> true;
            case DOWNLOADING -> j.status == Job.Status.DOWNLOADING;
            case QUEUED -> j.status == Job.Status.QUEUED;
            case DONE -> j.status == Job.Status.DONE;
            case FAILED -> j.status == Job.Status.FAILED || j.status == Job.Status.CANCELLED;
        };
    }

    /** The blue button, or null for an empty slot. */
    static Action primary(Job j) {
        return switch (j.status) {
            case DOWNLOADING -> null;
            case QUEUED -> Action.TOP;
            case DONE -> j.files.isEmpty() ? Action.OPEN_PAGE : Action.PLAY;
            case FAILED, CANCELLED -> Action.OPEN_PAGE;
        };
    }

    /** The plain button; every row has one, so the columns read as a complete grid. */
    static Action secondary(Job j) {
        return switch (j.status) {
            case DOWNLOADING, QUEUED -> Action.OPEN_PAGE;
            case DONE -> j.files.isEmpty() ? Action.RETRY : Action.REVEAL;
            case FAILED, CANCELLED -> Action.RETRY;
        };
    }

    /** The icon-only last column, on every row: ✕ takes a job out of the queue, 🗑 takes a finished one off the list. */
    static Action dismiss(Job j) {
        return switch (j.status) {
            case DOWNLOADING -> Action.CANCEL;
            case QUEUED -> Action.REMOVE;
            case DONE, FAILED, CANCELLED -> Action.DELETE;
        };
    }

    /** Rows the Delete key may take out of the list: everything but the download in progress. */
    static boolean removableByKey(Job j) {
        return j.status != Job.Status.DOWNLOADING;
    }

    static String title(Job j) {
        return j.title.isBlank() ? j.link.url() : j.title;
    }

    /** queuePosition: 1-based place among waiting jobs (only used for QUEUED). */
    static String subtitle(Job j, int queuePosition, long nowMs) {
        String site = j.link.label();
        return switch (j.status) {
            case DOWNLOADING -> {
                StringBuilder sb = new StringBuilder(site);
                sb.append(" · ").append(j.percent >= 0 ? j.percent + (Lang.isEnglish() ? "%" : " %") : Lang.t("preparando…"));
                if (!j.speed.isBlank()) sb.append(" · ").append(j.speed);
                if (!j.eta.isBlank()) sb.append(" · ").append(Lang.f("quedan %s", j.eta));
                yield sb.toString();
            }
            case QUEUED -> j.notBefore > nowMs
                    ? site + " · " + Lang.f("reintento en %d s", Math.max(1, (j.notBefore - nowMs) / 1000))
                    : site + " · " + Lang.f("en cola (%s)", Lang.ordinal(queuePosition));
            case DONE -> "✓ " + Lang.t("Listo") + " · " + ago(j.createdAt, nowMs)
                    + (j.bytes >= 0 ? " · " + InstaTray.formatBytes(j.bytes) : "");
            // the reason was saved in the language of the moment; t() converts it if it is a whole table entry
            case FAILED -> "✕ " + (j.message.isBlank() ? Lang.t("Falló") : Lang.t(j.message));
            case CANCELLED -> Lang.t("Cancelado");
        };
    }

    private static String ago(long then, long now) {
        long min = (now - then) / 60_000;
        if (min < 1) return Lang.t("ahora");
        if (min < 60) return Lang.f("hace %d min", min);
        if (min < 24 * 60) return Lang.f("hace %d h", min / 60);
        if (min < 48 * 60) return Lang.t("ayer");
        if (Lang.isEnglish()) return new SimpleDateFormat("MMM d", java.util.Locale.ENGLISH).format(new Date(then));
        return new SimpleDateFormat("dd/MM").format(new Date(then));
    }

    /** Downloading rows always show a bar; before yt-dlp reports a percentage it sweeps back and forth. */
    static Bar barMode(Job j) {
        if (j.status != Job.Status.DOWNLOADING) return Bar.NONE;
        return j.percent >= 0 ? Bar.DETERMINATE : Bar.INDETERMINATE;
    }

    static Tone tone(Job j) {
        return switch (j.status) {
            case DONE -> Tone.OK;
            case FAILED -> Tone.BAD;
            case CANCELLED -> Tone.MUTED;
            default -> Tone.NORMAL;
        };
    }

    static List<FooterAction> footer(Filter f) {
        return switch (f) {
            case ALL -> List.of(FooterAction.PAUSE, FooterAction.DETAILS);
            case DOWNLOADING, QUEUED -> List.of(FooterAction.PAUSE, FooterAction.CANCEL_ALL, FooterAction.DETAILS);
            case FAILED -> List.of(FooterAction.RETRY_ALL, FooterAction.DELETE_ALL_FAILED, FooterAction.DETAILS);
            case DONE -> List.of(FooterAction.REMOVE_ALL_DONE, FooterAction.DETAILS);
        };
    }

    static String footerLabel(FooterAction a, boolean paused, boolean detailsOpen) {
        return switch (a) {
            case PAUSE -> paused ? "▶ " + Lang.t("Reanudar") : "⏸ " + Lang.t("Pausar");
            case CANCEL_ALL -> "✕ " + Lang.t("Cancelar todas");
            case RETRY_ALL -> "↻ " + Lang.t("Reintentar todas");
            case DELETE_ALL_FAILED -> "🗑 " + Lang.t("Borrar todas");
            case REMOVE_ALL_DONE -> "🗑 " + Lang.t("Quitar todas de la lista");
            case DETAILS -> Lang.t("Detalles") + (detailsOpen ? " ▾" : " ▸");
        };
    }

    static String emptyText(Filter f) {
        return switch (f) {
            case ALL -> Lang.t("Copia el enlace de un video y aparecerá aquí.");
            case DOWNLOADING -> Lang.t("No se está descargando nada.");
            case QUEUED -> Lang.t("No hay nada en cola.");
            case DONE -> Lang.t("Todavía no hay descargas listas.");
            case FAILED -> Lang.t("No hay descargas fallidas.");
        };
    }

    /** What is downloading and waiting (in queue order), then finished jobs, newest first. */
    static List<Job> ordered(List<Job> queue, List<Job> finished) {
        List<Job> list = new ArrayList<>(queue);
        finished.stream().sorted(Comparator.comparingLong((Job j) -> j.createdAt).reversed()).forEach(list::add);
        return list;
    }

    static final int RIGHT_INSET = 14, BUTTON_W = 150, BUTTON_H = 24, GAP = 6, TRASH_W = 26;

    /**
     * [primary, secondary, trash] in a row of this size: the same column for every row. Left to right: icon, blue,
     * plain, so the two wide buttons end flush with the window edge like the toolbar's (user asked for it).
     */
    static Rectangle[] buttonBounds(int rowWidth, int rowHeight) {
        int y = (rowHeight - BUTTON_H) / 2;
        int secondX = rowWidth - RIGHT_INSET - BUTTON_W;
        int firstX = secondX - GAP - BUTTON_W;
        int trashX = firstX - GAP - TRASH_W;
        return new Rectangle[]{
                new Rectangle(firstX, y, BUTTON_W, BUTTON_H),
                new Rectangle(secondX, y, BUTTON_W, BUTTON_H),
                new Rectangle(trashX, y, TRASH_W, BUTTON_H)};
    }
}
