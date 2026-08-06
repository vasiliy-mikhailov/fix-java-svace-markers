package tech.mikhailov.fsm.runner;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import tech.mikhailov.fsm.lib.SourceText;

/**
 * Applying the fixer's search/replace edits, and refusing the ones that would game the proof —
 * {@code lib/edit.js}.
 *
 * <p>Two separate jobs live here because both are safety rules, not conveniences:
 *
 * <ul>
 *   <li>{@link #fixTarget} resolves a path inside the workspace and REFUSES anything under a test tree
 *       or the proof test itself. Enforced server-side so the fixer/test independence never depends on
 *       the caller getting it right.</li>
 *   <li>{@link #applyEdit} takes the exact unique match first, then falls back to a
 *       whitespace-insensitive one so a correct fix is not thrown away over line-wrapping.</li>
 * </ul>
 */
final class Edit {

    private Edit() {
    }

    /** {@code {text, note}} or {@code {error}} — one shape or the other, never both. */
    record Applied(String text, String note, String error) {

        static Applied replaced(String text, String note) {
            return new Applied(text, note, null);
        }

        static Applied failed(String error) {
            return new Applied(null, null, error);
        }

        boolean ok() {
            return error == null;
        }
    }

    /** {@code {path}} or {@code {error}}. */
    record Target(Path path, String error) {

        static Target of(Path path) {
            return new Target(path, null);
        }

        static Target failed(String error) {
            return new Target(null, error);
        }

        boolean ok() {
            return error == null;
        }
    }

    /**
     * A string with every whitespace run collapsed to one space, and the map back to the original.
     *
     * @param norm  the collapsed text
     * @param index {@code index[i]} is the offset in the source that produced {@code norm[i]}; for a
     *              whitespace run it is the offset of the FIRST character of that run
     */
    record Normalized(String norm, int[] index) {
    }

    /**
     * Collapse every whitespace run to one space, remembering where each character came from.
     *
     * <p>WHITESPACE MEANS {@link SourceText#isSpace} AND NOT JAVA'S {@code \s}. That set includes
     * U+00A0 and the BOM; Java's {@code \s} is
     * {@code [ \t\n\x0B\f\r]} and would leave a no-break space standing as a normal character. A model
     * that indents with one — or a file saved by an editor that did — would then fail to match at all,
     * which is the one outcome this whole fallback exists to prevent. (The set is not "JavaScript's" —
     * {@link SourceText} states the reason it actually has.)
     */
    static Normalized wsNorm(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int[] index = new int[s.length()];
        boolean previousWasSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (SourceText.isSpace(ch)) {
                if (!previousWasSpace) {
                    index[out.length()] = i;
                    out.append(' ');
                    previousWasSpace = true;
                }
            } else {
                index[out.length()] = i;
                out.append(ch);
                previousWasSpace = false;
            }
        }
        return new Normalized(out.toString(), index);
    }

    /**
     * Apply one edit.
     *
     * <p>The exact unique match is the contract — it is what makes an edit unambiguous. The fallback
     * exists because WebGoat is google-java-format'ed, so a wrapped expression puts the operator at the
     * START of each continuation line while the model reliably quotes it back at the END of the previous
     * one. The tokens are identical and only the wrapping differs, so collapsing whitespace makes them
     * agree. Without it a correct parameterised-query fix for a genuinely reproduced bug was discarded
     * as "old_str not found" — the defect proven and the patch thrown away.
     *
     * <p>The fallback still demands exactly ONE candidate, so it can never choose between them. That
     * refusal is the reason it is safe to have at all: guessing which of two near-identical spans the
     * model meant would let a "proven" fix land somewhere nobody reviewed.
     *
     * <p>ONE CATALOGUED DIVERGENCE. {@code new_str} is inserted VERBATIM. A regex-replace on the exact
     * path expands {@code $&}, {@code $$}, {@code $`} and {@code $'} inside the replacement — so a fix
     * containing {@code $&} is silently corrupted, and only on that path: the whitespace path below
     * concatenates and does not expand. The
     * port keeps the behaviour the two paths agree on, which is also the only one a fixer could have
     * meant. Java source with a {@code $} in it is not exotic (nested class names, shell templates).
     *
     * @param old the text to find, or {@code null} for a request that carried no {@code old_str} at all —
     *            see the refusal below, and {@link Text#fieldOrAbsent} for why the caller has to say which
     */
    static Applied applyEdit(String cur, String old, String replacement) {
        // AN ABSENT old_str IS NOT A SEARCH, and the distinction is only expressible as null: the string
        // "undefined" is a legitimate needle (Java source says that word in comments, literals and
        // identifiers), so a caller that coerces the absence into it asks this function to go and find
        // one — and where the file contains it exactly once, the edit applies where nobody aimed it and
        // the run reports an ordinary build result for a patch nobody intended. Refused PER EDIT, so
        // the request's other edits survive — the way a path Java cannot spell already does.
        //
        // Refused HERE rather than only at the call site because this is the function that does the
        // searching: a needle the caller never had must not be searchable, whoever calls next.
        if (old == null) {
            return Applied.failed("old_str is missing");
        }
        int exact = splitCount(cur, old);
        if (exact == 1) {
            return Applied.replaced(replaceFirst(cur, old, replacement), "");
        }
        if (exact > 1) {
            return Applied.failed("old_str matches " + exact + "x (not unique)");
        }

        Normalized current = wsNorm(cur);
        String needle = SourceText.trim(wsNorm(old).norm());
        if (needle.isEmpty()) {
            return Applied.failed("old_str is empty");
        }
        int hits = splitCount(current.norm(), needle);
        if (hits == 0) {
            return Applied.failed("old_str not found");
        }
        if (hits > 1) {
            return Applied.failed("old_str not found exactly, and the whitespace-insensitive match "
                    + "is ambiguous (" + hits + " candidates)");
        }
        int at = current.norm().indexOf(needle);
        int start = current.index()[at];
        int end = current.index()[at + needle.length() - 1] + 1;
        // The span starts at the first non-whitespace character — `needle` is trimmed — so the file's
        // own indentation survives and the replacement is trimmed to suit. Trimmed with SourceText's
        // set, because String.strip() would also eat U+001C..U+001F, which are data.
        return Applied.replaced(cur.substring(0, start) + SourceText.trim(replacement) + cur.substring(end),
                "matched ignoring whitespace/line-wrapping");
    }

    /**
     * {@code s.split(sep).length - 1} — how an occurrence is counted here.
     *
     * <p>Spelled out because the empty separator is not the obvious case and it decides which error a
     * caller gets: {@code "abc".split("")} is three pieces (so two "occurrences") while
     * {@code "".split("")} is NO pieces (so minus one). Both land on "old_str is empty" or "not
     * unique", never on a silent insertion — except for a two-character file, where this counts one
     * and prepends the replacement. Left as it is rather than tidied: the tidy version is a second
     * behaviour that no recorded case covers.
     *
     * <p>THE TWO-CHARACTER CASE IS A REAL DEFECT AND IS NOT FIXED HERE. {@code applyEdit("{}", "",
     * "HACKED")} answers {@code ok=true, text="HACKED{}"} — a patch nobody aimed, which is the outcome
     * the {@code old == null} refusal exists to prevent. The one-line guard that closes it
     * ({@code if (old.isEmpty())} above the call in {@link #applyEdit}) moves 554 recorded cases of
     * the differential catalogue and takes two of its invariants off zero violations, so it is a
     * deliberate re-record and not a drive-by fix. The empty branch below is also NOT deletable:
     * without it the loop never advances, because {@code indexOf("", from)} answers {@code from} for
     * ever.
     */
    static int splitCount(String haystack, String needle) {
        if (needle.isEmpty()) {
            return haystack.isEmpty() ? -1 : haystack.length() - 1;
        }
        int count = 0;
        int from = 0;
        int at;
        while ((at = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = at + needle.length();
        }
        return count;
    }

    /** {@code cur.replace(old, replacement)} for a STRING search: the first occurrence, literally. */
    private static String replaceFirst(String cur, String old, String replacement) {
        int at = cur.indexOf(old);
        return cur.substring(0, at) + replacement + cur.substring(at + old.length());
    }

    /**
     * Resolve an edit path inside {@code ws}. Refuses workspace escapes and ANY edit under a test tree.
     *
     * <p>This is the invariant that stops a fix grading its own exam: the fixer was handed a test it did
     * not write, and if it could weaken that test a green run would prove nothing. It is enforced here,
     * on the server, rather than in the caller that builds the edit list — a check the requester can
     * skip is not a check.
     *
     * @param testPath the proof test's path as the request spelled it, refused by exact match on top of
     *                 the {@code src/test/} rule, because a project can put its tests anywhere
     */
    static Target fixTarget(Path ws, String p, String testPath) {
        Path base = ws.toAbsolutePath().normalize();
        // A path this JVM cannot SPELL, which is not the same failure as a path that is not there and
        // must not be reported as one. `Path.of` would throw below and the catch would answer "file not
        // found" — for a file that exists, on a runner whose process was started without a UTF-8 locale.
        // The fixer then sees its correct patch refused with a reason that sends a reviewer to the
        // repository instead of to the deployment. Unreachable when the encoding is UTF-8, which is what
        // the image guarantees; see PathEncoding.
        if (!PathEncoding.spells(p)) {
            return Target.failed(PathEncoding.refusal("path"));
        }
        Path full;
        try {
            // Leading slashes are stripped, not rejected: a model that quotes an absolute-looking path
            // means the repository-relative one, and `path.resolve` would otherwise leave the workspace.
            full = base.resolve(Workspace.stripLeadingSlashes(p == null ? "" : p)).normalize();
        } catch (InvalidPathException notAFilename) {
            // A NUL in the middle of a name, which `Path.of` refuses. Throwing here would end the whole
            // prove as `ok: false`, so a model reply garbled in ONE edit would cost the marker its
            // other, good edits and be recorded as an infra failure to retry. Reported per-edit
            // therefore, and worded "file not found" — no byte sequence containing a NUL can name a file
            // on any filesystem this service clones onto, so it is both true and what the caller already
            // knows how to render.
            return Target.failed("file not found");
        }
        if (!full.startsWith(base)) {
            return Target.failed("path escapes workspace");
        }
        String rel = base.relativize(full).toString().replace(File.separatorChar, '/');
        // STRIPPED THE SAME WAY `p` IS, because the two are about to be compared for equality. `rel`
        // comes out of base.relativize and never carries a leading slash; the request's test_path may,
        // and Prove.java:116 puts it through this same helper when it WRITES the test — so an
        // absolute-looking test_path is a SUPPORTED shape, not a malformed one. Comparing the two
        // spellings raw made "/tests/Proof.java" unequal to "tests/Proof.java", and for a project whose
        // tests are not under src/test/ this arm is the only guard there is: the fixer was handed the
        // proof test and could grade its own exam.
        String tp = Workspace.stripLeadingSlashes(
                (testPath == null ? "" : testPath).replace(File.separatorChar, '/'));
        String lower = rel.toLowerCase(Locale.ROOT);
        if (lower.contains("/src/test/") || lower.startsWith("src/test/")
                || (!tp.isEmpty() && rel.equals(tp))) {
            return Target.failed("refuses to edit a test file (fixer may not touch the test)");
        }
        return Target.of(full);
    }
}
