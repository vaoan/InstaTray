public class JobTsvTest {
    public static void main(String[] args) {
        InstaTray.Link link = new InstaTray.Link(InstaTray.Site.YOUTUBE, "yt:abc", "https://youtu.be/abc");

        Job job = new Job(link, 1_700_000_000_000L);
        job.status = Job.Status.DONE;
        job.title = "Pan casero";
        T.eq("titleRoundTrips", "Pan casero", Job.fromLine(job.toLine()).title);

        job.title = "a\tb\nc";
        T.eq("titleWithTabsRoundTrips", "a b c", Job.fromLine(job.toLine()).title);
        T.eq("titleWithTabsRoundTrips columns", 9, job.toLine().split("\t", -1).length);

        // lines written before the title column existed
        String seven = "1700000000000\tyt:abc\thttps://youtu.be/abc\tYOUTUBE\tDONE\t\t/v/a.mp4";
        String eight = seven + "\thttps://page.example/x";
        Job old7 = Job.fromLine(seven), old8 = Job.fromLine(eight);
        T.check("oldLinesStillLoad", old7 != null && old8 != null && old7.title.isEmpty() && old8.title.isEmpty()
                && "https://page.example/x".equals(old8.page), "7=" + old7 + " 8=" + old8);
        T.done();
    }
}
