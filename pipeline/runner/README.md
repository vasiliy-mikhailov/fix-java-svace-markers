# fsm-runner

The prover's hands: clone a repository, write the reproducer test, build **RED**, apply the fixer's edits,
build **GREEN**, and answer with what happened. It decides nothing — every judgement about the result
belongs to `engine`.

This is `java-runner/src/server.js` in Java, route for route. **That JavaScript was deleted on
2026-07-31.** What it ANSWERED was kept: the 23 851 cases it generated and the replies it gave are
frozen under `harness/fixtures` as data, and the comparison against them is now a JUnit test
(`DifferentialHarnessTest`) that runs on every `mvn test`. **23 401 of them are replayed today** — the
450-case `lease` family went with `/lease` and `Lease.java` on 2026-08-01, and `DifferentialHarnessTest`
hardcodes 23 401 so a corpus that quietly lost cases cannot self-certify — where before it was a shell script nothing
invoked. `harness/README.md` states what that freeze buys, what it costs, and why the fixtures cannot
be regenerated.

> ## THIS IS A LIBRARY NOW, NOT A SERVICE — read this before the rest of the file
>
> There is **no `fsm-runner` container**. `deploy/docker-compose.yml` declares one service, `fsm`, and
> the prove runs inside it: `tech.mikhailov.fsm.runner.LocalRunner` is the same `Prove`, `Workspace` and
> `Build` behind the same single FIFO build thread, called in-process by the orchestrator's
> `LocalRunnerClient`. `RunnerServer` is unchanged and still wraps `LocalRunner` over HTTP — set
> `FSM_RUNNER_MODE=http` and everything below about ports, routes and reply shapes is live again.
>
> **Why.** The split cost an ADDRESS: `http://fsm-runner:8090` written in the compose environment, in
> `application.yml` and compiled into `HttpRunnerClient.DEFAULT_BASE_URL`, each a fallback for the one
> above it, none read until a marker is proved. A stale value anywhere in that chain surfaced hours into
> a 6-26 hour run as a refused connect filed as an infrastructure failure — which reads as a runner that
> is down rather than as a name nothing serves.
>
> **Two things below are now WRONG as written**, and are kept because the reasoning around them is not:
> - `runner/Dockerfile` and `runner/settings.xml` are **deleted**. There is one `pipeline/Dockerfile`;
>   its runtime stage is this one's, JDK for JDK. The mirror is `MAVEN_MIRROR_URL` at RUN time
>   (`MavenSettings`) — empty means Central — instead of a `mirrorOf=*` settings.xml baked into the
>   image, which made every prove fail on every machine but one.
> - the process line is `env LC_ALL=C.UTF-8 java -jar /app/fsm.jar`, a Spring Boot fat jar, rather than
>   `-cp` over a thin jar and `/app/libs`. Everything the paragraph below says about `env` and the
>   locale is unchanged and still load-bearing.
>
> **THE 2026-07-31 CUTOVER IS DONE.** `java-runner` (Node) is gone. The comparison table below is
> history: it records what was swapped and why, and the "Cutover" section is the account of how it was
> done, not a procedure to run. Anything naming `fsm-java-runner`, `fsm-n8n` or `fsm-dashboard` as a
> *live* container is stale.

| | `fsm-java-runner` | `fsm-runner` |
|---|---|---|
| service / image | `java-runner`, `fsm-java-runner:latest` | `runner`, `fsm-runner:latest` |
| process | `node /app/src/server.js` | `env LC_ALL=C.UTF-8 java -cp /app/fsm-runner.jar:/app/libs/* tech.mikhailov.fsm.runner.Runner` |
| port | 8090 | 8090 |
| routes | `/health` `/fs/read_file` `/run_test` | identical, less `/lease` (see below) |
| toolchain in the image | JDKs 8/11/17/21/25 + Maven | identical |
| `/cache` | `fsm-java-runner-cache` | **`fsm-runner-cache`** — a new, empty volume, see below |
| the clone credential | in each checkout's `.git/config` | in a git credential helper, on disk nowhere |

**Why the process line begins with `env`.** `java.nio.file.Path` spells filenames in `sun.jnu.encoding`,
which comes from the process's locale and *cannot* be set with a `-D` — the JDK overwrites the command
line with the platform's value. debian-slim has no locale, so without this the encoding is
`ANSI_X3.4-1968` and no path outside ASCII can be named from Java at all: an edit aimed at
`…/Café.java` came back "file not found", a non-ASCII `test_path` ended the prove with a raw
`InvalidPathException`, and `/fs/read_file` told the dashboard "source unavailable" for a file in the
checkout. The variable is on the exec line and **not** an image `ENV`, and `Proc` strips `LANG`,
`LANGUAGE` and every `LC_*` out of every child, so no build of any repository under test inherits it —
that isolation is the whole reason the image declares no locale, and it is now enforced by code rather
than by an absent line. `PathEncoding`, `pipeline/Dockerfile` and `PathEncodingTest` carry the detail; the
first line of the log reports `sun.jnu.encoding=` and warns if it is not UTF-8.

---

## Build

**The build context is the reactor root (`pipeline/`), not this directory.** `runner` depends on
`tech.mikhailov.fsm:engine` and resolves it from the reactor, because nothing publishes that jar to any
repository. A context of `./runner` cannot build the image at all, and the failure arrives minutes in as an
unresolvable dependency.

**There is no image of this module's own.** `runner/Dockerfile` was deleted when the three services
became one; this module is compiled into `pipeline/Dockerfile`'s single image, whose runtime stage is
this one's old one JDK for JDK. So the build command is the deployment's:

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
under BuildKit on a developer's arm64 machine, where java-runner's hardcoded `linux/x64` built happily and
then died at run time with "exec format error".

`MAVEN_MIRROR_URL` behaves exactly as it does for `engine` and `orchestrator`: **opt-in, defaulting to
Central**, because a baked-in `http://nexus:8081` mirror would make the image unbuildable everywhere —
including on the host that has Nexus. The *run-time* mirror is a separate, equally opt-in setting; see
[The mirror](#the-mirror-is-optional-and-that-reversal-was-the-fix).

Three more things the build does on purpose:

- **Tests run in the image build**, both modules'. The runner's suite is the ported specification of the
  one distinction the whole pipeline rests on — did the test *run* and fail, or did it never run.
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
yourself: `deploy/docker-compose.yml` declares no `runner` service and there is no `fsm-runner:latest`
image any more. You do not need one. The single image already carries this module, inside the
orchestrator's Spring Boot fat jar, and Boot's `PropertiesLauncher` will start any main class in it:

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

Read that line rather than trusting the command: it prints the URI the client actually resolved. For one
revision `FSM_RUNNER_URL` was **not** listed under `environment:` in the compose file while
`FSM_RUNNER_MODE` was, so this pair switched the mode and silently dropped the address, and every prove
went to `HttpRunnerClient.DEFAULT_BASE_URL` — `http://fsm-runner:8090`, which nothing serves.
`DeploymentTest` now pins the two to travel together.

`jdks` is the list actually **on disk**, not the list the code knows about. A JDK missing from that reply
is a download that failed in the image build, and it matters: `/run_test` retries onto the major a build
demands, so a missing 25 means WebGoat is recorded as unreproducible on a JDK it was never going to
compile under.

| variable | default | what it is |
|---|---|---|
| `PORT` | `8090` | `RunnerServer.DEFAULT_PORT`. Not a preference: n8n's generated workflow has `:8090` inlined in six places and `HttpRunnerClient.DEFAULT_BASE_URL` names it too. A value that is not a number makes the process **refuse to start**, rather than bind a random port and look healthy (`Number(process.env.PORT)` gave `NaN`, and Node bound a random port). |
| `BIND` | `0.0.0.0` | Leave it. The service publishes no ports; loopback would make it unreachable from every other container while looking perfectly healthy from inside its own. |
| `CACHE` | `/cache` | Where the clones go. The image prepares this path and compose mounts a named volume on it. |
| `GITHUB_TOKEN` | *(unset)* | The clone credential. Absent is legitimate — public repositories clone without one, and the failure when it *is* needed is a git error inside `error`, not a crash. |

No `QWEN_*`, no `SVACE_*`: this service never calls a model. It has one secret and one job.

### The mirror is optional, and that reversal was the fix

**This section used to say the opposite, and the opposite was the defect.** `runner/settings.xml` set
`mirrorOf=*` at `http://nexus:8081/…` and the image copied it in unconditionally. A mirror of `*` is not
a cache in front of Central — it is **the only repository Maven will talk to**. So on every machine
without that Nexus on that Docker network the container started, answered `/health` with all five JDKs,
cloned happily, and then failed every RED build with hundreds of lines about unresolvable artifacts,
which reads as a broken target repository rather than as an image carrying somebody else's
infrastructure.

`runner/settings.xml` is **deleted**. The mirror is now `MAVEN_MIRROR_URL`, read at RUN time by
`MavenSettings`:

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

There is no longer an `mvn-cache` network to be on, and `DeploymentTest` no longer pins one — the
Nexus is reached by address, not by shared bridge.

---

## The `/cache` volume

**`fsm-runner` starts on a new, empty volume (`fsm-runner-cache`) rather than sharing
`fsm-java-runner-cache`.** Two independent reasons, either of which is sufficient:

**1. The volume holds leaked credentials, and this service would adopt them.** Every clone the JS made
used a `https://<token>@github.com/…` URL, and git writes that verbatim into the new repository's
`.git/config` as `remote.origin.url`. The Java port does not do that — `Workspace.CREDENTIAL_HELPER`
hands the token to git through a one-shot `GIT_CONFIG_COUNT` helper that leaves nothing behind, and
`/fs/read_file` now refuses any path with a dot-named component at any depth, links resolved, so
`.git/config` cannot be read back out through the port either. But **the port adopts an existing checkout
instead of re-cloning it**: `prepareWs` takes any directory that has a `.git`, and `prepareFs` takes any
tree whose `HEAD` resolves. Neither rewrites `remote.origin.url`. Sharing the volume would therefore carry
those tokens forward indefinitely — surviving a rotation of `GITHUB_TOKEN` in `.env`, because nothing
would ever look at that config again. Starting empty is what retires them, and
`docker volume rm fsm_fsm-java-runner-cache` after the cutover is what deletes the copies.

**2. Two processes, no lock.** `/run_test` is serialised inside **one** process — a single-thread FIFO
build queue — because there is one cached workspace per repository and two proves would `reset --hard`
and patch each other's tree, then both report about a file neither of them wrote. That guarantee is
per-process. While both services are up (which is the whole point of adding one rather than editing one),
a shared `/cache` would remove the only mutual exclusion that exists, during exactly the window this
arrangement is meant to make safe.

There is also a third, smaller reason: the image runs as an unprivileged uid and owns `/cache`, which
works because Docker seeds an *empty* named volume from the image's directory, ownership included. The
existing volume's trees are root-owned and would fail every write.

**The cost is real and is paid once.** A cold re-clone per repository, minutes each through the mirror,
and the first prove of each repository loses the `target/` (and the accumulated local repository) that
makes the second build of it minutes instead of most of an hour. On a 282-marker run over a couple of
dozen repositories that is roughly one slow first pass. `Workspace.keyFor` is unchanged — the same
`sha1("<repo>@<branch>")` truncated the same way — so this is a *choice* to start clean, not a
consequence of the port; pointing the service at the old volume would in fact work, which is why the
decision is written down here and pinned by a test.

Everything else about the mount is java-runner's semantics unchanged: a named volume, not a bind mount
(a couple of dozen cloned repositories do not belong inside a git worktree), holding both the patched
build workspaces at `/cache/<key>` and the read-only checkouts at `/cache/fs/<key>` that the dashboard's
marker view and the orchestrator's source window read through this service.

---

## The contract

Five routes on one handler, matched by **exact** path (`com.sun.net.httpserver` matches contexts by
*prefix*, so a context per route would make `POST /run_test/oops` silently start a build). Everything is
`200` except a body that is not JSON (`400`), a body over 16 MB (`413`) and a path or method that does not
exist (`404`). **A build that failed answers `200` with `{"ok": false, "error": …}`**, because that is an
*answer about a marker*: the orchestrator's `RunnerClient` treats a non-2xx as "nothing was learned" and
puts the marker back untouched, so promoting a build failure to a `500` would erase the reason from the
run history.

`GET` is answered without reading a body and never routes to a POST endpoint, so `POST /health` and
`POST /health` is a `404`. Preserved from the JS; the healthcheck and every `curl` in the operators'
notes use `GET /health`.

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

`test_class`, `test_path` and `test_code` are **refused** when absent rather than coerced, and that is the
port's one deliberate change of shape. The JS interpolated whatever it got, so a missing `test_class`
produced `-Dtest=undefined`, which matches nothing. The natural Java coercion produces an empty
`-Dtest=`, which runs the repository's **entire suite** — and on a project with failing tests of its own
the red build would then report failures and the marker would come back `red_reproduced: true` having
never been tested. A fabricated reproduction is the one answer this service must never give.

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

Field names and field **order** are the JS object's: two services read this reply and one of them —
n8n's shim — passes it through untouched into a Data Table row a human diffs.

`source` says where the numbers came from. This run's JUnit XML wins over console scraping whenever it
produced any (which is what makes Gradle work), and stale reports from an earlier build in the cached
workspace are gated out by mtime — without that, a previous run's green report reads as this run's and
turns "the build did not run" into "the fix works".

### The rest

| route | request | reply |
|---|---|---|
| `GET /health` | — | `{"ok": true, "jdks": ["8","11","17","21","25"]}` |
| `POST /fs/read_file` | `{"repo": "…", "branch": "main", "path": "src/main/java/…/A.java"}` | `{"path": …, "content": …, "truncated": false}` or `{"error": "…"}` |

`/lease` and `/lease/release` **were deleted on 2026-08-01**, together with `Lease.java`, its 35 tests
and the harness's 450-case `lease` family. They existed because two n8n schedule ticks could race over
one workspace; n8n is gone, and Spring Batch gives the orchestrator single-flight structurally, so
nothing acquired them. `migrate-to-spring.sh`'s lease probe went with them — with nothing holding the
lease it would have reported "free" while a prove was running, which is a false negative on the exact
condition a pre-flight exists to detect.

`/fs/read_file` never throws for a bad request: a failed clone, an escaping path, a refused path and a
missing file all come back as `{"error": …}` with a `200`, because the caller renders "source unavailable
— *reason*" in a tab whose other four panes are fine. It serves **source**, not the checkout: nothing
under a dot-named component is served, at any depth, with links resolved.

---

## Cutover

> **COMPLETED, AND NOT A PROCEDURE. Do not run anything in this section.** Every `docker compose`
> command below names a service that no longer exists — `runner`, `java-runner`, `orchestrator`,
> `dashboard`, `n8n` — and each will fail with *no such service*. `deploy/docker-compose.yml` declares
> ONE running service, `fsm`. This is kept as the record of how the cutover was done and what it cost,
> because the reasoning is still what stops the next one going wrong; the commands are an artefact of a
> stack that is gone. Two of the three callers below no longer
> exist: `fsm-dashboard` and `fsm-n8n` were deleted from the deployment along with `java-runner`, and
> the orchestrator is the only caller left. Its default is now `http://fsm-runner:8090` in all three
> places that state it — `application.yml`, `HttpRunnerClient.DEFAULT_BASE_URL` and the compose
> `FSM_RUNNER_URL` line — and `DeploymentTest` pins them to each other and to a service the compose
> file declares. The retired volume `fsm_fsm-java-runner-cache` still exists on the host and is what
> `docker volume rm` retires; nothing in the stack mounts it.

Three callers addressed the runner, and **they resolved it three different ways** — which is the whole
reason this section is long. There was no single switch.

| caller | how it resolves the runner | what to change |
|---|---|---|
| `fsm-orchestrator` | `fsm.runner.base-url`, i.e. `FSM_RUNNER_URL`, default `http://fsm-java-runner:8090` (`application.yml`, `HttpRunnerClient.DEFAULT_BASE_URL`) | an environment line on the service |
| `fsm-dashboard` | `JAVA_RUNNER`, already an environment line, default `http://fsm-java-runner:8090` (`dashboard/src/server.js`) | the value of that line |
| `fsm-n8n` | **nothing at run time.** `const RUNNER = 'http://fsm-java-runner:8090'` in `n8n/agentic/src/gen-prover.js`, *inlined into `workflow_prover.json` at generation time* — six URLs: two `/lease`, two `/lease/release`, two `/run_test` | the constant, then regenerate **and redeploy the workflow** |

The n8n row is the trap. Unlike the engine — which every shim reads as
`$env.FSM_ENGINE_URL || 'http://fsm-engine:8092'`, so a compose variable moves it — the runner's URL is
**baked into the workflow JSON**. A cutover done entirely in `docker-compose.yml` leaves n8n's prover
posting to `fsm-java-runner` while the orchestrator posts to `fsm-runner`: both runners then proving, on
two workspaces, with no lock between them, and nothing anywhere red.

### 0. Stop proving first

**Swapping the runner kills an in-flight prove.** Every step below either recreates a container or
restarts n8n, and a prove is a 20-to-90-minute Maven build held open by one HTTP request:

- `docker compose up -d orchestrator` recreates the container: `SIGTERM`, the context shuts down, the
  request is abandoned.
- `agentic/deploy.sh` restarts `fsm-n8n` — it says so itself — which kills whatever the prover was
  waiting on.
- stopping or bypassing the runner mid-build abandons the build itself. `Runner`'s shutdown hook
  interrupts the build thread, which kills the child Maven rather than orphaning one that owns the
  workspace.

**Nothing is corrupted.** A marker mid-prove goes back to `new` with its attempt count untouched, and the
Data Table rows / H2 records survive. What is lost is the wall clock — up to 90 minutes of the one
serialised workspace. So drain first, or accept the loss deliberately:

```bash
cd pipeline/deploy
docker compose logs --tail=50 runner java-runner    # is a build in progress?
docker compose logs --tail=50 orchestrator | grep -i 'run_test\|proving'
```

Then make sure **exactly one** prover is scheduled, which is a pre-existing rule and not new to this
cutover (`orchestrator/README.md`, "Cutting over from n8n"): either deactivate the n8n `prover` workflow
and leave `FSM_PROVE_SCHEDULE=true`, or set `FSM_PROVE_SCHEDULE=false` and drive the orchestrator by
`POST` only. Two provers on one runner patch each other's tree; two provers on two runners do it twice as
quietly.

### 1. Bring the new runner up alongside the old one

```bash
cd pipeline/deploy
docker compose build runner        # or the mirrored by-hand build above, on the deployment host
docker compose up -d runner        # java-runner keeps running and keeps its warm cache
docker compose exec runner curl -fsS http://127.0.0.1:8090/health
# {"ok":true,"jdks":["8","11","17","21","25"]}   <- all five, or stop here
docker compose exec runner curl -s -o /dev/null -m 20 -w '%{http_code}\n' \
  http://nexus:8081/repository/maven-public/     # any status; 000 means it is off mvn-cache
docker exec fsm curl -fsS http://fsm-runner:8090/health   # resolvable by its callers
```

Nothing is switched over yet. A runner nobody posts to does nothing.

### 2. Point the orchestrator at it

`FSM_RUNNER_URL` is **not** in the orchestrator's `environment:` list today, because it has never needed
to move. Add it as a literal:

```yaml
  orchestrator:
    environment:
      - FSM_RUNNER_URL=http://fsm-runner:8090      # remove this line to roll back
```

```bash
docker compose up -d orchestrator                  # a recreate — see step 0
docker compose logs orchestrator | grep '\[runner\]'
#   [runner] http://fsm-runner:8090/run_test, up to 5400s per prove, 3 connect attempt(s)
```

That line is the confirmation — `ClientConfig` logs the URI the client actually resolved, so it says
where proves are going rather than what the environment was meant to say.

> **`FSM_RUNNER_URL=${FSM_RUNNER_URL:-}` was once unsafe here, and is now the shape the compose file
> uses.** Spring resolves `${FSM_RUNNER_URL:http://fsm-runner:8090}` against a variable that exists and
> is empty, so `fsm.runner.base-url` becomes `""` rather than the default. That used to split the brain:
> `HttpRunnerClient` guarded the blank and fell back, while `SourceWindowService` re-derived the address
> itself and did not — proves went to the runner and every source window said "source unavailable".
>
> **Both halves now agree.** `SourceWindowService` no longer holds an address at all; it takes a
> `SourceReader` chosen in `ClientConfig` beside the prove client, and `HttpSourceReader` guards blank
> against the same `HttpRunnerClient.DEFAULT_BASE_URL`. So an empty value degrades to one default in both
> places, which is what makes the pass-through safe — and the pass-through is what makes
> `FSM_RUNNER_MODE=http` addressable at all.

### 3. Point the dashboard at it

```yaml
  dashboard:
    environment:
      - JAVA_RUNNER=http://fsm-runner:8090
```

```bash
docker compose up -d dashboard
```

This must be the **same** runner the prove posted to, or a reviewer is shown source from a different
checkout than the one that was judged — which is the entire reason the dashboard reads source through the
runner instead of fetching it from GitHub again.

### 4. Nothing else to point at it

The orchestrator reads `FSM_RUNNER_URL` (default `http://fsm-runner:8090`, and `DeploymentTest` pins
that default against a service the compose file actually declares). There is no third place to edit:
n8n used to carry the runner's URL *inlined into generated workflow JSON*, so swapping the runner meant
regenerating and redeploying a workflow. n8n is gone and so is that step.


### 5. Prove one marker, then retire the old service

```bash
curl -sS -XPOST http://localhost:8085/api/prove/marker -H 'Content-Type: application/json' \
     -d '{"dedup_key":"WebGoat/WebGoat|src/main/java/…/A.java|42|DEREF_OF_NULL"}'
```

Single-quote the JSON — the key contains `|`. Watch `docker compose logs -f runner`: the first prove of a
repository on the fresh volume is a cold clone plus a cold Maven resolve, so expect it to be slow. What
you are checking is `red_reproduced` / `green_passed` on a marker whose previous verdict you know.

When it has held for a full drain:

```bash
docker compose stop java-runner            # reversible; the cache is still there
# …and once you are sure. THIS is what actually deletes the leaked tokens:
docker compose rm -sf java-runner          # remove the container, not the stack
docker volume rm fsm_fsm-java-runner-cache
```

`docker compose rm -sf java-runner`, **not `docker compose down`** — `down` takes n8n, the engine and the
orchestrator with it, which is another in-flight prove for no reason. A volume can be removed as soon as no
container is holding it.

Then delete the `java-runner` service and the `fsm-java-runner-cache` volume from `docker-compose.yml` in
one commit (the guard test in `compose.test.js` names `java-runner`, so it comes out in the same commit),

### Rollback

Every step is reversible, and cheaply, because `java-runner` is still up with a **warm** cache — rolling
back is a container recreate, not a re-clone.

1. remove the `FSM_RUNNER_URL` line from the `orchestrator` service → `docker compose up -d orchestrator`
   (its default is `http://fsm-java-runner:8090`).
2. set `JAVA_RUNNER=http://fsm-java-runner:8090` on `dashboard` → `docker compose up -d dashboard`.
3. revert `RUNNER` in `agentic/src/gen-prover.js`, `node src/gen-prover.js`, `./deploy.sh workflow_prover.json`.
4. leave `fsm-runner` running or `docker compose stop runner`; **keep `fsm-runner-cache`** so a second
   attempt does not re-clone everything again.

Roll back in that order — orchestrator first — so that at no point are two provers posting to two
different runners. And note that step 1 and step 3 each kill an in-flight prove, exactly as the forward
cutover does.

---

## Layout

| path | what |
|---|---|
| `pom.xml` | module: Java 25, no third-party runtime dependencies, one on `engine` for the JavaScript semantics (`Json`, `Js.string`, `JsText.isSpace`) |
| ~~`Dockerfile`~~ | **deleted.** One image is built from `pipeline/Dockerfile`, whose runtime stage is this one's, JDK for JDK |
| ~~`settings.xml`~~ | **deleted.** The run-time mirror is `MAVEN_MIRROR_URL` (`MavenSettings`); a baked `mirrorOf=*` → `nexus:8081` made every prove fail off one network |
| `…/MavenSettings.java` | writes the `settings.xml` handed to every `mvn` with `-s`, from `MAVEN_MIRROR_URL`; unset means Central |
| `…/LocalRunner.java` | the in-process prover the orchestrator calls: same `Prove`, same single FIFO build thread, no HTTP |
| `src/main/java/…/Runner.java` | entrypoint: environment, cache, bind, shutdown hook |
| `…/RunnerServer.java` | the five routes, the virtual-thread executor, the single-thread build queue |
| `…/Prove.java` | `doRunTest`: write the test, RED, apply, GREEN |
| `…/Build.java` | what a build *did* — the JUnit-XML-over-console rule, and the JDK retry |
| `…/Edit.java` | `applyEdit` / `fixTarget`: the structural allowlist that stops a fix editing its own test |
| `…/Workspace.java` | the two clones, the cache key, the credential helper, `/fs/read_file` |
| `…/Proc.java` | the `execFile` wrapper every `git`/`mvn`/`./gradlew` goes through; never throws |
| `harness/` | the FROZEN JavaScript answers (`fixtures/`), the catalogue the test asserts, and `README.md`, which is honest about what freezing them cost |

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
— `mvn -pl orchestrator test -Dtest=DeploymentTest` from `pipeline/`. That is where "this runner must not
adopt the retired java-runner cache", "the checkouts live in a named volume at the path the process is
told to use" and "the mode and the runner's address are pass-through together" live, because none is a
statement about what any code computes. **"The runner must be on `mvn-cache`" is gone** — there is no
runner service and no `mvn-cache` network; the mirror is an address (`MAVEN_MIRROR_URL`), pinned instead
by `theMavenMirrorIsSettableOnTheRunningContainer`. It used to be pinned from the Node side as well, in
`n8n/agentic/test/compose.test.js`; that tree is deleted and every assertion it made was moved into
`DeploymentTest` first.
