import java.util.Scanner;
import java.awt.RenderingHints.Key;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;

//Shared functions and variables that the FSAE related bots will use.

public abstract class FSAEBot extends Thread {

    protected int DELAY = 200; //Default 200. Delay time for everything. Multiply by delay strength to change length
    protected double DEVICE_UPDATE_DELAY_STRENGTH = 1.5; // Default 1. Multiplied to DELAY. The Delay time after updating a device (tag name, type, etc).
    protected String FILE_NAME = "fsae_zones.txt";

    protected int CURRENT_ZONE_ADDRESS = 0; //Intended to be the address of the first fsae input zone (Normal XX Floor)
    protected boolean is_running = false; 
    protected boolean is_paused = true;
    protected DataEntryBot bot;
    protected ArrayList<String> floors;

    public abstract void run();

    public abstract void updateZone(String name, int type_key);

    public void readFloors() {
        try {
            File fsaefile = new File(FILE_NAME);
            Scanner scanner = new Scanner(fsaefile);
            floors = new ArrayList<String>();

            String line;
            //Having a number in brackets will be used to set the first input zone address for updating logic.
            while(scanner.hasNextLine()) {
                line = scanner.nextLine();
                if(line.contains("[") && line.contains("]")) {
                    line = line.replace("[","");
                    line = line.replace("]","");
                    CURRENT_ZONE_ADDRESS = Integer.parseInt(line); 
                    System.out.println("First input zone index: " + CURRENT_ZONE_ADDRESS);
                } else {
                    floors.add(line);
                }
            }

            System.out.println("Final input zone address: " + ((4 * floors.size() - 1) + CURRENT_ZONE_ADDRESS));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setIsRunning(boolean status) {
        is_running = status;

        //Set bot to null to prevent further inputs
        if(!is_running) {
            bot = null;
        }
    }

    public void setIsPaused(boolean status) {
        is_paused = status;
    }

    public boolean getIsPaused() {
        return is_paused;
    }
}
