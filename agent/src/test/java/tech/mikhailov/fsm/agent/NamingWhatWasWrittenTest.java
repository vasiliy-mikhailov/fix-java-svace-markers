package tech.mikhailov.fsm.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * TURNING A MARKER INTO A PATH, and a claim into a directory name.
 *
 * <p>Both were defects that made every marker in a report unprovable at once.
 */
class NamingWhatWasWrittenTest {

    private static String fileOf(String marker) throws Exception {
        Method m = Prove.class.getDeclaredMethod("fileOf", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, marker);
    }

    private static String slug(String marker) throws Exception {
        // SUPERVISOR'S, WHICH IS THE ONE THAT WAS ALWAYS AUTHORITATIVE. Dashboard held a private
        // copy for its own links; that page is gone, and a second spelling of this rule is what the
        // test exists to prevent in the first place.
        Method m = Supervisor.class.getDeclaredMethod("slug", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, marker);
    }

    /**
     * An analyser reports the path it compiled, which is wherever CI checked the project out.
     * Resolving that against a checkout escapes it, and every marker in the report became infra.
     */
    @Test
    void anAnalysersBuildPathIsMadeRepoRelative() throws Exception {
        assertEquals("src/main/java/org/owasp/webgoat/X.java",
                fileOf("https://r.git|/builds/gitlab/team/owasp-webgoat/"
                        + "src/main/java/org/owasp/webgoat/X.java|54|TAINTED_PTR"));
    }

    /** A path already relative is left alone. */
    @Test
    void aRelativePathIsUntouched() throws Exception {
        assertEquals("src/main/java/A.java", fileOf("r|src/main/java/A.java|1|C"));
    }

    /** Integration-test trees are source roots too; treating only src/main as one lost 74 markers. */
    @Test
    void integrationTestTreesAreSourceRootsToo() throws Exception {
        assertEquals("src/it/java/org/owasp/P.java",
                fileOf("r|/builds/x/src/it/java/org/owasp/P.java|18|FB.EI_EXPOSE_REP"));
    }

    /**
     * The slug names the claim directory. entrypoint.sh makes it by taking everything after the
     * last slash, replacing every character outside A-Za-z0-9._- with an underscore, and cutting to
     * eighty. A slug the dashboard cannot reproduce reads as a marker nobody is working on, which is
     * how "proving" lied about twenty-five rows.
     */
    @Test
    void theSlugMatchesTheShellThatMakesTheDirectory() throws Exception {
        assertEquals("Servers.java_54_TAINTED_PTR",
                slug("https://github.com/W/W.git|src/main/java/a/b/Servers.java|54|TAINTED_PTR"));
    }

    @Test
    void theSlugIsCutAtEighty() throws Exception {
        String overlong = "r|src/main/java/" + "A".repeat(200) + ".java|1|C";
        assertEquals(80, slug(overlong).length());
    }
}
