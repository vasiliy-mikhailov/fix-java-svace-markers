package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * WHAT {@code repo} IS ALLOWED TO BE, now that it is not always a GitHub slug.
 *
 * <p>This class used to be one line — {@code "https://github.com/" + repo + ".git"} — and that line was
 * the whole reason the pipeline could analyse GitHub and nothing else. Every case below is either a
 * shape a Guild with its own GitLab will type, or a string that must NEVER reach {@code git clone}.
 *
 * <p>THE REFUSALS MATTER MORE THAN THE ACCEPTANCES, and each of them is a specific way a repo string
 * turns into something other than a clone:
 * <ul>
 *   <li>A leading {@code -} is an OPTION, not a URL. {@code git clone --upload-pack=…} runs a command
 *       of the caller's choosing on this container.</li>
 *   <li>{@code ext::sh -c …} is a git TRANSPORT that executes its argument. It is a URL by git's rules
 *       and a remote shell by anybody else's.</li>
 *   <li>{@code file:///…} clones out of this container's own filesystem, which is where the H2 store,
 *       the prompts and the other repositories' checkouts live.</li>
 *   <li>A password in the userinfo — {@code https://u:p@host/x.git} — is written VERBATIM into the new
 *       checkout's {@code .git/config}, which is inside the tree {@code /fs/read_file} serves. That is
 *       the exact leak {@link Workspace#CREDENTIAL_HELPER} exists to prevent, and accepting such a URL
 *       would reintroduce it through the front door.</li>
 * </ul>
 */
class CloneUrlTest {

    @Nested
    class TheShapesAGuildTypes {

        @Test
        void ownerNameStillMeansTheDefaultHostAndIsStillGithubOutOfTheBox() {
            // The behaviour every existing runbook, the bundled example and the deployed stack depend
            // on. It is a DEFAULT now rather than a constant, but the default must not move.
            assertEquals("https://github.com/WebGoat/WebGoat.git",
                    CloneUrl.of("WebGoat/WebGoat", CloneUrl.DEFAULT_HOST).url());
            assertEquals("github.com", CloneUrl.DEFAULT_HOST);
        }

        @Test
        void theDefaultHostMovesSoAGuildCanKeepTypingSlugs() {
            // FSM_GIT_HOST=gitlab.company.internal, and `grp/proj` means what it always meant — a
            // repository on the host this deployment analyses.
            assertEquals("https://gitlab.company.internal/grp/proj.git",
                    CloneUrl.of("grp/proj", "gitlab.company.internal").url());
        }

        @Test
        void aNestedGroupPathIsARepositoryAndNotAMalformedSlug() {
            // GitLab subgroups are the shape that a strict owner/name check refuses outright. There is
            // no upper bound on the nesting, so there is no segment count to encode.
            assertEquals("https://gitlab.company.internal/grp/sub/deeper/proj.git",
                    CloneUrl.of("grp/sub/deeper/proj", "gitlab.company.internal").url());
        }

        @Test
        void aFullHttpsUrlIsTakenVerbatim() {
            assertEquals("https://gitlab.company/grp/proj.git",
                    CloneUrl.of("https://gitlab.company/grp/proj.git", CloneUrl.DEFAULT_HOST).url());
            // …with or without the suffix. Appending .git to a URL somebody wrote out in full would be
            // this code second-guessing a path that server may well serve as given.
            assertEquals("https://gitlab.company/grp/proj",
                    CloneUrl.of("https://gitlab.company/grp/proj", CloneUrl.DEFAULT_HOST).url());
        }

        @Test
        void anSshUrlIsTakenVerbatimIncludingItsUsername() {
            // ssh://git@host/path is the shape an internal host with no HTTPS front end serves, and the
            // username in it is not a credential — the key is in the container's ssh config.
            assertEquals("ssh://git@gitlab.company/grp/proj.git",
                    CloneUrl.of("ssh://git@gitlab.company/grp/proj.git", CloneUrl.DEFAULT_HOST).url());
        }

        @Test
        void theScpShorthandIsAcceptedBecauseItIsWhatGitLabPrintsOnItsCloneButton() {
            assertEquals("git@gitlab.company:grp/proj.git",
                    CloneUrl.of("git@gitlab.company:grp/proj.git", CloneUrl.DEFAULT_HOST).url());
        }

        @Test
        void aBareHostAndPathIsAUrlWithTheSchemeLeftOff() {
            // The first segment carries a dot, so it is a host and not an owner. Nothing else can tell
            // `gitlab.company/grp/proj` from `owner/name`, and reading it as an owner would send the
            // clone to github.com/gitlab.company/grp/proj — a well-formed URL and a 404.
            assertEquals("https://gitlab.company/grp/proj.git",
                    CloneUrl.of("gitlab.company/grp/proj", CloneUrl.DEFAULT_HOST).url());
            // …and a port is a host too.
            assertEquals("https://gitea:3000/grp/proj.git",
                    CloneUrl.of("gitea:3000/grp/proj", CloneUrl.DEFAULT_HOST).url());
        }

        @Test
        void surroundingWhitespaceIsTrimmedBecauseTheValueCameOutOfACsvCell() {
            assertEquals("https://github.com/o/r.git",
                    CloneUrl.of("  o/r\n", CloneUrl.DEFAULT_HOST).url());
        }
    }

    @Nested
    class TheStringsThatMustNotReachGitClone {

        @Test
        void anEmptyRepoIsRefusedRatherThanClonedFromTheDefaultHostsRoot() {
            assertRefused(CloneUrl.of("", CloneUrl.DEFAULT_HOST), "empty");
            assertRefused(CloneUrl.of(null, CloneUrl.DEFAULT_HOST), "empty");
            assertRefused(CloneUrl.of("   ", CloneUrl.DEFAULT_HOST), "empty");
        }

        @Test
        void aBareNameWithNoPathIsRefusedInsteadOfCloningTheHostItself() {
            assertRefused(CloneUrl.of("WebGoat", CloneUrl.DEFAULT_HOST), "WebGoat");
        }

        @Test
        void aLeadingDashIsAnOptionAndIsRefused() {
            // `git clone --upload-pack=touch\ /tmp/pwned x` runs that command. The argument list is
            // built here, so this is the only place it can be stopped.
            assertRefused(CloneUrl.of("--upload-pack=/bin/sh", CloneUrl.DEFAULT_HOST), "option");
            assertRefused(CloneUrl.of("-o/r", CloneUrl.DEFAULT_HOST), "option");
        }

        @Test
        void aGitTransportHelperIsRefusedBecauseItIsARemoteShell() {
            // Spelled with no spaces in it deliberately: with a space the whitespace rule above fires
            // first and this assertion would pass while the transport check did nothing at all — the
            // value itself contains the letters "ext", so even the message would look right.
            assertRefused(CloneUrl.of("ext::whoami", CloneUrl.DEFAULT_HOST), "transport helper");
            assertRefused(CloneUrl.of("ext::sh", CloneUrl.DEFAULT_HOST), "transport helper");
        }

        @Test
        void aLocalFileUrlIsRefusedBecauseThisContainerIsNotARepositoryHost() {
            assertRefused(CloneUrl.of("file:///state", CloneUrl.DEFAULT_HOST), "file");
            assertRefused(CloneUrl.of("/cache/fs/abcdef", CloneUrl.DEFAULT_HOST), "absolute");
        }

        @Test
        void aPasswordInTheUrlIsRefusedBecauseGitWritesItIntoTheCheckout() {
            // The property this keeps: a clone URL is stored verbatim as remote.origin.url, and
            // .git/config sits inside the tree the source window serves. See Workspace.CREDENTIAL_HELPER
            // — the token travels in a one-shot helper precisely so that it is never in a URL.
            assertRefused(CloneUrl.of("https://user:ghp_secret@gitlab.company/g/p.git",
                    CloneUrl.DEFAULT_HOST), "credential");
            assertFalse(CloneUrl.of("https://user:ghp_secret@gitlab.company/g/p.git",
                            CloneUrl.DEFAULT_HOST).error().contains("ghp_secret"),
                    "the refusal is read off a dashboard and pasted into tickets; it must not quote "
                            + "the secret it refused");
        }

        @Test
        void anUnknownSchemeIsRefusedRatherThanHandedToGitToInterpret() {
            assertRefused(CloneUrl.of("javascript://x/y", CloneUrl.DEFAULT_HOST), "scheme");
        }

        @Test
        void whitespaceInsideARepoIsRefusedBecauseItIsNotOneArgument() {
            assertRefused(CloneUrl.of("o/r --upload-pack=x", CloneUrl.DEFAULT_HOST), "whitespace");
            assertRefused(CloneUrl.of("o/r\nx/y", CloneUrl.DEFAULT_HOST), "whitespace");
        }

        @Test
        void aDotDotSegmentIsRefusedRatherThanNormalisedIntoADifferentRepository() {
            assertRefused(CloneUrl.of("grp/../../other/proj", CloneUrl.DEFAULT_HOST), "..");
        }

        @Test
        void everyRefusalNamesTheValueAndTheShapesThatWouldHaveWorked() {
            String error = CloneUrl.of("WebGoat", CloneUrl.DEFAULT_HOST).error();
            // A refusal a reviewer reads off a stuck marker. Without the accepted shapes in it, the
            // only way to learn what to type is to read this file.
            assertTrue(error.contains("owner/name"), error);
            assertTrue(error.contains("https://"), error);
        }
    }

    @Nested
    class TheGithubIsm {

        @Test
        void ownerNameIsRecognisedSoTheContentsApiCanRefuseWhatItCannotServe() {
            assertTrue(CloneUrl.isOwnerName("WebGoat/WebGoat"));
            assertTrue(CloneUrl.isOwnerName(" WebGoat/WebGoat "));
            assertFalse(CloneUrl.isOwnerName("https://gitlab.company/g/p.git"));
            assertFalse(CloneUrl.isOwnerName("grp/sub/proj"));
            assertFalse(CloneUrl.isOwnerName("gitlab.company/g/p"));
            assertFalse(CloneUrl.isOwnerName("WebGoat"));
            assertFalse(CloneUrl.isOwnerName(""));
            assertFalse(CloneUrl.isOwnerName(null));
        }
    }

    private static void assertRefused(CloneUrl.Resolved resolved, String mentions) {
        assertFalse(resolved.ok(), "accepted: " + resolved.url());
        assertNull(resolved.url(), "a refused repo must carry no URL at all");
        assertTrue(resolved.error().toLowerCase(java.util.Locale.ROOT)
                        .contains(mentions.toLowerCase(java.util.Locale.ROOT)),
                "the refusal does not mention `" + mentions + "`: " + resolved.error());
    }
}
