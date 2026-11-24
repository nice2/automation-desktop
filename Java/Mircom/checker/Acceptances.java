package checker;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import checker.Job.Circuit;
import checker.Job.Equation;
import checker.Job.Led;
import checker.Report.Finding;

//Findings accepted with a reason ("special conditions"). Kept per job in assets/checker/accepted/<job id>.tsv, outside
//the job itself, so they carry over to later versions and exports of the same job.
//
//An acceptance stores a snapshot of what it was about: the check, the problem, the address and what was there (device
//type, input type, both tag lines) and the devices involved. It only hides a finding while all of that still matches,
//so a smoke that became a heat, a device that moved, or a different problem shows up again. Acceptances that no
//longer match anything are reported (with what changed) so they can be reviewed or removed.
public final class Acceptances {

    private Acceptances() {}

    //Where acceptance files live; relative to the folder the checker runs from (Java/Mircom)
    public static File dir = new File("assets/checker/accepted");

    static final String[] COLUMNS = { "check", "address", "type", "detail", "tag", "location", "problem", "involved",
        "where", "job", "version", "version_name", "reason", "by", "date" };

    public static class Acceptance {
        final Map<String, String> v = new HashMap<>();

        public String get(String column) {
            return v.getOrDefault(column, "");
        }

        //Everything that has to match for the acceptance to apply
        String fingerprint() {
            return String.join("|", get("check"), get("address"), get("type"), get("detail"), get("tag"), get("location"), get("problem"), get("involved"));
        }

        public String reason() {
            return get("reason");
        }

        //e.g. "v73 (version 10) on 2026-11-17 by mnikulin"
        public String when() {
            String name = get("version_name").isBlank() ? "version " + get("version") : get("version_name") + " (version " + get("version") + ")";
            return name + " on " + get("date").replace('T', ' ') + (get("by").isEmpty() ? "" : " by " + get("by"));
        }
    }

    //-------------------------------------------------------------------------------------------------
    //Snapshot of a finding

    static Map<String, String> snapshot(Finding f) {
        Map<String, String> m = new HashMap<>();
        Place p = f.place;
        m.put("check", f.rule);
        m.put("address", address(p));
        m.put("type", p.type);
        m.put("detail", detail(f.subject));
        m.put("tag", p.tag);
        m.put("location", p.location);
        m.put("problem", f.summary);
        m.put("involved", involved(f));
        m.put("where", p.text());
        return m;
    }

    //Where it is, without names that can be edited (node/CPU names, tags)
    static String address(Place p) {
        if (p.node < 0 && p.address.isEmpty()) return "text|" + p.tag; //e.g. "Base Control", "Input zones"
        String node = p.node < 0 ? "" : "Node " + p.node;
        //Logic equations are renumbered as others are added, so they are known by the input zone they drive
        if (p.address.startsWith("Logic ")) return node + "|logic|" + p.type;
        //"32.1" (first input of a multi-input device) and "32" (single-input device) are the same address
        return node + "|" + p.cpu.replaceAll(".*\\(", "(") + "|" + p.loop + "|" + p.address.replaceAll("^(\\d+)\\.1$", "$1");
    }

    //What the thing is beyond its device type: input type for circuits, the equation text for logic
    static String detail(Object subject) {
        if (subject instanceof Circuit) return ((Circuit) subject).process_name;
        if (subject instanceof Led) return ((Led) subject).led_type_name;
        if (subject instanceof Equation) return ((Equation) subject).text.replaceAll("\\s+", " ").trim();
        return "";
    }

    //The devices/zones/LEDs involved, as a short hash (order does not matter)
    static String involved(Finding f) {
        if (f.items.isEmpty()) return "";
        TreeSet<String> set = new TreeSet<>();
        for (Place p : f.items) set.add(p.text());
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(String.join("\n", set).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder(f.items.size() + ":");
            for (int i = 0; i < 6; i++) s.append(String.format("%02x", h[i]));
            return s.toString();
        } catch (Exception e) {
            return f.items.size() + ":" + set.hashCode();
        }
    }

    static String fingerprint(Finding f) {
        Map<String, String> m = snapshot(f);
        return String.join("|", m.get("check"), m.get("address"), m.get("type"), m.get("detail"), m.get("tag"), m.get("location"), m.get("problem"), m.get("involved"));
    }

    //-------------------------------------------------------------------------------------------------
    //File

    static File file(Job job) {
        return new File(dir, job.identity() + ".tsv");
    }

    public static List<Acceptance> load(Job job) {
        List<Acceptance> list = new ArrayList<>();
        File f = file(job);
        if (!f.isFile()) return list;
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) return list;
            String[] head = lines.get(0).replace("﻿", "").split("\t", -1);
            for (String line : lines.subList(1, lines.size())) {
                if (line.isBlank()) continue;
                String[] cells = line.split("\t", -1);
                Acceptance a = new Acceptance();
                for (int i = 0; i < head.length && i < cells.length; i++) a.v.put(head[i], unescape(cells[i]));
                list.add(a);
            }
        } catch (IOException e) {
            System.err.println("Could not read " + f + ": " + e.getMessage());
        }
        return list;
    }

    static void save(Job job, List<Acceptance> list) throws IOException {
        File f = file(job);
        if (list.isEmpty()) {
            Files.deleteIfExists(f.toPath());
            return;
        }
        f.getParentFile().mkdirs();
        StringBuilder s = new StringBuilder();
        s.append(String.join("\t", COLUMNS)).append('\n'); //the job's name is in every row, so the file opens fine in Excel
        for (Acceptance a : list) {
            List<String> cells = new ArrayList<>();
            for (String c : COLUMNS) cells.add(escape(a.get(c)));
            s.append(String.join("\t", cells)).append('\n');
        }
        //Write to a temporary file first so a crash never leaves half a file
        File tmp = new File(f.getPath() + ".tmp");
        Files.writeString(tmp.toPath(), s.toString(), StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", " ").replace("\r", "").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n == 'n' ? '\n' : n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    //-------------------------------------------------------------------------------------------------
    //Changes

    public static void accept(Job job, Finding f, String reason) throws IOException {
        List<Acceptance> list = load(job);
        String print = fingerprint(f);
        list.removeIf(a -> a.fingerprint().equals(print));
        Acceptance a = new Acceptance();
        a.v.putAll(snapshot(f));
        a.v.put("job", job.job_id + " " + job.description.trim());
        a.v.put("version", String.valueOf(job.version));
        a.v.put("version_name", job.version_description.trim());
        a.v.put("reason", reason.trim());
        a.v.put("by", System.getProperty("user.name", ""));
        a.v.put("date", LocalDateTime.now().withSecond(0).withNano(0).toString());
        list.add(a);
        save(job, list);
    }

    public static void remove(Job job, Acceptance gone) throws IOException {
        List<Acceptance> list = load(job);
        list.removeIf(a -> a.fingerprint().equals(gone.fingerprint()));
        save(job, list);
    }

    //-------------------------------------------------------------------------------------------------
    //Applying to a report

    //The pseudo-check that lists acceptances which no longer match anything. Runs last, after every real check.
    public static class Review implements Rule {

        public String id() {
            return "ACCEPTED";
        }

        public String name() {
            return "Accepted exceptions";
        }

        public String title() {
            return "Findings accepted earlier with a reason; lists acceptances that no longer match the job";
        }

        public boolean supports(Job.Panel panel) {
            return panel.isReadable();
        }

        public void check(Job job, Report report) {
            apply(job, report);
        }
    }

    //Marks findings that match an acceptance, and reports acceptances that match nothing, saying what changed
    static void apply(Job job, Report report) {
        List<Acceptance> list = load(job);
        Map<String, Acceptance> by_print = new HashMap<>();
        for (Acceptance a : list) by_print.put(a.fingerprint(), a);

        List<Finding> current = new ArrayList<>(report.findings());
        java.util.Set<String> used = new java.util.HashSet<>();
        int accepted = 0;
        for (Finding f : current) {
            Acceptance a = by_print.get(fingerprint(f));
            if (a == null) continue;
            f.acceptance = a;
            used.add(a.fingerprint());
            accepted++;
        }

        int stale = 0;
        for (Acceptance a : list) {
            if (used.contains(a.fingerprint())) continue;
            stale++;
            String changes = whatChanged(job, a, current);
            Finding f = report.warning(Place.text(a.get("where")), "Accepted in " + shortVersion(a) + ", no longer matches");
            f.acceptance = a;
            f.stale = true;
            f.more("Was: " + a.get("problem") + "\nReason given: " + a.reason() + "\n" + changes);
        }
        report.note(accepted + " finding" + (accepted == 1 ? "" : "s") + " accepted"
            + (stale > 0 ? ", " + stale + " acceptance" + (stale == 1 ? "" : "s") + " to review" : ""));
        if (list.isEmpty()) report.note("None yet. Select a finding and press Accept to record a special condition.");
    }

    private static String shortVersion(Acceptance a) {
        return a.get("version_name").isBlank() ? "version " + a.get("version") : a.get("version_name");
    }

    //Explains why an acceptance no longer applies
    private static String whatChanged(Job job, Acceptance a, List<Finding> current) {
        //Same check at the same address, but something about it changed
        for (Finding f : current) {
            if (!f.rule.equals(a.get("check")) || !address(f.place).equals(a.get("address"))) continue;
            Map<String, String> now = snapshot(f);
            List<String> diffs = new ArrayList<>();
            diff(diffs, "Problem", a.get("problem"), now.get("problem"));
            diff(diffs, "Device type", a.get("type"), now.get("type"));
            diff(diffs, "Input type", a.get("detail"), now.get("detail"));
            diff(diffs, "Tag", a.get("tag"), now.get("tag"));
            diff(diffs, "Location", a.get("location"), now.get("location"));
            if (!a.get("involved").equals(now.get("involved"))) diffs.add("The devices involved changed (" + count(a.get("involved")) + " then, " + f.items.size() + " now)");
            if (!diffs.isEmpty()) return "What changed:\n" + String.join("\n", diffs);
        }
        //Nothing reported there any more: is the thing still at that address?
        Place now = placeAt(job, a.get("address"));
        if (now == null) return "Nothing is at this address any more (removed or moved).";
        List<String> diffs = new ArrayList<>();
        diff(diffs, "Device type", a.get("type"), now.type);
        diff(diffs, "Tag", a.get("tag"), now.tag);
        diff(diffs, "Location", a.get("location"), now.location);
        if (!diffs.isEmpty()) return "The problem is no longer reported, and the device changed:\n" + String.join("\n", diffs);
        return "The problem is no longer reported (fixed). This acceptance can be removed.";
    }

    private static String count(String involved) {
        int colon = involved.indexOf(':');
        return colon < 0 ? "0" : involved.substring(0, colon);
    }

    private static void diff(List<String> out, String what, String was, String now) {
        if (!was.equals(now == null ? "" : now)) out.add(what + ": was \"" + was + "\", now \"" + now + "\"");
    }

    private static Place placeAt(Job job, String address) {
        for (Circuit c : job.inputs.values()) {
            Place p = Place.of(job, c);
            if (address(p).equals(address)) return p;
        }
        for (Circuit c : job.outputs.values()) {
            Place p = Place.of(job, c);
            if (address(p).equals(address)) return p;
        }
        for (Led l : job.leds.values()) {
            for (int[] s : l.slots) {
                Place p = Place.of(job, l, s[0], s[1]);
                if (address(p).equals(address)) return p;
            }
        }
        for (Equation e : job.equations.values()) {
            Place p = Place.of(job, e);
            if (address(p).equals(address)) return p;
        }
        return null;
    }
}
