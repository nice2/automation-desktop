package checker;

//One family of checks. Add a rule by implementing this and listing it in CorrelationChecker.RULES.
public interface Rule {

    //Short id shown in the report and accepted by --rules, e.g. "FSAE-DISPLAY"
    String id();

    //Short readable name for the checker window, e.g. "FSAE graphic adder"
    default String name() {
        return id();
    }

    //One line saying what the rule looks at
    String title();

    //Which panels the rule understands. Rules are skipped (and the report says so) on other panels.
    boolean supports(Job.Panel panel);

    void check(Job job, Report report);
}
