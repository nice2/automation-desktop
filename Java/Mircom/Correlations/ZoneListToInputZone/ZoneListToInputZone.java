import java.util.Scanner;
import java.awt.RenderingHints.Key;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;

//For Input Zone updating: create input zones in advance (preferably type Monitor) then select the first zone to be updated
//To update logic: Get the first input zone address (IZ-##) then write it in fsae_zones inside [brackets]. Press the key inside the Advanced Logic window for each zone to be updated.

public class ZoneListToInputZone extends Thread {
    
    public ZoneListToInputZone() {
         try {
            bot = new DataEntryBot(DELAY);           
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private int DELAY = 200; //Default 200. Delay time for everything. Multiply by delay strength to change length
    private double DEVICE_UPDATE_DELAY_STRENGTH = 1.5; // Default 1. Multiplied to DELAY. The Delay time after updating a device (tag name, type, etc).
    private String FILE_NAME = "../../assets/temp_zones.csv";

    private DataEntryBot bot;
    private ArrayList<Zone> sensors; //smoke/heat devices
    private ArrayList<Zone> modules; //module devices
    private ArrayList<Zone> phones;  //phone devices

    public void run() {
        try {
            ZoneList zone_list = new ZoneList(FILE_NAME);
            zone_list.readFile();
            zone_list.displayZoneList();
            organizeZones(zone_list);
            
            for (Zone z : phones) {
                updateRow(z);
            }

            for (Zone z : sensors) {
                updateRow(z);
            }

            for (Zone z : modules) {
                updateRow(z);
            }

            System.out.println("Data Entry Complete.");
            System.exit(MAX_PRIORITY);
        }
        catch(Exception e) {
            e.printStackTrace();
        }
    }

    protected void updateRow(Zone zone) {
        try {
            System.out.println("Updating: " + zone.getZoneinfo());

            Thread.sleep(DELAY);

            //Tag 1
            bot.pressKey(KeyEvent.VK_ENTER, 1);
            bot.pasteText(zone.getTag1());
            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);

            //Tag 2
            bot.pasteText(zone.getTag2());
            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);

            //Type
            if(zone.isSensor() || zone.getType().equals("Alarm Input")) {
                 //Alarm
                bot.pressKey(KeyEvent.VK_A, 1);
            } 
            else if(zone.getType().equals("Non-latched Supervisory") || zone.getType().equals("Latched Supervisory")) {
                //Supv
                bot.pressKey(KeyEvent.VK_S, 1);
            }
            else {
                //Mon
                bot.pressKey(KeyEvent.VK_M, 1);
            }

            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH * 3);

            //F1, F4
            if(zone.isNS()) {
                bot.pressKey(KeyEvent.VK_N, 1);
                bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);
            } else if(zone.getType().equals("Latched Supervisory")) {
                bot.pressKey(KeyEvent.VK_ENTER, 1);
                bot.pressKey(KeyEvent.VK_C, 1);
                bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);
            }

            bot.pressKey(KeyEvent.VK_ESCAPE, 1);
            bot.pressKey(KeyEvent.VK_DOWN);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected void organizeZones(ZoneList zone_list) {
        phones = new ArrayList<Zone>(); //phone addresses
        sensors = new ArrayList<Zone>(); //smoke/heat addresses
        modules = new ArrayList<Zone>(); //module addresses
        
        //Add to respective arrays for organized inserting and duplication checking
        for(Zone zone : zone_list.zones) {     
            if(zone.isSensor()) {
                //Update tags for Dual Heats and Smoke CO specifically
                if (zone.getType().equals("Photo Detector") && zone.isDualInput()) {
                    zone.setTag1("Smoke Detector");
                }
                
                if (zone.getType().equals("Heat Detector") && zone.isDualInput()) {
                    zone.setTag1("Smoke Detector");
                }
                sensors.add(zone);
            } 
            else if(zone.getType().equals("Telephone Module")) {
                phones.add(zone);
            }
            else {
                modules.add(zone);
            }
        }
    }
}
