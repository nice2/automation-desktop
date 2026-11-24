package checker;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import checker.Job.Circuit;
import checker.Job.Kind;

//Panel-wide checks that apply to every job, not just FSAE.
public final class LinkRules {

    private LinkRules() {}

    //Every link row must point at things that exist and are the right kind
    public static class Integrity implements Rule {

        public String id() {
            return "LINKS";
        }

        public String name() {
            return "Link integrity";
        }

        public String title() {
            return "Correlation rows point at real circuits, LEDs and zones";
        }

        public boolean supports(Job.Panel panel) {
            return panel.isReadable();
        }

        public void check(Job job, Report report) {
            duplicates(job.zone_links, "Device-zone link", report);
            duplicates(job.io_links, "Input-output link", report);
            duplicates(job.led_input_links, "LED-input link", report);
            duplicates(job.led_output_links, "LED-output link", report);

            for (int[] l : job.zone_links) {
                Circuit src = job.inputs.get(l[0]);
                Circuit zone = job.inputs.get(l[1]);
                if (src == null) report.error(Place.text("Device-zone link " + l[0] + " > " + l[1]), "Links from input circuit " + l[0] + ", which does not exist");
                if (zone == null) report.error(src != null ? src : Place.text("Device-zone link " + l[0] + " > " + l[1]), "Links to input zone circuit " + l[1] + ", which does not exist");
                if (src == null || zone == null) continue;
                if (zone.kind != Kind.INPUT_ZONE) report.error(src, "Linked as an input zone to something that is not one: " + Place.brief(zone));
                if (src.kind == Kind.INPUT_ZONE) report.warning(src, "Input zone linked into another input zone: " + Place.brief(zone));
            }
            for (int[] l : job.io_links) {
                Circuit in = job.inputs.get(l[0]);
                if (in == null) report.error(Place.text("Input-output link " + l[0] + " > " + l[1]), "Links from input circuit " + l[0] + ", which does not exist");
                if (!job.outputs.containsKey(l[1])) report.error(in != null ? in : Place.text("Input-output link"), "Links to output circuit " + l[1] + ", which does not exist");
            }
            for (int[] l : job.led_input_links) {
                if (!job.leds.containsKey(l[0])) report.error(Place.text("LED group " + l[0]), "LED group does not exist (linked to input " + l[1] + ")");
                if (!job.inputs.containsKey(l[1])) report.error(job.leds.containsKey(l[0]) ? job.leds.get(l[0]) : Place.text("LED group " + l[0]), "Linked to input circuit " + l[1] + ", which does not exist");
            }
            for (int[] l : job.led_output_links) {
                if (!job.leds.containsKey(l[0])) report.error(Place.text("LED group " + l[0]), "LED group does not exist (linked to output " + l[1] + ")");
                if (!job.outputs.containsKey(l[1])) report.error(job.leds.containsKey(l[0]) ? job.leds.get(l[0]) : Place.text("LED group " + l[0]), "Linked to output circuit " + l[1] + ", which does not exist");
            }
            for (Job.Led led : job.leds.values()) {
                if (led.slots.isEmpty()) report.warning(led, "LED defined but not placed on any display");
            }
            for (Job.Equation e : job.equations.values()) {
                for (Map.Entry<Integer, Integer> in : e.inputs.entrySet()) {
                    if (!job.inputs.containsKey(in.getValue())) report.error(e, "Logic refers to input circuit " + in.getValue() + ", which does not exist");
                }
            }
            report.note(job.summary());
        }

        private static void duplicates(List<int[]> links, String what, Report report) {
            Map<String, Integer> seen = new HashMap<>();
            for (int[] l : links) seen.merge(l[0] + " > " + l[1], 1, Integer::sum);
            for (Map.Entry<String, Integer> e : seen.entrySet()) {
                if (e.getValue() > 1) report.warning(Place.text(what + " " + e.getKey()), "Listed " + e.getValue() + " times");
            }
        }
    }

    //Things that are correlated to nothing, so they do nothing (device inputs) or never turn on (input zones)
    public static class Coverage implements Rule {

        //Telephone riser lines, amplifiers and display switches are not expected to be correlated
        private static final Set<String> SKIPPED_LOOPS = Set.of("Telephone", "Amplifiers", "Switches");

        public String id() {
            return "COVERAGE";
        }

        public String name() {
            return "Unused devices & zones";
        }

        public String title() {
            return "Every device input and input zone is correlated to something";
        }

        public boolean supports(Job.Panel panel) {
            return panel.isReadable();
        }

        public void check(Job job, Report report) {
            Set<Integer> used_inputs = new HashSet<>();
            for (int[] l : job.io_links) used_inputs.add(l[0]);
            for (Job.Equation e : job.equations.values()) used_inputs.addAll(e.inputs.values());

            int devices = 0, zones = 0, placeholders = 0;
            for (Circuit c : job.inputs.values()) {
                boolean linked = !job.zonesOf(c).isEmpty() || used_inputs.contains(c.ckt) || !job.ledsOf(c).isEmpty();
                if (c.kind == Kind.DEVICE) {
                    if (SKIPPED_LOOPS.contains(c.loop_type) || c.isSpare()) continue;
                    devices++;
                    if (linked) continue;
                    if (job.panel.hasZones()) {
                        report.warning(c, "Not correlated to anything").more("Not in any input zone, output, LED or logic, so activating it does nothing.");
                    } else if (Roles.family(c) == Roles.Family.ALARM) {
                        //FX400/FX2000: supervisory inputs only report, but an alarm input should set off strobes and relays
                        report.warning(c, "Alarm input not correlated to anything").more("It activates no output or LED. Strobes correlate to all alarms.");
                    }
                } else if (c.kind == Kind.INPUT_ZONE) {
                    if (c.isPlaceholder()) {
                        placeholders++;
                        continue;
                    }
                    zones++;
                    if (job.sourcesOf(c).isEmpty() && job.equationsDriving(c).isEmpty()) {
                        report.warning(c, "Never activates").more("No device is in this input zone and no logic drives it.");
                    }
                }
            }
            report.note(devices + " device inputs and " + zones + " input zones checked");
            if (placeholders > 0) {
                report.note(Report.plural(placeholders, "blank / XXXX separator input zone") + " skipped (checklist: delete empty input zones before sending)");
            }
        }
    }
}
