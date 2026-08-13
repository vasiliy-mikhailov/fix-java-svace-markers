package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * WHAT A SHELL NEEDS TO MOUNT THIS TOOL, AND NOTHING ELSE.
 *
 * <p>This is one of several tools a developer is meant to reach from one UI — {@code
 * bump-java-version}, {@code improve-java-tests}, whatever follows. Each is its own repository, its
 * own container and its own deploy; a shell in a separate repository composes them behind one origin
 * by rewriting a path prefix at each.
 *
 * <p>THE SHELL IS WRITTEN BY SOMEBODY WHO WILL NOT HAVE READ THIS CODE. That is the whole design
 * constraint. Everything a shell needs is therefore SERVED — a manifest it can fetch and a health
 * check it can poll — rather than agreed in conversation, because an agreement nobody can query is an
 * agreement that goes stale on the first deploy and reports success while doing so. Chapter 17 of the
 * spec is the prose; this is the part a program can read.
 *
 * <p>THE BASE PATH IS CONFIGURATION. A tool that hard-codes where it is mounted can be mounted one
 * way, and the second tool wanting that prefix cannot be mounted at all. Empty by default, so this
 * serves at {@code /} exactly as it does today and nothing about the current deployment changes.
 */
final class Zone {

    /** The id a shell stores against. Stable across renames of anything else. */
    static final String ID = "fix-java-svace-markers";

    private Zone() {
    }

    /**
     * Where the shell has mounted this tool, with no trailing slash. Empty when standalone.
     *
     * <p>Normalised rather than trusted: a shell author setting {@code BASE_PATH=fsm/} or
     * {@code /fsm/} means the same thing, and a base path that differs from itself by a slash
     * produces links that 404 in ways that look like a routing bug in the shell.
     */
    static String basePath() {
        String raw = System.getenv().getOrDefault("BASE_PATH", "").strip();
        if (raw.isEmpty() || raw.equals("/")) {
            return "";
        }
        String withLead = raw.startsWith("/") ? raw : "/" + raw;
        return withLead.endsWith("/") ? withLead.substring(0, withLead.length() - 1) : withLead;
    }

    /** Static assets go under their own prefix, or two zones' bundles collide on one origin. */
    static String assetPrefix() {
        String base = basePath();
        return base.isEmpty() ? "/-static" : base + "-static";
    }

    /**
     * The git SHA of the running image, which is also its Docker tag.
     *
     * <p>So a shell can say exactly which build it is talking to. Unknown is reported as such rather
     * than as a plausible-looking blank: a version field that is silently empty is one a shell will
     * render as a gap and nobody will chase.
     */
    static String version() {
        String v = System.getenv().getOrDefault("FSM_VERSION", "").strip();
        return v.isEmpty() ? "unknown" : v;
    }

    /**
     * Everything a shell needs, in one document it fetches rather than one somebody transcribes.
     *
     * <p>Paths in {@code nav} are relative to the base path: the shell prefixes them, because this
     * tool does not know what the shell's URL bar says. {@code badges} is the indirection that lets a
     * count reach the shell's navigation without the shell knowing what a finding is — it polls the
     * endpoint and reads the field. When the next tool wants a badge for something else, the shell
     * needs no change.
     */
    static String manifest() {
        String base = basePath();
        return """
                {
                  "id": "%s",
                  "name": "Prove markers",
                  "description": "Proves static-analysis markers: a failing test, a patch, the same test passing.",
                  "version": "%s",
                  "basePath": "%s",
                  "assetPrefix": "%s",
                  "api": "%s/api",
                  "health": "%s/api/health",
                  "nav": [
                    {"label": "Markers", "path": "/", "badge": null},
                    {"label": "Findings", "path": "/overwatch", "badge": "findings"},
                    {"label": "Ask", "path": "/chat", "badge": null},
                    {"label": "Settings", "path": "/settings", "badge": null}
                  ],
                  "badges": {
                    "findings": {"endpoint": "/api/badges", "field": "findings"}
                  }
                }
                """.formatted(ID, Settlement.escape(version()), base, assetPrefix(), base, base);
    }

    /**
     * WHETHER THE RECORD CAN BE SERVED — not whether the model can be reached.
     *
     * <p>The dashboard is worth serving when the inference endpoint is down: the whole record is still
     * readable and that is most of what anybody comes for. A health check that went red because a
     * model was unreachable would have the shell hiding a tool that was working, at exactly the
     * moment somebody wanted to look at what it had already done.
     *
     * @return null when healthy, or the one sentence explaining why not
     */
    static String unhealthy(Path results) {
        if (!Files.isDirectory(results)) {
            return "the results directory " + results + " is not there; nothing can be read";
        }
        if (!Files.isReadable(results)) {
            return "the results directory " + results + " cannot be read";
        }
        return null;
    }

    /** The counts a shell puts on its own navigation, knowing nothing about what they mean. */
    static String badges(Path results) {
        return "{\"findings\":" + open(results.resolve("overwatch.jsonl"))
                + ",\"proving\":" + proving(results) + "}";
    }

    /** Findings the critic has not dismissed. `refuted` is excluded; a count that only grows is
     *  a count nobody reads twice. */
    private static int open(Path findings) {
        int n = 0;
        if (!Files.isReadable(findings)) {
            return 0;
        }
        try {
            for (String line : Files.readAllLines(findings)) {
                String verdict = Json.field(line, "verdict");
                if (verdict.isBlank() || verdict.equals("holds")) {
                    n++;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
        return n;
    }

    /** How many proves are actually running, which is claims minus anything already settled. */
    private static int proving(Path results) {
        Path claims = results.resolve("claims");
        if (!Files.isDirectory(claims)) {
            return 0;
        }
        try (var dirs = Files.list(claims)) {
            List<Path> held = dirs.toList();
            int running = 0;
            for (Path claim : held) {
                Path lane = results.resolve("m").resolve(claim.getFileName().toString())
                        .resolve("settlements.jsonl");
                if (!settled(lane)) {
                    running++;
                }
            }
            return running;
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
    }

    private static boolean settled(Path settlements) {
        if (!Files.isReadable(settlements)) {
            return false;
        }
        try {
            for (String line : Files.readAllLines(settlements)) {
                String state = Json.field(line, "state");
                if (!state.isBlank() && !state.equals("proving") && !state.equals("infra")
                        && !state.equals("queued")) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
        return false;
    }
}
