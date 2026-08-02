package tech.mikhailov.fsm.lib;

/**
 * A Svace severity, the grade the backlog is triaged on, and the order the queue is drained in.
 *
 * <p>The JS kept these as two parallel object literals, SEVERITY_MAP and SEVERITY_RANK, and
 * checker-map.test.js spends a whole test asserting they have the same keys — because they can drift,
 * and drift is silent: a severity that ranks but does not grade is filed low, and one that grades but
 * does not rank sorts below every UNKNOWN severity, so any min_severity setting at all drops it.
 *
 * <p>One enum carrying both fields makes that failure unrepresentable, which is the reason to write it
 * this way rather than porting two maps and the test that checks they agree. The tests that survive
 * the port are the ones with content left: that the ranks are distinct and ordered, and that a higher
 * rank is never graded less urgent.
 */
public enum Severity {

    /** The order of the constants IS the order an interrupted run works the backlog in. */
    CRITICAL("Critical", Grade.HIGH, 3),
    MAJOR("Major", Grade.HIGH, 2),
    NORMAL("Normal", Grade.MEDIUM, 1),
    MINOR("Minor", Grade.LOW, 0);

    /** The three levels the backlog is triaged on, and nothing else. */
    public enum Grade {
        HIGH("high"), MEDIUM("medium"), LOW("low");

        private final String wire;

        Grade(String wire) {
            this.wire = wire;
        }

        /** Lower-case spelling written into the suspicions table, as the JS wrote it. */
        public String wire() {
            return wire;
        }
    }

    /**
     * Rank given to a severity the report uses but this enum does not know. parse-markers.js ranks it
     * -1, so every known severity outranks it and a min_severity filter drops it — an unrecognised
     * severity is not evidence of urgency.
     */
    public static final int UNKNOWN_RANK = -1;

    private final String label;
    private final Grade grade;
    private final int rank;

    Severity(String label, Grade grade, int rank) {
        this.label = label;
        this.grade = grade;
        this.rank = rank;
    }

    /** The spelling Svace writes in the CSV. */
    public String label() {
        return label;
    }

    public Grade grade() {
        return grade;
    }

    public int rank() {
        return rank;
    }

    /** Look up by the CSV spelling; null when the report used a severity we do not know. */
    public static Severity of(String label) {
        for (Severity s : values()) {
            if (s.label.equals(label)) {
                return s;
            }
        }
        return null;
    }

    /** Rank for sorting the queue, with the JS's -1 for anything unrecognised. */
    public static int rankOf(String label) {
        Severity s = of(label);
        return s == null ? UNKNOWN_RANK : s.rank;
    }
}
