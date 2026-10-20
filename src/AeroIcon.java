import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws glossy "Aero" style spheres (Windows Vista look) for the tray and the .app icon.
 * Idle: aqua glass orb with a white download arrow (the project's own icon).
 * Busy: the orb takes the color of the site being downloaded and shows the pending count.
 */
public final class AeroIcon {

    /** Base colors of the orb, painted as a diagonal gradient (bottom-left -> top-right). */
    public enum Palette {
        IDLE(0x9EF0FF, 0x38B6F0, 0x1279CF, 0x0A4596),
        // Weighted toward the yellow/orange and violet/blue ends so it never reads as plain red at 22pt
        INSTAGRAM(0xFFE27A, 0xFFB238, 0xF56A2C, 0xC1358A, 0x7B3BD0, 0x4C5FE8, 0x3A4FD9),
        // Single flat red: clearly different from the multicolor Instagram orb
        YOUTUBE(0xFF3B30, 0xEE1C12, 0xD40D06, 0xB00000),
        X(0xB8C0C8, 0x5C646E, 0x2A2F35, 0x08090B),
        TWITCH(0xD9C2FF, 0xA970FF, 0x7B3FE4, 0x3E1A8C),
        // Dark orb with a cyan edge bottom-left and a pink edge top-right
        TIKTOK(0x25F4EE, 0x1B2A30, 0x0B0B0E, 0x2A1018, 0xFE2C55),
        REDDIT(0xFFC29A, 0xFF8A45, 0xFF4500, 0xB23000),
        FACEBOOK(0x9CC2FF, 0x4A8CF7, 0x1877F2, 0x0A47A3),
        PINTEREST(0xFFC7D4, 0xF2668A, 0xD1185A, 0x80093A),
        BLUESKY(0xDDF0FF, 0x93CCFF, 0x4FA3FF, 0x1E6FE0),
        KICK(0xD9FFB8, 0x8CF75A, 0x53FC18, 0x2A8F00),
        VIMEO(0xB5F5EA, 0x4FD6C8, 0x1AA6A6, 0x0A6060),
        DAILYMOTION(0xA9B3FF, 0x5664E8, 0x2734A8, 0x0E1555),
        RUMBLE(0xDDEFB0, 0xA3C955, 0x6E9B2A, 0x3A5810),
        STREAMABLE(0xB0E4FF, 0x4DB5F5, 0x1690D8, 0x0A5A8C),
        IMGUR(0xB3F5D2, 0x45D894, 0x1BB76E, 0x0A663C),
        // Pearl: almost white glass
        NINEGAG(0xFFFFFF, 0xE6E6E6, 0xB3B3B3, 0x6E6E6E),
        TUMBLR(0x9DB0C9, 0x56708F, 0x2C4466, 0x0B1A30),
        LINKEDIN(0xA6CCF2, 0x4791D9, 0x0A66C2, 0x053A70),
        SNAPCHAT(0xFFFFC2, 0xFFF870, 0xFFE600, 0xC9A800),
        BILIBILI(0xFFD6E4, 0xFFA3C0, 0xFB7299, 0xC23D6B);

        final Color[] colors;

        Palette(int... rgb) {
            colors = new Color[rgb.length];
            for (int i = 0; i < rgb.length; i++) colors[i] = new Color(rgb[i]);
        }
    }

    private AeroIcon() {}

    /** The project icon (idle state). */
    public static BufferedImage render(int size) {
        return render(size, Palette.IDLE, null);
    }

    /**
     * @param badge text drawn in the center (e.g. pending count), or null for the idle arrow glyph
     */
    public static BufferedImage render(int size, Palette palette, String badge) {
        return render(size, palette, badge, -1f);
    }

    /**
     * @param progress 0..1 fills the orb with saturated color from the bottom up over a washed-out
     *                 version of the same palette; negative means no progress (fully saturated)
     */
    public static BufferedImage render(int size, Palette palette, String badge, float progress) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        float s = size;
        boolean small = size < 64;
        float pad = small ? s * 0.04f : s * 0.08f;
        float d = s - pad * 2;
        float x = pad, y = small ? pad : pad * 0.8f;
        Ellipse2D sphere = new Ellipse2D.Float(x, y, d, d);

        if (!small) drawDropShadow(g, s, y, d);

        // Base gradient
        Color[] cs = palette.colors;
        float[] stops = new float[cs.length];
        for (int i = 0; i < cs.length; i++) stops[i] = i / (float) (cs.length - 1);
        Point2D from = new Point2D.Float(x, y + d), to = new Point2D.Float(x + d, y);
        if (progress < 0) {
            g.setPaint(new LinearGradientPaint(from, to, stops, cs));
            g.fill(sphere);
        } else {
            float p = Math.min(1f, progress);
            Color[] washed = new Color[cs.length];
            for (int i = 0; i < cs.length; i++) washed[i] = washOut(cs[i]);
            g.setPaint(new LinearGradientPaint(from, to, stops, washed));
            g.fill(sphere);

            float level = y + d * (1 - p);
            Area liquid = new Area(sphere);
            liquid.intersect(new Area(new Rectangle2D.Float(x, level, d, d * p + 1)));
            g.setPaint(new LinearGradientPaint(from, to, stops, cs));
            g.fill(liquid);

            // Bright meniscus line at the fill level
            if (p > 0.01f && p < 0.99f) {
                Graphics2D gl = (Graphics2D) g.create();
                gl.setClip(sphere);
                gl.setStroke(new BasicStroke(Math.max(1f, d * 0.03f)));
                gl.setColor(new Color(255, 255, 255, 200));
                gl.draw(new Line2D.Float(x, level, x + d, level));
                gl.dispose();
            }
        }

        // 3D shading: dark rim toward the edges
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(x + d * 0.5f, y + d * 0.42f), d * 0.62f,
                new float[]{0f, 0.70f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 25), new Color(10, 10, 40, 140)}));
        g.fill(sphere);

        // Inner glow from the bottom (light passing through the glass)
        g.setClip(sphere);
        g.setPaint(new RadialGradientPaint(
                new Point2D.Float(x + d * 0.5f, y + d * 1.02f), d * 0.55f,
                new float[]{0f, 1f},
                new Color[]{new Color(255, 255, 255, 150), new Color(255, 255, 255, 0)}));
        g.fill(sphere);
        g.setClip(null);

        // Idle glyph sits inside the glass, under the reflection
        if (badge == null) drawArrow(g, x, y, d, small);

        // Top glossy highlight (classic Aero "lens" reflection)
        float hw = d * 0.74f, hh = d * 0.50f;
        Ellipse2D gloss = new Ellipse2D.Float(x + (d - hw) / 2, y + d * 0.04f, hw, hh);
        int glossTop = badge == null ? 235 : 200;
        g.setPaint(new GradientPaint(
                0, (float) gloss.getY(), new Color(255, 255, 255, glossTop),
                0, (float) (gloss.getY() + hh), new Color(255, 255, 255, 14)));
        g.fill(gloss);

        // Small specular sparkle
        if (!small) {
            float sp = d * 0.10f;
            Point2D sc = new Point2D.Float(x + d * 0.30f, y + d * 0.20f);
            g.setPaint(new RadialGradientPaint(sc, sp,
                    new float[]{0f, 1f},
                    new Color[]{new Color(255, 255, 255, 255), new Color(255, 255, 255, 0)}));
            g.fill(new Ellipse2D.Double(sc.getX() - sp, sc.getY() - sp, sp * 2, sp * 2));
        }

        // Thin glass rim
        g.setStroke(new BasicStroke(Math.max(1f, d * 0.012f)));
        g.setPaint(new GradientPaint(0, y, new Color(255, 255, 255, 170),
                0, y + d, new Color(255, 255, 255, 40)));
        float inset = Math.max(0.5f, d * 0.006f);
        g.draw(new Ellipse2D.Float(x + inset, y + inset, d - inset * 2, d - inset * 2));

        // Count goes on top of the reflection so it stays readable in the menu bar
        if (badge != null) drawBadge(g, badge, x, y, d);

        g.dispose();
        return img;
    }

    /** Desaturated, lighter version of a color for the "empty" part of the orb. */
    private static Color washOut(Color c) {
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        return Color.getHSBColor(hsb[0], hsb[1] * 0.22f, Math.min(1f, hsb[2] * 0.55f + 0.30f));
    }

    private static void drawDropShadow(Graphics2D g, float s, float y, float d) {
        float sw = d * 0.80f, sh = d * 0.14f;
        Point2D c = new Point2D.Float(s / 2, y + d + sh * 0.15f);
        Graphics2D gs = (Graphics2D) g.create();
        gs.setPaint(new RadialGradientPaint(c, sw / 2,
                new float[]{0f, 1f},
                new Color[]{new Color(0, 0, 0, 90), new Color(0, 0, 0, 0)}));
        gs.translate(c.getX(), c.getY());
        gs.scale(1, sh / sw);
        gs.translate(-c.getX(), -c.getY());
        gs.fill(new Ellipse2D.Double(c.getX() - sw / 2, c.getY() - sw / 2, sw, sw));
        gs.dispose();
    }

    /** White download arrow dropping into a tray line, with a soft glassy shadow. */
    private static void drawArrow(Graphics2D g, float x, float y, float d, boolean small) {
        float cx = x + d / 2;
        float shaft = d * 0.13f;
        float headW = d * 0.42f;
        Path2D arrow = new Path2D.Float();
        arrow.moveTo(cx - shaft / 2, y + d * 0.22f);
        arrow.lineTo(cx + shaft / 2, y + d * 0.22f);
        arrow.lineTo(cx + shaft / 2, y + d * 0.47f);
        arrow.lineTo(cx + headW / 2, y + d * 0.47f);
        arrow.lineTo(cx, y + d * 0.70f);
        arrow.lineTo(cx - headW / 2, y + d * 0.47f);
        arrow.lineTo(cx - shaft / 2, y + d * 0.47f);
        arrow.closePath();
        float trayW = d * 0.50f, trayH = d * (small ? 0.09f : 0.07f);
        RoundRectangle2D tray = new RoundRectangle2D.Float(cx - trayW / 2, y + d * 0.76f, trayW, trayH, trayH, trayH);

        Area glyph = new Area(arrow);
        glyph.add(new Area(tray));

        Graphics2D gg = (Graphics2D) g.create();
        gg.translate(0, d * 0.025f);
        gg.setColor(new Color(0, 20, 60, 110));
        gg.fill(glyph);
        gg.dispose();

        g.setPaint(new GradientPaint(0, y + d * 0.22f, new Color(255, 255, 255, 255),
                0, y + d * 0.85f, new Color(215, 240, 255, 255)));
        g.fill(glyph);
    }

    /** Bold white number with a dark outline, centered in the orb. */
    /**
     * Bold white number with a dark outline, centered in the orb. A trailing "%" (download progress) is drawn as a
     * small superscript next to the number, so the digits keep their full size and the queue count ("3") never
     * looks like a percentage ("45%").
     */
    private static void drawBadge(Graphics2D g, String text, float x, float y, float d) {
        boolean percent = text.endsWith("%");
        String digits = percent ? text.substring(0, text.length() - 1) : text;
        float fontSize = d * (digits.length() >= 2 ? 0.56f : 0.68f);
        Font font = new Font("Helvetica Neue", Font.BOLD, Math.round(fontSize));
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), digits);
        Rectangle2D b = gv.getVisualBounds();

        Area shape;
        if (!percent) {
            shape = new Area(gv.getOutline((float) (x + d / 2 - b.getCenterX()), (float) (y + d / 2 - b.getCenterY())));
        } else {
            Font small = font.deriveFont(fontSize * 0.46f);
            GlyphVector pv = small.createGlyphVector(g.getFontRenderContext(), "%");
            Rectangle2D pb = pv.getVisualBounds();
            float gap = d * 0.025f;
            float groupW = (float) (b.getWidth() + gap + pb.getWidth());
            float left = x + d / 2 - groupW / 2;
            float nx = (float) (left - b.getX());
            float ny = (float) (y + d / 2 - b.getCenterY());
            shape = new Area(gv.getOutline(nx, ny));
            // superscript: "%" top aligned with the top of the digits
            float px = (float) (left + b.getWidth() + gap - pb.getX());
            float py = (float) (ny + b.getY() - pb.getY());
            shape.add(new Area(pv.getOutline(px, py)));
        }

        g.setStroke(new BasicStroke(Math.max(1.5f, d * 0.08f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0, 0, 0, 150));
        g.draw(shape);
        g.setColor(Color.WHITE);
        g.fill(shape);
    }

    /**
     * iconutil PNG set: AeroIcon <iconset-dir>
     * Preview sheet of every state: AeroIcon --preview <file.png>
     */
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--preview")) {
            writePreview(new File(args[1]));
            return;
        }
        if (args[0].equals("--palettes")) {
            writePalettes(new File(args[1]));
            return;
        }
        File dir = new File(args[0]);
        dir.mkdirs();
        int[] sizes = {16, 32, 128, 256, 512};
        for (int sz : sizes) {
            ImageIO.write(render(sz), "png", new File(dir, "icon_" + sz + "x" + sz + ".png"));
            ImageIO.write(render(sz * 2), "png", new File(dir, "icon_" + sz + "x" + sz + "@2x.png"));
        }
    }

    /** Every site color at menu-bar size and large, with its name, to check they are told apart. */
    private static void writePalettes(File out) throws Exception {
        Palette[] all = Palette.values();
        int cols = 7, cell = 150, tiny = 44;
        int rows = (all.length + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * cell, rows * (cell + 30), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0xEDEDED));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font("Helvetica Neue", Font.PLAIN, 13));
        for (int i = 0; i < all.length; i++) {
            int x = (i % cols) * cell, y = (i / cols) * (cell + 30);
            g.drawImage(render(96, all[i], all[i] == Palette.IDLE ? null : "45%", 0.45f), x + 8, y + 8, null);
            g.setColor(new Color(0x2B2B2B));
            g.fillRoundRect(x + 104, y + 30, tiny + 8, tiny + 8, 8, 8);
            g.drawImage(render(tiny, all[i], all[i] == Palette.IDLE ? null : "3", -1f), x + 108, y + 34, null);
            g.setColor(Color.DARK_GRAY);
            g.drawString(all[i].name(), x + 10, y + cell + 12);
        }
        g.dispose();
        ImageIO.write(sheet, "png", out);
    }

    private static void writePreview(File out) throws Exception {
        Object[][] states = {
                // single download -> badge is its percent; several -> badge is the pending count
                {Palette.IDLE, null, -1f}, {Palette.INSTAGRAM, "7%", 0.07f}, {Palette.YOUTUBE, "45%", 0.45f},
                {Palette.YOUTUBE, "3", 0.45f}, {Palette.X, "99%", 0.99f}, {Palette.TWITCH, "9+", 0.6f},
        };
        int big = 200, tiny = 44, gap = 30;
        int w = states.length * (big + gap) + gap, h = big + tiny + gap * 3 + 22;
        BufferedImage sheet = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(0xEDEDED));
        g.fillRect(0, 0, w, h);
        // menu-bar strip to judge the 22pt size
        g.setColor(new Color(0x2B2B2B));
        g.fillRect(0, big + gap * 2, w, tiny + 12);
        for (int i = 0; i < states.length; i++) {
            Palette p = (Palette) states[i][0];
            String badge = (String) states[i][1];
            float progress = (Float) states[i][2];
            int x = gap + i * (big + gap);
            g.drawImage(render(big, p, badge, progress), x, gap, null);
            g.drawImage(render(tiny, p, badge, progress), x + (big - tiny) / 2, big + gap * 2 + 6, null);
        }
        g.dispose();
        ImageIO.write(sheet, "png", out);
    }
}
