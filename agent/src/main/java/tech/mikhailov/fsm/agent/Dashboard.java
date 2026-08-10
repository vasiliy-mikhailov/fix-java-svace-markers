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
            .rate{margin-top:8px;display:flex;gap:6px;flex-wrap:wrap}
            .rate select,.rate input,.rate button{background:#0d1117;color:#c9d1d9;border:1px solid #30363d;
            border-radius:6px;padding:4px 8px;font:inherit;font-size:11px}
            .rate input{flex:1;min-width:180px}.rate button{cursor:pointer;border-color:#1f6feb;color:#58a6ff}
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
                events(trace, settlements, query(e, "k"), open(e))));
        // THE WHOLE TRACE, every marker, in the order it happened. The per-marker view answers "why
        // did this settle so"; this one answers "what is this thing doing", which is a different
        // question and the one asked while a run is in flight.
        server.createContext("/trace", e -> send(e, "text/html; charset=utf-8",
                events(trace, settlements, "", open(e))));
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
        b.append("</div><table><tr><th>marker</th><th>state</th>"
                + "<th>human-equiv</th><th>took</th><th>dialog</th></tr>");

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
            b.append("<tr><td><a href='/marker?k=").append(enc(key)).append("'>")
                    .append(esc(file.substring(file.lastIndexOf('/') + 1))).append("</a><div class=k>")
                    .append(esc(checker)).append("</div></td><td><span class='s ")
                    .append(esc(css(state))).append("'>").append(esc(state))
                    .append("</span></td><td>").append(mins > 0 ? hm(mins) : "<span class=k>—</span>")
                    .append("</td><td class=k>").append(took > 0 ? clock(took) : "—")
                    .append("</td><td class=k>")
                    .append(events.getOrDefault(key, 0)).append(" event(s)</td></tr>");
        });
        return b.append("</table><a class=back href='/trace'>the whole trace, every marker →</a>")
                .toString();
    }

    // ----------------------------------------------------------------- marker

    /**
     * Everything that happened, in order — to one marker, or to all of them when {@code key} is empty.
     *
     * @param expand every fold open. Long, and exactly what a reader wants when the interesting part
     *               is a prompt rather than an answer.
     */
    private static String events(Path trace, Path settlements, String key, boolean expand) {
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
        b.append("<a class=back href='/'>← all markers</a> ")
                .append("<a class=back href='").append(key.isEmpty() ? "/trace" : "/marker?k=" + enc(key))
                .append(expand ? "" : (key.isEmpty() ? "?raw=1" : "&raw=1"))
                .append("'>").append(expand ? "collapse" : "expand everything").append("</a>");
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
                        .append("</span><div class=k>").append(esc(cut(field(e, "arguments"), 160)))
                        .append("</div>").append(fold("what it returned", field(e, "result"), expand));
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
                .append("<select name=kind>");
        for (String k : Feedback.KINDS) {
            f.append("<option>").append(esc(k)).append("</option>");
        }
        return f.append("</select><input name=note placeholder='what a reviewer would say'>")
                .append("<button>label</button></form>").toString();
    }

    private static String hidden(String name, String value) {
        return "<input type=hidden name='" + esc(name) + "' value='" + esc(value) + "'>";
    }

    /** One labelled example, appended. A malformed post costs a row, never the page. */
    private static void record(Path file, Map<String, String> form) {
        try {
            new Feedback(form.getOrDefault("marker", ""), form.getOrDefault("agent", ""),
                    (int) num(form.getOrDefault("event", "0")), form.getOrDefault("kind", ""),
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

    private static boolean open(HttpExchange e) {
        return !query(e, "raw").isEmpty();
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
