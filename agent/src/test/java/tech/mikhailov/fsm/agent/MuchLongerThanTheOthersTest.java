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
 * "TOO LONG" IS A COMPARISON, NOT A NUMBER SOMEBODY PICKED.
 *
 * <p>Half an hour is only long because most markers finish in five, and the moment the model, the
 * endpoint or the subject changes, a fixed cap is either strangling ordinary work or letting a stuck
 * prove run all day. The median of what has actually settled moves with them.
 *
 * <p>The floor matters more than the multiple. Early in a run the median comes from two or three
 * markers and can be a minute, which would set aside everything — so nothing is an outlier until
 * there are enough settlements to have a median of, and nothing is an outlier under twenty minutes
 * however fast the rest of the run has been.
 */
class MuchLongerThanTheOthersTest {

    private static Trace quiet() {
        return new Trace() {
            @Override public void asked(String a, String p, String r) { }
            @Override public void thought(String agent, String text) { }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
            @Override public void failed(String marker, Throwable cause) { }
            @Override public void progress(String marker, String note) { }
            @Override public void priced(String marker, String minutes, String items) { }
        };
    }

    /** A settled lane that took the given minutes. */
    private static void lane(Path results, String id, long minutes, boolean settled)
            throws Exception {
        Path d = results.resolve("m").resolve(id);
        Files.createDirectories(d);
        long start = System.currentTimeMillis() - minutes * 60_000;
        Files.writeString(d.resolve("trace.jsonl"),
                "{\"at\":\"" + start + "\",\"kind\":\"asked\"}\n"
                        + "{\"at\":\"" + (start + minutes * 60_000) + "\",\"kind\":\"asked\"}\n");
        Files.writeString(d.resolve("settlements.jsonl"),
                settled ? "{\"state\":\"by-design\"}\n" : "{\"state\":\"proving\"}\n");
    }

    @Test
    @DisplayName("with too few settlements there is no typical duration and nothing is an outlier")
    void tooEarly(@TempDir Path dir) throws Exception {
        for (int i = 0; i < Pace.ENOUGH - 1; i++) {
            lane(dir, "quick" + i, 5, true);
        }
        lane(dir, "slow", 600, false);
        assertEquals(0, Pace.typical(dir),
                "a median of three markers is not a median; setting anything aside on it would "
                        + "set aside everything");
        assertTrue(Pace.outlier(dir, dir.resolve("m").resolve("slow")).isBlank(),
                "ten hours is not an outlier if nothing is known about the others yet");
    }

    @Test
    @DisplayName("a prove far past the median is named, with both numbers")
    void outlier(@TempDir Path dir) throws Exception {
        for (int i = 0; i < Pace.ENOUGH; i++) {
            lane(dir, "quick" + i, 10, true);
        }
        lane(dir, "slow", 10 * Pace.MUCH_LONGER + 30, false);
        assertEquals(10, Pace.typical(dir));
        String said = Pace.outlier(dir, dir.resolve("m").resolve("slow"));
        assertTrue(said.contains("MUCH LONGER"), said);
        assertTrue(said.contains("median of 10"), "the comparison, not just the verdict: " + said);
        assertTrue(said.contains("not necessarily stuck"),
                "it is a slot problem rather than a fault, and saying otherwise invites the wrong "
                        + "tool: " + said);
    }

    @Test
    @DisplayName("a quick run cannot make twenty minutes an outlier")
    void floor(@TempDir Path dir) throws Exception {
        for (int i = 0; i < Pace.ENOUGH; i++) {
            lane(dir, "quick" + i, 1, true);
        }
        lane(dir, "slow", Pace.NEVER_BEFORE - 5, false);
        assertTrue(Pace.outlier(dir, dir.resolve("m").resolve("slow")).isBlank(),
                "four times one minute is four minutes, and setting a marker aside that has been "
                        + "going a quarter of an hour is churn rather than scheduling");
    }

    @Test
    @DisplayName("one four-hour prove does not drag the typical up to hide itself")
    void median(@TempDir Path dir) throws Exception {
        for (int i = 0; i < Pace.ENOUGH; i++) {
            lane(dir, "quick" + i, 6, true);
        }
        lane(dir, "monster", 240, true);
        assertEquals(6, Pace.typical(dir),
                "a mean would be dragged far enough by the four-hour one to make itself ordinary, "
                        + "which is exactly the case this exists to catch");
    }

    @Test
    @DisplayName("set aside, then brought back")
    void pauseResume(@TempDir Path dir) throws Exception {
        assertFalse(Pace.postponed(dir, "x"));
        Pace.postpone(dir, "x", "taking six times the median", quiet());
        assertTrue(Pace.postponed(dir, "x"));
        assertEquals(java.util.List.of("x"), Pace.allPostponed(dir),
                "the pool reads this list when the queue is done");
        Pace.resume(dir, "x", quiet());
        assertFalse(Pace.postponed(dir, "x"));
        assertTrue(Pace.allPostponed(dir).isEmpty());
    }

    @Test
    @DisplayName("only settled lanes count towards what is typical")
    void onlySettled(@TempDir Path dir) throws Exception {
        for (int i = 0; i < Pace.ENOUGH; i++) {
            lane(dir, "running" + i, 300, false);
        }
        assertEquals(0, Pace.typical(dir),
                "a run where nothing has finished has no typical duration, and taking the "
                        + "unfinished ones as evidence would make every long prove look normal");
    }
}
