package tech.mikhailov.fsm.nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.JsonExtract;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.lib.MockNecessity;
import tech.mikhailov.fsm.lib.TestRealness;
import tech.mikhailov.fsm.lib.Values;

/**
 * {@code Reproducer critic} — the second opinion on the MOCKS in a test that already drives the real
 * subject.
 *
 * <p>THE MEASUREMENT THAT ASKED FOR IT. Of 544 recorded proves in {@code /data/feedback}, 140 carry an
 * {@code excessive_mocking} complaint; the stub count behind them runs min 1, median 7, p90 11, max 19.
 * {@link TestRealness} awards its {@code +10} only at zero stubs, so a single mocked {@code Connection}
 * costs exactly what nine mocked value objects cost, and part of that 140 is the scorer being strict
 * rather than the test being bad. WHICH PART is the whole question, and no counter can answer it: nine
 * stubs in a class with nine I/O collaborators is a correct test, and nine in a class with none is a
 * habit. Judging NECESSITY needs something that has read the subject's source, so this stage is a model
 * call and not another regular expression.
 *
 * <p>TWO LAYERS, because a model call per prove is not free.
 * <ol>
 *   <li>{@link #gate} is the CHEAP GATE, and it is {@link TestRealness}'s answer re-read rather than
 *       recomputed. A test the free scorer is already happy with is accepted with NO model call at all,
 *       and {@link Gate#reason()} says which clause skipped it — the critic has to be invisible when
 *       the proof is already good, and provably so.</li>
 *   <li>The call runs only when the gate is unhappy. It reads the TEST and the SUBJECT'S SOURCE and
 *       answers, per mock, whether the real collaborator could have been used instead.</li>
 * </ol>
 *
 * <p>THREE PARTS, exactly as {@link FixSkeptic}, because only the middle one needs a model:
 * <ul>
 *   <li>{@link #criticPrompt} — the node's inputs to the exact text the model is sent (pure)</li>
 *   <li>{@link #parseCriticReply} — a reply to a verdict plus per-mock findings (pure)</li>
 *   <li>{@link #reproducerCritic} — the gate, the call, the catch, the merge (the shell)</li>
 * </ul>
 *
 * <p>IT FAILS CLOSED, AND CLOSED HERE MEANS ACCEPT — THE OPPOSITE OF {@link FixSkeptic}'S DIRECTION.
 * This is the single thing to get right in this file. An unreachable fix skeptic yields {@code unknown}
 * and no pull request, because silence must not approve a patch against a stranger's repository. An
 * unreachable reproducer critic must ACCEPT THE TEST AS IT IS: the writer gets no new information from
 * a retry that carries no findings, so it produces the same test, and a prove that fails over it throws
 * away a proof that may have been perfectly good. Every default in this class therefore lands on
 * {@link MockNecessity#accepts()}, and only a model that said {@code reducible} on purpose — with at
 * least one finding a retry can quote — withholds acceptance.
 *
 * <p>…AND THE THREE ARE STILL DISTINGUISHABLE, which is the other half of failing closed in either
 * direction. {@link MockNecessity#NOT_RUN} is the gate declining to spend a call,
 * {@link MockNecessity#UNAUDITED} is a call that produced nothing usable, and {@code critic_answered}
 * beside the verdict is TRUE only where the model's own reply was read back. Without that receipt, a run
 * in which the endpoint was never reachable and a run in which the critic approved all 140 tests are the
 * same rows.
 *
 * <p>WHEN IT IS ASKED, AND WHEN IT IS NOT. {@code ProveChain} consults this stage only after the free
 * scorer has already complained about a test's mocking; an UNSOUND test is retried with no model call
 * at all, because {@code TestRealness} has already decided that for nothing. It is also reachable
 * through {@code POST /node/reproducer-critic} on the engine's debugging service, which makes it
 * exercisable against a real marker's test and source
 * before anything spends an attempt budget on it. Wiring it is four lines beside the skeptic's, with a
 * {@code JudgingCall.REPRODUCER_CRITIC} transport and a {@code PromptRecorder}; the LOOP — re-asking
 * the reproducer with these findings quoted — is the part that still has to be designed.
 */
public final class ReproducerCritic {

    private ReproducerCritic() {
    }

    /**
     * How much of the TEST the model is shown. Twenty thousand characters is several hundred lines of
     * JUnit; a test longer than that has a problem this stage is not about.
     */
    static final int TEST_CUT = 20_000;

    /**
     * How much of the SUBJECT'S SOURCE the model is shown, and why it is three times the test's.
     *
     * <p>Necessity is decided by the collaborator's TYPE, and the declaration that settles it can be
     * anywhere in the file — a field at the top, a constructor parameter in the middle, an inner class
     * at the bottom. {@link FixSkeptic#SKEPTIC_CUT}'s 20 000 would routinely cut away the very
     * declaration the answer turns on, and a critic that judges from half a class complains about mocks
     * whose real forms it never saw. 60 000 characters covers the whole of all but the largest files in
     * the warm repositories ({@code BuildReproduceInput.SRC_MAX} caps the fetch itself at 300 000), and
     * what is cut is announced rather than dropped in silence.
     */
    static final int SOURCE_CUT = 60_000;

    /** How much of a failed call's message reaches the row. The row is not a log file. */
    private static final int REASON_CUT = 150;

    /**
     * What the extractor anchors on, in the order a well-formed reply writes them.
     *
     * <p>{@link JsonExtract} rather than {@link FixSkeptic}'s {@code indexOf('{')..lastIndexOf('}')}:
     * this reply carries an ARRAY OF OBJECTS, so the naive slice has many more braces to be wrong about,
     * and the corpus already records 32 {@code reply_unparseable} events against the simpler shapes.
     */
    private static final List<String> KEYS = List.of("verdict", "reason", "findings");

    /**
     * The exact text the critic sends, as a Java 25 text block.
     *
     * <p>THE PROMPT IS THE PRODUCT OF THIS STAGE. Everything else here is plumbing; this is the part
     * that decides whether the answer is worth having, and it is written to do four things at once:
     * <ul>
     *   <li>give the model BOTH the test and the subject's source, because necessity cannot be judged
     *       from either alone — the test says what was mocked, only the source says what it was;</li>
     *   <li>ask PER MOCK, with the two lists — what legitimately cannot be stood up, and what plainly
     *       can — spelled out concretely rather than left to the model's taste;</li>
     *   <li>say IN AS MANY WORDS that "all of these are necessary, keep this test" is a correct and
     *       expected answer. A critic that believes it is being paid to find fault will find some, and
     *       every one it invents is an attempt from a finite budget spent rewriting a good test;</li>
     *   <li>forbid the complaint a retry cannot act on: a mock may not be called avoidable unless the
     *       reply NAMES the concrete thing to use instead. "Use fewer mocks" produces the same test
     *       again, and this repository has 140 examples of what the writer does unprompted.</li>
     * </ul>
     *
     * <p>SIX POSITIONAL {@code %s}: stamp, class under test, checker, claim, test, source. See
     * {@link #criticPrompt}, which is what defines the order, and {@code PromptSource.Stage} which
     * refuses to start on a file whose count disagrees.
     *
     * <p>PUBLIC and named {@code DEFAULT_} for the same reason {@link FixSkeptic#DEFAULT_PROMPT} is:
     * {@code prompts/proof-critic.txt} wins over it, {@code DEFAULT_PROOF_CRITIC_PROMPT} in
     * the environment comes between them, and this node still decides NOTHING about where the text came
     * from. The {@code \s\} at a line end is a space that survives incidental-whitespace stripping; the
     * trailing {@code \} joins the line to the next without a newline.
     */
    public static final String DEFAULT_PROMPT = """
            %s
            You are reviewing ONE JUnit 5 test that another model wrote to settle a static-analysis\s\
            marker against %s. That the test drives the real class has ALREADY been checked and is not\s\
            your question. Yours is narrower: FOR EACH MOCK OR STUB THE TEST SETS UP, could the real\s\
            collaborator have been used instead?

            THE CLAIM THE TEST SETTLES: %s
            %s

            NECESSARY — a collaborator that cannot be stood up inside a unit test, or one the claim is\s\
            ABOUT. A database, a Connection, a DataSource, a Statement or a ResultSet; a network or HTTP\s\
            client; a filesystem, a socket, a process, a thread pool, a clock, a source of randomness; a\s\
            servlet request, a framework or container context. A mock is necessary whenever the proof IS\s\
            the interaction: a test showing that the real subject CLOSES a resource has to mock the\s\
            resource in order to watch it happen.

            AVOIDABLE — a collaborator that is cheap, deterministic and constructible in a line or two:\s\
            a value object, a record, a DTO, an entity, an enum, a builder, a String, a collection, a\s\
            pure function, or an interface that already has a real implementation in the source below\s\
            doing no I/O. Stubbing one of those hides real behaviour behind the writer's own\s\
            assumptions, and the assertion then measures the stub instead of the subject.

            JUDGE EACH MOCK AGAINST THE SOURCE, NEVER AGAINST A COUNT. Nine mocks in a class with nine\s\
            I/O collaborators is a correct test; two in a class with none is not. Do not call a mock\s\
            avoidable unless you can name the concrete real thing to use in its place AND that thing is\s\
            visible in the source below. A complaint that cannot name a replacement cannot be acted on,\s\
            and asking for "fewer mocks" produces the same test again.

            "ALL OF THESE ARE NECESSARY — KEEP THIS TEST" IS A CORRECT AND EXPECTED ANSWER, and it is\s\
            not a failure to find fault. This test was written by a model that had read the same source\s\
            you are about to read, and most of the time it mocked what it had to. A test rewritten for\s\
            the sake of a lower count is a WORSE test. Answer `necessary` whenever every mock earns\s\
            its place against the source.

            BUT WHEN YOU ARE GENUINELY UNSURE, ASK FOR THE REWRITE. The two mistakes do not cost the\s\
            same. If you wrongly say `reducible`, one model writes one more test — seconds, and the\s\
            better of the two is kept. If you wrongly say `necessary`, a PERSON reads this test later\s\
            and decides whether to trust a proof built on stubs, which is twenty to forty-five minutes\s\
            of the only resource here that cannot be parallelised. A mock you cannot justify from the\s\
            source is not a mock to wave through: uncertain means `reducible`, and only what you can\s\
            defend means `necessary`.

            THE TEST:
            ```java
            %s
            ```

            THE SUBJECT'S SOURCE:
            ```java
            %s
            ```

            Reply ONLY JSON, no prose:
            {"verdict":"necessary|reducible","reason":"one sentence","findings":[{"mock":"the type or\s\
            field as the test spells it","necessary":true|false,"why":"one sentence","instead":"the\s\
            concrete real collaborator to use in its place, or empty when it is necessary"}]}
            List ONE finding per mock or stub the test sets up, INCLUDING every one you judge\s\
            necessary — the ones you keep are the evidence that you looked at them. Answer `reducible`\s\
            only if at least one finding says `"necessary":false` and names its replacement; otherwise\s\
            answer `necessary`. If the test or the source reaches you truncated or unreadable, answer\s\
            `necessary`: a test you could not read whole is not a test you may ask anyone to rewrite.\
            """;

    /**
     * What the prompt is built from. Loosely typed for the reason {@link FixSkeptic.PromptInput} is:
     * these values come off untyped upstream items and every one is read through a coercion.
     *
     * @param stamp     the pipeline+stage version, concatenated through {@link Llm#orMissing}
     * @param className the SIMPLE name of the class under test — the same string {@link TestRealness}
     *                  was given, so the critic and the scorer are talking about one class
     * @param src       the subject's whole source file, as {@code Build reproduce input} decoded it
     */
    public record PromptInput(String stamp, Object className, Object checker, Object description,
                              Object testCode, Object src) {
    }

    /**
     * ONE MOCK, JUDGED — and the unit a retry quotes.
     *
     * <p>Structured rather than a sentence, because the loop that does not exist yet has to be able to
     * say WHICH mock and WHAT to use instead. That is the difference between a retry that changes the
     * test and a retry that produces the same one: "use fewer mocks" is what the reproducer is already
     * being told implicitly, 140 times.
     *
     * @param mock      the type or field as the TEST spells it, so the writer can find it
     * @param necessary true when this mock earns its place — the common case, and kept in the list on
     *                  purpose: the mocks the critic accepted are the evidence that it looked at them
     * @param instead   the concrete replacement, and EMPTY whenever {@code necessary} is true
     */
    public record Finding(String mock, boolean necessary, String why, String instead) {

        /** The row's shape. Key order is fixed because it is read by a human in a transcript. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mock", mock);
            m.put("necessary", necessary);
            m.put("why", why);
            m.put("instead", instead);
            return m;
        }

        /** Whether this finding is one a retry could act on: avoidable, named, with a replacement. */
        boolean actionable() {
            return !necessary && !mock.isEmpty() && !instead.isEmpty();
        }
    }

    /**
     * The triple the parser produces and the row records.
     *
     * @param verdict the WIRE SPELLING of a {@link MockNecessity}, a String rather than the enum
     *                because it is the value of the {@code critic_verdict} column. The vocabulary is
     *                typed where it is DECIDED — inside {@link #parseCriticReply} — and flattened here
     *                at the edge, which is the rule the rest of this package follows.
     */
    public record Reply(String verdict, String reason, List<Finding> findings) {
    }

    /**
     * Whether the model is worth calling at all, and — when it is not — WHICH clause said so.
     *
     * @param reason lands verbatim in {@code critic_reason} on a skipped prove, so an operator reading
     *               a row can tell "the scorer was already happy" from "there was no test"
     */
    public record Gate(boolean call, String reason) {
    }

    /**
     * The upstream items the shell reads, plus everything it takes off the environment.
     *
     * @param item             the {@code Parse test} row the node runs on; the whole of it flows through
     * @param buildReproduceIn {@code Build reproduce input}, for {@code src} — the subject's source. It
     *                         is a separate item and not folded into {@code item} because
     *                         {@code Parse test} does not carry the source, and refetching it here
     *                         would put a network call inside a stage whose contract is that the same
     *                         request produces the same bytes
     */
    public record Request(Object prepProver, Object buildReproduceIn, Object item, Llm.Endpoint llm,
                          String criticStamp, String promptTemplate) {

        /**
         * The request with the text this class ships with, for every caller that has no opinion about
         * the template. Same delegation, and the same reason, as {@link FixSkeptic.Request}'s.
         */
        public Request(Object prepProver, Object buildReproduceIn, Object item, Llm.Endpoint llm,
                       String criticStamp) {
            this(prepProver, buildReproduceIn, item, llm, criticStamp, DEFAULT_PROMPT);
        }

        /** Read the request out of a posted body. The keys are the stage names, snake-cased. */
        public static Request of(Object body) {
            return new Request(Json.get(body, "prep_prover"),
                    Json.get(body, "build_reproduce_input"), Json.get(body, "item"),
                    Llm.Endpoint.of(Json.get(body, "env")), Json.str(body, "critic_stamp"));
        }
    }

    /**
     * THE CHEAP GATE: is there a mocking question here at all?
     *
     * <p>It re-reads {@link TestRealness}'s answer off the {@code Parse test} row rather than running
     * the scorer again. One computation, one answer: a second run here could disagree with the one that
     * wrote {@code test_score} and the log line an operator is reading, and two realness verdicts for
     * one test is worse than none.
     *
     * <p>THE THREE CLAUSES, AND WHY EACH SKIPS RATHER THAN CALLS:
     * <ul>
     *   <li>NO TEST. {@code can_prove} false, or an empty {@code test_code}: there are no mocks to
     *       judge, and a marker the reproducer declined is a result, not a test to improve.</li>
     *   <li>NOT SOUND. The scorer has already rejected it for FREE and for a worse reason — the subject
     *       is itself mocked, or is never touched. Those have their own complaints
     *       ({@code mocks_subject_under_test}, {@code never_exercises_subject}) and their own specific
     *       retry text; spending a mocking call on one buys an opinion nobody will read.</li>
     *   <li>NO STUBS. {@link TestRealness#STUB_MOCK_REASON} absent from the reasons means the scorer
     *       counted zero stubs and awarded its bonus. There is nothing to be necessary or avoidable,
     *       and this is the clause that makes the critic INVISIBLE on a proof that was already good.</li>
     * </ul>
     *
     * <p>THERE IS DELIBERATELY NO THRESHOLD ON THE COUNT. One stub could be gated out as obviously
     * fine, and that would cut the 140 down cheaply — but the corpus's minimum IS one, and deciding
     * that one mock is acceptable and seven are not is exactly the necessity judgement a counter cannot
     * make. It is the judgement this stage exists to move OFF a number. If the call volume has to come
     * down later, this is the one method to change, and the change should be argued as a cost decision
     * out loud rather than smuggled in as a constant.
     *
     * <p>The reasons are read verbatim from the row, so they stay {@link TestRealness}'s wording — the
     * same shared-constant discipline {@code Critiques} follows, and for the same reason: a substring
     * re-spelled in a second place does not fail, it silently stops matching.
     */
    public static Gate gate(Object parseTest) {
        if (!Json.truthy(parseTest, "can_prove")
                || Values.text(Json.get(parseTest, "test_code")).isEmpty()) {
            return new Gate(false, "no test to critique");
        }
        if (!Json.truthy(parseTest, "test_sound")) {
            return new Gate(false, "the test is not sound, which is a worse complaint than mocking "
                    + "and the free scorer already made it");
        }
        if (!Values.text(Json.get(parseTest, "test_realness")).contains(TestRealness.STUB_MOCK_REASON)) {
            return new Gate(false, "no stubs — the realness scorer is already satisfied");
        }
        return new Gate(true, "");
    }

    /**
     * Trim, and SAY SO in the text the model reads — with the note pointing at ACCEPTANCE.
     *
     * <p>{@link FixSkeptic#cut} tells the model to answer {@code unknown}, which routes a fix away from
     * a pull request. The direction inverts here for the reason the class comment gives: a critic that
     * saw half a source file must not ask anyone to rewrite a test over the half it did not see, so the
     * note names {@code necessary}, which is this stage's accepting answer.
     */
    static String cut(Object x, int limit) {
        String t = Values.text(x);
        return t.length() <= limit ? t
                : t.substring(0, limit) + "\n…[TRUNCATED " + (t.length() - limit)
                        + " chars — reply verdict 'necessary' if you cannot judge the whole of it]";
    }

    /** Assemble the prompt from {@link #DEFAULT_PROMPT}. Pure, so it can be asserted byte for byte. */
    public static String criticPrompt(PromptInput in) {
        return criticPrompt(in, DEFAULT_PROMPT);
    }

    /**
     * Assemble the prompt from an arbitrary template — the resolved file, when there is one.
     *
     * <p>The template's six {@code %s} are positional and THIS METHOD is what defines them: stamp,
     * class under test, checker, claim, test code, subject source. A file with the wrong number of them
     * is rejected at START-UP by {@code PromptSource}, not here: a marker discovering it mid-run would
     * take a prove down after a clone and two Maven builds.
     *
     * <p>Every absent field is NAMED rather than left blank — see {@link Llm#orMissing}. A prompt that
     * says {@code THE CLAIM THE TEST SETTLES:} with nothing after it reads as truncated, and a model
     * that thinks its prompt was cut off answers about the truncation instead of about the mocks.
     */
    public static String criticPrompt(PromptInput in, String template) {
        return template.formatted(Llm.orMissing(in.stamp(), "stamp"),
                Llm.orMissing(in.className(), "class under test"),
                Llm.orMissing(in.checker(), "checker"),
                Llm.orMissing(in.description(), "claim"),
                cut(in.testCode(), TEST_CUT),
                Llm.orMissing(cut(in.src(), SOURCE_CUT), "subject source"));
    }

    /**
     * A chat-completions reply to the verdict-plus-findings triple the node returns.
     *
     * <p>Only {@link MockNecessity#ANSWERS} — the two words the prompt asked for — are accepted;
     * everything else is {@link MockNecessity#UNAUDITED}, which {@link MockNecessity#accepts()} treats
     * as leaving the test alone. That is the whole fail-closed rule, and note again which way it points:
     * an unrecognised word here KEEPS the test, where the same situation in {@link FixSkeptic} withholds
     * a pull request.
     *
     * <p>TWO DOWNGRADES, AND BOTH ARE THE POINT OF THE FINDINGS LIST:
     * <ul>
     *   <li>{@code reducible} WITH NOTHING ACTIONABLE becomes {@code unaudited}. A complaint whose
     *       findings name no mock, or name no replacement, gives a retry nothing to quote — and a retry
     *       that can only say "use fewer mocks" produces the same test while spending an attempt. The
     *       reply was read, and it still did not contain a request anybody can act on.</li>
     *   <li>{@code necessary} ALONGSIDE an avoidable finding stays {@code necessary}. The model
     *       contradicted itself, and the tie goes to acceptance, because the verdict field is the one
     *       the loop branches on and the safe direction is to keep a test that may be fine.</li>
     * </ul>
     *
     * <p>{@code "necessary"} IS READ STRICTLY AS A BOOLEAN, exactly as {@code ParseTest} reads
     * {@code can_prove}: the STRING {@code "false"} is truthy in this codebase's JS-shaped coercions,
     * so a lenient read would turn a model's sloppy typing into a demand to rewrite a test. Only a real
     * {@code false} marks a mock avoidable.
     *
     * <p>Nothing is caught here. A body that is not a chat completion throws out of
     * {@link Llm#replyText}; that is a FAILED CALL and belongs in the shell's catch, labelled as such.
     * Laundering it into "the critic found nothing" would report a broken endpoint as a satisfied
     * reviewer — and since satisfied is this stage's accepting answer, that failure would be invisible.
     */
    public static Reply parseCriticReply(Object r) {
        String t = Llm.replyText(r);
        Map<String, Object> jj = t.isEmpty() ? null : JsonExtract.extractJson(t, KEYS);
        if (jj == null) {
            // The call HAPPENED and came back unreadable. Not NOT_RUN: that word means the gate
            // declined to ask, and an operator has to be able to tell those apart.
            return new Reply(MockNecessity.UNAUDITED.wire(),
                    "the critic's reply was not the JSON object the stage asked for", List.of());
        }

        List<Finding> findings = findings(Json.get(jj, "findings"));
        String said = Values.text(Json.get(jj, "verdict"));
        MockNecessity v = MockNecessity.of(said);
        if (v == null || !MockNecessity.ANSWERS.contains(v)) {
            return new Reply(MockNecessity.UNAUDITED.wire(),
                    said.isEmpty() ? "the critic's reply carried no verdict field"
                            : "unrecognised verdict: " + said,
                    findings);
        }
        if (v == MockNecessity.REDUCIBLE && findings.stream().noneMatch(Finding::actionable)) {
            return new Reply(MockNecessity.UNAUDITED.wire(),
                    "the critic answered reducible but named no mock a retry could replace",
                    findings);
        }

        String given = Values.text(Json.get(jj, "reason"));
        // A recognised verdict with no reason is NOT an unrecognised verdict — the same distinction
        // FixSkeptic draws, and for the same reason: "unrecognised verdict: necessary" in a row sends a
        // reader hunting a parser bug that does not exist and hides the real gap.
        return new Reply(v.wire(),
                !given.isEmpty() ? given : "(verdict given without a reason)", findings);
    }

    /**
     * The findings array, kept in the model's own order and skipping anything that is not an object.
     *
     * <p>A finding with no {@code mock} name is DROPPED rather than kept as a blank: it is the field
     * that makes a finding quotable, and "the mock called (unnamed)" in a retry is noise the writer
     * cannot act on. Dropping it is also what lets the {@code reducible} downgrade above fire — a reply
     * whose complaints are all anonymous accepts the test rather than demanding an unspecified rewrite.
     */
    private static List<Finding> findings(Object raw) {
        List<Finding> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return List.copyOf(out);
        }
        for (Object element : list) {
            if (!(element instanceof Map<?, ?>)) {
                continue;
            }
            String mock = Values.text(Json.get(element, "mock")).trim();
            if (mock.isEmpty()) {
                continue;
            }
            // Strict: only a real `false` marks a mock avoidable. @see #parseCriticReply
            boolean necessary = !Boolean.FALSE.equals(Json.get(element, "necessary"));
            out.add(new Finding(mock, necessary, Values.text(Json.get(element, "why")),
                    necessary ? "" : Values.text(Json.get(element, "instead"))));
        }
        return List.copyOf(out);
    }

    /**
     * The node's entry point: gate, call, parse, merge.
     *
     * <p>The incoming {@code Parse test} row flows through untouched and is spread FIRST, for the reason
     * {@link FixSkeptic#fixSkeptic} spreads its item first: on a retried prove the row still carries the
     * previous attempt's critique, and merging it last would republish a stale judgement of a new test.
     */
    public static Map<String, Object> reproducerCritic(Request req, Llm.Http http) {
        Object test = req.item();
        Gate gate = gate(test);

        // The NOT_RUN initialiser is deliberate: when the gate declines, nobody has judged these mocks,
        // and NECESSARY would have claimed somebody did. Both words accept the test — that is this
        // stage's safe direction — but only one of them is a judgement.
        String verdict = MockNecessity.NOT_RUN.wire();
        String reason = gate.reason();
        List<Finding> findings = List.of();
        // THE RECEIPT, exactly as `skeptic_answered`: TRUE only where the model's own reply was read
        // back, whatever it said. Every other path here ACCEPTS the test, so without this boolean a run
        // whose endpoint was unreachable and a run in which the critic approved all 140 tests produce
        // identical rows — and this stage's whole value is the difference between those two.
        boolean answered = false;
        if (gate.call()) {
            String prompt = criticPrompt(new PromptInput(req.criticStamp(),
                    Json.get(req.prepProver(), "class_name"),
                    Json.get(req.prepProver(), "svace_checker"),
                    Json.get(req.prepProver(), "description"),
                    Json.get(test, "test_code"),
                    Json.get(req.buildReproduceIn(), "src")), req.promptTemplate());
            // The parse is INSIDE the try: a malformed body throws out of the JSON parser, and that is
            // a failed call, not a judgement. Outside it, the failure would escape the node and the
            // prove would end with the marker's lease stranded.
            try {
                // temperature 0: this reply is BRANCHED ON, so it is a certification, and a
                // certification that varies run to run is not one. @see Llm#chat, and
                // ACertificationDoesNotVaryRunToRunTest, which fails if this line changes.
                Reply parsed = parseCriticReply(http.request(Llm.chat(req.llm(), prompt, 0)));
                verdict = parsed.verdict();
                reason = parsed.reason();
                findings = parsed.findings();
                // LAST, and only if both the call and the parse got this far: a body that threw in the
                // parser is a failed CALL, and a receipt set before it would claim the model had spoken
                // when what it sent could not be read.
                answered = true;
            } catch (Exception e) {
                // UNAUDITED, which accepts. An unreachable critic must not cost a test: the writer
                // would be re-asked with no findings to quote and would write the same test again.
                verdict = MockNecessity.UNAUDITED.wire();
                reason = "critic call failed: " + Llm.failureText(e, REASON_CUT, "error");
            }
        }

        Map<String, Object> out = spread(test);
        out.put("critic_verdict", verdict);
        out.put("critic_reason", reason);
        out.put("critic_answered", answered);
        out.put("critic_findings", findings.stream().map(Finding::toMap).toList());
        return out;
    }

    /**
     * A copy of the item that keeps its key ORDER. An item that is missing, null or not an object
     * copies to nothing — all three deliberately: the caller has to get a row either way.
     */
    private static Map<String, Object> spread(Object item) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (item instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(Objects.toString(k), v));
        }
        return out;
    }
}
