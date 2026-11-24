package checker;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.healthmarketscience.jackcess.Database;
import com.healthmarketscience.jackcess.DatabaseBuilder;
import com.healthmarketscience.jackcess.Row;
import com.healthmarketscience.jackcess.Table;

//One job version read from a configurator database: an exported job .mdb, or the configurator's own
//configurator.mdb (which holds every job, so a job number is needed). Read-only; nothing is ever written.
//
//Two layouts are understood:
//  FX4000 (MGC-4000 Configurator): devices in DeviceConfig, one circuit row per sub-address, input zones on a virtual CPU.
//  FX6000/FlexNet (MGC Configurator): no DeviceConfig; each circuit row carries node/loop/address, the extra inputs of a
//    multi-input device are their own rows ("B", "C" device types) pointing back with PrimDevAddr, input zones are
//    "Input Zone" device type rows.
//Both keep correlations, LEDs, logic and manual controls in the same tables, so the rules work on either.
//FX400/FX2000 keep correlations in one IOCorrs table keyed by loop/address and will get their own loader.
public class Job {

    public enum Panel {
        FX4000("FX4000 (MGC-4000 Configurator)"),
        FX6000("FX6000 / FlexNet (MGC Configurator)"),
        FX400("FX400 (MGC-400 Series Configurator)"),
        FX2000("FX2000 (FX-2000 Configuration Utility)"),
        UNKNOWN("unknown panel");

        public final String label;

        Panel(String label) {
            this.label = label;
        }

        //Panels with input zones, logic and the MGC correlation tables
        public boolean isMgc() {
            return this == FX4000 || this == FX6000;
        }

        //Panels whose jobs this checker can read (devices, correlations, LEDs, bypass)
        public boolean isReadable() {
            return this != UNKNOWN;
        }

        //Panels that correlate devices to input zones (FX400/FX2000 correlate devices straight to outputs)
        public boolean hasZones() {
            return isMgc();
        }
    }

    //Loop types the MGC-4000 configurator uses for its virtual input/output zone lists (SYSLoopTypes)
    static final int LOOP_TYPE_INPUT_ZONE = 126;
    static final int LOOP_TYPE_OUTPUT_ZONE = 127;
    //Manual control group type used by the Mixed / Zone Bypass switch
    static final int CTRL_TYPE_BYPASS = 1;

    public final File file;
    public long file_stamp; //the file's modified time when it was read, to notice a save in the configurator since
    public Panel panel = Panel.UNKNOWN;
    public int job_id;
    public int version;
    public String description = "";
    public String version_description = "";
    public String job_guid = ""; //the configurator's ID for the job; the same in every version and in exports

    //A stable name for this job across versions and files: its GUID, or its description when it has none
    public String identity() {
        String g = job_guid.replace("-", "").replace("{", "").replace("}", "");
        if (!g.isEmpty() && !g.matches("0+")) return job_guid.replaceAll("[{}]", "").toLowerCase();
        return "job-" + description.trim().replaceAll("[^A-Za-z0-9]+", "_");
    }

    public final Map<Integer, Circuit> inputs = new TreeMap<>(); //by CktNo
    public final Map<Integer, Circuit> outputs = new TreeMap<>(); //by CktNo
    public final Map<Integer, Led> leds = new TreeMap<>(); //by LedGrp
    public final Map<Integer, Equation> equations = new TreeMap<>(); //by EquationNo
    public final Map<Integer, ControlGroup> controls = new TreeMap<>(); //by CtrlGrp

    //Raw link rows, kept as-is so integrity checks can see dangling or duplicate rows
    public final List<int[]> zone_links = new ArrayList<>(); //IptZoneCorrs: {input ckt, input zone ckt}
    public final List<int[]> io_links = new ArrayList<>(); //IOCorrs: {input ckt, output ckt}
    public final List<int[]> led_input_links = new ArrayList<>(); //LedIpts: {led group, input ckt}
    public final List<int[]> led_output_links = new ArrayList<>(); //LedOpts: {led group, output ckt}
    public final List<int[]> status_links = new ArrayList<>(); //StatusCorrs: {status, output ckt}

    public final Map<String, String> frame_types = new HashMap<>(); //"node/cpu/frame" -> frame type name
    public final Map<Integer, String> node_names = new HashMap<>(); //node -> its tag, e.g. "FCC 2FL (Cell-13FL)"
    public final Map<String, String> cpu_types = new HashMap<>(); //"node/cpu" -> card type, e.g. "S200 QLA"
    public final Map<Integer, String> status_names = new HashMap<>(); //system status -> name, e.g. "Common Trouble"
    public final Map<Integer, String> led_state_names = new TreeMap<>(); //SomeActive value -> name, e.g. 18 -> "Green Continuous"
    public final List<int[]> led_proving_links = new ArrayList<>(); //LedProvingStat: {led group, proving group}
    public final Map<Integer, Proving> provings = new TreeMap<>(); //by proving group

    //A proving group: the input zone that proves an output came on (ProvingIpts) and the outputs it watches (ProvingCtrl)
    public static class Proving {
        public int group;
        public Circuit zone;
        public final List<Circuit> outputs = new ArrayList<>();
    }

    //Circuits whose correlations the configurator manages somewhere else (hazard zones, output zones, equipment sets),
    //with the reason. The correlation editor will not add links to them.
    public final Map<Integer, String> locked_inputs = new HashMap<>(); //input ckt -> reason
    public final Map<Integer, String> locked_outputs = new HashMap<>(); //output ckt -> reason

    //Lookups built from the link rows once loading is done
    private final Map<Integer, List<Circuit>> zones_of = new HashMap<>(); //device input ckt -> its input zones
    private final Map<Integer, List<Circuit>> sources_of = new HashMap<>(); //input zone ckt -> device inputs feeding it
    private final Map<Integer, List<Circuit>> direct_outputs = new HashMap<>(); //input ckt -> outputs in IOCorrs
    private final Map<Integer, List<Circuit>> direct_inputs = new HashMap<>(); //output ckt -> inputs in IOCorrs
    private final Map<Integer, List<Led>> leds_of_input = new HashMap<>();
    private final Map<Integer, List<Equation>> equations_of_subject = new HashMap<>();

    //Rules can park shared work here (e.g. the FSAE floor groups) so it is only worked out once
    public final Map<String, Object> cache = new HashMap<>();

    private Job(File file) {
        this.file = file;
    }

    //-------------------------------------------------------------------------------------------------
    //Model

    public enum Kind { DEVICE, INPUT_ZONE, OUTPUT_ZONE, PANEL }

    public static class Circuit {
        public int ckt;
        public boolean output;
        public Kind kind = Kind.DEVICE;
        public int node, cpu, loop, addr, sub = 1;
        public int primary_addr = -1; //FX6000: address of the device's first input, for its "B"/"C" rows
        public String loop_type = ""; //SYSLoopTypes name, e.g. S200, S500, Telephone, IptZone
        public String device_type = "";
        public int process_type;
        public String process_name = "";
        public String tag = "";
        public boolean silenceable = true; //false = NS (non-silenceable) flag
        public boolean aux_reset; //outputs: AR flag
        public Boolean co_flag; //FX6000 input zones: CO flag (F4). Null when the panel does not store it.
        public Boolean class_a; //FX400 inputs: Class-A option. Null when the panel does not store it.
        //The configurator's correlation class of the process type (SYSProcTypes.LedCktType): 4 alarm, 5 supervisory,
        //6 trouble, 7 monitor / building, 8 signal, 9 relay, 10 CO (FX400), 0 = takes no correlations. -1 = unknown.
        public int corr_class = -1;

        //Short, unambiguous name for reports, e.g. N1 IZ61 "Low Heat 13FL SouthT" or N1 C1 L1-109.2 HeatPhoto (2351TAP) "..."
        public String label() {
            String t = cleanTag();
            switch (kind) {
                case INPUT_ZONE: return "N" + node + (cpu > 0 && cpu < 66 ? " C" + cpu : "") + " IZ" + addr + " \"" + t + "\"";
                case OUTPUT_ZONE: return "N" + node + " OZ" + addr + " \"" + t + "\"";
                default:
                    return "N" + node + " C" + cpu + " L" + loop + "-" + addr + (sub > 1 || primary_addr >= 0 ? "." + sub : "")
                        + " " + device_type + (t.isEmpty() ? "" : " \"" + t + "\"");
            }
        }

        public String cleanTag() {
            return tag.trim().replaceAll("\\s{2,}", " ");
        }

        //First tag line (the configurator stores two 20-character lines in one field)
        public String tag1() {
            return (tag.length() > 20 ? tag.substring(0, 20) : tag).trim();
        }

        //Second tag line, usually the location
        public String tag2() {
            return tag.length() > 20 ? tag.substring(20).trim() : "";
        }

        //Both tag lines with a space between, for reading words: a full first line runs straight into the second
        //("Waterflow SouthTower" + "14FL Stair D"), which would otherwise hide words like 14FL
        //Squashed tags are split into words too: "DrySysPS10WatrFlo1FL" -> "Dry Sys PS 10 Watr Flo 1 FL"
        public String words() {
            return splitWords(tag1() + " " + tag2());
        }

        public static String splitWords(String text) {
            String t = text.trim();
            t = t.replaceAll("(?<=[a-z])(?=[A-Z])", " "); //camelCase
            t = t.replaceAll("(?<=[A-Za-z])(?=\\d)|(?<=\\d)(?=[A-Za-z])", " "); //letters and digits
            return t.replaceAll("\\s+", " ");
        }

        //Same for every input of one physical device
        public String deviceKey() {
            return node + "/" + cpu + "/" + loop + "/" + (primary_addr >= 0 ? primary_addr : addr);
        }

        //Blank or XXXX-tagged input zones are separators / placeholders (checklist: remove before sending)
        public boolean isPlaceholder() {
            String t = tag.trim();
            return t.isEmpty() || t.matches("[Xx]+");
        }

        public boolean isSpare() {
            String t = tag.toLowerCase();
            return t.isBlank() || t.contains("spare") || t.contains("not used") || t.contains("unused");
        }
    }

    public static class Led {
        public int group;
        public int led_type;
        public String led_type_name = "";
        public String display_type = ""; //SYSLedTypes.DisplayType, e.g. "Ipt Zone", "Auto/Man Slide"
        public String description = ""; //SYSLedTypes.Description, e.g. "Alarm Stat", "Supervisory Stat"
        public String tag = "";
        public Map<String, Integer> states = new LinkedHashMap<>(); //AllActive, SomeActive, ... as stored
        public final List<int[]> slots = new ArrayList<>(); //{node, cpu, frame, led}
        public final List<Circuit> inputs = new ArrayList<>();
        public final List<Circuit> outputs = new ArrayList<>();
        public final List<Integer> provings = new ArrayList<>(); //proving groups (LedProvingStat), e.g. an HOA switch's yellow LED

        public int someActive() {
            return states.getOrDefault("SomeActive", 0);
        }

        public String label() {
            StringBuilder s = new StringBuilder();
            for (int[] p : slots) {
                if (s.length() > 0) s.append(", ");
                s.append("N").append(p[0]).append(" C").append(p[1]).append(" F").append(p[2]).append(" LED").append(p[3]);
            }
            if (s.length() == 0) s.append("LED group ").append(group).append(" (no position)");
            return s + " \"" + tag.trim() + "\"";
        }
    }

    public static class Equation {
        public int number;
        public int node = -1;
        public String tag = "";
        public String text = "";
        public String comments = "";
        public final List<Circuit> subjects = new ArrayList<>(); //input zones this equation drives
        public final TreeMap<Integer, Integer> inputs = new TreeMap<>(); //sequence -> input ckt
        public final List<int[]> sequence = new ArrayList<>(); //compiled form: {sequence, term, value, mask}

        public String label() {
            return "Equation " + number + " \"" + tag.trim() + "\"";
        }

        //The compiled form with the circuit references left out, for comparing equations that share a template
        public String signature() {
            StringBuilder s = new StringBuilder();
            for (int[] r : sequence) s.append(r[1]).append(':').append(r[2]).append(':').append(r[3]).append(' ');
            return s.toString();
        }
    }

    //A manual control (switch) and the outputs it controls, e.g. the Mixed / Zone Bypass switch
    public static class ControlGroup {
        public int group;
        public int type;
        public final List<int[]> switches = new ArrayList<>(); //{node, cpu, frame, switch}
        public final Set<Circuit> outputs = new LinkedHashSet<>();
        public String name = ""; //FX400 bypass groups have a name, e.g. "Bypass Relays"

        public boolean isBypass() {
            return type == CTRL_TYPE_BYPASS;
        }

        public String label() {
            if (!name.isEmpty()) return "Bypass group \"" + name + "\"";
            List<String> s = new ArrayList<>();
            for (int[] p : switches) s.add("Node " + p[0] + " CPU " + p[1] + " frame " + p[2] + " switch " + p[3]);
            return (isBypass() ? "Bypass switch " : "Control group " + group + " ") + (s.isEmpty() ? "(no switch)" : String.join(", ", s));
        }
    }

    //-------------------------------------------------------------------------------------------------
    //Lookups

    public List<Circuit> zonesOf(Circuit c) {
        return zones_of.getOrDefault(c.ckt, List.of());
    }

    public List<Circuit> sourcesOf(Circuit zone) {
        return sources_of.getOrDefault(zone.ckt, List.of());
    }

    public List<Led> ledsOf(Circuit c) {
        return leds_of_input.getOrDefault(c.ckt, List.of());
    }

    public List<Equation> equationsDriving(Circuit zone) {
        return equations_of_subject.getOrDefault(zone.ckt, List.of());
    }

    //Outputs correlated straight to this input or input zone
    public List<Circuit> directOutputs(Circuit input) {
        return direct_outputs.getOrDefault(input.ckt, List.of());
    }

    //Inputs and input zones correlated straight to this output
    public List<Circuit> directInputs(Circuit output) {
        return direct_inputs.getOrDefault(output.ckt, List.of());
    }

    //Every output this device input turns on: correlated directly, or through an input zone it is in
    public Set<Circuit> outputsReachedBy(Circuit input) {
        Set<Circuit> set = new LinkedHashSet<>(directOutputs(input));
        for (Circuit z : zonesOf(input)) set.addAll(directOutputs(z));
        return set;
    }

    //Every device input that turns this output on (the inverse of outputsReachedBy)
    public Set<Circuit> devicesReaching(Circuit output) {
        Set<Circuit> set = new LinkedHashSet<>();
        for (Circuit c : directInputs(output)) {
            if (c.kind == Kind.INPUT_ZONE) set.addAll(sourcesOf(c));
            else set.add(c);
        }
        return set;
    }

    public List<Circuit> inputZones() {
        List<Circuit> list = new ArrayList<>();
        for (Circuit c : inputs.values()) if (c.kind == Kind.INPUT_ZONE) list.add(c);
        list.sort((a, b) -> a.node != b.node ? Integer.compare(a.node, b.node)
            : a.cpu != b.cpu ? Integer.compare(a.cpu, b.cpu) : Integer.compare(a.addr, b.addr));
        return list;
    }

    //The input zone at this address in the same input zone list (node and CPU) as the given zone
    public Circuit inputZoneNear(Circuit zone, int addr) {
        for (Circuit c : inputs.values()) {
            if (c.kind == Kind.INPUT_ZONE && c.node == zone.node && c.cpu == zone.cpu && c.addr == addr) return c;
        }
        return null;
    }

    public List<ControlGroup> bypassGroups() {
        List<ControlGroup> list = new ArrayList<>();
        for (ControlGroup g : controls.values()) if (g.isBypass()) list.add(g);
        return list;
    }

    public String nodeName(int node) {
        return node_names.getOrDefault(node, "");
    }

    public String cpuType(int node, int cpu) {
        return cpu_types.getOrDefault(node + "/" + cpu, "");
    }

    public String frameType(int node, int cpu, int frame) {
        return frame_types.getOrDefault(node + "/" + cpu + "/" + frame, "?");
    }

    public String summary() {
        int zones = 0;
        for (Circuit c : inputs.values()) if (c.kind == Kind.INPUT_ZONE) zones++;
        return inputs.size() + " input circuits (" + zones + " input zones), " + outputs.size() + " output circuits, "
            + zone_links.size() + " device-zone links, " + io_links.size() + " input-output links, "
            + leds.size() + " LEDs, " + equations.size() + " equations";
    }

    //-------------------------------------------------------------------------------------------------
    //Loading

    //Lists the job versions in a database as "job/version  description"
    public static List<String> listVersions(File file) throws IOException {
        List<String> list = new ArrayList<>();
        for (Version v : versions(file)) {
            list.add(v.job + "/" + v.version + "  " + v.description + "  (" + v.version_description + ")");
        }
        return list;
    }

    //One saved version of a job in a database
    public static class Version {
        public final int job, version;
        public final String description, version_description;
        public final String created; //sortable yyyy-MM-ddTHH:mm, "" if unknown

        Version(int job, int version, String description, String version_description, String created) {
            this.job = job;
            this.version = version;
            this.description = description;
            this.version_description = version_description;
            this.created = created;
        }

        @Override
        public String toString() {
            return "Job " + job + "-" + String.format("%02d", version) + "   " + description
                + (version_description.isBlank() ? "" : "   (" + version_description.trim() + ")")
                + (created.isEmpty() ? "" : "   " + created.replace('T', ' '));
        }
    }

    //Every job version in the database, sorted by job then version (built-in templates left out)
    public static List<Version> versions(File file) throws IOException {
        List<Version> list = new ArrayList<>();
        try (Database db = open(file)) {
            Map<Integer, String> names = new HashMap<>();
            Table jobs = db.getTable("Jobs");
            if (jobs != null) for (Row r : jobs) names.put(num(r, "JobID"), str(r, "Description"));
            for (Row r : db.getTable("JobVersions")) {
                int j = num(r, "JobID");
                if (j < 0) continue;
                list.add(new Version(j, num(r, "VersionNo"), names.getOrDefault(j, ""), str(r, "Description").replaceAll("\\s+", " "),
                    timestamp(r.get("CreationDate"))));
            }
        }
        list.sort((a, b) -> a.job != b.job ? Integer.compare(a.job, b.job) : Integer.compare(a.version, b.version));
        return list;
    }

    private static String timestamp(Object o) {
        if (o instanceof java.time.LocalDateTime) return ((java.time.LocalDateTime) o).withSecond(0).withNano(0).toString();
        if (o instanceof java.util.Date) {
            return java.time.LocalDateTime.ofInstant(((java.util.Date) o).toInstant(), java.time.ZoneId.systemDefault()).withSecond(0).withNano(0).toString();
        }
        return "";
    }

    //job/version may be null: an exported job holds one version, which is picked automatically.
    //With a job but no version, the newest version of that job is used.
    public static Job load(File file, Integer job_id, Integer version) throws IOException {
        Job job = new Job(file);
        job.file_stamp = file.lastModified(); //before reading, so a save during the read still counts as a change
        try (Database db = open(file)) {
            job.panel = detectPanel(db);
            job.pickVersion(db, job_id, version);
            if (!job.panel.isReadable()) return job;
            job.loadCircuits(db);
            job.loadShared(db);
        }
        job.index();
        return job;
    }

    private static Database open(File file) throws IOException {
        return new DatabaseBuilder(file).setReadOnly(true).open();
    }

    private static Panel detectPanel(Database db) throws IOException {
        if (db.getTableNames().contains("DeviceConfig")) return Panel.FX4000;
        Table ipt = db.getTable("InputCircuits");
        if (ipt == null) return Panel.UNKNOWN;
        if (has(ipt, "NodeNo") && has(ipt, "CktNoCPU")) return Panel.FX6000;
        if (has(ipt, "PrimDevAddr")) return Panel.FX400;
        if (has(ipt, "CrossZnCount")) return Panel.FX2000;
        return Panel.UNKNOWN;
    }

    private static boolean has(Table t, String column) {
        try {
            return t.getColumn(column) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void pickVersion(Database db, Integer want_job, Integer want_version) throws IOException {
        List<int[]> versions = new ArrayList<>();
        for (Row r : db.getTable("JobVersions")) if (num(r, "JobID") >= 0) versions.add(new int[] { num(r, "JobID"), num(r, "VersionNo") });
        if (versions.isEmpty()) throw new IOException("The database has no jobs.");

        int[] pick = null;
        if (want_job == null) {
            if (versions.size() > 1) {
                throw new IOException("The database holds " + versions.size() + " job versions. Pick one with --job (and --version), "
                    + "or check an exported job file instead. Use --list to see them.");
            }
            pick = versions.get(0);
        } else {
            for (int[] v : versions) {
                if (v[0] != want_job) continue;
                if (want_version != null ? v[1] == want_version : (pick == null || v[1] > pick[1])) pick = v;
            }
            if (pick == null) throw new IOException("Job " + want_job + (want_version != null ? " version " + want_version : "") + " is not in this database.");
        }
        job_id = pick[0];
        version = pick[1];

        Table jobs = db.getTable("Jobs");
        if (jobs != null) {
            for (Row r : jobs) {
                if (num(r, "JobID") != job_id) continue;
                description = str(r, "Description");
                job_guid = str(r, "JobGUID").trim();
            }
        }
        for (Row r : db.getTable("JobVersions")) {
            if (num(r, "JobID") == job_id && num(r, "VersionNo") == version) version_description = str(r, "Description");
        }
    }

    private boolean mine(Row r) {
        return num(r, "JobID") == job_id && num(r, "VersionNo") == version;
    }

    //Circuits differ between the two layouts; everything after this is shared
    private void loadCircuits(Database db) throws IOException {
        Map<Integer, String> device_types = lookup(db, "SYSDevTypes", "DevType", "Tag");
        Map<Integer, String> process_types = lookup(db, "SYSProcTypes", "ProcessType", "Tag");
        Map<Integer, String> process_classes = lookup(db, "SYSProcTypes", "ProcessType", "LedCktType");
        Map<Integer, String> loop_type_names = lookup(db, "SYSLoopTypes", "LoopType", "Tag");

        Map<String, Integer> loop_types = new HashMap<>(); //FX4000 "node/cpu/loop", FX6000 "node/loop" -> loop type
        Map<String, Integer> loop_cpu = new HashMap<>(); //FX6000 "node/loop" -> cpu
        for (Row r : db.getTable("LoopConfig")) {
            if (!mine(r)) continue;
            int node = nodeOf(r), cpu = num(r, "CPUNo"), loop = num(r, "LoopNo"), type = num(r, "LoopType");
            if (panel == Panel.FX4000) {
                loop_types.put(node + "/" + cpu + "/" + loop, type);
            } else {
                loop_types.put(node + "/" + loop, type);
                loop_cpu.put(node + "/" + loop, cpu);
            }
        }

        Map<Integer, Row> devices = new HashMap<>();
        if (panel == Panel.FX4000) for (Row r : db.getTable("DeviceConfig")) if (mine(r)) devices.put(num(r, "DevNo"), r);

        for (String table : new String[] { "InputCircuits", "OutputCircuits" }) {
            boolean output = table.startsWith("Output");
            for (Row r : db.getTable(table)) {
                if (!mine(r)) continue;
                Circuit c = new Circuit();
                c.ckt = num(r, "CktNo");
                c.output = output;
                c.process_type = num(r, "ProcessType");
                c.process_name = process_types.getOrDefault(c.process_type, "type " + c.process_type);
                try {
                    c.corr_class = Integer.parseInt(process_classes.getOrDefault(c.process_type, "-1").trim());
                } catch (NumberFormatException e) {
                    c.corr_class = -1;
                }
                c.tag = str(r, "Tag");
                c.silenceable = bool(r, "Silenceable", true);
                c.aux_reset = bool(r, "AuxReset", false);
                int loop_type = -1;
                int dev_type;
                if (panel == Panel.FX4000) {
                    c.sub = num(r, "SubAddr");
                    Row d = devices.get(num(r, "DevNo"));
                    if (d == null) {
                        (output ? outputs : inputs).put(c.ckt, c);
                        continue;
                    }
                    c.node = num(d, "NodeNo");
                    c.cpu = num(d, "CPUNo");
                    c.loop = num(d, "LoopNo");
                    c.addr = num(d, "DevAddr");
                    dev_type = num(d, "DevType");
                    loop_type = loop_types.getOrDefault(c.node + "/" + c.cpu + "/" + c.loop, -1);
                } else {
                    //FX6000 / FX400 / FX2000: each circuit row carries its own loop and address (FX400/FX2000 have one node)
                    c.node = nodeOf(r);
                    c.loop = num(r, "LoopNo");
                    c.addr = num(r, "DevAddr");
                    c.cpu = loop_cpu.getOrDefault(c.node + "/" + c.loop, 0);
                    dev_type = num(r, "DevType");
                    loop_type = loop_types.getOrDefault(c.node + "/" + c.loop, -1);
                    int primary = r.containsKey("PrimDevAddr") ? num(r, "PrimDevAddr") : -1;
                    if (panel == Panel.FX6000) {
                        if (primary >= 0 && primary != c.addr) c.primary_addr = primary;
                    } else if (panel == Panel.FX400 && c.addr > FX400_MAX_ADDRESS && primary >= 0) {
                        //FX400 keeps the second input/relay of a dual module at a pseudo address (241+) pointing back
                        c.primary_addr = primary;
                        c.sub = 2;
                    }
                    if (r.containsKey("COFlag")) c.co_flag = bool(r, "COFlag", false);
                    if (panel == Panel.FX400 && !output && r.containsKey("ClassA")) c.class_a = bool(r, "ClassA", false);
                }
                c.device_type = device_types.getOrDefault(dev_type, "type " + dev_type);
                c.loop_type = loop_type_names.getOrDefault(loop_type, "");
                if (panel == Panel.FX6000) c.sub = subFromDeviceType(c.device_type);

                if (loop_type == LOOP_TYPE_INPUT_ZONE && panel == Panel.FX4000) c.kind = Kind.INPUT_ZONE;
                else if (loop_type == LOOP_TYPE_OUTPUT_ZONE && panel == Panel.FX4000) c.kind = Kind.OUTPUT_ZONE;
                else if (panel == Panel.FX6000 && c.device_type.equalsIgnoreCase("Input Zone")) c.kind = Kind.INPUT_ZONE;
                else if (c.cpu == 0 && (panel == Panel.FX4000 || "Conventional".equals(c.loop_type))) c.kind = Kind.PANEL;
                (output ? outputs : inputs).put(c.ckt, c);
            }
        }
    }

    //Highest real address on an FX400 loop; higher ones are the second halves of dual modules
    static final int FX400_MAX_ADDRESS = 240;

    //FX4000/FX6000 tables have NodeNo; FX400/FX2000 jobs are one node, numbered 1 here
    static int nodeOf(Row r) {
        return r.containsKey("NodeNo") ? num(r, "NodeNo") : 1;
    }

    //FX6000 names the extra inputs of one device Dual Monitor "B" (AP), Dual Heat Photo "C" (AP), ...
    private static int subFromDeviceType(String type) {
        if (type.contains("\"B\"")) return 2;
        if (type.contains("\"C\"")) return 3;
        if (type.contains("\"D\"")) return 4;
        return 1;
    }

    private void loadShared(Database db) throws IOException {
        Map<Integer, String> frame_type_names = lookup(db, "SYSFrameTypes", "FrameType", "Tag");
        Map<Integer, String> cpu_type_names = lookup(db, "SYSCPUTypes", "CPUType", "Tag");
        status_names.putAll(lookup(db, "SYSStatusTypes", "Status", "Tag"));
        for (Row r : rows(db, "NodeConfig")) if (mine(r) && r.containsKey("Tag")) node_names.put(nodeOf(r), str(r, "Tag").trim());
        for (Row r : rows(db, "CPUConfig")) {
            if (!mine(r)) continue;
            String tag = str(r, "Tag").trim();
            String type = cpu_type_names.getOrDefault(num(r, "CPUType"), "");
            cpu_types.put(nodeOf(r) + "/" + num(r, "CPUNo"), type + (tag.isEmpty() ? "" : " " + tag));
        }
        Map<Integer, String[]> led_type_names = new HashMap<>();
        for (Row r : db.getTable("SYSLedTypes")) led_type_names.put(num(r, "LedType"), new String[] { str(r, "DisplayType").trim(), str(r, "Description").trim() });

        for (Row r : rows(db, "IptZoneCorrs")) if (mine(r)) zone_links.add(new int[] { num(r, "IptCkt"), num(r, "IptZone") });
        for (Row r : rows(db, "IOCorrs")) if (mine(r)) io_links.add(new int[] { num(r, "IptCkt"), num(r, "OptCkt") });
        for (Row r : rows(db, "StatusCorrs")) if (mine(r)) status_links.add(new int[] { num(r, "Status"), num(r, "OptCkt") });

        for (Row r : rows(db, "DisplayFrames")) {
            if (mine(r)) {
                frame_types.put(nodeOf(r) + "/" + num(r, "CPUNo") + "/" + num(r, "FrameNo"),
                    frame_type_names.getOrDefault(num(r, "FrameType"), "type " + num(r, "FrameType")));
            }
        }

        String[] state_columns = { "AllActive", "Alert", "SomeActive", "PreAlarm", "Unbypass", "ManCtrl0", "ManCtrl1", "ManCtrl2", "Trouble" };
        for (Row r : rows(db, "LedDefinitions")) {
            if (!mine(r)) continue;
            Led l = new Led();
            l.group = num(r, "LedGrp");
            l.led_type = num(r, "LedType");
            String[] type = led_type_names.get(l.led_type);
            l.display_type = type == null ? "" : type[0];
            l.description = type == null ? "" : type[1];
            l.led_type_name = type == null ? "type " + l.led_type : type[0] + " / " + type[1];
            l.tag = str(r, "Tag");
            for (String s : state_columns) l.states.put(s, num(r, s));
            leds.put(l.group, l);
        }
        for (Row r : rows(db, "LedLookup")) {
            if (!mine(r)) continue;
            Led l = leds.get(num(r, "LedGrp"));
            if (l != null) l.slots.add(new int[] { nodeOf(r), num(r, "CPUNo"), num(r, "FrameNo"), num(r, "LedNo") });
        }
        for (Row r : rows(db, "LedIpts")) if (mine(r)) led_input_links.add(new int[] { num(r, "LedGrp"), num(r, "IptCkt") });
        for (Row r : rows(db, "LedOpts")) if (mine(r)) led_output_links.add(new int[] { num(r, "LedGrp"), num(r, "OptCkt") });
        for (Row r : rows(db, "LedProvingStat")) if (mine(r)) led_proving_links.add(new int[] { num(r, "LedGrp"), num(r, "ProvingGrp") });
        for (Row r : rows(db, "SYSLedStates")) led_state_names.put(num(r, "Condition"), str(r, "Description"));
        for (Row r : rows(db, "ProvingIpts")) {
            if (!mine(r)) continue;
            Proving p = provings.computeIfAbsent(num(r, "ProvingGrp"), k -> new Proving());
            p.group = num(r, "ProvingGrp");
            p.zone = inputs.get(num(r, "IptCkt"));
        }
        for (Row r : rows(db, "ProvingCtrl")) {
            if (!mine(r)) continue;
            Proving p = provings.computeIfAbsent(num(r, "ProvingGrp"), k -> new Proving());
            p.group = num(r, "ProvingGrp");
            Circuit o = outputs.get(num(r, "OptCkt"));
            if (o != null) p.outputs.add(o);
        }

        for (Row r : rows(db, "Equations")) {
            if (!mine(r)) continue;
            Equation e = new Equation();
            e.number = num(r, "EquationNo");
            if (r.containsKey("NodeNo")) e.node = num(r, "NodeNo");
            e.tag = str(r, "Tag");
            e.text = str(r, "Equation");
            e.comments = str(r, "Comments");
            equations.put(e.number, e);
        }
        for (Row r : rows(db, "EquationInput")) {
            Equation e = mine(r) ? equations.get(num(r, "EquationNo")) : null;
            if (e != null) e.inputs.put(num(r, "Sequence"), num(r, "IptCkt"));
        }
        for (Row r : rows(db, "EquationSequence")) {
            Equation e = mine(r) ? equations.get(num(r, "EquationNo")) : null;
            if (e != null) e.sequence.add(new int[] { num(r, "Sequence"), num(r, "Term"), num(r, "Value"), num(r, "Mask") });
        }
        for (Equation e : equations.values()) e.sequence.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (Row r : rows(db, "EquationSubjIpt")) {
            Equation e = mine(r) ? equations.get(num(r, "EquationNo")) : null;
            Circuit c = inputs.get(num(r, "IptCkt"));
            if (e != null && c != null) e.subjects.add(c);
        }
        for (Equation e : equations.values()) if (e.node < 0 && !e.subjects.isEmpty()) e.node = e.subjects.get(0).node;

        for (Row r : rows(db, "ManCtrlGroups")) {
            if (!mine(r)) continue;
            ControlGroup g = new ControlGroup();
            g.group = num(r, "CtrlGrp");
            g.type = num(r, "CtrlType");
            controls.put(g.group, g);
        }
        for (Row r : rows(db, "SwManCtrl")) {
            ControlGroup g = mine(r) ? controls.get(num(r, "CtrlGrp")) : null;
            if (g != null) g.switches.add(new int[] { nodeOf(r), num(r, "CPUNo"), num(r, "FrameNo"), num(r, "SwitchNo") });
        }
        for (Row r : rows(db, "ManCtrlOpts")) {
            ControlGroup g = mine(r) ? controls.get(num(r, "CtrlGrp")) : null;
            Circuit c = outputs.get(num(r, "OptCkt"));
            if (g != null && c != null) g.outputs.add(c);
        }

        //FX400: named bypass groups instead of a bypass switch (kept apart from control group numbers)
        Map<Integer, ControlGroup> bypass_groups = new HashMap<>();
        for (Row r : rows(db, "BypGrps")) {
            if (!mine(r)) continue;
            ControlGroup g = new ControlGroup();
            g.group = 10000 + num(r, "BypGrpNo");
            g.type = CTRL_TYPE_BYPASS;
            g.name = str(r, "Tag").trim();
            if (g.name.isEmpty()) g.name = "Group " + num(r, "BypGrpNo");
            bypass_groups.put(num(r, "BypGrpNo"), g);
            controls.put(g.group, g);
        }
        for (Row r : rows(db, "BypOptCkts")) {
            ControlGroup g = mine(r) ? bypass_groups.get(num(r, "BypGrpNo")) : null;
            Circuit c = outputs.get(num(r, "CktNo"));
            if (g != null && c != null) g.outputs.add(c);
        }

        //Circuits whose correlations are set somewhere else in the configurator
        for (Row r : rows(db, "OptZnCorrs")) if (mine(r)) locked_outputs.put(num(r, "OptCkt"), "It is in an output zone; the configurator does not allow direct input correlations to it.");
        for (Row r : rows(db, "HazardOptCkts")) if (mine(r)) locked_outputs.put(num(r, "CktNo"), "It is in hazard zone " + num(r, "ZoneNo") + "; change it there in the configurator.");
        for (Row r : rows(db, "HazardIptCkts")) if (mine(r)) locked_inputs.put(num(r, "CktNo"), "It is in hazard zone " + num(r, "ZoneNo") + "; change it there in the configurator.");
        for (Row r : rows(db, "RelayGrpOpts")) if (mine(r)) locked_outputs.put(num(r, "OptCkt"), "It is in Equipment Set " + num(r, "RelayGrp") + "; its relays must keep the same correlations, so change them in the configurator.");
    }

    private void index() {
        for (int[] link : zone_links) {
            Circuit src = inputs.get(link[0]);
            Circuit zone = inputs.get(link[1]);
            if (src == null || zone == null) continue;
            zones_of.computeIfAbsent(src.ckt, k -> new ArrayList<>()).add(zone);
            sources_of.computeIfAbsent(zone.ckt, k -> new ArrayList<>()).add(src);
        }
        for (int[] link : io_links) {
            Circuit in = inputs.get(link[0]);
            Circuit out = outputs.get(link[1]);
            if (in == null || out == null) continue;
            direct_outputs.computeIfAbsent(in.ckt, k -> new ArrayList<>()).add(out);
            direct_inputs.computeIfAbsent(out.ckt, k -> new ArrayList<>()).add(in);
        }
        for (int[] link : led_input_links) {
            Led l = leds.get(link[0]);
            Circuit c = inputs.get(link[1]);
            if (l == null || c == null) continue;
            l.inputs.add(c);
            leds_of_input.computeIfAbsent(c.ckt, k -> new ArrayList<>()).add(l);
        }
        for (int[] link : led_output_links) {
            Led l = leds.get(link[0]);
            Circuit c = outputs.get(link[1]);
            if (l != null && c != null) l.outputs.add(c);
        }
        for (int[] link : led_proving_links) {
            Led l = leds.get(link[0]);
            if (l != null) l.provings.add(link[1]);
        }
        for (Equation e : equations.values()) {
            for (Circuit c : e.subjects) equations_of_subject.computeIfAbsent(c.ckt, k -> new ArrayList<>()).add(e);
        }
    }

    //Rows of a table, or none if this configurator version does not have it
    private static Iterable<Row> rows(Database db, String table) throws IOException {
        Table t = db.getTable(table);
        return t == null ? List.of() : t;
    }

    private static Map<Integer, String> lookup(Database db, String table, String key, String value) throws IOException {
        Map<Integer, String> map = new HashMap<>();
        for (Row r : rows(db, table)) map.put(num(r, key), str(r, value));
        return map;
    }

    static int num(Row r, String column) {
        Object o = r.get(column);
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof Boolean) return ((Boolean) o) ? 1 : 0;
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    static boolean bool(Row r, String column, boolean fallback) {
        Object o = r.get(column);
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        return fallback;
    }

    static String str(Row r, String column) {
        Object o = r.get(column);
        return o == null ? "" : o.toString();
    }
}
