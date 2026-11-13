import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

//Opens the bot window and keeps the old global hotkeys working:
//F2 FX400, F3 FX2000, F4 Flexnet, F5 FX4000 start (or resume) with the configurator in front, ` stops the run.
public class Automation implements NativeKeyListener {

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
            System.out.println("Program ready. Use the window, or with the configurator in front press F2 FX400, F3 FX2000, F4 Flexnet, F5 FX4000. ` stops a run.");
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

        switch (e.getKeyCode()) {
            case NativeKeyEvent.VC_BACKQUOTE:
                SwingUtilities.invokeLater(w::hotkeyStop);
                break;
            case NativeKeyEvent.VC_F2:
                SwingUtilities.invokeLater(() -> w.hotkeyStart(BotWindow.Panel.FX_400));
                break;
            case NativeKeyEvent.VC_F3:
                SwingUtilities.invokeLater(() -> w.hotkeyStart(BotWindow.Panel.FX_2000));
                break;
            case NativeKeyEvent.VC_F4:
                SwingUtilities.invokeLater(() -> w.hotkeyStart(BotWindow.Panel.FLEXNET));
                break;
            case NativeKeyEvent.VC_F5:
                SwingUtilities.invokeLater(() -> w.hotkeyStart(BotWindow.Panel.FX_4000));
                break;
        }
    }
}
