class Zone {
    private double address;
    private subZone subAddress = null;
    private String type = "";
    private boolean isNS = false;
    private boolean isPullSttn = false;
    private boolean isSensor = false;
    private boolean isDualInput = false;
    private String tag1;
    private String tag2;

    public Zone(double address, String tag1, String tag2) {
        this.address = address;
        this.tag1 = tag1;
        this.tag2 = tag2;

        type = classify(tag1);
    }

    public void addSubZone(double subAddress, String tag1, String tag2) {
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

        if (Zone.checkTags(tag, new String[] { "waterf", "ps10", "ps 10" })) {
            type = "Alarm Input";
            this.isNS = true;
            this.isDualInput = true;
        } else if (Zone.checkTags(tag, new String[] { "pull"})) {
            type = "Alarm Input";
            this.isPullSttn = true;
        } else if (Zone.checkTags(tag, new String[] { "co", "carb" })) {
            type = "Latched Supervisory";
            this.isDualInput = true;
        } else if (Zone.checkTags(tag, new String[] { "valve", "tamper", "stat", "pump", "intake", "discharge",
                "jockey", "jocky", "bypass" })) {
            type = "Non-latched Supervisory";
            this.isDualInput = true;
        } else if (Zone.checkTags(tag, new String[] { "smoke", "duct" })) {
            type = "Photo Detector";
            this.isSensor = true;
        } else if (Zone.checkTags(tag, new String[] { "relay", "door", "damper", "shutdown", "Recall", "fsd",
                "fan", "shunt", })) {
            type = "Relay";
        } else if (Zone.checkTags(tag, new String[] { "heat" })) {
            type = "Heat Detector";
            this.isSensor = true;
        } else if (Zone.checkTags(tag, new String[] { "blank" , "spare" })) {
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

    public String getType(){
        return type;
    }

    public boolean isNS() {
        return isNS;
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