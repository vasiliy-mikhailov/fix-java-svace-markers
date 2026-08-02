package tech.mikhailov.fsm.orch.ui;

import java.util.List;
import tech.mikhailov.fsm.nodes.Verdict;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * A RUN THAT A REAL PROVE WOULD HAVE LEFT BEHIND, written through the real DAOs.
 *
 * <p>WHY THE DAOs AND NOT SQL. The page reads {@code /api/state}, which is
 * {@link tech.mikhailov.fsm.orch.web.DashboardService#state()} over {@link Suspicion#toMap()} and
 * {@link Bug#toMap()}; those maps ARE the contract with {@code static/app.js}. Inserting rows with
 * hand-written SQL would let a column drift out of the record without the browser tests noticing —
 * which is the same second-column-list defect {@link tech.mikhailov.fsm.orch.web.MarkerColumns} and
 * this module's pom argue against everywhere else. Going through {@link SuspicionDao#upsert} and
 * {@link BugDao#upsert} means the rows arrive by the route the pipeline actually writes them.
 *
 * <p>EVERY MARKER HAS A DISTINCT FILE NAME, and that is load-bearing rather than tidy. The page
 * renders {@code pkg(file)} into the markers table, so a distinct file name is how a browser test
 * addresses ONE row — {@code tr:has-text("ProvenLeak.java")} — without depending on the order
 * {@link SuspicionDao#findAll()} happens to return. An order-dependent locator is a test that starts
 * clicking a different marker the day somebody adds an ORDER BY.
 *
 * <p>THE OUTCOMES ARE NOT DECORATION. Each one is the input to a specific rendering rule that has
 * shipped broken:
 * <ul>
 *   <li>{@link #PROVEN_1} and friends carry a test, a diff, a drafted PR and an argued verdict — the
 *       four things the investigation modal opens onto, and the four that read "not proven yet" when
 *       {@code /api/bug} went missing;</li>
 *   <li>{@link #NO_ARTIFACT} carries NONE of them, so the modal's empty state can be asserted as an
 *       empty state rather than as leftovers from the marker opened before it;</li>
 *   <li>{@link #SKIPPED_WITH_TEXT} and {@link #SKIPPED_NO_TEXT} carry
 *       {@code bugs.verdict_status = 'skipped'} — a run made cheap on purpose, which must render as a
 *       banner and never as an argued verdict;</li>
 *   <li>the {@code new} and {@code proving} rows are what the Run-progress arithmetic subtracts, so
 *       the numbers on screen have something to be wrong about.</li>
 * </ul>
 */
final class Seeds {

    private Seeds() {
    }

    // ---- the markers, by dedup_key ------------------------------------------------------------

    static final String QUEUED_1 = "ui-queued-1";
    static final String QUEUED_2 = "ui-queued-2";
    static final String IN_FLIGHT = "ui-in-flight";

    /** Proven end to end: red, green, a diff, a drafted PR and an argued verdict. */
    static final String PROVEN_1 = "ui-proven-1";

    static final String PROVEN_2 = "ui-proven-2";
    static final String PROVEN_3 = "ui-proven-3";

    /** Settled, argued, and the argument was SKIPPED — with the composed text the hatch leaves. */
    static final String SKIPPED_WITH_TEXT = "ui-skipped-text";

    /** Settled, and nobody argued it at all: the bare {@code verdict_status = 'skipped'} row. */
    static final String SKIPPED_NO_TEXT = "ui-skipped-bare";

    /** Settled with NO {@code bugs} row — the modal's empty state. */
    static final String NO_ARTIFACT = "ui-no-artifact";

    static final String REPRODUCED = "ui-reproduced";
    static final String INFRA_STUCK = "ui-infra-stuck";

    // ---- the file names the browser addresses rows by -----------------------------------------

    static final String PROVEN_1_FILE = "ProvenLeak.java";
    static final String NO_ARTIFACT_FILE = "NeverCompiled.java";
    static final String SKIPPED_WITH_TEXT_FILE = "Refuted.java";
    static final String SKIPPED_NO_TEXT_FILE = "ByDesign.java";

    // ---- distinctive strings a test can look for on screen ------------------------------------

    /** In {@code bugs.verdict_text} for {@link #PROVEN_1}: an argument a model actually wrote. */
    static final String PROVEN_1_VERDICT =
            "the stream opened at line 42 is never closed on the exception path";

    /** In {@code bugs.test_code} for {@link #PROVEN_1}: the reproducer's class name. */
    static final String PROVEN_1_TEST_CLASS = "ProvenLeakReproducerTest";

    /** In {@code bugs.fix_diff} for {@link #PROVEN_1}: the line the patch removes. */
    static final String PROVEN_1_FIX_REMOVES = "return new FileInputStream(target);";

    /** In {@code bugs.fix_diff} for {@link #PROVEN_1}: the line the patch adds. */
    static final String PROVEN_1_FIX_ADDS = "try (var in = new FileInputStream(target))";

    /** In {@code bugs.pr_title} for {@link #PROVEN_1}. */
    static final String PROVEN_1_PR_TITLE = "Close the FileInputStream opened in ProvenLeak#read";

    /**
     * The text a SKIPPED verdict still carries when the exhausted-build hatch composed one.
     *
     * <p>It is character-for-character the shape of a marker whose model endpoint was down, which is
     * exactly why the page must label it rather than print it as a conclusion.
     */
    static final String SKIPPED_COMPOSED_TEXT =
            "NOT SETTLED - every build attempt for this marker ended before a test could run";

    /** The banner {@code renderMarker()} paints on a skipped row. Verbatim from {@code app.js}. */
    static final String SKIPPED_BANNER = "Verdict — NOT WRITTEN (stage switched off)";

    /** The header {@code renderMarker()} uses instead of "Verdict" when the text was composed. */
    static final String COMPOSED_HEADER = "Composed from the run, not argued";

    /**
     * The ONE tab a marker with no artifact gets, verbatim from {@code showInvestigation()}.
     *
     * <p>Written down once, because it is both the label a test clicks and the thing that is WRONG
     * when {@code /api/bug} has gone: every proven marker in the run collapses to this tab, and a test
     * that guessed the wording would fail on the guess rather than on the defect.
     */
    static final String NO_ARTIFACT_TAB = "Reproducer / Fixer / PR";

    /** What the markers table calls the file column's package prefix; stripped by {@code pkg()}. */
    static final String SOURCE_ROOT = "src/main/java/tech/example/";

    /**
     * The repository every marker here came from, in the {@code owner/name} shape the pipeline
     * requires — {@code IngestRequest#repo}, and what {@code GithubSourceClient} builds
     * {@code /repos/{repo}/contents/…} out of.
     *
     * <p>Named rather than inlined because {@code MarkerLinksLeadToTheCodeTest} spells out the whole
     * {@code https://github.com/…} URL these rows must produce, and a fixture that could drift from
     * the URL asserted against it would make that test pass on the wrong string.
     */
    static final String REPO = "example/target-repo";

    /** @see #REPO */
    static final String BRANCH = "main";

    /** The line every seeded marker was reported on. @see #REPO */
    static final int LINE = 42;

    /**
     * Wipe both tables and write the run above.
     *
     * <p>ORDER MATTERS: artifacts first. {@code bugs} references a marker only by
     * {@code suspicion_key} — there is no foreign key, deliberately, so evidence survives a re-scan —
     * but clearing markers first would leave a window in which {@code /api/state} joins artifacts onto
     * nothing and every row reports {@code marker_orphaned}. Nothing here runs concurrently with a
     * browser, and the order is still written down because the reverse produces a page that renders
     * perfectly and says the wrong thing.
     */
    static void backlog(SuspicionDao suspicions, BugDao bugs) {
        bugs.deleteAll();
        suspicions.deleteAll();

        suspicions.upsert(marker(QUEUED_1, "Queued1.java", SuspicionDao.STATUS_NEW, "high"));
        suspicions.upsert(marker(QUEUED_2, "Queued2.java", SuspicionDao.STATUS_NEW, "medium"));
        suspicions.upsert(marker(IN_FLIGHT, "InFlight.java", SuspicionDao.STATUS_PROVING, "high"));
        suspicions.upsert(marker(PROVEN_1, PROVEN_1_FILE, "verified", "high"));
        suspicions.upsert(marker(PROVEN_2, "ProvenClose.java", "verified", "high"));
        suspicions.upsert(marker(PROVEN_3, "ProvenStream.java", "verified", "medium"));
        suspicions.upsert(marker(SKIPPED_WITH_TEXT, SKIPPED_WITH_TEXT_FILE, "false_positive", "low"));
        suspicions.upsert(marker(SKIPPED_NO_TEXT, SKIPPED_NO_TEXT_FILE, "by_design", "low"));
        suspicions.upsert(marker(NO_ARTIFACT, NO_ARTIFACT_FILE, "unprovable", "medium"));
        suspicions.upsert(marker(REPRODUCED, "Reproduced.java", "reproduced", "high"));
        suspicions.upsert(marker(INFRA_STUCK, "InfraStuck.java", "infra_stuck", "high"));

        bugs.upsert(proven(PROVEN_1, PROVEN_1_FILE, "pr_ready"));
        bugs.upsert(proven(PROVEN_2, "ProvenClose.java", "pr_ready"));
        bugs.upsert(proven(PROVEN_3, "ProvenStream.java", "pr_rejected"));
        bugs.upsert(skipped(SKIPPED_WITH_TEXT, SKIPPED_WITH_TEXT_FILE, "false_positive",
                SKIPPED_COMPOSED_TEXT, "undetermined"));
        bugs.upsert(skipped(SKIPPED_NO_TEXT, SKIPPED_NO_TEXT_FILE, "by_design", "", ""));
        bugs.upsert(reproducedOnly());
    }

    /** Every marker this fixture writes, in the order it writes them. */
    static List<String> markerKeys() {
        return List.of(QUEUED_1, QUEUED_2, IN_FLIGHT, PROVEN_1, PROVEN_2, PROVEN_3,
                SKIPPED_WITH_TEXT, SKIPPED_NO_TEXT, NO_ARTIFACT, REPRODUCED, INFRA_STUCK);
    }

    // ---- rows ---------------------------------------------------------------------------------

    private static Suspicion marker(String key, String file, String status, String severity) {
        String className = file.substring(0, file.length() - ".java".length());
        return new Suspicion(key, "SV-" + key, REPO, BRANCH, SOURCE_ROOT + file,
                className, "read", LINE, LINE, className + "#read", "exact", "resource-leak",
                severity, "MEMORY_LEAK.EX", severity.equals("high") ? "critical" : "major",
                "leak in " + className + "#read",
                "a stream opened in " + className + "#read is never closed",
                "line 42: new FileInputStream(target)", status, "", 1L, "ingester@1.0",
                "example/target-repo:" + className + "#read");
    }

    /** Red, green, a diff, a drafted PR and an argued verdict — the fully worked marker. */
    private static Bug proven(String key, String file, String state) {
        String className = file.substring(0, file.length() - ".java".length());
        return new Bug(key, REPO, SOURCE_ROOT + file,
                "leak in " + className + "#read", "17",
                "src/test/java/tech/example/" + className + "ReproducerTest.java",
                """
                package tech.example;

                class %sReproducerTest {
                    @Test
                    void theStreamIsLeakedWhenReadThrows() throws Exception {
                        assertThrows(IOException.class, () -> new %s().read(missing));
                    }
                }
                """.formatted(className, className),
                """
                [{"path":"src/main/java/tech/example/%s","old_str":"%s","new_str":"%s"}]
                """.formatted(file, PROVEN_1_FIX_REMOVES, PROVEN_1_FIX_ADDS).strip(),
                true, true, 0.82d, "worth opening upstream",
                "Close the FileInputStream opened in " + className + "#read",
                "The stream is opened before the try block, so an exception on the read path leaks it.",
                state, "", BRANCH,
                "{\"pipeline\":\"1.4.0\",\"reproducer\":\"1.4.0\",\"fixer\":\"1.4.0\"}",
                "The claim holds: " + PROVEN_1_VERDICT + ".", "true-positive", "MEMORY_LEAK.EX");
    }

    /**
     * A marker whose argument was never asked for.
     *
     * <p>{@code verdict_status = 'skipped'} is the ONLY column separating this from a model that was
     * asked and answered with nothing. That is the whole point of the row, and of the two tests that
     * open it.
     */
    private static Bug skipped(String key, String file, String state, String text, String kind) {
        String className = file.substring(0, file.length() - ".java".length());
        return new Bug(key, REPO, SOURCE_ROOT + file,
                "leak in " + className + "#read", "17", "", "", "[]", false, false, 0d, "",
                "", "", state, "", BRANCH, "{\"pipeline\":\"1.4.0\"}", text, kind, "MEMORY_LEAK.EX",
                Verdict.SKIPPED);
    }

    /** Real, and no fix was found: red verified, green not. */
    private static Bug reproducedOnly() {
        return new Bug(REPRODUCED, REPO, SOURCE_ROOT + "Reproduced.java",
                "leak in Reproduced#read", "17",
                "src/test/java/tech/example/ReproducedReproducerTest.java",
                "class ReproducedReproducerTest { }", "[]", true, false, 0.5d, "needs a human",
                "", "", "needs_review", "", BRANCH, "{\"pipeline\":\"1.4.0\"}",
                "The claim holds and the fix did not pass review.", "true-positive-unfixed",
                "MEMORY_LEAK.EX");
    }
}
