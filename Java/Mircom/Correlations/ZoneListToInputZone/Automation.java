import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

public class Automation implements NativeKeyListener{

    private int START_DELAY = 0;

    private ZoneListToInputZone bot;

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

        System.out.println("Program ready. Press F2 to update Input Zones.");
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

        if (e.getKeyCode() == NativeKeyEvent.VC_F2) {
            try {
                //Start a new thread only if it doesn't exist or is no longer alive
                if (bot == null || (bot != null && !bot.isAlive())) {
                    bot = new ZoneListToInputZone();
                }
                
                if (!bot.isAlive()) {
                    Thread.sleep(START_DELAY);  
                    bot.start();
                }

            } catch (Exception except) {
                except.printStackTrace();
            }
        }        
	}
}
