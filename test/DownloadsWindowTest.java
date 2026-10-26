import java.util.List;

public class DownloadsWindowTest {
    static Job job(String id, Job.Status status) {
        Job j = new Job(new InstaTray.Link(InstaTray.Site.YOUTUBE, "yt:" + id, "https://youtu.be/" + id), System.currentTimeMillis());
        j.status = status;
        return j;
    }

    public static void main(String[] args) {
        Job dl = job("a", Job.Status.DOWNLOADING), q = job("b", Job.Status.QUEUED);

        DownloadsWindow.Rows rows = new DownloadsWindow.Rows();
        rows.reload(List.of(dl, q), JobView.Filter.ALL);
        rows.list.setSelectedIndex(0);
        dl.status = Job.Status.DONE; // finishes: now listed after the queued one
        rows.reload(List.of(q, dl), JobView.Filter.ALL);
        T.check("selectionSurvivesReload", rows.list.getSelectedValue() == dl && rows.list.getSelectedIndex() == 1,
                "selected " + rows.list.getSelectedValuesList());

        Job dl2 = job("c", Job.Status.DOWNLOADING), other = job("d", Job.Status.DOWNLOADING);
        rows.reload(List.of(dl2, other), JobView.Filter.DOWNLOADING);
        rows.list.setSelectedIndex(0);
        dl2.status = Job.Status.DONE; // leaves the "Descargando" filter
        rows.reload(List.of(other, dl2), JobView.Filter.DOWNLOADING);
        T.check("selectionDropsWhenFilteredOut", rows.list.isSelectionEmpty() && rows.model.size() == 1,
                "selected " + rows.list.getSelectedValuesList() + " size " + rows.model.size());

        rows.reload(List.of(dl, q), JobView.Filter.FAILED);
        T.check("filterApplied", rows.model.isEmpty(), "size " + rows.model.size());
        T.done();
    }
}
