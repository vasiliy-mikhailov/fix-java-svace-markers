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
            .infra{background:#2d1618;color:#f85149}
            .proving{background:#122033;color:#58a6ff}
            .proving::before{content:"● ";animation:p 1.4s ease-in-out infinite}
            @keyframes p{0%,100%{opacity:1}50%{opacity:.25}}
            .ev{border-left:2px solid #21262d;margin:0 24px;padding:12px 0 12px 16px}
            .ev.asked{border-color:#58a6ff}.ev.built{border-color:#d29922}.ev.settled{border-color:#3fb950}
            .ev.failed{border-color:#f85149}.ev.tool{border-color:#30363d}
            .who{color:#58a6ff;font-weight:600}.kind{color:#7d8590;font-size:11px;text-transform:uppercase;
            letter-spacing:.06em;margin-left:8px}
            pre{white-space:pre-wrap;word-break:break-word;background:#161b22;border:1px solid #21262d;
            border-radius:6px;padding:10px;margin:8px 0;overflow-x:auto;font-size:12px;line-height:1.5}
            details{margin:6px 0}summary{cursor:pointer;color:#7d8590;font-size:11px;user-select:none}
            summary:hover{color:#c9d1d9}
            .empty{padding:48px 24px;color:#7d8590}.back{padding:14px 24px;display:block}
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
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8087;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/settlements", e -> send(e, "application/json",
                "[" + String.join(",", lines(settlements)) + "]"));
        server.createContext("/api/trace", e -> send(e, "application/json",
                "[" + String.join(",", lines(trace)) + "]"));
        server.createContext("/marker", e -> send(e, "text/html; charset=utf-8",
                events(trace, settlements, query(e, "k"), open(e))));
        // THE WHOLE TRACE, every marker, in the order it happened. The per-marker view answers "why
        // did this settle so"; this one answers "what is this thing doing", which is a different
        // question and the one asked while a run is in flight.
        server.createContext("/trace", e -> send(e, "text/html; charset=utf-8",
                events(trace, settlements, "", open(e))));
        server.createContext("/", e -> send(e, "text/html; charset=utf-8",
                index(settlements, trace)));
        server.start();
        System.out.println("dashboard on http://127.0.0.1:" + port + "  reading " + settlements);
    }

    // ------------------------------------------------------------------ index

    private static String index(Path settlements, Path trace) {
        Map<String, String> latest = new LinkedHashMap<>();
        for (String line : lines(settlements)) {
            latest.put(field(line, "suspicion_key"), line);
        }
        Map<String, Integer> events = new LinkedHashMap<>();
        for (String line : lines(trace)) {
            events.merge(field(line, "marker"), 1, Integer::sum);
        }

        StringBuilder b = head("markers", latest.size() + " marker(s) · "
                + events.values().stream().mapToInt(Integer::intValue).sum() + " trace event(s)");
        if (latest.isEmpty()) {
            return b.append("<div class=empty>No prove has run yet.</div>").toString();
        }

        Map<String, Integer> counts = new TreeMap<>();
        latest.values().forEach(r -> counts.merge(field(r, "state"), 1, Integer::sum));
        b.append("<div class=counts>");
        counts.forEach((k, n) -> b.append("<div class=c><b>").append(n).append("</b><span>")
                .append(esc(k)).append("</span></div>"));
        b.append("</div><table><tr><th>marker</th><th>state</th><th>dialog</th></tr>");

        latest.forEach((key, row) -> {
            String file = field(row, "file");
            String state = field(row, "state");
            b.append("<tr><td><a href='/marker?k=").append(enc(key)).append("'>")
                    .append(esc(file.substring(file.lastIndexOf('/') + 1))).append("</a><div class=k>")
                    .append(esc(field(row, "svace_checker"))).append("</div></td><td><span class='s ")
                    .append(esc(css(state))).append("'>").append(esc(state))
                    .append("</span></td><td class=k>")
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

        for (String e : mine) {
            String kind = field(e, "kind");
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
                        .append(fold("the prompt it was given", field(e, "prompt"), expand));
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
                case "failed" -> b.append("<span class=who>failed</span><pre>")
                        .append(esc(field(e, "cause"))).append("</pre>");
                default -> b.append("<span class=kind>").append(esc(kind)).append("</span>");
            }
            b.append("</div>");
        }
        return b.toString();
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
