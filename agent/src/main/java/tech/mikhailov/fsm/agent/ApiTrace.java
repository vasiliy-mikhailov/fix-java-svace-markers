package tech.mikhailov.fsm.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * THE WHOLE TRACE AS JSON, FOR THE ZONE THAT REPLACES {@code /trace}.
 *
 * <p>Everything that happened to every marker — prompts, reasoning, tool calls, builds and
 * settlements — merged into one story, so a reader watching a run in flight can see what it is
 * doing right now rather than why one marker settled the way it did. It follows the shape
 * {@link Api} set: hand-built JSON, every string through {@link Settlement#escape}, every read
 * through {@link Dashboard#lines}.
 *
 * <p>FACTS, NOT SENTENCES. {@code at} is epoch millis and not "3h 12m"; {@code phase} is what was
 * recorded and not the upper-cased version the page prints; {@code passed} and {@code infra} are
 * the two booleans and not the one word the page derives from them; {@code marker} is the whole key
 * and not the tail the link text shows. Each of those is a decision the page takes at render time,
 * and a decision baked into JSON is one the client cannot revisit.
 *
 * <p>Prompts and replies go out IN FULL, exactly as the page renders them. The reason is in
 * {@link Trace}: prompt tuning replays a recorded (prompt, reply) pair and scores the reply, so a
 * trace that abbreviates either one is a trace nothing can be trained from. The page truncates for
 * display; so can the client.
 *
 * <p>WHICH FIELDS CAN BE NULL, and why the line is drawn where it is. A field that identifies the
 * event — {@code marker}, {@code agent}, {@code at}, and the estimator's {@code minutes} — is null
 * when the record genuinely does not have it: most kinds have no agent at all, and an empty string
 * there would name an agent whose name is blank. A field that IS the kind's payload —
 * {@code prompt}, {@code reply}, {@code text}, {@code result}, {@code summary} … — is always a
 * string, because empty means empty: an agent that answers with tool calls and no content said
 * nothing, which is a judgement and not a gap.
 */
final class ApiTrace {

    private ApiTrace() {
    }

    /**
     * {@code GET /api/trace?from=N} — the run's whole record, and how much of it there is.
     *
     * <p>OLDEST FIRST, NEWEST AT THE BOTTOM. The brief for this screen says "newest first" and the
     * page does the opposite: it sorts ascending and the browser inserts arriving events with
     * {@code beforeend}. Appending is the whole point — replacing the list, or reversing it, closes
     * every fold the reader opened and throws away their scroll position. This sends the order the
     * page produces, not the order the brief describes.
     *
     * <p>A RESPONSE IS ONLY WHAT IS NEW. The client holds everything before {@code from} with
     * whatever it opened still open, so this sends the tail and a {@code cursor} counting the
     * whole file. A {@code from} past the end is an empty tail rather than an error: the results
     * directory can be replaced under a page that is still polling, and the client sees that in
     * {@code cursor} without this having to fail.
     *
     * @param from how many events the client already holds, as its last {@code cursor} reported
     */
    /**
     * How many events one request may carry, when the caller does not say.
     *
     * <p>Because the honest number was measured: this run's trace is 61 MB as JSON and the HTML page
     * it replaces is 84 MB. Neither is a page load — that is a document larger than the browser will
     * hold, sent so a reader can look at the last twenty things that happened. The page got away with
     * it by being the only option; the port is the moment to stop.
     *
     * <p>A window, not a truncation: `cursor` is always the true total, and a caller that wants the
     * next page asks for it. A response that quietly held less than it claimed would be the same bug
     * in a smaller document.
     */
    static final int WINDOW = 500;

    static String trace(Path trace, Path settlements, int from) {
        return trace(trace, settlements, from, WINDOW);
    }

    static String trace(Path trace, Path settlements, int from, int limit) {
        List<String> all = new ArrayList<>(Dashboard.lines(trace));
        // Concatenating four workers' files gives four ordered runs, not one. The stamp is what
        // makes them a single story again. A missing or malformed `at` sorts as 0 and lands at the
        // very top of the run — the page's behaviour, ported rather than fixed, because the index
        // below has to mean the same thing on both.
        all.sort((a, b) -> Long.compare(num(Dashboard.field(a, "at")),
                num(Dashboard.field(b, "at"))));

        // Read once for the whole document: a claim is a directory listing, and one per settled
        // event would stat the claims directory a thousand times to answer one request.
        Set<String> claimed = Run.claims(settlements);

        // THE WINDOW, AFTER the sort and against the true total, so `cursor` still means what it
        // meant and a caller can page from it.
        int total = all.size();
        int start = Math.max(0, Math.min(from, total));
        int end = limit <= 0 ? total : Math.min(total, start + limit);
        List<String> window = all.subList(start, end);

        StringBuilder b = new StringBuilder("{\"cursor\":").append(total);
        b.append(",\"from\":").append(start).append(",\"returned\":").append(window.size());
        b.append(",\"more\":").append(end < total);
        b.append(",\"openFindings\":").append(open(settlements.resolveSibling("overwatch.jsonl")));
        b.append(",\"events\":[");
        boolean first = true;
        // The absolute index is kept, not the window's offset: it is the event's name for the whole
        // run, and a client paging backwards must get the same number for the same event.
        for (int i = start; i < end; i++) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append(event(all.get(i), i, claimed));
        }
        return b.append("]}").toString();
    }

    /**
     * ONE EVENT, with the fields its kind actually carries.
     *
     * <p>An UNRECOGNISED KIND keeps its common fields and gets no others, which is what the page
     * does with it — it renders the kind's own name and nothing else. That is the right behaviour
     * for an append-only record that will grow kinds: refusing the line, or the document, would
     * make every reader of an old client blind to a whole run the day a new kind is written.
     *
     * @param index the event's position in THIS ordering — what {@code from} counts, and the number
     *              the feedback form posts as {@code event}. THE INDEX IS NOT AN IDENTITY: the same
     *              physical event has a different number on the marker page, which numbers within
     *              one marker, and feedback.jsonl already holds both kinds of number with nothing
     *              to tell them apart. Use it to point at an event in this response, not to
     *              remember one.
     */
    private static String event(String e, int index, Set<String> claimed) {
        String kind = Dashboard.field(e, "kind");
        String marker = Dashboard.field(e, "marker");
        StringBuilder b = new StringBuilder("{\"index\":").append(index);
        b.append(",\"at\":").append(number(Dashboard.field(e, "at")));
        b.append(",\"kind\":").append(quote(kind));
        // The whole key. The page's link text is everything after the last SLASH — for
        // `repo|src/main/java/Foo.java|82|CHECKER` that is `Foo.java|82|CHECKER`, which is neither
        // the file name nor the marker slug. That truncation is display, and it belongs on the side
        // that also owns the marker page's title, so the link and its destination keep matching.
        b.append(",\"marker\":").append(orNull(marker));
        b.append(",\"agent\":").append(orNull(Dashboard.field(e, "agent")));
        switch (kind) {
            case "asked" -> b.append(",\"prompt\":").append(quote(Dashboard.field(e, "prompt")))
                    .append(",\"reply\":").append(quote(Dashboard.field(e, "reply")));
            case "asking" -> b.append(",\"standing\":")
                    .append(quote(Dashboard.field(e, "standing")))
                    .append(",\"task\":").append(quote(Dashboard.field(e, "task")));
            // WHAT WENT DOWN THE WIRE. A kind with no case here reaches the page carrying nothing
            // but its name, which is how `sent` rows drew an empty fold on every lane.
            // `system` is a real kind and easy to miss — the run-level prompt, which reached this
            // page with its name and nothing else.
            case "system" -> b.append(",\"prompt\":").append(quote(Dashboard.field(e, "prompt")));
            case "sent" -> b.append(",\"role\":").append(quote(Dashboard.field(e, "role")))
                    .append(",\"text\":").append(quote(Dashboard.field(e, "text")));
            case "thought" -> b.append(",\"text\":").append(quote(Dashboard.field(e, "text")));
            // `arguments` is JSON that was itself a JSON string, so field() has already peeled one
            // level and it arrives as ordinary text with real newlines. It is text to display, not
            // JSON to re-parse.
            case "tool" -> b.append(",\"tool\":").append(quote(Dashboard.field(e, "tool")))
                    .append(",\"arguments\":").append(quote(Dashboard.field(e, "arguments")))
                    .append(",\"result\":").append(quote(Dashboard.field(e, "result")));
            // BOTH BOOLEANS, NOT THE WORD. `infra` wins over `passed` — a build that never compiled
            // cannot have failed the test, and "failed" would read as evidence about the defect
            // rather than about the machine. That is a judgement, and it belongs where the pill is
            // drawn; a single label here would move it back into Java. `phase` goes out as
            // recorded, because upper-casing it is typography.
            case "built" -> b.append(",\"phase\":").append(quote(Dashboard.field(e, "phase")))
                    .append(",\"passed\":").append(flag(Dashboard.field(e, "passed")))
                    .append(",\"infra\":").append(flag(Dashboard.field(e, "infra")))
                    .append(",\"summary\":").append(quote(Dashboard.field(e, "summary")));
            case "progress" -> b.append(",\"note\":").append(quote(Dashboard.field(e, "note")));
            // THE STATE ON SCREEN IS NOT THE STATE IN THE FILE. Resolved through Run rather than
            // sent raw: `proving` with no claim directory behind it is `interrupted`, and a client
            // shown the raw word draws a pulsing blue pill for a container that died an hour ago.
            // The rule lives in Run.resolve so the page and the API cannot disagree about it. For
            // every disposition a finished prove writes this is the identity — which is the point,
            // it costs nothing to be right about the one case that is not.
            case "settled" -> b.append(",\"state\":")
                    .append(quote(Run.resolve(Dashboard.field(e, "state"),
                            claimed.contains(Supervisor.slug(marker)))))
                    .append(",\"because\":").append(quote(Dashboard.field(e, "because")));
            // A NUMBER OR NULL, NEVER 0. Prove re-asks the estimator for `minutes: N` and, when the
            // second answer still has no figure in it, records `minutes: unknown` — so a blank here
            // is a real gap. Zero would say a person would have spent no time on this marker, which
            // is a different claim, a false one, and the one that would then be summed over a run.
            case "priced" -> b.append(",\"minutes\":").append(number(Dashboard.field(e, "minutes")))
                    .append(",\"itemisation\":")
                    .append(quote(Dashboard.field(e, "itemisation")));
            // WITH THE STACK, which the page does not show and nothing else serves.
            // "NullPointerException: null" names no line and no cause, and a record that cannot
            // locate its own failure sends a reader to a container log that does not have it.
            case "failed" -> b.append(",\"cause\":").append(quote(Dashboard.field(e, "cause")))
                    .append(",\"stack\":").append(quote(Dashboard.field(e, "stack")));
            default -> { }
        }
        return b.append('}').toString();
    }

    /**
     * A recorded boolean, as a JSON boolean.
     *
     * <p>The trace writes these quoted and {@link Settlement} writes them unquoted;
     * {@link Dashboard#field} reads both, which is the fix for the bug that had {@code red_verified}
     * reading empty for every marker that had genuinely gone red, and the semaphore never lighting.
     */
    private static String flag(String recorded) {
        return "true".equals(recorded) ? "true" : "false";
    }

    /**
     * A recorded number, or null when there is not one there.
     *
     * <p>Null rather than 0 in both places it is used, for the same reason each time: 0 is a value
     * a reader will believe. An unreadable stamp is not a moment in 1970, and an estimate the
     * estimator never made is not a marker that would have cost a person nothing.
     */
    private static String number(String recorded) {
        try {
            return String.valueOf(Long.parseLong(recorded == null ? "" : recorded.strip()));
        } catch (NumberFormatException notANumber) {
            return "null";
        }
    }

    /**
     * Findings the critic has not dismissed, for the badge this screen's header carries.
     *
     * <p>The number the page's own badge shows, from {@link Dashboard#holding}, because it is the
     * SAME badge in the same corner of the same header: a count that changes as the reader moves
     * between the page and the zone is a count neither of them can be read for. `refuted` is
     * excluded — a count that only ever grows is a count nobody reads twice.
     *
     * <p>UNJUDGED IS A WORD HERE, NOT A BLANK, and that distinction is the whole of this method.
     * {@link Overwatch} writes the verdict as one of `holds`, `refuted` or the literal `unjudged`
     * and never leaves it empty, so a test that recognises "nobody reached it" only as a blank
     * counts none of them and the badge reads lower than the truth — two of four on the file this
     * was checked against. That is precisely the failure the badge was put in the header to
     * prevent: findings reported, judged and left unread for ten hours while somebody rediscovered
     * the same faults by hand. Both spellings are accepted because the record has two ways of
     * saying it, and a critic that could not be reached must not be able to suppress a warning by
     * saying nothing.
     *
     * <p>The {@code "finding"} guard is what keeps this counting findings: overwatch.jsonl is
     * append-only and the day something writes another kind of row into it, a scan that counted
     * every line would inflate the badge instead.
     */
    private static int open(Path findings) {
        int n = 0;
        for (String line : Dashboard.lines(findings)) {
            String verdict = Dashboard.field(line, "verdict");
            if (line.contains("\"finding\"") && (verdict.isBlank() || verdict.equals("holds")
                    || verdict.equals("unjudged"))) {
                n++;
            }
        }
        return n;
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** A JSON string, escaped by the one escaper this program has. */
    private static String quote(String s) {
        return "\"" + Settlement.escape(s == null ? "" : s) + "\"";
    }

    /** A string the record may simply not have. Absent is null, and the client decides what to
     *  draw — an empty string here would be a value, and a wrong one. */
    private static String orNull(String s) {
        return s == null || s.isBlank() ? "null" : quote(s);
    }
}
