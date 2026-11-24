package checker;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

//Collects what the rules find. ERROR = the panel will not do what the correlation is meant to do.
//WARNING = unusual, worth a look, may be intentional. INFO = context for the reader, not a problem.
//
//A finding is kept short: what it is about (its place) and a one-line summary. Anything longer - the guideline it
//comes from, the list of devices involved, extra explanation - is attached separately and only shown on request.
public class Report {

    public enum Severity { ERROR, WARNING, INFO }

    public static class Finding {
        public final String rule;
        public final Severity severity;
        public final Object subject; //Circuit, Led, Equation, Place, ... (whatever it is about)
        public final Place place;
        public final String summary;
        public String why = ""; //the guideline it comes from
        public String more = ""; //extra explanation
        public String items_title = "";
        public final List<Place> items = new ArrayList<>(); //devices/zones/LEDs involved
        public Acceptances.Acceptance acceptance; //set when accepted (or, with stale, an acceptance to review)
        public boolean stale; //an acceptance that no longer matches the job

        //Accepted with a reason: still listed, but not counted as an open problem
        public boolean isAccepted() {
            return acceptance != null && !stale;
        }

        private final Job job;

        Finding(Job job, String rule, Severity severity, Object subject, String summary) {
            this.job = job;
            this.rule = rule;
            this.severity = severity;
            this.subject = subject;
            this.place = Place.of(job, subject);
            this.summary = summary;
        }

        //The guideline this comes from, e.g. "Cheat sheet, Zones: General Alarm - correlate to all signals and inputs"
        public Finding why(String text) {
            why = text;
            return this;
        }

        public Finding more(String text) {
            more = text;
            return this;
        }

        //Devices, zones or LEDs involved, listed in the details
        public Finding items(String title, Collection<?> list) {
            items_title = title;
            for (Object o : list) items.add(Place.of(job, o));
            return this;
        }

        public String where() {
            return place.text();
        }
    }

    private final Job job;
    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private final Map<String, List<String>> notes = new LinkedHashMap<>();
    private final List<Finding> findings = new ArrayList<>();
    private Rule current;

    //Ids of rules that do not support this job's panel, so did not run
    public final List<String> skipped = new ArrayList<>();

    public Report(Job job) {
        this.job = job;
    }

    void begin(Rule rule) {
        current = rule;
        rules.put(rule.id(), rule);
        notes.putIfAbsent(rule.id(), new ArrayList<>());
    }

    public Finding error(Object subject, String summary) {
        return add(Severity.ERROR, subject, summary);
    }

    public Finding warning(Object subject, String summary) {
        return add(Severity.WARNING, subject, summary);
    }

    public Finding info(Object subject, String summary) {
        return add(Severity.INFO, subject, summary);
    }

    //A summary line for the rule's section, e.g. "73 floor groups checked"
    public void note(String text) {
        notes.get(current.id()).add(text);
    }

    private Finding add(Severity severity, Object subject, String summary) {
        Finding f = new Finding(job, current.id(), severity, subject, summary);
        findings.add(f);
        return f;
    }

    public List<Finding> findings() {
        return findings;
    }

    public int rulesRun() {
        return rules.size();
    }

    //Rules that ran, in report order
    public List<Rule> rules() {
        return new ArrayList<>(rules.values());
    }

    //Summary lines a rule wrote, e.g. "73 floor groups found"
    public List<String> notes(String rule_id) {
        return notes.getOrDefault(rule_id, List.of());
    }

    public int count(Severity severity) {
        int n = 0;
        for (Finding f : findings) if (f.severity == severity && !f.isAccepted()) n++;
        return n;
    }

    public int count(String rule_id, Severity severity) {
        int n = 0;
        for (Finding f : findings) if (f.severity == severity && f.rule.equals(rule_id) && !f.isAccepted()) n++;
        return n;
    }

    //-------------------------------------------------------------------------------------------------
    //Output

    public void print(PrintStream out, boolean verbose) {
        for (Rule rule : rules.values()) {
            List<Finding> mine = new ArrayList<>();
            int errors = 0, warnings = 0, accepted = 0;
            for (Finding f : findings) {
                if (!f.rule.equals(rule.id())) continue;
                if (f.isAccepted()) {
                    accepted++;
                    if (verbose) mine.add(f);
                    continue;
                }
                if (f.severity == Severity.ERROR) errors++;
                if (f.severity == Severity.WARNING) warnings++;
                if (f.severity != Severity.INFO || verbose) mine.add(f);
            }
            String status = (errors + warnings == 0 ? "OK" : plural(errors, "error") + ", " + plural(warnings, "warning"))
                + (accepted > 0 ? " (+" + accepted + " accepted)" : "");
            out.println();
            out.println("[" + rule.name() + "] " + status);
            for (String n : notes.get(rule.id())) out.println("    " + n);
            mine.sort((a, b) -> a.severity.compareTo(b.severity));
            for (Finding f : mine) {
                out.println(String.format("  %-7s %s", f.isAccepted() ? "ACCEPTED" : f.stale ? "REVIEW" : f.severity.toString(), f.where()));
                out.println("          " + f.summary);
                if (verbose) {
                    if (!f.more.isEmpty()) out.println("          " + f.more);
                    int shown = 0;
                    for (Place p : f.items) {
                        if (shown++ == 8) {
                            out.println("            ... " + (f.items.size() - 8) + " more");
                            break;
                        }
                        out.println("            " + p.text());
                    }
                    if (!f.why.isEmpty()) out.println("          Why: " + f.why);
                    if (f.isAccepted()) out.println("          Accepted: " + f.acceptance.reason() + " (" + f.acceptance.when() + ")");
                }
            }
        }
        out.println();
        out.println("Total: " + plural(count(Severity.ERROR), "error") + ", " + plural(count(Severity.WARNING), "warning"));
    }

    public void writeCsv(File file) throws IOException {
        try (PrintWriter w = new PrintWriter(file, StandardCharsets.UTF_8)) {
            w.print('﻿'); //so Excel opens it as UTF-8
            w.println("Check,Severity,Node,CPU,Loop,Address,Type,Tag,Location,Problem,Details,Why,Accepted");
            for (Finding f : findings) {
                Place p = f.place;
                List<String> details = new ArrayList<>();
                if (!f.more.isEmpty()) details.add(f.more);
                for (Place i : f.items) details.add(i.text());
                String check = f.rule;
                if (rules.containsKey(f.rule)) check = rules.get(f.rule).name();
                w.println(String.join(",", csv(check), f.severity.toString(), csv(p.nodeText()), csv(p.cpu), csv(p.loop), csv(p.address),
                    csv(p.type), csv(p.tag), csv(p.location), csv(f.summary), csv(String.join("; ", details)), csv(f.why),
                    csv(f.isAccepted() ? f.acceptance.reason() + " (" + f.acceptance.when() + ")" : "")));
            }
        }
    }

    private static String csv(String s) {
        return "\"" + s.replaceAll("\\s*\\n\\s*", "; ").replace("\"", "\"\"") + "\"";
    }

    static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
