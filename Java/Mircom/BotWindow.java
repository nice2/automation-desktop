import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

//Control window for the data entry bots: convert the zone list, pick the configurator,
//choose where to start, and start/pause/resume/stop.
public class BotWindow extends JFrame implements ConfigBot.Listener {

    //Each configurator the bot can drive and how to recognise its window
    public enum Panel {
        FX_400("FX-400 (MGC-400 Configurator)", FX400::new, "MGC-400 Series Configurator", "mgc-400_config.exe", false),
        FX_2000("FX-2000", FX2000::new, "FX-2000 Configuration", "fxconfig.exe", false),
        FLEXNET("Flexnet (MGC Configurator)", Flexnet::new, "MGC Fire Detection", "mgcconfig.exe", true),
        FX_4000("FX-4000 (MGC-4000 Configurator)", FX4000::new, "MGC-4000 Configurator", "configurator.exe", true);

        final String label;
        final Supplier<ConfigBot> factory;
        final String title_keyword;
        final String exe;
        final boolean needs_first_row; //update pass only jumps to the top (Home) if devices were inserted this run

        Panel(String label, Supplier<ConfigBot> factory, String title_keyword, String exe, boolean needs_first_row) {
            this.label = label;
            this.factory = factory;
            this.title_keyword = title_keyword;
            this.exe = exe;
            this.needs_first_row = needs_first_row;
        }

        boolean matches(DataEntryBot.WindowInfo window) {
            return window.title().toLowerCase(Locale.ROOT).contains(title_keyword.toLowerCase(Locale.ROOT))
                || window.process().equalsIgnoreCase(exe);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final String ZONE_FILE = "assets/temp_zones.csv";
    private static final String CONVERTER = "tools/excelReader.py";
    private static final int MAX_LOG_CHARS = 200_000;

    //Zone table columns
    private static final int COL_NUMBER = 0, COL_ADDRESS = 1, COL_TYPE = 2, COL_TAG1 = 3, COL_TAG2 = 4, COL_OVERRIDES = 5;
    private static final String AUTO_TYPE = "(automatic from Tag 1)";
    private static final String[] DEVICE_TYPES = { AUTO_TYPE, "Photo Detector", "Heat Detector", "Alarm Input", "Alarm Input Class A",
        "Latched Supervisory", "Non-latched Supervisory", "Relay", "Telephone Module", "Speakers", "Blank Device" };
    private static final Color SUB_ROW_BACKGROUND = new Color(242, 244, 247);
    private static final Color OVERRIDE_FOREGROUND = new Color(0, 90, 180);

    private final JComboBox<Panel> panel_box = new JComboBox<>(Panel.values());
    private final JComboBox<DataEntryBot.WindowInfo> window_box = new JComboBox<>();
    private final JLabel zone_status = new JLabel(" ");
    private final JRadioButton insert_radio = new JRadioButton("Insert devices", true);
    private final JRadioButton update_radio = new JRadioButton("Update tags/types");
    private final ZoneTableModel table_model = new ZoneTableModel();
    private final JTable table = new JTable(table_model);
    private final JLabel start_label = new JLabel(" ");
    private final JLabel hint_label = new JLabel(" ");
    private final JButton convert_button = new JButton("Convert Excel/CSV...");
    private final JButton reload_button = new JButton("Reload");
    private final JButton refresh_button = new JButton("Refresh");
    private final JButton beginning_button = new JButton("From the beginning");
    private final JButton start_button = new JButton("Start");
    private final JButton pause_button = new JButton("Pause");
    private final JButton resume_button = new JButton("Resume");
    private final JButton stop_button = new JButton("Stop");
    private final JLabel status_label = new JLabel("Ready");
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea log = new JTextArea();
    private final JTabbedPane tabs = new JTabbedPane();
    private static final int ZONE_TAB = 0, FSAE_TAB = 1;
    private FsaePanel fsae;

    private ConfigBot.Plan plan;
    private ConfigBot.Phase shown_phase = ConfigBot.Phase.INSERT;
    private volatile ConfigBot bot;
    private boolean running = false;
    private boolean user_paused = false;
    private boolean converting = false;
    private int pending_edits = 0; //table edits waiting to be saved to the zone list
    private boolean last_edit_failed = false;
    private File zone_dir; //folder of the last zone list converted, so the browser reopens there

    public BotWindow() {
        super("Mircom Data Entry");
        installLog();
        fsae = new FsaePanel(this);
        buildLayout();
        wireActions();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if(bot != null && bot.getIsRunning()) {
                    bot.setIsRunning(false);
                }
                fsae.stopIfRunning();
                dispose();
                System.exit(0);
            }
        });

        setSize(1000, 820);
        setLocationByPlatform(true);

        refreshWindows();
        loadPlan(true);
        updateControls();
    }

    // ---------------------------------------------------------------- layout

    private void buildLayout() {
        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        //Row 0: zone list
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        top.add(new JLabel("Zone list:"), c);
        c.gridx = 1; c.weightx = 1;
        top.add(zone_status, c);
        c.gridx = 2; c.weightx = 0;
        top.add(buttons(convert_button, reload_button), c);

        //Row 1: configurator type
        c.gridx = 0; c.gridy = 1;
        top.add(new JLabel("Configurator:"), c);
        c.gridx = 1; c.gridwidth = 2;
        top.add(panel_box, c);
        c.gridwidth = 1;

        //Row 2: configurator window
        c.gridx = 0; c.gridy = 2;
        top.add(new JLabel("Window:"), c);
        c.gridx = 1; c.weightx = 1;
        top.add(window_box, c);
        c.gridx = 2; c.weightx = 0;
        top.add(buttons(refresh_button), c);

        //Row 3: start phase
        ButtonGroup phases = new ButtonGroup();
        phases.add(insert_radio);
        phases.add(update_radio);
        c.gridx = 0; c.gridy = 3;
        top.add(new JLabel("Start in:"), c);
        c.gridx = 1;
        JPanel phase_panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        phase_panel.add(insert_radio);
        phase_panel.add(update_radio);
        top.add(phase_panel, c);
        c.gridx = 2;
        top.add(buttons(beginning_button), c);

        //Row 4-5: start point summary + hint
        c.gridx = 0; c.gridy = 4; c.gridwidth = 3;
        start_label.setFont(start_label.getFont().deriveFont(Font.BOLD));
        top.add(start_label, c);
        c.gridy = 5;
        top.add(hint_label, c);
        c.gridwidth = 1;

        //Table of devices in the order the bot handles them
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //clicking elsewhere saves the edit
        table.setDefaultRenderer(Object.class, new ZoneCellRenderer());
        table.getColumnModel().getColumn(COL_NUMBER).setMaxWidth(50);
        table.getColumnModel().getColumn(COL_ADDRESS).setMaxWidth(90);
        table.getColumnModel().getColumn(COL_OVERRIDES).setPreferredWidth(160);
        JComboBox<String> type_box = new JComboBox<>(DEVICE_TYPES);
        type_box.setEditable(true);
        DefaultCellEditor type_editor = new DefaultCellEditor(type_box);
        type_editor.setClickCountToStart(2); //a single click only picks the start row, like the text cells
        table.getColumnModel().getColumn(COL_TYPE).setCellEditor(type_editor);
        table.getTableHeader().setToolTipText("<html>Double-click a Type, Tag 1, Tag 2 or Overrides cell to edit it. Edits are saved to " + ZONE_FILE + ".<br>"
            + "Overrides: type=&lt;device type&gt;; " + String.join("; ", ZoneList.OVERRIDE_FLAGS) + " - put ! in front to turn one off.</html>");
        JScrollPane table_scroll = new JScrollPane(table);
        table_scroll.setBorder(BorderFactory.createTitledBorder("Devices in the order the bot handles them - select where to start, double-click a cell to edit"));

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane log_scroll = new JScrollPane(log);
        log_scroll.setBorder(BorderFactory.createTitledBorder("Log"));

        table_scroll.setPreferredSize(new java.awt.Dimension(600, 300));
        log_scroll.setPreferredSize(new java.awt.Dimension(600, 160));

        //Bottom: controls
        JPanel controls = new JPanel(new BorderLayout(8, 4));
        controls.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        controls.add(buttons(start_button, pause_button, resume_button, stop_button), BorderLayout.WEST);
        JPanel status = new JPanel(new BorderLayout(4, 2));
        status.add(status_label, BorderLayout.NORTH);
        progress.setStringPainted(true);
        progress.setString("");
        status.add(progress, BorderLayout.SOUTH);
        controls.add(status, BorderLayout.CENTER);

        JPanel zone_tab = new JPanel(new BorderLayout());
        zone_tab.add(top, BorderLayout.NORTH);
        zone_tab.add(table_scroll, BorderLayout.CENTER);
        zone_tab.add(controls, BorderLayout.SOUTH);

        //Zone list devices and FSAE each get a tab; the log below is shared
        tabs.addTab("Zone list devices", zone_tab);
        tabs.addTab("FSAE", fsae);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, log_scroll);
        split.setResizeWeight(0.85);
        setContentPane(split);
        //The tabs start with most of the height; the log keeps a strip at the bottom
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                split.setDividerLocation(0.85);
            }
        });
    }

    private static JPanel buttons(JButton... list) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for(JButton b : list) {
            panel.add(b);
        }
        return panel;
    }

    private void wireActions() {
        convert_button.addActionListener(e -> convertZoneList());
        reload_button.addActionListener(e -> loadPlan(true));
        refresh_button.addActionListener(e -> refreshWindows());
        panel_box.addActionListener(e -> {
            refreshWindows();
            loadPlan(true);
        });
        insert_radio.addActionListener(e -> showPhase(ConfigBot.Phase.INSERT, 0));
        update_radio.addActionListener(e -> showPhase(ConfigBot.Phase.UPDATE, 0));
        beginning_button.addActionListener(e -> {
            insert_radio.setSelected(true);
            showPhase(ConfigBot.Phase.INSERT, 0);
        });
        table.getSelectionModel().addListSelectionListener(e -> updateStartLabel());

        start_button.addActionListener(e -> start(false));
        pause_button.addActionListener(e -> pause());
        resume_button.addActionListener(e -> resume(true));
        stop_button.addActionListener(e -> stop());
    }

    // ---------------------------------------------------------------- zone list / plan

    private Panel panel() {
        return (Panel) panel_box.getSelectedItem();
    }

    //Reads the zone list and shows the order the selected configurator's bot will use
    private void loadPlan(boolean reset_start) {
        try {
            ConfigBot planner = panel().factory.get();
            plan = planner.plan();

            File file = new File(ZONE_FILE);
            String modified = new SimpleDateFormat("MMM d, h:mm a").format(new Date(file.lastModified()));
            zone_status.setText(String.format("%s - %d devices to insert, %d to update (saved %s)",
                ZONE_FILE, plan.inserts.size(), plan.updates.size(), modified));

            int keep = reset_start ? 0 : Math.max(0, selectedIndex());
            if(reset_start) {
                insert_radio.setSelected(true);
            }
            showPhase(insert_radio.isSelected() ? ConfigBot.Phase.INSERT : ConfigBot.Phase.UPDATE, keep);
        } catch (Exception e) {
            plan = null;
            table_model.setZones(new ArrayList<>());
            zone_status.setText("Could not read " + ZONE_FILE + ": " + e.getMessage());
            updateStartLabel();
        }
        updateControls();
    }

    private void showPhase(ConfigBot.Phase phase, int select) {
        shown_phase = phase;
        table_model.setZones(plan == null ? new ArrayList<>() : plan.get(phase));
        selectRow(select);
        updateStartLabel();
    }

    //Selects the device at this position in the bot's order
    private void selectRow(int index) {
        showTableRow(table_model.rowOf(index));
    }

    private void showTableRow(int row) {
        if(row >= 0 && row < table_model.getRowCount()) {
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
        }
        else {
            table.clearSelection();
        }
    }

    //Position in the bot's order of the selected device (a .2 row counts as its .1 device), or -1
    private int selectedIndex() {
        int row = table.getSelectedRow();
        return row < 0 ? -1 : table_model.indexOf(row);
    }

    private void updateStartLabel() {
        int index = selectedIndex();
        String phase_name = shown_phase == ConfigBot.Phase.INSERT ? "insert" : "update";

        if(plan == null || index < 0) {
            start_label.setText(plan == null ? "No zone list loaded" : "Select a device to start from");
        }
        else {
            Zone zone = table_model.zones.get(index);
            start_label.setText(String.format("Start at %s #%d of %d:  %s", phase_name, index + 1,
                table_model.zones.size(), zone.getZoneinfo()));
        }

        if(shown_phase == ConfigBot.Phase.INSERT) {
            hint_label.setText(index > 0
                ? "Devices above the selected one must already be in the configurator. Tags/types are then entered for every device."
                : "Inserts every device, then enters tags/types for every device.");
        }
        else {
            hint_label.setText(panel().needs_first_row
                ? "Skips inserting. Click the FIRST device row in the configurator before pressing Start - the bot moves down from there."
                : "Skips inserting. The bot jumps to the first device row (Home) and moves down from there.");
        }
    }

    // ---------------------------------------------------------------- configurator windows

    private void refreshWindows() {
        DataEntryBot.WindowInfo previous = (DataEntryBot.WindowInfo) window_box.getSelectedItem();
        List<DataEntryBot.WindowInfo> matches = new ArrayList<>();

        try {
            for(DataEntryBot.WindowInfo w : DataEntryBot.listWindows()) {
                if(panel().matches(w)) {
                    matches.add(w);
                }
            }
        } catch (Exception e) {
            System.out.println("Could not list windows: " + e);
        }

        window_box.setModel(new DefaultComboBoxModel<>(matches.toArray(new DataEntryBot.WindowInfo[0])));
        for(DataEntryBot.WindowInfo w : matches) {
            if(previous != null && w.hwnd() == previous.hwnd()) {
                window_box.setSelectedItem(w);
            }
        }

        if(matches.isEmpty()) {
            window_box.setToolTipText("No open " + panel().label + " window found. Open the job, then press Refresh.");
        }
        else {
            window_box.setToolTipText(null);
        }
        updateControls();
    }

    // ---------------------------------------------------------------- run control

    //from_hotkey: started with the Start/Pause hotkey, so report problems in the log instead of a dialog
    private void start(boolean from_hotkey) {
        if(running || converting || fsae.isRunning()) {
            return;
        }

        //Save a cell still being edited first, so the bot reads the edited zone list
        if(table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        if(pending_edits > 0) {
            SwingUtilities.invokeLater(() -> {
                if(!last_edit_failed) {
                    start(from_hotkey);
                }
            });
            return;
        }

        DataEntryBot.WindowInfo window = (DataEntryBot.WindowInfo) window_box.getSelectedItem();
        if(window == null) {
            if(from_hotkey) {
                //Don't pop a dialog over the configurator - just say why nothing happened
                Toolkit.getDefaultToolkit().beep();
                System.out.println("Start hotkey: no open " + panel().label + " window found. Pick the configurator type in the window, open the job, then try again.");
                return;
            }
            JOptionPane.showMessageDialog(this, "No open " + panel().label + " window found.\nOpen the job in the configurator, then press Refresh.",
                "No configurator window", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String admin_problem = DataEntryBot.adminProblem(window);
        if(admin_problem != null) {
            if(from_hotkey) {
                Toolkit.getDefaultToolkit().beep();
                System.out.println(admin_problem.replace('\n', ' '));
                return;
            }
            if(JOptionPane.showConfirmDialog(this, admin_problem + "\n\nStart anyway?", "Configurator is running as administrator",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
                return;
            }
        }

        ConfigBot.Phase phase = shown_phase;
        int index = Math.max(0, selectedIndex());

        ConfigBot new_bot = panel().factory.get();
        try {
            ConfigBot.Plan fresh = new_bot.plan();
            if(plan == null || fresh.get(phase).size() != plan.get(phase).size()) {
                //Zone list changed on disk since it was shown - make the user re-pick
                loadPlan(false);
                JOptionPane.showMessageDialog(this, "The zone list changed since it was loaded.\nCheck the start point, then press Start again.",
                    "Zone list changed", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not read the zone list:\n" + e.getMessage(), "Zone list", JOptionPane.ERROR_MESSAGE);
            return;
        }

        new_bot.setStartPoint(phase, index);
        new_bot.setListener(this);
        new_bot.setTargetWindow(window.hwnd());

        bot = new_bot;
        user_paused = false;
        progress.setMaximum(Math.max(1, plan.get(phase).size()));
        progress.setValue(index);
        progress.setString("");
        status_label.setText("Starting...");
        setRunning(true);

        //Bring the configurator forward (a no-op if the hotkey was pressed with it already in front), then start typing
        new Thread(() -> {
            new_bot.focusTarget();
            new_bot.start();
        }, "bot-start").start();
    }

    private void pause() {
        if(bot != null && running && !bot.getIsPaused()) {
            user_paused = true;
            bot.requestPause();
            status_label.setText("Pausing after the current device...");
            updateControls();
        }
    }

    //refocus: bring the configurator back to the front first (GUI button). Not needed from the hotkey.
    private void resume(boolean refocus) {
        ConfigBot b = bot;
        if(b == null || !running || !b.getIsPaused()) {
            return;
        }

        user_paused = false;
        status_label.setText("Resuming...");
        new Thread(() -> {
            if(refocus) {
                b.focusTarget();
            }
            b.setIsPaused(false);
        }, "bot-resume").start();
    }

    private void stop() {
        if(bot != null && running) {
            status_label.setText("Stopping...");
            bot.setIsRunning(false);
        }
    }

    private void setRunning(boolean value) {
        running = value;

        //While running, clicking this window must not pull focus away from the configurator
        setFocusableWindowState(!value);
        setAlwaysOnTop(value);
        tabs.setEnabledAt(FSAE_TAB, !value);
        fsae.setBlocked(value);
        updateControls();
    }

    //Called by the FSAE tab when its bot starts/stops: same window behaviour, and the zone list tab is locked
    void setFsaeRunning(boolean value) {
        setFocusableWindowState(!value);
        setAlwaysOnTop(value);
        tabs.setEnabledAt(ZONE_TAB, !value);
        updateControls();
    }

    private void updateControls() {
        boolean paused = running && bot != null && bot.getIsPaused();
        boolean idle = !running && !converting && (fsae == null || !fsae.isRunning());

        start_button.setEnabled(idle && plan != null && window_box.getItemCount() > 0);
        pause_button.setEnabled(running && !paused && bot != null && !bot.isPauseRequested());
        resume_button.setEnabled(paused);
        stop_button.setEnabled(running);

        convert_button.setEnabled(idle);
        reload_button.setEnabled(idle);
        refresh_button.setEnabled(idle);
        panel_box.setEnabled(idle);
        window_box.setEnabled(idle);
        insert_radio.setEnabled(idle);
        update_radio.setEnabled(idle);
        beginning_button.setEnabled(idle);
        table.setEnabled(idle);
    }

    // Hotkeys (from Automation)

    //Start/Pause hotkey: pauses or resumes whichever bot is running, otherwise starts the bot on the tab in front
    //using the configurator and window picked in this window
    public void hotkeyStartPause() {
        if(running) {
            if(bot == null) {
                return;
            }
            if(bot.getIsPaused()) {
                resume(false);
            }
            else {
                pause();
            }
            return;
        }
        if(fsae.isRunning() || tabs.getSelectedIndex() == FSAE_TAB) {
            fsae.hotkey();
            return;
        }
        if(converting) {
            return;
        }
        if(window_box.getItemCount() == 0) {
            refreshWindows(); //the configurator may have been opened after the last Refresh
        }
        start(true);
    }

    public void hotkeyStop() {
        stop();
        fsae.stopIfRunning();
    }

    // ---------------------------------------------------------------- bot callbacks (bot thread)

    @Override
    public void onProgress(ConfigBot.Phase phase, int index, int total, Zone zone) {
        SwingUtilities.invokeLater(() -> {
            if(phase != shown_phase) {
                if(phase == ConfigBot.Phase.INSERT) {
                    insert_radio.setSelected(true);
                }
                else {
                    update_radio.setSelected(true);
                }
                showPhase(phase, index);
            }
            else {
                selectRow(index);
            }

            //The selected row always follows the device being worked on, so after a stop,
            //Start continues from the device that was in progress.
            progress.setMaximum(Math.max(1, total));
            progress.setValue(index + 1);
            progress.setString((phase == ConfigBot.Phase.INSERT ? "Insert " : "Update ") + (index + 1) + " / " + total);
            if(!bot_paused()) {
                status_label.setText((phase == ConfigBot.Phase.INSERT ? "Inserting: " : "Updating: ") + zone.getZoneinfo());
            }
        });
    }

    private boolean bot_paused() {
        return bot != null && bot.getIsPaused();
    }

    @Override
    public void onPausedChanged(boolean paused) {
        SwingUtilities.invokeLater(() -> {
            if(paused) {
                if(!user_paused) {
                    //Paused by the setup prompt or the ready check - the reason is in the log
                    Toolkit.getDefaultToolkit().beep();
                    status_label.setText("Paused - see the log for why. Fix it, then press Resume.");
                }
                else {
                    status_label.setText("Paused between devices. Press Resume to continue (don't change the selected row).");
                }
            }
            else if(running) {
                status_label.setText("Running...");
            }
            updateControls();
        });
    }

    @Override
    public void onFinished(boolean completed) {
        SwingUtilities.invokeLater(() -> {
            setRunning(false);
            if(completed) {
                status_label.setText("Done.");
                progress.setValue(progress.getMaximum());
                insert_radio.setSelected(true);
                showPhase(ConfigBot.Phase.INSERT, 0);
            }
            else {
                status_label.setText("Stopped. Start will continue from the selected device - check it in the configurator first.");
            }
            toFront();
        });
    }

    // ---------------------------------------------------------------- Excel conversion

    private void convertZoneList() {
        converting = true;
        updateControls();
        status_label.setText("Opening file browser...");

        //The Windows dialog runs in a separate process, so wait for it off the EDT
        File start_dir = zone_dir != null && zone_dir.isDirectory() ? zone_dir : new File("assets").getAbsoluteFile();
        new Thread(() -> {
            File picked;
            try {
                picked = WindowsFileDialog.open(start_dir);
            } catch (Exception e) {
                System.out.println("Windows file browser unavailable (" + e.getMessage() + "), using the fallback dialog.");
                picked = WindowsFileDialog.FALLBACK;
            }
            File result = picked;
            SwingUtilities.invokeLater(() -> {
                File source = result == WindowsFileDialog.FALLBACK ? pickWithAwtDialog(start_dir) : result;
                if(source == null) {
                    converting = false;
                    status_label.setText("Ready");
                    updateControls();
                    return;
                }
                runConverter(source);
            });
        }, "pick-zone-list").start();
    }

    //Native (older-style) Windows dialog, used only if PowerShell can't show the modern one
    private File pickWithAwtDialog(File start_dir) {
        FileDialog dialog = new FileDialog(this, "Select the zone list", FileDialog.LOAD);
        dialog.setDirectory(start_dir.getAbsolutePath());
        dialog.setFile("*.xlsx;*.xlsm;*.csv"); //acts as the filter on Windows
        dialog.setVisible(true);
        if(dialog.getFile() == null) {
            return null;
        }
        return new File(dialog.getDirectory(), dialog.getFile());
    }

    private void runConverter(File source) {
        if(!WindowsFileDialog.isZoneList(source)) {
            converting = false;
            status_label.setText("Ready");
            updateControls();
            JOptionPane.showMessageDialog(this, source.getName() + " is not a zone list. Pick an .xlsx, .xlsm or .csv file.");
            return;
        }
        if(source.getAbsoluteFile().equals(new File(ZONE_FILE).getAbsoluteFile())) {
            converting = false;
            status_label.setText("Ready");
            updateControls();
            JOptionPane.showMessageDialog(this, "That is the bot's own output file. Pick the original Excel/CSV zone list.");
            return;
        }
        zone_dir = source.getAbsoluteFile().getParentFile();

        status_label.setText("Converting " + source.getName() + "...");
        System.out.println("Converting " + source.getAbsolutePath());

        new Thread(() -> {
            int exit_code;
            try {
                Process process = new ProcessBuilder("python", CONVERTER, source.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                }
                exit_code = process.waitFor();
            } catch (Exception e) {
                System.out.println("Could not run the converter (is Python installed and on PATH?): " + e.getMessage());
                exit_code = -1;
            }

            int result = exit_code;
            SwingUtilities.invokeLater(() -> {
                converting = false;
                if(result == 0) {
                    status_label.setText("Converted " + source.getName() + ".");
                    loadPlan(true);
                }
                else {
                    status_label.setText("Conversion failed - see the log.");
                    JOptionPane.showMessageDialog(this, "Converting " + source.getName() + " failed. The log has the details.",
                        "Conversion failed", JOptionPane.ERROR_MESSAGE);
                }
                updateControls();
            });
        }, "convert").start();
    }

    //Shows the standard Windows "Open" dialog (the same one Explorer/Office use: address bar,
    //search, Quick access, OneDrive) through PowerShell's OpenFileDialog, since Swing/AWT only
    //offer their own chooser or the older-style Windows dialog.
    static final class WindowsFileDialog {
        static final File FALLBACK = new File("<fallback>"); //returned when PowerShell couldn't show it
        static final String[] EXTENSIONS = {".xlsx", ".xlsm", ".csv"};

        static boolean isZoneList(File file) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            for(String extension : EXTENSIONS) {
                if(name.endsWith(extension)) {
                    return true;
                }
            }
            return false;
        }

        //Blocks until the dialog closes. Returns the chosen file, null if cancelled, or FALLBACK.
        static File open(File start_dir) throws Exception {
            return dialog(start_dir, "Select the zone list",
                "Zone lists (*.xlsx;*.xlsm;*.csv)|*.xlsx;*.xlsm;*.csv|Excel workbooks (*.xlsx;*.xlsm)|*.xlsx;*.xlsm|CSV files (*.csv)|*.csv|All files (*.*)|*.*",
                false);
        }

        //Open or Save As dialog for any file type, called off the EDT. filter uses the Windows "Name|*.ext|..." format.
        //Falls back to the older native dialog if PowerShell can't show it. Returns null if cancelled.
        static File show(java.awt.Frame parent, File start_dir, String title, String filter, boolean save) {
            File picked;
            try {
                picked = dialog(start_dir, title, filter, save);
            } catch (Exception e) {
                System.out.println("Windows file browser unavailable (" + e.getMessage() + "), using the fallback dialog.");
                picked = FALLBACK;
            }
            if(picked != FALLBACK) {
                return picked;
            }

            File[] result = new File[1];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    FileDialog dialog = new FileDialog(parent, title, save ? FileDialog.SAVE : FileDialog.LOAD);
                    dialog.setDirectory(start_dir.getAbsolutePath());
                    dialog.setVisible(true);
                    if(dialog.getFile() != null) {
                        result[0] = new File(dialog.getDirectory(), dialog.getFile());
                    }
                });
            } catch (Exception e) {
                System.out.println("Could not show a file dialog: " + e.getMessage());
            }
            return result[0];
        }

        private static File dialog(File start_dir, String title, String filter, boolean save) throws Exception {
            String script = String.join("\n",
                "$ErrorActionPreference = 'Stop'",
                "$ProgressPreference = 'SilentlyContinue'",
                "[Console]::OutputEncoding = New-Object Text.UTF8Encoding $false",
                "Add-Type -AssemblyName System.Windows.Forms",
                "[Windows.Forms.Application]::EnableVisualStyles()",
                //Invisible topmost owner so the dialog opens in front of the bot window
                "$owner = New-Object Windows.Forms.Form -Property @{ TopMost = $true; ShowInTaskbar = $false }",
                "$dialog = New-Object Windows.Forms." + (save ? "SaveFileDialog" : "OpenFileDialog"),
                "$dialog.Title = '" + title.replace("'", "''") + "'",
                "$dialog.Filter = '" + filter.replace("'", "''") + "'",
                "$dialog.InitialDirectory = '" + start_dir.getAbsolutePath().replace("'", "''") + "'",
                "$dialog.RestoreDirectory = $true",
                "if ($dialog.ShowDialog($owner) -eq [Windows.Forms.DialogResult]::OK) { [Console]::Out.Write($dialog.FileName) }",
                "$owner.Dispose()");
            String encoded = java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));

            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-STA",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectError(ProcessBuilder.Redirect.DISCARD) //PowerShell writes CLIXML progress noise there
                .start();
            process.getOutputStream().close();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("﻿", "").trim();
            int exit_code = process.waitFor();
            if(exit_code != 0) {
                System.out.println("Windows file browser failed (PowerShell exit code " + exit_code + "), using the fallback dialog.");
                return FALLBACK;
            }
            return output.isEmpty() ? null : new File(output);
        }
    }

    // ---------------------------------------------------------------- log

    //Copies everything printed (System.out/err) into the log box as well as the console
    private void installLog() {
        PrintStream console_out = System.out;
        PrintStream console_err = System.err;
        System.setOut(new PrintStream(new LogStream(console_out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LogStream(console_err), true, StandardCharsets.UTF_8));
    }

    private class LogStream extends OutputStream {
        private final PrintStream console;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LogStream(PrintStream console) {
            this.console = console;
        }

        @Override
        public synchronized void write(int b) {
            if(b == '\n') {
                flushLine();
            }
            else if(b != '\r') {
                line.write(b);
            }
        }

        private void flushLine() {
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            console.println(text);
            SwingUtilities.invokeLater(() -> {
                log.append(text + "\n");
                if(log.getDocument().getLength() > MAX_LOG_CHARS) {
                    log.replaceRange("", 0, log.getDocument().getLength() - MAX_LOG_CHARS / 2);
                }
                log.setCaretPosition(log.getDocument().getLength());
            });
        }
    }

    // ---------------------------------------------------------------- table

    //Saves an edited cell to the zone list file, then reloads so the table shows what the bot will do
    private void editZone(Zone zone, int column, String value) {
        pending_edits--;
        last_edit_failed = false;

        value = value.trim();
        if(column == COL_TYPE && value.equals(AUTO_TYPE)) {
            value = "";
        }
        if(value.equals(String.valueOf(table_model.cell(zone, column)).trim())) {
            return; //unchanged
        }

        double address = zone.getAddress();
        String column_name = table_model.getColumnName(column);
        try {
            switch(column) {
                case COL_TYPE:
                    ZoneList.setTypeOverride(ZONE_FILE, address, value);
                    break;
                case COL_TAG1:
                    ZoneList.editCell(ZONE_FILE, address, ZoneList.TAG1_COLUMN, value);
                    break;
                case COL_TAG2:
                    ZoneList.editCell(ZONE_FILE, address, ZoneList.TAG2_COLUMN, value);
                    break;
                case COL_OVERRIDES:
                    value = ZoneList.normalizeOverrides(value);
                    ZoneList.editCell(ZONE_FILE, address, ZoneList.OVERRIDES_COLUMN, value);
                    break;
                default:
                    return;
            }
        } catch (IllegalArgumentException e) {
            last_edit_failed = true;
            JOptionPane.showMessageDialog(this, e.getMessage(), "Can't use that " + column_name, JOptionPane.WARNING_MESSAGE);
            return;
        } catch (Exception e) {
            last_edit_failed = true;
            JOptionPane.showMessageDialog(this, "Could not save the change to " + ZONE_FILE + ":\n" + e.getMessage(),
                "Edit not saved", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String shown = value.isEmpty() ? (column == COL_TYPE ? "automatic" : "(blank)") : value;
        System.out.println("Edited " + address + " " + column_name + ": " + shown);
        loadPlan(false);
        showTableRow(table_model.rowOfAddress(address)); //the edit can move the device, e.g. a new type changes the insert order
        status_label.setText("Saved " + address + " to " + ZONE_FILE + ". Converting the Excel/CSV again replaces edits made here.");
    }

    //Shades .2 rows and highlights overrides
    private class ZoneCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, column);
            Zone zone = table_model.zoneAt(row);

            boolean overridden = column == COL_OVERRIDES
                || (column == COL_TYPE && zone.getOverrides().contains("type="));
            if(!selected) {
                setBackground(zone instanceof subZone ? SUB_ROW_BACKGROUND : table.getBackground());
                setForeground(overridden ? OVERRIDE_FOREGROUND : table.getForeground());
            }
            if(overridden && !zone.getOverrides().isEmpty()) {
                setFont(getFont().deriveFont(Font.BOLD));
            }
            setToolTipText(column == COL_OVERRIDES && !zone.getOverrides().isEmpty() ? zone.getOverrides() : null);
            return this;
        }
    }

    //One row per device in the bot's order, each followed by its .2 address (if it has one)
    private class ZoneTableModel extends AbstractTableModel {
        private final String[] COLUMNS = { "#", "Address", "Type", "Tag 1", "Tag 2", "Overrides" };
        List<Zone> zones = new ArrayList<>(); //the bot's order
        private final List<Zone> rows = new ArrayList<>();
        private final List<Integer> row_index = new ArrayList<>(); //position in zones of each row's device

        void setZones(List<Zone> zones) {
            this.zones = zones;
            rows.clear();
            row_index.clear();
            for(int i = 0; i < zones.size(); i++) {
                Zone zone = zones.get(i);
                rows.add(zone);
                row_index.add(i);
                if(zone.getSubAddress() != null) {
                    rows.add(zone.getSubAddress());
                    row_index.add(i);
                }
            }
            fireTableDataChanged();
        }

        Zone zoneAt(int row) {
            return rows.get(row);
        }

        int indexOf(int row) {
            return row_index.get(row);
        }

        //Table row of the device at this position in the bot's order, or -1
        int rowOf(int index) {
            return row_index.indexOf(index);
        }

        int rowOfAddress(double address) {
            for(int row = 0; row < rows.size(); row++) {
                if(rows.get(row).getAddress() == address) {
                    return row;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Zone zone = rows.get(row);
            if(column == COL_NUMBER) {
                return zone instanceof subZone ? "" : row_index.get(row) + 1;
            }
            if(column == COL_ADDRESS && zone instanceof subZone) {
                return "  └ " + zone.getAddress();
            }
            return cell(zone, column);
        }

        Object cell(Zone zone, int column) {
            switch(column) {
                case COL_ADDRESS: return String.valueOf(zone.getAddress());
                case COL_TYPE: return zone.getType();
                case COL_TAG1: return zone.getTag1();
                case COL_TAG2: return zone.getTag2();
                case COL_OVERRIDES: return zone.getOverrides();
                default: return "";
            }
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column >= COL_TYPE && !running && !converting;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            //Saved after the editor closes - reloading the table while it is still open confuses JTable
            Zone zone = rows.get(row);
            String text = value == null ? "" : value.toString();
            pending_edits++;
            SwingUtilities.invokeLater(() -> editZone(zone, column, text));
        }
    }
}
