package checker;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import checker.Job.Circuit;
import checker.Job.Kind;
import checker.Roles.Device;
import checker.Roles.Output;

//What the checker could not work out from tags. The other checks decide what a device is (waterflow, pull station,
//fan shutdown relay, 1FL, ...) from its tag, so an unclear or mistyped tag means a device is quietly left out of
//them. This lists those devices so they can be fixed in the job, or reported as a missing word for the checker.
//Findings are informational: shown when this check is selected (or Info is ticked), not counted as problems.
public final class TagRules {

    private TagRules() {}

    public static class Unrecognised implements Rule {

        public String id() {
            return "TAGS";
        }

        public String name() {
            return "Unrecognised tags";
        }

        public String title() {
            return "Devices and outputs the checker cannot identify from their tag, so the other checks skip them";
        }

        public boolean supports(Job.Panel panel) {
            return panel.isReadable();
        }

        public void check(Job job, Report report) {
            Map<Device, Integer> devices = new EnumMap<>(Device.class);
            int unknown_devices = 0, unknown_outputs = 0, no_floor = 0;
            for (Circuit c : CheatSheetRules.devices(job)) {
                Device role = Roles.device(c);
                devices.merge(role, 1, Integer::sum);
                if (role == Device.OTHER) {
                    unknown_devices++;
                    report.info(c, "Can't tell what this device is from its tag")
                        .more("Checks that depend on the kind of device (pull station, waterflow, CO, elevator recall) skip it; "
                            + "only its input type (" + c.process_name + ") is used.");
                }
                if (role == Device.WATERFLOW && !Roles.hasFloor(c)) {
                    no_floor++;
                    report.info(c, "No floor in this waterflow's tag")
                        .more("The elevator recall check needs the floor (1FL and below go to the alternate recall). It is treated as above 1FL.");
                }
            }
            Map<Output, Integer> outputs = new EnumMap<>(Output.class);
            for (Circuit o : job.outputs.values()) {
                if (o.isSpare() || o.kind == Kind.OUTPUT_ZONE) continue;
                if (o.process_type != 13 && o.process_type != 14 && o.process_type != 15) continue;
                Output role = Roles.output(o);
                outputs.merge(role, 1, Integer::sum);
                if (role == Output.OTHER && !Roles.knownOther(o)) {
                    unknown_outputs++;
                    report.info(o, "Can't tell what this output is from its tag")
                        .more("The relay and strobe checks (recall, fan shutdown, door release, strobes...) skip it.");
                }
            }
            report.note(summary("Devices", devices, Device.OTHER) + "; " + unknown_devices + " not recognised");
            report.note(summary("Outputs", outputs, Output.OTHER) + "; " + unknown_outputs + " not recognised");
            if (no_floor > 0) report.note(Report.plural(no_floor, "waterflow") + " without a floor in the tag");
        }

        private static <E extends Enum<E>> String summary(String what, Map<E, Integer> counts, E other) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<E, Integer> e : counts.entrySet()) {
                if (e.getKey() == other) continue;
                String label = e.getKey() instanceof Device ? ((Device) e.getKey()).label : ((Output) e.getKey()).label;
                parts.add(e.getValue() + " " + label);
            }
            return what + " recognised: " + (parts.isEmpty() ? "none" : String.join(", ", parts));
        }
    }
}
