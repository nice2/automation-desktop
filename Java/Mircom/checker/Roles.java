package checker;

import java.util.regex.Pattern;

import checker.Job.Circuit;

//Works out what a device, input zone or output is from its tag (and device/process type), using the names
//from the Device & Zone Cheat Sheet. Tags are free text, so this is keyword matching; anything not
//recognised is OTHER and the cheat sheet rules leave it alone.
public final class Roles {

    private Roles() {}

    //--------------------------------------------------------------------------------------------------
    //Process types (SYSProcTypes) grouped the way the cheat sheet talks about them

    public enum Family { ALARM, SUPERVISORY, LATCHED, TROUBLE, MONITOR, OUTPUT, NONE }

    //By name, because the configurators number some types differently (FX400: 7 = Building/Property Safety,
    //10 = Hazard Release, 12 = CO Latched Supervised; FX2000: 9 = Non-Latch Alarm)
    public static Family family(Circuit c) {
        String n = c.process_name.toLowerCase();
        if (n.contains("supv") || n.contains("supervis")) return n.contains("non") ? Family.SUPERVISORY : Family.LATCHED;
        if (n.contains("alarm") || n.contains("alm") || n.contains("waterflow") || n.contains("pull") || n.contains("hazard")) return Family.ALARM;
        if (n.contains("trouble")) return Family.TROUBLE;
        if (n.contains("monitor") || n.contains("building") || n.contains("remote")) return Family.MONITOR;
        if (n.equals("signal") || n.equals("strobe") || n.equals("relay")) return Family.OUTPUT;
        if (!n.startsWith("type ")) return Family.NONE;
        switch (c.process_type) { //name missing: fall back to the MGC numbering
            case 0: case 1: case 2: case 5: case 10: return Family.ALARM; //Verified, Alarm Input, Priority, Waterflow, Manual Pull
            case 3: return Family.LATCHED;
            case 4: return Family.SUPERVISORY; //Non-Latch Supv
            case 6: return Family.TROUBLE;
            case 7: return Family.MONITOR;
            case 13: case 14: case 15: return Family.OUTPUT; //Signal, Strobe, Relay
            default: return Family.NONE;
        }
    }

    //--------------------------------------------------------------------------------------------------
    //Device inputs

    public enum Device {
        WATERFLOW("Waterflow", Family.ALARM),
        PULL("Pull Station", Family.ALARM),
        ANSUL("Ansul System", Family.ALARM),
        CO("CO Detector", Family.LATCHED),
        LOW_HEAT("Low Heat Detector", Family.SUPERVISORY),
        SMOKE("Smoke Detector", Family.ALARM),
        HEAT("Heat Detector", Family.ALARM),
        SUPERVISORY("supervisory device", Family.SUPERVISORY),
        PSU_TROUBLE("trouble input (power supply / duct cover)", Family.TROUBLE),
        OTHER("device", null);

        public final String label;
        public final Family expected; //what the cheat sheet sets this device to, null = no rule

        Device(String label, Family expected) {
            this.label = label;
            this.expected = expected;
        }
    }

    private static final Pattern WATERFLOW = Pattern.compile("water\\s*flow|waterf|watr\\s*flo|wtr\\s*flo|ps\\s*-?10(?!\\d)|\\bwf\\b");
    private static final Pattern PULL = Pattern.compile("pull");
    private static final Pattern ANSUL = Pattern.compile("ansul");
    private static final Pattern LOW_HEAT = Pattern.compile("lo(w)?\\s*heat");
    private static final Pattern CO = Pattern.compile("\\bco\\b|carbon|co\\s*det|\\bc\\s?o\\s+det");
    private static final Pattern SMOKE = Pattern.compile("smoke|\\bduct|photo|\\bsd\\b|\\bdd\\b");
    private static final Pattern HEAT = Pattern.compile("heat|135|\\bhd\\b");
    //Duct detector cover-open / trouble modules (cheat sheet: Duct Det Cover Open - Trouble Input)
    private static final Pattern DUCT_TROUBLE = Pattern.compile("cover\\s*open|\\bcover\\b|\\btrbl\\b|trouble");
    private static final Pattern PSU = Pattern.compile("inx|psu|power supply|\\bpsp\\b");
    private static final Pattern SUPERVISORY = Pattern.compile(
        "valv|tamper|pump|\\bpmp\\b|jock|generator|gen\\b|radio|door open|damp|fan|power|pwr|fault|key|dry sys|air|rcv|intake|discharge|bypass|"
        + "status|running|\\brun\\b|purge|fail|trouble|troubl|elev|phase|phse|low temp|hoistway|meter|riser|active|shunt|open|closed|"
        + "level|temp|tank|ups\\b|battery|line off|\\b24 ?v|shaft light|trigger|standpipe|sprnklr|sprinkler|\\bts\\b|\\bfsd\\b");

    public static Device device(Circuit c) {
        String type = c.device_type.toLowerCase();
        //FX6000 combo smoke/CO: the "B" input is the CO
        if (type.contains("fire-co") && c.sub == 2) return Device.CO;
        if (type.contains("fire-co")) return Device.SMOKE;
        boolean monitor_module = type.contains("monit") || type.contains("input") || type.contains("ipt mod"); //FX400: Dual Input (4040/1)
        String all = c.words().toLowerCase();
        //INX-10 / power supply inputs and duct detector cover/trouble modules are trouble inputs
        String t1 = Job.Circuit.splitWords(c.tag1()).toLowerCase();
        if (PSU.matcher(t1).find() && (monitor_module || t1.contains("trouble") || t1.contains("fault") || t1.contains("fail"))) return Device.PSU_TROUBLE;
        if (monitor_module && all.contains("duct") && DUCT_TROUBLE.matcher(all).find()) return Device.PSU_TROUBLE;

        Device first = fromWords(t1, monitor_module);
        //Line 2 is usually the location, but some tags put the device there ("1FL Stair B / Pull Station").
        //Only take it when line 1 names nothing specific, and only for kinds that are not also room names
        //("Heat Pump Rm", "Smoke Shaft" are places).
        if (first == Device.OTHER || first == Device.SUPERVISORY) {
            Device second = fromWords(Job.Circuit.splitWords(c.tag2()).toLowerCase(), monitor_module);
            if (second == Device.WATERFLOW || second == Device.PULL || second == Device.CO || second == Device.ANSUL) return second;
            if (first == Device.OTHER && second != Device.OTHER) return second;
        }
        if (first != Device.OTHER) return first;
        //Tag is only a location ("1st Floor Hallway"): detectors still say what they are in their device type
        if (type.contains("photo") || type.contains("ion det") || type.contains("smoke") || type.contains("laser") || type.contains("acclimate")) return Device.SMOKE;
        if (type.contains("heat")) return Device.HEAT;
        return Device.OTHER;
    }

    private static Device fromWords(String t, boolean monitor_module) {
        if (WATERFLOW.matcher(t).find()) return Device.WATERFLOW;
        if (PULL.matcher(t).find()) return Device.PULL;
        if (ANSUL.matcher(t).find()) return Device.ANSUL;
        if (LOW_HEAT.matcher(t).find()) return Device.LOW_HEAT;
        if (CO.matcher(t).find() && !t.contains("smoke")) return Device.CO;
        boolean smoke = SMOKE.matcher(t).find(), heat = HEAT.matcher(t).find();
        //On a monitor module, status words win ("Smoke Damper Status"), except a smoke/heat simply on a module
        if (monitor_module && SUPERVISORY.matcher(t).find()) return Device.SUPERVISORY;
        if (smoke) return Device.SMOKE;
        if (heat) return Device.HEAT;
        if (SUPERVISORY.matcher(t).find()) return Device.SUPERVISORY;
        return Device.OTHER;
    }

    //Smokes/heats the cheat sheet ties to elevator recall: elevator lobby, EMR, top of shaft
    private static final Pattern ELEVATOR = Pattern.compile("elev|\\bemr\\b|machine\\s*r|shaft|hoistway");

    public static boolean elevatorRelated(Circuit c) {
        return ELEVATOR.matcher(c.words().toLowerCase()).find();
    }

    //A floor written in a tag: 1FL, 1 FL, 1F, 1st Fl, 1 Floor (not part of a longer number like 21FL or PS10)
    private static final Pattern FLOOR = Pattern.compile("(?<![a-z0-9])(\\d{1,3})\\s*(?:st|nd|rd|th)?\\s*(?:fl|flr|floor|f)(?![a-z])");

    private static final Pattern BELOW_GRADE = Pattern.compile("\\b(sub\\s*)?cel(l|lar)?\\b|subcel|basement|bsmt|\\bb\\d\\b|lower level");

    //True when the tag places the device on the 1st floor, the usual alternate recall floor
    public static boolean firstFloor(Circuit c) {
        java.util.regex.Matcher m = FLOOR.matcher(c.words().toLowerCase());
        while (m.find()) if (Integer.parseInt(m.group(1)) == 1) return true;
        return false;
    }

    private static final Pattern OTHER_LEVEL = Pattern.compile("\\broof|\\bemr\\b|penthouse|\\bph\\b|bulkhead|blkhd|mezz|ground|lobby");

    //True when the tag says which floor/level the device is on at all
    public static boolean hasFloor(Circuit c) {
        String t = c.words().toLowerCase();
        return FLOOR.matcher(t).find() || BELOW_GRADE.matcher(t).find() || OTHER_LEVEL.matcher(t).find();
    }

    //True when the tag places the device below the 1st floor (cellar, sub-cellar, basement)
    public static boolean belowGrade(Circuit c) {
        return !firstFloor(c) && BELOW_GRADE.matcher(c.words().toLowerCase()).find();
    }

    //--------------------------------------------------------------------------------------------------
    //Input zones

    public enum Zone {
        GENERAL_ALARM("General Alarm"),
        AUTOMATIC_ALARM("Automatic Alarm"),
        NON_LATCHED("Non-Latched Supervisory"),
        LATCHED("Latched Supervisory"),
        WATERFLOW("Waterflow"),
        ELEV_RECALL("Elevator Recall"),
        ALT_RECALL("Alt Elevator Recall"),
        OTHER("input zone");

        public final String label;

        Zone(String label) {
            this.label = label;
        }
    }

    public static Zone zone(Circuit c) {
        String t = Text.norm(c.tag1());
        if (t.contains("generalalarm")) return Zone.GENERAL_ALARM;
        if (t.contains("automaticalarm") || t.startsWith("autoalarm")) return Zone.AUTOMATIC_ALARM;
        if (t.contains("nonlatch")) return Zone.NON_LATCHED;
        if (t.startsWith("latched") || t.equals("cosupv") || t.equals("latchedsupv")) return Zone.LATCHED;
        if (t.startsWith("waterflow") || t.equals("allwaterflows")) return Zone.WATERFLOW;
        if (t.contains("recal") && (t.startsWith("alt") || t.contains("alternate"))) return Zone.ALT_RECALL;
        if (t.contains("elevrecal") || t.contains("elevatorrecal") || t.startsWith("recal")) return Zone.ELEV_RECALL;
        return Zone.OTHER;
    }

    //--------------------------------------------------------------------------------------------------
    //Outputs

    public enum Output {
        NOTIFICATION("strobe / horn circuit"),
        SPEAKER("speaker"),
        SOUNDER("sounder base"),
        ELEV_RECALL("elevator recall / shunt trip relay"),
        ALT_RECALL("alt elevator recall relay"),
        FAN_SHUTDOWN("fan / AC shutdown relay"),
        BOILER_SHUTDOWN("boiler / HWH / laundry shutdown relay"),
        DOOR_RELEASE("door release relay"),
        MUSIC_SHUTDOWN("music shutdown relay"),
        SHAFT_LIGHT("FSAE shaft light relay"),
        GENERATOR_SHUTDOWN("generator shutdown relay"),
        DAMPER("damper / fan relay"),
        SIGNAL_SILENCE("signal silence relay"),
        TROUBLE_TRIP("trouble trip relay"),
        OTHER("output");

        public final String label;

        Output(String label) {
            this.label = label;
        }
    }

    private static final Pattern NOTIFICATION = Pattern.compile("strobe|horn|bell|chime|\\bnac\\b|audible|visual|signal circuit");
    private static final Pattern NOT_NOTIFICATION = Pattern.compile("sync|paging|page");

    //Outputs that are recognised but have no cheat sheet rule (so they are not listed as unrecognised)
    private static final Pattern KNOWN_OTHER = Pattern.compile(
        "^base signal|sync|paging|\\bmic\\b|shutter|shuttr|\\bgas\\b|fuel|stove|oven|heat\\s*trac|water\\s*temp|generat\\w*\\s*(run|fail)|"
        + "central|local alarm|alarm signal|^trouble\\b|^waterflow\\b|^smoke\\b|relay waterflow|relay valve");

    public static boolean knownOther(Circuit c) {
        return KNOWN_OTHER.matcher(c.words().toLowerCase()).find();
    }

    //The first tag line says what the output is, the second where it is ("Fan Shutdown South" / "27FL Boiler Plant"),
    //so the first line decides and the whole tag is only used when the first line says nothing
    public static Output output(Circuit c) {
        Output role = output(c, Job.Circuit.splitWords(c.tag1()).toLowerCase());
        return role != Output.OTHER ? role : output(c, c.words().toLowerCase());
    }

    private static Output output(Circuit c, String t) {
        String type = c.device_type.toLowerCase();
        if (type.contains("sounder") || t.contains("sounder")) return Output.SOUNDER;
        if (t.contains("speaker") || type.contains("watt") || type.startsWith("control")) return Output.SPEAKER;
        if (t.contains("signal silence") || t.contains("sig sil")) return Output.SIGNAL_SILENCE;
        if (t.contains("trip") && (t.contains("trouble") || t.contains("trbl"))) return Output.TROUBLE_TRIP;
        if (t.contains("shaft light") || t.contains("hoistway light")) return Output.SHAFT_LIGHT;
        if (t.contains("recal") || t.contains("shunt")) { //"recal": tags get cut short, e.g. "ElevRecalSouthTowr"
            return t.startsWith("alt") || t.contains("alternate") ? Output.ALT_RECALL : Output.ELEV_RECALL;
        }
        boolean shut = t.contains("shut") || t.contains("shtdwn") || t.contains("shtdn") || t.contains("shtdown") || t.contains("shut dn");
        if (t.contains("music") || (t.contains("audio") && shut)) return Output.MUSIC_SHUTDOWN;
        if (t.contains("door") && (t.contains("release") || t.contains("holder") || t.contains("mag"))) return Output.DOOR_RELEASE;
        if (t.contains("generator") && shut) return Output.GENERATOR_SHUTDOWN;
        if ((t.contains("boiler") || t.contains("boilr") || t.contains("hwh") || t.contains("water heater") || t.contains("laundry")) && shut) {
            return Output.BOILER_SHUTDOWN;
        }
        if ((t.contains("fan") || t.contains("ac ") || t.contains("a/c") || t.contains("hvac") || t.contains("ahu") || t.contains("rtu"))
            && shut) {
            return Output.FAN_SHUTDOWN;
        }
        if (t.contains("damp") || t.contains("purge") || t.contains("fresh air") || t.contains("spf") || t.contains("epf")
            || t.contains("fsd") || t.contains("exhaust") || t.contains("fan") || t.contains("pressuriz")) {
            return Output.DAMPER;
        }
        //Signal circuits are also used for sync, paging-active and other non-notification jobs, so a Signal
        //only counts as a strobe/horn circuit when its tag says so. Strobe-type circuits always count.
        if (NOT_NOTIFICATION.matcher(t).find()) return Output.OTHER;
        if (c.process_type == 14) return Output.NOTIFICATION;
        if (c.process_type == 13 && NOTIFICATION.matcher(t).find()) return Output.NOTIFICATION;
        return Output.OTHER;
    }
}
