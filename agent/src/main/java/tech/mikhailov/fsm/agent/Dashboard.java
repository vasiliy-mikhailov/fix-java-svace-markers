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




    private Dashboard() {
    }

    public static void main(String[] args) throws IOException {
        Path results = Path.of(args.length > 0 ? args[0] : "results");
        Path settlements = results.toString().endsWith(".jsonl")
                ? results : results.resolve("settlements.jsonl");
        Path trace = settlements.resolveSibling("trace.jsonl");
        Path feedback = settlements.resolveSibling("feedback.jsonl");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8087;

        // THE CODE'S PROMPTS, COLLECTED ONCE AT START-UP. The dashboard builds no agents of its
        // own, so without this it could show what an agent is being told and not what it would be
        // told if the override were removed.
        Path here = settlements.getParent() == null ? Path.of(".") : settlements.getParent();
        serving(here);
        BUILT_INS.putAll(Agents.builtIn(here,
                new JsonlTrace(here.resolve("dashboard-trace.jsonl"),
                        here.resolve("dashboard-settlements.jsonl"), "dashboard"),
                (phase, test) -> new Runner.Result(true, false, "the dashboard does not build")));

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
        // WHAT A SHELL NEEDS, SERVED RATHER THAN AGREED. Another session writes the shell; it
        // cannot read this code, so everything it needs is fetchable and versioned. See spec/17.
        route(server, "/.well-known/microfrontend.json",
                e -> send(e, "application/json", Zone.manifest()));
        route(server, "/api/health", e -> {
            String why = Zone.unhealthy(here);
            String body = why == null
                    ? "{\"ok\":true,\"version\":\"" + Settlement.escape(Zone.version()) + "\"}"
                    : "{\"ok\":false,\"why\":\"" + Settlement.escape(why) + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            // 503, so a shell can show a degraded zone without parsing the body to find out.
            e.sendResponseHeaders(why == null ? 200 : 503, bytes.length);
            try (OutputStream out = e.getResponseBody()) {
                out.write(bytes);
            }
        });
        route(server, "/api/badges", e -> send(e, "application/json", Zone.badges(here)));
        // THE FIRST OF THE EIGHT ROUTES THAT COMPUTE IN JAVA AND EMIT HTML. The zone reads this
        // instead of the table; the page below still renders from the same Run.rows(), so the two
        // cannot disagree while both exist.
        route(server, "/api/index", e -> send(e, "application/json",
                Api.index(settlements, trace, Api.queue(settlements))));
        // AND THE OTHER SEVEN. Every screen the zone renders now has a document to render from,
        // and each is answered from the same files the page beside it reads.
        route(server, "/api/marker", e -> send(e, "application/json",
                ApiMarker.marker(settlements, trace, query(e, "k"))));
        route(server, "/api/marker/agent", e -> send(e, "application/json",
                ApiMarker.markerAgent(trace, query(e, "k"), query(e, "a"))));
        // NOT /api/trace — THAT NAME IS TAKEN, and by something with a different job. The existing
        // one dumps the raw JSONL as an array; it is the corpus, documented in the README, and a
        // reader may be training on it. HttpServer.createContext throws on a duplicate path, so
        // shadowing it would not have degraded quietly — it would have refused to start, which is
        // the better failure and still not one to ship.
        route(server, "/api/events", e -> send(e, "application/json",
                ApiTrace.trace(trace, settlements, (int) num(query(e, "from")),
                        query(e, "limit").isEmpty() ? ApiTrace.WINDOW
                                : (int) num(query(e, "limit")))));
        route(server, "/api/overwatch", e -> send(e, "application/json",
                ApiOverwatch.overwatch(here, query(e, "a"))));
        route(server, "/api/chat", e -> send(e, "application/json", ApiChat.chat(here)));
        route(server, "/api/live", e -> send(e, "application/json",
                ApiLive.live(here, query(e, "k"))));
        // NOT THROUGH send(), which writes a whole body and closes. This one holds the exchange open
        // and writes frames as the lane produces them — the page stops asking and the server tells.
        route(server, "/api/stream",
                e -> ApiStream.stream(e, settlements, query(e, "k"), query(e, "have")));
        route(server, "/api/settings/model", e -> send(e, "application/json", ApiSettings.model()));
        route(server, "/api/settings/subject", e -> send(e, "application/json",
                ApiSettings.subject(here)));
        route(server, "/api/settings/prompts", e -> send(e, "application/json",
                ApiSettings.prompts(BUILT_INS)));
        route(server, "/api/settings/run", e -> send(e, "application/json",
                ApiSettings.run(here)));

        // THE REACT ZONE, ALONGSIDE THE PAGES IT WILL REPLACE.
        //
        // Both UIs are up while the port is in progress, which is the only way to compare them on the
        // same run rather than on a screenshot. The zone is built with BASE_PATH=/ui so its links and
        // its asset URLs already carry the prefix — the export bakes them in, and a bundle served
        // from a path it was not built for is a page of 404s with a blank body.

        route(server, "/api/settlements", e -> send(e, "application/json",
                "[" + String.join(",", lines(settlements)) + "]"));
        route(server, "/api/trace", e -> send(e, "application/json",
                "[" + String.join(",", lines(trace)) + "]"));
        // THE CORPUS. Every labelled example, prompt and reply included, ready to train on with no
        // join back to the trace.
        route(server, "/api/feedback", e -> send(e, "application/json",
                "[" + String.join(",", lines(feedback)) + "]"));
        // THE WRITE PATHS, WHICH THE ZONE POSTS TO ON THE SAME URLS ITS PAGES LIVE AT.
        //
        // `/chat` and `/settings` are both a page and a handler now, so they branch on the method:
        // POST does the work, anything else falls through to the exported zone. The URLs are the ones
        // the Java forms used, because they are also the ones the React app posts to and the ones in
        // anybody's bookmarks.
        route(server, "/feedback", e -> {
            // ONCE. The body is a stream: a second read returns nothing.
            Map<String, String> posted = "POST".equals(e.getRequestMethod()) ? form(e) : Map.of();
            if (!posted.isEmpty()) {
                record(feedback, posted);
            }
            e.getResponseHeaders().set("Location", posted.getOrDefault("back", "/"));
            e.sendResponseHeaders(303, -1);
            e.close();
        });
        // PROVING IT AGAIN, ORDERED BY A PERSON. Not counted against the supervisor's two: the line
        // it writes carries `by`, and the limit counts only what the agent itself ordered.
        route(server, "/reprove", e -> {
            Map<String, String> form = form(e);
            String marker = form.getOrDefault("marker", "");
            new Supervisor(here, new JsonlTrace(here.resolve("dashboard-trace.jsonl"),
                    here.resolve("dashboard-settlements.jsonl"), "dashboard"))
                    .reprove(marker, form.getOrDefault("why", "no reason given"));
            e.getResponseHeaders().add("Location", "/marker/?k=" + enc(marker));
            e.sendResponseHeaders(303, -1);
            e.close();
        });
        route(server, "/settings", e -> {
            if (e.getRequestMethod().equalsIgnoreCase("POST") && Upload.isMultipart(e)) {
                // THE SUBJECT ARRIVES AS FILES, so it does not go through form(), which reads a
                // query string. The outcome is JSON now: the page that asked is the one that draws it.
                send(e, "application/json", ApiSettings.posted(e, here));
                return;
            }
            if (e.getRequestMethod().equalsIgnoreCase("POST")) {
                Map<String, String> form = form(e);
                String setting = form.getOrDefault("setting", "");
                try {
                    if (setting.equals("model")) {
                        if (form.containsKey("revert")) {
                            Tuning.revert();
                        } else {
                            Tuning.save(form);
                        }
                    } else if (setting.equals("workers")) {
                        Workers.save(here, (int) num(form.getOrDefault("workers", "")));
                    } else if (form.containsKey("revert")) {
                        Prompts.revert(form.getOrDefault("agent", ""));
                    } else {
                        Prompts.save(form.getOrDefault("agent", ""),
                                form.getOrDefault("prompt", ""));
                    }
                } catch (IOException notSaved) {
                    // The page re-reads what is on disk, which is the honest reply.
                }
                send(e, "application/json", "{\"saved\":true}");
                return;
            }
            Web.serve(e, "");
        });
        route(server, "/chat", e -> {
            if (e.getRequestMethod().equalsIgnoreCase("POST")) {
                String said = Chat.ask(here, form(e).getOrDefault("q", ""));
                send(e, "application/json",
                        "{\"said\":\"" + Settlement.escape(said) + "\"}");
                return;
            }
            Web.serve(e, "");
        });

        // AND THE ZONE AT THE ROOT, which is every other path.
        //
        // A catch-all, registered last in reading order but matched by longest prefix rather than by
        // order, so every route above still wins for its own path. What reaches here is the exported
        // React app and its assets — and a request for something neither serves gets the zone's own
        // 404 rather than a Java page that no longer exists.
        route(server, "/", e -> Web.serve(e, ""));

        server.start();
        System.out.println("dashboard on http://127.0.0.1:" + port + "  reading " + settlements);
    }

    // ------------------------------------------------------------------ index


































    // ----------------------------------------------------------------- marker














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






    /** A field that should be a number, or 0 — a malformed one must not take the page down. */
    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
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
    static List<String> lines(Path file) {
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
    /**
     * THE CODE'S PROMPTS, COLLECTED ONCE AT START-UP.
     *
     * <p>The dashboard builds no agents of its own, so without this it could show what an agent is
     * being told and not what it would be told if the override were removed — and could not tell a
     * marker proved under the built-in from one proved under an edit that has since been reverted.
     */
    private static final Map<String, String> BUILT_INS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * WHICH RESULTS DIRECTORY THIS DASHBOARD SERVES. Configuration, not state.
     *
     * <p>It is fixed for the life of the process — the path comes from the command line and nothing
     * can change it — so it is here rather than in the signature of all thirteen pages. {@code head}
     * takes a title and a subtitle; threading a path through every one of them to draw one badge
     * would put the results directory into functions that have no other use for it.
     *
     * <p>The FILE under it is read fresh on every request, so the count moves while the run does.
     *
     * <p>Set through {@link #serving}, by {@code main} and by the tests, because a page whose header
     * is right only after {@code main} has run is a page no test can check — which is how the first
     * version of this went in: the badge worked over HTTP and was silently absent when the renderer
     * was called directly.
     */
    private static volatile Path root = Path.of(".");

    /** Names the results directory. Called by {@code main}, and by tests before they render. */
    static void serving(Path results) {
        root = results;
    }









    /** Java's words, its strings and its comments. Three colours, which is what a reader uses. */
    private static final java.util.regex.Pattern JAVA = java.util.regex.Pattern.compile(
            "(?<comment>//[^\n]*|/\\*.*?\\*/)"
                    + "|(?<string>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
                    + "|(?<word>\\b(?:abstract|assert|boolean|break|byte|case|catch|char|class|"
                    + "const|continue|default|do|double|else|enum|extends|final|finally|float|for|"
                    + "goto|if|implements|import|instanceof|int|interface|long|native|new|package|"
                    + "private|protected|public|return|short|static|strictfp|super|switch|"
                    + "synchronized|this|throw|throws|transient|try|var|void|volatile|while|true|"
                    + "false|null|record|sealed|yield)\\b)"
                    + "|(?<number>\\b\\d[\\w.]*)",
            java.util.regex.Pattern.DOTALL);




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
    static String field(String json, String key) {
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
    /**
     * ONE ROUTE, REGISTERED AT THE ROOT AND UNDER THE MOUNT PREFIX.
     *
     * <p>A zone mounted at {@code /ui} asks for {@code /ui/api/index}, because that is what the
     * manifest promises: {@code api} is {@code <base>/api}, and every link the export bakes in
     * carries the prefix. Serving the API only at the root made the zone's first fetch 404 — the
     * page mounted, drew its loading state, and stayed there.
     *
     * <p>Both, rather than moving: the Java pages and every existing reader still use the root paths,
     * and {@code /api/trace} is a documented corpus somebody may be training on. Nothing that worked
     * yesterday stops working because a second UI arrived.
     */
    private static void route(HttpServer server, String path,
            com.sun.net.httpserver.HttpHandler handler) {
        com.sun.net.httpserver.HttpHandler wrapped = guarded(handler);
        server.createContext(path, wrapped);
        // ONLY THE DATA ROUTES, and that restriction is load-bearing.
        //
        // Duplicating EVERY route under the prefix registered `/ui/` for the root page — and
        // HttpServer matches the longest prefix, so `/ui/` (four characters) beat the zone's own
        // `/ui` (three) and the new UI served the old dashboard. The pages do not belong under the
        // mount prefix at all: that space is the zone's, and the zone is what replaces them.
        String base = Zone.basePath();
        boolean data = path.startsWith("/api") || path.startsWith("/.well-known");
        if (data && !base.isEmpty() && !path.startsWith(base)) {
            server.createContext(base + path, wrapped);
        }
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
                // JSON, BECAUSE EVERY READER OF THIS IS NOW A PROGRAM. The handler that broke
                // was answering a fetch from the zone, and an HTML error page reaches it as a parse
                // failure with the real cause thrown away. The stack goes in the body: this server
                // is behind auth on somebody's own box, and a 500 nobody can read is a 500 nobody
                // fixes.
                byte[] body = ("{\"error\":\"" + Settlement.escape(broke.toString())
                        + "\",\"path\":\"" + Settlement.escape(exchange.getRequestURI().toString())
                        + "\",\"where\":\"" + Settlement.escape(where.toString())
                        + "\"}").getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
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
