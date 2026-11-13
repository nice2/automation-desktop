import java.io.File;
import java.awt.event.KeyEvent;
import org.ini4j.Ini;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class ConfigBot extends Thread implements DataEntryBot.PauseHandler {
    protected int DELAY = 200; //Default 200. Delay time for everything. Multiply by delay strength to change length
    protected double DEVICE_INSERT_DELAY_STRENGTH = 2; // Default 2. Multiplied to DELAY. The Delay time after inserting a device.
    protected double DEVICE_UPDATE_DELAY_STRENGTH = 1.5; // Default 1.5. Multiplied to DELAY. The Delay time after updating a device (tag name, type, etc).
    protected boolean BYPASS_PAUSE = false; //Prevents the error prompt from showing
    protected boolean IGNORE_TAG_LENGTH = false; //Omits tag length requirement from errors
    protected boolean SKIP_INSERT_DEVICES = false; //Set the bot to update devices only instead of inserting first. Meant for when the devices are entered and other details (type, f1 tags,etc) are untouched.
    protected boolean WAIT_FOR_READY = true; //Wait for the configurator to finish processing instead of relying only on fixed delays
    protected int READY_TIMEOUT = 30000; //ms the configurator may stay busy before the bot pauses and asks for help
    protected String SETTINGS_FILE = "settings.ini";

    protected DataEntryBot bot;
    protected int skip_count;
    protected volatile boolean is_running = false; //used to stop the bot from running without closing process
    protected volatile boolean is_paused = false; //paused by the user, the setup prompt, or the ready check

    protected ArrayList<Zone> sensors; //smoke/heat devices
    protected ArrayList<Zone> modules; //module devices

    //The two passes the bot makes: insert every device, then go back over the rows to enter tags/types
    public enum Phase { INSERT, UPDATE }

    //Lets the GUI follow along. All methods are called from the bot thread.
    public interface Listener {
        void onProgress(Phase phase, int index, int total, Zone zone);
        void onPausedChanged(boolean paused);
        void onFinished(boolean completed);
    }

    //What the bot will do, in the order it does it. Built by plan() with the same code that does the real run.
    public static class Plan {
        public final ZoneList zone_list;
        public final List<Zone> inserts = new ArrayList<>();
        public final List<Zone> updates = new ArrayList<>();

        Plan(ZoneList zone_list) {
            this.zone_list = zone_list;
        }

        public List<Zone> get(Phase phase) {
            return phase == Phase.INSERT ? inserts : updates;
        }
    }

    private Plan plan;
    private boolean planning = false;
    private Listener listener;

    //Start point. Everything before start_index in start_phase is skipped.
    private Phase start_phase = Phase.INSERT;
    private int start_index = 0;
    private int insert_counter = 0;
    private int update_counter = 0;
    private boolean skipping_rows = false;
    protected boolean inserted_this_run = false; //true once the insert pass has run, so the update pass knows to jump to the top

    public ConfigBot() {
        try {
            readSettings();
            bot = new DataEntryBot(DELAY);
            bot.setPauseHandler(this);
            if(WAIT_FOR_READY) {
                bot.enableReadyCheck(READY_TIMEOUT);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //Name printed in messages, e.g. "FX4000"
    protected abstract String panelName();

    //Sort/adjust the zone list before anything is inserted (organizeZones, AP start, etc.)
    protected abstract void prepare(ZoneList zone_list);

    //Settings the user has to turn on in the configurator before entry. Empty = no setup pause.
    protected abstract List<String> requiredSettings(ZoneList zone_list);

    //The insert pass. Call insertAt(zone) for every device, in order, keeping skip_count up to date.
    protected abstract void insertDevices(ZoneList zone_list);

    protected abstract void addPhotoDetector();

    protected abstract void addNonLatchedSupv();

    protected abstract void addLatchedSupv();

    protected abstract void addHeatDetector();

    protected abstract void addRelay();

    protected abstract void insertDevice(Zone zone);

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    //Start somewhere other than the beginning. index is a position in plan().get(phase).
    //Starting in the update phase skips inserting entirely.
    public void setStartPoint(Phase phase, int index) {
        start_phase = phase;
        start_index = Math.max(0, index);
    }

    //Use a specific configurator window instead of locking onto whatever is in front when typing starts
    public void setTargetWindow(long hwnd) {
        bot.setTargetWindow(hwnd);
    }

    //Bring the configurator back to the front (e.g. after clicking Resume in the GUI)
    public void focusTarget() {
        bot.focusTarget();
    }

    //Reads the zone list and works out the insert and update order without pressing any keys
    public Plan plan() throws Exception {
        if(plan != null) {
            return plan;
        }

        ZoneList zone_list = new ZoneList();
        zone_list.readFile();
        prepare(zone_list);

        Plan result = new Plan(zone_list);
        plan = result;

        boolean was_running = is_running;
        planning = true;
        is_running = true; //the insert loops only run while is_running
        bot.setDryRun(true);
        try {
            insertDevices(zone_list);
            enterZoneList(zone_list);
        } finally {
            bot.setDryRun(false);
            is_running = was_running;
            planning = false;
        }

        return result;
    }

    public void run() {
        System.out.println("Starting " + panelName() + " Data Entry");
        boolean completed = false;

        try {
            is_running = true;
            setIsPaused(false);

            Plan p = plan();
            ZoneList zone_list = p.zone_list;
            zone_list.displayZoneList();

            System.out.println("----------------------------------------------------------------");
            if (validateZones(zone_list)) {
                is_running = false;
                System.out.println("----------------------------------------------------------------");
                System.out.println("Errors found in zone list, please correct them and run again.");
                System.out.println("----------------------------------------------------------------");
                System.out.println(panelName() + " entry did not run");
                return;
            }

            boolean from_beginning = start_phase == Phase.INSERT && start_index == 0;
            List<String> setup = requiredSettings(zone_list);

            //Only ask for setup on a fresh run. When resuming, it was already done.
            if(!setup.isEmpty() && !BYPASS_PAUSE && from_beginning) {
                System.out.println("The following settings need to be enabled for data entry:");
                for(String s : setup) {
                    System.out.println(s);
                }
                System.out.println("Please make necessary changes, then press Resume (or the start F-key) to continue.");
                System.out.println("----------------------------------------------------------------");

                setIsPaused(true);
                while(is_paused && is_running) {
                    Thread.sleep(100); //Wait until resumed
                }
            }

            if(!from_beginning) {
                List<Zone> order = p.get(start_phase);
                String where = start_index < order.size() ? order.get(start_index).getZoneinfo() : "the end";
                System.out.println("Starting at " + (start_phase == Phase.INSERT ? "insert" : "update") + " #" + (start_index + 1) + ": " + where);
            }

            insert_counter = 0;
            update_counter = 0;

            if(is_running && !SKIP_INSERT_DEVICES && start_phase == Phase.INSERT) {
                inserted_this_run = true;
                insertDevices(zone_list);

                //Additional delay to ensure final device is added
                if(!WAIT_FOR_READY) {
                    Thread.sleep(DELAY);
                }
            }
            else {
                System.out.println("Skipping device insertion.");
            }

            if(is_running) {
                enterZoneList(zone_list);
            }

            if(is_running && !bot.isStopped()) {
                System.out.println(panelName() + " Entry Complete");
                completed = true;
            }
            else {
                System.out.println(panelName() + " entry stopped");
            }
        }
        catch(Exception e) {
            e.printStackTrace();
        }
        finally {
            is_running = false;
            if(listener != null) {
                listener.onFinished(completed);
            }
        }
    }

    //Every device in the insert pass goes through here
    protected void insertAt(Zone zone) {
        if(planning) {
            plan.inserts.add(zone);
            return;
        }

        int index = insert_counter++;
        if(!is_running || (start_phase == Phase.INSERT && index < start_index)) {
            return; //before the start point - already in the configurator
        }

        report(Phase.INSERT, index, zone);
        insertDevice(zone);
    }

    //Every device in the update pass goes through here
    protected void updateAt(Zone zone) {
        if(planning) {
            plan.updates.add(zone);
            return;
        }

        int index = update_counter++;
        if(!is_running) {
            return;
        }

        if(start_phase == Phase.UPDATE && index < start_index) {
            //Before the start point: step over this device's rows without changing them
            skipping_rows = true;
            try {
                updateZone(zone);
            } finally {
                skipping_rows = false;
            }
            return;
        }

        report(Phase.UPDATE, index, zone);
        updateZone(zone);
    }

    private void report(Phase phase, int index, Zone zone) {
        System.out.println((phase == Phase.INSERT ? "Inserting: " : "Updating: ") + zone.getZoneinfo());
        if(listener != null) {
            listener.onProgress(phase, index, plan == null ? -1 : plan.get(phase).size(), zone);
        }
    }

    //Call at the top of updateRow. When skipping rows before the start point, moves down a row and returns true.
    protected boolean skipRow() {
        if(skipping_rows) {
            bot.pressKey(KeyEvent.VK_DOWN);
            return true;
        }
        return false;
    }

    //The old fixed pause before each step. Not needed when the ready check is on.
    protected void stepPause() throws InterruptedException {
        if(!WAIT_FOR_READY) {
            Thread.sleep(DELAY);
        }
    }

    //Shared insert flow: open the insert dialog, pick the device type, move to the address, confirm, and back out.
    //chooseType only presses the keys that differ between device types.
    protected void insertWith(double insert_delay_strength, Runnable chooseType) {
        open();
        chooseType.run();
        skipDevices();
        bot.pressKey(KeyEvent.VK_ENTER, 1, insert_delay_strength);
        bot.pressKey(KeyEvent.VK_ESCAPE);
        bot.pressKey(KeyEvent.VK_END);
    }

    protected void insertWith(Runnable chooseType) {
        insertWith(DEVICE_INSERT_DELAY_STRENGTH, chooseType);
    }

    protected void skipDevices() {
        bot.pressKey(KeyEvent.VK_RIGHT, skip_count);
    }

    protected void open() {
        try {
            stepPause();
            bot.pressCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_F10, 1);

            bot.pressKey(KeyEvent.VK_DOWN);
            bot.pressKey(KeyEvent.VK_ENTER, 1 , DEVICE_INSERT_DELAY_STRENGTH);

        } catch (Exception e) {
            System.err.println(e);
        }
    }

    protected void updateTags(Zone zone) {
        try {
            stepPause();
            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);
            bot.pasteText(zone.getTag1());
            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);
            bot.pasteText(zone.getTag2());
            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected abstract void updateType(Zone zone);

    protected void updateRow(Zone zone) {
        if(skipRow()) {
            return;
        }

        updateTags(zone);
        updateType(zone);

        if(zone.isNS()) {
            bot.pressKey(KeyEvent.VK_N, 1, DEVICE_UPDATE_DELAY_STRENGTH);
        }

        bot.pressKey(KeyEvent.VK_ENTER, 1 , DEVICE_UPDATE_DELAY_STRENGTH);

        if(zone.isAR()) {
            bot.pressKey(KeyEvent.VK_A);
            bot.pressKey(KeyEvent.VK_ENTER, 1 , DEVICE_UPDATE_DELAY_STRENGTH);
        }

        bot.pressKey(KeyEvent.VK_ESCAPE);
        bot.pressKey(KeyEvent.VK_DOWN);
    }

    protected abstract void updateZone(Zone zone);

    protected void enterZoneList(ZoneList zone_list) {
        try {
            bot.pressKey(KeyEvent.VK_HOME, 1, 1);
            for(Zone zone : zone_list.zones) {
                if(!zone.getType().equals("Blank Device")) {
                    updateAt(zone);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //Check each zone to see if it meets the configurator's requirements. Returns True if one incorrect device found
    protected abstract boolean validateZones(ZoneList zone_list);

    //Check if zone's type is typically used with this configurator
    protected abstract boolean validateType(Zone zone);

    protected void organizeZones(ZoneList zone_list) {
        sensors = new ArrayList<Zone>(); //smoke/heat addresses
        modules = new ArrayList<Zone>(); //module addresses

        //Add to respective arrays for organized inserting and duplication checking
        for(Zone zone :zone_list.zones) {
            if(zone.isSensor()) {
                sensors.add(zone);
            }
            else {
                modules.add(zone);
            }
        }
    }

    protected void readSettings() {
        try{
            Ini ini;
            File ini_file = new File(SETTINGS_FILE);

            //Create settings file if doesn't exist
            if(!ini_file.exists()) {
                ini_file.createNewFile();
            }

            ini = new Ini(ini_file);

            //Add settings if doesn't exist - only checks if ini section exists, not keys
            if(!ini.containsKey("Key Delay")) {
                ini.add("Key Delay");
                ini.putComment("Key Delay", 
                    String.format("delayTime - Default %s. Delay time for everything. Multiply by delay strength to change duration. \n" +
                    "#deviceInsertDelayStrength - Default %s. Multiplied to delayTime. The Delay time after inserting a device. \n" +
                    "#deviceUpdateDelayStrength - Default %s. Multiplied to delayTime. The Delay time after updating a device (tag name, type, etc).",
                    DELAY, DEVICE_INSERT_DELAY_STRENGTH, DEVICE_UPDATE_DELAY_STRENGTH)
                );
                ini.put("Key Delay", "delayTime", DELAY);
                ini.put("Key Delay", "deviceInsertDelayStrength", DEVICE_INSERT_DELAY_STRENGTH);
                ini.put("Key Delay", "deviceUpdateDelayStrength", DEVICE_UPDATE_DELAY_STRENGTH);
            }

            if(!ini.containsKey("Ready Check")) {
                ini.add("Ready Check");
                ini.putComment("Ready Check",
                    String.format("waitForReady - Default %s. After each key press, wait until the configurator has finished processing. \n" +
                    "#   The delays above then only count down while the configurator is idle, and start over whenever it is busy. \n" +
                    "#   Set to false to go back to fixed delays only. \n" +
                    "#readyTimeout - Default %s. Milliseconds the configurator may stay busy before the bot pauses and asks you to check it.",
                    WAIT_FOR_READY, READY_TIMEOUT)
                );
                ini.put("Ready Check", "waitForReady", WAIT_FOR_READY);
                ini.put("Ready Check", "readyTimeout", READY_TIMEOUT);
            }

            if(!ini.containsKey("Options")) {
                ini.add("Options");
                ini.putComment("Options", 
                    "bypassPause - Prevents the error prompt from showing. \n" +
                    "#ignoreTagLength - Omits tag length requirement from errors. \n" +
                    "#skipInsertDevices - Set the bot to update devices only instead of inserting first. Meant for when the devices are entered and other details (type, f1 tags,etc) are untouched."
                );
                ini.put("Options", "bypassPause", BYPASS_PAUSE);
                ini.put("Options", "ignoreTagLength", IGNORE_TAG_LENGTH);
                ini.put("Options", "skipInsertDevices", SKIP_INSERT_DEVICES);   
            }

            ini.store(ini_file);

            //Read settings
            DELAY = ini.get("Key Delay", "delayTime", int.class);
            DEVICE_INSERT_DELAY_STRENGTH = ini.get("Key Delay", "deviceInsertDelayStrength", double.class);
            DEVICE_UPDATE_DELAY_STRENGTH = ini.get("Key Delay", "deviceUpdateDelayStrength", double.class);
            BYPASS_PAUSE = ini.get("Options", "bypassPause", boolean.class);
            IGNORE_TAG_LENGTH = ini.get("Options", "ignoreTagLength", boolean.class);
            SKIP_INSERT_DEVICES = ini.get("Options", "skipInsertDevices", boolean.class);
            WAIT_FOR_READY = ini.get("Ready Check", "waitForReady", boolean.class);
            READY_TIMEOUT = ini.get("Ready Check", "readyTimeout", int.class);
            
        }catch(Exception e) {
            e.printStackTrace();
        }
    }
    
    public void setIsRunning(boolean status) {
        is_running = status;

        //Stop further inputs. The bot object stays so a key press mid-step can't hit a null reference.
        if(!is_running) {
            is_paused = false;
            if(bot != null) {
                bot.stop();
            }
        }
    }

    public boolean getIsRunning() {
        return is_running;
    }

    public void setIsPaused(boolean status) {
        boolean changed = is_paused != status;
        is_paused = status;

        if(changed && listener != null) {
            listener.onPausedChanged(status);
        }
    }

    public boolean getIsPaused() {
        return is_paused;
    }
}
