public class GlassTest {
    public static void main(String[] args) {
        // the shimmer sweeps left to right, then starts over: inside [-band, width] and periodic
        int w = 300;
        boolean inside = true, moves = false;
        double prev = Glass.shimmerX(0, w);
        for (long t = 0; t < Glass.SHIMMER_MS; t += 40) {
            double x = Glass.shimmerX(t, w);
            inside &= x >= -Glass.SHIMMER_BAND && x <= w;
            moves |= x > prev;
            prev = x;
        }
        T.check("shimmerInsideBar", inside, "");
        T.check("shimmerMovesRight", moves, "");
        T.check("shimmerRepeats", Glass.shimmerX(123, w) == Glass.shimmerX(123 + Glass.SHIMMER_MS, w), "");

        // the indeterminate block goes back and forth and never leaves the bar
        int block = 60;
        boolean fits = true;
        double min = Double.MAX_VALUE, max = -1;
        for (long t = 0; t < Glass.SWEEP_MS; t += 20) {
            double x = Glass.sweepX(t, w, block);
            fits &= x >= 0 && x + block <= w;
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        T.check("sweepStaysInBar", fits, "");
        T.check("sweepCoversBar", min < 5 && max > w - block - 5, min + ".." + max);
        T.check("sweepReturns", Math.abs(Glass.sweepX(0, w, block) - Glass.sweepX(Glass.SWEEP_MS, w, block)) < 1e-9, "");
        T.done();
    }
}
