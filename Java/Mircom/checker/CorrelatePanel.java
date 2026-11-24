package checker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

import checker.Correlations.Edits;
import checker.Correlations.Link;
import checker.Correlations.Role;
import checker.Correlations.Type;
import checker.Job.Circuit;
import checker.Job.ControlGroup;
import checker.Job.Equation;
import checker.Job.Kind;
import checker.Job.Led;

//The "Correlate" tab: see everything correlated to any input, input zone, output or LED of a job, and add or
//remove correlations the way the configurator allows (Correlations has the rules). Changes stay pending (together
//with the LEDs tab's ticked fixes for the same job version) until
//"Save edited copy", which writes a NEW file (EditedCopy) to load into the configurator with Import Job.
@SuppressWarnings("serial")
public class CorrelatePanel extends JPanel {

    //What the panel needs from the window around it
    interface Host {
        void status(String text);
        void detailsChanged();
        void save(); //saves the pending changes of this tab AND the LEDs tab into one new copy
        void changed(); //the pending changes changed
        List<Choice> choices(); //every job version in the open files
        void correlate(Object entry); //load this version into the tab (asks first if there are unsaved changes)
    }

    //A job version in the job picker at the top of the tab
    static final class Choice {
        final Object entry; //the window's own handle for the version; null = nothing to load
        final String key; //file|job|version
        final String text;

        Choice(Object entry, String key, String text) {
            this.entry = entry;
            this.key = key;
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final Host host;
    private Job job;
    private Edits edits;

    //Which job is being correlated; opening it lists every version in the open files
    private final JComboBox<Choice> job_box = new JComboBox<>();
    private boolean filling_job_box;
    private final JLabel pending = new JLabel(" ");
    private final JButton save_button = new JButton("Save edited copy...");
    private final JButton discard_button = new JButton("Discard changes");

    //Items to pick from
    private final JComboBox<ItemKind> item_kind = new JComboBox<>();
    private final JTextField item_search = new JTextField(10);
    private final JLabel item_count = new JLabel(" ");
    private final ItemModel item_model = new ItemModel();
    private final JTable item_table = CheckerPanel.wideTable(item_model);
    private final TableRowSorter<ItemModel> item_sorter = new TableRowSorter<>(item_model);

    //What the picked item is correlated to
    private final JLabel links_title = new JLabel(" ");
    private final LinkModel link_model = new LinkModel();
    private final JTable link_table = CheckerPanel.wideTable(link_model);
    private final JButton remove_button = new JButton("Remove");
    private final JButton undo_button = new JButton("Undo");

    //What can be added
    private final JComboBox<Role> role_box = new JComboBox<>();
    private final JTextField cand_search = new JTextField(10);
    private final JCheckBox show_blocked = new JCheckBox("Show not allowed");
    private final JLabel cand_count = new JLabel(" ");
    private final CandidateModel cand_model = new CandidateModel();
    private final JTable cand_table = CheckerPanel.wideTable(cand_model);
    private final TableRowSorter<CandidateModel> cand_sorter = new TableRowSorter<>(cand_model);
    private final JButton add_button = new JButton("Add");
    private final JLabel no_roles = new JLabel(" ");
    private String last_role = "";

    CorrelatePanel(Host host) {
        super(new BorderLayout());
        this.host = host;

        job_box.setFont(job_box.getFont().deriveFont(Font.BOLD, job_box.getFont().getSize() + 2f));
        job_box.setToolTipText("The job version whose correlations are shown. Click to pick another version from the open files.");
        job_box.setMaximumRowCount(20);
        job_box.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                fillJobBox(true);
            }

            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });
        job_box.addActionListener(e -> {
            if (filling_job_box) return;
            Choice c = (Choice) job_box.getSelectedItem();
            if (c == null || c.entry == null || c.key.equals(currentKey())) return;
            SwingUtilities.invokeLater(() -> {
                fillJobBox(false); //shows the current job until the picked one has loaded
                host.correlate(c.entry);
            });
        });
        pending.setFont(pending.getFont().deriveFont(Font.BOLD));
        save_button.setFont(save_button.getFont().deriveFont(Font.BOLD));
        save_button.setToolTipText("Write the changes into a NEW copy of the job file. Load the copy in the configurator with Import Job; the open file is not changed.");
        save_button.addActionListener(e -> host.save());
        discard_button.addActionListener(e -> discard());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(pending);
        buttons.add(discard_button);
        buttons.add(save_button);
        JLabel job_label = new JLabel("Job:");
        job_label.setFont(job_label.getFont().deriveFont(Font.BOLD, job_label.getFont().getSize() + 2f));
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        header.add(job_label, BorderLayout.WEST);
        header.add(job_box, BorderLayout.CENTER);
        header.add(buttons, BorderLayout.EAST);

        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, linksPanel(), addPanel());
        right.setResizeWeight(0.45);
        right.setBorder(null);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, itemsPanel(), right);
        split.setResizeWeight(0.36);
        split.setBorder(null);

        add(header, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        setJob(null);
    }

    //-------------------------------------------------------------------------------------------------
    //Layout

    private JPanel itemsPanel() {
        item_kind.addActionListener(e -> loadItems());
        CheckerPanel.onType(item_search, this::filterItems);
        item_search.setToolTipText("Matches any column, e.g. 40FL, IZ 131, low heat, Loop 2");
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.add(item_kind);
        bar.add(new JLabel("Search:"));
        bar.add(item_search);
        item_count.setForeground(CheckerPanel.MUTED_COLOR);
        bar.add(item_count);

        item_table.setRowSorter(item_sorter);
        CheckerPanel.setupTable(item_table, new CheckerPanel.ColoredRenderer(v -> Color.BLACK));
        item_table.setDefaultRenderer(Integer.class, item_table.getDefaultRenderer(Object.class)); //numbers on the left too
        fillWith(item_table, "Location");
        item_table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showItem();
        });

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder("Pick an item"));
        p.add(bar, BorderLayout.NORTH);
        p.add(new JScrollPane(item_table), BorderLayout.CENTER);
        p.setMinimumSize(new Dimension(250, 100));
        return p;
    }

    private JPanel linksPanel() {
        link_table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        link_table.setRowHeight(link_table.getRowHeight() + 6);
        link_table.setDefaultRenderer(Object.class, new CheckerPanel.ColoredRenderer(v -> v instanceof State ? ((State) v).color : Color.BLACK));
        link_table.setDefaultRenderer(State.class, link_table.getDefaultRenderer(Object.class));
        link_table.setDefaultRenderer(Integer.class, link_table.getDefaultRenderer(Object.class));
        fillWith(link_table, "Note");
        link_table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateButtons();
        });
        remove_button.setToolTipText("Remove the selected correlations (saved only with Save edited copy)");
        remove_button.addActionListener(e -> removeSelected());
        undo_button.setToolTipText("Put the selected correlations back the way they were loaded");
        undo_button.addActionListener(e -> undoSelected());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(remove_button);
        buttons.add(undo_button);

        links_title.setFont(links_title.getFont().deriveFont(Font.BOLD));
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder("Correlated to"));
        p.add(links_title, BorderLayout.NORTH);
        p.add(new JScrollPane(link_table), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private JPanel addPanel() {
        role_box.addActionListener(e -> {
            Role r = (Role) role_box.getSelectedItem();
            if (r != null) last_role = r.label;
            loadCandidates();
        });
        CheckerPanel.onType(cand_search, this::filterCandidates);
        cand_search.setToolTipText("Matches any column, e.g. 40FL, IZ 131, low heat, Loop 2");
        show_blocked.setForeground(CheckerPanel.MUTED_COLOR);
        show_blocked.setToolTipText("Also list what the configurator would refuse, with the reason");
        show_blocked.addActionListener(e -> filterCandidates());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.add(role_box);
        bar.add(new JLabel("Search:"));
        bar.add(cand_search);
        bar.add(show_blocked);
        cand_count.setForeground(CheckerPanel.MUTED_COLOR);
        bar.add(cand_count);

        cand_table.setRowSorter(cand_sorter);
        cand_table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        cand_table.setRowHeight(cand_table.getRowHeight() + 6);
        cand_table.setDefaultRenderer(Object.class, new CheckerPanel.ColoredRenderer(v -> v instanceof Allowed ? ((Allowed) v).color : Color.BLACK));
        cand_table.setDefaultRenderer(Allowed.class, cand_table.getDefaultRenderer(Object.class));
        cand_table.setDefaultRenderer(Integer.class, cand_table.getDefaultRenderer(Object.class));
        fillWith(cand_table, "Location");
        cand_table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtons();
                host.detailsChanged(); //the full reason of the picked row
            }
        });
        cand_table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) addSelected();
            }
        });
        add_button.setFont(add_button.getFont().deriveFont(Font.BOLD));
        add_button.setToolTipText("Correlate the selected items (or double-click one). Saved only with Save edited copy.");
        add_button.addActionListener(e -> addSelected());
        no_roles.setForeground(CheckerPanel.MUTED_COLOR);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(add_button);
        buttons.add(no_roles);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(BorderFactory.createTitledBorder("Add a correlation"));
        p.add(bar, BorderLayout.NORTH);
        p.add(new JScrollPane(cand_table), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    //-------------------------------------------------------------------------------------------------
    //Job

    public Job job() {
        return job;
    }

    public boolean hasChanges() {
        return edits != null && !edits.isEmpty();
    }

    //Starts editing a job version (pending changes of the previous one are dropped; the window asks first)
    public void setJob(Job j) {
        job = j;
        edits = j == null ? null : new Edits(j);
        item_kind.removeAllItems();
        if (j != null) for (ItemKind k : ItemKind.values()) if (k.applies(j.panel)) item_kind.addItem(k);
        fillJobBox(false);
        loadItems();
        updatePending();
    }

    private String currentKey() {
        return job == null ? "" : CheckerPanel.key(job);
    }

    //all = every version in the open files (when the list opens); otherwise just the current job
    private void fillJobBox(boolean all) {
        filling_job_box = true;
        try {
            job_box.removeAllItems();
            String key = currentKey();
            Choice current = job == null ? new Choice(null, "", "Pick a job version...")
                : new Choice(null, key, CheckerPanel.jobTitle(job) + "      [" + CheckerPanel.fileLabel(job.file) + "]");
            List<Choice> list = all ? host.choices() : List.of();
            boolean found = false;
            for (Choice c : list) {
                job_box.addItem(c);
                if (c.key.equals(key)) {
                    job_box.setSelectedItem(c);
                    found = true;
                }
            }
            if (!found) {
                job_box.insertItemAt(current, 0);
                job_box.setSelectedIndex(0);
            }
            if (all && list.isEmpty()) job_box.addItem(new Choice(null, "", "Open a job file or a configurator first (buttons at the top)."));
        } finally {
            filling_job_box = false;
        }
    }

    //-------------------------------------------------------------------------------------------------
    //Items

    enum ItemKind {
        ZONES("Input zones"), DEVICES("Device inputs"), INPUTS("Inputs"), OUTPUTS("Outputs"), ZONE_LEDS("Input zone LEDs"), LEDS("All LEDs");

        final String label;

        ItemKind(String label) {
            this.label = label;
        }

        boolean applies(Job.Panel p) {
            switch (this) {
                case ZONES: case DEVICES: case ZONE_LEDS: return p.hasZones();
                case INPUTS: return !p.hasZones();
                default: return true;
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void loadItems() {
        item_model.rows.clear();
        ItemKind kind = (ItemKind) item_kind.getSelectedItem();
        if (job != null && kind != null) {
            switch (kind) {
                case ZONES:
                    for (Circuit c : job.inputZones()) item_model.rows.add(new Item(c, Place.of(job, c)));
                    break;
                case DEVICES:
                    for (Circuit c : job.inputs.values()) if (c.kind != Kind.INPUT_ZONE) item_model.rows.add(new Item(c, Place.of(job, c)));
                    break;
                case INPUTS:
                    for (Circuit c : job.inputs.values()) item_model.rows.add(new Item(c, Place.of(job, c)));
                    break;
                case OUTPUTS:
                    for (Circuit c : job.outputs.values()) item_model.rows.add(new Item(c, Place.of(job, c)));
                    break;
                case ZONE_LEDS:
                case LEDS:
                    for (Led l : job.leds.values()) {
                        if (kind == ItemKind.ZONE_LEDS && !Correlations.isZoneLed(l)) continue;
                        if (l.slots.isEmpty()) continue;
                        item_model.rows.add(new Item(l, Place.of(job, l, -1, -1)));
                    }
                    break;
                default:
                    break;
            }
        }
        item_model.fireTableStructureChanged();
        filterItems(); //also sizes the columns
        showItem();
    }

    private void filterItems() {
        item_sorter.setRowFilter(textFilter(item_search.getText()));
        item_count.setText(item_table.getRowCount() + " of " + item_model.rows.size());
        fit(item_table); //sized to the rows that are showing
    }

    private Object selectedItem() {
        int row = item_table.getSelectedRow();
        return row < 0 ? null : item_model.rows.get(item_table.convertRowIndexToModel(row)).obj;
    }

    private void showItem() {
        Object item = selectedItem();
        links_title.setText(item == null ? (job == null ? " " : "Pick an item on the left.") : Place.of(job, item).text());
        loadLinks();
        role_box.removeAllItems();
        no_roles.setText(" ");
        if (item != null) {
            Role keep = null;
            for (Role r : Correlations.roles(job, item)) {
                role_box.addItem(r);
                if (r.label.equals(last_role)) keep = r;
            }
            if (keep != null) role_box.setSelectedItem(keep);
            String why = Correlations.noRolesReason(job, item);
            if (why != null) no_roles.setText(why);
        }
        role_box.setEnabled(role_box.getItemCount() > 0);
        loadCandidates();
        host.detailsChanged();
    }

    //-------------------------------------------------------------------------------------------------
    //Current correlations of the picked item

    enum State {
        LINKED("Correlated", Color.BLACK), ADDED("Added", CheckerPanel.ADDED_COLOR), REMOVED("Removed", CheckerPanel.REMOVED_COLOR),
        READ_ONLY("Read-only", CheckerPanel.MUTED_COLOR);

        final String label;
        final Color color;

        State(String label, Color color) {
            this.label = label;
            this.color = color;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void loadLinks() {
        link_model.rows.clear();
        Object item = selectedItem();
        if (item != null) {
            for (Link l : edits.linksOf(item)) {
                boolean item_is_a = l.end(job, true) == item;
                Object other = l.end(job, !item_is_a);
                State st = edits.added.contains(l) ? State.ADDED : edits.removed.contains(l) ? State.REMOVED : State.LINKED;
                String note = Correlations.cannotRemove(edits, l);
                if (note == null && st == State.LINKED) note = "";
                link_model.rows.add(new LinkRow(l, other, Place.of(job, other), what(l, item_is_a, other), st, note == null ? "" : note));
            }
            readOnlyLinks(item);
        }
        link_model.fireTableStructureChanged();
        fit(link_table);
        updateButtons();
    }

    //What the other end is, seen from the picked item
    private static String what(Link l, boolean item_is_a, Object other) {
        switch (l.type) {
            case DEVICE_ZONE: return item_is_a ? "Input zone" : "Device";
            case INPUT_OUTPUT: return item_is_a ? "Output" : ((Circuit) other).kind == Kind.INPUT_ZONE ? "Input zone" : "Input";
            case LED_INPUT: return item_is_a ? "Input zone" : "LED";
            default: return "";
        }
    }

    //Correlations that are shown but changed only in the configurator
    private void readOnlyLinks(Object item) {
        if (item instanceof Led) {
            for (Circuit c : ((Led) item).outputs) link_model.rows.add(readOnly(c, "Output", "Switch / status LED outputs are set in the configurator."));
            return;
        }
        Circuit c = (Circuit) item;
        if (c.output) {
            for (int[] s : job.status_links) {
                if (s[1] == c.ckt) link_model.rows.add(readOnly(Place.text(job.status_names.getOrDefault(s[0], "Status " + s[0])), "System status", ""));
            }
            for (ControlGroup g : job.controls.values()) if (g.outputs.contains(c)) link_model.rows.add(readOnly(Place.text(g.label()), "Switch", ""));
            for (Led l : job.leds.values()) if (l.outputs.contains(c)) link_model.rows.add(readOnly(l, "LED", ""));
            return;
        }
        for (Equation e : job.equations.values()) {
            if (e.subjects.contains(c)) link_model.rows.add(readOnly(e, "Logic driving it", ""));
            else if (e.inputs.containsValue(c.ckt)) link_model.rows.add(readOnly(e, "Used in logic", ""));
        }
        //A device on an FX4000/FX6000: the outputs it turns on through its zones, with the pending changes
        if (c.kind == Kind.DEVICE && job.panel.hasZones()) {
            Set<Circuit> seen = new LinkedHashSet<>();
            for (Link zl : edits.linksOf(c)) {
                if (zl.type != Type.DEVICE_ZONE || !edits.linked(zl)) continue;
                Circuit zone = (Circuit) zl.end(job, false);
                for (Link ol : edits.linksOf(zone)) {
                    if (ol.type != Type.INPUT_OUTPUT || !edits.linked(ol)) continue;
                    Circuit out = (Circuit) ol.end(job, false);
                    if (seen.add(out)) link_model.rows.add(readOnly(out, "Output via IZ " + zone.addr, "Turned on through input zone " + zone.addr + "; change it on the zone."));
                }
            }
        }
    }

    private LinkRow readOnly(Object other, String what, String note) {
        return new LinkRow(null, other, Place.of(job, other), what, State.READ_ONLY, note);
    }

    private List<LinkRow> selectedLinks() {
        List<LinkRow> list = new ArrayList<>();
        for (int row : link_table.getSelectedRows()) list.add(link_model.rows.get(link_table.convertRowIndexToModel(row)));
        return list;
    }

    private void removeSelected() {
        List<String> refused = new ArrayList<>();
        for (LinkRow r : selectedLinks()) {
            if (r.link == null || r.state == State.REMOVED) continue;
            String why = Correlations.cannotRemove(edits, r.link);
            if (why != null) refused.add(r.place.brief() + ": " + why);
            else edits.remove(r.link);
        }
        changed();
        if (!refused.isEmpty()) JOptionPane.showMessageDialog(this, String.join("\n", refused), "Not removed", JOptionPane.INFORMATION_MESSAGE);
    }

    private void undoSelected() {
        for (LinkRow r : selectedLinks()) if (r.link != null) edits.undo(r.link);
        changed();
    }

    //-------------------------------------------------------------------------------------------------
    //Candidates

    enum Allowed {
        YES("Yes", CheckerPanel.OK_COLOR), CAUTION("Caution", CheckerPanel.WARNING_COLOR), NO("No", CheckerPanel.ERROR_COLOR);

        final String label;
        final Color color;

        Allowed(String label, Color color) {
            this.label = label;
            this.color = color;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private void loadCandidates() {
        cand_model.rows.clear();
        Object item = selectedItem();
        Role role = (Role) role_box.getSelectedItem();
        if (item != null && role != null) {
            for (Object other : Correlations.candidates(job, role)) {
                if (other == item) continue;
                Link l = role.link(item, other);
                if (edits.linked(l)) continue; //already in the list above
                String why = Correlations.blocked(edits, l);
                String caution = why == null ? Correlations.caution(edits, l) : null;
                Allowed a = why != null ? Allowed.NO : caution != null ? Allowed.CAUTION : Allowed.YES;
                cand_model.rows.add(new Cand(other, Place.of(job, other), l, a, why != null ? why : caution != null ? caution : ""));
            }
        }
        cand_model.rows.sort((x, y) -> Integer.compare(x.allowed.ordinal(), y.allowed.ordinal())); //allowed first, not allowed last
        cand_model.fireTableStructureChanged();
        filterCandidates(); //also sizes the columns
        updateButtons();
    }

    private void filterCandidates() {
        RowFilter<CandidateModel, Integer> text = textFilter(cand_search.getText());
        boolean blocked = show_blocked.isSelected();
        cand_sorter.setRowFilter(new RowFilter<CandidateModel, Integer>() {
            public boolean include(Entry<? extends CandidateModel, ? extends Integer> entry) {
                Cand c = cand_model.rows.get(entry.getIdentifier());
                return (blocked || c.allowed != Allowed.NO) && text.include(entry);
            }
        });
        int allowed = 0;
        for (Cand c : cand_model.rows) if (c.allowed != Allowed.NO) allowed++;
        int not = cand_model.rows.size() - allowed;
        cand_count.setText(cand_table.getRowCount() + " shown, " + allowed + " allowed" + (not > 0 ? ", " + not + " not allowed" : ""));
        fit(cand_table); //e.g. the Note column needs room once not-allowed rows show
    }

    private void addSelected() {
        List<Cand> picked = new ArrayList<>();
        List<String> refused = new ArrayList<>(), cautions = new ArrayList<>();
        for (int row : cand_table.getSelectedRows()) {
            Cand c = cand_model.rows.get(cand_table.convertRowIndexToModel(row));
            if (c.allowed == Allowed.NO) refused.add(c.place.brief() + ": " + c.note);
            else {
                picked.add(c);
                if (c.allowed == Allowed.CAUTION) cautions.add(c.place.brief() + ": " + c.note);
            }
        }
        if (!refused.isEmpty()) {
            JOptionPane.showMessageDialog(this, "The configurator would not accept:\n" + String.join("\n", first(refused, 15)), "Not added", JOptionPane.INFORMATION_MESSAGE);
        }
        if (picked.isEmpty()) return;
        if (!cautions.isEmpty()) {
            int answer = JOptionPane.showConfirmDialog(this, "Please check:\n" + String.join("\n", first(cautions, 15)) + "\n\nAdd anyway?", "Add correlation",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
        }
        for (Cand c : picked) {
            //Re-checked one by one: an earlier pick can change what the next one may do
            if (Correlations.blocked(edits, c.link) == null) edits.add(c.link);
        }
        changed();
    }

    private static List<String> first(List<String> list, int n) {
        if (list.size() <= n) return list;
        List<String> l = new ArrayList<>(list.subList(0, n));
        l.add("... and " + (list.size() - n) + " more");
        return l;
    }

    //-------------------------------------------------------------------------------------------------
    //Pending changes, saving

    private void changed() {
        Object item = selectedItem();
        loadLinks();
        loadCandidates();
        item_model.fireTableRowsUpdated(0, Math.max(0, item_model.rows.size() - 1)); //link counts
        reselect(item);
        updatePending();
        host.detailsChanged();
    }

    private void reselect(Object item) {
        if (item == null) return;
        for (int i = 0; i < item_model.rows.size(); i++) {
            if (item_model.rows.get(i).obj == item) {
                int view = item_table.convertRowIndexToView(i);
                if (view >= 0 && item_table.getSelectedRow() != view) item_table.getSelectionModel().setSelectionInterval(view, view);
                return;
            }
        }
    }

    private void updatePending() {
        refreshPending();
        host.changed();
    }

    private int other_changes; //ticked LED fixes (LEDs tab) on the same job version, saved together with these

    void pendingElsewhere(int n) {
        other_changes = n;
        refreshPending();
    }

    private void refreshPending() {
        int n = edits == null ? 0 : edits.size();
        String text = n == 0 ? (job == null ? " " : "No changes") : Report.plural(n, "change") + " not saved";
        if (other_changes > 0) text = (n == 0 ? "" : text + ", ") + Report.plural(other_changes, "LED change") + " ticked";
        pending.setText(text);
        pending.setForeground(n + other_changes == 0 ? CheckerPanel.MUTED_COLOR : CheckerPanel.CHANGED_COLOR);
        save_button.setEnabled(n + other_changes > 0);
        discard_button.setEnabled(n > 0);
    }

    Edits edits() {
        return edits;
    }

    private void updateButtons() {
        boolean removable = false, undoable = false;
        for (LinkRow r : selectedLinks()) {
            if (r.link == null) continue;
            if (r.state == State.ADDED || r.state == State.REMOVED) undoable = true;
            if (r.state != State.REMOVED && Correlations.cannotRemove(edits, r.link) == null) removable = true;
        }
        remove_button.setEnabled(removable);
        undo_button.setEnabled(undoable);
        boolean addable = false;
        for (int row : cand_table.getSelectedRows()) if (cand_model.rows.get(cand_table.convertRowIndexToModel(row)).allowed != Allowed.NO) addable = true;
        add_button.setEnabled(addable);
    }

    private void discard() {
        if (!hasChanges()) return;
        int answer = JOptionPane.showConfirmDialog(this, "Drop " + Report.plural(edits.size(), "change") + "?", "Discard changes", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        edits.clear();
        changed();
        host.status("Changes discarded.");
    }

    //The pending changes, one per line
    List<String> changeLines() {
        List<String> lines = new ArrayList<>();
        if (edits == null) return lines;
        for (Link l : edits.removed) lines.add("Remove  " + l.text(job));
        for (Link l : edits.added) lines.add("Add  " + l.text(job));
        return lines;
    }

    //After the window saved the pending changes into a new copy: carry on editing the saved copy, on the same item
    void savedAs(Job saved) {
        Job from = job;
        ItemKind kind = (ItemKind) item_kind.getSelectedItem();
        Object item = selectedItem();
        String item_key = item == null ? null : Place.of(from, item).text();
        setJob(saved);
        if (kind != null) item_kind.setSelectedItem(kind);
        if (item_key != null) {
            for (int i = 0; i < item_model.rows.size(); i++) {
                if (item_model.rows.get(i).place.text().equals(item_key)) {
                    reselect(item_model.rows.get(i).obj);
                    break;
                }
            }
        }
    }

    //-------------------------------------------------------------------------------------------------
    //Details pane

    void details(StringBuilder h) {
        if (job == null) {
            h.append("<p style='color:gray'>Pick a job version on the left and press <b>Correlate</b> to see and change its correlations. ")
                .append("Changes are written to a new copy of the job, which you load in the configurator with Import Job.</p>");
            return;
        }
        Object item = selectedItem();
        if (item != null) {
            h.append("<p style='font-size:larger'><b>").append(CheckerPanel.esc(Place.of(job, item).text())).append("</b></p>");
            int n = edits.countLinks(item);
            h.append("<p>").append(Report.plural(n, "correlation")).append(" that can be changed here.");
            String why = Correlations.noRolesReason(job, item);
            if (why != null) h.append(" ").append(CheckerPanel.esc(why));
            h.append("</p>");
        } else {
            h.append("<p style='color:gray'>Pick an item on the left to see what it is correlated to. Below, add what the configurator allows; ")
                .append("tick <i>Show not allowed</i> to see why something is missing.</p>");
        }
        int cand_row = cand_table.getSelectedRow();
        if (cand_row >= 0) {
            Cand c = cand_model.rows.get(cand_table.convertRowIndexToModel(cand_row));
            String color = CheckerPanel.hex(c.allowed.color);
            h.append("<p><b>To add:</b> ").append(CheckerPanel.esc(c.place.text())).append("<br><b style='color:").append(color).append("'>")
                .append(c.allowed == Allowed.NO ? "Not allowed" : c.allowed == Allowed.CAUTION ? "Allowed, please check" : "Allowed").append("</b>");
            if (!c.note.isEmpty()) h.append(": ").append(CheckerPanel.esc(c.note));
            h.append("</p>");
        }
        List<String> lines = changeLines();
        if (!lines.isEmpty()) {
            h.append("<p><b>Not saved yet</b> (").append(lines.size()).append(")</p><p>");
            for (String s : first(lines, 100)) {
                String color = s.startsWith("Add") ? CheckerPanel.hex(CheckerPanel.ADDED_COLOR) : s.startsWith("Remove") ? CheckerPanel.hex(CheckerPanel.REMOVED_COLOR) : "gray";
                h.append("<span style='color:").append(color).append("'>").append(CheckerPanel.esc(s)).append("</span><br>");
            }
            h.append("</p>");
        }
    }

    //-------------------------------------------------------------------------------------------------
    //Column widths: every column only as wide as it needs (within the caps below); one column takes the rest

    //Caps for these tables, tighter than the Check tab's so the Note column has room
    private static final java.util.Map<String, Integer> CAP = java.util.Map.ofEntries(
        java.util.Map.entry("Links", 46), java.util.Map.entry("Allowed", 58), java.util.Map.entry("Status", 84),
        java.util.Map.entry("Correlated as", 120), java.util.Map.entry("Node", 40), java.util.Map.entry("CPU", 72),
        java.util.Map.entry("Loop", 38), java.util.Map.entry("Address", 62), java.util.Map.entry("Type", 140),
        java.util.Map.entry("Tag", 170), java.util.Map.entry("Location", 150), java.util.Map.entry("Note", 260));

    //Makes this column take whatever width the others leave, whenever the table is resized
    private static void fillWith(JTable t, String column) {
        t.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        t.putClientProperty("fill", column);
        t.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent e) {
                java.awt.Container parent = t.getParent();
                if (parent instanceof javax.swing.JComponent && ((javax.swing.JComponent) parent).getClientProperty("fillListener") == null) {
                    javax.swing.JComponent vp = (javax.swing.JComponent) parent;
                    vp.putClientProperty("fillListener", Boolean.TRUE);
                    vp.addComponentListener(new java.awt.event.ComponentAdapter() {
                        public void componentResized(java.awt.event.ComponentEvent ev) {
                            fill(t);
                        }
                    });
                }
                fill(t);
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent e) {
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent e) {
            }
        });
    }

    //Sizes the columns to their contents within CAP, then stretches the fill column
    private static void fit(JTable t) {
        CheckerPanel.fitColumns(t, 300);
        Object fill = t.getClientProperty("fill");
        for (int c = 0; c < t.getColumnCount(); c++) {
            javax.swing.table.TableColumn col = t.getColumnModel().getColumn(c);
            String name = String.valueOf(col.getHeaderValue());
            if (col.getMaxWidth() == 0) continue; //hidden: same value on every row
            int w = col.getPreferredWidth();
            if (!name.equals(fill) && CAP.containsKey(name)) w = Math.min(w, CAP.get(name));
            col.setPreferredWidth(w);
            col.setWidth(w);
            if (name.equals(fill)) t.putClientProperty("fitted", w);
        }
        fill(t);
    }

    private static final int FILL_MIN = 180;

    private static void fill(JTable t) {
        Object fill = t.getClientProperty("fill");
        if (fill == null || t.getParent() == null) return;
        int others = 0;
        javax.swing.table.TableColumn target = null;
        for (int c = 0; c < t.getColumnCount(); c++) {
            javax.swing.table.TableColumn col = t.getColumnModel().getColumn(c);
            if (fill.equals(String.valueOf(col.getHeaderValue()))) target = col;
            else others += col.getWidth();
        }
        if (target == null) return;
        Object fitted = t.getClientProperty("fitted");
        //At least FILL_MIN wide (the rest scrolls; the full text is in the tooltip and the details pane), or all the room left
        int need = fitted instanceof Integer ? (Integer) fitted : 60;
        int w = Math.max(Math.min(need, FILL_MIN), t.getParent().getWidth() - others);
        target.setPreferredWidth(w);
        target.setWidth(w);
        t.revalidate();
        t.repaint();
    }

    //-------------------------------------------------------------------------------------------------
    //Search

    private static <M extends AbstractTableModel> RowFilter<M, Integer> textFilter(String query) {
        String[] words = query.trim().toLowerCase().split("\\s+");
        return new RowFilter<M, Integer>() {
            public boolean include(Entry<? extends M, ? extends Integer> entry) {
                if (words.length == 0 || words[0].isEmpty()) return true;
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < entry.getValueCount(); i++) s.append(entry.getStringValue(i)).append(' ');
                String text = s.toString().toLowerCase();
                String squashed = text.replace(" ", "");
                for (String w : words) if (!text.contains(w) && !squashed.contains(w)) return false;
                return true;
            }
        };
    }

    //-------------------------------------------------------------------------------------------------
    //Table models

    private static final class Item {
        final Object obj;
        final Place place;

        Item(Object obj, Place place) {
            this.obj = obj;
            this.place = place;
        }
    }

    private static final class LinkRow {
        final Link link; //null for read-only rows
        final Object other;
        final Place place;
        final String what;
        final State state;
        final String note;

        LinkRow(Link link, Object other, Place place, String what, State state, String note) {
            this.link = link;
            this.other = other;
            this.place = place;
            this.what = what;
            this.state = state;
            this.note = note;
        }
    }

    private static final class Cand {
        final Object obj;
        final Place place;
        final Link link;
        final Allowed allowed;
        final String note;

        Cand(Object obj, Place place, Link link, Allowed allowed, String note) {
            this.obj = obj;
            this.place = place;
            this.link = link;
            this.allowed = allowed;
            this.note = note;
        }
    }

    //Place columns, with optional columns before and after
    private abstract static class PlaceModel<T> extends AbstractTableModel {
        final List<T> rows = new ArrayList<>();
        final String[] lead, trail;

        PlaceModel(String[] lead, String[] trail) {
            this.lead = lead;
            this.trail = trail;
        }

        abstract Place place(T row);
        abstract Object lead(T row, int col);
        abstract Object trail(T row, int col);

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return lead.length + CheckerPanel.PLACE_COLUMNS.length + trail.length; }

        public String getColumnName(int c) {
            if (c < lead.length) return lead[c];
            c -= lead.length;
            if (c < CheckerPanel.PLACE_COLUMNS.length) return CheckerPanel.PLACE_COLUMNS[c];
            return trail[c - CheckerPanel.PLACE_COLUMNS.length];
        }

        public Object getValueAt(int row, int c) {
            T t = rows.get(row);
            if (c < lead.length) return lead(t, c);
            c -= lead.length;
            if (c < CheckerPanel.PLACE_COLUMNS.length) {
                Place p = place(t);
                if (c == 0) return p.node < 0 ? "" : p.node;
                return CheckerPanel.tableValues(p)[c];
            }
            return trail(t, c - CheckerPanel.PLACE_COLUMNS.length);
        }

        public Class<?> getColumnClass(int c) {
            if (rows.isEmpty()) return Object.class;
            Object v = getValueAt(0, c);
            return v instanceof State ? State.class : v instanceof Allowed ? Allowed.class : v instanceof Integer ? Integer.class : Object.class;
        }
    }

    private class ItemModel extends PlaceModel<Item> {
        ItemModel() {
            super(new String[0], new String[] { "Links" });
        }

        Place place(Item i) { return i.place; }
        Object lead(Item i, int c) { return ""; }
        Object trail(Item i, int c) { return edits == null ? 0 : edits.countLinks(i.obj); }
    }

    private static class LinkModel extends PlaceModel<LinkRow> {
        LinkModel() {
            super(new String[] { "Status", "Correlated as" }, new String[] { "Note" });
        }

        Place place(LinkRow r) { return r.place; }
        Object lead(LinkRow r, int c) { return c == 0 ? r.state : r.what; }
        Object trail(LinkRow r, int c) { return r.note; }
    }

    private static class CandidateModel extends PlaceModel<Cand> {
        CandidateModel() {
            //Note next to Allowed so the reason is in view; it is only as wide as the reasons showing
            super(new String[] { "Allowed", "Note" }, new String[0]);
        }

        Place place(Cand c) { return c.place; }
        Object lead(Cand c, int col) { return col == 0 ? c.allowed : c.note; }
        Object trail(Cand c, int col) { return ""; }
    }
}
