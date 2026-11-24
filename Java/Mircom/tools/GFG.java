package tools;

// Java program using label (swing)
// to display the message “GFG WEB Site Click”
import java.io.*;
import javax.swing.*;

// Main class
public class GFG {

    public static File getExcelFile(){
        try {
            // Using this process to invoke the constructor,
            // JFileChooser points to user's default directory
            JFileChooser j = new JFileChooser("Quick access");

            // only allow excel files
            j.setFileFilter(new javax.swing.filechooser.FileFilter() {
                public boolean accept(File f) {
                    return f.getName().toLowerCase().endsWith(".xlsx") || f.isDirectory();
                }

                public String getDescription() {
                    return "Excel files (*.xlsx)";
                }
            });

            // Open the save dialog
            j.showSaveDialog(null);

            // Get the selected file
            File file = j.getSelectedFile();
            // Print the file path
            System.out.println("Selected file: " + file.getAbsolutePath());
            // Print the file name
            System.out.println("File name: " + file.getName());

            return  file;
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return null;
        }

    }
}