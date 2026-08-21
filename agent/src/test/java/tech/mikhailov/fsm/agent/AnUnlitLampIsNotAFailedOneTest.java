package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PROVE THAT DIED FETCHING ITS CHECKOUT SAID THE TEST HAD BEEN RUN.
 *
 * <p>{@link JsonlTrace#failed} writes an {@code infra} settlement, and {@link Settlement} carried
 * {@code red_verified} as a primitive {@code boolean} — so every row had one whether or not a build
 * existed to report, and a note wrote {@code false}. {@link Api} already had a guard for exactly
 * this and its own comment states the principle: {@code false} does not mean "not yet", it means
 * "we ran the test and it did not fail", and that is the one claim this pipeline must never make by
 * accident.
 *
 * <p>The guard skipped rows whose state was {@code proving}. An {@code infra} row is not
 * {@code proving}. So it walked straight through, and the marker page drew a dim red lamp whose own
 * label reads "reproduced: the test failed first — it was reached and did not happen" for a marker
 * where nothing was reached and no test was ever written.
 *
 * <p>IT PRODUCED NO ERROR, NO FAILED CALL AND NO RED TEST. The lamp was plausible, the page
 * rendered, and the only way to see it was to know what the lamp meant and find a marker that had
 * died in infra. That is why the fix is at the RECORD: a row that reported no build now omits both
 * fields, so "did this row report a build" is a question about the row rather than about a list of
 * state names somebody has to remember to extend.
 */
class AnUnlitLampIsNotAFailedOneTest {

    private static final String KEY =
            "https://github.com/WebGoat/WebGoat.git|src/main/java/a/A.java|10|TAINTED_PTR";

    /** A settlements file holding exactly the given rows. */
    private static Path record(Path dir, String... rows) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve("settlements.jsonl");
        Files.writeString(file, String.join("\n", rows) + (rows.length == 0 ? "" : "\n"));
        return file;
    }

    private static String row(String state, String flags) {
        return "{\"suspicion_key\":\"" + KEY + "\",\"state\":\"" + state + "\",\"verdict_kind\":\""
                + state + "\",\"verdict_text\":\"IOException\"" + flags + "}";
    }

    @Test
    @DisplayName("a note with no build to report writes no build fields at all")
    void theRecordCanSayNothingRan(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve("settlements.jsonl");
        Settlement.note(file, KEY, "proving", "reproduce: planning");
        Settlement.note(file, KEY, "infra", "IOException: no space left on device");
        String written = Files.readString(file);
        assertTrue(!written.contains("red_verified"),
                "a stage boundary and an infra failure have no build result, and writing `false` for "
                        + "one is the pipeline claiming a test ran: " + written);
        assertTrue(!written.contains("green_verified"), written);
    }

    @Test
    @DisplayName("and a real settlement still records what the runner actually reported")
    void aRealBuildIsStillRecorded(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve("settlements.jsonl");
        Settlement.note(file, KEY, "verified/pr-ready", "the patch holds", true, true);
        String written = Files.readString(file);
        assertTrue(written.contains("\"red_verified\":true"), written);
        assertTrue(written.contains("\"green_verified\":true"), written);
    }

    @Test
    @DisplayName("the index reports absent, not false, for a marker that only ever failed on infra")
    void theIndexDoesNotDeny(@TempDir Path dir) throws Exception {
        // WRITTEN THE OLD WAY ON PURPOSE. The record on disk is months of infra rows carrying
        // red_verified=false, and a fix that only changes the writer leaves every one of them
        // lying. This row is exactly what those look like.
        Path file = record(dir, row("infra", ",\"red_verified\":false,\"green_verified\":false"));
        String json = Api.index(file, dir.resolve("trace.jsonl"), List.of(KEY));
        assertTrue(json.contains("\"redVerified\":null"),
                "an infra row reports no build, so the answer is absent — `false` there says the "
                        + "test ran and passed: " + json);
        assertTrue(json.contains("\"greenVerified\":null"), json);
    }

    @Test
    @DisplayName("and so does the marker page, which reads the flags by a different route")
    void theMarkerPageDoesNotDenyEither(@TempDir Path dir) throws Exception {
        Path file = record(dir, row("infra", ",\"red_verified\":false,\"green_verified\":false"));
        String json = ApiMarker.marker(file, dir.resolve("trace.jsonl"), KEY);
        assertTrue(json.contains("\"redVerified\":null"),
                "`settled()` picks the infra row because it is not `proving`, and the flags must not "
                        + "come from it: " + json);
        // AND THE INFRA REASON STILL COMES FROM THAT ROW, which is why `settled` was not narrowed
        // instead — `infra_reason` and `verdict_text` live there and nowhere else.
        assertTrue(json.contains("IOException"), "the failure itself must still be readable: " + json);
    }

    @Test
    @DisplayName("a RED that genuinely went red survives a later infra failure")
    void aLaterFailureDoesNotEraseAProof(@TempDir Path dir) throws Exception {
        Path file = record(dir,
                row("needs-review", ",\"red_verified\":true,\"green_verified\":false"),
                row("infra", ",\"red_verified\":false,\"green_verified\":false"));
        assertTrue(Api.index(file, dir.resolve("trace.jsonl"), List.of(KEY)).contains("\"redVerified\":true"),
                "the test really did fail once; a later prove dying on infra is not evidence against "
                        + "it, and overwriting the proof with `false` was the old behaviour");
    }
}
