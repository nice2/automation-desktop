package checker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.prefs.Preferences;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import checker.Report.Finding;
import checker.Report.Severity;

//The correlation checker as a window panel. Left: the job files that are open, each with its jobs and their versions.
//Right: "Check" shows what the checks found in one version, "Compare" shows what changed between two versions.
//"Correlate" (CorrelatePanel) shows and changes one version's correlations; changes are only ever written to a NEW
//copy of the job file (JobWriter), never to the open file or a configurator's own database.
//Kept as a JPanel so it can sit in its own window (CheckerWindow) or as a tab in the bot window.
@SuppressWarnings("serial")
public class CheckerPanel extends JPanel {

    static final Color ERROR_COLOR = new Color(190, 0, 0);
    static final Color WARNING_COLOR = new Color(170, 105, 0);
    static final Color OK_COLOR = new Color(0, 125, 50);
    static final Color ADDED_COLOR = new Color(0, 125, 50);
    static final Color REMOVED_COLOR = new Color(190, 0, 0);
    static final Color CHANGED_COLOR = new Color(0, 90, 180);
    static final Color MUTED_COLOR = new Color(110, 110, 110);

    //The configurators' own databases, offered as shortcuts when they exist on this PC
    static final File FX4000_DB = new File("C:\\ProgramData\\Mircom Group of Companies\\MGC-4000 Configurator V2\\Database\\configurator.mdb");
    static final File FX6000_DB = new File("C:\\ProgramData\\Mircom Group of Companies\\MGC-Config_v14\\Database\\configurator.mdb");
    static final File FX400_DB = new File("C:\\ProgramData\\Mircom Group of Companies\\MGC-400 Series Configuration Tools\\configurator.mdb");
    static final File FX2000_DB = new File("C:\\Program Files (x86)\\Mircom Technologies\\FX-2000 Configuration Tools\\configurator.mdb");

    //Location columns shared by the findings and the comparison tables
    static final String[] PLACE_COLUMNS = { "Node", "CPU", "Loop", "Address", "Type", "Tag", "Location" };

    private final Preferences prefs = Preferences.userNodeForPackage(CheckerPanel.class);
    private final Map<String, Job> loaded = new LinkedHashMap<>(); //recently loaded job versions, by file/job/version
    private final Map<File, Long> stamps = new java.util.HashMap<>(); //when each open file was last read (its modified time then)
    private final JButton reload_button = new JButton("Reload");

    //Left: open files > jobs > versions
    private final DefaultMutableTreeNode tree_root = new DefaultMutableTreeNode("Files");
    private final DefaultTreeModel tree_model = new DefaultTreeModel(tree_root);
    private final JTree tree = new JTree(tree_model);
    private final JButton check_button = new JButton("Check");
    private final JButton compare_button = new JButton("Compare");

    private final JLabel status = new JLabel("Open a job file, or one of the configurator databases.");
    private final JProgressBar busy = new JProgressBar();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JEditorPane details = new JEditorPane("text/html", "");

    //Check tab
    private Job job;
    private Report report;
    private final JLabel job_label = new JLabel(" ");
    private final JLabel total_label = new JLabel(" ");
    private final RuleModel rule_model = new RuleModel();
    private final JTable rule_table = new JTable(rule_model);
    private final FindingModel finding_model = new FindingModel();
    private final JTable finding_table = wideTable(finding_model);
    private final TableRowSorter<FindingModel> finding_sorter = new TableRowSorter<>(finding_model);
    private final JCheckBox show_errors = new JCheckBox("Errors", true);
    private final JCheckBox show_warnings = new JCheckBox("Warnings", true);
    private final JTextField finding_search = new JTextField(12);
    private final JLabel finding_count = new JLabel(" ");
    private final JButton finding_csv = new JButton("Save CSV...");
    private final JCheckBox show_info = new JCheckBox("Info", false);
    private final JCheckBox show_accepted = new JCheckBox("Accepted", false);
    private final JButton accept_button = new JButton("Accept...");
    private final JButton unaccept_button = new JButton("Remove acceptance");

    //How a finding is shown in the Severity column (and sorted: problems first)
    enum Status {
        ERROR("Error"), WARNING("Warning"), REVIEW("Review"), INFO("Info"), ACCEPTED("Accepted");

        final String label;

        Status(String label) {
            this.label = label;
        }

        static Status of(Finding f) {
            if (f.isAccepted()) return ACCEPTED;
            if (f.stale) return REVIEW;
            return f.severity == Severity.ERROR ? ERROR : f.severity == Severity.WARNING ? WARNING : INFO;
        }

        Color color() {
            switch (this) {
                case ERROR: return ERROR_COLOR;
                case WARNING: return WARNING_COLOR;
                case REVIEW: return CHANGED_COLOR;
                default: return MUTED_COLOR;
            }
        }
    }

    //Compare tab
    private Compare compare;
    private final JLabel compare_label = new JLabel(" ");
    private final JLabel compare_total = new JLabel(" ");
    private final CategoryModel category_model = new CategoryModel();
    private final JTable category_table = new JTable(category_model);
    private final DiffModel diff_model = new DiffModel();
    private final JTable diff_table = wideTable(diff_model);
    private final TableRowSorter<DiffModel> diff_sorter = new TableRowSorter<>(diff_model);
    private final JCheckBox show_added = new JCheckBox("Added", true);
    private final JCheckBox show_removed = new JCheckBox("Removed", true);
    private final JCheckBox show_changed = new JCheckBox("Changed", true);
    private final JTextField diff_search = new JTextField(16);
    private final JLabel diff_count = new JLabel(" ");
    private final JButton diff_csv = new JButton("Save CSV...");

    //Correlate and LEDs tabs: the only places that change anything, both on the same job version, and only ever into a new
    //copy of the job file. Their changes stay pending until "Save edited copy" (either tab) writes them all together.
    private final JButton correlate_button = new JButton("Correlate");
    private final JButton close_button = new JButton("Close");
    private final CorrelatePanel correlate_panel = new CorrelatePanel(new CorrelatePanel.Host() {
        public void status(String text) {
            status.setText(text);
        }

        public void detailsChanged() {
            if (tabs.getSelectedIndex() == CORRELATE_TAB) showDetails();
        }

        public void save() {
            saveEdits();
        }

        public void changed() {
            pendingChanged();
        }

        public List<CorrelatePanel.Choice> choices() {
            List<CorrelatePanel.Choice> list = new ArrayList<>();
            Enumeration<?> e = tree_root.depthFirstEnumeration();
            List<VersionEntry> versions = new ArrayList<>();
            while (e.hasMoreElements()) {
                Object o = ((DefaultMutableTreeNode) e.nextElement()).getUserObject();
                if (o instanceof VersionEntry) versions.add((VersionEntry) o);
            }
            //Tree order (files, jobs newest first, versions newest first); depth-first visits children first, so restore it
            versions.sort((a, b) -> Integer.compare(treeIndex(a), treeIndex(b)));
            for (VersionEntry v : versions) {
                Job.Version jv = v.version;
                list.add(new CorrelatePanel.Choice(v, key(v), fileLabel(v.file) + "   >   " + versionName(jv) + ":  " + jv.description
                    + (jv.created.isEmpty() ? "" : "   (" + jv.created.replace('T', ' ') + ")")));
            }
            return list;
        }

        public void correlate(Object entry) {
            VersionEntry v = (VersionEntry) entry;
            TreePath p = pathOf(v);
            if (p != null) tree.setSelectionPath(p);
            runCorrelate(v);
        }
    });
    static final int CORRELATE_TAB = 2;

    private final LedPanel led_panel = new LedPanel(new LedPanel.Host() {
        public void status(String text) {
            status.setText(text);
        }

        public void save() {
            saveEdits();
        }

        public void changed() {
            pendingChanged();
        }
    });
    static final int LED_TAB = 3;

    //Row of a version in the expanded-or-not tree, for ordering
    private int treeIndex(VersionEntry v) {
        int i = 0;
        Enumeration<?> e = tree_root.preorderEnumeration();
        while (e.hasMoreElements()) {
            if (((DefaultMutableTreeNode) e.nextElement()).getUserObject() == v) return i;
            i++;
        }
        return i;
    }

    public CheckerPanel() {
        super(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(toolbar(), BorderLayout.NORTH);

        details.setEditable(false);
        details.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        details.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        JScrollPane details_scroll = new JScrollPane(details);
        details_scroll.setBorder(BorderFactory.createTitledBorder("Details"));
        details_scroll.setPreferredSize(new Dimension(400, 230));

        tabs.addTab("Check", checkTab());
        tabs.addTab("Compare", compareTab());
        tabs.addTab("Correlate", correlate_panel);
        tabs.addTab("LEDs", led_panel);
        tabs.addChangeListener(e -> showDetails());

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, details_scroll);
        right.setResizeWeight(0.72);
        right.setBorder(null);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, jobsPanel(), right);
        main.setResizeWeight(0); //extra width goes to the results
        main.setBorder(null);
        add(main, BorderLayout.CENTER);

        JLabel hint = new JLabel("Job files are never changed. Correlation and LED changes are saved together to a new copy, which you load in the configurator with Import Job.");
        hint.setForeground(MUTED_COLOR);
        add(hint, BorderLayout.SOUTH);
        clearCheck();
        clearCompare();

        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke("F5"), "reload");
        getActionMap().put("reload", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (reload_button.isEnabled()) reload();
            }
        });
        //Notice when the configurator saves to an open file, and point at Reload
        new javax.swing.Timer(3000, e -> watchFiles()).start();
    }

    private void watchFiles() {
        boolean changed = false;
        for (Map.Entry<File, Long> e : stamps.entrySet()) if (e.getKey().lastModified() != e.getValue()) changed = true;
        reload_button.setText(changed ? "Reload (changed)" : "Reload");
        reload_button.setForeground(changed ? CHANGED_COLOR : null);
        reload_button.setFont(reload_button.getFont().deriveFont(changed ? Font.BOLD : Font.PLAIN));
    }

    //-------------------------------------------------------------------------------------------------
    //Layout

    private JPanel toolbar() {
        JButton open = new JButton("Open job file...");
        open.addActionListener(e -> pickFile());
        JButton fx4000 = new JButton("FX4000 configurator");
        fx4000.setToolTipText("Every job in the MGC-4000 Configurator V2 on this PC");
        fx4000.addActionListener(e -> openFile(FX4000_DB));
        fx4000.setVisible(FX4000_DB.isFile());
        JButton fx6000 = new JButton("FX6000 configurator");
        fx6000.setToolTipText("Every job in the MGC Configurator v14 (FX6000 / FlexNet) on this PC");
        fx6000.addActionListener(e -> openFile(FX6000_DB));
        fx6000.setVisible(FX6000_DB.isFile());

        reload_button.setToolTipText("Read the open files again (F5), e.g. after saving in the configurator. Keeps the selected job and re-runs the check.");
        reload_button.addActionListener(e -> reload());
        reload_button.setEnabled(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(reload_button);
        buttons.add(open);
        buttons.add(fx4000);
        buttons.add(fx6000);
        buttons.add(shortcut("FX400 configurator", "Every job in the MGC-400 Series configurator on this PC", FX400_DB));
        buttons.add(shortcut("FX2000 configurator", "Every job in the FX-2000 configuration utility on this PC", FX2000_DB));

        busy.setIndeterminate(true);
        busy.setVisible(false);
        busy.setPreferredSize(new Dimension(110, 14));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(status);
        right.add(busy);

        JPanel p = new JPanel(new BorderLayout());
        p.add(buttons, BorderLayout.WEST);
        p.add(right, BorderLayout.CENTER);
        return p;
    }

    private JButton shortcut(String text, String tip, File db) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(e -> openFile(db));
        b.setVisible(db.isFile());
        return b;
    }

    private JPanel jobsPanel() {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setCellRenderer(new JobRenderer());
        tree.addTreeSelectionListener(e -> updateButtons());
        tree.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                //Double-click opens the version in the tab that is showing (Correlate stays on Correlate)
                if (e.getClickCount() == 2 && selectedVersions().size() == 1) {
                    if (tabs.getSelectedIndex() == CORRELATE_TAB) runCorrelate();
                    else runCheck();
                }
            }

            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            //Right-click: close the file under the mouse, or every file
            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p != null && !tree.isPathSelected(p)) tree.setSelectionPath(p);
                javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
                List<File> files = selectedFiles();
                javax.swing.JMenuItem close = new javax.swing.JMenuItem(files.size() == 1 ? "Close " + fileLabel(files.get(0)) : "Close " + Report.plural(files.size(), "file"));
                close.setEnabled(!files.isEmpty());
                close.addActionListener(a -> closeFiles(selectedFiles()));
                javax.swing.JMenuItem all = new javax.swing.JMenuItem("Close all");
                all.setEnabled(tree_root.getChildCount() > 0);
                all.addActionListener(a -> closeFiles(openFiles()));
                menu.add(close);
                menu.add(all);
                menu.show(tree, e.getX(), e.getY());
            }
        });

        check_button.setFont(check_button.getFont().deriveFont(Font.BOLD));
        check_button.setToolTipText("Run every check on the selected version (or double-click a version)");
        check_button.addActionListener(e -> runCheck());
        compare_button.setToolTipText("Two versions selected (Ctrl+click): what changed between them. One: what changed since the version before it.");
        compare_button.addActionListener(e -> runCompare());
        correlate_button.setToolTipText("See and change the selected version's correlations (saved to a new copy of the job)");
        correlate_button.addActionListener(e -> runCorrelate());
        //Two rows, so the buttons fit the narrow jobs list
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row1.add(check_button);
        row1.add(compare_button);
        close_button.setToolTipText("Close the selected file(s) or configurator(s) in this list (right-click for Close all). Files on disk are not touched.");
        close_button.addActionListener(e -> closeFiles(selectedFiles()));
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row2.add(correlate_button);
        row2.add(close_button);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(row1, BorderLayout.NORTH);
        buttons.add(row2, BorderLayout.SOUTH);

        JLabel hint = new JLabel("<html>Pick a version to check. Ctrl+click two versions to compare them. Correlate: see and change its correlations.</html>");
        hint.setForeground(MUTED_COLOR);
        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.NORTH);
        south.add(hint, BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        JScrollPane scroll = new JScrollPane(tree);
        p.setBorder(BorderFactory.createTitledBorder("Jobs"));
        p.add(scroll, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        p.setPreferredSize(new Dimension(200, 400));
        p.setMinimumSize(new Dimension(150, 200));
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        updateButtons();
        return p;
    }

    private JPanel checkTab() {
        JPanel header = header(job_label, total_label);

        rule_table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rule_table.setRowHeight(rule_table.getRowHeight() + 6);
        rule_table.setDefaultRenderer(Object.class, new RuleRenderer());
        rule_table.getColumnModel().getColumn(0).setPreferredWidth(122);
        rule_table.getColumnModel().getColumn(1).setPreferredWidth(88);
        rule_table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                filterFindings();
                showDetails();
            }
        });
        JScrollPane rule_scroll = new JScrollPane(rule_table);
        rule_scroll.setPreferredSize(new Dimension(212, 200));
        rule_scroll.setMinimumSize(new Dimension(180, 100));

        show_errors.setForeground(ERROR_COLOR);
        show_warnings.setForeground(WARNING_COLOR);
        show_errors.addActionListener(e -> filterFindings());
        show_warnings.addActionListener(e -> filterFindings());
        onType(finding_search, this::filterFindings);
        finding_csv.addActionListener(e -> saveFindings());
        show_info.setForeground(MUTED_COLOR);
        show_info.setToolTipText("Notes that are not problems, e.g. tags the checker could not read");
        show_accepted.setForeground(MUTED_COLOR);
        show_accepted.setToolTipText("Findings accepted earlier with a reason (special conditions)");
        show_info.addActionListener(e -> filterFindings());
        show_accepted.addActionListener(e -> filterFindings());
        accept_button.setToolTipText("Record that this finding is OK for this job, with the reason. It stays accepted in later versions while the device and problem stay the same.");
        accept_button.addActionListener(e -> acceptSelected());
        unaccept_button.addActionListener(e -> unacceptSelected());
        accept_button.setEnabled(false);
        unaccept_button.setEnabled(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JLabel accept_hint = new JLabel("Special condition for this job?");
        accept_hint.setForeground(MUTED_COLOR);
        actions.add(accept_hint);
        actions.add(accept_button);
        actions.add(unaccept_button);
        JPanel filters = filterBar(finding_count, finding_csv, show_errors, show_warnings, show_info, show_accepted, finding_search);

        finding_table.setRowSorter(finding_sorter);
        setupTable(finding_table, new FindingRenderer());
        finding_table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetails();
                updateAcceptButtons();
            }
        });

        JPanel findings = new JPanel(new BorderLayout(0, 4));
        findings.add(filters, BorderLayout.NORTH);
        findings.add(new JScrollPane(finding_table), BorderLayout.CENTER);
        findings.add(actions, BorderLayout.SOUTH);
        findings.setMinimumSize(new Dimension(300, 100)); //the filter bar must not push the check list closed

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rule_scroll, findings);
        split.setResizeWeight(0); //extra width goes to the findings, not the list of checks
        split.setBorder(null);
        JPanel p = new JPanel(new BorderLayout());
        p.add(header, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JPanel compareTab() {
        JPanel header = header(compare_label, compare_total);

        category_table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        category_table.setRowHeight(category_table.getRowHeight() + 6);
        category_table.setDefaultRenderer(Object.class, new CategoryRenderer());
        category_table.getColumnModel().getColumn(0).setPreferredWidth(150);
        category_table.getColumnModel().getColumn(1).setPreferredWidth(50);
        category_table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                filterDiffs();
                showDetails();
            }
        });
        JScrollPane category_scroll = new JScrollPane(category_table);
        category_scroll.setPreferredSize(new Dimension(205, 200));
        category_scroll.setMinimumSize(new Dimension(180, 100));

        show_added.setForeground(ADDED_COLOR);
        show_removed.setForeground(REMOVED_COLOR);
        show_changed.setForeground(CHANGED_COLOR);
        for (JCheckBox b : new JCheckBox[] { show_added, show_removed, show_changed }) b.addActionListener(e -> filterDiffs());
        onType(diff_search, this::filterDiffs);
        diff_csv.addActionListener(e -> saveDiffs());
        JPanel filters = filterBar(diff_count, diff_csv, show_added, show_removed, show_changed, diff_search);

        diff_table.setRowSorter(diff_sorter);
        setupTable(diff_table, new DiffRenderer());
        diff_table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showDetails();
        });

        JPanel diffs = new JPanel(new BorderLayout(0, 4));
        diffs.add(filters, BorderLayout.NORTH);
        diffs.add(new JScrollPane(diff_table), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, category_scroll, diffs);
        split.setResizeWeight(0); //extra width goes to the findings, not the list of checks
        split.setBorder(null);
        JPanel p = new JPanel(new BorderLayout());
        p.add(header, BorderLayout.NORTH);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    static JPanel header(JLabel title, JLabel total) {
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize() + 3f));
        total.setFont(total.getFont().deriveFont(Font.BOLD, total.getFont().getSize() + 1f));
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        header.add(title, BorderLayout.CENTER);
        header.add(total, BorderLayout.EAST);
        return header;
    }

    private static JPanel filterBar(JLabel count, Component csv, Component... controls) {
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(new JLabel("Show:"));
        for (Component c : controls) {
            if (c instanceof JTextField) {
                left.add(new JLabel("   Search:"));
                ((JTextField) c).setToolTipText("Matches any column, e.g. 40FL, IZ 131, waterflow, Loop 2");
            }
            left.add(c);
        }
        count.setForeground(MUTED_COLOR);
        left.add(count);
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(left, BorderLayout.CENTER);
        bar.add(csv, BorderLayout.EAST);
        return bar;
    }

    static void onType(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { action.run(); }
            public void removeUpdate(DocumentEvent e) { action.run(); }
            public void changedUpdate(DocumentEvent e) { action.run(); }
        });
    }

    //A table that scrolls sideways when its columns do not fit, and stretches its last column when they do
    static JTable wideTable(TableModel model) {
        JTable t = new JTable(model) {
            public boolean getScrollableTracksViewportWidth() {
                return getPreferredSize().width < getParent().getWidth();
            }
        };
        t.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        return t;
    }

    static void setupTable(JTable t, DefaultTableCellRenderer renderer) {
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setRowHeight(t.getRowHeight() + 6);
        t.setDefaultRenderer(Object.class, renderer);
        t.setDefaultRenderer(Severity.class, renderer);
        t.setDefaultRenderer(Compare.Change.class, renderer);
        t.setDefaultRenderer(Status.class, renderer);
        t.getTableHeader().setReorderingAllowed(true);
    }

    //Most a column may take, so the columns that matter (Tag, Location, Problem) get the room. Cut-off cells show in full as a tooltip.
    static final Map<String, Integer> MAX_WIDTH = Map.ofEntries(
        Map.entry("Severity", 66), Map.entry("Check", 110), Map.entry("Node", 38), Map.entry("CPU", 100), Map.entry("Loop", 40),
        Map.entry("Address", 64), Map.entry("Type", 125), Map.entry("Tag", 190), Map.entry("Location", 180),
        Map.entry("Change", 66), Map.entry("Category", 140), Map.entry("What", 420), Map.entry("Before", 220), Map.entry("After", 220));

    //Sizes each column to its contents (header and rows), within the limits above, so short columns stay short
    static void fitColumns(JTable t, int max_rows) {
        for (int c = 0; c < t.getColumnCount(); c++) {
            TableColumn col = t.getColumnModel().getColumn(c);
            Component h = t.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(t, col.getHeaderValue(), false, false, -1, c);
            int w = h.getPreferredSize().width + 10;
            int rows = Math.min(t.getRowCount(), max_rows);
            for (int r = 0; r < rows; r++) {
                Component comp = t.prepareRenderer(t.getCellRenderer(r, c), r, c);
                w = Math.max(w, comp.getPreferredSize().width + 6);
            }
            boolean last = c == t.getColumnCount() - 1;
            w = Math.min(w, last ? 1600 : MAX_WIDTH.getOrDefault(String.valueOf(col.getHeaderValue()), 300));
            //Node / CPU / Loop that are the same on every row (single-node jobs, everything on the main panel) are hidden;
            //the details pane still shows them
            String header = String.valueOf(col.getHeaderValue());
            boolean hide = false;
            if ((header.equals("Node") || header.equals("CPU") || header.equals("Loop")) && t.getRowCount() > 0) {
                hide = true;
                Object first = t.getValueAt(0, c);
                for (int r = 1; r < t.getRowCount() && hide; r++) hide = java.util.Objects.equals(first, t.getValueAt(r, c));
            }
            col.setMinWidth(hide ? 0 : 15);
            col.setMaxWidth(hide ? 0 : Integer.MAX_VALUE);
            if (hide) w = 0;
            col.setPreferredWidth(w);
            col.setWidth(w); //preferred widths alone are not applied to a table that is already laid out
        }
        t.revalidate();
        t.repaint();
    }

    //-------------------------------------------------------------------------------------------------
    //Files, jobs and versions

    private static class Source {
        final File file;

        Source(File file) {
            this.file = file;
        }
    }

    private static class JobEntry {
        final File file;
        final int job;
        final String description;
        final int versions;
        final String newest;

        JobEntry(File file, int job, String description, int versions, String newest) {
            this.file = file;
            this.job = job;
            this.description = description;
            this.versions = versions;
            this.newest = newest;
        }
    }

    private static class VersionEntry {
        final File file;
        final Job.Version version;

        VersionEntry(File file, Job.Version version) {
            this.file = file;
            this.version = version;
        }
    }

    private void pickFile() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        FileDialog dialog = new FileDialog(owner instanceof Frame ? (Frame) owner : null, "Open a configurator job (.mdb)", FileDialog.LOAD);
        dialog.setDirectory(prefs.get("lastDir", new File(System.getProperty("user.home"), "Downloads").getPath()));
        dialog.setFile("*.mdb");
        dialog.setVisible(true);
        if (dialog.getFile() == null) return;
        File picked = new File(dialog.getDirectory(), dialog.getFile());
        prefs.put("lastDir", picked.getParent());
        openFile(picked);
    }

    //Adds the file's jobs to the tree (replacing it if already open). A file with one version is checked straight away.
    public void openFile(File f) {
        openFile(f, true);
    }

    //show_check false: the check runs, but the tab that is showing stays (after saving an edited copy)
    private void openFile(File f, boolean show_check) {
        if (f == null || !f.isFile()) {
            status.setText("File not found: " + f);
            return;
        }
        working("Reading the jobs in " + f.getName() + "...");
        new SwingWorker<List<Job.Version>, Void>() {
            protected List<Job.Version> doInBackground() throws Exception {
                return Job.versions(f);
            }

            protected void done() {
                idle();
                List<Job.Version> versions;
                try {
                    versions = get();
                } catch (Exception e) {
                    failed("Could not read " + f.getName(), e);
                    return;
                }
                DefaultMutableTreeNode file_node = addToTree(f, versions);
                stamps.put(f, f.lastModified());
                reload_button.setEnabled(true);
                watchFiles();
                if (versions.size() == 1) {
                    DefaultMutableTreeNode v = (DefaultMutableTreeNode) file_node.getFirstChild().getChildAt(0);
                    tree.setSelectionPath(new TreePath(v.getPath()));
                    runCheck((VersionEntry) v.getUserObject(), show_check);
                } else {
                    status.setText(f.getName() + ": " + Report.plural(file_node.getChildCount(), "job") + ", " + versions.size() + " versions.");
                }
            }
        }.execute();
    }

    private DefaultMutableTreeNode addToTree(File f, List<Job.Version> versions) {
        //Replace a file that is already open, in the same place
        int at = 0;
        for (int i = 0; i < tree_root.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) tree_root.getChildAt(i);
            if (((Source) n.getUserObject()).file.equals(f)) {
                tree_model.removeNodeFromParent(n);
                at = i;
                break;
            }
        }
        DefaultMutableTreeNode file_node = new DefaultMutableTreeNode(new Source(f));
        //Jobs, most recently changed first; versions newest first
        Map<Integer, List<Job.Version>> by_job = new LinkedHashMap<>();
        for (Job.Version v : versions) by_job.computeIfAbsent(v.job, k -> new ArrayList<>()).add(v);
        List<List<Job.Version>> jobs = new ArrayList<>(by_job.values());
        for (List<Job.Version> list : jobs) list.sort((a, b) -> b.created.compareTo(a.created) != 0 ? b.created.compareTo(a.created) : Integer.compare(b.version, a.version));
        jobs.sort((a, b) -> b.get(0).created.compareTo(a.get(0).created));
        for (List<Job.Version> list : jobs) {
            Job.Version first = list.get(0);
            DefaultMutableTreeNode job_node = new DefaultMutableTreeNode(new JobEntry(f, first.job, first.description, list.size(), first.created));
            for (Job.Version v : list) job_node.add(new DefaultMutableTreeNode(new VersionEntry(f, v)));
            file_node.add(job_node);
        }
        tree_model.insertNodeInto(file_node, tree_root, at);
        tree.expandPath(new TreePath(file_node.getPath()));
        if (file_node.getChildCount() > 0) tree.expandPath(new TreePath(((DefaultMutableTreeNode) file_node.getFirstChild()).getPath()));
        return file_node;
    }

    private List<VersionEntry> selectedVersions() {
        List<VersionEntry> list = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) return list;
        for (TreePath p : paths) {
            Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            if (o instanceof VersionEntry) list.add((VersionEntry) o);
        }
        return list;
    }

    private void updateButtons() {
        int n = selectedVersions().size();
        check_button.setEnabled(n == 1);
        compare_button.setEnabled(n == 1 || n == 2);
        correlate_button.setEnabled(n == 1);
        close_button.setEnabled(!selectedFiles().isEmpty());
    }

    //The files (or configurator databases) of the selected tree rows, whatever level is selected
    private List<File> selectedFiles() {
        List<File> list = new ArrayList<>();
        TreePath[] paths = tree.getSelectionPaths();
        if (paths == null) return list;
        for (TreePath p : paths) {
            if (p.getPathCount() < 2) continue;
            Object o = ((DefaultMutableTreeNode) p.getPathComponent(1)).getUserObject();
            if (o instanceof Source && !list.contains(((Source) o).file)) list.add(((Source) o).file);
        }
        return list;
    }

    private List<File> openFiles() {
        List<File> list = new ArrayList<>();
        for (int i = 0; i < tree_root.getChildCount(); i++) list.add(((Source) ((DefaultMutableTreeNode) tree_root.getChildAt(i)).getUserObject()).file);
        return list;
    }

    //Takes files out of the list (nothing on disk changes); clears whatever was showing from them
    private void closeFiles(List<File> files) {
        if (files.isEmpty()) return;
        Job editing = correlate_panel.job();
        if (editing != null && files.contains(editing.file) && !confirmDropChanges()) return;
        for (File f : files) {
            for (int i = 0; i < tree_root.getChildCount(); i++) {
                DefaultMutableTreeNode n = (DefaultMutableTreeNode) tree_root.getChildAt(i);
                if (((Source) n.getUserObject()).file.equals(f)) {
                    tree_model.removeNodeFromParent(n);
                    break;
                }
            }
            stamps.remove(f);
            synchronized (loaded) {
                loaded.keySet().removeIf(k -> k.startsWith(f.getPath() + "|"));
            }
        }
        if (job != null && files.contains(job.file)) clearCheck();
        if (compare != null && (files.contains(compare.before.file) || files.contains(compare.after.file))) clearCompare();
        if (editing != null && files.contains(editing.file)) edit(null);
        reload_button.setEnabled(tree_root.getChildCount() > 0);
        watchFiles();
        updateButtons();
        showDetails();
        status.setText(files.size() == 1 ? "Closed " + fileLabel(files.get(0)) + "." : "Closed " + Report.plural(files.size(), "file") + ".");
    }

    //How a file is named in the list: the configurators by name, other files by file name
    static String fileLabel(File f) {
        return f.equals(FX4000_DB) ? "FX4000 configurator" : f.equals(FX6000_DB) ? "FX6000 configurator"
            : f.equals(FX400_DB) ? "FX400 configurator" : f.equals(FX2000_DB) ? "FX2000 configurator" : f.getName();
    }

    //The version before this one in the same job, or null
    private VersionEntry previousVersion(VersionEntry v) {
        Enumeration<?> e = tree_root.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            if (n.getUserObject() instanceof VersionEntry && ((VersionEntry) n.getUserObject()).version == v.version) {
                DefaultMutableTreeNode next = n.getNextSibling(); //versions are newest first, so the next sibling is older
                return next == null ? null : (VersionEntry) next.getUserObject();
            }
        }
        return null;
    }

    private Job load(VersionEntry v) throws Exception {
        String key = v.file.getPath() + "|" + v.version.job + "|" + v.version.version;
        Job j = loaded.get(key);
        if (j != null) return j;
        j = Job.load(v.file, v.version.job, v.version.version);
        synchronized (loaded) {
            loaded.put(key, j);
            if (loaded.size() > 6) loaded.remove(loaded.keySet().iterator().next());
        }
        return j;
    }

    //-------------------------------------------------------------------------------------------------
    //Reloading: read every open file again, keep the selection, re-run what is showing

    private static String key(File f, int job, int version) {
        return f.getPath() + "|" + job + "|" + version;
    }

    private static String key(VersionEntry v) {
        return key(v.file, v.version.job, v.version.version);
    }

    static String key(Job j) {
        return key(j.file, j.job_id, j.version);
    }

    private VersionEntry findVersion(String key) {
        Enumeration<?> e = tree_root.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            Object o = ((DefaultMutableTreeNode) e.nextElement()).getUserObject();
            if (o instanceof VersionEntry && key((VersionEntry) o).equals(key)) return (VersionEntry) o;
        }
        return null;
    }

    private TreePath pathOf(Object user_object) {
        Enumeration<?> e = tree_root.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            if (n.getUserObject() == user_object) return new TreePath(n.getPath());
        }
        return null;
    }

    private void reload() {
        List<File> files = new ArrayList<>();
        for (int i = 0; i < tree_root.getChildCount(); i++) files.add(((Source) ((DefaultMutableTreeNode) tree_root.getChildAt(i)).getUserObject()).file);
        if (files.isEmpty()) return;

        //What to put back afterwards
        List<String> selected = new ArrayList<>();
        for (VersionEntry v : selectedVersions()) selected.add(key(v));
        List<String> expanded = new ArrayList<>();
        Enumeration<?> e = tree_root.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            if (n.getUserObject() instanceof JobEntry && tree.isExpanded(new TreePath(n.getPath()))) {
                JobEntry j = (JobEntry) n.getUserObject();
                expanded.add(j.file.getPath() + "|" + j.job);
            }
        }
        String checked = job == null ? null : key(job);
        String[] compared = compare == null ? null : new String[] { key(compare.before), key(compare.after) };
        int tab = tabs.getSelectedIndex();

        synchronized (loaded) {
            loaded.clear(); //everything is read again
        }
        reload_button.setEnabled(false);
        working("Reloading " + Report.plural(files.size(), "file") + "...");
        Map<File, Long> read_at = new java.util.concurrent.ConcurrentHashMap<>();
        new SwingWorker<Map<File, List<Job.Version>>, Void>() {
            protected Map<File, List<Job.Version>> doInBackground() throws Exception {
                Map<File, List<Job.Version>> map = new LinkedHashMap<>();
                for (File f : files) {
                    read_at.put(f, f.lastModified()); //before reading, so a save during the read still shows as changed
                    map.put(f, Job.versions(f));
                }
                return map;
            }

            protected void done() {
                idle();
                reload_button.setEnabled(true);
                Map<File, List<Job.Version>> map;
                try {
                    map = get();
                } catch (Exception ex) {
                    failed("Could not reload. If the configurator is saving right now, wait a moment and press Reload again", ex);
                    return;
                }
                for (Map.Entry<File, List<Job.Version>> m : map.entrySet()) addToTree(m.getKey(), m.getValue());
                stamps.putAll(read_at);
                watchFiles();

                Enumeration<?> en = tree_root.depthFirstEnumeration();
                while (en.hasMoreElements()) {
                    DefaultMutableTreeNode n = (DefaultMutableTreeNode) en.nextElement();
                    if (n.getUserObject() instanceof JobEntry) {
                        JobEntry j = (JobEntry) n.getUserObject();
                        if (expanded.contains(j.file.getPath() + "|" + j.job)) tree.expandPath(new TreePath(n.getPath()));
                    }
                }
                List<TreePath> paths = new ArrayList<>();
                for (String k : selected) {
                    VersionEntry v = findVersion(k);
                    if (v != null) paths.add(pathOf(v));
                }
                tree.setSelectionPaths(paths.toArray(new TreePath[0]));

                List<String> gone = new ArrayList<>();
                if (compared != null) {
                    VersionEntry a = findVersion(compared[0]), b = findVersion(compared[1]);
                    if (a != null && b != null) runCompare(a, b, false);
                    else gone.add("the compared versions");
                }
                if (checked != null) {
                    VersionEntry v = findVersion(checked);
                    if (v != null) runCheck(v, false);
                    else gone.add("the checked version");
                }
                tabs.setSelectedIndex(tab);
                String time = java.time.LocalTime.now().withNano(0).toString();
                status.setText("Reloaded at " + time + "." + (gone.isEmpty() ? "" : " Not found any more: " + String.join(", ", gone) + "."));
            }
        }.execute();
    }

    //-------------------------------------------------------------------------------------------------
    //Correlating

    private void runCorrelate() {
        List<VersionEntry> sel = selectedVersions();
        if (sel.size() == 1) runCorrelate(sel.get(0));
    }

    private void runCorrelate(VersionEntry v) {
        if (!confirmDropChanges()) return;
        correlate_button.setEnabled(false);
        working("Reading " + versionName(v.version) + " for correlating...");
        new SwingWorker<Job, Void>() {
            protected Job doInBackground() throws Exception {
                //Read fresh, so the edits are made on the file as it is now
                return Job.load(v.file, v.version.job, v.version.version);
            }

            protected void done() {
                idle();
                updateButtons();
                try {
                    Job j = get();
                    edit(j);
                    tabs.setSelectedIndex(CORRELATE_TAB);
                    showDetails();
                    status.setText(j.panel.isReadable() ? "Correlating " + versionName(j) + ". Changes are saved to a new copy." : "This job's panel type is not supported.");
                } catch (Exception e) {
                    failed("Could not read the job. If the configurator is saving right now, try again, or export the job and open the export", e);
                }
            }
        }.execute();
    }

    //Asks before unsaved correlation changes / ticked LED fixes are dropped; true = go ahead
    public boolean confirmDropChanges() {
        if (!hasChanges()) return true;
        tabs.setSelectedIndex(correlate_panel.hasChanges() ? CORRELATE_TAB : LED_TAB);
        int answer = JOptionPane.showConfirmDialog(this, pendingText() + " to " + versionName(correlate_panel.job()) + " not saved. Drop them?",
            "Unsaved changes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        return answer == JOptionPane.OK_OPTION;
    }

    private boolean hasChanges() {
        return correlate_panel.hasChanges() || led_panel.hasChanges();
    }

    private String pendingText() {
        List<String> parts = new ArrayList<>();
        Correlations.Edits c = correlate_panel.edits();
        if (c != null && !c.isEmpty()) parts.add(Report.plural(c.size(), "correlation change"));
        Leds.Edits l = led_panel.edits();
        if (!l.isEmpty()) parts.add(Report.plural(l.size(), "LED change"));
        return String.join(" and ", parts);
    }

    //The job version the Correlate and LEDs tabs work on (both always the same one)
    private void edit(Job j) {
        correlate_panel.setJob(j);
        led_panel.setJob(j);
        pendingChanged();
    }

    private static boolean sameVersion(Job a, Job b) {
        return a != null && b != null && a.file.equals(b.file) && a.job_id == b.job_id && a.version == b.version && a.file_stamp == b.file_stamp;
    }

    //Each tab shows the other's pending count, and either one's Save button saves both
    private void pendingChanged() {
        if (correlate_panel == null || led_panel == null) return; //while the window is being built
        Correlations.Edits c = correlate_panel.edits();
        correlate_panel.pendingElsewhere(led_panel.job() == null ? 0 : led_panel.edits().size());
        led_panel.pendingElsewhere(c == null ? 0 : c.size());
        if (tabs.getSelectedIndex() == CORRELATE_TAB || tabs.getSelectedIndex() == LED_TAB) showDetails();
    }

    //Writes every pending change of the edited version - correlations and LED fixes - into one new copy
    private void saveEdits() {
        Job j = correlate_panel.job();
        if (j == null || !hasChanges()) return;
        Correlations.Edits corr = correlate_panel.edits();
        Leds.Edits leds = led_panel.edits();
        File target = EditedCopy.pickTarget(this, j, prefs.get("lastDir", System.getProperty("user.home")));
        if (target == null) return;
        prefs.put("lastDir", target.getParent());
        try {
            JobWriter.checkTarget(j.file, target);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Save edited copy", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> lines = new ArrayList<>();
        List<String> corr_lines = correlate_panel.changeLines();
        if (!corr_lines.isEmpty()) {
            lines.add("CORRELATIONS (" + corr_lines.size() + ")");
            for (String l : corr_lines) lines.add("  " + l);
        }
        List<String> led_lines = leds.describe();
        if (!led_lines.isEmpty()) {
            if (!lines.isEmpty()) lines.add("");
            lines.add("LEDs (" + led_lines.size() + ")");
            for (String l : led_lines) lines.add("  " + l);
        }
        javax.swing.JTextArea list = new javax.swing.JTextArea(String.join("\n", lines), Math.min(14, lines.size() + 1), 90);
        list.setEditable(false);
        String what = pendingText();
        Object[] message = { "<html>Write " + esc(what) + " into a new file:<br><b>" + esc(target.getPath())
            + "</b><br><br>" + esc(j.file.getName()) + " is not changed. Load the new file in the configurator with <b>Import Job</b>.</html>",
            new JScrollPane(list) };
        if (JOptionPane.showConfirmDialog(this, message, "Save edited copy", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        working("Saving " + target.getName() + "...");
        new SwingWorker<Job, Void>() {
            protected Job doInBackground() throws Exception {
                EditedCopy.save(j, corr, leds, target);
                return Job.load(target, j.job_id, j.version);
            }

            protected void done() {
                idle();
                Job saved;
                try {
                    saved = get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    status.setText("Not saved.");
                    JOptionPane.showMessageDialog(CheckerPanel.this, "Not saved:\n" + cause.getMessage(), "Save edited copy", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                correlate_panel.savedAs(saved); //carry on editing the saved copy
                led_panel.setJob(saved);
                pendingChanged();
                openFile(target, false);
                status.setText("Saved " + what + " to " + target.getName() + ". Load it in the configurator with Import Job.");
                JOptionPane.showMessageDialog(CheckerPanel.this, "Saved " + what + " to\n" + target.getPath()
                    + "\n\nIn the configurator use Import Job and pick this file; it comes in as a new version.\nThe Correlate and LEDs tabs now work on the saved copy.",
                    "Save edited copy", JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    //-------------------------------------------------------------------------------------------------
    //Checking

    private void runCheck() {
        List<VersionEntry> sel = selectedVersions();
        if (sel.size() != 1) return;
        runCheck(sel.get(0), true);
    }

    private void runCheck(VersionEntry v, boolean show_tab) {
        check_button.setEnabled(false);
        working("Checking " + versionName(v.version) + "...");
        new SwingWorker<Object[], Void>() {
            protected Object[] doInBackground() throws Exception {
                Job j = load(v);
                return new Object[] { j, CorrelationChecker.run(j, null) };
            }

            protected void done() {
                idle();
                updateButtons();
                try {
                    Object[] result = get();
                    showCheck((Job) result[0], (Report) result[1]);
                    if (show_tab) tabs.setSelectedIndex(0);
                } catch (Exception e) {
                    failed("Could not check the job. If the configurator is saving right now, try again, or export the job and open the export", e);
                }
            }
        }.execute();
    }

    private void showCheck(Job j, Report r) {
        Rule was_selected = job != null && job.identity().equals(j.identity()) ? selectedRule() : null;
        job = j;
        report = r;
        if (!sameVersion(j, correlate_panel.job())) {
            if (!hasChanges()) edit(j); //the Correlate and LEDs tabs follow the checked version, unless changes are pending there
            else status.setText("Correlate / LEDs tabs still on " + versionName(correlate_panel.job()) + ": " + pendingText() + " not saved.");
        }
        job_label.setText(jobTitle(j));
        int errors = r.count(Severity.ERROR), warnings = r.count(Severity.WARNING);
        if (r.rulesRun() == 0) {
            total_label.setText("Not checked");
            total_label.setForeground(MUTED_COLOR);
        } else if (errors + warnings == 0) {
            total_label.setText("All checks passed");
            total_label.setForeground(OK_COLOR);
        } else {
            total_label.setText(Report.plural(errors, "error") + "   " + Report.plural(warnings, "warning"));
            total_label.setForeground(errors > 0 ? ERROR_COLOR : WARNING_COLOR);
        }
        int accepted = 0;
        for (Finding f : r.findings()) if (f.isAccepted()) accepted++;
        if (accepted > 0) total_label.setText(total_label.getText() + "   (" + accepted + " accepted)");
        rule_model.load(r);
        finding_model.load(r);
        finding_sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING))); //errors first, then report order
        finding_csv.setEnabled(!finding_model.rows.isEmpty());
        int select = 0;
        for (int i = 0; i < rule_model.rows.size(); i++) if (was_selected != null && rule_model.rows.get(i).rule == was_selected) select = i;
        rule_table.getSelectionModel().setSelectionInterval(select, select);
        filterFindings();
        fitColumns(finding_table, 400); //after filtering, so the rows are there to measure
        updateAcceptButtons();
        showDetails();
        status.setText(r.rulesRun() == 0 ? "Nothing was checked: there are no checks for " + j.panel.label + " yet." : "Checked " + versionName(j) + ".");
    }

    private void clearCheck() {
        job = null;
        report = null;
        rule_model.rows.clear();
        rule_model.fireTableDataChanged();
        finding_model.rows.clear();
        finding_model.fireTableDataChanged();
        job_label.setText("Select a job version on the left and press Check.");
        total_label.setText(" ");
        finding_count.setText(" ");
        finding_csv.setEnabled(false);
        accept_button.setEnabled(false);
        unaccept_button.setEnabled(false);
    }

    private Rule selectedRule() {
        int row = rule_table.getSelectedRow();
        return row < 0 ? null : rule_model.rows.get(row).rule;
    }

    private void filterFindings() {
        Rule rule = selectedRule();
        boolean errors = show_errors.isSelected(), warnings = show_warnings.isSelected();
        boolean info = show_info.isSelected(), accepted = show_accepted.isSelected();
        String text = finding_search.getText().trim().toLowerCase(Locale.ROOT);
        finding_sorter.setRowFilter(new RowFilter<FindingModel, Integer>() {
            public boolean include(Entry<? extends FindingModel, ? extends Integer> entry) {
                Finding f = finding_model.rows.get(entry.getIdentifier());
                boolean this_check = rule != null && f.rule.equals(rule.id());
                if (rule != null && !this_check) return false;
                switch (Status.of(f)) {
                    case ERROR: if (!errors) return false; break;
                    case WARNING: if (!warnings) return false; break;
                    case INFO: if (!info && !this_check) return false; break; //a check's own notes show when it is selected
                    case ACCEPTED: if (!accepted && !this_check) return false; break;
                    default:
                }
                return text.isEmpty() || rowText(entry).contains(text);
            }
        });
        finding_count.setText(report == null ? " " : "   " + finding_table.getRowCount() + " of " + finding_model.rows.size() + " shown");
    }

    private static String rowText(RowFilter.Entry<?, ?> entry) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < entry.getValueCount(); i++) s.append(entry.getStringValue(i)).append(' ');
        return s.toString().toLowerCase(Locale.ROOT);
    }

    //-------------------------------------------------------------------------------------------------
    //Comparing

    private void runCompare() {
        List<VersionEntry> sel = selectedVersions();
        VersionEntry a, b;
        if (sel.size() == 2) {
            a = sel.get(0);
            b = sel.get(1);
            //Older first: by date, then version number
            int order = a.version.created.compareTo(b.version.created);
            if (order > 0 || (order == 0 && a.version.version > b.version.version)) {
                VersionEntry t = a;
                a = b;
                b = t;
            }
        } else if (sel.size() == 1) {
            b = sel.get(0);
            a = previousVersion(b);
            if (a == null) {
                JOptionPane.showMessageDialog(this, "This is the oldest version of the job. Ctrl+click a second version (any job or file) to compare with.",
                    "Compare", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        } else {
            return;
        }
        runCompare(a, b, true);
    }

    private void runCompare(VersionEntry from, VersionEntry to, boolean show_tab) {
        compare_button.setEnabled(false);
        working("Comparing " + versionName(from.version) + " with " + versionName(to.version) + "...");
        new SwingWorker<Compare, Void>() {
            protected Compare doInBackground() throws Exception {
                return new Compare(load(from), load(to));
            }

            protected void done() {
                idle();
                updateButtons();
                try {
                    showCompare(get());
                    if (show_tab) tabs.setSelectedIndex(1);
                } catch (Exception e) {
                    failed("Could not compare the versions", e);
                }
            }
        }.execute();
    }

    private void showCompare(Compare c) {
        compare = c;
        compare_label.setText(versionName(c.before) + "   >   " + versionName(c.after) + "      " + c.after.description);
        int added = 0, removed = 0, changed = 0;
        for (Compare.Diff d : c.diffs) {
            if (d.change == Compare.Change.ADDED) added++;
            else if (d.change == Compare.Change.REMOVED) removed++;
            else changed++;
        }
        compare_total.setText(c.diffs.isEmpty() ? "No differences" : added + " added   " + removed + " removed   " + changed + " changed");
        compare_total.setForeground(c.diffs.isEmpty() ? OK_COLOR : CHANGED_COLOR);
        category_model.load(c);
        diff_model.load(c);
        diff_csv.setEnabled(!c.diffs.isEmpty());
        category_table.getSelectionModel().setSelectionInterval(0, 0);
        filterDiffs();
        fitColumns(diff_table, 400);
        showDetails();
        String note = c.before.panel != c.after.panel ? " (different panels: addresses will not line up)" : "";
        status.setText("Compared " + versionName(c.before) + " with " + versionName(c.after) + note + ".");
    }

    private void clearCompare() {
        compare = null;
        category_model.rows.clear();
        category_model.fireTableDataChanged();
        diff_model.rows.clear();
        diff_model.fireTableDataChanged();
        compare_label.setText("Ctrl+click two versions on the left and press Compare.");
        compare_total.setText(" ");
        diff_count.setText(" ");
        diff_csv.setEnabled(false);
    }

    private void filterDiffs() {
        int row = category_table.getSelectedRow();
        String category = row <= 0 ? null : category_model.rows.get(row);
        boolean added = show_added.isSelected(), removed = show_removed.isSelected(), changed = show_changed.isSelected();
        String text = diff_search.getText().trim().toLowerCase(Locale.ROOT);
        diff_sorter.setRowFilter(new RowFilter<DiffModel, Integer>() {
            public boolean include(Entry<? extends DiffModel, ? extends Integer> entry) {
                Compare.Diff d = diff_model.rows.get(entry.getIdentifier());
                if (category != null && !d.category.equals(category)) return false;
                if (d.change == Compare.Change.ADDED && !added) return false;
                if (d.change == Compare.Change.REMOVED && !removed) return false;
                if (d.change == Compare.Change.CHANGED && !changed) return false;
                return text.isEmpty() || rowText(entry).contains(text);
            }
        });
        diff_count.setText(compare == null ? " " : "   " + diff_table.getRowCount() + " of " + diff_model.rows.size() + " shown");
    }

    //-------------------------------------------------------------------------------------------------
    //Details pane

    private void showDetails() {
        StringBuilder h = new StringBuilder("<html><body style='font-family:sans-serif; margin:4px'>");
        if (tabs.getSelectedIndex() == 0) findingDetails(h);
        else if (tabs.getSelectedIndex() == CORRELATE_TAB) correlate_panel.details(h);
        else if (tabs.getSelectedIndex() == LED_TAB) led_panel.details(h);
        else diffDetails(h);
        h.append("</body></html>");
        details.setText(h.toString());
        details.setCaretPosition(0);
    }

    private void findingDetails(StringBuilder h) {
        if (report == null) return;
        int row = finding_table.getSelectedRow();
        if (row < 0) {
            Rule rule = selectedRule();
            List<Rule> rules = rule == null ? report.rules() : List.of(rule);
            if (rule == null) h.append("<p style='color:gray'>Click a finding to see it in full. Click a check on the left to see only its findings.</p>");
            for (Rule r : rules) {
                h.append("<p><b>").append(esc(r.name())).append("</b> &nbsp;<span style='color:gray'>").append(esc(r.title())).append("</span>");
                for (String n : report.notes(r.id())) h.append("<br>&nbsp;&nbsp;").append(esc(n));
                h.append("</p>");
            }
            if (rule == null && !report.skipped.isEmpty()) {
                h.append("<p style='color:gray'>Not used on ").append(esc(job.panel.label)).append(": ").append(esc(String.join(", ", report.skipped))).append("</p>");
            }
            return;
        }
        Finding f = finding_model.rows.get(finding_table.convertRowIndexToModel(row));
        Status st = Status.of(f);
        h.append("<p><b style='color:").append(hex(st.color())).append("'>").append(st.label).append("</b>")
            .append(" &nbsp;<span style='color:gray'>").append(esc(ruleName(f.rule))).append("</span></p>");
        h.append("<p style='font-size:larger'><b>").append(esc(f.summary)).append("</b></p>");
        placeTable(h, f.place);
        if (!f.more.isEmpty()) h.append("<p>").append(esc(f.more).replace("\n", "<br>")).append("</p>");
        if (!f.items.isEmpty()) {
            h.append("<p><b>").append(esc(f.items_title)).append("</b> (").append(f.items.size()).append(")</p>");
            itemsTable(h, f.items);
        }
        if (!f.why.isEmpty()) h.append("<p style='color:gray'>Why: ").append(esc(f.why)).append("</p>");
        if (f.isAccepted()) {
            h.append("<p style='background:#eeeeee'><b>Accepted:</b> ").append(esc(f.acceptance.reason()).replace("\n", "<br>"))
                .append("<br><span style='color:gray'>In ").append(esc(f.acceptance.when()))
                .append(". Stays accepted while this device and problem stay the same.</span></p>");
        } else if (f.stale) {
            h.append("<p style='color:gray'>Accepted in ").append(esc(f.acceptance.when()))
                .append(". Press Remove acceptance once reviewed, or accept the new finding again.</p>");
        } else if (f.severity != Severity.INFO) {
            h.append("<p style='color:gray'>If this is intended for this job, press Accept and give the reason.</p>");
        }
    }

    //-------------------------------------------------------------------------------------------------
    //Accepting findings (special conditions)

    private Finding selectedFinding() {
        int row = finding_table.getSelectedRow();
        return row < 0 ? null : finding_model.rows.get(finding_table.convertRowIndexToModel(row));
    }

    private void updateAcceptButtons() {
        Finding f = selectedFinding();
        accept_button.setEnabled(f != null && f.acceptance == null && f.severity != Severity.INFO);
        unaccept_button.setEnabled(f != null && f.acceptance != null);
    }

    private void acceptSelected() {
        Finding f = selectedFinding();
        if (f == null || job == null) return;
        javax.swing.JTextArea reason = new javax.swing.JTextArea(4, 40);
        reason.setLineWrap(true);
        reason.setWrapStyleWord(true);
        Object[] message = { "<html><b>" + esc(f.summary) + "</b><br>" + esc(f.place.text()) + "<br><br>Why is this OK for this job?</html>", new JScrollPane(reason) };
        while (true) {
            int answer = JOptionPane.showConfirmDialog(this, message, "Accept finding", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
            if (!reason.getText().isBlank()) break;
            JOptionPane.showMessageDialog(this, "Please give a reason, so whoever checks this job later knows why.", "Accept finding", JOptionPane.WARNING_MESSAGE);
        }
        try {
            Acceptances.accept(job, f, reason.getText());
            recheck("Accepted. Saved in " + Acceptances.file(job).getPath());
        } catch (Exception e) {
            failed("Could not save the acceptance", e);
        }
    }

    private void unacceptSelected() {
        Finding f = selectedFinding();
        if (f == null || f.acceptance == null || job == null) return;
        int answer = JOptionPane.showConfirmDialog(this, "Remove the acceptance \"" + f.acceptance.reason() + "\"?", "Remove acceptance",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        try {
            Acceptances.remove(job, f.acceptance);
            recheck("Acceptance removed.");
        } catch (Exception e) {
            failed("Could not remove the acceptance", e);
        }
    }

    //Re-runs the checks on the job that is showing (already loaded, so this is quick)
    private void recheck(String message) {
        Report r = CorrelationChecker.run(job, null);
        showCheck(job, r);
        status.setText(message);
    }

    private void diffDetails(StringBuilder h) {
        if (compare == null) return;
        int row = diff_table.getSelectedRow();
        if (row < 0) {
            h.append("<p style='color:gray'>Click a change to see it in full. Click a category on the left to see only its changes.</p>");
            h.append("<p><b>Before:</b> ").append(esc(jobTitle(compare.before))).append("<br><b>After:</b> ").append(esc(jobTitle(compare.after))).append("</p>");
            return;
        }
        Compare.Diff d = diff_model.rows.get(diff_table.convertRowIndexToModel(row));
        Color c = changeColor(d.change);
        h.append("<p><b style='color:").append(hex(c)).append("'>").append(changeName(d.change)).append("</b> &nbsp;<span style='color:gray'>")
            .append(esc(d.category)).append("</span></p>");
        h.append("<p style='font-size:larger'><b>").append(esc(d.what)).append("</b></p>");
        placeTable(h, d.place);
        if (d.change == Compare.Change.CHANGED) {
            h.append("<table cellpadding=3><tr><td valign=top style='color:gray'>Before</td><td>").append(esc(d.before.isEmpty() ? "-" : d.before)).append("</td></tr>");
            h.append("<tr><td valign=top style='color:gray'>After</td><td>").append(esc(d.after.isEmpty() ? "-" : d.after)).append("</td></tr></table>");
        }
    }

    //The place as one compact row under its column names (empty columns left out)
    private static void placeTable(StringBuilder h, Place p) {
        String[] values = placeValues(p);
        StringBuilder head = new StringBuilder(), cells = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null || values[i].isEmpty()) continue;
            head.append("<td style='color:gray'>").append(PLACE_COLUMNS[i]).append("&nbsp;&nbsp;</td>");
            cells.append("<td>").append(esc(values[i])).append("&nbsp;&nbsp;</td>");
        }
        h.append("<table cellpadding=1 cellspacing=0><tr>").append(head).append("</tr><tr>").append(cells).append("</tr></table>");
    }

    private static void itemsTable(StringBuilder h, List<Place> items) {
        h.append("<table cellpadding=2 cellspacing=0 border=0><tr style='color:gray'>");
        for (String c : PLACE_COLUMNS) h.append("<td><u>").append(c).append("</u></td>");
        h.append("</tr>");
        int shown = 0;
        for (Place p : items) {
            if (shown++ == 200) {
                h.append("<tr><td colspan=7 style='color:gray'>... and ").append(items.size() - 200).append(" more</td></tr>");
                break;
            }
            h.append("<tr>");
            for (String v : placeValues(p)) h.append("<td>").append(esc(v)).append("&nbsp;&nbsp;</td>");
            h.append("</tr>");
        }
        h.append("</table>");
    }

    //Shorter forms for the table columns: "S200 QLA (CPU 1)" -> "S200 QLA 1", "Main panel (CPU 0)" -> "Main",
    //"Loop 2" -> "2", "Frame 5" -> "F5". The details pane and CSV keep the full text.
    static String[] tableValues(Place p) {
        String cpu = p.cpu.startsWith("Main panel") ? "Main" : p.cpu.replaceAll("\\s*\\(CPU (\\d+)\\)$", " $1");
        String loop = p.loop.replaceFirst("^Loop ", "").replaceFirst("^Frame ", "F");
        return new String[] { p.nodeText(), cpu, loop, p.address, p.type, p.tag, p.location };
    }

    static String[] placeValues(Place p) {
        return new String[] { p.nodeText(), p.cpu, p.loop, p.address, p.type, p.tag, p.location };
    }

    //-------------------------------------------------------------------------------------------------
    //CSV

    private void saveFindings() {
        if (report == null) return;
        File out = saveDialog(job.job_id + "-" + String.format("%02d", job.version) + " correlation check.csv");
        if (out == null) return;
        try {
            report.writeCsv(out);
            status.setText("Saved " + out.getName());
        } catch (Exception e) {
            failed("Could not save " + out.getName(), e);
        }
    }

    private void saveDiffs() {
        if (compare == null) return;
        File out = saveDialog(compare.before.job_id + "-" + String.format("%02d", compare.before.version) + " vs " + compare.after.job_id + "-"
            + String.format("%02d", compare.after.version) + " changes.csv");
        if (out == null) return;
        try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8)) {
            w.print('\uFEFF');
            w.println("Change,Category,Node,CPU,Loop,Address,Type,Tag,Location,What,Before,After");
            for (Compare.Diff d : compare.diffs) {
                List<String> cells = new ArrayList<>(List.of(changeName(d.change), d.category));
                for (String v : placeValues(d.place)) cells.add(v);
                cells.add(d.what);
                cells.add(d.before);
                cells.add(d.after);
                List<String> quoted = new ArrayList<>();
                for (String c : cells) quoted.add("\"" + c.replace("\"", "\"\"").replaceAll("\\s*\\n\\s*", " ") + "\"");
                w.println(String.join(",", quoted));
            }
            status.setText("Saved " + out.getName());
        } catch (Exception e) {
            failed("Could not save " + out.getName(), e);
        }
    }

    private File saveDialog(String name) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        FileDialog dialog = new FileDialog(owner instanceof Frame ? (Frame) owner : null, "Save as CSV", FileDialog.SAVE);
        dialog.setDirectory(prefs.get("lastDir", System.getProperty("user.home")));
        dialog.setFile(name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        dialog.setVisible(true);
        if (dialog.getFile() == null) return null;
        String file = dialog.getFile().toLowerCase(Locale.ROOT).endsWith(".csv") ? dialog.getFile() : dialog.getFile() + ".csv";
        return new File(dialog.getDirectory(), file);
    }

    //-------------------------------------------------------------------------------------------------
    //Helpers

    private void working(String text) {
        status.setText(text);
        busy.setVisible(true);
    }

    private void idle() {
        busy.setVisible(false);
    }

    private void failed(String what, Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        status.setText(what + ".");
        JOptionPane.showMessageDialog(this, what + ":\n" + cause.getMessage(), "Correlation checker", JOptionPane.ERROR_MESSAGE);
    }

    static String versionName(Job.Version v) {
        return "Job " + v.job + " " + (v.version_description.isBlank() ? "version " + v.version : v.version_description.trim());
    }

    static String versionName(Job j) {
        return "Job " + j.job_id + " " + (j.version_description.isBlank() ? "version " + j.version : j.version_description.trim());
    }

    static String jobTitle(Job j) {
        return versionName(j) + ":  " + j.description + "   -   " + j.panel.label;
    }

    private static String ruleName(String id) {
        for (Rule r : CorrelationChecker.RULES) if (r.id().equals(id)) return r.name();
        return id;
    }

    static String changeName(Compare.Change c) {
        return c == Compare.Change.ADDED ? "Added" : c == Compare.Change.REMOVED ? "Removed" : "Changed";
    }

    static Color changeColor(Compare.Change c) {
        return c == Compare.Change.ADDED ? ADDED_COLOR : c == Compare.Change.REMOVED ? REMOVED_COLOR : CHANGED_COLOR;
    }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    //-------------------------------------------------------------------------------------------------
    //Tree and table models

    private class JobRenderer extends DefaultTreeCellRenderer {
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            String gray = selected ? "" : " color='#707070'";
            if (o instanceof Source) {
                File f = ((Source) o).file;
                setText("<html><b>" + esc(fileLabel(f)) + "</b></html>");
                setToolTipText(f.getPath());
            } else if (o instanceof JobEntry) {
                JobEntry j = (JobEntry) o;
                setText("<html><b>" + esc(j.description.isBlank() ? "(no name)" : j.description) + "</b><br><font" + gray + ">Job " + j.job + "  -  "
                    + Report.plural(j.versions, "version") + "</font></html>");
                setToolTipText("<html><b>" + esc(j.description) + "</b><br>Job " + j.job + ", last saved " + esc(j.newest.replace('T', ' ')) + "</html>");
            } else if (o instanceof VersionEntry) {
                Job.Version v = ((VersionEntry) o).version;
                String name = v.version_description.isBlank() ? "version " + v.version : v.version_description.trim();
                setText("<html>" + esc(name) + "  <font" + gray + ">" + esc(v.created.replace('T', ' ')) + "</font></html>");
                setToolTipText("Job " + v.job + ", version number " + v.version);
            }
            setIcon(null);
            return this;
        }
    }

    private static class RuleRow {
        final Rule rule; //null = every check
        final int errors, warnings;
        final boolean skipped;
        int info, accepted, review;

        RuleRow(Rule rule, int errors, int warnings, boolean skipped) {
            this.rule = rule;
            this.errors = errors;
            this.warnings = warnings;
            this.skipped = skipped;
        }

        RuleRow counts(Report r) {
            for (Finding f : r.findings()) {
                if (rule != null && !f.rule.equals(rule.id())) continue;
                Status s = Status.of(f);
                if (s == Status.INFO) info++;
                if (s == Status.ACCEPTED) accepted++;
                if (s == Status.REVIEW) review++;
            }
            return this;
        }

        //Short, for the narrow Result column; the full counts are in the tooltip
        String result() {
            if (skipped) return "n/a";
            List<String> parts = new ArrayList<>();
            if (errors > 0) parts.add(errors + " err");
            if (warnings - review > 0) parts.add((warnings - review) + " warn");
            if (review > 0) parts.add(review + " review");
            if (parts.isEmpty()) parts.add(info > 0 ? info + " info" : "OK");
            if (accepted > 0) parts.add("+" + accepted + " acc");
            return String.join(", ", parts);
        }

        String fullResult() {
            if (skipped) return "Does not apply to this panel";
            List<String> parts = new ArrayList<>();
            parts.add(Report.plural(errors, "error"));
            parts.add(Report.plural(warnings - review, "warning"));
            if (review > 0) parts.add(review + " to review");
            if (accepted > 0) parts.add(accepted + " accepted");
            if (info > 0) parts.add(info + " info");
            return String.join(", ", parts);
        }
    }

    private static class RuleModel extends AbstractTableModel {
        final List<RuleRow> rows = new ArrayList<>();

        void load(Report r) {
            rows.clear();
            rows.add(new RuleRow(null, r.count(Severity.ERROR), r.count(Severity.WARNING), false).counts(r));
            for (Rule rule : r.rules()) rows.add(new RuleRow(rule, r.count(rule.id(), Severity.ERROR), r.count(rule.id(), Severity.WARNING), false).counts(r));
            //Checks that do not apply to this panel (e.g. FSAE on FX400) are not listed; the details pane names them
            fireTableDataChanged();
        }

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 2; }
        public String getColumnName(int c) { return c == 0 ? "Check" : "Result"; }

        public Object getValueAt(int row, int col) {
            RuleRow r = rows.get(row);
            if (col == 0) return r.rule == null ? "All checks" : r.rule.name();
            return r.result();
        }
    }

    private class RuleRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, false, row, col);
            RuleRow r = rule_model.rows.get(row);
            setToolTipText((r.rule == null ? "Every check" : r.rule.title()) + ": " + r.fullResult());
            setFont(getFont().deriveFont(r.rule == null ? Font.BOLD : Font.PLAIN));
            if (!selected) {
                if (col == 0) setForeground(r.skipped ? MUTED_COLOR : Color.BLACK);
                else setForeground(r.skipped ? MUTED_COLOR : r.errors > 0 ? ERROR_COLOR : r.warnings - r.review > 0 ? WARNING_COLOR
                    : r.review > 0 ? CHANGED_COLOR : r.info > 0 ? MUTED_COLOR : OK_COLOR);
            }
            return this;
        }
    }

    //Shared by both result tables: leading columns, then the place columns, then trailing columns
    private abstract static class PlaceTableModel<T> extends AbstractTableModel {
        final List<T> rows = new ArrayList<>();
        final String[] lead, trail;

        PlaceTableModel(String[] lead, String[] trail) {
            this.lead = lead;
            this.trail = trail;
        }

        abstract Place place(T row);
        abstract Object lead(T row, int col);
        abstract Object trail(T row, int col);

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return lead.length + PLACE_COLUMNS.length + trail.length; }

        public String getColumnName(int c) {
            if (c < lead.length) return lead[c];
            c -= lead.length;
            if (c < PLACE_COLUMNS.length) return PLACE_COLUMNS[c];
            return trail[c - PLACE_COLUMNS.length];
        }

        public Object getValueAt(int row, int c) {
            T t = rows.get(row);
            if (c < lead.length) return lead(t, c);
            c -= lead.length;
            if (c < PLACE_COLUMNS.length) {
                Place p = place(t);
                if (c == 0) return p.node < 0 ? "" : p.node; //sorts as a number
                return tableValues(p)[c];
            }
            return trail(t, c - PLACE_COLUMNS.length);
        }

        public Class<?> getColumnClass(int c) {
            if (rows.isEmpty()) return Object.class;
            Object v = getValueAt(0, c);
            return v instanceof Severity ? Severity.class : v instanceof Compare.Change ? Compare.Change.class : v instanceof Status ? Status.class : Object.class;
        }
    }

    private static class FindingModel extends PlaceTableModel<Finding> {
        FindingModel() {
            super(new String[] { "Severity", "Check" }, new String[] { "Problem" });
        }

        void load(Report r) {
            rows.clear();
            rows.addAll(r.findings());
            fireTableStructureChanged();
        }

        Place place(Finding f) { return f.place; }
        Object lead(Finding f, int c) { return c == 0 ? Status.of(f) : ruleName(f.rule); }

        Object trail(Finding f, int c) {
            String s = f.summary + (f.items.isEmpty() ? "" : "  (" + f.items.size() + ")");
            return f.isAccepted() ? s + "  -  accepted: " + f.acceptance.reason().replace('\n', ' ') : s;
        }
    }

    private static class DiffModel extends PlaceTableModel<Compare.Diff> {
        DiffModel() {
            super(new String[] { "Change", "Category" }, new String[] { "What", "Before", "After" });
        }

        void load(Compare c) {
            rows.clear();
            rows.addAll(c.diffs);
            fireTableStructureChanged();
        }

        Place place(Compare.Diff d) { return d.place; }
        Object lead(Compare.Diff d, int c) { return c == 0 ? d.change : d.category; }

        Object trail(Compare.Diff d, int c) {
            String v = c == 0 ? d.what : c == 1 ? d.before : d.after;
            return v.length() > 120 ? v.substring(0, 117) + "..." : v;
        }
    }

    private static class CategoryModel extends AbstractTableModel {
        final List<String> rows = new ArrayList<>(); //first row = all
        final List<Integer> counts = new ArrayList<>();

        void load(Compare c) {
            rows.clear();
            counts.clear();
            rows.add("All changes");
            counts.add(c.diffs.size());
            for (String cat : Compare.CATEGORIES) {
                rows.add(cat);
                counts.add(c.count(cat));
            }
            fireTableDataChanged();
        }

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 2; }
        public String getColumnName(int c) { return c == 0 ? "Category" : "Changes"; }
        public Object getValueAt(int row, int col) { return col == 0 ? rows.get(row) : counts.get(row); }
    }

    private class CategoryRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, false, row, col);
            int n = category_model.counts.get(row);
            setFont(getFont().deriveFont(row == 0 ? Font.BOLD : Font.PLAIN));
            setHorizontalAlignment(col == 1 ? RIGHT : LEFT);
            if (!selected) setForeground(n == 0 ? MUTED_COLOR : col == 1 ? CHANGED_COLOR : Color.BLACK);
            return this;
        }
    }

    //Colors the first column and shows the full cell text as a tooltip (for cut-off cells)
    static class ColoredRenderer extends DefaultTableCellRenderer {
        final Function<Object, Color> color;

        ColoredRenderer(Function<Object, Color> color) {
            this.color = color;
        }

        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int col) {
            String text = value instanceof Severity ? (value == Severity.ERROR ? "Error" : "Warning")
                : value instanceof Status ? ((Status) value).label
                : value instanceof Compare.Change ? changeName((Compare.Change) value) : value == null ? "" : String.valueOf(value);
            super.getTableCellRendererComponent(table, text, selected, false, row, col);
            int width = table.getColumnModel().getColumn(col).getWidth();
            setToolTipText(!text.isEmpty() && getFontMetrics(getFont()).stringWidth(text) + 6 > width ? text : null);
            boolean first = table.convertColumnIndexToModel(col) == 0;
            setFont(getFont().deriveFont(first ? Font.BOLD : Font.PLAIN));
            boolean faded = value instanceof Status ? value == Status.ACCEPTED : table.getValueAt(row, 0) == Status.ACCEPTED;
            if (!selected) setForeground(first ? color.apply(table.getValueAt(row, 0)) : faded ? MUTED_COLOR : Color.BLACK);
            return this;
        }
    }

    private static class FindingRenderer extends ColoredRenderer {
        FindingRenderer() {
            super(v -> v instanceof Status ? ((Status) v).color() : v == Severity.ERROR ? ERROR_COLOR : WARNING_COLOR);
        }
    }

    private static class DiffRenderer extends ColoredRenderer {
        DiffRenderer() {
            super(v -> v instanceof Compare.Change ? changeColor((Compare.Change) v) : Color.BLACK);
        }
    }
}
