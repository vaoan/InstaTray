import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The Liquid Glass skin (macOS 26 style): flat surfaces with soft vertical light, pill-shaped controls, the system
 * accent blue, and a thin specular rim on every glass element (bright along the top edge, fading downwards).
 * Also the progress-bar animation math.
 */
final class Glass {

    private Glass() {
    }

    /** One shimmer pass across a progress bar, and the width of its highlight. */
    static final long SHIMMER_MS = 1800;
    static final int SHIMMER_BAND = 80;
    /** One back-and-forth of the indeterminate block. */
    static final long SWEEP_MS = 2000;

    /** Left edge of the shimmer highlight at time t: from -band (hidden left) towards the right end, then again. */
    static double shimmerX(long t, int width) {
        double phase = Math.floorMod(t, SHIMMER_MS) / (double) SHIMMER_MS;
        return -SHIMMER_BAND + (width + SHIMMER_BAND) * phase;
    }

    /** Left edge of the indeterminate block at time t: left to right and back, always inside the bar. */
    static double sweepX(long t, int width, int block) {
        double phase = Math.floorMod(t, SWEEP_MS) / (double) SWEEP_MS;
        double tri = phase < 0.5 ? phase * 2 : (1 - phase) * 2;
        return tri * Math.max(0, width - block);
    }

    // ---------------------------------------------------------------- surfaces

    static final int ACCENT_LIGHT = 0x007AFF, ACCENT_DARK = 0x0A84FF;

    static Color accent(boolean dark) {
        return rgb(dark ? ACCENT_DARK : ACCENT_LIGHT);
    }

    /** Toolbar background, full width: frosted, a highlight on its top edge and a hairline under it. */
    static void chrome(Graphics2D g, int w, int h, boolean dark) {
        g.setPaint(dark ? new GradientPaint(0, 0, rgb(0x2C2C30), 0, h, rgb(0x232326))
                : new GradientPaint(0, 0, rgb(0xFDFDFF), 0, h, rgb(0xF4F4F8)));
        g.fillRect(0, 0, w, h);
        g.setColor(rgba(0xFFFFFF, dark ? 22 : 255));
        g.fillRect(0, 0, w, 1);
        g.setColor(dark ? rgba(0x000000, 150) : rgba(0x000000, 24));
        g.fillRect(0, h - 1, w, 1);
    }

    /** Band under the toolbar (the filter chips sit on it): the plain window colour. */
    static void band(Graphics2D g, int w, int h, boolean dark) {
        g.setColor(dark ? rgb(0x1A1A1C) : rgb(0xF2F2F7));
        g.fillRect(0, 0, w, h);
    }

    /** Status bar at the bottom: frosted like the toolbar, hairline on top. */
    static void footer(Graphics2D g, int w, int h, boolean dark) {
        g.setPaint(dark ? new GradientPaint(0, 0, rgb(0x262629), 0, h, rgb(0x202023))
                : new GradientPaint(0, 0, rgb(0xFAFAFC), 0, h, rgb(0xF2F2F6)));
        g.fillRect(0, 0, w, h);
        g.setColor(dark ? rgba(0x000000, 150) : rgba(0x000000, 24));
        g.fillRect(0, 0, w, 1);
        g.setColor(rgba(0xFFFFFF, dark ? 14 : 200));
        g.fillRect(0, 1, w, 1);
    }

    /** Row background: nothing when idle, a rounded accent tint when selected. */
    static void row(Graphics2D g, int w, int h, boolean dark, boolean selected) {
        if (!selected) return;
        RoundRectangle2D sel = new RoundRectangle2D.Double(6.5, 2.5, w - 13, h - 5, 14, 14);
        g.setColor(rgba(dark ? ACCENT_DARK : ACCENT_LIGHT, dark ? 56 : 30));
        g.fill(sel);
        g.setColor(rgba(dark ? ACCENT_DARK : ACCENT_LIGHT, dark ? 110 : 70));
        g.draw(sel);
    }

    /** Light passing through a glass shape: a soft sheen on its top half and a rim bright on top, faint below. */
    static void sheen(Graphics2D g, RoundRectangle2D shape, int sheenAlpha, int rimTop, int rimBottom) {
        Rectangle2D b = shape.getBounds2D();
        g.setPaint(new GradientPaint(0, (float) b.getY(), rgba(0xFFFFFF, sheenAlpha),
                0, (float) b.getCenterY(), rgba(0xFFFFFF, 0)));
        g.fill(shape);
        RoundRectangle2D rim = new RoundRectangle2D.Double(b.getX() + .5, b.getY() + .5, b.getWidth() - 1, b.getHeight() - 1,
                Math.max(0, shape.getArcWidth() - 1), Math.max(0, shape.getArcHeight() - 1));
        g.setPaint(new GradientPaint(0, (float) b.getY(), rgba(0xFFFFFF, rimTop),
                0, (float) b.getMaxY(), rgba(0xFFFFFF, rimBottom)));
        g.draw(rim);
    }

    private static RoundRectangle2D pill(Rectangle r) {
        return new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, r.height, r.height);
    }

    /** Soft shadow under a raised control. */
    private static void shadow(Graphics2D g, Rectangle r, int alpha) {
        g.setColor(rgba(0x000000, alpha));
        g.fill(new RoundRectangle2D.Double(r.x, r.y + 1, r.width, r.height, r.height, r.height));
    }

    /** A pill button body (text and icon are drawn on top by the caller): accent glass or clear glass. */
    static void button(Graphics2D g, Rectangle r, boolean primary, boolean hover, boolean dark) {
        Rectangle body = new Rectangle(r.x + 1, r.y + 1, r.width - 2, r.height - 3);
        RoundRectangle2D shape = pill(body);
        if (primary) {
            shadow(g, body, dark ? 90 : 40);
            int base = dark ? (hover ? 0x2B95FF : ACCENT_DARK) : (hover ? 0x1A88FF : ACCENT_LIGHT);
            g.setColor(rgb(base));
            g.fill(shape);
            sheen(g, shape, 60, 150, 20);
        } else if (dark) {
            shadow(g, body, 70);
            g.setColor(rgba(0xFFFFFF, hover ? 40 : 24));
            g.fill(shape);
            sheen(g, shape, 18, 70, 8);
        } else {
            shadow(g, body, 22);
            g.setColor(hover ? rgb(0xF6F6F9) : Color.WHITE);
            g.fill(shape);
            g.setColor(rgba(0x000000, hover ? 40 : 28));
            g.draw(new RoundRectangle2D.Double(body.x + .5, body.y + .5, body.width - 1, body.height - 1, body.height, body.height));
        }
    }

    /** A filter chip body: accent glass when on, a faint tint when off. */
    static void chip(Graphics2D g, Rectangle r, boolean on, boolean dark) {
        RoundRectangle2D shape = pill(r);
        if (on) {
            g.setColor(accent(dark));
            g.fill(shape);
            sheen(g, shape, 50, 140, 20);
        } else {
            g.setColor(dark ? rgba(0xFFFFFF, 20) : rgba(0x000000, 13));
            g.fill(shape);
        }
    }

    /** Thin pill progress bar, accent gradient with a soft moving shimmer. */
    static void progress(Graphics2D g, Rectangle r, double fraction, long now, boolean dark) {
        RoundRectangle2D track = track(g, r, dark);
        int fw = (int) Math.round(r.width * Math.max(0, Math.min(1, fraction)));
        if (fw <= 0) return;
        Shape old = g.getClip();
        g.clip(track);
        RoundRectangle2D filled = new RoundRectangle2D.Double(r.x, r.y, Math.max(fw, r.height), r.height, r.height, r.height);
        g.setPaint(new GradientPaint(r.x, 0, rgb(dark ? 0x64D2FF : 0x5AC8FA), r.x + Math.max(fw, 1), 0, accent(dark)));
        g.fill(filled);
        g.setPaint(new GradientPaint(0, r.y, rgba(0xFFFFFF, 90), 0, r.y + r.height / 2f, rgba(0xFFFFFF, 0)));
        g.fill(filled);
        g.clip(filled);
        double x = r.x + shimmerX(now, fw);
        g.setPaint(new LinearGradientPaint((float) x, 0, (float) (x + SHIMMER_BAND), 0, new float[]{0f, .5f, 1f},
                new Color[]{rgba(0xFFFFFF, 0), rgba(0xFFFFFF, dark ? 80 : 110), rgba(0xFFFFFF, 0)}));
        g.fill(filled);
        g.setClip(old);
    }

    /** Progress bar whose end is unknown: an accent capsule glides back and forth. */
    static void indeterminate(Graphics2D g, Rectangle r, long now, boolean dark) {
        RoundRectangle2D track = track(g, r, dark);
        int block = Math.max(30, r.width / 4);
        Shape old = g.getClip();
        g.clip(track);
        double x = r.x + sweepX(now, r.width, block);
        RoundRectangle2D b = new RoundRectangle2D.Double(x, r.y, block, r.height, r.height, r.height);
        int a = dark ? ACCENT_DARK : ACCENT_LIGHT;
        g.setPaint(new LinearGradientPaint((float) x, 0, (float) x + block, 0, new float[]{0f, .3f, .7f, 1f},
                new Color[]{rgba(a, 60), rgba(a, 255), rgba(a, 255), rgba(a, 60)}));
        g.fill(b);
        g.setPaint(new GradientPaint(0, r.y, rgba(0xFFFFFF, 90), 0, r.y + r.height / 2f, rgba(0xFFFFFF, 0)));
        g.fill(b);
        g.setClip(old);
    }

    private static RoundRectangle2D track(Graphics2D g, Rectangle r, boolean dark) {
        RoundRectangle2D track = pill(r);
        g.setColor(dark ? rgba(0xFFFFFF, 26) : rgba(0x000000, 18));
        g.fill(track);
        return track;
    }

    /** On/off switch: accent track when on, white knob with a soft shadow. */
    static void toggle(Graphics2D g, Rectangle r, boolean on, boolean dark) {
        RoundRectangle2D t = pill(r);
        g.setColor(on ? accent(dark) : dark ? rgba(0xFFFFFF, 46) : rgba(0x000000, 30));
        g.fill(t);
        if (on) sheen(g, t, 40, 110, 15);
        double k = r.height - 4;
        double kx = on ? r.x + r.width - k - 2 : r.x + 2;
        g.setColor(rgba(0x000000, 50));
        g.fill(new Ellipse2D.Double(kx, r.y + 2.8, k, k));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(kx, r.y + 2, k, k));
    }

    /** Text field background: a pill of clear glass, with the accent focus ring. */
    static void field(Graphics2D g, Rectangle r, boolean dark, boolean focused) {
        RoundRectangle2D s = new RoundRectangle2D.Double(r.x + 1, r.y + 1, r.width - 2, r.height - 2, r.height - 2, r.height - 2);
        g.setColor(dark ? rgba(0xFFFFFF, 18) : Color.WHITE);
        g.fill(s);
        if (dark) {
            sheen(g, s, 0, 40, 10);
        } else {
            g.setColor(rgba(0x000000, 30));
            g.draw(new RoundRectangle2D.Double(r.x + 1.5, r.y + 1.5, r.width - 3, r.height - 3, r.height - 3, r.height - 3));
        }
        if (focused) {
            Stroke old = g.getStroke();
            g.setStroke(new BasicStroke(2f));
            g.setColor(rgba(dark ? ACCENT_DARK : ACCENT_LIGHT, 170));
            g.draw(new RoundRectangle2D.Double(r.x + 1, r.y + 1, r.width - 2, r.height - 2, r.height - 2, r.height - 2));
            g.setStroke(old);
        }
    }

    /** Scrollbar thumb: a thin translucent capsule. */
    static void thumb(Graphics2D g, Rectangle r, boolean dark) {
        double w = Math.min(6, r.width - 4);
        g.setColor(dark ? rgba(0xFFFFFF, 70) : rgba(0x000000, 60));
        g.fill(new RoundRectangle2D.Double(r.x + (r.width - w) / 2, r.y + 2, w, r.height - 4, w, w));
    }

    static Color rgb(int rgb) {
        return new Color(rgb);
    }

    static Color rgba(int rgb, int alpha) {
        return new Color((alpha << 24) | (rgb & 0xFFFFFF), true);
    }
}
