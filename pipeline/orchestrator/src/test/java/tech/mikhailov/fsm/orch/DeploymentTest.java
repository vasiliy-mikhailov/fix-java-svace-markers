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
import tech.mikhailov.fsm.orch.config.FsmProperties;

/**
 * THE DEPLOYMENT IS PART OF THE PROGRAM, and every failure pinned here is invisible.
 *
 * <p>ZEROTH, AND THE ONE A STRANGER MEETS FIRST: THE STACK HAS TO START ON A MACHINE THAT IS NOT THE
 * DEPLOYMENT HOST. This file used to declare {@code proxy-net} and {@code mvn-cache} as
 * {@code external: true} — two networks that exist on exactly one machine in the world. Anywhere else
 * {@code docker compose up -d} dies on the first line with "network mvn-cache declared as external, but
 * could not be found", which is a hard stop before anything at all has run. That is not a subtle
 * failure, but it is a total one, and it shipped: the images built, three of them, and everything after
 * the build failed. A deployment that needs a host's private networks keeps them in an override file of
 * its own — see {@code deploy/docker-compose.override.yml.example} — because Compose merges an override
 * automatically and the base file then stays runnable by everyone.
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
 * calls — the two agents and the three judging stages — so it inherits the requirement wholesale.
 *
 * <p>THAT CHECK MOVED RATHER THAN DIED, and where it went matters. The committed compose file declares
 * no networks at all now and must not (see {@link #theStackNeedsNoNetworkFromSomebodyElsesHost}), so
 * there is nothing left in it to assert a superset over. The requirement moved into
 * {@code deploy/docker-compose.override.yml.example} — the committed template every deployment that
 * needs a private route copies — and so did the assertion:
 * {@link #theOverrideExampleKeepsEveryJudgingServiceOnTheModelsRoute}. For a while it lived nowhere:
 * {@code Service.networks} was still parsed and read by no test, so this class documented a guard it
 * did not have.
 *
 * <p>THIRD: WHERE THE PROVE IS POSTED. Node and n8n were removed from the deployment —
 * {@code n8n}, {@code java-runner} and {@code dashboard} were deleted, and {@code runner} has since
 * followed them into {@code fsm} — and deleting a service does not break the file that points at it.
 * {@code FSM_RUNNER_URL} kept defaulting to {@code http://fsm-java-runner:8090}
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
 *
 * <p>TWO OF THOSE CHANGED SHAPE WHEN THREE SERVICES BECAME ONE, and neither was dropped. The
 * {@code mvn-cache} route is gone as a NETWORK because the Nexus is no longer addressed as
 * {@code nexus:8081} from inside a private bridge — it is {@code MAVEN_MIRROR_URL}, a runtime setting
 * taking a public hostname, pinned by {@link #theMavenMirrorIsSettableOnTheRunningContainer} and by
 * {@code TheMavenMirrorIsARuntimeSettingTest}. The {@code proxy-net} superset moved to the override
 * example, above.
 */
class DeploymentTest {

    /** THE service. One container: the schedule, the queue, the dashboard AND the prover. */
    private static final String APP = "fsm";

    /** What {@code docker compose up -d} starts with no arguments. Exactly one thing. */
    private static final List<String> LIVE = List.of(APP);

    /**
     * Declared, but behind a profile — started only by naming it. {@code engine} is the debugging
     * route: the judgement over HTTP, one stage at a time, against a request written by hand. The
     * orchestrator embeds the same code as a library, so nothing in a run ever calls it.
     */
    private static final List<String> OPTIONAL = List.of("engine");

    /**
     * Gone, and named so their return is a failure rather than a surprise. {@code runner} joins the
     * n8n-era three: its prove now runs inside {@code fsm} (fsm.runner.mode=local), and a SECOND prover
     * in the stack is the sharpest thing that can go wrong here — {@code /run_test} is serialised inside
     * ONE process around one workspace per repository, and two processes share no lock at all.
     */
    private static final List<String> DELETED = List.of("n8n", "java-runner", "dashboard", "runner");

    private static final Path ROOT = fleetRoot();
    private static final Map<String, Service> COMPOSE = ROOT == null
            ? Map.of()
            : services(read(ROOT.resolve("deploy").resolve("docker-compose.yml")));

    /**
     * These checks read files OUTSIDE this module — deploy/docker-compose.yml above all — so they can only
     * run against a full worktree. The image build copies just orchestrator/, engine/ and pom.xml into
     * /src, on purpose: the running container has no business carrying the deployment stack. Inside that build
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
                + "deploy/docker-compose.yml, which the image build deliberately does not copy. Run "
                + "`mvn test` from the reactor root to exercise them.");
    }

    @Test
    void theComposeFileIsParsedAsTheseChecksExpect() {
        // Guard against every assertion below passing because the parser found nothing. If the file is
        // restructured, this fails loudly rather than the checks turning into no-ops.
        assertThat(COMPOSE).containsKeys(LIVE.toArray(String[]::new));
        assertThat(COMPOSE).containsKeys(OPTIONAL.toArray(String[]::new));
        assertThat(COMPOSE.get(APP).environment).isNotEmpty();
        assertThat(COMPOSE.get("engine").profiles)
                .as("a parser that finds no `profiles:` entry would make the opt-in check below pass "
                        + "for a service that is in fact started by every `docker compose up`")
                .isNotEmpty();
        // …and the same guard for the mounts and the top-level volume declarations, so the volume
        // checks below cannot pass by finding nothing either.
        assertThat(COMPOSE.get(APP).volumes)
                .as("the one service should mount its state and its cache")
                .isNotEmpty();
        assertThat(topLevelVolumes(read(ROOT.resolve("deploy").resolve("docker-compose.yml"))))
                .as("no top-level volumes parsed out of docker-compose.yml")
                .isNotEmpty();
    }

    /**
     * THE DEPLOYMENT IS ONE SERVICE, and everything else in the file is opt-in.
     *
     * <p>The acceptance criterion this whole change was made against is a stranger's first run: clone,
     * set the model endpoint, ONE command, a working dashboard. "One command" is not satisfied by a
     * stack that also happens to start two containers the reader was not told about and cannot explain
     * — each of which is a thing to wait for, a thing to read logs from, and a thing whose health is a
     * separate question.
     *
     * <p>A resurrected {@code runner}, {@code n8n} or {@code java-runner} is not a harmless leftover:
     * all three prove markers. {@code /run_test} is serialised inside ONE process around one workspace
     * per repository and two processes have no lock between them at all, so a second prover
     * {@code reset --hard}s and patches the tree the first one is building in. That surfaces as a Maven
     * build that inexplicably compiled somebody else's patch, which is not a thing anyone debugs
     * quickly.
     */
    @Test
    void theDeploymentIsOneServiceAndEverythingElseIsOptIn() {
        List<String> started = COMPOSE.entrySet().stream()
                .filter(entry -> entry.getValue().profiles.isEmpty())
                .map(Map.Entry::getKey)
                .toList();

        assertThat(started)
                .as("`docker compose up -d` starts more than the one container the runbook promises")
                .containsExactlyInAnyOrderElementsOf(LIVE);
        assertThat(COMPOSE.keySet())
                .as("a second prover in this stack shares no lock with the one inside fsm")
                .doesNotContainAnyElementsOf(DELETED);
    }

    /**
     * THE STACK STARTS ON A MACHINE THAT IS NOT THE DEPLOYMENT HOST.
     *
     * <p>{@code proxy-net} and {@code mvn-cache} were declared {@code external: true} here. Those are
     * one host's networks — a shared Caddy is on the first and a Nexus on the second — and an external
     * network that does not exist is not a degraded start, it is no start at all:
     *
     * <pre>network mvn-cache declared as external, but could not be found</pre>
     *
     * <p>on the first line of {@code docker compose up -d}, before a single container is created. The
     * images built fine, which is exactly why it shipped: every step of DOCKER.md up to that point
     * worked.
     *
     * <p>THE DEPLOYMENT HOST DID NOT LOSE ANYTHING. Compose merges {@code docker-compose.override.yml}
     * automatically when it is present, and that file is where a host's private networks belong — it is
     * gitignored, it is one {@code cp} from the example this test also pins, and it keeps the committed
     * file runnable by everyone else.
     */
    @Test
    void theStackNeedsNoNetworkFromSomebodyElsesHost() throws IOException {
        // THE DIRECTIVES ONLY. This file is more comment than YAML and the paragraph explaining why
        // `external: true` is absent contains the words `external: true` — a check over the raw text
        // would fail on the one piece of writing that must survive.
        String directives = read(ROOT.resolve("deploy").resolve("docker-compose.yml")).lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .map(DeploymentTest::strip)
                .reduce("", (a, b) -> a + b + "\n");

        assertThat(directives)
                .as("an `external: true` network is a hard failure on every machine that does not have "
                        + "it, before anything starts. Put it in docker-compose.override.yml, which "
                        + "Compose merges automatically and which is not committed.")
                .doesNotContain("external:");

        // …and the route back for the host that DOES have them, because deleting a capability without
        // leaving the door open is how the next person re-adds `external: true` to the committed file.
        Path example = ROOT.resolve("deploy").resolve("docker-compose.override.yml.example");
        assertThat(example)
                .as("the deployment host needs proxy-net; without an example nobody knows where it went")
                .exists();
        assertThat(read(example)).contains("proxy-net").contains("external: true");
        assertThat(Files.readAllLines(ROOT.getParent().resolve(".gitignore")))
                .as("an override committed by accident re-breaks every other machine, silently")
                .contains("docker-compose.override.yml");
    }

    /**
     * THE MODEL'S ROUTE LIVES IN THE OVERRIDE EXAMPLE NOW, AND IT IS STILL A GUARD.
     *
     * <p>THE DEFECT THIS DESCENDS FROM. Three stages that call the model moved into a container that was
     * not on {@code proxy-net}. The name did not resolve; {@code Fix skeptic}, {@code PR maker} and
     * {@code Verdict} all fail CLOSED, so each answered HTTP 200 with a safe default and every marker
     * settled as {@code needs_review} with {@code skeptic_verdict 'unknown'} — against a run history that
     * stayed green from end to end. The only evidence was the word "unknown" in a cell.
     *
     * <p>THE OLD GUARD WAS A SUPERSET CHECK over the committed file: whatever networks the engine had,
     * the orchestrator had too. That check could not survive the merge, because the committed file now
     * declares NO networks at all — it must not, or it stops starting on every machine that is not the
     * deployment host (see {@link #theStackNeedsNoNetworkFromSomebodyElsesHost}). Deleting the check with
     * the networks would have been wrong: the requirement did not go away, it MOVED, into
     * {@code docker-compose.override.yml.example}. That file is gitignored once copied but committed as
     * the template, it is what every deployment that needs a private route starts from, and it is the
     * last committed place where a service is put on the model's network.
     *
     * <p>SO THE SUPERSET CHECK MOVED WITH IT, and it is not a formality. {@code engine} runs the SAME
     * judgement code and makes the SAME model calls; an example that wires {@code fsm} to
     * {@code proxy-net} and leaves {@code engine} off it hands a reader a debugging tool that answers
     * every stage with the fail-closed default — a tool that lies, reached for precisely when something
     * is already wrong.
     *
     * <p>AND {@code default} HAS TO BE LISTED EXPLICITLY. Listing {@code networks:} at all REPLACES the
     * implicit default network rather than adding to it, so an override that names only {@code proxy-net}
     * silently takes the container off the stack's own bridge. The example says so in a comment; a
     * comment is not a guard.
     */
    @Test
    void theOverrideExampleKeepsEveryJudgingServiceOnTheModelsRoute() {
        String example = read(ROOT.resolve("deploy").resolve("docker-compose.override.yml.example"));
        Map<String, Service> override = services(example);

        // The parse-guard, for the same reason theComposeFileIsParsedAsTheseChecksExpect exists: an
        // example whose shape drifted would make every assertion below pass by finding nothing.
        assertThat(override)
                .as("the override example no longer wires the one service; these checks would be vacuous")
                .containsKey(APP);
        assertThat(override.get(APP).networks())
                .as("no networks parsed off `%s` in the override example — the file this test exists to "
                        + "check is the one file left that carries the model's route", APP)
                .isNotEmpty();

        List<String> declared = topLevel("networks", example);
        assertThat(declared)
                .as("no top-level `networks:` parsed out of the override example")
                .isNotEmpty();

        for (Map.Entry<String, Service> service : override.entrySet()) {
            List<String> networks = service.getValue().networks();
            if (networks.isEmpty()) {
                continue;
            }
            assertThat(networks)
                    .as("`%s` lists `networks:` without `default`. Listing any network REPLACES the "
                            + "implicit default rather than adding to it, so this takes the container "
                            + "off the stack's own bridge — where the profiled engine and every future "
                            + "service live.", service.getKey())
                    .contains("default");
            for (String network : networks) {
                if (!network.equals("default")) {
                    assertThat(declared)
                            .as("`%s` joins `%s`, which no top-level `networks:` entry in the example "
                                    + "declares — `docker compose up` refuses to start the whole stack",
                                    service.getKey(), network)
                            .contains(network);
                }
            }
        }

        // THE SUPERSET, which is the ported assertion and the reason for the whole test.
        assertThat(override)
                .as("the engine makes the same model calls as fsm and needs the same routes; dropping it "
                        + "from the example is how it silently loses them")
                .containsKey("engine");
        assertThat(override.get("engine").networks())
                .as("the engine is missing a route that fsm has. It runs the SAME judging stages, and "
                        + "they fail CLOSED: off the model's network it answers every stage HTTP 200 "
                        + "with a downgraded verdict nobody made, which is exactly the failure this "
                        + "example exists to prevent — in the tool you reach for to debug it.")
                .containsAll(override.get(APP).networks());
    }

    @Test
    void theOneContainerIsNamedTheSameWayEveryRunbookNamesIt() {
        Service app = COMPOSE.get(APP);

        // Without container_name compose would call it fsm-fsm-1, and every `docker logs`, `docker exec`
        // and log grep in the README would be wrong.
        assertThat(app.scalar("container_name")).contains("fsm");
        assertThat(read(ROOT.resolve("deploy").resolve("docker-compose.yml")))
                .as("the project name is what keeps this stack's volumes and network out of the other "
                        + "pipeline's — it used to share the directory basename `n8n`, and without an "
                        + "explicit name the project follows whatever this directory is called next")
                .contains("\nname: fsm\n");
    }

    @Test
    void theDatabasePathIsSetAndPointsIntoANamedVolume() {
        Service orchestrator = COMPOSE.get(APP);

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
        assertThat(topLevelVolumes(read(ROOT.resolve("deploy").resolve("docker-compose.yml"))))
                .as("a named volume that is not declared makes `docker compose up` refuse to start")
                .contains(source);
        // Read-only would make the whole service pointless, and `:ro` on this mount is an easy paste.
        assertThat(volume).doesNotEndWith(":ro");
    }

    /**
     * THE ENGINE IS AN OPT-IN DEBUGGING ROUTE, and keeping it is a deliberate choice rather than an
     * oversight.
     *
     * <p>NOTHING IN A RUN CALLS IT. The orchestrator embeds the same judgement code as a LIBRARY, so
     * there is no HTTP hop between the queue and the judgement, and {@code FSM_ENGINE_URL} is read by
     * nothing at all. What it is for is reproducing ONE stage against a request written by hand — the
     * only way to debug a stage without a 6-26 hour run around it.
     *
     * <p>SO IT IS DECLARED AND NOT STARTED. A profile is the mechanism, chosen over deleting the
     * service (which loses the route) and over leaving it running (which makes "one command, one
     * container" false and gives a reader a second thing to explain). {@code docker compose --profile
     * engine up -d engine} is the whole of it.
     */
    @Test
    void theEngineIsDeclaredButNotStarted() {
        Service engine = COMPOSE.get("engine");

        assertThat(engine.profiles)
                .as("the engine has no profile, so `docker compose up -d` starts it — which makes the "
                        + "runbook's 'one container' wrong and gives a reader a service nothing calls")
                .isNotEmpty();
        assertThat(engine.scalar("container_name")).contains("fsm-engine");
    }

    /**
     * THE MAVEN MIRROR IS A RUNTIME SETTING ON THE CONTAINER, and this test is the compose half of the
     * defect that made every prove fail on a clean machine.
     *
     * <p>{@code runner/Dockerfile} copied a committed {@code settings.xml} into the runtime stage
     * unconditionally, and it pinned {@code mirrorOf=*} to {@code http://nexus:8081/…}. A mirror of
     * {@code *} is the ONLY repository Maven will talk to, so off that one Docker network the container
     * started, answered /health with all five JDKs, cloned the repository, and failed EVERY build in
     * hundreds of lines about unresolvable artifacts. That reads as a broken project.
     *
     * <p>The fix has to reach the CONTAINER and not only the image build. A build argument is baked in
     * at {@code docker build} time and is no use to somebody running an image they did not build, which
     * is exactly what the Java Guild will be doing against a Nexus at their own endpoint. So the
     * variable is listed under {@code environment:} — and a value set only in {@code .env} reaches
     * Compose's interpolation and never the process, which is the same silent no-op
     * {@code FSM_RUNNER_URL} once had.
     */
    @Test
    void theMavenMirrorIsSettableOnTheRunningContainer() {
        assertThat(COMPOSE.get(APP).environmentValue("MAVEN_MIRROR_URL"))
                .as("MAVEN_MIRROR_URL has to be listed under `environment:` to reach the process at "
                        + "all; a build arg alone cannot be changed by someone running the image")
                .isPresent();
        assertThat(COMPOSE.get(APP).environmentValue("MAVEN_MIRROR_URL").orElseThrow())
                .as("the DEFAULT must be empty, which means Central. It is what a reader with nothing "
                        + "but Docker gets, and it is the first thing they will do.")
                .isEqualTo("${MAVEN_MIRROR_URL:-}");
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
    void theCheckoutsLiveInANamedVolumeAtThePathTheProcessIsToldToUse() {
        Service app = COMPOSE.get(APP);

        // THE PATH IS READ OFF THE SERVICE, not restated here. The prover now runs inside this process
        // and takes its cache directory from CACHE (fsm.runner.cache); a second copy of "/cache" in this
        // file would let the mount and the setting drift apart and still pass — which is precisely the
        // failure being guarded against, because an unmounted cache is not an error.
        String configured = app.environmentValue("CACHE")
                .orElseThrow(() -> new AssertionError("CACHE is not set on " + APP + ". Unset, the "
                        + "prover falls back to application.yml's ./data/cache — a relative path, which "
                        + "inside a container is the writable layer: every `docker compose up -d` then "
                        + "throws away every checkout and every target/ behind it, silently."));

        String cache = app.volumes.stream()
                .filter(mount -> mountedAt(mount, configured))
                .map(DeploymentTest::mountSource)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "nothing is mounted at " + configured + ", which is where CACHE points: "
                        + app.volumes + ". Unmounted, it is the container's writable layer, so every "
                        + "`docker compose up -d` discards every checkout and every target/ behind it."));

        assertThat(isBindMount(cache))
                .as("a bind mount would put a couple of dozen cloned repositories inside a git "
                        + "worktree: %s", cache)
                .isFalse();
        assertThat(topLevelVolumes(read(ROOT.resolve("deploy").resolve("docker-compose.yml"))))
                .as("a named volume that is not declared makes `docker compose up` refuse to start")
                .contains(cache);
        assertThat(cache)
                .as("this is pointed at the retired java-runner cache. Those checkouts carry a "
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
        List<String> declared = topLevelVolumes(read(ROOT.resolve("deploy").resolve("docker-compose.yml")));
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
     * THE PROVE HAS NO ADDRESS, which is the point of the merge and not a side effect of it.
     *
     * <p>{@code FSM_RUNNER_URL} was written in THREE places — this file, {@code application.yml} and
     * {@link HttpRunnerClient#DEFAULT_BASE_URL} — each a fallback for the one above it, and NONE of them
     * read until a marker is actually proved. That is how {@code fsm-java-runner} survived the container
     * it named: compose starts such a stack without complaint, /healthz is green, the schedule fires,
     * and six hours later the first prove spends three connect attempts on a name nothing serves and is
     * filed as an infrastructure failure — which reads as a runner that is down.
     *
     * <p>With {@code fsm.runner.mode=local} that chain is not merely correct, it is unread. The compose
     * file must therefore NOT set the variable: a value here would be configuration that looks
     * load-bearing and is not, which is the same defect wearing the opposite face.
     */
    @Test
    void theOneServiceConfiguresNoRunnerAddressAtAll() {
        Service app = COMPOSE.get(APP);

        assertThat(composeDefault(app.environmentValue("FSM_RUNNER_MODE").orElse(
                        FsmProperties.Runner.LOCAL)))
                .as("the deployment this file describes is the one-container one")
                .isEqualTo(FsmProperties.Runner.LOCAL);

        // NO ADDRESS IS ASSERTED BY THIS FILE. The default has to resolve to blank, because a literal
        // here is a knob that changes nothing in the `local` shape and is believed by the next reader —
        // which is how `fsm-java-runner` outlived the container it named.
        String url = app.environmentValue("FSM_RUNNER_URL").orElse("");
        assertThat(composeDefault(url))
                .as("this file names a runner. In the default (local) shape nothing reads it, so it is a "
                        + "stale address waiting to be believed; the http shape takes it from the "
                        + "environment.")
                .isEmpty();

        // …BUT IF THE MODE TRAVELS, THE ADDRESS TRAVELS WITH IT. Compose hands a variable to the process
        // only when it is listed under `environment:`; one set in the shell or .env and not listed here
        // reaches Compose's interpolation and stops. With the mode listed and the address not,
        // `FSM_RUNNER_MODE=http FSM_RUNNER_URL=… docker compose up -d` — the command both runbooks
        // printed — switched the mode and dropped the address, so every prove went to
        // HttpRunnerClient.DEFAULT_BASE_URL: a name nothing in this stack serves, discovered hours into a
        // run as an infrastructure failure. Half a pass-through is worse than neither.
        if (app.environmentValue("FSM_RUNNER_MODE").isPresent()) {
            assertThat(app.environmentValue("FSM_RUNNER_URL"))
                    .as("FSM_RUNNER_MODE is pass-through but FSM_RUNNER_URL is not, so the http shape "
                            + "this file advertises cannot be addressed by the command that selects it. "
                            + "List both (the address defaulting to empty) or neither.")
                    .isPresent();
        }
    }

    /**
     * THE REMOTE SHAPE IS STILL COHERENT, in the two places that remain.
     *
     * <p>{@code fsm.runner.mode=http} is supported — {@code /run_test} runs third-party build scripts,
     * and a deployment that wants that behind a container boundary is making a reasonable trade. What
     * that mode reads is {@code application.yml}'s placeholder default and, behind it,
     * {@link HttpRunnerClient#DEFAULT_BASE_URL}, which is reached whenever the placeholder resolves to
     * blank. Two copies of one address, neither exercised until a marker is proved: pinned to each
     * other so they cannot drift while nobody is looking.
     */
    @Test
    void theRemoteRunnersAddressSaysTheSameThingInBothPlacesThatKeepIt() throws IOException {
        String yaml = Files.readString(
                ROOT.resolve("orchestrator/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        java.util.regex.Matcher placeholder = java.util.regex.Pattern
                .compile("\\$\\{FSM_RUNNER_URL:([^}]*)}").matcher(yaml);
        assertThat(placeholder.find())
                .as("application.yml no longer defaults fsm.runner.base-url from FSM_RUNNER_URL, so a "
                        + "deployment that sets that variable reaches nothing")
                .isTrue();

        assertThat(HttpRunnerClient.DEFAULT_BASE_URL)
                .as("the compiled-in default disagrees with the yaml. It is the last fallback in the "
                        + "chain — reached whenever the placeholder resolves to blank — so a stale one "
                        + "here is invisible in every configuration that looks correct.")
                .isEqualTo(placeholder.group(1));
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

    /**
     * ONE IMAGE, AND IT CARRIES THE TOOLCHAIN THE PROVE NEEDS.
     *
     * <p>Two Dockerfiles were merged into this one, and the merge is only correct if the RUNTIME stage
     * keeps what the runner's had. This image does not merely run its own code: {@code /run_test} shells
     * out to {@code git}, {@code mvn} and {@code ./gradlew} over somebody else's repository. Strip the
     * JDKs or Maven out and the container starts, serves the dashboard, answers /healthz — and reports
     * every marker in the backlog as an infrastructure failure.
     *
     * <p>FIVE JDKs, NOT ONE. {@code Build.requiredJdk} reads "release version N not supported" out of a
     * failed build and {@code Prove} retries on N, so every major it may switch to has to be installed
     * or the retry silently declines and the marker is recorded as unreproducible on a JDK it was never
     * going to compile under. WebGoat needs 25.
     */
    @Test
    void theOneImageIsBuiltThroughTheReactorAndCarriesTheBuildToolchain() throws IOException {
        Service app = COMPOSE.get(APP);

        // `orchestrator` depends on `engine` AND on `runner`, and resolves both FROM THE REACTOR —
        // nothing publishes those jars anywhere. A build context of ../orchestrator therefore cannot
        // build this image at all, and the failure arrives as an unresolvable dependency in the middle
        // of a Docker build that takes minutes to reach it.
        assertThat(app.scalar("build.context"))
                .as("the context has to be the reactor root, which holds all three modules")
                .contains("..");

        Path dockerfile = ROOT.resolve("Dockerfile");
        assertThat(dockerfile)
                .as("the one image lives at the reactor root, because that is its build context")
                .exists();
        String image = Files.readString(dockerfile, StandardCharsets.UTF_8);

        // Multi-stage: the runtime layer must not carry the ~/.m2 tree the build accumulated.
        assertThat(image).contains("AS build");
        assertThat(image.split("(?m)^FROM ").length - 1)
                .as("multi-stage, like engine/Dockerfile and for the same reasons")
                .isGreaterThanOrEqualTo(2);
        // The whole port targets 25; an image on 21 fails at class-file version, at run time.
        assertThat(image).contains("JDK_VERSION=25");
        // …and it has to build the reactor, or the engine's and runner's classes are simply absent.
        assertThat(image).contains("-pl orchestrator").contains("-am");

        String runtime = image.substring(image.lastIndexOf("\nFROM "));
        assertThat(runtime)
                .as("the runtime stage must install every JDK Build.JDKS names; a retry onto a major "
                        + "that is not there silently declines and the marker is filed as unreproducible")
                .contains("for v in 8 11 17 21 25");
        assertThat(runtime)
                .as("Prove shells out to `mvn`; without it every prove is an infrastructure failure")
                .contains("apache-maven");
        assertThat(runtime)
                .as("every prove starts with a clone")
                .contains("git");
        // AND THE TWO DELETED FILES STAY DELETED. Leaving them is how somebody rebuilds the split stack
        // from a Dockerfile that no longer matches the compose file or the code.
        assertThat(ROOT.resolve("orchestrator").resolve("Dockerfile")).doesNotExist();
        assertThat(ROOT.resolve("runner").resolve("Dockerfile")).doesNotExist();
    }

    /**
     * NOTHING SHIPPED CARRIES A VALUE THAT ONLY RESOLVES ON ONE MACHINE.
     *
     * <p>ORIGIN. {@code .env.example} shipped {@code QWEN_BASE_URL=http://inference-vllm:8000/v1} — a
     * compose service name on the author's host. Step three of the runbook is {@code cp .env.example
     * .env}, so what a stranger got was a pre-filled wrong value, which is worse than a blank one
     * precisely because it looks configured. This pipeline fails CLOSED: the stack comes up healthy,
     * every judging stage answers 200 with a safe default, and markers settle as {@code needs_review}
     * against a green run history.
     *
     * <p>It was fixed in {@code deploy/.env.example} and not in the copy at the repository root, which
     * still carried both the hostname and the private model name. A guard rather than a second fix,
     * because "check the other copies" is not a thing anybody does twice.
     */
    @Test
    void noShippedExampleCarriesAValueThatOnlyWorksOnTheAuthorsHost() throws IOException {
        List<Path> examples;
        try (java.util.stream.Stream<Path> tree = Files.walk(ROOT.getParent())) {
            examples = tree.filter(path -> path.getFileName().toString().endsWith(".env.example"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .sorted()
                    .toList();
        }
        assertThat(examples).as("no .env.example found at all — this check would pass vacuously")
                .isNotEmpty();

        for (Path example : examples) {
            List<String> settings = Files.readAllLines(example, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.startsWith("#") && line.contains("="))
                    .toList();
            assertThat(settings)
                    .as("%s pre-fills a model endpoint. It must be BLANK: this pipeline fails closed, "
                            + "so a hostname that resolves nowhere produces a green run in which every "
                            + "marker settles with a downgraded verdict nobody made.", example)
                    .noneMatch(line -> line.startsWith("QWEN_BASE_URL=")
                            && line.length() > "QWEN_BASE_URL=".length())
                    .noneMatch(line -> line.startsWith("QWEN_MODEL=")
                            && line.length() > "QWEN_MODEL=".length());
            assertThat(settings)
                    .as("%s names a host that exists on one machine", example)
                    .noneMatch(line -> line.contains("inference-vllm"))
                    .noneMatch(line -> line.contains("nexus:8081"));
        }
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
        Service orchestrator = COMPOSE.get(APP);

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

    /** One compose service, in the five shapes these checks ask about. */
    private record Service(Map<String, String> scalars, List<String> networks, List<String> volumes,
                           List<String> environment, List<String> profiles) {

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
                        new ArrayList<>(), new ArrayList<>());
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
                if (value.startsWith("[") && value.endsWith("]")) {
                    // A FLOW LIST on the key line — `profiles: ["engine"]` — which compose accepts and
                    // which is the readable spelling for a one-element list. Parsed here rather than
                    // forbidden in the file, because a check that silently sees no profiles is a check
                    // that reports the engine as part of the default stack when it is not.
                    for (String item : value.substring(1, value.length() - 1).split(",")) {
                        if (!item.isBlank()) {
                            String parsed = unquote(item.strip());
                            switch (key) {
                                case "networks" -> current.networks().add(parsed);
                                case "volumes" -> current.volumes().add(parsed);
                                case "environment" -> current.environment().add(parsed);
                                case "profiles" -> current.profiles().add(parsed);
                                default -> { }
                            }
                        }
                    }
                    continue;
                }
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
                    case "profiles" -> current.profiles().add(entry);
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
        return topLevel("volumes", yaml);
    }

    /** The names declared under a top-level {@code <section>:} key — {@code volumes:} or {@code networks:}. */
    private static List<String> topLevel(String section, String yaml) {
        List<String> names = new ArrayList<>();
        boolean inSection = false;
        for (String raw : yaml.split("\n")) {
            String line = strip(raw);
            if (line.isBlank()) {
                continue;
            }
            if (!line.startsWith(" ")) {
                inSection = line.startsWith(section + ":");
                continue;
            }
            if (inSection && line.matches("^ {2}[A-Za-z0-9_.-]+:\\s*")) {
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
                    && Files.isRegularFile(candidate.resolve("deploy").resolve("docker-compose.yml"))) {
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
