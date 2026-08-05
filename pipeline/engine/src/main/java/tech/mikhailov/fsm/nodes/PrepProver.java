package tech.mikhailov.fsm.nodes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.mikhailov.fsm.lib.CheckerMap;
import tech.mikhailov.fsm.lib.SourceText;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.lib.Json;

/**
 * {@code Prep prover} — resolves one marker into the paths, package and branch the whole prove
 * depends on.
 *
 * <p>Everything downstream is built from what this node computes. The reproducer is told which package
 * to declare and which path to write to, the source fetch is aimed at a branch, and the retry policy
 * reads {@code settle_by}. None of it fails loudly when it is wrong.
 *
 * <p>THE REGRESSION THIS NODE CARRIES, found by e2e and not by any unit test. It split the file path on
 * {@code "/src/main/java/"} WITH a leading slash. That matches a module-prefixed path and does NOT
 * match the ingester's repo-relative one, so on a single-module repo like
 * WebGoat the separator never matched: module, package and package directory all came out empty and
 * every generated test landed in the default package at the root of {@code src/test/java}. Nothing
 * threw, and the test still compiled — it was simply in the wrong package, unable to see the
 * package-private members it was written to exercise. The separator is therefore matched WITHOUT the
 * leading slash, and {@code module} is derived from whether the match is at offset zero.
 *
 * <p>THE OTHER SILENT FAILURE, in the branch. Hardcoding {@code 'main'} destroyed every finding on any
 * repo that uses develop / master / 4.x / v5-master: the source fetch 404s, the reproducer is handed
 * an empty file, and the marker is recorded as rejected — indistinguishable from a real false
 * positive. So an unresolvable branch is FLAGGED ({@code branch_ok} plus a {@code branch_error} that
 * names the cause) rather than guessed, and record-outcome turns that into infra_error and a retry.
 */
public final class PrepProver {

    private PrepProver() {
    }

    /**
     * The path separator, WITHOUT a leading slash. See the class comment — this single character is
     * the regression.
     */
    private static final String MARK = "src/main/java/";

    /** Trailing separators, however many: 'core//' and 'core/' are both the module 'core'. */
    private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");

    /** Anything Java will not accept in an identifier, dropped rather than substituted. */
    private static final Pattern NOT_IDENTIFIER = Pattern.compile("[^A-Za-z0-9_]");

    /**
     * The ingester's provenance hint, read with or WITHOUT whitespace after the colon.
     *
     * <p>The evidence blob is free-form prose the ingester assembles, so demanding a space would
     * downgrade every {@code argue} marker to {@code test} — and a dead store, which nothing
     * observable at runtime can exhibit, would then burn a second prove attempt on a JUnit test that
     * can only ever fail to reproduce. {@code \s} is the WIDER whitespace set, not Java's; see
     * {@link SourceText#SPACE_CLASS}.
     */
    private static final Pattern SETTLE_BY =
            Pattern.compile("Settle-by:[" + SourceText.SPACE_CLASS + "]*(\\w+)");

    /** How much of a lookup failure survives into the row; see {@link #describe}. */
    private static final int ERROR_CHARS = 200;

    /** An unbounded lookup would hang the whole prove on a stalled connection. */
    static final int LOOKUP_TIMEOUT_MS = 30_000;

    /**
     * The request the node asks its caller to make. A value object rather than a call, so this class
     * stays pure and the SHAPE of the request is testable — every field in it is load-bearing and each
     * one has failed in production at least once.
     *
     * @param headers    insertion-ordered: the assertion that reads them back is order-blind, but a
     *                   reader diffing two recorded requests is not
     * @param json       false hands back an unparsed string body, so {@code default_branch} would come
     *                   back absent for every repo — silently, as an empty branch
     * @param timeoutMs  see {@link #LOOKUP_TIMEOUT_MS}
     */
    public record LookupRequest(String url, Map<String, String> headers, boolean json, int timeoutMs) {
    }

    /**
     * The GitHub call, as a seam. This module owns no HTTP client: the caller injects one, which is
     * what keeps every stage in this package a pure function over its request.
     */
    @FunctionalInterface
    public interface RepoLookup {

        /** @return the parsed response body, or throws {@link LookupFailed} */
        Object fetch(LookupRequest request);
    }

    /**
     * A failed lookup, carrying the VALUE it failed with rather than a message.
     *
     * <p>The value, because the two shapes that arrive are read differently: an HTTP-level failure
     * carries {@code description}, and a transport failure carries {@code message}. Collapsing them
     * reports "no default_branch returned" — which is GitHub's answer — for a request that never
     * reached GitHub, and after that triage cannot tell infra from verdict.
     */
    public static final class LookupFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient Object rejection;

        public LookupFailed(Object rejection) {
            super(null, null, false, false);          // control flow, not a stack trace worth building
            this.rejection = rejection;
        }

        /** The failure value: a Map for an HTTP-level failure, a Throwable for a transport one. */
        public Object rejection() {
            return rejection;
        }
    }

    /**
     * One suspicion row plus the credential the branch lookup needs.
     *
     * @param suspicion   the claimed suspicion row, as the queue handed it over
     * @param githubToken the GitHub token, kept as a RAW value so an unset one still produces the
     *                    header "Bearer undefined". GitHub answers that with 401, which is visible;
     *                    "Bearer " with nothing after it looks like a request nobody meant to
     *                    authenticate.
     */
    public record Request(Object suspicion, Object githubToken) {

        /** Read the request out of a posted body. */
        public static Request of(Object body) {
            return new Request(Json.get(body, "suspicion"), Json.get(body, "github_token"));
        }
    }

    /**
     * The prepared marker. Every later stage in the prove reads its fields off this item.
     *
     * <p>Several components are {@code Object} rather than {@code String}: they are passed through
     * untouched, or through {@code x || ''}, both of which preserve whatever type the ingester wrote.
     * Typing them here would insert a coercion nothing else performs, and that is the kind that shows
     * up three stages later as the word "null" inside a fenced Java block.
     *
     * @param branch      may be a non-string when GitHub answers with an odd {@code default_branch};
     *                    {@code (ri && ri.default_branch) || ''} does not coerce
     * @param branchError the ONLY record of why a branch is missing, and the difference between
     *                    "retry this" and "this repo has no default branch"
     * @param settleBy    the wire spelling of a {@link CheckerMap.SettleBy}: 'test' = a JUnit test can
     *                    exhibit this; 'argue' = nothing observable at runtime distinguishes the
     *                    flagged code (dead store, hard-coded secret), so the only honest outcome is a
     *                    written verdict. Decides whether a non-reproduction is worth a second prove
     *                    attempt. A String and NOT the enum, because the value is recovered by grepping
     *                    a free-form evidence blob and whatever word that finds is carried through
     *                    verbatim — see the read below and {@link CheckerMap.SettleBy#of(Object)},
     *                    which is where a caller turns it back into one of the two that mean anything.
     */
    public record Outcome(Object suspicionKey, Object repo, Object branch, boolean branchOk,
                          String branchError, double proveAttempts, String file, String module,
                          String pkg, String className, Object method, String testClass,
                          String testPath, Object category, Object severity, Object title,
                          Object description, String evidence, Object markerId, Object svaceChecker,
                          Object svaceSeverity, double svaceLine, String settleBy) {

        /** The response body, in a FIXED key order — see the note on {@link #put}. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            put(m, "suspicion_key", suspicionKey);
            put(m, "repo", repo);
            put(m, "branch", branch);
            put(m, "branch_ok", branchOk);
            put(m, "branch_error", branchError);
            put(m, "prove_attempts", proveAttempts);
            put(m, "file", file);
            put(m, "module", module);
            put(m, "pkg", pkg);
            put(m, "class_name", className);
            put(m, "method", method);
            put(m, "test_class", testClass);
            put(m, "test_path", testPath);
            put(m, "category", category);
            put(m, "severity", severity);
            put(m, "title", title);
            put(m, "description", description);
            put(m, "evidence", evidence);
            put(m, "marker_id", markerId);
            put(m, "svace_checker", svaceChecker);
            put(m, "svace_severity", svaceSeverity);
            put(m, "svace_line", svaceLine);
            put(m, "settle_by", settleBy);
            return m;
        }

        /**
         * {@code JSON.stringify} DROPS a key whose value is undefined, and the passthrough fields are
         * undefined whenever the ingester never set them. Emitting {@code "method": null} instead
         * would be a claim nobody made — and the next stage reads it back with {@code || ''}, where
         * null and undefined happen to agree, but splices it into a prompt with {@code +}, where they
         * read as the words "null" and "undefined".
         */
        private static void put(Map<String, Object> m, String key, Object value) {
            if (value != null) {
                m.put(key, value);
            }
        }
    }

    /** Resolve the marker. {@code lookup} is called at most once, and only for a blank branch. */
    public static Outcome prepProver(Request req, RepoLookup lookup) {
        Object s = req.suspicion();

        // The suspector already analysed a specific branch — reuse it, which also avoids one API call
        // per suspicion. Trimmed because the column arrives from SQLite/CSV and is routinely padded:
        // untrimmed it goes straight into the raw.githubusercontent URL, which 404s.
        Object branch = SourceText.trim(Values.text(Json.get(s, "branch")));
        String branchError = "";
        if (SourceText.isBlank(Values.text(branch))) {
            try {
                Object info = lookup.fetch(lookupRequest(s, req.githubToken()));
                branch = Values.orIfAbsent(Json.get(info, "default_branch"), "");
            } catch (LookupFailed e) {
                branchError = describe(e.rejection());
            }
        }
        if (SourceText.isBlank(Values.text(branch)) && branchError.isEmpty()) {
            branchError = "no default_branch returned";
        }

        String file = Values.text(Json.get(s, "file"));
        int at = file.indexOf(MARK);
        // at == 0 is a single-module repo: the path STARTS at src/main/java, so there is no module.
        // Only the trailing separator is dropped, never an interior one — collapsing the first '/'
        // instead would fuse 'core/legacy' into 'corelegacy', a directory `mvn -pl` refuses to build.
        String module = at > 0 ? TRAILING_SLASHES.matcher(file.substring(0, at)).replaceAll("") : "";
        String rest = at >= 0 ? file.substring(at + MARK.length()) : "";
        // lastIndexOf('/') is -1 for a class directly under src/main/java, and slice(0, -1) would
        // silently truncate the FILENAME's last character into a bogus package. Test for the
        // separator instead.
        String pkgdir = rest.indexOf('/') >= 0 ? rest.substring(0, rest.lastIndexOf('/')) : "";
        String pkg = pkgdir.replace('/', '.');

        String[] segments = file.split("/", -1);
        String last = segments[segments.length - 1];
        String fallbackName = last.endsWith(".java")
                ? last.substring(0, last.length() - ".java".length()) : last;
        String cls = NOT_IDENTIFIER.matcher(
                Values.orIfBlank(Json.get(s, "class_name"), fallbackName)).replaceAll("");
        String testClass = cls + "FsmProofTest";
        String testPath = (module.isEmpty() ? "" : module + "/") + "src/test/java/"
                + (pkgdir.isEmpty() ? "" : pkgdir + "/") + testClass + ".java";

        String evidence = Values.text(Json.get(s, "evidence"));
        Matcher settle = SETTLE_BY.matcher(evidence);

        double svaceLine = Values.numberOr(Json.get(s, "svace_line"), 0);
        if (svaceLine == 0) {
            svaceLine = Values.numberOr(Json.get(s, "line"), 0);
        }

        return new Outcome(
                Json.get(s, "dedup_key"), Json.get(s, "repo"), branch,
                !SourceText.isBlank(Values.text(branch)), branchError,
                Values.numberOr(Json.get(s, "prove_attempts"), 0),
                file, module, pkg, cls, Json.get(s, "method"), testClass, testPath,
                Json.get(s, "category"), Json.get(s, "severity"), Json.get(s, "title"),
                Json.get(s, "description"), evidence,
                Values.orIfAbsent(Json.get(s, "marker_id"), ""),
                Values.orIfAbsent(Json.get(s, "svace_checker"), ""),
                Values.orIfAbsent(Json.get(s, "svace_severity"), ""),
                // The DEFAULT, off the enum that owns the vocabulary: a marker whose evidence carries
                // no Settle-by hint at all is treated as one a test can settle. The literal used to
                // sit here while `ParseMarkers` wrote the same word out of CheckerMap.SettleBy and
                // `Verdict` compared against its sibling — three copies of a two-word vocabulary.
                svaceLine, settle.find() ? settle.group(1) : CheckerMap.SettleBy.TEST.wire());
    }

    /**
     * The {@code Authorization} header, with an UNSET TOKEN SPELLED OUT rather than left blank.
     *
     * <p>WHY THIS IS NOT {@code "Bearer " + Values.text(token)}. An empty Bearer is the one rendering
     * that hides the fault. GitHub does not read it as "no credential" and refuse — it reads it as a
     * request nobody meant to authenticate, drops the caller onto the 60-per-hour ANONYMOUS quota and
     * serves no private repository at all. The run then fails INTERMITTENTLY, an hour in, on whichever
     * marker happened to cross the quota, and the header that caused it looks perfectly ordinary in a
     * log. The old code avoided that by writing the JavaScript word {@code undefined} into the header;
     * the word was retired on 2026-08-05 with the rest of the emulation, and it was the wrong marker
     * anyway — greppable only if you already know that this codebase spells "missing" that way.
     *
     * <p>So the header NAMES THE VARIABLE, exactly as {@link tech.mikhailov.fsm.lib.Llm#baseUrl} does
     * for {@code QWEN_BASE_URL} and for the same reason. GitHub rejects it with 401 immediately — a
     * loud, first-request, deterministic failure instead of a slow quota leak — and the 401 carries its
     * own diagnosis and its own fix in the text.
     *
     * <p>Shared with {@code GithubSourceClient}, which sends the identical header on the source fetch.
     * It used to hold its own copy with a comment claiming they matched; they had already drifted apart
     * by the time anyone read it, so there is now one of them.
     */
    public static String authorization(Object token) {
        return "Bearer " + Values.orIfBlank(token, "(GITHUB_TOKEN is not set)");
    }

    /** The lookup, spelled out: every header here is the fix for a way the call has failed before. */
    private static LookupRequest lookupRequest(Object suspicion, Object token) {
        Map<String, String> headers = new LinkedHashMap<>();
        // GitHub answers a User-Agent-less request with 403 and an unauthenticated one with 60
        // requests an hour and no private repos at all. Either way default_branch comes back absent
        // for every row, and every marker in the run is recorded against an empty branch.
        //
        // The NAME is addressed to a repository owner reading their access log: it says what is
        // reading their source and why. It is also a deliberate divergence from the frozen differential
        // corpus, which recorded the old value — so changing it again means re-baselining a catalogue.
        // See harness/README.md, "Re-baselines".
        headers.put("User-Agent", "svace-marker-fixer");
        headers.put("Accept", "application/vnd.github+json");
        headers.put("Authorization", authorization(token));
        // One short-lived call per suspicion; a pooled connection to api.github.com outlives the run
        // and holds a socket open for nothing.
        headers.put("Connection", "close");
        return new LookupRequest(
                "https://api.github.com/repos/" + Values.text(Json.get(suspicion, "repo")),
                headers, true, LOOKUP_TIMEOUT_MS);
    }

    /**
     * Name the cause of a failed lookup, in 200 characters.
     *
     * <p>Truncated because GitHub answers a rate limit with a multi-KB HTML page: untruncated it lands
     * in the suspicion row and then in every prover prompt built from it, costing tokens and burying
     * the marker. Truncated from the END — the cause is at the front.
     *
     * <p>A rejection with neither field (a bare socket or abort object) must not stringify into the
     * literal "undefined", which reads in the Data Table like a real GitHub answer rather than a
     * missing one.
     */
    private static String describe(Object rejection) {
        String text = Values.orIfBlank(errorMessage(rejection),
                Values.orIfBlank(errorDescription(rejection), ""));
        if (text.isEmpty()) {
            return "repo lookup failed";
        }
        return text.substring(0, Math.min(text.length(), ERROR_CHARS));
    }

    /**
     * {@code e.message}, over either shape a rejection arrives in.
     *
     * <p>A rejection that is neither — {@code throw 'boom'}, or a bare abort object — has no
     * properties at all, which is why this returns UNDEFINED rather than the value itself.
     */
    private static Object errorMessage(Object rejection) {
        if (rejection instanceof Throwable t) {
            return t.getMessage();
        }
        return Json.get(rejection, "message");
    }

    /** {@code e.description} — the field an HTTP-level failure carries and no Java exception does. */
    private static Object errorDescription(Object rejection) {
        return Json.get(rejection, "description");
    }
}
