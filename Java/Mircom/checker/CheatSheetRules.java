package checker;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import checker.Job.Circuit;
import checker.Job.Kind;
import checker.Roles.Device;
import checker.Roles.Family;
import checker.Roles.Output;
import checker.Roles.Zone;

//Checks from the FlexNet & FX6000 "Device & Zone Cheat Sheet" (FX4000 has the same requirements).
//Findings say what is wrong in a few words; the cheat sheet line they come from is attached with why().
public final class CheatSheetRules {

    private CheatSheetRules() {}

    //Device inputs the cheat sheet rules look at: real devices, not panel/telephone/amplifier circuits or spares
    static List<Circuit> devices(Job job) {
        List<Circuit> list = new ArrayList<>();
        for (Circuit c : job.inputs.values()) {
            if (c.kind != Kind.DEVICE || c.isSpare()) continue;
            if (c.loop_type.equals("Telephone") || c.loop_type.equals("Amplifiers") || c.loop_type.equals("Switches")) continue;
            if (c.device_type.toLowerCase().contains("phone")) continue; //firephone / telephone modules
            list.add(c);
        }
        return list;
    }

    static Map<Zone, List<Circuit>> zonesByRole(Job job) {
        Map<Zone, List<Circuit>> map = new EnumMap<>(Zone.class);
        for (Circuit z : job.inputZones()) map.computeIfAbsent(Roles.zone(z), k -> new ArrayList<>()).add(z);
        return map;
    }

    static boolean inAny(Job job, Circuit device, List<Circuit> zones) {
        if (zones == null) return false;
        for (Circuit z : job.zonesOf(device)) if (zones.contains(z)) return true;
        return false;
    }

    static boolean reachesAny(Job job, Circuit device, List<Circuit> outputs) {
        for (Circuit o : job.outputsReachedBy(device)) if (outputs.contains(o)) return true;
        return false;
    }

    //Which cheat sheet the guideline comes from, set by each check for the job's panel
    private static final ThreadLocal<String> SOURCE = ThreadLocal.withInitial(() -> "Device & Zone Cheat Sheet");

    static String source(Job.Panel panel) {
        switch (panel) {
            case FX400: return "FX-400 Device Cheat Sheet";
            case FX2000: return "FX-2000 Device Cheat Sheet";
            default: return "FlexNet & FX6000 Device & Zone Cheat Sheet";
        }
    }

    static String sheet(String line) {
        return SOURCE.get() + ": " + line;
    }

    //---------------------------------------------------------------------------------------------------

    public static class DeviceSetup implements Rule {

        public String id() {
            return "SHEET-DEVICES";
        }

        public String name() {
            return "Device setup";
        }

        public String title() {
            return "Cheat sheet device setup: input type and NS flag for each kind of device";
        }

        public boolean supports(Job.Panel panel) {
            return panel.isReadable();
        }

        public void check(Job job, Report report) {
            SOURCE.set(source(job.panel));
            int checked = 0;
            for (Circuit c : devices(job)) {
                Device role = Roles.device(c);
                if (role.expected == null) continue;
                checked++;
                Family is = Roles.family(c);
                if (is != role.expected && !(role == Device.SUPERVISORY && is == Family.TROUBLE)) {
                    String want = role.expected == Family.ALARM ? "Alarm Input"
                        : role.expected == Family.LATCHED ? "Latched Supv" : role.expected == Family.TROUBLE ? "Trouble Input" : "Non-Latch Supv";
                    boolean serious = is == Family.ALARM || role.expected == Family.ALARM;
                    String more = "Tagged as a " + role.label + ".";
                    //Combo smoke/CO (sounder base) detectors inside apartments are sometimes set up as supervisory on purpose
                    if (c.device_type.toLowerCase().contains("fire-co") && role == Device.SMOKE) {
                        serious = false;
                        more += " Combo smoke/CO: fine if this is an in-unit detector meant to be supervisory.";
                    }
                    String summary = "Type is " + c.process_name + ", should be " + want;
                    (serious ? report.error(c, summary) : report.warning(c, summary)).more(more).why(sheet("Devices table, " + role.label));
                }
                if (role == Device.WATERFLOW && c.silenceable) {
                    report.error(c, "Waterflow without NS").why(sheet("Devices, Waterflow - waterflow should have NS"));
                }
                if (role == Device.PULL && Boolean.FALSE.equals(c.class_a)) {
                    report.warning(c, "Pull station not Class-A").why(sheet("Devices, Pull Station - Options: Class-A"));
                }
            }
            report.note(checked + " recognised device inputs checked");
        }
    }

    //---------------------------------------------------------------------------------------------------

    public static class DeviceZones implements Rule {

        public String id() {
            return "SHEET-ZONES";
        }

        public String name() {
            return "Standard zones";
        }

        public String title() {
            return "Cheat sheet zones: every device in General Alarm / Automatic Alarm / Supervisory / Waterflow as required";
        }

        public boolean supports(Job.Panel panel) {
            return panel.hasZones();
        }

        public void check(Job job, Report report) {
            SOURCE.set(source(job.panel));
            Map<Zone, List<Circuit>> zones = zonesByRole(job);
            List<String> found = new ArrayList<>();
            for (Map.Entry<Zone, List<Circuit>> e : zones.entrySet()) {
                if (e.getKey() != Zone.OTHER) found.add(e.getKey().label + " x" + e.getValue().size());
            }
            report.note("Standard zones found: " + (found.isEmpty() ? "none" : String.join(", ", found)));

            //Zone setup
            for (Map.Entry<Zone, List<Circuit>> e : zones.entrySet()) {
                for (Circuit z : e.getValue()) {
                    Family f = Roles.family(z);
                    switch (e.getKey()) {
                        case GENERAL_ALARM: case AUTOMATIC_ALARM: case WATERFLOW: case ELEV_RECALL: case ALT_RECALL:
                            if (f != Family.ALARM) report.error(z, "Zone type is " + z.process_name + ", should be Alarm").why(sheet("Zones, " + e.getKey().label + " - Alarm"));
                            break;
                        case NON_LATCHED: case LATCHED:
                            if (f != Family.SUPERVISORY && f != Family.LATCHED) {
                                report.error(z, "Zone type is " + z.process_name + ", should be Supervisory").why(sheet("Zones, " + e.getKey().label + " - Supv."));
                            }
                            break;
                        default:
                    }
                    if (e.getKey() == Zone.WATERFLOW && z.silenceable) report.error(z, "Waterflow zone without F1 NS").why(sheet("Zones, Waterflow - F1 NS"));
                    if (e.getKey() == Zone.LATCHED && Boolean.FALSE.equals(z.co_flag)) report.warning(z, "Missing F4 CO flag").why(sheet("Zones, Supervisory (Latched) - F4 CO"));
                }
            }

            List<Circuit> ga = zones.get(Zone.GENERAL_ALARM), auto = zones.get(Zone.AUTOMATIC_ALARM);
            List<Circuit> nls = zones.get(Zone.NON_LATCHED), latched = zones.get(Zone.LATCHED), wf = zones.get(Zone.WATERFLOW);
            List<Circuit> alarm_zones = new ArrayList<>();
            for (Zone z : new Zone[] { Zone.GENERAL_ALARM, Zone.AUTOMATIC_ALARM, Zone.WATERFLOW, Zone.ELEV_RECALL, Zone.ALT_RECALL }) {
                if (zones.containsKey(z)) alarm_zones.addAll(zones.get(z));
            }

            int alarms = 0, supv = 0, latch = 0, waterflows = 0;
            List<Circuit> no_ga = new ArrayList<>(), no_auto = new ArrayList<>(), no_nls = new ArrayList<>(), no_latched = new ArrayList<>(), no_wf = new ArrayList<>();
            for (Circuit c : devices(job)) {
                Family f = Roles.family(c);
                Device role = Roles.device(c);
                if (f == Family.ALARM) {
                    alarms++;
                    if (ga != null && !inAny(job, c, ga)) no_ga.add(c);
                    if (auto != null && role != Device.PULL && !inAny(job, c, auto)) no_auto.add(c);
                    if (role == Device.PULL && inAny(job, c, auto)) {
                        report.error(c, "Pull station in Automatic Alarm").why(sheet("Zones, Automatic Alarm - all alarm inputs besides pull station"));
                    }
                    if (role == Device.WATERFLOW) {
                        waterflows++;
                        if (wf != null && !inAny(job, c, wf)) no_wf.add(c);
                    }
                    if (inAny(job, c, nls) || inAny(job, c, latched)) report.warning(c, "Alarm input in a supervisory zone");
                } else if (f == Family.SUPERVISORY || f == Family.LATCHED) {
                    if (f == Family.SUPERVISORY) {
                        supv++;
                        if (nls != null && !inAny(job, c, nls)) no_nls.add(c);
                    } else if (role == Device.CO) { //Latched Supervisory zone is for CO detectors
                        latch++;
                        if (latched != null && !inAny(job, c, latched)) no_latched.add(c);
                    }
                    for (Circuit z : job.zonesOf(c)) {
                        if (alarm_zones.contains(z)) report.error(c, "Supervisory input in " + z.cleanTag()).more("Supervisory devices must not set off alarm zones.");
                    }
                }
            }
            missing(report, "General Alarm", ga, alarms, no_ga, "Zones, General Alarm - correlate to all signals and inputs");
            missing(report, "Automatic Alarm", auto, alarms, no_auto, "Zones, Automatic Alarm - all alarm inputs besides pull station");
            missing(report, "Non-Latched Supv", nls, supv, no_nls, "Zones, Supervisory (Non-Latched) - all non-latched devices");
            missing(report, "Latched Supv", latched, latch, no_latched, "Zones, Supervisory (Latched) - for CO detectors");
            missing(report, "Waterflow", wf, waterflows, no_wf, "Zones, Waterflow - for all waterflows");
        }

        private static void missing(Report report, String zone, List<Circuit> zones, int expected, List<Circuit> devices, String sheet) {
            if (zones == null) {
                if (expected > 0) {
                    report.warning(Place.text("Input zones"), "No " + zone + " zone found")
                        .more(Report.plural(expected, "device") + " would belong in it. Zones are found by their tag.").why(sheet(sheet));
                }
                return;
            }
            for (Circuit c : devices) report.error(c, "Not in " + zone).why(sheet(sheet));
        }
    }

    //---------------------------------------------------------------------------------------------------

    public static class Outputs implements Rule {

        public String id() {
            return "SHEET-OUTPUTS";
        }

        public String name() {
            return "Relays & strobes";
        }

        public String title() {
            return "Cheat sheet outputs: strobes, elevator recall, fan/boiler shutdown, door release and other relays";
        }

        public boolean supports(Job.Panel panel) {
            return panel.isReadable();
        }

        public void check(Job job, Report report) {
            SOURCE.set(source(job.panel));
            List<Circuit> alarms = new ArrayList<>(), automatic = new ArrayList<>(), waterflows = new ArrayList<>();
            for (Circuit c : devices(job)) {
                if (Roles.family(c) != Family.ALARM) continue;
                alarms.add(c);
                Device role = Roles.device(c);
                if (role != Device.PULL) automatic.add(c);
                if (role == Device.WATERFLOW) waterflows.add(c);
            }

            Map<Output, Integer> counts = new EnumMap<>(Output.class);
            List<Circuit> mains = new ArrayList<>(), alts = new ArrayList<>(), shaft_lights = new ArrayList<>();
            for (Circuit o : job.outputs.values()) {
                if (o.kind == Kind.OUTPUT_ZONE) continue;
                if (isSignal(o)) noSupervisoryOnSignal(report, o, job.devicesReaching(o));
                if (o.isSpare()) continue;
                Output role = Roles.output(o);
                counts.merge(role, 1, Integer::sum);
                Set<Circuit> reach = job.devicesReaching(o);
                switch (role) {
                    case NOTIFICATION:
                        //NS is for strobes (visual); horn, bell and NAC circuits may silence
                        boolean strobe = o.process_type == 14 || o.words().toLowerCase().contains("strobe");
                        if (strobe && o.silenceable) report.warning(o, "Strobe circuit without NS").why(sheet("Checklist, Loop 0 - strobes are NS"));
                        need(report, o, reach, alarms, "alarm inputs", "Correlations - strobes correlate to all alarms");
                        break;
                    case DOOR_RELEASE:
                        need(report, o, reach, automatic, "alarm inputs", "Correlations - door release gets all alarms");
                        noSupervisory(report, o, reach, false, "Devices, Door Release - correlated to all alarms");
                        break;
                    case MUSIC_SHUTDOWN:
                        need(report, o, reach, alarms, "alarm inputs", "Devices, Music Shutdown - correlate to General Alarm");
                        break;
                    case FAN_SHUTDOWN: {
                        need(report, o, reach, automatic, "automatic alarms", "Correlations - Fan/AC shutdowns get all alarms but not pull stations");
                        List<Circuit> pulls = new ArrayList<>();
                        for (Circuit c : reach) if (Roles.device(c) == Device.PULL) pulls.add(c);
                        if (!pulls.isEmpty()) {
                            report.warning(o, "Activated by " + Report.plural(pulls.size(), "pull station"))
                                .items("Pull stations", pulls).why(sheet("Correlations - Fan/AC shutdowns get all alarms but not pull stations"));
                        }
                        noSupervisory(report, o, reach, false, "Correlations - Fan/AC shutdowns get all alarms");
                        if (!o.aux_reset) report.warning(o, "Missing F3 AR").why(sheet("Devices, AC/Fan Shutdown - F3 AR"));
                        break;
                    }
                    case ELEV_RECALL:
                        mains.add(o);
                        noSupervisory(report, o, reach, false, "Correlations - elev recall gets elevator smokes and waterflows");
                        break;
                    case ALT_RECALL:
                        alts.add(o);
                        noSupervisory(report, o, reach, false, "Correlations - elev recall gets elevator smokes and waterflows");
                        break;
                    case BOILER_SHUTDOWN: {
                        boolean co = false;
                        for (Circuit c : reach) if (Roles.device(c) == Device.CO) co = true;
                        if (!co) report.error(o, "No CO detector activates it").why(sheet("Correlations - Boiler/HWH/Laundry shutdown gets the CO detector(s) in the same room"));
                        break;
                    }
                    case GENERATOR_SHUTDOWN: {
                        boolean heat = false;
                        for (Circuit c : reach) if (Roles.device(c) == Device.HEAT) heat = true;
                        if (!heat) report.warning(o, "No heat detector activates it").why(sheet("Devices, Generator Shutdown - correlate to heat detector in same room"));
                        break;
                    }
                    case SHAFT_LIGHT:
                        shaft_lights.add(o);
                        break;
                    case TROUBLE_TRIP: {
                        boolean trouble = false;
                        for (int[] l : job.status_links) {
                            if (l[1] == o.ckt && job.status_names.getOrDefault(l[0], "").toLowerCase().contains("trouble")) trouble = true;
                        }
                        if (!trouble) report.warning(o, "Not on the Common Trouble status").why(sheet("Devices, Trouble Trip Relay - correlate to Common Trouble Status"));
                        break;
                    }
                    default:
                }
            }

            elevatorRecall(job, report, waterflows, automatic, mains, alts);
            shaftLights(job, report, shaft_lights);

            List<String> parts = new ArrayList<>();
            for (Map.Entry<Output, Integer> e : counts.entrySet()) {
                if (e.getKey() != Output.OTHER) parts.add(e.getValue() + " " + e.getKey().label);
            }
            report.note("Outputs recognised: " + (parts.isEmpty() ? "none" : String.join(", ", parts)));
        }

        //Elevator recall. Waterflows on 1FL and below (cellar, sub-cellar, basement) go to the alternate recall when
        //there is one, everything else to the main recall (main recall relays and shunt trips). With several recalls of
        //a kind (towers, car lifts) each device needs at least one of them, since "if there are multiple recalls,
        //correlate only to their associated devices".
        private static void elevatorRecall(Job job, Report report, List<Circuit> waterflows, List<Circuit> automatic,
                                           List<Circuit> mains, List<Circuit> alts) {
            if (mains.isEmpty() && alts.isEmpty()) return;
            String why_main = sheet("Correlations - elev recall/shunt trip relays get all waterflows; main recalls get everything but 1FL");
            String why_alt = sheet("Correlations - alt recalls get 1FL and below (the alternate recall floor)");

            List<Circuit> any_recall = new ArrayList<>(mains);
            any_recall.addAll(alts);
            //Only the waterflow -> recall direction is checked. A recall relay without a waterflow is normal: not every
            //job has waterflows, or one on the floors that recall belongs to.
            boolean only_one = waterflows.size() == 1;
            for (Circuit w : waterflows) {
                boolean first = Roles.firstFloor(w), below = Roles.belowGrade(w);
                boolean alt_floor = first || below;
                String floor = first ? "1FL waterflow" : below ? "Below-grade waterflow" : "Waterflow";
                boolean to_alt = alt_floor && !alts.isEmpty();
                List<Circuit> want = to_alt ? alts : mains;
                if (want.isEmpty()) continue;
                boolean on_main = reachesAny(job, w, mains);
                if (!reachesAny(job, w, want)) {
                    String which = to_alt ? "alt elevator recall" : "main elevator recall";
                    if (to_alt && on_main && only_one) {
                        //The building's only waterflow is often the main riser and may be meant for the main recall
                        report.warning(w, "Only waterflow is on the main recall, not the alt")
                            .more("It is on " + (first ? "1FL" : "a below-grade floor") + ", which normally goes to the alternate recall. "
                                + "As the job's only waterflow it may be meant for the main recall; accept it if so.")
                            .items("Alt elevator recall relays", alts).why(why_alt);
                    } else {
                        report.error(w, floor + " not on " + which)
                            .items("Expected " + (want.size() == 1 ? "" : "one of the ") + which + " relays", want).why(to_alt ? why_alt : why_main);
                    }
                } else if (to_alt && on_main) {
                    report.warning(w, floor + " also on the main recall")
                        .more("Main recalls normally get everything but 1FL and below." + (only_one ? " It is the job's only waterflow, so this may be intended." : ""))
                        .why(why_main);
                }
                if (!alt_floor && reachesAny(job, w, alts)) {
                    report.warning(w, "Above 1FL, but on alt elevator recall").why(why_alt);
                }
            }

            //Elevator lobby / EMR / top of shaft smokes and heats: any recall will do (car lifts, towers and the alt floor
            //each have their own), but they must recall something
            for (Circuit c : automatic) {
                Device role = Roles.device(c);
                if ((role != Device.SMOKE && role != Device.HEAT) || !Roles.elevatorRelated(c)) continue;
                if (reachesAny(job, c, any_recall)) continue;
                report.warning(c, "Elevator detector not on any elevator recall")
                    .more("Its tag mentions an elevator lobby, EMR or shaft.")
                    .why(sheet("Correlations - elev recall relays get elev lobby smokes, top of shaft smokes and smokes in the EMR"));
            }
        }

        //Shaft light relays follow elevator recall (main and alt) on their node
        private static void shaftLights(Job job, Report report, List<Circuit> shaft_lights) {
            if (shaft_lights.isEmpty()) return;
            Map<Zone, List<Circuit>> zones = zonesByRole(job);
            List<Circuit> recall_zones = new ArrayList<>();
            if (zones.containsKey(Zone.ELEV_RECALL)) recall_zones.addAll(zones.get(Zone.ELEV_RECALL));
            if (zones.containsKey(Zone.ALT_RECALL)) recall_zones.addAll(zones.get(Zone.ALT_RECALL));
            for (Circuit light : shaft_lights) {
                List<Circuit> missing = new ArrayList<>();
                for (Circuit z : recall_zones) if (z.node == light.node && !job.directInputs(light).contains(z)) missing.add(z);
                if (!missing.isEmpty()) {
                    report.warning(light, "Not on " + Report.plural(missing.size(), "elevator recall zone"))
                        .items("Recall zones it is not correlated to", missing)
                        .why(sheet("Devices, FSAE Shaft Lights - correlate to elevator recall (including alt)"));
                }
            }
        }

        //One finding per output listing the required devices that do not activate it
        private static void need(Report report, Circuit output, Set<Circuit> reach, List<Circuit> required, String what, String sheet) {
            Set<Circuit> missing = new LinkedHashSet<>();
            for (Circuit c : required) if (!reach.contains(c)) missing.add(c);
            if (missing.isEmpty()) return;
            String summary = missing.size() == required.size() ? "None of the " + required.size() + " " + what + " activate it"
                : "Missing " + missing.size() + " of " + required.size() + " " + what;
            report.error(output, summary).items("Not activating it", missing).why(sheet(sheet));
        }

        //Signal and strobe circuits (the configurator's signal class), whatever their tag says: sync, signal silence
        //and spare signals too, since a spare one sounds as soon as something is wired to it
        static boolean isSignal(Circuit o) {
            return o.corr_class == 8 || (o.corr_class < 0 && (o.process_type == 13 || o.process_type == 14));
        }

        private static final Pattern CO_SIGNAL = Pattern.compile("\\bco\\b|amber|carbon");
        private static final Pattern SUPERVISORY_SIGNAL = Pattern.compile("supv|supervis");

        //No supervisory input may set off a signal or strobe, on every panel. Left alone on purpose: sounder bases
        //(in-unit combo smoke/CO detectors are set up as supervisory and sound their own base), CO detectors on CO
        //(amber) strobes, and signals tagged as supervisory bells.
        private static void noSupervisoryOnSignal(Report report, Circuit o, Set<Circuit> reach) {
            if (Roles.output(o) == Output.SOUNDER) return;
            String words = o.words().toLowerCase();
            if (SUPERVISORY_SIGNAL.matcher(words).find()) return;
            boolean co_signal = CO_SIGNAL.matcher(words).find();
            List<Circuit> supv = new ArrayList<>();
            for (Circuit c : reach) {
                Family f = Roles.family(c);
                if (f != Family.SUPERVISORY && f != Family.LATCHED) continue;
                if (co_signal && Roles.device(c) == Device.CO) continue;
                supv.add(c);
            }
            if (supv.isEmpty()) return;
            String what = o.process_type == 14 ? "strobe" : "signal";
            boolean spare = o.isSpare();
            report.error(o, (spare ? "Spare " + what : Character.toUpperCase(what.charAt(0)) + what.substring(1)) + " activated by "
                    + Report.plural(supv.size(), "supervisory input"))
                .more(spare ? "It is spare now, but would sound on a supervisory as soon as something is wired to it." : "")
                .items("Supervisory inputs", supv).why(sheet("Correlations - do not correlate any supervisory to strobes / signals"));
        }

        private static void noSupervisory(Report report, Circuit output, Set<Circuit> reach, boolean error, String sheet) {
            List<Circuit> supv = new ArrayList<>();
            for (Circuit c : reach) {
                Family f = Roles.family(c);
                if (f == Family.SUPERVISORY || f == Family.LATCHED) supv.add(c);
            }
            if (supv.isEmpty()) return;
            String summary = "Activated by " + Report.plural(supv.size(), "supervisory input");
            (error ? report.error(output, summary) : report.warning(output, summary)).items("Supervisory inputs", supv).why(sheet(sheet));
        }
    }

    //---------------------------------------------------------------------------------------------------

    public static class Bypass implements Rule {

        public String id() {
            return "SHEET-BYPASS";
        }

        public String name() {
            return "Zone bypass";
        }

        public String title() {
            return "Cheat sheet zone bypass: every relay, signal and strobe is on the Mixed / Zone Bypass switch";
        }

        //FX400 has no Zone Bypass switch; its bypass groups are checked by Fx400Bypass
        public boolean supports(Job.Panel panel) {
            return panel.isReadable() && panel != Job.Panel.FX400;
        }

        public void check(Job job, Report report) {
            SOURCE.set(source(job.panel));
            List<Job.ControlGroup> groups = job.bypassGroups();
            List<Circuit> outputs = new ArrayList<>();
            for (Circuit o : job.outputs.values()) {
                if (o.isSpare() || o.kind == Kind.OUTPUT_ZONE) continue;
                if (o.process_type == 13 || o.process_type == 14 || o.process_type == 15) outputs.add(o);
            }
            String why = sheet("Correlations - correlate all relays to Zone Bypass; Zone Bypass - all relays, signals, strobes");
            if (groups.isEmpty()) {
                if (!outputs.isEmpty()) report.error(Place.text("Base Control"), "No Zone Bypass switch").why(why);
                return;
            }
            Set<Circuit> bypassed = new LinkedHashSet<>();
            for (Job.ControlGroup g : groups) {
                bypassed.addAll(g.outputs);
                report.note(g.label() + ": " + Report.plural(g.outputs.size(), "output"));
            }
            for (Circuit o : outputs) if (!bypassed.contains(o)) report.warning(o, "Not on Zone Bypass").why(why);
        }
    }

    //---------------------------------------------------------------------------------------------------

    //FX400 checklist, Bypass Group: one group with all relays and signals, one with only relays, one with only
    //signals, one with only elevator recalls. (FX400 has bypass groups instead of the Zone Bypass switch.)
    public static class Fx400Bypass implements Rule {

        public String id() {
            return "FX400-BYPASS";
        }

        public String name() {
            return "Bypass groups";
        }

        public String title() {
            return "FX-400 checklist bypass groups: all relays and signals, only relays, only signals, only elevator recalls";
        }

        public boolean supports(Job.Panel panel) {
            return panel == Job.Panel.FX400;
        }

        public void check(Job job, Report report) {
            List<Job.ControlGroup> groups = job.bypassGroups();
            List<Circuit> outputs = new ArrayList<>();
            for (Circuit o : job.outputs.values()) {
                if (o.isSpare() || o.kind == Kind.OUTPUT_ZONE) continue;
                if (o.process_type == 13 || o.process_type == 14 || o.process_type == 15) outputs.add(o);
            }
            String why = "FX-400 checklist, Bypass Group: groups for all relays and signals, only relays, only signals, only elevator recalls";
            if (groups.isEmpty()) {
                //Many existing FX400 jobs predate the bypass group checklist, so it is a warning
                if (!outputs.isEmpty()) report.warning(Place.text("Bypass groups"), "No bypass groups").why(why);
                return;
            }
            for (Job.ControlGroup g : groups) report.note(g.label() + ": " + Report.plural(g.outputs.size(), "output"));
            List<Circuit> relays = new ArrayList<>(), signals = new ArrayList<>(), recalls = new ArrayList<>();
            for (Circuit o : outputs) {
                if (o.process_type == 15) relays.add(o);
                else signals.add(o);
                Output role = Roles.output(o);
                if (role == Output.ELEV_RECALL || role == Output.ALT_RECALL) recalls.add(o);
            }
            boolean all = false, only_relays = false, only_signals = false, only_recalls = false;
            for (Job.ControlGroup g : groups) {
                if (g.outputs.isEmpty()) continue;
                if (g.outputs.containsAll(relays) && g.outputs.containsAll(signals)) all = true;
                //What kind of group it is goes by everything in it, spares included (a spare signal makes it a mixed group)
                boolean relays_only = true, signals_only = true, recalls_only = true;
                for (Circuit c : g.outputs) {
                    if (c.process_type != 13 && c.process_type != 14 && c.process_type != 15) continue;
                    if (c.process_type == 15) signals_only = false;
                    else relays_only = false;
                    if (!recalls.contains(c)) recalls_only = false;
                }
                if (relays_only) only_relays = true;
                if (signals_only) only_signals = true;
                if (recalls_only) only_recalls = true;
            }
            if (!all) {
                //The relays and signals that the fullest group leaves out
                Job.ControlGroup fullest = null;
                for (Job.ControlGroup g : groups) if (fullest == null || g.outputs.size() > fullest.outputs.size()) fullest = g;
                List<Circuit> left_out = new ArrayList<>(outputs);
                if (fullest != null) left_out.removeAll(fullest.outputs);
                report.warning(Place.text("Bypass groups"), "No bypass group with all relays and signals").why(why)
                    .items(fullest == null || fullest.outputs.isEmpty() ? "Relays and signals" : "Not in " + fullest.label(), left_out);
            }
            if (!only_relays && !relays.isEmpty()) report.warning(Place.text("Bypass groups"), "No bypass group with only relays").why(why);
            if (!only_signals && !signals.isEmpty()) report.warning(Place.text("Bypass groups"), "No bypass group with only signals").why(why);
            if (!only_recalls && !recalls.isEmpty()) report.warning(Place.text("Bypass groups"), "No bypass group with only elevator recalls").why(why);
        }
    }
}
