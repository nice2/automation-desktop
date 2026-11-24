package checker;

import java.io.File;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

//Stand-alone window around CheckerPanel. checker.bat with no arguments opens this.
public class CheckerWindow {

    public static void main(String[] args) {
        open(args.length > 0 ? new File(args[0]) : null);
    }

    //Opens the window; with a file, its jobs are listed (and an exported job is checked) straight away
    public static void open(File file) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                //default look is fine
            }
            JFrame frame = new JFrame("Mircom Correlation Checker");
            CheckerPanel panel = new CheckerPanel();
            //Unsaved correlation changes are asked about before closing
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                public void windowClosing(java.awt.event.WindowEvent e) {
                    if (panel.confirmDropChanges()) System.exit(0);
                }
            });
            frame.setContentPane(panel);
            frame.setSize(1300, 820);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            if (file != null) panel.openFile(file);
        });
    }
}
