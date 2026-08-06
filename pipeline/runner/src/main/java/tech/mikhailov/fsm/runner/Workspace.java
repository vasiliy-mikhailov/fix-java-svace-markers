package tech.mikhailov.fsm.runner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.lib.Json;

/**
 * The two clones: {@code prepareWs}, {@code prepareFs} and the read behind {@code /fs/read_file}.
 *
 * <p>TWO CLONES, NEVER ONE. {@code /cache/fs/<key>} is read-only and never mutated; {@code /cache/<key>}
 * is the build workspace that gets patched. They are separate because the dashboard reads source through
 * this service while a prove is running: one clone would show a reviewer the file with the fixer's edit
 * already in it, and the marker view exists precisely to show the code that was JUDGED.
 *
 * <p>NEITHER IS CLONED TWICE. Both directories live in a persistent volume, so the {@link #keyFor} hash
 * is part of the contract with the disk and not an implementation detail: change how it is spelled and
 * every repository re-clones from scratch on the next run — minutes per repo, and the Nexus mirror wears
 * it. It is the SHA-1 of {@code "<repo>@<branch>"}, truncated, and an existing checkout under that name
 * is ADOPTED rather than re-cloned — which is also why the cache volume must never be one some other
 * program wrote; see the note on {@link #prepareFs}.
 */
final class Workspace {

    /**
     * Written only AFTER the rename, and the only thing a reader may trust.
     *
     * <p>Testing for {@code .git} instead would let a reader see a half-populated tree and be told the
     * code does not exist — which the dashboard renders as "source unavailable" for a marker whose file
     * is perfectly fine.
     */
    static final String FS_DONE = ".fsm_clone_complete";

    /** 15 minutes: a cold clone of a large repository on a shared mirror. */
    static final long WS_CLONE_TIMEOUT_MS = 900_000L;

    /** 10 minutes for the read-only copy — a dashboard reader is waiting on it, not a prove. */
    static final long FS_CLONE_TIMEOUT_MS = 600_000L;

    /** A local {@code rev-parse} either answers at once or the checkout is broken. */
    static final long REV_PARSE_TIMEOUT_MS = 60_000L;

    /**
     * How long a failed clone is remembered.
     *
     * <p>The dashboard asks for source on every marker a reviewer opens. Without this, a repository that
     * does not exist — a private fork, a deleted branch — costs a full clone attempt per click.
     */
    static final long NEGATIVE_CACHE_MS = 60_000L;

    /** The read-only reply is capped: the marker view shows a window, not a 4 MB generated file. */
    static final int MAX_CONTENT = 80_000;

    /**
     * The most a caller may ask for with {@code max_content}, and why the knob exists at all.
     *
     * <p>{@link #MAX_CONTENT} is a REVIEWER'S window — a screenful either side of one line. The prove's
     * source fetch reads through this same route now that the pipeline no longer needs a host-specific
     * contents API, and a screenful is the wrong answer for it: {@code BuildReproduceInput} re-anchors
     * the marker against whatever comes back, so an 80 kB slice of a 200 kB file puts the marker's line
     * past the end of the source and the marker is written up as drift — a fabricated finding about
     * somebody else's repository. So a caller may ask for more.
     *
     * <p>1 MB, matching what the GitHub contents API itself serves before it stops inlining content, so
     * the two source paths cannot disagree about which files are too big. And it is a CEILING because the
     * reply is assembled in memory on a route that is deliberately not serialised: "as much as you like"
     * is a request any caller could repeat until the heap is gone.
     */
    static final int MAX_CONTENT_CEILING = 1_000_000;

    /**
     * Where the token is handed to git — an environment variable of this process's own choosing, read by
     * {@link #CREDENTIAL_HELPER} and written nowhere.
     *
     * <p>Not {@code GITHUB_TOKEN}: this name exists so the helper text can name it without the value ever
     * appearing in a command line, a config file or a git trace.
     */
    static final String TOKEN_ENV = "FSM_GIT_TOKEN";

    /**
     * The credential, supplied per command instead of persisted.
     *
     * <p>A {@code https://<token>@github.com/…} clone URL is written VERBATIM into the new repository's
     * {@code .git/config} as {@code remote.origin.url}, and {@code .git/config} is inside the tree
     * {@link #readFile} serves — which is how the pull-request token became readable by anything that
     * could reach this service. A helper is consulted at clone time and leaves nothing behind.
     *
     * <p>{@code $1} is the operation git asks for ({@code get}, {@code store}, {@code erase}); only
     * {@code get} is answered, and the helper exits 0 either way so a {@code store} is a silent no-op
     * rather than a failed clone.
     */
    static final String CREDENTIAL_HELPER =
            "!f() { if [ \"$1\" = get ]; then printf 'username=x-access-token\\npassword=%s\\n' \"$"
                    + TOKEN_ENV + "\"; fi; }; f";

    /** {@code {ws}} or {@code {error}}. */
    record Prepared(Path ws, String error) {

        boolean ok() {
            return error == null;
        }
    }

    private final Path cache;
    private final Path fsCache;
    private final String token;
    private final Proc.Exec exec;

    /**
     * Where a bare {@code owner/name} lives — {@link CloneUrl#DEFAULT_HOST} unless a deployment moved it.
     *
     * <p>A FIELD and not a constant, which is the second gap in one word: the host used to be spelled
     * into the clone URL, so a Guild with gitlab.company.internal could not point this pipeline at their
     * code with any setting at all.
     */
    private final String gitHost;

    /**
     * base -> when its clone last failed. ConcurrentHashMap because {@code /fs/read_file} is NOT
     * serialised: it only reads, and two reviewers opening two markers really do arrive at once.
     */
    private final Map<Path, Long> fsFailed = new ConcurrentHashMap<>();

    /** Epoch millis. Injected so a test can prove the negative cache EXPIRES without waiting a minute. */
    private final LongSupplier clock;

    Workspace(Path cache, String token, Proc.Exec exec) {
        this(cache, token, exec, CloneUrl.DEFAULT_HOST);
    }

    Workspace(Path cache, String token, Proc.Exec exec, String gitHost) {
        this(cache, token, exec, gitHost, System::currentTimeMillis);
    }

    // THERE IS NO (cache, token, exec, LongSupplier) OVERLOAD ANY MORE. Two four-argument
    // constructors that differ only in the TYPE of the last argument is how a caller means to move the
    // git host and moves the clock instead, silently, in a class whose clock decides how long a failed
    // clone stays negatively cached. One test wanted it; it now names the host it is not changing.
    Workspace(Path cache, String token, Proc.Exec exec, String gitHost, LongSupplier clock) {
        this.cache = cache;
        this.fsCache = cache.resolve("fs");
        // NULL IS NOT EMPTY, and it arrives here: an unset variable reads back as null through
        // Secrets, and every clone on a tokenless deployment died in gitEnv with a
        // NullPointerException that named nothing an operator could act on. Normalised once, here,
        // because "no credential" is one state and the rest of this class tests it with isEmpty().
        this.token = token == null ? "" : token;
        this.exec = exec;
        this.gitHost = gitHost == null || gitHost.isBlank() ? CloneUrl.DEFAULT_HOST : gitHost.trim();
        this.clock = clock;
    }

    /**
     * {@code sha1(repo@branch)}, first 12 hex characters — the directory name under {@code /cache}.
     *
     * <p>Twelve characters of SHA-1 is not a security claim; it is a filename short enough to read in a
     * {@code docker exec ls} and long enough that the couple of dozen repositories in a run cannot
     * collide. See the class comment for why the spelling must not drift.
     */
    static String keyFor(String repo, String branch) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((repo + "@" + branch).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            // Every JRE ships SHA-1; this cannot happen, and pretending it might would mean inventing
            // a second cache key that no existing directory matches.
            throw new IllegalStateException("SHA-1 is not available", impossible);
        }
    }

    /**
     * The clone URL, or the reason this {@code repo} is not one.
     *
     * <p>NO CREDENTIAL IN IT — see {@link #CREDENTIAL_HELPER} for where the token goes instead, and why a
     * URL is the one place it must not be. {@link CloneUrl} refuses a URL that carries one, so that
     * property cannot be undone from the outside either.
     *
     * <p>It used to be {@code "https://github.com/" + repo + ".git"}, one line, and that line was the
     * whole reason this pipeline could analyse GitHub and nothing else.
     */
    CloneUrl.Resolved cloneUrl(String repo) {
        return CloneUrl.of(repo, gitHost);
    }

    /**
     * The environment a git command that talks to github runs with.
     *
     * <p>{@code GIT_CONFIG_COUNT} is git's environment spelling of {@code -c}: one-shot configuration,
     * inherited by {@code git-remote-https}, and — verified against git 2.50 — NOT written into the
     * repository it clones. Two entries, because the first one CLEARS any helper the image or a mounted
     * home directory might configure: without it an inherited helper would be consulted first and could
     * answer with a credential of its own.
     *
     * <p>The whole parent environment is carried over because {@code execFile} REPLACES the environment
     * when one is passed, and git needs {@code PATH} and {@code HOME} like anything else.
     *
     * <p>{@code GIT_TERMINAL_PROMPT=0} regardless of the token: a credential git cannot obtain must fail
     * the clone, not sit on a terminal that does not exist until the 15-minute timeout kills it.
     */
    Map<String, String> gitEnv() {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put("GIT_TERMINAL_PROMPT", "0");
        if (!token.isEmpty()) {
            env.put("GIT_CONFIG_COUNT", "2");
            env.put("GIT_CONFIG_KEY_0", "credential.helper");
            env.put("GIT_CONFIG_VALUE_0", "");
            env.put("GIT_CONFIG_KEY_1", "credential.helper");
            env.put("GIT_CONFIG_VALUE_1", CREDENTIAL_HELPER);
            env.put(TOKEN_ENV, token);
        }
        // An EMPTY password is not "no credential": github answers 401 to it for a repository anonymous
        // git could have read, so with no token nothing is configured at all.
        return env;
    }

    /**
     * The build workspace: cloned once, then reset to pristine before every job.
     *
     * <p>Reset rather than re-cloned because {@code target/} and the local {@code .m2} stay behind: the
     * second build of a repository is minutes instead of the better part of an hour. {@code reset --hard}
     * drops the previous fix and {@code clean -fd} drops the previous test file, which together are
     * exactly the state a prove must not inherit — a leftover green test would pass before the fix.
     *
     * <p>AND THE CLEANUP HAS TO BE CHECKED. Drop the two exit statuses on the floor and a workspace
     * that could NOT be cleaned comes back as
     * pristine and the next marker's RED build ran against the previous marker's patched source: the test
     * does not fail, {@code red_reproduced} is false for a defect that is really there, and the pipeline
     * writes a false_positive rebuttal about code that is not what is in the repository. That is the one
     * answer this service must never give, and it is silent — nothing downstream can tell it apart from a
     * marker that genuinely does not reproduce.
     *
     * <p>The way in is ordinary. {@link RunnerServer#close} calls {@code shutdownNow()} on every deploy,
     * which interrupts the build thread inside {@link Proc#execFile}, which SIGKILLs the child. git's
     * lockfile cleanup runs on SIGTERM and cannot run on SIGKILL, so a {@code reset --hard} killed there
     * leaves {@code .git/index.lock} behind and every later {@code reset --hard} in that checkout exits
     * 128. Nothing removes that lock, so the damage is permanent for the directory; a permission error
     * during {@code clean -fd} gets there by a different road.
     *
     * <p>THE REMEDY IS THE WHOLE CHECKOUT, not the lockfile. Unpicking git's internal state from out here
     * would be guessing at which of its files may be deleted, and the cache directory is reconstructible
     * by definition — so a tree that cannot be cleaned is thrown away and cloned again. It costs one cold
     * clone plus a {@code target/} rebuild, ONLY on the path where the alternative is a fabricated
     * verdict, and it is what makes the failure self-healing: with 356 markers over a single repository,
     * a workspace that stays broken is the rest of the run.
     */
    Prepared prepareWs(String repo, String branch) {
        // FIRST, and before any directory is touched or any process is started. A repo that is not a
        // repository is not a clone failure: nothing was asked of any host, so "clone failed" would send
        // a reviewer to the network and the token for a value somebody typed. And `git clone
        // --upload-pack=<command>` RUNS that command, so a guard that ran after the exec would be no
        // guard at all.
        CloneUrl.Resolved url = cloneUrl(repo);
        if (!url.ok()) {
            return new Prepared(null, url.error());
        }
        Path base = cache.resolve(keyFor(repo, branch));
        String unclean = null;
        if (Files.exists(base.resolve(".git"))) {
            String at = base.toString();
            Proc.Result reset = exec.run(List.of("git", "-C", at, "reset", "--hard"), null, null,
                    Proc.DEFAULT_TIMEOUT_MS);
            Proc.Result clean = exec.run(List.of("git", "-C", at, "clean", "-fd"), null, null,
                    Proc.DEFAULT_TIMEOUT_MS);
            if (reset.code() == 0 && clean.code() == 0) {
                return new Prepared(base, null);
            }
            // Both, in order: a stale index.lock fails the reset and lets the clean succeed, and a
            // permission error does the opposite, so either one alone would name the wrong culprit.
            // Tailed SEPARATELY — git writes its `fatal:` last, and a `clean -fd` with a thousand
            // "Removing …" lines would otherwise be the only thing left in a shared budget.
            unclean = "workspace was not clean (reset " + reset.code() + ", clean " + clean.code()
                    + "):\n" + Text.tail(reset.out(), 500) + Text.tail(clean.out(), 500);
            // The only place this is ever said out loud when the recovery WORKS. A re-clone that
            // happened for no visible reason is the kind of thing an operator finds out about from the
            // clock, and the reason it happened is the reason to go and look at the volume.
            System.err.println("fsm-runner: re-cloning " + base + " — " + unclean);
            try {
                deleteTree(base);
            } catch (IOException cannotClear) {
                return new Prepared(null, unclean + "\nand could not be cleared: " + cannotClear);
            }
        }
        Proc.Result r = exec.run(
                List.of("git", "clone", "--depth", "1", "--branch", branch, url.url(),
                        base.toString()),
                null, gitEnv(), WS_CLONE_TIMEOUT_MS);
        if (r.code() != 0) {
            // The cleanup complaint is carried into the reply as well as the log: this is the answer an
            // operator reads off a stuck marker, and "clone failed" on a repository that has been
            // cloning fine all day does not say that the CACHED checkout is what went wrong.
            return new Prepared(null, (unclean == null ? "" : unclean + "\n")
                    + "clone failed:\n" + Text.tail(r.out(), 1500));
        }
        return new Prepared(base, null);
    }

    /**
     * The read-only source cache, or null when it could not be produced.
     *
     * <p>Cloned into a temp directory and RENAMED into place, with {@link #FS_DONE} written only after
     * the rename, so a reader never observes a partial tree.
     *
     * <p>KNOWN RACE, left in deliberately. Two simultaneous first-time reads of the same repo both
     * clone into the same {@code <key>.tmp}. The failure mode is a failed clone that the negative cache
     * then absorbs, so it costs a retry and never a wrong answer. If it ever needs fixing, the fix is a
     * per-key mutex here and nothing else changes.
     */
    Path prepareFs(String repo, String branch) {
        // Same guard as prepareWs and for the same reason; the caller turns null into a reason of its
        // own, which is why this one does not have to carry the text.
        if (!cloneUrl(repo).ok()) {
            return null;
        }
        Path base = fsCache.resolve(keyFor(repo, branch));
        if (Files.exists(base.resolve(FS_DONE))) {
            return base;
        }
        try {
            Files.createDirectories(fsCache);
            // A checkout git can resolve HEAD in is complete by definition — adopt it rather than
            // re-clone. This is also why the cache volume must not be one another program wrote:
            // adoption never rewrites remote.origin.url, so a token baked into it would be reused.
            if (Files.exists(base.resolve(".git"))
                    && exec.run(List.of("git", "-C", base.toString(), "rev-parse", "HEAD"),
                            null, null, REV_PARSE_TIMEOUT_MS).code() == 0) {
                Text.write(base.resolve(FS_DONE), branch);
                return base;
            }

            Long failedAt = fsFailed.get(base);
            if (failedAt != null && failedAt > clock.getAsLong() - NEGATIVE_CACHE_MS) {
                return null;
            }

            Path tmp = base.resolveSibling(base.getFileName() + ".tmp");
            deleteTree(tmp);
            Proc.Result r = exec.run(
                    List.of("git", "clone", "--depth", "1", "--branch", branch,
                            cloneUrl(repo).url(), tmp.toString()),
                    null, gitEnv(), FS_CLONE_TIMEOUT_MS);
            if (r.code() != 0) {
                deleteTree(tmp);
                fsFailed.put(base, clock.getAsLong());
                return null;
            }
            deleteTree(base);
            // Atomic: readers never observe a partial tree. Both paths are in the same directory, so
            // this is a rename(2) and not a copy.
            Files.move(tmp, base, StandardCopyOption.ATOMIC_MOVE);
            Text.write(base.resolve(FS_DONE), branch);
            return base;
        } catch (IOException e) {
            // Let it propagate: the route answers {"ok": false, "error": …}, which the dashboard
            // renders as "source unavailable" and the orchestrator records as infra.
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code POST /fs/read_file} — one file out of the read-only checkout, for the dashboard's marker
     * view and the orchestrator's source window.
     *
     * <p>NEVER THROWS FOR A BAD REQUEST: a clone that failed, a path that escapes, a path that is refused
     * and a file that is not there all come back as {@code {"error": …}} with a 200, because the caller
     * renders "source unavailable — &lt;reason&gt;" in a tab whose other four panes are fine.
     *
     * <p>IT SERVES SOURCE, NOT THE CHECKOUT. See {@link #hidden}: nothing under a dot-named directory or
     * file is served, at any depth, links resolved — {@code .git/config} carried the pull-request token,
     * and a caller that could reach this port could read it by name.
     */
    Map<String, Object> readFile(Object body) {
        String repo = Text.field(body, "repo");
        String branch = Text.orDefault(Json.get(body, "branch"), "main");
        // Answered SEPARATELY from "clone failed", because they send a reader to different places: one
        // is the network or the token, the other is a value somebody typed into the ingest body. The
        // prove-time source fetch reads this route too and turns the two into different outcomes — a
        // refused repo is never a file that has gone missing.
        CloneUrl.Resolved url = cloneUrl(repo);
        if (!url.ok()) {
            return error(url.error());
        }
        Path base = prepareFs(repo, branch);
        if (base == null) {
            return error("clone failed");
        }

        Object requested = Json.get(body, "path");
        Path root = base.toAbsolutePath().normalize();
        // A name this JVM cannot SPELL — see PathEncoding, and see fixTarget for the same guard on the
        // edit path. Without it the reviewer is told "source unavailable — file not found" about a file
        // that is in the checkout, and the cause (a runner process with no UTF-8 locale) is nowhere in
        // the answer. Unreachable when the encoding is UTF-8.
        if (!PathEncoding.spells(Values.text(requested))) {
            return error(PathEncoding.refusal("path"));
        }
        Path full;
        try {
            full = root.resolve(stripLeadingSlashes(Values.text(requested))).normalize();
        } catch (InvalidPathException notAFilename) {
            // A NUL in the name: `Path.of` refuses it, `path.resolve` did not, and `existsSync` then
            // simply said no. Answering the same {"error": "file not found: …"} keeps the promise this
            // method's comment makes — a bad request is an answer, not a throw — where letting it out
            // would reach the route's catch-all and put a Java stack trace in the dashboard's
            // "source unavailable" line.
            return error("file not found: " + Text.field(body, "path"));
        }
        // Component-wise, and NOT a string prefix test. A string test admits a SIBLING directory whose
        // name merely starts with the key — and `<key>.tmp`, the half-finished clone, is exactly such a
        // sibling, with a .git/config of its own. Nothing sends a path like that; closing it is free.
        if (!full.startsWith(root)) {
            return error("path escapes repo");
        }
        // Refused BEFORE the file is even looked for, so the answer says nothing about what is there.
        if (hidden(root, full)) {
            return error("path not permitted");
        }
        if (!Files.isRegularFile(full)) {
            return error("file not found: " + Text.field(body, "path"));
        }
        // AND AGAIN WITH THE LINKS RESOLVED. The two tests above are lexical, and a lexical rule trusts
        // the tree: any repository this pipeline clones may ship `Innocent.java -> .git/config`, and then
        // nothing the CALLER typed was suspicious at all. `toRealPath` is what turns both rules into
        // statements about the file finally opened. The root is resolved too — /var is a symlink to
        // /private/var on the machines this is developed on, and comparing a real path against a
        // symlinked root would refuse every request.
        try {
            Path realRoot = root.toRealPath();
            Path real = full.toRealPath();
            if (!real.startsWith(realRoot)) {
                return error("path escapes repo");
            }
            if (hidden(realRoot, real)) {
                return error("path not permitted");
            }
        } catch (IOException vanished) {
            // It was a regular file a moment ago: a clone replaced underneath us. Same answer as any
            // other file that is not there.
            return error("file not found: " + Text.field(body, "path"));
        }

        String content;
        try {
            content = Text.read(full);
        } catch (IOException unreadable) {
            return error("file not found: " + Text.field(body, "path"));
        }

        int cap = capFor(Json.get(body, "max_content"));
        Map<String, Object> out = new LinkedHashMap<>();
        // The request's own value, uncoerced: the caller matches the reply to the file it asked for.
        out.put("path", requested);
        out.put("content", content.length() > cap ? content.substring(0, cap) : content);
        out.put("truncated", content.length() > cap);
        return out;
    }

    /**
     * How much of the file this reply may carry.
     *
     * <p>Absent, unparseable or non-positive all mean {@link #MAX_CONTENT} — the dashboard's window, which
     * is what every caller got before this parameter existed and what a caller sending nothing must keep
     * getting. Anything above {@link #MAX_CONTENT_CEILING} is clamped rather than refused: the request is
     * still answerable, and a refusal here would reach the source window as "source unavailable" for a
     * file that is perfectly readable.
     */
    private static int capFor(Object requested) {
        double asked = Values.leadingInt(Values.text(requested).trim());
        if (Double.isNaN(asked) || asked <= 0) {
            return MAX_CONTENT;
        }
        return (int) Math.min(asked, MAX_CONTENT_CEILING);
    }

    /**
     * Does any component below {@code root} begin with a dot?
     *
     * <p>THE CONTAINMENT RULE, and it is a rule about the TREE rather than about a list of names worth
     * hiding. {@code .git/config} holds the clone credential, {@code .git/logs} holds the URLs it was
     * used with, and {@code .fsm_clone_complete} is this service's own bookkeeping — none of them is
     * repository SOURCE, which is the only thing the marker view and the source window ask for. A
     * denylist of {@code .git} alone would be a promise about the one directory somebody thought of.
     *
     * <p>Checked after normalisation, so {@code ./src/main/java/A.java} — which a caller may legitimately
     * send — has no dot component at all, while {@code a/../.git/config} has one.
     */
    private static boolean hidden(Path root, Path path) {
        for (Path part : root.relativize(path)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> error(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", reason);
        return out;
    }

    /** {@code String(p || '').replace(/^\/+/, '')} — an absolute-looking path means repo-relative. */
    static String stripLeadingSlashes(String p) {
        int i = 0;
        while (i < p.length() && p.charAt(i) == '/') {
            i++;
        }
        return p.substring(i);
    }

    /** {@code fs.rmSync(p, {recursive: true, force: true})}: gone, or it was never there. */
    static void deleteTree(Path path) throws IOException {
        // NOFOLLOW_LINKS: a BROKEN symlink does not "exist" to Files.exists, and skipping it here would
        // leave it behind to fail the rename that follows.
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
