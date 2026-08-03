package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.nodes.BuildReproduceInput;
import tech.mikhailov.fsm.orch.batch.ProveProcessor;

/**
 * THE SOURCE FETCH, WITHOUT A HOST-SPECIFIC API.
 *
 * <p>The prove already clones the repository — {@code Workspace} does it for every marker — and the
 * dashboard already reads source out of that checkout. The second read of the same file over the GitHub
 * contents API bought exactly one thing: a dependency on GitHub. This client deletes it, and with it the
 * last reason this pipeline could only analyse one host.
 *
 * <p>THE ONE FAILURE THIS CLASS EXISTS TO PREVENT, and it is the reason every case below is about an
 * ERROR rather than about a file: {@code BuildReproduceInput} reads {@code content} off whatever comes
 * back and finds none when the fetch produced nothing. An empty source is INDISTINGUISHABLE from a file
 * that was deleted from the repository — the reproducer is told the code is gone, the marker is written
 * up as stale, and the run records a finding nobody made. So a clone that failed and a repo that is not a
 * repository must THROW ({@link InfraFailure}: nothing was learned, retry), and only a file that is
 * genuinely absent from a checkout that genuinely exists may come back as a 404.
 */
class CheckoutSourceClientTest {

    /** A runner that answers one file, whatever is asked for. */
    private static SourceReader serving(String content) {
        return reader(body -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", Json.str(body, "path"));
            out.put("content", content);
            out.put("truncated", Boolean.FALSE);
            return out;
        });
    }

    private static SourceReader failing(String error) {
        return reader(body -> Map.of("error", error));
    }

    private static SourceReader reader(UnaryOperator<Map<String, Object>> answer) {
        return new SourceReader() {
            @Override
            public Object read(Map<String, Object> body) {
                return answer.apply(body);
            }

            @Override
            public String describe() {
                return "the scripted runner";
            }
        };
    }

    @Nested
    class WhatTheEngineIsHanded {

        @Test
        void aFileComesBackInTheShapeTheEngineAlreadyDecodes() throws Exception {
            String java = "package a;\nclass A { int x; }\n";
            SourceClient.Source fetched = new CheckoutSourceClient(serving(java))
                    .fetch("grp/proj", "src/main/java/a/A.java", "release/1.2", null);

            assertThat(fetched.httpStatus()).isEqualTo(200);
            // base64 under `content`, with `encoding`, because that is what BuildReproduceInput decodes
            // — and the whole point is that NO stage downstream can tell which client it came from.
            assertThat(Json.str(fetched.body(), "encoding")).isEqualTo("base64");
            assertThat(Json.str(fetched.body(), "path")).isEqualTo("src/main/java/a/A.java");

            BuildReproduceInput.Outcome built = BuildReproduceInput.buildReproduceInput(
                    new BuildReproduceInput.Request(
                            Map.of("file", "src/main/java/a/A.java", "svace_line", 2), fetched.body()));
            assertThat(built.src()).isEqualTo(java);
        }

        @Test
        void theRequestNamesTheRepoTheBranchAndThePathTheProveIsAbout() throws Exception {
            Map<String, Object> asked = new LinkedHashMap<>();
            SourceClient.Source fetched = new CheckoutSourceClient(reader(body -> {
                asked.putAll(body);
                return Map.of("path", "x", "content", "class A {}\n", "truncated", Boolean.FALSE);
            })).fetch("https://gitlab.company/grp/proj.git", "src/main/java/A.java", "develop", null);

            assertThat(fetched.httpStatus()).isEqualTo(200);
            assertThat(asked).containsEntry("repo", "https://gitlab.company/grp/proj.git");
            // THE REF PrepProver RESOLVED, never a default. A window read off `main` while the prove ran
            // on `develop` is source that compiles and is not the code Svace flagged.
            assertThat(asked).containsEntry("branch", "develop");
            assertThat(asked).containsEntry("path", "src/main/java/A.java");
        }

        @Test
        void itAsksForTheWholeFileAndNotForTheDashboardsWindow() throws Exception {
            // The reviewer's window is a screenful. Re-anchoring against a screenful of a long file puts
            // the marker's line past the end of the source, and the marker is written up as drift.
            Map<String, Object> asked = new LinkedHashMap<>();
            new CheckoutSourceClient(reader(body -> {
                asked.putAll(body);
                return Map.of("path", "x", "content", "", "truncated", Boolean.FALSE);
            })).fetch("o/r", "A.java", "main", null);

            assertThat(asked).containsEntry("max_content", CheckoutSourceClient.MAX_CONTENT);
            assertThat(CheckoutSourceClient.MAX_CONTENT)
                    .as("the same ceiling the contents API itself stops inlining at, so the two source "
                            + "paths cannot disagree about which files are too big")
                    .isEqualTo(1_000_000);
        }
    }

    @Nested
    class TheDifferenceBetweenAMissingFileAndAMissingAnswer {

        @Test
        void aFileThatIsNotInTheCheckoutIsAFactAboutTheMarker() throws Exception {
            SourceClient.Source fetched = new CheckoutSourceClient(
                    failing("file not found: src/main/java/Gone.java"))
                    .fetch("o/r", "src/main/java/Gone.java", "main", null);

            // 404 is what the contents API answered for the same situation, and RecordOutcome already
            // knows what to do with it. The status is the finding; the body carries the reason.
            assertThat(fetched.httpStatus()).isEqualTo(404);
            assertThat(Json.str(fetched.body(), "message")).contains("Gone.java");
        }

        @Test
        void aCloneThatFailedIsNotAFileThatWasDeleted() {
            // The whole class in one assertion. A clone failure returned as a 404 would be recorded as
            // "the marker's file has moved or gone" — a fabricated finding about somebody else's
            // repository, produced by an outage.
            assertThatThrownBy(() -> new CheckoutSourceClient(failing("clone failed"))
                    .fetch("o/r", "A.java", "main", null))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("clone failed");
        }

        @Test
        void aRepoThatIsNotARepositoryIsNotAFileThatWasDeleted() {
            assertThatThrownBy(() -> new CheckoutSourceClient(
                    failing("`repo` starts with '-', which git clone reads as an OPTION"))
                    .fetch("-x", "A.java", "main", null))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("OPTION");
        }

        @Test
        void aRefusedPathIsNotAFileThatWasDeleted() {
            // "path escapes repo" and "path not permitted" are refusals of the REQUEST. Reporting them
            // as a missing file would blame the repository for this pipeline's own guard.
            for (String refusal : new String[] {"path escapes repo", "path not permitted"}) {
                assertThatThrownBy(() -> new CheckoutSourceClient(failing(refusal))
                        .fetch("o/r", "A.java", "main", null))
                        .as(refusal)
                        .isInstanceOf(InfraFailure.class);
            }
        }

        @Test
        void aRunnerThatCouldNotBeReachedIsInfraAndCarriesTheAddress() {
            SourceReader dead = new SourceReader() {
                @Override
                public Object read(Map<String, Object> body) throws Exception {
                    throw new java.net.ConnectException("Connection refused");
                }

                @Override
                public String describe() {
                    return "http://fsm-runner:8090/fs/read_file";
                }
            };
            assertThatThrownBy(() -> new CheckoutSourceClient(dead).fetch("o/r", "A.java", "main", null))
                    .isInstanceOf(InfraFailure.class)
                    // The address, because the two failures that actually happen here are "the runner
                    // container is not up" and "the URL points somewhere else", and neither is legible
                    // from "Connection refused" alone.
                    .hasMessageContaining("http://fsm-runner:8090/fs/read_file")
                    .hasMessageContaining("Connection refused");
        }

        @Test
        void anInterruptIsReportedAndTheFlagIsPutBack() {
            SourceReader interrupted = new SourceReader() {
                @Override
                public Object read(Map<String, Object> body) throws Exception {
                    throw new InterruptedException("shutting down");
                }

                @Override
                public String describe() {
                    return "in-process";
                }
            };
            assertThatThrownBy(() -> new CheckoutSourceClient(interrupted)
                    .fetch("o/r", "A.java", "main", null))
                    .isInstanceOf(InfraFailure.class);
            assertThat(Thread.interrupted())
                    .as("a swallowed interrupt leaves a shutting-down container with a thread that "
                            + "never notices")
                    .isTrue();
        }

        @Test
        void everyReasonLeadsWithTheSameWordsTheColumnIsGreppedBy() {
            assertThat(CheckoutSourceClient.REASON).isEqualTo("source fetch: ");
        }
    }

    @Nested
    class WhatItDoesNotDo {

        @Test
        void theTokenIsNotItsToPassOn() throws Exception {
            // The clone credential belongs to the RUNNER, which hands it to git through a one-shot
            // credential helper. A token travelling into this call would be a second copy of the secret
            // in a process that has no use for it — and the argument exists only because SourceClient's
            // other implementation talks to an API that needs one.
            Map<String, Object> asked = new LinkedHashMap<>();
            new CheckoutSourceClient(reader(body -> {
                asked.putAll(body);
                return Map.of("path", "x", "content", "", "truncated", Boolean.FALSE);
            })).fetch("o/r", "A.java", "main", "ghp_secret");

            assertThat(asked.toString()).doesNotContain("ghp_secret");
        }

        @Test
        void itIsWhatTheProveChainAsksForByName() {
            // A compile-time statement that this really is a SourceClient and can be handed to the
            // chain. The class it plugs into is the one the whole prove is built around.
            assertThat(SourceClient.class)
                    .isAssignableFrom(CheckoutSourceClient.class);
            assertThat(ProveProcessor.class.getConstructors()).isNotEmpty();
        }
    }

    /** UTF-8 in, UTF-8 out — a source file is not ASCII on any real codebase. */
    @Test
    void nonAsciiSourceSurvivesTheBase64RoundTrip() throws Exception {
        String source = "class A { String s = \"café — шифр\"; }\n";
        SourceClient.Source fetched = new CheckoutSourceClient(serving(source))
                .fetch("o/r", "A.java", "main", null);

        byte[] decoded = Base64.getDecoder().decode(Json.str(fetched.body(), "content"));
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo(source);
    }
}
