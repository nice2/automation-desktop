package checker;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

import checker.Job.Circuit;
import checker.Job.Led;

//The "LEDs" tab: the LED corrections of the "Correcting LEDs" guide (see Leds) for the job version being worked on (the
//same one as the Correlate tab), with a tick per fix. Ticks stay pending, like correlation changes; "Save edited copy"
//writes both into one NEW file (EditedCopy), loaded in the configurator with Import Job.
@SuppressWarnings("serial")
public class LedPanel extends JPanel {

    interface Host {
        void status(String text);
        void save(); //saves the pending changes of this tab AND the Correlate tab into one new copy
        void changed(); //the ticked changes changed
    }

    //A LED state in a combo box
    static final class State {
        final int value;
        final String name;

        State(int value, String name) {
            this.value = value;
            this.name = name;
        }

        public String toString() {
            return name;
        }

        public boolean equals(Object o) {
            return o instanceof State && ((State) o).value == value;
        }

        public int hashCode() {
            return value;
        }
    }

    //A zone or proving group in a combo box; null obj = none
    static final class Pick {
        final Object obj;
        final String name;

        Pick(Object obj, String name) {
            this.obj = obj;
            this.name = name;
        }

        public String toString() {
            return name;
        }

        public boolean equals(Object o) {
            return o instanceof Pick && java.util.Objects.equals(((Pick) o).obj, obj);
        }

        public int hashCode() {
            return java.util.Objects.hashCode(obj);
        }
    }

    static final Pick NONE = new Pick(null, "(none)");

    private final Host host;
    private Job job;
    private Leds.ColorPlan plan;

    private final JLabel job_label = new JLabel(" ");
    private final JLabel pending_label = new JLabel(" ");
    private final JButton save_button = new JButton("Save edited copy...");
    private final JTabbedPane sections = new JTabbedPane();
    private int other_changes; //pending correlation changes (Correlate tab), saved together with these

    //FSAE
    private final PlanModel plan_model = new PlanModel();
    private final JTable plan_table = new JTable(plan_model);
    private final FsaeModel fsae_model = new FsaeModel();
    private final JTable fsae_table = new JTable(fsae_model);
    private final TableRowSorter<FsaeModel> fsae_sorter = new TableRowSorter<>(fsae_model);
    private final JCheckBox fsae_only_wrong = new JCheckBox("Only LEDs to fix", true);
    private final JLabel plan_label = new JLabel(" ");
    private final JButton plan_save = new JButton("Remember colors for this job");
    private final JButton plan_defaults = new JButton("Defaults");

    //Page select
    private final PageModel page_model = new PageModel();
    private final JTable page_table = new JTable(page_model);
    private final TableRowSorter<PageModel> page_sorter = new TableRowSorter<>(page_model);
    private final JCheckBox page_only_wrong = new JCheckBox("Only buttons to fix", true);

    //HOA
    private final HoaModel hoa_model = new HoaModel();
    private final JTable hoa_table = new JTable(hoa_model);
    private final JCheckBox hoa_steady = new JCheckBox("Also make every switch's red and green LED steady (Continuous)");

    public LedPanel(Host host) {
        super(new BorderLayout(0, 6));
        this.host = host;
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel top = new JPanel(new BorderLayout());
        top.add(job_label, BorderLayout.CENTER);
        JPanel save = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        save.add(pending_label);
        save.add(save_button);
        top.add(save, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        sections.addTab("FSAE colors", fsaeSection());
        sections.addTab("Page select", pageSection());
        sections.addTab("HOA switches", hoaSection());
        add(sections, BorderLayout.CENTER);

        save_button.setFont(save_button.getFont().deriveFont(java.awt.Font.BOLD));
        save_button.setToolTipText("Write the ticked LED fixes and the Correlate tab's changes into a NEW copy of the job file, for Import Job.");
        save_button.addActionListener(e -> host.save());
        setJob(null);
    }

    public Job job() {
        return job;
    }

    //-------------------------------------------------------------------------------------------------
    //Sections

    private JPanel fsaeSection() {
        plan_table.setRowHeight(22);
        plan_table.setFillsViewportHeight(true);
        plan_table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(LedColors.combo(steadyStates(null))));
        LedColors.columns(plan_table, 2);
        plan_table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        JPanel plan_box = new JPanel(new BorderLayout(0, 4));
        plan_box.setBorder(BorderFactory.createTitledBorder("Color plan: the color each kind of FSAE LED should be steady at"));
        JScrollPane plan_scroll = new JScrollPane(plan_table);
        plan_box.add(plan_scroll, BorderLayout.CENTER);
        JPanel plan_buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        plan_buttons.add(plan_save);
        plan_buttons.add(plan_defaults);
        plan_buttons.add(plan_label);
        plan_box.add(plan_buttons, BorderLayout.SOUTH);
        plan_save.addActionListener(e -> savePlan());
        plan_defaults.addActionListener(e -> {
            Leds.ColorPlan d = new Leds.ColorPlan();
            plan.colors.clear();
            plan.colors.putAll(d.colors);
            plan.leds.clear();
            planChanged();
        });

        fsae_table.setRowSorter(fsae_sorter);
        fsae_table.setRowHeight(20);
        fsae_table.setFillsViewportHeight(true);
        fsae_table.getColumnModel().getColumn(FsaeModel.WANT).setCellEditor(new DefaultCellEditor(LedColors.combo(steadyStates(null))));
        LedColors.columns(fsae_table, 8, FsaeModel.WANT);
        fsae_table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        fsae_only_wrong.addActionListener(e -> filterFsae());
        JPanel leds = new JPanel(new BorderLayout(0, 4));
        leds.setBorder(BorderFactory.createTitledBorder("FSAE graphic adder LEDs (a color set here is remembered for that LED)"));
        leds.add(tickBar(fsae_only_wrong, 0), BorderLayout.NORTH);
        leds.add(new JScrollPane(fsae_table), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, plan_box, leds);
        split.setResizeWeight(0.3);
        split.setBorder(null);
        JPanel p = new JPanel(new BorderLayout());
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JPanel pageSection() {
        page_table.setRowSorter(page_sorter);
        page_table.setRowHeight(20);
        page_table.setFillsViewportHeight(true);
        page_table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        page_only_wrong.addActionListener(e -> filterPage());
        JPanel p = new JPanel(new BorderLayout(0, 4));
        JLabel hint = new JLabel("<html>A page select button's red and green LEDs lose their amplifier links, so paging one floor lights only that floor's button. "
            + "The yellow (trouble) LED keeps them. Buttons whose only circuit is the amplifier are left as they are. FX6000 only.</html>");
        JPanel north = new JPanel(new BorderLayout());
        north.add(hint, BorderLayout.CENTER);
        north.add(tickBar(page_only_wrong, 1), BorderLayout.SOUTH);
        p.add(north, BorderLayout.NORTH);
        p.add(new JScrollPane(page_table), BorderLayout.CENTER);
        return p;
    }

    private JPanel hoaSection() {
        hoa_table.setRowHeight(22);
        hoa_table.setFillsViewportHeight(true);
        hoa_table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        LedColors.columns(hoa_table, 7);
        hoa_steady.addActionListener(e -> updatePending());
        JPanel p = new JPanel(new BorderLayout(0, 4));
        JLabel hint = new JLabel("<html>Red LED = the switch's RED zone, green = its GREEN zone, yellow = its proving group. Zones are suggested from the tags: "
            + "check them, change them in the table, then tick Fix. Only missing links are added. FX6000 only.</html>");
        JPanel north = new JPanel(new BorderLayout());
        north.add(hint, BorderLayout.CENTER);
        north.add(tickBar(hoa_steady, 2), BorderLayout.SOUTH);
        p.add(north, BorderLayout.NORTH);
        p.add(new JScrollPane(hoa_table), BorderLayout.CENTER);
        return p;
    }

    //"Tick all" / "Untick all" next to a section's filter
    private JPanel tickBar(JCheckBox box, int section) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton all = new JButton("Tick all to fix"), none = new JButton("Untick all");
        all.addActionListener(e -> tickAll(section, true));
        none.addActionListener(e -> tickAll(section, false));
        p.add(all);
        p.add(none);
        p.add(box);
        return p;
    }

    private void tickAll(int section, boolean on) {
        if (section == 0) {
            for (FsaeRow r : fsae_model.rows) r.fix = on && r.wrong();
            fsae_model.fireTableDataChanged();
            filterFsae();
        } else if (section == 1) {
            for (PageRow r : page_model.rows) r.fix = on && r.button.needsFix();
            page_model.fireTableDataChanged();
        } else {
            for (HoaRow r : hoa_model.rows) r.fix = on && r.missing();
            hoa_model.fireTableDataChanged();
        }
        updatePending();
    }

    private State[] steadyStates(Job j) {
        return new State[] { state(j, Leds.GREEN), state(j, Leds.YELLOW), state(j, Leds.RED) };
    }

    private static State state(Job j, int value) {
        return new State(value, Leds.stateName(j, value));
    }

    //-------------------------------------------------------------------------------------------------
    //Job

    //Shows the job's LEDs, nothing ticked. The same job again (re-checked, unchanged file) keeps the ticks and picks.
    public void setJob(Job j) {
        if (j != null && job != null && j.file.equals(job.file) && j.identity().equals(job.identity()) && j.file_stamp == job.file_stamp) {
            job = j;
            return;
        }
        job = j;
        boolean fsae = j != null && (j.panel == Job.Panel.FX4000 || j.panel == Job.Panel.FX6000);
        boolean fx6 = j != null && j.panel == Job.Panel.FX6000;
        plan = j == null ? new Leds.ColorPlan() : Leds.plan(j);
        plan_model.load(fsae ? Leds.fsaeLeds(j) : List.of());
        fsae_model.load(fsae ? Leds.fsaeLeds(j) : List.of());
        page_model.load(fx6 ? Leds.pageButtons(j) : List.of());
        hoa_model.load(fx6 ? Leds.hoaSwitches(j) : List.of());
        hoaEditors();
        hoa_steady.setSelected(false);
        if (j == null) {
            job_label.setText("<html><span style='color:gray'>Check a job version (FX4000 / FX6000), or press Correlate, to see its LEDs here.</span></html>");
        } else if (!fsae) {
            job_label.setText("<html><b>" + CheckerPanel.esc(title(j)) + "</b> &nbsp; <span style='color:gray'>LED fixes are for FX4000 and FX6000 jobs.</span></html>");
        } else {
            job_label.setText("<html><b>" + CheckerPanel.esc(title(j)) + "</b>" + (fx6 ? "" : " &nbsp; <span style='color:gray'>(page select and HOA fixes are FX6000 only)</span>") + "</html>");
        }
        sections.setEnabledAt(1, fx6);
        sections.setEnabledAt(2, fx6);
        if (!fx6) sections.setSelectedIndex(0);
        sections.setTitleAt(0, "FSAE colors" + count(fsae_model.toFix()));
        sections.setTitleAt(1, "Page select" + count(page_model.toFix()));
        sections.setTitleAt(2, "HOA switches" + count(hoa_model.toFix()));
        plan_save.setEnabled(fsae);
        plan_defaults.setEnabled(fsae);
        planLabel();
        filterFsae();
        filterPage();
        for (JTable t : new JTable[] { plan_table, fsae_table, page_table, hoa_table }) CheckerPanel.fitColumns(t, 300);
        updatePending();
    }

    private static String count(int n) {
        return n == 0 ? "" : " (" + n + ")";
    }

    private static String title(Job j) {
        return j.file.getName() + "   >   " + CheckerPanel.jobTitle(j);
    }

    private void planLabel() {
        plan_label.setText(job == null ? " " : plan.saved ? "Remembered for this job" : "Defaults (not remembered yet)");
    }

    private void savePlan() {
        if (job == null) return;
        try {
            Leds.savePlan(job, plan);
            planLabel();
            host.status("LED colors remembered for job " + job.job_id + ". Re-check to see them in the Check tab.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not save the colors:\n" + e.getMessage(), "LED colors", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void planChanged() {
        plan_model.fireTableDataChanged();
        fsae_model.replan();
        planLabel();
        filterFsae();
        updatePending();
    }

    private void filterFsae() {
        boolean only = fsae_only_wrong.isSelected();
        fsae_sorter.setRowFilter(!only ? null : new RowFilter<FsaeModel, Integer>() {
            public boolean include(Entry<? extends FsaeModel, ? extends Integer> e) {
                FsaeRow r = fsae_model.rows.get(e.getIdentifier());
                return r.wrong() || r.fix;
            }
        });
    }

    private void filterPage() {
        boolean only = page_only_wrong.isSelected();
        page_sorter.setRowFilter(!only ? null : new RowFilter<PageModel, Integer>() {
            public boolean include(Entry<? extends PageModel, ? extends Integer> e) {
                return page_model.rows.get(e.getIdentifier()).button.needsFix();
            }
        });
    }

    //Combo boxes of RED / GREEN zones and proving groups for the HOA table
    private void hoaEditors() {
        List<Pick> red = new ArrayList<>(List.of(NONE)), green = new ArrayList<>(List.of(NONE)), proving = new ArrayList<>(List.of(NONE));
        if (job != null) {
            for (Circuit z : Leds.colorZones(job, "red")) red.add(zonePick(z));
            for (Circuit z : Leds.colorZones(job, "green")) green.add(zonePick(z));
            for (Job.Proving p : job.provings.values()) proving.add(provingPick(job, p.group));
        }
        hoa_table.getColumnModel().getColumn(HoaModel.RED).setCellEditor(new DefaultCellEditor(new JComboBox<>(red.toArray(new Pick[0]))));
        hoa_table.getColumnModel().getColumn(HoaModel.GREEN).setCellEditor(new DefaultCellEditor(new JComboBox<>(green.toArray(new Pick[0]))));
        hoa_table.getColumnModel().getColumn(HoaModel.PROVING).setCellEditor(new DefaultCellEditor(new JComboBox<>(proving.toArray(new Pick[0]))));
    }

    static Pick zonePick(Circuit z) {
        return z == null ? NONE : new Pick(z, "IZ " + z.addr + "  " + z.cleanTag());
    }

    static Pick provingPick(Job j, Integer group) {
        if (group == null) return NONE;
        Job.Proving p = j.provings.get(group);
        return new Pick(group, "Group " + group + (p == null || p.zone == null ? "" : "  " + p.zone.cleanTag()));
    }

    //-------------------------------------------------------------------------------------------------
    //Pending changes and saving

    Leds.Edits edits() {
        Leds.Edits e = new Leds.Edits(job);
        if (job == null) return e;
        for (FsaeRow r : fsae_model.rows) if (r.fix && r.wrong()) e.setColor(r.f.led, r.want);
        for (PageRow r : page_model.rows) if (r.fix && r.button.needsFix()) e.fixPaging(r.button);
        for (HoaRow r : hoa_model.rows) {
            Leds.HoaSwitch s = r.sw;
            s.red_zone = (Circuit) r.red.obj;
            s.green_zone = (Circuit) r.green.obj;
            s.proving = (Integer) r.proving.obj;
            if (r.fix) e.fixHoa(s, false);
            if (hoa_steady.isSelected()) {
                e.setColor(s.red, Leds.RED);
                e.setColor(s.green, Leds.GREEN);
            }
        }
        return e;
    }

    private void updatePending() {
        refreshPending();
        host.changed();
    }

    //Correlation changes pending on the same job version, saved together with the ticked LED fixes
    void pendingElsewhere(int n) {
        other_changes = n;
        refreshPending();
    }

    private void refreshPending() {
        int n = job == null ? 0 : edits().size();
        String text = n == 0 ? "No LED fixes ticked" : Report.plural(n, "LED change") + " ticked";
        if (other_changes > 0) text += ", " + Report.plural(other_changes, "correlation change");
        pending_label.setText(text);
        pending_label.setForeground(n + other_changes == 0 ? CheckerPanel.MUTED_COLOR : CheckerPanel.CHANGED_COLOR);
        save_button.setEnabled(n + other_changes > 0);
    }

    public boolean hasChanges() {
        return job != null && !edits().isEmpty();
    }

    //Text for the window's Details pane
    void details(StringBuilder h) {
        h.append("<p><b>LED fixes</b> from the <i>Correcting LEDs</i> guide. Tick what to fix; ticks stay pending, like changes in the Correlate tab "
            + "(both tabs work on the same job version). <b>Save edited copy</b> writes everything pending into one new file, which you load in the configurator "
            + "with <b>Import Job</b>. The job file itself is never changed.</p>");
        h.append("<ul><li><b>FSAE colors</b> (FX4000 / FX6000): every graphic adder LED steady in its color. Colors depend on the FSAE plan: set them per kind of LED "
            + "(or per LED) and press <i>Remember colors for this job</i>; the Check tab then checks against them.</li>"
            + "<li><b>Page select</b> (FX6000): red/green LEDs of each button lose the shared amplifier link.</li>"
            + "<li><b>HOA switches</b> (FX6000): red/green LEDs show the switch's RED/GREEN zones, yellow its proving group.</li></ul>");
        if (job != null) {
            List<String> lines = edits().describe();
            if (!lines.isEmpty()) {
                h.append("<p><b>Ticked changes</b></p><ul>");
                for (String l : lines.subList(0, Math.min(40, lines.size()))) h.append("<li>").append(CheckerPanel.esc(l)).append("</li>");
                if (lines.size() > 40) h.append("<li>... ").append(lines.size() - 40).append(" more</li>");
                h.append("</ul>");
            }
        }
    }

    //-------------------------------------------------------------------------------------------------
    //Table models

    private final class PlanModel extends AbstractTableModel {
        final List<Leds.FsaeKind> kinds = new ArrayList<>();
        final Map<Leds.FsaeKind, Integer> counts = new EnumMap<>(Leds.FsaeKind.class);

        void load(List<Leds.FsaeLed> list) {
            kinds.clear();
            counts.clear();
            Map<Integer, Boolean> seen = new LinkedHashMap<>();
            for (Leds.FsaeLed f : list) {
                if (seen.put(f.led.group, true) != null) continue;
                counts.merge(f.kind, 1, Integer::sum);
            }
            for (Leds.FsaeKind k : Leds.FsaeKind.values()) if (counts.containsKey(k)) kinds.add(k);
            fireTableDataChanged();
        }

        public int getRowCount() {
            return kinds.size();
        }

        public int getColumnCount() {
            return 3;
        }

        public String getColumnName(int c) {
            return new String[] { "Kind of LED", "LEDs", "Steady color" }[c];
        }

        public Object getValueAt(int r, int c) {
            Leds.FsaeKind k = kinds.get(r);
            switch (c) {
                case 0: return k.label;
                case 1: return counts.get(k);
                default: return state(job, plan.colors.get(k));
            }
        }

        public boolean isCellEditable(int r, int c) {
            return c == 2;
        }

        public void setValueAt(Object v, int r, int c) {
            if (!(v instanceof State)) return;
            plan.colors.put(kinds.get(r), ((State) v).value);
            planChanged();
        }
    }

    private static final class FsaeRow {
        final Leds.FsaeLed f;
        int want;
        boolean fix;

        FsaeRow(Leds.FsaeLed f) {
            this.f = f;
        }

        boolean wrong() {
            return f.led.someActive() != want;
        }
    }

    private final class FsaeModel extends AbstractTableModel {
        static final int FIX = 0, WANT = 9;
        final List<FsaeRow> rows = new ArrayList<>();

        void load(List<Leds.FsaeLed> list) {
            rows.clear();
            Map<Integer, Boolean> seen = new LinkedHashMap<>();
            for (Leds.FsaeLed f : list) if (seen.put(f.led.group, true) == null) rows.add(new FsaeRow(f));
            replan();
        }

        void replan() {
            for (FsaeRow r : rows) {
                r.want = plan.expected(r.f);
                r.fix = r.fix && r.wrong();
            }
            fireTableDataChanged();
            sections.setTitleAt(0, "FSAE colors" + count(toFix()));
        }

        int toFix() {
            int n = 0;
            for (FsaeRow r : rows) if (r.wrong()) n++;
            return n;
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return 10;
        }

        public String getColumnName(int c) {
            return new String[] { "Fix", "Node", "CPU", "Frame", "LED", "Group", "Tag", "Kind", "Now", "Should be" }[c];
        }

        public Class<?> getColumnClass(int c) {
            return c == FIX ? Boolean.class : c >= 1 && c <= 5 ? Integer.class : Object.class;
        }

        public Object getValueAt(int i, int c) {
            FsaeRow r = rows.get(i);
            switch (c) {
                case 0: return r.fix && r.wrong();
                case 1: case 2: case 3: case 4: return r.f.slot[c - 1];
                case 5: return r.f.led.group;
                case 6: return r.f.led.tag.trim().replaceAll("\\s{2,}", " ");
                case 7: return r.f.kind.label;
                case 8: return Leds.stateName(job, r.f.led.someActive());
                default: return state(job, r.want);
            }
        }

        public boolean isCellEditable(int i, int c) {
            return (c == FIX && rows.get(i).wrong()) || c == WANT;
        }

        public void setValueAt(Object v, int i, int c) {
            FsaeRow r = rows.get(i);
            if (c == FIX) r.fix = Boolean.TRUE.equals(v);
            if (c == WANT && v instanceof State) {
                r.want = ((State) v).value;
                if (r.want == plan.colors.get(r.f.kind)) plan.leds.remove(r.f.key());
                else plan.leds.put(r.f.key(), r.want);
                r.fix = r.wrong();
                planLabel();
            }
            fireTableRowsUpdated(i, i);
            updatePending();
        }
    }

    private static final class PageRow {
        final Leds.PageButton button;
        boolean fix;

        PageRow(Leds.PageButton b) {
            button = b;
        }
    }

    private final class PageModel extends AbstractTableModel {
        final List<PageRow> rows = new ArrayList<>();

        void load(List<Leds.PageButton> list) {
            rows.clear();
            for (Leds.PageButton b : list) rows.add(new PageRow(b));
            fireTableDataChanged();
        }

        int toFix() {
            int n = 0;
            for (PageRow r : rows) if (r.button.needsFix()) n++;
            return n;
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return 6;
        }

        public String getColumnName(int c) {
            return new String[] { "Fix", "Button", "LED groups", "Amplifier links (red / green)", "Speaker circuits", "Status" }[c];
        }

        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : Object.class;
        }

        public Object getValueAt(int i, int c) {
            PageRow r = rows.get(i);
            Leds.PageButton b = r.button;
            switch (c) {
                case 0: return r.fix && b.needsFix();
                case 1: return b.name();
                case 2: return b.red.group + "-" + b.yellow.group;
                case 3: return b.amps_red.isEmpty() && b.amps_green.isEmpty() ? "" : LedRules.names(b.amps_red) + " / " + LedRules.names(b.amps_green);
                case 4: return LedRules.names(new ArrayList<>(b.own));
                default: return b.needsFix() ? "Lights from the amplifier" : b.own.isEmpty() ? "Amplifier only (left as is)" : "OK";
            }
        }

        public boolean isCellEditable(int i, int c) {
            return c == 0 && rows.get(i).button.needsFix();
        }

        public void setValueAt(Object v, int i, int c) {
            rows.get(i).fix = Boolean.TRUE.equals(v);
            fireTableRowsUpdated(i, i);
            updatePending();
        }
    }

    private static final class HoaRow {
        final Leds.HoaSwitch sw;
        Pick red, green, proving;
        boolean fix;

        HoaRow(Job j, Leds.HoaSwitch s) {
            sw = s;
            red = zonePick(s.red_zone);
            green = zonePick(s.green_zone);
            proving = provingPick(j, s.proving);
        }

        //A link this row would add
        boolean missing() {
            return (sw.red.inputs.isEmpty() && red.obj != null) || (sw.green.inputs.isEmpty() && green.obj != null) || (sw.yellow.provings.isEmpty() && proving.obj != null);
        }

        String status() {
            if (sw.complete()) return "Linked";
            List<String> m = new ArrayList<>();
            if (sw.red.inputs.isEmpty()) m.add("red");
            if (sw.green.inputs.isEmpty()) m.add("green");
            if (sw.yellow.provings.isEmpty()) m.add("yellow");
            return "No " + String.join(", ", m) + " link" + (missing() ? "" : " (no zone picked)");
        }
    }

    private final class HoaModel extends AbstractTableModel {
        static final int RED = 3, GREEN = 4, PROVING = 5;
        final List<HoaRow> rows = new ArrayList<>();

        void load(List<Leds.HoaSwitch> list) {
            rows.clear();
            for (Leds.HoaSwitch s : list) rows.add(new HoaRow(job, s));
            fireTableDataChanged();
        }

        int toFix() {
            int n = 0;
            for (HoaRow r : rows) if (r.fix) n++;
            return n;
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return 8;
        }

        public String getColumnName(int c) {
            return new String[] { "Fix", "Switch", "LED groups", "Red LED shows", "Green LED shows", "Yellow LED: proving", "Status", "Red / green now" }[c];
        }

        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : Object.class;
        }

        public Object getValueAt(int i, int c) {
            HoaRow r = rows.get(i);
            switch (c) {
                case 0: return r.fix && r.missing();
                case 1: return r.sw.name();
                case 2: return r.sw.red.group + "-" + r.sw.yellow.group;
                case RED: return r.red;
                case GREEN: return r.green;
                case PROVING: return r.proving;
                case 6: return r.status();
                default: return Leds.stateName(job, r.sw.red.someActive()) + " / " + Leds.stateName(job, r.sw.green.someActive());
            }
        }

        //Picks only where the LED has no link yet; existing links are changed in the configurator
        public boolean isCellEditable(int i, int c) {
            HoaRow r = rows.get(i);
            switch (c) {
                case 0: return r.missing();
                case RED: return r.sw.red.inputs.isEmpty();
                case GREEN: return r.sw.green.inputs.isEmpty();
                case PROVING: return r.sw.yellow.provings.isEmpty();
                default: return false;
            }
        }

        public void setValueAt(Object v, int i, int c) {
            HoaRow r = rows.get(i);
            if (c == 0) r.fix = Boolean.TRUE.equals(v);
            else if (v instanceof Pick) {
                if (c == RED) r.red = (Pick) v;
                if (c == GREEN) r.green = (Pick) v;
                if (c == PROVING) r.proving = (Pick) v;
                r.fix = r.missing();
            }
            fireTableRowsUpdated(i, i);
            updatePending();
        }
    }
}
