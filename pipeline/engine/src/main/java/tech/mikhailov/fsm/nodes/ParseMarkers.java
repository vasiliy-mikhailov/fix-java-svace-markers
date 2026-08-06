package tech.mikhailov.fsm.nodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tech.mikhailov.fsm.lib.AnchorStatus;
import tech.mikhailov.fsm.lib.CheckerMap;
import tech.mikhailov.fsm.lib.Csv;
import tech.mikhailov.fsm.lib.SourceText;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Severity;
import tech.mikhailov.fsm.lib.SuspicionStatus;

/**
 * {@code Parse markers} — turns a Svace CSV report into one suspicion row per marker.
 *
 * <p>This replaces a file-walking orchestrator and LLM suspector entirely. Svace
 * has already decided what is suspicious, so ingest is a pure deterministic transform: one CSV row in,
 * one suspicion out. There is no dedup stage either — Svace de-duplicates its own markers, and the only
 * genuine repeats (same checker, same file, same line) are two distinct obligations rather than a
 * duplicate, kept apart by an occurrence index.
 *
 * <p>EVERYTHING DOWNSTREAM TRUSTS THIS. Nothing here fails loudly on its own: a bad path prefix, a
 * dropped filter or a colliding {@code dedup_key} corrupts the whole backlog silently, and the symptom
 * arrives days later as a prover that cannot open a file or a marker that never gets worked. The three
 * failures the tests exist to prevent, in the order they cost the most:
 *
 * <ol>
 *   <li>AN UNSTRIPPED CI BUILD PATH. Markers carry an absolute build path —
 *       {@code /builds/gitlab/<group>/<project>/src/main/java/...} — which exists on no checkout
 *       anywhere. See {@link #normalisePath}.</li>
 *   <li>A DEDUP_KEY COLLISION. Two markers on ONE line are two distinct obligations, not a duplicate.
 *       They are kept apart by an occurrence index, and the FIRST occurrence carries no suffix — or
 *       every key changes the day a second marker appears on that line, and the whole backlog
 *       re-ingests as new work.</li>
 *   <li>LOSING THE SEVERITY ORDERING. Insertion order IS priority order; see the sort at the end of
 *       {@link #parseMarkers}.</li>
 * </ol>
 *
 * <p>WHY THE BODY IS {@code Object}. It is an ingest payload, written by hand into a curl call, so
 * every field in it can be absent, null or the wrong type and there is a defined answer for each.
 * Typing it as a record would replace those answers with binding errors on a request a human is still
 * composing, so the body arrives as it is and each read goes
 * through {@link Values} / {@link Json}, which spell the coercions out.
 *
 * <p>WHY THIS STAGE DOES NOT LOG. The summary is RETURNED — and, on the "everything filtered out"
 * path, embedded in the exception message — so the edge logs it once. A println inside a pure function
 * is a side effect no test can assert on and no caller can turn off.
 *
 * <p>THE THREE PLACES THIS DELIBERATELY DIVERGES FROM THE RECORDED REFERENCE. A differential harness
 * ran 5,562 generated requests through both; 5,419 were byte-identical and every one of the 143
 * that were not belongs to one of these. All three are DEFECTS this code does not reproduce:
 *
 * <ol>
 *   <li>A CHECKER NAMED AFTER AN {@code Object.prototype} MEMBER. An unguarded
 *       {@code CHECKER_MAP[checker]} off an object literal lets {@code checker="toString"} resolve up
 *       the prototype chain to a FUNCTION. That is truthy, so the row is NOT counted as unmapped and
 *       NOT given the fallback claim: {@code m[0]}, {@code m[1]} and {@code m[2]} are all undefined and
 *       the row reads "Claim: undefined. Settle-by: undefined.". `Prep prover` then greps
 *       {@code /Settle-by:\s*(\w+)/}, gets "undefined", compares it against 'argue', and queues the
 *       marker for a reproducer that is asked to settle a claim nobody wrote. This is the exact
 *       incident {@link CheckerMap}'s class comment records. {@code Map.get} has no prototype chain, so
 *       here the row is unmapped, counted, and carries a real claim.</li>
 *   <li>A SEVERITY NAMED AFTER AN {@code Object.prototype} MEMBER. Same cause, worse effect.
 *       {@code SEVERITY_RANK["toString"]} is a function, so the sort comparator returns NaN — which
 *       makes it NON-TRANSITIVE, and V8's TimSort then produces an arbitrary permutation. Observed:
 *       a Major marker queued at position 14 of 17, below Minor and below five garbage rows. That is
 *       precisely the failure the severity sort exists to prevent. Unguarded it also writes the
 *       FUNCTION into the {@code severity} cell the dashboard filters on, and counts it in {@code by_severity}
 *       by string concatenation ({@code "function toString() { [native code] }1"}).
 *       {@link Severity#rankOf} answers {@code UNKNOWN_RANK} for all of them, so they sort below Minor
 *       in report order and grade 'low'.</li>
 *   <li>A {@code csv_path} THAT IS A DIRECTORY. Node's message is
 *       "EISDIR: illegal operation on a directory, read", which names neither the path nor the mount —
 *       and the mount is the whole diagnosis. See {@link #read}.</li>
 * </ol>
 */
public final class ParseMarkers {

    private ParseMarkers() {
    }

    /**
     * The CI prefix used when the request says nothing — the root the WebGoat scanner actually
     * recorded. It is a DEFAULT and not a constant: a report from any other CI root arrives with its
     * own {@code path_prefix}, and {@code path_prefix: ""} turns stripping off entirely.
     */
    static final String DEFAULT_PATH_PREFIX = "/builds/gitlab/drit_digital_trace/owasp-webgoat/";

    /** Where the report is expected on the mounted repo when the request names no path. */
    static final String DEFAULT_CSV_PATH = "/data/data/svace/webgoat-markers-356.csv";

    /**
     * A marker under {@code src/test} or {@code src/it} is STRUCTURALLY unfixable here: the runner
     * refuses any edit under a test tree, so such a marker could only ever end as a verdict. Matched
     * anywhere in the repo-relative path, not just at its head, because a multi-module report puts the
     * module directory first.
     */
    private static final Pattern TEST_TREE = Pattern.compile("(^|/)src/(test|it)/");

    /** Raised for a request that cannot produce a backlog at all. Its message is part of the wire
     * contract: the ingest endpoint returns it verbatim. */
    public static final class IngestFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        IngestFailed(String message) {
            super(message);
        }

        IngestFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The ingest request.
     *
     * @param body    the webhook body, untyped — see the porting note on the class
     * @param version stamped onto every row as {@code version}, so a row can be pinned to the ingest
     *                that produced it
     */
    public record Request(Object body, String version) {

        /** Read the request out of a posted body: {@code {"body": {...}, "version": "i1"}}. */
        public static Request of(Object posted) {
            return new Request(Json.get(posted, "body"), Json.str(posted, "version"));
        }
    }

    /**
     * One suspicion — the row the prover leases and the reviewer eventually reads.
     *
     * @param line       provisional; re-anchoring against the real checkout may move it. A double
     *                   rather than a long because it travels the wire as a JavaScript Number, and the
     *                   difference is observable at the extremes; see {@link Values#leadingInt}
     * @param svaceLine  what Svace actually reported — never overwritten, so a re-anchor that goes
     *                   wrong can still be traced back to the marker
     * @param method     unknown until the prover re-anchors against the real checkout
     * @param category   the checker's category, or {@code unmapped} when the table has not caught up
     * @param severity   the graded severity the backlog filters on, NOT the reported one
     */
    public record Suspicion(String dedupKey, String markerId, String repo, String branch, String file,
                            String className, String method, double line, double svaceLine,
                            String anchor, String anchorStatus, String category, String severity,
                            String svaceChecker, String svaceSeverity, String title,
                            String description, String evidence, String status, String note,
                            long proveAttempts, String version, String methodKey) {

        /** The row, in a FIXED key order, so a consumer that takes its columns from the first row
         * it sees gets the same columns every time. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dedup_key", dedupKey);
            m.put("marker_id", markerId);
            m.put("repo", repo);
            m.put("branch", branch);
            m.put("file", file);
            m.put("class_name", className);
            m.put("method", method);
            m.put("line", number(line));
            m.put("svace_line", number(svaceLine));
            m.put("anchor", anchor);
            m.put("anchor_status", anchorStatus);
            m.put("category", category);
            m.put("severity", severity);
            m.put("svace_checker", svaceChecker);
            m.put("svace_severity", svaceSeverity);
            m.put("title", title);
            m.put("description", description);
            m.put("evidence", evidence);
            m.put("status", status);
            m.put("note", note);
            m.put("prove_attempts", proveAttempts);
            m.put("version", version);
            m.put("method_key", methodKey);
            return m;
        }
    }

    /**
     * What happened to the rows that did NOT become suspicions.
     *
     * <p>Each counter is the only record that its filter fired: a marker that is silently absent looks
     * exactly like a marker the scanner never raised. They are asserted as a whole object in the tests
     * for the same reason — one number can go up while another quietly goes down.
     *
     * @param unmappedKept counted but NOT dropped; the row is still ingested under category
     *                     {@code unmapped}, and this is the operator's cue to extend the checker map
     */
    public record Skipped(long tests, long checkerFilter, long severityFilter, long unmappedKept,
                          long badRow) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tests", tests);
            m.put("checker_filter", checkerFilter);
            m.put("severity_filter", severityFilter);
            m.put("unmapped_kept", unmappedKept);
            m.put("bad_row", badRow);
            return m;
        }
    }

    /**
     * The ingest's own account of itself, carried on the first row as {@code __summary} so the final
     * node can report it without a second pass.
     *
     * @param source  {@code csv_text} for an inline body, otherwise the path actually read
     * @param csvRows data rows the CSV parser found, header excluded — comparable against the
     *                scanner's own total, which is how a truncated download is spotted
     */
    public record Summary(String source, String repo, String branch, long ingested, long csvRows,
                          Map<String, Long> bySeverity, Skipped skipped,
                          Map<String, Long> unmappedCheckers) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", source);
            m.put("repo", repo);
            m.put("branch", branch);
            m.put("ingested", ingested);
            m.put("csv_rows", csvRows);
            m.put("by_severity", new TreeMap<>(bySeverity));
            m.put("skipped", skipped.toMap());
            m.put("unmapped_checkers", new TreeMap<>(unmappedCheckers));
            return m;
        }

        /** The serialised form written into {@code __summary} and into the "nothing left" error. */
        public String json() {
            return Json.stringify(toMap());
        }
    }

    /** The backlog plus the account of how it was produced. */
    public record Result(List<Suspicion> suspicions, Summary summary) {

        /**
         * The item list: one map per suspicion, with {@code __summary} appended to the FIRST one.
         *
         * <p>Appended, not prepended: it is added to an already-built object, and a consumer that takes
         * its column order from the first item it sees must not find {@code __summary} standing in
         * front of the marker's own fields.
         */
        public List<Map<String, Object>> items() {
            List<Map<String, Object>> out = new ArrayList<>(suspicions.size());
            for (Suspicion s : suspicions) {
                out.add(s.toMap());
            }
            if (!out.isEmpty()) {
                out.get(0).put("__summary", summary.json());
            }
            return out;
        }
    }

    /**
     * THE INGEST BODY, parsed — every key of the webhook payload, read once and in one place.
     *
     * <p>It is a hand-written curl payload (see the class comment), so each of these carries a
     * deliberate answer to "what if this field is absent, null, or the wrong type?", and each of those
     * answers is a decision somebody has to be able to find. They were already read in one block at the
     * top of {@link #parseMarkers}; naming the block is what makes the REQUEST CONTRACT legible on its
     * own — twenty lines instead of a method that has to be read to its end to learn what it accepts.
     *
     * <p>THE FILE IS NOT READ HERE. {@link #csvPath} resolves the default and stops; the read is a side
     * effect and it belongs after the {@code repo} check, or a request that names no repository would
     * touch the disk before it is rejected.
     *
     * @param prefix       the CI path prefix to strip. ABSENT means "use the WebGoat default";
     *                     PRESENT-BUT-FALSY means "do not strip at all". Those are different requests
     *                     and JSON can express both, so the distinction is a containsKey and not a null
     *                     check.
     * @param includeTests STRICTLY true, not merely truthy: the string "false" is a plausible typo in a
     *                     hand-written body and "false" is truthy — reading it as a request for the 74
     *                     structurally unfixable test-tree markers would put a day of prover time into
     *                     work the runner refuses to do.
     * @param onlyCheckers NULL means no filter at all, and that is NOT the same as an empty set: an
     *                     {@code only_checkers: []} filters nothing, where an empty SET would filter
     *                     everything and ingest a backlog of nothing.
     * @param minRank      from a value that is deliberately NOT trimmed: min_severity is looked up
     *                     verbatim, so a padded value ranks -1 and filters nothing. That is the safe
     *                     direction — the opposite mistake drops markers the operator asked to keep.
     */
    private record Body(String repo, String branch, boolean includeTests, String prefix,
                        Set<String> onlyCheckers, int minRank, String csvText, String csvPath) {

        static Body of(Object b) {
            Object rawOnly = Json.get(b, "only_checkers");
            Set<String> onlyCheckers = null;
            if (rawOnly instanceof List<?> l && !l.isEmpty()) {
                // Trimmed, because the list arrives from a hand-written request body and a padded name
                // matches no checker at all — which would silently ingest nothing and read as "the
                // report has no such findings".
                onlyCheckers = new LinkedHashSet<>();
                for (Object o : l) {
                    onlyCheckers.add(SourceText.trim(Values.text(o)));
                }
            }
            Object path = Json.get(b, "csv_path");
            return new Body(
                    SourceText.trim(Values.text(Json.get(b, "repo"))),
                    SourceText.trim(Values.text(Json.get(b, "branch"))),
                    Boolean.TRUE.equals(Json.get(b, "include_tests")),
                    has(b, "path_prefix")
                            ? Values.text(Json.get(b, "path_prefix")) : DEFAULT_PATH_PREFIX,
                    onlyCheckers,
                    Severity.rankOf(Values.text(Json.get(b, "min_severity"))),
                    Values.text(Json.get(b, "csv_text")),
                    Values.orIfBlank(path, DEFAULT_CSV_PATH));
        }
    }

    /** Turn the request's CSV into the backlog. */
    public static Result parseMarkers(Request req) {
        // THE PARSE, AND IT IS THE ONLY ONE: nothing below this line reads a key of the request.
        Body body = Body.of(req.body());
        String repo = body.repo();
        if (repo.isEmpty()) {
            throw new IngestFailed("ingest: `repo` is required (e.g. \"WebGoat/WebGoat\")");
        }
        String prefix = body.prefix();
        Set<String> onlyCheckers = body.onlyCheckers();
        int minRank = body.minRank();

        // source: an inline body, or a file off the mounted repo
        String text = body.csvText();
        String source = "csv_text";
        if (text.isEmpty()) {
            text = read(body.csvPath());
            source = body.csvPath();
        }

        List<List<String>> rows = Csv.parse(text);
        if (rows.isEmpty()) {
            throw new IngestFailed("ingest: CSV parsed to zero rows (" + source + ")");
        }
        List<String> head = new ArrayList<>();
        for (String h : rows.get(0)) {
            // Locale.ROOT via toLowerCase(char-wise Unicode default): NOT the default locale. Under a
            // Turkish locale "SEVERITY".toLowerCase() is "severıty" with a dotless i, the column is
            // not found, and every ingest on that host dies on a report every other host reads.
            head.add(SourceText.trim(h).toLowerCase(Locale.ROOT));
        }
        // Evaluated in this order, so the FIRST missing column is the one reported.
        int iSev = column(head, "severity");
        int iChk = column(head, "checker");
        int iFile = column(head, "file");
        int iLine = column(head, "line");

        List<Suspicion> out = new ArrayList<>();
        Map<String, Long> seen = new LinkedHashMap<>();      // dedup_key base -> occurrence count
        Map<String, Long> unmapped = new LinkedHashMap<>();
        long tests = 0;
        long checkerFilter = 0;
        long severityFilter = 0;
        long unmappedKept = 0;
        long badRow = 0;

        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String checker = SourceText.trim(cell(row, iChk));
            String rawFile = SourceText.trim(cell(row, iFile));
            double line = Values.leadingInt(SourceText.trim(cell(row, iLine)));
            String sev = SourceText.trim(cell(row, iSev));

            // A row missing any of checker/file/line would become a marker the prover cannot act on:
            // nothing to prove, nothing to open, or nowhere to look.
            if (checker.isEmpty() || rawFile.isEmpty() || !isFinite(line)) {
                badRow++;
                continue;
            }
            if (onlyCheckers != null && !onlyCheckers.contains(checker)) {
                checkerFilter++;
                continue;
            }
            if (Severity.rankOf(sev) < minRank) {
                severityFilter++;
                continue;
            }

            String file = normalisePath(rawFile, prefix);
            if (TEST_TREE.matcher(file).find() && !body.includeTests()) {
                tests++;
                continue;
            }

            CheckerMap.Entry m = CheckerMap.get(checker);
            if (m == null) {
                unmapped.merge(checker, 1L, Long::sum);
                unmappedKept++;
            }
            // An unknown checker is still a real marker — ingest it with the checker name as its own
            // meaning rather than dropping a finding because this map has not caught up with the
            // scanner.
            String category = m == null ? "unmapped" : m.category();
            String meaning = m == null
                    ? "Svace checker " + checker + " (no description available in the checker map)"
                    : m.meaning();
            String prove = m == null ? CheckerMap.SettleBy.TEST.wire() : m.settleBy().wire();

            String lineText = Values.plain(line);
            String base = repo + "|" + file + "|" + lineText + "|" + checker;
            long occurrence = seen.merge(base, 1L, Long::sum);
            String suffix = occurrence > 1 ? "#" + occurrence : "";
            String className = stripJavaSuffix(lastSegment(file));
            Severity graded = Severity.of(sev);

            out.add(new Suspicion(
                    base + suffix,
                    checker + "@" + file + ":" + lineText + suffix,
                    repo, body.branch(), file,
                    className,
                    "",
                    line,
                    line,
                    "",
                    // No anchor yet — `Build reproduce input` re-anchors this against the real
                    // checkout and overwrites the column with one of the other three spellings. Off
                    // the enum, which is the only place all four are written down. @see AnchorStatus
                    AnchorStatus.PENDING.wire(),
                    category,
                    graded == null ? Severity.Grade.LOW.wire() : graded.grade().wire(),
                    checker,
                    sev,
                    checker + " at " + className + ".java:" + lineText,
                    meaning,
                    "Svace " + sev + " marker `" + checker + "` at " + file + ":" + lineText
                            + ". Claim: " + meaning + ". Settle-by: " + prove + ".",
                    // The queue token, off the enum — the same rule the grade above follows. This is
                    // where every marker's status vocabulary starts, and a literal here is a spelling
                    // the drain's `WHERE status = ?` would have to agree with by luck.
                    SuspicionStatus.NEW.wire(),
                    "",
                    0L,
                    req.version(),
                    ""));
        }

        // INSERTION ORDER IS PRIORITY ORDER. The prover drains status='new' oldest-first under the
        // runner lease, and a full report is 282 markers at minutes each — over a day of wall clock,
        // which will realistically be stopped part-way. In CSV order that day goes to whatever the
        // scanner listed first (the report opens with Minor rows) and the Critical taint markers are
        // reached last. Sort by severity so the run is useful at every point at which it might be
        // stopped. List.sort is stable, as Array#sort is, so markers of equal severity keep their
        // report order and a re-ingest yields the same queue.
        out.sort((a, c) -> Integer.compare(Severity.rankOf(c.svaceSeverity()),
                Severity.rankOf(a.svaceSeverity())));

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Suspicion s : out) {
            bySeverity.merge(s.svaceSeverity(), 1L, Long::sum);
        }
        Summary summary = new Summary(source, repo, body.branch(), out.size(), rows.size() - 1L,
                bySeverity, new Skipped(tests, checkerFilter, severityFilter, unmappedKept, badRow),
                unmapped);
        if (out.isEmpty()) {
            throw new IngestFailed("ingest: every row was filtered out — " + summary.json());
        }
        return new Result(List.copyOf(out), summary);
    }

    /**
     * Absolute CI build path -> repo-relative.
     *
     * <p>Falls back to slicing at the first {@code /src/} segment so a report from a DIFFERENT CI root
     * still normalizes instead of silently producing an unopenable path. That fallback also hides
     * prefix bugs on WebGoat's own paths — every marker there normalizes to the same string either way
     * — which is why the tests put markers OUTSIDE {@code src/}, where only the prefix can do the job.
     *
     * <p>Only LEADING slashes are dropped. An interior one is a directory boundary, and collapsing it
     * would rewrite a path that some checkout genuinely has.
     */
    private static String normalisePath(String p, String prefix) {
        String s = p.replace('\\', '/');
        if (!prefix.isEmpty() && s.startsWith(prefix)) {
            return stripLeadingSlashes(s.substring(prefix.length()));
        }
        int i = s.indexOf("/src/");
        if (i >= 0) {
            return s.substring(i + 1);
        }
        return stripLeadingSlashes(s);
    }

    private static String stripLeadingSlashes(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '/') {
            i++;
        }
        return s.substring(i);
    }

    /** {@code file.split('/').pop() || ''} — the empty string for an empty path or a trailing slash. */
    private static String lastSegment(String file) {
        return file.substring(file.lastIndexOf('/') + 1);
    }

    /**
     * {@code s.replace(/\.java$/, '')}. Written with {@code endsWith} rather than a regex on purpose:
     * Java's {@code $} also matches BEFORE a trailing line terminator, so a regex would strip ".java"
     * out of "A.java\n" and leave a class name the prover then greps for and never finds.
     */
    private static String stripJavaSuffix(String s) {
        return s.endsWith(".java") ? s.substring(0, s.length() - ".java".length()) : s;
    }

    /** {@code row[i] || ''} — a short row reads as an absent cell rather than throwing. */
    private static String cell(List<String> row, int i) {
        return i < row.size() ? row.get(i) : "";
    }

    /** {@code isFinite(x)} — parseInt never yields ±Infinity except by overflowing a digit string. */
    private static boolean isFinite(double x) {
        return !Double.isNaN(x) && !Double.isInfinite(x);
    }

    /** Whether the body carries the key AT ALL, which is not the same as carrying a value. */
    private static boolean has(Object container, String key) {
        return container instanceof Map<?, ?> m && m.containsKey(key);
    }

    /** Column index by header name, or a failure that names what was actually in the file. */
    private static int column(List<String> head, String name) {
        int i = head.indexOf(name);
        if (i < 0) {
            // The header is quoted back because "no `line` column" on its own does not say whether the
            // export changed, the delimiter was wrong, or the quoting shifted every column by one.
            throw new IngestFailed("ingest: CSV has no `" + name + "` column; got "
                    + Json.stringify(head));
        }
        return i;
    }

    /**
     * A JS Number as the value that goes on the wire. Long while it is exactly a long, so
     * {@code "line": 7} does not become {@code "line": 7.0} in a cell a human reads.
     */
    private static Object number(double d) {
        return d == Math.rint(d) && Math.abs(d) < 9.223372036854776E18 ? (Object) (long) d : d;
    }

    /**
     * Read the report off the mounted repo.
     *
     * <p>The failure message has to name the path AND the mount, because that is the whole diagnosis: a
     * raw NoSuchFileException reads as an engine bug rather than as a missing volume, and the person
     * looking at it is an operator, not the guild.
     */
    private static String read(String path) {
        Path p;
        try {
            p = Path.of(path);
        } catch (InvalidPathException badPath) {
            // Node's existsSync swallows this and answers false; the diagnosis is the same either way
            // ("there is no such file"), and it is the one the operator can act on.
            p = null;
        }
        if (p == null || !Files.exists(p)) {
            throw new IngestFailed("ingest: no CSV at " + path + " (is the repo mounted at /data?)");
        }
        try {
            // Decoded like Node's readFileSync(p, 'utf8'): malformed bytes become U+FFFD rather than
            // throwing, which Files.readString would do. A report with one bad byte must still ingest
            // its other 355 markers.
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The commonest cause by far is a volume mounted at the DIRECTORY the report lives in
            // rather than at the file. The underlying error names neither the path nor the mount, so
            // naming the path here is what keeps this a request the operator can fix.
            throw new IngestFailed("ingest: cannot read " + path + ": " + e.getMessage(), e);
        }
    }
}
