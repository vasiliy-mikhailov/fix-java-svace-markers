package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CLONE THAT COULD NOT REACH THE HOST SAID NOTHING, AND THE BUILD TOOK THE BLAME.
 *
 * <p>{@code git clone --depth 1 "$repo" "$dir" >/dev/null 2>&1} discarded both streams. On a machine
 * with no route to the host the clone failed, printed nothing, and left an empty directory — and the
 * failure surfaced twenty seconds later as
 * {@code no pom.xml and no build.gradle in /work/checkouts/WebGoat}, which sends the reader to the
 * build system for a problem that was the network. That is the message a person actually got.
 *
 * <p>The mirror is the other half. A marker's first field is the repository its code lives in, and on
 * an air-gapped network none of them is reachable. Rewriting three hundred markers throws away the
 * canonical identifier a settlement is read against later, and a hand-run
 * {@code git config --global} reverts on the next deploy because a deploy recreates the container. So
 * the rules live with the run and are applied before anything clones.
 */
class NoRouteToTheHostTest {

    private static String entrypoint() throws Exception {
        return Files.readString(Path.of("entrypoint.sh"));
    }

    @Test
    @DisplayName("the reason a clone failed is kept, and goes to stderr")
    void theReasonSurvives() throws Exception {
        String sh = entrypoint();
        assertTrue(!sh.contains(">/dev/null 2>&1)") || !sh.contains("clone_said="),
                "the clone is discarding what git said again, which is how a network failure comes "
                        + "back as a missing pom.xml");
        // THE COMMAND IS ASSEMBLED NOW, not written out, because the registry may name a branch to
        // clone. This asserts the property that mattered — git's output is CAPTURED — rather than
        // the spelling of the arguments, which was what broke when `--branch` arrived.
        assertTrue(sh.contains("clone_said=$(git clone") && sh.contains("2>&1)"),
                "git's own words are what tell somebody the host is unreachable");
        assertTrue(sh.contains("--branch \"$want_branch\""),
                "and a subject that only builds on a stubbed branch has to be cloned on it");
        // STDERR, NOT STDOUT, and this is not a style point: `checkout()` returns the path by command
        // substitution, so anything on stdout is appended to the directory the caller then builds in.
        int said = sh.indexOf("clone_said=$(git clone");
        int redirect = sh.indexOf("} >&2", said);
        assertTrue(said > 0 && redirect > said && redirect - said < 800,
                "the failure must go to stderr; on stdout it becomes part of the checkout path");
    }

    @Test
    @DisplayName("and it names the three ways out rather than leaving somebody to find them")
    void itSaysWhatToDo() throws Exception {
        String sh = entrypoint();
        int said = sh.indexOf("could not clone");
        assertTrue(said > 0, "the failure no longer says anything");
        String message = sh.substring(said, Math.min(sh.length(), said + 700));
        for (String way : new String[] {"source.zip", "git-mirror"}) {
            assertTrue(message.contains(way),
                    way + " is a supported way to run without reaching the host, and the one place "
                            + "somebody is looking when it fails is this message: " + message);
        }
    }

    @Test
    @DisplayName("the mirror is applied before anything clones")
    void mirrorFirst() throws Exception {
        String sh = entrypoint();
        int mirror = sh.indexOf("url.$to.insteadOf");
        int clone = sh.indexOf("clone_said=$(git clone");
        assertTrue(mirror > 0, "no mirror is applied at all");
        assertTrue(mirror < clone,
                "a rewrite configured after the clone rewrites nothing: " + mirror + " vs " + clone);
    }

    @Test
    @DisplayName("a pair is written; half a line is dropped rather than half-applied")
    void onlyWholePairs(@TempDir Path dir) throws Exception {
        Subject.saveMirror(dir, "https://github.com/ https://gitlab.internal/mirror/\n"
                + "# a comment\n"
                + "https://only-a-from.example/\n"
                + "\n");
        String held = Files.readString(dir.resolve("git-mirror"));
        assertEquals("https://github.com/ https://gitlab.internal/mirror/\n", held,
                "git config would take a half-line without complaining and rewrite URLs to nothing, "
                        + "and that failure looks exactly like the host being unreachable: " + held);
    }

    @Test
    @DisplayName("blank clears it, so a mirror can be taken off without editing a file")
    void blankClears(@TempDir Path dir) throws Exception {
        Subject.saveMirror(dir, "https://github.com/ https://gitlab.internal/mirror/");
        assertTrue(Files.exists(dir.resolve("git-mirror")));
        Subject.saveMirror(dir, "   ");
        assertTrue(!Files.exists(dir.resolve("git-mirror")),
                "a rule nobody can remove from the page is one that gets removed by hand, inside a "
                        + "container, and comes back on the next deploy");
        assertEquals("", Subject.mirror(dir));
    }

    @Test
    @DisplayName("what is stored is shown back, because a mirror is not a secret")
    void itIsShownBack(@TempDir Path dir) throws Exception {
        // THE CREDENTIAL BESIDE IT IS DELIBERATELY WRITE-ONLY. This is not: a field that started
        // blank while a rule was in force would invite somebody to retype it and end up with two
        // rules pointing different ways, which is a fetch nobody can predict.
        Subject.saveMirror(dir, "https://github.com/ https://gitlab.internal/mirror/");
        assertEquals("https://github.com/ https://gitlab.internal/mirror/", Subject.mirror(dir));
    }
}
