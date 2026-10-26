import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * The app's only window: a link field, filter chips, one row per download with its buttons in a fixed column,
 * a footer whose actions depend on the filter, and a collapsible "Detalles" panel with yt-dlp's output.
 * Everything custom-painted reads Theme.current(), so dark mode is a repaint.
 */
final class DownloadsWindow {

    private static final int ROW_H = 54;
    private static final Font BASE = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final Font SMALL = BASE.deriveFont(12f);
    private static final Font BOLD = BASE.deriveFont(Font.BOLD);

    private final WindowHost host;
    private final Runnable openSettings;
    private final JFrame frame = new JFrame("InstaTray");
    private final Rows rows = new Rows();
    private final PlaceholderField field = new PlaceholderField("Pega un enlace…");
    private final JLabel fieldMessage = new JLabel(" ");
    private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final Map<JobView.Filter, Chip> chipFor = new EnumMap<>(JobView.Filter.class);
    private final JPanel footerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    private final JLabel watchLabel = new JLabel();
    private final Switch watchSwitch = new Switch();
    private final JLabel folderLabel = new JLabel();
    private final JPanel details = new JPanel(new BorderLayout());
    private final JTextArea output = new JTextArea();
    private final List<JComponent> themed = new ArrayList<>();
    private JobView.Filter filter = JobView.Filter.ALL;
    private Timer messageTimer;
    private Job shownOutputFor;
    private String shownOutput = "";
    private String footerKey = "";

    DownloadsWindow(WindowHost host, Runnable openSettings) {
        this.host = host;
        this.openSettings = openSettings;
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE); // closing only hides; app keeps running
        frame.setIconImage(AeroIcon.render(256));

        JPanel root = panel(new BorderLayout());
        root.add(top(), BorderLayout.NORTH);
        root.add(list(), BorderLayout.CENTER);
        JPanel south = panel(new BorderLayout());
        south.add(detailsPanel(), BorderLayout.CENTER);
        south.add(footer(), BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        frame.setContentPane(root);
        details.setVisible(host.detailsOpen());

        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, menu), "settings");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, menu), "close");
        root.getActionMap().put("settings", action(openSettings));
        root.getActionMap().put("close", action(() -> frame.setVisible(false)));

        frame.setSize(720, 520);
        frame.setMinimumSize(new Dimension(560, 360));
        frame.setLocationRelativeTo(null);
        applyTheme();
        reload();

        // Poll while visible: cheap, and avoids wiring listeners through the worker thread
        new Timer(500, e -> {
            if (frame.isVisible()) reload();
        }).start();
        Theme.watch(this::applyTheme);
    }

    JFrame frame() {
        return frame;
    }

    void show(JobView.Filter f) {
        setFilter(f);
        frame.setVisible(true);
        frame.setState(Frame.NORMAL);
        frame.toFront();
        try {
            Desktop.getDesktop().requestForeground(true);
        } catch (Exception ignored) {
        }
    }

    /** One line under the link field; clears itself after 6 s. */
    void showFieldMessage(String text) {
        fieldMessage.setText(text);
        if (messageTimer != null) messageTimer.stop();
        messageTimer = new Timer(6000, e -> fieldMessage.setText(" "));
        messageTimer.setRepeats(false);
        messageTimer.start();
    }

    // ---------------------------------------------------------------- layout

    private JComponent top() {
        JPanel top = panel(new BorderLayout());
        top.setBorder(new EmptyBorder(14, 14, 4, 14));

        JPanel fieldRow = panel(new BorderLayout(8, 0));
        field.setFont(BASE);
        field.addActionListener(e -> submit());
        fieldRow.add(field, BorderLayout.CENTER);
        JPanel buttons = panel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(new PillButton("Descargar", null, true, this::submit));
        buttons.add(new PillButton("Ajustes", Glyph.GEAR, false, openSettings));
        fieldRow.add(buttons, BorderLayout.EAST);
        top.add(fieldRow, BorderLayout.NORTH);

        fieldMessage.setFont(SMALL);
        fieldMessage.setBorder(new EmptyBorder(4, 2, 6, 0));
        top.add(fieldMessage, BorderLayout.CENTER);

        chips.setOpaque(false);
        for (JobView.Filter f : JobView.Filter.values()) {
            Chip c = new Chip(f);
            chipFor.put(f, c);
            chips.add(c);
        }
        top.add(chips, BorderLayout.SOUTH);
        return top;
    }

    private JComponent list() {
        JList<Job> list = rows.list;
        list.setCellRenderer(new RowRenderer());
        list.setFixedCellHeight(ROW_H);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        RowMouse mouse = new RowMouse();
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshOutput();
        });
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "primary");
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "remove");
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove");
        list.getActionMap().put("primary", action(() -> {
            Job j = list.getSelectedValue();
            if (j != null && JobView.primary(j) != null) run(JobView.primary(j), j);
        }));
        list.getActionMap().put("remove", action(() -> list.getSelectedValuesList().stream()
                .filter(JobView::removableByKey).forEach(host::remove)));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        themed.add(scroll);
        return scroll;
    }

    private JComponent detailsPanel() {
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        output.setBorder(new EmptyBorder(6, 8, 6, 8));
        JScrollPane scroll = new JScrollPane(output);
        scroll.setPreferredSize(new Dimension(100, 150));
        details.add(scroll, BorderLayout.CENTER);
        JPanel bar = panel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        bar.add(new PillButton("Copiar", null, false, () -> host.copyToClipboardSilently(output.getText())));
        details.add(bar, BorderLayout.SOUTH);
        themed.add(details);
        themed.add(scroll);
        return details;
    }

    private JComponent footer() {
        JPanel footer = panel(new BorderLayout());
        footer.setBorder(new EmptyBorder(8, 14, 9, 14));
        footer.putClientProperty("footer", true);
        JPanel left = panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        Runnable toggleWatch = () -> {
            host.setWatching(!host.isWatching());
            reload();
        };
        link(watchLabel, toggleWatch);
        watchSwitch.onToggle = toggleWatch;
        link(folderLabel, host::openVideosFolder);
        JLabel dot = new JLabel(" · ");
        dot.setFont(SMALL);
        dot.putClientProperty("secondary", true);
        left.add(watchSwitch);
        left.add(Box.createHorizontalStrut(6));
        left.add(watchLabel);
        left.add(dot);
        left.add(folderLabel);
        footer.add(left, BorderLayout.WEST);
        footerRight.setOpaque(false);
        footer.add(footerRight, BorderLayout.EAST);
        return footer;
    }

    private void rebuildFooterActions() {
        String key = filter + "/" + host.isPaused() + "/" + details.isVisible();
        if (key.equals(footerKey)) return;
        footerKey = key;
        footerRight.removeAll();
        List<JobView.FooterAction> actions = JobView.footer(filter);
        for (int i = 0; i < actions.size(); i++) {
            JobView.FooterAction a = actions.get(i);
            if (i > 0) {
                JLabel dot = new JLabel(" · ");
                dot.setFont(SMALL);
                dot.setForeground(Theme.current().secondary());
                footerRight.add(dot);
            }
            JLabel l = new JLabel(JobView.footerLabel(a, host.isPaused(), details.isVisible()));
            link(l, () -> footerAction(a));
            l.setForeground(Theme.current().secondary());
            footerRight.add(l);
        }
        footerRight.revalidate();
        footerRight.repaint();
    }

    // ---------------------------------------------------------------- behaviour

    private void submit() {
        String text = field.getText().trim();
        if (text.isEmpty()) return;
        field.setText("");
        host.submit(text, () -> {
            showFieldMessage("Ese enlace no tiene un video compatible");
            if (field.getText().isEmpty()) field.setText(text); // let the user fix it
        });
    }

    private void setFilter(JobView.Filter f) {
        filter = f;
        reload();
    }

    private void reload() {
        List<Job> queue = host.queueSnapshot();
        List<Job> all = JobView.ordered(queue, host.finishedSnapshot());
        rows.queue = queue;
        rows.reload(all, filter);
        for (JobView.Filter f : JobView.Filter.values()) {
            chipFor.get(f).set(f == filter, (int) all.stream().filter(j -> JobView.matches(f, j)).count());
        }
        boolean watching = host.isWatching();
        watchSwitch.setOn(watching);
        watchLabel.setText(watching ? "Vigilando portapapeles" : "Portapapeles en pausa");
        watchLabel.setForeground(watching ? Theme.current().text() : Theme.current().secondary());
        folderLabel.setText(host.videosFolderLabel());
        rebuildFooterActions();
        refreshOutput();
    }

    private void footerAction(JobView.FooterAction a) {
        switch (a) {
            case PAUSE -> host.setPaused(!host.isPaused());
            case CANCEL_ALL -> {
                if (confirm("¿Cancelar la descarga actual y quitar las que esperan?")) host.cancelAll();
            }
            case RETRY_ALL -> rows.all.stream().filter(j -> JobView.matches(JobView.Filter.FAILED, j)).forEach(host::retry);
            case DELETE_ALL_FAILED -> removeAll(j -> JobView.matches(JobView.Filter.FAILED, j),
                    "¿Quitar de la lista las %d descargas fallidas?");
            case REMOVE_ALL_DONE -> removeAll(j -> j.status == Job.Status.DONE,
                    "¿Quitar de la lista las %d descargas listas? Los videos se quedan en tu carpeta.");
            case DETAILS -> {
                details.setVisible(!details.isVisible());
                host.setDetailsOpen(details.isVisible());
                frame.getContentPane().revalidate();
            }
        }
        reload();
    }

    private void removeAll(Predicate<Job> which, String question) {
        long n = rows.all.stream().filter(which).count();
        if (n == 0) return;
        if (confirm(String.format(question, n))) host.removeFinished(which);
    }

    private boolean confirm(String question) {
        return JOptionPane.showConfirmDialog(frame, question, "InstaTray", JOptionPane.YES_NO_OPTION)
                == JOptionPane.YES_OPTION;
    }

    private void run(JobView.Action a, Job j) {
        switch (a) {
            case CANCEL -> host.cancel(j);
            case TOP -> host.move(j, Integer.MIN_VALUE);
            case REMOVE -> host.remove(j);
            case PLAY -> {
                if (j.files.isEmpty()) return;
                if (Files.exists(Paths.get(j.files.get(0)))) {
                    host.openUrl(j.files.get(0));
                } else {
                    showFieldMessage("No encuentro el archivo; quizá lo moviste o lo borraste.");
                    host.openVideosFolder();
                }
            }
            case REVEAL -> {
                if (!j.files.isEmpty()) host.revealInFinder(Paths.get(j.files.get(0)));
            }
            case OPEN_PAGE -> host.openUrl(j.page.isEmpty() ? j.link.url() : j.page);
            case RETRY -> host.retry(j);
        }
        reload();
    }

    private void showMenu(Job j, MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        for (JobView.Action a : new JobView.Action[]{JobView.primary(j), JobView.secondary(j)}) {
            if (a != null) menu.add(item(a.label, () -> run(a, j)));
        }
        if (JobView.deletable(j)) menu.add(item("Borrar de la lista", () -> host.remove(j)));
        menu.addSeparator();
        menu.add(item("Copiar enlace", () -> host.copyToClipboardSilently(j.link.url())));
        menu.add(item("Detalles", () -> {
            rows.list.setSelectedValue(j, false);
            if (!details.isVisible()) footerAction(JobView.FooterAction.DETAILS);
        }));
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void refreshOutput() {
        if (!details.isVisible()) return;
        Job first = rows.list.getSelectedValue();
        String text;
        if (first == null) {
            text = "Selecciona una descarga para ver qué pasó.";
        } else {
            String out = first.outputText();
            text = first.link.url() + "\n" + first.statusText()
                    + (first.message.isEmpty() ? "" : " — " + first.message)
                    + (first.files.isEmpty() ? "" : "\nArchivo: " + String.join(", ", first.files))
                    + "\n\n" + (out.isEmpty() ? "(sin salida todavía, o de una sesión anterior)" : out);
        }
        if (first != shownOutputFor || !text.equals(shownOutput)) {
            boolean follow = first == shownOutputFor; // keep scrolled to the end while it streams
            output.setText(text);
            output.setCaretPosition(follow ? output.getDocument().getLength() : 0);
            shownOutputFor = first;
            shownOutput = text;
        }
    }

    private void applyTheme() {
        Theme.Palette p = Theme.current();
        applyTo(frame.getContentPane(), p);
        for (JComponent c : themed) {
            c.setBackground(p.row());
            if (c instanceof JScrollPane s) {
                s.getViewport().setBackground(p.row());
                s.setBorder(s.getBorder() instanceof EmptyBorder ? s.getBorder() : BorderFactory.createMatteBorder(1, 0, 0, 0, p.separator()));
            }
        }
        rows.list.setBackground(p.row());
        output.setBackground(p.row());
        output.setForeground(p.text());
        output.setCaretColor(p.text());
        field.setBackground(p.row());
        field.setForeground(p.text());
        field.setCaretColor(p.text());
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(p.buttonBorder(), 1, true), new EmptyBorder(5, 8, 5, 8)));
        fieldMessage.setForeground(p.secondary());
        folderLabel.setForeground(p.secondary());
        footerKey = "";
        reload();
        frame.repaint();
    }

    private static void applyTo(Component c, Theme.Palette p) {
        if (c instanceof JPanel panel) {
            panel.setBackground(Boolean.TRUE.equals(panel.getClientProperty("footer")) ? p.footer() : p.window());
        }
        if (c instanceof JLabel l && Boolean.TRUE.equals(l.getClientProperty("secondary"))) l.setForeground(p.secondary());
        if (c instanceof Container k) for (Component child : k.getComponents()) applyTo(child, p);
    }

    // ---------------------------------------------------------------- small helpers

    private static JPanel panel(LayoutManager layout) {
        return new JPanel(layout);
    }

    private static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
    }

    private static JMenuItem item(String label, Runnable r) {
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> r.run());
        return i;
    }

    private static void link(JLabel l, Runnable r) {
        l.setFont(SMALL);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                r.run();
            }
        });
    }

    // ---------------------------------------------------------------- the list

    /** The list model; selection follows Job objects, not row indexes, across reloads. */
    static final class Rows {
        final DefaultListModel<Job> model = new DefaultListModel<>();
        List<Job> all = List.of();
        List<Job> queue = List.of();
        JobView.Filter filter = JobView.Filter.ALL;
        final JList<Job> list = new JList<>(model) {
            @Override
            protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                if (!model.isEmpty()) return;
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(BASE);
                g.setColor(Theme.current().secondary());
                String text = JobView.emptyText(filter);
                FontMetrics fm = g.getFontMetrics();
                Rectangle view = getVisibleRect();
                g.drawString(text, view.x + (view.width - fm.stringWidth(text)) / 2, view.y + view.height / 2);
                g.dispose();
            }
        };

        void reload(List<Job> ordered, JobView.Filter f) {
            all = ordered;
            filter = f;
            List<Job> shown = ordered.stream().filter(j -> JobView.matches(f, j)).toList();
            boolean same = shown.size() == model.size();
            for (int i = 0; same && i < shown.size(); i++) same = shown.get(i) == model.get(i);
            if (same) {
                list.repaint(); // only statuses / percentages changed
                return;
            }
            List<Job> before = list.getSelectedValuesList();
            model.clear();
            model.addAll(shown);
            list.clearSelection();
            for (Job j : before) {
                int r = shown.indexOf(j);
                if (r >= 0) list.addSelectionInterval(r, r);
            }
        }

        /** 1-based place among the jobs waiting in the queue. */
        int queuePosition(Job j) {
            int n = 0;
            for (Job q : queue) {
                if (q.status == Job.Status.QUEUED) n++;
                if (q == j) return n;
            }
            return 0;
        }
    }

    private final class RowMouse extends MouseAdapter {
        @Override
        public void mouseMoved(MouseEvent e) {
            int[] hit = hit(e);
            RowRenderer r = (RowRenderer) rows.list.getCellRenderer();
            int row = hit == null ? -1 : hit[0], button = hit == null ? -1 : hit[1];
            if (row != r.hoverRow || button != r.hoverButton) {
                r.hoverRow = row;
                r.hoverButton = button;
                rows.list.setCursor(button >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                rows.list.repaint();
            }
        }

        @Override
        public void mouseExited(MouseEvent e) {
            RowRenderer r = (RowRenderer) rows.list.getCellRenderer();
            r.hoverRow = r.hoverButton = -1;
            rows.list.repaint();
        }

        @Override
        public void mousePressed(MouseEvent e) {
            if (e.isPopupTrigger()) popup(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (e.isPopupTrigger()) {
                popup(e);
                return;
            }
            int[] hit = hit(e);
            if (hit == null || hit[1] < 0 || !SwingUtilities.isLeftMouseButton(e)) return;
            Job j = rows.model.get(hit[0]);
            JobView.Action a = hit[1] == 0 ? JobView.primary(j) : hit[1] == 1 ? JobView.secondary(j) : null;
            if (a != null) run(a, j);
            else if (hit[1] == 2) host.remove(j);
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
            int[] hit = hit(e);
            if (hit == null || hit[1] >= 0) return; // a button handles its own click
            Job j = rows.model.get(hit[0]);
            if (JobView.primary(j) != null) run(JobView.primary(j), j);
        }

        private void popup(MouseEvent e) {
            int[] hit = hit(e);
            if (hit == null) return;
            Job j = rows.model.get(hit[0]);
            if (!rows.list.isSelectedIndex(hit[0])) rows.list.setSelectedIndex(hit[0]);
            showMenu(j, e);
        }

        /** {row, button} with button -1 = none, 0 primary, 1 secondary, 2 trash; null outside any row. */
        private int[] hit(MouseEvent e) {
            int row = rows.list.locationToIndex(e.getPoint());
            if (row < 0) return null;
            Rectangle cell = rows.list.getCellBounds(row, row);
            if (cell == null || !cell.contains(e.getPoint())) return null;
            Job j = rows.model.get(row);
            Rectangle[] b = JobView.buttonBounds(cell.width, cell.height);
            Point p = new Point(e.getX() - cell.x, e.getY() - cell.y);
            if (JobView.primary(j) != null && b[0].contains(p)) return new int[]{row, 0};
            if (JobView.secondary(j) != null && b[1].contains(p)) return new int[]{row, 1};
            if (JobView.deletable(j) && b[2].contains(p)) return new int[]{row, 2};
            return new int[]{row, -1};
        }
    }

    private final class RowRenderer extends JComponent implements ListCellRenderer<Job> {
        int hoverRow = -1, hoverButton = -1;
        private Job job;
        private int index;
        private boolean selected;
        private final Map<InstaTray.Site, Image> orbs = new HashMap<>();

        @Override
        public Component getListCellRendererComponent(JList<? extends Job> list, Job value, int index, boolean selected, boolean focus) {
            this.job = value;
            this.index = index;
            this.selected = selected;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            Theme.Palette p = Theme.current();
            int w = getWidth(), h = getHeight();
            g.setColor(selected ? p.rowSelected() : p.row());
            g.fillRect(0, 0, w, h);
            g.setColor(p.separator());
            g.fillRect(14, h - 1, w - 14, 1);

            g.drawImage(orb(job.link.site()), 14, (h - 28) / 2, 28, 28, null);

            Rectangle[] b = JobView.buttonBounds(w, h);
            int textX = 14 + 28 + 12, textW = b[0].x - 12 - textX;
            g.setFont(BOLD);
            g.setColor(p.text());
            boolean bar = job.status == Job.Status.DOWNLOADING && job.percent >= 0;
            int titleY = bar ? 19 : 23;
            g.drawString(ellipsize(JobView.title(job), g.getFontMetrics(), textW), textX, titleY);
            g.setFont(SMALL);
            g.setColor(switch (JobView.tone(job)) {
                case OK -> p.ok();
                case BAD -> p.bad();
                case MUTED, NORMAL -> p.secondary();
            });
            g.drawString(ellipsize(JobView.subtitle(job, rows.queuePosition(job), System.currentTimeMillis()),
                    g.getFontMetrics(), textW), textX, titleY + 17);
            if (bar) {
                int by = titleY + 25;
                g.setColor(p.track());
                g.fill(new RoundRectangle2D.Double(textX, by, textW, 4, 4, 4));
                g.setColor(p.accent());
                g.fill(new RoundRectangle2D.Double(textX, by, textW * Math.min(100, job.percent) / 100.0, 4, 4, 4));
            }

            JobView.Action a1 = JobView.primary(job), a2 = JobView.secondary(job);
            if (a1 != null) paintButton(g, b[0], a1.label, Glyph.of(a1), true, hoverRow == index && hoverButton == 0, p);
            if (a2 != null) paintButton(g, b[1], a2.label, Glyph.of(a2), false, hoverRow == index && hoverButton == 1, p);
            if (JobView.deletable(job)) {
                boolean hover = hoverRow == index && hoverButton == 2;
                if (hover) {
                    g.setColor(p.button());
                    g.fill(new RoundRectangle2D.Double(b[2].x, b[2].y, b[2].width, b[2].height, 8, 8));
                }
                Glyph.TRASH.paint(g, b[2].x + (b[2].width - 12) / 2.0, b[2].y + (b[2].height - 12) / 2.0,
                        hover ? p.bad() : p.secondary());
            }
            g.dispose();
        }

        private Image orb(InstaTray.Site site) {
            return orbs.computeIfAbsent(site, s -> AeroIcon.render(56, s.palette(), ""));
        }
    }

    static String ellipsize(String s, FontMetrics fm, int width) {
        if (width <= 0) return "";
        if (fm.stringWidth(s) <= width) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fm.stringWidth(s.substring(0, mid) + "…") <= width) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, lo) + "…";
    }

    /** A rounded button: accent fill for the primary one, plain with a border otherwise; icon + label centred. */
    static void paintButton(Graphics2D g, Rectangle r, String label, Glyph glyph, boolean primary, boolean hover, Theme.Palette p) {
        RoundRectangle2D shape = new RoundRectangle2D.Double(r.x + 0.5, r.y + 0.5, r.width - 1, r.height - 1, 8, 8);
        Color fill = primary ? p.accent() : p.button();
        if (hover) fill = primary ? fill.brighter() : mix(fill, p.accent(), 0.12);
        g.setColor(fill);
        g.fill(shape);
        if (!primary) {
            g.setColor(p.buttonBorder());
            g.draw(shape);
        }
        Color ink = primary ? p.accentText() : p.buttonText();
        g.setFont(SMALL);
        FontMetrics fm = g.getFontMetrics();
        int iconW = glyph == null ? 0 : 12 + 6;
        String text = ellipsize(label, fm, r.width - 16 - iconW);
        int total = iconW + fm.stringWidth(text);
        int x = r.x + (r.width - total) / 2;
        if (glyph != null) glyph.paint(g, x, r.y + (r.height - 12) / 2.0, ink);
        g.setColor(ink);
        g.drawString(text, x + iconW, r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.getRed() * (1 - t) + b.getRed() * t), (int) (a.getGreen() * (1 - t) + b.getGreen() * t),
                (int) (a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    /** 12×12 vector icons for the buttons (emoji render unreliably in Swing). */
    enum Glyph {
        CROSS, UP, PLAY, MAGNIFIER, GLOBE, RETRY, TRASH, GEAR;

        static Glyph of(JobView.Action a) {
            return switch (a) {
                case CANCEL, REMOVE -> CROSS;
                case TOP -> UP;
                case PLAY -> PLAY;
                case REVEAL -> MAGNIFIER;
                case OPEN_PAGE -> GLOBE;
                case RETRY -> RETRY;
            };
        }

        void paint(Graphics2D g0, double x, double y, Color c) {
            Graphics2D g = (Graphics2D) g0.create();
            g.translate(x, y);
            g.setColor(c);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (this) {
                case CROSS -> {
                    g.draw(new Line2D.Double(2.5, 2.5, 9.5, 9.5));
                    g.draw(new Line2D.Double(9.5, 2.5, 2.5, 9.5));
                }
                case UP -> {
                    g.draw(new Line2D.Double(1.5, 1.5, 10.5, 1.5));
                    g.draw(new Line2D.Double(6, 4, 6, 11));
                    g.draw(new Line2D.Double(3, 7, 6, 4));
                    g.draw(new Line2D.Double(9, 7, 6, 4));
                }
                case PLAY -> {
                    Path2D t = new Path2D.Double();
                    t.moveTo(3, 1.5);
                    t.lineTo(10.5, 6);
                    t.lineTo(3, 10.5);
                    t.closePath();
                    g.fill(t);
                }
                case MAGNIFIER -> {
                    g.draw(new Ellipse2D.Double(1.5, 1.5, 7, 7));
                    g.draw(new Line2D.Double(7.5, 7.5, 10.5, 10.5));
                }
                case GLOBE -> {
                    g.setStroke(new BasicStroke(1.2f));
                    g.draw(new Ellipse2D.Double(1, 1, 10, 10));
                    g.draw(new Ellipse2D.Double(3.8, 1, 4.4, 10));
                    g.draw(new Line2D.Double(1, 6, 11, 6));
                }
                case RETRY -> {
                    g.draw(new Arc2D.Double(1.5, 1.5, 9, 9, 60, 280, Arc2D.OPEN));
                    Path2D head = new Path2D.Double();
                    head.moveTo(8.2, 0.4);
                    head.lineTo(9.6, 3.4);
                    head.lineTo(6.4, 3.8);
                    g.draw(head);
                }
                case TRASH -> {
                    g.draw(new Line2D.Double(1.5, 3, 10.5, 3));
                    g.draw(new Line2D.Double(4.5, 3, 4.5, 1.5));
                    g.draw(new Line2D.Double(4.5, 1.5, 7.5, 1.5));
                    g.draw(new Line2D.Double(7.5, 1.5, 7.5, 3));
                    Path2D can = new Path2D.Double();
                    can.moveTo(2.8, 3);
                    can.lineTo(3.5, 11);
                    can.lineTo(8.5, 11);
                    can.lineTo(9.2, 3);
                    g.draw(can);
                }
                case GEAR -> {
                    Path2D gear = new Path2D.Double(Path2D.WIND_EVEN_ODD);
                    for (int i = 0; i < 16; i++) { // 8 teeth: alternate outer and inner radius
                        double a = Math.PI / 8 * i - Math.PI / 16, r = i % 2 == 0 ? 6 : 4.6;
                        double x1 = 6 + Math.cos(a) * r, y1 = 6 + Math.sin(a) * r;
                        double a2 = a + Math.PI / 8, x2 = 6 + Math.cos(a2) * r, y2 = 6 + Math.sin(a2) * r;
                        if (i == 0) gear.moveTo(x1, y1);
                        else gear.lineTo(x1, y1);
                        gear.lineTo(x2, y2);
                    }
                    gear.closePath();
                    gear.append(new Ellipse2D.Double(4, 4, 4, 4), false);
                    g.fill(gear);
                }
            }
            g.dispose();
        }
    }

    /** A painted button that sizes itself to its label. */
    static final class PillButton extends JComponent {
        private final String label;
        private final Glyph glyph;
        private final boolean primary;
        private boolean hover;

        PillButton(String label, Glyph glyph, boolean primary, Runnable action) {
            this.label = label;
            this.glyph = glyph;
            this.primary = primary;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    action.run();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(SMALL);
            return new Dimension(fm.stringWidth(label) + 28 + (glyph == null ? 0 : 18), 28);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            paintButton(g, new Rectangle(0, 0, getWidth(), getHeight()), label, glyph, primary, hover, Theme.current());
            g.dispose();
        }
    }

    /** A small macOS-style switch (accent track when on). */
    private static final class Switch extends JComponent {
        Runnable onToggle = () -> {};
        private boolean on;

        Switch() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Vigilar portapapeles");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    onToggle.run();
                }
            });
        }

        void setOn(boolean on) {
            if (on == this.on) return;
            this.on = on;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(30, 18);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Theme.Palette p = Theme.current();
            double w = 28, h = 16, y = (getHeight() - h) / 2;
            g.setColor(on ? p.accent() : p.track());
            g.fill(new RoundRectangle2D.Double(1, y, w, h, h, h));
            if (!on) {
                g.setColor(p.buttonBorder());
                g.draw(new RoundRectangle2D.Double(1, y, w, h, h, h));
            }
            double knob = h - 4;
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(on ? 1 + w - knob - 2 : 3, y + 2, knob, knob));
            g.dispose();
        }
    }

    /** A filter pill: "Fallidas 2"; the selected one is dark (light theme) or light (dark theme). */
    private final class Chip extends JComponent {
        private final JobView.Filter f;
        private boolean on;
        private int count;

        Chip(JobView.Filter f) {
            this.f = f;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    setFilter(f);
                }
            });
        }

        void set(boolean on, int count) {
            if (on == this.on && count == this.count) return;
            this.on = on;
            this.count = count;
            revalidate();
            repaint();
        }

        private String text() {
            return f.label + "  " + count;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(SMALL).stringWidth(text()) + 22, 24);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Theme.Palette p = Theme.current();
            g.setColor(on ? p.chipOn() : p.chip());
            g.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), getHeight(), getHeight()));
            g.setFont(SMALL);
            FontMetrics fm = g.getFontMetrics();
            int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            Color ink = on ? p.chipOnText() : p.text();
            g.setColor(ink);
            g.drawString(f.label, 11, y);
            g.setColor(on ? mix(ink, p.chipOn(), 0.4) : p.secondary());
            g.drawString(String.valueOf(count), 11 + fm.stringWidth(f.label + "  "), y);
            g.dispose();
        }
    }

    /** Text field with a grey hint while empty. */
    private static final class PlaceholderField extends JTextField {
        private final String hint;

        PlaceholderField(String hint) {
            this.hint = hint;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (!getText().isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Theme.current().secondary());
            g.setFont(getFont());
            Insets in = getInsets();
            g.drawString(hint, in.left, in.top + g.getFontMetrics().getAscent());
            g.dispose();
        }
    }

    // ---------------------------------------------------------------- --preview

    /** java DownloadsWindow --preview DIR: PNGs of the window in sample states, light and dark. */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].equals("--preview")) {
            System.err.println("usage: DownloadsWindow --preview DIR");
            System.exit(2);
        }
        File dir = new File(args[1]);
        dir.mkdirs();
        EventQueue.invokeAndWait(() -> {
            try {
                PreviewHost h = new PreviewHost(true);
                render(h, false, JobView.Filter.ALL, false, new File(dir, "downloads-light.png"));
                render(h, true, JobView.Filter.ALL, false, new File(dir, "downloads-dark.png"));
                render(h, true, JobView.Filter.FAILED, false, new File(dir, "downloads-failed-filter-dark.png"));
                render(h, true, JobView.Filter.ALL, true, new File(dir, "downloads-details-dark.png"));
                PreviewHost empty = new PreviewHost(false);
                empty.watching = false; // shows the switch off
                render(empty, false, JobView.Filter.ALL, false, new File(dir, "downloads-empty-light.png"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }

    private static void render(PreviewHost h, boolean dark, JobView.Filter f, boolean details, File out) throws Exception {
        Theme.force(dark);
        h.details = details;
        DownloadsWindow w = new DownloadsWindow(h, () -> {});
        w.setFilter(f);
        if (details) w.rows.list.setSelectedIndex(3);
        JFrame fr = w.frame;
        fr.addNotify();
        fr.validate();
        Container cp = fr.getContentPane();
        layoutAll(cp);
        w.refreshOutput();
        BufferedImage img = new BufferedImage(cp.getWidth() * 2, cp.getHeight() * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.scale(2, 2);
        cp.printAll(g);
        g.dispose();
        ImageIO.write(img, "png", out);
        fr.dispose();
    }

    static void layoutAll(Component c) {
        c.doLayout();
        if (c instanceof Container k) for (Component x : k.getComponents()) layoutAll(x);
    }

    /** Sample jobs for --preview; every action is a no-op. */
    static final class PreviewHost implements WindowHost {
        private final List<Job> queue = new ArrayList<>(), finished = new ArrayList<>();
        boolean details;

        PreviewHost(boolean withJobs) {
            if (!withJobs) return;
            long now = System.currentTimeMillis();
            Job dl = job(InstaTray.Site.YOUTUBE, "yt:1", now, Job.Status.DOWNLOADING, "Cómo hacer pan casero en 10 minutos");
            dl.percent = 45;
            dl.speed = "2.1 MB/s";
            dl.eta = "0:12";
            queue.add(dl);
            queue.add(job(InstaTray.Site.INSTAGRAM, "ig:2", now, Job.Status.QUEUED, "Reel de @fulano"));
            Job done = job(InstaTray.Site.TIKTOK, "tt:3", now - 5 * 60_000, Job.Status.DONE, "Baile viral #fyp");
            done.files = List.of("/tmp/baile.mp4");
            done.bytes = 12_582_912;
            finished.add(done);
            Job f1 = job(InstaTray.Site.OTHER, "ytdl:Generic:4", now - 20 * 60_000, Job.Status.FAILED, "");
            f1.message = "No deja bajar este video fuera de su página";
            f1.addOutput("[generic] Extracting URL: https://embed.ejemplo.com/video/123");
            f1.addOutput("ERROR: HTTP Error 403: Forbidden");
            finished.add(f1);
            Job f2 = job(InstaTray.Site.INSTAGRAM, "ig:5", now - 60 * 60_000, Job.Status.FAILED, "Un título larguísimo ".repeat(15));
            f2.message = "Faltan las cookies de Instagram";
            finished.add(f2);
        }

        private static Job job(InstaTray.Site site, String key, long at, Job.Status st, String title) {
            Job j = new Job(new InstaTray.Link(site, key, "https://embed.ejemplo.com/video/" + key.hashCode()), at);
            j.status = st;
            j.title = title;
            return j;
        }

        @Override public List<Job> queueSnapshot() { return queue; }
        @Override public List<Job> finishedSnapshot() { return finished; }
        @Override public void submit(String text, Runnable onNothingFound) {}
        @Override public void move(Job job, int delta) {}
        @Override public void cancel(Job job) {}
        @Override public void cancelAll() {}
        @Override public void retry(Job job) {}
        @Override public void remove(Job job) {}
        @Override public void removeFinished(Predicate<Job> which) {}
        @Override public boolean isPaused() { return false; }
        @Override public void setPaused(boolean on) {}
        boolean watching = true;
        @Override public boolean isWatching() { return watching; }
        @Override public void setWatching(boolean on) {}
        @Override public String videosFolderLabel() { return "Documentos/Videos"; }
        @Override public void openVideosFolder() {}
        @Override public void revealInFinder(Path file) {}
        @Override public void openUrl(String url) {}
        @Override public void copyToClipboardSilently(String text) {}
        @Override public boolean subfolders() { return false; }
        @Override public void setSubfolders(boolean on) {}
        @Override public boolean embeds() { return false; }
        @Override public void setEmbeds(boolean on) {}
        @Override public boolean notifications() { return true; }
        @Override public void setNotifications(boolean on) {}
        @Override public int maxRetries() { return 2; }
        @Override public void setMaxRetries(int n) {}
        @Override public int retryDelaySec() { return 30; }
        @Override public void setRetryDelaySec(int sec) {}
        @Override public void chooseVideosDir(Window owner) {}
        @Override public void cookiesSummary(Consumer<String> onEdt) { onEdt.accept("Cookies: Instagram, YouTube (2 de 26 sitios)"); }
        @Override public boolean detailsOpen() { return details; }
        @Override public void setDetailsOpen(boolean open) {}
    }
}
