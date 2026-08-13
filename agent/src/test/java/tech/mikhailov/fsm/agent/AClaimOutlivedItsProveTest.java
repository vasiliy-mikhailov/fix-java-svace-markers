package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A FIX THAT THE NEXT LINE CANCELLED, AND NOTHING FAILED.
 *
 * <p>The pool decides twice whether to prove a marker. First {@code settled} asks whether it has a
 * disposition — and that function was deliberately taught to answer NO for a marker whose prove
 * ended in {@code infra}, because a prove that THREW has not settled anything and the marker has to
 * go back in the queue. Then, three lines later, {@code mkdir claims/$id || continue} skipped it,
 * because the claim from the dead attempt was still sitting there and no code path ever removed it.
 *
 * <p>So the second gate silently repealed the first. Every marker whose prove threw was retired by
 * the mechanism that exists to stop two provers taking the same marker, the README's promise that
 * "a marker already settled anywhere is skipped" quietly became "already ATTEMPTED is skipped", and
 * the reported symptom — markers stuck in `infra` that the pipeline would not revisit — survived the
 * fix written for it. Both gates read correctly on their own; only their order was wrong, which is
 * why reading either one found nothing.
 *
 * <p>The watcher paid for it too. It reads {@code claims/} to tell a prove that is thinking from one
 * that has died, so several hundred finished markers arrived in its brief as "QUIET, still claimed
 * (idle=1009m)". It spent two whole passes reporting a stalled pipeline that was not stalled, and
 * its critic refuted both findings.
 *
 * <p>These tests hold the rule that was missing: a claim lasts exactly as long as the prove.
 */
class AClaimOutlivedItsProveTest {

    /** The pool's `release`, in Java, so the rule can be asserted without starting a container. */
    private static void release(Path results, String id, boolean settled) throws Exception {
        Path out = results.resolve("m").resolve(id);
        if (!settled && Files.isDirectory(out)) {
            Files.createDirectories(results.resolve("dead"));
            Files.move(out, results.resolve("dead")
                    .resolve(id + ".attempt-" + (Pace.tries(results, id) + 1)));
        }
        deleteRecursively(results.resolve("claims").resolve(id));
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())
                    ::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static Path proved(Path results, String id, String state) throws Exception {
        Path out = results.resolve("m").resolve(id);
        Files.createDirectories(out);
        Files.writeString(out.resolve("trace.jsonl"),
                "{\"at\":\"1786460466653\",\"kind\":\"asked\",\"agent\":\"reproducer\"}\n");
        if (state != null) {
            Files.writeString(out.resolve("settlements.jsonl"),
                    "{\"state\":\"proving\"}\n{\"state\":\"" + state + "\"}\n");
        }
        Files.createDirectories(results.resolve("claims").resolve(id));
        return out;
    }

    @Test
    @DisplayName("a prove that threw gives its marker back, rather than keeping it forever")
    void infraGoesBack(@TempDir Path results) throws Exception {
        proved(results, "Ping.java_34_FB.DM_DEFAULT_ENCODING", "infra");
        release(results, "Ping.java_34_FB.DM_DEFAULT_ENCODING", false);
        assertFalse(Files.exists(results.resolve("claims").resolve("Ping.java_34_FB.DM_DEFAULT_ENCODING")),
                "the claim is the whole bug: settled() says NO for `infra` so the pool would take "
                        + "this marker again, and a claim left behind makes the very next line "
                        + "skip it — for the rest of the run, and every run after it");
    }

    @Test
    @DisplayName("the attempt is kept, so the next one starts on a clean trace")
    void keptNotDropped(@TempDir Path results) throws Exception {
        proved(results, "m1", "infra");
        release(results, "m1", false);
        assertTrue(Files.exists(results.resolve("dead").resolve("m1.attempt-1")
                .resolve("trace.jsonl")),
                "Prove APPENDS. Leaving the dead attempt in place would land the retry on top of "
                        + "it in one trace.jsonl, and the record would read as a single prove that "
                        + "changed its mind — two reproducers, two verdicts, no line between them");
        assertFalse(Files.exists(results.resolve("m").resolve("m1")),
                "and it must leave m/, which is where settled() looks — a dead attempt left there "
                        + "answers on the live one's behalf");
    }

    @Test
    @DisplayName("a settled marker keeps its record where the pool can find it")
    void settledStays(@TempDir Path results) throws Exception {
        proved(results, "m2", "by-design");
        release(results, "m2", true);
        assertTrue(Files.exists(results.resolve("m").resolve("m2").resolve("settlements.jsonl")),
                "settled() decides by grepping m/*/settlements.jsonl; archiving a settled marker "
                        + "would lose its answer and the pool would prove it all over again");
        assertFalse(Files.exists(results.resolve("claims").resolve("m2")),
                "but the claim still goes, or the watcher reads a finished marker as a stalled one");
    }

    @Test
    @DisplayName("three failures and the marker is left for a person, rather than cycled")
    void bounded(@TempDir Path results) throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            proved(results, "hopeless", "infra");
            release(results, "hopeless", false);
            assertEquals(attempt, Pace.tries(results, "hopeless"),
                    "each failed attempt has to count, or releasing the claim is an endless loop: "
                            + "take it, fail in the same place, put it back");
        }
        assertTrue(Pace.tries(results, "hopeless") >= 3,
                "the pool's gate reads this number; without it a marker that dies on contact "
                        + "would be retried for as long as the run lasts");
    }

    @Test
    @DisplayName("a supervisor restart is not one of the pool's goes")
    void restartsAreNotTries(@TempDir Path results) throws Exception {
        Files.createDirectories(results.resolve("dead").resolve("m3.restart-1"));
        Files.createDirectories(results.resolve("dead").resolve("m3.restart-2"));
        Files.createDirectories(results.resolve("dead").resolve("m3.before-postponing"));
        assertEquals(0, Pace.tries(results, "m3"),
                "a restart and a postponement are somebody spending another go on this marker; "
                        + "counting them here would have two supervisor decisions exhaust the "
                        + "pool's allowance for a marker the pool had tried once");
        assertEquals(3, Pace.attempts(results, "m3"),
                "while the OTHER count still sees them all — that one asks how much time this "
                        + "marker has cost, and it cost all three");
    }

    @Test
    @DisplayName("an unreadable archive stops the retries rather than licensing them")
    void unreadable(@TempDir Path results) throws Exception {
        Files.writeString(results.resolve("dead"), "not a directory");
        assertEquals(0, Pace.tries(results, "m4"),
                "no archive is no attempts, which is the honest reading of a run that has not "
                        + "put anything aside yet");
    }

    @Test
    @DisplayName("a settled marker is never reported as quiet, however long its claim lingers")
    void settledIsNotQuiet(@TempDir Path results) throws Exception {
        long old = System.currentTimeMillis() - java.time.Duration.ofHours(17).toMillis();
        Path dir = results.resolve("m").resolve("done");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("trace.jsonl"),
                "{\"at\":\"" + old + "\",\"kind\":\"tool\",\"tool\":\"read_file\"}\n");
        Files.writeString(dir.resolve("settlements.jsonl"),
                "{\"state\":\"proving\"}\n{\"state\":\"verified/pr-ready\"}\n");
        Files.createDirectories(results.resolve("claims").resolve("done"));
        String digest = new Overwatch(results, null, null).digest();
        assertFalse(digest.contains("QUIET"),
                "this marker answered seventeen hours ago. Reported as a stalled prove it cost the "
                        + "watcher two passes and two refuted findings, and the pass it spends on a "
                        + "phantom is the pass it does not spend on the pipeline: " + digest);
        assertTrue(digest.contains("verified/pr-ready"), digest);
    }

    @Test
    @DisplayName("a prove that really has gone quiet is still flagged")
    void quietStillFlagged(@TempDir Path results) throws Exception {
        long old = System.currentTimeMillis() - java.time.Duration.ofHours(2).toMillis();
        Path dir = results.resolve("m").resolve("stuck");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("trace.jsonl"),
                "{\"at\":\"" + old + "\",\"kind\":\"tool\",\"tool\":\"read_file\"}\n");
        Files.createDirectories(results.resolve("claims").resolve("stuck"));
        assertTrue(new Overwatch(results, null, null).digest().contains("QUIET, still claimed"),
                "the fix must not buy its quiet by blinding the watcher to the one case it is for");
    }
}
