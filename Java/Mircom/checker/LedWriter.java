package checker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.healthmarketscience.jackcess.Cursor;
import com.healthmarketscience.jackcess.CursorBuilder;
import com.healthmarketscience.jackcess.Database;
import com.healthmarketscience.jackcess.DatabaseBuilder;
import com.healthmarketscience.jackcess.Row;
import com.healthmarketscience.jackcess.Table;

import checker.Job.Circuit;
import checker.Job.Kind;
import checker.Job.Led;

//Saves LED corrections (Leds.Edits) into a COPY of the job file, like JobWriter does for correlations: the open file and
//the configurators' own databases are never written; the copy is loaded with Import Job. The rows changed are the ones
//the manual fixes in the guide change (seen by diffing 355 Exterior V20/V21, V58/V59, FX4000 v65/v65.1):
//  LedDefinitions.SomeActive   FSAE colors, HOA red/green continuous
//  LedOpts                     rows deleted (page select red/green LED -> amplifier)
//  LedIpts, LedProvingStat     rows added (HOA switch LEDs)
public final class LedWriter {

    private LedWriter() {
    }

    public static File save(Leds.Edits edits, File target) throws IOException {
        Job job = edits.job;
        File source = job.file;
        if (edits.isEmpty()) throw new IOException("There are no LED changes to save.");
        JobWriter.checkTarget(source, target);
        if (source.lastModified() != job.file_stamp) {
            throw new IOException(source.getName() + " has changed since the job was read (saved in the configurator?). Reload, then make the changes again.");
        }
        validate(job, edits);

        File temp = new File(target.getParentFile(), target.getName() + ".writing");
        Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            Job copy = Job.load(temp, job.job_id, job.version);
            for (Led l : touched(job, edits)) {
                Led c = copy.leds.get(l.group);
                if (c == null || !fingerprint(l).equals(fingerprint(c))) {
                    throw new IOException("The job in " + source.getName() + " no longer matches what was loaded (LED group " + l.group + "). Reload and make the changes again.");
                }
            }
            try (Database db = new DatabaseBuilder(temp).setReadOnly(false).open()) {
                writeStates(db, job, edits);
                deleteOpts(db, job, edits);
                addRows(db, job, "LedIpts", "IptCkt", edits.add_ipts, true);
                addRows(db, job, "LedProvingStat", "ProvingGrp", edits.add_provings, false);
            }
            verify(job, Job.load(temp, job.job_id, job.version), edits);
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
        return target;
    }

    //Only the kinds of change the guide describes, on the LEDs it describes
    static void validate(Job job, Leds.Edits edits) throws IOException {
        for (Map.Entry<Integer, Integer> e : edits.some_active.entrySet()) {
            if (job.leds.get(e.getKey()) == null) throw new IOException("LED group " + e.getKey() + " not found.");
            if (!Leds.continuous(e.getValue())) throw new IOException("Only Red/Green/Yellow Continuous are set here (LED group " + e.getKey() + ").");
        }
        for (String k : edits.delete_opts) {
            int[] p = pair(k);
            Led l = job.leds.get(p[0]);
            Circuit c = job.outputs.get(p[1]);
            if (l == null || c == null || !l.outputs.contains(c)) throw new IOException("LED group " + p[0] + " is not linked to output " + p[1] + ".");
            if (!Leds.amplifier(c) || !l.display_type.equalsIgnoreCase("Page Select")) throw new IOException("Only amplifier links of page select LEDs are removed here (LED group " + p[0] + ").");
        }
        for (String k : edits.add_ipts) {
            int[] p = pair(k);
            Led l = job.leds.get(p[0]);
            Circuit c = job.inputs.get(p[1]);
            if (l == null || !Leds.hoa(l)) throw new IOException("LED group " + p[0] + " is not an HOA switch LED.");
            if (c == null || c.kind != Kind.INPUT_ZONE) throw new IOException("Input " + p[1] + " is not an input zone.");
            if (l.inputs.contains(c)) throw new IOException("LED group " + p[0] + " already shows " + c.cleanTag() + ".");
        }
        for (String k : edits.add_provings) {
            int[] p = pair(k);
            Led l = job.leds.get(p[0]);
            if (l == null || !Leds.hoa(l)) throw new IOException("LED group " + p[0] + " is not an HOA switch LED.");
            if (!job.provings.containsKey(p[1])) throw new IOException("Proving group " + p[1] + " not found.");
            if (l.provings.contains(p[1])) throw new IOException("LED group " + p[0] + " already shows proving group " + p[1] + ".");
        }
    }

    private static int[] pair(String k) {
        String[] s = k.split("/");
        return new int[] { Integer.parseInt(s[0]), Integer.parseInt(s[1]) };
    }

    private static List<Led> touched(Job job, Leds.Edits edits) {
        Set<Integer> groups = new HashSet<>(edits.some_active.keySet());
        for (Set<String> set : List.of(edits.delete_opts, edits.add_ipts, edits.add_provings)) for (String k : set) groups.add(pair(k)[0]);
        List<Led> list = new ArrayList<>();
        for (int g : groups) list.add(job.leds.get(g));
        return list;
    }

    private static String fingerprint(Led l) {
        StringBuilder s = new StringBuilder(l.led_type + "|" + l.tag + "|" + l.states);
        for (int[] p : l.slots) s.append('|').append(p[0]).append('/').append(p[1]).append('/').append(p[2]).append('/').append(p[3]);
        for (Circuit c : l.inputs) s.append("|i").append(c.ckt);
        for (Circuit c : l.outputs) s.append("|o").append(c.ckt);
        for (int p : l.provings) s.append("|p").append(p);
        return s.toString();
    }

    private static boolean ours(Job job, Row r) {
        return Job.num(r, "JobID") == job.job_id && Job.num(r, "VersionNo") == job.version;
    }

    private static Table table(Database db, String name) throws IOException {
        Table t = db.getTable(name);
        if (t == null) throw new IOException("This job file has no " + name + " table.");
        return t;
    }

    private static void writeStates(Database db, Job job, Leds.Edits edits) throws IOException {
        if (edits.some_active.isEmpty()) return;
        Cursor c = CursorBuilder.createCursor(table(db, "LedDefinitions"));
        int done = 0;
        while (c.moveToNextRow()) {
            Row r = c.getCurrentRow();
            if (!ours(job, r)) continue;
            Integer want = edits.some_active.get(Job.num(r, "LedGrp"));
            if (want == null) continue;
            r.put("SomeActive", want);
            c.updateCurrentRowFromMap(r);
            done++;
        }
        if (done != edits.some_active.size()) throw new IOException("Found " + done + " of " + edits.some_active.size() + " LED definitions to change.");
    }

    private static void deleteOpts(Database db, Job job, Leds.Edits edits) throws IOException {
        if (edits.delete_opts.isEmpty()) return;
        Cursor c = CursorBuilder.createCursor(table(db, "LedOpts"));
        int done = 0;
        while (c.moveToNextRow()) {
            Row r = c.getCurrentRow();
            if (ours(job, r) && edits.delete_opts.contains(Job.num(r, "LedGrp") + "/" + Job.num(r, "OptCkt"))) {
                c.deleteCurrentRow();
                done++;
            }
        }
        if (done != edits.delete_opts.size()) throw new IOException("Found " + done + " of " + edits.delete_opts.size() + " LED output rows to delete.");
    }

    private static void addRows(Database db, Job job, String name, String column, Set<String> keys, boolean chk_active) throws IOException {
        if (keys.isEmpty()) return;
        Table t = table(db, name);
        for (String k : keys) {
            int[] p = pair(k);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("JobID", job.job_id);
            row.put("VersionNo", job.version);
            row.put("LedGrp", p[0]);
            row.put(column, p[1]);
            if (chk_active) row.put("ChkActive", Boolean.TRUE); //as the configurator writes it
            t.addRowFromMap(row);
        }
    }

    //The copy has exactly the changes: every other LED and link as before
    private static void verify(Job job, Job after, Leds.Edits edits) throws IOException {
        List<String> diff = new ArrayList<>();
        for (Led l : job.leds.values()) {
            Led a = after.leds.get(l.group);
            if (a == null) {
                diff.add("LED group " + l.group + " missing");
                continue;
            }
            Map<String, Integer> want = new LinkedHashMap<>(l.states);
            if (edits.some_active.containsKey(l.group)) want.put("SomeActive", edits.some_active.get(l.group));
            if (!want.equals(a.states)) diff.add("LED group " + l.group + " settings " + a.states + ", expected " + want);

            Set<Integer> outs = ckts(l.outputs), ins = ckts(l.inputs);
            Set<Integer> provs = new HashSet<>(l.provings);
            for (String k : edits.delete_opts) if (pair(k)[0] == l.group) outs.remove(pair(k)[1]);
            for (String k : edits.add_ipts) if (pair(k)[0] == l.group) ins.add(pair(k)[1]);
            for (String k : edits.add_provings) if (pair(k)[0] == l.group) provs.add(pair(k)[1]);
            if (!outs.equals(ckts(a.outputs))) diff.add("LED group " + l.group + " outputs " + ckts(a.outputs) + ", expected " + outs);
            if (!ins.equals(ckts(a.inputs))) diff.add("LED group " + l.group + " inputs " + ckts(a.inputs) + ", expected " + ins);
            if (!provs.equals(new HashSet<>(a.provings))) diff.add("LED group " + l.group + " proving " + a.provings + ", expected " + provs);
        }
        if (after.leds.size() != job.leds.size()) diff.add("LED count " + after.leds.size() + ", expected " + job.leds.size());
        if (!Correlations.existing(after).equals(withoutLedInputs(job, edits))) {
            //zone and output correlations must be untouched; LED inputs are compared above
            diff.add("other correlations changed");
        }
        if (!diff.isEmpty()) throw new IOException("The saved copy does not match the changes:\n" + String.join("\n", diff.subList(0, Math.min(10, diff.size()))));
    }

    private static Set<Correlations.Link> withoutLedInputs(Job job, Leds.Edits edits) {
        Set<Correlations.Link> set = Correlations.existing(job);
        for (String k : edits.add_ipts) set.add(new Correlations.Link(Correlations.Type.LED_INPUT, pair(k)[0], pair(k)[1]));
        return set;
    }

    private static Set<Integer> ckts(List<Circuit> list) {
        Set<Integer> s = new HashSet<>();
        for (Circuit c : list) s.add(c.ckt);
        return s;
    }
}
