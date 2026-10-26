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
        T.eq("actions downloading", "null/OPEN_PAGE/CANCEL", acts(job(Job.Status.DOWNLOADING)));
        T.eq("actions queued", "TOP/OPEN_PAGE/REMOVE", acts(job(Job.Status.QUEUED)));
        T.eq("actions done", "PLAY/REVEAL/DELETE", acts(done));
        T.eq("actions failed", "OPEN_PAGE/RETRY/DELETE", acts(job(Job.Status.FAILED)));
        T.eq("actions cancelled", "OPEN_PAGE/RETRY/DELETE", acts(job(Job.Status.CANCELLED)));
        T.eq("doneWithoutFilesOffersPage", "OPEN_PAGE/RETRY/DELETE", acts(job(Job.Status.DONE)));
        // every row fills the plain and the icon column (only the downloading row lends the blue one to its bar)
        for (Job.Status st : Job.Status.values()) {
            Job j = job(st);
            T.check("gridComplete " + st, JobView.secondary(j) != null && JobView.dismiss(j) != null
                    && (JobView.primary(j) != null) == (st != Job.Status.DOWNLOADING), acts(j));
        }

        Job dl = job(Job.Status.DOWNLOADING);
        T.check("downloadingIsNotRemovableByKey", !JobView.removableByKey(dl), "");
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
            T.check("buttonBoundsAligned " + w, b.length == 3 && b[0].width == b[1].width && b[0].width == 150
                    && b[1].x + b[1].width == w - 14 && b[0].y == b[1].y && b[1].y == b[2].y
                    && b[2].x + b[2].width + 6 == b[0].x && b[0].x + b[0].width + 6 == b[1].x
                    && b[0].height == 24 && b[0].y == (52 - 24) / 2, java.util.Arrays.toString(b));
        }
        // barMode: downloading always shows a bar; unknown percent sweeps instead of filling
        Job known = job(Job.Status.DOWNLOADING);
        known.percent = 10;
        T.eq("barDeterminate", JobView.Bar.DETERMINATE, JobView.barMode(known));
        T.eq("barIndeterminate", JobView.Bar.INDETERMINATE, JobView.barMode(job(Job.Status.DOWNLOADING)));
        T.eq("barNoneWhenQueued", JobView.Bar.NONE, JobView.barMode(job(Job.Status.QUEUED)));
        T.eq("barNoneWhenDone", JobView.Bar.NONE, JobView.barMode(done));

        // English: labels, ordinal, relative time (Lang is Spanish until forced)
        Lang.force(true);
        T.eq("enActionLabel", "Move to top", JobView.Action.TOP.text());
        T.eq("enFilterLabel", "Failed", JobView.Filter.FAILED.text());
        Job waitingEn = job(Job.Status.QUEUED);
        T.check("enQueuedOrdinal", JobView.subtitle(waitingEn, 2, System.currentTimeMillis()).endsWith("queued (2nd)"),
                JobView.subtitle(waitingEn, 2, System.currentTimeMillis()));
        Job doneEn = job(Job.Status.DONE);
        T.check("enAgo", JobView.subtitle(doneEn, 0, doneEn.createdAt + 5 * 60_000).contains("Done · 5 min ago"),
                JobView.subtitle(doneEn, 0, doneEn.createdAt + 5 * 60_000));
        T.eq("enFooter", "Details ▸", JobView.footerLabel(JobView.FooterAction.DETAILS, false, false));
        Lang.force(false);
        T.eq("esAgain", "Poner primero", JobView.Action.TOP.text());

        T.check("buttonBoundsIgnoreText", java.util.Arrays.equals(JobView.buttonBounds(700, 52), JobView.buttonBounds(700, 52)), "");
        T.done();
    }

    static String acts(Job j) {
        return JobView.primary(j) + "/" + JobView.secondary(j) + "/" + JobView.dismiss(j);
    }
}
