package tech.mikhailov.fsm.orch.batch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import tech.mikhailov.fsm.nodes.ParseMarkers;

/**
 * The ingest webhook's body, as {@link JobParameters} and back — and the ONE place the two
 * vocabularies are lined up.
 *
 * <p>WHY THIS IS NOT A FEW {@code getString} CALLS IN THE TASKLET. {@link ParseMarkers} reads its body
 * with JavaScript coercions that a Java caller has to reproduce EXACTLY, and two of them turn on
 * distinctions a naive translation destroys:
 *
 * <ul>
 *   <li>{@code path_prefix} — ABSENT means "strip the WebGoat CI root"; PRESENT-BUT-EMPTY means "do
 *       not strip at all". The engine tells them apart with a {@code containsKey}, so this class must
 *       too: an absent parameter leaves the key out, and an empty one puts an empty string IN. Collapse
 *       them and a report from another CI root silently ingests 282 unopenable paths.</li>
 *   <li>{@code include_tests} — read as {@code === true}. The string {@code "false"} is truthy in
 *       JavaScript, and reading it as a request for the 74 structurally unfixable test-tree markers
 *       would put a day of prover time into work the runner refuses to do. So the parameter is parsed
 *       to a real Boolean here and the engine's strict test does the rest.</li>
 * </ul>
 *
 * <p>{@code min_severity} is passed through UNTRIMMED, deliberately: {@link ParseMarkers} looks it up
 * verbatim, so a padded value ranks -1 and filters nothing. That is the safe direction — the opposite
 * mistake drops markers the operator asked to keep — and it is the engine's decision, not this class's.
 *
 * @param csvPath      where the Svace report is, as the ORCHESTRATOR's process sees it. Null uses the
 *                     engine's default. This process reads the file itself now, so the path is
 *                     resolved in this container rather than in the engine's — one fewer mount.
 * @param repo         {@code owner/name}. Required; the engine refuses a body without one.
 * @param branch       stamped onto every row. Blank means {@code Prep prover} resolves the default
 *                     branch per marker, one GitHub call each.
 * @param pathPrefix   see above. Null = absent = the engine's WebGoat default.
 * @param includeTests null = absent = false; the engine's own strict test decides.
 * @param onlyCheckers empty = no filter. Names are trimmed inside the engine.
 * @param minSeverity  empty = no filter.
 */
public record IngestRequest(String csvPath, String repo, String branch, String pathPrefix,
                            Boolean includeTests, List<String> onlyCheckers, String minSeverity) {

    /** Job parameter names, spelled as the job is launched with them. */
    public static final String CSV_PATH = "csvPath";

    /** @see #CSV_PATH */
    public static final String REPO = "repo";

    /** @see #CSV_PATH */
    public static final String BRANCH = "branch";

    /** @see #CSV_PATH */
    public static final String PATH_PREFIX = "pathPrefix";

    /** @see #CSV_PATH */
    public static final String INCLUDE_TESTS = "includeTests";

    /** @see #CSV_PATH */
    public static final String ONLY_CHECKERS = "onlyCheckers";

    /** @see #CSV_PATH */
    public static final String MIN_SEVERITY = "minSeverity";

    /**
     * How a checker list travels through a job parameter, which holds one scalar.
     *
     * <p>Comma, because a checker name is a Svace identifier and never contains one.
     */
    public static final String CHECKER_SEPARATOR = ",";

    /** Normalised on the way in, so every later read sees the same shape. */
    public IngestRequest {
        onlyCheckers = onlyCheckers == null ? List.of() : List.copyOf(onlyCheckers);
    }

    /**
     * The parameters this job was launched with, read back.
     *
     * <p>{@code containsKey} rather than a null test for {@code pathPrefix}: the whole point of the
     * parameter is that an empty value is a DIFFERENT REQUEST from an absent one.
     */
    public static IngestRequest of(JobParameters parameters) {
        Map<String, ?> present = parameters.getParameters();
        String checkers = parameters.getString(ONLY_CHECKERS);
        return new IngestRequest(
                parameters.getString(CSV_PATH),
                parameters.getString(REPO),
                parameters.getString(BRANCH),
                present.containsKey(PATH_PREFIX) ? parameters.getString(PATH_PREFIX) : null,
                present.containsKey(INCLUDE_TESTS)
                        ? Boolean.valueOf("true".equalsIgnoreCase(parameters.getString(INCLUDE_TESTS)))
                        : null,
                split(checkers),
                parameters.getString(MIN_SEVERITY));
    }

    /**
     * The identifying half of this job's parameters.
     *
     * <p>Every field is identifying, so two ingests that differ in any of them are two JobInstances and
     * the second is not refused as "already complete". The launcher adds the timestamp that makes an
     * identical re-ingest a new instance too — re-running the same report after fixing the checker map
     * is a thing operators do.
     */
    public JobParameters toJobParameters() {
        JobParametersBuilder b = new JobParametersBuilder();
        putIfPresent(b, CSV_PATH, csvPath);
        b.addString(REPO, repo == null ? "" : repo);
        putIfPresent(b, BRANCH, branch);
        // NOT putIfPresent: "" is a real request here, and dropping it would turn "do not strip" into
        // "strip the WebGoat root".
        if (pathPrefix != null) {
            b.addString(PATH_PREFIX, pathPrefix);
        }
        if (includeTests != null) {
            b.addString(INCLUDE_TESTS, includeTests.toString());
        }
        if (!onlyCheckers.isEmpty()) {
            b.addString(ONLY_CHECKERS, String.join(CHECKER_SEPARATOR, onlyCheckers));
        }
        putIfPresent(b, MIN_SEVERITY, minSeverity);
        return b.toJobParameters();
    }

    /**
     * The body {@link ParseMarkers.Request} was written against, key for key.
     */
    public Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        // Always present, even when blank: the engine's own "`repo` is required" message is the one
        // operators grep for, and inventing a different failure here would hide it.
        body.put("repo", repo == null ? "" : repo);
        body.put("branch", branch == null ? "" : branch);
        if (csvPath != null && !csvPath.isBlank()) {
            body.put("csv_path", csvPath);
        }
        if (pathPrefix != null) {
            body.put("path_prefix", pathPrefix);
        }
        if (includeTests != null) {
            body.put("include_tests", includeTests);
        }
        if (!onlyCheckers.isEmpty()) {
            body.put("only_checkers", onlyCheckers);
        }
        if (minSeverity != null && !minSeverity.isEmpty()) {
            body.put("min_severity", minSeverity);
        }
        return body;
    }

    /** One line for the run log: what this ingest was asked to do, without the CSV in it. */
    public String describe() {
        return "repo=" + repo + " branch=" + (branch == null || branch.isBlank() ? "(resolve)" : branch)
                + " csv=" + (csvPath == null ? "(engine default)" : csvPath)
                + " prefix=" + (pathPrefix == null ? "(engine default)"
                        : pathPrefix.isEmpty() ? "(none)" : pathPrefix)
                + " tests=" + (includeTests != null && includeTests)
                + " checkers=" + (onlyCheckers.isEmpty() ? "(all)" : onlyCheckers)
                + " minSeverity=" + (minSeverity == null || minSeverity.isEmpty()
                        ? "(none)" : minSeverity);
    }

    private static void putIfPresent(JobParametersBuilder b, String key, String value) {
        if (value != null && !value.isBlank()) {
            b.addString(key, value);
        }
    }

    /** A comma-joined list back to names. Blank entries are dropped; the engine trims the rest. */
    private static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : joined.split(CHECKER_SEPARATOR, -1)) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }
}
