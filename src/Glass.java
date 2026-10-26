import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The 2000s glass skin: WMP11's glossy black chrome in dark mode, Vista's light-blue glass in light mode.
 * Every surface is two gradients split at half height (the "glass line"). Also the progress-bar animation math.
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

    /** Fills shape with a top-half and a bottom-half gradient that meet at the glass line. */
    static void fill(Graphics2D g, Shape shape, Color top1, Color top2, Color bottom1, Color bottom2) {
        Rectangle2D b = shape.getBounds2D();
        float mid = (float) (b.getY() + b.getHeight() / 2);
        Shape old = g.getClip();
        g.clip(new Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight() / 2));
        g.setPaint(new GradientPaint(0, (float) b.getY(), top1, 0, mid, top2));
        g.fill(shape);
        g.setClip(old);
        g.clip(new Rectangle2D.Double(b.getX(), mid, b.getWidth(), b.getHeight() / 2 + 1));
        g.setPaint(new GradientPaint(0, mid, bottom1, 0, (float) b.getMaxY(), bottom2));
        g.fill(shape);
        g.setClip(old);
    }

    /** Toolbar / footer background, full width. */
    static void chrome(Graphics2D g, int w, int h, boolean dark, boolean glowLine) {
        Rectangle r = new Rectangle(0, 0, w, h);
        if (dark) {
            fill(g, r, rgb(0x3B3F45), rgb(0x1C1E22), rgb(0x050506), rgb(0x121316));
            g.setColor(Color.BLACK);
            g.fillRect(0, h - 1, w, 1);
            if (glowLine) { // WMP11's thin cyan light under the toolbar
                g.setPaint(new LinearGradientPaint(0, 0, w, 0, new float[]{0f, .5f, 1f},
                        new Color[]{new Color(0x36C8FF, true), rgba(0x36C8FF, 200), new Color(0x36C8FF, true)}));
                g.fillRect(0, h - 1, w, 1);
            }
        } else {
            fill(g, r, rgb(0xE3F1FD), rgb(0xCBE4FA), rgb(0xB4D7F6), rgb(0xD6EBFC));
            g.setColor(rgb(0x8DB2E3));
            g.fillRect(0, h - 1, w, 1);
        }
    }

    /** Flat-ish band under the toolbar (the filter chips sit on it). */
    static void band(Graphics2D g, int w, int h, boolean dark) {
        g.setPaint(dark ? new GradientPaint(0, 0, rgb(0x141518), 0, h, rgb(0x0B0B0D))
                : new GradientPaint(0, 0, rgb(0xF4F9FE), 0, h, rgb(0xE9F3FC)));
        g.fillRect(0, 0, w, h);
        if (!dark) {
            g.setColor(rgb(0xD5E5F5));
            g.fillRect(0, h - 1, w, 1);
        }
    }

    /** Status bar at the bottom. */
    static void footer(Graphics2D g, int w, int h, boolean dark) {
        Rectangle r = new Rectangle(0, 0, w, h);
        if (dark) {
            fill(g, r, rgb(0x25282D), rgb(0x111316), rgb(0x050506), rgb(0x0E0F12));
            g.setColor(Color.BLACK);
        } else {
            fill(g, r, rgb(0xF0F6FC), rgb(0xDCE9F6), rgb(0xCFE0F2), rgb(0xE1EDF9));
            g.setColor(rgb(0xA9C4E3));
        }
        g.fillRect(0, 0, w, 1);
    }

    /** Row background: a faint gradient in dark mode, Explorer's blue highlight when selected. */
    static void row(Graphics2D g, int w, int h, boolean dark, boolean selected) {
        if (selected) {
            RoundRectangle2D sel = new RoundRectangle2D.Double(4.5, 1.5, w - 9, h - 3, 6, 6);
            if (dark) {
                g.setPaint(new GradientPaint(0, 0, rgb(0x123A57), 0, h, rgb(0x0B2236)));
                g.fill(sel);
                g.setColor(rgb(0x2C9BE0));
            } else {
                g.setPaint(new GradientPaint(0, 0, rgb(0xF2F9FE), 0, h, rgb(0xD6F0FD)));
                g.fill(sel);
                g.setColor(rgb(0x84ACDD));
            }
            g.draw(sel);
            return;
        }
        if (dark) {
            g.setPaint(new GradientPaint(0, 0, rgb(0x111317), 0, h, rgb(0x0C0D10)));
            g.fillRect(0, 0, w, h);
        }
    }

    /** A glass button body (text and icon are drawn on top by the caller). */
    static void button(Graphics2D g, Rectangle r, boolean primary, boolean hover, boolean dark) {
        double arc = dark ? r.height : 6; // WMP11 pills, Vista's almost square buttons
        RoundRectangle2D shape = new RoundRectangle2D.Double(r.x + .5, r.y + .5, r.width - 1, r.height - 1, arc, arc);
        if (primary) {
            if (dark) {
                g.setColor(rgba(0x29B6FF, hover ? 110 : 60)); // glow
                g.fill(new RoundRectangle2D.Double(r.x - 1.5, r.y - 1.5, r.width + 3, r.height + 3, arc + 3, arc + 3));
                fill(g, shape, rgb(hover ? 0xB0EDFF : 0x8FE3FF), rgb(0x2AA7E8), rgb(0x0A6FB8), rgb(0x1C8FD6));
            } else {
                fill(g, shape, rgb(hover ? 0xC3E6FA : 0xA7D9F5), rgb(0x5FB5E8), rgb(0x2D8FD5), rgb(0x49A7E3));
            }
        } else if (dark) {
            fill(g, shape, rgb(hover ? 0x666D78 : 0x4D535C), rgb(0x2C3037), rgb(0x15171B), rgb(hover ? 0x2E343C : 0x22262C));
        } else if (hover) { // Vista's light-blue hover
            fill(g, shape, rgb(0xEAF6FD), rgb(0xD9F0FC), rgb(0xBEE6FD), rgb(0xA7D9F5));
        } else {
            fill(g, shape, rgb(0xF2F2F2), rgb(0xEBEBEB), rgb(0xDDDDDD), rgb(0xCFCFCF));
        }
        g.setColor(dark ? Color.BLACK : primary ? rgb(0x2C628B) : hover ? rgb(0x3C7FB1) : rgb(0x707070));
        g.draw(shape);
        // inner highlight along the top edge
        g.setColor(dark ? rgba(0xFFFFFF, primary ? 150 : 60) : rgba(0xFFFFFF, 200));
        g.draw(new RoundRectangle2D.Double(r.x + 1.5, r.y + 1.5, r.width - 3, r.height - 3, Math.max(0, arc - 2), Math.max(0, arc - 2)));
    }

    /** A filter chip body. */
    static void chip(Graphics2D g, Rectangle r, boolean on, boolean dark) {
        double arc = dark ? r.height : 6;
        RoundRectangle2D shape = new RoundRectangle2D.Double(r.x + .5, r.y + .5, r.width - 1, r.height - 1, arc, arc);
        if (dark) {
            if (on) {
                g.setColor(rgba(0x2BB5FF, 70));
                g.fill(new RoundRectangle2D.Double(r.x - 1, r.y - 1, r.width + 2, r.height + 2, arc + 2, arc + 2));
                fill(g, shape, rgb(0x77D9FF), rgb(0x1E95D8), rgb(0x0B63A6), rgb(0x1580C6));
            } else {
                fill(g, shape, rgb(0x2F333A), rgb(0x23262B), rgb(0x16181B), rgb(0x1C1F23));
            }
            g.setColor(Color.BLACK);
        } else {
            if (on) fill(g, shape, rgb(0xE5F4FD), rgb(0xC4E5F8), rgb(0xA9D8F5), rgb(0xC9E7F9));
            else fill(g, shape, rgb(0xFDFDFD), rgb(0xF4F4F4), rgb(0xEBEBEB), rgb(0xE6E6E6));
            g.setColor(on ? rgb(0x3C7FB1) : rgb(0xB5B5B5));
        }
        g.draw(shape);
    }

    /** Glossy progress bar with a moving shimmer: cyan (dark) or Vista green (light). */
    static void progress(Graphics2D g, Rectangle r, double fraction, long now, boolean dark) {
        RoundRectangle2D track = track(g, r, dark);
        int fw = (int) Math.round(r.width * Math.max(0, Math.min(1, fraction)));
        if (fw <= 0) return;
        Shape old = g.getClip();
        g.clip(track);
        Rectangle filled = new Rectangle(r.x, r.y, fw, r.height);
        paintFill(g, filled, dark);
        g.clip(filled);
        double x = r.x + shimmerX(now, fw);
        g.setPaint(new LinearGradientPaint((float) x, 0, (float) (x + SHIMMER_BAND), 0, new float[]{0f, .5f, 1f},
                new Color[]{rgba(0xFFFFFF, 0), rgba(0xFFFFFF, dark ? 150 : 170), rgba(0xFFFFFF, 0)}));
        g.fill(filled);
        g.setClip(old);
    }

    /** Progress bar whose end is unknown: a glossy block goes back and forth. */
    static void indeterminate(Graphics2D g, Rectangle r, long now, boolean dark) {
        RoundRectangle2D track = track(g, r, dark);
        int block = Math.max(30, r.width / 4);
        Shape old = g.getClip();
        g.clip(track);
        double x = r.x + sweepX(now, r.width, block);
        Rectangle b = new Rectangle((int) x, r.y, block, r.height);
        Area fade = new Area(b);
        g.setPaint(new LinearGradientPaint(b.x, 0, b.x + block, 0, new float[]{0f, .25f, .75f, 1f},
                new Color[]{rgba(dark ? 0x36BDF5 : 0x20B11A, 0), rgba(dark ? 0x36BDF5 : 0x20B11A, 255),
                        rgba(dark ? 0x36BDF5 : 0x20B11A, 255), rgba(dark ? 0x36BDF5 : 0x20B11A, 0)}));
        g.fill(fade);
        g.setPaint(new GradientPaint(0, r.y, rgba(0xFFFFFF, 150), 0, r.y + r.height / 2f, rgba(0xFFFFFF, 0)));
        g.fill(fade);
        g.setClip(old);
    }

    private static RoundRectangle2D track(Graphics2D g, Rectangle r, boolean dark) {
        RoundRectangle2D track = new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, dark ? r.height : 3, dark ? r.height : 3);
        if (dark) {
            g.setPaint(new GradientPaint(0, r.y, rgb(0x050608), 0, r.y + r.height, rgb(0x1D2026)));
            g.fill(track);
            g.setColor(Color.BLACK);
        } else {
            g.setPaint(new GradientPaint(0, r.y, rgb(0xF3F3F3), 0, r.y + r.height, rgb(0xDCDCDC)));
            g.fill(track);
            g.setColor(rgb(0xB2B2B2));
        }
        g.draw(track);
        return track;
    }

    private static void paintFill(Graphics2D g, Rectangle filled, boolean dark) {
        if (dark) fill(g, filled, rgb(0xA9ECFF), rgb(0x36BDF5), rgb(0x0D7CC4), rgb(0x2FA6E6));
        else fill(g, filled, rgb(0xCBF5C5), rgb(0x61D256), rgb(0x20B11A), rgb(0x4DD43F));
    }

    /** On/off switch: glass track, white pearl knob. */
    static void toggle(Graphics2D g, Rectangle r, boolean on, boolean dark) {
        RoundRectangle2D t = new RoundRectangle2D.Double(r.x + .5, r.y + .5, r.width - 1, r.height - 1, r.height, r.height);
        if (on) {
            if (dark) {
                g.setColor(rgba(0x2BB5FF, 80));
                g.fill(new RoundRectangle2D.Double(r.x - 1.5, r.y - 1.5, r.width + 3, r.height + 3, r.height + 3, r.height + 3));
            }
            fill(g, t, rgb(0x8FE3FF), rgb(0x2AA7E8), rgb(0x0A6FB8), rgb(0x1C8FD6));
        } else if (dark) {
            fill(g, t, rgb(0x2F333A), rgb(0x23262B), rgb(0x16181B), rgb(0x1C1F23));
        } else {
            fill(g, t, rgb(0xF2F2F2), rgb(0xEBEBEB), rgb(0xDDDDDD), rgb(0xCFCFCF));
        }
        g.setColor(dark ? Color.BLACK : on ? rgb(0x2C628B) : rgb(0x8A8A8A));
        g.draw(t);
        double k = r.height - 4;
        double kx = on ? r.x + r.width - k - 2 : r.x + 2;
        Ellipse2D knob = new Ellipse2D.Double(kx, r.y + 2, k, k);
        g.setPaint(new RadialGradientPaint((float) (kx + k * .4), (float) (r.y + 2 + k * .3), (float) k,
                new float[]{0f, 1f}, new Color[]{Color.WHITE, rgb(0xC9D3DC)}));
        g.fill(knob);
        g.setColor(rgba(0x000000, 90));
        g.draw(knob);
    }

    /** Text field background: dark inset pill or Vista's white box. */
    static void field(Graphics2D g, Rectangle r, boolean dark, boolean focused) {
        if (dark) {
            RoundRectangle2D s = new RoundRectangle2D.Double(r.x + .5, r.y + .5, r.width - 1, r.height - 1, r.height, r.height);
            g.setPaint(new GradientPaint(0, r.y, rgb(0x050608), 0, r.y + r.height, rgb(0x1A1D22)));
            g.fill(s);
            g.setColor(focused ? rgb(0x2C9BE0) : Color.BLACK);
            g.draw(s);
            g.setColor(rgba(0xFFFFFF, 26));
            g.drawLine(r.x + r.height / 2, r.y + r.height - 1, r.x + r.width - r.height / 2, r.y + r.height - 1);
        } else {
            RoundRectangle2D s = new RoundRectangle2D.Double(r.x + .5, r.y + .5, r.width - 1, r.height - 1, 4, 4);
            g.setColor(Color.WHITE);
            g.fill(s);
            g.setColor(focused ? rgb(0x3C7FB1) : rgb(0xA4BED4));
            g.draw(s);
        }
    }

    static Color rgb(int rgb) {
        return new Color(rgb);
    }

    static Color rgba(int rgb, int alpha) {
        return new Color((alpha << 24) | (rgb & 0xFFFFFF), true);
    }
}
