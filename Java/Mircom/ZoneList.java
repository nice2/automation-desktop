import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.function.Consumer;

public class ZoneList {
    private String FILENAME = "assets/temp_zones.csv";

    //Columns of the converted zone list (Zone, Type, Location, Overrides)
    public static final int TAG1_COLUMN = 1;
    public static final int TAG2_COLUMN = 2;
    public static final int OVERRIDES_COLUMN = 3;

    //Flag overrides understood by readFile. Put ! in front to turn one off.
    public static final String[] OVERRIDE_FLAGS = { "isAR", "isDualInput", "isNS", "isSensor", "isMini" };

    public ArrayList<Zone> zones = new ArrayList<Zone>();
    public boolean CONTAINS_AR = false;
    public boolean CONTAINS_DUAL_HEAT = false;
    public boolean CONTAINS_SPEAKERS = false;
    public ArrayList<String> DUPLICATES = new ArrayList<String>();
    public int AP_START = 1;

    private ArrayList<String> combined_zones = new ArrayList<String>(); // Tag1 + " " + Tag2

    public ZoneList() {

    } // end constructor

    public ZoneList(String filename) {
        FILENAME = filename;
    }

    public void addZone(double address, String tag1, String tag2) {
        if (!isSubZone(address)) {
            zones.add(new Zone(address, tag1, tag2));
        } else {
            Zone last = zones.get(zones.size() - 1);
            last.addSubZone(address, tag1, tag2);
        }

        // Duplicate checking
        String full_zone = tag1 + " " + tag2;

        if(combined_zones.contains(full_zone) && !full_zone.toLowerCase().contains("spare")) {
            DUPLICATES.add(address + " " + full_zone);
        } else {
            combined_zones.add(full_zone);
        }
    }

    public void displayZoneList() {
        for (Zone zone : zones) {
            if (!zone.getType().equals("Blank Device")) {
                System.out.println(zone.getZoneinfo());
                if(zone.getSubAddress() != null){
                    System.out.println("  |__ " + zone.getSubAddress().getZoneinfo());
                }
            }
        }
    }

    public void readFile() throws Exception {
        File temp_file = new File(FILENAME);
        Scanner temp_scan = new Scanner(temp_file);

        String headers = temp_scan.nextLine();

        //Get AP Start from headers if it exists
        int ap_start_index = headers.toLowerCase().indexOf("ap start");

        if(ap_start_index > 0) {
            String nums = "";
            int next_comma = Math.min(headers.indexOf(",", ap_start_index), headers.length());
            if(next_comma < 0) {
                next_comma = headers.length();
            }

            for (char c : headers.substring(ap_start_index, next_comma).toCharArray()) {
                if (Character.isDigit(c)) {
                    nums += c;
                }
            }

            AP_START = Integer.parseInt(nums);
        }

        String[] parts;
        String content = "";

        while (temp_scan.hasNext()) {
            content = temp_scan.nextLine();
            parts = content.split("\\,", -1); //-1 keeps empty cells at the end, e.g. a blank Location
            //System.out.println(parts[0] + " , " + parts[1] + ", " + parts[2]);
            this.addZone(Double.parseDouble(parts[0]), parts[1], parts[2]);

            Zone last_zone = zones.getLast();
            if(isSubZone(Double.parseDouble(parts[0]))) {
                last_zone = last_zone.getSubAddress();
            }

            //Load Overrides from column 4 if they exist. Overrides are separated by ;
            if(parts.length > 3 && parts[3] != null && !parts[3].isEmpty()) {
                last_zone.setOverrides(parts[3].trim());
                String[] overrides = parts[3].split(";");
                boolean toggle;

                for(String over : overrides) {
                    toggle = true;

                    //Override Type
                    if(over.contains("type=")) {
                        String[] new_type = over.split("type=");
                        if(new_type.length > 0) {
                            last_zone.setType(new_type[1]);
                        }
                        else {
                            System.out.println(last_zone.getAddress() + " : invalid type provided");
                        }
                    }

                    if(over.contains("!")) {
                        toggle = false;
                    }

                    //Set AR
                    if(over.contains("isAR")) {
                        last_zone.setAR(toggle);
                    }

                    //Set Dual
                    if(over.contains("isDualInput")) {
                        last_zone.setDualInput(toggle);
                    }

                    //Set NS
                    if(over.contains("isNS")) {
                        last_zone.setNS(toggle);
                    }

                    //Set Sensor
                    if(over.contains("isSensor")) {
                        last_zone.setSensor(toggle);
                    }

                    //Set Mini
                    if(over.contains("isMini")) {
                        last_zone.setMini(toggle);
                    }
                }

                /*
                System.out.println(last_zone.getAddress()
                 +  " type : " + last_zone.getType()
                 +  ", isAR:" + last_zone.isAR()
                 + ", isNS: " + last_zone.isNS()
                 + " , isDual: " + last_zone.isDualInput());
                 */
            }

            if (last_zone.isAR()) {
                CONTAINS_AR = true;
            }

            if (last_zone.getType().equals("Heat Detector") && last_zone.isDualInput()) {
                CONTAINS_DUAL_HEAT = true;
            }

            if (last_zone.getType().equals("Speakers")) {
                CONTAINS_SPEAKERS = true;
            }
        }
        temp_scan.close();
    }

    // ---------------------------------------------------------------- editing the converted file

    //Changes one cell of a device's row, leaving every other line of the file as it was
    public static void editCell(String filename, double address, int column, String value) throws Exception {
        checkCell(value);
        editRow(filename, address, parts -> parts[column] = value);
    }

    //Sets the device type with a type= override. An empty type, or the type Tag 1 already gives, removes the override.
    public static void setTypeOverride(String filename, double address, String type) throws Exception {
        checkCell(type);
        editRow(filename, address, parts -> {
            List<String> tokens = new ArrayList<>();
            for(String token : parts[OVERRIDES_COLUMN].split(";")) {
                if(!token.isBlank() && !token.contains("type=")) {
                    tokens.add(token.trim());
                }
            }

            String automatic = new Zone(address, parts[TAG1_COLUMN], parts[TAG2_COLUMN]).getType();
            if(!type.isEmpty() && !type.equals(automatic)) {
                tokens.add(0, "type=" + type);
            }
            parts[OVERRIDES_COLUMN] = String.join(";", tokens);
        });
    }

    //Tidies an overrides cell ("type=Relay; isMini") and rejects anything readFile wouldn't understand
    public static String normalizeOverrides(String overrides) {
        List<String> tokens = new ArrayList<>();
        for(String token : overrides.split(";")) {
            token = token.trim().replaceAll("\\s*=\\s*", "=");
            if(token.isEmpty()) {
                continue;
            }

            if(token.toLowerCase(Locale.ROOT).startsWith("type=")) {
                String type = token.substring("type=".length()).trim();
                if(type.isEmpty()) {
                    throw new IllegalArgumentException("\"type=\" needs a device type after it, e.g. type=Relay");
                }
                tokens.add("type=" + type);
                continue;
            }

            boolean off = token.startsWith("!");
            String name = off ? token.substring(1).trim() : token;
            String flag = null;
            for(String known : OVERRIDE_FLAGS) {
                if(known.equalsIgnoreCase(name)) {
                    flag = known;
                }
            }
            if(flag == null) {
                throw new IllegalArgumentException("Unknown override \"" + token + "\".\nUse type=<device type> or "
                    + String.join(", ", OVERRIDE_FLAGS) + " (put ! in front to turn one off), separated by ;");
            }
            tokens.add((off ? "!" : "") + flag);
        }

        String result = String.join(";", tokens);
        checkCell(result);
        return result;
    }

    //readFile splits lines on commas, so a cell can't contain one
    private static void checkCell(String value) {
        if(value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("Commas, quotes and line breaks can't be used in the zone list.");
        }
    }

    private static void editRow(String filename, double address, Consumer<String[]> edit) throws Exception {
        Path path = Paths.get(filename);
        String text = Files.readString(path, StandardCharsets.UTF_8);
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = text.split("\r?\n", -1);

        for(int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split(",", -1);
            double row_address;
            try {
                row_address = Double.parseDouble(parts[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if(row_address != address) {
                continue;
            }

            if(parts.length <= OVERRIDES_COLUMN) {
                int old_length = parts.length;
                parts = Arrays.copyOf(parts, OVERRIDES_COLUMN + 1);
                Arrays.fill(parts, old_length, parts.length, "");
            }
            edit.accept(parts);
            lines[i] = String.join(",", parts);
            Files.writeString(path, String.join(newline, lines), StandardCharsets.UTF_8);
            return;
        }

        throw new Exception("Zone " + address + " is not in " + filename);
    }

    public boolean isSubZone(Double address) {
        String decimal = Double.toString(address);
        decimal = decimal.substring(decimal.length() - 1, decimal.length());

        if (decimal.equals("1") || decimal.equals("0")) {
            return false;
        } else if (decimal.equals("2")) {
            return true;
        }

        return false;
    }
}
