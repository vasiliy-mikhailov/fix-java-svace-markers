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
            .sev{font-size:11px;padding:2px 6px;border-radius:3px;background:#21262d;color:#7d8590}
            .sev.critical{background:#3d1113;color:#ff7b72}
            .sev.major{background:#3b2300;color:#f0883e}
            .sev.minor{background:#161b22;color:#7d8590}
            .sev.normal{background:#132132;color:#79c0ff}
            td.why{max-width:44em}
            td.why summary{cursor:pointer;color:#9198a1;font-size:12px;line-height:1.45}
            td.why pre{white-space:pre-wrap;font-size:12px;margin:6px 0 0}
            pre.flagged{background:#0d1117;border-left:2px solid #30363d;padding:8px 10px;
              white-space:pre;overflow-x:auto;color:#8b949e;font-size:11.5px;line-height:1.5}
            .alarm{border:1px solid #30363d;background:#12161c;border-radius:6px;
                   padding:.7rem .9rem;margin:.6rem 0}
            .alarm>a{color:#d7dbe0;text-decoration:none}
            .alarm .hold{color:#f85149}
            .alarm ul{margin:.45rem 0 0;padding-left:1.1rem}
            .alarm li{margin:.15rem 0}
            .alarm li a{color:#d7dbe0;text-decoration:none;font-size:.85rem}
            .alarm .v{display:inline-block;min-width:4.7rem;margin-right:.55rem;padding:0 .3rem;
                      border-radius:3px;font-size:.68rem;text-align:center;vertical-align:1px}
            .alarm .v.holds{background:#3d1518;color:#ff7b72}
            .alarm.some-hold{border-color:#5a2a2c}
            .alarm .v.unjudged{background:#2b2011;color:#d29922}
            .alarm .v.refuted{background:#161b22;color:#6e7681}
            .alarm li a:hover{text-decoration:underline}
            .alarm details.rest{margin-top:.3rem}
            .alarm details.rest>summary{color:#8b949e;font-size:.85rem;cursor:pointer;
            list-style:none;padding-left:1.1rem}
            .alarm details.rest>summary::before{content:"\u25b8 ";display:inline-block;width:1em}
            .alarm details.rest[open]>summary::before{content:"\u25be "}
            .alarm details.rest>summary:hover{text-decoration:underline}
            .ev:target{outline:2px solid #f85149;outline-offset:3px;scroll-margin-top:1rem}
            .live{margin:.6rem 0}
            .live details.stream{border:1px solid #21262d;border-radius:6px;margin:.3rem 0;
                                 background:#0d1117}
            .live details.stream>summary{padding:.4rem .7rem;cursor:pointer;font-size:.82rem;
                                         color:#8b949e;list-style:none}
            .live details.stream>summary::before{content:"\u25b8 ";display:inline-block;width:1em}
            .live details.stream[open]>summary::before{content:"\u25be "}
            .live details.stream>pre{margin:0;padding:.1rem .8rem .7rem;max-height:20rem;
                                     overflow:auto;font-size:.76rem;color:#a371f7;
                                     white-space:pre-wrap;word-break:break-word}
            p.account{margin:.5rem 0 .2rem;font-size:.95rem;line-height:1.7;color:#d7dbe0;
                      max-width:52em}
            .ev.thought{border-left-color:#6e5494}
            .ev.thought .who{color:#a371f7}
            .false-positive,.by-design,.unprovable,.not-a-bug{background:#161b22;color:#8b949e}
            .queued{background:#0d1117;color:#6e7681;border:1px solid #21262d}
            .sema{display:flex;gap:5px;margin-top:5px}
            .sema i{width:9px;height:9px;border-radius:50%;display:block;border:1px solid #30363d}
            .sema i.lit.red{background:#f85149;border-color:#f85149;box-shadow:0 0 5px #f8514966}
            .sema i.lit.green{background:#3fb950;border-color:#3fb950;box-shadow:0 0 5px #3fb95066}
            .sema i.dim{background:#21262d}
            .sema i.none{background:transparent;border-style:dashed}
            .interrupted{background:#20161f;color:#bc8cff;border:1px solid #21262d}
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
    /**
     * Live, without throwing away the reader's place.
     *
     * <p>The trace is append-only, so a page showing events can ASK FOR THE NEW ONES and put them at
     * the bottom — every fold stays as it was and the scroll does not move. The index has no state in
     * it, so it re-renders wholesale and only the scroll is restored.
     */
    private static final String LIVE = """
            <script>
            (function(){
              var src = new EventSource('/events');
              var here = location.pathname + location.search;
              var isList = location.pathname === '/' || location.pathname === '/dashboard/';
              src.onmessage = function(m){
                var n = JSON.parse(m.data);
                if (isList) {
                  fetch(here, {headers:{'X-Fragment':'1'}}).then(function(r){return r.text()}).then(function(html){
                    var y = window.scrollY;
                    document.body.innerHTML = html;
                    if (window.__keepOpen) window.__keepOpen();
                    window.scrollTo(0, y);
                  });
                  return;
                }
                var seen = +(document.body.dataset.events || 0);
                if (n.trace <= seen) return;
                fetch(here + (here.indexOf('?') < 0 ? '?' : '&') + 'from=' + seen,
                      {headers:{'X-Fragment':'1'}})
                  .then(function(r){return r.text()}).then(function(html){
                    if (!html.trim()) return;
                    document.body.insertAdjacentHTML('beforeend', html);
                    document.body.dataset.events = n.trace;
                  });
              };
            })();
            </script>
            """;

    /**
     * WHAT THE READER OPENED STAYS OPEN.
     *
     * <p>Two bugs lived here. The restore ran inline at the top of the document, where
     * {@code querySelectorAll('details')} matches nothing because none of them are parsed yet — so
     * every fold was faithfully SAVED and never once put back. And the key was a fold's position
     * among all folds on the page, which on the index moves every time a marker settles, so even a
     * restore that ran would have opened somebody else's row.
     *
     * <p>Now: restored after the document is parsed, and keyed by id where a fold has one, so the
     * supervisor's expander is remembered as itself and not as "the first fold".
     */
    private static final String KEEP_OPEN = """
            <script>
            (function(){
              var K='open:'+location.pathname+location.search, S=sessionStorage;
              function all(){return [].slice.call(document.querySelectorAll('details'))}
              function name(d,i){return d.id||('#'+i)}
              function save(){
                try{
                  var open=[]; all().forEach(function(d,i){ if(d.open) open.push(name(d,i)) });
                  S.setItem(K,JSON.stringify(open));
                }catch(e){}
              }
              function restore(keepScroll){
                try{
                  var open=JSON.parse(S.getItem(K)||'[]');
                  all().forEach(function(d,i){ if(open.indexOf(name(d,i))>=0) d.open=true });
                  if(!keepScroll){ var y=+S.getItem(K+':y'); if(y) window.scrollTo(0,y); }
                }catch(e){}
              }
              if(document.readyState==='loading'){
                document.addEventListener('DOMContentLoaded',function(){restore(false)});
              }else{ restore(false); }
              document.addEventListener('toggle',save,true);
              addEventListener('scroll',function(){ try{S.setItem(K+':y',window.scrollY)}catch(e){} },{passive:true});
              // THE LIST REPLACES ITS WHOLE BODY WHEN AN EVENT ARRIVES, so nothing fires again:
              // not DOMContentLoaded, and not the scripts in the replacement, which innerHTML does
              // not execute. The listener above is on `document` and survives; this is how the
              // swap asks for the folds back. The scroll is the caller's to keep.
              window.__keepOpen=function(){ restore(true) };
              // WHAT IS BEING SAID NOW, every two seconds, into its own container. The open/closed
              // state of these folds is the reader's, so it is read off the page before the swap
              // and put back after — sessionStorage is for folds that survive a reload, and these
              // are replaced far too often to go through it.
              function poll(){
                var box=document.getElementById('live'); if(!box) return;
                fetch(box.dataset.live||'/live').then(function(r){return r.text()}).then(function(html){
                  var box=document.getElementById('live'); if(!box) return;
                  var shut={};
                  [].forEach.call(box.querySelectorAll('details'),function(d){ shut[d.id]=!d.open });
                  box.innerHTML=html;
                  [].forEach.call(box.querySelectorAll('details'),function(d){
                    if(shut[d.id]) d.open=false;
                  });
                }).catch(function(){});
              }
              setInterval(poll,2000);
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
        // A THREAD PER REQUEST, because one of them blocks for half an hour. With the default
        // executor every handler runs on the dispatcher thread, so a single reader holding the
        // event stream open stops the server answering anything at all — the page, the API and the
        // next reader's stream included. Cached rather than fixed: streams are idle almost always,
        // and a fixed pool of n stops serving at the n+1th reader.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dashboard");
            t.setDaemon(true);
            return t;
        }));
        route(server, "/api/settlements", e -> send(e, "application/json",
                "[" + String.join(",", lines(settlements)) + "]"));
        route(server, "/api/trace", e -> send(e, "application/json",
                "[" + String.join(",", lines(trace)) + "]"));
        // THE CORPUS. Every labelled example, prompt and reply included, ready to train on with no
        // join back to the trace.
        route(server, "/api/feedback", e -> send(e, "application/json",
                "[" + String.join(",", lines(feedback)) + "]"));
        route(server, "/feedback", e -> {
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
        route(server, "/marker", e -> send(e, "text/html; charset=utf-8",
                marker(trace, settlements, query(e, "k"), query(e, "a"), open(e),
                        (int) num(query(e, "from")), fragment(e))));
        // THE WHOLE TRACE, every marker, in the order it happened. The per-marker view answers "why
        // did this settle so"; this one answers "what is this thing doing", which is a different
        // question and the one asked while a run is in flight.
        route(server, "/trace", e -> send(e, "text/html; charset=utf-8",
                events(trace, settlements, "", open(e), "",
                        (int) num(query(e, "from")), fragment(e))));
        // WHAT IS WRONG WITH THE PIPELINE, as opposed to with a marker. Its own page because it is
        // its own question: every other view here answers "what happened to this defect".
        // THE SUPERVISOR IS AN AGENT AND ITS RECORD IS READ THE SAME WAY. Its findings answer
        // "what is wrong"; its trace answers "and how do you know", which is the question a reader
        // brings the moment a finding surprises them. Without it the watcher was the one thing in
        // this program asserting things with no way to check them.
        route(server, "/overwatch", e -> send(e, "text/html; charset=utf-8",
                overwatch(settlements.resolveSibling("overwatch.jsonl"),
                        settlements.resolveSibling("restarts.jsonl"),
                        settlements.resolveSibling("overwatch-trace.jsonl"),
                        settlements.resolveSibling("overwatch-settlements.jsonl"),
                        query(e, "a"), open(e), (int) num(query(e, "from")), fragment(e))));
        // WHAT IS BEING SAID RIGHT NOW. Polled rather than pushed: the event stream fires when the
        // counts move, and an agent reasoning for four minutes moves no counts — which is precisely
        // the stretch worth watching. No key is the supervisor; a key is that one prove.
        //
        // REGISTERED THE WAY THE OTHERS ARE. It was written as server.createContext, which every
        // other route here stopped using when they gained an error page, so it registered nothing
        // and every request for it fell through to `/` — the poller then wrote a copy of the entire
        // index page inside the index page, header, banner and all.
        route(server, "/live", e -> send(e, "text/html; charset=utf-8",
                live(settlements.getParent() == null ? Path.of(".") : settlements.getParent(),
                        query(e, "k"))));
        route(server, "/", e -> send(e, "text/html; charset=utf-8",
                index(settlements, trace, lines(settlements.resolveSibling("markers.txt")))));

        // SERVER-SENT EVENTS. The page used to reload itself every fifteen seconds, which is why it
        // needed a script to put the reader's folds back afterwards: a full reload throws away
        // everything they had open and where they were. One long-lived connection per reader, a line
        // when the counts move, and the page decides what to do with it — append, or re-render the
        // parts that have no state in them.
        route(server, "/events", e -> {
            e.getResponseHeaders().set("Content-Type", "text/event-stream");
            e.getResponseHeaders().set("Cache-Control", "no-cache");
            e.getResponseHeaders().set("Connection", "keep-alive");
            try {
                e.sendResponseHeaders(200, 0);
                OutputStream out = e.getResponseBody();
                int lastTrace = -1;
                int lastSettled = -1;
                // Bounded: a reader who closed the tab should not hold a thread for the run's life,
                // and EventSource reconnects on its own when the stream ends.
                for (int tick = 0; tick < 900; tick++) {
                    int t = lines(trace).size();
                    int s = lines(settlements).size();
                    if (t != lastTrace || s != lastSettled) {
                        lastTrace = t;
                        lastSettled = s;
                        out.write(("data: {\"trace\":" + t + ",\"settled\":" + s + "}\n\n")
                                .getBytes(StandardCharsets.UTF_8));
                    } else {
                        // A comment keeps the connection alive through a proxy that times idle ones
                        // out — Caddy sits in front of this.
                        out.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                    }
                    out.flush();
                    Thread.sleep(2000);
                }
            } catch (IOException | InterruptedException gone) {
                // The reader navigated away. Nothing to clean up and nothing worth logging.
            } finally {
                e.close();
            }
        });
        server.start();
        System.out.println("dashboard on http://127.0.0.1:" + port + "  reading " + settlements);
    }

    // ------------------------------------------------------------------ index

    /**
     * The slug a claim directory is named by — the same rule {@code entrypoint.sh} slugs with.
     *
     * <p>Everything after the last slash, non-alphanumerics replaced, cut to eighty. It has to match
     * exactly: a claim this cannot find reads as a marker nobody is working on.
     */
    private static String slug(String marker) {
        String tail = marker.substring(marker.lastIndexOf('/') + 1)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        return tail.length() <= 80 ? tail : tail.substring(0, 80);
    }

    /**
     * THE SUPERVISOR'S FINDINGS, worst first, each one feedback-able like any other answer.
     *
     * <p>An unjudged finding is shown, and shown as unjudged: a critic that could not be reached must
     * not be able to suppress a warning, and a reader deciding what to act on needs to know which of
     * the two they are looking at.
     */
    private static String overwatch(Path findings, Path restarts, Path trace, Path settlements,
            String view, boolean expand, int from, boolean fragment) {
        String nav = supervisorTabs(view);
        if (view.equals("trace")) {
            return supervisorRecord(trace, nav, expand);
        }
        if (view.equals("overwatch") || view.equals("overwatch-critic")) {
            return supervisorAgent(trace, view, nav, expand);
        }
        return reported(findings, restarts, nav, expand);
    }

    /** The supervisor's own tabs. {@link #tab} builds marker URLs and these are not markers. */
    private static String supervisorTabs(String current) {
        StringBuilder b = new StringBuilder("<nav class=tabs>");
        for (String[] t : new String[][] {{"", "findings"}, {"overwatch", "overwatch"},
                {"overwatch-critic", "overwatch-critic"}, {"trace", "the record"}}) {
            b.append("<a class='").append(t[0].equals(current) ? "on" : "")
                    .append("' href='/overwatch").append(t[0].isEmpty() ? "" : "?a=" + t[0])
                    .append("'>").append(esc(t[1])).append("</a>");
        }
        return b.append("<a href='/'>&larr; all markers</a></nav>").toString();
    }

    /**
     * THE SUPERVISOR'S WHOLE RECORD, and whole means whole.
     *
     * <p>Eight megabytes of it after an afternoon, most of it the traces the watcher opened. It is
     * not trimmed and it is not capped: a page that quietly shows part of a record reads as the
     * record, and every cut this program has made to a payload for a reader's convenience has later
     * turned out to remove the field somebody needed. If a browser will not open it, that is a
     * complaint worth having and acting on, and not one worth guessing at first.
     */
    private static String supervisorRecord(Path trace, String nav, boolean expand) {
        // A COPY, BECAUSE read() HANDS BACK AN IMMUTABLE LIST. Sorting it threw
        // UnsupportedOperationException out of the handler, which HttpServer answers by closing the
        // connection with no status and no log line — an empty reply in twenty milliseconds, which
        // reads exactly like a page too big to build and is nothing of the kind.
        List<String> all = new ArrayList<>(read(trace));
        all.sort((a, b) -> Long.compare(num(field(b, "at")), num(field(a, "at"))));
        return supervisorEvents(all, "the supervisor's record", nav, expand, "/overwatch?a=trace");
    }

    /** Newest first, all of it. */
    private static String supervisorEvents(List<String> all, String title, String nav,
            boolean expand, String self) {
        StringBuilder b = head(title, all.size() + " event(s), newest first").append(nav);
        if (all.isEmpty()) {
            return b.append("<div class=empty>Nothing yet. The supervisor looks on its own "
                    + "schedule.</div>").toString();
        }
        for (int i = 0; i < all.size(); i++) {
            b.append(one(all.get(i), "", expand, i, self));
        }
        return b.toString();
    }


    /**
     * One supervisor agent: what it said, and what it worked through to say it.
     *
     * <p>Newest first here, unlike a marker's tab. A prove is read after it settles and its story
     * runs forwards; the supervisor is read while it is running, and the question is always what it
     * just said.
     */
    private static String supervisorAgent(Path trace, String agent, String nav, boolean expand) {
        List<String> mine = new ArrayList<>();
        for (String line : read(trace)) {
            if (field(line, "agent").equals(agent)) {
                mine.add(line);
            }
        }
        mine.sort((a, b) -> Long.compare(num(field(b, "at")), num(field(a, "at"))));
        return supervisorEvents(mine, agent, nav, expand, "/overwatch?a=" + agent);
    }

    /**
     * THE SUPERVISOR'S FINDINGS, worst first, each one feedback-able like any other answer.
     *
     * <p>An unjudged finding is shown, and shown as unjudged: a critic that could not be reached must
     * not be able to suppress a warning, and a reader deciding what to act on needs to know which of
     * the two they are looking at.
     */
    /**
     * THE MARKERS A FINDING NAMES, MADE REACHABLE.
     *
     * <p>A finding says "3 markers" and names them as the directory slugs it read — and the reader
     * who wants to check the claim then has to copy a slug, guess the marker key it came from and
     * paste it into a URL. The names are already exact; this only makes them what they look like.
     *
     * <p>Escaped first and linked second, so nothing in a finding an agent wrote can put markup on
     * this page.
     */
    private static String linked(String finding, Map<String, String> markers) {
        String safe = esc(finding);
        for (Map.Entry<String, String> e : markers.entrySet()) {
            String slug = e.getKey();
            if (!safe.contains(slug)) {
                continue;
            }
            safe = safe.replace(slug, "\u0000<a href='/marker?k=" + enc(e.getValue()) + "'>"
                    + slug + "</a>\u0001");
        }
        // Two passes would relink the href of a slug that is a prefix of another. The sentinels
        // mark what is already done, and come out at the end.
        return safe.replace("\u0000", "").replace("\u0001", "");
    }

    /** Every queued marker, by the directory name the pool gives it. */
    private static Map<String, String> slugs(Path markersFile) {
        Map<String, String> byLength = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(read(markersFile));
        // LONGEST FIRST, so a slug that contains a shorter one is linked whole rather than having
        // its middle replaced.
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String key : keys) {
            String trimmed = key.strip();
            if (!trimmed.isEmpty()) {
                byLength.put(Supervisor.slug(trimmed), trimmed);
            }
        }
        return byLength;
    }

    private static String reported(Path findings, Path restarts, String nav, boolean expand) {
        Map<String, String> markers = slugs(findings.resolveSibling("markers.txt"));
        List<String> all = read(findings);
        List<String> cut = read(restarts);
        long holds = all.stream().filter(l -> field(l, "verdict").equals("holds")).count();
        long refuted = all.stream().filter(l -> field(l, "verdict").equals("refuted")).count();
        long unjudged = all.size() - holds - refuted;
        StringBuilder b = head("What is wrong with the pipeline",
                all.isEmpty() ? "the supervisor has not reported yet"
                        : holds + " hold, " + refuted + " refuted, " + unjudged + " unjudged"
                                + " &middot; " + cut.size() + " prove(s) restarted")
                .append(nav);

        if (!cut.isEmpty()) {
            StringBuilder tree = new StringBuilder();
            for (String line : cut) {
                tree.append(field(line, "id")).append("  attempt ").append(field(line, "attempt"))
                        .append("  killed=").append(field(line, "killed")).append('\n')
                        .append("    ").append(field(line, "why")).append("\n\n");
            }
            b.append("<div class='ev tool'><span class=kind>the tree was cut here</span>")
                    .append(fold(cut.size() + " restart(s)", tree.toString(), expand))
                    .append("</div>");
        }

        // HOLDS FIRST, because a reader with ten minutes should spend them on what survived being
        // attacked. Refuted findings stay on the page: what the watcher wrongly worries about is
        // itself a thing to fix in the watcher.
        for (String want : new String[] {"holds", "unjudged", "refuted"}) {
            for (int i = 0; i < all.size(); i++) {
                String line = all.get(i);
                String verdict = field(line, "verdict");
                if (verdict.isBlank()) {
                    verdict = "unjudged";
                }
                if (!verdict.equals(want)) {
                    continue;
                }
                b.append("<div id='f").append(i).append("' class='ev ")
                        .append(verdict.equals("refuted") ? "tool" : "asked")
                        .append("'><span class=who>overwatch</span><span class='s ")
                        .append(verdict.equals("holds") ? "settled"
                                : verdict.equals("refuted") ? "infra" : "needs-review")
                        .append("'>").append(verdict).append("</span>")
                        .append("<pre>").append(linked(field(line, "finding"), markers))
                        .append("</pre>")
                        .append(fold("what the critic said", field(line, "judgement"), expand))
                        .append(rate("overwatch", "overwatch", i, "/overwatch",
                                field(line, "finding"), field(line, "judgement")))
                        .append("</div>");
            }
        }
        if (all.isEmpty()) {
            b.append("<div class=empty>Nothing reported yet. The supervisor runs on its own "
                    + "schedule and reports only patterns, so a quiet page is a good sign. "
                    + "Its record is on the tabs above whether or not it has concluded "
                    + "anything.</div>");
        }
        return b.toString();
    }

    /**
     * WHAT THE ANALYSER THOUGHT IT WAS WORTH, joined from the run it came out of.
     *
     * <p>A marker key is repo|file|line|checker and carries no severity, and adding a fifth field
     * would change every key — re-proving the whole queue to display one word. So this is reference
     * data, read beside the queue and joined on filename, line and checker.
     *
     * <p>It covers the 282 markers that analyser run reported. The other 74 are src/it and src/test,
     * which it excluded, and they get "—" rather than a guess: a table that prints Minor for
     * everything it does not know about is worse than one that admits the gap.
     */
    private static Map<String, String> severities(Path beside) {
        Map<String, String> by = new LinkedHashMap<>();
        for (String line : read(beside.resolveSibling("severities.tsv"))) {
            String[] f = line.split("\\t");
            if (f.length >= 4) {
                by.put(f[0] + "|" + f[1] + "|" + f[2], f[3]);
            }
        }
        return by;
    }

    /**
     * WHAT THE SUPERVISOR FOUND, WHERE IT CANNOT BE MISSED.
     *
     * <p>Only what survived its critic: `holds` earns the banner and `refuted` does not appear.
     * `unjudged` does — a finding the critic never reached is not one it dismissed, and the
     * direction that costs least is to show it.
     *
     * <p>Newest first, and the count is the point. One finding is a curiosity; nineteen holding
     * findings on a run that is still going is the reason to stop and read them.
     */
    private static String warnings(Path findings) {
        // THE POSITION IN THE FILE IS THE NAME. The findings page groups by verdict, so its
        // display order is not the file's — an anchor computed from where a finding happens to be
        // rendered would move the moment its critic answered. This one does not.
        // WHAT SURVIVED FIRST, AND WHAT DID NOT STILL SHOWN. A refuted finding was left off this
        // banner as noise, and that was wrong twice over: it made a list of twenty-two closed and
        // open complaints look like twenty-two open ones, and it hid the one thing a reader needs
        // to decide where to spend ten minutes — whether anybody has checked this yet. Holding
        // first, unjudged next because nobody has looked, refuted last and greyed.
        List<String> all = read(findings);
        List<String> live = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (String want : new String[] {"holds", "unjudged", "refuted"}) {
            for (int i = all.size() - 1; i >= 0; i--) {
                if (verdictOf(all.get(i)).equals(want)) {
                    live.add(all.get(i));
                    at.add(i);
                    tally.merge(want, 1, Integer::sum);
                }
            }
        }
        if (live.isEmpty()) {
            return "";
        }
        // THE BOX IS NOT THE ALARM. A red banner on every run teaches a reader to see past it,
        // and most of what is in here is a complaint that has already been answered. Only the
        // findings that survived their critic are coloured, and only they tint the border.
        int holding = tally.getOrDefault("holds", 0);
        StringBuilder sub = new StringBuilder();
        tally.forEach((v, n) -> sub.append(sub.length() == 0 ? "" : " \u00b7 ")
                .append(v.equals("holds") ? "<span class=hold>" + n + " hold</span>"
                        : n + " " + v));
        StringBuilder b = new StringBuilder("<div class='alarm"
                + (holding > 0 ? " some-hold" : "") + "'><a href='/overwatch'><b>")
                .append(live.size()).append(live.size() == 1 ? " finding" : " findings")
                .append(" from the supervisor</b> \u2014 ").append(sub).append("</a><ul>");
        int shown = Math.min(SHOWN, live.size());
        for (int i = 0; i < shown; i++) {
            b.append(item(live.get(i), at.get(i)));
        }
        b.append("</ul>");
        // THE REST OPEN WHERE THEY ARE. "and 17 more" was a link to the findings page, so reading
        // them meant leaving the run you were watching and finding your place again — and a count
        // is the one thing a list of complaints must not be reduced to, because the number is the
        // alarm and the complaints are the reason. Every line was already read from the file.
        if (live.size() > shown) {
            b.append("<details id=more class=rest><summary>and ").append(live.size() - shown)
                    .append(" more</summary><ul>");
            for (int i = shown; i < live.size(); i++) {
                b.append(item(live.get(i), at.get(i)));
            }
            b.append("</ul></details>");
        }
        return b.append("</div>").toString();
    }

    /**
     * How many findings stand open without being asked for.
     *
     * <p>Enough that the banner reads as a list rather than a headline, few enough that it does not
     * push the run's own table below the fold — which would trade one thing nobody reads for
     * another.
     */
    private static final int SHOWN = 5;

    /** A finding's verdict, with the two ways of recording "nobody judged it" collapsed into one. */
    private static String verdictOf(String finding) {
        String verdict = field(finding, "verdict");
        return verdict.isBlank() ? "unjudged" : verdict;
    }

    /**
     * One line of the banner: what the critic made of the finding, then the finding's own first
     * line, linked to itself on the findings page.
     *
     * <p>The heading marks come off because these were written as markdown for a page that renders
     * none, and a banner reading "## Finding:" five times says nothing five times.
     */
    private static String item(String finding, int position) {
        String first = field(finding, "finding").strip()
                .replaceAll("^[#*\\s]*(Finding:)?\\s*", "");
        int newline = first.indexOf('\n');
        String verdict = verdictOf(finding);
        return "<li><span class='v " + verdict + "'>" + verdict + "</span>"
                + "<a href='/overwatch#f" + position + "'>"
                + esc(newline < 0 ? first : first.substring(0, newline)) + "</a></li>";
    }

    /**
     * THE SUPERVISOR AND EVERY PROVE THAT IS SPEAKING, one fold each.
     *
     * <p>Five, because the pool runs four and the supervisor is one — but derived from what is
     * actually claimed rather than from the number four, so a run at a different width still shows
     * all of it.
     *
     * <p>A live file is only shown for a prove that is still CLAIMED. When a prove ends the file
     * stays behind holding its last answer, and a panel that went on showing it would be a live
     * view that is quietly a museum.
     */
    private static String live(Path results, String key) {
        // ONE PROVE PER PAGE. Four provers on the front page meant four folds nobody had asked for
        // above a table of 356 rows, and none of them was the marker the reader had come to look
        // at. A prove's live view belongs on that prove's page, next to its trace; the front page
        // keeps the supervisor, which is about the run rather than about any marker.
        if (!key.isEmpty()) {
            String id = Supervisor.slug(key);
            Path settlements = results.resolve("m").resolve(id).resolve("settlements.jsonl");
            if (settled(settlements)) {
                return "<div class=k>This prove has finished. What it said is on the tabs above; "
                        + "this view is only for one still running.</div>";
            }
            return panel(id, results.resolve("m").resolve(id).resolve("trace.jsonl.live"), true);
        }
        StringBuilder b = new StringBuilder();
        b.append(panel("supervisor", results.resolve("overwatch-trace.jsonl.live"), true));
        return b.toString();
    }

    /** Every prove still running, for the pages that want the whole pool. */
    private static String proving(Path results) {
        StringBuilder b = new StringBuilder();
        // CLAIMED IS NOT RUNNING. A claim is how the pool remembers it has already dealt with a
        // marker, so it OUTLIVES the prove — every settled marker still holds one, and taking the
        // claims directory as the list of active provers gave thirty-four panels for a pool of
        // four. A prove is running when it is claimed and has not settled, which is the same test
        // the pool itself uses to decide whether to take a marker again.
        List<String> claimed = new ArrayList<>();
        Path claims = results.resolve("claims");
        if (Files.isDirectory(claims)) {
            try (var dirs = Files.list(claims)) {
                dirs.map(d -> d.getFileName().toString()).sorted()
                        .filter(id -> !settled(results.resolve("m").resolve(id)
                                .resolve("settlements.jsonl")))
                        .forEach(claimed::add);
            } catch (IOException none) {
                // No claims directory is a run that has not started.
            }
        }
        for (String id : claimed) {
            b.append(panel(id, results.resolve("m").resolve(id).resolve("trace.jsonl.live"), true));
        }
        if (claimed.isEmpty()) {
            b.append("<div class=k>No prove is running.</div>");
        }
        return b.toString();
    }

    /**
     * Whether a prove reached one of the seven states this program decides.
     *
     * <p>The same seven the pool greps for. Anything else — {@code proving}, {@code infra}, an empty
     * file, no file — is a prove that has not finished, which is exactly what a live panel is for.
     */
    private static boolean settled(Path settlements) {
        for (String line : read(settlements)) {
            String state = field(line, "state");
            if (!state.isBlank() && !state.equals("proving") && !state.equals("infra")
                    && !state.equals("queued")) {
                return true;
            }
        }
        return false;
    }

    /** One fold: who is speaking, how long ago, and the last of what they have said. */
    private static String panel(String who, Path file, boolean open) {
        String text = "";
        String agent = "";
        long at = 0;
        if (Files.isReadable(file)) {
            try {
                String all = Files.readString(file);
                int one = all.indexOf('\n');
                int two = one < 0 ? -1 : all.indexOf('\n', one + 1);
                if (two > 0) {
                    agent = all.substring(0, one);
                    at = num(all.substring(one + 1, two));
                    text = all.substring(two + 1);
                }
            } catch (IOException | RuntimeException unreadable) {
                // Being rewritten as we read it. The next poll is 2 seconds away.
            }
        }
        long ago = at == 0 ? -1 : (System.currentTimeMillis() - at) / 1000;
        String label = esc(who.length() > 46 ? who.substring(0, 46) : who)
                + (agent.isEmpty() ? "" : " <span class=k>&middot; " + esc(agent) + "</span>")
                + (ago < 0 ? " <span class=k>&middot; nothing yet</span>"
                        : ago > 90 ? " <span class=k>&middot; quiet " + (ago / 60) + "m</span>"
                                : " <span class=k>&middot; " + ago + "s ago</span>");
        // THE TAIL, because a reasoning turn runs to tens of thousands of characters and the end is
        // where it is now. Opening on the beginning would show the same paragraph for four minutes.
        String tail = text.length() > LIVE_TAIL ? text.substring(text.length() - LIVE_TAIL) : text;
        return "<details class=stream" + (open ? " open" : "") + " id='live-" + esc(who) + "'>"
                + "<summary>" + label + "</summary><pre>"
                + (tail.isBlank() ? "…" : esc(tail)) + "</pre></details>";
    }

    /** How much of an answer in progress to show. The end of it, not the start. */
    private static final int LIVE_TAIL = 4_000;

    /**
     * The checked summary for one lane: {@code [0]} the line for the table, {@code [1]} the account
     * for the marker's own page. Both blank where nothing has interpreted it yet.
     *
     * <p>TWO JOBS, NOT TWO LENGTHS OF ONE. The short line decides whether to open a row out of 356;
     * the long one answers what happened once you have. Truncating the second into the first gives
     * a table of sentences that all begin the same way and stop before the part that distinguishes
     * them.
     */
    private static String[] summary(Path results, String key) {
        Path file = results.resolve("m").resolve(Supervisor.slug(key)).resolve("summary.txt");
        try {
            if (!Files.isReadable(file)) {
                return new String[] {"", ""};
            }
            String all = Files.readString(file).strip();
            int gap = all.indexOf("\n\n");
            return gap < 0 ? new String[] {all, all}
                    : new String[] {all.substring(0, gap).strip(), all.substring(gap + 2).strip()};
        } catch (IOException | RuntimeException none) {
            return new String[] {"", ""};
        }
    }

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
        // WHAT IS CLAIMED IS WHAT IS BEING PROVED. A settlement row saying "proving" only means a
        // prove once started; the row survives a container that was replaced under it, and every
        // interrupted marker then reads as busy forever. The claim directory is the fact: a prover
        // takes one before it works and the run ends with it still there.
        java.util.Set<String> claimed = new java.util.HashSet<>();
        Path claims = settlements.resolveSibling("claims");
        if (Files.isDirectory(claims)) {
            try (var c = Files.list(claims)) {
                c.forEach(p -> claimed.add(p.getFileName().toString()));
            } catch (IOException none) {
                // No claims directory is a run that has not started, not an error.
            }
        }

        Map<String, String> all = new LinkedHashMap<>();
        for (String marker : queued) {
            all.put(marker.trim(), "queued");
        }
        latest.forEach((k, row) -> {
            String state = field(row, "state");
            if (state.equals("proving")) {
                // Claimed and unfinished is being proved. Unclaimed and unfinished was interrupted —
                // a different thing, and one nobody should wait on.
                state = claimed.contains(slug(k)) ? "proving" : "interrupted";
            }
            all.put(k, state);
        });
        // Claimed but no row yet: taken seconds ago, before the first stage reported.
        for (String marker : queued) {
            String k = marker.trim();
            if (claimed.contains(slug(k)) && "queued".equals(all.get(k))) {
                all.put(k, "proving");
            }
        }
        int total = all.size();
        int settled = (int) all.values().stream()
                .filter(s -> !s.equals("proving") && !s.equals("queued")
                        && !s.equals("interrupted")).count();
        long began = first.values().stream().mapToLong(Long::longValue).min().orElse(0L);
        long elapsed = began == 0 ? 0 : System.currentTimeMillis() - began;

        StringBuilder b = head("markers", all.size() + " marker(s) · "
                + events.values().stream().mapToInt(Integer::intValue).sum() + " trace event(s)");
        if (all.isEmpty()) {
            return b.append("<div class=empty>No markers queued and no prove has run.</div>")
                    .toString();
        }
        // THE SUPERVISOR FOUND ALL OF THIS TEN HOURS BEFORE ANYONE READ IT. Its findings sat in
        // overwatch.jsonl and on a page that had to be navigated to, so the runaway reproducer, the
        // passing REDs and the whole DM_DEFAULT_ENCODING family were each reported, judged, marked
        // `holds` — and then rediscovered from scratch by a person who never opened the page. A
        // warning nobody is shown is a warning nobody has. It goes at the top of the page everybody
        // already has open.
        b.append(warnings(settlements.resolveSibling("overwatch.jsonl")));
        // A CONTAINER THE POLLER REPLACES, and only this. Refreshing the whole page every two
        // seconds to watch one agent think would throw away every fold the reader had opened and
        // their place in a table of 356 rows.
        b.append("<div id=live class=live data-live='/live'>")
                .append(live(settlements.getParent() == null ? Path.of(".")
                        : settlements.getParent(), ""))
                .append("</div>");
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
        Map<String, String> severity = severities(settlements);
        // ONE ROW READS AS ONE SENTENCE, left to right: how bad, what and where, what we decided,
        // why, what it cost. `where` used to sit between severity and state, splitting the two
        // things a reader compares, and `latest` repeated at the far right what `why` already says
        // for a marker still running. Both are gone — the package now sits under the filename it
        // belongs to, and a running marker's progress note is its `why` until it has a better one.
        b.append("</div><table><tr><th>severity</th><th>marker</th><th>state</th>"
                + "<th>why</th><th>took</th><th>a person would have</th></tr>");

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
            String name = file.substring(file.lastIndexOf('/') + 1);
            String sev = severity.getOrDefault(name + "|" + line + "|" + checker, "");
            // THE ARGUMENT, IN THE TABLE. Reading the table used to tell you a marker was a
            // false-positive and nothing about why, so every question started with opening it.
            // Folded, because a table of thirty paragraphs is not a table — and not shortened,
            // because a reason cut at two hundred characters is a reason nobody can check.
            String why = row.isEmpty() ? "" : field(row, "verdict_text");
            // A RUNNING MARKER HAS NO ARGUMENT YET, and what it is doing is the nearest thing to
            // one. It used to be in a column of its own at the far right, which meant a reader
            // scanning `why` found a dash for every marker in flight and had to jump the width of
            // the table to find out that anything was happening at all.
            // WHAT A PERSON WOULD SAY HAPPENED, where somebody has said it. The verdict's own
            // first sentence is an argument addressed to the next agent; it stays underneath in the
            // fold, because a summary is a READING of the record and the record is the record.
            String summary = summary(settlements.getParent() == null ? Path.of(".")
                    : settlements.getParent(), key)[0];
            String reason = why.isBlank() ? oneLine(saidLast.getOrDefault(key, "")) : why;
            b.append("<tr><td><span class='sev ").append(esc(sev.toLowerCase())).append("'>")
                    .append(sev.isEmpty() ? "&mdash;" : esc(sev)).append("</span></td>")
                    .append("<td><a href='/marker?k=").append(enc(key)).append("'>")
                    .append(esc(name))
                    .append(line.isEmpty() ? "" : ":" + esc(line)).append("</a>")
                    .append("<div class=k>").append(esc(checker)).append("</div>")
                    .append("<div class=k>").append(esc(dir)).append("</div></td>")
                    .append("<td>")
                    // PROVING IS THE ONE STATE THAT IS STILL HAPPENING. Every other word in this
                    // column is a conclusion and reads fine as text; this one is a question — what
                    // is it doing — and the answer is one page away.
                    .append(state.equals("proving")
                            ? "<a class='s " + esc(css(state)) + "' href='/marker?k=" + enc(key)
                                    + "&a=live'>" + esc(state) + "</a>"
                            : "<span class='s " + esc(css(state)) + "'>" + esc(state) + "</span>")
                    .append(flags(row)).append("</td>")
                    .append("<td class=why>").append(reason.isBlank()
                            ? "<span class=k>&mdash;</span>"
                            : why.isBlank()
                                    ? "<span class=k>" + esc(cut(reason, 150)) + "</span>"
                                    : "<details><summary>"
                                            + esc(summary.isBlank() ? firstSentence(why) : summary)
                                            + "</summary>" + code(flagged(CHECKOUTS,
                                                    parts.length > 0 ? parts[0] : "", file, line))
                                            + "<pre>" + esc(why) + "</pre></details>")
                    .append("</td>")
                    .append("<td class=k>").append(took > 0 ? clock(took) : "—")
                    .append("<div class=k>").append(events.getOrDefault(key, 0))
                    .append(" event(s)</div></td>")
                    .append("<td>").append(mins > 0 ? hm(mins) : "<span class=k>—</span>")
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
                                 boolean expand, int from, boolean fragment) {
        if (agent.equals("live")) {
            // THE CONTAINER CARRIES ITS OWN URL, so the same poller serves this page and the front
            // one without either knowing about the other.
            return head(key.substring(key.lastIndexOf('/') + 1), "what this prove is saying now")
                    .append(tabs(key, "live"))
                    .append("<div id=live class=live data-live='/live?k=").append(enc(key))
                    .append("'>")
                    .append(live(trace.getParent() == null ? Path.of(".") : trace.getParent(), key))
                    .append("</div>").toString();
        }
        if (agent.equals("trace")) {
            return events(trace, settlements, key, expand, tabs(key, agent), from, fragment);
        }
        // An agent tab shows one answer, not a stream, so a live update re-renders it rather than
        // appending — and there is nothing below the final answer to lose.
        if (fragment) {
            return "";
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
            // WHAT HAPPENED, IN ENGLISH, ABOVE THE ARTEFACTS. Everything else on this tab is
            // evidence — the test, each agent's final word — and evidence is what you read after
            // you know what you are looking at.
            String account = summary(trace.getParent() == null ? Path.of(".") : trace.getParent(),
                    key)[1];
            if (!account.isBlank()) {
                b.append("<div class='ev asked'><span class=who>what happened</span>")
                        .append("<span class=kind>read against the record</span>")
                        .append("<p class=account>").append(esc(account)).append("</p></div>");
            }

            // THE TEST ITSELF, first. It is the artefact the whole prove turns on, and reading it
            // out of a write_file argument on another tab is work a reader should not have to do.
            String test = "";
            String path = "";
            for (String e : mine) {
                if (field(e, "kind").equals("tool") && field(e, "tool").equals("write_file")) {
                    String content = field(field(e, "arguments"), "content");
                    if (!content.isBlank()) {
                        test = content;
                        path = field(field(e, "arguments"), "path");
                    }
                }
            }
            if (!test.isBlank()) {
                b.append("<div class='ev asked'><span class=who>the test</span>")
                        .append("<span class=kind>").append(esc(path)).append("</span><pre>")
                        .append(esc(test)).append("</pre></div>");
            }

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

        // THE REASONING IS MOST WANTED WHILE THE AGENT IS STILL WORKING, so it is gathered before
        // the paths below return — an agent seven tool calls in has said nothing yet, and its
        // thinking is the only account of what it is doing. Newest turn first: the last thought is
        // the one that produced whatever answer sits above it.
        StringBuilder thinking = new StringBuilder();
        List<String> thoughts = new ArrayList<>();
        for (String e : mine) {
            if (field(e, "kind").equals("thought") && field(e, "agent").equals(agent)) {
                thoughts.add(field(e, "text"));
            }
        }
        for (int n = thoughts.size() - 1; n >= 0; n--) {
            thinking.append(fold(thoughts.size() == 1 ? "what it worked through"
                    : "what it worked through, turn " + (n + 1), thoughts.get(n), expand));
        }
        String reasoned = thinking.length() == 0 ? ""
                : "<div class='ev thought'>" + thinking + "</div>";

        List<String> said = asked(mine, agent);
        if (said.isEmpty()) {
            // AN AGENT MID-ANSWER HAS NOT "NOT RUN". It reads and greps for minutes before its first
            // token, and reporting that as nothing throws away the only view of what it is doing.
            if (calls == 0) {
                return b.append(reasoned.isEmpty()
                        ? "<div class=empty>" + esc(agent) + " has not run for this marker.</div>"
                        : "<div class='ev asked'><span class=who>" + esc(agent)
                                + "</span><span class=kind>thinking</span></div>" + reasoned)
                        .toString();
            }
            return b.append("<div class='ev asked'><span class=who>").append(esc(agent))
                    .append("</span><span class=kind>working — ").append(calls)
                    .append(" tool call(s), no answer yet</span></div>")
                    .append(reasoned)
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

        b.append(reasoned);

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
                .append(tab(key, "", "summary", current))
                // SECOND, AND ONLY WHILE IT MEANS ANYTHING. Every other tab is a record of what was
                // said; this one is what is being said, so it belongs beside the summary rather
                // than after ten agents.
                .append(tab(key, "live", "live", current));
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
                                 String nav, int from, boolean fragment) {
        List<String> mine = new ArrayList<>();
        for (String line : lines(trace)) {
            if (key.isEmpty() || field(line, "marker").equals(key)) {
                mine.add(line);
            }
        }
        // Concatenating four workers' files gives four ordered runs, not one. The stamp is what
        // makes them a single story again.
        mine.sort((a, b) -> Long.compare(num(field(a, "at")), num(field(b, "at"))));
        String state = "";
        for (String line : lines(settlements)) {
            if (field(line, "suspicion_key").equals(key)) {
                state = field(line, "state");
            }
        }

        // A FRAGMENT IS ONLY WHAT IS NEW. The page already holds everything before `from`, with
        // whatever the reader opened still open; sending it again would close all of it.
        if (fragment) {
            StringBuilder tail = new StringBuilder();
            for (int i = Math.max(0, from); i < mine.size(); i++) {
                tail.append(one(mine.get(i), key, expand, i, ""));
            }
            return tail.toString();
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
        for (int i = 0; i < mine.size(); i++) {
            b.append(one(mine.get(i), key, expand, i, self));
        }
        return b.append(cursor(mine.size())).toString();
    }

    /**
     * ONE EVENT, rendered the same whether the page is being built or extended.
     *
     * <p>Two renderers would drift, and the one that drifts is always the live path — it is the one
     * nobody looks at directly.
     *
     * @param i the event's index, which the feedback form uses to point at exactly this answer
     */
    private static String one(String e, String key, boolean expand, int i, String self) {
        if (self.isEmpty()) {
            self = key.isEmpty() ? "/trace" : "/marker?k=" + enc(key);
        }
        String kind = field(e, "kind");
        StringBuilder b = new StringBuilder("<div class='ev ").append(esc(kind)).append("'>");
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
                case "thought" -> b.append("<span class=who>").append(esc(field(e, "agent")))
                        .append("</span><span class=kind>thought</span>")
                        .append(fold("what it worked through", field(e, "text"), expand));
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

    /**
     * THE TWO FACTS A STATE DOES NOT CARRY: did a test fail before the patch, did it pass after.
     *
     * <p>Lit means the build said so. Dim means it was reached and did not happen — a RED that
     * passed, a GREEN that failed. Hollow means it was never got to, which is a different answer
     * from "no": a marker the reproducer declined never had a red to fail.
     */
    private static String flags(String row) {
        if (row.isEmpty()) {
            return "";
        }
        String red = field(row, "red_verified");
        String green = field(row, "green_verified");
        String state = field(row, "state");
        boolean reachedRed = !state.equals("queued") && !state.equals("not-a-bug");
        boolean reachedGreen = "true".equals(red);
        return "<div class=sema>"
                + dot("red", "true".equals(red), reachedRed, "reproduced: the test failed first")
                + dot("green", "true".equals(green), reachedGreen, "fixed: the same test then passed")
                + "</div>";
    }

    private static String dot(String which, boolean lit, boolean reached, String title) {
        String cls = lit ? which + " lit" : reached ? which + " dim" : which + " none";
        return "<i class='" + cls + "' title='" + esc(title) + "'></i>";
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

    /** A request the live script made, wanting only what changed. */
    private static boolean fragment(HttpExchange e) {
        return e.getRequestHeaders().getFirst("X-Fragment") != null;
    }

    /** How many events the page already holds, so the live script asks only for what follows. */
    private static String cursor(int events) {
        return "<script>document.body.dataset.events=" + events + "</script>";
    }

    private static StringBuilder head(String title, String sub) {
        return new StringBuilder("<style>").append(CSS).append("</style>")
                .append(LIVE).append(KEEP_OPEN)
                .append("<header><h1>").append(esc(title)).append("</h1><div class=sub>")
                .append(sub).append("</div></header>");
    }

    /**
     * Every worker's copy of one file, concatenated.
     *
     * <p>PARALLEL PROVERS DO NOT SHARE A FILE. Appending from four processes looks safe — O_APPEND
     * makes the offset update atomic — but a line here can be sixty kilobytes of prompt, and a write
     * that large is not one syscall. Two workers interleave mid-line and both records are lost, in a
     * corpus whose whole purpose is to be read later. So each prove writes
     * {@code results/m/<marker>/trace.jsonl} and this reads them all.
     *
     * <p>Absent is not an error: a run that has settled nothing yet is the normal first state.
     */
    private static List<String> lines(Path file) {
        List<String> all = new ArrayList<>(read(file));
        Path root = file.getParent();
        String name = file.getFileName().toString();
        // One directory per MARKER, not per worker: a pool hands the next marker to whichever
        // prover is free, so a worker index names nothing a reader wants and changes run to run.
        Path perMarker = root == null ? null : root.resolve("m");
        if (perMarker != null && Files.isDirectory(perMarker)) {
            try (var dirs = Files.list(perMarker)) {
                dirs.filter(Files::isDirectory).sorted()
                        .forEach(w -> all.addAll(read(w.resolve(name))));
            } catch (IOException none) {
                // A run that has proved nothing yet has no per-marker directories.
            }
        }
        return all;
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Where the provers keep their trees. The same value {@code entrypoint.sh} uses. */
    private static final Path CHECKOUTS =
            Path.of(System.getenv().getOrDefault("CHECKOUTS", "/work/checkouts"));

    /** Lines of context either side of the flagged one. Enough to see the statement it is part of. */
    private static final int AROUND = 4;

    /**
     * THE LINE THE ANALYSER FLAGGED, WITH THE CODE AROUND IT.
     *
     * <p>A verdict about {@code ProfileUploadBase.java:82} is not checkable without line 82. The
     * argument names it, quotes fragments of it and reasons about it, and a reader had to open the
     * marker, open the reproducer's prompt and scroll to find the four lines the whole thing is
     * about. They are four lines. They belong next to the verdict.
     *
     * <p>Read from the reference checkout the provers share, and empty when there is not one — this
     * is a convenience for a reader, and a dashboard that will not start because a tree is missing
     * would be a poor trade for it.
     */
    private static String flagged(Path checkouts, String repo, String file,
            String lineNumber) {
        int want;
        try {
            want = Integer.parseInt(lineNumber.strip());
        } catch (NumberFormatException noLine) {
            return "";
        }
        String name = repo.substring(repo.lastIndexOf('/') + 1).replaceAll("\\.git$", "");
        // NOT read(), WHICH DROPS BLANK LINES. It is the right reader for a JSONL record, where a
        // blank line is nothing, and the wrong one for source, where a blank line is line 79 and
        // every number after it shifts. It put `public ResponseEntity getProfilePicture` at line 82
        // of ProfileUploadBase, four lines below the truth, and I read that as the marker having
        // drifted and nearly wrote it up as a finding about the analyser.
        List<String> source;
        try {
            source = Files.readAllLines(checkouts.resolve(name).resolve(file));
        } catch (IOException | RuntimeException noTree) {
            return "";
        }
        if (source.isEmpty() || want < 1) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        for (int n = Math.max(1, want - AROUND); n <= Math.min(source.size(), want + AROUND); n++) {
            b.append(n == want ? "&gt;&gt; " : "   ").append(n).append("  ")
                    .append(esc(source.get(n - 1))).append('\n');
        }
        if (want > source.size()) {
            // THE MARKERS CAME OFF AN OLDER REVISION and some point past the end of the file now.
            // Saying so here is the difference between a reader trusting the line number and a
            // reader knowing not to.
            b.append("\n&gt;&gt; line ").append(want).append(" — THIS FILE HAS ")
                    .append(source.size()).append(". The analyser ran against an older revision.");
        }
        return b.toString();
    }

    /**
     * THE FIRST SENTENCE THAT SAYS ANYTHING, for the closed fold.
     *
     * <p>Not a truncation — the whole argument is inside the fold, one click away and on this page.
     * This is the line a reader scans down the table, and the naive first sentence was mostly not
     * one: an argument opens by repeating the word it settled on ("false-positive false-positive
     * The static analyzer claims…") and then clears its throat ("Looking at this issue: 1."), so
     * two of every three rows summarised to nothing.
     *
     * <p>So: drop a leading restatement of the verdict, drop anything that ends in a colon, and take
     * the first sentence long enough to be a claim. Where nothing qualifies the reader gets the
     * opening as it stands, which is the honest answer for an argument that has no first sentence.
     */
    private static String firstSentence(String why) {
        // Stripping the markdown leaves its spacing behind: `**The bug**: Line 91` becomes
        // `The bug : Line 91`, which reads as a typo in a column meant to be scanned.
        String flat = why.strip().replaceAll("[*`#>]", " ").replaceAll("\\s+", " ")
                .replaceAll("\\s+([:;,.])", "$1").strip();
        for (String word : new String[] {"false-positive", "by-design", "unprovable", "reproduced",
                "needs-review", "verified/pr-ready", "verified/pr-rejected", "make", "reject",
                "sound", "redo", "over-fit", "regression-risk", "necessary", "reducible"}) {
            while (flat.toLowerCase().startsWith(word + " ")) {
                flat = flat.substring(word.length()).strip();
            }
        }
        for (String sentence : flat.split("(?<=[.!?])\\s+")) {
            String one = sentence.strip();
            if (one.length() >= 40 && !one.endsWith(":") && !one.matches("(?i)looking at .{0,40}")) {
                return one.length() <= 240 ? one : one.substring(0, 240) + "…";
            }
        }
        return flat.length() <= 240 ? flat : flat.substring(0, 240) + "…";
    }

    /** The flagged lines, already escaped, or nothing at all when there was no tree to read. */
    private static String code(String lines) {
        return lines.isBlank() ? "" : "<pre class=flagged>" + lines + "</pre>";
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
    /**
     * Reading a field out of a tool's arguments means reading JSON that was itself a JSON string, so
     * one level of escaping is already gone by the time this sees it. That is why it works: the
     * value arrives as ordinary text with real newlines, not as \n.
     */
    private static String field(String json, String key) {
        int k = json.indexOf('"' + key + "\":");
        if (k < 0) {
            return "";
        }
        // A VALUE IS NOT ALWAYS QUOTED. Settlement writes booleans and Feedback writes an int
        // unquoted, and scanning for the next quote then skips past them and finds the following
        // KEY's quote instead — which is why red_verified read as empty for every marker that had
        // genuinely gone red, and the semaphore never lit.
        int colon = json.indexOf(':', k + key.length());
        int scan = colon + 1;
        while (scan < json.length() && json.charAt(scan) == ' ') {
            scan++;
        }
        if (scan < json.length() && json.charAt(scan) != '"') {
            int stop = scan;
            while (stop < json.length() && ",}".indexOf(json.charAt(stop)) < 0) {
                stop++;
            }
            return json.substring(scan, stop).trim();
        }
        int open = scan;
        if (open >= json.length()) {
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

    /**
     * A HANDLER THAT THROWS MUST SAY SO, TO THE READER, IN THE BROWSER.
     *
     * <p>{@code HttpServer} answers an exception from a handler by closing the connection: no status,
     * no body, no log line. Empty reply in twenty milliseconds — which reads exactly like a page too
     * big to build, and sent me looking at response sizes and memory limits for an
     * UnsupportedOperationException from sorting an immutable list.
     *
     * <p>The stack goes in the page for the same reason it goes in a settlement: a failure that
     * cannot locate itself sends whoever is reading to a container log that does not have it.
     */
    private static void route(HttpServer server, String path,
            com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, guarded(handler));
    }

    private static com.sun.net.httpserver.HttpHandler guarded(
            com.sun.net.httpserver.HttpHandler handler) {
        return exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException | RuntimeException | Error broke) {
                StringBuilder where = new StringBuilder(broke.getClass().getName() + ": "
                        + broke.getMessage());
                for (StackTraceElement at : broke.getStackTrace()) {
                    where.append("\n  at ").append(at);
                }
                byte[] body = ("<style>" + CSS + "</style><header><h1>this page broke</h1>"
                        + "<div class=sub>" + esc(exchange.getRequestURI().toString())
                        + "</div></header><div class='ev failed'><pre>" + esc(where.toString())
                        + "</pre></div>").getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(500, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } catch (IOException gone) {
                    // The reader closed the connection. Nothing left to tell.
                }
            }
        };
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
