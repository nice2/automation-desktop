import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

public class Automation implements NativeKeyListener{

    private int START_DELAY = 0;

    private FX400 bot;

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

        System.out.println("Program ready. Press F2 to start FX400, F3 to start FX2000, F4 to start Flexnet, ` to Exit anytime.");
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

            /*
            //Stops the data entry bot WITHOUT requiring a restart  - this will throw errors, may want to catch them properly
            try {
                System.out.println("Exiting Program");
                fx400.interrupt();
                fx400.setIsRunning(false);
            } catch (Exception nativeHookException) {
                nativeHookException.printStackTrace();
            }
            */
        }

        if (e.getKeyCode() == NativeKeyEvent.VC_F2 || e.getKeyCode() == NativeKeyEvent.VC_F3 || e.getKeyCode() == NativeKeyEvent.VC_F4) {
            try {
                //Start a new thread only if it doesn't exist or is no longer alive
                if(bot == null || (bot != null && !bot.isAlive())) {
                    switch (e.getKeyCode()) {
                        case NativeKeyEvent.VC_F2:
                            bot = new FX400();
                            break;
                        case NativeKeyEvent.VC_F3:
                            bot = new FX2000();
                            break;
                        case NativeKeyEvent.VC_F4: //Flexnet will want higher (2+ enter delay strength)
                            bot = new Flexnet();
                            break;
                    }
                }
                
                if(!bot.isAlive()) {
                    Thread.sleep(START_DELAY);  
                    bot.start();
                }else if(bot.isAlive() && bot.getIsPaused()) {
                    bot.setIsPaused(false);
                }
            } catch (Exception except) {
                except.printStackTrace();
            }
        }        
	}
}
