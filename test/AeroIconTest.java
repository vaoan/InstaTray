import java.awt.image.BufferedImage;

public class AeroIconTest {
    public static void main(String[] args) {
        int s = AeroIcon.TRAY_PX;
        BufferedImage arrow = AeroIcon.renderTrayArrow(s);
        T.eq("trayArrowSize", s, arrow.getWidth());
        int minX = s, maxX = -1, minY = s, maxY = -1, notWhite = 0;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int argb = arrow.getRGB(x, y);
                if (argb >>> 24 == 0) continue;
                if ((argb & 0xFFFFFF) != 0xFFFFFF) notWhite++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        T.eq("trayArrowOnlyWhite", 0, notWhite); // no tile, gradient or shadow: macOS tints it as a template
        T.check("trayArrowNoTile", arrow.getRGB(0, 0) >>> 24 == 0 && arrow.getRGB(s - 1, s / 2) >>> 24 == 0, "edges not transparent");
        T.check("trayArrowCentered", Math.abs(minX - (s - 1 - maxX)) <= 1 && Math.abs(minY - (s - 1 - maxY)) <= 1,
                minX + "," + maxX + " " + minY + "," + maxY);
        T.check("trayArrowFillsLikeMenuBarGlyphs", maxY - minY >= 28 && maxY - minY <= 36, "height " + (maxY - minY));

        // the .app, window and busy icons keep their tile
        BufferedImage tile = AeroIcon.render(s);
        T.check("tileUnchanged", tile.getRGB(s / 2, s / 8) >>> 24 == 255 && (tile.getRGB(s / 8, s / 2) & 0xFFFFFF) != 0xFFFFFF, "");

        byte[] png = {1, 2, 3};
        T.eq("iconCommandTemplate", "icon\tAQID\ttemplate\n", NativeBar.iconCommand(png, true));
        T.eq("iconCommandPlain", "icon\tAQID\n", NativeBar.iconCommand(png, false));
        T.done();
    }
}
