# fsm-runner

The prover's hands: clone a repository, write the reproducer test, build **RED**, apply the fixer's edits,
build **GREEN**, and answer with what happened. It decides nothing — every judgement about the result
belongs to `engine`.

Its edit application, build parsing and path containment are pinned by a **frozen differential
harness**: 23 401 cases, each with the answer a recorded reference run gave for it, replayed by
`DifferentialHarnessTest` on every `mvn test`. `DifferentialHarnessTest` hardcodes the case count, so a
corpus that quietly loses cases cannot certify itself green. `harness/README.md` states what the
recording is, what the freeze buys, what it costs, and why the fixtures cannot be regenerated.

> ## THIS IS A LIBRARY, NOT A SERVICE — read this before the rest of the file
>
> In the default shape there is **no runner container**. `deploy/docker-compose.yml` declares one
> service, `fsm`, and the prove runs inside it: `tech.mikhailov.fsm.runner.LocalRunner` is the same
> `Prove`, `Workspace` and `Build` behind the same single FIFO build thread, called in-process by the
> orchestrator's `LocalRunnerClient`. `RunnerServer` wraps exactly that code over HTTP — set
> `FSM_RUNNER_MODE=http` and everything below about ports, routes and reply shapes is live.
>
> **Why in-process is the default.** The split costs an ADDRESS: `FSM_RUNNER_URL` in the compose
> environment, a `${FSM_RUNNER_URL:…}` placeholder in `application.yml` and
> `HttpRunnerClient.DEFAULT_BASE_URL` compiled in behind both, each a fallback for the one above it and
> none of them read until a marker is proved. A wrong value anywhere in that chain surfaces hours into a
> 6-26 hour run as a refused connect filed as an infrastructure failure — which reads as a prover that is
> down rather than as a name nothing serves.
>
> **This module has no image and no `settings.xml` of its own.** There is one `pipeline/Dockerfile`,
> whose runtime stage carries git, JDK 8/11/17/21/25 and Maven, and the process line is
> `env LC_ALL=C.UTF-8 java -jar /app/fsm.jar` over a Spring Boot fat jar. The Maven mirror is
> `MAVEN_MIRROR_URL` read at RUN time by `MavenSettings` — empty means Central. A `mirrorOf=*`
> `settings.xml` baked into an image would make every prove fail on every machine where that hostname
> does not resolve; see [The mirror](#the-mirror-is-optional-and-that-is-the-point).

**Why the process line begins with `env`.** `java.nio.file.Path` spells filenames in `sun.jnu.encoding`,
which comes from the process's locale and *cannot* be set with a `-D` — the JDK overwrites the command
line with the platform's value. debian-slim ships no locale, so without this the encoding is
`ANSI_X3.4-1968` and no path outside ASCII can be named from Java at all: an edit aimed at
`…/Café.java` comes back "file not found", a non-ASCII `test_path` ends the prove with a raw
`InvalidPathException`, and `/fs/read_file` tells the dashboard "source unavailable" for a file that is
in the checkout. The variable is on the exec line and **not** an image `ENV`, and `Proc` strips `LANG`,
`LANGUAGE` and every `LC_*` out of every child, so no build of any repository under test inherits it.
That isolation is deliberate and is enforced by code: a locale reaching a JDK 8 or 11 build changes its
`file.encoding`, which changes what its tests DO, and a deployment detail must never become a variable
in the verdict. `PathEncoding`, `pipeline/Dockerfile` and `PathEncodingTest` carry the detail; the first
line of the log reports `sun.jnu.encoding=` and warns if it is not UTF-8.

---

## Build

**The build context is the reactor root (`pipeline/`), not this directory.** `runner` depends on
`tech.mikhailov.fsm:engine` and resolves it from the reactor, because nothing publishes that jar to any
repository. A context of `./runner` cannot build the image at all, and the failure arrives minutes in as an
unresolvable dependency.

**There is no image of this module's own.** It is compiled into `pipeline/Dockerfile`'s single image,
whose runtime stage carries the five JDKs, Maven and git this module shells out to. So the build command
is the deployment's:

```bash
cd pipeline/deploy
docker compose up -d --build                   # Maven Central; works on a fresh clone anywhere
```

Through the Nexus mirror instead — worth it on the deployment host, where a cold build is a few hundred
artifacts — the build itself has to be **on the `mvn-cache` network**, and that is not something
`docker compose build` can ask for. So build it by hand, from the reactor root, under the tag compose
already expects:

```bash
cd pipeline
DOCKER_BUILDKIT=0 docker build --network mvn-cache \
  --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ \
  -t fsm:latest .
docker compose -f deploy/docker-compose.yml up -d           # uses the tag; does not rebuild
```

`--network <name>` is the classic builder's, hence `DOCKER_BUILDKIT=0`. That also leaves `TARGETARCH`
unset, which is why the Dockerfile reads it as `${TARGETARCH:-amd64}` — correct on this host, and correct
under BuildKit on a developer's arm64 machine. Do not hardcode `linux/x64` in its place: the image then
builds happily on arm64 and every `java` in it dies at run time with "exec format error".

`MAVEN_MIRROR_URL` behaves exactly as it does for `engine` and `orchestrator`: **opt-in, defaulting to
Central**, because a baked-in `http://nexus:8081` mirror would make the image unbuildable everywhere —
including on the host that has Nexus. The *run-time* mirror is a separate, equally opt-in setting; see
[The mirror](#the-mirror-is-optional-and-that-reversal-was-the-fix).

Three more things the build does on purpose:

- **Tests run in the image build**, every module's. The runner's suite is the specification of the one
  distinction the whole pipeline rests on — did the test *run* and fail, or did it never run.
  `--build-arg SKIP_TESTS=-DskipTests` exists only to reproduce a build whose failure you are already
  looking at.
- **All four module poms are copied.** Maven builds the model for every module the aggregator lists
  before `-pl` selects anything, so a missing one fails the build before it starts with `Child module
  /src/orchestrator of /src/pom.xml does not exist`. A module added to `pipeline/pom.xml` is a `COPY`
  line added to `pipeline/Dockerfile` — the one place, now that there is one image.
- **`fsm-runner.jar` is a THIN jar.** This module has no shade plugin and no spring-boot repackage, so
  the jar holds `tech.mikhailov.fsm.runner.*` and nothing else — `java -jar fsm-runner.jar` dies on the
  first line of `main` with `NoClassDefFoundError: tech/mikhailov/fsm/lib/Json$JsonException`, because
  `Json`, `Js.string` and `JsText.isSpace` are the engine's. **The shipped image no longer contains this
  jar at all**: `pipeline/Dockerfile` copies the orchestrator's Spring Boot fat jar, which repackages
  every runtime dependency including this module and `engine`. The thin jar and its `target/libs` are a
  local-build artefact — see "Locally, without Docker", which still needs the
  `dependency:copy-dependencies` in the same Maven invocation as `package` (a separate one cannot
  resolve `engine`: it comes from the reactor and nothing ran `install`).

### Locally, without Docker

```bash
cd pipeline
mvn -B -pl runner -am package \
    dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/libs
CACHE=/tmp/fsm-cache PORT=8099 java \
    -cp "runner/target/fsm-runner.jar:runner/target/libs/*" tech.mikhailov.fsm.runner.Runner &
curl -s localhost:8099/health
# {"ok":true,"jdks":[]}
```

Quote the `-cp` value: the `*` is the JVM's, and a shell that expands it first produces a class path of
one jar and a syntax error. `"jdks":[]` is correct here and is the point of the next paragraph — the
routes all answer, but a real `/run_test` will not work, because the image installs JDKs at
`/opt/jdk/<major>` and `Build.javaHome` looks there. On a laptop a prove fails in Maven with a `JAVA_HOME`
that does not exist. Use the image for that.

---

## Run

**By default this module is never "run".** It is called in-process, and the line that says so is:

```bash
docker compose logs fsm | grep '\[runner\]'
# [runner] in-process, cache /cache, maven central (no MAVEN_MIRROR_URL)
```

**To run it as a service anyway** — `fsm.runner.mode=http`, the split shape — you supply the container
yourself: `deploy/docker-compose.yml` declares no `runner` service and there is no separate runner image.
You do not need one. The single image already carries this module, inside the orchestrator's Spring Boot
fat jar, and Boot's `PropertiesLauncher` will start any main class in it:

```bash
docker run -d --name fsm-split-runner --network fsm_default -e PORT=8090 -e CACHE=/cache \
  --entrypoint env fsm:latest LC_ALL=C.UTF-8 java \
  -Dloader.main=tech.mikhailov.fsm.runner.Runner \
  -cp /app/fsm.jar org.springframework.boot.loader.launch.PropertiesLauncher

docker exec fsm-split-runner curl -fsS http://127.0.0.1:8090/health
# {"ok":true,"jdks":["8","11","17","21","25"]}
```

`env LC_ALL=C.UTF-8` for the same reason the main entrypoint has it, and it is not decoration: without
it `sun.jnu.encoding` is `ANSI_X3.4-1968` and no non-ASCII path can be named from Java at all. The
startup line reports what it got — `sun.jnu.encoding=UTF-8`, or a warning.

Then point the orchestrator at it. **Both variables must be set on the `fsm` service**, and both are
pass-through in the compose file for exactly that reason:

```bash
FSM_RUNNER_MODE=http FSM_RUNNER_URL=http://fsm-split-runner:8090 docker compose up -d
docker compose logs fsm | grep '\[runner\]'
# [runner] http://fsm-split-runner:8090/run_test, up to 5400s per prove, 3 connect attempt(s)
```

Read that line rather than trusting the command: it prints the URI the client actually resolved. Compose
passes a variable to the process only if it is listed under `environment:`, so if the mode is listed and
the address is not, this pair switches the mode and **silently drops the address** — every prove then
goes to `HttpRunnerClient.DEFAULT_BASE_URL`, which nothing in the stack serves, and it surfaces hours
into a run as an infrastructure failure. `DeploymentTest` pins the two to travel together.

An **empty** `FSM_RUNNER_URL` is safe, and that is what makes the pass-through possible: Spring resolves
`${FSM_RUNNER_URL:…}` against a variable that exists and is empty to `""`, not to the placeholder's
default, and both halves of this shape guard blank against the same `DEFAULT_BASE_URL` —
`HttpRunnerClient` for the prove and `HttpSourceReader` for the source window. They must keep agreeing:
the dashboard reads source through the same prover that did the build, so if the two resolved differently
a reviewer would be shown source from a different checkout than the one that was judged.

`jdks` is the list actually **on disk**, not the list the code knows about. A JDK missing from that reply
is a download that failed in the image build, and it matters: `/run_test` retries onto the major a build
demands, so a missing 25 means WebGoat is recorded as unreproducible on a JDK it was never going to
compile under.

| variable | default | what it is |
|---|---|---|
| `PORT` | `8090` | `RunnerServer.DEFAULT_PORT`, and `HttpRunnerClient.DEFAULT_BASE_URL` names it too, so moving it means moving `FSM_RUNNER_URL` with it. A value that is not a number makes the process **refuse to start**, rather than bind a random port and look healthy to everything except the caller. |
| `BIND` | `0.0.0.0` | Leave it. The service publishes no ports; loopback would make it unreachable from every other container while looking perfectly healthy from inside its own. |
| `CACHE` | `/cache` | Where the clones go. The image prepares this path and compose mounts a named volume on it. |
| `GIT_TOKEN` (`GITHUB_TOKEN` when it is unset) | *(unset)* | The clone credential, for whatever host the marker's `repo` names. Absent is legitimate — public repositories clone without one, and the failure when it *is* needed is a git error inside `error`, not a crash. |
| `FSM_GIT_HOST` | `github.com` | Where a bare `owner/name` is cloned from. A `repo` that is a full clone URL (`https://gitlab.company/g/p.git`, `ssh://git@host/g/p.git`, `git@host:g/p.git`) or a bare `host/group/project` names its own host and ignores this. `tech.mikhailov.fsm.runner.CloneUrl` decides, and refuses anything that is not a repository — a leading `-` (an option to `git clone`), `ext::` (a transport that runs a command), `file://`, and any URL carrying a credential. |

No `QWEN_*`, no `SVACE_*`: this service never calls a model. It has one secret and one job.

### The mirror is optional, and that is the point

**Never bake a mirror into the image.** A `settings.xml` setting `mirrorOf=*` is not a cache in front of
Central — it is **the only repository Maven will talk to**. On any machine where that hostname does not
resolve, the container starts, answers `/health` with all five JDKs, clones happily, and then fails every
RED build with hundreds of lines about unresolvable artifacts, which reads as a broken target repository
rather than as an image carrying somebody else's infrastructure.

So the mirror is `MAVEN_MIRROR_URL`, read at RUN time by `MavenSettings`:

- **unset — the default —** means Maven Central, and it must work on a laptop with nothing but Docker.
- set means that URL, written to a `settings.xml` on the cache volume and handed to every `mvn` with
  `-s`.

An environment variable rather than a build argument, because the Guild will run an image they did not
build against a Nexus at their own endpoint. Use a **public hostname**: a compose-internal name like
`nexus:8081` resolves only inside one Docker network, which is precisely why it could not be a default.
`TheMavenMirrorIsARuntimeSettingTest` pins the code half and `DeploymentTest` the compose half. Which one
is in force is in the boot log:

```bash
docker compose logs fsm | grep '\[runner\]'
# [runner] in-process, cache /cache, maven central (no MAVEN_MIRROR_URL)
# [runner] in-process, cache /cache, maven /cache/maven-settings.xml
```

The mirror is reached by address, not over a shared Docker bridge, so nothing has to declare a network
for it — which is what keeps the committed compose file runnable on a machine that has neither.

---

## The `/cache` volume

A **named volume**, not a bind mount — a couple of dozen cloned repositories do not belong inside a git
worktree — holding both the patched build workspaces at `/cache/<key>` and the read-only checkouts at
`/cache/fs/<key>` that the dashboard's marker view and the orchestrator's source window read through.

**Do not point this at a cache volume some other program wrote, and do not share one between two
provers.** Three reasons, each sufficient on its own:

**1. This code ADOPTS an existing checkout instead of re-cloning it.** `prepareWs` takes any directory
that has a `.git`, and `prepareFs` takes any tree whose `HEAD` resolves; neither rewrites
`remote.origin.url`. So if a checkout on that volume was cloned from `https://<token>@github.com/…`, git
wrote that token verbatim into its `.git/config` and this service will keep using it — past a rotation of
`GIT_TOKEN` in `.env`, because nothing ever looks at that config again. Nothing here creates such a
checkout: `Workspace.CREDENTIAL_HELPER` hands the token to git through a one-shot `GIT_CONFIG_COUNT`
helper that leaves nothing on disk, and `/fs/read_file` refuses any path with a dot-named component at
any depth, links resolved, so `.git/config` cannot be read back out through the port either. Adoption is
the one way a credential gets in.

**2. Two processes, no lock.** `/run_test` is serialised inside **one** process — a single-thread FIFO
build queue — because there is one cached workspace per repository and two proves would `reset --hard`
and patch each other's tree, then both report about a file neither of them wrote. That guarantee is
per-process, so a shared `/cache` removes the only mutual exclusion that exists.

**3. Ownership.** The image runs as an unprivileged uid and owns `/cache`, which works because Docker
seeds an *empty* named volume from the image's directory, ownership included. A pre-existing root-owned
tree fails every write instead.

**Starting a cache from empty costs one slow pass and is paid once.** A cold clone per repository,
minutes each, and the first prove of each one has no `target/` and no warm local repository, which is
what makes the second build of it minutes rather than most of an hour. Over a couple of dozen
repositories that is roughly one slow first pass. `Workspace.keyFor` is `sha1("<repo>@<branch>")`
truncated, so the cache is addressed by content of the request and nothing else.

---

## The contract

Five routes on one handler, matched by **exact** path (`com.sun.net.httpserver` matches contexts by
*prefix*, so a context per route would make `POST /run_test/oops` silently start a build). Everything is
`200` except a body that is not JSON (`400`), a body over 16 MB (`413`) and a path or method that does not
exist (`404`). **A build that failed answers `200` with `{"ok": false, "error": …}`**, because that is an
*answer about a marker*: the orchestrator's `RunnerClient` treats a non-2xx as "nothing was learned" and
puts the marker back untouched, so promoting a build failure to a `500` would erase the reason from the
run history.

`GET` is answered without reading a body and never routes to a POST endpoint, so `POST /health` is a
`404`. The healthcheck and every `curl` in the operators' notes use `GET /health`.

### `POST /run_test`

The one route that matters. One clone plus two Maven builds, up to 20 minutes each; callers allow 90
minutes. Requests are **queued FIFO** and served one at a time, and waiting is cheap for the caller (the
wait is on a virtual thread), so a second prove arriving mid-build is slow, not lost.

```jsonc
{
  "repo":       "WebGoat/WebGoat",          // required
  "branch":     "main",                     // default "main"
  "jdk":        "17",                       // default "17"; one of 8, 11, 17, 21, 25
  "module":     "webgoat-lessons/xxe",      // optional; Maven -pl … -am, or a Gradle project path
  "build":      "auto",                     // "auto" | "maven" | "gradle" — detection can be overridden
  "test_class": "XxeReproTest",             // required, non-empty  -> -Dtest=…
  "test_path":  "src/test/java/…/XxeReproTest.java",   // required, non-empty
  "test_code":  "package …;",               // required (may be empty)
  "fix_edits":  [ { "path": "src/main/java/…/A.java", "old_str": "…", "new_str": "…" } ]
}
```

`test_class`, `test_path` and `test_code` are **refused** when absent rather than coerced, and that is
load-bearing rather than strictness. The natural coercion of a missing `test_class` is an empty
`-Dtest=`, which runs the repository's **entire suite** — and on a project with failing tests of its own
the red build then reports failures and the marker comes back `red_reproduced: true` having never been
tested. A fabricated reproduction is the one answer this service must never give.

```jsonc
{
  "ok": true,
  "red_reproduced": true,      // the test RAN and FAILED. A compile error is NOT a reproduction:
                               //   nothing ran, so nothing was established about the code.
  "green_passed": true,        // the test RAN and PASSED after the edits, on the JDK red settled on
  "proven": true,              // red_reproduced && green_passed
  "jdk": "25",                 // the JDK actually used — a build that demands another gets ONE retry
  "applied_files": ["src/main/java/…/A.java (matched ignoring whitespace/line-wrapping)"],
  "edit_errors":   [],         // per-edit refusals, e.g. "file not found", "old_str matches 3x (not
                               //   unique)", "refuses to edit a test file (fixer may not touch the test)"
  "red_summary":   { "tests": "junit-xml: tests=1 failures=1 errors=0", "ran": 1, "failures": 1,
                     "errors": 0, "test_executed": true, "build": "BUILD FAILURE",
                     "compile_error": false, "source": "junit-xml" },
  "green_summary": { "…": "…" },
  "red_output":    "…tail of the build log…",
  "green_output":  "…"
}
```

**The field names are the contract**, and a tolerant reader is why: the caller reads this reply key by
key out of a map, so a renamed key is read as absent — a missing `red_reproduced` is `false`, which is a
marker recorded as not reproduced rather than an error anybody sees.

`source` says where the numbers came from. This run's JUnit XML wins over console scraping whenever it
produced any (which is what makes Gradle work), and stale reports from an earlier build in the cached
workspace are gated out by mtime — without that, a previous run's green report reads as this run's and
turns "the build did not run" into "the fix works".

### The rest

| route | request | reply |
|---|---|---|
| `GET /health` | — | `{"ok": true, "jdks": ["8","11","17","21","25"]}` |
| `POST /fs/read_file` | `{"repo": "…", "branch": "main", "path": "src/main/java/…/A.java"}` | `{"path": …, "content": …, "truncated": false}` or `{"error": "…"}` |

**There is no lease route, and nothing should add one back as a substitute for the build queue.** Mutual
exclusion here is structural: Spring Batch gives the orchestrator single-flight, and this module
serialises every prove behind one FIFO build thread. A lease is advisory — a caller that does not take
it is not stopped — so a probe against one reports "free" while a prove is running, which is a false
negative on exactly the condition such a check exists to detect.

`/fs/read_file` never throws for a bad request: a failed clone, an escaping path, a refused path and a
missing file all come back as `{"error": …}` with a `200`, because the caller renders "source unavailable
— *reason*" in a tab whose other four panes are fine. It serves **source**, not the checkout: nothing
under a dot-named component is served, at any depth, with links resolved.

---

## Recreating the container kills an in-flight prove

Worth knowing before any `docker compose up -d`, because a prove is a 20-to-90-minute Maven build held
open by one call. A recreate sends `SIGTERM`, the context shuts down, and the call is abandoned; the
shutdown hook interrupts the build thread and kills the child Maven rather than orphaning one that owns
the shared workspace.

**Nothing is corrupted.** A marker mid-prove goes back to `new` with its attempt count untouched and the
H2 rows survive. What is lost is wall clock — up to 90 minutes of the one serialised workspace. So drain
first, or accept the loss deliberately:

```bash
cd pipeline/deploy
docker compose logs --tail=50 fsm | grep -i 'run_test\|proving'
```

## Layout

| path | what |
|---|---|
| `pom.xml` | module: Java 25, no third-party runtime dependencies, one on `engine` for the shared coercion and JSON helpers (`Json`, `Js.string`, `JsText.isSpace`), whose exact semantics the frozen harness pins |
| `…/MavenSettings.java` | writes the `settings.xml` handed to every `mvn` with `-s`, from `MAVEN_MIRROR_URL`; unset means Central |
| `…/LocalRunner.java` | the in-process prover the orchestrator calls: same `Prove`, same single FIFO build thread, no HTTP |
| `src/main/java/…/Runner.java` | entrypoint: environment, cache, bind, shutdown hook |
| `…/RunnerServer.java` | the five routes, the virtual-thread executor, the single-thread build queue |
| `…/Prove.java` | `doRunTest`: write the test, RED, apply, GREEN |
| `…/Build.java` | what a build *did* — the JUnit-XML-over-console rule, and the JDK retry |
| `…/Edit.java` | `applyEdit` / `fixTarget`: the structural allowlist that stops a fix editing its own test |
| `…/Workspace.java` | the two clones, the cache key, the credential helper, `/fs/read_file` |
| `…/Proc.java` | the `execFile` wrapper every `git`/`mvn`/`./gradlew` goes through; never throws |
| `harness/` | the FROZEN reference answers (`fixtures/`), the divergence catalogue the test asserts, and `README.md`, which is honest about what freezing them cost |

Tests, from `pipeline/`:

```bash
mvn -B -pl runner -am test                                   # engine, then runner's 188
mvn -B -pl runner -am test-compile \
  && mvn -B -pl runner org.pitest:pitest-maven:mutationCoverage   # report: runner/target/pit-reports/
( cd runner && sh harness/run.sh )                           # …and print the differential report
```

PIT needs the `test-compile` first — invoked as a bare goal it does not run the lifecycle. `Runner` is
excluded from mutation: it is an environment read, a bind and a shutdown hook, so its mutants are only
killable by an integration test and a low score there would read as "the logic is untested" when the logic
is in `Edit`, `Build`, `Workspace` and `Prove`.

The compose wiring is pinned in `orchestrator/src/test/java/tech/mikhailov/fsm/orch/DeploymentTest.java`
— `mvn -pl orchestrator test -Dtest=DeploymentTest` from `pipeline/`. That is where "the checkouts live
in a named volume at the path the process is told to use", "the mode and the prover's address are
pass-through together" and "the Maven mirror is settable on the running container" live, because none of
them is a statement about what any code computes, and nothing else checks the compose file at all.
