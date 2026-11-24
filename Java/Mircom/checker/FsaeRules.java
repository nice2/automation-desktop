package checker;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import checker.Job.Circuit;
import checker.Job.Equation;
import checker.Job.Kind;
import checker.Job.Led;

//FSAE (Fire Service Access Elevator) checks. Each floor has 4 input zones in a row - Normal, Low Heat,
//High Heat, Smoke Det - fed by the dual heat/smoke detector in that floor's elevator lobby, a Normal logic
//equation over the Low/High Heat zones, and one LED per zone on each FSAE graphic annunciator.
//
//Nothing here is hard-coded to one job: floor groups are found from the zone tags, and where a job has its
//own convention (which sub-address feeds which zone, the Normal equation's compiled form, LED settings)
//the most common choice in the job is taken as the expected one and the odd ones out are reported.
public final class FsaeRules {

    private FsaeRules() {}

    public enum Role {
        //zone_words: how the bot starts the input zone tag. tag_words: anything seen at the start of LED/logic tags.
        NORMAL(7, 5, new String[] { "Normal" }, new String[] { "normal", "norm" }),
        LOW_HEAT(4, 3, new String[] { "Low Heat" }, new String[] { "low heat", "loheat", "lowheat", "lo heat", "low" }),
        HIGH_HEAT(1, 2, new String[] { "High Heat" }, new String[] { "high heat", "hiheat", "highheat", "hi heat", "high" }),
        SMOKE(1, 2, new String[] { "Smoke Det" }, new String[] { "smoke det", "smoked", "smokedet", "smoke detector", "smoke" });

        final int process_type; //Monitor, Non-Latch Supv, Alarm Input, Alarm Input
        final int led_type; //Monitor Stat, Supervisory Stat, Alarm Stat, Alarm Stat
        final String[] zone_words;
        final String[] tag_words;

        Role(int process_type, int led_type, String[] zone_words, String[] tag_words) {
            this.process_type = process_type;
            this.led_type = led_type;
            this.zone_words = zone_words;
            this.tag_words = tag_words;
        }

        String[] allWords() {
            String[] all = new String[zone_words.length + tag_words.length];
            System.arraycopy(zone_words, 0, all, 0, zone_words.length);
            System.arraycopy(tag_words, 0, all, zone_words.length, tag_words.length);
            return all;
        }

        boolean startsZoneTag(String tag) {
            String t = tag.trim().toLowerCase();
            for (String w : zone_words) if (t.startsWith(w.toLowerCase() + " ") || t.equals(w.toLowerCase())) return true;
            return false;
        }

        String label() {
            switch (this) {
                case NORMAL: return "Normal";
                case LOW_HEAT: return "Low Heat";
                case HIGH_HEAT: return "High Heat";
                default: return "Smoke Det";
            }
        }
    }

    static final Role[] ROLES = Role.values();

    //One floor: 4 consecutive input zones on the same node
    public static class Group implements Place.Placeable {
        int node;
        int addr; //address of the Normal zone
        final EnumMap<Role, Circuit> zones = new EnumMap<>(Role.class);
        String floor = ""; //Normal zone tag without the word "Normal", as typed

        public Place place(Job job) {
            Circuit normal = zones.get(Role.NORMAL);
            String cpu = job.panel == Job.Panel.FX6000 ? Place.cpuName(job, normal.node, normal.cpu) : "";
            return new Place(node, job.nodeName(node), cpu, "", "IZ " + addr + "-" + (addr + 3), "FSAE floor group", floor, "");
        }

        //Normalized location text of one of the group's zones, used to compare against other tags
        String floorOf(Role role) {
            return Text.stripWord(zones.get(role).tag, role.allWords());
        }
    }

    static class Groups {
        final List<Group> list = new ArrayList<>();
        final Map<Integer, Group> by_zone = new HashMap<>(); //zone ckt -> group
        final Map<Integer, Role> role_of = new HashMap<>(); //zone ckt -> role
        final List<Circuit> strays = new ArrayList<>(); //look like FSAE zones but are not in a complete group

        Role role(Circuit c) {
            return role_of.get(c.ckt);
        }

        List<Circuit> zones(Role role) {
            List<Circuit> list = new ArrayList<>();
            for (Group g : this.list) list.add(g.zones.get(role));
            return list;
        }
    }

    static Groups groups(Job job) {
        Object cached = job.cache.get("fsae.groups");
        if (cached != null) return (Groups) cached;

        Groups groups = new Groups();
        List<Circuit> zones = job.inputZones();
        Set<Integer> grouped = new HashSet<>();
        for (Circuit normal : zones) {
            if (!Role.NORMAL.startsZoneTag(normal.tag)) continue;
            Group g = new Group();
            g.node = normal.node;
            g.addr = normal.addr;
            g.zones.put(Role.NORMAL, normal);
            for (int i = 1; i < ROLES.length; i++) {
                Circuit z = job.inputZoneNear(normal, normal.addr + i);
                if (z == null || !ROLES[i].startsZoneTag(z.tag)) {
                    g = null;
                    break;
                }
                g.zones.put(ROLES[i], z);
            }
            if (g == null) continue;
            g.floor = Text.stripWordDisplay(normal.tag, Role.NORMAL.zone_words);
            groups.list.add(g);
            for (Role r : ROLES) {
                Circuit z = g.zones.get(r);
                groups.by_zone.put(z.ckt, g);
                groups.role_of.put(z.ckt, r);
                grouped.add(z.ckt);
            }
        }
        for (Circuit z : zones) {
            if (grouped.contains(z.ckt)) continue;
            for (Role r : ROLES) if (r != Role.NORMAL && r.startsZoneTag(z.tag)) groups.strays.add(z);
        }
        job.cache.put("fsae.groups", groups);
        return groups;
    }

    //The value most of the items have, or null if there are none
    static <T> T majority(Iterable<T> values) {
        Map<T, Integer> counts = new LinkedHashMap<>();
        for (T v : values) counts.merge(v, 1, Integer::sum);
        T best = null;
        int best_n = 0;
        for (Map.Entry<T, Integer> e : counts.entrySet()) {
            if (e.getValue() > best_n) {
                best = e.getKey();
                best_n = e.getValue();
            }
        }
        return best;
    }

    //---------------------------------------------------------------------------------------------------

    public static class Zones implements Rule {

        public String id() {
            return "FSAE-ZONES";
        }

        public String name() {
            return "FSAE zones";
        }

        public String title() {
            return "FSAE floor groups: Normal, Low Heat, High Heat, Smoke Det input zones";
        }

        public boolean supports(Job.Panel panel) {
            return panel.isMgc();
        }

        public void check(Job job, Report report) {
            Groups groups = groups(job);
            if (groups.list.isEmpty()) {
                report.note("No FSAE floor groups found (input zones tagged Normal / Low Heat / High Heat / Smoke Det in a row).");
                return;
            }
            Map<Integer, Integer> per_node = new TreeMap<>();
            for (Group g : groups.list) per_node.merge(g.node, 1, Integer::sum);
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : per_node.entrySet()) parts.add("node " + e.getKey() + ": " + e.getValue());
            report.note(Report.plural(groups.list.size(), "floor group") + " found (" + String.join(", ", parts) + ")");

            for (Group g : groups.list) {
                for (Role r : ROLES) {
                    Circuit z = g.zones.get(r);
                    if (z.process_type != r.process_type) {
                        report.error(z, "Zone type is " + z.process_name + ", should be " + processName(job, r))
                            .why("FSAE zones: Normal = Mon., Low Heat = Supv., High Heat and Smoke Det = Alarm");
                    }
                    if (r == Role.NORMAL) continue;
                    String own = g.floorOf(r);
                    String normal = g.floorOf(Role.NORMAL);
                    if (Text.similarity(own, normal) < 0.95) {
                        Group other = Text.best(own, groups.list, x -> x.floorOf(r));
                        if (other != g && Text.similarity(own, other.floorOf(r)) > Text.similarity(own, normal)) {
                            report.warning(z, "Tag reads like another floor (" + other.floor + ")").more("It sits in the group for \"" + g.floor + "\".");
                        }
                    }
                }
            }
            for (Circuit z : groups.strays) {
                report.warning(z, "Not in a complete FSAE group").more("Tagged like an FSAE zone, but not one of 4 consecutive Normal / Low Heat / High Heat / Smoke Det zones.");
            }
        }

        private static String processName(Job job, Role r) {
            for (Circuit c : job.inputs.values()) if (c.process_type == r.process_type) return c.process_name;
            return String.valueOf(r.process_type);
        }
    }

    //---------------------------------------------------------------------------------------------------

    public static class Devices implements Rule {

        public String id() {
            return "FSAE-DEVICES";
        }

        public String name() {
            return "FSAE detectors";
        }

        public String title() {
            return "FSAE device links: each floor's dual heat/smoke feeds its Low Heat, High Heat and Smoke Det zones";
        }

        public boolean supports(Job.Panel panel) {
            return panel.isMgc();
        }

        public void check(Job job, Report report) {
            Groups groups = groups(job);
            if (groups.list.isEmpty()) return;

            //Learn which sub-address feeds which zone, and which device types are used, from the job itself
            Map<Role, List<Integer>> subs = new EnumMap<>(Role.class);
            List<String> types = new ArrayList<>();
            for (Group g : groups.list) {
                for (Role r : ROLES) {
                    if (r == Role.NORMAL) continue;
                    List<Circuit> src = job.sourcesOf(g.zones.get(r));
                    if (src.size() == 1) {
                        subs.computeIfAbsent(r, k -> new ArrayList<>()).add(src.get(0).sub);
                        types.add(src.get(0).device_type);
                    }
                }
            }
            Map<Role, Integer> expected_sub = new EnumMap<>(Role.class);
            for (Map.Entry<Role, List<Integer>> e : subs.entrySet()) expected_sub.put(e.getKey(), majority(e.getValue()));
            Set<String> device_types = new LinkedHashSet<>(types);
            report.note("Expected wiring (most common in this job): " + subDescription(expected_sub) + " of " + String.join(" / ", device_types));

            Map<String, Group> device_group = new HashMap<>(); //device -> the group it feeds
            for (Group g : groups.list) {
                if (!job.sourcesOf(g.zones.get(Role.NORMAL)).isEmpty()) {
                    for (Circuit s : job.sourcesOf(g.zones.get(Role.NORMAL))) {
                        report.warning(g.zones.get(Role.NORMAL), "Device linked to a Normal zone").items("Linked device", List.of(s)).more("Normal zones are driven by logic only.");
                    }
                }
                Set<String> devices = new LinkedHashSet<>();
                for (Role r : ROLES) {
                    if (r == Role.NORMAL) continue;
                    Circuit z = g.zones.get(r);
                    List<Circuit> src = job.sourcesOf(z);
                    if (src.isEmpty()) {
                        report.error(z, "No detector linked").more(r.label() + " for \"" + g.floor + "\" never activates.");
                        continue;
                    }
                    if (src.size() > 1) report.warning(z, src.size() + " device inputs linked").items("Linked inputs", src);
                    for (Circuit c : src) {
                        devices.add(c.deviceKey());
                        Integer want = expected_sub.get(r);
                        if (want != null && c.sub != want) {
                            report.error(z, "Fed from sub-address " + c.sub + ", should be " + want).items("Linked input", List.of(c))
                                .more("Most floors in this job feed " + r.label() + " from sub-address " + want + ".");
                        }
                        if (c.node != g.node) {
                            report.warning(z, "Fed by a device on node " + c.node).items("Linked input", List.of(c));
                        }
                        Group other = device_group.put(c.deviceKey(), g);
                        if (other != null && other != g) {
                            report.error(c, "Feeds two FSAE floors").more("\"" + other.floor + "\" and \"" + g.floor + "\".");
                        }
                    }
                }
                if (devices.size() > 1) {
                    report.warning(g, "Fed by " + devices.size() + " different devices").more("Normally one dual heat/smoke detector feeds Low Heat, High Heat and Smoke Det.");
                }
            }

            //Dual heat/smoke detectors that are not in any FSAE group
            Set<String> seen = new HashSet<>();
            for (Circuit c : job.inputs.values()) {
                if (c.kind != Kind.DEVICE || !device_types.contains(c.device_type) || !seen.add(c.deviceKey())) continue;
                if (!device_group.containsKey(c.deviceKey())) {
                    report.warning(c, "Dual heat not in any FSAE group").more("Same device type as the FSAE detectors, but not linked to any FSAE floor.");
                }
            }
        }

        private static String subDescription(Map<Role, Integer> subs) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Role, Integer> e : subs.entrySet()) parts.add("sub " + e.getValue() + " -> " + e.getKey().label());
            return String.join(", ", parts);
        }
    }

    //---------------------------------------------------------------------------------------------------

    public static class Logic implements Rule {

        //FX4000: 01-**-**IZ-0061:Trouble    FX6000/Flexnet: 01-00-**-IZ-085:F
        private static final Pattern IZ_REF = Pattern.compile("(\\d+)-\\S*?IZ-0*(\\d+):(\\w+)");

        public String id() {
            return "FSAE-LOGIC";
        }

        public String name() {
            return "FSAE Normal logic";
        }

        public String title() {
            return "FSAE Normal logic: each Normal zone is on when its Low/High Heat are not in alarm or trouble";
        }

        public boolean supports(Job.Panel panel) {
            return panel.isMgc();
        }

        public void check(Job job, Report report) {
            Groups groups = groups(job);
            if (groups.list.isEmpty()) return;

            List<String> signatures = new ArrayList<>();
            for (Group g : groups.list) {
                List<Equation> eqs = job.equationsDriving(g.zones.get(Role.NORMAL));
                if (eqs.size() == 1) signatures.add(eqs.get(0).signature());
            }
            String expected_signature = majority(signatures);
            List<Equation> all_fsae = new ArrayList<>();
            for (Group g : groups.list) all_fsae.addAll(job.equationsDriving(g.zones.get(Role.NORMAL)));

            int ok = 0;
            for (Group g : groups.list) {
                Circuit normal = g.zones.get(Role.NORMAL);
                Circuit low = g.zones.get(Role.LOW_HEAT);
                Circuit high = g.zones.get(Role.HIGH_HEAT);
                List<Equation> eqs = job.equationsDriving(normal);
                if (eqs.isEmpty()) {
                    report.error(normal, "No Normal logic").more("No logic equation drives this Normal zone, so it never turns on.");
                    continue;
                }
                if (eqs.size() > 1) {
                    report.error(normal, eqs.size() + " logic equations drive it");
                }
                for (Equation e : eqs) {
                    int problems = 0;
                    List<Integer> got = new ArrayList<>(e.inputs.values());
                    List<Integer> want = List.of(low.ckt, high.ckt, low.ckt, high.ckt);
                    if (!got.equals(want)) {
                        List<Object> checks = new ArrayList<>();
                        for (int ckt : got) checks.add(job.inputs.containsKey(ckt) ? job.inputs.get(ckt) : Place.text("circuit " + ckt + " (missing)"));
                        report.error(e, "Checks the wrong zones").items("It checks", checks)
                            .more("Should check " + Place.brief(low) + " and " + Place.brief(high) + " (trouble, then input).");
                        problems++;
                    }
                    if (expected_signature != null && !e.signature().equals(expected_signature)) {
                        report.warning(e, "Written differently from the other floors").more(e.text.replaceAll("\\s+", " ").trim());
                        problems++;
                    }
                    //The text should name the same zones as the compiled form
                    Set<String> refs = new LinkedHashSet<>();
                    Matcher m = IZ_REF.matcher(e.text);
                    while (m.find()) refs.add(Integer.parseInt(m.group(1)) + "/" + Integer.parseInt(m.group(2)));
                    Set<String> want_refs = Set.of(low.node + "/" + low.addr, high.node + "/" + high.addr);
                    if (!refs.isEmpty() && !refs.equals(want_refs)) {
                        report.error(e, "Text refers to IZ " + String.join(", ", refs).replaceAll("\\d+/", ""))
                            .more("This floor's Low/High Heat are IZ " + low.addr + " and IZ " + high.addr + ".");
                        problems++;
                    }
                    if (e.node != normal.node) {
                        report.warning(e, "Logic on node " + e.node + ", zone on node " + normal.node);
                    }
                    //Equation name should be about this floor
                    String name = Text.stripWord(e.tag, Role.NORMAL.allWords());
                    Equation closest = Text.best(name, all_fsae, x -> Text.stripWord(x.subjects.isEmpty() ? "" : x.subjects.get(0).tag, Role.NORMAL.allWords()));
                    if (closest != null && closest != e && Text.similarity(name, g.floorOf(Role.NORMAL)) < 0.95
                        && Text.similarity(name, Text.stripWord(closest.subjects.get(0).tag, Role.NORMAL.allWords())) >= 0.95) {
                        report.warning(e, "Named for a different floor").more("This logic drives " + Place.brief(normal) + ".");
                        problems++;
                    }
                    if (problems == 0) ok++;
                }
            }
            report.note(ok + " of " + Report.plural(groups.list.size(), "Normal zone") + " have the expected logic");
        }
    }

    //---------------------------------------------------------------------------------------------------

    public static class Display implements Rule {

        public String id() {
            return "FSAE-DISPLAY";
        }

        public String name() {
            return "FSAE graphic adder";
        }

        public String title() {
            return "FSAE graphic adder: one LED per zone on each FSAE annunciator, linked to the right zone";
        }

        public boolean supports(Job.Panel panel) {
            return panel.isMgc();
        }

        public void check(Job job, Report report) {
            Groups groups = groups(job);
            if (groups.list.isEmpty()) return;

            //Annunciators (node/cpu) that show any FSAE zone, and every FSAE LED on each
            Map<String, List<Led>> annunciators = new TreeMap<>();
            Map<Integer, Led> fsae_leds = new TreeMap<>();
            for (Led l : job.leds.values()) {
                boolean fsae = false;
                for (Circuit c : l.inputs) if (groups.role(c) != null) fsae = true;
                if (!fsae) continue;
                fsae_leds.put(l.group, l);
                for (int[] p : l.slots) annunciators.computeIfAbsent(p[0] + "/" + p[1], k -> new ArrayList<>()).add(l);
            }
            if (annunciators.isEmpty()) {
                report.error(Place.text("FSAE graphic adder"), "No FSAE zone has an LED").more(Report.plural(groups.list.size() * 4, "FSAE zone") + ", none linked to an LED.");
                return;
            }
            int zone_count = groups.list.size() * ROLES.length;
            for (Map.Entry<String, List<Led>> a : annunciators.entrySet()) {
                report.note("Annunciator " + annunciatorName(job, a.getKey(), a.getValue()) + ": " + Report.plural(a.getValue().size(), "FSAE LED"));
            }

            //Each zone: exactly one LED on each annunciator that shows most of the FSAE zones
            for (Map.Entry<String, List<Led>> a : annunciators.entrySet()) {
                Map<Integer, List<Led>> by_zone = new HashMap<>();
                for (Led l : a.getValue()) {
                    for (Circuit c : l.inputs) if (groups.role(c) != null) by_zone.computeIfAbsent(c.ckt, k -> new ArrayList<>()).add(l);
                }
                boolean full = by_zone.size() * 2 >= zone_count;
                String[] nc = a.getKey().split("/");
                String name = annunciator(job, a.getKey());
                for (Group g : groups.list) {
                    for (Role r : ROLES) {
                        Circuit z = g.zones.get(r);
                        List<Led> shown = by_zone.getOrDefault(z.ckt, List.of());
                        if (shown.isEmpty() && full) {
                            report.error(z, "No LED on " + name);
                        } else if (shown.size() > 1) {
                            List<Place> places = new ArrayList<>();
                            for (Led l : shown) places.add(Place.of(job, l, Integer.parseInt(nc[0]), Integer.parseInt(nc[1])));
                            report.error(z, shown.size() + " LEDs on " + name).items("LEDs linked to this zone", places);
                        }
                    }
                }
                if (!full) report.note("Annunciator " + name + " shows only some FSAE zones, so missing LEDs there are not reported.");
            }

            //Each LED: right type, consistent settings, tag matches the zone it is linked to
            Map<Role, List<String>> states = new EnumMap<>(Role.class);
            for (Led l : fsae_leds.values()) {
                Role r = fsaeRole(groups, l);
                if (r != null) states.computeIfAbsent(r, k -> new ArrayList<>()).add(l.states.toString());
            }
            Map<Role, String> expected_states = new EnumMap<>(Role.class);
            for (Map.Entry<Role, List<String>> e : states.entrySet()) expected_states.put(e.getKey(), majority(e.getValue()));

            for (Led l : fsae_leds.values()) {
                if (l.inputs.size() > 1) {
                    report.warning(l, "Linked to " + l.inputs.size() + " inputs").items("Linked inputs", l.inputs);
                }
                for (Circuit z : l.inputs) {
                    Role r = groups.role(z);
                    if (r == null) continue;
                    Group g = groups.by_zone.get(z.ckt);
                    if (l.led_type != r.led_type) {
                        report.error(l, "Assignment is " + shortType(l.led_type_name) + ", should be " + shortType(ledTypeName(job, r)))
                            .items("Shows", List.of(z)).why("Cheat sheet, Graphic Adder (FSAE): Normal = Monitor Stat, Low Heat = Supervisory, High Heat / Smoke Det = Alarm Stat");
                    }
                    String want_states = expected_states.get(r);
                    if (want_states != null && !l.states.toString().equals(want_states)) {
                        report.warning(l, "LED settings differ from other " + r.label() + " LEDs").more("This LED: " + l.states + "\nOthers: " + want_states);
                    }
                    //Does the LED's own tag point at a different floor than the zone it is linked to?
                    String led_floor = Text.stripWord(l.tag, r.allWords());
                    double linked = Text.similarity(led_floor, g.floorOf(r));
                    Group closest = Text.best(led_floor, groups.list, x -> x.floorOf(r));
                    if (closest != null && closest != g) {
                        double other = Text.similarity(led_floor, closest.floorOf(r));
                        if (other > linked + 0.05) {
                            String summary = "Tagged " + closest.floor + ", linked to " + g.floor;
                            Report.Finding f = other >= 0.95 && linked < 0.95 ? report.error(l, summary) : report.warning(l, summary);
                            f.items("Linked to, then the zone its tag matches", List.of(z, closest.zones.get(r)));
                        }
                    }
                }
            }

            //Annunciators laid out the same should show the same zone at the same position
            List<String> keys = new ArrayList<>(annunciators.keySet());
            for (int i = 0; i < keys.size(); i++) {
                for (int j = i + 1; j < keys.size(); j++) mirror(job, groups, keys.get(i), keys.get(j), annunciators, report);
            }
        }

        private static void mirror(Job job, Groups groups, String a, String b, Map<String, List<Led>> annunciators, Report report) {
            Map<String, Led> pos_a = positions(annunciators.get(a), a);
            Map<String, Led> pos_b = positions(annunciators.get(b), b);
            int same = 0;
            List<String> differ = new ArrayList<>();
            for (Map.Entry<String, Led> e : pos_a.entrySet()) {
                Led other = pos_b.get(e.getKey());
                if (other == null) continue;
                if (fsaeZones(groups, e.getValue()).equals(fsaeZones(groups, other))) same++;
                else differ.add(e.getKey());
            }
            String na = annunciator(job, a), nb = annunciator(job, b);
            if (same + differ.size() == 0) return;
            if (same < 4 * differ.size()) {
                report.note("Annunciators " + na + " and " + nb + " are laid out differently, so they are not compared position by position.");
                return;
            }
            report.note("Annunciators " + na + " and " + nb + " share a layout: " + same + " of " + (same + differ.size()) + " positions match");
            for (String pos : differ) {
                Led la = pos_a.get(pos), lb = pos_b.get(pos);
                String[] fa = a.split("/");
                report.warning(Place.of(job, la, Integer.parseInt(fa[0]), Integer.parseInt(fa[1])), "Annunciators disagree here")
                    .items(na + ", then " + nb, List.of(la.inputs.isEmpty() ? Place.text("nothing") : la.inputs.get(0), lb.inputs.isEmpty() ? Place.text("nothing") : lb.inputs.get(0)));
            }
        }

        private static Map<String, Led> positions(List<Led> leds, String annunciator) {
            Map<String, Led> map = new TreeMap<>();
            for (Led l : leds) for (int[] p : l.slots) if ((p[0] + "/" + p[1]).equals(annunciator)) map.put(p[2] + "/" + p[3], l);
            return map;
        }

        private static Set<Integer> fsaeZones(Groups groups, Led l) {
            Set<Integer> set = new HashSet<>();
            for (Circuit c : l.inputs) if (groups.role(c) != null) set.add(c.ckt);
            return set;
        }

        private static Role fsaeRole(Groups groups, Led l) {
            for (Circuit c : l.inputs) if (groups.role(c) != null) return groups.role(c);
            return null;
        }

        private static String annunciatorName(Job job, String key, List<Led> leds) {
            Set<String> types = new LinkedHashSet<>();
            for (Led l : leds) {
                for (int[] p : l.slots) {
                    if ((p[0] + "/" + p[1]).equals(key)) types.add(job.frameType(p[0], p[1], p[2]));
                }
            }
            return annunciator(job, key) + " (" + String.join(", ", types) + ")";
        }

        private static String ledTypeName(Job job, Role r) {
            for (Led l : job.leds.values()) if (l.led_type == r.led_type) return l.led_type_name;
            return "type " + r.led_type;
        }

        private static String shortType(String led_type_name) {
            return led_type_name.replaceFirst("^.*/\\s*", "");
        }

        private static String annunciator(Job job, String key) {
            String[] nc = key.split("/");
            return "Node " + nc[0] + " " + Place.cpuName(job, Integer.parseInt(nc[0]), Integer.parseInt(nc[1]));
        }
    }
}
