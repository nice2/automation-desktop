package checker;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import checker.Job.Circuit;
import checker.Job.Kind;
import checker.Job.Led;

//LED corrections from the "Correcting LEDs" guide (FlexNet & FX6000 Guides). The configurator sets these LEDs up in a way
//that blinks or lights the wrong button, so they are fixed in the database:
//  FSAE graphic adder (FX4000 / FX6000): each LED's SomeActive set to a "[color] Continuous" state. The color depends on the
//    job (FSAE plan), so each job has a color plan: defaults by kind of LED, changed and remembered per job.
//  Page select / speaker buttons (FX6000): a button's red status and green LED groups (configurator Grp and Grp+1) lose
//    their amplifier circuits (LedOpts rows), so a shared amplifier does not light every floor button; the yellow group
//    (Grp+2) keeps them. Done on every button that has its own speaker circuit, as in 355 Exterior V59.
//  HOA switches (FX6000): red LED (Grp) shows the switch's RED logic zone, green (Grp+1) its GREEN zone (LedIpts), yellow
//    (Grp+2) its proving group (LedProvingStat). Optionally red/green set to Continuous.
public final class Leds {

    private Leds() {
    }

    //SYSLedStates values
    public static final int RED = 17, GREEN = 18, YELLOW = 19;

    static String stateName(Job job, int value) {
        String n = job == null ? null : job.led_state_names.get(value);
        if (n != null) return n;
        switch (value) {
            case RED: return "Red Continuous";
            case GREEN: return "Green Continuous";
            case YELLOW: return "Yellow Continuous";
            default: return "state " + value;
        }
    }

    static boolean continuous(int value) {
        return value == RED || value == GREEN || value == YELLOW;
    }

    //-------------------------------------------------------------------------------------------------
    //FSAE graphic adder colors

    public enum FsaeKind {
        NORMAL("Normal", GREEN),
        LOW_HEAT("Low Heat", YELLOW),
        HIGH_HEAT("High Heat", RED),
        SMOKE("Smoke Det", RED),
        POWER_ON("Power On", GREEN),
        SYSTEM_TROUBLE("System Trouble", YELLOW),
        POWER_FAULT("Power / fault", YELLOW),
        SHAFT_SMOKE("Shaft / vent smoke", RED),
        OTHER_ALARM("Other alarm", RED),
        OTHER_SUPERVISORY("Other supervisory", YELLOW),
        OTHER_MONITOR("Other monitor", GREEN),
        OTHER_TROUBLE("Other trouble", YELLOW);

        public final String label;
        public final int color; //default, from the guide's cheat sheet and the 355 Exterior fixes

        FsaeKind(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    public static final class FsaeLed {
        public final Led led;
        public final int[] slot; //{node, cpu, frame, led}
        public final FsaeKind kind;

        FsaeLed(Led led, int[] slot, FsaeKind kind) {
            this.led = led;
            this.slot = slot;
            this.kind = kind;
        }

        public String key() {
            return slot[0] + "/" + slot[1] + "/" + slot[2] + "/" + slot[3];
        }
    }

    //Every LED on an FSAE graphic adder (AGD graphic display frames) that shows an input zone
    public static List<FsaeLed> fsaeLeds(Job job) {
        List<FsaeLed> list = new ArrayList<>();
        if (!job.panel.isMgc()) return list;
        FsaeRules.Groups groups = FsaeRules.groups(job);
        for (Led l : job.leds.values()) {
            if (l.inputs.isEmpty()) continue;
            for (int[] p : l.slots) {
                if (!job.frameType(p[0], p[1], p[2]).toUpperCase().contains("AGD")) continue;
                list.add(new FsaeLed(l, p, kind(groups, l)));
            }
        }
        list.sort((a, b) -> compareSlots(a.slot, b.slot));
        return list;
    }

    private static int compareSlots(int[] a, int[] b) {
        for (int i = 0; i < 4; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        return 0;
    }

    static FsaeKind kind(FsaeRules.Groups groups, Led l) {
        for (Circuit c : l.inputs) {
            FsaeRules.Role r = groups.role(c);
            if (r == FsaeRules.Role.NORMAL) return FsaeKind.NORMAL;
            if (r == FsaeRules.Role.LOW_HEAT) return FsaeKind.LOW_HEAT;
            if (r == FsaeRules.Role.HIGH_HEAT) return FsaeKind.HIGH_HEAT;
            if (r == FsaeRules.Role.SMOKE) return FsaeKind.SMOKE;
        }
        String t = (l.tag + " " + (l.inputs.isEmpty() ? "" : l.inputs.get(0).words())).toLowerCase();
        String d = l.description.toLowerCase();
        if (t.contains("power on") || t.contains("pwr on")) return FsaeKind.POWER_ON;
        if (t.contains("system") && (t.contains("trouble") || t.contains("trbl"))) return FsaeKind.SYSTEM_TROUBLE;
        if (d.contains("alarm") && t.matches(".*(smoke|shaft|hoistway).*")) return FsaeKind.SHAFT_SMOKE; //an alarm LED, even "Vent Smoke Alarm"
        if (d.contains("alarm")) return FsaeKind.OTHER_ALARM;
        if (t.matches(".*\\b(pwr|power|fault|vent)\\b.*")) return FsaeKind.POWER_FAULT;
        if (d.contains("supervisory")) return FsaeKind.OTHER_SUPERVISORY;
        if (d.contains("trouble")) return FsaeKind.OTHER_TROUBLE;
        return FsaeKind.OTHER_MONITOR;
    }

    //A job's FSAE color plan: a color per kind of LED (defaults until changed), plus colors for single LEDs
    public static final class ColorPlan {
        public final Map<FsaeKind, Integer> colors = new EnumMap<>(FsaeKind.class);
        public final Map<String, Integer> leds = new LinkedHashMap<>(); //LED position "node/cpu/frame/led" -> color
        public boolean saved; //read from the job's plan file (as opposed to the defaults)

        public ColorPlan() {
            for (FsaeKind k : FsaeKind.values()) colors.put(k, k.color);
        }

        public int expected(FsaeLed f) {
            Integer one = leds.get(f.key());
            return one != null ? one : colors.get(f.kind);
        }
    }

    //Where color plans live; relative to the folder the checker runs from (Java/Mircom), next to the acceptances
    public static File plan_dir = new File("assets/checker/leds");

    static File planFile(Job job) {
        return new File(plan_dir, job.identity() + ".tsv");
    }

    public static ColorPlan plan(Job job) {
        ColorPlan plan = new ColorPlan();
        File f = planFile(job);
        if (!f.isFile()) return plan;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String[] c = line.split("\t");
                if (c.length < 3 || c[0].equals("what")) continue;
                int color = Integer.parseInt(c[2].trim());
                if (c[0].equals("kind")) {
                    try {
                        plan.colors.put(FsaeKind.valueOf(c[1]), color);
                    } catch (IllegalArgumentException e) {
                        //a kind from a newer version of the checker; ignore
                    }
                } else if (c[0].equals("led")) {
                    plan.leds.put(c[1], color);
                }
            }
            plan.saved = true;
        } catch (IOException | NumberFormatException e) {
            System.err.println("Could not read " + f + ": " + e.getMessage());
        }
        return plan;
    }

    public static void savePlan(Job job, ColorPlan plan) throws IOException {
        File f = planFile(job);
        f.getParentFile().mkdirs();
        StringBuilder s = new StringBuilder("what\tkey\tcolor\tname\n");
        for (Map.Entry<FsaeKind, Integer> e : plan.colors.entrySet()) {
            s.append("kind\t").append(e.getKey().name()).append('\t').append(e.getValue()).append('\t').append(stateName(job, e.getValue())).append('\n');
        }
        for (Map.Entry<String, Integer> e : plan.leds.entrySet()) {
            s.append("led\t").append(e.getKey()).append('\t').append(e.getValue()).append('\t').append(stateName(job, e.getValue())).append('\n');
        }
        Files.writeString(f.toPath(), s.toString(), StandardCharsets.UTF_8);
        plan.saved = true;
    }

    //-------------------------------------------------------------------------------------------------
    //Page select (speaker) buttons, FX6000

    public static final class PageButton {
        public final Led red, green, yellow; //red status = configurator Grp, green = Grp+1, yellow = Grp+2
        public final List<Circuit> amps_red = new ArrayList<>(), amps_green = new ArrayList<>(); //amplifier rows to delete
        public final Set<Circuit> own = new java.util.LinkedHashSet<>(); //the button's other (speaker) circuits

        PageButton(Led red, Led green, Led yellow) {
            this.red = red;
            this.green = green;
            this.yellow = yellow;
        }

        public String name() {
            for (Led l : new Led[] { red, green, yellow }) if (!l.tag.isBlank()) return l.tag.trim().replaceAll("\\s{2,}", " ");
            return "Page select " + red.group;
        }

        //Has amplifier rows on the red/green groups while it also has its own speaker circuit to light it
        public boolean needsFix() {
            return !own.isEmpty() && (!amps_red.isEmpty() || !amps_green.isEmpty());
        }
    }

    static boolean amplifier(Circuit c) {
        return c.loop_type.equalsIgnoreCase("Amplifiers") || c.device_type.toLowerCase().contains("watt");
    }

    public static List<PageButton> pageButtons(Job job) {
        List<PageButton> list = new ArrayList<>();
        if (job.panel != Job.Panel.FX6000) return list;
        for (Led green : job.leds.values()) {
            if (!green.display_type.equalsIgnoreCase("Page Select") || !green.description.equalsIgnoreCase("PageSelect")) continue;
            Led red = job.leds.get(green.group - 1), yellow = job.leds.get(green.group + 1);
            if (red == null || yellow == null || !red.description.toLowerCase().contains("red status") || !yellow.description.toLowerCase().contains("trouble")) continue;
            PageButton b = new PageButton(red, green, yellow);
            for (Circuit c : red.outputs) if (amplifier(c)) b.amps_red.add(c);
            for (Circuit c : green.outputs) if (amplifier(c)) b.amps_green.add(c);
            for (Led l : new Led[] { red, green, yellow }) for (Circuit c : l.outputs) if (!amplifier(c)) b.own.add(c);
            list.add(b);
        }
        return list;
    }

    //-------------------------------------------------------------------------------------------------
    //HOA switches, FX6000

    public static final class HoaSwitch {
        public final Led red, green, yellow; //configurator Grp, Grp+1, Grp+2
        public Circuit red_zone, green_zone; //suggested (or already linked)
        public Integer proving; //suggested (or already linked) proving group

        HoaSwitch(Led red, Led green, Led yellow) {
            this.red = red;
            this.green = green;
            this.yellow = yellow;
        }

        public String name() {
            for (Led l : new Led[] { red, green, yellow }) if (!l.tag.isBlank()) return l.tag.trim().replaceAll("\\s{2,}", " ");
            return "HOA switch " + red.group;
        }

        public boolean complete() {
            return !red.inputs.isEmpty() && !green.inputs.isEmpty() && !yellow.provings.isEmpty();
        }
    }

    static boolean hoa(Led l) {
        return l.display_type.toLowerCase().startsWith("auto/man");
    }

    public static List<HoaSwitch> hoaSwitches(Job job) {
        List<HoaSwitch> list = new ArrayList<>();
        if (job.panel != Job.Panel.FX6000) return list;
        Set<Integer> used = new HashSet<>();
        for (Led red : job.leds.values()) {
            if (!hoa(red) || used.contains(red.group) || red.slots.isEmpty()) continue;
            Led green = job.leds.get(red.group + 1), yellow = job.leds.get(red.group + 2);
            if (green == null || yellow == null || !hoa(green) || !hoa(yellow) || !yellow.description.toLowerCase().contains("trouble")) continue;
            if (red.description.toLowerCase().contains("trouble") || green.description.toLowerCase().contains("trouble")) continue;
            used.add(red.group);
            used.add(green.group);
            used.add(yellow.group);
            HoaSwitch s = new HoaSwitch(red, green, yellow);
            s.red_zone = red.inputs.isEmpty() ? suggestZone(job, s.name(), "red") : red.inputs.get(0);
            s.green_zone = green.inputs.isEmpty() ? suggestZone(job, s.name(), "green") : green.inputs.get(0);
            s.proving = yellow.provings.isEmpty() ? suggestProving(job, s.name()) : yellow.provings.get(0);
            list.add(s);
        }
        return list;
    }

    //Input zones that could be a switch's RED / GREEN zone: their tag has the word
    public static List<Circuit> colorZones(Job job, String color) {
        List<Circuit> list = new ArrayList<>();
        for (Circuit z : job.inputZones()) if (words(z.words()).contains(color)) list.add(z);
        return list;
    }

    static Circuit suggestZone(Job job, String switch_name, String color) {
        Circuit best = null;
        double best_score = 0;
        for (Circuit z : colorZones(job, color)) {
            double s = match(switch_name, z.words());
            if (s > best_score) {
                best_score = s;
                best = z;
            }
        }
        return best;
    }

    static Integer suggestProving(Job job, String switch_name) {
        Integer best = null;
        double best_score = 0;
        for (Job.Proving p : job.provings.values()) {
            if (p.zone == null) continue;
            double s = match(switch_name, p.zone.words());
            if (s > best_score) {
                best_score = s;
                best = p.group;
            }
        }
        return best;
    }

    //Words of a tag with HOA vocabulary evened out: "Fresh Air", "Press", "SPF" = pressurization; "Purge", "Exhaust" = purge;
    //"St" = stair. Colors and filler words are dropped.
    static Set<String> words(String text) {
        Set<String> set = new java.util.LinkedHashSet<>();
        for (String w : text.toLowerCase().replace("fresh air", "press").split("[^a-z0-9]+")) {
            if (w.isEmpty()) continue;
            if (w.equals("st") || w.equals("stairs")) w = "stair";
            if (w.startsWith("press") || w.equals("spf") || w.equals("supply") || w.equals("fresh")) w = "press";
            if (w.equals("exhaust") || w.equals("epf")) w = "purge";
            if (w.equals("grn")) w = "green";
            if (w.equals("air") || w.equals("hoa") || w.equals("proving") || w.equals("sw") || w.equals("switch")) continue;
            set.add(w);
        }
        return set;
    }

    //How well a zone's tag fits a switch: shared words minus the zone's words the switch lacks. A short name (the "A" of
    //Stair A, a number) on either side that the other lacks rules it out, so Stair A never matches Stair B or a garage.
    static double match(String switch_name, String zone_tag) {
        Set<String> a = words(switch_name), b = words(zone_tag);
        b.remove("red");
        b.remove("green");
        for (String w : a) if (identifier(w) && !b.contains(w)) return 0;
        for (String w : b) if (identifier(w) && !a.contains(w)) return 0;
        double shared = 0, missing = 0;
        for (String w : b) {
            if (a.contains(w)) shared++;
            else missing++;
        }
        return shared >= 2 && shared > missing ? shared - missing / 2 : 0;
    }

    private static boolean identifier(String w) {
        return w.length() <= 2 && !w.equals("fl");
    }

    //-------------------------------------------------------------------------------------------------
    //Pending LED changes for one job version

    public static final class Edits {
        public final Job job;
        public final Map<Integer, Integer> some_active = new LinkedHashMap<>(); //LED group -> new SomeActive
        public final Set<String> delete_opts = new java.util.LinkedHashSet<>(); //"group/ckt" LedOpts rows to delete
        public final Set<String> add_ipts = new java.util.LinkedHashSet<>(); //"group/ckt" LedIpts rows to add
        public final Set<String> add_provings = new java.util.LinkedHashSet<>(); //"group/proving" LedProvingStat rows to add

        public Edits(Job job) {
            this.job = job;
        }

        //The same changes, for the same job version read from another file (a copy with the correlation changes already in it)
        public Edits on(Job other) {
            Edits e = new Edits(other);
            e.some_active.putAll(some_active);
            e.delete_opts.addAll(delete_opts);
            e.add_ipts.addAll(add_ipts);
            e.add_provings.addAll(add_provings);
            return e;
        }

        public int size() {
            return some_active.size() + delete_opts.size() + add_ipts.size() + add_provings.size();
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public void setColor(Led l, int value) {
            if (l.someActive() == value) some_active.remove(l.group);
            else some_active.put(l.group, value);
        }

        public void fixPaging(PageButton b) {
            for (Circuit c : b.amps_red) delete_opts.add(b.red.group + "/" + c.ckt);
            for (Circuit c : b.amps_green) delete_opts.add(b.green.group + "/" + c.ckt);
        }

        public void fixHoa(HoaSwitch s, boolean continuous) {
            if (s.red_zone != null && !s.red.inputs.contains(s.red_zone)) add_ipts.add(s.red.group + "/" + s.red_zone.ckt);
            if (s.green_zone != null && !s.green.inputs.contains(s.green_zone)) add_ipts.add(s.green.group + "/" + s.green_zone.ckt);
            if (s.proving != null && !s.yellow.provings.contains(s.proving)) add_provings.add(s.yellow.group + "/" + s.proving);
            if (continuous) {
                setColor(s.red, RED);
                setColor(s.green, GREEN);
            }
        }

        //One line per change, for the save confirmation
        public List<String> describe() {
            List<String> out = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : some_active.entrySet()) {
                Led l = job.leds.get(e.getKey());
                out.add("LED group " + e.getKey() + " \"" + (l == null ? "" : l.tag.trim()) + "\": " + (l == null ? "" : stateName(job, l.someActive()) + " -> ") + stateName(job, e.getValue()));
            }
            for (String k : delete_opts) {
                String[] p = k.split("/");
                Circuit c = job.outputs.get(Integer.parseInt(p[1]));
                out.add("LED group " + p[0] + ": remove " + (c == null ? "output " + p[1] : "\"" + c.cleanTag() + "\""));
            }
            for (String k : add_ipts) {
                String[] p = k.split("/");
                Circuit c = job.inputs.get(Integer.parseInt(p[1]));
                out.add("LED group " + p[0] + ": show " + (c == null ? "input " + p[1] : Place.brief(c)));
            }
            for (String k : add_provings) {
                String[] p = k.split("/");
                Job.Proving pr = job.provings.get(Integer.parseInt(p[1]));
                out.add("LED group " + p[0] + ": proving group " + p[1] + (pr == null || pr.zone == null ? "" : " (" + Place.brief(pr.zone) + ")"));
            }
            return out;
        }
    }

    static boolean isZone(Circuit c) {
        return c != null && c.kind == Kind.INPUT_ZONE;
    }
}
