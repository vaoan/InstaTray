import java.awt.*;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Two tabs: "Cola" (what is downloading and waiting, with reorder / cancel / pause) and
 * "Historial" (finished jobs with retry / open actions). Both share a panel with yt-dlp's output.
 */
final class HistoryWindow {

    private final InstaTray app;
    private final JFrame frame;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JobTable queue;
    private final JobTable done;
    private final JTextArea output = new JTextArea();
    private final JToggleButton pause = new JToggleButton("Pausar cola");
    private Job shownOutputFor;
    private String shownOutput = "";

    HistoryWindow(InstaTray app) {
        this.app = app;
        frame = new JFrame("InstaTray — Cola e historial");
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setIconImage(AeroIcon.render(256));

        queue = new JobTable(app::queueSnapshot,
                new String[]{"#", "Sitio", "Estado", "Detalle", "Enlace"}, new int[]{30, 75, 130, 300, 300});
        done = new JobTable(app::finishedSnapshot,
                new String[]{"Hora", "Sitio", "Estado", "Detalle", "Enlace"}, new int[]{75, 75, 90, 360, 260});

        tabs.addTab("Cola", queuePanel());
        tabs.addTab("Historial", historyPanel());
        tabs.addChangeListener(e -> refreshOutput());

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane outputScroll = new JScrollPane(output);
        outputScroll.setBorder(BorderFactory.createTitledBorder("Qué pasó (salida de yt-dlp)"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, outputScroll);
        split.setResizeWeight(0.62);
        frame.setContentPane(split);
        frame.setSize(940, 600);
        frame.setLocationRelativeTo(null);

        // Poll while visible: cheap, and avoids wiring listeners through the worker thread
        new Timer(500, e -> {
            if (frame.isVisible()) refresh();
        }).start();
    }

    private JPanel queuePanel() {
        JButton top = button("Poner primero", () -> queue.selected().forEach(j -> app.move(j, Integer.MIN_VALUE)));
        JButton up = button("Subir", () -> queue.selected().forEach(j -> app.move(j, -1)));
        JButton down = button("Bajar", () -> queue.selected().reversed().forEach(j -> app.move(j, +1)));
        JButton cancel = button("Cancelar", () -> queue.selected().forEach(app::cancel));
        JButton cancelAll = button("Cancelar todo", () -> {
            int ok = JOptionPane.showConfirmDialog(frame, "¿Cancelar todas las descargas en cola y la actual?",
                    "InstaTray", JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION) app.cancelAll();
        });
        pause.addActionListener(e -> app.setPaused(pause.isSelected()));
        queue.onSelection = sel -> {
            boolean any = !sel.isEmpty();
            boolean movable = sel.stream().anyMatch(j -> j.status == Job.Status.QUEUED);
            top.setEnabled(movable);
            up.setEnabled(movable);
            down.setEnabled(movable);
            cancel.setEnabled(any);
        };
        return withButtons(queue, top, up, down, cancel, cancelAll, pause);
    }

    private JPanel historyPanel() {
        JButton retry = button("Reintentar", () -> done.selected().forEach(app::retry));
        JButton retryFailed = button("Reintentar fallidos", () -> done.all().stream()
                .filter(j -> j.status == Job.Status.FAILED || j.status == Job.Status.CANCELLED).forEach(app::retry));
        JButton openVideo = button("Mostrar video", () -> done.selected().stream().filter(j -> !j.files.isEmpty())
                .findFirst().ifPresent(j -> app.revealInFinder(Paths.get(j.files.get(0)))));
        JButton copyLink = button("Copiar enlace", () -> app.copyToClipboardSilently(
                String.join("\n", done.selected().stream().map(j -> j.link.url()).toList())));
        JButton openLink = button("Abrir enlace", () -> done.selected().stream().findFirst()
                .ifPresent(j -> app.openUrl(j.link.url())));
        JButton clear = button("Limpiar historial", app::clearFinishedJobs);
        JButton folder = button("Abrir carpeta", app::openDocuments);
        done.onSelection = sel -> {
            boolean any = !sel.isEmpty();
            retry.setEnabled(any);
            openVideo.setEnabled(sel.stream().anyMatch(j -> !j.files.isEmpty()));
            copyLink.setEnabled(any);
            openLink.setEnabled(any);
        };
        return withButtons(done, retry, retryFailed, openVideo, copyLink, openLink, clear, folder);
    }

    private static JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    private JPanel withButtons(JobTable table, AbstractButton... buttons) {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        for (AbstractButton b : buttons) bar.add(b);
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(table.table), BorderLayout.CENTER);
        p.add(bar, BorderLayout.SOUTH);
        table.table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                table.onSelection.accept(table.selected());
                refreshOutput();
            }
        });
        table.onSelection.accept(List.of());
        return p;
    }

    void show() {
        refresh();
        // Open on the queue when something is pending, otherwise on the history
        tabs.setSelectedIndex(app.queueSnapshot().isEmpty() ? 1 : 0);
        frame.setVisible(true);
        frame.setState(Frame.NORMAL);
        frame.toFront();
        try {
            Desktop.getDesktop().requestForeground(true);
        } catch (Exception ignored) {
        }
    }

    private void refresh() {
        queue.reload();
        done.reload();
        tabs.setTitleAt(0, "Cola (" + queue.all().size() + ")");
        tabs.setTitleAt(1, "Historial (" + done.all().size() + ")");
        pause.setSelected(app.isPaused());
        pause.setText(app.isPaused() ? "Reanudar cola" : "Pausar cola");
        refreshOutput();
    }

    private void refreshOutput() {
        JobTable t = tabs.getSelectedIndex() == 0 ? queue : done;
        List<Job> sel = t.selected();
        Job first = sel.isEmpty() ? null : sel.get(0);
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

    /** A job table that keeps its selection across reloads. */
    private static final class JobTable {
        final JTable table;
        private final Supplier<List<Job>> source;
        private final String[] columns;
        private List<Job> rows = List.of();
        java.util.function.Consumer<List<Job>> onSelection = sel -> {};
        private final SimpleDateFormat today = new SimpleDateFormat("HH:mm:ss");
        private final SimpleDateFormat older = new SimpleDateFormat("dd/MM HH:mm");

        JobTable(Supplier<List<Job>> source, String[] columns, int[] widths) {
            this.source = source;
            this.columns = columns;
            table = new JTable(new Model());
            table.setRowHeight(22);
            table.setFillsViewportHeight(true);
            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        }

        List<Job> all() {
            return rows;
        }

        List<Job> selected() {
            return Arrays.stream(table.getSelectedRows()).filter(r -> r < rows.size()).mapToObj(rows::get).toList();
        }

        void reload() {
            List<Job> before = selected();
            List<Job> next = source.get();
            boolean sameRows = next.equals(rows);
            rows = next;
            if (sameRows) {
                // only statuses / percentages changed: repaint without touching the selection
                ((AbstractTableModel) table.getModel()).fireTableRowsUpdated(0, Math.max(0, rows.size() - 1));
                return;
            }
            ((AbstractTableModel) table.getModel()).fireTableDataChanged();
            for (Job j : before) {
                int r = rows.indexOf(j);
                if (r >= 0) table.getSelectionModel().addSelectionInterval(r, r);
            }
        }

        private final class Model extends AbstractTableModel {
            @Override public int getRowCount() { return rows.size(); }
            @Override public int getColumnCount() { return columns.length; }
            @Override public String getColumnName(int c) { return columns[c]; }

            @Override
            public Object getValueAt(int row, int col) {
                Job j = rows.get(row);
                return switch (col) {
                    case 0 -> columns[0].equals("#") ? String.valueOf(row + 1)
                            : (System.currentTimeMillis() - j.createdAt < 86_400_000L ? today : older)
                                    .format(new Date(j.createdAt));
                    case 1 -> j.link.site().label;
                    case 2 -> j.statusText();
                    case 3 -> j.message;
                    default -> j.link.url();
                };
            }
        }

        private final class StatusRenderer extends DefaultTableCellRenderer {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                if (!sel && row < rows.size()) {
                    c.setForeground(switch (rows.get(row).status) {
                        case FAILED -> new Color(0xC62828);
                        case DONE -> new Color(0x2E7D32);
                        case DOWNLOADING -> new Color(0x1565C0);
                        case QUEUED, CANCELLED -> Color.GRAY;
                    });
                }
                return c;
            }
        }
    }
}
