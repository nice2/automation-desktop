import java.awt.Robot;
import java.awt.event.KeyEvent;

public class DataEntryBot {
    Robot robot;
    int delay = 200;
    private int skipCount = 19;
    
    private final boolean ROBOT_DELAY = true;
    private final boolean THREAD_DELAY = true;

    public DataEntryBot(){
        try {
            Thread.sleep(5000);
            robot = new Robot();
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    public void skipDevices(){
        try {
            Thread.sleep(delay);
            for(int i = 0; i < skipCount; i++){
                robot.keyPress(KeyEvent.VK_RIGHT); robot.keyRelease(KeyEvent.VK_RIGHT); Thread.sleep(15);
            }

        } catch (Exception e) {
            // TODO: handle exception
        }

    }
    

    public void open(){
        try {
            Thread.sleep(delay);
            robot.keyPress(KeyEvent.VK_SHIFT);
            robot.keyPress(KeyEvent.VK_F10);
            robot.keyRelease(KeyEvent.VK_SHIFT);
            robot.keyRelease(KeyEvent.VK_F10);
            robot.delay(delay);
            robot.keyPress(KeyEvent.VK_DOWN); robot.keyRelease(KeyEvent.VK_DOWN);
            robot.delay(delay);
            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER);
            robot.delay(delay);
            
        } catch (Exception e) {
            System.err.println(e);
        }
    }
    

    public void addPhotoDetector(){
        try {
            open();
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            skipDevices();
            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER);
            robot.delay(delay);
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE);
            robot.delay(delay);
            robot.keyPress(KeyEvent.VK_END); robot.keyRelease(KeyEvent.VK_END);
            
        } catch (Exception e) {
            System.err.println(e);
        }
    }


    public void addAlarmInputMod(){
        try {
            open();
            robot.keyPress(KeyEvent.VK_D); robot.keyRelease(KeyEvent.VK_D); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_D); robot.keyRelease(KeyEvent.VK_D); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);

            skipDevices();

            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE); robot.delay(delay);

            robot.keyPress(KeyEvent.VK_END); robot.keyRelease(KeyEvent.VK_END); robot.delay(delay);
            
        } catch (Exception e) {
            System.err.println(e);
        }
    }


    public void addNonLatchedSupv(){
        try {
            open();
            robot.keyPress(KeyEvent.VK_D); robot.keyRelease(KeyEvent.VK_D); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_D); robot.keyRelease(KeyEvent.VK_D); robot.delay(delay);

            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);

            robot.keyPress(KeyEvent.VK_N); robot.keyRelease(KeyEvent.VK_N); robot.delay(delay);

            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            skipDevices();
            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_END); robot.keyRelease(KeyEvent.VK_END); robot.delay(delay);
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    public void updateSkipCount(int skipCount){
        this.skipCount = skipCount;
    }



    public void addLatchedSupv(){
        try {
            open();
            robot.keyPress(KeyEvent.VK_D); robot.keyRelease(KeyEvent.VK_D); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_D); robot.keyRelease(KeyEvent.VK_D); robot.delay(delay);

            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);

            robot.keyPress(KeyEvent.VK_L); robot.keyRelease(KeyEvent.VK_L); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            skipDevices();

            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_END); robot.keyRelease(KeyEvent.VK_END); robot.delay(delay);
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    public void addHeatDetector(){
        try {
            open();
            robot.keyPress(KeyEvent.VK_H); robot.keyRelease(KeyEvent.VK_H); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_H); robot.keyRelease(KeyEvent.VK_H); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_H); robot.keyRelease(KeyEvent.VK_H); robot.delay(delay);

            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);

            skipDevices();

            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_END); robot.keyRelease(KeyEvent.VK_END); robot.delay(delay);

        } catch (Exception e) {
            System.err.println(e);
        }
    }



    public void addRelay(){
        try {
            open();
            robot.keyPress(KeyEvent.VK_D); robot.keyRelease(KeyEvent.VK_D); robot.delay(delay);

            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_TAB); robot.keyRelease(KeyEvent.VK_TAB); robot.delay(delay);

            skipDevices();

            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE); robot.delay(delay);
            robot.keyPress(KeyEvent.VK_END); robot.keyRelease(KeyEvent.VK_END); robot.delay(delay);
            
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    private void enterTag(String tag){
        try {
            Thread.sleep(delay);
            for(int i = 0; i < tag.length(); i++){
                char c = tag.charAt(i);
                int keyCode = KeyEvent.getExtendedKeyCodeForChar(c);
                if (keyCode == KeyEvent.VK_UNDEFINED) {
                    System.err.println("Key code not found for character: " + c);
                } else {
                    robot.keyPress(keyCode); robot.keyRelease(keyCode); Thread.sleep(15);
                }
            }
        } catch (Exception e) {
            System.err.println(e);
        }
    }

    private void updateTags(Zone zone){
        try {
            Thread.sleep(delay);
            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER); robot.delay(delay);
            enterTag(zone.getTag1());
            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER); robot.delay(delay);
            enterTag(zone.getTag2());
            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER); robot.delay(delay);
        } catch (Exception e) {
            // TODO: handle exception
        }
    }

    private void updateType(Zone zone){
        try {
            Thread.sleep(delay);
            switch(zone.getType()){
                case "Photo Detector":
                    robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A); robot.delay(delay);
                    break;
                case "Alarm Input":
                    robot.keyPress(KeyEvent.VK_M); robot.keyRelease(KeyEvent.VK_M); robot.delay(delay);
                    robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A); robot.delay(delay);
                    robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A); robot.delay(delay);
                    robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A); robot.delay(delay);
                    break;
                case "Non-latched Supervisory":
                    robot.keyPress(KeyEvent.VK_N); robot.keyRelease(KeyEvent.VK_N); robot.delay(delay);
                    break;
                case "Latched Supervisory":
                    robot.keyPress(KeyEvent.VK_L); robot.keyRelease(KeyEvent.VK_L); robot.delay(delay);
                    break;
                case "Heat Detector":
                    robot.keyPress(KeyEvent.VK_M); robot.keyRelease(KeyEvent.VK_M); robot.delay(delay);
                    robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A); robot.delay(delay);
                    robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A); robot.delay(delay);
                    robot.keyPress(KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_A); robot.delay(delay);
                    break;
                case "Blank Device":
                    robot.keyPress(KeyEvent.VK_N); robot.keyRelease(KeyEvent.VK_N); robot.delay(delay);
                    robot.keyPress(KeyEvent.VK_B); robot.keyRelease(KeyEvent.VK_B); robot.delay(delay);
                    robot.keyPress(KeyEvent.VK_B); robot.keyRelease(KeyEvent.VK_B); robot.delay(delay);
                    break;
                case "Relay":
                    robot.keyPress(KeyEvent.VK_R); robot.keyRelease(KeyEvent.VK_R); robot.delay(delay);
                    break;
            }
            robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER); robot.delay(delay);
        } catch (Exception e) {
            // TODO: handle exception
        }
    }

    private void updateRow(Zone zone){
        updateTags(zone);
        updateType(zone);
        
        if(zone.isNS()){
            robot.keyPress(KeyEvent.VK_N); robot.keyRelease(KeyEvent.VK_N); robot.delay(delay);
        }

        robot.keyPress(KeyEvent.VK_ENTER); robot.keyRelease(KeyEvent.VK_ENTER); robot.delay(delay);

        robot.keyPress(KeyEvent.VK_ESCAPE); robot.keyRelease(KeyEvent.VK_ESCAPE); robot.delay(delay);
        robot.keyPress(KeyEvent.VK_DOWN); robot.keyRelease(KeyEvent.VK_DOWN); robot.delay(delay);
    }


    private void updateZone(Zone zone){
        try {
            updateRow(zone);

            if (zone.getSubAddress() != null){
                updateRow(zone.getSubAddress());
            } else if(zone.isDualInput() || zone.getType().equals("Relay")) {
                // add empty
                updateRow(new subZone(zone.getAddress()+0.1, "    Spare", zone.getTag2()));
            }
        } catch (Exception e) {
            // TODO: handle exception
            System.out.println(e);
        }
        
    }

    public void enterZoneList(ZoneList zoneList){
        try {
            robot.keyPress(KeyEvent.VK_PAGE_UP); robot.keyRelease(KeyEvent.VK_PAGE_UP); robot.delay(delay);

            for(Zone zone : zoneList.zones){
                if(!zone.getType().equals("Blank Device")){
                    updateZone(zone);
                }
            }
            
        } catch (Exception e) {
            // TODO: handle exception
        }
    }

    //Give key and distance, press key distance times
    public void reachEntry(int key, int targetDistance)
    {
        for(int i = 0; i < targetDistance; i++){
        try {
            if(THREAD_DELAY){
                Thread.sleep(delay);
            }

            robot.keyPress(key); 
            robot.keyRelease(key);

            if(ROBOT_DELAY)
            {
                robot.delay(delay);
            }
            
            }catch (Exception e) {
            // TODO: handle exception
            }
        }
    }   
}
