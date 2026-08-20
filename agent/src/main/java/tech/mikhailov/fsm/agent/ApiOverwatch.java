package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WHAT IS WRONG WITH THE PIPELINE, AS JSON — the supervisor's four views behind one endpoint.
 *
 * <p>The second of the six routes that compute in Java and emit HTML. {@link Api} set the shape and
 * this keeps it: a manual {@link StringBuilder}, {@link Settlement#escape} on every string through
 * {@link #quote}, {@link Dashboard#field} for reading. There is no JSON library here on purpose —
 * the record is flat maps of strings written by {@link JsonlTrace} and {@link Overwatch}, so reading
 * it stays a scan rather than a parse, and a malformed line costs one blank field where a parser
 * would refuse the whole document.
 *
 * <p>FACTS, NOT SENTENCES. {@code at} is epoch millis and not "3h 12m"; a finding is its whole text
 * and not its heading; a verdict is the word {@code holds}, not the CSS class {@code settled} the
 * page happens to borrow for it. The page's own vocabulary is a decision the client must be able to
 * revisit — {@code refuted} is drawn today with a class that everywhere else in this program means
 * "never ran", which is the opposite of "the critic knocked it down", and a payload that shipped the
 * class would have made that permanent.
 *
 * <p>The only resolutions here are two, and both are resolutions rather than formatting. A blank
 * verdict is reported as {@code unjudged} ({@link #verdictOf}), because the record has two ways of
 * saying nobody judged it and a client counting the other one would disagree with the badge in the
 * header. And {@code view} is the view actually served rather than the parameter as given, so an
 * unrecognised {@code ?a=} lights the tab that is rendering — on the HTML page it renders the
 * findings body with no tab lit at all.
 *
 * <p>READ FAILURES ARE EMPTY. A supervisor that has not looked yet has no findings file, and a run
 * thirty seconds old has none of these files; every one of them reads as an empty list.
 */
final class ApiOverwatch {

    private ApiOverwatch() {
    }

    /**
     * {@code GET /api/overwatch?a=<view>} — what the supervisor concluded, who let it stand, which
     * proves it cut, and the record it worked through to say any of it.
     *
     * <p>FINDINGS KEEP THEIR POSITION IN THE FILE, and the array is in file order. The page groups
     * them holds, then unjudged, then refuted — a reader with ten minutes should spend them on what
     * survived being attacked — so a position computed from display order MOVES the moment a critic
     * answers, and the feedback row written from that row is filed against a different finding.
     * {@code position} is the index the rating form already posts as its event id; grouping is the
     * client's to do and it must carry the position through it.
     *
     * <p>{@code findings} and {@code restarts} ride on every view because the header badge counting
     * what still stands is on every view — the tally itself is the findings view's subtitle, but a
     * document that sent {@code findings: []} under a badge saying three stand would be two answers
     * to one question. {@code events} is empty on the findings view: the record is eight
     * megabytes after an afternoon, and the findings screen renders none of it. It is neither capped
     * nor trimmed on the views that do render it — a document that quietly holds part of a record
     * reads as the record, and every cut this program has made to a payload for a reader's
     * convenience has later turned out to remove the field somebody needed.
     *
     * @param results the results directory; every file this reads sits directly under it
     * @param view    {@code findings|trace|<any name in Agents.WATCH>}; anything else is findings
     */
    static String overwatch(Path results, String view) {
        String served = served(view);
        List<String> found = read(results.resolve("overwatch.jsonl"));
        List<String> cut = read(results.resolve("restarts.jsonl"));
        Map<String, String> slugs = slugs(results.resolve("markers.txt"));

        int holds = 0;
        int refuted = 0;
        int unjudged = 0;
        for (String line : found) {
            switch (verdictOf(line)) {
                case "holds" -> holds++;
                case "refuted" -> refuted++;
                default -> unjudged++;
            }
        }

        StringBuilder b = new StringBuilder("{\"view\":").append(quote(served));
        // ONE NUMBER, NOT TWO READS. `open` is the header badge and the tally is the subtitle, and
        // both come from this one pass over the file: `unjudged` counts because a critic that could
        // not be reached must not be able to suppress a warning, and `refuted` does not, because a
        // count that only ever grows is a count nobody reads twice. Dashboard.holding is the same
        // rule for the page; if these ever disagree, one of them has been edited alone.
        b.append(",\"open\":").append(holds + unjudged);
        b.append(",\"tally\":{\"holds\":").append(holds)
                .append(",\"refuted\":").append(refuted)
                .append(",\"unjudged\":").append(unjudged)
                // Every restart, including the ones a person ordered — this is the count the page's
                // "N prove(s) restarted" shows, and who ordered each one is on the row itself.
                .append(",\"restarts\":").append(cut.size()).append('}');

        b.append(",\"findings\":[");
        for (int i = 0; i < found.size(); i++) {
            String line = found.get(i);
            if (i > 0) {
                b.append(',');
            }
            String text = Dashboard.field(line, "finding");
            b.append("{\"position\":").append(i);
            b.append(",\"at\":").append(number(Dashboard.field(line, "at")));
            b.append(",\"verdict\":").append(quote(verdictOf(line)));
            // THE WHOLE FINDING, not a heading and a body. The watcher is told to start each
            // finding with `## finding`, and that heading is the boundary Overwatch.split cuts on
            // rather than a title the record carries — splitting it again here would bake a parse
            // of an agent's prose into the payload, and a report written without the heading has
            // no title to cut off it.
            b.append(",\"finding\":").append(quote(text));
            // "" IS HOW AN UNJUDGED FINDING IS SHAPED, not an error and not a missing value: the
            // critic was asked and said nothing, and its absence on the page is the signal.
            b.append(",\"judgement\":").append(quote(Dashboard.field(line, "judgement")));
            b.append(",\"markers\":[").append(named(text, slugs)).append("]}");
        }
        b.append(']');

        b.append(",\"restarts\":[");
        for (int i = 0; i < cut.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            restart(b, cut.get(i));
        }
        b.append(']');

        b.append(",\"events\":[").append(served.equals("findings") ? "" : events(results, served));
        return b.append("]}").toString();
    }

    /**
     * The supervisor's record, newest first, for one agent or for all of it.
     *
     * <p>NEWEST FIRST, unlike a marker's trace. A prove is read after it settles and its story runs
     * forwards; the supervisor is read while it is running, and the question is always what it just
     * said.
     */
    private static String events(Path results, String served) {
        // A COPY, BECAUSE read() HANDS BACK AN IMMUTABLE LIST. Sorting it in place threw
        // UnsupportedOperationException out of the handler, which HttpServer answers by closing the
        // connection with no status and no log line — an empty reply in twenty milliseconds, which
        // reads exactly like a payload too big to build and is nothing of the kind.
        List<String> all = new ArrayList<>(read(results.resolve("overwatch-trace.jsonl")));
        if (!served.equals("trace")) {
            // An exact agent match, like the page: an event from any third supervisor agent is
            // reachable only under the whole record.
            all.removeIf(line -> !Dashboard.field(line, "agent").equals(served));
        }
        all.sort((x, y) -> Long.compare(num(Dashboard.field(y, "at")),
                num(Dashboard.field(x, "at"))));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            event(b, all.get(i), i);
        }
        return b.toString();
    }

    /**
     * One trace event: the common fields, then whatever its kind was written with.
     *
     * <p>THE KINDS ARE {@link JsonlTrace}'S, NOT THE PAGE'S. Two fields the page draws nothing with
     * travel anyway — a failure's {@code stack}, because a failure that cannot locate itself sends
     * whoever is reading to a container log that does not have it, and a settlement's {@code red} and
     * {@code green}, because they are what the runner reported rather than what the disposition
     * implies. A kind nobody has written yet arrives with its common fields only; adding one to the
     * trace means adding it here, or its payload is silently dropped.
     *
     * @param index the event's position in THIS ordering, which is what the page's rating form posts
     *              as its {@code event} — the page numbers these after the sort, so the number is
     *              only meaningful alongside the order it was sent in. It is sent rather than left
     *              for the client to count because a client is free to re-sort (oldest first is the
     *              natural way to read a conversation), and a feedback row written from a re-sorted
     *              list is filed against a different event. THE INDEX IS NOT AN IDENTITY, unlike a
     *              finding's {@code position}: that is a place in a file and survives the next
     *              write, this is a place in this response and does not.
     */
    private static void event(StringBuilder b, String line, int index) {
        String kind = Dashboard.field(line, "kind");
        b.append("{\"index\":").append(index);
        b.append(",\"at\":").append(number(Dashboard.field(line, "at")));
        b.append(",\"kind\":").append(quote(kind));
        // NOT A MARKER KEY. Every event in this file carries marker="overwatch" — the supervisor's
        // own name, which is what its JsonlTrace was constructed with — and a client that builds
        // /marker?k=<this> from it links to a marker that cannot exist, which is what the page does
        // today. It is sent as recorded because that is what the record says; it is not a key.
        b.append(",\"marker\":").append(quote(Dashboard.field(line, "marker")));
        if (Body.carries(kind)) {
            b.append(",\"text\":").append(quote(Body.of(line)));
        }
        switch (kind) {
            case "asked" -> b.append(",\"agent\":").append(quote(Dashboard.field(line, "agent")))
                    // IN FULL, both of them, exactly as the trace holds them. A prompt truncated
                    // here is a training example nobody can use.
                    .append(",\"prompt\":").append(quote(Dashboard.field(line, "prompt")))
                    .append(",\"reply\":").append(quote(Dashboard.field(line, "reply")));
            case "tool" -> b.append(",\"agent\":").append(quote(Dashboard.field(line, "agent")))
                    .append(",\"tool\":").append(quote(Dashboard.field(line, "tool")))
                    .append(",\"arguments\":").append(quote(Dashboard.field(line, "arguments")))
                    .append(",\"result\":").append(quote(Dashboard.field(line, "result")));
            case "built" -> b.append(",\"phase\":").append(quote(Dashboard.field(line, "phase")))
                    .append(",\"passed\":").append(flag(Dashboard.field(line, "passed")))
                    .append(",\"infra\":").append(flag(Dashboard.field(line, "infra")))
                    .append(",\"summary\":").append(quote(Dashboard.field(line, "summary")));
            case "settled" -> b.append(",\"state\":").append(quote(Dashboard.field(line, "state")))
                    .append(",\"because\":").append(quote(Dashboard.field(line, "because")))
                    .append(",\"red\":").append(flag(Dashboard.field(line, "red")))
                    .append(",\"green\":").append(flag(Dashboard.field(line, "green")));
            // `minutes` is null when the estimator gave no figure. It answers "unknown" when it
            // cannot price something, and a zero here would sum into a human-equivalent total that
            // says the work cost nothing.
            case "priced" -> b.append(",\"minutes\":")
                    .append(number(Dashboard.field(line, "minutes")))
                    .append(",\"itemisation\":")
                    .append(quote(Dashboard.field(line, "itemisation")));
            default -> { }
        }
        b.append('}');
    }

    /**
     * One prove the supervisor cut short, with all seven recorded fields.
     *
     * <p>{@code by} IS NULL WHEN THE RECORD DOES NOT SAY, and that is not the same as "the
     * supervisor". Both the agent's restart and a person's /reprove append here — the record of what
     * happened to a marker has to be one file — and before the field existed neither line said
     * which, so two presses of the dashboard's button silently spent the supervisor's allowance of
     * two for a marker it had never touched. {@code Supervisor.restarts} still reads a line with no
     * {@code by} as the agent's, because a limit must fail in the direction that does not lift
     * itself; that is a decision about the limit and not a fact about who cut the tree, and
     * inventing it here would put a name on the screen that the file does not carry.
     *
     * <p>The row carries both {@code id} (the directory slug) and {@code marker} (the exact key),
     * so a client linking either needs no join.
     */
    private static void restart(StringBuilder b, String line) {
        String by = Dashboard.field(line, "by");
        // Written as quoted strings by Supervisor.record; sent as the number and the boolean they
        // are, because a client parsing "2" out of JSON is a client deciding what it means.
        b.append("{\"at\":").append(number(Dashboard.field(line, "at")));
        b.append(",\"id\":").append(quote(Dashboard.field(line, "id")));
        b.append(",\"marker\":").append(quote(Dashboard.field(line, "marker")));
        b.append(",\"attempt\":").append(number(Dashboard.field(line, "attempt")));
        b.append(",\"killed\":").append(flag(Dashboard.field(line, "killed")));
        b.append(",\"by\":").append(by.isBlank() ? "null" : quote(by));
        b.append(",\"why\":").append(quote(Dashboard.field(line, "why")));
        b.append('}');
    }

    /**
     * THE MARKERS A FINDING NAMES, MADE REACHABLE.
     *
     * <p>A finding says "3 markers" and names them as the directory slugs it read; a reader wanting
     * to check the claim would otherwise copy a slug, guess the marker key it came from and paste it
     * into a URL. The names are already exact — this only says what they point at.
     *
     * <p>THE KEY TRAVELS WITH THE SLUG because the slug cannot be reversed: {@link Supervisor#slug}
     * takes everything after the last slash, replaces every non-alphanumeric and cuts to eighty, so
     * two markers can share one and neither can be turned back into a marker key. Longest slug
     * first, so a client substituting them in the text links a slug that contains a shorter one
     * whole rather than having its middle replaced.
     */
    private static String named(String finding, Map<String, String> slugs) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : slugs.entrySet()) {
            if (!finding.contains(e.getKey())) {
                continue;
            }
            if (b.length() > 0) {
                b.append(',');
            }
            b.append("{\"slug\":").append(quote(e.getKey()))
                    .append(",\"key\":").append(quote(e.getValue())).append('}');
        }
        return b.toString();
    }

    /** Every queued marker by the directory name the pool gives it, longest slug first. */
    private static Map<String, String> slugs(Path markersFile) {
        Map<String, String> byLength = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(read(markersFile));
        // LONGEST SLUG FIRST. The page sorts the marker keys by length, which is the same intent
        // through a proxy; the length that decides whether one name contains another is the slug's.
        keys.sort((a, b) -> Integer.compare(Supervisor.slug(b.strip()).length(),
                Supervisor.slug(a.strip()).length()));
        for (String key : keys) {
            String trimmed = key.strip();
            // An empty slug is contained in every text there is, and would name every marker on
            // every finding.
            if (!trimmed.isEmpty() && !Supervisor.slug(trimmed).isEmpty()) {
                byLength.put(Supervisor.slug(trimmed), trimmed);
            }
        }
        return byLength;
    }

    /** A finding's verdict, with the two ways of recording "nobody judged it" collapsed into one. */
    private static String verdictOf(String finding) {
        String verdict = Dashboard.field(finding, "verdict");
        return verdict.isBlank() ? "unjudged" : verdict;
    }

    /**
     * The view that is actually being served. An unrecognised one is the findings view.
     *
     * <p>COMPARED EXACTLY, AS THE PAGE COMPARES IT, and not trimmed first. The page tests
     * {@code view.equals("trace")} against the decoded query value, so {@code ?a=%20trace} renders
     * the findings body there; trimming here would serve the record for the same URL and report
     * {@code "view":"trace"} for it — the two readers disagreeing about which screen they are on,
     * which is the one thing this function exists to prevent.
     */
    private static String served(String view) {
        String asked = view == null ? "" : view;
        if (asked.equals("trace")) {
            return asked;
        }
        // THE PER-AGENT VIEWS ARE THE WATCHERS, TAKEN FROM THE LIST RATHER THAN TYPED. This was
        // `case "overwatch", "overwatch-critic"`, and the day those two became six triple members
        // both names stopped matching anything: every per-agent link would have fallen through to
        // the findings view, silently, with the URL still saying which agent it meant.
        //
        // `events()` filters on an exact `agent` field match, so whatever is allowed here must be a
        // real agent name — which is precisely why it should come from the same list the agents do.
        return Agents.WATCH.contains(asked) ? asked : "findings";
    }

    /**
     * ONE FILE, NOT {@link Dashboard#lines}, and the blank lines dropped exactly as the page drops
     * them.
     *
     * <p>{@code lines} concatenates every per-marker copy of a file, which is right for the provers'
     * trace and wrong here twice over: the supervisor is one process writing at the results root, so
     * there are no copies to find, and a finding's {@code position} is its position in THIS file —
     * anything appended after it would renumber the anchors the feedback rows were written against.
     *
     * <p>Absent is not an error: a run that has settled nothing yet is the normal first state.
     */
    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    /** A field that should be a number, or 0 — a malformed one must not take the payload down. */
    private static long num(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * A recorded number, or {@code null} where the record does not carry one.
     *
     * <p>Not zero. A zero is a value somebody could have written, and a client cannot tell one this
     * invented from one an estimator meant.
     */
    private static String number(String s) {
        try {
            return String.valueOf(Long.parseLong(s.strip()));
        } catch (NumberFormatException notANumber) {
            return "null";
        }
    }

    /** A boolean the record wrote as a string, or {@code null} — never a false nobody recorded. */
    private static String flag(String s) {
        return "true".equals(s.strip()) ? "true" : "false".equals(s.strip()) ? "false" : "null";
    }

    /**
     * A JSON string, escaped by the one escaper this program has.
     *
     * <p>EVERY string goes through here. These lines hold whole prompts, replies and diffs with
     * quotes and newlines in them, and one unescaped value makes the whole document unparseable —
     * which the reader sees as a blank screen rather than as a missing field.
     */
    private static String quote(String s) {
        return "\"" + Settlement.escape(s == null ? "" : s) + "\"";
    }
}
