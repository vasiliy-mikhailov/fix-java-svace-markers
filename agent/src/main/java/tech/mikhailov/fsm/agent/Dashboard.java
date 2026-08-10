package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * THE SETTLEMENTS, SERVED. {@code java … Dashboard [results.jsonl] [port]}
 *
 * <p>It reads the file on every request and holds nothing. A prove appends a line; a refresh shows it.
 * There is no database to fall behind the file, and no schema to migrate, because the file IS the
 * record — the same property that makes {@link Settlement} a JSONL append rather than a table.
 *
 * <p>Two endpoints and a page: {@code /} renders, {@code /api/settlements} returns the raw lines for
 * anything that would rather read them itself.
 */
public final class Dashboard {

    private static final String CSS = """
            *{box-sizing:border-box}body{margin:0;font:14px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;
            background:#0d1117;color:#c9d1d9}header{padding:18px 24px;border-bottom:1px solid #21262d}
            h1{margin:0;font-size:15px;font-weight:600;letter-spacing:.02em}
            .sub{color:#7d8590;font-size:12px;margin-top:4px}
            .counts{display:flex;flex-wrap:wrap;gap:8px;padding:16px 24px}
            .c{padding:6px 12px;border:1px solid #21262d;border-radius:6px;background:#161b22}
            .c b{font-size:18px;display:block}.c span{color:#7d8590;font-size:11px}
            table{width:100%;border-collapse:collapse}th{text-align:left;color:#7d8590;font-weight:500;
            font-size:11px;text-transform:uppercase;letter-spacing:.06em;padding:10px 24px;
            border-bottom:1px solid #21262d}
            td{padding:10px 24px;border-bottom:1px solid #161b22;vertical-align:top}
            tr:hover td{background:#0f141a}
            .k{color:#7d8590;font-size:12px}.f{color:#58a6ff}
            .s{padding:2px 8px;border-radius:20px;font-size:11px;white-space:nowrap}
            .verified{background:#132e1a;color:#3fb950}.reproduced{background:#2b2011;color:#d29922}
            .needs-review{background:#2b2011;color:#d29922}
            .false-positive{background:#161b22;color:#7d8590}.by-design{background:#161b22;color:#7d8590}
            .unprovable{background:#161b22;color:#7d8590}.not-a-bug{background:#161b22;color:#7d8590}
            .INFRA{background:#2d1618;color:#f85149}
            .why{color:#8b949e;font-size:12px;white-space:pre-wrap;max-width:70ch;margin-top:6px}
            .empty{padding:48px 24px;color:#7d8590}
            """;

    private Dashboard() {
    }

    public static void main(String[] args) throws IOException {
        Path results = Path.of(args.length > 0 ? args[0] : "results/settlements.jsonl");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8087;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/settlements", e -> send(e, "application/json",
                "[" + String.join(",", lines(results)) + "]"));
        server.createContext("/", e -> send(e, "text/html; charset=utf-8", page(results)));
        server.start();
        System.out.println("dashboard on http://127.0.0.1:" + port + "  reading " + results);
    }

    /** Absent is not an error: a run that has settled nothing yet is the normal first state. */
    private static List<String> lines(Path results) {
        try {
            return Files.readAllLines(results).stream().filter(l -> !l.isBlank()).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String page(Path results) {
        List<String> rows = lines(results);
        StringBuilder b = new StringBuilder("<style>").append(CSS).append("</style>")
                .append("<header><h1>settlements</h1><div class=sub>")
                .append(results).append(" · ").append(rows.size()).append(" prove(s)</div></header>");

        if (rows.isEmpty()) {
            return b.append("<div class=empty>No prove has completed yet.<br><br>"
                    + "<code>java … Prove &lt;checkout&gt; 'repo|file|line|checker'</code></div>")
                    .toString();
        }

        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (String row : rows) {
            counts.merge(field(row, "state"), 1, Integer::sum);
        }
        b.append("<div class=counts>");
        counts.forEach((k, n) -> b.append("<div class=c><b>").append(n).append("</b><span>")
                .append(esc(k)).append("</span></div>"));
        b.append("</div><table><tr><th>marker</th><th>settled</th></tr>");

        for (String row : rows) {
            String file = field(row, "file");
            String state = field(row, "state");
            b.append("<tr><td><span class=f>").append(esc(file.substring(file.lastIndexOf('/') + 1)))
                    .append("</span><div class=k>").append(esc(field(row, "svace_checker")))
                    .append("</div></td><td><span class='s ").append(esc(cls(state))).append("'>")
                    .append(esc(state)).append("</span><div class=why>")
                    .append(esc(cut(field(row, "verdict_text")))).append("</div></td></tr>");
        }
        return b.append("</table>").toString();
    }

    /** A settled state is one CSS class; anything else is styled as infra. */
    private static String cls(String state) {
        return state.matches("[a-z-]+") ? state : "INFRA";
    }

    private static String cut(String s) {
        return s.length() <= 600 ? s : s.substring(0, 600) + "…";
    }

    /**
     * One field out of one settlement line.
     *
     * <p>The rows are written by {@link Settlement} and read here, and both are flat maps of strings
     * and booleans — so this stays a scan rather than a parser. A malformed line yields an empty
     * field and one blank cell, where a parser would refuse the whole page.
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
                v.append(n == 'n' ? '\n' : n == 't' ? '\t' : n == 'r' ? '\r' : n);
            } else if (c == '"') {
                break;
            } else {
                v.append(c);
            }
        }
        return v.toString();
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
