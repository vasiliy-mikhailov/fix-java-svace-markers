package tech.mikhailov.fsm.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.Json;

/**
 * {@code doRunTest} — clone, write the test, build RED, apply the fix, build GREEN.
 *
 * <p>WHAT THE REPLY MEANS, because the engine's whole state machine reads it:
 * <ul>
 *   <li>{@code red_reproduced} — the test RAN and FAILED. A build error or a style failure is NOT a
 *       reproduction: nothing ran, so nothing was established about the code.</li>
 *   <li>{@code green_passed} — the test RAN and PASSED after the edits, on the JDK the red run resolved
 *       to.</li>
 *   <li>{@code ok: false} — the runner could not do its job at all (a clone that failed, a JDK it does
 *       not have, a crash). Still a 200: {@code RecordOutcome} turns it into {@code infra_error} with
 *       the reason recorded, and a thrown exception would strand that text.</li>
 * </ul>
 *
 * <p>The field names and the field ORDER are the JS object's, because two services read this reply and
 * one of them — n8n's shim — passes it through untouched into a Data Table row a human diffs.
 */
final class Prove {

    private final Workspace workspace;
    private final Proc.Exec exec;

    /**
     * The Maven settings file this deployment's mirror was written to, or null for Central.
     *
     * <p>Held rather than read from the environment at each build, because it is decided ONCE on the way
     * up — see {@link MavenSettings}. A prove that re-read it would be a prove whose repository could
     * change halfway through a run.
     */
    private final Path mavenSettings;

    Prove(Workspace workspace, Proc.Exec exec) {
        this(workspace, exec, null);
    }

    Prove(Workspace workspace, Proc.Exec exec, Path mavenSettings) {
        this.workspace = workspace;
        this.exec = exec;
        this.mavenSettings = mavenSettings;
    }

    /** A build plus the JDK it settled on, which may not be the one it was asked for. */
    private record Built(int code, String out, String jdk) {
    }

    /**
     * Run one prove.
     *
     * <p>Never called concurrently: {@link RunnerServer} serialises it, because there is ONE cached
     * workspace per repository and two proves would patch each other's tree.
     */
    Map<String, Object> runTest(Object body) {
        String repo = Text.field(body, "repo");
        String branch = Text.orDefault(Json.get(body, "branch"), "main");
        String jdk = Text.orDefault(Json.get(body, "jdk"), "17");
        String module = Js.truthy(Json.get(body, "module"))
                ? Js.string(Json.get(body, "module")) : null;
        String buildTool = Text.orDefault(Json.get(body, "build"), "auto");
        String testClass = requiredName(body, "test_class");
        String testPath = requiredName(body, "test_path");
        String testCode = requiredText(body, "test_code");

        if (!Build.JDKS.contains(jdk)) {
            return failure("unsupported jdk " + jdk);
        }
        // EVERY VALUE THAT LEAVES THIS JVM AS BYTES, checked once and before the clone.
        //
        // `test_path` is the sharp one: `ws.resolve(testPath)` used to throw InvalidPathException
        // straight out of runTest, RunnerServer's catch-all turned the stack trace into {"ok": false},
        // and RecordOutcome recorded an infra_error and RETRIED — forever, because the retry resolves
        // the same path in the same JVM.
        //
        // The other four are worse in a quieter way. A command line is encoded with the SAME charset and
        // ProcessImpl REPLACES what it cannot spell instead of failing: `-Dtest=a.Café` reaches Maven as
        // `-Dtest=a.Caf?`, which matches no test, and the marker comes back "the test did not run" —
        // indistinguishable from a defect that could not be reproduced. `repo` and `branch` go to
        // `git clone` the same way, and `module` to `-pl`.
        //
        // All five are unreachable when the encoding is UTF-8, which the image guarantees; see
        // PathEncoding, and see Runner for the warning printed by a process that did not get a locale.
        String unspellable = firstUnspellable("repo", repo, "branch", branch, "module", module,
                "test_class", testClass, "test_path", testPath);
        if (unspellable != null) {
            return failure(PathEncoding.refusal(unspellable));
        }

        Workspace.Prepared prep = workspace.prepareWs(repo, branch);
        if (!prep.ok()) {
            return failure(prep.error());
        }
        Path ws = prep.ws();

        try {
            // 1) write the test. No fixTarget check here, deliberately: the test path is the ENGINE's
            // and the whole point of the run is to put a file under src/test/, which fixTarget refuses.
            //
            // The leading slashes are stripped to match `path.join(ws, testPath)`, which treats its
            // second argument as relative however it is spelled. Path.resolve does NOT — an absolute
            // "/src/test/…/T.java" would be written to the filesystem ROOT, where it would fail on
            // permissions in the container and succeed on a developer's machine.
            Path testFile = ws.resolve(Workspace.stripLeadingSlashes(testPath)).normalize();
            Files.createDirectories(testFile.getParent());
            Text.write(testFile, testCode);

            // 2) RED — the test must actually RUN and FAIL.
            double redStarted = System.currentTimeMillis() / 1000.0;
            Built red = build(ws, jdk, module, buildTool, testClass);
            jdk = red.jdk();
            Build.Summary redSummary = Build.outcome(red.out(), ws, redStarted);
            boolean redReproduced = redSummary.testExecuted()
                    && redSummary.failures() + redSummary.errors() > 0;

            // 3) apply the fix
            List<String> applied = new ArrayList<>();
            List<String> editErrors = new ArrayList<>();
            for (Object edit : editsOf(body)) {
                applyOne(ws, testPath, edit, applied, editErrors);
            }

            // 4) GREEN — the test must RUN and PASS, on the JDK the red run resolved to
            double greenStarted = System.currentTimeMillis() / 1000.0;
            Built green = build(ws, jdk, module, buildTool, testClass);
            jdk = green.jdk();
            Build.Summary greenSummary = Build.outcome(green.out(), ws, greenStarted);
            boolean greenPassed = greenSummary.testExecuted()
                    && greenSummary.failures() + greenSummary.errors() == 0
                    && !greenSummary.compileError()
                    // A Gradle build can exit non-zero for reasons that are not the test (a report task,
                    // a daemon warning), so its own XML is allowed to overrule the exit status. Maven's
                    // exit status is trusted because a green surefire run means a zero exit.
                    && (green.code() == 0 || "junit-xml".equals(greenSummary.source()));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("red_reproduced", redReproduced);
            out.put("green_passed", greenPassed);
            out.put("proven", redReproduced && greenPassed);
            out.put("jdk", jdk);                      // the JDK actually used, auto-detected if wrong
            out.put("applied_files", applied);
            out.put("edit_errors", editErrors);
            out.put("red_summary", redSummary.toMap());
            out.put("green_summary", greenSummary.toMap());
            out.put("red_output", Text.tail(red.out()));
            out.put("green_output", Text.tail(green.out()));
            return out;
        } catch (IOException e) {
            // Writing the test, or reading a file an edit names. The JS let this abort the whole prove
            // too, which the engine records as infra and retries; see applyOne for the one case where
            // that is arguably wrong and is kept anyway.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One build, with a single retry onto the JDK the build says it needs.
     *
     * <p>The retry is what stops a marker being recorded as unreproducible on a JDK it was never going
     * to compile under — WebGoat needs 25 and asks for it by failing.
     */
    private Built build(Path ws, String jdk, String module, String buildTool, String testClass) {
        Build.Command command = Build.buildCmd(ws, jdk, module, buildTool, testClass, mavenSettings);
        Proc.Result r = exec.run(command.cmd(), ws, command.env(), Proc.DEFAULT_TIMEOUT_MS);
        String want = Build.requiredJdk(r.out(), jdk);
        if (want == null) {
            return new Built(r.code(), r.out(), jdk);
        }
        Build.Command retry = Build.buildCmd(ws, want, module, buildTool, testClass, mavenSettings);
        Proc.Result second = exec.run(retry.cmd(), ws, retry.env(), Proc.DEFAULT_TIMEOUT_MS);
        // Only ONE retry, and the second run's output replaces the first: a module that demands a third
        // JDK is a project misconfiguration, and looping would spend an hour of the shared workspace
        // discovering it.
        return new Built(second.code(), second.out(), want);
    }

    /**
     * Apply one edit, recording either how it applied or why it did not.
     *
     * <p>{@code applied_files} records HOW: a fix that only matched after normalising whitespace is
     * still a real fix, but the caller has to be able to see that the recorded diff is not a
     * byte-for-byte quote of the file.
     *
     * <p>ONE INHERITED DEFECT, KEPT ON PURPOSE. An edit whose {@code path} resolves to something that is
     * not a readable file — a directory, most easily an edit with no path at all, which resolves to the
     * workspace root — aborts the ENTIRE prove rather than being recorded in {@code edit_errors}. The JS
     * did exactly this ({@code readFileSync} on a directory throws EISDIR), and the engine turns the
     * resulting {@code ok: false} into a retryable infra error. Reporting it per-edit instead would be a
     * better answer and a DIFFERENT one: the marker would settle as "the fix did not work" rather than
     * "we could not apply it", which is a verdict about the code and not about the request.
     */
    private void applyOne(Path ws, String testPath, Object edit, List<String> applied,
                          List<String> editErrors) throws IOException {
        String path = Js.orEmptyString(Json.get(edit, "path"));
        // The MESSAGE uses interpolation's coercion and the LOOKUP uses `p || ''`; the JS mixed the two
        // on adjacent lines, so an edit with no path is reported as "undefined" and resolved as "".
        String reported = Text.field(edit, "path");

        Edit.Target target = Edit.fixTarget(ws, path, testPath);
        if (!target.ok()) {
            editErrors.add(reported + ": " + target.error());
            return;
        }
        if (!Files.exists(target.path())) {
            editErrors.add(reported + ": file not found");
            return;
        }
        // old_str is read through fieldOrAbsent and new_str through field, and the ASYMMETRY IS THE POINT.
        //
        // old_str is a SEARCH NEEDLE. Coerced, an absent one becomes the string "undefined" and this line
        // asks applyEdit to find those nine characters in the source — a word Java files contain for
        // ordinary reasons. Exactly one occurrence and the edit applies somewhere nobody aimed it, with a
        // normal red/green reply for a patch nobody intended: a wrong answer wearing the shape of a right
        // one. `undefined` alone is not enough information to refuse it either, because an edit whose
        // old_str really IS that word is an honest quote of the file, so the absence has to be carried as
        // null all the way to the refusal.
        //
        // new_str is INSERTED, never searched for, so the same coercion is harmless AND faithful:
        // `cur.replace(old, undefined)` spelled the replacement "undefined" and wrote it into the file,
        // which is what this service has always done. It lands exactly where the edit aimed, `undefined`
        // is not a Java expression, and the green build therefore fails to compile — a bad patch that
        // announces itself in the diff and in green_passed, which is the opposite of the old_str case.
        // An explicit `"new_str": null` still splices the word "null", which is the JS's other spelling.
        Edit.Applied result = Edit.applyEdit(Text.read(target.path()),
                Text.fieldOrAbsent(edit, "old_str"), Text.field(edit, "new_str"));
        if (!result.ok()) {
            editErrors.add(reported + ": " + result.error());
            return;
        }
        Text.write(target.path(), result.text());
        applied.add(reported + (result.note().isEmpty() ? "" : " (" + result.note() + ")"));
    }

    /**
     * {@code body.fix_edits || []}.
     *
     * <p>A truthy non-array is REFUSED rather than ignored. The JS did {@code for (const ed of …)} over
     * it, which iterates a string CHARACTER BY CHARACTER and throws on anything else — either way the
     * prove ended as {@code ok: false}. Treating it as "no edits" would instead run a GREEN build on
     * unpatched code and report {@code green_passed: false}, which is a verdict on the fix rather than a
     * complaint about the request.
     */
    private static List<?> editsOf(Object body) {
        Object edits = Json.get(body, "fix_edits");
        if (!Js.truthy(edits)) {
            return List.of();
        }
        if (edits instanceof List<?> list) {
            return list;
        }
        throw new IllegalArgumentException("fix_edits must be an array, not " + Text.string(edits));
    }

    /**
     * A string field the prove cannot proceed without.
     *
     * <p>WHY IT IS REFUSED RATHER THAN COERCED — this is the port's one deliberate change of shape, and
     * it is here because the natural coercion is DANGEROUS. The JS interpolated whatever it was given, so
     * an absent {@code test_class} produced {@code -Dtest=undefined}, which matches no test. The
     * equivalent Java coercion produces an empty {@code -Dtest=} — and that runs the repository's ENTIRE
     * suite. On a project with failing tests of its own, the red build would then report failures and the
     * marker would come back {@code red_reproduced: true} having never been tested: a FABRICATED
     * reproduction, which is the one answer this service must never give.
     *
     * <p>Refusing lands the caller in the same place the JS did, because {@code writeFileSync} threw a
     * TypeError for a missing {@code test_path} too: {@code {"ok": false, "error": …}}, which
     * {@code RecordOutcome} records as infra and retries.
     */
    private static String requiredText(Object body, String key) {
        Object value = Json.get(body, key);
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(key + " must be a string, not " + Text.field(body, key));
    }

    /**
     * …and one whose emptiness would change what gets built. An empty {@code test_code} is allowed: the
     * JS wrote the empty file, the build then found no test, and "the test did not run" is a truthful
     * answer that the engine already knows how to read.
     */
    private static String requiredName(Object body, String key) {
        String value = requiredText(body, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
        return value;
    }

    /**
     * The name of the first field this JVM cannot spell, or null.
     *
     * <p>In request order rather than "whichever is checked first happens to win", so the reply names
     * the same field every time it is asked. A null value is spellable — an absent {@code module} is
     * not a request this can complain about.
     */
    private static String firstUnspellable(String... fieldsAndValues) {
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            if (!PathEncoding.spells(fieldsAndValues[i + 1])) {
                return fieldsAndValues[i];
            }
        }
        return null;
    }

    /** {@code {ok: false, error: …}} — a 200 reply, because it is an answer about this marker. */
    static Map<String, Object> failure(String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.FALSE);
        out.put("error", error);
        return out;
    }
}
