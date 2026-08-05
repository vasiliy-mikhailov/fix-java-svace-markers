package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;

/**
 * THE SAMPLING TEMPERATURE OF EVERY MODEL CALL WHOSE REPLY IS BRANCHED ON.
 *
 * <p>{@code lib/Llm.java} STATED the rule before this file existed, on {@link Llm#chat}: <em>0 for the
 * skeptic (a certification should not vary run to run) and 0.2 for the two that write prose.</em> That
 * wording is quoted in the PAST TENSE deliberately — this change rewrote it, so a reader who goes
 * looking for it will find the corrected rule instead. It was written as a comment, so nothing
 * enforced it, and the classification it handed out was wrong: {@code Verdict}
 * was filed under "the two that write prose" and {@code Verdict} does not write prose — it produces
 * {@code kind}, which is copied verbatim into the {@code verdict_kind} column and picks the marker's
 * {@code SuspicionStatus}. This file is the rule with teeth.
 *
 * <p>THE MEASUREMENT THAT PROMPTED IT (2026-08-04). Twenty already-settled markers were re-proved
 * through {@code POST /api/prove/marker} against a container that was NEVER RESTARTED — byte-identical
 * code, the same image, the same prompts, the same model. Three of the twenty came back with a
 * different verdict. One of those three was an {@code unprovable} that turned into {@code infra_stuck},
 * which is an infrastructure failure rather than a disagreement, so the true churn on identical inputs
 * is 2 in 20. TREAT THAT AS A DIRECTION, NOT A RATE: at n=20 the 95% interval runs from roughly 1% to
 * 32%, so "about 10%" is the point estimate of a small sample and nothing in this repo can re-measure
 * it — it needed a live container. A verdict that moves at all on input that did not move is a sample,
 * not a measurement, and the column it lands in is read as a finding.
 *
 * <p>THE TEST OF WHETHER A CALL IS "PROSE" IS NOT WHETHER IT RETURNS ANY, it is whether anything in the
 * reply is branched on afterwards. {@code PrMaker} returns both, from one call, and that is why it is
 * here rather than exempt.
 *
 * <p>WHAT THIS FILE PINS TODAY, so the asymmetry is not mistaken for an oversight:
 *
 * <ul>
 *   <li>{@code Verdict} — 0. Changed on 2026-08-05, which is what this file was written to force; it
 *       moved 973 cases of the node-family harness catalogue, re-baselined under proof in
 *       {@code engine/harness/README.md}.</li>
 *   <li>{@code FixSkeptic} — 0. The site that already obeyed the rule; pinned so it keeps obeying it.</li>
 *   <li>{@code PrMaker} — 0.2, DELIBERATELY, and the only assertion here that is not
 *       {@link #CERTIFICATION}. Its one call writes prose and returns a branched-on {@code decision},
 *       so the constant alone cannot fix it and splitting the call is the real repair. The test pins
 *       the current value so it cannot drift while that stays unfixed.</li>
 * </ul>
 *
 * <p>Each test below drives the REAL node entry point against a recording endpoint and reads the
 * temperature out of the request the node actually built, so a constant changed in the node is caught
 * whatever the javadoc nearby says.
 */
class ACertificationDoesNotVaryRunToRunTest {

    /** The only value a reply that gets branched on may be sampled at. @see Llm#chat */
    private static final double CERTIFICATION = 0.0;

    /**
     * The value for a reply only a human reads. It survives at exactly ONE call site — {@code PrMaker},
     * whose single call writes prose AND returns a decision — and that site is a known defect pinned
     * here rather than an endorsement. See the javadoc on the PR maker test.
     */
    private static final double PROSE = 0.2;

    // ---------------------------------------------------------------------------------------------
    // the recording endpoint
    // ---------------------------------------------------------------------------------------------

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Object completion(String content) {
        return item("choices", List.of(item("message", item("content", content))));
    }

    /** Records every request and answers each one with the same well-formed reply. */
    private static final class Recorder implements Llm.Http {
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private final Object reply;

        Recorder(String content) {
            this.reply = completion(content);
        }

        @Override
        public Object request(Map<String, Object> options) {
            calls.add(options);
            return reply;
        }

        /** The temperature of the one call the stage made; it must have made exactly one. */
        double temperature(String stage) {
            assertEquals(1, calls.size(),
                    stage + " did not make exactly one call, so this test is asserting nothing");
            Object t = Json.get(calls.get(0).get("body"), "temperature");
            assertTrue(t instanceof Number, stage + " sent a non-numeric temperature: " + t);
            return ((Number) t).doubleValue();
        }
    }

    private static final Llm.Endpoint LLM = new Llm.Endpoint("http://llm/v1", "k", "m");

    // ---------------------------------------------------------------------------------------------
    // Verdict — the adjudication
    // ---------------------------------------------------------------------------------------------

    @Test
    void theAdjudicationIsSampledAtZeroBecauseVerdictKindIsACertificationAndNotProse() {
        Recorder http = new Recorder(
                "{\"kind\":\"false-positive\",\"verdict\":\"the guard on line 12 rejects it\","
                + "\"confidence\":\"high\"}");
        // A second non-reproduction at the minimum sample count: the route that writes a verdict.
        Verdict.verdict(new Verdict.Request(
                item("state", "not_reproduced", "attempts", 2L, "infra_reason", ""),
                item("suspicion_key", "k", "repo", "o/r", "file", "src/main/java/a/B.java",
                        "svace_checker", "HANDLE_LEAK", "svace_severity", "Major", "svace_line", 44L,
                        "description", "a resource is not closed", "settle_by", "test"),
                item("can_prove", true, "repro_root_cause", "rc", "test_score", 90L),
                item("fix_root_cause", "because X"),
                item("red_summary", item("test_executed", true), "red_output", ""),
                item("method_text", "void f(){}", "src", "class B {}", "anchor", "B#f():44",
                        "anchor_status", "exact", "anchor_note", "inside f()"),
                item("pr_reason", ""),
                LLM, "", "", 2, "[stage vd1]", true), http, s -> { });

        assertEquals(CERTIFICATION, http.temperature("Verdict"),
                """
                VERDICT IS THE CERTIFICATION, so it is sampled at 0 — Verdict.java:700 has just been \
                changed back to 0.2 or thereabouts.

                The rule was ALREADY WRITTEN, as a comment on Llm#chat, before this test existed: \
                "0 for the skeptic (a certification should not vary run to run) and 0.2 for the two \
                that write prose". Do not go looking for that sentence — the same change that added \
                this test rewrote it, because it was the thing that misfiled Verdict. Verdict was \
                classified as one of the two that write prose. It is not one of them. `kind` comes \
                back from THIS call, is copied verbatim into the verdict_kind column, and picks the \
                marker's SuspicionStatus: `false-positive` asserts we tested the claim and it does \
                not hold, `by-design` concedes the claim and calls the code deliberate, `unprovable` \
                says we never managed to test it. Those are three different findings about somebody \
                else's source code, and which one a marker gets is what this temperature decides.

                Measured, 2026-08-04: 20 settled markers re-proved through POST /api/prove/marker \
                against a container that was NEVER RESTARTED — byte-identical code, same image, same \
                prompts, same model — returned 3 different verdicts. One was an infra failure \
                (unprovable -> infra_stuck), so the churn on identical input is 2 of 20 — a small
                sample whose interval is wide, and a direction rather than a rate.

                If you are raising this number again, the thing to argue with is not the number: say \
                which of those three certifications you are willing to have flip on a re-run of an \
                input that did not change.""");
    }

    // ---------------------------------------------------------------------------------------------
    // Fix skeptic — the site that already obeys the rule
    // ---------------------------------------------------------------------------------------------

    @Test
    void theSkepticIsSampledAtZeroBecauseItsVerdictIsTheGateInFrontOfEveryPullRequest() {
        Recorder http = new Recorder("{\"verdict\":\"sound\",\"reason\":\"a general correction\"}");
        FixSkeptic.fixSkeptic(new FixSkeptic.Request(
                item("title", "t", "description", "d"),
                item("test_code", "class T {}"),
                item("can_fix", true, "fix_edits_json", "[]"),
                item("proven", true),
                LLM, "[stage sk5]"), http);

        assertEquals(CERTIFICATION, http.temperature("Fix skeptic"),
                """
                FixSkeptic.java:276 is the one site that already obeyed the rule, and this pins it so \
                it keeps obeying it. `skeptic_verdict` comes back from this call and is the gate \
                PrMaker asks before a pull request is even considered (SkepticVerdict#certifies), and \
                RecordOutcome routes off it as well. A `sound` that varies run to run is a pull \
                request against a stranger's repository that varies run to run.""");
    }

    // ---------------------------------------------------------------------------------------------
    // PR maker — prose AND a decision, out of one call: THE ONE SITE STILL SAMPLED FOR PROSE
    // ---------------------------------------------------------------------------------------------

    /**
     * THIS TEST PINS A KNOWN DEFECT AT ITS CURRENT VALUE. It is the only one in this file that does
     * not assert {@link #CERTIFICATION}, and the asymmetry is deliberate — read this before "fixing"
     * it in either direction.
     *
     * <p>PR maker really does write prose — {@code pr_title} and {@code pr_body} — and 0.2 was chosen
     * for that half. But the SAME reply carries {@code decision}, and a decision is not prose.
     * {@code parsePrReply} copies it into {@code pr_decision}; {@code RecordOutcome} routes the marker
     * to {@code PR_READY} or {@code PR_REJECTED} off it (RecordOutcome.java:615 and :618);
     * {@code Critiques} files a {@code reject} as a {@code PR_REJECTED} complaint that feeds back into
     * the prompts (Critiques.java:264). {@code make} versus {@code reject} is whether a pull request is
     * opened against somebody else's repository, and that should not be a coin the model flips again
     * on the next run.
     *
     * <p>SO WHY IS IT STILL 0.2? Because unlike {@code Verdict}, this one CANNOT be repaired by moving
     * the constant. One call is doing two jobs and only one of the two is allowed to vary; dropping it
     * to 0 would fix the decision by freezing the prose, and raising it back would be worse. THE REAL
     * REPAIR IS TO SPLIT the curation decision out of the prose-writing call, which is a change to the
     * stage's shape and not to a number, and it is not in scope here.
     *
     * <p>Until it is split, this test pins 0.2 so the value cannot drift silently: anyone who changes
     * it in either direction lands in this file and has to say which half they are optimising for.
     */
    @Test
    void theCuratorIsStillSampledForProseEvenThoughTheSameReplyAlsoReturnsPrDecision() {
        Recorder http = new Recorder(
                "{\"decision\":\"make\",\"reason\":\"production code\",\"pr_title\":\"Escape it\","
                + "\"pr_body\":\"body\"}");
        PrMaker.prMaker(new PrMaker.Request(
                item("repo", "o/r", "file", "src/main/java/a/S.java", "title", "SQLi in sort",
                        "description", "column concatenated"),
                item("test_code", "class T {}"),
                item("fix_root_cause", "unvalidated column", "fix_edits_json", "[]"),
                item("red_reproduced", true),
                item("proven", true, "skeptic_verdict", "sound"),
                LLM, "[stage pr3]"), http);

        assertEquals(PROSE, http.temperature("PR maker"),
                """
                PR maker is the ONE call still sampled for prose, and this is the one assertion in \
                this file that pins 0.2 rather than 0. That is on purpose — see the javadoc above.

                It is NOT an endorsement. The same reply carries `decision`, which RecordOutcome \
                routes PR_READY/PR_REJECTED off and Critiques feeds back into the prompts, so half \
                of this call has exactly the defect Verdict had. The difference is that Verdict's \
                was fixable by moving the constant and this one is not: one call is doing two jobs, \
                and dropping it to 0 would freeze the prose to fix the decision. THE REPAIR IS TO \
                SPLIT THE CALL, and that is a change to the stage's shape, not to this number.

                If you are here because you changed the constant: say which half you are optimising \
                for, and prefer splitting the call over moving this line.""");
    }

    // ---------------------------------------------------------------------------------------------
    // the census — a fourth call site cannot appear without coming through here
    // ---------------------------------------------------------------------------------------------

    @Test
    void everyModelCallInTheNodesPackageIsOneOfTheThreePinnedAbove() {
        // The three tests above pin the call sites that EXIST. This one pins the list itself, so a
        // fourth stage that starts asking a model has to be classified here — decides, or writes prose
        // — instead of quietly picking a number. The rule was a javadoc for months precisely because
        // nothing ever forced anyone to read it.
        Path nodes = Path.of("src/main/java/tech/mikhailov/fsm/nodes");
        assertTrue(Files.isDirectory(nodes),
                "the nodes sources are not where this test looks (" + nodes.toAbsolutePath()
                + "), so the census is asserting nothing — fix the path, do not delete the test");

        Map<String, Integer> found = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(nodes)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                long n = Files.readAllLines(f).stream().filter(l -> l.contains("Llm.chat(")).count();
                if (n > 0) {
                    found.put(f.getFileName().toString(), (int) n);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("could not read the nodes sources", e);
        }

        assertEquals(new TreeSet<>(Map.of("FixSkeptic.java", 1, "PrMaker.java", 1, "Verdict.java", 1)
                        .entrySet().stream().map(e -> e.getKey() + " x" + e.getValue()).toList()),
                new TreeSet<>(found.entrySet().stream().map(e -> e.getKey() + " x" + e.getValue())
                        .toList()),
                """
                The set of stages that ask a model has changed. Every one of them has to declare, in \
                this file, whether its reply is BRANCHED ON — in which case it is sampled at 0, \
                because a certification that varies run to run is not a certification — or whether it \
                is only read by a human, which is the only thing 0.2 is for. See the @param \
                temperature javadoc on Llm#chat.

                SCOPE, SO THIS IS NOT MISREAD AS WIDER THAN IT IS: this census walks the engine's \
                nodes package ONLY, and it counts LINES containing `Llm.chat(`, not calls. \
                HttpLlmClient in the orchestrator also calls Llm.chat, and is deliberately not \
                covered: it TAKES a temperature as a parameter rather than choosing one, so it is \
                transport and has no classification to make. A stage that chooses a temperature \
                outside this package is NOT caught here.""");
    }
}
