package checker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
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

import checker.Correlations.Edits;
import checker.Correlations.Link;
import checker.Correlations.Type;
import checker.Job.Circuit;
import checker.Job.Led;

//Saves correlation changes into a COPY of the job file. The original file and the configurators' own databases
//are never written: the copy is loaded into the configurator with Import Job, which gives it a new version.
//
//Only link rows change (IptZoneCorrs, IOCorrs, LedIpts), the same rows the configurators themselves write when a
//correlation is added or deleted (checked by diffing their databases before and after, 2026-11-16).
//The database's own keys refuse duplicates and links to circuits that do not exist.
public final class JobWriter {

    private JobWriter() {
    }

    //The configurators' live databases on this PC; never a target
    static final List<File> LIVE_DATABASES = List.of(CheckerPanel.FX4000_DB, CheckerPanel.FX6000_DB, CheckerPanel.FX400_DB, CheckerPanel.FX2000_DB);

    //What was written, after reading the copy back
    public static final class Result {
        public final File file;
        public final int added, removed;

        Result(File file, int added, int removed) {
            this.file = file;
            this.added = added;
            this.removed = removed;
        }
    }

    //Copies the job's file to target and makes the pending changes in the copy. Checks the copy afterwards.
    public static Result save(Edits edits, File target) throws IOException {
        Job job = edits.job;
        File source = job.file;
        if (edits.isEmpty()) throw new IOException("There are no changes to save.");
        checkTarget(source, target);
        if (source.lastModified() != job.file_stamp) {
            throw new IOException(source.getName() + " has changed since the job was read (saved in the configurator?). "
                + "Reload, then make the changes again, so they go into the current job.");
        }
        //Every change must still be allowed (the rules may have been relaxed by nothing but a bug; better to stop)
        Edits check = new Edits(job);
        for (Link l : edits.removed) check.remove(l);
        for (Link l : edits.added) check.add(l);
        for (Link l : new ArrayList<>(edits.added)) {
            check.undo(l); //checked as if it were the one being added now
            String why = Correlations.blocked(check, l);
            check.add(l);
            if (why != null) throw new IOException("Not allowed: " + l.text(job) + "\n" + why);
        }
        for (Link l : edits.removed) {
            String why = Correlations.cannotRemove(edits, l);
            if (why != null) throw new IOException("Cannot remove: " + l.text(job) + "\n" + why);
        }

        File temp = new File(target.getParentFile(), target.getName() + ".writing");
        Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            //The copy must hold the same job as the one the changes were made on
            Job copy = Job.load(temp, job.job_id, job.version);
            for (Link l : allLinks(edits)) sameEnds(job, copy, l);

            try (Database db = new DatabaseBuilder(temp).setReadOnly(false).open()) {
                for (Type type : Type.values()) write(db, job, type, edits);
            }

            //Read it back: every added link is there, every removed one is gone, nothing else changed
            Job after = Job.load(temp, job.job_id, job.version);
            Set<Link> want = Correlations.existing(job);
            want.removeAll(edits.removed);
            want.addAll(edits.added);
            Set<Link> got = Correlations.existing(after);
            if (!got.equals(want)) {
                List<String> diff = new ArrayList<>();
                for (Link l : want) if (!got.contains(l)) diff.add("missing: " + l.text(job));
                for (Link l : got) if (!want.contains(l)) diff.add("unexpected: " + l.text(job));
                throw new IOException("The saved copy does not match the changes:\n" + String.join("\n", diff.subList(0, Math.min(10, diff.size()))));
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp.toPath());
        }
        return new Result(target, edits.added.size(), edits.removed.size());
    }

    static void checkTarget(File source, File target) throws IOException {
        File t = target.getCanonicalFile();
        if (t.equals(source.getCanonicalFile())) throw new IOException("Save the changes to a new file; the open job file is never changed.");
        for (File live : LIVE_DATABASES) {
            if (live.exists() && t.equals(live.getCanonicalFile())) throw new IOException(target + " is a configurator's own database. Save to a new file and use Import Job.");
        }
        if (t.getName().equalsIgnoreCase("configurator.mdb") || t.getName().equalsIgnoreCase("config-ref.mdb")) {
            throw new IOException("Pick another name than " + t.getName() + ", so the copy is never mistaken for a configurator's own database.");
        }
        if (t.getParentFile() == null || !t.getParentFile().isDirectory()) throw new IOException("Folder not found: " + t.getParent());
    }

    private static List<Link> allLinks(Edits edits) {
        List<Link> list = new ArrayList<>(edits.added);
        list.addAll(edits.removed);
        return list;
    }

    //Both ends of the link are the same circuits / LEDs in the copy as in the job the changes were made on
    private static void sameEnds(Job job, Job copy, Link l) throws IOException {
        for (boolean side : new boolean[] { true, false }) {
            Object a = l.end(job, side), b = l.end(copy, side);
            if (a == null || b == null || !fingerprint(a).equals(fingerprint(b))) {
                throw new IOException("The job in " + job.file.getName() + " no longer matches what was loaded (" + l.text(job) + "). Reload and make the changes again.");
            }
        }
    }

    private static String fingerprint(Object o) {
        if (o instanceof Circuit) {
            Circuit c = (Circuit) o;
            return c.kind + "|" + c.output + "|" + c.node + "|" + c.cpu + "|" + c.loop + "|" + c.addr + "|" + c.sub + "|" + c.process_type + "|" + c.tag;
        }
        Led l = (Led) o;
        StringBuilder s = new StringBuilder(l.led_type + "|" + l.tag);
        for (int[] p : l.slots) s.append('|').append(p[0]).append('/').append(p[1]).append('/').append(p[2]).append('/').append(p[3]);
        return s.toString();
    }

    private static void write(Database db, Job job, Type type, Edits edits) throws IOException {
        List<Link> remove = new ArrayList<>(), add = new ArrayList<>();
        for (Link l : edits.removed) if (l.type == type) remove.add(l);
        for (Link l : edits.added) if (l.type == type) add.add(l);
        if (remove.isEmpty() && add.isEmpty()) return;
        Table t = db.getTable(type.table);
        if (t == null) throw new IOException("This job file has no " + type.table + " table.");

        if (!remove.isEmpty()) {
            Map<String, Link> wanted = new HashMap<>();
            for (Link l : remove) wanted.put(l.a + "/" + l.b, l);
            int deleted = 0;
            Cursor c = CursorBuilder.createCursor(t);
            while (c.moveToNextRow()) {
                Row r = c.getCurrentRow();
                if (Job.num(r, "JobID") != job.job_id || Job.num(r, "VersionNo") != job.version) continue;
                if (wanted.containsKey(Job.num(r, type.column_a) + "/" + Job.num(r, type.column_b))) {
                    c.deleteCurrentRow();
                    deleted++;
                }
            }
            if (deleted < remove.size()) throw new IOException("Could not find every " + type.table + " row to delete (" + deleted + " of " + remove.size() + ").");
        }
        for (Link l : add) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("JobID", job.job_id);
            row.put("VersionNo", job.version);
            row.put(type.column_a, l.a);
            row.put(type.column_b, l.b);
            if (type == Type.LED_INPUT) row.put("ChkActive", Boolean.TRUE); //what the configurators write for an input zone LED
            t.addRowFromMap(row);
        }
    }

    //Suggested name for the copy, e.g. "test 355 Exterior St_12-10 (correlations edited).mdb"
    public static String suggestedName(Job job) {
        String base = job.file.getName().replaceFirst("(?i)\\.mdb$", "");
        if (base.equalsIgnoreCase("configurator")) base = (job.description.isBlank() ? "job" : job.description.trim()) + "_" + job.job_id + "-" + String.format("%02d", job.version);
        base = base.replaceFirst("\\s*\\(correlations edited( \\d+)?\\)$", "");
        return (base + " (correlations edited).mdb").replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
