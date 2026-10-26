import java.awt.Rectangle;
import java.util.List;

public class JobViewTest {
    static final long NOW = 1_800_000_000_000L;

    static Job job(Job.Status status) {
        InstaTray.Link link = new InstaTray.Link(InstaTray.Site.YOUTUBE, "yt:" + status, "https://youtu.be/" + status);
        Job j = new Job(link, NOW);
        j.status = status;
        return j;
    }

    public static void main(String[] args) {
        // filtersPartitionStatuses
        for (Job.Status st : Job.Status.values()) {
            Job j = job(st);
            int others = 0;
            for (JobView.Filter f : JobView.Filter.values()) if (f != JobView.Filter.ALL && JobView.matches(f, j)) others++;
            T.check("filtersPartitionStatuses " + st, JobView.matches(JobView.Filter.ALL, j) && others == 1, "others=" + others);
        }
        T.check("cancelledIsFailed", JobView.matches(JobView.Filter.FAILED, job(Job.Status.CANCELLED)), "");

        // actionsPerStatus
        Job done = job(Job.Status.DONE);
        done.files = List.of("/v/a.mp4");
        T.eq("actions downloading", "null/CANCEL", acts(job(Job.Status.DOWNLOADING)));
        T.eq("actions queued", "TOP/REMOVE", acts(job(Job.Status.QUEUED)));
        T.eq("actions done", "PLAY/REVEAL", acts(done));
        T.eq("actions failed", "OPEN_PAGE/RETRY", acts(job(Job.Status.FAILED)));
        T.eq("actions cancelled", "OPEN_PAGE/RETRY", acts(job(Job.Status.CANCELLED)));
        T.eq("doneWithoutFilesOffersPage", "OPEN_PAGE/null", acts(job(Job.Status.DONE)));

        Job dl = job(Job.Status.DOWNLOADING);
        T.check("downloadingIsNotDeletable", !JobView.deletable(dl) && !JobView.removableByKey(dl), "");
        T.check("failedIsDeletable", JobView.deletable(job(Job.Status.FAILED)) && !JobView.deletable(done), "");
        T.check("removableByKey", JobView.removableByKey(done) && JobView.removableByKey(job(Job.Status.QUEUED)), "");

        // titles and subtitles
        T.eq("titleFallsBackToUrl", "https://youtu.be/DONE", JobView.title(done));
        done.title = "Pan casero";
        T.eq("titleUsesTitle", "Pan casero", JobView.title(done));

        dl.percent = 45;
        dl.speed = "2.1 MB/s";
        dl.eta = "0:12";
        T.eq("subtitleDownloading", "YouTube · 45 % · 2.1 MB/s · quedan 0:12", JobView.subtitle(dl, 0, NOW));
        Job starting = job(Job.Status.DOWNLOADING);
        T.eq("subtitleDownloadingUnknown", "YouTube · preparando…", JobView.subtitle(starting, 0, NOW));

        Job q = job(Job.Status.QUEUED);
        T.eq("subtitleQueued", "YouTube · en cola (2.º)", JobView.subtitle(q, 2, NOW));
        q.notBefore = NOW + 12_000;
        T.eq("subtitleRetryWait", "YouTube · reintento en 12 s", JobView.subtitle(q, 2, NOW));

        Job d = new Job(done.link, NOW - 5 * 60_000);
        d.status = Job.Status.DONE;
        d.bytes = 12_582_912;
        T.eq("subtitleDoneRelative", "✓ Listo · hace 5 min · 12.0 MB", JobView.subtitle(d, 0, NOW));
        d.bytes = -1;
        T.eq("subtitleDoneNoSize", "✓ Listo · hace 5 min", JobView.subtitle(d, 0, NOW));
        Job justNow = new Job(done.link, NOW - 20_000);
        justNow.status = Job.Status.DONE;
        T.eq("subtitleDoneNow", "✓ Listo · ahora", JobView.subtitle(justNow, 0, NOW));
        Job hours = new Job(done.link, NOW - 3 * 3_600_000L);
        hours.status = Job.Status.DONE;
        T.eq("subtitleDoneHours", "✓ Listo · hace 3 h", JobView.subtitle(hours, 0, NOW));

        Job f = job(Job.Status.FAILED);
        T.eq("subtitleFailedBlankMessage", "✕ Falló", JobView.subtitle(f, 0, NOW));
        f.message = "No deja bajar este video fuera de su página";
        T.eq("subtitleFailed", "✕ No deja bajar este video fuera de su página", JobView.subtitle(f, 0, NOW));
        T.eq("subtitleCancelled", "Cancelado", JobView.subtitle(job(Job.Status.CANCELLED), 0, NOW));

        T.eq("tones", List.of(JobView.Tone.NORMAL, JobView.Tone.NORMAL, JobView.Tone.OK, JobView.Tone.BAD, JobView.Tone.MUTED),
                List.of(JobView.tone(job(Job.Status.QUEUED)), JobView.tone(dl), JobView.tone(done), JobView.tone(f),
                        JobView.tone(job(Job.Status.CANCELLED))));

        // footerPerFilter
        var P = JobView.FooterAction.PAUSE;
        var D = JobView.FooterAction.DETAILS;
        T.eq("footer all", List.of(P, D), JobView.footer(JobView.Filter.ALL));
        T.eq("footer downloading", List.of(P, JobView.FooterAction.CANCEL_ALL, D), JobView.footer(JobView.Filter.DOWNLOADING));
        T.eq("footer queued", List.of(P, JobView.FooterAction.CANCEL_ALL, D), JobView.footer(JobView.Filter.QUEUED));
        T.eq("footer failed", List.of(JobView.FooterAction.RETRY_ALL, JobView.FooterAction.DELETE_ALL_FAILED, D),
                JobView.footer(JobView.Filter.FAILED));
        T.eq("footer done", List.of(JobView.FooterAction.REMOVE_ALL_DONE, D), JobView.footer(JobView.Filter.DONE));
        T.eq("emptyText all", "Copia el enlace de un video y aparecerá aquí.", JobView.emptyText(JobView.Filter.ALL));
        T.eq("emptyText failed", "No hay descargas fallidas.", JobView.emptyText(JobView.Filter.FAILED));

        // orderedQueueThenNewest
        Job old = new Job(done.link, NOW - 100), mid = new Job(done.link, NOW - 50), cur = job(Job.Status.DOWNLOADING),
                wait = job(Job.Status.QUEUED);
        List<Job> ordered = JobView.ordered(List.of(cur, wait), List.of(old, mid));
        T.check("orderedQueueThenNewest", ordered.size() == 4 && ordered.get(0) == cur && ordered.get(1) == wait
                && ordered.get(2) == mid && ordered.get(3) == old, ordered.toString());

        // buttonBoundsAligned / buttonBoundsIgnoreText
        for (int w : new int[]{600, 900}) {
            Rectangle[] b = JobView.buttonBounds(w, 52);
            T.check("buttonBoundsAligned " + w, b.length == 3 && b[0].width == b[1].width && b[0].width == 134
                    && b[2].x + b[2].width == w - 14 && b[0].y == b[1].y && b[1].y == b[2].y
                    && b[0].x + b[0].width + 6 == b[1].x && b[1].x + b[1].width + 6 == b[2].x
                    && b[0].height == 24 && b[0].y == (52 - 24) / 2, java.util.Arrays.toString(b));
        }
        T.check("buttonBoundsIgnoreText", java.util.Arrays.equals(JobView.buttonBounds(700, 52), JobView.buttonBounds(700, 52)), "");
        T.done();
    }

    static String acts(Job j) {
        return JobView.primary(j) + "/" + JobView.secondary(j);
    }
}
