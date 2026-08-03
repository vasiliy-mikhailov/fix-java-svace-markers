# fix-java-svace-markers

Turns a Svace static-analysis report into one of two things per marker:

1. **A proven fix** — a JUnit test that fails red on the unpatched code and goes green with the patch,
   plus a **drafted** pull request. Nothing is ever pushed or opened automatically.
2. **A written verdict** — an argued explanation of why the marker is a false positive, by design, or
   could not be proven, when no failing test can be produced.

Every marker ends in one or the other. On the reference run — WebGoat, 282 markers from a 356-row Svace
report — that was 87 drafted PRs and 282 written verdicts, in about 28 hours unattended.

It is **all Java**. **One container**, one command.

---

## What you need

- **Docker** — that is the whole runtime requirement. You do not need Java or Maven to *run* it.
- **JDK 25 + Maven** only if you want to build or test outside a container.
- An **OpenAI-compatible model endpoint** (this deployment uses vLLM serving Qwen).
- A **GitHub token** with read access to the repositories being analysed. It is only ever used to clone
  and read; nothing is pushed.

---

## Quickstart

```bash
git clone git@github.com:vasiliy-mikhailov/fix-java-svace-markers.git
cd fix-java-svace-markers/pipeline/deploy

cp .env.example .env         # then fill in QWEN_* and GITHUB_TOKEN — see "Configuration" below
docker compose up -d --build # one image, one container

curl -s localhost:8085/healthz          # -> ok
```

Ingest a Svace report and start proving:

```bash
curl -s -X POST localhost:8085/api/ingest -H 'Content-Type: application/json' -d '{
  "csvPath": "/data/data/svace/webgoat-markers-356.csv",
  "repo": "WebGoat/WebGoat",
  "branch": "main",
  "pathPrefix": "src/main/java/"
}'

curl -s -X POST localhost:8085/api/prove
```

Then open **http://localhost:8085/** — the dashboard shows every marker, its verdict, the test that was
written, the fix diff and the drafted PR body.

> **No git, no build, or no route to Maven Central on the target machine?**
> [DOCKER.md](DOCKER.md) covers two alternatives to building from source: handing over a saved image
> (`docker save` / `docker load`, ~1.8 GB, no build and no source), and a registry. It also has the
> model-endpoint and volume traps in one place.

The report is a four-column CSV: `Severity,Checker,File,Line`. Put yours under `data/svace/`; the
container reads it from `/data/data/svace/`.

---

## One container

| | what it does | port |
|---|---|---|
| **`fsm`** | Everything. The Spring Batch job that drives each marker, the dashboard, every REST endpoint, the H2 database, the five model calls — **and the prover**: it clones the target repo, writes the test, applies the patch and runs Maven twice (red, then green). Ships JDK 8/11/17/21/25 + Maven + git. | 8085 |

`engine` and `runner` are **libraries inside that process**, not services: there is no HTTP hop between
the queue and the judgement, and none between the queue and the prover. That is deliberate. An address
between them is read only when a marker is proved, so a wrong one does not surface until hours into a run,
and it surfaces as a refused connect filed as an infrastructure failure — which reads as a prover that is
down rather than as a name nothing serves.

Two things are still available and neither is on by default:

```bash
# the judgement over HTTP — replay ONE stage against a request you wrote by hand,
# without a 6-26 hour run around it
docker compose --profile engine up -d engine     # localhost:8092

# the prover in a container of its own, if you want a boundary between this
# pipeline's credentials and the build scripts of the repos under analysis.
# You supply the runner: the SAME image starts one, because the fat jar carries it.
docker run -d --name fsm-split-runner --network fsm_default -e PORT=8090 -e CACHE=/cache \
  --entrypoint env fsm:latest LC_ALL=C.UTF-8 java \
  -Dloader.main=tech.mikhailov.fsm.runner.Runner \
  -cp /app/fsm.jar org.springframework.boot.loader.launch.PropertiesLauncher
FSM_RUNNER_MODE=http FSM_RUNNER_URL=http://fsm-split-runner:8090 docker compose up -d
docker compose logs fsm | grep '\[runner\]'   # confirms the URI actually resolved
```

### What happens to one marker

```
Prep → Fetch source → Reproducer (LLM) → Parse test → run_test EXPECT RED
     → Fixer (LLM) → Parse fix → run_test EXPECT GREEN
     → Fix skeptic (LLM) → PR maker (LLM) → Record outcome → Verdict (LLM) → write
```

Five model calls. A marker only reaches `verified` if the runner observed a real failing test before the
patch and a real passing one after — `red_verified`, `green_verified`, `proven`, parsed from JUnit XML,
not from console text.

---

> **A worked example** — the WebGoat Svace report, the 282 verdicts it produced, and what to expect
> if you run it yourself: [examples/webgoat/](examples/webgoat/).

## Where the judgement actually lives

**`prompts/*.txt` at the repo root** — `reproducer`, `fixer`, `fix-skeptic`, `pr-maker`, `verdict`. Edit
these to change behaviour; they are mounted into the container and read at boot. The startup log tells
you which came from a file and which fell back to a compiled-in default:

```
[prompts] reproducer <- FILE /data/prompts/reproducer.txt (2895 chars)
```

**`pipeline/engine/src/main/java/tech/mikhailov/fsm/nodes/`** — the ten decision classes. They are pure
functions over maps with no I/O, which is why they have 901 tests and why you can call one from a unit
test without a container.

---

## Running it in anger

```bash
# one named marker, no scheduler — the debugging path.
# The key goes in the BODY, not the query string: it contains `|` and `/`.
curl -s -X POST localhost:8085/api/prove -H 'Content-Type: application/json' \
  -d '{"dedup_key":"WebGoat/WebGoat|src/main/java/org/owasp/A.java|42|DEREF_OF_NULL"}'

# drain the whole backlog
curl -s -X POST localhost:8085/api/prove

# where it is up to
curl -s localhost:8085/api/state | head -c 400
```

Set `FSM_PROVE_SCHEDULE=true` to have it drain on a timer instead of on demand.

---

## Tests

```bash
cd pipeline
mvn -B test            # 1658 tests across engine (901), orchestrator (569), runner (188)
```

The **browser tests are deliberately not in that run** — they need the browsers that ship in the
Playwright image. The build prints a line saying so, and:

```bash
orchestrator/playwright/run.sh          # builds the image, runs the UI suite in it
```

There is also a **differential harness**: 23,401 frozen cases, each with the answer a recorded reference
run gave for it, and 745 catalogued divergences from those answers, every one explained. It runs as a
normal JUnit test. `pipeline/runner/harness/README.md` says what the recording is and how to read a
change in it.

---

## Configuration

Everything is environment variables; nothing sensitive is in a yaml file. Copy `.env.example` and fill in:

| variable | what it is |
|---|---|
| `QWEN_BASE_URL` `QWEN_API_KEY` `QWEN_MODEL` | the OpenAI-compatible model endpoint |
| `GITHUB_TOKEN` | clone/read access to the analysed repositories |
| `FSM_DB_PATH` | H2 location. **Must be on a mounted volume** — see below |
| `CACHE` | where the checkouts and build workspaces go. **Must be on a mounted volume**, same argument |
| `FSM_PROVE_SCHEDULE` | `true` to drain on a timer, `false` for REST-only |
| `FSM_PROVE_VERDICT` | `false` skips the verdict *argument* to iterate on prompts cheaply |
| `FSM_FEEDBACK` | `true` records full prompts, replies and critiques for prompt tuning |
| `MAVEN_MIRROR_URL` | which repository Maven resolves the analysed projects from. **Empty means Central**, which is the default and works on a machine with nothing but Docker. Set it to your own Nexus (`https://nexus.example.com/repository/maven-public/`) and it takes effect on the next `up -d` — no rebuild, on an image you did not build |
| `FSM_RUNNER_MODE` | `local` (default) runs the prove in this process; `http` posts it to `FSM_RUNNER_URL` |

---

## Things that cost us time — read this before debugging

**The H2 path must be on a volume.** `FSM_DB_PATH` defaults to `./data/fsm`, which inside a container is
a writable layer thrown away on the next `up -d`. The stack starts, serves, accepts an ingest, runs for
hours and reads zero afterwards, with nothing red at any point. Compose sets it correctly; if you deploy
some other way, set it yourself.

**`QWEN_BASE_URL` must resolve *inside the container*.** Miss it and the three judging stages fail
*closed* — HTTP 200, a downgraded verdict, a green run history, and no error anywhere. `.env.example`
ships it BLANK on purpose: a pre-filled wrong value is worse than an empty one, because it looks
configured. Check it the way that answers the question:

```bash
docker exec fsm curl -s -o /dev/null -w '%{http_code}\n' "$QWEN_BASE_URL"
```

Any status — 200, 401, 404 — means the route exists. Only "refused" or "could not resolve" is broken.
If your model lives in another Compose stack, put this container on that stack's network with
`docker-compose.override.yml` (copy the committed example).

**The compose file declares no external networks, and must not.** An `external: true` network is a name
that exists on one machine; anywhere else `docker compose up -d` dies on its first line — *"network
mvn-cache declared as external, but could not be found"* — after a build that succeeded, with nothing
started. A host's private wiring belongs in `docker-compose.override.yml`, which Compose merges
automatically and which is gitignored.

**The Maven mirror is a runtime setting, and must not be baked into the image.** A `settings.xml` in the
image pinning `mirrorOf=*` is not a cache in front of Central — it is *the only repository Maven will
talk to*, so off the one network that hostname resolves on, every prove fails with hundreds of lines
about unresolvable artifacts and reads as a broken project rather than as an image carrying somebody
else's infrastructure. So: unset means Central; `MAVEN_MIRROR_URL` set means that URL. Use a public
hostname — a compose-internal name only resolves inside one network, which is exactly why it cannot be a
default. (The same variable is *also* a build argument, selecting the mirror the image's own build
resolves through. `docker compose build` cannot join a custom network, so a mirror that lives on one
needs `DOCKER_BUILDKIT=0 docker build --network <net> …`; BuildKit rejects custom network modes.)

**The feedback directory needs the container's uid.** `FSM_FEEDBACK=true` writes as uid 10002; if the
host directory is owned by someone else the service says so loudly on startup and records nothing.

**`infra_error` is not a verdict.** A build that never compiled, a source fetch that returned nothing, or
an unparseable model reply are *pipeline* failures: they retry and never become a judgement about the
code. If you change that, you break the only property that makes the output trustworthy.

---

## What is not done

- The run that produced the reference numbers spanned three deploys — a clean cold *start*, but a
  mixed-version *run*. A single-binary drain would be a stronger claim.
- Two toothless tests are known and unfixed: one assertion that cannot fail, and a dead-anchor check
  that was run by hand but never shipped.
- MAC (coverage × mutation) is 93.9 / 76.2 / 74.1 for engine / orchestrator / runner. Only the engine
  clears 90.

---

## Layout

```
prompts/                 the five prompts — edit these to change behaviour
data/svace/              Svace reports (CSV)
feedback/                recorded prompts, replies and critiques (gitignored)
pipeline/
  pom.xml                the reactor: engine, orchestrator, runner
  Dockerfile             THE image — all three modules, plus git, five JDKs and Maven
  engine/                judgement, as pure functions
  orchestrator/          Spring Batch + dashboard + H2 + the entrypoint
  runner/                clone, patch, build, run tests — a library, not a service
  deploy/docker-compose.yml  the deployment: one service, plus `engine` behind a profile
  deploy/docker-compose.override.yml.example  a host's private wiring, uncommitted
```

Three Maven modules, one image. `runner` stays a module of its own — its 188 tests are the specification
of the one distinction the whole pipeline rests on (did the test RUN and fail, or did it never run?), and
it keeps a zero-third-party-dependency policy that a merge into `orchestrator` would quietly break.

Every volume in `deploy/docker-compose.yml` pins its PHYSICAL name (`name: fsm_fsm-orchestrator-state`),
so no directory name and no Compose project name is what keeps the live data addressable. Keep it that
way: without the pin, Compose derives `<project>_<key>`, and renaming the project or the directory makes
it look for a volume that does not exist, **create it empty**, and start a healthy service serving an
empty backlog — the schema is recreated on the way up, so nothing errors and nothing is red.
