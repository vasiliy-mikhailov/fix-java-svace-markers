package tech.mikhailov.fsm.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Is the proof about the REAL code, or about a mock?
 *
 * <p>A red-&gt;green flip only says something about the source if the test EXERCISED the source. A test
 * that mocks the class under test, or never touches it, can be driven red and then green entirely by
 * its own stubbing — the execution record is byte-identical to a real proof and means nothing.
 *
 * <p>Mock-heaviness is deliberately NOT the signal. The pipeline's best resource-leak proof mocks
 * Connection, Statement and ResultSet and is completely sound, because the object under test is real
 * and the verify() asserts the real object's behaviour. Mocking a collaborator you cannot stand up is
 * good practice; mocking the subject is the defect.
 *
 * <p>The score is an ORDERING over sound proofs, not a pass mark: record-outcome.js writes it into
 * value_score so a reviewer with limited time sees which of two proven fixes is better evidence.
 */
public final class TestRealness {

    private TestRealness() {
    }

    /**
     * @param sound        the test drives the real class, so a red-&gt;green flip is evidence about it
     * @param score        0..100 ordering over sound proofs; 0 whenever {@code sound} is false
     * @param reasons      what the reader is told, in the order the JS collected it
     * @param mocksSubject the cardinal sin: the class under test is itself a mock or a spy
     * @param touchesReal  the class is constructed, or a static of it is called, and not mocked
     */
    public record Result(boolean sound, int score, List<String> reasons,
                         boolean mocksSubject, boolean touchesReal) {
    }

    // Masking patterns. Order matters: a `//` inside a block comment is part of the comment, and a
    // quote inside either is not a string literal.
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(\\\\.|[^\"\\\\\n])*\"");
    private static final Pattern NOT_NEWLINE = Pattern.compile("[^\n]");

    private static final Pattern ASSERTS =
            Pattern.compile("\\bassert[A-Z]\\w*\\s*\\(|\\bassertThat\\s*\\(|\\bfail\\s*\\(");
    private static final Pattern VERIFIES = Pattern.compile("\\bverify\\s*\\(");
    private static final Pattern STUBS =
            Pattern.compile("\\bwhen\\s*\\(|\\bmock\\s*\\(|\\bgiven\\s*\\(|@Mock\\b");

    private static final String STUB_MOCK_REASON =
            " stub/mock setup(s) for collaborators (legitimate when the real ones need a DB/network)";
    private static final String NO_STUBS_REASON =
            "no stubbing at all — drives the real objects end to end";
    private static final String INTERACTION_ONLY_REASON =
            "asserts only on interactions (verify), not on returned values/state";

    /**
     * @param src the test source the reproducer wrote
     * @param cls the simple name of the class under test
     */
    public static Result testRealness(String src, String cls) {
        List<String> reasons = new ArrayList<>();
        // Strip anything that could not be part of an identifier before the name is spliced into a
        // pattern. The class name arrives from a model reply, so it is not trusted input.
        String name = cls == null ? "" : cls.replaceAll("[^A-Za-z0-9_$]", "");
        if (src == null || src.isEmpty() || name.isEmpty()) {
            reasons.add("no test source or no class name");
            // Both flags stay false. This is the one path that returns before they are computed, so
            // it is the only place their defaults are observable — and a default of `true` would tell
            // the caller that a source nobody could even scan both mocks its subject and drives it.
            return new Result(false, 0, List.copyOf(reasons), false, false);
        }

        String s = mask(src);
        // Only `$` is a metacharacter that survives the sanitising above, but quoting the whole name
        // is what makes that reasoning unnecessary rather than a comment somebody has to re-derive.
        String n = Pattern.quote(name);

        // THE CARDINAL SIN: the subject itself is a mock, so every observed behaviour is stubbed.
        // The annotation forms are PROXIMITY rules — the annotation must be within 80 characters of
        // the declaration to be read as annotating it — which is why mask() preserves offsets.
        boolean mocksSubject =
                find(s, "mock\\s*\\(\\s*" + n + "\\s*\\.\\s*class")
                        || find(s, "@Mock[\\s\\S]{0,80}?\\b" + n + "\\s+\\w")
                        || find(s, "@(?:Spy|InjectMocks)[\\s\\S]{0,80}?\\b" + n + "\\s+\\w");

        long constructs = count(s, "new\\s+" + n + "\\s*\\(");
        long statics = count(s, "\\b" + n + "\\s*\\.\\s*[a-z]\\w*\\s*\\(");
        boolean touchesReal = (constructs > 0 || statics > 0) && !mocksSubject;

        long asserts = count(s, ASSERTS);
        long verifies = count(s, VERIFIES);
        long stubs = count(s, STUBS);

        if (mocksSubject) {
            reasons.add("the class under test is itself mocked/spied — the test observes stubs, not "
                    + name);
        }
        if (constructs == 0 && statics == 0) {
            reasons.add("the test never constructs " + name
                    + " and never calls a static method on it");
        }
        if (!touchesReal) {
            return new Result(false, 0, List.copyOf(reasons), mocksSubject, false);
        }

        // Preference ordering among SOUND tests.
        int score = 55;                                  // it does drive the real class
        if (constructs > 0) {
            score += 20;
            reasons.add("instantiates the real " + name);
        } else {
            reasons.add("exercises " + name + " through static calls");
        }
        if (asserts > 0) {
            score += 15;
            reasons.add(asserts + " value/state assertion(s)");
        }
        if (asserts == 0 && verifies > 0) {
            // Interaction-only is legitimate for "must call close()" style claims, but it is weaker
            // evidence than asserting a value, so it ranks below — it is not penalised into
            // unsoundness.
            score -= 10;
            reasons.add(INTERACTION_ONLY_REASON);
        }
        if (stubs == 0) {
            score += 10;
            reasons.add(NO_STUBS_REASON);
        } else {
            reasons.add(stubs + STUB_MOCK_REASON);
        }
        return new Result(true, Math.max(0, Math.min(100, score)), List.copyOf(reasons),
                false, true);
    }

    /**
     * Blank out comments and string literals so a class name inside either cannot count as a usage.
     *
     * <p>Comments are replaced CHARACTER FOR CHARACTER rather than deleted. The @Mock rules above
     * measure a distance in characters, so a mask that collapsed a comment to something shorter would
     * drag an unrelated @Mock collaborator inside the 80-character window and condemn a sound proof as
     * mocking its own subject. Newlines are kept for the same reason.
     */
    private static String mask(String src) {
        String s = BLOCK_COMMENT.matcher(src)
                .replaceAll(m -> NOT_NEWLINE.matcher(m.group()).replaceAll(" "));
        s = LINE_COMMENT.matcher(s).replaceAll(m -> " ".repeat(m.group().length()));
        // String literals collapse to "" — their length is not load-bearing, and keeping the quotes
        // means an unterminated literal cannot swallow the rest of the file.
        return STRING_LITERAL.matcher(s).replaceAll("\"\"");
    }

    private static boolean find(String s, String regex) {
        return Pattern.compile(regex).matcher(s).find();
    }

    private static long count(String s, String regex) {
        return count(s, Pattern.compile(regex));
    }

    private static long count(String s, Pattern p) {
        Matcher m = p.matcher(s);
        return m.results().count();
    }
}
