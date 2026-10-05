public class Zone {
    private double address;
    private subZone subAddress = null;
    private String type = "";
    private boolean isNS = false;
    private boolean isAR = false;
    private boolean isPullSttn = false;
    private boolean isSensor = false;
    private boolean isDualInput = false;
    private boolean isMini = false;
    private String tag1;
    private String tag2;

    public Zone(double address, String tag1, String tag2) {
        this.address = address;
        this.tag1 = tag1;
        this.tag2 = tag2;

        type = classify(tag1);
    }

    public void addSubZone(double subAddress, String tag1, String tag2) {
        this.isDualInput = true;
        this.subAddress = new subZone(subAddress, tag1, tag2);
    }

    // display all info about zone
    public String getZoneinfo() {
        String zoneInfo = "";
        zoneInfo += Double.toString(address) + " (" + type + ") "  + " (" + tag1 + ") " + " (" + tag2 + ") ";

        if (subAddress != null) {
            zoneInfo = zoneInfo + " --> " + subAddress.getZoneinfo();
        }

        return zoneInfo;
    }

    // returns a device type
    public String classify(String tag) {
        String type = "N/A";

        if (Zone.checkTags(tag, new String[] { "waterf", "ps10", "ps 10", "water flow" })) {
            type = "Alarm Input";
            this.isNS = true;
            this.isDualInput = true;
        } else if(Zone.checkTags(tag, new String[] { "ansul" })) {
            type = "Alarm Input";
        } else if (Zone.checkTags(tag, new String[] { "pull" })) {
            type = "Alarm Input Class A";
            this.isMini = true;
            this.isPullSttn = true;
        } else if (Zone.checkTags(tag, new String[] { "co", "carb" }) && !Zone.checkTags(tag, new String[] { "smoke", "duct" })) {
            type = "Latched Supervisory";
            this.isDualInput = true;
        } else if (Zone.checkTags(tag, new String[] { "valve", "tamper", "stat", "pump", "intake", "discharge",
                "jockey", "jocky", "bypass", "radio trouble", "low heat", "generator", "dry sys", "elev pwr", "monitor" })) {
            type = "Non-latched Supervisory";
            this.isDualInput = true;         
            if (Zone.checkTags(tag, new String[] { "radio"})) {
                this.isMini = true;
            }
        } else if (Zone.checkTags(tag, new String[] { "smoke", "duct" })) {

            /*
            if (Zone.checkTags(tag, new String[] { "cover" })) {
                type = "Trouble Input";
                this.isDualInput = true; 
            }
            else {
                if (Zone.checkTags(tag, new String[] { "dual" , "combo"})) {
                    this.isDualInput = true;
                } 

                type = "Photo Detector";
                this.isSensor = true;
            }
            */
           if (Zone.checkTags(tag, new String[] { "dual" , "combo"})) {
                    this.isDualInput = true;
            } 

            type = "Photo Detector";
            this.isSensor = true;
                
        } else if(Zone.checkTags(tag, new String[] {"fan shut", "ac shut", "rtu shut"})){
            this.isAR = true;
            type = "Relay";
        } else if (Zone.checkTags(tag, new String[] { "relay", "door", "damper", "shutdown", "shut down", "Recall", "fsd",
                "fan", "shunt", })) {
            type = "Relay";
            this.isDualInput = true;
        } else if (Zone.checkTags(tag, new String[] { "heat" })) {
            if (Zone.checkTags(tag, new String[] { "dual" })) {
                this.isDualInput = true;
            }
            type = "Heat Detector";
            this.isSensor = true;
        } 
        else if (Zone.checkTags(tag, new String[] { "phone mod" })) {
            type = "Telephone Module";
        }
        else if (Zone.checkTags(tag, new String[] { "speaker" })) {
            type = "Speakers";
        }
        else if (Zone.checkTags(tag, new String[] { "blank" , "spare" })) {
            type = "Blank Device";
        } else {
            type = "Unknown";
        }
        return type;
    }

    // Checks a tag against a list of strings
    public static Boolean checkTags(String target, String[] tagsToCheck) {
        for (String text : tagsToCheck) {
            if (target.toLowerCase().contains(text.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    public void setDualInput(boolean in) {
        isDualInput = in;
    }

    public void setTag1(String tag) {
        tag1 = tag;
    }

    public void setType(String newtype) {
        type = newtype;
    }

    public void setNS(boolean newns) {
        isNS = newns;
    }

    public void setAR(boolean isar) {
        isAR = isar;
    }

    public void setSensor(boolean issensor) {
        isSensor = issensor;
    }

    public void setSubAddress(subZone newzone) {
        subAddress = newzone;
    }

    public void setMini(boolean ismini) {
        isMini = ismini;
    }

    public String getType() {
        return type;
    }

    public boolean isNS() {
        return isNS;
    }

    public boolean isAR() {
        return isAR;
    }

    public boolean isPullSttn() {
        return isPullSttn;
    }

    public boolean isSensor() {
        return isSensor;
    }

    public boolean isDualInput() {
        return isDualInput;
    }

    public boolean isMini() {
        return isMini;
    }

    public double getAddress() {
        return address;
    }

    public String getTag1() {
        return tag1;
    }

    public String getTag2() {
        return tag2;
    }

    public subZone getSubAddress() {
        return subAddress;
    }
}

class subZone extends Zone {
    public subZone(double address, String tag1, String tag2) {
        super(address, tag1, tag2);
    }
}