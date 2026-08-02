# fix-java-svace-markers

Turns a Svace static-analysis report into one of two things per marker:

1. **A proven fix** — a JUnit test that fails red on the unpatched code and goes green with the patch,
   plus a **drafted** pull request. Nothing is ever pushed or opened automatically.
2. **A written verdict** — an argued explanation of why the marker is a false positive, by design, or
   could not be proven, when no failing test can be produced.

Every marker ends in one or the other. On the reference run — WebGoat, 282 markers from a 356-row Svace
report — that was 87 drafted PRs and 282 written verdicts, in about 28 hours unattended.

It is **all Java**. Three containers, no Node, no n8n, no Python.

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

cp .env.example .env      # then fill in QWEN_* and GITHUB_TOKEN — see "Configuration" below
docker compose build      # engine, orchestrator, runner
docker compose up -d

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

The report is a four-column CSV: `Severity,Checker,File,Line`. Put yours under `data/svace/`; the
container reads it from `/data/data/svace/`.

---

## The three services

| service | what it does | port |
|---|---|---|
| **orchestrator** | Spring Batch job that drives each marker through the chain, plus the dashboard and every REST endpoint. Owns the H2 database. | 8085 |
| **runner** | Clones the target repo, writes the test, applies the patch, runs Maven twice (red, then green). Ships JDK 8/11/17/21/25 + Maven. | 8090 |
| **engine** | The judgement, as pure functions. Also runs standalone over HTTP so you can replay one stage by hand. | 8092 |

The orchestrator **embeds** the engine as a library — there is no HTTP hop between the queue and the
judgement. The `engine` service exists so you can exercise a single stage without a full run.

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
mvn -B test            # 1634 tests across engine (901), orchestrator (558), runner (175)
```

The **browser tests are deliberately not in that run** — they need the browsers that ship in the
Playwright image. The build prints a line saying so, and:

```bash
orchestrator/playwright/run.sh          # builds the image, runs the UI suite in it
```

There is also a **differential harness**: 23,401 generated cases comparing the Java against the
JavaScript it replaced, with 745 catalogued divergences, each explained. It runs as a normal JUnit test.

---

## Configuration

Everything is environment variables; nothing sensitive is in a yaml file. Copy `.env.example` and fill in:

| variable | what it is |
|---|---|
| `QWEN_BASE_URL` `QWEN_API_KEY` `QWEN_MODEL` | the OpenAI-compatible model endpoint |
| `GITHUB_TOKEN` | clone/read access to the analysed repositories |
| `FSM_DB_PATH` | H2 location. **Must be on a mounted volume** — see below |
| `FSM_PROVE_SCHEDULE` | `true` to drain on a timer, `false` for REST-only |
| `FSM_PROVE_VERDICT` | `false` skips the verdict *argument* to iterate on prompts cheaply |
| `FSM_FEEDBACK` | `true` records full prompts, replies and critiques for prompt tuning |
| `MAVEN_MIRROR_URL` | optional Nexus mirror for builds |

---

## Things that cost us time — read this before debugging

**The H2 path must be on a volume.** `FSM_DB_PATH` defaults to `./data/fsm`, which inside a container is
a writable layer thrown away on the next `up -d`. The stack starts, serves, accepts an ingest, runs for
hours and reads zero afterwards, with nothing red at any point. Compose sets it correctly; if you deploy
some other way, set it yourself.

**Every service that calls the model must be on the model's network.** Miss it and the three judging
stages fail *closed* — HTTP 200, a downgraded verdict, a green run history, and no error anywhere. Two
tests pin the compose networks for exactly this reason.

**`docker compose build` cannot join a custom network,** so a Maven mirror on one is unreachable at build
time. Use `DOCKER_BUILDKIT=0 docker build --network <net> …`; BuildKit rejects custom network modes.

**The feedback directory needs the container's uid.** `FSM_FEEDBACK=true` writes as uid 10002; if the
host directory is owned by someone else the service says so loudly on startup and records nothing.

**`infra_error` is not a verdict.** A build that never compiled, a source fetch that returned nothing, or
an unparseable model reply are *pipeline* failures: they retry and never become a judgement about the
code. If you change that, you break the only property that makes the output trustworthy.

---

## What is not done

- The run that produced the reference numbers spanned three deploys — a clean cold *start*, but a
  mixed-version *run*. A single-binary drain would be a stronger claim.
- `migrate-to-spring.sh` renames the deployment and has never been executed end to end.
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
  engine/                judgement, as pure functions
  orchestrator/          Spring Batch + dashboard + H2
  runner/                clone, patch, build, run tests
  deploy/docker-compose.yml the deployment
```

This tree was called `n8n-fleet/`, and `deploy/` was called `n8n/`, until the pipeline was ported off
n8n in July 2026. Nothing here runs n8n now — the live stack is three Java services. The rename was
safe to do late because every volume in `deploy/docker-compose.yml` pins its PHYSICAL name (`name:
fsm_fsm-orchestrator-state`), so no directory name has ever been what keeps the live data addressable.
