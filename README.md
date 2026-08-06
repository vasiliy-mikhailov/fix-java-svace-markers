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
- A **git token** (`GIT_TOKEN`) with read access to the repositories being analysed — GitHub, GitLab,
  Gitea, or a plain git server. It is only ever used to clone and read; nothing is pushed.

---

## Quickstart

```bash
git clone git@github.com:vasiliy-mikhailov/fix-java-svace-markers.git
cd fix-java-svace-markers/pipeline/deploy

cp .env.example .env         # then fill in QWEN_* and GIT_TOKEN — see "Configuration" below
docker compose up -d --build # one image, one container

curl -s localhost:8085/healthz          # -> ok
```

Ingest a Svace report and start proving. **Send the report in the request** — you do not need access to
any filesystem the container can see:

```bash
curl -sS -X POST localhost:8085/api/ingest \
  -F 'csv=@my-svace-report.csv' \
  -F 'repo=WebGoat/WebGoat' \
  -F 'branch=main' \
  -F 'path_prefix=src/main/java/'

curl -s -X POST localhost:8085/api/prove
```

**Both are safe to re-run.** The ingest *adds*: markers already in the backlog keep their status,
their verdict, their artifact and their attempt count. See
[Re-running the ingest is safe](#re-running-the-ingest-is-safe) below for what it takes to discard
anything.

Three ways to hand over the report, all the same endpoint:

| | when |
|---|---|
| `-F 'csv=@report.csv'` | **the default.** Streamed; never escaped into JSON; bounded at 32 MiB (`FSM_INGEST_MAX_CSV_BYTES`) |
| `"csv_text": "Severity,Checker,…"` in the JSON body | a small report, or one you are pasting into a script |
| `"csvPath": "/data/data/svace/x.csv"` | the report is already on the container's `/data` mount — what the bundled example uses |

An oversized report is **refused with 413**, never truncated: half a report is a backlog silently
missing markers.

### Re-running the ingest is safe

**`POST /api/ingest` adds. It never discards anything unless you ask it to.** Run it again after a
redeploy, after a crash, with the same report, as often as you like:

* a marker already in the backlog **keeps its status, its verdict, its artifact and its attempt
  count** — it is not re-queued and not re-proved;
* a marker the report raises that the backlog does not hold is **queued as new work**;
* a marker in the backlog that the report does *not* raise is **left exactly as it is**, and counted.
  A report is a statement about the markers it contains — `min_severity` and `only_checkers` mean an
  omission is not a claim that a marker is gone.

The reply says which of those you are about to get, before anything happens:

```json
{"started": true, "executionId": 12, "mode": "additive", "discards": 0,
 "backlogBefore": 282, "settledBefore": 268,
 "effect": "markers already in the backlog keep their status, verdict, artifact and attempt count; …"}
```

and `GET /api/ingest/last` says what it actually did, long after the log has rotated:

```bash
curl -s localhost:8085/api/ingest/last
# {"ran":true,"status":"COMPLETED","account":{"mode":"additive","added":14,"kept":268,"absent":3,
#  "absentKeys":[…],"discardedMarkers":0,"written":14}}
```

### …and how to reset, when you really mean it

To throw the backlog away and rebuild it from the report, say so **and say how much you are
destroying**:

```bash
curl -sS -X POST localhost:8085/api/ingest \
  -F 'csv=@my-svace-report.csv' -F 'repo=WebGoat/WebGoat' -F 'branch=main' \
  -F 'reset=true' -F 'reset_confirm=268'
```

`reset_confirm` is the number of **settled** markers being discarded. Get it wrong, or leave it out,
and the request is refused with the right number in the message — so the refusal is also the dry run:

```
a reset would DISCARD the whole backlog — 282 marker(s), 268 of them carrying a verdict, and
240 artifact(s) — and there is no undo. no `reset_confirm` was sent. To go ahead, re-send with
`reset_confirm`: 268. To ADD this report to the backlog instead, which keeps every settled marker
exactly as it is, send it without `reset`.
```

Nothing is required when there is nothing settled to lose. **Comments people wrote survive both
paths** — they live in their own table precisely so no ingest can reach them.

For a deployment whose backlog is disposable — a nightly scan reloaded from scratch — set
`FSM_INGEST_RESET=true` once and every ingest resets with no token. That is a standing decision, kept
in git and announced in the boot log on every start; a request may still override it with
`"reset": false`.

Then open **http://localhost:8085/** — the dashboard shows every marker, its verdict, the test that was
written, the fix diff and the drafted PR body.

> **No git, no build, or no route to Maven Central on the target machine?**
> [DOCKER.md](DOCKER.md) covers two alternatives to building from source: handing over a saved image
> (`docker save` / `docker load`, ~1.8 GB, no build and no source), and a registry. It also has the
> model-endpoint and volume traps in one place.

The report is a four-column CSV: `Severity,Checker,File,Line`.

---

## GitLab, Gitea, or a plain git server

Anything `git clone` can reach. Put the **clone URL** in `repo` and name the branch:

```bash
curl -sS -X POST localhost:8085/api/ingest \
  -F 'csv=@svace-report.csv' \
  -F 'repo=https://gitlab.company.internal/platform/payments-api.git' \
  -F 'branch=main' \
  -F 'path_prefix=/builds/gitlab/platform/payments-api/'

curl -s -X POST localhost:8085/api/prove
```

`GIT_TOKEN` is the credential for that host. It is handed to git through a one-shot credential helper
in the environment — never in the URL, never written into a checkout's `.git/config`, never on a
command line. A `repo` that arrives *with* a credential in it is refused for exactly that reason.

`repo` accepts:

| | means |
|---|---|
| `owner/name` | that repository on `FSM_GIT_HOST` (default `github.com`) |
| `group/sub/project` | a GitLab subgroup path on `FSM_GIT_HOST` |
| `https://gitlab.company/grp/proj.git` | that URL, verbatim |
| `ssh://git@gitlab.company/grp/proj.git`, `git@gitlab.company:grp/proj.git` | that URL, verbatim |
| `gitlab.company/grp/proj` | the same, over https |

If your Guild's server is the normal case, set `FSM_GIT_HOST=gitlab.company.internal` once and keep
typing `group/project`.

**`branch` is required whenever `repo` is not `owner/name`,** and the request is refused without one.
A blank branch means "resolve the repository's default branch per marker", and that lookup is the
GitHub API — for any other host it can only fail, one marker at a time, hours into a run.

**Source is read out of the checkout the prove already makes** (`FSM_SOURCE_MODE=checkout`, the
default), so no host-specific API is involved at all and no API rate limit is spent. `FSM_SOURCE_MODE=github`
keeps the GitHub contents API for a deployment that wants it; it needs an `owner/name` repo and refuses
anything else loudly rather than answering 404s that would be recorded as *"the marker's file was
deleted from the repository"*.

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
functions over maps with no I/O, which is why they can be tested exhaustively and why you can call one
from a unit test without a container.

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
mvn -B test            # all three modules; the run prints its own totals
```

**No test count is written down here, deliberately.** This file used to print one per module, and they
were wrong in both directions at once — one module overstated, two understated — which is probably part
of why they lasted: counts that are uniformly low read as stale, and counts that are wrong both ways
read as measured. A number that changes on every commit that adds a test cannot survive in prose, and a stale one
in the first file a new maintainer reads is worse than no number, because it reads exactly like the
facts around it. `mvn -B test` prints the current totals per module, and `DeploymentTest` fails if a
count comes back into either README.

The **browser tests are deliberately not in that run** — they need the browsers that ship in the
Playwright image. The build prints a line saying so, and:

```bash
orchestrator/playwright/run.sh          # builds the image, runs the UI suite in it
```

There is also a **differential harness**: 23,401 frozen cases, each with the answer a recorded reference
run gave for it, and 833 catalogued divergences from those answers, every one explained. It runs as a
normal JUnit test. `pipeline/runner/harness/README.md` says what the recording is and how to read a
change in it.

---

## Configuration

Everything is environment variables; nothing sensitive is in a yaml file. Copy `.env.example` and fill in:

| variable | what it is |
|---|---|
| `QWEN_BASE_URL` `QWEN_API_KEY` `QWEN_MODEL` | the OpenAI-compatible model endpoint |
| `GIT_TOKEN` | clone/read access to the analysed repositories, on any git host. `GITHUB_TOKEN` is still read when it is unset |
| `FSM_GIT_HOST` | where a bare `owner/name` lives. Default `github.com`; a full clone URL in `repo` overrides it per repository |
| `FSM_SOURCE_MODE` | `checkout` (default) reads a marker's source from the clone the prove makes — any host; `github` uses the GitHub contents API |
| `FSM_INGEST_MAX_CSV_BYTES` | the bound on a report sent in the request. 32 MiB. Refused, never truncated |
| `FSM_PROVE_SCHEDULE` | `true` to drain on a timer, `false` for REST-only |
| `FSM_PROVE_VERDICT` | `false` skips the verdict *argument* to iterate on prompts cheaply |
| `FSM_FEEDBACK` | `true` records full prompts, replies and critiques for prompt tuning |
| `MAVEN_MIRROR_URL` | which repository Maven resolves the analysed projects from. **Empty means Central**, which is the default and works on a machine with nothing but Docker. Set it to your own Nexus (`https://nexus.example.com/repository/maven-public/`) and it takes effect on the next `up -d` — no rebuild, on an image you did not build |
| `FSM_RUNNER_MODE` | `local` (default) runs the prove in this process; `http` posts it to `FSM_RUNNER_URL` |

**This table is the variables an operator sets, not every variable the stack reads.** Two paths it also
reads — `FSM_DB_PATH` for H2 and `CACHE` for the checkouts and build workspaces — are set by
`docker-compose.yml` itself, hardcoded and uninterpolated, pointing inside the named volumes it mounts,
so a value in `.env` is discarded. They had rows here and lines in `.env.example` for a long time; both were knobs that did
nothing. The volume argument is in `DOCKER.md`, "Things that will bite", and the compose file carries it
at each line.

---

## `infra_error` is not a verdict

A build that never compiled, a source fetch that returned nothing, or an unparseable model reply are
*pipeline* failures: they retry and never become a judgement about the code. If you change that, you
break the only property that makes the output trustworthy.

**The operational traps live in [`DOCKER.md`](DOCKER.md), under "Things that will bite".** There used to
be a second list of them here — same H2-on-a-volume paragraph, same `QWEN_BASE_URL` fail-closed
paragraph, same `external:` network, same Maven mirror, same uid 10002 — written out again at full
length. Two copies of an argument are one copy and one thing that will disagree with the compose file
later, and the copy to keep is the one next to the setting it is about. Deleted on 2026-08-06; the
sentence above stayed because it is the one item in that list that was never about deployment.

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

Three Maven modules, one image. `runner` stays a module of its own — its tests are the specification
of the one distinction the whole pipeline rests on (did the test RUN and fail, or did it never run?), and
it keeps a zero-third-party-dependency policy that a merge into `orchestrator` would quietly break.

Every volume in `deploy/docker-compose.yml` pins its PHYSICAL name (`name: fsm_fsm-orchestrator-state`),
so no directory name and no Compose project name is what keeps the live data addressable. Keep it that
way: without the pin, Compose derives `<project>_<key>`, and renaming the project or the directory makes
it look for a volume that does not exist, **create it empty**, and start a healthy service serving an
empty backlog — the schema is recreated on the way up, so nothing errors and nothing is red.
