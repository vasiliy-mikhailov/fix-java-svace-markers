package tech.mikhailov.fsm.runner;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * WHAT A {@code repo} MEANS, for every git host rather than for one.
 *
 * <p>This replaces {@code "https://github.com/" + repo + ".git"}, which was one line and was the whole
 * reason the pipeline could analyse GitHub and nothing else. A Guild with {@code gitlab.company.internal}
 * had no way in at all: the string was hardcoded, and the only knob near it — {@code FSM_GITHUB_API} —
 * moves the CONTENTS API, not the clone.
 *
 * <p>WHAT IS ACCEPTED, and why each shape is here:
 * <ul>
 *   <li>{@code owner/name} — what every existing runbook, the bundled example and the deployed stack
 *       send. It resolves against {@link #DEFAULT_HOST}, which stays {@code github.com}, so nothing that
 *       works today stops working; a Guild sets {@link #HOST_ENV} once and keeps typing slugs.</li>
 *   <li>{@code grp/sub/proj} — a GitLab subgroup path. There is no bound on the nesting, so there is no
 *       segment count to check; anything that is not a host in front is a path on the default host.</li>
 *   <li>{@code https://gitlab.company/grp/proj.git}, {@code ssh://git@host/grp/proj.git} — taken
 *       VERBATIM, including a {@code .git} that is or is not there. Appending a suffix to a URL somebody
 *       wrote out in full would be this code second-guessing a path that server may serve as given.</li>
 *   <li>{@code git@gitlab.company:grp/proj.git} — the scp shorthand, which is what GitLab's own clone
 *       button prints and therefore what gets pasted.</li>
 *   <li>{@code gitlab.company/grp/proj} — a URL with the scheme left off. The first segment carries a
 *       dot or a port, which is the only thing that distinguishes it from {@code owner/name}: read as an
 *       owner it would clone {@code github.com/gitlab.company/grp/proj}, which is a WELL-FORMED URL and
 *       a 404 — the failure that is invisible until somebody follows it.</li>
 * </ul>
 *
 * <p>WHAT IS REFUSED, AND WHY THIS CLASS EXISTS AT ALL. The result of this method becomes an argument to
 * {@code git clone} inside a container that holds the H2 store, the prompts and every other repository's
 * checkout. Four shapes are not repositories:
 * <ul>
 *   <li>a LEADING DASH is an option — {@code git clone --upload-pack=<command>} runs that command;</li>
 *   <li>{@code ext::<command>} is a git TRANSPORT that executes its argument. It is a URL by git's rules
 *       and a remote shell by everybody else's;</li>
 *   <li>{@code file://} and an absolute path clone out of THIS container's filesystem;</li>
 *   <li>a PASSWORD in the userinfo. git stores a clone URL verbatim as {@code remote.origin.url}, and
 *       {@code .git/config} is inside the tree {@link Workspace#readFile} serves — which is exactly the
 *       leak {@link Workspace#CREDENTIAL_HELPER} exists to close. Accepting such a URL would reopen it
 *       through the front door, so the credential keeps travelling in the environment and never here.
 *       A userinfo with no password ({@code ssh://git@host/…}) is a USERNAME and is fine.</li>
 * </ul>
 *
 * <p>A refusal is a VALUE and never an exception: it is answered to the caller as
 * {@code {"ok": false, "error": …}}, which {@code RecordOutcome} records as {@code infra_error} and
 * retries, and which {@code max-infra-strikes} eventually parks as {@code infra_stuck}. A repo string
 * that cannot be cloned has told us NOTHING about the code, so it must never become a verdict.
 */
public final class CloneUrl {

    /**
     * Where {@code owner/name} resolves when nothing says otherwise.
     *
     * <p>It stays github.com, and that is a compatibility decision rather than a preference: the bundled
     * example, the deployed runbooks and every curl in the README send a slug and must keep working.
     */
    public static final String DEFAULT_HOST = "github.com";

    /** The environment variable a deployment moves {@link #DEFAULT_HOST} with. */
    public static final String HOST_ENV = "FSM_GIT_HOST";

    /**
     * THE CLONE CREDENTIAL'S NAME, and it is here rather than in either process because both of them
     * read it and a second spelling would be a deployment that authenticates in one half of itself.
     *
     * <p>{@code GITHUB_TOKEN} became a misleading name the moment this class started resolving
     * gitlab.company.internal: it is the credential for whatever host {@link #of} produced, and a Guild
     * setting it is not talking to GitHub. Neutral, therefore — and NOT
     * {@link Workspace#TOKEN_ENV}, which is this service's own per-command name for the same secret and
     * must stay distinct: that one is deliberately absent from {@link Proc#SECRETS} so the credential
     * helper can see it, and this one is IN that list so no build of a third-party repository can.
     */
    public static final String GIT_TOKEN_ENV = "GIT_TOKEN";

    /**
     * The name it replaces, still read when {@link #GIT_TOKEN_ENV} is unset.
     *
     * <p>A fallback and not a deprecation warning: every deployed {@code .env}, every runbook and the
     * committed compose file name it, and a rename that breaks a running deployment to gain a better
     * word is not worth it.
     */
    public static final String LEGACY_GIT_TOKEN_ENV = "GITHUB_TOKEN";

    /**
     * The GitHub slug, which is the shape the CONTENTS API can serve and the only one it can.
     *
     * <p>Kept here rather than in the orchestrator so the clone and the source fetch cannot disagree
     * about what a GitHub repository looks like — a disagreement whose symptom is an empty source file
     * that reads as "the marker's file was deleted from the repository".
     */
    private static final Pattern OWNER_NAME =
            Pattern.compile("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+");

    /** {@code git@host:group/proj.git} — a username, a host, a colon and a relative path. */
    private static final Pattern SCP_SHORTHAND =
            Pattern.compile("[A-Za-z0-9._-]+@[A-Za-z0-9._-]+(?::[0-9]+)?:[^/:].*");

    /** A host in front of a path: a dot, a port, or the loopback name. */
    private static final Pattern LOOKS_LIKE_A_HOST =
            Pattern.compile("(?:[A-Za-z0-9-]+\\.)+[A-Za-z0-9-]+(?::[0-9]+)?|localhost(?::[0-9]+)?"
                    + "|[A-Za-z0-9-]+:[0-9]+");

    /**
     * The transports this pipeline clones over.
     *
     * <p>An ALLOWLIST, because git's own list includes {@code ext::} (run this command) and
     * {@code file://} (read this container), and a denylist is a promise about the ones somebody thought
     * of. {@code http} is here because an internal host without TLS is a real deployment; it is the
     * operator's call, not this class's.
     */
    private static final List<String> SCHEMES = List.of("https", "http", "ssh", "git");

    /** What a refusal says the caller could have typed instead. */
    private static final String SHAPES =
            "expected owner/name, group/sub/project, a full clone URL "
            + "(https://host/group/project.git, ssh://git@host/group/project.git) "
            + "or host/group/project";

    private CloneUrl() {
    }

    /**
     * A clone URL, or the reason there is none.
     *
     * @param url   what {@code git clone} is given, with NO credential in it. Null when refused.
     * @param error why it was refused, in words that reach a stuck marker's row and a dashboard cell.
     *              Null when accepted. It never quotes a secret: a refused
     *              {@code https://u:p@host/x} says so without repeating {@code p}.
     */
    public record Resolved(String url, String error) {

        public boolean ok() {
            return error == null;
        }

        static Resolved accept(String url) {
            return new Resolved(url, null);
        }

        static Resolved refuse(String reason) {
            return new Resolved(null, reason);
        }
    }

    /**
     * Turn a suspicion row's {@code repo} into something {@code git clone} can be given.
     *
     * @param repo        the row's value, as the ingest wrote it. Trimmed here because it arrives from a
     *                    hand-written JSON body or a CSV cell and is routinely padded — untrimmed it goes
     *                    straight into an argument list, where a trailing newline is part of the hostname.
     * @param defaultHost where a bare {@code owner/name} lives. Blank falls back to {@link #DEFAULT_HOST}.
     */
    public static Resolved of(String repo, String defaultHost) {
        String s = repo == null ? "" : repo.trim();
        if (s.isEmpty()) {
            return Resolved.refuse("`repo` is empty; " + SHAPES);
        }
        // Checked before anything else, and on the WHOLE string: every branch below builds one argv
        // entry, and an embedded space or newline is a second argument to git however the rest parses.
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20 || c == 0x7f) {
                return Resolved.refuse("`repo` contains whitespace or a control character, so it is not "
                        + "one argument to git clone: " + quote(s));
            }
        }
        if (s.charAt(0) == '-') {
            // `git clone --upload-pack=<command> <dir>` runs that command in this container. The argv is
            // assembled from this value, so this is the only place it can be stopped.
            return Resolved.refuse("`repo` starts with '-', which git clone reads as an OPTION and not "
                    + "as a repository: " + quote(s));
        }
        // `ext::` and `transport::url` are git remote HELPERS, and the ext one executes its argument.
        // Tested before the scheme split because `ext::sh -c x` has no "://" in it at all.
        if (s.contains("::")) {
            return Resolved.refuse("`repo` names a git transport helper (ext::/<transport>::), which "
                    + "runs a command rather than cloning a repository: " + quote(s));
        }
        if (s.charAt(0) == '/') {
            return Resolved.refuse("`repo` is an absolute path on this container, not a repository to "
                    + "clone: " + quote(s));
        }
        if (hasDotDotSegment(s)) {
            return Resolved.refuse("`repo` contains a '..' segment, which resolves to a different "
                    + "repository from the one it names: " + quote(s));
        }

        int scheme = s.indexOf("://");
        if (scheme > 0) {
            return withScheme(s, s.substring(0, scheme).toLowerCase(Locale.ROOT));
        }
        // Before the path branch: `git@host:grp/p.git` has a colon and no scheme, and would otherwise be
        // read as a host named "git@host" with a port that is not a number.
        if (SCP_SHORTHAND.matcher(s).matches()) {
            if (s.substring(0, s.indexOf('@')).indexOf(':') >= 0) {
                return Resolved.refuse(credentialRefusal(s));
            }
            return Resolved.accept(s);
        }
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1) {
            return Resolved.refuse("`repo` names no repository under a host: " + quote(s) + "; "
                    + SHAPES);
        }
        String head = s.substring(0, slash);
        String host = defaultHost == null || defaultHost.isBlank()
                ? DEFAULT_HOST : defaultHost.trim();
        if (LOOKS_LIKE_A_HOST.matcher(head).matches()) {
            // A URL with the scheme left off. https, because that is what a browser would have used and
            // because http would be a downgrade this code chose on the operator's behalf.
            return Resolved.accept("https://" + withGitSuffix(s));
        }
        return Resolved.accept("https://" + host + "/" + withGitSuffix(s));
    }

    /**
     * Whether this is the {@code owner/name} slug the GitHub contents API is built around.
     *
     * <p>Read by the source fetch and by the default-branch lookup, both of which build
     * {@code /repos/{repo}/…} and can serve NOTHING else. They refuse loudly on a false here — a GitLab
     * host answers that URL with a 404, and a 404 is recorded as a real finding about the marker ("the
     * file has moved or gone"), which is a lie about a repository nobody ever asked.
     */
    public static boolean isOwnerName(String repo) {
        return repo != null && OWNER_NAME.matcher(repo.trim()).matches();
    }

    /** A URL that spelled out its transport: check the transport, the host and the credential. */
    private static Resolved withScheme(String s, String scheme) {
        if (!SCHEMES.contains(scheme)) {
            return Resolved.refuse("`repo` uses the scheme '" + scheme + "', which this pipeline does "
                    + "not clone over (it clones " + String.join(", ", SCHEMES) + "): " + quote(s));
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException notAUrl) {
            // The reason is quoted rather than the exception's toString, which repeats the whole input
            // a second time in a cell that is already clipped.
            return Resolved.refuse("`repo` is not a URL: " + quote(s) + " (" + notAUrl.getReason()
                    + ")");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return Resolved.refuse("`repo` names no host: " + quote(s) + "; " + SHAPES);
        }
        String userInfo = uri.getUserInfo();
        if (userInfo != null && userInfo.indexOf(':') >= 0) {
            return Resolved.refuse(credentialRefusal(s));
        }
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            return Resolved.refuse("`repo` names a host with no repository on it: " + quote(s) + "; "
                    + SHAPES);
        }
        return Resolved.accept(s);
    }

    /**
     * The one refusal that must not echo what it refused.
     *
     * <p>Every other message quotes the value, because naming it is most of the diagnosis. This one
     * cannot: the value IS the secret, and the message lands in a suspicion row, on the dashboard and in
     * whatever ticket somebody pastes it into.
     */
    private static String credentialRefusal(String s) {
        return "`repo` carries a credential in its URL, which git writes verbatim into the checkout's "
                + ".git/config — where the source window would serve it. Put the token in "
                + GIT_TOKEN_ENV + " instead, which reaches git through a one-shot credential helper and "
                + "is never written to disk. (host " + hostOf(s) + ")";
    }

    /** The host alone, for a message that must not repeat the credential beside it. */
    private static String hostOf(String s) {
        int at = s.lastIndexOf('@');
        if (at < 0 || at + 1 >= s.length()) {
            return "unknown";
        }
        String rest = s.substring(at + 1);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            if (rest.charAt(i) == '/' || rest.charAt(i) == ':') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }

    /**
     * {@code .git} on a path this code assembled — and never on a URL somebody wrote out in full.
     *
     * <p>The suffix is optional to every server here, so it is added only where this code is already
     * inventing the URL and the value it is inventing from is unambiguously a repository path.
     */
    private static String withGitSuffix(String path) {
        return path.endsWith(".git") ? path : path + ".git";
    }

    private static boolean hasDotDotSegment(String s) {
        for (String segment : s.split("/", -1)) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /** Clipped, because this reaches a table cell: a 4 KB paste is not a diagnosis. */
    private static String quote(String s) {
        String clipped = s.length() > 120 ? s.substring(0, 120) + "…" : s;
        return "`" + clipped + "`";
    }
}
