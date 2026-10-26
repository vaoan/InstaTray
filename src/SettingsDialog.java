import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** "Ajustes": the options that are set once (folder, clipboard, retries, notifications) and the cookie status. */
final class SettingsDialog {

    private static final Integer[] RETRIES = {0, 1, 2, 3, 4, 5};
    private static final Integer[] DELAYS = {10, 30, 60, 300};
    private static final Font BASE = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final Font HELP = BASE.deriveFont(11f);
    private static final Font HEADER = BASE.deriveFont(Font.BOLD, 11f);

    private final WindowHost host;
    private final JDialog dialog;
    private final JLabel folder = new JLabel();
    private final JCheckBox subfolders = new JCheckBox("Una subcarpeta por red social");
    private final JCheckBox watch = new JCheckBox("Vigilar portapapeles");
    private final JCheckBox embeds = new JCheckBox("Buscar video embebido en otras páginas");
    private final JComboBox<Integer> retries = new JComboBox<>(RETRIES);
    private final JComboBox<Integer> delay = new JComboBox<>(DELAYS);
    private final JCheckBox notify = new JCheckBox("Avisar con una notificación si una descarga falla");
    private final JLabel cookies = new JLabel();
    private boolean loading;

    SettingsDialog(Frame owner, WindowHost host) {
        this.host = host;
        dialog = new JDialog(owner, "Ajustes", true);
        dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);

        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(new EmptyBorder(16, 20, 18, 20));

        JButton change = new JButton("Cambiar…");
        change.addActionListener(e -> {
            host.chooseVideosDir(dialog);
            load();
        });
        JPanel folderRow = row(folder, Box.createHorizontalStrut(10), change);
        section(root, "Carpeta", true);
        root.add(folderRow);
        add(root, subfolders, "Videos/Instagram, Videos/YouTube…");

        section(root, "Descargas", false);
        add(root, watch, "Descarga sola cada enlace de video que copies.");
        add(root, embeds, "Reproductores, HLS y <video> de sitios que no están en la lista.");
        delay.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                int sec = (Integer) v;
                return super.getListCellRendererComponent(l, sec < 60 ? sec + " s" : sec / 60 + " min", i, sel, f);
            }
        });
        JPanel retryRow = row(label("Si falla, reintentar "), retries, label(" veces, esperando "), delay);
        root.add(retryRow);
        root.add(help("Cada reintento espera un poco más."));

        section(root, "Avisos", false);
        add(root, notify, "Las descargas que salen bien nunca avisan.");

        section(root, "Cookies", false);
        cookies.setFont(BASE);
        cookies.setText("Buscando cookies…"); // sizes the row before the real summary arrives
        root.add(row(cookies));
        root.add(help("Exporta las cookies de cada sitio a Descargas para videos privados."));

        subfolders.addActionListener(e -> {
            if (!loading) host.setSubfolders(subfolders.isSelected());
            load();
        });
        watch.addActionListener(e -> {
            if (!loading) host.setWatching(watch.isSelected());
        });
        embeds.addActionListener(e -> {
            if (!loading) host.setEmbeds(embeds.isSelected());
        });
        notify.addActionListener(e -> {
            if (!loading) host.setNotifications(notify.isSelected());
        });
        retries.addActionListener(e -> {
            if (!loading) host.setMaxRetries((Integer) retries.getSelectedItem());
        });
        delay.addActionListener(e -> {
            if (!loading) host.setRetryDelaySec((Integer) delay.getSelectedItem());
        });

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

    private void load() {
        loading = true;
        folder.setText("Videos en: " + host.videosFolderLabel());
        subfolders.setSelected(host.subfolders());
        watch.setSelected(host.isWatching());
        embeds.setSelected(host.embeds());
        notify.setSelected(host.notifications());
        retries.setSelectedItem(host.maxRetries());
        delay.setSelectedItem(host.retryDelaySec());
        loading = false;
    }

    private void applyTheme() {
        Theme.Palette p = Theme.current();
        paint(dialog.getContentPane(), p);
        dialog.getContentPane().repaint();
    }

    private static void paint(Component c, Theme.Palette p) {
        if (c instanceof JPanel || c instanceof JCheckBox) c.setBackground(p.window());
        if (c instanceof JLabel l) {
            Object role = l.getClientProperty("role");
            l.setForeground("help".equals(role) || "header".equals(role) ? p.secondary() : p.text());
        }
        if (c instanceof JCheckBox b) b.setForeground(p.text());
        if (c instanceof Container k) for (Component child : k.getComponents()) paint(child, p);
    }

    // ---------------------------------------------------------------- layout helpers

    private static void section(JPanel root, String title, boolean first) {
        if (!first) root.add(Box.createVerticalStrut(14));
        JLabel l = new JLabel(title.toUpperCase());
        l.setFont(HEADER);
        l.putClientProperty("role", "header");
        root.add(row(l));
        root.add(Box.createVerticalStrut(6));
    }

    private static void add(JPanel root, JCheckBox box, String helpText) {
        box.setFont(BASE);
        box.setOpaque(true);
        root.add(row(box));
        root.add(help(helpText));
    }

    private static JComponent help(String text) {
        JLabel l = new JLabel(text);
        l.setFont(HELP);
        l.putClientProperty("role", "help");
        l.setBorder(new EmptyBorder(0, 24, 6, 0)); // lines up with the checkbox text
        return row(l);
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(BASE);
        return l;
    }

    private static JPanel row(Component... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (Component c : parts) {
            if (c instanceof JLabel l && l.getFont() == null) l.setFont(BASE);
            p.add(c);
        }
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
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
