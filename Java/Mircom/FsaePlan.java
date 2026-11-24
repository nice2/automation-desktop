import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

//Everything the FSAE bot enters for one job. Each floor gets 4 input zones in a row (Normal, Low Heat,
//High Heat, Smoke Det), a Normal logic equation over its dual heat, and 4 graphic adder entries.
//The three lists (inputZones, logic, adder) are exactly what the bot types, in order, so the GUI shows them as-is.
public class FsaePlan {

    public static final int TAG_LENGTH = 20; //per tag line, longer tags get cut off by the configurator

    //The panels differ in how the logic is written and in the graphic adder's extra tag column
    public enum PanelType {
        FX6000("FX6000 (MGC Configurator)", 3, "-", "F", "A", false),
        FX4000("FX4000 (MGC-4000 Configurator)", 4, "", "Trouble", "Input", true);

        final String label;
        final int digits; //IZ addresses are 3 digits on FX6000/Flexnet, 4 on FX4000
        final String dash; //FX6000: 01-00-**-IZ-085, FX4000: 01-**-**IZ-0085
        final String trouble;
        final String alarm;
        final boolean adder_tag2; //FX4000's graphic adder has a Tag2 column to step over

        PanelType(String label, int digits, String dash, String trouble, String alarm, boolean adder_tag2) {
            this.label = label;
            this.digits = digits;
            this.dash = dash;
            this.trouble = trouble;
            this.alarm = alarm;
            this.adder_tag2 = adder_tag2;
        }

        int maxAddress() {
            return digits == 3 ? 999 : 9999;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    //The 4 input zones made for every floor, in address order
    public enum ZoneType {
        NORMAL("Normal", "Normal", KeyEvent.VK_M, "Monitor", "Monitor Stat"),
        LOW_HEAT("Low Heat", "LoHeat", KeyEvent.VK_S, "Non-Latch Supv", "Supervisory Stat"),
        HIGH_HEAT("High Heat", "HiHeat", KeyEvent.VK_A, "Alarm Input", "Alarm Stat"),
        SMOKE("Smoke Det", "SmokeD", KeyEvent.VK_A, "Alarm Input", "Alarm Stat");

        final String default_iz_word;
        final String default_adder_word;
        final int key; //picks the input zone type and the graphic adder assignment
        final String iz_type;
        final String adder_assignment;

        ZoneType(String default_iz_word, String default_adder_word, int key, String iz_type, String adder_assignment) {
            this.default_iz_word = default_iz_word;
            this.default_adder_word = default_adder_word;
            this.key = key;
            this.iz_type = iz_type;
            this.adder_assignment = adder_assignment;
        }
    }

    //One floor as typed in the GUI. Blank group/node/loop = same as the row above, blank address = row above + 4.
    //Loop is FX6000 only: 00 for the panel's own input zones, otherwise the loop whose input zone list holds them.
    public static class Floor {
        public String group = "";
        public String name = "";
        public String node = "";
        public String loop = "";
        public String address = "";

        public Floor() {
        }

        public Floor(String group, String name, String node, String address) {
            this(group, name, node, "", address);
        }

        public Floor(String group, String name, String node, String loop, String address) {
            this.group = group == null ? "" : group.trim();
            this.name = name == null ? "" : name.trim();
            this.node = node == null ? "" : node.trim();
            this.loop = loop == null ? "" : loop.trim();
            this.address = address == null ? "" : address.trim();
        }

        Floor copy() {
            return new Floor(group, name, node, loop, address);
        }
    }

    //A floor with the blanks filled in
    public static class Resolved {
        public final int row; //position in the floors table
        public final Floor floor;
        public String group = "";
        public int node = 1;
        public String loop = "00"; //as written in the logic: 00, 02 ... on FX6000, ** on FX4000
        public int address = -1; //Normal IZ address, -1 if it couldn't be worked out
        public final List<String> errors = new ArrayList<>();

        Resolved(int row, Floor floor) {
            this.row = row;
            this.floor = floor;
        }

        public boolean hasAddress() {
            return address > 0;
        }

        //Same input zone list in the configurator
        boolean sameList(Resolved other) {
            return other != null && node == other.node && loop.equals(other.loop);
        }
    }

    //One row of a step's list. problems block the run, warnings don't.
    public static abstract class Entry {
        public final Resolved floor;
        public final List<String> problems = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public String note = ""; //what the user has to do before the bot gets here, if anything

        Entry(Resolved floor) {
            this.floor = floor;
            problems.addAll(floor.errors);
        }

        public abstract String describe();
    }

    public static class InputZone extends Entry {
        public final ZoneType type;
        public final int node;
        public final String loop;
        public final int address;
        public final String tag1;
        public final String tag2;
        public boolean select_first = false; //not right after the previous zone, so the user has to select it

        InputZone(Resolved floor, ZoneType type, int address, String tag1, String tag2) {
            super(floor);
            this.type = type;
            this.node = floor.node;
            this.loop = floor.loop;
            this.address = address;
            this.tag1 = tag1;
            this.tag2 = tag2;
        }

        @Override
        public String describe() {
            return "IZ " + address + " (" + listName(node, loop) + ") " + tag1 + (tag2.isEmpty() ? "" : " / " + tag2);
        }
    }

    public static class Logic extends Entry {
        public final int node;
        public final int address;
        public final String low_heat;
        public final String high_heat;
        public final String equation;
        public final String name;
        public final String comment;

        Logic(Resolved floor, String low_heat, String high_heat, String equation, String name, String comment) {
            super(floor);
            this.node = floor.node;
            this.address = floor.address;
            this.low_heat = low_heat;
            this.high_heat = high_heat;
            this.equation = equation;
            this.name = name;
            this.comment = comment;
        }

        @Override
        public String describe() {
            return name + " (Normal IZ " + address + ", node " + node + ")";
        }
    }

    public static class AdderEntry extends Entry {
        public final ZoneType type;
        public final int adder; //1-based graphic adder number, counted from the one the run starts in
        public final int row; //1-based row inside that adder
        public final String tag;
        public boolean new_adder = false; //first row of the next adder: the bot pauses so the user can move to it

        AdderEntry(Resolved floor, ZoneType type, int adder, int row, String tag) {
            super(floor);
            //The adder doesn't use the node or addresses, so only a missing floor name matters here
            problems.clear();
            if(floor.floor.name.isEmpty()) {
                problems.add("floor name is blank");
            }
            this.type = type;
            this.adder = adder;
            this.row = row;
            this.tag = tag;
        }

        @Override
        public String describe() {
            return "adder " + adder + " row " + row + ": " + tag;
        }
    }

    // ---------------------------------------------------------------- settings

    public PanelType panel = PanelType.FX6000;
    public final Map<ZoneType, String> iz_words = new LinkedHashMap<>();
    public final Map<ZoneType, String> adder_words = new LinkedHashMap<>();
    public String iz_tag1 = "{type} {floor} {group}";
    public String iz_tag2 = ""; //blank = leave Tag (Line 2) alone
    public String adder_tag = "{type} {floor} {group}";
    public String logic_name = "NORMAL {floor} {group}";
    public String logic_comment = "NORMAL {floor} {group} - Dual Heat Not In Alarm Or Trouble";
    public int adder_first_row = 1; //first empty row of the graphic adder the run starts in
    public int adder_rows = 48; //rows per graphic adder

    public final List<Floor> floors = new ArrayList<>();

    public FsaePlan() {
        for(ZoneType type : ZoneType.values()) {
            iz_words.put(type, type.default_iz_word);
            adder_words.put(type, type.default_adder_word);
        }
    }

    public FsaePlan copy() {
        FsaePlan p = new FsaePlan();
        p.panel = panel;
        p.iz_words.putAll(iz_words);
        p.adder_words.putAll(adder_words);
        p.iz_tag1 = iz_tag1;
        p.iz_tag2 = iz_tag2;
        p.adder_tag = adder_tag;
        p.logic_name = logic_name;
        p.logic_comment = logic_comment;
        p.adder_first_row = adder_first_row;
        p.adder_rows = adder_rows;
        for(Floor f : floors) {
            p.floors.add(f.copy());
        }
        return p;
    }

    // ---------------------------------------------------------------- working out the lists

    //Fills in the blanks: group/node carry down, a blank address is the row above + 4 on the same node
    public List<Resolved> resolve() {
        List<Resolved> result = new ArrayList<>();
        Resolved previous = null;

        for(int i = 0; i < floors.size(); i++) {
            Floor f = floors.get(i);
            Resolved r = new Resolved(i, f);

            r.group = !f.group.isEmpty() ? f.group : previous == null ? "" : previous.group;

            if(!f.node.isEmpty()) {
                try {
                    r.node = Integer.parseInt(f.node);
                    if(r.node < 1 || r.node > 99) {
                        r.errors.add("node must be 1-99");
                    }
                } catch (NumberFormatException e) {
                    r.errors.add("node \"" + f.node + "\" is not a number");
                    r.node = previous == null ? 1 : previous.node;
                }
            }
            else {
                r.node = previous == null ? 1 : previous.node;
            }

            if(panel == PanelType.FX4000) {
                r.loop = "**";
            }
            else if(!f.loop.isEmpty()) {
                try {
                    int loop = Integer.parseInt(f.loop);
                    if(loop < 0 || loop > 99) {
                        r.errors.add("loop must be 0-99");
                    }
                    r.loop = String.format("%02d", Math.max(0, loop));
                } catch (NumberFormatException e) {
                    r.errors.add("loop \"" + f.loop + "\" is not a number");
                }
            }
            else {
                r.loop = previous == null || previous.loop.equals("**") ? "00" : previous.loop;
            }

            if(!f.address.isEmpty()) {
                try {
                    r.address = Integer.parseInt(f.address);
                } catch (NumberFormatException e) {
                    r.errors.add("IZ address \"" + f.address + "\" is not a number");
                }
            }
            else if(previous != null && previous.hasAddress() && r.sameList(previous)) {
                r.address = previous.address + 4;
            }
            else {
                r.errors.add(previous == null ? "the first floor needs its Normal IZ address"
                    : "node or loop changed - enter this floor's Normal IZ address");
            }

            if(r.address != -1 && (r.address < 1 || r.address + 3 > panel.maxAddress())) {
                r.errors.add("IZ " + r.address + "-" + (r.address + 3) + " is outside 1-" + panel.maxAddress());
            }
            if(f.name.isEmpty()) {
                r.errors.add("floor name is blank");
            }

            result.add(r);
            previous = r;
        }

        //Two floors can't share input zones
        for(int a = 0; a < result.size(); a++) {
            for(int b = a + 1; b < result.size(); b++) {
                Resolved x = result.get(a), y = result.get(b);
                if(x.hasAddress() && y.hasAddress() && x.sameList(y) && Math.abs(x.address - y.address) < 4) {
                    String clash = "IZ " + x.address + "-" + (x.address + 3) + " overlaps IZ " + y.address + "-" + (y.address + 3) + " (" + listName(x.node, x.loop) + ")";
                    x.errors.add(clash + " of row " + (y.row + 1));
                    y.errors.add(clash + " of row " + (x.row + 1));
                }
            }
        }

        return result;
    }

    //Floors in the order their input zones sit in the configurator: by node, then address
    private List<Resolved> byAddress(List<Resolved> resolved) {
        List<Resolved> sorted = new ArrayList<>(resolved);
        sorted.sort(Comparator.comparingInt((Resolved r) -> r.node)
            .thenComparing(r -> r.loop)
            .thenComparingInt(r -> r.hasAddress() ? r.address : Integer.MAX_VALUE)
            .thenComparingInt(r -> r.row));
        return sorted;
    }

    //Step 1: Tag/Type for 4 input zones per floor, in configurator order. The bot moves down one row
    //after each, so a gap in the addresses or a new node means the user selects the next zone first.
    public List<InputZone> inputZones() {
        List<InputZone> list = new ArrayList<>();
        InputZone previous = null;

        for(Resolved r : byAddress(resolve())) {
            ZoneType[] types = ZoneType.values();
            for(int i = 0; i < types.length; i++) {
                ZoneType type = types[i];
                String word = iz_words.get(type);
                InputZone z = new InputZone(r, type, r.address + i,
                    fill(iz_tag1, word, r), iz_tag2.isBlank() ? "" : fill(iz_tag2, word, r));
                checkLength(z, "tag line 1", z.tag1);
                checkLength(z, "tag line 2", z.tag2);

                if(previous == null || !previous.floor.sameList(r) || previous.address + 1 != z.address) {
                    z.select_first = true;
                    z.note = "Select " + izName(r, z.address) + " first";
                }
                list.add(z);
                previous = z;
            }
        }
        return list;
    }

    //Step 2: one equation per floor, entered in the Normal zone's Advanced Logic window
    public List<Logic> logic() {
        List<Logic> list = new ArrayList<>();
        for(Resolved r : byAddress(resolve())) {
            String low = izReference(r, r.address + 1);
            String high = izReference(r, r.address + 2);
            String n = "%n"; //same line breaks the configurator has always been given

            String equation = String.format("NOT ANY 1 OF (  " + n +
                " %s:%s ,  " + n +
                " %s:%s ,  " + n +
                "  " + n +
                "  " + n +
                " %s:%s ,  " + n +
                " %s:%s ) ",
                low, panel.trouble, high, panel.trouble, low, panel.alarm, high, panel.alarm);
            if(panel == PanelType.FX4000) {
                equation = "if ( \n" + equation + "\n ) then use IptAlarm \n else use IptNormal";
            }

            Logic l = new Logic(r, low, high, equation, fill(logic_name, "", r), fill(logic_comment, "", r));
            checkLength(l, "logic name", l.name);
            l.note = "Open Advanced Logic for " + izName(r, r.address);
            list.add(l);
        }
        return list;
    }

    //Step 3: graphic adder. Floors are grouped by Group in table order; each group gets all its Normals,
    //then Low Heats, High Heats and Smoke Dets. Adders hold adder_rows entries, then the next adder is used.
    public List<AdderEntry> adder() {
        List<Resolved> resolved = resolve();
        List<String> groups = new ArrayList<>();
        for(Resolved r : resolved) {
            if(!groups.contains(r.group)) {
                groups.add(r.group);
            }
        }

        List<AdderEntry> list = new ArrayList<>();
        int rows = Math.max(1, adder_rows);
        int position = Math.max(0, Math.min(rows - 1, adder_first_row - 1));

        for(String group : groups) {
            for(ZoneType type : ZoneType.values()) {
                for(Resolved r : resolved) {
                    if(!r.group.equals(group)) {
                        continue;
                    }
                    AdderEntry e = new AdderEntry(r, type, position / rows + 1, position % rows + 1,
                        fill(adder_tag, adder_words.get(type), r));
                    checkLength(e, "tag", e.tag);
                    if(list.isEmpty()) {
                        e.note = "Select adder row " + e.row + " first";
                    }
                    else if(e.row == 1) {
                        e.new_adder = true;
                        e.note = "Move to the next graphic adder, select row 1";
                    }
                    list.add(e);
                    position++;
                }
            }
        }
        return list;
    }

    public String izName(Resolved r, int address) {
        return "IZ-" + pad(address) + " on " + listName(r.node, r.loop);
    }

    static String listName(int node, String loop) {
        return "node " + node + (loop.equals("00") || loop.equals("**") ? "" : " loop " + loop);
    }

    //How the logic refers to an input zone, e.g. 01-00-**-IZ-085 (FX6000) or 01-**-**IZ-0085 (FX4000)
    public String izReference(Resolved r, int address) {
        return String.format("%02d-%s-**%sIZ-%s", r.node, r.loop, panel.dash, pad(address));
    }

    private String pad(int address) {
        return String.format("%0" + panel.digits + "d", Math.max(0, address));
    }

    //Fills {type} {floor} {group} and tidies the spaces left by blank ones
    static String fill(String template, String type_word, Resolved r) {
        String text = template
            .replace("{type}", type_word == null ? "" : type_word)
            .replace("{floor}", r.floor.name)
            .replace("{group}", r.group);
        return text.trim().replaceAll(" {2,}", " ");
    }

    private static void checkLength(Entry e, String what, String text) {
        if(text.length() > TAG_LENGTH) {
            e.warnings.add(what + " is " + text.length() + " characters - the configurator keeps the first " + TAG_LENGTH
                + " (\"" + text.substring(0, TAG_LENGTH) + "\")");
        }
    }

    // ---------------------------------------------------------------- floor list helpers

    //Expands "EMR, Roof, 26-14, Cell" into EMR, Roof, 26FL, 25FL ... 14FL, Cell
    public static List<String> expandFloors(String spec, String suffix) {
        List<String> names = new ArrayList<>();
        for(String part : spec.split("[,;\\n]")) {
            String token = part.trim();
            if(token.isEmpty()) {
                continue;
            }

            java.util.regex.Matcher range = java.util.regex.Pattern.compile("(\\d+)\\s*(?:-|to)\\s*(\\d+)").matcher(token);
            if(range.matches()) {
                int from = Integer.parseInt(range.group(1));
                int to = Integer.parseInt(range.group(2));
                int step = from <= to ? 1 : -1;
                for(int n = from; n != to + step; n += step) {
                    names.add(n + suffix);
                }
            }
            else if(token.matches("\\d+")) {
                names.add(token + suffix);
            }
            else {
                names.add(token);
            }
        }
        return names;
    }

    // ---------------------------------------------------------------- saving / loading

    public void save(File file) throws Exception {
        Properties p = new Properties();
        p.setProperty("panel", panel.name());
        for(ZoneType type : ZoneType.values()) {
            p.setProperty("word.iz." + type.name(), iz_words.get(type));
            p.setProperty("word.adder." + type.name(), adder_words.get(type));
        }
        p.setProperty("template.izTag1", iz_tag1);
        p.setProperty("template.izTag2", iz_tag2);
        p.setProperty("template.adderTag", adder_tag);
        p.setProperty("template.logicName", logic_name);
        p.setProperty("template.logicComment", logic_comment);
        p.setProperty("adder.firstRow", String.valueOf(adder_first_row));
        p.setProperty("adder.rows", String.valueOf(adder_rows));
        p.setProperty("floors", String.valueOf(floors.size()));
        for(int i = 0; i < floors.size(); i++) {
            Floor f = floors.get(i);
            String key = String.format("floor.%03d.", i + 1);
            p.setProperty(key + "group", f.group);
            p.setProperty(key + "name", f.name);
            p.setProperty(key + "node", f.node);
            p.setProperty(key + "loop", f.loop);
            p.setProperty(key + "address", f.address);
        }

        File parent = file.getAbsoluteFile().getParentFile();
        if(parent != null) {
            parent.mkdirs();
        }
        //Write next to it first so a failed save can't wipe the job
        File temp = new File(file.getAbsolutePath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            p.store(w, "FSAE job for the Mircom data entry bot - edit it in the bot window's FSAE tab");
        }
        Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static FsaePlan load(File file) throws Exception {
        if(file.getName().toLowerCase().endsWith(".txt")) {
            return importText(file);
        }

        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            p.load(r);
        }

        FsaePlan plan = new FsaePlan();
        try {
            plan.panel = PanelType.valueOf(p.getProperty("panel", "FX6000"));
        } catch (IllegalArgumentException e) {
            plan.panel = PanelType.FX6000;
        }
        for(ZoneType type : ZoneType.values()) {
            plan.iz_words.put(type, p.getProperty("word.iz." + type.name(), type.default_iz_word));
            plan.adder_words.put(type, p.getProperty("word.adder." + type.name(), type.default_adder_word));
        }
        plan.iz_tag1 = p.getProperty("template.izTag1", plan.iz_tag1);
        plan.iz_tag2 = p.getProperty("template.izTag2", plan.iz_tag2);
        plan.adder_tag = p.getProperty("template.adderTag", plan.adder_tag);
        plan.logic_name = p.getProperty("template.logicName", plan.logic_name);
        plan.logic_comment = p.getProperty("template.logicComment", plan.logic_comment);
        plan.adder_first_row = intOf(p.getProperty("adder.firstRow"), plan.adder_first_row);
        plan.adder_rows = intOf(p.getProperty("adder.rows"), plan.adder_rows);

        int count = intOf(p.getProperty("floors"), 0);
        for(int i = 1; i <= count; i++) {
            String key = String.format("floor.%03d.", i);
            plan.floors.add(new Floor(p.getProperty(key + "group"), p.getProperty(key + "name"),
                p.getProperty(key + "node"), p.getProperty(key + "loop"), p.getProperty(key + "address")));
        }
        return plan;
    }

    //The old fsae_zones.txt: one floor per line, the first Normal IZ address in [brackets]
    static FsaePlan importText(File file) throws Exception {
        FsaePlan plan = new FsaePlan();
        String first_address = "";
        List<String> names = new ArrayList<>();
        for(String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            line = line.replace("﻿", "").trim();
            if(line.startsWith("[") && line.endsWith("]")) {
                first_address = String.valueOf(Integer.parseInt(line.substring(1, line.length() - 1).trim()));
            }
            else if(!line.isEmpty()) {
                names.add(line);
            }
        }
        for(int i = 0; i < names.size(); i++) {
            plan.floors.add(new Floor("", names.get(i), i == 0 ? "1" : "", i == 0 ? first_address : ""));
        }
        //Old files put the tower in the floor name and used the same words everywhere
        for(ZoneType type : ZoneType.values()) {
            plan.adder_words.put(type, type.default_iz_word);
        }
        return plan;
    }

    private static int intOf(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
