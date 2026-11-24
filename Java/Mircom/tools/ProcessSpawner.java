package tools;
import java.io.IOException;

public class ProcessSpawner {
    public static void main(String[] args) {
        try {
            String command = "python /path/to/your/script.py";
            Process process = Runtime.getRuntime().exec(command);
            int exitCode = process.waitFor();
            System.out.println("Python script executed with exit code: " + exitCode);
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}