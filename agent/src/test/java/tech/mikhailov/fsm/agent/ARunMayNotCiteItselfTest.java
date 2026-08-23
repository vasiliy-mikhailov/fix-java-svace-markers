package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A TEST WRITTEN TO DEMONSTRATE A MARKER CANNOT ALSO SHOW THE BEHAVIOUR WAS INTENDED.
 *
 * <p>The verdict agent reads the checkout, and by the time it is asked the checkout holds this run's
 * own test and this run's own patch. Thirteen settlements in a 67-marker run cited them: `by-design`
 * because "an existing test depends on this", where the test had been written eleven minutes earlier
 * by the reproducer, in that prove, about that marker. The record does not show it, because a
 * citation of our test reads exactly like a citation of theirs.
 *
 * <p>`git status` is the line between the two, so this is a fact rather than a rule an agent has to
 * remember.
 */
class ARunMayNotCiteItselfTest {

    private static final String MARKER = "https://github.com/WebGoat/WebGoat.git|"
            + "src/main/java/org/owasp/webgoat/lessons/EncDec.java|40|DM_DEFAULT_ENCODING";

    private static String whatThisRunMade(Path checkout) throws Exception {
        Constructor<Prove> c = Prove.class.getDeclaredConstructor(
                Path.class, Path.class, String.class, Agents.class, Runner.class, Trace.class);
        c.setAccessible(true);
        Prove prove = c.newInstance(checkout, checkout, MARKER, null, null, null);
        Method m = Prove.class.getDeclaredMethod("whatThisRunMade");
        m.setAccessible(true);
        return (String) m.invoke(prove);
    }

    /** A repository with one committed file, which is therefore not ours. */
    private static Path repository(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/test/java"));
        Files.writeString(dir.resolve("src/test/java/EncDecTest.java"), "// theirs, committed\n");
        Shell.run(dir, "git", "init", "-q");
        Shell.run(dir, "git", "config", "user.email", "t@t");
        Shell.run(dir, "git", "config", "user.name", "t");
        Shell.run(dir, "git", "add", "-A");
        Shell.run(dir, "git", "commit", "-q", "-m", "upstream");
        return dir;
    }

    @Test
    @DisplayName("a clean tree hands the judge nothing to disregard")
    void clean(@TempDir Path dir) throws Exception {
        assertTrue(whatThisRunMade(repository(dir)).isEmpty(),
                "before the reproducer runs there is nothing of ours, and saying so at length would "
                        + "train the judge to skim past the notice on the runs that need it");
    }

    @Test
    @DisplayName("the test this run wrote is named and ruled out")
    void ourTest(@TempDir Path dir) throws Exception {
        Path repo = repository(dir);
        Files.writeString(repo.resolve("src/test/java/ProvingEncDecTest.java"), "// ours\n");
        String notice = whatThisRunMade(repo);
        assertTrue(notice.contains("INADMISSIBLE"), "stated as a heading, not buried");
        assertTrue(notice.contains("ProvingEncDecTest.java"), "named:\n" + notice);
        assertFalse(notice.contains("EncDecTest.java\n") && !notice.contains("ProvingEncDec"),
                "the committed sibling stays admissible — it is evidence about the project");
    }

    @Test
    @DisplayName("the patch this run applied is ours too")
    void ourPatch(@TempDir Path dir) throws Exception {
        Path repo = repository(dir);
        Files.writeString(repo.resolve("src/test/java/EncDecTest.java"), "// theirs, patched by us\n");
        String notice = whatThisRunMade(repo);
        assertTrue(notice.contains("EncDecTest.java"),
                "a modified file is ours as surely as a new one — the fixer edits in place");
        assertTrue(notice.contains("you do not have an argument"),
                "and the consequence is spelled out, so the judge knows what to do instead of "
                        + "noting the caveat and citing it anyway");
    }

    @Test
    @DisplayName("somewhere that is not a repository says nothing rather than guessing")
    void notARepository(@TempDir Path dir) throws Exception {
        assertTrue(whatThisRunMade(dir).isEmpty(),
                "git status fails, and inventing an inadmissible list from a failed command would "
                        + "block arguments that were fine");
    }
}
