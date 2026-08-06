package tech.mikhailov.fsm.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust JSON extraction from an LLM reply.
 *
 * <p>The reproducer and fixer embed a whole Java file inside a JSON string, and the model wraps it in
 * prose or a ```json fence and occasionally truncates it. The naive
 * {@code indexOf('{')..lastIndexOf('}')} then grabs a stray brace out of the prose — a javadoc
 * {@code @code} reference, which is a brace followed by an at-sign, is enough — and a perfectly good
 * reply is discarded as unparseable. Every stage that reads a model reply comes through here, so when
 * it fails the pipeline
 * does not crash: it records "reply was not parseable JSON", retries, and eventually gives up on a
 * marker that was answered perfectly well.
 *
 * <p>Four passes, in falling order of confidence:
 * <ol>
 *   <li>a fenced block, LAST first — a worked example in the prose must not shadow the answer;</li>
 *   <li>the {@code '{'} whose FIRST key is one the answer should actually have;</li>
 *   <li>both ends of the reply positionally, bounded;</li>
 *   <li>a truncated tail, repaired by closing the open delimiters innermost-first.</li>
 * </ol>
 *
 * <p>WHY THIS DEPENDS ON {@link Json} AND NOT ON A LIBRARY. The algorithm is "try candidate
 * substrings and take the first that PARSES", which only distinguishes candidates because
 * {@code JSON.parse} rejects trailing content. Jackson accepts it unless FAIL_ON_TRAILING_TOKENS is
 * set, so {@code {"can_prove":true} — and here is my reasoning...} would parse to the first object
 * and the scan would stop on a candidate that must be walked past. See the class comment on
 * {@link Json}.
 *
 * <p>Both bounds below are there because a model that collapses into repetition is a real failure
 * mode, and neither the candidate scan nor the rewind may become quadratic on a reply whose prose is
 * thick with javadoc references.
 */
public final class JsonExtract {

    private JsonExtract() {
    }

    /**
     * How many {@code '{'} positions the POSITIONAL pass looks at from each end. It only bites on
     * objects that do not open with one of the caller's keys — pass 2 is what makes position
     * irrelevant for a well-formed answer.
     */
    private static final int MAX_STARTS = 40;

    /** How far the repair rewinds through closed structures before giving the reply up. */
    private static final int MAX_REWINDS = 400;

    /**
     * A fenced block. {@code json} is optional and case-insensitive because the model writes
     * {@code ```JSON}, {@code ```json} and a bare {@code ```} in roughly equal measure, and the
     * separator after it is optional too — {@code ```json{"a":1}``` } on one line is a real reply.
     * The body is lazy so the FIRST closing fence ends it; a greedy body would swallow the answer,
     * the prose after it and a second fence into one unparseable candidate.
     */
    private static final Pattern FENCE = Pattern.compile(
            "```(?:json)?[" + SourceText.SPACE_CLASS + "]*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    /**
     * Pull the answer out of a model reply.
     *
     * @param text the raw reply; null and blank are answered with null rather than an exception
     * @param keys the fields the answer should have — an object carrying NONE of them is not an
     *             answer, it is an example or a fragment of prose, and returning it would let a stage
     *             read a missing {@code can_prove} as a considered {@code false}. They are spliced
     *             into a regex UNESCAPED, which is safe only because every caller passes compile-time
     *             constants — keep it that way.
     * @return the object, or null when nothing usable is in there
     */
    public static Map<String, Object> extractJson(String text, List<String> keys) {
        String t = text == null ? "" : text;
        if (SourceText.isBlank(t)) {
            return null;
        }

        // 1. FENCES, last first.
        List<String> fences = new ArrayList<>();
        Matcher fence = FENCE.matcher(t);
        while (fence.find()) {
            fences.add(fence.group(1));
        }
        for (int i = fences.size() - 1; i >= 0; i--) {
            String body = SourceText.trim(fences.get(i));
            Object o = Json.parseOrNull(body);
            if (!Json.truthy(o)) {                       // `tryParse(x) || repair(x)`
                o = repair(body);
            }
            if (usable(o, keys)) {
                return asObject(o);
            }
        }

        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '{') {
                starts.add(i);
            }
        }

        // 2. THE OBJECT THAT OPENS WITH ONE OF OUR KEYS. This is what makes the answer findable
        // however much prose surrounds it, and it beats position in both directions: a reply that is
        // answered first and illustrated afterwards, and one illustrated first and answered after.
        Pattern keyRe = Pattern.compile("^[" + SourceText.SPACE_CLASS + "]*\"("
                + String.join("|", keys) + ")\"[" + SourceText.SPACE_CLASS + "]*:");
        // ONE matcher, moved over the reply with region() — never t.substring(p + 1). The substring
        // copied the whole tail per candidate, so a reply thick with braces cost O(braces × length):
        // measured 8.6 GB allocated on a 709 KB reply with 24,001 braces, which is the quadratic the
        // class comment above forbids. lookingAt() anchors at the region start, which is what find()
        // on the '^'-anchored pattern did to the copy; matches() would demand the whole tail.
        Matcher key = keyRe.matcher(t);
        for (int p : starts) {
            key.region(p + 1, t.length());
            if (key.lookingAt()) {
                Map<String, Object> r = tryAt(t, p, keys);
                if (r != null) {
                    // Not the end of it if this one fails: an anchored start that cannot be repaired
                    // ('{"can_prove":tru}' before a corrected answer) must fall through to the next.
                    return r;
                }
            }
        }

        // 3. POSITION, from the END first — when the model corrects itself the later object is the
        // answer, and reading the first one would report the retracted verdict.
        List<Integer> order = new ArrayList<>();
        for (int k = starts.size() - 1; k >= 0 && starts.size() - k <= MAX_STARTS; k--) {
            order.add(starts.get(k));
        }
        for (int k = 0; k < starts.size() && k < MAX_STARTS; k++) {
            if (!order.contains(starts.get(k))) {
                order.add(starts.get(k));
            }
        }
        for (int p : order) {
            Map<String, Object> r = tryAt(t, p, keys);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** Try the tail from a candidate {@code '{'}: whole first, then repaired. */
    private static Map<String, Object> tryAt(String t, int p, List<String> keys) {
        String body = t.substring(p);
        int last = body.lastIndexOf('}');
        if (last > 0) {
            Object o = Json.parseOrNull(body.substring(0, last + 1));
            if (usable(o, keys)) {
                return asObject(o);
            }
        }
        Object rep = repair(body);
        return usable(rep, keys) ? asObject(rep) : null;
    }

    /**
     * Is this an ANSWER, or merely something that parsed?
     *
     * <p>The test is {@code o && typeof o === 'object' && keys.some(k => k in o)}. An array is
     * {@code typeof 'object'} too, but no array has a key called {@code can_prove}, so requiring a
     * map is the same test. The bare-value case is the one that matters: a fence holding {@code 42}
     * parses fine, and asking {@code 'can_prove' in 42} would THROW — the stage would see a crash
     * where the reply merely needed skipping.
     */
    private static boolean usable(Object o, List<String> keys) {
        if (!(o instanceof Map<?, ?> map)) {
            return false;
        }
        for (String k : keys) {
            if (map.containsKey(k)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")                       // usable() has just proven the shape
    private static Map<String, Object> asObject(Object o) {
        return (Map<String, Object>) o;
    }

    /** Where a structure closed, and how deep the nesting was AFTER it closed. */
    private record Mark(int index, String open) {
    }

    /**
     * Repair a reply the token limit cut off.
     *
     * <p>One left-to-right walk records the delimiters still open, whether the cut landed inside a
     * string, and every point where a structure CLOSED. Then: close what is open; failing that, rewind
     * to the last structure that closed and close from there. The rewind is what recovers an answer
     * with an unfinished object hanging off the end — {@code "meta":{"retry":tru} } cannot be
     * completed by adding delimiters, but the edit list before it closed cleanly and its snapshot says
     * how deep that point was.
     */
    private static Object repair(String body) {
        StringBuilder open = new StringBuilder();
        List<Mark> marks = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (escaped) {
                // Consumed AND cleared. Left set, the rest of the reply would be skipped, the walk
                // would never see the string close, and it would "close" a string that is already
                // shut — putting a quote in the middle of the Java payload.
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                // The embedded Java file is full of braces. Counting them would leave two objects to
                // close that were never opened.
                continue;
            }
            if (ch == '{' || ch == '[') {
                open.append(ch);
            } else if (ch == '}' || ch == ']') {
                if (open.length() > 0) {                 // Array.prototype.pop() on empty is a no-op:
                    open.setLength(open.length() - 1);   // a stray extra closer is walked past
                }
                marks.add(new Mark(i, open.toString()));
            }
        }

        Object whole = close(body, open.toString(), inString);
        if (Json.truthy(whole)) {
            return whole;
        }
        for (int m = marks.size() - 1, tries = 0; m >= 0 && tries < MAX_REWINDS; m--, tries++) {
            Mark mark = marks.get(m);
            Object r = close(body.substring(0, mark.index() + 1), mark.open(), false);
            if (Json.truthy(r)) {
                return r;
            }
        }
        return null;
    }

    /**
     * Close one candidate and try it.
     *
     * @param txt      the candidate, as far as it got
     * @param open     the delimiters still open at that point, outermost first
     * @param inString whether the cut landed inside a string literal
     */
    private static Object close(String txt, String open, boolean inString) {
        StringBuilder out = new StringBuilder(txt);
        if (inString) {
            // A trailing run of backslashes of ODD length ends in a dangling escape. Kept, it would
            // escape the quote appended on the next line and leave the string open; an EVEN run is a
            // finished escape and must stay, or "C:\\" loses its backslash.
            int run = 0;
            while (run < out.length() && out.charAt(out.length() - 1 - run) == '\\') {
                run++;
            }
            if (run % 2 == 1) {
                out.setLength(out.length() - 1);
            }
            out.append('"');
        }
        // Trailing whitespace and commas go together, and as a RUN: a pretty-printed reply cut after
        // a member trails ",\n  ", and dropping only the last character would leave "{...,}", which
        // does not parse. JavaScript's \s here, not Java's — see SourceText.
        int end = out.length();
        while (end > 0 && (SourceText.isSpace(out.charAt(end - 1)) || out.charAt(end - 1) == ',')) {
            end--;
        }
        out.setLength(end);
        // A dangling colon becomes an explicit null. Left as it is, the whole reply is unparseable and
        // a verdict that did arrive is thrown away with the key that did not.
        if (end > 0 && out.charAt(end - 1) == ':') {
            out.append("null");
        }
        // Innermost first: the string, then the edit, then the list, then the reply.
        for (int k = open.length() - 1; k >= 0; k--) {
            out.append(open.charAt(k) == '{' ? '}' : ']');
        }
        return Json.parseOrNull(out.toString());
    }
}
