package checker;

import java.util.ArrayList;
import java.util.List;

import checker.Job.Circuit;
import checker.Job.Equation;
import checker.Job.Kind;
import checker.Job.Led;

//Where something is, split into the parts the configurator shows: node, CPU (card), loop, address, then what it
//is and its two tag lines. Used for the table columns in the window and for one-line text everywhere else.
public final class Place {

    public final int node; //-1 when not tied to a node
    public final String node_name;
    public final String cpu;
    public final String loop;
    public final String address;
    public final String type;
    public final String tag;
    public final String location;

    public Place(int node, String node_name, String cpu, String loop, String address, String type, String tag, String location) {
        this.node = node;
        this.node_name = node_name == null ? "" : node_name;
        this.cpu = cpu == null ? "" : cpu;
        this.loop = loop == null ? "" : loop;
        this.address = address == null ? "" : address;
        this.type = type == null ? "" : type;
        this.tag = tag == null ? "" : tag;
        this.location = location == null ? "" : location;
    }

    //A place that is just a description, e.g. "Base Control"
    public static Place text(String what) {
        return new Place(-1, "", "", "", "", "", what, "");
    }

    public String nodeText() {
        return node < 0 ? "" : node + (node_name.isBlank() ? "" : "  " + node_name.trim());
    }

    //One line, e.g. Node 1 > S200 QLA (CPU 1) > Loop 1 > 109.2  HeatPhoto (2351TAP)  "Smoke Detector / 13FL SouthElevLobby"
    public String text() {
        List<String> path = new ArrayList<>();
        if (node >= 0) path.add("Node " + node);
        if (!cpu.isEmpty()) path.add(cpu);
        if (!loop.isEmpty()) path.add(loop);
        if (!address.isEmpty()) path.add(address);
        StringBuilder s = new StringBuilder(String.join(" > ", path));
        if (!type.isEmpty()) s.append(s.length() > 0 ? "  " : "").append(type);
        String tags = tags();
        if (!tags.isEmpty()) s.append(s.length() > 0 ? "  \"" + tags + "\"" : tags);
        return s.toString();
    }

    //Shorter one line without CPU and type, e.g. Node 2 > IZ 24 "Elev Recall / Center"
    public String brief() {
        List<String> path = new ArrayList<>();
        if (node >= 0) path.add("Node " + node);
        if (!loop.isEmpty()) path.add(loop);
        if (!address.isEmpty()) path.add(address);
        String tags = tags();
        return String.join(" > ", path) + (tags.isEmpty() ? "" : (path.isEmpty() ? "" : "  ") + "\"" + tags + "\"");
    }

    public String tags() {
        if (location.isEmpty()) return tag;
        if (tag.isEmpty()) return location;
        return tag + " / " + location;
    }

    @Override
    public String toString() {
        return text();
    }

    //-------------------------------------------------------------------------------------------------

    //Works out the place of anything a rule reports on
    static Place of(Job job, Object subject) {
        if (subject instanceof Place) return (Place) subject;
        if (subject instanceof Circuit) return of(job, (Circuit) subject);
        if (subject instanceof Led) return of(job, (Led) subject, -1, -1);
        if (subject instanceof Equation) return of(job, (Equation) subject);
        if (subject instanceof Placeable) return ((Placeable) subject).place(job);
        return text(String.valueOf(subject));
    }

    //Things other than circuits/LEDs/equations that can be reported on (e.g. an FSAE floor group)
    public interface Placeable {
        Place place(Job job);
    }

    static Place of(Job job, Circuit c) {
        String node_name = job == null ? "" : job.nodeName(c.node);
        String tag1 = c.tag1(), tag2 = c.tag2();
        switch (c.kind) {
            case INPUT_ZONE: {
                //FX4000 keeps every input zone on one virtual CPU; FX6000 has a list per CPU, so the CPU matters there
                String cpu = job != null && job.panel == Job.Panel.FX6000 ? cpuName(job, c.node, c.cpu) : "";
                return new Place(c.node, node_name, cpu, "", "IZ " + c.addr, "Input zone (" + c.process_name + ")", tag1, tag2);
            }
            case OUTPUT_ZONE:
                return new Place(c.node, node_name, "", "", "OZ " + c.addr, "Output zone", tag1, tag2);
            default: {
                int addr = c.primary_addr >= 0 ? c.primary_addr : c.addr;
                boolean multi = c.sub > 1 || c.primary_addr >= 0 || c.device_type.toLowerCase().contains("dual") || c.device_type.contains("TAP");
                String address = addr + (multi ? "." + c.sub : "");
                return new Place(c.node, node_name, cpuName(job, c.node, c.cpu), "Loop " + c.loop, address, c.device_type, tag1, tag2);
            }
        }
    }

    //An LED at one position; node/cpu -1 = its first position
    static Place of(Job job, Led l, int node, int cpu) {
        int[] slot = null;
        for (int[] p : l.slots) {
            if (node < 0 || (p[0] == node && p[1] == cpu)) {
                slot = p;
                break;
            }
        }
        String type = "LED (" + l.led_type_name.replaceFirst("^.*/\\s*", "") + ")";
        String tag = l.tag.trim().replaceAll("\\s{2,}", " ");
        if (slot == null) return new Place(-1, "", "", "", "LED group " + l.group, type, tag, "");
        return new Place(slot[0], job == null ? "" : job.nodeName(slot[0]), cpuName(job, slot[0], slot[1]), "Frame " + slot[2], "LED " + slot[3], type, tag, "");
    }

    static Place of(Job job, Equation e) {
        String subject = e.subjects.isEmpty() ? "" : "drives IZ " + e.subjects.get(0).addr;
        return new Place(e.node, job == null || e.node < 0 ? "" : job.nodeName(e.node), "", "", "Logic " + e.number,
            "Logic equation" + (subject.isEmpty() ? "" : " (" + subject + ")"), e.tag.trim().replaceAll("\\s{2,}", " "), "");
    }

    static String cpuName(Job job, int node, int cpu) {
        if (job == null) return "CPU " + cpu;
        String type = job.cpuType(node, cpu);
        if (cpu == 0) return "Main panel (CPU 0)";
        return (type.isEmpty() ? "CPU" : type) + " (CPU " + cpu + ")";
    }

    //Short description of a circuit for inside sentences, e.g. IZ 61 "Low Heat 13FL SouthT" or 109.2 "Smoke Detector"
    static String brief(Circuit c) {
        String tag = c.cleanTag();
        if (c.kind == Kind.INPUT_ZONE) return "IZ " + c.addr + " \"" + tag + "\"";
        if (c.kind == Kind.OUTPUT_ZONE) return "OZ " + c.addr + " \"" + tag + "\"";
        return "\"" + tag + "\"";
    }
}
