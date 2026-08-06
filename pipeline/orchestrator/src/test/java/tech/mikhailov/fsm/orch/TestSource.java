package tech.mikhailov.fsm.orch;

/**
 * READING JAVA SOURCE AS TEXT, for the source-scanning guards — and the ONE implementation of it.
 *
 * <p>WHY THIS FILE EXISTS. Three guards in this module read {@code src/main/java} as text and each one
 * carried its own {@code stripComments}: {@code TheInnerCirclesDependOnNoFrameworkTest},
 * {@code NoControllerAssemblesItsOwnResponseTest} and
 * {@code NoNewCallerReachesTheBacklogAroundItsPortTest}. Two of the three were the naive
 * line-oriented version and one was literal-aware, and the difference is not style: a {@code //}
 * inside a string literal — {@code "https://gitlab.company/grp/proj.git"}, which
 * {@code JobsController} really does contain — made the naive strippers discard the rest of that
 * LINE, so part of a file one of those guards governs was invisible to it. A {@code /*} inside a
 * literal would discard everything to the next {@code *}{@code /}, and the check would go quietly
 * green over it. That is the failure mode this whole module writes tests against: a guard that passes
 * because it read nothing.
 *
 * <p>Public rather than package-private only because one of the three callers lives in
 * {@code tech.mikhailov.fsm.orch.web}. It is test-scope; nothing in {@code src/main} may use it.
 */
public final class TestSource {

    private TestSource() {
    }

    /**
     * Comments out, STRING LITERALS IN.
     *
     * <p>Both halves are load-bearing and they pull in opposite directions. COMMENTS HAVE TO GO
     * because every file these guards read argues for its own decisions at length and names, in prose,
     * the very things the guard forbids — the DAO, the frameworks the inner circles refuse, the exact
     * JSON a controller exchanges — so a naive substring search would report every explanation of a
     * rule as a violation of it. LITERALS HAVE TO STAY because that is where the SQL and the wire
     * names are, and being inside a literal is what tells a {@code //} in a URL apart from the start of
     * a comment. A line-oriented stripper cannot make that distinction, which is why there is one of
     * these and not three.
     *
     * <p>Text blocks are handled whole: everything between {@code """} and the next {@code """} is
     * kept, including any {@code //} in it.
     */
    public static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int length = source.length();
        while (i < length) {
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? length : end + 3;
                out.append(source, i, end);
                i = end;
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                char quote = source.charAt(i);
                int end = i + 1;
                while (end < length && source.charAt(end) != quote) {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, length);
                out.append(source, i, end);
                i = end;
            } else {
                out.append(source.charAt(i));
                i++;
            }
        }
        return out.toString();
    }
}
