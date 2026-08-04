package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * THE NAMES THE DASHBOARD SENDS ARE THE NAMES THE PAGE READS — for the whole read path, not just for a
 * comment.
 *
 * <p>{@code ThePageAndTheApiAgreeOnEveryFieldNameTest} does this for {@code asComment()} and it is the
 * strongest guard in the module. It also covers 8 names out of the read path's several dozen. THIRTY
 * of them had nothing: the envelope of {@code /api/state}, the five columns of the activity table, the
 * eleven tiles of the effort panel, the six itemised rates under them, and the three numbers the socket
 * pushes on every transition. {@link WorkModel.Metrics} says so in its own javadoc — "the component
 * names ARE the JSON keys the page reads, so renaming one here silently blanks a tile rather than
 * failing anything". This is that failing.
 *
 * <p>WHY IT IS POSSIBLE HERE AND NOT FOR A MARKER ROW, which is the whole argument for
 * {@link DashboardPresenter} being a presenter rather than a set of view models. Every name checked
 * below is read through a CHOKE POINT — {@code render(s)} destructures the state document at the top
 * and {@code liveCounts(c)} is seven lines long — exactly as {@code asComment()} is the choke point for
 * a comment. The 41 marker and artifact column names have no such point: the page reads them at 241
 * sites on 123 lines, almost all of them inside HTML template strings, so a check over THEM would be a
 * regex over string concatenation and would be weakest precisely where the names are most numerous.
 * The rows are therefore not re-described anywhere — see
 * {@code TheReadPathHasNoSecondColumnListTest} — and what IS re-described is checked here.
 *
 * <p>IT READS THE SHIPPED FILE. {@code static/app.js} is served out of the jar with no build step, so
 * the bytes parsed here are the bytes the browser runs.
 *
 * <p>AND EVERY EXTRACTION IS PROVEN NON-EMPTY BEFORE IT IS JUDGED. The failure mode of a
 * source-scanning test is that the shape it looks for moves, it finds nothing, and "none of them are
 * wrong" is reported as agreement. Each check below therefore names the fields without which the
 * panel it guards is not that panel, and asserts they were found first.
 *
 * <p>THE DIRECTION IS "THE PAGE READS ONLY WHAT THE SERVER SENDS", deliberately, and not equality. A
 * key the server sends that no panel reads yet is dead weight, not an outage; a key the page reads that
 * nothing sends renders as {@code undefined} — a blank tile, an em dash, an uncoloured row — with no
 * error anywhere. Only one of those two is invisible, so only one of them is a build failure.
 */
class ThePageAndTheStateDocumentAgreeOnEveryNameTest {

    /** {@code s.suspicions} — the state document, as {@code render(s)} destructures it. */
    private static final Pattern OFF_STATE = Pattern.compile("\\bs\\.([a-z_]+)");

    /** {@code a.wf} — one row of the activity table. */
    private static final Pattern OFF_RUN = Pattern.compile("\\ba\\.([a-z_]+)");

    /** {@code w.machineHours} — the effort model. */
    private static final Pattern OFF_WORK = Pattern.compile("\\bw\\.([a-zA-Z]+)");

    /** {@code hm.write_test} — one itemised rate under the effort panel. */
    private static final Pattern OFF_RATES = Pattern.compile("\\bhm\\.([a-z_]+)");

    /** {@code c.remaining} — the counts document the socket pushes. */
    private static final Pattern OFF_COUNTS = Pattern.compile("\\bc\\.([a-zA-Z_]+)");

    // ---- the state document ------------------------------------------------------------------------

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED. {@code render()} reads {@code s.suspicions} and
     * {@code s.bugs} without a guard, so a rename of either is a TypeError that kills the whole render
     * — the page freezes on whatever it last drew and keeps polling. {@code s.activity} and
     * {@code s.work} are read with {@code ||[]} and {@code ||{}}, which is worse: the activity table
     * empties, the stage banner stops saying "proving", and all five effort tiles go to em dashes,
     * on a run that is working perfectly. Nothing logs.
     */
    @Test
    void everyNameThePageReadsOffTheStateDocumentIsOneTheServerSends() throws Exception {
        Set<String> read = namesIn(OFF_STATE, render());

        assertThat(read)
                .as("the state document's own names could not be found in render() at all, so this "
                        + "check would agree that nothing disagrees. The function's shape changed — "
                        + "fix the extraction rather than deleting the check")
                .contains("suspicions", "bugs", "activity", "work", "prover_built");

        assertThat(read)
                .as("static/app.js reads these off /api/state and DashboardPresenter.state() does "
                        + "not send them. There is no failure to see: an absent key is `undefined`, "
                        + "which renders as an empty table or a missing panel on a run that is "
                        + "working. Sent: %s", stateKeys())
                .isSubsetOf(stateKeys());
    }

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED. The activity panel is the one screen an operator watches to
     * decide whether a 26-hour run is alive. {@code a.status} paints the dot AND drives the stage
     * banner's pulse; rename it and every run renders uncoloured with the banner permanently idle,
     * which is indistinguishable from a pipeline that has stopped.
     */
    @Test
    void everyNameThePageReadsOffAnActivityRowIsOneTheServerSends() throws Exception {
        Set<String> read = namesIn(OFF_RUN, render());

        assertThat(read)
                .as("the activity row's names could not be found in render(); the table's shape "
                        + "changed and this check would pass by reading nothing")
                .contains("wf", "status", "started", "file", "dur");

        Set<String> sent = new LinkedHashSet<>(
                DashboardPresenter.run("prover", "success", "1970-01-01 00:00:00.000", 3.0).keySet());
        assertThat(read)
                .as("the activity table reads these and DashboardPresenter.run() does not send "
                        + "them. Sent: %s", sent)
                .isSubsetOf(sent);
    }

    // ---- the effort model, which IS a view model and had no guard at all ---------------------------

    /**
     * THE ONE PLACE THIS MODULE ALREADY HAS VIEW MODELS, now held to the same rule.
     *
     * <p>{@link WorkModel.Metrics} is a record whose COMPONENT NAMES are the wire names — there is no
     * map and no annotation between it and the JSON — and its javadoc states the consequence: renaming
     * a component "silently blanks a tile rather than failing anything". A blanked tile is an em dash,
     * and an em dash is exactly what an unmeasured figure legitimately looks like, so the page cannot
     * tell a renamed field from a run with no machine time yet. This is the check that argument asked
     * for and did not have.
     */
    @Test
    void everyNameThePageReadsOffTheEffortModelIsAComponentOfIt() throws Exception {
        Set<String> read = namesIn(OFF_WORK, render());

        assertThat(read)
                .as("the effort panel's names could not be found in render(); with none of them read "
                        + "this check would pass over a view model nothing else guards")
                .contains("humanHours", "machineHours", "fte", "etaSec", "humanMin");

        Set<String> sent = new LinkedHashSet<>();
        for (RecordComponent component : WorkModel.Metrics.class.getRecordComponents()) {
            sent.add(component.getName());
        }
        assertThat(read)
                .as("static/app.js reads these off the `work` object and WorkModel.Metrics has no "
                        + "such component, so Jackson sends nothing and the tile renders as the em "
                        + "dash that means \"not measured yet\". Sent: %s", sent)
                .isSubsetOf(sent);
    }

    /**
     * The itemised rates the page prints back to the reader — "triage 10m + assess 20m, plus
     * write-test 45m…".
     *
     * <p>The whole point of itemising them is that a reader can check the arithmetic against the
     * sentence, and a missing rate renders as {@code 0} through {@code (hm.triage||0)}: not a blank, a
     * NUMBER, and a wrong one, in a paragraph explaining a 9.5x efficiency claim.
     */
    @Test
    void everyRateThePagePrintsIsOneTheEffortModelCarries() throws Exception {
        Set<String> read = namesIn(OFF_RATES, render());

        assertThat(read)
                .as("the rate card could not be found in render(); the sentence that itemises the "
                        + "human-equivalent estimate changed shape")
                .contains("triage", "assess", "write_test", "verify", "write_fix", "rebut");

        assertThat(read)
                .as("the page prints these as the per-marker rates and WorkModel.HUMAN_MIN does not "
                        + "carry them; `(hm.x||0)` renders a missing one as 0 minutes, inside the "
                        + "paragraph that justifies the efficiency figure. Carried: %s",
                        WorkModel.HUMAN_MIN.keySet())
                .isSubsetOf(WorkModel.HUMAN_MIN.keySet());
    }

    // ---- the counts document, pushed on every transition -------------------------------------------

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED. {@code liveCounts()} is what moves the page between
     * snapshots — it is pushed on every single marker transition, where {@code /api/state} is polled
     * only while the socket is down. A renamed key writes {@code "undefined rows"} into the table
     * captions and {@code "no markers ingested yet"} under a full backlog, and it does it on the
     * transport that carries almost every update.
     */
    @Test
    void everyNameThePageReadsOffTheCountsDocumentIsOneTheServerSends() throws Exception {
        Set<String> read = namesIn(OFF_COUNTS, functionBody("function liveCounts(c){"));

        assertThat(read)
                .as("liveCounts() reads no field names at all, so this check would pass over the "
                        + "transport that carries every live update")
                .contains("total", "bugs", "remaining");

        Set<String> sent = new LinkedHashSet<>(DashboardPresenter.counts(
                Map.of("new", 1L), Map.of("pr_ready", 1L), 1, 1, 0, 0, 1, 1, 0, 0).keySet());
        assertThat(read)
                .as("the socket's counts payload does not carry these. Sent: %s", sent)
                .isSubsetOf(sent);
    }

    // ---- what each side says -----------------------------------------------------------------------

    /** The keys of a real {@code /api/state}, from the presenter itself rather than from a list. */
    private static Set<String> stateKeys() {
        return new LinkedHashSet<>(DashboardPresenter.state("org/repo", List.of(), List.of(),
                List.of(), WorkModel.workMetrics(List.of(), List.of(), List.of()), true).keySet());
    }

    private static Set<String> namesIn(Pattern pattern, String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = pattern.matcher(source);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /** {@code render(s)}'s body, out of the file the browser is served. */
    private static String render() throws Exception {
        return functionBody("function render(s){");
    }

    /**
     * One function's body, bounded at its own closing brace in column one — which is how every
     * function in that file is written.
     *
     * <p>Taking the whole file instead would drag in reads of unrelated locals that happen to be
     * called {@code s}, {@code a}, {@code w} or {@code c} and turn these into checks about something
     * else.
     */
    private static String functionBody(String declaration) throws Exception {
        String app = new ClassPathResource("static/app.js")
                .getContentAsString(StandardCharsets.UTF_8);
        int start = app.indexOf(declaration);
        assertThat(start)
                .as("`%s` is gone from static/app.js — the page no longer reads the dashboard's "
                        + "documents the way this check knows how to look", declaration)
                .isNotNegative();
        int end = app.indexOf("\n}", start);
        assertThat(end).isGreaterThan(start);
        return app.substring(start, end);
    }

    /**
     * AND THE FIVE CHECKS ABOVE ARE NOT READING THE SAME THING FIVE TIMES.
     *
     * <p>A subset assertion is satisfied by an empty left-hand side, and four of the five extractions
     * are single-letter prefixes over one function — the cheapest way for this class to rot is for
     * {@code render()} to be rewritten so that one of them silently stops matching while the others
     * still do. The per-test guards catch that one at a time; this states the total, so a rewrite that
     * halves the coverage cannot pass by leaving each individual core set intact.
     */
    @Test
    void theWholeReadPathIsCovered() throws Exception {
        String render = render();
        List<String> everyName = new ArrayList<>();
        everyName.addAll(namesIn(OFF_STATE, render));
        everyName.addAll(namesIn(OFF_RUN, render));
        everyName.addAll(namesIn(OFF_WORK, render));
        everyName.addAll(namesIn(OFF_RATES, render));
        everyName.addAll(namesIn(OFF_COUNTS, functionBody("function liveCounts(c){")));

        assertThat(everyName)
                .as("this class exists to hold thirty hand-written wire names to the page that reads "
                        + "them, and it is now checking %s. Something it used to parse has moved; "
                        + "find it rather than lowering the number", everyName.size())
                .hasSizeGreaterThanOrEqualTo(30);
    }
}
