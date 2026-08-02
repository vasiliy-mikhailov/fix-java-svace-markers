# fsm-runner

The prover's hands: clone a repository, write the reproducer test, build **RED**, apply the fixer's edits,
build **GREEN**, and answer with what happened. It decides nothing — every judgement about the result
belongs to `engine`.

This is `java-runner/src/server.js` in Java, route for route. **That JavaScript was deleted on
2026-07-31.** What it ANSWERED was kept: 23 851 cases and the replies it gave are frozen under
`harness/fixtures` as data, and the comparison against them is now a JUnit test
(`DifferentialHarnessTest`) that runs on every `mvn test` — where before it was a shell script nothing
invoked. `harness/README.md` states what that freeze buys, what it costs, and why the fixtures cannot
be regenerated.

**THE CUTOVER IS DONE.** `deploy/docker-compose.yml` declares three services — `engine`, `orchestrator`,
`runner` — and `java-runner` is no longer one of them. The comparison table below is history: it
records what was swapped and why, and the "Cutover" section is kept as the account of how it was done,
not as a procedure to run. Anything in it that names `fsm-java-runner`, `fsm-n8n` or `fsm-dashboard` as
a *live* container is stale.

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
than by an absent line. `PathEncoding`, `runner/Dockerfile` and `PathEncodingTest` carry the detail; the
first line of the log reports `sun.jnu.encoding=` and warns if it is not UTF-8.

---

## Build

**The build context is the reactor root (`pipeline/`), not this directory.** `runner` depends on
`tech.mikhailov.fsm:engine` and resolves it from the reactor, because nothing publishes that jar to any
repository. A context of `./runner` cannot build the image at all, and the failure arrives minutes in as an
unresolvable dependency.

```bash
cd pipeline/deploy
docker compose build runner                    # Maven Central; works on a fresh clone anywhere
```

Through the Nexus mirror instead — worth it on the deployment host, where a cold build is a few hundred
artifacts — the build itself has to be **on the `mvn-cache` network**, and that is not something
`docker compose build` can ask for. So build it by hand under the tag compose already expects:

```bash
cd pipeline
DOCKER_BUILDKIT=0 docker build --network mvn-cache -f runner/Dockerfile \
  --build-arg MAVEN_MIRROR_URL=http://nexus:8081/repository/maven-public/ \
  -t fsm-runner:latest .
docker compose -f deploy/docker-compose.yml up -d runner    # uses the tag; does not rebuild
```

`--network <name>` is the classic builder's, hence `DOCKER_BUILDKIT=0`. That also leaves `TARGETARCH`
unset, which is why the Dockerfile reads it as `${TARGETARCH:-amd64}` — correct on this host, and correct
under BuildKit on a developer's arm64 machine, where java-runner's hardcoded `linux/x64` built happily and
then died at run time with "exec format error".

`MAVEN_MIRROR_URL` behaves exactly as it does for `engine` and `orchestrator`: **opt-in, defaulting to
Central**, because a baked-in `http://nexus:8081` mirror would make the image unbuildable everywhere —
including on the host that has Nexus. The *run-time* mirror is the opposite case and is unconditional; see
[The mirror](#the-mirror-is-not-optional).

Three more things the build does on purpose:

- **Tests run in the image build**, both modules'. The runner's suite is the ported specification of the
  one distinction the whole pipeline rests on — did the test *run* and fail, or did it never run.
  `--build-arg SKIP_TESTS=-DskipTests` exists only to reproduce a build whose failure you are already
  looking at.
- **All four module poms are copied**, including `orchestrator/pom.xml`, which this image never compiles.
  Maven builds the model for every module the aggregator lists before `-pl` selects anything, so a missing
  one fails the build before it starts with `Child module /src/orchestrator of /src/pom.xml does not exist`.
  A module added to `pipeline/pom.xml` is a `COPY` line added to `runner/Dockerfile` and to
  `orchestrator/Dockerfile`.
- **`fsm-runner.jar` is a THIN jar**, and the image ships `/app/libs` beside it. This module has no shade
  plugin and no spring-boot repackage, so the jar holds `tech.mikhailov.fsm.runner.*` and nothing else —
  `java -jar fsm-runner.jar` dies on the first line of `main` with
  `NoClassDefFoundError: tech/mikhailov/fsm/lib/Json$JsonException`, because `Json`, `Js.string` and
  `JsText.isSpace` are the engine's. The build therefore runs `dependency:copy-dependencies` **in the same
  Maven invocation** as `package` (a separate one cannot resolve `engine`: it comes from the reactor and
  nothing ran `install`), and the entrypoint is `-cp … tech.mikhailov.fsm.runner.Runner`.

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

```bash
cd pipeline/deploy
docker compose up -d runner                    # alongside java-runner; nothing is switched over yet
docker compose exec runner curl -fsS http://127.0.0.1:8090/health
# {"ok":true,"jdks":["8","11","17","21","25"]}
```

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

### The mirror is not optional

`runner/settings.xml` sets `mirrorOf=*`. That makes the Nexus on the `mvn-cache` network **the only
repository Maven will talk to** — not a cache in front of Central. Off that network the container starts,
answers `/health` with all five JDKs, clones happily, and then fails every RED build with hundreds of
lines about unresolvable artifacts, which reads as a broken target repository rather than as a missing
word in a compose file. The orchestrator's `DeploymentTest` pins the network for that reason. To check
it from the running container:

```bash
docker compose exec runner curl -s -o /dev/null -m 20 -w '%{http_code}\n' \
  http://nexus:8081/repository/maven-public/
# any status at all means the route exists — which is the only thing being asked
```

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

> **Completed. Kept as the record, not as instructions.** Two of the three callers below no longer
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
docker exec fsm-orchestrator curl -fsS http://fsm-runner:8090/health   # resolvable by its callers
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

> **Do not write `FSM_RUNNER_URL=${FSM_RUNNER_URL:-}`**, the shape the engine's variable uses on the
> `n8n` service. That is safe there because the shim is JavaScript and `'' || default` is the default. It
> is *not* safe here: Spring resolves `${FSM_RUNNER_URL:http://fsm-java-runner:8090}` against a variable
> that exists and is empty, so `fsm.runner.base-url` becomes `""`. `HttpRunnerClient` happens to guard a
> blank and falls back to `fsm-java-runner`; `SourceWindowService` does not, and ends up with a base URL
> of `""`. The result is a split brain: proves go to the **old** runner while every source window in the
> dashboard says "source unavailable". Set a real value or leave the line out entirely.

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
| `Dockerfile` | multi-stage; reactor build, then the full JDK/Maven toolchain because `/run_test` shells out |
| `settings.xml` | the **run-time** Maven mirror, `mirrorOf=*` → `nexus:8081`. Its own copy, not java-runner's, so java-runner can be deleted |
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
mvn -B -pl runner -am test                                   # engine, then runner's 203
mvn -B -pl runner -am test-compile \
  && mvn -B -pl runner org.pitest:pitest-maven:mutationCoverage   # report: runner/target/pit-reports/
( cd runner && sh harness/run.sh )                           # …and print the differential report
```

PIT needs the `test-compile` first — invoked as a bare goal it does not run the lifecycle. `Runner` is
excluded from mutation: it is an environment read, a bind and a shutdown hook, so its mutants are only
killable by an integration test and a low score there would read as "the logic is untested" when the logic
is in `Edit`, `Build`, `Workspace` and `Prove`.

The compose wiring is pinned in `orchestrator/src/test/java/tech/mikhailov/fsm/orch/DeploymentTest.java`
— `mvn -pl orchestrator test -Dtest=DeploymentTest` from `pipeline/`. That is where "the runner must be
on `mvn-cache`" and "this runner must not adopt the retired java-runner cache" live, because neither is a
statement about what any code computes. It used to be pinned from the Node side as well, in
`n8n/agentic/test/compose.test.js`; that tree is deleted and every assertion it made was moved into
`DeploymentTest` first.
