import java.io.File;
import java.awt.event.KeyEvent;
import org.ini4j.Ini;
import java.util.ArrayList;
import java.util.Collections;

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
    protected volatile boolean is_paused = false; //used to prompt user to enable AR related settings -- MAKE USE OF THIS IF PAUSING GUI

    protected ArrayList<Zone> sensors; //smoke/heat devices
    protected ArrayList<Zone> modules; //module devices

    public ConfigBot() {
        try {
            readSettings();
            bot = new DataEntryBot(DELAY);
            if(WAIT_FOR_READY) {
                bot.enableReadyCheck(READY_TIMEOUT, this);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public abstract void run();

    protected abstract void addPhotoDetector();

    protected abstract void addNonLatchedSupv();

    protected abstract void addLatchedSupv();

    protected abstract void addHeatDetector();

    protected abstract void addRelay();

    protected abstract void insertDevice(Zone zone);

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
            Thread.sleep(DELAY);
            bot.pressCombo(KeyEvent.VK_SHIFT, KeyEvent.VK_F10, 1);

            bot.pressKey(KeyEvent.VK_DOWN);
            bot.pressKey(KeyEvent.VK_ENTER, 1 , DEVICE_INSERT_DELAY_STRENGTH);
            
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    protected void updateTags(Zone zone) {
        try {
            Thread.sleep(DELAY);
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
                System.out.println("Updating: " + zone.getZoneinfo());
                if(!zone.getType().equals("Blank Device")) {
                    updateZone(zone);
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
        if(!is_running && bot != null) {
            bot.stop();
        }
    }

    public void setIsPaused(boolean status) {
        is_paused = status;
    }

    public boolean getIsPaused() {
        return is_paused;
    }
}
