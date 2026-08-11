package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * THE RECORD, READABLE. {@code java … Dashboard [results-dir] [port]}
 *
 * <p>Two views, because there are two questions. The index answers "what happened to my markers";
 * a marker page answers "why", and answering why means the whole dialog — every prompt, every reply,
 * every tool call, every build, in the order they occurred. A settlement without its dialog is a
 * verdict without a trial record: you can read it, you cannot check it.
 *
 * <p>It reads both files on every request and holds nothing. A prove appends; a refresh shows it.
 *
 * <p>Prompts, tool arguments and build logs are long and mostly uninteresting until they are the only
 * thing that matters, so they sit inside {@code <details>} — collapsed, one click away. What is never
 * collapsed is what an agent ANSWERED, because that is the part a reader is here to judge.
 *
 * <p>A LIVE PAGE MUST NOT UNDO ITS READER. The refresh that keeps an in-flight prove moving would
 * otherwise snap every fold shut and jump to the top on a fifteen-second timer, so what is open and
 * where the page is scrolled survive the reload — see {@link #KEEP_OPEN}. That is the whole of the
 * JavaScript here, and it exists because without it the page fights whoever is reading it.
 */
public final class Dashboard {

    private static final String CSS = """
            *{box-sizing:border-box}body{margin:0;font:13px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace;
            background:#0d1117;color:#c9d1d9}a{color:#58a6ff;text-decoration:none}a:hover{text-decoration:underline}
            header{padding:16px 24px;border-bottom:1px solid #21262d}
            h1{margin:0;font-size:14px;font-weight:600}.sub{color:#7d8590;font-size:12px;margin-top:3px}
            .counts{display:flex;flex-wrap:wrap;gap:8px;padding:14px 24px}
            .c{padding:6px 12px;border:1px solid #21262d;border-radius:6px;background:#161b22}
            .c b{font-size:17px;display:block}.c span{color:#7d8590;font-size:11px}
            table{width:100%;border-collapse:collapse}th{text-align:left;color:#7d8590;font-weight:500;font-size:11px;
            text-transform:uppercase;letter-spacing:.06em;padding:9px 24px;border-bottom:1px solid #21262d}
            td{padding:9px 24px;border-bottom:1px solid #161b22;vertical-align:top}tr:hover td{background:#0f141a}
            .k{color:#7d8590;font-size:11px}
            .s{padding:2px 9px;border-radius:20px;font-size:11px;white-space:nowrap;display:inline-block}
            .verified,.verified-pr-ready,.verified-pr-rejected{background:#132e1a;color:#3fb950}
            .reproduced,.needs-review{background:#2b2011;color:#d29922}
            .false-positive,.by-design,.unprovable,.not-a-bug{background:#161b22;color:#8b949e}
            .to-do{background:#0d1117;color:#6e7681;border:1px solid #21262d}
            td.latest{color:#8b949e;font-size:12px;max-width:46ch}
            .tabs{display:flex;gap:2px;flex-wrap:wrap;padding:10px 24px;border-bottom:1px solid #21262d}
            .tabs a{padding:5px 11px;border-radius:6px;font-size:12px;color:#8b949e}
            .tabs a:hover{background:#161b22;text-decoration:none}
            .tabs a.on{background:#1f6feb;color:#fff}
            .infra{background:#2d1618;color:#f85149}
            .proving{background:#122033;color:#58a6ff}
            .proving::before{content:"● ";animation:p 1.4s ease-in-out infinite}
            @keyframes p{0%,100%{opacity:1}50%{opacity:.25}}
            .ev{border-left:2px solid #21262d;margin:0 24px;padding:12px 0 12px 16px}
            .ev.asked{border-color:#58a6ff}.ev.built{border-color:#d29922}.ev.settled{border-color:#3fb950}
            .ev.failed{border-color:#f85149}.ev.tool{border-color:#30363d}
            .ev.priced{border-color:#a371f7}
            .who{color:#58a6ff;font-weight:600}.kind{color:#7d8590;font-size:11px;text-transform:uppercase;
            letter-spacing:.06em;margin-left:8px}
            pre{white-space:pre-wrap;word-break:break-word;background:#161b22;border:1px solid #21262d;
            border-radius:6px;padding:10px;margin:8px 0;overflow-x:auto;font-size:12px;line-height:1.5}
            details{margin:6px 0}summary{cursor:pointer;color:#7d8590;font-size:11px;user-select:none}
            summary:hover{color:#c9d1d9}
            .empty{padding:48px 24px;color:#7d8590}.back{padding:14px 24px;display:block}
            .bar{height:4px;background:#161b22;margin:0}
            .bar i{display:block;height:100%;background:linear-gradient(90deg,#1f6feb,#3fb950)}
            .rate{margin-top:12px;border-top:1px dashed #21262d;padding-top:10px}
            .rate label{display:block;color:#c9d1d9;font-size:12px;margin-bottom:5px}
            .rate textarea{width:100%;background:#0d1117;color:#c9d1d9;border:1px solid #30363d;
            border-radius:6px;padding:8px;font:inherit;font-size:12px;resize:vertical}
            .rate button{background:#0d1117;border:1px solid #30363d;
            border-radius:6px;padding:5px 12px;font:inherit;font-size:11px;margin-top:6px}
            .rate button{cursor:pointer;border-color:#1f6feb;color:#58a6ff}
            """;

    /**
     * Remember which folds are open and where the page is scrolled, across the refresh.
     *
     * <p>Keyed by the fold's index within the page, which is stable because events are appended: an
     * event arriving later cannot renumber the ones already rendered. Session storage rather than
     * local, so opening a second marker in another tab does not inherit this one's state.
     */
    private static final String KEEP_OPEN = """
            <script>
            (function(){
              var K='open:'+location.pathname+location.search, S=sessionStorage;
              function all(){return [].slice.call(document.querySelectorAll('details'))}
              try{
                var open=JSON.parse(S.getItem(K)||'[]');
                all().forEach(function(d,i){ if(open.indexOf(i)>=0) d.open=true });
                var y=+S.getItem(K+':y'); if(y) window.scrollTo(0,y);
              }catch(e){}
              document.addEventListener('toggle',function(){
                try{
                  var open=[]; all().forEach(function(d,i){ if(d.open) open.push(i) });
                  S.setItem(K,JSON.stringify(open));
                }catch(e){}
              },true);
              addEventListener('scroll',function(){ try{S.setItem(K+':y',window.scrollY)}catch(e){} },{passive:true});
            })();
            </script>
            """;

    private Dashboard() {
    }

    public static void main(String[] args) throws IOException {
        Path results = Path.of(args.length > 0 ? args[0] : "results");
        Path settlements = results.toString().endsWith(".jsonl")
                ? results : results.resolve("settlements.jsonl");
        Path trace = settlements.resolveSibling("trace.jsonl");
        Path feedback = settlements.resolveSibling("feedback.jsonl");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8087;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/settlements", e -> send(e, "application/json",
                "[" + String.join(",", lines(settlements)) + "]"));
        server.createContext("/api/trace", e -> send(e, "application/json",
                "[" + String.join(",", lines(trace)) + "]"));
        // THE CORPUS. Every labelled example, prompt and reply included, ready to train on with no
        // join back to the trace.
        server.createContext("/api/feedback", e -> send(e, "application/json",
                "[" + String.join(",", lines(feedback)) + "]"));
        server.createContext("/feedback", e -> {
            // ONCE. The body is a stream: a second read returns nothing, and the redirect would lose
            // the page the reader was on.
            Map<String, String> posted = "POST".equals(e.getRequestMethod())
                    ? form(e) : Map.of();
            if (!posted.isEmpty()) {
                record(feedback, posted);
            }
            // Straight back to where the reader was, so rating several answers is one page.
            e.getResponseHeaders().set("Location", posted.getOrDefault("back", "/"));
            e.sendResponseHeaders(303, -1);
            e.close();
        });
        server.createContext("/marker", e -> send(e, "text/html; charset=utf-8",
                marker(trace, settlements, query(e, "k"), query(e, "a"), open(e))));
        // THE WHOLE TRACE, every marker, in the order it happened. The per-marker view answers "why
        // did this settle so"; this one answers "what is this thing doing", which is a different
        // question and the one asked while a run is in flight.
        server.createContext("/trace", e -> send(e, "text/html; charset=utf-8",
                events(trace, settlements, "", open(e), "")));
        server.createContext("/", e -> send(e, "text/html; charset=utf-8",
                index(settlements, trace, lines(settlements.resolveSibling("markers.txt")))));
        server.start();
        System.out.println("dashboard on http://127.0.0.1:" + port + "  reading " + settlements);
    }

    // ------------------------------------------------------------------ index

    private static String index(Path settlements, Path trace, List<String> queued) {
        Map<String, String> latest = new LinkedHashMap<>();
        for (String line : lines(settlements)) {
            latest.put(field(line, "suspicion_key"), line);
        }
        Map<String, Integer> events = new LinkedHashMap<>();
        for (String line : lines(trace)) {
            events.merge(field(line, "marker"), 1, Integer::sum);
        }

        // Per marker: what it cost a person, and how long the machine actually took over it.
        Map<String, Integer> priced = new LinkedHashMap<>();
        Map<String, Long> first = new LinkedHashMap<>();
        Map<String, Long> last = new LinkedHashMap<>();
        for (String line : lines(trace)) {
            String m = field(line, "marker");
            long at = num(field(line, "at"));
            if (at > 0) {
                first.putIfAbsent(m, at);
                last.put(m, at);
            }
            if (field(line, "kind").equals("priced")) {
                // An estimator that answered in prose contributes nothing rather than a guess.
                priced.merge(m, (int) num(field(line, "minutes")), Integer::sum);
            }
        }
        // The last thing said about each marker — its stage while in flight, its argument once
        // settled. A row that shows only a state makes every proving marker look identical.
        Map<String, String> saidLast = new LinkedHashMap<>();
        for (String line : lines(trace)) {
            String kind = field(line, "kind");
            if (kind.equals("progress")) {
                saidLast.put(field(line, "marker"), field(line, "note"));
            } else if (kind.equals("settled")) {
                saidLast.put(field(line, "marker"), field(line, "because"));
            } else if (kind.equals("failed")) {
                saidLast.put(field(line, "marker"), field(line, "cause"));
            }
        }
        Map<String, Long> span = new LinkedHashMap<>();
        first.forEach((m, t0) -> span.put(m, last.getOrDefault(m, t0) - t0));
        int humanMinutes = priced.values().stream().mapToInt(Integer::intValue).sum();

        // EVERY MARKER THE RUN WAS GIVEN, not only the ones it has reached. A queue you cannot see
        // is a queue you cannot plan around, and "to-do" is a state like any other.
        Map<String, String> all = new LinkedHashMap<>();
        for (String marker : queued) {
            all.put(marker.trim(), "to-do");
        }
        latest.forEach((k, row) -> all.put(k, field(row, "state")));
        int total = all.size();
        int settled = (int) all.values().stream()
                .filter(s -> !s.equals("proving") && !s.equals("to-do")).count();
        long began = first.values().stream().mapToLong(Long::longValue).min().orElse(0L);
        long elapsed = began == 0 ? 0 : System.currentTimeMillis() - began;

        StringBuilder b = head("markers", all.size() + " marker(s) · "
                + events.values().stream().mapToInt(Integer::intValue).sum() + " trace event(s)");
        if (all.isEmpty()) {
            return b.append("<div class=empty>No markers queued and no prove has run.</div>")
                    .toString();
        }
        b.append(progress(total, settled, elapsed));


        Map<String, Integer> counts = new TreeMap<>();
        all.values().forEach(s -> counts.merge(s, 1, Integer::sum));
        b.append("<div class=counts>");
        counts.forEach((k, n) -> b.append("<div class=c><b>").append(n).append("</b><span>")
                .append(esc(k)).append("</span></div>"));
        if (humanMinutes > 0) {
            b.append("<div class=c><b>").append(humanMinutes / 60).append("h ")
                    .append(humanMinutes % 60).append("m</b><span>human-equivalent</span></div>");
        }
        b.append("</div><table><tr><th>marker</th><th>where</th><th>state</th>"
                + "<th>human-equiv</th><th>took</th><th>latest</th></tr>");

        all.forEach((key, state) -> {
            String row = latest.getOrDefault(key, "");
            // A marker not yet reached has no settlement row, so its file and checker come from the
            // key itself — which is repo|file|line|checker and always present.
            String[] parts = key.split("\\|");
            String file = row.isEmpty() ? (parts.length > 1 ? parts[1] : key) : field(row, "file");
            String checker = row.isEmpty() ? (parts.length > 3 ? parts[3] : "")
                    : field(row, "svace_checker");
            int mins = priced.getOrDefault(key, 0);
            long took = span.getOrDefault(key, 0L);
            String line = parts.length > 2 ? parts[2] : "";
            String dir = file.contains("/") ? file.substring(0, file.lastIndexOf('/')) : "";
            // The package tail, because two markers in the same run are routinely both LessonPage.java.
            dir = dir.replace("src/main/java/", "").replace("src/test/java/", "");
            b.append("<tr><td><a href='/marker?k=").append(enc(key)).append("'>")
                    .append(esc(file.substring(file.lastIndexOf('/') + 1)))
                    .append(line.isEmpty() ? "" : ":" + esc(line)).append("</a><div class=k>")
                    .append(esc(checker)).append("</div></td>")
                    .append("<td class=k>").append(esc(dir)).append("</td>")
                    .append("<td><span class='s ").append(esc(css(state))).append("'>").append(esc(state))
                    .append("</span>").append(flags(row)).append("</td>")
                    .append("<td>").append(mins > 0 ? hm(mins) : "<span class=k>—</span>")
                    .append("</td><td class=k>").append(took > 0 ? clock(took) : "—")
                    .append("<div class=k>").append(events.getOrDefault(key, 0)).append(" event(s)</div>")
                    .append("</td><td class=latest>")
                    .append(esc(cut(oneLine(saidLast.getOrDefault(key, "")), 150)))
                    .append("</td></tr>");
        });
        return b.append("</table><a class=back href='/trace'>the whole trace, every marker →</a>")
                .toString();
    }

    // ----------------------------------------------------------------- marker

    /** The agents, in the order they run. A tab each, plus the record itself. */
    private static final List<String> AGENTS = List.of("reproducer", "proof-critic", "fixer",
            "fix-critic", "pr-maker", "pr-critic", "verdict", "estimator", "estimator-critic");

    /**
     * One marker, by tab.
     *
     * <p>The chronological trace answers "what happened" and is the wrong shape for "what did the
     * fixer end up saying" — a reproducer that answered twice buries its final test under the
     * rejected one and eighty tool calls. So each agent gets a tab showing its LAST answer first,
     * with earlier attempts folded beneath it in the order they were given.
     *
     * @param agent one of {@link #AGENTS}, {@code trace} for the record, or empty for the summary
     */
    private static String marker(Path trace, Path settlements, String key, String agent,
                                 boolean expand) {
        if (agent.equals("trace")) {
            return events(trace, settlements, key, expand, tabs(key, agent));
        }
        List<String> mine = new ArrayList<>();
        for (String line : lines(trace)) {
            if (field(line, "marker").equals(key)) {
                mine.add(line);
            }
        }
        String state = "";
        for (String line : lines(settlements)) {
            if (field(line, "suspicion_key").equals(key)) {
                state = field(line, "state");
            }
        }
        StringBuilder b = head(key.substring(key.lastIndexOf('/') + 1),
                esc(key) + (state.isEmpty() ? "" : " · <span class='s " + css(state) + "'>"
                        + esc(state) + "</span>"));
        b.append(tabs(key, agent));

        if (agent.isEmpty()) {
            // The summary: every agent's final word, in the order they ran, and nothing else.
            for (String who : AGENTS) {
                List<String> said = asked(mine, who);
                if (said.isEmpty()) {
                    long busy = mine.stream().filter(e -> field(e, "kind").equals("tool")
                            && field(e, "agent").endsWith(who)).count();
                    if (busy > 0) {
                        b.append("<div class='ev tool'><span class=who>").append(esc(who))
                                .append("</span><span class=kind>working — ").append(busy)
                                .append(" tool call(s) · <a href='/marker?k=").append(enc(key))
                                .append("&a=").append(who).append("'>open</a></span></div>");
                    }
                    continue;
                }
                String last = said.get(said.size() - 1);
                b.append("<div class='ev asked'><span class=who>").append(esc(who))
                        .append("</span><span class=kind>")
                        .append(said.size() > 1 ? "final of " + said.size() + " attempts" : "answered")
                        .append(" · <a href='/marker?k=").append(enc(key)).append("&a=").append(who)
                        .append("'>open</a></span><pre>").append(esc(field(last, "reply")))
                        .append("</pre></div>");
            }
            return b.toString();
        }

        StringBuilder tools = new StringBuilder();
        int calls = 0;
        for (String e : mine) {
            if (field(e, "kind").equals("tool") && field(e, "agent").endsWith(agent)) {
                calls++;
                // IN FULL. The argument to write_file IS the test, and cutting it at 110 characters
                // shows the path and hides the only thing worth reading.
                tools.append(field(e, "tool")).append("  ").append(field(e, "arguments"))
                        .append('\n').append("    → ")
                        .append(field(e, "result").replace("\n", "\n    "))
                        .append("\n\n");
            }
        }

        List<String> said = asked(mine, agent);
        if (said.isEmpty()) {
            // AN AGENT MID-ANSWER HAS NOT "NOT RUN". It reads and greps for minutes before its first
            // token, and reporting that as nothing throws away the only view of what it is doing.
            if (calls == 0) {
                return b.append("<div class=empty>").append(esc(agent))
                        .append(" has not run for this marker.</div>").toString();
            }
            return b.append("<div class='ev asked'><span class=who>").append(esc(agent))
                    .append("</span><span class=kind>working — ").append(calls)
                    .append(" tool call(s), no answer yet</span></div>")
                    .append("<div class='ev tool'>")
                    .append(fold("what it has reached for", tools.toString(), expand))
                    .append("</div>").toString();
        }
        int i = mine.indexOf(said.get(said.size() - 1));
        String last = said.get(said.size() - 1);
        b.append("<div class='ev asked'><span class=who>").append(esc(agent))
                .append("</span><span class=kind>")
                .append(said.size() > 1 ? "final answer, attempt " + said.size() : "answered")
                .append("</span><pre>").append(esc(field(last, "reply"))).append("</pre>")
                .append(fold("the prompt it was given", field(last, "prompt"), expand))
                .append(rate(key, agent, i, "/marker?k=" + enc(key) + "&a=" + agent,
                        field(last, "prompt"), field(last, "reply")))
                .append("</div>");

        for (int n = said.size() - 2; n >= 0; n--) {
            String earlier = said.get(n);
            b.append("<div class='ev tool'><span class=kind>attempt ").append(n + 1)
                    .append(", superseded</span>")
                    .append(fold("what it said", field(earlier, "reply"), expand))
                    .append(fold("the prompt", field(earlier, "prompt"), expand)).append("</div>");
        }

        // WITH WHAT CAME BACK. A list of calls says what an agent looked for; only the results say
        // what it found, and "it grepped for Serializable" and "it grepped for Serializable and got
        // nothing" are different stories about the same answer.
        if (tools.length() > 0) {
            b.append("<div class='ev tool'>")
                    .append(fold("what it reached for", tools.toString(), expand)).append("</div>");
        }
        return b.toString();
    }

    /** Every {@code asked} by one agent, oldest first. */
    private static List<String> asked(List<String> events, String agent) {
        List<String> out = new ArrayList<>();
        for (String e : events) {
            if (field(e, "kind").equals("asked") && field(e, "agent").equals(agent)) {
                out.add(e);
            }
        }
        return out;
    }

    private static String tabs(String key, String current) {
        StringBuilder b = new StringBuilder("<nav class=tabs>")
                .append(tab(key, "", "summary", current));
        for (String a : AGENTS) {
            b.append(tab(key, a, a, current));
        }
        return b.append(tab(key, "trace", "the record", current))
                .append("<a href='/'>← all markers</a></nav>").toString();
    }

    private static String tab(String key, String a, String label, String current) {
        return "<a class='" + (a.equals(current) ? "on" : "") + "' href='/marker?k=" + enc(key)
                + (a.isEmpty() ? "" : "&a=" + a) + "'>" + esc(label) + "</a>";
    }

    /**
     * Everything that happened, in order — to one marker, or to all of them when {@code key} is empty.
     *
     * @param expand every fold open. Long, and exactly what a reader wants when the interesting part
     *               is a prompt rather than an answer.
     */
    private static String events(Path trace, Path settlements, String key, boolean expand,
                                 String nav) {
        List<String> mine = new ArrayList<>();
        for (String line : lines(trace)) {
            if (key.isEmpty() || field(line, "marker").equals(key)) {
                mine.add(line);
            }
        }
        String state = "";
        for (String line : lines(settlements)) {
            if (field(line, "suspicion_key").equals(key)) {
                state = field(line, "state");
            }
        }

        String title = key.isEmpty() ? "whole trace" : key.substring(key.lastIndexOf('/') + 1);
        String where = key.isEmpty() ? "every marker" : esc(key);
        StringBuilder b = head(title, where + " · " + mine.size() + " event(s)"
                + (state.isEmpty() ? "" : " · <span class='s " + css(state) + "'>"
                + esc(state) + "</span>"));
        b.append(nav).append("<a class=back href='")
                .append(key.isEmpty() ? "/trace" : "/marker?k=" + enc(key) + "&a=trace")
                .append(expand ? (key.isEmpty() ? "?fold=1" : "&fold=1") : "")
                .append("'>").append(expand ? "fold the long parts" : "open everything").append("</a>");
        if (mine.isEmpty()) {
            return b.append("<div class=empty>Nothing traced for this marker.</div>").toString();
        }

        String self = key.isEmpty() ? "/trace" : "/marker?k=" + enc(key);
        int i = -1;
        for (String e : mine) {
            String kind = field(e, "kind");
            i++;
            b.append("<div class='ev ").append(esc(kind)).append("'>");
            if (key.isEmpty()) {
                String m = field(e, "marker");
                b.append("<div class=k><a href='/marker?k=").append(enc(m)).append("'>")
                        .append(esc(m.substring(m.lastIndexOf('/') + 1))).append("</a></div>");
            }
            switch (kind) {
                case "asked" -> b.append("<span class=who>").append(esc(field(e, "agent")))
                        .append("</span><span class=kind>answered</span>")
                        .append("<pre>").append(esc(field(e, "reply"))).append("</pre>")
                        .append(fold("the prompt it was given", field(e, "prompt"), expand))
                        .append(rate(field(e, "marker"), field(e, "agent"), i, self,
                                field(e, "prompt"), field(e, "reply")));
                case "tool" -> b.append("<span class=who>").append(esc(field(e, "agent")))
                        .append("</span><span class=kind>").append(esc(field(e, "tool")))
                        .append("</span>")
                        .append(fold("arguments", field(e, "arguments"), expand))
                        .append(fold("what it returned", field(e, "result"), expand));
                case "built" -> b.append("<span class=who>").append(esc(field(e, "phase").toUpperCase()))
                        .append("</span><span class=kind>")
                        .append("true".equals(field(e, "infra")) ? "never ran"
                                : "true".equals(field(e, "passed")) ? "passed" : "failed")
                        .append("</span>").append(fold("build output", field(e, "summary"), expand));
                case "progress" -> b.append("<span class=kind>· ")
                        .append(esc(field(e, "note"))).append("</span>");
                case "settled" -> b.append("<span class='s ").append(esc(css(field(e, "state"))))
                        .append("'>").append(esc(field(e, "state"))).append("</span>")
                        .append("<pre>").append(esc(field(e, "because"))).append("</pre>");
                case "priced" -> b.append("<span class=who>")
                        .append(esc(field(e, "minutes"))).append(" min</span>")
                        .append("<span class=kind>human-equivalent</span><pre>")
                        .append(esc(field(e, "itemisation"))).append("</pre>");
                case "failed" -> b.append("<span class=who>failed</span><pre>")
                        .append(esc(field(e, "cause"))).append("</pre>");
                default -> b.append("<span class=kind>").append(esc(kind)).append("</span>");
            }
            b.append("</div>");
        }
        return b.toString();
    }

    /**
     * How far in, how long it has taken, and how long is left.
     *
     * <p>The ETA is settled markers over elapsed time, extrapolated — honest only while the markers
     * are alike, which they are not: one that the reproducer declines costs a minute and one that
     * goes red, green and two rounds with a skeptic costs twenty. It is shown because a wrong
     * estimate that converges beats no estimate at all, and it is labelled so nobody plans around it.
     *
     * @param total markers the run was given, or 0 when it was a single prove and there is no run
     */
    private static String progress(int total, int settled, long elapsed) {
        if (total <= 0) {
            return elapsed <= 0 ? ""
                    : "<div class=counts><div class=c><b>" + clock(elapsed) + "</b><span>elapsed</span></div></div>";
        }
        int pct = Math.min(100, settled * 100 / Math.max(1, total));
        String eta = settled > 0 && settled < total
                ? clock(elapsed / settled * (total - settled)) : "—";
        return "<div class=bar><i style='width:" + pct + "%'></i></div>"
                + "<div class=counts>"
                + "<div class=c><b>" + settled + " / " + total + "</b><span>" + pct + "% settled</span></div>"
                + "<div class=c><b>" + clock(elapsed) + "</b><span>elapsed</span></div>"
                + "<div class=c><b>" + eta + "</b><span>eta, extrapolated</span></div>"
                + "</div>";
    }

    /**
     * The rating control on one answer.
     *
     * <p>A plain form and a 303 back to the same page: rating six answers on a marker should be six
     * clicks and no navigation. The prompt and the reply ride along as hidden fields so the row the
     * server writes is a complete training example without a second read of the trace.
     */
    private static String rate(String marker, String agent, int event, String back,
                               String prompt, String reply) {
        StringBuilder f = new StringBuilder("<form class=rate method=post action='/feedback'>")
                .append(hidden("marker", marker)).append(hidden("agent", agent))
                .append(hidden("event", String.valueOf(event))).append(hidden("back", back))
                .append(hidden("prompt", prompt)).append(hidden("reply", reply))
                .append("<label>Tell this agent what it should have done</label>")
                .append("<textarea name=note rows=4 placeholder='")
                .append("Write it as you would say it to whoever wrote this answer. This text, the ")
                .append("prompt above and the reply become one training example.'></textarea>")
                .append("<button>save</button>");
        return f.append("</form>").toString();
    }

    private static String hidden(String name, String value) {
        return "<input type=hidden name='" + esc(name) + "' value='" + esc(value) + "'>";
    }

    /** One labelled example, appended. A malformed post costs a row, never the page. */
    private static void record(Path file, Map<String, String> form) {
        try {
            new Feedback(form.getOrDefault("marker", ""), form.getOrDefault("agent", ""),
                    (int) num(form.getOrDefault("event", "0")),
                    form.getOrDefault("note", ""), String.valueOf(System.currentTimeMillis()),
                    form.getOrDefault("prompt", ""), form.getOrDefault("reply", "")).appendTo(file);
        } catch (IOException e) {
            System.err.println("feedback: " + e.getMessage());
        }
    }

    /** application/x-www-form-urlencoded, which is what a form without JavaScript sends. */
    private static Map<String, String> form(HttpExchange e) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
            // An unreadable body is an empty form, and record() writes a row that says so.
        }
        return out;
    }

    /** RED and GREEN as they were recorded — the two facts a state alone does not carry. */
    private static String flags(String row) {
        if (row.isEmpty()) {
            return "";
        }
        String red = field(row, "red_verified");
        String green = field(row, "green_verified");
        if (red.isEmpty() && green.isEmpty()) {
            return "";
        }
        return "<div class=k>" + ("true".equals(red) ? "red ✓" : "red ·")
                + " " + ("true".equals(green) ? "green ✓" : "green ·") + "</div>";
    }

    /** The first line worth showing: agents answer with their verdict first and reasoning after. */
    private static String oneLine(String text) {
        for (String line : text.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty() && !t.equals("---")) {
                return t;
            }
        }
        return "";
    }

    private static String hm(int minutes) {
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    private static String clock(long millis) {
        long s = millis / 1000;
        return s < 60 ? s + "s" : s < 3600 ? (s / 60) + "m " + (s % 60) + "s"
                : (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    /** A field that should be a number, or 0 — a malformed one must not take the page down. */
    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    private static String fold(String label, String body, boolean expand) {
        return body.isEmpty() ? ""
                : "<details" + (expand ? " open" : "") + "><summary>" + esc(label) + " ("
                + body.length() + " chars)</summary><pre>" + esc(body) + "</pre></details>";
    }

    /**
     * OPEN UNLESS ASKED TO FOLD. A fold saves scrolling and costs a click on every single thing a
     * reader came to look at, and reading a prove is reading the prompts. {@code ?fold=1} collapses
     * them for anyone skimming a long record instead.
     */
    private static boolean open(HttpExchange e) {
        return query(e, "fold").isEmpty();
    }

    // ------------------------------------------------------------------ plumbing

    private static StringBuilder head(String title, String sub) {
        return new StringBuilder("<style>").append(CSS).append("</style>")
                .append("<meta http-equiv=refresh content=15>").append(KEEP_OPEN)
                .append("<header><h1>").append(esc(title)).append("</h1><div class=sub>")
                .append(sub).append("</div></header>");
    }

    /** Absent is not an error: a run that has settled nothing yet is the normal first state. */
    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A settled state is one CSS class; anything else is styled as infra. */
    private static String css(String state) {
        return state.matches("[a-z-]+") ? state : "infra";
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    /**
     * One field out of one line.
     *
     * <p>The rows are flat maps of strings written by {@link Settlement} and {@link JsonlTrace}, so
     * this stays a scan rather than a parser: a malformed line costs one blank cell, where a parser
     * would refuse the whole page.
     */
    private static String field(String json, String key) {
        int k = json.indexOf('"' + key + "\":");
        if (k < 0) {
            return "";
        }
        int open = json.indexOf('"', k + key.length() + 3);
        if (open < 0) {
            return "";
        }
        StringBuilder v = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                v.append(switch (n) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else if (c == '"') {
                break;
            } else {
                v.append(c);
            }
        }
        return v.toString();
    }

    private static String query(HttpExchange e, String name) {
        String q = e.getRequestURI().getRawQuery();
        if (q == null) {
            return "";
        }
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void send(HttpExchange e, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", type);
        e.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = e.getResponseBody()) {
            out.write(bytes);
        }
    }
}
