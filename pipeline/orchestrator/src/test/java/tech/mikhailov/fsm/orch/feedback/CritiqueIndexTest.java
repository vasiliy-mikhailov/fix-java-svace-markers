package tech.mikhailov.fsm.orch.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.mikhailov.fsm.feedback.CritiqueKind;
import tech.mikhailov.fsm.feedback.MarkerFeedback;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.nodes.BuildReproduceInput;
import tech.mikhailov.fsm.nodes.ParseFix;
import tech.mikhailov.fsm.nodes.ParseTest;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.nodes.RecordOutcome;
import tech.mikhailov.fsm.trial.Trial;
import tech.mikhailov.fsm.feedback.StageTrace;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.TestRealness;

/**
 * THE READ PATH, AGAINST THE BYTES THE WRITER ACTUALLY WRITES.
 *
 * <p>Everything this class asserts is a claim the guidance panel makes on screen, and every one of them
 * has a way of being quietly wrong:
 *
 * <ul>
 *   <li>OFF AND EMPTY MUST NOT BE THE SAME ANSWER. The store ships disabled, so the overwhelmingly
 *       likely first render of this panel is a deployment that has recorded nothing — and "no data"
 *       drawn as an empty list reads as "no problems found" about a pipeline nobody has ever asked.
 *       {@link #everyStateSaysSomethingDifferent()} pins that all five states produce different
 *       words.</li>
 *   <li>THE COUNT IS THE FEATURE. One "too many mocks" is noise and forty is evidence, so the
 *       grouping, the ordering and the distinct-marker count are asserted, not assumed.</li>
 *   <li>A POLL MUST NOT RE-READ THE FILE. {@link #everyLineIsParsedExactlyOnce()} appends to a file
 *       that has already been folded and proves the second pass consumed only the new bytes.</li>
 *   <li>A TORN TAIL IS NOT A RECORD. The writer's own header says a line with no trailing newline is
 *       an append in progress; counting it would report a half-written prove as a complaint.</li>
 * </ul>
 *
 * <p>THE FIXTURE IS BUILT THROUGH {@link MarkerFeedback#toMap()} AND {@code Critiques.harvest}, NOT
 * HAND-WRITTEN JSON. A reader tested against JSON a test author invented proves only that the author
 * can read their own file: the shapes that matter are the ones the writer emits, and the two drift the
 * moment either side gains a field. Building the record through the real writer means a change to
 * either half fails here.
 */
class CritiqueIndexTest {

    @TempDir
    Path directory;

    private Path file;

    @BeforeEach
    void locateTheStore() {
        file = directory.resolve("gepa-feedback.jsonl");
    }

    // ---- the states, which are the whole point of the panel -------------------------------------

    /**
     * OFF IS NOT EMPTY, EMPTY IS NOT CLEAN, AND NONE OF THEM IS "no problems found".
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the panel would render the same thing for a deployment
     * that never switched the store on and for a run in which every marker came back clean. The first
     * is "you have no evidence"; the second is "you have evidence and it is good news". Telling an
     * operator the second when the first is true is the failure this feature exists to avoid.
     */
    @Test
    void everyStateSaysSomethingDifferent() throws IOException {
        List<String> headlines = new ArrayList<>();
        List<String> hints = new ArrayList<>();

        CritiqueIndex off = new CritiqueIndex(false, file);
        assertThat(off.state()).isEqualTo(CritiqueIndex.State.OFF);
        headlines.add(off.headline());
        hints.add(String.valueOf(off.guidance().get("hint")));
        assertThat(off.headline()).containsIgnoringCase("off");
        assertThat(String.valueOf(off.guidance().get("hint")))
                .as("the panel has to name the switch, or 'off' is a dead end for whoever reads it")
                .contains("FSM_FEEDBACK");

        CritiqueIndex waiting = new CritiqueIndex(true, file);
        assertThat(waiting.state()).isEqualTo(CritiqueIndex.State.WAITING);
        headlines.add(waiting.headline());
        hints.add(String.valueOf(waiting.guidance().get("hint")));

        write(header(), record("clean-1", List.of()));
        CritiqueIndex clean = new CritiqueIndex(true, file);
        assertThat(clean.state()).isEqualTo(CritiqueIndex.State.CLEAN);
        headlines.add(clean.headline());
        hints.add(String.valueOf(clean.guidance().get("hint")));

        write(header(), record("noisy-1", List.of(mockingCritique())));
        CritiqueIndex recording = new CritiqueIndex(true, file);
        assertThat(recording.state()).isEqualTo(CritiqueIndex.State.RECORDING);
        headlines.add(recording.headline());
        hints.add(String.valueOf(recording.guidance().get("hint")));

        // UNREADABLE: a directory where the file should be. It exists, so it is not WAITING, and it
        // cannot be read, so it is not CLEAN — the third answer that would otherwise be silent.
        Path other = directory.resolve("unreadable.jsonl");
        Files.createDirectory(other);
        CritiqueIndex broken = new CritiqueIndex(true, other);
        assertThat(broken.state()).isEqualTo(CritiqueIndex.State.UNREADABLE);
        headlines.add(broken.headline());
        hints.add(String.valueOf(broken.guidance().get("hint")));

        assertThat(headlines)
                .as("two states that produce the same sentence are two states a reader cannot tell "
                        + "apart: %s", headlines)
                .doesNotHaveDuplicates()
                .hasSize(5);
        assertThat(hints).as("the second line has to differ too: %s", hints).doesNotHaveDuplicates();
    }

    /**
     * A DISABLED STORE DOES NOT TOUCH THE DISK, however full the file is.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the panel would show yesterday's critiques on a
     * deployment whose writer is off, so the counts would freeze at whatever the last enabled run left
     * and quietly go stale for weeks. "Off" has to mean the reader agrees with the writer.
     */
    @Test
    void switchedOffMeansNothingIsRead() throws IOException {
        write(header(), record("noisy-1", List.of(mockingCritique())));

        CritiqueIndex index = new CritiqueIndex(false, file);
        Map<String, Object> document = index.guidance();

        assertThat(document.get("enabled")).isEqualTo(false);
        assertThat(document.get("state")).isEqualTo("off");
        assertThat(document.get("records")).isEqualTo(0L);
        assertThat(guidance(document)).isEmpty();
    }

    // ---- the grouping, which is the evidence ----------------------------------------------------

    /**
     * GROUPED BY KIND, ORDERED BY RECURRENCE, AND COUNTED TWICE — occurrences AND distinct markers.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the panel would put a complaint raised once at the top
     * and a complaint raised forty times below the fold, which inverts the only signal it carries.
     * The distinct-marker count is the second half: forty occurrences from ONE marker retried forty
     * times is a bad marker, not a bad prompt, and a panel that could not distinguish them would send
     * somebody to rewrite a brief over a single file.
     */
    @Test
    void complaintsAreGroupedByKindAndOrderedByHowOftenTheyRecur() throws IOException {
        List<Object> lines = new ArrayList<>();
        lines.add(header());
        // three distinct markers, each mocking too much
        lines.add(record("m-1", List.of(mockingCritique())));
        lines.add(record("m-2", List.of(mockingCritique())));
        lines.add(record("m-3", List.of(mockingCritique())));
        // the SAME marker, twice — one re-prove, not two markers
        lines.add(record("m-4", List.of(interactionOnlyCritique())));
        lines.add(record("m-4", List.of(interactionOnlyCritique())));
        write(lines.toArray());

        CritiqueIndex index = new CritiqueIndex(true, file);
        List<Map<String, Object>> guidance = guidance(index.guidance());

        assertThat(guidance).hasSize(2);
        assertThat(guidance.get(0).get("kind")).isEqualTo(CritiqueKind.EXCESSIVE_MOCKING);
        assertThat(guidance.get(0).get("count")).isEqualTo(3);
        assertThat(guidance.get(0).get("markers")).isEqualTo(3);
        assertThat(guidance.get(1).get("kind")).isEqualTo(CritiqueKind.NO_STATE_ASSERTION);
        assertThat(guidance.get(1).get("count")).isEqualTo(2);
        assertThat(guidance.get(1).get("markers"))
                .as("one marker proved twice is ONE marker; counting the retry would claim evidence "
                        + "about a prompt that only ever came from a single file")
                .isEqualTo(1);

        // The quotable sentence, verbatim from the realness scorer, is what makes the count
        // believable. A kind with no evidence under it is a number nobody will act on.
        assertThat(String.valueOf(guidance.get(0).get("example")))
                .contains(TestRealness.STUB_MOCK_REASON);
        assertThat(guidance.get(0).get("stages")).isEqualTo(List.of("reproducer"));
        assertThat(guidance.get(0).get("sources")).isEqualTo(List.of("test_realness"));
    }

    /**
     * WHICH PROMPT FILE TO EDIT, named in the document rather than left to the reader.
     *
     * <p>THE LOOP THIS WHOLE FEATURE SERVES: a complaint recurs, you open that prompt, you change the
     * wording, the complaint stops. The stage is spelled {@code fix_skeptic} in a critique and the file
     * is {@code fix-skeptic.txt} on disk — one character apart, which is exactly the sort of gap that
     * gets bridged by a hand-written map that then rots.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the panel would send a reader to a file that does not
     * exist, and the loop would stop at its last step.
     */
    @Test
    void everyComplaintNamesThePromptFileBehindIt() {
        assertThat(CritiqueIndex.promptFor("reproducer").get("file"))
                .isEqualTo("prompts/reproducer.txt");
        assertThat(CritiqueIndex.promptFor("fixer").get("file")).isEqualTo("prompts/fixer.txt");
        assertThat(CritiqueIndex.promptFor("fix_skeptic").get("file"))
                .as("the critique spells it with an underscore and the file has a hyphen")
                .isEqualTo("prompts/fix-skeptic.txt");
        assertThat(CritiqueIndex.promptFor("pr_maker").get("file")).isEqualTo("prompts/pr-maker.txt");
        assertThat(CritiqueIndex.promptFor("verdict").get("file")).isEqualTo("prompts/verdict.txt");
        // A stage nobody has a prompt for resolves to nothing rather than to a plausible guess.
        assertThat(CritiqueIndex.promptFor("ingester").get("file")).isEqualTo("");
    }

    /**
     * A READER CAN REACH THE MARKERS BEHIND A COUNT.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: "excessive_mocking — 41" would be an assertion with no
     * way to check it. The panel's whole claim to being evidence is that the reader can open one of
     * the markers and read the test.
     */
    @Test
    void aRecurrenceCarriesTheMarkersItCameFrom() throws IOException {
        write(header(), record("m-1", List.of(mockingCritique())),
                record("m-2", List.of(mockingCritique())),
                // m-1 again: one marker, proved twice. It is ONE chip.
                record("m-1", List.of(mockingCritique())));

        List<Map<String, Object>> guidance = guidance(new CritiqueIndex(true, file).guidance());
        List<?> recent = (List<?>) guidance.get(0).get("recent");

        assertThat(guidance.get(0).get("count")).isEqualTo(3);
        assertThat(recent)
                .as("a marker proved three times is one file, and listing it three times reads as "
                        + "three — which is the confusion the separate `markers` count exists to "
                        + "prevent")
                .hasSize(2);
        assertThat(recent.stream().map(r -> Json.str(r, "key")).collect(Collectors.toSet()))
                .isEqualTo(Set.of("m-1", "m-2"));
        assertThat(Json.str(recent.get(0), "file")).endsWith(".java");
        assertThat(Json.str(recent.get(0), "line")).isEqualTo("42");
    }

    /**
     * ONE MARKER'S OWN CRITICISM, newest first, with the recurrence count attached.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: "previous markers with negative comments" would be
     * answerable only from the aggregate panel, and a reviewer looking at ONE marker would have no way
     * of knowing it had been criticised at all — which is the half of the user's request that is about
     * a specific marker rather than about the run.
     */
    @Test
    void aMarkerCarriesItsOwnCritiquesNewestFirst() throws IOException {
        write(header(),
                record("m-1", "2026-07-30T09:00:00Z", List.of(mockingCritique())),
                record("m-1", "2026-07-31T09:00:00Z", List.of(interactionOnlyCritique())),
                record("m-2", List.of(mockingCritique())));

        CritiqueIndex index = new CritiqueIndex(true, file);
        Map<String, Object> document = index.forMarker("m-1");
        List<?> critiques = (List<?>) document.get("critiques");

        assertThat(document.get("state")).isEqualTo("recording");
        assertThat(critiques).hasSize(2);
        assertThat(Json.str(critiques.get(0), "kind")).isEqualTo(CritiqueKind.NO_STATE_ASSERTION);
        assertThat(Json.str(critiques.get(0), "written_at")).isEqualTo("2026-07-31T09:00:00Z");
        assertThat(Json.str(critiques.get(1), "kind")).isEqualTo(CritiqueKind.EXCESSIVE_MOCKING);
        // THE NUMBER THAT MAKES ONE LINE ACTIONABLE. Two markers mocked too much; a reader of m-1 has
        // no way to see the other one, so the count travels with the critique.
        assertThat(Json.num(critiques.get(1), "kind_markers")).isEqualTo(2);
        assertThat(Json.str(Json.get(critiques.get(1), "prompt"), "file"))
                .isEqualTo("prompts/reproducer.txt");

        // A marker nobody complained about is EMPTY, and the header still says the store is on — so
        // the modal can say "nothing was wrong with this one" rather than nothing at all.
        Map<String, Object> quiet = index.forMarker("never-heard-of-it");
        assertThat((List<?>) quiet.get("critiques")).isEmpty();
        assertThat(quiet.get("state")).isEqualTo("recording");
        assertThat(quiet.get("enabled")).isEqualTo(true);
    }

    // ---- the file, which is append-only and can be enormous --------------------------------------

    /**
     * A POLL RE-READS NOTHING. Every line is parsed once in the lifetime of the process.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the dashboard polls every two seconds against a file that
     * accumulates across runs and is measured in gigabytes. Re-reading it per poll would not fail —
     * it would make the dashboard slower every day until it stopped answering, which is the worst
     * shape a defect can have.
     *
     * <p>AND IT IS ASSERTED ON THE BYTES, NOT ON THE COUNTS. That distinction is the whole test. A
     * reader that threw its projection away and re-parsed the entire file on every poll would produce
     * a byte-for-byte IDENTICAL document to this one — same records, same counts, same order — and
     * would simply get slower every day until it stopped finishing. There is no assertion about the
     * OUTPUT that can tell the two apart, which is why {@link CritiqueIndex#bytesRead()} exists: it is
     * the only witness the behaviour has. (Confirmed the hard way: a first draft of this test watched
     * the counts, and re-reading the whole file on every pass passed it.)
     */
    @Test
    void everyLineIsParsedExactlyOnce() throws IOException {
        write(header(), record("m-1", List.of(mockingCritique())));
        long first = Files.size(file);

        CritiqueIndex index = new CritiqueIndex(true, file);
        assertThat(index.guidance().get("critiques")).isEqualTo(1L);
        assertThat(index.bytesRead()).isEqualTo(first);

        // A POLL OVER AN UNCHANGED FILE READS NOTHING AT ALL.
        index.refreshNow();
        index.refreshNow();
        assertThat(index.bytesRead())
                .as("the dashboard polls every two seconds against a file that accumulates across "
                        + "runs; a pass that re-opened it would be the whole defect")
                .isEqualTo(first);

        append(record("m-2", List.of(mockingCritique())));
        long grown = Files.size(file);
        index.refreshNow();

        assertThat(index.bytesRead())
                .as("only the appended bytes may be read — %d new, not %d total", grown - first, grown)
                .isEqualTo(grown);

        Map<String, Object> document = index.guidance();
        assertThat(document.get("records")).isEqualTo(2L);
        assertThat(document.get("critiques")).isEqualTo(2L);
        assertThat(guidance(document).get(0).get("count")).isEqualTo(2);
    }

    /**
     * A LINE WITH NO TRAILING NEWLINE IS AN APPEND IN PROGRESS, and is not a record.
     *
     * <p>The store's own header says so. WHAT WOULD BE WRONG IF THIS FAILED: a poll landing in the
     * middle of a write would either raise an unparseable-JSON error into the panel or count half a
     * prove, and then count it AGAIN when the write completed.
     */
    @Test
    void aHalfWrittenRecordIsNotCountedUntilItsNewlineArrives() throws IOException {
        write(header(), record("m-1", List.of(mockingCritique())));
        // A torn tail: the bytes are there, the newline is not.
        Files.writeString(file, Json.stringify(record("m-2", List.of(mockingCritique()))),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        CritiqueIndex index = new CritiqueIndex(true, file);
        assertThat(index.guidance().get("records")).isEqualTo(1L);

        // …and when the writer finishes the line, it counts. Once.
        Files.writeString(file, "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        index.refreshNow();
        assertThat(index.guidance().get("records")).isEqualTo(2L);
        assertThat(index.guidance().get("critiques")).isEqualTo(2L);
    }

    /**
     * A FILE WITH NO NEWLINE IN IT IS READ ONCE AND THEN LET GO OF.
     *
     * <p>Every one of this class's three headline bounds is about a file that has newlines in it. Give
     * it one that does not — a truncated copy, a binary dropped in the path by mistake, an operator's
     * {@code cat} of two files, or simply one enormous record — and all three stop holding at once:
     * {@code consumed} is only incremented at a {@code '\n'}, so it stays 0, the {@code BUDGET_BYTES}
     * loop guard never trips, {@code offset} never advances, and every byte goes into an unbounded
     * {@code ByteArrayOutputStream}. The next poll starts from zero and does it again, two seconds
     * later, for ever.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED, and it is two separate things. The dashboard would re-read
     * and re-buffer the whole file every two seconds — the exact defect
     * {@code everyLineIsParsedExactlyOnce} exists to forbid, reached by an input that test does not
     * cover. And the pass would report {@code complete}, because {@code consumed < BUDGET_BYTES} is
     * trivially true when nothing was consumed — so the panel would put a full-total headline over a
     * file it had not read a record of.
     *
     * <p>Sized past the budget deliberately: under it, "read it all" and "read the budget" are the same
     * number and the test would prove nothing.
     */
    @Test
    void aFileWithNoNewlineIsNotReReadOnEveryPollAndIsNotCalledComplete() throws IOException {
        // No newline anywhere, and larger than one pass is allowed to consume.
        Files.writeString(file, "x".repeat(CritiqueIndex.BUDGET_BYTES + 4096),
                StandardCharsets.UTF_8);
        long size = Files.size(file);

        CritiqueIndex index = new CritiqueIndex(true, file);
        index.guidance();
        // It takes more than one pass, because it is bigger than one pass's budget — that part is the
        // design working. What must not happen is a SECOND reading of the same bytes.
        for (int poll = 0; poll < 5; poll++) {
            index.refreshNow();
        }
        long readOnce = index.bytesRead();

        assertThat(readOnce)
                .as("the file was read more than once: six polls took %d bytes off a %d-byte file",
                        readOnce, size)
                .isEqualTo(size);
        index.refreshNow();
        assertThat(index.bytesRead()).as("and a further poll reads nothing at all").isEqualTo(size);
        assertThat(index.guidance().get("complete"))
                .as("a pass that folded no record must not describe its counts as a total")
                .isEqualTo(false);
        // …and the reason is stated rather than shown as an empty panel, because "on and empty" and
        // "on and unreadable" are the two answers this class exists to keep apart.
        assertThat(index.state()).isEqualTo(CritiqueIndex.State.UNREADABLE);
        assertThat(index.headline()).contains("CANNOT BE READ");
    }

    /**
     * A FILE THAT WAS REPLACED STARTS AGAIN, rather than splicing a new tail onto old counts.
     *
     * <p>{@code FeedbackStore.repairOrCreate} deletes and re-creates the file when a kill landed inside
     * the very first write, and an operator may move one aside between runs. WHAT WOULD BE WRONG IF
     * THIS FAILED: the offset would point past the end of the new file and the panel would freeze at
     * the old counts forever, with no error anywhere.
     */
    @Test
    void aReplacedFileIsReadFromTheBeginning() throws IOException {
        write(header(), record("m-1", List.of(mockingCritique())),
                record("m-2", List.of(mockingCritique())));
        CritiqueIndex index = new CritiqueIndex(true, file);
        assertThat(index.guidance().get("records")).isEqualTo(2L);

        Files.delete(file);
        write(header(), record("m-9", List.of(interactionOnlyCritique())));
        index.refreshNow();

        Map<String, Object> document = index.guidance();
        assertThat(document.get("records")).isEqualTo(1L);
        assertThat(guidance(document)).singleElement()
                .satisfies(g -> assertThat(g.get("kind")).isEqualTo(CritiqueKind.NO_STATE_ASSERTION));
    }

    /**
     * THE HEADER LINE IS NOT A MARKER, and a line that will not parse does not take the panel down.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the record count would be one too high on every file in
     * existence, and a single corrupt line — the thing an append-only file across a crash is most
     * likely to contain — would blank the panel with an exception instead of skipping one record.
     */
    @Test
    void theHeaderAndACorruptLineAreBothSkipped() throws IOException {
        Files.writeString(file, Json.stringify(header()) + "\n"
                + "{not json at all\n"
                + Json.stringify(record("m-1", List.of(mockingCritique()))) + "\n",
                StandardCharsets.UTF_8);

        CritiqueIndex index = new CritiqueIndex(true, file);
        Map<String, Object> document = index.guidance();

        assertThat(document.get("records")).isEqualTo(1L);
        assertThat(document.get("critiques")).isEqualTo(1L);
        assertThat(document.get("state")).isEqualTo("recording");
    }

    /**
     * THE RECORD THE WRITER ACTUALLY WRITES, folded end to end.
     *
     * <p>Everything above builds its {@code feedback} array through {@code Critiques.harvest}, but via
     * a helper; this one goes the whole way — a {@link MarkerFeedback} of the shape
     * {@code ProveProcessor} constructs, serialised by {@link FeedbackStore} itself and read back by
     * the index. WHAT WOULD BE WRONG IF THIS FAILED: the panel would be correct about a JSON shape
     * nobody writes.
     */
    @Test
    void theStoresOwnBytesAreReadableByTheIndex() {
        FeedbackStore store = new FeedbackStore(true, file);
        store.append(realRecord());

        CritiqueIndex index = new CritiqueIndex(true, file);
        Map<String, Object> document = index.guidance();

        assertThat(document.get("state")).isEqualTo("recording");
        assertThat(document.get("records")).isEqualTo(1L);
        assertThat(guidance(document)).extracting(g -> g.get("kind"))
                .as("the realness scorer's two reasons are two kinds, not one blob")
                .contains(CritiqueKind.EXCESSIVE_MOCKING, CritiqueKind.NO_STATE_ASSERTION);
        assertThat((List<?>) index.forMarker("real-1").get("critiques")).isNotEmpty();
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** A whole prove of the shape {@code ProveProcessor} builds, with a test that mocks too much. */
    private static Map<String, Object> realRecord() {
        Map<String, Object> prep = new LinkedHashMap<>();
        prep.put("suspicion_key", "real-1");
        prep.put("repo", "org/app");
        prep.put("file", "src/main/java/org/app/Leak.java");
        prep.put("svace_line", 42.0);
        prep.put("svace_checker", "HANDLE_LEAK.EX");

        Map<String, Object> parseTest = new LinkedHashMap<>();
        parseTest.put("can_prove", true);
        parseTest.put("test_code", "class LeakTest { @Test void t() { } }");
        parseTest.put("test_score", 2.0);
        parseTest.put("test_sound", false);
        parseTest.put("test_realness", "9" + TestRealness.STUB_MOCK_REASON + "; "
                + TestRealness.INTERACTION_ONLY_REASON);

        // Built as the chain builds one — a Trial, from the typed pieces — because that is what the
        // store now writes. The two rows this fixture states are the ones the harvest reads.
        Trial trial = Trial.of("real-1", "2026-07-31T12:00:00Z",
                new PrepProver.Outcome("real-1", "org/app", "main", true, "", 0d,
                        "src/main/java/org/app/Leak.java", "", "org.app", "Leak", "leak",
                        "LeakTest", "src/test/java/org/app/LeakTest.java", "correctness", "high",
                        "a leak", "the handle escapes", "", "m-1", "HANDLE_LEAK.EX", "Major", 42d,
                        "test"),
                new BuildReproduceInput.Outcome(prep, "class Leak {}", false, "WRITE A TEST",
                        "leak", "exact", "", null, ""),
                StageTrace.of("write a test", "{}"),
                new ParseTest.Result(prep, true, false, "class LeakTest { @Test void t() { } }",
                        false, 2, "9" + TestRealness.STUB_MOCK_REASON + "; "
                                + TestRealness.INTERACTION_ONLY_REASON,
                        false, "", "", Map.of(), ""),
                Map.of("ok", true, "red_summary", Map.of("test_executed", true),
                        "red_reproduced", true),
                null, StageTrace.NOT_CALLED,
                new ParseFix.Result(prep, false, false, "", "", "", "", "", "", Map.of()),
                Map.of(), StageTrace.NOT_CALLED, Map.of(), StageTrace.NOT_CALLED, Map.of(),
                new RecordOutcome.Outcome("real-1", "org/app", "src/main/java/org/app/Leak.java",
                        "a leak", "21", "src/test/java/org/app/LeakTest.java", "", "[]", true,
                        false, 2d, "", "", "", MarkerState.NEEDS_REVIEW, "", 1L, "main", Map.of()),
                StageTrace.NOT_CALLED, Map.of(), "", Map.of());
        return new MarkerFeedback(trial).toMap();
    }

    private static Map<String, Object> header() {
        return FeedbackStore.header("2026-07-31T00:00:00Z");
    }

    private static Map<String, Object> record(String key, List<Map<String, Object>> critiques) {
        return record(key, "2026-07-31T12:00:00Z", critiques);
    }

    /** A record carrying only the fields the index reads, which is the contract under test. */
    private static Map<String, Object> record(String key, String writtenAt,
                                              List<Map<String, Object>> critiques) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("suspicion_key", key);
        marker.put("file", "src/main/java/org/app/" + key + ".java");
        marker.put("svace_line", 42.0);
        marker.put("svace_checker", "HANDLE_LEAK.EX");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", MarkerFeedback.SCHEMA);
        m.put("written_at", writtenAt);
        m.put("dedup_key", key);
        m.put("marker", marker);
        m.put("feedback", critiques);
        return m;
    }

    private static Map<String, Object> mockingCritique() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", "reproducer");
        m.put("source", "test_realness");
        m.put("kind", CritiqueKind.EXCESSIVE_MOCKING);
        m.put("text", "9" + TestRealness.STUB_MOCK_REASON);
        m.put("context", Map.of("stubs", 9.0));
        return m;
    }

    private static Map<String, Object> interactionOnlyCritique() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", "reproducer");
        m.put("source", "test_realness");
        m.put("kind", CritiqueKind.NO_STATE_ASSERTION);
        m.put("text", TestRealness.INTERACTION_ONLY_REASON);
        m.put("context", Map.of());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> guidance(Map<String, Object> document) {
        return (List<Map<String, Object>>) document.get("guidance");
    }

    private void write(Object... lines) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Object line : lines) {
            text.append(Json.stringify(line)).append('\n');
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    private void append(Object line) throws IOException {
        Files.writeString(file, Json.stringify(line) + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
    }
}
