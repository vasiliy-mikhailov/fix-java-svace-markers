package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.orch.client.HttpRunnerClient;

/**
 * THE DEPLOYMENT IS PART OF THE PROGRAM, and the two failures pinned here are both invisible.
 *
 * <p>FIRST: {@code FSM_DB_PATH}. {@code application.yml} defaults the H2 file to {@code ./data/fsm},
 * relative to the working directory — which inside a container is a writable layer thrown away with the
 * container. That default is correct for {@code mvn spring-boot:run} and is a data-loss bug in compose:
 * the service starts, serves, accepts an ingest, runs for hours, and loses 282 markers with their
 * evidence and their drafted PR bodies on the next {@code docker compose up -d}. Nothing is red at any
 * point; the dashboard simply reads zero afterwards. So the compose service must set it, and set it
 * under a NAMED VOLUME — a bind mount into the repository would work and would also put a live database
 * into a git worktree.
 *
 * <p>SECOND: THE NETWORKS. This is the same defect the engine shipped, and what it cost is recorded
 * below: three stages that call the
 * model moved into a container that was not on {@code proxy-net}, the name did not resolve, and because
 * those stages fail CLOSED the run history stayed green while every marker settled as
 * {@code needs_review} with {@code skeptic_verdict 'unknown'}. The orchestrator now makes ALL of those
 * calls — the two agents and the three judging stages — so it inherits the requirement wholesale, and
 * it needs the default network as well because {@code /run_test} goes to {@code fsm-runner}.
 *
 * <p>THIRD, AND NEW: WHERE THE PROVE IS POSTED. Node and n8n were removed from the deployment —
 * {@code n8n}, {@code java-runner} and {@code dashboard} are deleted and the live stack is
 * {@code engine}, {@code orchestrator}, {@code runner} — and deleting a service does not break the file
 * that points at it. {@code FSM_RUNNER_URL} kept defaulting to {@code http://fsm-java-runner:8090}
 * after that container was gone, and compose starts such a stack without complaint. The failure arrives
 * hours later on the first marker, as a connect retried three times and then recorded as an
 * infrastructure failure — which reads as a runner that is down, not as a name nothing serves. So the
 * URL is checked against the services this very file declares.
 *
 * <p>NOT AN ALLOWLIST. Nothing here says which hosts are legitimate; that
 * judgement is what took production down once already. It says only that the container the calls were
 * moved INTO has at least the routes of the containers they were moved out of, and that the names it
 * is configured with exist.
 *
 * <p>THIS IS THE ONLY PLACE THE COMPOSE FILE IS CHECKED. {@code n8n/agentic/test/compose.test.js} used
 * to assert the same class of property from the Node side and was deleted with the n8n generator tree it
 * lived in. Everything it asserted was moved here first, assertion for assertion — the engine's
 * {@code proxy-net} route and its superset relationship with the orchestrator's networks, the runner's
 * {@code mvn-cache} route, the runner's {@code /cache} named volume with its four conditions, and the
 * two-way closure between declared and mounted volumes. A guard is not something to lose to a tidy-up:
 * the whole reason these live in a test rather than a comment is that every failure they catch produces
 * a GREEN run that decided nothing.
 */
class DeploymentTest {

    /** The service, as it must be named for the fleet's {@code container_name} convention to hold. */
    private static final String ORCHESTRATOR = "orchestrator";

    /** The live stack, in full. Anything else under {@code services:} is something meant to be gone. */
    private static final List<String> LIVE = List.of("engine", ORCHESTRATOR, "runner");

    /** Deleted with the Node/n8n half of the fleet; named so their return is a failure, not a surprise. */
    private static final List<String> DELETED = List.of("n8n", "java-runner", "dashboard");

    private static final Path ROOT = fleetRoot();
    private static final Map<String, Service> COMPOSE = ROOT == null
            ? Map.of()
            : services(read(ROOT.resolve("n8n").resolve("docker-compose.yml")));

    /**
     * These checks read files OUTSIDE this module — n8n/docker-compose.yml above all — so they can only
     * run against a full worktree. The image build copies just orchestrator/, engine/ and pom.xml into
     * /src, on purpose: the running container has no business carrying the n8n stack. Inside that build
     * the fleet root does not exist, and asserting would fail the image over a file that was correctly
     * left out.
     *
     * So SKIP there, and skip LOUDLY — JUnit reports these as skipped with the reason below, never as
     * passed. A deployment check that quietly turned into a no-op is precisely the failure mode this
     * class exists to catch, so it must not become one itself. In a worktree (a developer's machine,
     * CI, `mvn test` at the reactor root) ROOT resolves and every assertion runs.
     */
    @org.junit.jupiter.api.BeforeEach
    void onlyMeaningfulInAWorktree() {
        org.junit.jupiter.api.Assumptions.assumeTrue(ROOT != null,
                "no fleet root above " + Path.of("").toAbsolutePath() + " — these checks read "
                + "n8n/docker-compose.yml, which the image build deliberately does not copy. Run "
                + "`mvn test` from the reactor root to exercise them.");
    }

    @Test
    void theComposeFileIsParsedAsTheseChecksExpect() {
        // Guard against every assertion below passing because the parser found nothing. If the file is
        // restructured, this fails loudly rather than the checks turning into no-ops.
        assertThat(COMPOSE).containsKeys(LIVE.toArray(String[]::new));
        assertThat(COMPOSE.get(ORCHESTRATOR).networks)
                .as("the orchestrator should list several networks; a parser that found one is a "
                        + "parser that is about to make the reachability checks vacuous")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(COMPOSE.get(ORCHESTRATOR).environment).isNotEmpty();
        // …and the same guard for the mounts and the top-level volume declarations, so the volume
        // checks below cannot pass by finding nothing either.
        assertThat(COMPOSE.get("runner").volumes)
                .as("runner should mount its cache")
                .isNotEmpty();
        assertThat(topLevelVolumes(read(ROOT.resolve("n8n").resolve("docker-compose.yml"))))
                .as("no top-level volumes parsed out of docker-compose.yml")
                .isNotEmpty();
    }

    /**
     * The deployment is three services. Node and n8n were removed from it — the containers are gone
     * from the host — and this is what keeps the file honest about that.
     *
     * <p>A resurrected {@code n8n} or {@code java-runner} is not a harmless leftover: both would prove
     * markers. {@code /run_test} is serialised inside ONE process around one workspace per repository
     * and two processes have no lock between them at all, so a second prover {@code reset --hard}s and
     * patches the tree the first one is building in. That surfaces as a Maven build that
     * inexplicably compiled somebody else's patch, which is not a thing anyone debugs quickly.
     */
    @Test
    void theDeploymentIsExactlyTheThreeLiveServices() {
        assertThat(COMPOSE.keySet())
                .as("docker-compose.yml declares services beyond engine/orchestrator/runner")
                .containsExactlyInAnyOrderElementsOf(LIVE);
        assertThat(COMPOSE.keySet())
                .as("Node and n8n were removed from the deployment; a second prover in the stack "
                        + "shares no lock with the runner")
                .doesNotContainAnyElementsOf(DELETED);
    }

    @Test
    void theOrchestratorIsDeployedUnderTheFleetsOwnNaming() {
        Service orchestrator = COMPOSE.get(ORCHESTRATOR);

        // The project is named `fsm`, so every container is fsm-*; without container_name compose would
        // call this one fsm-orchestrator-1 and every URL, every log grep and every `docker exec` in the
        // README would be wrong.
        assertThat(orchestrator.scalar("container_name")).contains("fsm-orchestrator");
        assertThat(read(ROOT.resolve("n8n").resolve("docker-compose.yml")))
                .as("the project name is what keeps this stack's volumes and network out of the other "
                        + "pipeline's, which shares the directory basename `n8n`")
                .contains("\nname: fsm\n");
    }

    @Test
    void theDatabasePathIsSetAndPointsIntoANamedVolume() {
        Service orchestrator = COMPOSE.get(ORCHESTRATOR);

        String dbPath = orchestrator.environmentValue("FSM_DB_PATH").orElse(null);
        assertThat(dbPath)
                .as("unset, the H2 file lands on the container's writable layer and a deploy silently "
                        + "discards every settled verdict — see application.yml, which says so itself")
                .isNotNull()
                .startsWith("/");

        // The file is FSM_DB_PATH + ".mv.db", so what has to be mounted is the directory holding it.
        String directory = dbPath.substring(0, dbPath.lastIndexOf('/'));
        String volume = orchestrator.volumes.stream()
                .filter(mount -> mountedAt(mount, directory))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "nothing is mounted at " + directory + ", which is where FSM_DB_PATH points: "
                        + orchestrator.volumes));

        String source = volume.substring(0, volume.indexOf(':'));
        assertThat(source)
                .as("a bind mount would put a live H2 store holding run data inside a git worktree")
                .doesNotStartWith(".").doesNotStartWith("/").doesNotStartWith("~");
        assertThat(topLevelVolumes(read(ROOT.resolve("n8n").resolve("docker-compose.yml"))))
                .as("a named volume that is not declared makes `docker compose up` refuse to start")
                .contains(source);
        // Read-only would make the whole service pointless, and `:ro` on this mount is an easy paste.
        assertThat(volume).doesNotEndWith(":ro");
    }

    @Test
    void theOrchestratorCanResolveEveryNameItsOutboundCallsNeed() {
        Service orchestrator = COMPOSE.get(ORCHESTRATOR);

        // The runner, on the default network: /run_test is a clone plus two Maven builds and it is
        // the one call without which nothing is proved at all.
        assertThat(orchestrator.networks)
                .as("POST http://fsm-runner:8090/run_test leaves from here")
                .contains("default");

        // …and proxy-net BY NAME. fsm-n8n used to be the reference this was compared against — "every
        // network n8n is on" — and n8n is gone, so comparing against it would now assert nothing at
        // all. The requirement did not go anywhere: the two agent calls and the three judging calls
        // leave from THIS process, `inference-vllm` is a compose name on that bridge, and the judging
        // ones fail CLOSED. See the class comment for what the last omission cost.
        assertThat(orchestrator.networks)
                .as("fsm-orchestrator is not on proxy-net, where the model lives. The judging calls "
                        + "fail CLOSED, so a name that does not resolve here does not produce an "
                        + "error — it settles every marker as needs_review with skeptic_verdict "
                        + "'unknown', a green run history and nothing red anywhere")
                .contains("proxy-net");

        // The runner is reached over the compose network by name, so it has to be on the same one.
        assertThat(COMPOSE.get("runner").networks)
                .as("fsm-runner publishes no port; the default network is the only way in")
                .contains("default");
    }

    /**
     * THE ENGINE'S HALF OF THE SAME REQUIREMENT — ported from {@code compose.test.js}, which is the file
     * that held it and is now deleted.
     *
     * <p>This is the container the defect in the class comment actually happened in. {@code fsm-engine}
     * serves the three judging stages over HTTP, and every one of them calls the model; off
     * {@code proxy-net} the name {@code inference-vllm} does not resolve, {@code Llm.text} catches it and
     * fails CLOSED, and the service answers 200 with {@code skeptic_verdict 'unknown'}. The orchestrator
     * embedding the same code as a library does not make the HTTP path stop mattering — it is how a
     * single stage is reproduced against a request written by hand, which is the only way to debug one
     * stage without a 6-26 hour run around it, and a stage that silently judges nothing there is a
     * debugging tool that lies.
     *
     * <p>NOT AN ALLOWLIST. The second assertion names no host: it says the engine has at least the routes
     * of the orchestrator, which runs the same judgement code and makes the same outbound calls. That
     * comparison used to be anchored to {@code fsm-n8n}; with that service deleted it is anchored here,
     * because a comparison against a container that no longer exists passes by finding nothing.
     */
    @Test
    void theEngineCanResolveEveryNameTheJudgementItRunsHasToReach() {
        Service engine = COMPOSE.get("engine");

        assertThat(engine.networks)
                .as("fsm-engine is not on proxy-net. The model lives there. Off it, Fix skeptic, PR "
                        + "maker and Verdict reach a name that does not resolve, fail closed, answer "
                        + "200 with skeptic_verdict=unknown, and every marker settles as needs_review "
                        + "with a green run history and nothing red anywhere")
                .contains("proxy-net");

        assertThat(engine.networks)
                .as("fsm-engine is missing a network fsm-orchestrator is on, and the two run the SAME "
                        + "judgement code — one over HTTP and one as a library. A name that resolves in "
                        + "one and not the other does not fail the run: the judging stages fail closed, "
                        + "so the marker settles with a downgraded verdict and nothing is red")
                .containsAll(COMPOSE.get(ORCHESTRATOR).networks);
    }

    /**
     * THE SAME CLASS OF DEFECT, ONE SERVICE LATER — ported from {@code compose.test.js}. What
     * {@code runner} inherited from the Node container it replaced is not only five routes but a network
     * requirement, because {@code /run_test} does not compute an answer: it shells out to Maven.
     *
     * <p>{@code runner/settings.xml} sets {@code mirrorOf=*}, so the Nexus on {@code mvn-cache} is not a
     * cache in front of Central — it is the ONLY repository Maven will talk to. That makes this line a
     * functional dependency of every prove, and ITS FAILURE IS DISGUISED: the container starts, answers
     * /health with all five JDKs, clones, and then every RED build dies in hundreds of lines of "Could
     * not resolve dependencies … Connection refused", which reads as a broken target repository or a
     * Nexus outage rather than as a missing word in a compose file. Every marker in the backlog comes
     * back {@code ok: false}, is recorded as an infrastructure failure and is retried forever against
     * repositories that are perfectly fine.
     *
     * <p>NOT AN ALLOWLIST, on the same principle: it names {@code mvn-cache} because that is the route
     * whose absence is silent rather than loud, and says nothing about which hosts are legitimate.
     */
    @Test
    void theRunnerIsOnTheNetworkItsMavenMirrorLivesOn() {
        assertThat(COMPOSE.get("runner").networks)
                .as("fsm-runner is not on mvn-cache. runner/settings.xml mirrors * to the Nexus that "
                        + "lives on that network, so off it EVERY Maven build of EVERY target repository "
                        + "fails to resolve anything. The runner still starts and still answers /health, "
                        + "so the symptom is hundreds of lines of Maven errors per marker — which looks "
                        + "like a broken repository, not like broken wiring")
                .contains("mvn-cache");
    }

    /**
     * THE RUNNER'S WORKSPACE — ported from {@code compose.test.js}; see the {@code runner} service in
     * docker-compose.yml for the full argument.
     *
     * <p>It has to be a NAMED VOLUME. Unmounted, {@code /cache} is the container's writable layer and
     * every {@code docker compose up -d} throws away every checkout and every {@code target/} behind it —
     * minutes per repository, silently, on a run that is 282 markers long. A bind mount would put a
     * couple of dozen cloned third-party repositories inside a git worktree instead.
     *
     * <p>And it must not be the RETIRED {@code fsm-java-runner-cache}. That volume outlives the deleted
     * java-runner service until an operator removes it, and every clone the JS made carries
     * {@code https://<token>@github.com/…} verbatim in its {@code .git/config} as
     * {@code remote.origin.url}. The Java port never writes that (Workspace's credential helper) but it
     * ADOPTS an existing checkout rather than re-cloning it, and adoption never rewrites
     * {@code remote.origin.url} — so pointing at the old volume would carry the credential forward
     * indefinitely, surviving a rotation of {@code GITHUB_TOKEN} in {@code .env}.
     */
    @Test
    void theRunnerKeepsItsCheckoutsInItsOwnNamedVolume() {
        Service runner = COMPOSE.get("runner");

        String cache = runner.volumes.stream()
                .filter(mount -> mountedAt(mount, "/cache"))
                .map(DeploymentTest::mountSource)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "nothing is mounted at /cache on fsm-runner: " + runner.volumes + ". Unmounted, "
                        + "/cache is the container's writable layer, so every `docker compose up -d` "
                        + "discards every checkout and every target/ behind it."));

        assertThat(isBindMount(cache))
                .as("a bind mount would put a couple of dozen cloned repositories inside a git "
                        + "worktree: %s", cache)
                .isFalse();
        assertThat(topLevelVolumes(read(ROOT.resolve("n8n").resolve("docker-compose.yml"))))
                .as("a named volume that is not declared makes `docker compose up` refuse to start")
                .contains(cache);
        assertThat(cache)
                .as("fsm-runner is pointed at the retired java-runner cache. Those checkouts carry a "
                        + "tokenized remote.origin.url that the Java port never writes and never "
                        + "rewrites, and adopting them carries the credential forward past a rotation "
                        + "of GITHUB_TOKEN")
                .doesNotContain("java-runner");
    }

    /**
     * NO ORPHANED VOLUMES, IN EITHER DIRECTION — ported from {@code compose.test.js}.
     *
     * <p>{@code fsm-java-runner-cache} was declared here for a service that no longer exists, and a
     * declaration nothing mounts is not inert: it is the name an operator reaches for when wiring up a
     * new service, which is exactly how the retired token-bearing cache would come back. The other
     * direction is louder but still worth pinning — a service that mounts a named volume no top-level
     * {@code volumes:} entry declares makes {@code docker compose up} refuse to start the whole stack.
     */
    @Test
    void everyDeclaredVolumeIsMountedAndEveryNamedMountIsDeclared() {
        List<String> declared = topLevelVolumes(read(ROOT.resolve("n8n").resolve("docker-compose.yml")));
        List<String> mounted = COMPOSE.values().stream()
                .flatMap(service -> service.volumes.stream())
                .map(DeploymentTest::mountSource)
                .filter(source -> !isBindMount(source))
                .distinct()
                .toList();

        assertThat(mounted)
                .as("a top-level volume is mounted by nothing. It belonged to a service that was "
                        + "deleted; leaving the declaration is how a retired cache gets adopted by the "
                        + "next service someone wires up")
                .containsAll(declared);
        assertThat(declared)
                .as("a service mounts a named volume that no top-level `volumes:` entry declares — "
                        + "`docker compose up` refuses to start")
                .containsAll(mounted);
    }

    /**
     * WHERE THE PROVE IS POSTED, checked against the services that actually exist.
     *
     * <p>Deleting a service does not break the file that points at it. {@code FSM_RUNNER_URL} kept its
     * default of {@code http://fsm-java-runner:8090} after that container was deleted, and compose is
     * perfectly happy to start a stack whose environment names a host nothing serves — there is no
     * error on the way up and nothing is red. The failure is the first marker of the run, six hours
     * later: a connect retried three times and then recorded as an infrastructure failure, which reads
     * as a runner that is down rather than as a name that no longer exists.
     *
     * <p>It also has to be LISTED on the service. A value set only in {@code .env} reaches compose's
     * variable interpolation and never the container, so repointing the runner that way prints
     * "Running" — i.e. no change — while every prove keeps going to the old service.
     */
    @Test
    void theRunnerUrlNamesAServiceInThisFile() {
        String configured = COMPOSE.get(ORCHESTRATOR).environmentValue("FSM_RUNNER_URL").orElse(null);
        assertThat(configured)
                .as("FSM_RUNNER_URL has to be listed on the service to reach the container at all")
                .isNotNull();

        String host = URI.create(composeDefault(configured)).getHost();
        List<String> containers = COMPOSE.values().stream()
                .map(service -> service.scalar("container_name").orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        assertThat(containers)
                .as("FSM_RUNNER_URL defaults to %s, whose host `%s` is not a container in this file. "
                        + "An operator who sets nothing in .env gets exactly that string, and the "
                        + "stack starts clean and proves nothing", composeDefault(configured), host)
                .contains(host);
    }

    /**
     * ONE RUNNER, DECLARED THREE TIMES, and all three have to say the same thing.
     *
     * <p>The runner's address is written in {@code docker-compose.yml} (the {@code FSM_RUNNER_URL}
     * passthrough), in {@code application.yml} (the placeholder default behind it) and in
     * {@link HttpRunnerClient#DEFAULT_BASE_URL} (the compiled-in default that binding falls back to).
     * Each is a fallback for the layer above it, so a deployment can pick up a stale one from anywhere
     * in that chain — and NONE of them is exercised by anything until a marker is proved. That is why
     * the java-runner default survived the container it named: the wiring is only ever read six hours
     * into a run, on the one call that matters, and the symptom there is a failed connect that reads as
     * a service being down.
     *
     * <p>Pinning them to each other rather than to a literal keeps this from becoming a fourth place
     * the name is written. {@link #theRunnerUrlNamesAServiceInThisFile} is what ties the chain to a
     * container that exists.
     */
    @Test
    void theRunnersAddressSaysTheSameThingInAllThreePlaces() throws IOException {
        String fromCompose = composeDefault(
                COMPOSE.get(ORCHESTRATOR).environmentValue("FSM_RUNNER_URL").orElseThrow());

        String yaml = Files.readString(
                ROOT.resolve("orchestrator/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        java.util.regex.Matcher placeholder = java.util.regex.Pattern
                .compile("\\$\\{FSM_RUNNER_URL:([^}]*)}").matcher(yaml);
        assertThat(placeholder.find())
                .as("application.yml no longer defaults fsm.runner.base-url from FSM_RUNNER_URL, so the "
                        + "environment line on the compose service reaches nothing")
                .isTrue();

        assertThat(placeholder.group(1))
                .as("application.yml and docker-compose.yml disagree about where the prove is posted. "
                        + "Neither is read until the first marker of a 6-26 hour run.")
                .isEqualTo(fromCompose);
        assertThat(HttpRunnerClient.DEFAULT_BASE_URL)
                .as("the compiled-in default disagrees with the yaml. It is the last fallback in the "
                        + "chain — reached whenever the placeholder resolves to blank — so a stale one "
                        + "here is invisible in every configuration that looks correct.")
                .isEqualTo(fromCompose);
    }

    /**
     * The default compose substitutes when the variable is unset — {@code ${FOO:-default}} to
     * {@code default}. That half is the one that matters: it is what a deployment with an empty
     * {@code .env} actually runs with.
     */
    private static String composeDefault(String value) {
        java.util.regex.Matcher interpolated = java.util.regex.Pattern
                .compile("^\\$\\{[A-Za-z_][A-Za-z0-9_]*:-(.*)}$").matcher(value);
        return interpolated.matches() ? interpolated.group(1) : value;
    }

    @Test
    void theImageIsBuiltThroughTheReactorSoTheEngineIsInIt() throws IOException {
        Service orchestrator = COMPOSE.get(ORCHESTRATOR);

        // `orchestrator` depends on `tech.mikhailov.fsm:engine` and resolves it FROM THE REACTOR, not
        // from a repository — nothing publishes that jar anywhere. A build context of ../orchestrator
        // therefore cannot build this image at all, and the failure arrives as an unresolvable
        // dependency in the middle of a Docker build that takes minutes to reach it.
        assertThat(orchestrator.scalar("build.context"))
                .as("the context has to be the reactor root, which holds both modules")
                .contains("..");
        assertThat(orchestrator.scalar("build.dockerfile")).contains("orchestrator/Dockerfile");

        Path dockerfile = ROOT.resolve("orchestrator").resolve("Dockerfile");
        assertThat(dockerfile).exists();
        String image = Files.readString(dockerfile, StandardCharsets.UTF_8);
        // Multi-stage: the runtime layer must not carry Maven or the ~/.m2 tree the build accumulated.
        assertThat(image).contains("AS build");
        assertThat(image.split("(?m)^FROM ").length - 1)
                .as("multi-stage, like engine/Dockerfile and for the same reasons")
                .isGreaterThanOrEqualTo(2);
        // The whole port targets 25; an image on 21 fails at class-file version, at run time.
        assertThat(image).contains("JDK_VERSION=25");
        // …and it has to build the reactor, or the engine's classes are simply absent.
        assertThat(image).contains("-pl orchestrator").contains("-am");
    }

    @Test
    void theRunbookExists() throws IOException {
        String readme = Files.readString(ROOT.resolve("orchestrator").resolve("README.md"),
                StandardCharsets.UTF_8);

        // The three things a Java developer who has never seen n8n needs, and the reason this file is
        // asserted on rather than merely written: each of them is a route that exists in the code and
        // is undiscoverable without being written down.
        assertThat(readme).contains("/api/prove/marker");
        assertThat(readme).contains("fsm.prove.marker");
        assertThat(readme).contains("FSM_DB_PATH");

        // The example environment names every variable and carries no value for any secret.
        List<String> example = Files.readAllLines(
                ROOT.resolve("orchestrator").resolve(".env.example"), StandardCharsets.UTF_8);
        assertThat(example).anyMatch(line -> line.startsWith("QWEN_BASE_URL="))
                .anyMatch(line -> line.startsWith("QWEN_API_KEY="))
                .anyMatch(line -> line.startsWith("GITHUB_TOKEN="));
        assertThat(example)
                .as("an example file with a real key in it is how a credential reaches git history")
                .noneMatch(line -> line.startsWith("QWEN_API_KEY=") && line.length() > "QWEN_API_KEY=".length())
                .noneMatch(line -> line.startsWith("GITHUB_TOKEN=") && line.length() > "GITHUB_TOKEN=".length());
    }

    // ---- the feedback store's writable path ---------------------------------------------------------

    @Test
    void theFeedbackStoreHasSomewhereWritableToLandAndTheRepoStaysReadOnly() {
        Service orchestrator = COMPOSE.get(ORCHESTRATOR);

        // The default the process uses when nothing overrides it, read off application.yml rather than
        // restated here: this check is about the MOUNT matching the path, and a second copy of the path
        // in this file would let the two drift and still pass.
        String configured = defaultFeedbackPath();
        String directory = configured.substring(0, configured.lastIndexOf('/'));

        String mount = orchestrator.volumes.stream()
                .filter(entry -> mountedAt(entry, directory))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "nothing is mounted at " + directory + ", which is where fsm.feedback.path "
                        + "points. The repository is bound read-only, so the store would log a warning "
                        + "per marker for 26 hours and write nothing: " + orchestrator.volumes));

        // WRITABLE, which is the entire point of adding a second mount over a read-only one.
        assertThat(mount)
                .as("a `:ro` here is a feature that works on a laptop and silently no-ops deployed")
                .doesNotEndWith(":ro");
        // A BIND FROM THE REPOSITORY, because the user wants the file at the repo root where a
        // prompt-tuning pass will look for it — not a named volume nobody can read without docker.
        assertThat(mount.substring(0, mount.indexOf(':')))
                .as("the feedback file has to land in the worktree, not inside a docker volume")
                .startsWith("../../");

        // …AND ONLY THAT DIRECTORY. This service clones and patches third-party code; the repo mount it
        // already has must still be read-only, or the blast radius of a bad Maven run is the worktree.
        assertThat(orchestrator.volumes)
                .as("the repository itself must stay read-only")
                .contains("../../:/data:ro");
        assertThat(mount.substring(0, mount.indexOf(':')))
                .as("this is the whole repo made writable under another name")
                .isNotEqualTo("../../");
    }

    @Test
    void theFeedbackFileCannotBeCommittedByAccident() throws IOException {
        // It holds full prompts, full model replies and verbatim third-party source. The same reasoning
        // that keeps the H2 store out of git applies, and a rule nobody wrote is a file somebody pushes.
        List<String> ignored = Files.readAllLines(ROOT.getParent().resolve(".gitignore"),
                StandardCharsets.UTF_8);

        assertThat(ignored).contains("feedback/*");
        // …and the DIRECTORY survives, because docker creates a missing bind-mount source as root and
        // this service runs unprivileged. An ignored directory that does not exist in a clone is a
        // store that cannot write on the first deploy.
        assertThat(ignored).contains("!feedback/.gitkeep");
        assertThat(ROOT.getParent().resolve("feedback").resolve(".gitkeep")).exists();
    }

    /** {@code fsm.feedback.path}'s default, read out of application.yml where it is declared. */
    private static String defaultFeedbackPath() {
        String yaml = read(ROOT.resolve("orchestrator").resolve("src").resolve("main")
                .resolve("resources").resolve("application.yml"));
        for (String line : yaml.split("\n")) {
            String body = line.strip();
            if (body.startsWith("path: ${FSM_FEEDBACK_PATH:")) {
                return body.substring(body.indexOf(':', body.indexOf("${")) + 1, body.lastIndexOf('}'));
            }
        }
        throw new AssertionError("application.yml no longer declares fsm.feedback.path; this check "
                + "cannot verify a mount for a path it cannot find");
    }

    // ---- the parser -------------------------------------------------------------------------------

    /** One compose service, in the four shapes these checks ask about. */
    private record Service(Map<String, String> scalars, List<String> networks, List<String> volumes,
                           List<String> environment) {

        Optional<String> scalar(String key) {
            return Optional.ofNullable(scalars.get(key));
        }

        /** The value of an {@code environment} entry written {@code - NAME=value}. */
        Optional<String> environmentValue(String name) {
            return environment.stream()
                    .filter(entry -> entry.startsWith(name + "="))
                    .map(entry -> entry.substring(name.length() + 1))
                    .findFirst();
        }
    }

    /**
     * The services, without a YAML dependency.
     *
     * <p>The shape parsed
     * here — two spaces for a service, four for its keys, six for a list item — is the shape docker
     * compose documents, and {@link #theComposeFileIsParsedAsTheseChecksExpect} fails if the file stops
     * matching it, so a restructured file cannot turn these assertions into no-ops. Adding a YAML
     * library to this module to read one file would be a runtime dependency the fleet does not have.
     */
    private static Map<String, Service> services(String yaml) {
        Map<String, Service> services = new LinkedHashMap<>();
        Service current = null;
        String key = null;
        boolean inServices = false;
        for (String raw : yaml.split("\n")) {
            String line = strip(raw);
            if (line.isBlank()) {
                continue;
            }
            if (!line.startsWith(" ")) {
                inServices = line.startsWith("services:");
                current = null;
                key = null;
                continue;
            }
            if (!inServices) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            String body = line.strip();
            if (indent == 2 && body.endsWith(":")) {
                current = new Service(new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>(),
                        new ArrayList<>());
                services.put(body.substring(0, body.length() - 1), current);
                key = null;
                continue;
            }
            if (current == null) {
                continue;
            }
            if (indent == 4 && body.contains(":")) {
                key = body.substring(0, body.indexOf(':'));
                String value = body.substring(body.indexOf(':') + 1).strip();
                if (!value.isEmpty()) {
                    current.scalars().put(key, unquote(value));
                }
                continue;
            }
            if (indent == 6 && body.startsWith("- ")) {
                String entry = unquote(body.substring(2).strip());
                switch (key == null ? "" : key) {
                    case "networks" -> current.networks().add(entry);
                    case "volumes" -> current.volumes().add(entry);
                    case "environment" -> current.environment().add(entry);
                    default -> { }
                }
                continue;
            }
            if (indent == 6 && body.contains(":") && key != null) {
                // `build:` and friends — one level of nesting, addressed as "build.context".
                current.scalars().put(key + "." + body.substring(0, body.indexOf(':')),
                        unquote(body.substring(body.indexOf(':') + 1).strip()));
            }
        }
        return services;
    }

    /**
     * The names declared under the top-level {@code volumes:} key.
     *
     * <p>A DECLARATION IS A BARE {@code   name:} AT EXACTLY TWO SPACES, and that precision is load-bearing
     * rather than tidiness. Each volume in this file carries a nested {@code name:} pinning its physical
     * name, so a check of "indented and contains a colon" collects the literal string {@code name} as if
     * it were a declared volume. That was harmless while the only caller asked
     * {@code contains(theOneIExpect)}; it is not harmless for
     * {@link #everyDeclaredVolumeIsMountedAndEveryNamedMountIsDeclared}, which asks the reverse question
     * and would fail on a phantom volume that no service can possibly mount. Same shape the deleted
     * {@code compose.test.js} matched.
     */
    private static List<String> topLevelVolumes(String yaml) {
        List<String> names = new ArrayList<>();
        boolean inVolumes = false;
        for (String raw : yaml.split("\n")) {
            String line = strip(raw);
            if (line.isBlank()) {
                continue;
            }
            if (!line.startsWith(" ")) {
                inVolumes = line.startsWith("volumes:");
                continue;
            }
            if (inVolumes && line.matches("^ {2}[A-Za-z0-9_.-]+:\\s*")) {
                String body = line.strip();
                names.add(body.substring(0, body.indexOf(':')));
            }
        }
        return names;
    }

    /** Is {@code mount} a {@code source:target[:mode]} entry whose target is this directory? */
    private static boolean mountedAt(String mount, String directory) {
        String[] parts = mount.split(":");
        return parts.length >= 2 && parts[1].equals(directory);
    }

    /** The {@code source} half of a {@code source:target[:mode]} mount. */
    private static String mountSource(String mount) {
        int colon = mount.indexOf(':');
        return colon < 0 ? mount : mount.substring(0, colon);
    }

    /** A mount source that is a path rather than a named volume. */
    private static boolean isBindMount(String source) {
        return !source.isEmpty() && ".~/".indexOf(source.charAt(0)) >= 0;
    }

    /** A line with its trailing comment removed; compose files here are heavily annotated. */
    private static String strip(String raw) {
        int comment = raw.indexOf(" #");
        String line = comment < 0 ? raw : raw.substring(0, comment);
        return line.replaceAll("\\s+$", "");
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + path.toAbsolutePath(), e);
        }
    }

    /**
     * The reactor root, found by walking UP rather than by a relative path.
     *
     * <p>Surefire runs with the module directory as its working directory, but an IDE need not, and a
     * test that silently reads nothing because the path was wrong is exactly what
     * {@link #theComposeFileIsParsedAsTheseChecksExpect} exists to catch — so the search is explicit and
     * fails by name.
     */
    private static Path fleetRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("orchestrator"))
                    && Files.isRegularFile(candidate.resolve("n8n").resolve("docker-compose.yml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        // null, not an AssertionError: a static initialiser that throws turns every test in the class
        // into an ExceptionInInitializerError, which is how this failed the image build — six red tests
        // reporting a JVM init failure instead of one honest "not applicable here". onlyMeaningfulInAWorktree()
        // turns this null into a skip with a reason.
        return null;
    }
}
