import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.awt.event.KeyEvent;

public class FX2000 extends ConfigBot{

    protected String panelName() {
        return "FX2000";
    }

    protected void prepare(ZoneList zone_list) {
        organizeZones(zone_list);
    }

    protected List<String> requiredSettings(ZoneList zone_list) {
        List<String> settings = new ArrayList<>();
        if(zone_list.CONTAINS_AR) {
            settings.add("Auxiliary Reset in Base Control/Annun. Idx 3");
        }
        return settings;
    }

    protected void insertDevices(ZoneList zone_list) {
        Zone zone;

        if(!sensors.isEmpty()) {
            zone = sensors.get(0);
            skip_count = (int) zone.getAddress() - 1;

            for(int current_zone = 0; current_zone < sensors.size() && is_running; current_zone++) {
                zone = sensors.get(current_zone);
                if(current_zone > 0) {
                    skip_count += (int) zone.getAddress() - (int) sensors.get(current_zone - 1).getAddress() - 1;
                }
                insertAt(zone);
            }
        }

        if(!modules.isEmpty()) {
            zone = modules.get(0);
            skip_count = (int) zone.getAddress() - 1 - 100;

            for(int current_zone = 0; current_zone < modules.size() && is_running; current_zone++) {
                zone = modules.get(current_zone);
                if(current_zone > 0) {
                    skip_count += ((int) zone.getAddress() - 100) - ((int) modules.get(current_zone - 1).getAddress() - 100) - 1;
                }
                insertAt(zone);
            }
        }
    }
    
    protected void addPhotoDetector() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_TAB, 2);
        });
    }

    protected void addAlarmInputMod() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_I);
            bot.pressKey(KeyEvent.VK_TAB, 2);
        });
    }

    protected void addNonLatchedSupv() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_I);
            bot.pressKey(KeyEvent.VK_TAB);
            bot.pressKey(KeyEvent.VK_N, 2);
            bot.pressKey(KeyEvent.VK_TAB);
        });
    }

    protected void addLatchedSupv() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_I);
            bot.pressKey(KeyEvent.VK_TAB);
            bot.pressKey(KeyEvent.VK_L);
            bot.pressKey(KeyEvent.VK_TAB);
        });
    }

    protected void addHeatDetector() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_H);
            bot.pressKey(KeyEvent.VK_TAB,2);
        });
    }

    protected void addRelay() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_R);
            bot.pressKey(KeyEvent.VK_TAB, 2);
        });
    }

    protected void insertDevice(Zone zone) { 
        System.out.println("Inserting: " + zone.getZoneinfo());

        switch (zone.getType()) {
            case "Photo Detector":
                addPhotoDetector();
                break;
            case "Alarm Input":
            case "Alarm Input Class A":
                addAlarmInputMod();
                break;
            case "Non-latched Supervisory":
                addNonLatchedSupv();
                break;
            case "Latched Supervisory":
                addLatchedSupv();
                break;
            case "Heat Detector":
                addHeatDetector();
                break;
            case "Relay":
                addRelay();
                break;
        }
    }

    //Not used
    protected void updateType(Zone zone) {};

    @Override
    protected void updateRow(Zone zone) {
        if(skipRow()) {
            return;
        }

        updateTags(zone);

        bot.pressKey(KeyEvent.VK_ENTER, 1 , DEVICE_UPDATE_DELAY_STRENGTH); //make up for not updating Type
        
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

    protected void updateZone(Zone zone) {
        try {
            updateRow(zone);
        } catch (Exception e) {
            e.printStackTrace();
        }    
    }

    protected boolean validateType(Zone zone) {
        boolean result = false;
        switch (zone.getType()) {
            case "Photo Detector":
            case "Alarm Input":
            case "Alarm Input Class A":
            case "Non-latched Supervisory":
            case "Latched Supervisory":
            case "Heat Detector":
            case "Relay":
                result = true;
                break;
        }

        return result;
    }

    protected boolean validateZones(ZoneList zone_list) {
        boolean invalid_found = false;
        boolean current_zone_valid;
        String zone_errors;
        ArrayList<Integer> usedZones = new ArrayList<>(); //smoke/heat addresses
        ArrayList<Integer> used100Zones = new ArrayList<>(); //ipt/relay addresses
        
        //Add all addresses to check for duplicates later
        for(Zone zone :zone_list.zones) {     
            if(zone.isSensor()) {
                usedZones.add((int) zone.getAddress());
            } 
            else {
                used100Zones.add((int) zone.getAddress());
            }
        }

        if(!zone_list.DUPLICATES.isEmpty()) {
            invalid_found = true;
            System.out.println("Duplicates detected:");
            for(String s : zone_list.DUPLICATES) {
                System.out.println(s);
            }
        }

        for(Zone zone : zone_list.zones) {
            current_zone_valid = true;
            zone_errors = zone.getAddress() + " " + zone.getTag1() + " errors: ";

            //Check zone type if it is unknown or blank
            //Check address in valid range
            //Smoke, heat: 1 - 99
            //Ipt, Relay: 101 - 199
            if(Zone.checkTags(zone.getType(), new String[] { "unknown", "blank"})) {
                current_zone_valid = false;
                zone_errors += "unknown zone type, ";
            }
            else if(zone.isSensor()) {
                if((zone.getAddress() < 0 || zone.getAddress() > 99)) {
                    current_zone_valid = false;
                    zone_errors += "address out of range for sensor, ";
                }

                //Check for duplicate addresses 
                if(Collections.frequency(usedZones, (int) zone.getAddress()) > 1) {
                    current_zone_valid = false;
                    zone_errors += "duplicate sensor address, ";
                }
            } 
            else {
                if((int) zone.getAddress() < 101 || (int) zone.getAddress() > 199) {
                    current_zone_valid = false;
                    zone_errors += "address out of range for module, ";
                }

                //Check for duplicate addresses 
                if(Collections.frequency(used100Zones, (int) zone.getAddress()) > 1) {
                    current_zone_valid = false;
                    zone_errors += "duplicate module address, ";
                }
            }

            //Check tag lengths
            if(zone.getTag1().length() > 20 && !IGNORE_TAG_LENGTH) {
                current_zone_valid = false;
                zone_errors += "tag 1 length > 20, ";
            }

            if(zone.getTag2().length() > 20 && !IGNORE_TAG_LENGTH) {
                current_zone_valid = false;
                zone_errors += "tag 2 length > 20, ";
            }

            if(!validateType(zone)) {
                current_zone_valid = false;
                zone_errors += "invalid zone type for this configurator, ";
            }

            if (!current_zone_valid) {
                System.out.println(zone_errors);
                invalid_found = true;
            }
        }
        return invalid_found;
    }
}
