package checker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import checker.Job.Circuit;
import checker.Job.Kind;
import checker.Job.Led;
import checker.Job.Panel;

//Correlation editing: the link types that can be changed, the configurators' rules for which links are allowed,
//and the pending changes (added / removed links) for one job version.
//
//The rules mirror what the configurators themselves accept, worked out from their lookup tables (SYSProcTypes /
//SYSLedTypes correlation classes), their own error messages, and every saved job on this PC (2026-11-18):
//  FX4000 / FX6000: a device input goes only into an input zone on its own node (FX6000: own CPU) of the same class
//    (alarm, supervisory, trouble, monitor); a zone driven by a logic equation takes no devices; outputs are turned on
//    by input zones or correlatable switch inputs, never straight by a device, and may be on another node;
//    an input zone LED only shows input zones of its class (Mixed shows any).
//  FX400 / FX2000: an input (device or panel circuit) turns outputs on directly; hazard zones, output zones and
//    equipment sets keep their own correlations.
//Anything else (switch LEDs, page select, status correlations, logic, output zones) stays read-only here.
public final class Correlations {

    private Correlations() {
    }

    //-------------------------------------------------------------------------------------------------
    //Link types

    public enum Type {
        //a = device input ckt, b = input zone ckt
        DEVICE_ZONE("Device > input zone", "IptZoneCorrs", "IptCkt", "IptZone"),
        //a = input or input zone ckt, b = output ckt
        INPUT_OUTPUT("Input > output", "IOCorrs", "IptCkt", "OptCkt"),
        //a = LED group, b = input zone ckt
        LED_INPUT("LED > input zone", "LedIpts", "LedGrp", "IptCkt");

        public final String label, table, column_a, column_b;

        Type(String label, String table, String column_a, String column_b) {
            this.label = label;
            this.table = table;
            this.column_a = column_a;
            this.column_b = column_b;
        }
    }

    //Whether this panel's configurator correlations of this type can be edited here
    public static boolean editable(Panel panel, Type type) {
        switch (type) {
            case DEVICE_ZONE: return panel.hasZones();
            case INPUT_OUTPUT: return panel.isReadable();
            //Input zone LEDs: only checked against FX4000/FX6000 jobs (FX400/FX2000 jobs on this PC have none to learn from)
            case LED_INPUT: return panel.hasZones();
            default: return false;
        }
    }

    public static final class Link {
        public final Type type;
        public final int a, b;

        public Link(Type type, int a, int b) {
            this.type = type;
            this.a = a;
            this.b = b;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Link)) return false;
            Link l = (Link) o;
            return l.type == type && l.a == a && l.b == b;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, a, b);
        }

        //Readable form, e.g. Device > input zone: Node 1 > Loop 1 > 109.2 "Low Heat..." > Node 1 > IZ 61 "Low Heat 13FL"
        public String text(Job job) {
            Object x = end(job, true), y = end(job, false);
            return type.label + ":  " + (x == null ? "#" + a : Place.of(job, x).brief()) + "   >   " + (y == null ? "#" + b : Place.of(job, y).brief());
        }

        //The thing at one end (a Circuit or an Led), or null if it is not in the job
        public Object end(Job job, boolean side_a) {
            int n = side_a ? a : b;
            switch (type) {
                case DEVICE_ZONE: return job.inputs.get(n);
                case INPUT_OUTPUT: return side_a ? job.inputs.get(n) : job.outputs.get(n);
                case LED_INPUT: return side_a ? job.leds.get(n) : job.inputs.get(n);
                default: return null;
            }
        }
    }

    //Every link of the editable types that is in the job as loaded
    public static Set<Link> existing(Job job) {
        Set<Link> set = new LinkedHashSet<>();
        for (int[] l : job.zone_links) set.add(new Link(Type.DEVICE_ZONE, l[0], l[1]));
        for (int[] l : job.io_links) set.add(new Link(Type.INPUT_OUTPUT, l[0], l[1]));
        for (int[] l : job.led_input_links) set.add(new Link(Type.LED_INPUT, l[0], l[1]));
        return set;
    }

    //-------------------------------------------------------------------------------------------------
    //Pending changes for one job version

    public static final class Edits {
        public final Job job;
        private final Set<Link> before;
        public final Set<Link> added = new LinkedHashSet<>();
        public final Set<Link> removed = new LinkedHashSet<>();

        //Links by the circuit / LED at either end (identity), including added ones; removed ones stay listed
        private final java.util.IdentityHashMap<Object, List<Link>> by_item = new java.util.IdentityHashMap<>();

        public Edits(Job job) {
            this.job = job;
            this.before = existing(job);
            for (Link l : before) index(l);
        }

        private void index(Link l) {
            for (boolean side : new boolean[] { true, false }) {
                Object o = l.end(job, side);
                if (o == null) continue;
                List<Link> list = by_item.computeIfAbsent(o, k -> new ArrayList<>());
                if (!list.contains(l)) list.add(l);
            }
        }

        public boolean wasLinked(Link l) {
            return before.contains(l);
        }

        //Linked once the pending changes are made
        public boolean linked(Link l) {
            return added.contains(l) || (before.contains(l) && !removed.contains(l));
        }

        public void add(Link l) {
            if (removed.remove(l)) return;
            if (!before.contains(l) && added.add(l)) index(l);
        }

        public void remove(Link l) {
            if (added.remove(l)) return;
            if (before.contains(l)) removed.add(l);
        }

        //Puts one link back the way it was loaded
        public void undo(Link l) {
            added.remove(l);
            removed.remove(l);
        }

        public int size() {
            return added.size() + removed.size();
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public void clear() {
            added.clear();
            removed.clear();
        }

        //Every link touching this circuit or LED once the changes are made, plus the removed ones (to show them)
        public List<Link> linksOf(Object item) {
            List<Link> list = new ArrayList<>();
            for (Link l : by_item.getOrDefault(item, List.of())) if (before.contains(l) || added.contains(l)) list.add(l);
            return list;
        }

        public int countLinks(Object item) {
            int n = 0;
            for (Link l : by_item.getOrDefault(item, List.of())) if (linked(l)) n++;
            return n;
        }
    }

    //-------------------------------------------------------------------------------------------------
    //What can be correlated to what

    //One way an item can be correlated, as the configurator offers it, e.g. for an input zone "Outputs it turns on"
    public static final class Role {
        public final Type type;
        public final boolean item_is_a; //the selected item is end a of the link
        public final String label;

        Role(Type type, boolean item_is_a, String label) {
            this.type = type;
            this.item_is_a = item_is_a;
            this.label = label;
        }

        public Link link(Object item, Object other) {
            Object a = item_is_a ? item : other, b = item_is_a ? other : item;
            return new Link(type, id(a), id(b));
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static int id(Object o) {
        return o instanceof Circuit ? ((Circuit) o).ckt : ((Led) o).group;
    }

    //The kinds of correlation that can be added to this item on its panel
    public static List<Role> roles(Job job, Object item) {
        List<Role> list = new ArrayList<>();
        Panel p = job.panel;
        if (item instanceof Led) {
            if (editable(p, Type.LED_INPUT) && isZoneLed((Led) item)) list.add(new Role(Type.LED_INPUT, true, "Input zones this LED shows"));
            return list;
        }
        Circuit c = (Circuit) item;
        if (c.output) {
            if (c.kind != Kind.OUTPUT_ZONE) list.add(new Role(Type.INPUT_OUTPUT, false, p.hasZones() ? "Input zones that turn it on" : "Inputs that turn it on"));
            return list;
        }
        switch (c.kind) {
            case INPUT_ZONE:
                list.add(new Role(Type.DEVICE_ZONE, false, "Devices in this zone"));
                list.add(new Role(Type.INPUT_OUTPUT, true, "Outputs this zone turns on"));
                if (editable(p, Type.LED_INPUT)) list.add(new Role(Type.LED_INPUT, false, "LEDs that show this zone"));
                break;
            case DEVICE:
                if (p.hasZones()) list.add(new Role(Type.DEVICE_ZONE, true, "Input zones it goes into"));
                else list.add(new Role(Type.INPUT_OUTPUT, true, "Outputs it turns on"));
                break;
            case PANEL:
                if (!p.hasZones() || isSwitchInput(c)) list.add(new Role(Type.INPUT_OUTPUT, true, "Outputs it turns on"));
                break;
            default:
                break;
        }
        return list;
    }

    //Why nothing can be added to this item here, for the window to show (null when roles() is not empty)
    public static String noRolesReason(Job job, Object item) {
        if (!roles(job, item).isEmpty()) return null;
        if (item instanceof Led) {
            Led l = (Led) item;
            if (!job.panel.hasZones()) return "LED correlations on " + job.panel.label + " are set in the configurator.";
            return "\"" + l.led_type_name + "\" LEDs (switches, page select, system status, bypass) are set in the configurator. Only input zone LEDs can be correlated here.";
        }
        Circuit c = (Circuit) item;
        if (c.kind == Kind.OUTPUT_ZONE) return "Output zones are set in the configurator.";
        if (c.kind == Kind.PANEL) return "This panel circuit takes no correlations here.";
        return "Nothing can be correlated to this item.";
    }

    //Every item that could be at the other end for this role (allowed or not; blocked() says which)
    public static List<Object> candidates(Job job, Role role) {
        List<Object> list = new ArrayList<>();
        Panel p = job.panel;
        boolean want_a = !role.item_is_a;
        switch (role.type) {
            case DEVICE_ZONE:
                for (Circuit c : job.inputs.values()) if (want_a ? c.kind == Kind.DEVICE : c.kind == Kind.INPUT_ZONE) list.add(c);
                break;
            case INPUT_OUTPUT:
                if (want_a) {
                    for (Circuit c : job.inputs.values()) {
                        if (p.hasZones() ? (c.kind == Kind.INPUT_ZONE || (c.kind == Kind.PANEL && isSwitchInput(c))) : c.kind != Kind.INPUT_ZONE) list.add(c);
                    }
                } else {
                    for (Circuit c : job.outputs.values()) if (c.kind != Kind.OUTPUT_ZONE) list.add(c);
                }
                break;
            case LED_INPUT:
                if (want_a) {
                    for (Led l : job.leds.values()) if (isZoneLed(l)) list.add(l);
                } else {
                    for (Circuit c : job.inputs.values()) if (c.kind == Kind.INPUT_ZONE) list.add(c);
                }
                break;
            default:
                break;
        }
        return list;
    }

    //Why the configurator would not accept this link, or null if it would. Already-linked counts as not addable.
    public static String blocked(Edits edits, Link link) {
        Job job = edits.job;
        if (!editable(job.panel, link.type)) return link.type.label + " correlations on " + job.panel.label + " are set in the configurator.";
        if (edits.linked(link)) return "Already correlated.";
        Object x = link.end(job, true), y = link.end(job, false);
        if (x == null || y == null) return "Not in this job.";
        switch (link.type) {
            case DEVICE_ZONE: return blockedDeviceZone(job, (Circuit) x, (Circuit) y);
            case INPUT_OUTPUT: return blockedInputOutput(job, (Circuit) x, (Circuit) y);
            case LED_INPUT: return blockedLed(job, (Led) x, (Circuit) y);
            default: return "Not supported.";
        }
    }

    //Allowed, but worth a second look before adding; null when there is nothing to say
    public static String caution(Edits edits, Link link) {
        Job job = edits.job;
        Object x = link.end(job, true), y = link.end(job, false);
        if (x instanceof Circuit && y instanceof Circuit) {
            Circuit a = (Circuit) x, b = (Circuit) y;
            if (link.type == Type.DEVICE_ZONE && b.isPlaceholder()) return "This input zone is a placeholder (blank or XXXX tag).";
            if (link.type == Type.INPUT_OUTPUT && job.panel == Panel.FX400 && b.corr_class == 9 && statusDriven(job, b)) {
                return "This relay is also turned on by a system status. The MGC-400 configurator can refuse inputs on such relays.";
            }
            if (link.type == Type.INPUT_OUTPUT && a.kind == Kind.INPUT_ZONE && a.isPlaceholder()) return "This input zone is a placeholder (blank or XXXX tag).";
        }
        return null;
    }

    //Whether an existing link can be removed here (the configurator's other screens own some circuits)
    public static String cannotRemove(Edits edits, Link link) {
        Job job = edits.job;
        if (!editable(job.panel, link.type)) return link.type.label + " correlations on " + job.panel.label + " are set in the configurator.";
        Object x = link.end(job, true), y = link.end(job, false);
        if (link.type == Type.LED_INPUT && x instanceof Led && !isZoneLed((Led) x)) return "Switch / status LEDs are set in the configurator.";
        String locked = locked(job, x, y);
        return locked;
    }

    private static String blockedDeviceZone(Job job, Circuit dev, Circuit zone) {
        if (dev.output || dev.kind != Kind.DEVICE) return "Only device inputs go into input zones.";
        if (zone.kind != Kind.INPUT_ZONE) return "That is not an input zone.";
        String locked = locked(job, dev, zone);
        if (locked != null) return locked;
        if (dev.corr_class <= 0) return "Its input type (" + dev.process_name + ") takes no correlations.";
        if (zone.corr_class <= 0) return "The zone's type (" + zone.process_name + ") takes no correlations.";
        if (dev.node != zone.node) return "Input zones belong to one node: the device is on node " + dev.node + ", the zone on node " + zone.node + ".";
        if (job.panel == Panel.FX6000 && dev.cpu != zone.cpu) return "On the FX6000 each CPU has its own input zones: the device is on CPU " + dev.cpu + ", the zone on CPU " + zone.cpu + ".";
        if (dev.corr_class != zone.corr_class) {
            return a(className(dev.corr_class)) + " input (" + dev.process_name + ") can only go into " + a(className(dev.corr_class)).toLowerCase()
                + " zone; this zone is " + className(zone.corr_class) + " (" + zone.process_name + ").";
        }
        List<Job.Equation> logic = job.equationsDriving(zone);
        if (!logic.isEmpty()) return "This zone is controlled by a logic equation (" + logic.get(0).label() + "); the configurator does not allow device correlations to it.";
        return null;
    }

    private static String blockedInputOutput(Job job, Circuit in, Circuit out) {
        if (in.output) return "That is not an input.";
        if (!out.output) return "That is not an output.";
        if (job.panel.hasZones()) {
            if (in.kind == Kind.DEVICE) return "On " + job.panel.name() + " devices turn outputs on through input zones: put the device in a zone and correlate the zone to this output.";
            if (in.kind == Kind.PANEL && !isSwitchInput(in)) return "Only input zones and correlatable switch inputs turn outputs on.";
        } else if (in.kind == Kind.INPUT_ZONE) {
            return "That is not an input.";
        }
        if (out.kind == Kind.OUTPUT_ZONE) return "Output zones are set in the configurator.";
        String locked = locked(job, in, out);
        if (locked != null) return locked;
        if (in.corr_class <= 0 || in.process_name.toLowerCase().contains("hazard")) return "Its input type (" + in.process_name + ") takes no output correlations.";
        if (out.corr_class != 8 && out.corr_class != 9) return "Its output type (" + out.process_name + ") takes no input correlations.";
        return null;
    }

    private static String blockedLed(Job job, Led led, Circuit zone) {
        if (!isZoneLed(led)) return "Only input zone LEDs can be correlated here; \"" + led.led_type_name + "\" LEDs are set in the configurator.";
        if (zone.kind != Kind.INPUT_ZONE) return "Input zone LEDs show input zones, not single devices.";
        if (led.slots.isEmpty()) return "This LED group has no position on a display.";
        int want = ledClass(led);
        if (zone.corr_class <= 0) return "The zone's type (" + zone.process_name + ") cannot be shown on an LED.";
        if (want != MIXED && want != zone.corr_class) {
            return "A \"" + led.description + "\" LED shows " + className(want) + " zones; this zone is " + className(zone.corr_class) + " (" + zone.process_name + ").";
        }
        return null;
    }

    private static String locked(Job job, Object x, Object y) {
        for (Object o : new Object[] { x, y }) {
            if (!(o instanceof Circuit)) continue;
            Circuit c = (Circuit) o;
            String why = c.output ? job.locked_outputs.get(c.ckt) : job.locked_inputs.get(c.ckt);
            if (why != null) return why;
        }
        return null;
    }

    private static boolean statusDriven(Job job, Circuit out) {
        for (int[] l : job.status_links) if (l[1] == out.ckt) return true;
        return false;
    }

    //-------------------------------------------------------------------------------------------------
    //Classes

    static final int MIXED = 2;

    //Input zone display LEDs ("Ipt Zone" display type) are the ones whose correlations are edited here
    public static boolean isZoneLed(Led l) {
        return l.display_type.equalsIgnoreCase("Ipt Zone") && ledClass(l) > 0;
    }

    //The class of input an input zone LED shows, read from its description so every configurator's numbering works
    static int ledClass(Led l) {
        String d = l.description.toLowerCase();
        if (d.contains("mixed")) return MIXED;
        if (d.contains("co alarm")) return 10;
        if (d.contains("alarm")) return 4;
        if (d.contains("supervisory")) return 5;
        if (d.contains("trouble")) return 6;
        if (d.contains("monitor") || d.contains("bldg") || d.contains("building")) return 7;
        return -1;
    }

    //"A supervisory" / "An alarm"
    static String a(String word) {
        return (word.matches("(?i)^[aeiou].*") ? "An " : "A ") + word;
    }

    static String className(int c) {
        switch (c) {
            case MIXED: return "any";
            case 4: return "alarm";
            case 5: return "supervisory";
            case 6: return "trouble";
            case 7: return "monitor";
            case 8: return "signal";
            case 9: return "relay";
            case 10: return "CO alarm";
            default: return "unknown";
        }
    }

    //Correlatable switch inputs on the main panel (e.g. "Corr Sw Ipt") turn outputs on like an input zone
    static boolean isSwitchInput(Circuit c) {
        return c.kind == Kind.PANEL && !c.output && c.device_type.toLowerCase().contains("corr sw");
    }
}
