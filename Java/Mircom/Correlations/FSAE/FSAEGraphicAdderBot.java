import java.awt.RenderingHints.Key;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;

//Select the first row of the graphic adder, then press F4. All zones must be UNASSIGNED. The correct type will be entered.

public class FSAEGraphicAdderBot extends FSAEBot{

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //Settings

    //The first empty index to start from - meant for when there are already used zones prior to bot entry
    private int GRAPHIC_INDEX = 0; //Default 0 - this is the first index in the configurator

    //Tag names for each zone. Change these if they need to be shorter
    private String NORMAL_STRING = "Normal "; //Default "Normal "
    private String LOW_STRING = "Low Heat "; //Default "Low Heat "
    private String HIGH_STRING = "High Heat "; //Default "High Heat "
    private String SMOKE_STRING = "Smoke Det "; //Default "Smoke Det "

    //Override the default settings since it is not as heavy of a task
    private int DELAY = 200;
    private double SKIP_DELAY_STRENGTH = .4; // Default .4. For Full mode. The delay time for scrolling through zones.

    private boolean IS_FX4000 = false; //Press Enter once more since there is an additional tag

    /////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private int overall_index;

    //UNUSED - update Tags only. Unfortunately changing the Assignment will reset the Tag, so this wouldn't be very useful.
    private boolean TAG_ONLY_MODE = false; //Default False. 
    private int TAG_COLUMN_POSITION = 3;  //Default 3. This is the position of the Tag column in the configurator. If you want to speed things up, move Tag to the first column and change this to 1. 

    public void run() {
        try {
            if (floors == null) {
                readFloors();
                bot = new DataEntryBot(DELAY);  
                System.out.println("Reading floors: " + floors);
            }

            System.out.println("Starting Graphic Adder Entry");

            if(IS_FX4000) 
            {
                System.out.println("Entering data for FX4000");  
            } else {
                System.out.println("Entering data for Flexnet/FX6000");
            }

            setIsRunning(true);
            setIsPaused(false);

            overall_index = 0;

            for (String floor : floors) {
                System.out.println("Updating: " + floor + " Normal");
                updateZone(NORMAL_STRING + floor , KeyEvent.VK_M);
            }

            for (String floor : floors) {
                System.out.println("Updating: " + floor + " Low Heat");
                updateZone(LOW_STRING + floor , KeyEvent.VK_S);
            }

            for (String floor : floors) {
                System.out.println("Updating: " + floor + " High Heat");
                updateZone(HIGH_STRING + floor , KeyEvent.VK_A);
            }

            for (String floor : floors) {
                System.out.println("Updating: " + floor + " Smoke Det");
                updateZone(SMOKE_STRING + floor , KeyEvent.VK_A);
            }

            setIsRunning(false);
            System.out.println("Graphic Adder Entry Complete - Add the other zones afterwards");
            System.exit(MAX_PRIORITY);
        }
        catch(Exception e) {
            e.printStackTrace();
        }
    }

    public void updateZone(String name, int type_key) {
        try {

            Thread.sleep(DELAY);
            
            while(is_paused) {
                Thread.sleep(100);
            }

            //Type Ipt Zone
            bot.pressKey(KeyEvent.VK_ENTER, 1);
            bot.pressKey(KeyEvent.VK_I, 1);
            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);

            //Assignment
            bot.pressKey(type_key, 1);
            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);

            //Tag
            bot.pasteText(name);

            //Selection restarts from the top if a Type or Assignment is changed, need to get back to next available zone
            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);

            if(IS_FX4000) {
                bot.pressKey(KeyEvent.VK_ENTER);
            }

            if(GRAPHIC_INDEX != 0 && GRAPHIC_INDEX % 47 == 0) {
                GRAPHIC_INDEX = 0;
                setIsPaused(true);
                System.out.println("Entry paused. Move to next graphic adder, then press F4 to continue.");
            }
            else {
                //Go down only if there is something to enter
                if(overall_index < (floors.size() * 4) - 1) {
                    bot.pressKey(KeyEvent.VK_DOWN, 2 + GRAPHIC_INDEX, SKIP_DELAY_STRENGTH);
                }
                GRAPHIC_INDEX++;
            }

            overall_index++;

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //Unused
    public void updateZoneTag(String name) {

        try {
            Thread.sleep(DELAY);
            
            while(is_paused) {
                Thread.sleep(100);
            }

            bot.pressKey(KeyEvent.VK_ENTER, TAG_COLUMN_POSITION);
            bot.pasteText(name);

            //Selection restarts from the top if a Type or Assignment is changed, need to get back to next available zone
            bot.pressKey(KeyEvent.VK_ENTER, 1, DEVICE_UPDATE_DELAY_STRENGTH);
            bot.pressKey(KeyEvent.VK_DOWN);

            if(GRAPHIC_INDEX != 0 && GRAPHIC_INDEX % 47 == 0) {
                GRAPHIC_INDEX = 0;
                setIsPaused(true);
                System.out.println("Entry paused. Move to next graphic adder, then press F4 to continue.");
            } 
            else {
                GRAPHIC_INDEX++;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
