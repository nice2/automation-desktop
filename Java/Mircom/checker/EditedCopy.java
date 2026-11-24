package checker;

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

//Saves everything pending on one job version - correlation changes (Correlate tab) and ticked LED fixes (LEDs tab) -
//into ONE new copy of the job file, loaded in the configurator with Import Job. The open file and the configurators'
//own databases are never written.
//
//Correlations go first (JobWriter, which checks and reads back its rows), into a temporary copy; the LED fixes then go
//on top of that (LedWriter, same checks) into the target. Neither step renumbers anything, so the LED changes made on
//the original apply to the copy unchanged.
public final class EditedCopy {

    private EditedCopy() {
    }

    public static void save(Job job, Correlations.Edits correlations, Leds.Edits leds, File target) throws IOException {
        boolean corr = correlations != null && !correlations.isEmpty(), led = leds != null && !leds.isEmpty();
        if (!corr && !led) throw new IOException("There are no changes to save.");
        JobWriter.checkTarget(job.file, target);
        if (!led) {
            JobWriter.save(correlations, target);
            return;
        }
        if (!corr) {
            LedWriter.save(leds, target);
            return;
        }
        File middle = new File(target.getParentFile(), target.getName() + ".correlations");
        try {
            JobWriter.save(correlations, middle);
            Job copy = Job.load(middle, job.job_id, job.version);
            LedWriter.save(leds.on(copy), target);
        } finally {
            Files.deleteIfExists(middle.toPath());
        }
    }

    //Asks where to save; null = cancelled. Starts next to the job file (Downloads for a configurator's own database).
    static File pickTarget(Component parent, Job job, String last_dir) {
        boolean live = JobWriter.LIVE_DATABASES.contains(job.file);
        File dir = live ? new File(System.getProperty("user.home"), "Downloads") : job.file.getParentFile();
        if ((dir == null || !dir.isDirectory()) && last_dir != null) dir = new File(last_dir);
        File picked = WindowsFileDialog.save(parent, "Save the edited job as a new file", dir, suggestedName(job), "Configurator jobs", "mdb");
        if (picked == null) return null;
        return picked.getName().toLowerCase(Locale.ROOT).endsWith(".mdb") ? picked : new File(picked.getParentFile(), picked.getName() + ".mdb");
    }

    //e.g. "355 Exterior St_25-07 (edited).mdb"; an earlier "(edited)" / "(correlations edited)" / "(LEDs fixed)" is replaced
    public static String suggestedName(Job job) {
        String base = JobWriter.suggestedName(job).replaceFirst("(?i)\\.mdb$", "");
        for (String before = ""; !before.equals(base);) {
            before = base;
            base = base.replaceFirst("\\s*\\((correlations edited|LEDs fixed|edited)( \\d+)?\\)$", "");
        }
        return (base + " (edited).mdb").replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
