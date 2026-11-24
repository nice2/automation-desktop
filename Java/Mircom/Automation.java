import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

//Opens the bot window and listens for the global hotkeys:
//F8 starts, pauses or resumes the bot on the tab in front (configurator picked in the window), ` stops a run.
public class Automation implements NativeKeyListener {

    //Change these to move the hotkeys (NativeKeyEvent.VC_...)
    private static final int START_PAUSE_KEY = NativeKeyEvent.VC_F8;
    private static final int STOP_KEY = NativeKeyEvent.VC_BACKQUOTE;

    private static volatile BotWindow window;

    public static void main(String[] args) throws Exception {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            //default look is fine
        }

        SwingUtilities.invokeAndWait(() -> {
            window = new BotWindow();
            window.setVisible(true);
        });

        //Register key presses
        try {
            Logger.getLogger(GlobalScreen.class.getPackage().getName()).setLevel(Level.WARNING);
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(new Automation());
            System.out.println("Program ready. Use the window, or press " + NativeKeyEvent.getKeyText(START_PAUSE_KEY)
                + " to start/pause/resume the selected configurator. " + NativeKeyEvent.getKeyText(STOP_KEY) + " stops a run.");
        }
        catch (NativeHookException ex) {
            System.err.println("Hotkeys unavailable (" + ex.getMessage() + "). Use the window's buttons instead.");
        }
    }

    public void nativeKeyPressed(NativeKeyEvent e) {
        BotWindow w = window;
        if(w == null) {
            return;
        }

        int key = e.getKeyCode();
        if(key == STOP_KEY) {
            SwingUtilities.invokeLater(w::hotkeyStop);
        }
        else if(key == START_PAUSE_KEY) {
            SwingUtilities.invokeLater(w::hotkeyStartPause);
        }
    }
}
