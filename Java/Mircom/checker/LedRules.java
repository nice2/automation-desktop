package checker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import checker.Job.Circuit;
import checker.Job.Led;

//Checks from the "Correcting LEDs" guide. The LEDs tab fixes what these find (into a new copy of the job).
public final class LedRules {

    private LedRules() {
    }

    static final String GUIDE = "Correcting LEDs guide";

    //---------------------------------------------------------------------------------------------------

    public static class FsaeColors implements Rule {

        public String id() {
            return "LED-FSAE";
        }

        public String name() {
            return "FSAE LED colors";
        }

        public String title() {
            return "FSAE graphic adder LEDs: steady, in the job's color plan (LEDs tab)";
        }

        public boolean supports(Job.Panel panel) {
            return panel == Job.Panel.FX4000 || panel == Job.Panel.FX6000;
        }

        public void check(Job job, Report report) {
            List<Leds.FsaeLed> list = Leds.fsaeLeds(job);
            if (list.isEmpty()) return;
            Leds.ColorPlan plan = Leds.plan(job);
            report.note(Report.plural(list.size(), "FSAE graphic adder LED") + ", " + (plan.saved ? "color plan saved for this job" : "default colors (no color plan saved for this job)"));

            //One finding per kind of LED and wrong state, e.g. "142 Normal LEDs: Yellow Slow Flash, should be Green Continuous"
            Map<String, List<Led>> wrong = new LinkedHashMap<>();
            Map<String, Leds.FsaeKind> kinds = new LinkedHashMap<>();
            Map<Integer, Boolean> seen = new LinkedHashMap<>();
            for (Leds.FsaeLed f : list) {
                if (seen.put(f.led.group, true) != null) continue; //the state belongs to the LED group, shown on several annunciators
                int now = f.led.someActive(), want = plan.expected(f);
                if (now == want) continue;
                String key = f.kind.label + "|" + Leds.stateName(job, now) + "|" + Leds.stateName(job, want);
                wrong.computeIfAbsent(key, k -> new ArrayList<>()).add(f.led);
                kinds.put(key, f.kind);
            }
            for (Map.Entry<String, List<Led>> e : wrong.entrySet()) {
                String[] k = e.getKey().split("\\|");
                List<Led> leds = e.getValue();
                String summary = (leds.size() == 1 ? "LED" : Report.plural(leds.size(), "LED")) + " " + k[1] + ", should be " + k[2];
                Object subject = leds.size() == 1 ? leds.get(0) : Place.text(k[0] + " LEDs");
                Report.Finding f = plan.saved ? report.error(subject, summary) : report.warning(subject, summary);
                f.items(k[0] + " LEDs", leds).why(GUIDE + ", FSAE: set each graphic adder LED to a continuous color, so it does not blink")
                    .more(plan.saved ? "Colors from this job's color plan (LEDs tab)." : "Default color for " + k[0] + " LEDs; set this job's colors in the LEDs tab if the FSAE plan differs.");
            }
        }
    }

    //---------------------------------------------------------------------------------------------------

    public static class Paging implements Rule {

        public String id() {
            return "LED-PAGING";
        }

        public String name() {
            return "Page select LEDs";
        }

        public String title() {
            return "Page select buttons: red/green LEDs not linked to the shared amplifier (FX6000)";
        }

        public boolean supports(Job.Panel panel) {
            return panel == Job.Panel.FX6000;
        }

        public void check(Job job, Report report) {
            List<Leds.PageButton> buttons = Leds.pageButtons(job);
            if (buttons.isEmpty()) return;
            List<Led> wrong = new ArrayList<>();
            List<Circuit> amps = new ArrayList<>();
            int amp_only = 0;
            for (Leds.PageButton b : buttons) {
                if (b.own.isEmpty()) amp_only++;
                if (!b.needsFix()) continue;
                wrong.add(b.red);
                for (Circuit c : b.amps_red) if (!amps.contains(c)) amps.add(c);
                for (Circuit c : b.amps_green) if (!amps.contains(c)) amps.add(c);
            }
            report.note(Report.plural(buttons.size(), "page select button") + (amp_only > 0 ? ", " + amp_only + " with only an amplifier circuit (left as they are)" : ""));
            if (wrong.isEmpty()) return;
            Object subject = wrong.size() == 1 ? wrong.get(0) : Place.text("Page select buttons");
            report.error(subject, (wrong.size() == 1 ? "Lights" : wrong.size() + " buttons light") + " from the shared amplifier")
                .items("Buttons whose red/green LEDs list an amplifier circuit", wrong)
                .more("Amplifier circuits: " + names(amps) + ". Paging any floor lights every one of these buttons; the LEDs tab removes the amplifier from their red and green LEDs (the yellow trouble LED keeps it).")
                .why(GUIDE + ", Speakers / paging buttons (FX6000 only)");
        }
    }

    //---------------------------------------------------------------------------------------------------

    public static class Hoa implements Rule {

        public String id() {
            return "LED-HOA";
        }

        public String name() {
            return "HOA switch LEDs";
        }

        public String title() {
            return "HOA switches: red/green LEDs show the RED/GREEN zones, yellow the proving group (FX6000)";
        }

        public boolean supports(Job.Panel panel) {
            return panel == Job.Panel.FX6000;
        }

        public void check(Job job, Report report) {
            List<Leds.HoaSwitch> switches = Leds.hoaSwitches(job);
            if (switches.isEmpty()) return;
            int complete = 0, unmatched = 0;
            for (Leds.HoaSwitch s : switches) {
                if (s.complete()) complete++;
                List<String> missing = new ArrayList<>();
                List<Object> found = new ArrayList<>();
                if (s.red.inputs.isEmpty() && s.red_zone != null) {
                    missing.add("red");
                    found.add(s.red_zone);
                }
                if (s.green.inputs.isEmpty() && s.green_zone != null) {
                    missing.add("green");
                    found.add(s.green_zone);
                }
                if (s.yellow.provings.isEmpty() && s.proving != null) {
                    missing.add("yellow");
                    Job.Proving p = job.provings.get(s.proving);
                    found.add(p != null && p.zone != null ? p.zone : Place.text("Proving group " + s.proving));
                }
                if (!missing.isEmpty()) {
                    report.warning(s.red, "No " + String.join("/", missing) + " LED link; matching zones exist")
                        .items("Zones its tag matches", found).why(GUIDE + ", HOA switches: red = RED zone, green = GREEN zone, yellow = proving group");
                } else if (!s.complete()) {
                    unmatched++;
                }
                wrongLink(job, report, s, s.red, "Red", "red");
                wrongLink(job, report, s, s.green, "Green", "green");
            }
            report.note(complete + " of " + switches.size() + (switches.size() == 1 ? " HOA switch" : " HOA switches") + " fully linked"
                + (unmatched > 0 ? "; " + unmatched + " not linked and no RED/GREEN zone or proving group matches their tag" : ""));
        }

        //A link to a zone whose tag names another stair/fan than the switch, while a zone with the switch's name exists
        private static void wrongLink(Job job, Report report, Leds.HoaSwitch s, Led l, String label, String color) {
            if (l.inputs.size() != 1) return;
            Circuit linked = l.inputs.get(0);
            if (Leds.match(s.name(), linked.words()) > 0) return;
            Circuit better = Leds.suggestZone(job, s.name(), color);
            if (better == null || better == linked) return;
            report.warning(l, label + " LED shows " + linked.cleanTag() + ", tag matches " + better.cleanTag())
                .items("Linked, then the zone its tag matches", List.of(linked, better));
        }
    }

    static String names(List<Circuit> list) {
        List<String> n = new ArrayList<>();
        for (Circuit c : list) n.add(c.cleanTag());
        return String.join(", ", n);
    }
}
