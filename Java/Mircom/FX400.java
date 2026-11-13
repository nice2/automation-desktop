import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.awt.event.KeyEvent;
import java.io.File;
import org.ini4j.Ini;


public class FX400 extends ConfigBot{

    protected String panelName() {
        return "FX400";
    }

    //Sensors/modules both use the same set of addresses for FX400, so no organizeZones
    protected void prepare(ZoneList zone_list) {
        //Duct detectors have a spare input. Marked here (not while inserting) so the update pass
        //still adds the spare row when insertion is skipped or resumed past them.
        for(Zone zone : zone_list.zones) {
            if(zone.getType().equals("Photo Detector") && Zone.checkTags(zone.getTag1(), new String[] { "duct" })) {
                zone.setDualInput(true);
            }
        }
    }

    protected List<String> requiredSettings(ZoneList zone_list) {
        List<String> settings = new ArrayList<>();
        if(zone_list.CONTAINS_AR) {
            settings.add("AR/Buzzer Silence");
        }
        return settings;
    }

    protected void insertDevices(ZoneList zone_list) {
        ArrayList<Zone> zonelist = zone_list.zones;
        if(zonelist.isEmpty()) {
            return;
        }

        Zone zone = zonelist.get(0);
        skip_count = (int) zone.getAddress() - 1;

        for(int current_zone = 0; current_zone < zonelist.size() && is_running; current_zone++) {
            zone = zonelist.get(current_zone);
            if(current_zone > 0) {
                skip_count += (int) zone.getAddress() - (int) zonelist.get(current_zone - 1).getAddress() - 1;
            }
            insertAt(zone);
        }
    }

    protected void addPhotoDetector() {
        insertWith(() -> {
            //For MIX-4011
            //bot.pressKey(KeyEvent.VK_UP, 1);

            //MIX-4010 is the default
            bot.pressKey(KeyEvent.VK_TAB, 3);
        });
    }

    protected void addDuctDetector() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_P, 5);
            bot.pressKey(KeyEvent.VK_TAB, 2);
        });
    }

    protected void addAlarmInputMod() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_D, 2);
            bot.pressKey(KeyEvent.VK_TAB, 3);
        });
    }

    protected void addNonLatchedSupv() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_D, 2);
            bot.pressKey(KeyEvent.VK_TAB, 2);
            bot.pressKey(KeyEvent.VK_N);
            bot.pressKey(KeyEvent.VK_TAB);
        });
    }

    protected void addLatchedSupv() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_D,2);
            bot.pressKey(KeyEvent.VK_TAB,2);
            bot.pressKey(KeyEvent.VK_L);
            bot.pressKey(KeyEvent.VK_TAB);
        });
    }

    protected void addHeatDetector() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_H,3);
            bot.pressKey(KeyEvent.VK_TAB,3);
        });
    }

    protected void addAlarmInputClassA() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_D, 2);
            bot.pressKey(KeyEvent.VK_TAB, 1);
            bot.pressKey(KeyEvent.VK_C, 1);
            bot.pressKey(KeyEvent.VK_TAB, 2);
        });
    }

    protected void addRelay() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_D);
            bot.pressKey(KeyEvent.VK_TAB, 2);
        });
    }

    protected void addTroubleInput() {
        insertWith(() -> {
            bot.pressKey(KeyEvent.VK_D, 2);
            bot.pressKey(KeyEvent.VK_TAB, 2);
            bot.pressKey(KeyEvent.VK_T);
            bot.pressKey(KeyEvent.VK_TAB);
        });
    }

    protected void insertDevice(Zone zone){
        switch (zone.getType()) {
            case "Photo Detector":
                //Duct detectors have spare
                if (Zone.checkTags(zone.getTag1(), new String[] { "duct" })){
                    addDuctDetector();
                }
                else {
                    addPhotoDetector();
                }
                break;
            case "Alarm Input":
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
            case "Alarm Input Class A":
                addAlarmInputClassA();
                break;
            case "Trouble Input":
                addTroubleInput();
                break;
            case "Relay":
                addRelay();
                break;
        }
    }

    protected void updateType(Zone zone) {
        try {
            stepPause();
            switch(zone.getType()) {
                case "Photo Detector":
                    bot.pressKey(KeyEvent.VK_A);
                    break;
                case "Alarm Input":
                    bot.pressKey(KeyEvent.VK_M);
                    bot.pressKey(KeyEvent.VK_A, 3);
                    break;
                case "Non-latched Supervisory":
                    bot.pressKey(KeyEvent.VK_N);
                    break;
                case "Latched Supervisory":
                    bot.pressKey(KeyEvent.VK_L);
                    break;
                case "Heat Detector":
                    bot.pressKey(KeyEvent.VK_M);
                    bot.pressKey(KeyEvent.VK_A, 3);
                    break;
                case "Trouble Input":
                    bot.pressKey(KeyEvent.VK_N);
                    bot.pressKey(KeyEvent.VK_T);
                    break;
                case "Blank Device":
                    bot.pressKey(KeyEvent.VK_N);
                    bot.pressKey(KeyEvent.VK_B, 2);
                    break;
                case "Relay":
                    bot.pressKey(KeyEvent.VK_R);
                    break;
            }
            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected void updateZone(Zone zone) {
        try {
            updateRow(zone);

            if (zone.getSubAddress() != null) {
                updateRow(zone.getSubAddress());
            } else if(zone.isDualInput() || zone.getType().equals("Relay")) {
                // add empty
                updateRow(new subZone(zone.getAddress()+0.1, "    Spare", zone.getTag2()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
    }

    protected boolean validateType(Zone zone) {
        boolean result = false;
        switch (zone.getType()) {
            case "Photo Detector":
            case "Alarm Input":
            case "Non-latched Supervisory":
            case "Latched Supervisory":
            case "Heat Detector":
            case "Alarm Input Class A":
            case "Trouble Input":
            case "Relay":
            case "Blank Device":
                result = true;
                break;
        }

        return result;
    }

    protected boolean validateZones(ZoneList zone_list) {
        boolean invalid_found = false;
        boolean current_zone_valid;
        String zone_errors;
        ArrayList<Integer> usedZones = new ArrayList<>();
        
        //Add all addresses to check for duplicates later
        for(Zone zone :zone_list.zones) {
            usedZones.add((int) zone.getAddress());
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

            //Check for duplicate addresses
            if(Collections.frequency(usedZones, (int) zone.getAddress()) > 1) {
                current_zone_valid = false;
                zone_errors += "duplicate address, ";
            }

            //Check address in valid range
            if ((int) zone.getAddress() < 1 || (int) zone.getAddress() > 240) {
                current_zone_valid = false;
                zone_errors += "address out of range, ";
            }

            //Check zone type if it is unknown or blank
            if(Zone.checkTags(zone.getType(), new String[] { "unknown", "blank"})) {
                current_zone_valid = false;
                zone_errors += "unknown zone type, ";
            }

            /* //Can be skipped with options
            //Check if tag1 is named correctly
            if(Zone.checkTags(zone.getTag1(), new String[] { "spare", "blank", "unknown" })) {
                current_zone_valid = false;
                zone_errors += "invalid tag 1, ";
            }
            */

            //Check zone tag lengths
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
           
            if(zone.getSubAddress() != null) {
                /* 
                //Cannot be reliably used if there are type overrides
                //Check if subzone is spare, valve, or waterflow only
                if(!Zone.checkTags(zone.getSubAddress().getTag1(), new String[] { "spare", "valve", "waterfl", "valve", "tamper", "stat", "pump", "intake", "discharge",
                "jockey", "jocky", "bypass", "recall"})) {
                    current_zone_valid = false;
                    zone_errors += "invalid tag 1 name for subzone, ";
                }
                */

                
                //Check zone type if it is unknown
                if(Zone.checkTags(zone.getSubAddress().getType(), new String[] { "unknown" })) {
                    current_zone_valid = false;
                    zone_errors += "subzone unknown zone type, ";
                }
                

                //Subzone tag 2 lengths
                if(zone.getSubAddress().getTag1().length() > 20 && !IGNORE_TAG_LENGTH) {
                    current_zone_valid = false;
                    zone_errors += "subzone tag 1 length > 20, ";
                }

                if(zone.getSubAddress().getTag2().length() > 20 && !IGNORE_TAG_LENGTH) {
                    current_zone_valid = false;
                    zone_errors += "subzone tag 2 length > 20, ";
                }

                if(!validateType(zone.getSubAddress())) {
                    current_zone_valid = false;
                    zone_errors += "invalid subzone type for this configurator, ";
                }

                /*
                //Check if zone address matches with sub address
                if((int) zone.getAddress() != (int) zone.getSubAddress().getAddress()) {
                    current_zone_valid = false;
                    zone_errors += "address and subaddress do not match, ";
                }*/
            }

            if (!current_zone_valid) {
                System.out.println(zone_errors);
                invalid_found = true;
            }
        }
        return invalid_found;
    }
}
