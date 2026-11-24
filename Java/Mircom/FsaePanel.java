import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

//FSAE tab of the bot window. Replaces fsae_zones.txt: the floors and tag settings are edited here and
//saved to assets/fsae_job.properties, and each of the three FSAE steps can be previewed and started from any row.
public class FsaePanel extends JPanel implements FsaeBot.Listener {

    static final String JOB_FILE = "assets/fsae_job.properties";

    private static final Color PROBLEM = new Color(190, 0, 0);
    private static final Color WARNING = new Color(170, 95, 0);
    private static final Color INHERITED = new Color(130, 130, 130);
    private static final Color NOTE = new Color(0, 90, 180);

    private final BotWindow window;
    private FsaePlan plan = new FsaePlan();
    private String loaded_from = ""; //file the working job was last opened from / saved to, for the label

    //What each step will type, worked out from the plan after every edit
    private List<FsaePlan.Resolved> resolved = new ArrayList<>();
    private List<FsaePlan.InputZone> zones = new ArrayList<>();
    private List<FsaePlan.Logic> logic = new ArrayList<>();
    private List<FsaePlan.AdderEntry> adder = new ArrayList<>();

    private final JLabel file_label = new JLabel(" ");
    private final JButton new_button = new JButton("New");
    private final JButton open_button = new JButton("Open...");
    private final JButton save_button = new JButton("Save copy as...");
    private final JComboBox<FsaePlan.PanelType> panel_box = new JComboBox<>(FsaePlan.PanelType.values());
    private final JComboBox<DataEntryBot.WindowInfo> window_box = new JComboBox<>();
    private final JButton refresh_button = new JButton("Refresh");
    private final JRadioButton zones_radio = new JRadioButton("1. Input zones", true);
    private final JRadioButton logic_radio = new JRadioButton("2. Logic");
    private final JRadioButton adder_radio = new JRadioButton("3. Graphic adder");
    private final JLabel start_label = new JLabel(" ");
    private final JLabel hint_label = new JLabel(" ");

    private final FloorModel floor_model = new FloorModel();
    private final JTable floor_table = new JTable(floor_model);
    private final JButton add_button = new JButton("Add floors...");
    private final JButton insert_button = new JButton("Insert row");
    private final JButton delete_button = new JButton("Delete");
    private final JButton up_button = new JButton("Up");
    private final JButton down_button = new JButton("Down");

    private final List<Component> option_fields = new ArrayList<>();
    private final JTextField[] iz_word_fields = new JTextField[FsaePlan.ZoneType.values().length];
    private final JTextField[] adder_word_fields = new JTextField[FsaePlan.ZoneType.values().length];
    private final JTextField iz_tag1_field = new JTextField(18);
    private final JTextField iz_tag2_field = new JTextField(18);
    private final JTextField adder_tag_field = new JTextField(18);
    private final JTextField logic_name_field = new JTextField(18);
    private final JTextField logic_comment_field = new JTextField(18);
    private final JSpinner adder_first_spinner = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
    private final JSpinner adder_rows_spinner = new JSpinner(new SpinnerNumberModel(48, 1, 999, 1));
    private boolean filling_form = false; //don't treat filling in the form as an edit

    private final PreviewModel preview_model = new PreviewModel();
    private final JTable preview_table = new JTable(preview_model);
    private final JTextArea details = new JTextArea(5, 30);

    private final JButton start_button = new JButton("Start");
    private final JButton pause_button = new JButton("Pause");
    private final JButton resume_button = new JButton("Resume");
    private final JButton stop_button = new JButton("Stop");
    private final JLabel status_label = new JLabel("Ready");
    private final JProgressBar progress = new JProgressBar();

    private final Timer save_timer = new Timer(600, e -> saveWorkingFile());

    private FsaeBot.Step shown_step = FsaeBot.Step.INPUT_ZONES;
    private volatile FsaeBot bot;
    private boolean running = false;
    private boolean blocked = false; //the zone list bot is running
    private boolean user_paused = false;
    private boolean picking = false; //a file browser is open
    private File job_dir;

    public FsaePanel(BotWindow window) {
        super(new BorderLayout());
        this.window = window;
        save_timer.setRepeats(false);
        buildLayout();
        wireActions();
        loadWorkingFile();
        refreshWindows();
    }

    // ---------------------------------------------------------------- layout

    private void buildLayout() {
        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        top.add(new JLabel("FSAE job:"), c);
        c.gridx = 1; c.weightx = 1;
        top.add(file_label, c);
        c.gridx = 2; c.weightx = 0;
        top.add(buttons(new_button, open_button, save_button), c);

        c.gridx = 0; c.gridy = 1;
        top.add(new JLabel("Panel:"), c);
        c.gridx = 1; c.gridwidth = 2;
        top.add(panel_box, c);
        c.gridwidth = 1;

        c.gridx = 0; c.gridy = 2;
        top.add(new JLabel("Window:"), c);
        c.gridx = 1; c.weightx = 1;
        top.add(window_box, c);
        c.gridx = 2; c.weightx = 0;
        top.add(buttons(refresh_button), c);

        ButtonGroup steps = new ButtonGroup();
        steps.add(zones_radio);
        steps.add(logic_radio);
        steps.add(adder_radio);
        c.gridx = 0; c.gridy = 3;
        top.add(new JLabel("Step:"), c);
        c.gridx = 1; c.gridwidth = 2;
        JPanel step_panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        step_panel.add(zones_radio);
        step_panel.add(logic_radio);
        step_panel.add(adder_radio);
        top.add(step_panel, c);

        c.gridx = 0; c.gridy = 4; c.gridwidth = 3;
        start_label.setFont(start_label.getFont().deriveFont(Font.BOLD));
        top.add(start_label, c);
        c.gridy = 5;
        top.add(hint_label, c);

        //Left: what to enter (floors + tag settings)
        JTabbedPane setup = new JTabbedPane();
        setup.addTab("Floors", floorsPanel());
        setup.addTab("Tags & options", new JScrollPane(optionsPanel()));

        //Right: what the selected step will type
        preview_table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        preview_table.setFillsViewportHeight(true);
        preview_table.setDefaultRenderer(Object.class, new PreviewRenderer());
        JScrollPane preview_scroll = new JScrollPane(preview_table);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane details_scroll = new JScrollPane(details);
        details_scroll.setBorder(BorderFactory.createTitledBorder("Selected row"));
        JSplitPane preview_split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, preview_scroll, details_scroll);
        preview_split.setResizeWeight(0.8);
        preview_split.setBorder(BorderFactory.createTitledBorder("What this step types, in order - select where to start"));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, setup, preview_split);
        split.setResizeWeight(0.42);
        setup.setPreferredSize(new Dimension(380, 300));
        preview_split.setPreferredSize(new Dimension(520, 300));

        JPanel controls = new JPanel(new BorderLayout(8, 4));
        controls.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        controls.add(buttons(start_button, pause_button, resume_button, stop_button), BorderLayout.WEST);
        JPanel status = new JPanel(new BorderLayout(4, 2));
        status.add(status_label, BorderLayout.NORTH);
        progress.setStringPainted(true);
        progress.setString("");
        status.add(progress, BorderLayout.SOUTH);
        controls.add(status, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
    }

    private JPanel floorsPanel() {
        floor_table.setSelectionMode(ListSelectionModel.SINGLE_INTERVAL_SELECTION);
        floor_table.setFillsViewportHeight(true);
        floor_table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        floor_table.setDefaultRenderer(Object.class, new FloorRenderer());
        floor_table.getColumnModel().getColumn(FloorModel.NUMBER).setMaxWidth(36);
        floor_table.getColumnModel().getColumn(FloorModel.NODE).setMaxWidth(50);
        floor_table.getColumnModel().getColumn(FloorModel.LOOP).setMaxWidth(50);
        floor_table.getColumnModel().getColumn(FloorModel.ADDRESS).setMaxWidth(80);
        floor_table.getTableHeader().setToolTipText("<html>Each floor gets 4 input zones in a row: Normal, Low Heat, High Heat, Smoke Det.<br>"
            + "Blank Group, Node or Loop = same as the row above (grey). Blank Normal IZ = the row above + 4.<br>"
            + "Loop (FX6000 only): 00 for the panel's input zones, or the loop number used in the logic (e.g. 02-02-**-IZ-017).<br>"
            + "Rows are in graphic adder order (top floor first); input zones and logic go in address order.</html>");

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(new JScrollPane(floor_table), BorderLayout.CENTER);
        JPanel row_buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for(JButton b : new JButton[] { add_button, insert_button, delete_button, up_button, down_button }) {
            row_buttons.add(b);
        }
        panel.add(row_buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel optionsPanel() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        FsaePlan.ZoneType[] types = FsaePlan.ZoneType.values();
        c.gridy = 0;
        c.gridx = 1; form.add(bold("Input zone"), c);
        c.gridx = 2; form.add(bold("Graphic adder"), c);
        for(int i = 0; i < types.length; i++) {
            c.gridy++;
            c.gridx = 0; c.weightx = 0;
            form.add(new JLabel(types[i].default_iz_word + " - {type}:"), c);
            iz_word_fields[i] = new JTextField(9);
            adder_word_fields[i] = new JTextField(9);
            c.gridx = 1; c.weightx = 0.5;
            form.add(iz_word_fields[i], c);
            c.gridx = 2;
            form.add(adder_word_fields[i], c);
            option_fields.add(iz_word_fields[i]);
            option_fields.add(adder_word_fields[i]);
        }

        c.gridy++;
        c.gridx = 0; c.gridwidth = 3;
        form.add(bold("Tags  ({type} {floor} {group} are filled in per row)"), c);
        c.gridwidth = 1;
        formRow(form, c, "Input zone tag line 1:", iz_tag1_field);
        formRow(form, c, "Input zone tag line 2:", iz_tag2_field);
        c.gridy++;
        c.gridx = 1; c.gridwidth = 2;
        form.add(small("Leave line 2 blank to leave Tag (Line 2) as it is."), c);
        c.gridwidth = 1;
        formRow(form, c, "Graphic adder tag:", adder_tag_field);
        formRow(form, c, "Logic name:", logic_name_field);
        formRow(form, c, "Logic comment:", logic_comment_field);

        c.gridy++;
        c.gridx = 0; c.gridwidth = 3;
        form.add(bold("Graphic adder"), c);
        c.gridwidth = 1;
        formRow(form, c, "First empty row:", adder_first_spinner);
        formRow(form, c, "Rows per adder:", adder_rows_spinner);

        //Push everything to the top
        c.gridy++;
        c.weighty = 1;
        form.add(new JLabel(" "), c);

        option_fields.add(iz_tag1_field);
        option_fields.add(iz_tag2_field);
        option_fields.add(adder_tag_field);
        option_fields.add(logic_name_field);
        option_fields.add(logic_comment_field);
        option_fields.add(adder_first_spinner);
        option_fields.add(adder_rows_spinner);
        return form;
    }

    private static void formRow(JPanel form, GridBagConstraints c, String label, Component field) {
        c.gridy++;
        c.gridx = 0; c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        form.add(field, c);
        c.gridwidth = 1;
    }

    private static JLabel bold(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    private static JLabel small(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(INHERITED);
        return label;
    }

    private static JPanel buttons(JButton... list) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for(JButton b : list) {
            panel.add(b);
        }
        return panel;
    }

    private void wireActions() {
        new_button.addActionListener(e -> newJob());
        open_button.addActionListener(e -> openJob());
        save_button.addActionListener(e -> saveCopy());
        refresh_button.addActionListener(e -> refreshWindows());
        panel_box.addActionListener(e -> {
            if(!filling_form && plan.panel != panel_box.getSelectedItem()) {
                plan.panel = (FsaePlan.PanelType) panel_box.getSelectedItem();
                refreshWindows();
                planChanged();
            }
        });
        zones_radio.addActionListener(e -> showStep(FsaeBot.Step.INPUT_ZONES, 0));
        logic_radio.addActionListener(e -> showStep(FsaeBot.Step.LOGIC, 0));
        adder_radio.addActionListener(e -> showStep(FsaeBot.Step.GRAPHIC_ADDER, 0));
        preview_table.getSelectionModel().addListSelectionListener(e -> {
            if(!e.getValueIsAdjusting()) {
                updateStartLabel();
            }
        });
        floor_table.getSelectionModel().addListSelectionListener(e -> updateControls());

        add_button.addActionListener(e -> addFloors());
        insert_button.addActionListener(e -> insertRow());
        delete_button.addActionListener(e -> deleteRows());
        up_button.addActionListener(e -> moveRows(-1));
        down_button.addActionListener(e -> moveRows(1));

        FsaePlan.ZoneType[] types = FsaePlan.ZoneType.values();
        for(int i = 0; i < types.length; i++) {
            FsaePlan.ZoneType type = types[i];
            onText(iz_word_fields[i], text -> plan.iz_words.put(type, text.trim()));
            onText(adder_word_fields[i], text -> plan.adder_words.put(type, text.trim()));
        }
        onText(iz_tag1_field, text -> plan.iz_tag1 = text);
        onText(iz_tag2_field, text -> plan.iz_tag2 = text);
        onText(adder_tag_field, text -> plan.adder_tag = text);
        onText(logic_name_field, text -> plan.logic_name = text);
        onText(logic_comment_field, text -> plan.logic_comment = text);
        adder_first_spinner.addChangeListener(e -> {
            if(!filling_form) {
                plan.adder_first_row = (Integer) adder_first_spinner.getValue();
                planChanged();
            }
        });
        adder_rows_spinner.addChangeListener(e -> {
            if(!filling_form) {
                plan.adder_rows = (Integer) adder_rows_spinner.getValue();
                planChanged();
            }
        });

        start_button.addActionListener(e -> start());
        pause_button.addActionListener(e -> pause());
        resume_button.addActionListener(e -> resume());
        stop_button.addActionListener(e -> stop());
    }

    private void onText(JTextField field, Consumer<String> setter) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if(!filling_form) {
                    setter.accept(field.getText());
                    planChanged();
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });
    }

    // ---------------------------------------------------------------- the job

    private void loadWorkingFile() {
        File file = new File(JOB_FILE);
        if(file.exists()) {
            try {
                setPlan(FsaePlan.load(file), "");
                return;
            } catch (Exception e) {
                System.out.println("Could not read " + JOB_FILE + ": " + e.getMessage());
            }
        }
        setPlan(new FsaePlan(), "");
    }

    private void setPlan(FsaePlan new_plan, String from) {
        plan = new_plan;
        loaded_from = from;

        filling_form = true;
        try {
            panel_box.setSelectedItem(plan.panel);
            FsaePlan.ZoneType[] types = FsaePlan.ZoneType.values();
            for(int i = 0; i < types.length; i++) {
                iz_word_fields[i].setText(plan.iz_words.get(types[i]));
                adder_word_fields[i].setText(plan.adder_words.get(types[i]));
            }
            iz_tag1_field.setText(plan.iz_tag1);
            iz_tag2_field.setText(plan.iz_tag2);
            adder_tag_field.setText(plan.adder_tag);
            logic_name_field.setText(plan.logic_name);
            logic_comment_field.setText(plan.logic_comment);
            adder_first_spinner.setValue(plan.adder_first_row);
            adder_rows_spinner.setValue(plan.adder_rows);
        } finally {
            filling_form = false;
        }

        recompute();
        floor_model.fireTableDataChanged();
        showStep(shown_step, 0);
        updateFileLabel();
    }

    //Something in the plan was edited: rework the lists, redraw, and save soon
    private void planChanged() {
        int[] floor_rows = floor_table.getSelectedRows();
        int preview_row = preview_table.getSelectedRow();

        recompute();
        floor_model.fireTableDataChanged();
        if(floor_rows.length > 0 && floor_rows[floor_rows.length - 1] < floor_model.getRowCount()) {
            floor_table.setRowSelectionInterval(floor_rows[0], floor_rows[floor_rows.length - 1]);
        }
        preview_model.show(shown_step);
        selectPreviewRow(Math.max(0, preview_row));
        updateStartLabel();

        save_timer.restart();
    }

    private void recompute() {
        resolved = plan.resolve();
        zones = plan.inputZones();
        logic = plan.logic();
        adder = plan.adder();
    }

    private void saveWorkingFile() {
        try {
            plan.save(new File(JOB_FILE));
            updateFileLabel();
        } catch (Exception e) {
            System.out.println("Could not save " + JOB_FILE + ": " + e.getMessage());
            status_label.setText("Could not save " + JOB_FILE + " - see the log.");
        }
    }

    private void updateFileLabel() {
        file_label.setText(String.format("%s - %d floors (%d input zones)%s", JOB_FILE, plan.floors.size(), plan.floors.size() * 4,
            loaded_from.isEmpty() ? "" : ", from " + loaded_from));
    }

    private void newJob() {
        if(!plan.floors.isEmpty() && JOptionPane.showConfirmDialog(this,
                "Start a new FSAE job? The current floors are cleared (use \"Save copy as\" first to keep them).",
                "New FSAE job", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        FsaePlan fresh = new FsaePlan();
        fresh.panel = plan.panel;
        setPlan(fresh, "");
        saveWorkingFile();
    }

    private void openJob() {
        pickFile(false, file -> {
            try {
                FsaePlan opened = FsaePlan.load(file);
                setPlan(opened, file.getName());
                saveWorkingFile();
                System.out.println("Opened FSAE job " + file.getAbsolutePath());
                if(file.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
                    JOptionPane.showMessageDialog(this, "Imported " + opened.floors.size() + " floors from the old text file.\n"
                        + "Check the panel type, the floors' groups/nodes and the tag templates before running.",
                        "Imported", JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Could not open " + file.getName() + ":\n" + e.getMessage(),
                    "Open FSAE job", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void saveCopy() {
        pickFile(true, file -> {
            if(!file.getName().contains(".")) {
                file = new File(file.getPath() + ".fsae");
            }
            try {
                plan.save(file);
                loaded_from = file.getName();
                updateFileLabel();
                System.out.println("Saved FSAE job to " + file.getAbsolutePath());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Could not save " + file.getName() + ":\n" + e.getMessage(),
                    "Save FSAE job", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void pickFile(boolean save, Consumer<File> then) {
        File start_dir = job_dir != null && job_dir.isDirectory() ? job_dir : new File("assets").getAbsoluteFile();
        String filter = "FSAE jobs (*.fsae;*.properties;*.txt)|*.fsae;*.properties;*.txt|All files (*.*)|*.*";
        String title = save ? "Save the FSAE job as" : "Open an FSAE job (or an old fsae_zones.txt)";
        picking = true;
        updateControls();
        new Thread(() -> {
            File picked = BotWindow.WindowsFileDialog.show(window, start_dir, title, filter, save);
            SwingUtilities.invokeLater(() -> {
                picking = false;
                updateControls();
                if(picked != null) {
                    job_dir = picked.getAbsoluteFile().getParentFile();
                    then.accept(picked);
                }
            });
        }, "pick-fsae-job").start();
    }

    // ---------------------------------------------------------------- floors table edits

    private int insertPoint() {
        int[] rows = floor_table.getSelectedRows();
        return rows.length == 0 ? plan.floors.size() : rows[rows.length - 1] + 1;
    }

    //Write out a row's inherited group/node/address, so moving rows around it doesn't change it
    private void freeze(int row) {
        if(row < 0 || row >= plan.floors.size() || row >= resolved.size()) {
            return;
        }
        FsaePlan.Floor f = plan.floors.get(row);
        FsaePlan.Resolved r = resolved.get(row);
        if(f.group.isEmpty() && !r.group.isEmpty()) {
            f.group = r.group;
        }
        if(f.loop.isEmpty() && plan.panel == FsaePlan.PanelType.FX6000) {
            f.loop = r.loop;
        }
        if(f.node.isEmpty()) {
            f.node = String.valueOf(r.node);
        }
        if(f.address.isEmpty() && r.hasAddress()) {
            f.address = String.valueOf(r.address);
        }
    }

    private void addFloors() {
        stopEditing();
        int at = insertPoint();
        FsaePlan.Resolved above = at > 0 && at - 1 < resolved.size() ? resolved.get(at - 1) : null;

        JTextField spec = new JTextField("EMR, Roof, 26-14, Cell", 24);
        JTextField suffix = new JTextField("FL", 4);
        JTextField group = new JTextField(above == null ? "" : above.group, 10);
        JTextField node = new JTextField(above == null ? "1" : String.valueOf(above.node), 4);
        JTextField loop = new JTextField(above == null || above.loop.equals("**") ? "00" : above.loop, 4);
        loop.setEnabled(plan.panel == FsaePlan.PanelType.FX6000);
        JTextField address = new JTextField("", 6);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        Object[][] rows = {
            { "Floors, top to bottom:", spec },
            { "Number suffix:", suffix },
            { "Group (e.g. SouthTwr):", group },
            { "Node:", node },
            { "Loop (FX6000):", loop },
            { "First Normal IZ address:", address },
        };
        for(int i = 0; i < rows.length; i++) {
            c.gridy = i;
            c.gridx = 0;
            form.add(new JLabel((String) rows[i][0]), c);
            c.gridx = 1;
            form.add((Component) rows[i][1], c);
        }
        c.gridy = rows.length; c.gridx = 0; c.gridwidth = 2;
        form.add(small("<html>Ranges count either way: 26-14 adds 26FL, 25FL ... 14FL.<br>"
            + "Leave the address blank to continue from the row above (+4 per floor).</html>"), c);

        if(JOptionPane.showConfirmDialog(this, form, "Add floors", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        List<String> names = FsaePlan.expandFloors(spec.getText(), suffix.getText().trim());
        if(names.isEmpty()) {
            return;
        }

        freeze(at); //the row after the new ones keeps its values
        List<FsaePlan.Floor> added = new ArrayList<>();
        for(int i = 0; i < names.size(); i++) {
            FsaePlan.Floor f = new FsaePlan.Floor("", names.get(i), "", "");
            if(i == 0) {
                f.group = group.getText().trim();
                f.node = node.getText().trim();
                f.loop = plan.panel == FsaePlan.PanelType.FX6000 ? loop.getText().trim() : "";
                f.address = address.getText().trim();
            }
            added.add(f);
        }
        plan.floors.addAll(at, added);
        planChanged();
        floor_table.setRowSelectionInterval(at, at + added.size() - 1);
        floor_table.scrollRectToVisible(floor_table.getCellRect(at + added.size() - 1, 0, true));
    }

    private void insertRow() {
        stopEditing();
        int at = insertPoint();
        freeze(at);
        plan.floors.add(at, new FsaePlan.Floor());
        planChanged();
        floor_table.setRowSelectionInterval(at, at);
        floor_table.editCellAt(at, FloorModel.FLOOR);
        floor_table.requestFocusInWindow();
    }

    private void deleteRows() {
        stopEditing();
        int[] rows = floor_table.getSelectedRows();
        if(rows.length == 0) {
            return;
        }
        int first = rows[0], last = rows[rows.length - 1];
        freeze(last + 1);
        plan.floors.subList(first, last + 1).clear();
        planChanged();
        int select = Math.min(first, plan.floors.size() - 1);
        if(select >= 0) {
            floor_table.setRowSelectionInterval(select, select);
        }
    }

    private void moveRows(int direction) {
        stopEditing();
        int[] rows = floor_table.getSelectedRows();
        if(rows.length == 0) {
            return;
        }
        int first = rows[0], last = rows[rows.length - 1];
        if((direction < 0 && first == 0) || (direction > 0 && last >= plan.floors.size() - 1)) {
            return;
        }

        int other = direction < 0 ? first - 1 : last + 1; //the row the block swaps with
        for(int r = first; r <= last; r++) {
            freeze(r);
        }
        freeze(other);
        freeze(last + 1);
        freeze(direction > 0 ? last + 2 : -1);

        FsaePlan.Floor moved = plan.floors.remove(other);
        plan.floors.add(direction < 0 ? last : first, moved);
        planChanged();
        floor_table.setRowSelectionInterval(first + direction, last + direction);
    }

    private void stopEditing() {
        if(floor_table.isEditing()) {
            floor_table.getCellEditor().stopCellEditing();
        }
    }

    // ---------------------------------------------------------------- steps / preview

    private List<? extends FsaePlan.Entry> entries(FsaeBot.Step step) {
        switch(step) {
            case LOGIC: return logic;
            case GRAPHIC_ADDER: return adder;
            default: return zones;
        }
    }

    private void showStep(FsaeBot.Step step, int select) {
        shown_step = step;
        preview_model.show(step);
        selectPreviewRow(select);
        updateStartLabel();
    }

    private void selectPreviewRow(int row) {
        if(row >= 0 && row < preview_model.getRowCount()) {
            preview_table.setRowSelectionInterval(row, row);
            preview_table.scrollRectToVisible(preview_table.getCellRect(row, 0, true));
        }
        else {
            preview_table.clearSelection();
        }
    }

    private void updateStartLabel() {
        List<? extends FsaePlan.Entry> list = entries(shown_step);
        int index = preview_table.getSelectedRow();

        if(list.isEmpty()) {
            start_label.setText(plan.floors.isEmpty() ? "Add the floors on the left (Add floors...)" : "Nothing to enter");
            details.setText("");
        }
        else if(index < 0 || index >= list.size()) {
            start_label.setText("Select where to start");
            details.setText("");
        }
        else {
            FsaePlan.Entry e = list.get(index);
            start_label.setText(String.format("Start at %s #%d of %d:  %s", shown_step.label.toLowerCase(), index + 1, list.size(), e.describe()));
            details.setText(detailsOf(e));
            details.setCaretPosition(0);
        }

        long problems = list.stream().filter(e -> !e.problems.isEmpty()).count();
        long warnings = list.stream().filter(e -> !e.warnings.isEmpty()).count();
        String status = problems > 0 ? "  " + problems + " row(s) need fixing (red) before this step can run."
            : warnings > 0 ? "  " + warnings + " tag(s) longer than " + FsaePlan.TAG_LENGTH + " characters (orange)." : "";

        switch(shown_step) {
            case INPUT_ZONES:
                hint_label.setText("<html>Create the input zones first (Monitor is fine), select the first one to fill in the configurator, then Start."
                    + " The bot pauses wherever the next zone isn't right below (new node or gap)." + status + "</html>");
                break;
            case LOGIC:
                hint_label.setText("<html>Press Start, then for each floor: open Advanced Logic for its Normal zone, click into Equation, and press Enter logic (or F8)."
                    + status + "</html>");
                break;
            case GRAPHIC_ADDER:
                hint_label.setText("<html>Rows must be unassigned. Select the first row to fill in the graphic adder, then Start."
                    + " It pauses when an adder is full so you can move to the next one." + status + "</html>");
                break;
        }
        updateControls();
    }

    private String detailsOf(FsaePlan.Entry e) {
        StringBuilder text = new StringBuilder();
        for(String p : e.problems) {
            text.append("FIX: ").append(p).append('\n');
        }
        for(String w : e.warnings) {
            text.append("Note: ").append(w).append('\n');
        }
        if(!e.note.isEmpty()) {
            text.append("Before this row: ").append(e.note).append('\n');
        }
        if(e instanceof FsaePlan.InputZone z) {
            text.append("Tag (Line 1): ").append(z.tag1).append('\n');
            text.append("Tag (Line 2): ").append(plan.iz_tag2.isBlank() ? "(left as it is)" : z.tag2).append('\n');
            text.append("Type: ").append(z.type.iz_type);
        }
        else if(e instanceof FsaePlan.Logic l) {
            text.append("Name: ").append(l.name).append('\n');
            text.append("Comment: ").append(l.comment).append('\n');
            text.append("Equation:\n").append(l.equation.replace("\r", ""));
        }
        else if(e instanceof FsaePlan.AdderEntry a) {
            text.append("Type: Ipt Zone\nAssignment: ").append(a.type.adder_assignment).append(" (").append(a.floor.floor.name).append(")\n");
            text.append("Tag: ").append(a.tag);
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- configurator windows

    private boolean matchesPanel(DataEntryBot.WindowInfo w) {
        if(plan.panel == FsaePlan.PanelType.FX4000) {
            return BotWindow.Panel.FX_4000.matches(w);
        }
        String title = w.title().toLowerCase(Locale.ROOT);
        return BotWindow.Panel.FLEXNET.matches(w) || title.contains("6000");
    }

    private void refreshWindows() {
        DataEntryBot.WindowInfo previous = (DataEntryBot.WindowInfo) window_box.getSelectedItem();
        List<DataEntryBot.WindowInfo> all = new ArrayList<>();
        List<DataEntryBot.WindowInfo> matches = new ArrayList<>();
        try {
            for(DataEntryBot.WindowInfo w : DataEntryBot.listWindows()) {
                all.add(w);
                if(matchesPanel(w)) {
                    matches.add(w);
                }
            }
        } catch (Exception e) {
            System.out.println("Could not list windows: " + e);
        }

        //If the configurator isn't recognised, let the user pick it from everything that's open
        List<DataEntryBot.WindowInfo> shown = matches.isEmpty() ? all : matches;
        window_box.setModel(new DefaultComboBoxModel<>(shown.toArray(new DataEntryBot.WindowInfo[0])));
        window_box.setSelectedItem(null);
        for(DataEntryBot.WindowInfo w : shown) {
            if(previous != null && w.hwnd() == previous.hwnd()) {
                window_box.setSelectedItem(w);
            }
        }
        if(window_box.getSelectedItem() == null && !matches.isEmpty()) {
            window_box.setSelectedIndex(0);
        }
        window_box.setToolTipText(matches.isEmpty()
            ? "No " + plan.panel.name() + " configurator window recognised - pick it from the open windows, or open the job and press Refresh."
            : null);
        updateControls();
    }

    // ---------------------------------------------------------------- run control

    private void start() {
        if(running || blocked || picking) {
            return;
        }
        stopEditing();

        List<? extends FsaePlan.Entry> list = entries(shown_step);
        int index = Math.max(0, preview_table.getSelectedRow());
        if(index >= list.size()) {
            return;
        }

        List<String> problems = new ArrayList<>();
        for(int i = index; i < list.size() && problems.size() < 8; i++) {
            for(String p : list.get(i).problems) {
                problems.add("#" + (i + 1) + " " + list.get(i).floor.floor.name + ": " + p);
            }
        }
        if(!problems.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Fix these on the Floors tab first:\n" + String.join("\n", problems),
                "Can't start yet", JOptionPane.WARNING_MESSAGE);
            return;
        }

        DataEntryBot.WindowInfo target = (DataEntryBot.WindowInfo) window_box.getSelectedItem();
        if(target == null) {
            JOptionPane.showMessageDialog(this, "Pick the " + plan.panel.name() + " configurator window first (open the job, then press Refresh).",
                "No configurator window", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String admin_problem = DataEntryBot.adminProblem(target);
        if(admin_problem != null && JOptionPane.showConfirmDialog(this, admin_problem + "\n\nStart anyway?",
                "Configurator is running as administrator", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }

        FsaeBot new_bot;
        try {
            new_bot = new FsaeBot(plan, shown_step, index);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not start the bot:\n" + e.getMessage(), "FSAE", JOptionPane.ERROR_MESSAGE);
            return;
        }
        new_bot.setListener(this);
        new_bot.setTargetWindow(target.hwnd());
        bot = new_bot;
        user_paused = false;

        progress.setMaximum(Math.max(1, list.size()));
        progress.setValue(index);
        progress.setString("");
        status_label.setText("Starting...");
        setRunning(true);

        FsaeBot.Step step = shown_step;
        new Thread(() -> {
            //Logic waits for the user to open the first Advanced Logic window, so don't pull the main window over it
            if(step != FsaeBot.Step.LOGIC) {
                new_bot.focusTarget();
            }
            new_bot.start();
        }, "fsae-start").start();
    }

    private void pause() {
        FsaeBot b = bot;
        if(b != null && running && !b.getIsPaused()) {
            user_paused = true;
            b.requestPause();
            status_label.setText("Pausing after the current entry...");
            updateControls();
        }
    }

    private void resume() {
        FsaeBot b = bot;
        if(b == null || !running || !b.getIsPaused()) {
            return;
        }
        user_paused = false;
        status_label.setText(shown_step == FsaeBot.Step.LOGIC ? "Entering logic..." : "Resuming...");
        boolean refocus = shown_step != FsaeBot.Step.LOGIC; //logic types into whatever Advanced Logic window is in front
        new Thread(() -> {
            if(refocus) {
                b.focusTarget();
            }
            b.resumeRun();
        }, "fsae-resume").start();
    }

    private void stop() {
        FsaeBot b = bot;
        if(b != null && running) {
            status_label.setText("Stopping...");
            b.setIsRunning(false);
        }
    }

    private void setRunning(boolean value) {
        running = value;
        window.setFsaeRunning(value);
        updateControls();
    }

    private void updateControls() {
        boolean paused = running && bot != null && bot.getIsPaused();
        boolean idle = !running && !blocked && !picking;
        boolean has_rows = !entries(shown_step).isEmpty();

        start_button.setEnabled(idle && has_rows && window_box.getItemCount() > 0);
        pause_button.setEnabled(running && !paused && bot != null && !bot.isPauseRequested() && shown_step != FsaeBot.Step.LOGIC);
        resume_button.setEnabled(paused);
        resume_button.setText(shown_step == FsaeBot.Step.LOGIC ? "Enter logic" : "Resume");
        stop_button.setEnabled(running);

        for(JButton b : new JButton[] { new_button, open_button, save_button, refresh_button }) {
            b.setEnabled(idle);
        }
        panel_box.setEnabled(idle);
        window_box.setEnabled(idle);
        zones_radio.setEnabled(idle);
        logic_radio.setEnabled(idle);
        adder_radio.setEnabled(idle);
        preview_table.setEnabled(idle);
        floor_table.setEnabled(idle);
        boolean selected = floor_table.getSelectedRowCount() > 0;
        add_button.setEnabled(idle);
        insert_button.setEnabled(idle);
        delete_button.setEnabled(idle && selected);
        up_button.setEnabled(idle && selected);
        down_button.setEnabled(idle && selected);
        for(Component field : option_fields) {
            field.setEnabled(idle);
        }
    }

    // ---------------------------------------------------------------- for the bot window

    public boolean isRunning() {
        return running;
    }

    //The zone list bot is running, so this tab can't start
    public void setBlocked(boolean value) {
        blocked = value;
        updateControls();
    }

    public void stopIfRunning() {
        stop();
    }

    //Start/Pause hotkey: pause, resume / enter the next floor's logic, or start the selected step
    public void hotkey() {
        if(running) {
            if(bot != null && bot.getIsPaused()) {
                resume();
            }
            else if(shown_step != FsaeBot.Step.LOGIC) { //logic can't be paused partway, same as the Pause button
                pause();
            }
        }
        else if(!blocked && !picking) {
            start();
        }
    }

    // ---------------------------------------------------------------- bot callbacks (bot thread)

    @Override
    public void onProgress(FsaeBot.Step step, int index, int total) {
        SwingUtilities.invokeLater(() -> {
            selectPreviewRow(index);
            progress.setMaximum(Math.max(1, total));
            progress.setValue(index + (step == FsaeBot.Step.LOGIC ? 0 : 1));
            progress.setString(step.label + " " + (index + 1) + " / " + total);
            if(bot != null && !bot.getIsPaused()) {
                status_label.setText("Entering #" + (index + 1) + ": " + entries(step).get(index).describe());
            }
        });
    }

    @Override
    public void onPausedChanged(boolean paused, String reason) {
        SwingUtilities.invokeLater(() -> {
            if(paused) {
                if(reason == null) {
                    Toolkit.getDefaultToolkit().beep();
                    status_label.setText("Paused - see the log for why. Fix it, then press Resume.");
                }
                else {
                    if(!user_paused && shown_step != FsaeBot.Step.LOGIC) {
                        Toolkit.getDefaultToolkit().beep();
                    }
                    status_label.setText("<html>" + reason + "</html>");
                }
            }
            else if(running) {
                status_label.setText("Running...");
            }
            updateControls();
        });
    }

    @Override
    public void onFinished(boolean completed, String message) {
        SwingUtilities.invokeLater(() -> {
            setRunning(false);
            if(completed) {
                status_label.setText("Done - " + shown_step.label.toLowerCase() + " entered.");
                progress.setValue(progress.getMaximum());
            }
            else {
                status_label.setText("<html>" + (message != null ? message
                    : "Stopped. Start continues from the selected row - check it in the configurator first.") + "</html>");
            }
            updateStartLabel();
            window.toFront();
        });
    }

    // ---------------------------------------------------------------- floors table

    private class FloorModel extends AbstractTableModel {
        static final int NUMBER = 0, GROUP = 1, FLOOR = 2, NODE = 3, LOOP = 4, ADDRESS = 5, ZONES = 6;
        private final String[] COLUMNS = { "#", "Group", "Floor", "Node", "Loop", "Normal IZ", "Input zones" };

        @Override public int getRowCount() { return plan.floors.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Object getValueAt(int row, int column) {
            FsaePlan.Floor f = plan.floors.get(row);
            switch(column) {
                case NUMBER: return row + 1;
                case GROUP: return f.group;
                case FLOOR: return f.name;
                case NODE: return f.node;
                case LOOP: return plan.panel == FsaePlan.PanelType.FX6000 ? f.loop : "";
                case ADDRESS: return f.address;
                default:
                    FsaePlan.Resolved r = row < resolved.size() ? resolved.get(row) : null;
                    return r == null || !r.hasAddress() ? "?" : r.address + "-" + (r.address + 3);
            }
        }

        //Value shown in grey when the cell is blank
        String inherited(int row, int column) {
            FsaePlan.Resolved r = row < resolved.size() ? resolved.get(row) : null;
            if(r == null) {
                return "";
            }
            switch(column) {
                case GROUP: return r.group;
                case NODE: return String.valueOf(r.node);
                case LOOP: return r.loop;
                case ADDRESS: return r.hasAddress() ? String.valueOf(r.address) : "";
                default: return "";
            }
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column >= GROUP && column <= ADDRESS && !running
                && (column != LOOP || plan.panel == FsaePlan.PanelType.FX6000); //FX4000 logic always uses **
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            String text = value == null ? "" : value.toString().trim();
            FsaePlan.Floor f = plan.floors.get(row);
            switch(column) {
                case GROUP: f.group = text; break;
                case FLOOR: f.name = text; break;
                case NODE: f.node = text; break;
                case LOOP: f.loop = text; break;
                case ADDRESS: f.address = text; break;
                default: return;
            }
            SwingUtilities.invokeLater(FsaePanel.this::planChanged); //after the editor has closed
        }
    }

    private class FloorRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            String text = value == null ? "" : value.toString();
            boolean inherited = false;
            if(text.isEmpty() && column != FloorModel.FLOOR) {
                text = floor_model.inherited(row, column);
                inherited = true;
            }
            super.getTableCellRendererComponent(table, text, selected, focused, row, column);

            FsaePlan.Resolved r = row < resolved.size() ? resolved.get(row) : null;
            boolean bad = r != null && !r.errors.isEmpty();
            setFont(getFont().deriveFont(inherited ? Font.ITALIC : Font.PLAIN));
            if(!selected) {
                setForeground(bad && (column == FloorModel.ZONES || column == FloorModel.NUMBER) ? PROBLEM
                    : inherited ? INHERITED : table.getForeground());
            }
            setToolTipText(bad ? "<html>" + String.join("<br>", r.errors) + "</html>"
                : inherited && !text.isEmpty() ? "Same as above (blank)" : null);
            return this;
        }
    }

    // ---------------------------------------------------------------- preview table

    private class PreviewModel extends AbstractTableModel {
        private FsaeBot.Step step = FsaeBot.Step.INPUT_ZONES;
        private List<? extends FsaePlan.Entry> rows = new ArrayList<>();
        private String[] columns = {};

        void show(FsaeBot.Step step) {
            boolean structure = this.step != step || columns.length == 0;
            this.step = step;
            rows = entries(step);
            switch(step) {
                case INPUT_ZONES: columns = new String[] { "#", "Node", "IZ", "Type", "Tag line 1", "Tag line 2", "Before this row" }; break;
                case LOGIC: columns = new String[] { "#", "Node", "Normal IZ", "Logic name", "Low / High heat", "Before this row" }; break;
                case GRAPHIC_ADDER: columns = new String[] { "#", "Adder", "Row", "Assignment", "Tag", "Before this row" }; break;
            }
            if(structure) {
                fireTableStructureChanged();
                preview_table.getColumnModel().getColumn(0).setMaxWidth(40);
                preview_table.getColumnModel().getColumn(1).setMaxWidth(50);
                preview_table.getColumnModel().getColumn(2).setMaxWidth(step == FsaeBot.Step.LOGIC ? 80 : 60);
            }
            else {
                fireTableDataChanged();
            }
        }

        private String nodeText(int node, String loop) {
            return loop.equals("00") || loop.equals("**") ? String.valueOf(node) : node + " L" + loop;
        }

        FsaePlan.Entry entry(int row) {
            return rows.get(row);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }

        @Override
        public Object getValueAt(int row, int column) {
            FsaePlan.Entry e = rows.get(row);
            if(column == 0) {
                return row + 1;
            }
            if(column == columns.length - 1) {
                return !e.problems.isEmpty() ? e.problems.get(0) : e.note;
            }
            if(e instanceof FsaePlan.InputZone z) {
                switch(column) {
                    case 1: return nodeText(z.node, z.loop);
                    case 2: return z.address;
                    case 3: return z.type.iz_type;
                    case 4: return z.tag1;
                    case 5: return plan.iz_tag2.isBlank() ? "" : z.tag2;
                }
            }
            else if(e instanceof FsaePlan.Logic l) {
                switch(column) {
                    case 1: return nodeText(l.node, l.floor.loop);
                    case 2: return l.address;
                    case 3: return l.name;
                    case 4: return (l.address + 1) + " / " + (l.address + 2);
                }
            }
            else if(e instanceof FsaePlan.AdderEntry a) {
                switch(column) {
                    case 1: return a.adder;
                    case 2: return a.row;
                    case 3: return a.type.adder_assignment;
                    case 4: return a.tag;
                }
            }
            return "";
        }
    }

    private class PreviewRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            FsaePlan.Entry e = preview_model.entry(row);
            boolean last = column == preview_model.getColumnCount() - 1;
            boolean is_tag = preview_model.getColumnName(column).contains("Tag") || preview_model.getColumnName(column).contains("name");
            boolean too_long = is_tag && value != null && value.toString().length() > FsaePlan.TAG_LENGTH;

            if(!selected) {
                setForeground(!e.problems.isEmpty() ? PROBLEM : too_long ? WARNING : last ? NOTE : table.getForeground());
            }
            setFont(getFont().deriveFont(last && !e.note.isEmpty() || !e.problems.isEmpty() ? Font.BOLD : Font.PLAIN));
            List<String> tips = new ArrayList<>(e.problems);
            tips.addAll(e.warnings);
            setToolTipText(tips.isEmpty() ? null : "<html>" + String.join("<br>", tips) + "</html>");
            return this;
        }
    }
}
