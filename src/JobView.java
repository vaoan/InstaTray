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
        ALL("Todas"), DOWNLOADING("Descargando"), QUEUED("En cola"), DONE("Listas"), FAILED("Fallidas");

        final String label;

        Filter(String label) {
            this.label = label;
        }
    }

    enum Action {
        CANCEL("Cancelar"), TOP("Poner primero"), REMOVE("Quitar"), PLAY("Ver"), REVEAL("Mostrar en Finder"),
        OPEN_PAGE("Abrir página"), RETRY("Reintentar");

        final String label;

        Action(String label) {
            this.label = label;
        }
    }

    enum Tone { NORMAL, OK, BAD, MUTED }

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

    static Action secondary(Job j) {
        return switch (j.status) {
            case DOWNLOADING -> Action.CANCEL;
            case QUEUED -> Action.REMOVE;
            case DONE -> j.files.isEmpty() ? null : Action.REVEAL;
            case FAILED, CANCELLED -> Action.RETRY;
        };
    }

    /** Rows with the trash slot filled. */
    static boolean deletable(Job j) {
        return j.status == Job.Status.FAILED || j.status == Job.Status.CANCELLED;
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
                sb.append(" · ").append(j.percent >= 0 ? j.percent + " %" : "preparando…");
                if (!j.speed.isBlank()) sb.append(" · ").append(j.speed);
                if (!j.eta.isBlank()) sb.append(" · quedan ").append(j.eta);
                yield sb.toString();
            }
            case QUEUED -> j.notBefore > nowMs
                    ? site + " · reintento en " + Math.max(1, (j.notBefore - nowMs) / 1000) + " s"
                    : site + " · en cola (" + queuePosition + ".º)";
            case DONE -> "✓ Listo · " + ago(j.createdAt, nowMs) + (j.bytes >= 0 ? " · " + InstaTray.formatBytes(j.bytes) : "");
            case FAILED -> "✕ " + (j.message.isBlank() ? "Falló" : j.message);
            case CANCELLED -> "Cancelado";
        };
    }

    private static String ago(long then, long now) {
        long min = (now - then) / 60_000;
        if (min < 1) return "ahora";
        if (min < 60) return "hace " + min + " min";
        if (min < 24 * 60) return "hace " + min / 60 + " h";
        if (min < 48 * 60) return "ayer";
        return new SimpleDateFormat("dd/MM").format(new Date(then));
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
            case PAUSE -> paused ? "▶ Reanudar" : "⏸ Pausar";
            case CANCEL_ALL -> "✕ Cancelar todas";
            case RETRY_ALL -> "↻ Reintentar todas";
            case DELETE_ALL_FAILED -> "🗑 Borrar todas";
            case REMOVE_ALL_DONE -> "🗑 Quitar todas de la lista";
            case DETAILS -> detailsOpen ? "Detalles ▾" : "Detalles ▸";
        };
    }

    static String emptyText(Filter f) {
        return switch (f) {
            case ALL -> "Copia el enlace de un video y aparecerá aquí.";
            case DOWNLOADING -> "No se está descargando nada.";
            case QUEUED -> "No hay nada en cola.";
            case DONE -> "Todavía no hay descargas listas.";
            case FAILED -> "No hay descargas fallidas.";
        };
    }

    /** What is downloading and waiting (in queue order), then finished jobs, newest first. */
    static List<Job> ordered(List<Job> queue, List<Job> finished) {
        List<Job> list = new ArrayList<>(queue);
        finished.stream().sorted(Comparator.comparingLong((Job j) -> j.createdAt).reversed()).forEach(list::add);
        return list;
    }

    static final int RIGHT_INSET = 14, BUTTON_W = 150, BUTTON_H = 24, GAP = 6, TRASH_W = 26;

    /** [primary, secondary, trash] in a row of this size: the same column for every row. */
    static Rectangle[] buttonBounds(int rowWidth, int rowHeight) {
        int y = (rowHeight - BUTTON_H) / 2;
        int trashX = rowWidth - RIGHT_INSET - TRASH_W;
        int secondX = trashX - GAP - BUTTON_W;
        int firstX = secondX - GAP - BUTTON_W;
        return new Rectangle[]{
                new Rectangle(firstX, y, BUTTON_W, BUTTON_H),
                new Rectangle(secondX, y, BUTTON_W, BUTTON_H),
                new Rectangle(trashX, y, TRASH_W, BUTTON_H)};
    }
}
