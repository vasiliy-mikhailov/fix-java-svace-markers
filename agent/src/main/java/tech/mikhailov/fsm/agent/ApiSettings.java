package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE FOUR SETTINGS SCREENS AS JSON, FOR A CLIENT THAT RENDERS THEM SOMEWHERE ELSE.
 *
 * <p>{@code /settings} is one route with four faces — the prompts, the run width, the model and the
 * subject — and {@link Dashboard#settings} picks between them on a query parameter. The React zone
 * replacing those pages needs each face as a document, so there are four methods here and they read
 * exactly what the four page functions read: {@link Tuning}, {@link Workers}, {@link Prompts},
 * {@link Subject}. Nothing is recomputed and no rule is copied, so the page and the zone cannot
 * start disagreeing while both exist — which is the whole port, not a moment of it.
 *
 * <p>FACTS, NOT SENTENCES, like {@link Api}. {@code queued} is a count and not "356 marker(s)
 * queued"; {@code edited} is a boolean and not "edited &mdash; the environment's values are
 * underneath"; the group an agent belongs to is {@code chain} and not "the chain, in the order Prove
 * calls it". The page writes those sentences at the last moment and so should the client, because a
 * sentence in JSON is a decision the client cannot revisit.
 *
 * <p>TWO SECRETS PASS THROUGH THIS FILE AND NEITHER LEAVES IT. The model page renders the API key
 * into its own source, and {@link Tuning} says plainly what that costs — the value is then in
 * whatever caches or screenshots that page — but it buys a reveal button and a copy button behind
 * basic auth. An endpoint has no such excuse: nothing here reveals anything, so it sends whether a
 * key is set and where it came from, and never the key. The git token is the same and worse: it is
 * kept out of the clone URL precisely so it never reaches a process list or a log
 * ({@link Subject#saveToken}), and an endpoint that handed it back would undo that on its own.
 *
 * <p>No {@code findingsOpen} in these four documents, though {@code head()} draws that badge on
 * every page: the count is already served, for every screen at once, by {@code /api/badges}
 * ({@link Zone#badges}). Two routes computing it is two routes to keep in step.
 */
final class ApiSettings {

    private ApiSettings() {
    }

    /**
     * {@code GET /api/settings/model} — what every prover will ask for, and whether there is a
     * credential to ask with.
     *
     * <p>{@code values} is {@link Tuning#all()} in its own order and not sorted: model and endpoint
     * first, then the two bounds that must not be confused with each other. They are strings because
     * that is what the store holds and what a form round-trips.
     *
     * <p>THE VALUES ARE THE CLAMPED ONES, which is to say what the pipeline will actually use rather
     * than what somebody typed. Tuning clamps on the way out rather than on the way in, so a file
     * edited by hand or left behind by an older version cannot put the pipeline somewhere the code
     * does not expect — and a client that re-seeds its form from anything else shows a value no
     * prove will ever be given.
     */
    static String model() {
        StringBuilder b = new StringBuilder("{");
        // File-existence, not different-from-default: save the environment's own values back and
        // this flips true. It is what the page means by "edited" and what revert acts on.
        b.append("\"edited\":").append(Tuning.edited());
        b.append(",\"values\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : Tuning.all().entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        b.append('}');
        // WHETHER, AND FROM WHERE — never the key itself. See the note on this class.
        b.append(",\"keySet\":").append(Tuning.keyed());
        // NULL RATHER THAN A GUESS. keyFrom() answers "the environment" when nothing is stored
        // here, which with no key set anywhere would read as "the environment has one" — and the
        // screen that believed it would stop shouting NO KEY SET while nothing answers.
        b.append(",\"keyFrom\":").append(Tuning.keyed() ? quote(Tuning.keyFrom()) : "null");
        return b.append('}').toString();
    }

    /**
     * {@code GET /api/settings/subject} — what this pipeline is pointed at: the queue, the
     * repositories it names, whether a credential can reach them, whether an uploaded tree stands in
     * for the clone, and which JDK the subject's tests will run on.
     *
     * <p>Every read here is empty rather than an exception when the file is missing, because a run
     * that started thirty seconds ago has none of them yet and its dashboard must still render.
     */
    static String subject(Path results) {
        String host = Subject.tokenHost(results);

        StringBuilder b = new StringBuilder("{\"markers\":{");
        b.append("\"queued\":").append(Subject.count(results));
        // ALL OF THEM, in the order the queue first names them. The page shows the first and "and N
        // more"; that truncation is a decision about a narrow column and belongs to whatever is
        // drawing the column, not to this document.
        b.append(",\"repos\":[");
        boolean first = true;
        for (String repo : Subject.repos(results)) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append(quote(repo));
        }
        b.append("]}");

        // PRESENCE AND HOST, NEVER THE TOKEN. The host is what the page shows and is not the
        // secret; the token is stored in git's own credential store rather than pasted into a clone
        // URL so that it stays out of the process list every prover can read, and sending it here
        // would put it back in reach of anything that can call this route.
        b.append(",\"credential\":{\"present\":").append(!host.isBlank());
        b.append(",\"host\":").append(host.isBlank() ? "null" : quote(host)).append('}');

        String chosen = Subject.jdk(results);
        b.append(",\"jdk\":{\"chosen\":").append(quote(chosen));
        // FROM THE SERVER, because this is the list of JDKs in THIS IMAGE and changing the image
        // changes it. A client with its own copy offers a Java that is not installed.
        b.append(",\"available\":[");
        boolean firstJdk = true;
        for (String jdk : Subject.JDKS) {
            if (!firstJdk) {
                b.append(',');
            }
            firstJdk = false;
            b.append(quote(jdk));
        }
        // The literal Subject.jdk() falls back to, not JDKS.get(0): the two would part company the
        // day a newer JDK went into the image ahead of 25, and this field exists so the client can
        // tell "chosen" from "unchanged" without hardcoding either.
        b.append("],\"default\":\"25\"}");

        // A BOOLEAN IS ALL THE RECORD HOLDS. There is no name, size or upload time anywhere: the
        // byte count is reported once in the flash message after the upload and is gone on the next
        // read. Sending anything more would mean inventing it.
        b.append(",\"zip\":{\"present\":").append(Subject.hasZip(results)).append('}');
        // WHERE FETCHES ACTUALLY GO. Not a secret and not derived — the markers name one host
        // and this says where that host is reached, which a reader of a settlement needs in
        // order to know the code was the code the marker names.
        b.append(",\"mirror\":").append(quote(Subject.mirror(results)));
        return b.append('}').toString();
    }

    /**
     * {@code GET /api/settings/prompts} — every agent, what the code tells it, what this deployment
     * tells it instead, and what it will actually be given on the next marker.
     *
     * <p>ARRAY ORDER IS THE CONTRACT: {@link Agents#ORDER}, which is pipeline order, then anything
     * the order does not name, sorted. Sorting the whole thing alphabetically puts estimator-critic
     * first and reproducer eleventh, which is the reverse of how anybody thinks about this chain.
     */
    static String prompts(Map<String, String> builtIns) {
        // FROM THE ORDER ITSELF, NOT FROM THE MAP. BUILT_INS is a ConcurrentHashMap because it is
        // written from a handler thread, and a hash map has no order to preserve — putAll'ing an
        // ordered map into one throws the order away, which is exactly what it did. Anything the
        // order does not name still appears, at the end, so a new agent is visible before it is
        // listed rather than invisible until somebody remembers.
        List<String> names = new ArrayList<>(Agents.ORDER);
        builtIns.keySet().stream().filter(a -> !names.contains(a)).sorted().forEach(names::add);
        names.removeIf(a -> !builtIns.containsKey(a));

        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (String agent : names) {
            if (!first) {
                b.append(',');
            }
            first = false;
            String builtIn = builtIns.getOrDefault(agent, "");
            String saved = Prompts.saved(agent);
            String effective = Prompts.effective(agent, builtIn);
            b.append("{\"agent\":").append(quote(agent));
            // The state, not the heading. Three groups exist in the record and the page shows two,
            // which files `chat` under "watching the run" — and chat watches nothing.
            String group = group(agent);
            b.append(",\"group\":").append(group == null ? "null" : quote(group));
            b.append(",\"builtIn\":").append(quote(builtIn));
            // Blank means the built-in is in force. Deliberately the same answer for an override
            // that cannot be READ: falling back to the code's prompt is the only safe direction,
            // because the alternative is an agent running with no instructions and answering
            // something anyway.
            b.append(",\"saved\":").append(quote(saved));
            b.append(",\"staked\":").append(!Agents.staked(agent).isBlank());
            // What this agent is really being told, so an edit starts from the truth.
            b.append(",\"effective\":").append(quote(effective));
            // An override file exists and is not blank. Note this is NOT the same question as
            // whether it says anything different — paste the built-in back verbatim and save, and
            // this stays true until somebody reverts.
            b.append(",\"edited\":").append(!saved.isBlank());
            // WHICH IS WHY THE OTHER QUESTION IS ANSWERED HERE TOO, and answered by the server: the
            // comparison ignores trailing whitespace and line endings, because a text block, a
            // textarea and a file differ in all three and none of those differences changes what an
            // agent is told. A client rewriting that rule in JavaScript would drift from the marker
            // page, which uses it to decide whether a settlement was reached under instructions
            // nobody is using any more.
            b.append(",\"differs\":").append(!Prompts.same(effective, builtIn));
            b.append('}');
        }
        // AN OBJECT, NOT A BARE ARRAY, because the page has to show two things: the prompts, and
        // the paragraph prepended to the ones that judge the subject's code. Sending only the rows
        // would leave that paragraph invisible — and "a prompt half from the code and half from a
        // box is a prompt nobody can read in one place" is this page's own rule.
        return "{\"preamble\":" + quote(Agents.STAKES.strip())
                + ",\"rows\":" + b.append(']') + "}";
    }

    /**
     * {@code GET /api/settings/run} — how many proves run at once, and the range that is allowed.
     *
     * <p>The bounds are served rather than agreed. They are here and in the shell and both are
     * meant: sixteen at the top because the provers share one inference endpoint and past that they
     * are not proving markers faster, only queueing on the same tokens.
     *
     * <p>{@code workers} is the width in force, which is not proof that anybody set it: a missing or
     * unreadable file reads as the default, because stopping the pool dead over a typo in a one-line
     * file is worse than running at the width it has been running at.
     */
    static String run(Path results) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"workers\":").append(Workers.of(results));
        b.append(",\"min\":").append(Workers.LEAST);
        b.append(",\"max\":").append(Workers.MOST);
        // So the copy can name it, and so the client can tell the default apart from a choice that
        // happens to equal it without carrying its own copy of the number.
        b.append(",\"default\":").append(Workers.DEFAULT);
        return b.append('}').toString();
    }

    /** Which list an agent is in, or null for one no list names — absent is not a guess. */
    private static String group(String agent) {
        if (Agents.CHAIN.contains(agent)) {
            return "chain";
        }
        if (Agents.WATCH.contains(agent)) {
            return "watch";
        }
        return Agents.ASKED.contains(agent) ? "asked" : null;
    }

    /**
     * A JSON string, escaped by the one escaper this program has.
     *
     * <p>EVERY string goes through here, and on this route it matters more than most: a prompt is a
     * multi-line text block full of quotes, and one unescaped value makes the whole document
     * unparseable and the screen blank.
     */
    private static String quote(String s) {
        return "\"" + Settlement.escape(s == null ? "" : s) + "\"";
    }

    /**
     * WHAT AN UPLOAD DID, AS JSON RATHER THAN AS A PAGE.
     *
     * <p>Moved here whole from {@code Dashboard.subjectPosted}, which answered a multipart POST with
     * a rendered page — right when the page was Java's, and wrong now that the thing which asked is a
     * React screen that will draw the outcome itself.
     *
     * <p>THE CONVENTION IS THE ONE THE OLD CODE USED: a message beginning with {@code !} is a
     * failure. Kept rather than tidied into a boolean, because every branch below already speaks it
     * and rewriting all of them to hand back a flag is how a branch gets missed and an error renders
     * as a success. It is unpacked into {@code ok} here, once, where it can be read.
     */
    static String posted(com.sun.net.httpserver.HttpExchange e, Path results) {
        String said = said(e, results);
        boolean ok = !said.startsWith("!");
        return "{\"ok\":" + ok + ",\"said\":\""
                + Settlement.escape(ok ? said : said.substring(1)) + "\"}";
    }

    private static String said(com.sun.net.httpserver.HttpExchange e, Path results) {
        String said;
        try {
            Map<String, byte[]> parts = Upload.parts(e);
            String what = Upload.text(parts, "setting").strip();
            boolean forget = !Upload.text(parts, "forget").isBlank();
            switch (what) {
                case "markers" -> {
                    byte[] file = parts.get("file");
                    String text = file != null && file.length > 0
                            ? new String(file, java.nio.charset.StandardCharsets.UTF_8)
                            : Upload.text(parts, "text");
                    if (text.isBlank()) {
                        said = "!nothing was uploaded and nothing was pasted.";
                    } else {
                        List<String> wrong = Subject.saveMarkers(results, text);
                        said = wrong.isEmpty()
                                ? Subject.count(results) + " marker(s) queued. The old queue is "
                                        + "kept beside it."
                                : "!the queue was NOT replaced:\n  " + String.join("\n  ", wrong);
                    }
                }
                case "token" -> {
                    if (forget) {
                        Subject.forgetToken(results);
                        said = "the credential is gone; clones are public again.";
                    } else {
                        String host = Upload.text(parts, "host").strip();
                        String token = Upload.text(parts, "token").strip();
                        if (host.isBlank() || token.isBlank()) {
                            said = "!a host and a token are both needed.";
                        } else {
                            Subject.saveToken(results, host, token);
                            said = "a credential for " + host + " is stored, owner-only, in git's "
                                    + "own store.";
                        }
                    }
                }
                case "mirror" -> {
                    String pairs = Upload.text(parts, "mirror");
                    Subject.saveMirror(results, pairs);
                    String now = Subject.mirror(results);
                    said = now.isBlank()
                            ? "no mirror; clones go to the host each marker names."
                            : "clones are rewritten by " + now.lines().count()
                                    + " rule(s), from the next marker a prover starts.";
                }
                case "jdk" -> {
                    String chosen = Upload.text(parts, "jdk").strip();
                    Subject.saveJdk(results, chosen);
                    said = Subject.jdk(results).equals(chosen)
                            ? "builds will run on Java " + chosen + " from the next marker."
                            : "!" + chosen + " is not one of the JDKs in this image.";
                }
                case "zip" -> {
                    if (forget) {
                        Files.deleteIfExists(Subject.zip(results));
                        said = "the zip is gone; the markers' repository is cloned again.";
                    } else {
                        byte[] file = parts.get("file");
                        if (file == null || file.length == 0) {
                            said = "!no file was uploaded.";
                        } else if (!(file.length > 4 && file[0] == 'P' && file[1] == 'K')) {
                            said = "!that is not a zip — it does not start with PK.";
                        } else {
                            Files.write(Subject.zip(results), file);
                            said = (file.length / 1024) + "KB stored. Nothing will be cloned while "
                                    + "it is here.";
                        }
                    }
                }
                default -> said = "!nothing to do.";
            }
        } catch (IOException | RuntimeException failed) {
            said = "!" + failed.getClass().getSimpleName() + ": " + failed.getMessage();
        }
        return said;
        }
}
