package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ONE MARKER AS JSON, FOR THE SCREEN THAT REPLACES {@code /marker}.
 *
 * <p>{@link Api} sends the run; this sends what is under one row of it. TWO DOCUMENTS, not one,
 * because the screen asks two questions with different lifetimes: the marker itself changes when a
 * stage concludes, and one agent's tab changes with every tool call it makes. A client that had to
 * refetch ten agents' prompts and replies — tens of thousands of characters each, in full, on
 * purpose — to notice that a state moved would refetch the whole prove every poll.
 *
 * <p>FACTS, NOT SENTENCES, which is the rule the index already follows. {@code at} is epoch millis
 * rather than "3h 12m", {@code verdictText} is the whole argument rather than its first sentence,
 * and the flagged source is an array of lines with the numbers beside it rather than the page's
 * blob with {@code >> } glued into the gutter. A formatted string is a decision baked into JSON that
 * the client cannot revisit, and the two screens that want the same figure differently then get one
 * of them wrong.
 *
 * <p>The exceptions are RESOLUTIONS rather than formatting — answers this program can reach and a
 * browser cannot. {@code state} comes through {@link Run}, because a {@code proving} row with no
 * claim is {@code interrupted} and a client sent the raw state waits forever on a prove that is not
 * running. {@code tookMinutes} and {@code attempts} come through {@link Pace}, because the archived
 * attempts under {@code dead/} are not reachable from the wire and a marker restarted twice reads as
 * young without them.
 *
 * <p>ABSENT IS NULL, and blank counts as absent: nothing here is defaulted to false, zero or "". A
 * settlement that has not been written yet, a checker this pipeline has no note for, a checkout that
 * is not on this machine — each is {@code null}, and the client decides what to draw. A guessed
 * value is a fact this program did not observe.
 *
 * <p>READ FAILURES ARE EMPTY, NOT EXCEPTIONS. Every file read here belongs to a run that may have
 * started thirty seconds ago; a missing trace, an absent lane and an unreadable tree are all "it has
 * not got there yet", and this must render for all of them.
 */
final class ApiMarker {

    private ApiMarker() {
    }

    /** Lines of context either side of the flagged one. Enough to see the statement it is part of. */
    private static final int AROUND = 4;

    /** Where the provers keep their trees. The same value {@code entrypoint.sh} uses. */
    private static final Path CHECKOUTS =
            Path.of(System.getenv().getOrDefault("CHECKOUTS", "/work/checkouts"));

    /** What the library prefixes an agent's own tool calls with. See {@link #who}. */
    private static final String CONTEXT = "agent:";

    /**
     * {@code GET /api/marker?k=<key>} — what was claimed, how it settled, and what it cost.
     *
     * <p>Everything on the summary tab of the page, in the order a person asks it: the claim, the
     * code it points at, the state, the two build facts a state cannot carry, the test, the patch,
     * the argument, and what the run spent getting there.
     */
    static String marker(Path settlements, Path trace, String key) {
        // The page splits the key itself in four places; the client should not have to. The guards
        // are not decoration: a key typed into a query string arrives in whatever shape it was typed.
        String[] parts = key.split("\\|");
        String repo = parts.length > 0 ? parts[0] : "";
        String file = parts.length > 1 ? parts[1] : "";
        String line = parts.length > 2 ? parts[2] : "";
        String checker = parts.length > 3 ? parts[3] : "";
        Path results = settlements.getParent() == null ? Path.of(".") : settlements.getParent();
        String id = Supervisor.slug(key);

        // THE RESOLVED STATE, THROUGH THE ONE PLACE THAT RESOLVES IT. Copying the rule here would
        // let this drift from the page and from /api/index without either of them failing.
        Run.Row row = Run.rows(settlements, Api.queue(settlements)).get(key);
        String settled = settled(settlements, key);

        List<String> mine = new ArrayList<>();
        for (String event : Dashboard.lines(trace)) {
            if (Dashboard.field(event, "marker").equals(key)) {
                mine.add(event);
            }
        }

        // THE TAB ROW'S COUNTS, GATHERED BEFORE ANYTHING ELSE IS DECIDED. On the page this was
        // computed after the tab dispatch and two tabs rendered with no counts at all.
        Map<String, Integer> runs = new LinkedHashMap<>();
        for (String agent : Agents.CHAIN) {
            runs.put(agent, 0);
        }
        int humanMinutes = 0;
        for (String event : mine) {
            String kind = Dashboard.field(event, "kind");
            String agent = who(event);
            if (!agent.isEmpty()) {
                // AN AGENT THE CHAIN NO LONGER LISTS STILL HAS A TAB'S WORTH OF RECORD. This run's
                // own trace holds `fix-skeptic` answers from before that agent was renamed
                // `fix-critic`; a strip built only from Agents.CHAIN makes them unreachable.
                runs.putIfAbsent(agent, 0);
            }
            if (kind.equals("asked") && !agent.isEmpty()) {
                runs.merge(agent, 1, Integer::sum);
            } else if (kind.equals("priced")) {
                humanMinutes += (int) num(Dashboard.field(event, "minutes"));
            }
        }

        // WHAT WAS WRITTEN AND WHAT WOULD CHANGE, from the record first and the trace only if the
        // record has not got them. Settlement declares these four fields and nothing fills them
        // yet, so serving them alone would delete the test and the patch from this screen — they
        // are the two artefacts the whole prove turns on. Once a settlement is written with
        // test_code and fix_diff in it, both recoveries below can go.
        String testPath = Dashboard.field(settled, "test_path");
        String testCode = Dashboard.field(settled, "test_code");
        if (testCode.isBlank()) {
            // The LAST write_file, and its path from the same event. Only the reproducer has
            // write_file — the fixer is given edit_file precisely so it cannot write a test — so
            // this is the reproducer's test by construction rather than by luck.
            for (String event : mine) {
                if (!Dashboard.field(event, "kind").equals("tool")
                        || !Dashboard.field(event, "tool").equals("write_file")) {
                    continue;
                }
                String arguments = Dashboard.field(event, "arguments");
                String content = Dashboard.field(arguments, "content");
                if (!content.isBlank()) {
                    testCode = content;
                    testPath = Dashboard.field(arguments, "path");
                }
            }
        }
        String fixDiff = Dashboard.field(settled, "fix_diff");
        if (fixDiff.isBlank()) {
            fixDiff = patch(mine);
        }

        StringBuilder b = new StringBuilder("{");
        b.append("\"key\":").append(quote(key));
        b.append(",\"id\":").append(quote(id));
        b.append(",\"repo\":").append(quote(repo));
        b.append(",\"file\":").append(quote(file));
        b.append(",\"line\":").append(num(line));
        b.append(",\"checker\":").append(quote(checker));
        b.append(",\"claimNote\":").append(text(claimNote(checker)));
        // A KEY NEITHER THE QUEUE NOR THE RECORD HAS HEARD OF HAS NO STATE. `queued` would be a
        // claim about a queue this marker is not in.
        b.append(",\"state\":").append(row == null ? "null" : quote(row.state()));
        b.append(",\"redVerified\":").append(flag(settled, "red_verified"));
        b.append(",\"greenVerified\":").append(flag(settled, "green_verified"));
        b.append(",\"testPath\":").append(text(testPath));
        b.append(",\"testCode\":").append(text(testCode));
        b.append(",\"fixDiff\":").append(text(fixDiff));
        // Nothing writes infra_reason today. It is served as recorded rather than reconstructed
        // from verdict_text — which is where a prove that threw puts its cause — because the empty
        // field is the honest report of a value nothing computed.
        b.append(",\"infraReason\":").append(text(Dashboard.field(settled, "infra_reason")));
        b.append(",\"verdictText\":").append(text(Dashboard.field(settled, "verdict_text")));
        b.append(",\"summary\":").append(text(summary(results, id)));
        b.append(",\"humanMinutes\":").append(humanMinutes);
        // EVERY ATTEMPT, NOT THIS ONE. `restart_prove` moves the lane to dead/ and the next attempt
        // starts a fresh trace, so a marker can burn seventy-nine minutes, be restarted, burn
        // seventy-nine more and read as seventy-nine every time. Pace adds the archives back.
        b.append(",\"attempts\":").append(Pace.attempts(results, id));
        b.append(",\"tookMinutes\":").append(Pace.totalMinutes(results, id));
        b.append(",\"agents\":[");
        boolean first = true;
        for (Map.Entry<String, Integer> counted : runs.entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append("{\"agent\":").append(quote(counted.getKey()))
                    .append(",\"runs\":").append(counted.getValue()).append('}');
        }
        b.append("],\"flagged\":").append(flagged(repo, file, line));
        return b.append('}').toString();
    }

    /**
     * {@code GET /api/marker/agent?k=<key>&a=<agent>} — one agent's tab, oldest event first.
     *
     * <p>Everything this agent did to this marker, unabridged: each prompt, each reply, each thought
     * and each tool call with what came back. The page decides here which answer is final and folds
     * the superseded ones under it; that is a reading of an ordered list, so the list goes and the
     * client does the reading.
     *
     * <p>IN FULL, ALL OF IT. The argument to {@code write_file} IS the test, and the cut that showed
     * its path and hid its content is the reason the summary tab had to recover the test by scanning
     * these events in the first place.
     *
     * <p>The kinds that name no agent — {@code built}, {@code settled}, {@code priced},
     * {@code failed}, {@code progress} — belong to the marker rather than to a tab, so they never
     * match here; their fields ride the event shape as nulls so one reader can hold one type.
     *
     * <p>AND EVERY FIELD KEEPS THE RECORD'S OWN NAME — {@code arguments}, not {@code args}. Three
     * endpoints now serve trace events to the same zone ({@link ApiTrace}, {@link ApiOverwatch},
     * this), and a second name for the same value is the two-readers-disagree failure the whole port
     * is being written to avoid — silent, because both documents parse.
     */
    static String markerAgent(Path trace, String key, String agent) {
        String wanted = agent == null ? "" : agent.strip();
        if (wanted.isEmpty()) {
            // NO AGENT IS NOT EVERY AGENT. Matching a blank name would hand back the marker-level
            // events — builds and settlements — under a tab that belongs to nobody.
            return "[]";
        }
        List<String> mine = new ArrayList<>();
        for (String event : Dashboard.lines(trace)) {
            if (Dashboard.field(event, "marker").equals(key) && who(event).equals(wanted)) {
                mine.add(event);
            }
        }
        // Concatenating the run's trace and the lane's gives two ordered runs, not one. The stamp is
        // what makes them a single story again — and the page's agent tab, which does not sort, is
        // why a rating posted from it can point at a different answer than the record tab's.
        mine.sort((a, b) -> Long.compare(num(Dashboard.field(a, "at")),
                num(Dashboard.field(b, "at"))));

        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < mine.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(event(mine.get(i)));
        }
        return b.append(']').toString();
    }

    /** One trace event, with every field the kinds carry and {@code null} where a kind carries none. */
    private static String event(String e) {
        StringBuilder b = new StringBuilder("{");
        // A MOMENT OR NULL, NEVER 0. An unreadable stamp is not midnight in 1970, and a client that
        // labels or orders by it would date a whole tab to then — this run's own trace.jsonl holds
        // lines with no `at` on them at all. No clock this program reads produces 0, so 0 is absent.
        // /api/trace sends null here for the same reason.
        long at = num(Dashboard.field(e, "at"));
        b.append("\"at\":").append(at == 0 ? "null" : String.valueOf(at));
        b.append(",\"kind\":").append(quote(Dashboard.field(e, "kind")));
        b.append(",\"agent\":").append(quote(who(e)));
        b.append(",\"prompt\":").append(text(Dashboard.field(e, "prompt")));
        b.append(",\"reply\":").append(text(Dashboard.field(e, "reply")));
        // The reasoning, which is the only account of what an agent seven tool calls in is doing.
        b.append(",\"text\":").append(text(Dashboard.field(e, "text")));
        b.append(",\"tool\":").append(text(Dashboard.field(e, "tool")));
        b.append(",\"arguments\":").append(text(Dashboard.field(e, "arguments")));
        b.append(",\"result\":").append(text(Dashboard.field(e, "result")));
        b.append(",\"phase\":").append(text(Dashboard.field(e, "phase")));
        // THREE OUTCOMES, NOT TWO. `infra` means the build produced no test result at all — not a
        // pass and not a fail — so `passed` goes too; phase and infra without it describe a build
        // nobody can read. And `red` is the run BEFORE the patch, which is supposed to FAIL.
        b.append(",\"passed\":").append(flag(e, "passed"));
        b.append(",\"infra\":").append(flag(e, "infra"));
        b.append(",\"summary\":").append(text(Dashboard.field(e, "summary")));
        b.append(",\"cause\":").append(text(Dashboard.field(e, "cause")));
        b.append(",\"note\":").append(text(Dashboard.field(e, "note")));
        return b.append('}').toString();
    }

    /**
     * WHICH AGENT AN EVENT BELONGS TO, UNDER ONE RULE.
     *
     * <p>The same agent is recorded under two names: its answers arrive as {@code fixer} and the
     * tool calls made on its behalf as {@code agent:fixer}, because the runtime is built with
     * {@code agent:<name>} as its context. The page compensates with {@code endsWith} on tool calls
     * and {@code equals} on answers — two rules, which is how a tab can show one agent's tool calls
     * beside another's answers, and {@code endsWith("fixer")} would also claim a {@code prefixer}.
     * One rule here: drop the prefix and compare whole names.
     */
    private static String who(String event) {
        String agent = Dashboard.field(event, "agent");
        return agent.startsWith(CONTEXT) ? agent.substring(CONTEXT.length()) : agent;
    }

    /**
     * THE ROW THAT SETTLED IT, which is the last one that is not {@code proving}.
     *
     * <p>A lane writes a row per stage boundary, and the ones in between are progress notes:
     * {@link Settlement#note} fills {@code red_verified} and {@code green_verified} with false on
     * every one of them, because a note has no build result to report. Reading the last row of all
     * would therefore say "we ran the test and it did not fail" about a marker whose RED has not run
     * — so the same rule {@link Run} uses for the state decides these too, and a marker still in
     * flight reports null rather than a denial.
     */
    private static String settled(Path settlements, String key) {
        String row = "";
        for (String line : Dashboard.lines(settlements)) {
            if (!Dashboard.field(line, "suspicion_key").equals(key)
                    || Dashboard.field(line, "state").equals("proving")) {
                continue;
            }
            row = line;
        }
        return row;
    }

    /**
     * THE PATCH, WHICH NOTHING RECORDS AS AN ARTEFACT.
     *
     * <p>Java computes the diff and hands it to {@code fix-critic} inside a prompt, so the one thing
     * a reader most wants — what would actually change in their repository — exists only as text in
     * an argument. Recovered from between the heading and whatever follows it, and DELIBERATELY
     * FRAGILE: rewording either prompt makes the fix disappear from this screen with nothing
     * failing. It ends the day {@code fix_diff} is written into the settlement.
     */
    private static String patch(List<String> events) {
        String found = "";
        for (String e : events) {
            if (!Dashboard.field(e, "kind").equals("asked")
                    || !who(e).equals("fix-critic")) {
                continue;
            }
            String prompt = Dashboard.field(e, "prompt");
            int heading = prompt.indexOf("WHAT IT ACTUALLY CHANGED");
            if (heading < 0) {
                continue;
            }
            int start = prompt.indexOf('\n', heading);
            int stop = prompt.indexOf("\nThe patch changes ", start);
            if (stop < 0) {
                stop = prompt.indexOf("\nTHE PATCH DOES NOT TOUCH", start);
            }
            if (start > 0) {
                found = (stop > start ? prompt.substring(start, stop)
                        : prompt.substring(start)).strip();
            }
        }
        return found;
    }

    /**
     * WHAT THIS PIPELINE SAYS THE CHECKER MEANS — its first paragraph, or null.
     *
     * <p>{@code checkers/<name>.txt} holds the construct's regex on its first line and prose after
     * it, which is why this is not simply the file: the regex is machinery for {@link Checkers} and
     * reads as noise on a screen. The name is sanitised because it arrives in a URL and is about to
     * become a resource path.
     *
     * <p>The page's fallback — "This pipeline has no note for X, so what it means here is whatever
     * the agents below took it to mean" — is prose, not a fact, so it stays on the client. Null says
     * there is no note; a sentence saying so is a sentence a screen cannot reword.
     */
    private static String claimNote(String checker) {
        String safe = checker.replaceAll("[^A-Za-z0-9._-]", "");
        try (InputStream in = ApiMarker.class.getResourceAsStream("/checkers/" + safe + ".txt")) {
            if (in == null) {
                return null;
            }
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int nl = all.indexOf('\n');
            String note = nl < 0 ? "" : all.substring(nl + 1).strip();
            int para = note.indexOf("\n\n");
            return para < 0 ? note : note.substring(0, para).strip();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The lane interpreter's account, already checked by its critic. Null until it has run.
     *
     * <p>WHOLE, not split. The marker list shows the headline before the first blank line and this
     * screen shows the body after it, but a file with no blank line IS both halves — splitting here
     * would send it twice and let two screens say the same thing in different words.
     */
    private static String summary(Path results, String id) {
        Path file = results.resolve("m").resolve(id).resolve("summary.txt");
        try {
            return Files.isReadable(file) ? Files.readString(file).strip() : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * THE LINE THE ANALYSER FLAGGED, WITH THE CODE AROUND IT — as lines, not as a gutter.
     *
     * <p>A verdict about {@code ProfileUploadBase.java:82} is not checkable without line 82, and the
     * four lines around it are what make it a statement rather than a fragment. The page glues
     * {@code >> } and the numbers into the text; that is the client's decision, so what goes is the
     * window itself, the number it starts at, and how long the file is.
     *
     * <p>{@code fileLength} is not decoration: THE MARKERS CAME OFF AN OLDER REVISION and some point
     * past the end of the file now. A reader told the length knows not to trust the line number, and
     * a marker past the end has no window at all — an empty {@code lines} with a null
     * {@code firstLine}, which is a different answer from having no checkout.
     *
     * <p>Read with {@code readAllLines} and NOT with the record's reader, which drops blank lines: a
     * blank line is nothing in a JSONL record and is line 79 in source, and every number after it
     * shifts. That put a declaration four lines below the truth and read as the marker having
     * drifted.
     *
     * <p>Null when there is no tree to read. This is a convenience for a reader, and a dashboard
     * that would not answer because a checkout is missing is a poor trade for it.
     */
    private static String flagged(String repo, String file, String lineNumber) {
        int want;
        try {
            want = Integer.parseInt(lineNumber.strip());
        } catch (NumberFormatException noLine) {
            return "null";
        }
        String name = repo.substring(repo.lastIndexOf('/') + 1).replaceAll("\\.git$", "");
        List<String> source;
        try {
            source = Files.readAllLines(CHECKOUTS.resolve(name).resolve(file));
        } catch (IOException | RuntimeException noTree) {
            return "null";
        }
        if (source.isEmpty() || want < 1) {
            return "null";
        }
        int from = Math.max(1, want - AROUND);
        int to = Math.min(source.size(), want + AROUND);
        StringBuilder b = new StringBuilder("{\"firstLine\":")
                .append(from > to ? "null" : String.valueOf(from))
                .append(",\"flaggedLine\":").append(want)
                .append(",\"fileLength\":").append(source.size())
                .append(",\"lines\":[");
        for (int n = from; n <= to; n++) {
            if (n > from) {
                b.append(',');
            }
            b.append(quote(source.get(n - 1)));
        }
        return b.append("]}").toString();
    }

    /** A string, or {@code null} where nothing has written one. Blank and absent are one absence. */
    private static String text(String s) {
        return s == null || s.isBlank() ? "null" : quote(s);
    }

    /**
     * A recorded boolean, or {@code null} where nothing recorded it.
     *
     * <p>False is an answer — the test ran and did not fail — and defaulting to it says that about a
     * marker nothing has run yet. The record writes these unquoted, which {@link Dashboard#field}
     * reads only because it was taught to; before that every genuinely red marker read as blank.
     */
    private static String flag(String row, String field) {
        String said = Dashboard.field(row, field);
        return said.isBlank() ? "null" : String.valueOf(said.equals("true"));
    }

    /** A field that should be a number, or 0 — a malformed one must not take the document down. */
    private static long num(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * A JSON string, escaped by the one escaper this program has.
     *
     * <p>EVERYTHING goes through here. These events carry whole prompts, replies and diffs, with
     * quotes and newlines and the occasional control character in them; one unescaped value makes
     * the whole document unparseable and the screen blank.
     */
    private static String quote(String s) {
        return "\"" + Settlement.escape(s == null ? "" : s) + "\"";
    }
}
