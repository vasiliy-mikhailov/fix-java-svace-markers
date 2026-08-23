package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A HUNK HEADER SAYS WHICH LINES IT REPLACES, so whether a patch reaches the flagged one is
 * arithmetic and not an opinion.
 *
 * <p>Before this, the fix-critic was handed the fixer's own account of its patch and certified that.
 * Markers 02 and 19 reached `pr-ready` with the flagged line untouched: the fixer had edited a
 * neighbouring class, described the edit accurately, and the critic answered `sound` about a
 * sentence rather than a diff.
 */
class WhetherThePatchReachesTheLineTest {

    private static final String MARKER =
            "https://github.com/WebGoat/WebGoat.git|src/main/java/org/owasp/webgoat/lessons/"
                    + "missingac/MissingFunctionACYourHash.java|40|DEREF_OF_NULL";

    /** Reaches the private through a Prove that never runs — the method touches no field but two. */
    private static String verdictOn(String diff) throws Exception {
        Constructor<Prove> c = Prove.class.getDeclaredConstructor(
                Path.class, Path.class, String.class, Agents.class, Runner.class, Trace.class);
        c.setAccessible(true);
        Prove prove = c.newInstance(Path.of("."), Path.of("."), MARKER, null, null, null);
        Method m = Prove.class.getDeclaredMethod("reachesTheFlaggedLine", String.class);
        m.setAccessible(true);
        return (String) m.invoke(prove, diff);
    }

    private static boolean reaches(String diff) throws Exception {
        return !verdictOn(diff).startsWith("THE PATCH DOES NOT TOUCH");
    }

    @Test
    @DisplayName("a hunk spanning the flagged line reaches it")
    void spanning() throws Exception {
        assertTrue(reaches("""
                --- a/src/main/java/org/owasp/webgoat/lessons/missingac/MissingFunctionACYourHash.java
                +++ b/src/main/java/org/owasp/webgoat/lessons/missingac/MissingFunctionACYourHash.java
                @@ -37,8 +37,9 @@ public class MissingFunctionACYourHash {
                     public AttackResult completed(String hash) {
                -        return hash.equals(expected);
                +        return expected.equals(hash);
                """), "line 40 lies inside 37..44");
    }

    @Test
    @DisplayName("a hunk that stops short of it does not")
    void shortOf() throws Exception {
        assertFalse(reaches("""
                --- a/src/main/java/org/owasp/webgoat/lessons/missingac/MissingFunctionACYourHash.java
                +++ b/src/main/java/org/owasp/webgoat/lessons/missingac/MissingFunctionACYourHash.java
                @@ -12,4 +12,5 @@ import java.util.Objects;
                +import java.util.Optional;
                """), "12..15 does not contain 40, however plausible the change reads");
    }

    @Test
    @DisplayName("the right lines in the wrong file do not count")
    void anotherFile() throws Exception {
        assertFalse(reaches("""
                --- a/src/main/java/org/owasp/webgoat/lessons/missingac/DisplayUser.java
                +++ b/src/main/java/org/owasp/webgoat/lessons/missingac/DisplayUser.java
                @@ -38,6 +38,7 @@ public class DisplayUser {
                +        Objects.requireNonNull(user);
                """), "this is exactly the failure the check exists to catch — a neighbour edited instead");
    }

    @Test
    @DisplayName("a patch that touches nothing reaches nothing")
    void empty() throws Exception {
        assertFalse(reaches("(the working tree is unchanged outside the tests)"), "nothing changed");
    }

    @Test
    @DisplayName("the second file's hunks are not read against the first file's name")
    void twoFiles() throws Exception {
        assertFalse(reaches("""
                --- a/src/main/java/org/owasp/webgoat/lessons/missingac/MissingFunctionACYourHash.java
                +++ b/src/main/java/org/owasp/webgoat/lessons/missingac/MissingFunctionACYourHash.java
                @@ -12,2 +12,3 @@
                +import java.util.Optional;
                --- a/src/main/java/org/owasp/webgoat/lessons/missingac/DisplayUser.java
                +++ b/src/main/java/org/owasp/webgoat/lessons/missingac/DisplayUser.java
                @@ -38,6 +38,7 @@
                +        Objects.requireNonNull(user);
                """), "the flagged file was touched, but not over line 40");
    }

    @Test
    @DisplayName("when it does not reach, `sound` is made expensive rather than impossible")
    void whatItAsksFor() throws Exception {
        String said = verdictOn("(the working tree is unchanged outside the tests)");
        assertTrue(said.contains("may not answer `sound` without saying"),
                "a defect is sometimes correctly fixed at its source, so this asks for a sentence "
                        + "rather than forbidding the verdict");
        assertTrue(said.contains("over-fit"), "and names what the answer is when that sentence "
                + "cannot be written");
    }
}
