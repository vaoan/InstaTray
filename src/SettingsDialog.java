import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * "Ajustes": the options that are set once (folder, clipboard, retries, notifications) and the cookie status.
 * Laid out like macOS System Settings: a rounded card per section, one row per option with its title and grey help on
 * the left and its control (switch, menu, button) right-aligned, so every control sits in one column.
 */
final class SettingsDialog {

    private static final Integer[] RETRIES = {0, 1, 2, 3, 4, 5};
    private static final Integer[] DELAYS = {10, 30, 60, 300};
    private static final Font BASE = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final Font HELP = BASE.deriveFont(11f);
    private static final Font HEADER = BASE.deriveFont(Font.BOLD, 11f);
    private static final int WIDTH = 520, PAD = 14, CONTROL_W = 120; // menus and "Cambiar…" share one width

    private final WindowHost host;
    private final JDialog dialog;
    private final JLabel folder = new JLabel();
    private final DownloadsWindow.Switch subfolders = new DownloadsWindow.Switch();
    private final DownloadsWindow.Switch watch = new DownloadsWindow.Switch();
    private final DownloadsWindow.Switch embeds = new DownloadsWindow.Switch();
    private final Choice<Integer> retries = new Choice<>(RETRIES, n -> n == 1 ? "1 vez" : n + " veces");
    private final Choice<Integer> delay = new Choice<>(DELAYS, sec -> sec < 60 ? sec + " s" : sec / 60 + " min");
    private final DownloadsWindow.Switch notify = new DownloadsWindow.Switch();
    private final JLabel cookies = new JLabel();
    private final JLabel tools = new JLabel();
    private final DownloadsWindow.PillButton install;
    private boolean loading;

    SettingsDialog(Frame owner, WindowHost host) {
        this.host = host;
        dialog = new JDialog(owner, "Ajustes", true);
        install = new DownloadsWindow.PillButton("Instalar o actualizar", null, true, this::installTools);
        dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(new EmptyBorder(6, 20, 20, 20));

        DownloadsWindow.PillButton change = new DownloadsWindow.PillButton("Cambiar…", null, false, () -> {
            host.chooseVideosDir(dialog);
            load();
        });
        change.setPreferredSize(new Dimension(CONTROL_W, 28));
        folder.setFont(HELP);
        folder.putClientProperty("role", "help");
        card(root, "Carpeta",
                option(title("Carpeta de videos"), folder, change),
                option("Una subcarpeta por red social", "Videos/Instagram, Videos/YouTube…", subfolders));
        card(root, "Descargas",
                option("Vigilar portapapeles", "Descarga sola cada enlace de video que copies.", watch),
                option("Buscar video embebido en otras páginas",
                        "Reproductores, HLS y <video> de sitios que no están en la lista.", embeds),
                option("Si falla, reintentar", "Solo errores temporales: red, servidor, límite de peticiones.", retries),
                option("Espera entre reintentos", "Cada reintento espera un poco más.", delay));
        card(root, "Avisos",
                option("Avisar si una descarga falla", "Las descargas que salen bien nunca avisan.", notify));
        cookies.setFont(BASE);
        cookies.setText("Buscando cookies…"); // sizes the row before the real summary arrives
        card(root, "Cookies",
                option(cookies, help("Exporta las cookies de cada sitio a Descargas para videos privados."), null));
        tools.setFont(BASE);
        tools.setText("Revisando…");
        card(root, "Requisitos",
                option(tools, help(Platform.WINDOWS ? "Instala lo que falte con winget y actualiza yt-dlp, gallery-dl, ffmpeg y deno."
                        : "Instala lo que falte con Homebrew y actualiza yt-dlp, gallery-dl, ffmpeg y deno."),
                        install));

        subfolders.onToggle = () -> flip(subfolders, host::setSubfolders);
        watch.onToggle = () -> flip(watch, host::setWatching);
        embeds.onToggle = () -> flip(embeds, host::setEmbeds);
        notify.onToggle = () -> flip(notify, host::setNotifications);
        retries.onChange = n -> {
            if (!loading) host.setMaxRetries(n);
        };
        delay.onChange = sec -> {
            if (!loading) host.setRetryDelaySec(sec);
        };

        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, menu), "close");
        root.getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dialog.setVisible(false);
            }
        });

        dialog.setContentPane(root);
        Theme.addListener(this::applyTheme);
    }

    /** Re-reads every value, shows the dialog (blocks: it is modal). */
    void open() {
        load();
        cookies.setText("Buscando cookies…");
        host.cookiesSummary(text -> {
            cookies.setText(text);
            dialog.pack(); // the summary is usually wider than "Buscando cookies…"
        });
        applyTheme();
        dialog.pack();
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
    }

    private void installTools() {
        install.setEnabled(false);
        host.installTools(text -> {
            tools.setText(text);
            dialog.pack();
        }, () -> install.setEnabled(true));
    }

    private void showToolsState() {
        if (!install.isEnabled()) return; // an install is running: keep its progress line
        List<String> missing = host.missingTools();
        tools.setText(missing.isEmpty() ? "Todo instalado ✓" : "Falta: " + String.join(", ", missing));
    }

    private void load() {
        showToolsState();
        loading = true;
        folder.setText(host.videosFolderLabel());
        subfolders.setOn(host.subfolders());
        watch.setOn(host.isWatching());
        embeds.setOn(host.embeds());
        notify.setOn(host.notifications());
        retries.set(host.maxRetries());
        delay.set(host.retryDelaySec());
        loading = false;
    }

    private void applyTheme() {
        Theme.Palette p = Theme.current();
        paint(dialog.getContentPane(), p);
        dialog.getContentPane().repaint();
    }

    private static void paint(Component c, Theme.Palette p) {
        if (c instanceof JPanel) c.setBackground(p.window());
        if (c instanceof JLabel l) {
            Object role = l.getClientProperty("role");
            l.setForeground("help".equals(role) || "header".equals(role) ? p.secondary() : p.text());
        }
        if (c instanceof Container k) for (Component child : k.getComponents()) paint(child, p);
    }

    private void flip(DownloadsWindow.Switch s, java.util.function.Consumer<Boolean> set) {
        s.setOn(!s.isOn());
        set.accept(s.isOn());
        load(); // e.g. subfolders changes nothing else, but keeps every control showing the saved value
    }

    // ---------------------------------------------------------------- layout helpers

    /** Grey caps header, then a rounded card holding the rows, separated by inset hairlines. */
    private static void card(JPanel root, String title, JComponent... rows) {
        JLabel h = new JLabel(title.toUpperCase());
        h.setFont(HEADER);
        h.putClientProperty("role", "header");
        h.setBorder(new EmptyBorder(14, PAD, 6, 0));
        JPanel hr = new JPanel(new BorderLayout());
        hr.add(h);
        hr.setAlignmentX(Component.LEFT_ALIGNMENT);
        hr.setMaximumSize(new Dimension(Integer.MAX_VALUE, hr.getPreferredSize().height));
        root.add(hr);
        Card card = new Card();
        for (int i = 0; i < rows.length; i++) card.add(rows[i], i == 0);
        root.add(card);
    }

    private static JComponent option(String title, String helpText, JComponent control) {
        return option(title(title), help(helpText), control);
    }

    /** One row: title over help on the left, the control right-aligned and vertically centred. */
    private static JComponent option(JLabel title, JLabel help, JComponent control) {
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(title);
        text.add(Box.createVerticalStrut(2));
        text.add(help);
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(10, PAD, 10, PAD));
        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        row.add(text, c);
        if (control != null) {
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            c.anchor = GridBagConstraints.EAST;
            c.insets = new Insets(0, 16, 0, 0);
            row.add(control, c);
        }
        return row;
    }

    private static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setFont(BASE);
        return l;
    }

    private static JLabel help(String text) {
        JLabel l = new JLabel(text);
        l.setFont(HELP);
        l.putClientProperty("role", "help");
        return l;
    }

    /** The rounded section background (System Settings' grouped list). */
    private static final class Card extends JPanel {
        Card() {
            setOpaque(false);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        void add(JComponent row, boolean firstRow) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (!firstRow) add(new Hairline());
            add(row);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Theme.Palette p = Theme.current();
            java.awt.geom.RoundRectangle2D r = new java.awt.geom.RoundRectangle2D.Double(.5, .5, getWidth() - 1, getHeight() - 1, 16, 16);
            g.setColor(p.row());
            g.fill(r);
            g.setColor(Theme.isDark() ? Glass.rgba(0xFFFFFF, 20) : Glass.rgba(0x000000, 22));
            g.draw(r);
            g.dispose();
        }
    }

    /** Separator between rows of a card, inset like macOS lists. */
    private static final class Hairline extends JComponent {
        Hairline() {
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(WIDTH - 40, 1);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 1);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Theme.current().separator());
            g.fillRect(PAD, 0, getWidth() - PAD * 2, 1);
        }
    }

    /** A pop-up menu as a clear glass pill: "2 veces ⌄"; the options open in a menu under it. */
    private static final class Choice<T> extends JComponent {
        java.util.function.Consumer<T> onChange = v -> {};
        private final T[] options;
        private final java.util.function.Function<T, String> text;
        private T value;
        private boolean hover;

        Choice(T[] options, java.util.function.Function<T, String> text) {
            this.options = options;
            this.text = text;
            value = options[0];
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    JPopupMenu menu = new JPopupMenu();
                    for (T o : Choice.this.options) {
                        JMenuItem item = new JCheckBoxMenuItem(text.apply(o), o.equals(value));
                        item.addActionListener(ev -> {
                            set(o);
                            onChange.accept(o);
                        });
                        menu.add(item);
                    }
                    menu.show(Choice.this, 0, getHeight());
                }

                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        void set(T v) {
            value = v;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(BASE);
            int w = 0;
            for (T o : options) w = Math.max(w, fm.stringWidth(text.apply(o)));
            return new Dimension(Math.max(CONTROL_W, w + 44), 28);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Theme.Palette p = Theme.current();
            Glass.button(g, new Rectangle(0, 0, getWidth(), getHeight()), false, hover, Theme.isDark());
            g.setFont(BASE);
            FontMetrics fm = g.getFontMetrics();
            g.setColor(p.buttonText());
            g.drawString(text.apply(value), 14, (getHeight() - 2 + fm.getAscent() - fm.getDescent()) / 2);
            // chevron
            double cx = getWidth() - 17, cy = (getHeight() - 2) / 2.0;
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            java.awt.geom.Path2D v = new java.awt.geom.Path2D.Double();
            v.moveTo(cx - 3.5, cy - 1.5);
            v.lineTo(cx, cy + 2);
            v.lineTo(cx + 3.5, cy - 1.5);
            g.setColor(p.secondary());
            g.draw(v);
            g.dispose();
        }
    }

    // ---------------------------------------------------------------- --preview

    /** java SettingsDialog --preview DIR: settings-light.png and settings-dark.png. */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].equals("--preview")) {
            System.err.println("usage: SettingsDialog --preview DIR");
            System.exit(2);
        }
        File dir = new File(args[1]);
        dir.mkdirs();
        EventQueue.invokeAndWait(() -> {
            try {
                for (boolean dark : new boolean[]{false, true}) {
                    Theme.force(dark);
                    SettingsDialog s = new SettingsDialog(null, new DownloadsWindow.PreviewHost(false));
                    s.load();
                    s.host.cookiesSummary(s.cookies::setText);
                    s.applyTheme();
                    s.dialog.pack();
                    Container cp = s.dialog.getContentPane();
                    DownloadsWindow.layoutAll(cp);
                    BufferedImage img = new BufferedImage(cp.getWidth() * 2, cp.getHeight() * 2, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = img.createGraphics();
                    g.scale(2, 2);
                    cp.printAll(g);
                    g.dispose();
                    ImageIO.write(img, "png", new File(dir, dark ? "settings-dark.png" : "settings-light.png"));
                    s.dialog.dispose();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }
}
