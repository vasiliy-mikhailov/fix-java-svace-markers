package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two clones, and the read-only route the dashboard reads source through.
 *
 * <p>The cache keys are asserted against LITERAL hashes. That is deliberate: {@code /cache} is a persistent
 * volume that the JS filled, and this service is meant to adopt those directories rather than start a
 * second copy of every repository beside them. A change to how the key is spelled is a re-clone of
 * everything, so it has to fail here rather than be discovered as an unexplained hour of git traffic.
 */
class WorkspaceTest {

    private static Object body(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }

    @Test
    void theCacheKeyIsTheJsOne() {
        assertEquals("177930ea21d4", Workspace.keyFor("owner/repo", "main"));
        assertEquals("72e89edef07d", Workspace.keyFor("owner/repo", "dev"),
                "the branch is part of the key: two branches are two checkouts");
        assertEquals(12, Workspace.keyFor("owner/repo", "main").length());
    }

    @Test
    void theCloneUrlCarriesNoCredentialAtAll(@TempDir Path cache) {
        // `git clone https://<token>@github.com/o/r.git` writes that URL VERBATIM into the new
        // repository's .git/config (verified: git 2.50 stores remote.origin.url as given), and .git/config
        // sits inside the tree /fs/read_file serves. The token that can open pull requests was therefore
        // one POST away from any caller that could reach this service.
        Workspace withToken = new Workspace(cache, "ghp_secret", new FakeExec(c -> FakeExec.ok("")));
        assertEquals("https://github.com/o/r.git", withToken.cloneUrl("o/r"),
                "the URL is what lands on disk, so the credential must not be in it");
        Workspace without = new Workspace(cache, "", new FakeExec(c -> FakeExec.ok("")));
        assertEquals("https://github.com/o/r.git", without.cloneUrl("o/r"));
    }

    @Test
    void theTokenReachesGitAsOneShotConfigAndTouchesNeitherDiskNorCommandLine(@TempDir Path cache) {
        FakeExec exec = new FakeExec(c -> {
            FakeExec.clonedInto(c);
            return FakeExec.ok("");
        });
        new Workspace(cache, "ghp_secret", exec).prepareWs("o/r", "main");
        FakeExec.Call clone = exec.calls().getFirst();

        assertFalse(clone.joined().contains("ghp_secret"),
                "argv is readable by every process in the container: " + clone.joined());
        Map<String, String> env = clone.env();
        assertNotNull(env, "the credential travels in the environment, which is not persisted anywhere");
        assertEquals("ghp_secret", env.get("FSM_GIT_TOKEN"));
        // GIT_CONFIG_COUNT is git's own spelling of `-c`: one-shot config, passed down to git-remote-https
        // through the environment and never written into the clone. Verified against git 2.50 — a clone
        // made with these variables set has no credential.* in its .git/config.
        assertEquals("2", env.get("GIT_CONFIG_COUNT"));
        assertEquals("credential.helper", env.get("GIT_CONFIG_KEY_0"));
        assertEquals("", env.get("GIT_CONFIG_VALUE_0"),
                "the empty value CLEARS any inherited helper, so ours is the only one consulted");
        assertEquals("credential.helper", env.get("GIT_CONFIG_KEY_1"));
        assertTrue(env.get("GIT_CONFIG_VALUE_1").contains("FSM_GIT_TOKEN"),
                "the helper reads the token out of the environment: " + env.get("GIT_CONFIG_VALUE_1"));
        assertFalse(env.get("GIT_CONFIG_VALUE_1").contains("ghp_secret"),
                "…and not out of the helper text, which git would echo in a trace");
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"),
                "a credential git cannot get must fail, not wait 15 minutes on a terminal");
        assertEquals(System.getenv("PATH"), env.get("PATH"),
                "execFile REPLACES the environment, so the inherited one has to be carried over");
    }

    @Test
    void withNoTokenNoCredentialIsConfiguredAtAll(@TempDir Path cache) {
        // An empty password offered to git is not "no credential": github answers 401 for a repository
        // that anonymous git could have read.
        FakeExec exec = new FakeExec(c -> {
            FakeExec.clonedInto(c);
            return FakeExec.ok("");
        });
        new Workspace(cache, "", exec).prepareWs("o/r", "main");
        Map<String, String> env = exec.calls().getFirst().env();
        assertNotNull(env);
        assertNull(env.get("GIT_CONFIG_COUNT"));
        assertNull(env.get("FSM_GIT_TOKEN"));
        assertEquals("0", env.get("GIT_TERMINAL_PROMPT"));
    }

    @Nested
    class TheBuildWorkspaceIsClonedOnceAndResetAfterwards {

        @Test
        void firstJobClones(@TempDir Path cache) {
            FakeExec exec = new FakeExec(c -> {
                FakeExec.clonedInto(c);
                return FakeExec.ok("");
            });
            Workspace.Prepared p = new Workspace(cache, "", exec).prepareWs("o/r", "main");
            assertTrue(p.ok(), String.valueOf(p.error()));
            assertEquals(cache.resolve(Workspace.keyFor("o/r", "main")), p.ws());
            assertEquals(List.of("git clone --depth 1 --branch main https://github.com/o/r.git "
                    + p.ws()), exec.commands());
            assertEquals(Workspace.WS_CLONE_TIMEOUT_MS, exec.calls().getFirst().timeoutMillis(),
                    "a cold clone of a large repository needs longer than a build");
        }

        @Test
        void thesecondJobResetsInsteadOfRecloning(@TempDir Path cache) {
            FakeExec exec = new FakeExec(c -> {
                if (c.isGitClone()) {
                    FakeExec.clonedInto(c);
                }
                return FakeExec.ok("");
            });
            Workspace workspace = new Workspace(cache, "", exec);
            workspace.prepareWs("o/r", "main");
            Workspace.Prepared again = workspace.prepareWs("o/r", "main");

            assertTrue(again.ok());
            // reset --hard drops the previous fix and clean -fd drops the previous test file. target/ and
            // .m2 survive, which is the difference between a two-minute build and an hour.
            assertEquals(List.of("git -C " + again.ws() + " reset --hard",
                    "git -C " + again.ws() + " clean -fd"),
                    exec.commands().subList(1, exec.commands().size()));
        }

        @Test
        void aFailedCloneIsReportedWithTheEndOfGitsComplaint(@TempDir Path cache) {
            FakeExec exec = new FakeExec(c -> FakeExec.failed(
                    "x".repeat(4000) + "\nfatal: Remote branch nope not found in upstream origin"));
            Workspace.Prepared p = new Workspace(cache, "", exec).prepareWs("o/r", "nope");

            assertFalse(p.ok());
            assertTrue(p.error().startsWith("clone failed:\n"), p.error());
            assertTrue(p.error().contains("fatal: Remote branch nope not found"),
                    "the END of the output is kept, because that is where git says why");
            assertTrue(p.error().contains("...(truncated)..."), "and the cut is marked");
            assertTrue(p.error().length() < 2000, "1500 characters, not the whole 4 kB: " + p.error());
        }
    }

    /**
     * THE CLEANUP THAT FAILED, and the wrong answer it produces.
     *
     * <p>{@code reset --hard} and {@code clean -fd} are the ONLY thing standing between one marker's
     * prove and the next one's, and their exit status used to be dropped on the floor. A workspace that
     * could not be cleaned was still handed back as pristine, so the next marker's RED build ran against
     * the previous marker's patched source: the test does not fail, {@code red_reproduced} comes back
     * false for a defect that is really there, {@code RecordOutcome} routes it to NOT_REPRODUCED, and
     * {@code Verdict} writes a false_positive rebuttal about code that is not what is in the repository.
     * A wrong answer wearing the shape of a right one, and it is the one answer this fleet must not give.
     *
     * <p>It is not hypothetical. {@code RunnerServer.close()} calls {@code builds.shutdownNow()} on every
     * deploy — the handoff's "the restart kills any in-flight run" — which interrupts the build thread
     * inside {@link Proc#execFile}, which SIGKILLs the child. git's lockfile cleanup runs on SIGTERM and
     * cannot run on SIGKILL, so a {@code reset --hard} killed there leaves {@code .git/index.lock}
     * behind and every later {@code reset --hard} in that checkout exits 128 with "Unable to create
     * '…/.git/index.lock': File exists" (reproduced against git 2.50.1). Nothing anywhere removes that
     * lock, so without this the corruption is PERMANENT for the cache directory — and with 356 markers
     * over one repository, permanent means the rest of the run.
     */
    @Nested
    class AWorkspaceThatCouldNotBeCleanedIsNotPristine {

        /** What the repository has: no guard. */
        private static final String PRISTINE =
                "class Div { static int d(int a, int b) { return a / b; } }\n";

        /** What the PREVIOUS marker's fix left in the tracked source. */
        private static final String PATCHED =
                "class Div { static int d(int a, int b) { if (b == 0) return 0; return a / b; } }\n";

        /** A checkout as the previous prove left it: patched source, and a test file beside it. */
        private Path seedDirtyCheckout(Path cache) throws IOException {
            Path base = cache.resolve(Workspace.keyFor("o/r", "main"));
            Files.createDirectories(base.resolve(".git"));
            Files.createDirectories(base.resolve("src/main/java"));
            Files.createDirectories(base.resolve("src/test/java"));
            Files.writeString(base.resolve("src/main/java/Div.java"), PATCHED);
            Files.writeString(base.resolve("src/test/java/DivTest.java"), "class DivTest {}\n");
            return base;
        }

        /** A clone that puts the repository's own source back. */
        private FakeExec.Handler clonesPristine(FakeExec.Handler otherwise) {
            return c -> {
                if (c.isGitClone()) {
                    Path target = FakeExec.clonedInto(c);
                    Files.createDirectories(target.resolve("src/main/java"));
                    Files.writeString(target.resolve("src/main/java/Div.java"), PRISTINE);
                    return FakeExec.ok("");
                }
                return otherwise.handle(c);
            };
        }

        @Test
        void aResetThatCouldNotRunLeavesThePreviousMarkersFixInTrackedSource(@TempDir Path cache)
                throws IOException {
            Path base = seedDirtyCheckout(cache);
            Files.writeString(base.resolve(".git/index.lock"), "");

            FakeExec exec = new FakeExec(clonesPristine(c -> {
                if (c.joined().contains("reset --hard")) {
                    // Verbatim from git 2.50.1 with a stale lock present.
                    return new Proc.Result(128, "fatal: Unable to create '" + base
                            + "/.git/index.lock': File exists.\n\nAnother git process seems to be "
                            + "running in this repository\n");
                }
                // …and `clean -fd` exits 0 even so, which is why the reset's status is the one that
                // has to be read: it removes the untracked test file and leaves the patch behind.
                return FakeExec.ok("Removing src/test/\n");
            }));

            Workspace.Prepared p = new Workspace(cache, "", exec).prepareWs("o/r", "main");

            assertTrue(p.ok(), String.valueOf(p.error()));
            assertEquals(PRISTINE, Files.readString(p.ws().resolve("src/main/java/Div.java")),
                    "the RED build runs against whatever this hands back: with the previous marker's "
                    + "fix still in it the test passes, and a real defect is written up as a "
                    + "false positive");
            assertFalse(Files.exists(base.resolve(".git/index.lock")),
                    "and the lock that nothing else ever removes went with it");
        }

        @Test
        void aCleanThatCouldNotRunLeavesThePreviousMarkersTestFile(@TempDir Path cache)
                throws IOException {
            // The other cause, and it fails the other way round: `git clean -fd` exits 1 on a file it
            // cannot unlink ("warning: failed to remove src/test/java/DivTest.java: Permission
            // denied") while `reset --hard` is perfectly happy. A leftover test file is worse than a
            // leftover fix — it is GREEN before the fixer has written anything.
            Path base = seedDirtyCheckout(cache);

            FakeExec exec = new FakeExec(clonesPristine(c -> c.joined().contains("clean -fd")
                    ? new Proc.Result(1, "warning: failed to remove src/test/java/DivTest.java: "
                            + "Permission denied\n")
                    : FakeExec.ok("")));

            Workspace.Prepared p = new Workspace(cache, "", exec).prepareWs("o/r", "main");

            assertTrue(p.ok(), String.valueOf(p.error()));
            assertFalse(Files.exists(p.ws().resolve("src/test/java/DivTest.java")),
                    "a test file the last prove wrote passes before this one's fix is applied");
        }

        @Test
        void aWorkspaceThatCannotBeReplacedIsAnErrorAndNeverASilentPristine(@TempDir Path cache)
                throws IOException {
            // If the recovery clone fails too there is no workspace, and saying so is the honest
            // answer: RecordOutcome records infra_error and requeues, which is a marker deferred
            // rather than a marker decided wrongly.
            Path base = seedDirtyCheckout(cache);
            Files.writeString(base.resolve(".git/index.lock"), "");

            FakeExec exec = new FakeExec(c -> c.isGitClone()
                    ? FakeExec.failed("fatal: could not read Username for 'https://github.com'")
                    : new Proc.Result(128, "fatal: Unable to create '" + base
                            + "/.git/index.lock': File exists.\n"));

            Workspace.Prepared p = new Workspace(cache, "", exec).prepareWs("o/r", "main");

            assertFalse(p.ok(), "a tree that could not be made pristine is not a workspace");
            assertTrue(p.error().contains("index.lock"),
                    "…and it says why it threw the cached checkout away: " + p.error());
            assertTrue(p.error().contains("could not read Username"),
                    "…as well as why the replacement did not arrive: " + p.error());
        }

        @Test
        void aCleanupThatWorkedStillCostsNoCloneAtAll(@TempDir Path cache) throws IOException {
            // The recovery must stay on the failure path. Re-cloning a repository whose reset worked
            // would throw away target/ on every single marker — the difference between a two-minute
            // build and the better part of an hour, 356 times.
            seedDirtyCheckout(cache);
            FakeExec exec = new FakeExec(clonesPristine(c -> FakeExec.ok("")));
            Workspace.Prepared p = new Workspace(cache, "", exec).prepareWs("o/r", "main");

            assertTrue(p.ok(), String.valueOf(p.error()));
            assertEquals(List.of("git -C " + p.ws() + " reset --hard",
                    "git -C " + p.ws() + " clean -fd"), exec.commands());
            assertEquals(PATCHED, Files.readString(p.ws().resolve("src/main/java/Div.java")),
                    "git said it cleaned the tree, so nothing here second-guesses it");
        }
    }

    @Nested
    class TheReadOnlyCacheIsNeverObservedHalfPopulated {

        @Test
        void clonedIntoATempDirectoryThenRenamedIntoPlace(@TempDir Path cache) throws IOException {
            FakeExec exec = new FakeExec(c -> {
                Path target = FakeExec.clonedInto(c);
                Files.writeString(target.resolve("A.java"), "class A {}\n");
                return FakeExec.ok("");
            });
            Path base = new Workspace(cache, "", exec).prepareFs("o/r", "main");

            assertNotNull(base);
            assertTrue(exec.commands().getFirst().endsWith(base + ".tmp"),
                    "the clone lands in <key>.tmp: " + exec.commands().getFirst());
            assertEquals("main", Files.readString(base.resolve(Workspace.FS_DONE)),
                    "the marker is written only AFTER the rename, and records which branch is in there");
            assertTrue(Files.exists(base.resolve("A.java")));
            assertFalse(Files.exists(Path.of(base + ".tmp")), "the temp directory is gone");
        }

        @Test
        void aCompletedCloneIsNotTouchedAgain(@TempDir Path cache) {
            FakeExec exec = new FakeExec(c -> {
                FakeExec.clonedInto(c);
                return FakeExec.ok("");
            });
            Workspace workspace = new Workspace(cache, "", exec);
            Path first = workspace.prepareFs("o/r", "main");
            Path second = workspace.prepareFs("o/r", "main");

            assertEquals(first, second);
            assertEquals(1, exec.calls().size(),
                    "the dashboard asks for source on every marker a reviewer opens; a second clone per "
                    + "click would be minutes each");
        }

        @Test
        void aCheckoutGitCanResolveHeadInIsAdoptedRatherThanRecloned(@TempDir Path cache)
                throws IOException {
            // This is what lets the Java service inherit the directories the JS left in the volume: they
            // have a .git and no marker file, because the marker is this port's own bookkeeping.
            Path base = cache.resolve("fs").resolve(Workspace.keyFor("o/r", "main"));
            Files.createDirectories(base.resolve(".git"));
            Files.writeString(base.resolve("A.java"), "class A {}\n");

            FakeExec exec = new FakeExec(c -> FakeExec.ok("deadbeef"));
            assertEquals(base, new Workspace(cache, "", exec).prepareFs("o/r", "main"));
            assertEquals(List.of("git -C " + base + " rev-parse HEAD"), exec.commands());
            assertEquals("main", Files.readString(base.resolve(Workspace.FS_DONE)));
        }

        @Test
        void aCheckoutGitCannotResolveHeadInIsReplaced(@TempDir Path cache) throws IOException {
            // An interrupted clone leaves a .git that rev-parse rejects. Adopting it would serve a
            // reviewer an empty tree and call it the source of record.
            Path base = cache.resolve("fs").resolve(Workspace.keyFor("o/r", "main"));
            Files.createDirectories(base.resolve(".git"));

            FakeExec exec = new FakeExec(c -> {
                if (c.isGitClone()) {
                    Path target = FakeExec.clonedInto(c);
                    Files.writeString(target.resolve("A.java"), "class A {}\n");
                    return FakeExec.ok("");
                }
                return FakeExec.failed("fatal: ambiguous argument 'HEAD'");
            });
            Path resolved = new Workspace(cache, "", exec).prepareFs("o/r", "main");
            assertNotNull(resolved);
            assertTrue(Files.exists(resolved.resolve("A.java")), "the broken checkout was replaced");
        }

        @Test
        void aFailedCloneIsRememberedForAMinute(@TempDir Path cache) {
            FakeExec exec = new FakeExec(c -> FakeExec.failed("fatal: repository not found"));
            AtomicLong now = new AtomicLong(1_000_000);
            Workspace workspace = new Workspace(cache, "", exec, now::get);

            assertNull(workspace.prepareFs("o/r", "main"));
            assertNull(workspace.prepareFs("o/r", "main"));
            assertEquals(1, exec.calls().size(),
                    "a private fork or a deleted branch must not cost a clone attempt per click");

            now.addAndGet(Workspace.NEGATIVE_CACHE_MS - 1);
            assertNull(workspace.prepareFs("o/r", "main"));
            assertEquals(1, exec.calls().size(), "still inside the window");

            // …AND IT EXPIRES. A repository that was private when the run started, or a branch pushed a
            // minute later, must recover on its own — otherwise every marker in it reads as "source
            // unavailable" until somebody restarts the container.
            now.addAndGet(2);
            assertNull(workspace.prepareFs("o/r", "main"));
            assertEquals(2, exec.calls().size(), "the window lapsed, so it tried again");
        }

        @Test
        void aTempTreeLeftByACrashedCloneIsNotReused(@TempDir Path cache) throws IOException {
            // A container killed mid-clone leaves <key>.tmp behind. Cloning into it would mix two
            // checkouts and then rename the mixture into place as the source of record.
            Path base = cache.resolve("fs").resolve(Workspace.keyFor("o/r", "main"));
            Path tmp = Path.of(base + ".tmp");
            Files.createDirectories(tmp);
            Files.writeString(tmp.resolve("LEFTOVER"), "from a crashed clone");

            FakeExec exec = new FakeExec(c -> {
                Path target = FakeExec.clonedInto(c);
                Files.writeString(target.resolve("A.java"), "class A {}\n");
                return FakeExec.ok("");
            });
            Path resolved = new Workspace(cache, "", exec).prepareFs("o/r", "main");

            assertNotNull(resolved);
            assertTrue(Files.exists(resolved.resolve("A.java")));
            assertFalse(Files.exists(resolved.resolve("LEFTOVER")), "the stale tree was cleared first");
        }

        @Test
        void aFailedCloneLeavesNoPartialTreeBehind(@TempDir Path cache) throws IOException {
            FakeExec exec = new FakeExec(c -> {
                Path target = FakeExec.clonedInto(c);
                Files.writeString(target.resolve("half"), "");
                return FakeExec.failed("fatal: the connection died");
            });
            Path base = cache.resolve("fs").resolve(Workspace.keyFor("o/r", "main"));
            assertNull(new Workspace(cache, "", exec).prepareFs("o/r", "main"));
            assertFalse(Files.exists(Path.of(base + ".tmp")),
                    "the temp tree is deleted, or the next attempt clones into a dirty directory");
            assertFalse(Files.exists(base));
        }
    }

    @Nested
    class ReadFile {

        private Workspace withFile(Path cache, String name, String content) {
            return new Workspace(cache, "", new FakeExec(c -> {
                Path target = FakeExec.clonedInto(c);
                Files.createDirectories(target.resolve(name).getParent());
                Files.writeString(target.resolve(name), content);
                return FakeExec.ok("");
            }));
        }

        @Test
        void answersTheFileAndEchoesThePathBack(@TempDir Path cache) {
            Map<String, Object> r = withFile(cache, "src/main/java/A.java", "class A {}\n")
                    .readFile(body("repo", "o/r", "branch", "main", "path", "src/main/java/A.java"));
            assertEquals("src/main/java/A.java", r.get("path"),
                    "the caller matches the reply to the file it asked for");
            assertEquals("class A {}\n", r.get("content"));
            assertEquals(Boolean.FALSE, r.get("truncated"));
        }

        @Test
        void aLongFileIsCutAndSaysSo(@TempDir Path cache) {
            String big = "x".repeat(Workspace.MAX_CONTENT + 10);
            Map<String, Object> r = withFile(cache, "A.java", big)
                    .readFile(body("repo", "o/r", "path", "A.java"));
            assertEquals(Workspace.MAX_CONTENT, ((String) r.get("content")).length());
            assertEquals(Boolean.TRUE, r.get("truncated"),
                    "the marker view must know it is not looking at the whole file");
        }

        @Test
        void aFileExactlyAtTheLimitIsWholeAndSaysSo(@TempDir Path cache) {
            // The boundary is inclusive both ways. `truncated` drives the caller's "…and more" banner, and
            // the source window counts LINES off `content` — so claiming a whole file was cut sends a
            // reviewer looking for code that is already on the screen.
            String exact = "x".repeat(Workspace.MAX_CONTENT);
            Map<String, Object> r = withFile(cache, "A.java", exact)
                    .readFile(body("repo", "o/r", "path", "A.java"));
            assertEquals(exact, r.get("content"));
            assertEquals(Boolean.FALSE, r.get("truncated"));
        }

        @Test
        void branchDefaultsToMain(@TempDir Path cache) {
            Workspace workspace = withFile(cache, "A.java", "class A {}\n");
            assertEquals("class A {}\n",
                    workspace.readFile(body("repo", "o/r", "path", "A.java")).get("content"));
            assertEquals(cache.resolve("fs").resolve(Workspace.keyFor("o/r", "main")),
                    workspace.prepareFs("o/r", "main"), "…the same key the default produced");
        }

        @Test
        void aPathEscapingTheRepoIsRefused(@TempDir Path cache) {
            Map<String, Object> r = withFile(cache, "A.java", "class A {}\n")
                    .readFile(body("repo", "o/r", "path", "../../../etc/passwd"));
            assertEquals("path escapes repo", r.get("error"));
        }

        @Test
        void anAbsoluteLookingPathIsReadAsRepositoryRelative(@TempDir Path cache) {
            assertEquals("class A {}\n", withFile(cache, "A.java", "class A {}\n")
                    .readFile(body("repo", "o/r", "path", "/A.java")).get("content"));
        }

        @Test
        void aMissingFileNamesWhatWasAskedFor(@TempDir Path cache) {
            Map<String, Object> r = withFile(cache, "A.java", "class A {}\n")
                    .readFile(body("repo", "o/r", "path", "B.java"));
            assertEquals("file not found: B.java", r.get("error"));
        }

        @Test
        void aPathThatIsExplicitlyNullIsNamedNullAndNotUndefined(@TempDir Path cache) {
            // FOUND BY harness/run.sh. `${null}` is "null" and `${undefined}` is "undefined"; Json.parse
            // maps a missing key and an explicit null to the same Java null, so the spelling has to come
            // from the BODY rather than from the value. The dashboard prints this line verbatim under
            // "source unavailable", and the same coercion one field over names the cache directory.
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("repo", "o/r");
            b.put("path", null);
            assertEquals("file not found: null",
                    withFile(cache, "A.java", "class A {}\n").readFile(b).get("error"));

            b.remove("path");
            assertEquals("file not found: undefined",
                    withFile(cache, "A.java", "class A {}\n").readFile(b).get("error"),
                    "…and an absent path is still the other word");
        }

        @Test
        void aDirectoryIsNotAFile(@TempDir Path cache) {
            Map<String, Object> r = withFile(cache, "src/A.java", "class A {}\n")
                    .readFile(body("repo", "o/r", "path", "src"));
            assertEquals("file not found: src", r.get("error"));
        }

        @Test
        void aCloneThatFailedIsAnAnswerNotAnException(@TempDir Path cache) {
            // The dashboard renders "source unavailable — <reason>" inside a tab whose other four panes
            // are perfectly readable, so this must not throw.
            Workspace workspace = new Workspace(cache, "",
                    new FakeExec(c -> FakeExec.failed("fatal: repository not found")));
            assertEquals("clone failed",
                    workspace.readFile(body("repo", "o/r", "path", "A.java")).get("error"));
        }

        @Test
        void aFileWhoseBytesAreNotUtf8IsStillReadable(@TempDir Path cache) throws IOException {
            // A latin-1 source file is ordinary in an old repository. Files.readString would THROW on it,
            // and the throw would surface as the whole request failing; Node substituted U+FFFD.
            Workspace workspace = new Workspace(cache, "", new FakeExec(c -> {
                Path target = FakeExec.clonedInto(c);
                Files.write(target.resolve("A.java"),
                        new byte[] {'/', '/', ' ', (byte) 0xE9, '\n'});
                return FakeExec.ok("");
            }));
            Map<String, Object> r = workspace.readFile(body("repo", "o/r", "path", "A.java"));
            assertNull(r.get("error"), "a bad byte is not a missing file");
            assertEquals("// �\n", r.get("content"));
        }

        /** A clone that looks like a real one: source, plus the .git git leaves behind. */
        private Workspace withGitDir(Path cache) {
            return new Workspace(cache, "ghp_secret", new FakeExec(c -> {
                Path target = FakeExec.clonedInto(c);
                Files.createDirectories(target.resolve("src/main/java"));
                Files.writeString(target.resolve("src/main/java/A.java"), "class A {}\n");
                Files.writeString(target.resolve(".git").resolve("config"),
                        "[remote \"origin\"]\n\turl = https://ghp_secret@github.com/o/r.git\n");
                return FakeExec.ok("");
            }));
        }

        @Test
        void theGitDirectoryIsNeverServed(@TempDir Path cache) {
            // THE LEAK. A clone made by the JS put the token in remote.origin.url, and this route served
            // .git/config out of the read-only tree — no escape, no traversal, just the file's own name.
            Map<String, Object> r = withGitDir(cache)
                    .readFile(body("repo", "o/r", "path", ".git/config"));
            assertEquals("path not permitted", r.get("error"));
            assertNull(r.get("content"));
            assertFalse(String.valueOf(r).contains("ghp_secret"), String.valueOf(r));
        }

        @Test
        void aDotComponentIsRefusedAtEveryDepthAndBeforeAnythingIsEvenLookedFor(@TempDir Path cache) {
            Workspace workspace = withGitDir(cache);
            for (String p : List.of(".git", ".git/config", "/.git/config", "//.git/config",
                    "src/../.git/config", ".git/../.git/config", ".git/logs/HEAD",
                    ".fsm_clone_complete", ".env", "src/main/.hidden/x", "sub/.git/config")) {
                assertEquals("path not permitted",
                        workspace.readFile(body("repo", "o/r", "path", p)).get("error"),
                        "refused whatever the caller spells: " + p);
            }
        }

        @Test
        void anOrdinarySourcePathIsStillServed(@TempDir Path cache) {
            // The containment must cost the marker view nothing: this is what every real request looks
            // like, and a rule that also refused these would show reviewers "source unavailable".
            Workspace workspace = withGitDir(cache);
            assertEquals("class A {}\n",
                    workspace.readFile(body("repo", "o/r", "path", "src/main/java/A.java")).get("content"));
            assertEquals("class A {}\n",
                    workspace.readFile(body("repo", "o/r", "path", "./src/main/java/A.java")).get("content"),
                    "a leading ./ is not a hidden component once the path is normalised");
        }

        @Test
        void aSymlinkOntoTheGitDirectoryIsRefused(@TempDir Path cache) {
            // The rule must not depend on the CALLER asking nicely, and a lexical rule does: any
            // repository this fleet clones may ship a symlink whose own name says nothing.
            Workspace workspace = new Workspace(cache, "ghp_secret", new FakeExec(c -> {
                Path target = FakeExec.clonedInto(c);
                Files.writeString(target.resolve(".git").resolve("config"),
                        "\turl = https://ghp_secret@github.com/o/r.git\n");
                Files.createSymbolicLink(target.resolve("Innocent.java"), Path.of(".git/config"));
                return FakeExec.ok("");
            }));
            Map<String, Object> r = workspace.readFile(body("repo", "o/r", "path", "Innocent.java"));
            assertEquals("path not permitted", r.get("error"));
            assertFalse(String.valueOf(r).contains("ghp_secret"), String.valueOf(r));
        }

        @Test
        void aSymlinkOutOfTheTreeIsRefused(@TempDir Path cache) throws IOException {
            Files.writeString(cache.resolve("outside.txt"), "not in the repository\n");
            Workspace workspace = new Workspace(cache, "", new FakeExec(c -> {
                Path target = FakeExec.clonedInto(c);
                Files.createSymbolicLink(target.resolve("Innocent.java"),
                        cache.resolve("outside.txt"));
                return FakeExec.ok("");
            }));
            assertEquals("path escapes repo",
                    workspace.readFile(body("repo", "o/r", "path", "Innocent.java")).get("error"));
        }

        @Test
        void aPathJavaCannotEvenSpellIsAnAnswerToo(@TempDir Path cache) {
            // FOUND BY harness/run.sh. `Path.of` refuses a NUL inside a name and `path.resolve` did not,
            // so where the JS answered {"error": "file not found: …"} this threw an InvalidPathException
            // out of the route — and the dashboard's "source unavailable" line got a Java stack trace
            // instead of the reason. No NUL can name a file, so the answer is the same one.
            // Spelled with (char) 0, not as a literal: a raw NUL in this file would be invisible.
            String unspellable = "A" + (char) 0 + ".java";
            Map<String, Object> r = withFile(cache, "A.java", "class A {}\n")
                    .readFile(body("repo", "o/r", "path", unspellable));
            assertEquals("file not found: " + unspellable, r.get("error"));
        }
    }
}
