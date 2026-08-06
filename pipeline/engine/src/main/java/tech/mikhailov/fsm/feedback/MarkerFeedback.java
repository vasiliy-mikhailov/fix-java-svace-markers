package tech.mikhailov.fsm.feedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.trial.Step;
import tech.mikhailov.fsm.trial.Trial;

/**
 * ONE MARKER'S WHOLE PROVE, ON DISK — and NOTHING BUT A PROJECTION OF {@link Trial}.
 *
 * <h2>ONE COMPONENT, AND THAT IS THE DESIGN</h2>
 *
 * <p>The Trial is the accumulation; this class is the SERIALISER, and the distinction is not cosmetic.
 * A projection can only write what the entity carries, so a fact a training pass needs and the entity
 * lacks fails to COMPILE here rather than being quietly re-derived out of an untyped bag.
 *
 * <p>DO NOT GIVE THIS RECORD A SECOND COMPONENT BESIDE THE TRIAL. The moment one exists the archive
 * knows something the entity does not, and the entity is what the third use case reads: a label
 * without the prompt that earned it optimises nothing, and a prompt that lives only in the file is a
 * prompt the entity cannot be asked for.
 *
 * <h2>THE SHAPE, AND WHY IT IS ALLOWED TO HAVE MOVED</h2>
 *
 * <p>{@link #SCHEMA} IS BUMPED, and it is {@link Trial#SCHEMA} rather than a number of its own: the
 * entity decides the shape and the file follows it, so one identifier for both is one fewer thing to
 * keep in step. The store is append-only ACROSS DEPLOYMENTS, so lines written under
 * {@code fsm-feedback/1} and lines written under {@code fsm-trial/2} will sit in the same file, and a
 * reader must be able to tell them apart BY LOOKING. {@code CritiqueIndex} does exactly that — it names
 * both and refuses a third loudly — and {@code CritiqueIndexReadsBothSchemasTest} pins it.
 *
 * <p>WHAT A CONVERSION OF THE OLD LINES WOULD NEED is written down beside the reader that would have to
 * do it; see {@code CritiqueIndex.KNOWN_SCHEMAS}. The short of it: everything the dashboard reads
 * ({@code dedup_key}, {@code written_at}, {@code marker}, {@code feedback}) is unmoved and needs no
 * conversion at all, and the two sections that DID move are {@code stages.*} (each now also carries the
 * per-marker {@code input}, which an old line simply does not have) and {@code execution.red|green}
 * (now the runner reply as {@code Execution} parses it, rather than whatever keys that reply happened
 * to carry). Neither is recoverable from an old line, because neither was ever written.
 *
 * <h2>SEVEN SECTIONS, in the order somebody reads them</h2>
 * <ul>
 *   <li>{@code marker} — identity and every input the chain was given, plus the resolved anchor;</li>
 *   <li>{@code code_in} — the source the stages actually saw;</li>
 *   <li>{@code stages} — the five model calls: prompt, reply, the marker's own input, parsed result;</li>
 *   <li>{@code code_out} — the test, the diff per file, the PR title and body;</li>
 *   <li>{@code execution} — both builds and the flags they produced;</li>
 *   <li>{@code judgement} — realness, skeptic, curator, state, verdict, and whether it settled;</li>
 *   <li>{@code feedback} — the critiques, each attributed and each with a countable kind.</li>
 * </ul>
 *
 * <p>NOTHING IS DUPLICATED BETWEEN THEM. The test source is in {@code code_out} and not also in the
 * reproducer's parsed result; the diff is in {@code code_out} and not also in the fixer's. Each is
 * kilobytes and this file is appended to once per marker for as many runs as it takes.
 *
 * <p>WHAT IS BOUNDED, AND IT IS ONLY EVER THE INCIDENTAL: the build logs, at {@link #BUILD_LOG_TAIL}
 * characters, kept from the END where javac and surefire put the line that matters. Not a prompt, not a
 * reply, not the source — bounding any of those would delete the reason the file exists.
 *
 * @param trial the prove, whole. {@code writtenAt} is {@link Trial#startedAt()} and the clock is the
 *              caller's, which is what lets a test assert the record field for field.
 */
public record MarkerFeedback(Trial trial) {

    /** The store's format, in the record as well as in the file header: a reader may see either. */
    public static final String SCHEMA = Trial.SCHEMA;

    /**
     * THE OTHER SHAPE THE STORE'S FILE CONTAINS.
     *
     * <p>The file is append-only ACROSS DEPLOYMENTS, so one file holds records under this identifier
     * and records under {@link #SCHEMA}, in that order. A reader has to be able to NAME which one it is
     * holding rather than infer it from which keys are missing, which is guessing. A CONSTANT and not a
     * comment because {@code CritiqueIndex} branches on it — see the note there for what converting the
     * records under this identifier would cost.
     */
    public static final String LEGACY_SCHEMA = "fsm-feedback/1";

    /**
     * The only bound in the record, and it is on build logs alone.
     *
     * <p>Generous on purpose — this is an archive, not a prompt — but not unbounded: a Maven reactor
     * over a large repository with a cold cache prints megabytes of download lines per build, TWICE per
     * marker, and none of it is evidence about a model.
     */
    public static final int BUILD_LOG_TAIL = 8_000;

    /**
     * The log fields the bound applies to, in BOTH replies.
     *
     * <p>{@code red_output} appears in the fix run too: that run builds RED itself before applying the
     * edits, so its reply carries the same unbounded Maven log under a key the green run also has.
     * Bounding by key rather than by which call it came from is what stops one of the four slipping
     * through.
     */
    private static final List<String> BUILD_LOGS = List.of("red_output", "green_output");

    /** The whole record, in one insertion-ordered map ready for {@code Json.stringify}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("written_at", trial.startedAt());
        m.put("dedup_key", trial.dedupKey());
        m.put("marker", marker());
        m.put("code_in", codeIn());
        m.put("stages", stages());
        m.put("code_out", codeOut());
        m.put("execution", execution());
        m.put("judgement", judgement());
        m.put("feedback", critiques());
        return m;
    }

    /** Identity and every input — the whole {@code Prep prover} row, then what re-anchoring learned. */
    private Map<String, Object> marker() {
        Map<String, Object> m = trial.marker().toMap();
        // Only available AFTER the fetch, and worth as much as the rest put together: a verdict about
        // a marker whose location could not be trusted is a verdict about the wrong lines.
        m.put("anchor", trial.source().anchor());
        m.put("anchor_status", trial.source().anchorStatus());
        m.put("anchor_note", trial.source().anchorNote());
        return m;
    }

    /** The source the stages actually saw, and the context assembled with it. */
    private Map<String, Object> codeIn() {
        Trial.Source source = trial.source();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source.src());
        m.put("source_chars", (double) source.src().length());
        // The engine's own 300 000-character cut, reported rather than re-applied: a verdict on a file
        // the model only half saw is not trustworthy, and the record has to say which files those were.
        m.put("source_truncated", source.truncated());
        // NULL, not "", is what the entity carries for a line there was nothing to quote — and it is
        // rendered here rather than kept, because a reader of the archive wants text. @see Trial.Source
        m.put("line_text", Values.text(source.lineText()));
        m.put("method_text", source.methodText());
        return m;
    }

    /** The five calls, in the order the chain makes them, all five with the same shape. */
    private Map<String, Object> stages() {
        Trial.Proof proof = trial.proof().conclusion();
        Trial.Repair repair = trial.repair().conclusion();
        Trial.Certification certification = trial.certification().conclusion();
        Trial.Publication publication = trial.publication().conclusion();
        Trial.Argument argument = trial.argument().conclusion();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reproducer", stage(trial.proof(), parsed(
                "can_prove", proof.canProve(),
                "parse_failed", proof.parseFailed(),
                "test_sound", proof.sound(),
                "test_score", (double) proof.score(),
                "test_realness", proof.realness(),
                "test_mocks_subject", proof.mocksSubject(),
                "repro_value_verdict", proof.valueVerdict(),
                "repro_root_cause", proof.rootCause())));
        m.put("fixer", stage(trial.repair(), parsed(
                "can_fix", repair.canFix(),
                "fix_parse_failed", repair.parseFailed(),
                "fix_rejected", repair.rejected(),
                "fix_root_cause", repair.rootCause(),
                "pr_title", repair.prTitle(),
                "pr_body", repair.prBody())));
        m.put("fix_skeptic", stage(trial.certification(), parsed(
                "skeptic_verdict", certification.verdict(),
                "skeptic_reason", certification.reason(),
                "skeptic_answered", certification.answered())));
        m.put("pr_maker", stage(trial.publication(), parsed(
                "pr_decision", publication.decision(),
                "pr_reason", publication.reason(),
                "pr_curated", publication.curated(),
                "pr_title", publication.title(),
                "pr_body", publication.body())));
        // TWO FIELDS NO OTHER STAGE HAS, and they are the absence discipline made writable. A verdict
        // with no text has three causes that leave the identical row — the model argued nothing, the
        // call never came back, the stage was switched off — and a training pass must only ever learn
        // from the first. @see Trial.Argument
        m.put("verdict", stage(trial.argument(), parsed(
                "verdict_kind", argument.kind(),
                "verdict_text", argument.text(),
                "verdict_confidence", argument.confidence(),
                "verdict_call_failure", argument.callFailure(),
                "verdict_skipped", argument.skipped())));
        return m;
    }

    /**
     * ONE STEP'S OBJECT IN THE STORE — the prompt that went out, the reply that came back, the marker's
     * own contribution to that prompt, and what the stage read out of the answer.
     *
     * <p>{@code input} IS KEPT APART FROM {@code prompt} DELIBERATELY, and it is the one field this
     * shape gained. The prompt is template plus input; a training pass optimises the TEMPLATE. Held in
     * one string, every example teaches about one marker's substituted source as though it were part of
     * the wording under test. It is NULL — not "" — for the three judging stages, whose prompt is one
     * {@code formatted(...)} call with no separable per-marker part. @see Step#input()
     */
    private static Map<String, Object> stage(Step<?> step, Map<String, Object> parsed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("called", step.ask().called());
        m.put("prompt", step.ask().prompt());
        m.put("reply", step.ask().reply());
        m.put("input", step.input());
        m.put("parsed", parsed);
        return m;
    }

    /** The artifacts: the test, the diff, and the pull request that was drafted from them. */
    private Map<String, Object> codeOut() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("test_path", trial.marker().testPath());
        m.put("test_code", trial.proof().conclusion().testCode());
        // Parsed back to edits-per-file so old_str/new_str are addressable without a second JSON parse
        // in whatever reads this — and the string it came from is kept beside it, because that exact
        // text is what `bugs.fix_diff` holds and a reader may be comparing the two.
        String editsJson = trial.repair().conclusion().editsJson();
        Object edits = editsJson.isEmpty() ? List.of() : Json.parseOrNull(editsJson);
        m.put("fix_edits", edits instanceof List<?> ? edits : List.of());
        m.put("fix_edits_json", editsJson);
        m.put("fix_rejected", trial.repair().conclusion().rejected());
        // Off the ROUTING, which is what DECIDES them — including the one ⚠ banner it prefixes a
        // held-back draft with. Not off the curator's own row: reading the deciding stage is what keeps
        // the archive agreeing with the artifact if a later stage ever starts editing the body.
        m.put("pr_title", trial.routing().prTitle());
        m.put("pr_body", trial.routing().prBody());
        return m;
    }

    /** Both builds, whole, with only their logs bounded. */
    private Map<String, Object> execution() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("red", bounded(trial.red().toMap()));
        m.put("green", bounded(trial.green().toMap()));
        m.put("jdk", trial.routing().jdk());
        // The three flags off the stage that DECIDED them rather than re-derived here: a record that
        // recomputed them could disagree with the artifact it is supposed to explain.
        m.put("red_verified", trial.routing().redVerified());
        m.put("green_verified", trial.routing().greenVerified());
        m.put("proven", trial.green().proven());
        return m;
    }

    /** Everything that was concluded, and by whom. */
    private Map<String, Object> judgement() {
        Trial.Proof proof = trial.proof().conclusion();
        Trial.Certification certification = trial.certification().conclusion();
        Trial.Publication publication = trial.publication().conclusion();
        Trial.Argument argument = trial.argument().conclusion();
        Trial.Settlement settlement = trial.settlement();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("realness_score", (double) proof.score());
        m.put("realness_reasons", reasons());
        m.put("test_sound", proof.sound());
        m.put("test_mocks_subject", proof.mocksSubject());
        m.put("skeptic_verdict", certification.verdict());
        m.put("skeptic_reason", certification.reason());
        m.put("skeptic_answered", certification.answered());
        m.put("pr_decision", publication.decision());
        m.put("pr_reason", publication.reason());
        m.put("pr_curated", publication.curated());
        // BOTH STATES, and the Trial is the reason both survive. A written argument REPLACES the
        // routing state with its own conclusion — not_reproduced becomes false_positive — so the two
        // answer different questions: how the chain ROUTED, and what was CONCLUDED. A record with only
        // the second cannot be lined up against the run at all. @see Trial.Settlement#state()
        m.put("state", settlement.state());
        m.put("recorded_state", trial.routing().state().wire());
        m.put("verdict_kind", argument.kind());
        m.put("verdict_text", argument.text());
        m.put("verdict_confidence", argument.confidence());
        // The audit trail of the whole prove, verbatim: this is where the INFRA failures live, and
        // keeping them out of `feedback` is only honest if they are kept somewhere. The SETTLEMENT's
        // copy, because the verdict stage appends its own failed call to what the routing wrote — so
        // this one is the superset.
        m.put("infra_reason", settlement.infraReason());
        m.put("attempts", (double) trial.routing().attempts());
        m.put("suspicion_status", settlement.status());
        m.put("suspicion_note", settlement.note());
        // `new` is what an infra error below the ceiling and a pending retry both write. Counting
        // either as settled would treat a marker the pipeline has not finished with as evidence.
        // Asked of the enum that owns the column's vocabulary, not of a literal: the queue token and
        // this comparison have to agree, and there is now one place they are spelled.
        m.put("settled", SuspicionStatus.of(settlement.status()) != SuspicionStatus.NEW);
        m.put("versions", trial.versions());
        return m;
    }

    /** The realness reasons as the scorer produced them, rather than as one run-on sentence. */
    private List<Object> reasons() {
        String joined = trial.proof().conclusion().realness();
        if (joined.isEmpty()) {
            return List.of();
        }
        return List.of((Object[]) joined.split(Critiques.REASON_SEPARATOR));
    }

    private List<Object> critiques() {
        List<Object> out = new ArrayList<>();
        for (Critique c : Critiques.harvest(trial)) {
            out.add(c.toMap());
        }
        return out;
    }

    /** A serialised run with its build logs cut to their tail, and nothing else touched. */
    private static Map<String, Object> bounded(Map<String, Object> run) {
        for (String key : BUILD_LOGS) {
            if (run.get(key) instanceof String log && log.length() > BUILD_LOG_TAIL) {
                run.put(key, log.substring(log.length() - BUILD_LOG_TAIL));
            }
        }
        return run;
    }

    /** An insertion-ordered map from alternating key/value pairs. */
    private static Map<String, Object> parsed(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }
}
