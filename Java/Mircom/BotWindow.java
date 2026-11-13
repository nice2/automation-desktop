import java.awt.BorderLayout;
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
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;

//Control window for the data entry bots: convert the zone list, pick the configurator,
//choose where to start, and start/pause/resume/stop.
public class BotWindow extends JFrame implements ConfigBot.Listener {

    //Each configurator the bot can drive, how to recognise its window, and its old start hotkey
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

    private ConfigBot.Plan plan;
    private ConfigBot.Phase shown_phase = ConfigBot.Phase.INSERT;
    private volatile ConfigBot bot;
    private boolean running = false;
    private boolean user_paused = false;
    private boolean converting = false;

    public BotWindow() {
        super("Mircom Data Entry");
        installLog();
        buildLayout();
        wireActions();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if(bot != null && bot.getIsRunning()) {
                    bot.setIsRunning(false);
                }
                dispose();
                System.exit(0);
            }
        });

        setSize(900, 760);
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
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setMaxWidth(80);
        JScrollPane table_scroll = new JScrollPane(table);
        table_scroll.setBorder(BorderFactory.createTitledBorder("Devices in the order the bot handles them - select where to start"));

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane log_scroll = new JScrollPane(log);
        log_scroll.setBorder(BorderFactory.createTitledBorder("Log"));

        table_scroll.setPreferredSize(new java.awt.Dimension(600, 300));
        log_scroll.setPreferredSize(new java.awt.Dimension(600, 200));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, table_scroll, log_scroll);
        split.setResizeWeight(0.6);

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

        JPanel content = new JPanel(new BorderLayout());
        content.add(top, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(controls, BorderLayout.SOUTH);
        setContentPane(content);
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

            int keep = reset_start ? 0 : Math.max(0, table.getSelectedRow());
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

    private void selectRow(int row) {
        if(row >= 0 && row < table_model.getRowCount()) {
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
        }
        else {
            table.clearSelection();
        }
    }

    private void updateStartLabel() {
        int row = table.getSelectedRow();
        String phase_name = shown_phase == ConfigBot.Phase.INSERT ? "insert" : "update";

        if(plan == null || row < 0) {
            start_label.setText(plan == null ? "No zone list loaded" : "Select a device to start from");
        }
        else {
            Zone zone = table_model.zones.get(row);
            start_label.setText(String.format("Start at %s #%d of %d:  %s", phase_name, row + 1,
                table_model.getRowCount(), zone.getZoneinfo()));
        }

        if(shown_phase == ConfigBot.Phase.INSERT) {
            hint_label.setText(row > 0
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

    //from_hotkey: started with the old F-key while the configurator is in front, so lock onto that window
    private void start(boolean from_hotkey) {
        if(running || converting) {
            return;
        }

        DataEntryBot.WindowInfo window = (DataEntryBot.WindowInfo) window_box.getSelectedItem();
        if(!from_hotkey && window == null) {
            JOptionPane.showMessageDialog(this, "No open " + panel().label + " window found.\nOpen the job in the configurator, then press Refresh.",
                "No configurator window", JOptionPane.WARNING_MESSAGE);
            return;
        }

        ConfigBot.Phase phase = shown_phase;
        int index = Math.max(0, table.getSelectedRow());

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
        if(!from_hotkey) {
            new_bot.setTargetWindow(window.hwnd());
        }

        bot = new_bot;
        user_paused = false;
        progress.setMaximum(Math.max(1, plan.get(phase).size()));
        progress.setValue(index);
        progress.setString("");
        status_label.setText("Starting...");
        setRunning(true);

        //Bring the configurator forward (allowed because our window was just clicked), then start typing
        new Thread(() -> {
            if(!from_hotkey) {
                new_bot.focusTarget();
            }
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

    //refocus: bring the configurator back to the front first (GUI button). Not needed from the F-key.
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
        updateControls();
    }

    private void updateControls() {
        boolean paused = running && bot != null && bot.getIsPaused();
        boolean idle = !running && !converting;

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

    //Old F-key start: starts this configurator, or resumes if it is paused
    public void hotkeyStart(Panel hotkey_panel) {
        if(running) {
            if(bot != null && bot.getIsPaused()) {
                resume(false);
            }
            return;
        }

        if(panel() != hotkey_panel) {
            panel_box.setSelectedItem(hotkey_panel); //reloads the plan from the beginning
        }
        start(true);
    }

    public void hotkeyStop() {
        stop();
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
        JFileChooser chooser = new JFileChooser(new File("assets"));
        chooser.setFileFilter(new FileNameExtensionFilter("Zone list (*.xlsx, *.csv)", "xlsx", "csv"));
        if(chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File source = chooser.getSelectedFile();
        if(source.getAbsoluteFile().equals(new File(ZONE_FILE).getAbsoluteFile())) {
            JOptionPane.showMessageDialog(this, "That is the bot's own output file. Pick the original Excel/CSV zone list.");
            return;
        }

        converting = true;
        updateControls();
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

    private static class ZoneTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = { "#", "Address", "Type", "Tag 1", "Tag 2" };
        List<Zone> zones = new ArrayList<>();

        void setZones(List<Zone> zones) {
            this.zones = zones;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return zones.size();
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
            Zone zone = zones.get(row);
            switch(column) {
                case 0: return row + 1;
                case 1: return zone.getAddress();
                case 2: return zone.getType();
                case 3: return zone.getTag1();
                default: return zone.getTag2();
            }
        }
    }
}
