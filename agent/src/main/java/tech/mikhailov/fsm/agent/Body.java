package tech.mikhailov.fsm.agent;

/**
 * THE BODY OF AN EVENT, DECIDED IN ONE PLACE.
 *
 * <p>A row of the record carries its body in whichever field its kind uses: a note, a thought, a
 * cause, the text of a message. Three endpoints serve these rows and a page draws them, and until
 * now all four knew which field belonged to which kind — so adding a kind meant a case in each of
 * the three, a field on a shared TypeScript type, and a component to read it. The sibling harness
 * names the cost of that exactly: "a page that had to know which is which per kind would be a
 * second copy of the record's shape."
 *
 * <p>It had four copies here, and they drifted the way four copies do. `sent` reached the page with
 * its text and without its role, so every lane drew a fold with no label on it; the guard that
 * caught it had to be written afterwards, and it only catches the Java half.
 *
 * <p>SO THE KINDS THAT ARE ONLY TEXT ARE COMPOSED HERE. The page draws {@code text} without knowing
 * what kind produced it, and a new kind of that sort costs one line in {@link #of} and nothing at
 * all in TypeScript.
 *
 * <p>NOT EVERY KIND. A build draws a lamp with three outcomes, a tool call draws its arguments and
 * its result as two folds where an absent result is a deliberate absence, an answer carries the
 * control that rates it, a settlement draws a state pill and a price draws a figure. Those are
 * structure rather than prose and they keep their own components — the rule is not "no branching",
 * it is "no branching for the ones that are only words".
 */
final class Body {

    private Body() {
    }

    /** The kinds this composes. Anything else keeps its own fields and its own component. */
    static boolean carries(String kind) {
        return switch (kind) {
            case "progress", "thought", "sent", "metered", "system", "failed" -> true;
            default -> false;
        };
    }

    /**
     * What this row says, in the field its kind happens to use.
     *
     * <p>First non-empty rather than a switch, because the point is to stop enumerating: a kind that
     * puts its body in any of these is drawn without anything here being told about it.
     */
    static String of(String event) {
        String kind = Dashboard.field(event, "kind");
        if ("metered".equals(kind)) {
            return metered(event);
        }
        if ("sent".equals(kind)) {
            // THE ROLE IS PART OF THE BODY. It is what tells one of these from another, and when it
            // travelled as a field of its own the page lost it on the one endpoint that forgot.
            String role = Dashboard.field(event, "role");
            String text = Dashboard.field(event, "text");
            return (role.isBlank() ? "" : "[" + role + ", " + text.length() + " chars]\n") + text;
        }
        for (String field : new String[] {"note", "text", "prompt", "cause"}) {
            String said = Dashboard.field(event, field);
            if (!said.isBlank()) {
                return said;
            }
        }
        return "";
    }

    /**
     * What a call cost, on one line.
     *
     * <p>`LENGTH` is called out rather than left as one word among four: it means the answer above
     * it is incomplete, and every truncation this pipeline has had was found by a person noticing a
     * reply stopped mid-sentence.
     */
    private static String metered(String event) {
        String finish = Dashboard.field(event, "finish");
        long ms = num(Dashboard.field(event, "ms"));
        String line = "← " + grouped(num(Dashboard.field(event, "input"))) + " in / "
                + grouped(num(Dashboard.field(event, "output"))) + " out tokens"
                + (ms > 0 ? " · " + String.format("%.1fs", ms / 1000.0) : "")
                + (finish.isBlank() ? "" : " · " + finish);
        return "LENGTH".equals(finish) ? line + " — CUT OFF AT THE CAP" : line;
    }

    private static String grouped(long n) {
        return String.format("%,d", n);
    }

    private static long num(String said) {
        try {
            return said == null || said.isBlank() ? 0 : Long.parseLong(said.strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
