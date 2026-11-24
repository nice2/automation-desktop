import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

public class Automation implements NativeKeyListener{

    private int START_DELAY = 0;

    private FSAEBot bot;

    public static void main(String[] args){
        //Register key presses
        try {
			GlobalScreen.registerNativeHook();
		}
		catch (NativeHookException ex) {
			System.err.println("There was a problem registering the native hook.");
			System.err.println(ex.getMessage());

			System.exit(1);
		}

        GlobalScreen.addNativeKeyListener(new Automation());

        System.out.println("Program ready. Press F2 to update Input Zones, F3 to begin logic entry, F4 to update graphic adder.");
        System.out.println("-------------------------------------------------------------------------");
        System.out.println("Before Running: Set zones in fsae_zones.txt. Also set the first intended Input Zone address (Normal #FL) inside [brackets].");
        System.out.println("Input Zones: Create the Input Zones first, then select the first Input Zone to be used for FSAE.");
        System.out.println("Logic Entry: Open Advanced Logic window for first Input Zone, select the Equation text box, then press F3 to enter logic. Repeat for each zone.");
        System.out.println("Graphic Adder: Go to graphic adder then press F4. If all zones are used up," +
        "it will pause so that you can move to the next adder and continue by pressing F4. Enters Dual Heat devices only, the other zones will have to be added afterwards.");
    }

    public void nativeKeyPressed(NativeKeyEvent e) {
        
		if (e.getKeyCode() == NativeKeyEvent.VC_BACKQUOTE) {

            //Closes the program completely - will have to restart it
            try {
                System.out.println("Exiting Program");
                GlobalScreen.unregisterNativeHook();
                System.exit(0);
            } catch (NativeHookException nativeHookException) {
                nativeHookException.printStackTrace();
            }


        }

        if (e.getKeyCode() == NativeKeyEvent.VC_F2 || e.getKeyCode() == NativeKeyEvent.VC_F3 || e.getKeyCode() == NativeKeyEvent.VC_F4) {
            try {
                //Start a new thread only if it doesn't exist or is no longer alive
                if (bot == null || (bot != null && !bot.isAlive())) {
                    boolean status = false;
                    switch (e.getKeyCode()) {
                        case NativeKeyEvent.VC_F2:
                            status = false;
                            break;
                        case NativeKeyEvent.VC_F3:
                            status = true;
                            break;
                    }
                    if (bot == null) {
                        if(e.getKeyCode() == NativeKeyEvent.VC_F2 || e.getKeyCode() == NativeKeyEvent.VC_F3) {
                            bot = new FSAEInputZoneBot(status);
                        }
                        else if(e.getKeyCode() == NativeKeyEvent.VC_F4) {
                            bot = new FSAEGraphicAdderBot();
                        }
                        
                    }
                }
                
                if (!bot.isAlive()) {
                    Thread.sleep(START_DELAY);  
                    bot.start();
                } else if(bot.isAlive() && bot.getIsPaused()) {
                    bot.setIsPaused(false);
                }

            } catch (Exception except) {
                except.printStackTrace();
            }
        }        
	}
}
