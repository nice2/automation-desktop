package checker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import checker.Job.Circuit;
import checker.Job.Equation;
import checker.Job.Kind;
import checker.Job.Led;

//Differences between two job versions (or two jobs), in the terms a technician uses: devices, outputs, input zones,
//correlations, LEDs, logic and bypass. Things are matched by where they are (node/CPU/loop/address, IZ number, LED
//position), not by the configurator's internal circuit numbers, which shift every time a device is inserted.
public class Compare {

    public enum Change { ADDED, REMOVED, CHANGED }

    public static final String DEVICES = "Devices";
    public static final String OUTPUTS = "Outputs";
    public static final String ZONES = "Input zones";
    public static final String ZONE_LINKS = "Device > zone correlations";
    public static final String OUTPUT_LINKS = "Output correlations";
    public static final String LEDS = "LEDs";
    public static final String LED_LINKS = "LED correlations";
    public static final String LOGIC = "Logic";
    public static final String BYPASS = "Zone bypass";
    public static final String[] CATEGORIES = { DEVICES, OUTPUTS, ZONES, ZONE_LINKS, OUTPUT_LINKS, LEDS, LED_LINKS, LOGIC, BYPASS };

    public static class Diff {
        public final Change change;
        public final String category;
        public final Place place;
        public final String what; //e.g. "Tag", "Correlated to IZ 1 \"General Alarm\""
        public final String before;
        public final String after;

        Diff(Change change, String category, Place place, String what, String before, String after) {
            this.change = change;
            this.category = category;
            this.place = place;
            this.what = what;
            this.before = before == null ? "" : before;
            this.after = after == null ? "" : after;
        }
    }

    public final Job before, after;
    public final List<Diff> diffs = new ArrayList<>();

    public Compare(Job before, Job after) {
        this.before = before;
        this.after = after;
        circuits(true);
        circuits(false);
        zoneLinks();
        outputLinks();
        leds();
        ledLinks();
        logic();
        bypass();
    }

    public int count(String category) {
        int n = 0;
        for (Diff d : diffs) if (d.category.equals(category)) n++;
        return n;
    }

    //-------------------------------------------------------------------------------------------------
    //Keys

    static String key(Circuit c) {
        switch (c.kind) {
            case INPUT_ZONE: return "IZ/" + c.node + "/" + c.cpu + "/" + c.addr;
            case OUTPUT_ZONE: return "OZ/" + c.node + "/" + c.addr;
            default: return (c.output ? "O/" : "I/") + c.node + "/" + c.cpu + "/" + c.loop + "/" + (c.primary_addr >= 0 ? c.primary_addr : c.addr) + "/" + c.sub;
        }
    }

    static Map<String, Circuit> byKey(Iterable<Circuit> circuits) {
        Map<String, Circuit> map = new LinkedHashMap<>();
        for (Circuit c : circuits) map.putIfAbsent(key(c), c);
        return map;
    }

    static String slotKey(int[] p) {
        return p[0] + "/" + p[1] + "/" + p[2] + "/" + p[3];
    }

    //-------------------------------------------------------------------------------------------------
    //Devices, outputs and input zones

    private void circuits(boolean inputs) {
        Map<String, Circuit> a = byKey((inputs ? before.inputs : before.outputs).values());
        Map<String, Circuit> b = byKey((inputs ? after.inputs : after.outputs).values());
        for (Map.Entry<String, Circuit> e : b.entrySet()) {
            Circuit nb = e.getValue(), na = a.get(e.getKey());
            String cat = category(nb);
            if (na == null) {
                diffs.add(new Diff(Change.ADDED, cat, Place.of(after, nb), describe(nb), "", nb.cleanTag()));
                continue;
            }
            field(cat, nb, "Device type", na.device_type, nb.device_type);
            field(cat, nb, nb.kind == Kind.INPUT_ZONE ? "Zone type" : "Input type", na.process_name, nb.process_name);
            field(cat, nb, "Tag", na.cleanTag(), nb.cleanTag());
            field(cat, nb, "NS (non-silenceable)", yes(!na.silenceable), yes(!nb.silenceable));
            if (inputs) field(cat, nb, "CO flag", na.co_flag == null ? "" : yes(na.co_flag), nb.co_flag == null ? "" : yes(nb.co_flag));
            else field(cat, nb, "AR (aux reset)", yes(na.aux_reset), yes(nb.aux_reset));
        }
        for (Map.Entry<String, Circuit> e : a.entrySet()) {
            if (b.containsKey(e.getKey())) continue;
            Circuit c = e.getValue();
            diffs.add(new Diff(Change.REMOVED, category(c), Place.of(before, c), describe(c), c.cleanTag(), ""));
        }
    }

    private static String category(Circuit c) {
        if (c.kind == Kind.INPUT_ZONE) return ZONES;
        return c.output ? OUTPUTS : DEVICES;
    }

    private static String describe(Circuit c) {
        return c.kind == Kind.INPUT_ZONE ? "Input zone (" + c.process_name + ")" : c.device_type + " (" + c.process_name + ")";
    }

    private void field(String cat, Circuit c, String what, String was, String now) {
        if (!Objects.equals(was, now)) diffs.add(new Diff(Change.CHANGED, cat, Place.of(after, c), what, was, now));
    }

    private static String yes(boolean b) {
        return b ? "Yes" : "No";
    }

    //-------------------------------------------------------------------------------------------------
    //Correlations

    private void zoneLinks() {
        links(ZONE_LINKS, before.zone_links, after.zone_links, true);
    }

    private void outputLinks() {
        links(OUTPUT_LINKS, before.io_links, after.io_links, false);
    }

    //Links as "source key > target key", reported against the source with the target named
    private void links(String cat, List<int[]> la, List<int[]> lb, boolean to_input) {
        Map<String, Circuit[]> a = linkMap(before, la, to_input), b = linkMap(after, lb, to_input);
        for (Map.Entry<String, Circuit[]> e : b.entrySet()) {
            if (a.containsKey(e.getKey())) continue;
            Circuit[] p = e.getValue();
            diffs.add(new Diff(Change.ADDED, cat, Place.of(after, p[0]), "Correlated to " + Place.of(after, p[1]).brief(), "", "Correlated"));
        }
        for (Map.Entry<String, Circuit[]> e : a.entrySet()) {
            if (b.containsKey(e.getKey())) continue;
            Circuit[] p = e.getValue();
            diffs.add(new Diff(Change.REMOVED, cat, Place.of(before, p[0]), "Correlated to " + Place.of(before, p[1]).brief(), "Correlated", ""));
        }
    }

    private static Map<String, Circuit[]> linkMap(Job job, List<int[]> links, boolean to_input) {
        Map<String, Circuit[]> map = new LinkedHashMap<>();
        for (int[] l : links) {
            Circuit src = job.inputs.get(l[0]);
            Circuit dst = to_input ? job.inputs.get(l[1]) : job.outputs.get(l[1]);
            if (src == null || dst == null) continue;
            map.putIfAbsent(key(src) + " > " + key(dst), new Circuit[] { src, dst });
        }
        return map;
    }

    //-------------------------------------------------------------------------------------------------
    //LEDs

    private void leds() {
        Map<String, Object[]> a = slots(before), b = slots(after);
        for (Map.Entry<String, Object[]> e : b.entrySet()) {
            Led nb = (Led) e.getValue()[0];
            int[] slot = (int[]) e.getValue()[1];
            Place place = Place.of(after, nb, slot[0], slot[1]);
            Object[] old = a.get(e.getKey());
            if (old == null) {
                diffs.add(new Diff(Change.ADDED, LEDS, place, "LED (" + nb.led_type_name + ")", "", nb.tag.trim()));
                continue;
            }
            Led na = (Led) old[0];
            if (!na.led_type_name.equals(nb.led_type_name)) diffs.add(new Diff(Change.CHANGED, LEDS, place, "Assignment", na.led_type_name, nb.led_type_name));
            if (!na.tag.trim().equals(nb.tag.trim())) diffs.add(new Diff(Change.CHANGED, LEDS, place, "Tag", na.tag.trim(), nb.tag.trim()));
            if (!na.states.equals(nb.states)) diffs.add(new Diff(Change.CHANGED, LEDS, place, "LED settings", na.states.toString(), nb.states.toString()));
        }
        for (Map.Entry<String, Object[]> e : a.entrySet()) {
            if (b.containsKey(e.getKey())) continue;
            Led l = (Led) e.getValue()[0];
            int[] slot = (int[]) e.getValue()[1];
            diffs.add(new Diff(Change.REMOVED, LEDS, Place.of(before, l, slot[0], slot[1]), "LED (" + l.led_type_name + ")", l.tag.trim(), ""));
        }
    }

    //Every LED position: "node/cpu/frame/led" -> {Led, slot}
    private static Map<String, Object[]> slots(Job job) {
        Map<String, Object[]> map = new LinkedHashMap<>();
        for (Led l : job.leds.values()) for (int[] p : l.slots) map.putIfAbsent(slotKey(p), new Object[] { l, p });
        return map;
    }

    private void ledLinks() {
        Map<String, Object[]> a = ledLinkMap(before), b = ledLinkMap(after);
        for (Map.Entry<String, Object[]> e : b.entrySet()) {
            if (a.containsKey(e.getKey())) continue;
            Object[] v = e.getValue();
            diffs.add(new Diff(Change.ADDED, LED_LINKS, (Place) v[0], "Shows " + ((Place) v[1]).brief(), "", "Correlated"));
        }
        for (Map.Entry<String, Object[]> e : a.entrySet()) {
            if (b.containsKey(e.getKey())) continue;
            Object[] v = e.getValue();
            diffs.add(new Diff(Change.REMOVED, LED_LINKS, (Place) v[0], "Shows " + ((Place) v[1]).brief(), "Correlated", ""));
        }
    }

    //"slot > circuit key" -> {LED place, circuit place}
    private static Map<String, Object[]> ledLinkMap(Job job) {
        Map<String, Object[]> map = new LinkedHashMap<>();
        for (Led l : job.leds.values()) {
            List<Circuit> shown = new ArrayList<>(l.inputs);
            shown.addAll(l.outputs);
            for (int[] p : l.slots) {
                for (Circuit c : shown) {
                    map.putIfAbsent(slotKey(p) + " > " + key(c), new Object[] { Place.of(job, l, p[0], p[1]), Place.of(job, c) });
                }
            }
        }
        return map;
    }

    //-------------------------------------------------------------------------------------------------
    //Logic, matched by the input zone it drives (or its name when it drives none)

    private void logic() {
        Map<String, Equation> a = equations(before), b = equations(after);
        for (Map.Entry<String, Equation> e : b.entrySet()) {
            Equation nb = e.getValue(), na = a.get(e.getKey());
            Place place = Place.of(after, nb);
            if (na == null) {
                diffs.add(new Diff(Change.ADDED, LOGIC, place, "Logic equation", "", text(nb)));
                continue;
            }
            if (!text(na).equals(text(nb))) diffs.add(new Diff(Change.CHANGED, LOGIC, place, "Equation", text(na), text(nb)));
            if (!na.tag.trim().equals(nb.tag.trim())) diffs.add(new Diff(Change.CHANGED, LOGIC, place, "Name", na.tag.trim(), nb.tag.trim()));
            if (!na.comments.trim().equals(nb.comments.trim())) diffs.add(new Diff(Change.CHANGED, LOGIC, place, "Comment", na.comments.trim(), nb.comments.trim()));
        }
        for (Map.Entry<String, Equation> e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) diffs.add(new Diff(Change.REMOVED, LOGIC, Place.of(before, e.getValue()), "Logic equation", text(e.getValue()), ""));
        }
    }

    private static Map<String, Equation> equations(Job job) {
        Map<String, Equation> map = new LinkedHashMap<>();
        for (Equation e : job.equations.values()) {
            Set<String> subjects = new TreeSet<>();
            for (Circuit c : e.subjects) subjects.add(key(c));
            map.putIfAbsent(subjects.isEmpty() ? "name/" + e.tag.trim() : String.join(",", subjects), e);
        }
        return map;
    }

    private static String text(Equation e) {
        return e.text.replaceAll("\\s+", " ").trim();
    }

    //-------------------------------------------------------------------------------------------------
    //Zone bypass membership

    private void bypass() {
        Set<String> a = bypassed(before), b = bypassed(after);
        Map<String, Circuit> outs_a = byKey(before.outputs.values()), outs_b = byKey(after.outputs.values());
        for (String k : b) {
            if (!a.contains(k) && outs_a.containsKey(k)) diffs.add(new Diff(Change.ADDED, BYPASS, Place.of(after, outs_b.get(k)), "Put on Zone Bypass", "No", "Yes"));
        }
        for (String k : a) {
            if (!b.contains(k) && outs_b.containsKey(k)) diffs.add(new Diff(Change.REMOVED, BYPASS, Place.of(after, outs_b.get(k)), "Taken off Zone Bypass", "Yes", "No"));
        }
    }

    private static Set<String> bypassed(Job job) {
        Set<String> set = new LinkedHashSet<>();
        for (Job.ControlGroup g : job.bypassGroups()) for (Circuit c : g.outputs) set.add(key(c));
        return set;
    }
}
